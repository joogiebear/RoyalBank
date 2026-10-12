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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

// Two servers (two BankDatabase pools, two BankServices) on one database. Each server is driven from one
// thread, as its main thread would be; the threaded scenarios run both servers at once.
abstract class TwoServerScenarios {

    static final BankLevel STARTER = new BankLevel(1, "Starter Account", 50_000_000.0, 2.0, 1_000_000.0,
            0.0, List.of(), List.of(new InterestTranche(0.0, 50_000_000.0, 2.0)));
    static final BankLevel GOLD = new BankLevel(2, "Gold Account", 100_000_000.0, 2.0, 1_000_000.0,
            1_000.0, List.of(), List.of(new InterestTranche(0.0, 100_000_000.0, 2.0)));
    static final double EPS = 0.005;

    @TempDir
    Path dataFolder;

    final Wallets wallets = new Wallets();
    final List<Double> interestPaid = new CopyOnWriteArrayList<>();
    final List<String> coopEvents = new CopyOnWriteArrayList<>();
    // every test player counts as online on both servers, so each server keeps its own cache as in game
    final Map<UUID, Player> online = new java.util.concurrent.ConcurrentHashMap<>();
    Server a;
    Server b;
    private MockedStatic<Bukkit> bukkit;
    private ExecutorService threads;

    // storage section for both servers; they must point at the same database
    abstract void configureStorage(YamlConfiguration config) throws Exception;

    abstract Connection rawConnection() throws SQLException;

    void dropStorage() throws Exception {
    }

    @BeforeEach
    void startServers() throws Exception {
        bukkit = onlinePlayers();
        threads = Executors.newFixedThreadPool(2);
        a = new Server("A");
        b = new Server("B");
    }

    @AfterEach
    void stopServers() throws Exception {
        threads.shutdownNow();
        a.database.close();
        b.database.close();
        bukkit.close();
        dropStorage();
    }

    final class Server {
        final BankDatabase database;
        final BankService service;
        final YamlConfiguration config;

        Server(String name) throws Exception {
            MessageManager messages = TestMessages.bundled(dataFolder);
            config = TestMessages.bundledConfig();
            config.set("settings.first-deposit-bonus.enabled", false);
            config.set("settings.transfer.min-playtime-hours", 0);
            config.set("settings.upgrade-unlock-combined-balance", 0.0);
            config.set("transactions.prune-on-startup", false);
            configureStorage(config);
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getLogger("TwoServers-" + name));
            database = new BankDatabase(plugin);
            assertTrue(database.connect(), "server " + name + " connects");
            LevelManager levels = mock(LevelManager.class);
            when(levels.getLevels()).thenReturn(Map.of(1, STARTER, 2, GOLD));
            when(levels.getLevel(1)).thenReturn(Optional.of(STARTER));
            when(levels.getLevel(2)).thenReturn(Optional.of(GOLD));
            when(levels.getNextLevel(1)).thenReturn(Optional.of(GOLD));
            when(levels.getNextLevel(2)).thenReturn(Optional.empty());
            when(levels.getStartingLevel()).thenReturn(1);
            VaultHook vault = mock(VaultHook.class);
            when(vault.getEconomy()).thenReturn(wallets.economy);
            service = new BankService(plugin, database, levels, vault, messages);
            service.setAuditSink((uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName) -> {
                if (action.startsWith("interest")) {
                    interestPaid.add(amount);
                }
                if (action.startsWith("coop-withdrew") || action.equals("coop-received")) {
                    coopEvents.add(username + " " + action + " " + amount + (counterpartyName == null ? "" : " from " + counterpartyName));
                }
            });
        }

