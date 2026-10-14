package com.mystipixel.royalbank.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchedQueriesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dataFolder;

    private BankDatabase database;

    @BeforeEach
    void open() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "SQLITE");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BatchedQueriesTest"));
        database = new BankDatabase(plugin);
        assertTrue(database.connect());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement()) {
            for (String s : statements) {
                st.executeUpdate(s);
            }
        }
    }

    private void ledger(UUID account, long createdAt) throws Exception {
        sql("INSERT INTO bank_transactions (uuid, type, amount, balance_after, created_at, note)"
                + " VALUES ('" + account + "', 'DEPOSIT', 1, 1, " + createdAt + ", '')");
    }

    private List<Long> ids(UUID account) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM bank_transactions WHERE uuid = '" + account
                     + "' ORDER BY created_at DESC, id DESC")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    @Test
    void pruneKeepsTheNewestRowsOfEachAccount() throws Exception {
        for (long t = 1; t <= 5; t++) {
            ledger(A, t);
        }
        for (long t = 1; t <= 3; t++) {
            ledger(B, t);
        }
        for (int i = 0; i < 4; i++) {
            ledger(C, 10);                          // same timestamp: the id decides
        }
        List<Long> keepA = ids(A).subList(0, 3);
        List<Long> keepB = ids(B);
        List<Long> keepC = ids(C).subList(0, 3);

        List<String> over = database.accountsOverTransactionLimit(3);
        assertEquals(Set.of(A.toString(), C.toString()), Set.copyOf(over));

        assertEquals(3, database.pruneTransactions(over, 3));
        assertEquals(keepA, ids(A));
        assertEquals(keepB, ids(B));
        assertEquals(keepC, ids(C));
        assertEquals(List.of(), database.accountsOverTransactionLimit(3));
    }

    @Test
    void getAccountsReadsOnlyTheStoredRows() throws Exception {
        sql("INSERT INTO player_banks (uuid, username, balance) VALUES ('" + A + "', 'Alpha', 10)",
                "INSERT INTO player_banks (uuid, username, balance) VALUES ('" + B + "', 'Beta', 20)");

        List<BankAccount> rows = database.getAccounts(List.of(A, B, C));

        assertEquals(Set.of("Alpha", "Beta"), rows.stream().map(BankAccount::username).collect(Collectors.toSet()));
        assertEquals(List.of(), database.getAccounts(List.of()));
    }
}
