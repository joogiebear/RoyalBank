package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.InterestTranche;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

// What each bank action reports to the audit sink (EconGuard when installed), against a real SQLite database.
class BankServiceAuditTest {

    private static final BankLevel LEVEL = new BankLevel(1, "Starter Account", 50_000_000.0, 2.0, 250_000.0,
            0.0, List.of(), List.of(new InterestTranche(0.0, 50_000_000.0, 2.0)));

    record Event(UUID uuid, String action, double amount, boolean incoming, UUID counterparty, String counterpartyName) {
    }

    @TempDir
    Path dataFolder;

    private MockedStatic<Bukkit> bukkit;
    private YamlConfiguration config;
    private BankDatabase database;
    private Economy economy;
    private BankService service;
    private final List<Event> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        MessageManager messages = TestMessages.bundled(dataFolder);
        config = TestMessages.bundledConfig();
        config.set("settings.first-deposit-bonus.enabled", false);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BankServiceAuditTest"));
        database = new BankDatabase(plugin);
        assertTrue(database.connect());
        LevelManager levels = mock(LevelManager.class);
        when(levels.getLevels()).thenReturn(Map.of(1, LEVEL));
        when(levels.getLevel(1)).thenReturn(Optional.of(LEVEL));
        when(levels.getStartingLevel()).thenReturn(1);
        economy = mock(Economy.class);
        when(economy.has(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> new EconomyResponse(i.getArgument(1), 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> new EconomyResponse(i.getArgument(1), 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        VaultHook vault = mock(VaultHook.class);
        when(vault.getEconomy()).thenReturn(economy);
        service = new BankService(plugin, database, levels, vault, messages);
        service.setAuditSink((uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName) ->
                events.add(new Event(uuid, action, amount, incoming, counterparty, counterpartyName)));
    }

    @AfterEach
    void tearDown() {
        database.close();
        bukkit.close();
    }

    private Player player(String name, double bankBalance) {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        assertTrue(database.saveAccount(new BankAccount(uuid, name, bankBalance, 1, 0L, false)));
        return player;
    }

    private void coopBalance(UUID coop, double balance) {
        assertTrue(database.saveAccount(new BankAccount(coop, "Coop", balance, 1, 0L, false)));
    }

    private void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement()) {
            st.executeUpdate(statement);
        }
    }

    @Test
    void ownDepositAndWithdrawalAreNotIncoming() {
        Player steve = player("Steve", 1_000.0);

        assertTrue(service.deposit(steve, 100.0).success());
        assertTrue(service.withdraw(steve, 50.0).success());

        assertEquals(List.of(
                new Event(steve.getUniqueId(), "deposited", 100.0, false, null, null),
                new Event(steve.getUniqueId(), "withdrew", 50.0, false, null, null)), events);
    }

    @Test
    void transferIsIncomingForTheReceiverWithTheSenderAsCounterparty() {
        Player steve = player("Steve", 1_000.0);
        Player alex = player("Alex", 0.0);

        assertTrue(service.transfer(steve, alex, 200.0).success());

        assertEquals(List.of(
                new Event(steve.getUniqueId(), "transfer-sent", 200.0, false, alex.getUniqueId(), "Alex"),
                new Event(alex.getUniqueId(), "transfer-received", 200.0, true, steve.getUniqueId(), "Steve")), events);
    }

    @Test
    void interestIsIncomingWithoutCounterpartyWhetherPaidToBankOrPurse() {
        Player steve = player("Steve", 1_000.0);
        assertTrue(service.claimInterest(steve, true).success());

        config.set("settings.pay-interest-to-bank", false);
        Player alex = player("Alex", 1_000.0);
        assertTrue(service.claimInterest(alex, true).success());

        assertEquals(List.of(
                new Event(steve.getUniqueId(), "interest", 20.0, true, null, null),
                new Event(alex.getUniqueId(), "interest-to-purse", 20.0, true, null, null)), events);
    }

    @Test
    void coopMoneyIsIncomingOnlyWhenAnotherMemberPaidItIn() {
        UUID coop = UUID.randomUUID();
        Player alt = player("Alt", 0.0);
        Player main = player("Main", 0.0);

        coopBalance(coop, 0.0);
        assertNull(service.accountDeposit(alt, coop, "Coop", 300.0));
        assertNull(service.accountDeposit(main, coop, "Coop", 100.0));
        // main takes back its own 100 first, then 150 of the alt's 300
        assertNull(service.accountWithdraw(main, coop, "Coop", 250.0));
        // the alt's remaining 150 is its own money
        assertNull(service.accountWithdraw(alt, coop, "Coop", 150.0));

        assertEquals(List.of(
                new Event(alt.getUniqueId(), "coop-deposited", 300.0, false, null, null),
                new Event(main.getUniqueId(), "coop-deposited", 100.0, false, null, null),
                new Event(main.getUniqueId(), "coop-withdrew", 100.0, false, null, null),
                new Event(main.getUniqueId(), "coop-received", 150.0, true, alt.getUniqueId(), "Alt"),
                new Event(alt.getUniqueId(), "coop-withdrew", 150.0, false, null, null)), events);
    }

    @Test
    void coopMoneyWithNoKnownDepositorIsIncomingWithoutCounterparty() {
        UUID coop = UUID.randomUUID();
        Player main = player("Main", 0.0);
        coopBalance(coop, 500.0);

        assertNull(service.accountWithdraw(main, coop, "Coop", 200.0));

        assertEquals(List.of(new Event(main.getUniqueId(), "coop-withdrew-untracked", 200.0, true, null, null)), events);
    }

    @Test
    void aCoopWithdrawalThatIsNotSavedKeepsItsDepositors() throws Exception {
        UUID coop = UUID.randomUUID();
        Player alt = player("Alt", 0.0);
        Player main = player("Main", 0.0);
        coopBalance(coop, 0.0);
        assertNull(service.accountDeposit(alt, coop, "Coop", 300.0));

        sql("CREATE TRIGGER no_coop_withdraw BEFORE INSERT ON bank_transactions WHEN NEW.type = 'COOP_WITHDRAW'"
                + " BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        assertEquals("&cWithdrawal could not be saved and was reverted; please try again.",
                service.accountWithdraw(main, coop, "Coop", 300.0));
        sql("DROP TRIGGER no_coop_withdraw");
        assertNull(service.accountWithdraw(main, coop, "Coop", 300.0));

        assertEquals(List.of(
                new Event(alt.getUniqueId(), "coop-deposited", 300.0, false, null, null),
                new Event(main.getUniqueId(), "coop-received", 300.0, true, alt.getUniqueId(), "Alt")), events);
    }

    @Test
    void aFailingReportNeverFailsTheBankAction() {
        Player steve = player("Steve", 1_000.0);
        service.setAuditSink((uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName) -> {
            throw new IllegalStateException("audit down");
        });
        UUID coop = UUID.randomUUID();
        coopBalance(coop, 500.0);

        assertTrue(service.deposit(steve, 100.0).success());
        assertTrue(service.withdraw(steve, 100.0).success());
        assertNull(service.accountWithdraw(steve, coop, "Coop", 100.0));
    }
}
