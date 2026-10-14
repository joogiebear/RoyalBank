package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.InterestTranche;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.api.BankSnapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

// Interest is paid on the lowest balance held since the last claim, against a real SQLite database.
// The cooldown is 0 so a test can claim as often as it likes; only the minimum limits what is paid.
class BankServiceInterestTest {

    // 2% on everything, capped well above anything these tests pay
    private static final BankLevel LEVEL = new BankLevel(1, "Starter Account", 50_000_000.0, 2.0, 1_000_000.0,
            0.0, List.of(), List.of(new InterestTranche(0.0, 50_000_000.0, 2.0)));

    @TempDir
    Path dataFolder;

    private MockedStatic<Bukkit> bukkit;
    private YamlConfiguration config;
    private BankDatabase database;
    private final TestThreads threads = new TestThreads("Bank-Test");
    private BankService service;
    private final List<Double> interestPaid = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        MessageManager messages = TestMessages.bundled(dataFolder);
        config = TestMessages.bundledConfig();
        config.set("settings.first-deposit-bonus.enabled", false);
        config.set("settings.interest-cooldown-hours", 0);
        config.set("settings.transfer.min-playtime-hours", 0);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BankServiceInterestTest"));
        database = new BankDatabase(plugin);
        assertTrue(database.connect());
        LevelManager levels = mock(LevelManager.class);
        when(levels.getLevels()).thenReturn(Map.of(1, LEVEL));
        when(levels.getLevel(1)).thenReturn(Optional.of(LEVEL));
        when(levels.getStartingLevel()).thenReturn(1);
        Economy economy = mock(Economy.class);
        when(economy.has(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> new EconomyResponse(i.getArgument(1), 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> new EconomyResponse(i.getArgument(1), 0.0, EconomyResponse.ResponseType.SUCCESS, null));
        VaultHook vault = mock(VaultHook.class);
        when(vault.getEconomy()).thenReturn(economy);
        service = new BankService(plugin, database, levels, vault, messages, threads.db);
        service.setAuditSink((uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName) -> {
            if (action.startsWith("interest")) {
                interestPaid.add(amount);
            }
        });
    }

    @AfterEach
    void tearDown() {
        threads.shutdown();
        database.close();
        bukkit.close();
    }

    private Player player(String name, double balance) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        assertTrue(database.saveAccount(new BankAccount(player.getUniqueId(), name, balance, 1, 0L, true)));
        return player;
    }

    private BankAccount stored(Player player) {
        return database.getAccount(player.getUniqueId()).orElseThrow();
    }

    private double totalInterest() {
        return interestPaid.stream().mapToDouble(Double::doubleValue).sum();
    }

    private <T> T run(java.util.concurrent.CompletableFuture<T> action) {
        return threads.run(action);
    }

    @Test
    void oneBankrollPassedAroundAltsEarnsOnce() {
        Player main = player("Main", 1_000_000.0);
        Player alt1 = player("Alt1", 0.0);
        Player alt2 = player("Alt2", 0.0);

        assertTrue(run(service.claimInterest(main, true)).success());
        double pile = stored(main).balance();
        assertTrue(run(service.transfer(main, alt1, pile)).success());
        assertTrue(run(service.claimInterest(alt1, true)).success());
        assertTrue(run(service.transfer(alt1, alt2, pile)).success());
        assertTrue(run(service.claimInterest(alt2, true)).success());
        assertTrue(run(service.transfer(alt2, main, pile)).success());
        assertTrue(run(service.claimInterest(main, true)).success());

        assertEquals(List.of(20_000.0), interestPaid);
        assertEquals(1_020_000.0, stored(main).balance());
    }

    @Test
    void depositJustBeforeAClaimEarnsOnlyOnTheOldMinimum() {
        Player steve = player("Steve", 1_000_000.0);

        assertTrue(run(service.deposit(steve, 9_000_000.0)).success());
        assertTrue(run(service.claimInterest(steve, true)).success());
        assertTrue(run(service.withdraw(steve, 9_000_000.0)).success());

        assertEquals(List.of(20_000.0), interestPaid);
        assertEquals(1_020_000.0, stored(steve).balance());
    }

    @Test
    void depositedMoneyEarnsFromTheClaimAfterItArrived() {
        Player steve = player("Steve", 1_000_000.0);
        assertTrue(run(service.claimInterest(steve, true)).success());
        assertTrue(run(service.deposit(steve, 980_000.0)).success());

        assertTrue(run(service.claimInterest(steve, true)).success());
        assertTrue(run(service.claimInterest(steve, true)).success());

        assertEquals(List.of(20_000.0, 20_400.0, 40_408.0), interestPaid);
    }

