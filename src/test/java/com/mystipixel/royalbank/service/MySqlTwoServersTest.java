package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Runs only when ROYALBANK_TEST_MYSQL_URL is set, e.g. jdbc:mysql://127.0.0.1:3306/ (database part ignored),
// with ROYALBANK_TEST_MYSQL_USER and ROYALBANK_TEST_MYSQL_PASSWORD. Each test gets its own database, dropped after.
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "ROYALBANK_TEST_MYSQL_URL", matches = ".+")
class MySqlTwoServersTest extends TwoServerScenarios {

    private final String database = "rbtest_" + UUID.randomUUID().toString().substring(0, 8);
    private URI server;
    private String user;
    private String password;

    @Override
    void configureStorage(YamlConfiguration config) throws Exception {
        if (server == null) {
            server = URI.create(System.getenv("ROYALBANK_TEST_MYSQL_URL").substring("jdbc:".length()));
            user = System.getenv().getOrDefault("ROYALBANK_TEST_MYSQL_USER", "root");
            password = System.getenv().getOrDefault("ROYALBANK_TEST_MYSQL_PASSWORD", "");
            try (Connection connection = DriverManager.getConnection(rootUrl(), user, password);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE " + database);
            }
        }
        config.set("storage.type", "MYSQL");
        config.set("storage.mysql.host", server.getHost());
        config.set("storage.mysql.port", server.getPort() < 0 ? 3306 : server.getPort());
        config.set("storage.mysql.database", database);
        config.set("storage.mysql.username", user);
        config.set("storage.mysql.password", password);
        config.set("storage.mysql.properties", "useSSL=false&allowPublicKeyRetrieval=true");
        config.set("storage.mysql.pool-size", 4);
    }

    private String rootUrl() {
        int port = server.getPort() < 0 ? 3306 : server.getPort();
        return "jdbc:mysql://" + server.getHost() + ":" + port + "/?useSSL=false&allowPublicKeyRetrieval=true";
    }

    @Override
    Connection rawConnection() throws SQLException {
        int port = server.getPort() < 0 ? 3306 : server.getPort();
        return DriverManager.getConnection("jdbc:mysql://" + server.getHost() + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true", user, password);
    }

    private BankDatabase database(String name) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        configureStorage(config);
        config.set("storage.mysql.database", name);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MySqlTwoServersTest"));
        return new BankDatabase(plugin);
    }

    private void onServer(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(rootUrl(), user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    @Test
    void twoServersStartingTogetherOnAnEmptyDatabaseMigrateOnce() throws Exception {
        String fresh = database + "_start";
        onServer("CREATE DATABASE " + fresh);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<BankDatabase> databases = List.of(database(fresh), database(fresh));
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> started = new ArrayList<>();
            for (BankDatabase db : databases) {
                started.add(threads.submit(() -> {
                    go.await();
                    return db.connect();
                }));
            }
            go.countDown();
            for (Future<Boolean> result : started) {
                assertTrue(result.get(120, TimeUnit.SECONDS));
            }
            databases.forEach(BankDatabase::close);
            try (Connection connection = DriverManager.getConnection(rootUrl(), user, password);
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT version FROM " + fresh + ".royalbank_schema_version WHERE id = 1")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
        } finally {
            threads.shutdownNow();
            onServer("DROP DATABASE IF EXISTS " + fresh);
        }
    }

    @Test
    void tablesFromTheLastReleaseGetTheVersionColumnAndKeepTheirBalances() throws Exception {
        String old = database + "_old";
        UUID rich = UUID.randomUUID();
        onServer("CREATE DATABASE " + old);
        try {
            onServer("CREATE TABLE " + old + ".player_banks (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(32) NOT NULL,"
                    + " balance DOUBLE NOT NULL DEFAULT 0, level INT NOT NULL DEFAULT 1, last_interest_claim BIGINT NOT NULL DEFAULT 0,"
                    + " bonus_claimed TINYINT NOT NULL DEFAULT 0, min_balance_since_claim DOUBLE NULL)");
            onServer("INSERT INTO " + old + ".player_banks VALUES ('" + rich + "', 'Rich', 2500000.5, 2, 1700000000, 1, NULL)");
            BankDatabase db = database(old);
            assertTrue(db.connect());
            try {
                assertEquals(new BankAccount(rich, "Rich", 2_500_000.5, 2, 1_700_000_000L, true, 2_500_000.5),
                        db.getAccount(rich).orElseThrow());
                assertTrue(db.saveAccount(new BankAccount(rich, "Rich", 10.0, 2, 0L, true)));
            } finally {
                db.close();
            }
            try (Connection connection = DriverManager.getConnection(rootUrl(), user, password);
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT version FROM " + old + ".player_banks")) {
                assertTrue(row.next());
                assertEquals(1, row.getLong(1));
            }
        } finally {
            onServer("DROP DATABASE IF EXISTS " + old);
        }
    }

    @Override
    void dropStorage() throws Exception {
        try (Connection connection = DriverManager.getConnection(rootUrl(), user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
        }
    }
}
