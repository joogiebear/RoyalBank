package com.mystipixel.royalbank.data;

import com.mystipixel.royalcommon.storage.Dialect;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.Tx;
import com.mystipixel.royalcommon.storage.TxRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.sql.DataSource;

/**
 * SQLite or MySQL behind one HikariCP pool. Driver and pool come from Paper's library loader
 * (plugin.yml {@code libraries}), nothing is shaded.
 *
 * <p>Every money change is one transaction that locks the account row, re-reads it and lets a rule decide
 * on that fresh row, so a change made by another server on the same database is never overwritten.
 */
public final class BankDatabase {

    public enum Type { SQLITE, MYSQL }

    static final int SCHEMA_VERSION = 1;
    // a deadlock, lock wait timeout or busy database is retried this often before the action fails
    static final int TRANSACTION_ATTEMPTS = 3;
    static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 10;

    private static final double CONTRIBUTION_EPSILON = 1e-6;

    private static final String INSERT_TRANSACTION_SQL =
            "INSERT INTO bank_transactions (uuid, type, amount, balance_after, created_at, note) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_ACCOUNT_SQL =
            "SELECT uuid, username, balance, level, last_interest_claim, bonus_claimed,"
                    + " COALESCE(min_balance_since_claim, balance) AS min_balance, version FROM player_banks WHERE uuid = ?";
    private static final String UPDATE_ACCOUNT_SQL =
            "UPDATE player_banks SET username = ?, balance = ?, level = ?, last_interest_claim = ?, bonus_claimed = ?,"
                    + " min_balance_since_claim = ?, version = version + 1 WHERE uuid = ?";
    private static final String SELECT_VERSION_SQL = "SELECT version FROM player_banks WHERE uuid = ?";

    /** The row to create when an account does not exist yet; rename also updates the stored name. */
    public record NewAccount(UUID uuid, String username, int level, boolean rename) {
    }

    /** A {@code bank_transactions} row. */
    public record Ledger(UUID account, String type, double amount, double balanceAfter, String note) {
    }

    /**
     * What a rule decided after reading the locked rows: the rows to write (none when it turned the action
     * down) and a result for the caller.
     */
    public record Change<R>(List<BankAccount> accounts, List<Ledger> ledger, List<CoopContribution> contributions, R result) {

        public static <R> Change<R> none(R result) {
            return new Change<>(List.of(), List.of(), List.of(), result);
        }

        public static <R> Change<R> write(BankAccount account, List<Ledger> ledger, R result) {
            return new Change<>(List.of(account), ledger, List.of(), result);
        }

        public boolean written() {
            return !accounts.isEmpty();
        }

        public BankAccount account() {
            return accounts.getFirst();
        }
    }

    /** Decides a change from the locked, current row. May run more than once, so it must not touch anything else. */
    @FunctionalInterface
    public interface Rule<R> {
        Change<R> decide(BankAccount current);
    }

    @FunctionalInterface
    public interface PairRule<R> {
        Change<R> decide(BankAccount first, BankAccount second);
    }

    /**
     * depositors: the account's coop_contributions rows in the order they first paid in; nextSeq orders a new
     * depositor after them.
     */
    @FunctionalInterface
    public interface CoopRule<R> {
        Change<R> decide(BankAccount current, List<CoopContribution> depositors, long nextSeq);
    }

    private final JavaPlugin plugin;
    private final UnaryOperator<DataSource> wrap;

    private Type type;
    private HikariDataSource pool;
    // the pool, or what wrap made of it; every query goes through this
    private DataSource dataSource;
    private TxRunner db;
    private File databaseFile; // null when MYSQL

    public BankDatabase(JavaPlugin plugin) {
        this(plugin, UnaryOperator.identity());
    }

    /** wrap sees the pool before anything uses it; tests use it to watch which thread opens connections. */
    public BankDatabase(JavaPlugin plugin, UnaryOperator<DataSource> wrap) {
        this.plugin = plugin;
        this.wrap = wrap;
    }