        BankAccount stored(UUID id) {
            return database.getAccount(id).orElseThrow();
        }
    }

    // a purse per player, shared by both servers like a network economy
    static final class Wallets {
        private final Map<UUID, Double> purses = new HashMap<>();
        final Economy economy = mock(Economy.class);

        Wallets() {
            when(economy.has(any(OfflinePlayer.class), anyDouble()))
                    .thenAnswer(i -> get(i.getArgument(0, OfflinePlayer.class).getUniqueId()) + EPS >= i.getArgument(1, Double.class));
            when(economy.getBalance(any(OfflinePlayer.class)))
                    .thenAnswer(i -> get(i.getArgument(0, OfflinePlayer.class).getUniqueId()));
            when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble()))
                    .thenAnswer(i -> change(i.getArgument(0, OfflinePlayer.class).getUniqueId(), -i.getArgument(1, Double.class)));
            when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble()))
                    .thenAnswer(i -> change(i.getArgument(0, OfflinePlayer.class).getUniqueId(), i.getArgument(1, Double.class)));
        }

        synchronized double get(UUID id) {
            return purses.getOrDefault(id, 0.0);
        }

        synchronized void set(UUID id, double amount) {
            purses.put(id, amount);
        }

        private synchronized EconomyResponse change(UUID id, double delta) {
            double now = get(id) + delta;
            if (now < -EPS) {
                return new EconomyResponse(0.0, get(id), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            }
            purses.put(id, now);
            return new EconomyResponse(Math.abs(delta), now, EconomyResponse.ResponseType.SUCCESS, null);
        }
    }

    // static mocks belong to the thread that opens them
    private MockedStatic<Bukkit> onlinePlayers() {
        MockedStatic<Bukkit> mocked = mockStatic(Bukkit.class);
        mocked.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(i -> online.get(i.getArgument(0, UUID.class)));
        return mocked;
    }

    Player player(String name) {
        return player(UUID.randomUUID(), name);
    }

    Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(player.getInventory()).thenReturn(inventory);
        online.putIfAbsent(id, player);
        return player;
    }

    Player account(String name, double bank) {
        Player player = player(name);
        assertTrue(a.database.saveAccount(new BankAccount(player.getUniqueId(), name, bank, 1, 0L, true)));
        return player;
    }

    // the two servers run at once, each on its own thread with its own Bukkit stub
    void together(Runnable onA, Runnable onB) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> running = new ArrayList<>();
        for (Runnable work : List.of(onA, onB)) {
            running.add(threads.submit(() -> {
                try (MockedStatic<Bukkit> ignored = onlinePlayers()) {
                    go.await();
                    work.run();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : running) {
            future.get(300, TimeUnit.SECONDS);
        }
    }

    double contributionsSum(UUID account) throws SQLException {
        try (Connection connection = rawConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) FROM coop_contributions WHERE account_id = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getDouble(1);
            }
        }
    }

    long distinctSeqs(UUID account) throws SQLException {
        try (Connection connection = rawConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*), COUNT(DISTINCT seq) FROM coop_contributions WHERE account_id = ?")) {
            statement.setString(1, account.toString());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                assertEquals(row.getLong(1), row.getLong(2), "every depositor of one account has its own seq");
                return row.getLong(2);
            }
        }
    }

    @Test
    void aTransferToAPlayerCachedOnTheOtherServerSurvivesTheirNextDeposit() {
        Player sender = account("Sender", 1_000.0);
        Player steve = account("Steve", 100.0);
        wallets.set(steve.getUniqueId(), 50.0);
        b.service.loadAccount(steve);

        assertTrue(a.service.transfer(sender, steve, 500.0).success());
        assertTrue(b.service.deposit(steve, 10.0).success());

        assertEquals(610.0, a.stored(steve.getUniqueId()).balance(), 1e-9, "the transfer made on A is kept");
        assertEquals(500.0, a.stored(sender.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void anAdminAddOnOneServerSurvivesADepositOnTheOther() {
        Player steve = account("Steve", 100.0);
        wallets.set(steve.getUniqueId(), 50.0);
        b.service.loadAccount(steve);

        assertTrue(a.service.addBalance(steve, 1_000.0).success());
        assertTrue(b.service.deposit(steve, 10.0).success());

        assertEquals(1_110.0, a.stored(steve.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void coopDepositsAndWithdrawalsOnBothServersNeitherLoseNorCreateMoney() throws Exception {
        UUID coop = UUID.randomUUID();
        Player alice = player("Alice");
        Player bob = player("Bob");
        wallets.set(alice.getUniqueId(), 100_000.0);
        wallets.set(bob.getUniqueId(), 100_000.0);
        AtomicInteger failures = new AtomicInteger();

        together(() -> coopTraffic(a, alice, coop, 1, failures), () -> coopTraffic(b, bob, coop, 2, failures));

        double balance = a.service.getAccountBalance(coop);
        double purses = wallets.get(alice.getUniqueId()) + wallets.get(bob.getUniqueId());
        assertEquals(200_000.0, purses + balance, EPS, "money in purses plus the coop bank is unchanged");
        assertEquals(balance, contributionsSum(coop), EPS, "every coin in the bank has a known depositor");
        assertEquals(0, failures.get(), "no action reported a save failure");
        distinctSeqs(coop);
    }

    @Test
    void aCoopWithdrawalOnTheOtherServerIsReportedAgainstTheDepositor() throws Exception {
        UUID coop = UUID.randomUUID();
        Player alice = player("Alice");
        Player bob = player("Bob");
        wallets.set(alice.getUniqueId(), 1_000.0);

        assertNull(a.service.accountDeposit(alice, coop, "Coop", 300.0));
        assertNull(b.service.accountWithdraw(bob, coop, "Coop", 200.0));
        assertNull(a.service.accountDeposit(bob, coop, "Coop", 50.0));
        assertNull(b.service.accountWithdraw(alice, coop, "Coop", 150.0));

        assertEquals(List.of("Bob coop-received 200.0 from Alice", "Alice coop-withdrew 100.0", "Alice coop-received 50.0 from Bob"),
                coopEvents);
        assertEquals(0.0, contributionsSum(coop), EPS);
        assertEquals(0.0, a.service.getAccountBalance(coop), EPS);
    }

    private void coopTraffic(Server server, Player member, UUID coop, long seed, AtomicInteger failures) {
        Random random = new Random(seed);
        for (int i = 0; i < 120; i++) {
            double amount = 10 + random.nextInt(500);
            String error = i % 3 == 2
                    ? server.service.accountWithdraw(member, coop, "Coop", amount)
                    : server.service.accountDeposit(member, coop, "Coop", amount);
            if (error != null && (error.contains("saved") || error.contains("staff"))) {
                failures.incrementAndGet();
            }
        }
    }

    @Test
    void anUpgradeOnOneServerKeepsADepositMadeOnTheOther() {
        Player steve = account("Steve", 100.0);
        wallets.set(steve.getUniqueId(), 10_000.0);
        a.service.loadAccount(steve);

        assertTrue(b.service.deposit(steve, 5_000.0).success());
        assertTrue(a.service.upgrade(steve).success());

        BankAccount stored = a.stored(steve.getUniqueId());
        assertEquals(2, stored.level());
        assertEquals(5_100.0, stored.balance(), 1e-9, "the deposit made on B is kept");
        assertEquals(10_000.0 - 5_000.0 - GOLD.upgradeMoneyCost(), wallets.get(steve.getUniqueId()), 1e-9);
    }

    @Test
    void anUpgradeRacingDepositsLosesNothing() throws Exception {
        Player steve = account("Steve", 100.0);
        wallets.set(steve.getUniqueId(), 100_000.0);
        a.service.loadAccount(steve);
        b.service.loadAccount(steve);

        together(() -> {
            for (int i = 0; i < 20; i++) {
                if (a.service.upgrade(steve).success()) {
                    break;
                }
            }
        }, () -> {
            for (int i = 0; i < 40; i++) {
                assertTrue(b.service.deposit(steve, 100.0).success());
            }
        });

        BankAccount stored = a.stored(steve.getUniqueId());
        assertEquals(2, stored.level());
        assertEquals(100.0 + 4_000.0, stored.balance(), 1e-9);
        assertEquals(100_000.0 - 4_000.0 - GOLD.upgradeMoneyCost(), wallets.get(steve.getUniqueId()), 1e-9);
    }

    @Test
    void interestClaimedOnBothServersIsPaidOnce() {
        Player steve = account("Steve", 1_000_000.0);
        a.service.loadAccount(steve);
        b.service.loadAccount(steve);

        assertTrue(a.service.claimInterest(steve, true).success());
        assertEquals("interest.cooldown", b.service.claimInterest(steve, true).key());

        assertEquals(List.of(20_000.0), interestPaid);
        assertEquals(1_020_000.0, a.stored(steve.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void interestOnOneServerSurvivesATransferOnTheOther() {
        Player steve = account("Steve", 1_000_000.0);
        Player alex = account("Alex", 0.0);
        a.service.loadAccount(steve);
        b.service.loadAccount(steve);

        assertTrue(a.service.claimInterest(steve, true).success());
        assertTrue(b.service.transfer(steve, alex, 1_000_000.0).success());

        assertEquals(20_000.0, a.stored(steve.getUniqueId()).balance(), 1e-9, "the interest is still there");
        assertEquals(1_000_000.0, a.stored(alex.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void interestRacingTransfersIsPaidOnceAndLosesNothing() throws Exception {
        Player steve = account("Steve", 1_000_000.0);
        Player alex = account("Alex", 0.0);
        a.service.loadAccount(steve);
        b.service.loadAccount(steve);

        together(() -> {
            for (int i = 0; i < 20; i++) {
                a.service.claimInterest(steve, true);
            }
        }, () -> {
            for (int i = 0; i < 20; i++) {
                b.service.claimInterest(steve, true);
                assertTrue(b.service.transfer(steve, alex, 1_000.0).success());
            }
        });

        double paid = interestPaid.stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1, interestPaid.size(), "one claim per cooldown, network wide: " + interestPaid);
        assertEquals(1_000_000.0 + paid, a.stored(steve.getUniqueId()).balance() + a.stored(alex.getUniqueId()).balance(), 1e-6);
        assertEquals(20_000.0, a.stored(alex.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void aFirstJoinRacingATransferKeepsTheTransfer() {
        Player sender = account("Sender", 1_000.0);
        UUID newbieId = UUID.randomUUID();
        Player newbieOnA = player(newbieId, "Newbie");
        Player newbieOnB = mock(Player.class);
        when(newbieOnB.getUniqueId()).thenReturn(newbieId);
        when(newbieOnB.isOnline()).thenReturn(true);
        AtomicInteger nameReads = new AtomicInteger();
        // the transfer on A lands while B is creating the account on first join
        when(newbieOnB.getName()).thenAnswer(i -> {
            if (nameReads.getAndIncrement() == 0) {
                assertTrue(a.service.transfer(sender, newbieOnA, 500.0).success());
            }
            return "Newbie";
        });

        b.service.loadAccount(newbieOnB);

        assertEquals(500.0, a.stored(newbieId).balance(), 1e-9, "the transfer is not overwritten by the new account");
        assertEquals(500.0, a.stored(sender.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void firstJoinsRacingTransfersKeepEveryTransfer() throws Exception {
        Player sender = account("Sender", 1_000_000.0);
        List<UUID> newbies = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            newbies.add(UUID.randomUUID());
        }

        together(() -> {
            for (UUID id : newbies) {
                assertTrue(a.service.transfer(sender, player(id, "N" + id.toString().substring(0, 8)), 100.0).success());
            }
        }, () -> {
            for (UUID id : newbies) {
                b.service.loadAccount(player(id, "N" + id.toString().substring(0, 8)));
            }
        });

        for (UUID id : newbies) {
            assertEquals(100.0, a.stored(id).balance(), 1e-9, "transfer to " + id);
        }
        assertEquals(1_000_000.0 - 4_000.0, a.stored(sender.getUniqueId()).balance(), 1e-9);
    }

    @Test
    void depositsAndWithdrawalsOnBothServersAddUp() throws Exception {
        Player steve = account("Steve", 0.0);
        wallets.set(steve.getUniqueId(), 100_000.0);
        a.service.loadAccount(steve);
        b.service.loadAccount(steve);

        together(() -> personalTraffic(a, steve, 7), () -> personalTraffic(b, steve, 8));

        assertEquals(100_000.0, wallets.get(steve.getUniqueId()) + a.stored(steve.getUniqueId()).balance(), EPS);
    }

    private void personalTraffic(Server server, Player player, long seed) {
        Random random = new Random(seed);
        for (int i = 0; i < 80; i++) {
            double amount = 10 + random.nextInt(500);
            if (i % 3 == 2) {
                server.service.withdraw(player, amount);
            } else {
                server.service.deposit(player, amount);
            }
        }
    }
}
