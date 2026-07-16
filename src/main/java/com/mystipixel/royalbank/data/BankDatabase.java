package com.mystipixel.royalbank.data;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persistence layer over one HikariCP data source serving both SQLite (default, single server) and
 * MySQL (a shared bank DB across a network). The JDBC driver and pool are delivered by Paper's library
 * loader (see plugin.yml {@code libraries}); nothing is shaded.
 *
 * <p>Money-critical writes ({@link #saveAccountWithTransaction}, {@link #saveTransfer}) run inside a
 * single JDBC transaction on one pooled connection, so a balance change and its audit row — or both
 * legs of a transfer — either all commit or all roll back.
 */
public final class BankDatabase {

    public enum Type { SQLITE, MYSQL }

    private static final String INSERT_TRANSACTION_SQL =
            "INSERT INTO bank_transactions (uuid, type, amount, balance_after, created_at, note) VALUES (?, ?, ?, ?, ?, ?)";

    private final JavaPlugin plugin;

    private Type type;
    private HikariDataSource dataSource;
    private File databaseFile; // null when MYSQL

    public BankDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

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
                // SQLite is single-writer: a pool of 1 avoids SQLITE_BUSY entirely.
                hikari.setMaximumPoolSize(1);
                hikari.setConnectionInitSql(
                        "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;");
            }

            this.dataSource = new HikariDataSource(hikari);
            createTables();
            plugin.getLogger().info("RoyalBank connected to " + type + " storage.");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe("RoyalBank storage init failed: " + exception.getMessage());
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

    private void createTables() throws SQLException {
        String banksDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS player_banks (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32) NOT NULL,
                    balance DOUBLE NOT NULL DEFAULT 0,
                    level INT NOT NULL DEFAULT 1,
                    last_interest_claim BIGINT NOT NULL DEFAULT 0,
                    bonus_claimed TINYINT NOT NULL DEFAULT 0
                )
                """ : """
                CREATE TABLE IF NOT EXISTS player_banks (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 1,
                    last_interest_claim INTEGER NOT NULL DEFAULT 0,
                    bonus_claimed INTEGER NOT NULL DEFAULT 0
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
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(banksDdl);
            statement.executeUpdate(transactionsDdl);
            statement.executeUpdate(sharedDdl);
            if (!mysql()) {
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_bank_transactions_uuid_created ON bank_transactions(uuid, created_at DESC)");
            }
        }
        if (!mysql()) {
            migrateSqlite();
        }
    }

    /** Adds columns introduced after the first release to older SQLite databases. (MySQL starts current.) */
    private void migrateSqlite() throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(player_banks)")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        if (!columns.contains("bonus_claimed")) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE player_banks ADD COLUMN bonus_claimed INTEGER NOT NULL DEFAULT 0");
                plugin.getLogger().info("Migrated player_banks: added bonus_claimed column.");
            }
        }
    }

    // ------------------------------------------------------------------ accounts

    public BankAccount getOrCreateAccount(OfflinePlayer player, int startingLevel) {
        Optional<BankAccount> existing = getAccount(player.getUniqueId());
        if (existing.isPresent()) {
            BankAccount account = existing.get();
            String currentName = player.getName() == null ? account.username() : player.getName();
            if (!account.username().equalsIgnoreCase(currentName)) {
                BankAccount renamed = account.withUsername(currentName);
                saveAccount(renamed);
                return renamed;
            }
            return account;
        }

        BankAccount created = new BankAccount(
                player.getUniqueId(),
                player.getName() == null ? "Unknown" : player.getName(),
                0.0, startingLevel, 0L, false);
        saveAccount(created);
        return created;
    }

    public Optional<BankAccount> getAccount(UUID uuid) {
        String sql = "SELECT uuid, username, balance, level, last_interest_claim, bonus_claimed FROM player_banks WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BankAccount(
                        UUID.fromString(resultSet.getString("uuid")),
                        resultSet.getString("username"),
                        resultSet.getDouble("balance"),
                        resultSet.getInt("level"),
                        resultSet.getLong("last_interest_claim"),
                        resultSet.getInt("bonus_claimed") != 0
                ));
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load bank account " + uuid + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    /** Persists an account. Returns false (and logs) on failure so callers can react instead of losing the write. */
    public boolean saveAccount(BankAccount account) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(saveAccountSql())) {
            bindAccount(statement, account);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save bank account " + account.uuid() + ": " + exception.getMessage());
            return false;
        }
    }

    /**
     * Atomically persists the account balance/level change AND its audit transaction in one DB
     * transaction. Either both land or neither does, so the ledger and balance never diverge.
     */
    public boolean saveAccountWithTransaction(BankAccount account, String type, double amount, double balanceAfter, String note) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement accountStatement = connection.prepareStatement(saveAccountSql())) {
                    bindAccount(accountStatement, account);
                    accountStatement.executeUpdate();
                }
                try (PreparedStatement transactionStatement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
                    bindTransaction(transactionStatement, account.uuid(), type, amount, balanceAfter, note);
                    transactionStatement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                plugin.getLogger().severe("Could not save bank account+transaction for " + account.uuid() + ": " + exception.getMessage());
                return false;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Bank DB connection error (account+transaction): " + exception.getMessage());
            return false;
        }
    }

    /**
     * Atomically moves money between two accounts: persists both balances AND both audit rows in one DB
     * transaction, so a transfer can never debit one side without crediting the other.
     */
    public boolean saveTransfer(BankAccount sender, BankAccount recipient, double amount,
                                String senderType, String senderNote,
                                String recipientType, String recipientNote) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement accountStatement = connection.prepareStatement(saveAccountSql())) {
                    bindAccount(accountStatement, sender);
                    accountStatement.executeUpdate();
                    bindAccount(accountStatement, recipient);
                    accountStatement.executeUpdate();
                }
                try (PreparedStatement transactionStatement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
                    bindTransaction(transactionStatement, sender.uuid(), senderType, amount, sender.balance(), senderNote);
                    transactionStatement.executeUpdate();
                    bindTransaction(transactionStatement, recipient.uuid(), recipientType, amount, recipient.balance(), recipientNote);
                    transactionStatement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                plugin.getLogger().severe("Could not save bank transfer " + sender.uuid() + " -> " + recipient.uuid()
                        + ": " + exception.getMessage());
                return false;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Bank DB connection error (transfer): " + exception.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ shared accounts

    /** A shared account's balance, or {@code 0} if it doesn't exist yet. */
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

    /**
     * Atomically upsert a shared account's balance/label AND write its audit row (keyed by the account
     * id in {@code bank_transactions}) in one transaction — so a shared balance and its ledger never
     * diverge, exactly like personal accounts.
     */
    public boolean saveSharedWithTransaction(UUID id, String label, double newBalance,
                                             String type, double amount, String note) {
        String upsert = mysql()
                ? "INSERT INTO shared_banks (id, label, balance) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE label = VALUES(label), balance = VALUES(balance)"
                : "INSERT INTO shared_banks (id, label, balance) VALUES (?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET label = excluded.label, balance = excluded.balance";
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                    statement.setString(1, id.toString());
                    statement.setString(2, label == null ? "" : label);
                    statement.setDouble(3, newBalance);
                    statement.executeUpdate();
                }
                try (PreparedStatement transactionStatement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
                    bindTransaction(transactionStatement, id, type, amount, newBalance, note);
                    transactionStatement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                plugin.getLogger().severe("Could not save shared bank " + id + ": " + exception.getMessage());
                return false;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Bank DB connection error (shared account): " + exception.getMessage());
            return false;
        }
    }

    public boolean addTransaction(UUID uuid, String type, double amount, double balanceAfter, String note) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
            bindTransaction(statement, uuid, type, amount, balanceAfter, note);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save bank transaction for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    private String saveAccountSql() {
        return mysql() ? """
                INSERT INTO player_banks (uuid, username, balance, level, last_interest_claim, bonus_claimed)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    username = VALUES(username),
                    balance = VALUES(balance),
                    level = VALUES(level),
                    last_interest_claim = VALUES(last_interest_claim),
                    bonus_claimed = VALUES(bonus_claimed)
                """ : """
                INSERT INTO player_banks (uuid, username, balance, level, last_interest_claim, bonus_claimed)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    balance = excluded.balance,
                    level = excluded.level,
                    last_interest_claim = excluded.last_interest_claim,
                    bonus_claimed = excluded.bonus_claimed
                """;
    }

    private void bindAccount(PreparedStatement statement, BankAccount account) throws SQLException {
        statement.setString(1, account.uuid().toString());
        statement.setString(2, account.username());
        statement.setDouble(3, account.balance());
        statement.setInt(4, account.level());
        statement.setLong(5, account.lastInterestClaim());
        statement.setInt(6, account.bonusClaimed() ? 1 : 0);
    }

    private void bindTransaction(PreparedStatement statement, UUID uuid, String type, double amount, double balanceAfter, String note) throws SQLException {
        statement.setString(1, uuid.toString());
        statement.setString(2, type);
        statement.setDouble(3, amount);
        statement.setDouble(4, balanceAfter);
        statement.setLong(5, java.time.Instant.now().getEpochSecond());
        statement.setString(6, note == null ? "" : note);
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not roll back failed bank write: " + exception.getMessage());
        }
    }

    private void restoreAutoCommit(Connection connection, boolean value) {
        try {
            connection.setAutoCommit(value);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not restore auto-commit: " + exception.getMessage());
        }
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

    /** Prunes old transactions on a pooled connection. The window function runs on SQLite 3.25+/MySQL 8+. */
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

    /** The SQLite database file, or {@code null} on MySQL (where file-based backups don't apply). */
    public File getDatabaseFile() {
        return databaseFile;
    }

    /**
     * Creates a consistent SQLite backup via VACUUM INTO on a pooled connection. MySQL backups are out of
     * scope (use your DB tooling), so this returns false there.
     */
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
        if (dataSource == null) {
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
        dataSource.close();
        dataSource = null;
    }
}