    public boolean connect() {
        try {
            ConfigurationSection storage = plugin.getConfig().getConfigurationSection("storage");
            if (storage == null) {
                storage = plugin.getConfig().createSection("storage");
            }
            String rawType = storage.getString("type", "SQLITE").toUpperCase(Locale.ROOT);
            this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("RoyalBank");
            int timeoutSeconds = Math.max(1, storage.getInt("connection-timeout-seconds", DEFAULT_CONNECTION_TIMEOUT_SECONDS));
            hikari.setConnectionTimeout(timeoutSeconds * 1000L);

            if (type == Type.MYSQL) {
                ConfigurationSection my = storage.getConfigurationSection("mysql");
                if (my == null) {
                    my = storage.createSection("mysql");
                }
                String host = my.getString("host", "localhost");
                int port = my.getInt("port", 3306);
                String database = my.getString("database", "royalbank");
                String props = my.getString("properties", "useSSL=false");
                loadDriver("com.mysql.cj.jdbc.Driver");
                hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
                hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
                hikari.setUsername(my.getString("username", "root"));
                hikari.setPassword(my.getString("password", ""));
                hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
            } else {
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    plugin.getLogger().severe("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
                    return false;
                }
                databaseFile = new File(dataFolder, storage.getString("sqlite-file", "bank.db"));
                loadDriver("org.sqlite.JDBC");
                hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                hikari.setDriverClassName("org.sqlite.JDBC");
                hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
                // driver properties, not connectionInitSql: sqlite-jdbc runs only the first statement of it
                hikari.setDataSourceProperties(SqliteSettings.properties());
            }

            this.pool = new HikariDataSource(hikari);
            this.dataSource = wrap.apply(pool);
            this.db = new TxRunner(dataSource, TRANSACTION_ATTEMPTS);
            migrate();
            plugin.getLogger().info("RoyalBank connected to " + type + " storage.");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe("RoyalBank storage init failed: " + exception.getMessage());
            if (pool != null) {
                pool.close();
                pool = null;
                dataSource = null;
            }
            return false;
        }
    }

