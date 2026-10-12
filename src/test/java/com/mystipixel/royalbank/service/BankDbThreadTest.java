package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.api.BankSnapshot;
import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.InterestTranche;
import com.mystipixel.royalbank.config.ItemRequirement;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.config.RequirementType;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.hooks.VaultHook;
import com.mystipixel.royalbank.message.MessageManager;
import com.mystipixel.royalbank.message.TestMessages;
import com.mystipixel.royalcommon.storage.DbExecutor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Every bank action against a real SQLite database whose connections record the thread that opened them.
// The test thread plays the server's main thread: it starts actions and runs what comes back to the main queue.
class BankDbThreadTest {

    private static final String DB_THREAD = "Bank-Test-DB";
    private static final BankLevel STARTER = new BankLevel(1, "Starter Account", 50_000_000.0, 2.0, 1_000_000.0,
            0.0, List.of(), List.of(new InterestTranche(0.0, 50_000_000.0, 2.0)));
    private static final BankLevel GOLD = new BankLevel(2, "Gold Account", 100_000_000.0, 2.0, 1_000_000.0,
            1_000.0, List.of(new ItemRequirement(RequirementType.VANILLA, Material.GOLD_BLOCK, null, 64)),
            List.of(new InterestTranche(0.0, 100_000_000.0, 2.0)));

    @TempDir
    Path dataFolder;

    private final TestThreads threads = new TestThreads("Bank-Test");
    private final Set<String> connectionThreads = ConcurrentHashMap.newKeySet();
    private final Set<String> vaultThreads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean watching = new AtomicBoolean();
    private final AtomicBoolean databaseDown = new AtomicBoolean();
    private final Map<UUID, Double> purses = new HashMap<>();
    private final Map<UUID, Player> online = new ConcurrentHashMap<>();
    private final List<String> reported = new CopyOnWriteArrayList<>();
    private final AtomicBoolean purseRefuses = new AtomicBoolean();

