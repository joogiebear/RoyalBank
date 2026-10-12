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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// player_banks.version and royalbank_schema_version on SQLite files
class SchemaMigrationTest {

    private static final UUID RICH = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dataFolder;

    private BankDatabase database() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "SQLITE");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchemaMigrationTest"));
        return new BankDatabase(plugin);
    }

    private BankDatabase open() {
        BankDatabase database = database();
        assertTrue(database.connect());
        return database;
    }

    private Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
    }

    private long query(String sql) throws Exception {
        try (Connection c = raw(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getLong(1);
        }
    }

    @Test
    void aBankDbFromTheLastReleaseGetsAVersionAndKeepsItsBalances() throws Exception {
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE player_banks (
                        uuid TEXT PRIMARY KEY,
                        username TEXT NOT NULL,
                        balance REAL NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 1,
                        last_interest_claim INTEGER NOT NULL DEFAULT 0,
                        bonus_claimed INTEGER NOT NULL DEFAULT 0,
                        min_balance_since_claim REAL
                    )""");
            st.executeUpdate("INSERT INTO player_banks VALUES ('" + RICH + "', 'Rich', 2500000.5, 2, 1700000000, 1, 1000)");
        }

        BankDatabase database = open();
        try {
            BankAccount rich = database.getAccount(RICH).orElseThrow();
            assertEquals(new BankAccount(RICH, "Rich", 2_500_000.5, 2, 1_700_000_000L, true, 1000.0), rich);
        } finally {
            database.close();
        }
        assertEquals(1, query("SELECT version FROM royalbank_schema_version WHERE id = 1"));
        assertEquals(0, query("SELECT version FROM player_banks WHERE uuid = '" + RICH + "'"));
    }

    @Test
    void everyWriteBumpsTheVersion() throws Exception {
        BankDatabase database = open();
        try {
            assertTrue(database.saveAccount(new BankAccount(RICH, "Rich", 10.0, 1, 0L, false)));
            BankDatabase.NewAccount key = new BankDatabase.NewAccount(RICH, "Rich", 1, false);
            assertTrue(database.change(key, current -> BankDatabase.Change.write(current.withBalance(20.0), List.of(), 0)).isPresent());
            assertTrue(database.change(key, current -> BankDatabase.Change.none(0)).isPresent(), "a rejected change writes nothing");
            assertEquals(2, query("SELECT version FROM player_banks WHERE uuid = '" + RICH + "'"));

            database.getOrCreateAccount(player(RICH, "RichRenamed"), 1);
            assertEquals(3, query("SELECT version FROM player_banks WHERE uuid = '" + RICH + "'"));
            assertEquals(20.0, database.getAccount(RICH).orElseThrow().balance(), "a rename changes only the name");
        } finally {
            database.close();
        }
    }

    @Test
    void aSecondStartFindsNothingToMigrate() throws Exception {
        open().close();
        BankDatabase database = open();
        database.close();
        assertEquals(1, query("SELECT version FROM royalbank_schema_version WHERE id = 1"));
        assertEquals(1, query("SELECT COUNT(*) FROM royalbank_schema_version"));
    }

    @Test
    void twoServersStartingTogetherBothConnect() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<BankDatabase> databases = List.of(database(), database());
            List<Future<Boolean>> started = databases.stream().map(db -> threads.submit(() -> {
                go.await();
                return db.connect();
            })).toList();
            go.countDown();
            for (Future<Boolean> result : started) {
                assertTrue(result.get(60, TimeUnit.SECONDS));
            }
            databases.forEach(BankDatabase::close);
        } finally {
            threads.shutdownNow();
        }
        assertEquals(1, query("SELECT version FROM royalbank_schema_version WHERE id = 1"));
        assertEquals(0, query("SELECT COUNT(*) FROM player_banks"));
    }

    @Test
    void aDatabaseMigratedByANewerBuildIsRefused() throws Exception {
        open().close();
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE royalbank_schema_version SET version = 99 WHERE id = 1");
        }
        BankDatabase database = database();
        assertFalse(database.connect());
        database.close();
    }

    private static org.bukkit.OfflinePlayer player(UUID id, String name) {
        org.bukkit.OfflinePlayer player = mock(org.bukkit.OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        return player;
    }
}
