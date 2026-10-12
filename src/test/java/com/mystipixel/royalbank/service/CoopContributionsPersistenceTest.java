package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.InterestTranche;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalbank.data.CoopContribution;
import com.mystipixel.royalbank.hooks.VaultHook;
import com.mystipixel.royalbank.message.MessageManager;
import com.mystipixel.royalbank.message.TestMessages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

// Coop depositors are kept in coop_contributions, so a withdrawal after a restart is still reported
// against the member who paid the money in. Runs against a real SQLite file; a "restart" is a new
// database connection and a new service over the same file.
class CoopContributionsPersistenceTest {

    private static final BankLevel LEVEL = new BankLevel(1, "Starter Account", 50_000_000.0, 2.0, 250_000.0,
            0.0, List.of(), List.of(new InterestTranche(0.0, 50_000_000.0, 2.0)));

    record Event(UUID uuid, String action, double amount, boolean incoming, UUID counterparty) {
    }

    @TempDir
    Path dataFolder;

    private MockedStatic<Bukkit> bukkit;
    private JavaPlugin plugin;
    private MessageManager messages;
    private LevelManager levels;
    private VaultHook vault;
    private BankDatabase database;
    private BankService service;
    private final List<Event> events = new ArrayList<>();
    private final UUID coop = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        messages = TestMessages.bundled(dataFolder);
        YamlConfiguration config = TestMessages.bundledConfig();
        plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CoopContributionsPersistenceTest"));
        levels = mock(LevelManager.class);
        when(levels.getLevels()).thenReturn(Map.of(1, LEVEL));
        when(levels.getLevel(1)).thenReturn(Optional.of(LEVEL));
        when(levels.getStartingLevel()).thenReturn(1);
        Economy economy = mock(Economy.class);
        when(economy.has(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> new EconomyResponse(i.getArgument(1), 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> new EconomyResponse(i.getArgument(1), 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        vault = mock(VaultHook.class);
        when(vault.getEconomy()).thenReturn(economy);
        start();
    }

    @AfterEach
    void tearDown() {
        database.close();
        bukkit.close();
    }

    // what the plugin does on enable
    private void start() {
        database = new BankDatabase(plugin);
        assertTrue(database.connect());
        service = new BankService(plugin, database, levels, vault, messages);
        service.setAuditSink((uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName) -> {
            if (action.startsWith("coop-withdrew") || action.equals("coop-received")) {
                events.add(new Event(uuid, action, amount, incoming, counterparty));
            }
        });
    }

    private void restart() {
        database.close();
        start();
    }

    private List<CoopContribution> contributions() throws Exception {
        List<CoopContribution> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT account_id, member_uuid, member_name, amount, seq FROM coop_contributions ORDER BY account_id, seq")) {
            while (rs.next()) {
                rows.add(new CoopContribution(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getString(3), rs.getDouble(4), rs.getLong(5)));
            }
        }
        return rows;
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    void depositorsSurviveARestart() throws Exception {
        Player alt = player("Alt");
        Player main = player("Main");
        assertNull(service.accountDeposit(alt, coop, "Coop", 300.0));
        assertNull(service.accountDeposit(main, coop, "Coop", 100.0));

        restart();

        assertNull(service.accountWithdraw(main, coop, "Coop", 250.0));
        assertNull(service.accountWithdraw(alt, coop, "Coop", 150.0));

        assertEquals(List.of(
                new Event(main.getUniqueId(), "coop-withdrew", 100.0, false, null),
                new Event(main.getUniqueId(), "coop-received", 150.0, true, alt.getUniqueId()),
                new Event(alt.getUniqueId(), "coop-withdrew", 150.0, false, null)), events);
        assertEquals(List.of(), contributions(), "fully withdrawn depositors leave no rows");
    }

    @Test
    void partialWithdrawalsAndDepositOrderSurviveARestart() {
        Player first = player("First");
        Player second = player("Second");
        Player taker = player("Taker");
        assertNull(service.accountDeposit(first, coop, "Coop", 100.0));
        assertNull(service.accountDeposit(second, coop, "Coop", 100.0));
        assertNull(service.accountWithdraw(taker, coop, "Coop", 60.0));
        assertNull(service.accountDeposit(first, coop, "Coop", 10.0));

        restart();

        assertNull(service.accountWithdraw(taker, coop, "Coop", 150.0));

        assertEquals(List.of(
                new Event(taker.getUniqueId(), "coop-received", 60.0, true, first.getUniqueId()),
                new Event(taker.getUniqueId(), "coop-received", 50.0, true, first.getUniqueId()),
                new Event(taker.getUniqueId(), "coop-received", 100.0, true, second.getUniqueId())), events);
    }

    @Test
    void moneyFromBeforeThisChangeIsStillUntracked() {
        Player main = player("Main");
        assertTrue(database.saveAccount(new com.mystipixel.royalbank.data.BankAccount(coop, "Coop", 500.0, 1, 0L, false)));

        restart();

        assertNull(service.accountWithdraw(main, coop, "Coop", 200.0));
        assertEquals(List.of(new Event(main.getUniqueId(), "coop-withdrew-untracked", 200.0, true, null)), events);
    }

    @Test
    void aFailedDepositorWriteRollsBackTheWholeDeposit() throws Exception {
        Player alt = player("Alt");
        assertNull(service.accountDeposit(alt, coop, "Coop", 300.0));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TRIGGER no_contributions BEFORE UPDATE ON coop_contributions"
                    + " BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        }

        assertNotNull(service.accountDeposit(alt, coop, "Coop", 200.0), "the deposit reports the failure");

        assertEquals(300.0, service.getAccountBalance(coop), "the balance write rolled back with it");
        assertEquals(1, database.getRecentTransactions(coop, 10).size(), "no ledger row for the failed deposit");
        assertEquals(List.of(new CoopContribution(coop, alt.getUniqueId(), "Alt", 300.0, 1L)), contributions());
    }
}