    private MockedStatic<Bukkit> bukkit;
    private YamlConfiguration config;
    private BankDatabase database;
    private BankService service;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(i -> online.get(i.getArgument(0, UUID.class)));
        bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
        MessageManager messages = TestMessages.bundled(dataFolder);
        config = TestMessages.bundledConfig();
        config.set("settings.first-deposit-bonus.enabled", false);
        config.set("settings.transfer.min-playtime-hours", 0);
        config.set("settings.upgrade-unlock-combined-balance", 0.0);
        config.set("anti-abuse.notify-staff", false);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BankDbThreadTest"));
        database = new BankDatabase(plugin, this::watched);
        assertTrue(database.connect());
        LevelManager levels = mock(LevelManager.class);
        when(levels.getLevels()).thenReturn(Map.of(1, STARTER, 2, GOLD));
        when(levels.getLevel(1)).thenReturn(Optional.of(STARTER));
        when(levels.getLevel(2)).thenReturn(Optional.of(GOLD));
        when(levels.getNextLevel(1)).thenReturn(Optional.of(GOLD));
        when(levels.getNextLevel(2)).thenReturn(Optional.empty());
        when(levels.getStartingLevel()).thenReturn(1);
        Economy economy = economy();
        VaultHook vault = mock(VaultHook.class);
        when(vault.getEconomy()).thenReturn(economy);
        service = new BankService(plugin, database, levels, vault, messages, threads.db);
        service.setAuditSink((uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName) ->
                reported.add(username + " " + action + " " + amount));
    }

    @AfterEach
    void tearDown() {
        threads.shutdown();
        database.close();
        bukkit.close();
    }

    // the pool as the plugin uses it, but every connection notes its thread and fails while the database is "down"
    private DataSource watched(DataSource pool) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        if (watching.get()) {
                            connectionThreads.add(Thread.currentThread().getName());
                        }
                        if (databaseDown.get()) {
                            throw new SQLException("database is down");
                        }
                    }
                    try {
                        return method.invoke(pool, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Economy economy() {
        Economy economy = mock(Economy.class);
        when(economy.has(any(OfflinePlayer.class), anyDouble())).thenAnswer(i -> {
            vaultThreads.add(Thread.currentThread().getName());
            return purse(i.getArgument(0, OfflinePlayer.class)) + 1e-9 >= i.getArgument(1, Double.class);
        });
        when(economy.getBalance(any(OfflinePlayer.class))).thenAnswer(i -> purse(i.getArgument(0, OfflinePlayer.class)));
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(i -> pay(i.getArgument(0, OfflinePlayer.class), -i.getArgument(1, Double.class)));
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(i -> {
            if (purseRefuses.get()) {
                vaultThreads.add(Thread.currentThread().getName());
                return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "purse is full");
            }
            return pay(i.getArgument(0, OfflinePlayer.class), i.getArgument(1, Double.class));
        });
        return economy;
    }

    private synchronized double purse(OfflinePlayer player) {
        return purses.getOrDefault(player.getUniqueId(), 0.0);
    }

    private synchronized EconomyResponse pay(OfflinePlayer player, double delta) {
        vaultThreads.add(Thread.currentThread().getName());
        double now = purse(player) + delta;
        if (now < -1e-9) {
            return new EconomyResponse(0.0, purse(player), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        purses.put(player.getUniqueId(), now);
        return new EconomyResponse(Math.abs(delta), now, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private Player player(String name, double bank, double purse) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(player.getInventory()).thenReturn(inventory);
        online.put(id, player);
        purses.put(id, purse);
        assertTrue(database.saveAccount(new BankAccount(id, name, bank, 1, 0L, true)));
        threads.run(service.loadAccount(player));
        return player;
    }

    // 64 gold blocks while there is true, nothing after
    private ItemStack[] goldBlocks(Player player, AtomicBoolean there) {
        ItemStack gold = mock(ItemStack.class);
        when(gold.getType()).thenReturn(Material.GOLD_BLOCK);
        when(gold.getAmount()).thenReturn(64);
        ItemStack[] contents = {gold};
        when(player.getInventory().getContents()).thenAnswer(i -> there.get() ? contents : new ItemStack[0]);
        return contents;
    }

    private BankAccount stored(Player player) {
        return database.getAccount(player.getUniqueId()).orElseThrow();
    }

    private List<String> ledger(UUID account) {
        return database.getRecentTransactions(account, 20).stream().map(BankTransaction::type).toList().reversed();
    }

    private <T> T run(CompletableFuture<T> action) {
        return threads.run(action);
    }

    private void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
             Statement st = c.createStatement()) {
            st.executeUpdate(statement);
        }
    }

    @Test
    void noBankActionOpensAConnectionOnTheMainThread() throws Exception {
        Player steve = player("Steve", 1_000_000.0, 10_000.0);
        Player alex = player("Alex", 0.0, 0.0);
        UUID coop = UUID.randomUUID();
        watching.set(true);
        String mainThread = Thread.currentThread().getName();

        AsyncPlayerPreLoginEvent login = mock(AsyncPlayerPreLoginEvent.class);
        UUID newcomer = UUID.randomUUID();
        when(login.getUniqueId()).thenReturn(newcomer);
        when(login.getName()).thenReturn("Newcomer");
        when(login.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        CompletableFuture.runAsync(() -> service.onPreLogin(login)).get(10, TimeUnit.SECONDS);
        Player joined = mock(Player.class);
        when(joined.getUniqueId()).thenReturn(newcomer);
        when(joined.getName()).thenReturn("Newcomer");
        when(joined.isOnline()).thenReturn(true);
        online.put(newcomer, joined);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(joined);
        service.onPlayerJoin(join);
        threads.settle();
        assertTrue(service.getCachedAccount(newcomer).isPresent(), "the login loaded the account");

        assertTrue(run(service.deposit(steve, 1_000.0)).success());
        assertTrue(run(service.withdraw(steve, 500.0)).success());
        assertTrue(run(service.transfer(steve, alex, 100.0)).success());
        assertTrue(run(service.claimInterest(steve, true)).success());
        config.set("settings.pay-interest-to-bank", false);
        watching.set(false);
        Player saver = player("Saver", 1_000.0, 0.0);
        watching.set(true);
        assertEquals("interest.paid-to-purse", run(service.claimInterest(saver, true)).key());
        AtomicBoolean gold = new AtomicBoolean(true);
        goldBlocks(steve, gold);
        assertTrue(run(service.upgrade(steve)).success());
        assertTrue(run(service.setBalance(alex, 5.0)).success());
        assertTrue(run(service.addBalance(alex, 5.0)).success());
        assertTrue(run(service.removeBalance(alex, 1.0)).success());
        assertTrue(run(service.setLevel(alex, 2)).success());
        assertTrue(run(service.resetAccount(alex)).success());
        assertEquals("Alex", run(service.adminAccount(alex)).username());
        assertFalse(run(service.getRecentTransactions(steve.getUniqueId(), 10)).isEmpty());
        run(service.pruneTransactions(100));
        assertTrue(run(service.backup(dataFolder.resolve("backup.db"))));
        assertNull(run(service.accountDepositAsync(steve, coop, "Coop", 300.0)));
        assertNull(run(service.accountWithdrawAsync(steve, coop, "Coop", 100.0)));
        assertNull(run(service.accountUpgradeAsync(steve, coop, "Coop")));
        assertNull(service.accountDeposit(steve, coop, "Coop", 10.0));
        assertNull(service.accountWithdraw(steve, coop, "Coop", 10.0));
        assertEquals(200.0, service.getAccountBalance(coop), 1e-9);
        bukkit.when(() -> Bukkit.getOfflinePlayer(alex.getUniqueId())).thenReturn(alex);
        service.importAccount(alex.getUniqueId(), new BankSnapshot(7.0, 1, 0L, true));
        assertEquals(7.0, service.exportAccount(alex.getUniqueId()).balance(), 1e-9);
        assertEquals(5, service.getAccountTransactions(coop, 10).size());
        assertEquals(2, service.getAccountView(coop, "Coop").level());
        assertTrue(service.getUpgradeView(coop, "Coop").maxed());
        threads.settle();

        assertEquals(Set.of(DB_THREAD), connectionThreads, "connections were opened only on the database thread");
        assertEquals(Set.of(mainThread), vaultThreads, "Vault was only used on the main thread");
    }

    @Test
    void aSecondActionWhileOneIsInFlightGetsTheBusyMessage() {
        Player steve = player("Steve", 0.0, 1_000.0);

        CompletableFuture<OperationResult> first = service.deposit(steve, 100.0);
        List<OperationResult> spam = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            spam.add(service.deposit(steve, 100.0).join());
        }
        OperationResult withdrawMeanwhile = service.withdraw(steve, 10.0).join();

        assertTrue(spam.stream().allMatch(result -> "general.busy".equals(result.key()) && !result.success()));
        assertEquals("general.busy", withdrawMeanwhile.key());
        assertTrue(run(first).success());
        assertEquals(900.0, purses.get(steve.getUniqueId()), 1e-9, "charged once");
        assertEquals(100.0, stored(steve).balance(), 1e-9);
        assertTrue(run(service.deposit(steve, 100.0)).success(), "free again once the first finished");
    }

    @Test
    void theBusyMessageShipsWithAPlainDefault() {
        MessageManager messages = TestMessages.bundled(dataFolder);
        assertEquals("&cYour last bank action is still being processed. Please wait a moment.",
                messages.render(OperationResult.fail("general.busy")));
    }

    @Test
    void aFailedActionStillFreesThePlayer() {
        Player steve = player("Steve", 0.0, 1_000.0);
        databaseDown.set(true);
        assertEquals("deposit.refunded", run(service.deposit(steve, 100.0)).key());
        databaseDown.set(false);
        assertTrue(run(service.deposit(steve, 100.0)).success());
    }

    @Test
    void aDepositTheDatabaseCannotSaveIsRefunded() {
        Player steve = player("Steve", 50.0, 1_000.0);
        databaseDown.set(true);

        OperationResult result = run(service.deposit(steve, 300.0));

        databaseDown.set(false);
        assertEquals("deposit.refunded", result.key());
        assertEquals(1_000.0, purses.get(steve.getUniqueId()), 1e-9);
        assertEquals(50.0, stored(steve).balance(), 1e-9);
        assertEquals(List.of(), reported);
    }

    @Test
    void aWithdrawalThePurseRefusesGoesBackIntoTheBank() {
        Player steve = player("Steve", 1_000.0, 0.0);
        purseRefuses.set(true);

        OperationResult result = run(service.withdraw(steve, 400.0));

        assertEquals("withdraw.failed", result.key());
        assertEquals("purse is full", result.placeholders().get("reason"));
        BankAccount after = stored(steve);
        assertEquals(1_000.0, after.balance(), 1e-9);
        assertEquals(1_000.0, after.minBalanceSinceClaim(), 1e-9, "the interest minimum is back too");
        assertEquals(1_000.0, service.getCachedAccount(steve.getUniqueId()).orElseThrow().balance(), 1e-9);
        assertEquals(List.of("WITHDRAW", "WITHDRAW_REVERTED"), ledger(steve.getUniqueId()));
        assertEquals(0.0, purses.get(steve.getUniqueId()), 1e-9);
        assertEquals(List.of(), reported);
    }

    @Test
    void aWithdrawalTheDatabaseCannotSaveLeavesThePurseAlone() {
        Player steve = player("Steve", 1_000.0, 0.0);
        databaseDown.set(true);

        assertEquals("withdraw.reverted", run(service.withdraw(steve, 400.0)).key());

        databaseDown.set(false);
        assertEquals(0.0, purses.get(steve.getUniqueId()), 1e-9);
        assertEquals(1_000.0, stored(steve).balance(), 1e-9);
    }

    @Test
    void interestThePurseRefusesPutsTheClaimBack() {
        config.set("settings.pay-interest-to-bank", false);
        Player steve = player("Steve", 1_000.0, 0.0);
        purseRefuses.set(true);

        OperationResult refused = run(service.claimInterest(steve, true));

        assertEquals("interest.failed", refused.key());
        assertEquals(0L, stored(steve).lastInterestClaim(), "the claim time is back");
        assertEquals(1_000.0, stored(steve).minBalanceSinceClaim(), 1e-9);
        assertEquals(List.of("INTEREST", "INTEREST_REVERTED"), ledger(steve.getUniqueId()));
        assertEquals(List.of(), reported);

        purseRefuses.set(false);
        assertEquals("interest.paid-to-purse", run(service.claimInterest(steve, true)).key(), "the period can still be claimed");
        assertEquals(20.0, purses.get(steve.getUniqueId()), 1e-9);
    }

    @Test
    void anUpgradeTheDatabaseCannotSaveRefundsTheCoinsAndKeepsTheItems() throws Exception {
        Player steve = player("Steve", 0.0, 5_000.0);
        ItemStack[] items = goldBlocks(steve, new AtomicBoolean(true));
        sql("CREATE TRIGGER no_upgrade BEFORE INSERT ON bank_transactions WHEN NEW.type = 'UPGRADE'"
                + " BEGIN SELECT RAISE(ABORT, 'blocked'); END");

        OperationResult result = run(service.upgrade(steve));

        assertEquals("upgrade.refunded", result.key());
        assertEquals(5_000.0, purses.get(steve.getUniqueId()), 1e-9);
        assertEquals(1, stored(steve).level());
        verify(items[0], never()).setAmount(anyInt());
    }

    @Test
    void anUpgradeTakesTheItemsOnlyAfterItIsSaved() {
        Player steve = player("Steve", 0.0, 5_000.0);
        ItemStack[] items = goldBlocks(steve, new AtomicBoolean(true));

        assertEquals("upgrade.success", run(service.upgrade(steve)).key());

        assertEquals(2, stored(steve).level());
        assertEquals(4_000.0, purses.get(steve.getUniqueId()), 1e-9);
        verify(items[0]).setAmount(0);
    }

    @Test
    void itemsThatVanishWhileTheUpgradeIsSavedUndoTheUpgrade() {
        Player steve = player("Steve", 0.0, 5_000.0);
        AtomicBoolean there = new AtomicBoolean(true);
        ItemStack[] items = goldBlocks(steve, there);

        CompletableFuture<OperationResult> upgrade = service.upgrade(steve);
        there.set(false);
        OperationResult result = run(upgrade);

        assertEquals("upgrade.missing-items", result.key());
        assertEquals("64x Gold Block (have 0)", result.placeholders().get("items"));
        assertEquals(1, stored(steve).level());
        assertEquals(1, service.getCachedAccount(steve.getUniqueId()).orElseThrow().level());
        assertEquals(5_000.0, purses.get(steve.getUniqueId()), 1e-9);
        assertEquals(List.of("UPGRADE", "UPGRADE_REVERTED"), ledger(steve.getUniqueId()));
        verify(items[0], never()).setAmount(anyInt());
    }

    @Test
    void aSharedWithdrawalThePurseRefusesGoesBackWithItsDepositors() throws Exception {
        UUID coop = UUID.randomUUID();
        Player alt = player("Alt", 0.0, 1_000.0);
        Player main = player("Main", 0.0, 0.0);
        assertNull(run(service.accountDepositAsync(alt, coop, "Coop", 300.0)));
        purseRefuses.set(true);

        assertEquals("&cWithdraw failed: purse is full", run(service.accountWithdrawAsync(main, coop, "Coop", 300.0)));

        purseRefuses.set(false);
        assertEquals(300.0, service.getAccountBalance(coop), 1e-9);
        assertNull(run(service.accountWithdrawAsync(main, coop, "Coop", 300.0)));
        assertEquals(List.of("Alt coop-deposited 300.0", "Main coop-received 300.0"), reported,
                "the depositor came back, so the second withdrawal is still reported against Alt");
    }

    @Test
    void shutdownFinishesActionsInFlightAndRefusesNewOnes() {
        Player steve = player("Steve", 1_000.0, 500.0);
        Player alex = player("Alex", 1_000.0, 500.0);
        Player sam = player("Sam", 1_000.0, 0.0);
        config.set("settings.pay-interest-to-bank", false);

        CompletableFuture<OperationResult> withdrawal = service.withdraw(steve, 300.0);
        CompletableFuture<OperationResult> deposit = service.deposit(alex, 200.0);
        CompletableFuture<OperationResult> interest = service.claimInterest(sam, true);
        service.close();
        DbExecutor.ShutdownReport report = threads.main.finish(threads.db, Duration.ofSeconds(10));

        assertTrue(report.drained());
        assertTrue(withdrawal.join().success());
        assertTrue(deposit.join().success());
        assertTrue(interest.join().success());
        assertEquals(800.0, purses.get(steve.getUniqueId()), 1e-9, "the withdrawal reached the purse");
        assertEquals(700.0, stored(steve).balance(), 1e-9);
        assertEquals(1_200.0, stored(alex).balance(), 1e-9);
        assertEquals(20.0, purses.get(sam.getUniqueId()), 1e-9);
        assertEquals("general.busy", service.deposit(alex, 1.0).join().key());
    }

    @Test
    void shutdownGivesUpOnWorkThatOutlivesTheTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        threads.db.submit("stuck query", () -> release.await(30, TimeUnit.SECONDS));
        CompletableFuture<Integer> queued = service.pruneTransactions(10);

        DbExecutor.ShutdownReport report = threads.main.finish(threads.db, Duration.ofMillis(200));

        release.countDown();
        assertFalse(report.drained());
        assertTrue(report.leftOver().contains("stuck query"), String.valueOf(report.leftOver()));
        assertTrue(report.leftOver().contains("prune bank transactions"), String.valueOf(report.leftOver()));
        assertTrue(queued.isCompletedExceptionally());
    }
}
