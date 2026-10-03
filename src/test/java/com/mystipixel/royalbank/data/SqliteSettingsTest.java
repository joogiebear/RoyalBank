package com.mystipixel.royalbank.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every setting back off a real connection. The old multi-statement {@code connectionInitSql}
 * string looked right and applied only its first PRAGMA: journal mode became WAL, while synchronous,
 * busy_timeout and foreign_keys silently kept the driver's defaults (FULL, 3000 ms, OFF). Nothing
 * ever read them back, which is why it went unnoticed. These assertions are that read-back.
 */
class SqliteSettingsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a pooled connection, configured as BankDatabase configures it, has every pragma")
    void everyPragmaReachesAPooledConnection() throws Exception {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url("pooled.db"));
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
        hikari.setDataSourceProperties(SqliteSettings.properties());

        try (HikariDataSource dataSource = new HikariDataSource(hikari);
             Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            assertAllPragmas(st);
        }
    }

    @Test
    @DisplayName("every new connection gets them, not just the first")
    void everyPragmaIsAppliedToEachNewConnection() throws Exception {
        for (int i = 0; i < 2; i++) {
            try (Connection c = DriverManager.getConnection(url("direct.db"), SqliteSettings.properties());
                 Statement st = c.createStatement()) {
                assertAllPragmas(st);
            }
        }
    }

    @Test
    @DisplayName("foreign_keys is enforced, not merely reported as on")
    void foreignKeysAreEnforced() throws Exception {
        try (Connection c = DriverManager.getConnection(url("fk.db"), SqliteSettings.properties());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE parent (id TEXT PRIMARY KEY)");
            st.executeUpdate("CREATE TABLE child (id TEXT, parent_id TEXT REFERENCES parent(id))");
            SQLException rejected = assertThrows(SQLException.class,
                    () -> st.executeUpdate("INSERT INTO child VALUES ('c', 'missing')"),
                    "a row referencing a missing parent must be rejected");
            assertTrue(rejected.getMessage().toLowerCase().contains("foreign key"),
                    "expected a foreign key violation, got: " + rejected.getMessage());
        }
    }

    /**
     * Pins the driver behaviour this class exists to work around, using the exact string
     * {@link BankDatabase} used to hand to {@code connectionInitSql} and the exact JDBC call
     * HikariCP makes with it ({@code Statement#execute}, which prepares only the first statement —
     * {@code executeUpdate} would have run them all). If a future sqlite-jdbc runs the whole string,
     * this turns red and the choice can be revisited; until then it documents the bug.
     */
    @Test
    @DisplayName("the old multi-statement init string stops after its first pragma")
    void multiStatementInitSqlStopsAfterTheFirstPragma() throws Exception {
        try (Connection c = DriverManager.getConnection(url("legacy.db"));
             Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;"
                    + " PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;");
            assertEquals("wal", pragma(st, "journal_mode").toLowerCase(), "the first pragma applies");
            assertEquals("0", pragma(st, "foreign_keys"), "the last one does not");
        }
    }

    private static void assertAllPragmas(Statement st) throws Exception {
        assertEquals("wal", pragma(st, "journal_mode").toLowerCase());
        assertEquals("1", pragma(st, "synchronous"), "synchronous NORMAL is 1");
        assertEquals(String.valueOf(SqliteSettings.BUSY_TIMEOUT_MS), pragma(st, "busy_timeout"));
        assertEquals("1", pragma(st, "foreign_keys"), "foreign_keys must be ON");
    }

    private String url(String file) {
        return "jdbc:sqlite:" + dir.resolve(file);
    }

    private static String pragma(Statement st, String name) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
