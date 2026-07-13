package com.mystipixel.royalbank.data;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite persistence layer.
 *
 * Threading contract: the shared {@link #connection} is only ever used from the main server
 * thread. Long-running maintenance ({@link #pruneTransactions} / {@link #backupTo}) opens its
 * own short-lived connection so it can run off the main thread without contending with live
 * economy writes. WAL mode + a busy timeout make concurrent connections to the same file safe.
 */
public final class BankDatabase {
    private final JavaPlugin plugin;
    private Connection connection;
    private File databaseFile;
    private String jdbcUrl;

    public BankDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
                return false;
            }
            String fileName = plugin.getConfig().getString("database.file", "bank.db");
            databaseFile = new File(dataFolder, fileName);
            Class.forName("org.sqlite.JDBC");
            jdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
            connection = openConnection();
            createTables();
            return true;
        } catch (SQLException | ClassNotFoundException exception) {
            plugin.getLogger().severe("SQLite connection failed: " + exception.getMessage());
            return false;
        }
    }

    private Connection openConnection() throws SQLException {
        Connection newConnection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = newConnection.createStatement()) {
            // WAL gives better read/write concurrency; NORMAL keeps durability while reducing fsyncs;
            // busy_timeout lets a second connection wait for a lock instead of failing instantly.
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA cache_size=-8000");   // ~8 MB page cache
            statement.execute("PRAGMA mmap_size=67108864");  // 64 MB memory-mapped I/O
            statement.execute("PRAGMA temp_store=MEMORY");
        }
        return newConnection;
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_banks (
                        uuid TEXT PRIMARY KEY,
                        username TEXT NOT NULL,
                        balance REAL NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 1,
                        last_interest_claim INTEGER NOT NULL DEFAULT 0,
                        bonus_claimed INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount REAL NOT NULL,
                        balance_after REAL NOT NULL,
                        created_at INTEGER NOT NULL,
                        note TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bank_transactions_uuid_created ON bank_transactions(uuid, created_at DESC)");
        }
        migrate();
    }

    /** Adds columns introduced after the first release to databases created by older versions. */
    private void migrate() throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(player_banks)")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name").toLowerCase());
            }
        }
        if (!columns.contains("bonus_claimed")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE player_banks ADD COLUMN bonus_claimed INTEGER NOT NULL DEFAULT 0");
                plugin.getLogger().info("Migrated player_banks: added bonus_claimed column.");
            }
        }
    }

    public BankAccount getOrCreateAccount(OfflinePlayer player, int startingLevel) {
        Optional<BankAccount> existing = getAccount(player.getUniqueId());
        if (existing.isPresent()) {
            BankAccount account = existing.get();
            String currentName = player.getName() == null ? account.username() : player.getName();
            // Case-insensitive: avoid a pointless write when only the casing of a cached name differs.
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
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    /**
     * Persists an account. Returns false (and logs) on failure so callers can react
     * (e.g. roll back a Vault transaction) instead of silently losing the write.
     */
    public boolean saveAccount(BankAccount account) {
        try (PreparedStatement statement = connection.prepareStatement(SAVE_ACCOUNT_SQL)) {
            bindAccount(statement, account);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save bank account " + account.uuid() + ": " + exception.getMessage());
            return false;
        }
    }

    /**
     * Atomically persists the account balance/level change AND its audit transaction in a single
     * SQLite transaction. Either both land or neither does, so the ledger and the balance never diverge.
     * Returns false on failure (with rollback) so the caller can compensate.
     */
    public boolean saveAccountWithTransaction(BankAccount account, String type, double amount, double balanceAfter, String note) {
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement accountStatement = connection.prepareStatement(SAVE_ACCOUNT_SQL)) {
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
            plugin.getLogger().severe("Could not save bank account+transaction for " + account.uuid() + ": " + exception.getMessage());
            rollbackQuietly();
            return false;
        } finally {
            restoreAutoCommit(previousAutoCommit);
        }
    }

    public boolean addTransaction(UUID uuid, String type, double amount, double balanceAfter, String note) {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
            bindTransaction(statement, uuid, type, amount, balanceAfter, note);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save bank transaction for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    private static final String SAVE_ACCOUNT_SQL = """
            INSERT INTO player_banks (uuid, username, balance, level, last_interest_claim, bonus_claimed)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                balance = excluded.balance,
                level = excluded.level,
                last_interest_claim = excluded.last_interest_claim,
                bonus_claimed = excluded.bonus_claimed
            """;

    private static final String INSERT_TRANSACTION_SQL =
            "INSERT INTO bank_transactions (uuid, type, amount, balance_after, created_at, note) VALUES (?, ?, ?, ?, ?, ?)";

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

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            plugin.getLogger().severe("Could not roll back failed bank write: " + rollbackException.getMessage());
        }
    }

    private void restoreAutoCommit(boolean value) {
        try {
            connection.setAutoCommit(value);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not restore auto-commit: " + exception.getMessage());
        }
    }

    public List<BankTransaction> getRecentTransactions(UUID uuid, int limit) {
        String sql = "SELECT id, type, amount, balance_after, created_at, note FROM bank_transactions WHERE uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        List<BankTransaction> transactions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    /**
     * Prunes old transactions on a dedicated short-lived connection so the full-table scan never
     * blocks the main thread. Safe to call from an async task.
     */
    public int pruneTransactions(int maxPerPlayer) {
        if (maxPerPlayer <= 0) {
            return 0;
        }
        String sql = """
                DELETE FROM bank_transactions
                WHERE id NOT IN (
                    SELECT id FROM (
                        SELECT id,
                               ROW_NUMBER() OVER (PARTITION BY uuid ORDER BY created_at DESC, id DESC) AS row_number
                        FROM bank_transactions
                    ) ranked
                    WHERE ranked.row_number <= ?
                )
                """;
        try (Connection pruneConnection = openConnection();
             PreparedStatement statement = pruneConnection.prepareStatement(sql)) {
            statement.setInt(1, maxPerPlayer);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not prune bank transactions: " + exception.getMessage());
            return 0;
        }
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    /**
     * Creates a consistent backup via VACUUM INTO on a dedicated short-lived connection,
     * so it does not stall the main thread or contend with the live connection. Async-safe.
     */
    public boolean backupTo(Path targetPath) {
        if (targetPath == null) {
            return false;
        }
        String escapedPath = targetPath.toAbsolutePath().toString().replace("'", "''");
        try (Connection backupConnection = openConnection();
             Statement statement = backupConnection.createStatement()) {
            statement.executeUpdate("VACUUM INTO '" + escapedPath + "'");
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create SQLite backup: " + exception.getMessage());
            return false;
        }
    }

    public void close() {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ignored) {
            // best effort: keep the -wal file from growing
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not close SQLite database: " + exception.getMessage());
        }
    }
}