    private void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            plugin.getLogger().log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, exception);
        }
    }

    private boolean mysql() {
        return type == Type.MYSQL;
    }

    public Type type() {
        return type;
    }

    /** The pool, for read-only queries such as the version poll. Null before connect() or after close(). */
    public DataSource dataSource() {
        return dataSource;
    }

    // each step checks before it changes anything: on MySQL a server that dies half way runs it again
    private void migrate() throws SQLException {
        int before = new SchemaVersion(db, "royalbank").ensure(SCHEMA_VERSION, (connection, dialect, from, to) -> {
            if (from < 1) {
                createTables(connection);
                addMissingColumns(connection);
            }
        });
        if (before < SCHEMA_VERSION) {
            plugin.getLogger().info("Database schema migrated from version " + before + " to " + SCHEMA_VERSION + ".");
        }
        // NULL means "not known yet" (a row written without the column) and starts at the current balance
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE player_banks SET min_balance_since_claim = balance WHERE min_balance_since_claim IS NULL");
        }
    }

    private void createTables(Connection connection) throws SQLException {
        String banksDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS player_banks (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32) NOT NULL,
                    balance DOUBLE NOT NULL DEFAULT 0,
                    level INT NOT NULL DEFAULT 1,
                    last_interest_claim BIGINT NOT NULL DEFAULT 0,
                    bonus_claimed TINYINT NOT NULL DEFAULT 0,
                    min_balance_since_claim DOUBLE NULL,
                    version BIGINT NOT NULL DEFAULT 0
                )
                """ : """
                CREATE TABLE IF NOT EXISTS player_banks (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 1,
                    last_interest_claim INTEGER NOT NULL DEFAULT 0,
                    bonus_claimed INTEGER NOT NULL DEFAULT 0,
                    min_balance_since_claim REAL,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """;
        String transactionsDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS bank_transactions (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    amount DOUBLE NOT NULL,
                    balance_after DOUBLE NOT NULL,
                    created_at BIGINT NOT NULL,
                    note VARCHAR(255) NOT NULL DEFAULT '',
                    KEY idx_bank_transactions_uuid_created (uuid, created_at)
                )
                """ : """
                CREATE TABLE IF NOT EXISTS bank_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    balance_after REAL NOT NULL,
                    created_at INTEGER NOT NULL,
                    note TEXT NOT NULL DEFAULT ''
                )
                """;
        String sharedDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS shared_banks (
                    id VARCHAR(36) PRIMARY KEY,
                    label VARCHAR(48) NOT NULL DEFAULT '',
                    balance DOUBLE NOT NULL DEFAULT 0
                )
                """ : """
                CREATE TABLE IF NOT EXISTS shared_banks (
                    id TEXT PRIMARY KEY,
                    label TEXT NOT NULL DEFAULT '',
                    balance REAL NOT NULL DEFAULT 0
                )
                """;
        String contributionsDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS coop_contributions (
                    account_id VARCHAR(36) NOT NULL,
                    member_uuid VARCHAR(36) NOT NULL,
                    member_name VARCHAR(32) NOT NULL DEFAULT '',
                    amount DOUBLE NOT NULL,
                    seq BIGINT NOT NULL,
                    PRIMARY KEY (account_id, member_uuid)
                )
                """ : """
                CREATE TABLE IF NOT EXISTS coop_contributions (
                    account_id TEXT NOT NULL,
                    member_uuid TEXT NOT NULL,
                    member_name TEXT NOT NULL DEFAULT '',
                    amount REAL NOT NULL,
                    seq INTEGER NOT NULL,
                    PRIMARY KEY (account_id, member_uuid)
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(banksDdl);
            statement.executeUpdate(transactionsDdl);
            statement.executeUpdate(sharedDdl);
            statement.executeUpdate(contributionsDdl);
            if (!mysql()) {
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_bank_transactions_uuid_created ON bank_transactions(uuid, created_at DESC)");
            }
        }
    }

    // player_banks files from older builds; MySQL tables were always created with bonus_claimed
    private void addMissingColumns(Connection connection) throws SQLException {
        addColumn(connection, "bonus_claimed", mysql() ? "TINYINT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
        addColumn(connection, "min_balance_since_claim", mysql() ? "DOUBLE NULL" : "REAL");
        addColumn(connection, "version", mysql() ? "BIGINT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumn(Connection connection, String column, String definition) throws SQLException {
        if (hasColumn(connection, "player_banks", column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE player_banks ADD COLUMN " + column + " " + definition);
        }
        plugin.getLogger().info("Migrated player_banks: added " + column + " column.");
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates the account if it is missing and never overwrites one that exists; a changed name only updates
     * the username. On a database failure it logs and returns an unsaved starting account.
     */
    public BankAccount getOrCreateAccount(OfflinePlayer player, int startingLevel) {
        String name = player.getName();
        return getOrCreateAccount(new NewAccount(player.getUniqueId(), name == null ? "Unknown" : name, startingLevel, name != null));
    }

    public BankAccount getOrCreateAccount(NewAccount key) {
        try {
            return db.inTransaction(tx -> lockOrCreate(tx, key));
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load bank account " + key.uuid() + ": " + exception.getMessage());
            return new BankAccount(key.uuid(), key.username(), 0.0, key.level(), 0L, false);
        }
    }

    public Optional<BankAccount> getAccount(UUID uuid) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ACCOUNT_SQL)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load bank account " + uuid + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    /** The stored rows of these accounts in one connection; ids without a row are left out. */
    public List<BankAccount> getAccounts(Collection<UUID> ids) throws SQLException {
        List<BankAccount> accounts = new ArrayList<>(ids.size());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ACCOUNT_SQL)) {
            for (UUID id : ids) {
                statement.setString(1, id.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        accounts.add(readAccount(resultSet));
                    }
                }
            }
        }
        return accounts;
    }

    /** Replaces every field of the account, as a profile swap does. Returns false (and logs) on failure. */
    public boolean saveAccount(BankAccount account) {
        NewAccount key = new NewAccount(account.uuid(), account.username(), account.level(), false);
        return change(key, current -> Change.write(account, List.of(), Boolean.TRUE)).isPresent();
    }

    /**
     * Runs a rule against one locked account row (created first when missing) and writes what it decided,
     * with its ledger rows, in the same transaction. Empty when the database failed; that is logged.
     */
    public <R> Optional<Change<R>> change(NewAccount key, Rule<R> rule) {
        return run(key.uuid().toString(), tx -> {
            return apply(tx, rule.decide(lockOrCreate(tx, key)));
        });
    }

    /** Two accounts, locked in uuid order so two transfers in opposite directions cannot deadlock. */
    public <R> Optional<Change<R>> transfer(NewAccount first, NewAccount second, PairRule<R> rule) {
        return run(first.uuid() + " -> " + second.uuid(), tx -> {
            List<NewAccount> lockOrder = new ArrayList<>(List.of(first, second));
            lockOrder.sort(Comparator.comparing(key -> key.uuid().toString()));
            BankAccount lockedFirst = null;
            BankAccount lockedSecond = null;
            for (NewAccount key : lockOrder) {
                BankAccount locked = lockOrCreate(tx, key);
                if (key == first) {
                    lockedFirst = locked;
                } else {
                    lockedSecond = locked;
                }
            }
            return apply(tx, rule.decide(lockedFirst, lockedSecond));
        });
    }

    /** A shared account with its depositors, read inside the same locked transaction. */
    public <R> Optional<Change<R>> changeCoop(NewAccount key, CoopRule<R> rule) {
        return run(key.uuid().toString(), tx -> {
            BankAccount current = lockOrCreate(tx, key);
            List<CoopContribution> depositors = depositors(tx, key.uuid());
            long nextSeq = depositors.stream().mapToLong(CoopContribution::seq).max().orElse(0L) + 1;
            return apply(tx, rule.decide(current, depositors.stream().filter(d -> d.amount() > CONTRIBUTION_EPSILON).toList(), nextSeq));
        });
    }

    private <R> Optional<Change<R>> run(String what, TxRunner.Work<Change<R>> work) {
        try {
            return Optional.of(db.inTransaction(work));
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save bank change for " + what + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private BankAccount lockOrCreate(Tx tx, NewAccount key) throws SQLException {
        Optional<BankAccount> row = selectForUpdate(tx, key.uuid());
        if (row.isEmpty()) {
            String insert = tx.dialect().insertIgnore() + " INTO player_banks (uuid, username, balance, level,"
                    + " last_interest_claim, bonus_claimed, min_balance_since_claim, version) VALUES (?, ?, 0, ?, 0, 0, 0, 0)";
            try (PreparedStatement statement = tx.connection().prepareStatement(insert)) {
                statement.setString(1, key.uuid().toString());
                statement.setString(2, key.username());
                statement.setInt(3, key.level());
                statement.executeUpdate();
            }
            row = selectForUpdate(tx, key.uuid());
        }
        BankAccount account = row.orElseThrow(() -> new SQLException("Bank account " + key.uuid() + " is missing after insert"));
        if (key.rename() && !account.username().equalsIgnoreCase(key.username())) {
            try (PreparedStatement statement = tx.connection().prepareStatement(
                    "UPDATE player_banks SET username = ?, version = version + 1 WHERE uuid = ?")) {
                statement.setString(1, key.username());
                statement.setString(2, key.uuid().toString());
                statement.executeUpdate();
            }
            account = account.withUsername(key.username()).withVersion(account.version() + 1);
        }
        return account;
    }

    private Optional<BankAccount> selectForUpdate(Tx tx, UUID uuid) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(SELECT_ACCOUNT_SQL + tx.dialect().forUpdate())) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        }
    }

    private static BankAccount readAccount(ResultSet resultSet) throws SQLException {
        return new BankAccount(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("username"),
                resultSet.getDouble("balance"),
                resultSet.getInt("level"),
                resultSet.getLong("last_interest_claim"),
                resultSet.getInt("bonus_claimed") != 0,
                resultSet.getDouble("min_balance"),
                resultSet.getLong("version"));
    }

    // a locking read, so on MySQL it sees the last committed rows rather than the transaction's snapshot
    private List<CoopContribution> depositors(Tx tx, UUID account) throws SQLException {
        String sql = "SELECT account_id, member_uuid, member_name, amount, seq FROM coop_contributions"
                + " WHERE account_id = ? ORDER BY seq" + tx.dialect().forUpdate();
        List<CoopContribution> rows = new ArrayList<>();
        try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
            statement.setString(1, account.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new CoopContribution(
                            UUID.fromString(resultSet.getString("account_id")),
                            UUID.fromString(resultSet.getString("member_uuid")),
                            resultSet.getString("member_name"),
                            resultSet.getDouble("amount"),
                            resultSet.getLong("seq")));
                }
            }
        }
        return rows;
    }

    // returns the change with each written account carrying its new stored version
    private <R> Change<R> apply(Tx tx, Change<R> change) throws SQLException {
        if (!change.written()) {
            return change;
        }
        Connection connection = tx.connection();
        List<BankAccount> stored = new ArrayList<>(change.accounts().size());
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_ACCOUNT_SQL);
             PreparedStatement versionStatement = connection.prepareStatement(SELECT_VERSION_SQL)) {
            for (BankAccount account : change.accounts()) {
                statement.setString(1, account.username());
                statement.setDouble(2, account.balance());
                statement.setInt(3, account.level());
                statement.setLong(4, account.lastInterestClaim());
                statement.setInt(5, account.bonusClaimed() ? 1 : 0);
                statement.setDouble(6, account.minBalanceSinceClaim());
                statement.setString(7, account.uuid().toString());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Bank account " + account.uuid() + " was not locked before writing");
                }
                versionStatement.setString(1, account.uuid().toString());
                try (ResultSet row = versionStatement.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Bank account " + account.uuid() + " is missing after writing");
                    }
                    stored.add(account.withVersion(row.getLong(1)));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
            for (Ledger row : change.ledger()) {
                bindTransaction(statement, row.account(), row.type(), row.amount(), row.balanceAfter(), row.note());
                statement.executeUpdate();
            }
        }
        applyContributionChanges(connection, tx.dialect(), change.contributions());
        return new Change<>(List.copyOf(stored), change.ledger(), change.contributions(), change.result());
    }

    private void applyContributionChanges(Connection connection, Dialect dialect, List<CoopContribution> changes) throws SQLException {
        if (changes.isEmpty()) {
            return;
        }
        String add = dialect == Dialect.MYSQL
                ? "INSERT INTO coop_contributions (account_id, member_uuid, member_name, amount, seq) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), member_name = VALUES(member_name)"
                : "INSERT INTO coop_contributions (account_id, member_uuid, member_name, amount, seq) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(account_id, member_uuid) DO UPDATE SET amount = coop_contributions.amount + excluded.amount, "
                + "member_name = excluded.member_name";
        String subtract = "UPDATE coop_contributions SET amount = amount + ? WHERE account_id = ? AND member_uuid = ?";
        String dropEmpty = "DELETE FROM coop_contributions WHERE account_id = ? AND member_uuid = ? AND amount <= ?";
        try (PreparedStatement addStatement = connection.prepareStatement(add);
             PreparedStatement subtractStatement = connection.prepareStatement(subtract);
             PreparedStatement dropStatement = connection.prepareStatement(dropEmpty)) {
            for (CoopContribution change : changes) {
                String accountId = change.account().toString();
                String member = change.member().toString();
                if (change.amount() > 0.0) {
                    addStatement.setString(1, accountId);
                    addStatement.setString(2, member);
                    addStatement.setString(3, change.memberName() == null ? "" : change.memberName());
                    addStatement.setDouble(4, change.amount());
                    addStatement.setLong(5, change.seq());
                    addStatement.executeUpdate();
                } else {
                    subtractStatement.setDouble(1, change.amount());
                    subtractStatement.setString(2, accountId);
                    subtractStatement.setString(3, member);
                    subtractStatement.executeUpdate();
                    dropStatement.setString(1, accountId);
                    dropStatement.setString(2, member);
                    dropStatement.setDouble(3, CONTRIBUTION_EPSILON);
                    dropStatement.executeUpdate();
                }
            }
        }
    }

    /** Copies legacy {@code shared_banks} rows into {@code player_banks}. Idempotent. */
    public void migrateSharedAccountsToPlayerBanks(int startingLevel) {
        String sql = "INSERT INTO player_banks (uuid, username, balance, level, last_interest_claim, bonus_claimed, min_balance_since_claim) "
                + "SELECT id, label, balance, ?, 0, 0, balance FROM shared_banks "
                + "WHERE id NOT IN (SELECT uuid FROM player_banks)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, startingLevel);
            int moved = statement.executeUpdate();
            if (moved > 0) {
                plugin.getLogger().info("Migrated " + moved + " shared bank account(s) into player_banks.");
            }
        } catch (SQLException exception) {
            // shared_banks does not exist on a fresh install
            plugin.getLogger().fine("No shared_banks migration needed: " + exception.getMessage());
        }
    }

    public double getSharedBalance(UUID id) {
        String sql = "SELECT balance FROM shared_banks WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getDouble("balance") : 0.0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load shared bank " + id + ": " + exception.getMessage());
            return 0.0;
        }
    }

    /** Shared balance and its ledger row in one DB transaction. */
    public boolean saveSharedWithTransaction(UUID id, String label, double newBalance,
                                             String type, double amount, String note) {
        String upsert = mysql()
                ? "INSERT INTO shared_banks (id, label, balance) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE label = VALUES(label), balance = VALUES(balance)"
                : "INSERT INTO shared_banks (id, label, balance) VALUES (?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET label = excluded.label, balance = excluded.balance";
        try {
            return db.inTransaction(tx -> {
                try (PreparedStatement statement = tx.connection().prepareStatement(upsert)) {
                    statement.setString(1, id.toString());
                    statement.setString(2, label == null ? "" : label);
                    statement.setDouble(3, newBalance);
                    statement.executeUpdate();
                }
                try (PreparedStatement transactionStatement = tx.connection().prepareStatement(INSERT_TRANSACTION_SQL)) {
                    bindTransaction(transactionStatement, id, type, amount, newBalance, note);
                    transactionStatement.executeUpdate();
                }
                return true;
            });
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save shared bank " + id + ": " + exception.getMessage());
            return false;
        }
    }

    private void bindTransaction(PreparedStatement statement, UUID uuid, String type, double amount, double balanceAfter, String note) throws SQLException {
        statement.setString(1, uuid.toString());
        statement.setString(2, type);
        statement.setDouble(3, amount);
        statement.setDouble(4, balanceAfter);
        statement.setLong(5, java.time.Instant.now().getEpochSecond());
        statement.setString(6, note == null ? "" : note);
    }

    public List<BankTransaction> getRecentTransactions(UUID uuid, int limit) {
        String sql = "SELECT id, type, amount, balance_after, created_at, note FROM bank_transactions WHERE uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        List<BankTransaction> transactions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    transactions.add(new BankTransaction(
                            resultSet.getLong("id"),
                            resultSet.getString("type"),
                            resultSet.getDouble("amount"),
                            resultSet.getDouble("balance_after"),
                            resultSet.getLong("created_at"),
                            resultSet.getString("note")
                    ));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load recent bank transactions for " + uuid + ": " + exception.getMessage());
        }
        return transactions;
    }

    // the window function needs SQLite 3.25+ or MySQL 8+
    public int pruneTransactions(int maxPerPlayer) {
        if (maxPerPlayer <= 0) {
            return 0;
        }
        String sql = """
                DELETE FROM bank_transactions
                WHERE id NOT IN (
                    SELECT id FROM (
                        SELECT id,
                               ROW_NUMBER() OVER (PARTITION BY uuid ORDER BY created_at DESC, id DESC) AS rn
                        FROM bank_transactions
                    ) ranked
                    WHERE ranked.rn <= ?
                )
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxPerPlayer);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not prune bank transactions: " + exception.getMessage());
            return 0;
        }
    }

    /** {@code null} on MySQL. */
    public File getDatabaseFile() {
        return databaseFile;
    }

    /** SQLite backup via VACUUM INTO. Returns false on MySQL. */
    public boolean backupTo(Path targetPath) {
        if (targetPath == null) {
            return false;
        }
        if (mysql()) {
            plugin.getLogger().warning("'/bank admin backup' is only available on SQLite storage; use your MySQL tooling instead.");
            return false;
        }
        String escapedPath = targetPath.toAbsolutePath().toString().replace("'", "''");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("VACUUM INTO '" + escapedPath + "'");
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create SQLite backup: " + exception.getMessage());
            return false;
        }
    }

    public void close() {
        if (pool == null) {
            return;
        }
        if (!mysql()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // best effort: keep the -wal file from growing
            }
        }
        pool.close();
        pool = null;
        dataSource = null;
    }
}