    @Test
    void aSteadySaverEarnsExactlyAsBefore() {
        Player steve = player("Steve", 1_000_000.0);
        double expectedBalance = 1_000_000.0;
        List<Double> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double interest = Math.round(service.calculateInterest(expectedBalance, LEVEL) * 100.0) / 100.0;
            expected.add(interest);
            expectedBalance += interest;
            assertTrue(run(service.claimInterest(steve, true)).success());
        }
        assertEquals(expected, interestPaid);
        assertEquals(Math.round(expectedBalance * 100.0) / 100.0, stored(steve).balance());

        config.set("settings.pay-interest-to-bank", false);
        interestPaid.clear();
        Player alex = player("Alex", 1_000_000.0);
        assertTrue(run(service.claimInterest(alex, true)).success());
        assertTrue(run(service.claimInterest(alex, true)).success());
        assertEquals(List.of(20_000.0, 20_000.0), interestPaid);
    }

    @Test
    void moneyArrivingInAnEmptyAccountStartsAPeriodInsteadOfEarningAtOnce() {
        Player steve = player("Steve", 0.0);
        assertTrue(run(service.deposit(steve, 1_000_000.0)).success());

        OperationResult first = run(service.claimInterest(steve, true));
        assertEquals("interest.period-started", first.key());
        assertTrue(stored(steve).lastInterestClaim() > 0, "the claim used up the cooldown");
        assertEquals(1_000_000.0, stored(steve).minBalanceSinceClaim());

        assertTrue(run(service.claimInterest(steve, true)).success());
        assertEquals(List.of(20_000.0), interestPaid);
    }

    @Test
    void anEmptyBankStillKeepsItsCooldown() {
        Player steve = player("Steve", 0.0);

        assertEquals("interest.nothing-earned", run(service.claimInterest(steve, true)).key());
        assertEquals(0L, stored(steve).lastInterestClaim());
    }

    @Test
    void withdrawalsLowerTheMinimumAndDepositsDoNotRaiseIt() {
        Player steve = player("Steve", 1_000_000.0);

        assertTrue(run(service.withdraw(steve, 600_000.0)).success());
        assertTrue(run(service.deposit(steve, 600_000.0)).success());

        assertEquals(1_000_000.0, stored(steve).balance());
        assertEquals(400_000.0, stored(steve).minBalanceSinceClaim());
        assertTrue(run(service.claimInterest(steve, true)).success());
        assertEquals(List.of(8_000.0), interestPaid);
        assertEquals(1_008_000.0, stored(steve).minBalanceSinceClaim(), "a claim resets it to the balance after the claim");
    }

    @Test
    void coopWithdrawalsLowerTheMinimumToo() {
        UUID coop = UUID.randomUUID();
        Player steve = player("Steve", 0.0);
        assertTrue(database.saveAccount(new BankAccount(coop, "Coop", 1_000_000.0, 1, 0L, false)));

        assertEquals(null, service.accountWithdraw(steve, coop, "Coop", 700_000.0));
        assertEquals(null, service.accountDeposit(steve, coop, "Coop", 700_000.0));

        BankAccount account = database.getAccount(coop).orElseThrow();
        assertEquals(1_000_000.0, account.balance());
        assertEquals(300_000.0, account.minBalanceSinceClaim());
    }

    @Test
    void profileSwitchesResetTheMinimumAlongsideTheCooldown() {
        Player steve = player("Steve", 1_000_000.0);
        assertTrue(run(service.withdraw(steve, 1_000_000.0)).success());
        assertEquals(0.0, stored(steve).minBalanceSinceClaim());
        bukkit.when(() -> Bukkit.getOfflinePlayer(steve.getUniqueId())).thenReturn(steve);

        service.importAccount(steve.getUniqueId(), new BankSnapshot(5_000_000.0, 1, 123L, true));
        assertEquals(5_000_000.0, stored(steve).minBalanceSinceClaim());
        assertEquals(123L, stored(steve).lastInterestClaim());

        service.resetAccount(steve.getUniqueId());
        assertEquals(0.0, stored(steve).minBalanceSinceClaim());
        assertEquals(0L, stored(steve).lastInterestClaim());
    }

    @Test
    void theInfoLinesShowWhatTheNextClaimPays() {
        Player steve = player("Steve", 1_000_000.0);
        assertTrue(run(service.withdraw(steve, 500_000.0)).success());
        assertTrue(run(service.deposit(steve, 500_000.0)).success());
        // the lines are drawn from the cache, which the login fills
        when(steve.isOnline()).thenReturn(true);
        run(service.loadAccount(steve));

        assertTrue(service.describeBalance(steve).stream().anyMatch(line -> line.contains("$10,000")),
                String.valueOf(service.describeBalance(steve)));
    }
}
