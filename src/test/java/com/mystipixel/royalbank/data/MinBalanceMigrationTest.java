package com.mystipixel.royalbank.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Existing accounts get min_balance_since_claim on startup, starting at their current balance.
class MinBalanceMigrationTest {

    private static final UUID RICH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID POOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir
    Path dataFolder;

    private BankDatabase open() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "SQLITE");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MinBalanceMigrationTest"));
        BankDatabase database = new BankDatabase(plugin);
        assertTrue(database.connect());
        return database;
    }

    private void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement()) {
            for (String s : statements) {
                st.executeUpdate(s);
            }
        }
    }

    @Test
    void rowsFromBeforeTheColumnStartAtTheirBalance() throws Exception {
        sql("""
                CREATE TABLE player_banks (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 1,
                    last_interest_claim INTEGER NOT NULL DEFAULT 0,
                    bonus_claimed INTEGER NOT NULL DEFAULT 0
                )""",
                "INSERT INTO player_banks VALUES ('" + RICH + "', 'Rich', 2500000.5, 2, 1700000000, 1)",
                "INSERT INTO player_banks VALUES ('" + POOR + "', 'Poor', 0, 1, 0, 0)");

        BankDatabase database = open();
        try {
            BankAccount rich = database.getAccount(RICH).orElseThrow();
            assertEquals(2_500_000.5, rich.minBalanceSinceClaim());
            assertEquals(2_500_000.5, rich.balance());
            assertEquals(1_700_000_000L, rich.lastInterestClaim());
            assertEquals(0.0, database.getAccount(POOR).orElseThrow().minBalanceSinceClaim());
        } finally {
            database.close();
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM player_banks WHERE min_balance_since_claim IS NULL")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "the column is filled in the file, not only on read");
        }
    }

    @Test
    void anOldFileWithoutBonusClaimedMigratesBothColumns() throws Exception {
        sql("""
                CREATE TABLE player_banks (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 1,
                    last_interest_claim INTEGER NOT NULL DEFAULT 0
                )""",
                "INSERT INTO player_banks VALUES ('" + RICH + "', 'Rich', 750, 1, 0)");

        BankDatabase database = open();
        try {
            BankAccount rich = database.getAccount(RICH).orElseThrow();
            assertEquals(750.0, rich.minBalanceSinceClaim());
            assertEquals(false, rich.bonusClaimed());
        } finally {
            database.close();
        }
    }

    @Test
    void aSecondStartLeavesTrackedMinimumsAlone() {
        BankDatabase database = open();
        assertTrue(database.saveAccount(new BankAccount(RICH, "Rich", 1_000.0, 1, 5L, true, 200.0)));
        database.close();

        database = open();
        try {
            assertEquals(200.0, database.getAccount(RICH).orElseThrow().minBalanceSinceClaim());
        } finally {
            database.close();
        }
    }

    @Test
    void aColumnLeftEmptyByAnInterruptedMigrationIsFilledOnTheNextStart() throws Exception {
        open().close();
        sql("INSERT INTO player_banks (uuid, username, balance, level, last_interest_claim, bonus_claimed)"
                + " VALUES ('" + RICH + "', 'Rich', 900, 1, 0, 0)");

        BankDatabase database = open();
        try {
            assertEquals(900.0, database.getAccount(RICH).orElseThrow().minBalanceSinceClaim());
        } finally {
            database.close();
        }
    }
}
