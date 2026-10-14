package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.ItemRequirement;
import com.mystipixel.royalbank.config.InterestTranche;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.api.AccountView;
import com.mystipixel.royalbank.api.BankSnapshot;
import com.mystipixel.royalbank.api.RoyalBankAPI;
import com.mystipixel.royalbank.api.TransactionView;
import com.mystipixel.royalbank.api.UpgradeView;
import com.mystipixel.royalbank.config.RequirementType;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.data.CoopContribution;
import com.mystipixel.royalbank.hooks.VaultHook;
import com.mystipixel.royalbank.message.MessageManager;
import com.mystipixel.royalbank.security.AbuseMonitor;
import com.mystipixel.royalbank.util.Amounts;
import com.mystipixel.royalbank.util.Text;
import com.mystipixel.royalcommon.storage.BusyGuard;
import com.mystipixel.royalcommon.storage.DbExecutor;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Every bank action runs its database transaction on the plugin's database thread and its Vault, cache, chat
 * and audit steps on the main thread. Actions return a future completed on the main thread. Money always
 * leaves its old home before it reaches the new one, and the first step is reversed when the second fails.
 */
public final class BankService implements Listener, RoyalBankAPI {
    private static final long DEFAULT_INTEREST_COOLDOWN_HOURS = 31L;
    // how long a login waits for its account before joining without it (it is then loaded after the join)
    private static final long LOGIN_LOAD_TIMEOUT_SECONDS = 10L;
    // a login that never turned into a join (kicked, banned) leaves its preloaded row this long at most
    private static final long PRELOAD_KEEP_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final JavaPlugin plugin;
    private final BankDatabase database;
    private final LevelManager levelManager;
    private final VaultHook vaultHook;
    private final AbuseMonitor abuseMonitor;
    private final MessageManager messages;
    private final DbExecutor db;
    private AuditSink auditSink;

    // online players only, for display; every change writes the DB first, then refreshes this from the committed row
    private final AccountCache cache = new AccountCache();
    // one action at a time per player (the purse for shared accounts)
    private final BusyGuard<UUID> busy = new BusyGuard<>();
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private record Preloaded(BankAccount account, long loadedAt) {
    }

    public BankService(JavaPlugin plugin, BankDatabase database, LevelManager levelManager, VaultHook vaultHook,
                       MessageManager messages, DbExecutor db) {
        this.plugin = plugin;
        this.database = database;
        this.levelManager = levelManager;
        this.vaultHook = vaultHook;
        this.messages = messages;
        this.db = db;
        this.abuseMonitor = new AbuseMonitor(plugin, messages);
        this.auditSink = new LocalAuditSink(abuseMonitor);
    }

    public AccountCache accountCache() {
        return cache;
    }

    public AbuseMonitor getAbuseMonitor() {
        return abuseMonitor;
    }

    public void setAuditSink(AuditSink auditSink) {
        this.auditSink = auditSink;
    }

    /** True when EconGuard handles anti-abuse instead of the built-in monitor. */
    public boolean usesExternalAudit() {
        return !(auditSink instanceof LocalAuditSink);
    }

    /** Refuses new actions (they get the busy message); what is already running finishes. For onDisable. */
    public void close() {
        closed = true;
    }

    // work runs on the database thread; stages chained on the result run on the main thread
    private <T> CompletableFuture<T> onDb(String label, Callable<T> work) {
        return db.submit(label, work).whenCompleteAsync((result, failure) -> { }, db.mainThread());
    }

    // for the synchronous API: waits for the database thread, then the chained stages run on the caller's thread
    private <T> CompletableFuture<T> onDbBlocking(String label, Callable<T> work) {
        if (db.isDbThread()) {
            try {
                return CompletableFuture.completedFuture(work.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        try {
            return CompletableFuture.completedFuture(db.submit(label, work).join());
        } catch (CompletionException | CancellationException e) {
            return CompletableFuture.failedFuture(e.getCause() != null ? e.getCause() : e);
        }
    }

    private <T> CompletableFuture<T> onDb(boolean blocking, String label, Callable<T> work) {
        return blocking ? onDbBlocking(label, work) : onDb(label, work);
    }

    // a transaction whose failure (already logged) counts as "not saved", so the caller reverses its first step
    private <R> CompletableFuture<Optional<BankDatabase.Change<R>>> save(boolean blocking, String label,
                                                                         Callable<Optional<BankDatabase.Change<R>>> work) {
        return onDb(blocking, label, work).handle((saved, failure) -> failure == null ? saved : Optional.empty());
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private CompletableFuture<OperationResult> guarded(UUID key, String action, Supplier<CompletableFuture<OperationResult>> work) {
        if (closed || !busy.tryEnter(key)) {
            return done(OperationResult.fail("general.busy"));
        }
        CompletableFuture<OperationResult> running;
        try {
            running = work.get();
        } catch (RuntimeException e) {
            busy.exit(key);
            throw e;
        }
        return running.whenComplete((result, failure) -> {
            busy.exit(key);
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Bank " + action + " for " + key + " did not finish", failure);
            }
        });
    }

    // the synchronous API's answer when the action itself broke; the cause is already logged
    private static OperationResult resultOf(CompletableFuture<OperationResult> future, String key) {
        return future.handle((result, failure) -> failure == null ? result : OperationResult.fail(key)).join();
    }

    // login thread: the account is read before the player joins, so the join itself does no database work
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (closed || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        BankDatabase.NewAccount key = new BankDatabase.NewAccount(event.getUniqueId(), event.getName(),
                levelManager.getStartingLevel(), true);
        try {
            BankAccount account = db.submit("load bank account at login " + key.uuid(), () -> database.getOrCreateAccount(key))
                    .get(LOGIN_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            preloaded.put(key.uuid(), new Preloaded(account, System.nanoTime()));
        } catch (TimeoutException | ExecutionException e) {
            plugin.getLogger().warning("Could not load the bank account of " + key.username()
                    + " during login; it is loaded after the join instead.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Preloaded loaded = preloaded.remove(player.getUniqueId());
        long now = System.nanoTime();
        preloaded.values().removeIf(entry -> now - entry.loadedAt() > PRELOAD_KEEP_NANOS);
        if (loaded != null) {
            cache.put(loaded.account());
            afterJoinLoad(player);
        } else {
            loadAccount(player).whenComplete((account, failure) -> afterJoinLoad(player));
        }
        notifyPendingFlags(player);
    }

    private void afterJoinLoad(Player player) {
        if (!player.isOnline() || !plugin.getConfig().getBoolean("settings.interest-on-join", true)) {
            return;
        }
        // only a payout is announced at join (silent interest reads as a bug), delayed past join messages
        claimInterest(player, false).thenAccept(interest -> {
            if (interest.success() && interest.key() != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Text.color(
                                plugin.getConfig().getString("settings.messages-prefix", "")
                                        + messages.render(interest)));
                    }
                }, 40L);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        unloadAccount(event.getPlayer().getUniqueId());
        abuseMonitor.clearVelocity(event.getPlayer().getUniqueId());
    }

    private void notifyPendingFlags(Player player) {
        if (!plugin.getConfig().getBoolean("anti-abuse.notify-staff", true)) {
            return;
        }
        // next tick: after join messages and once permissions have settled
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.hasPermission("royalbank.alerts") && abuseMonitor.hasFlags()) {
                player.sendMessage(Text.color(messages.get("alerts.pending-flags",
                        "count", String.valueOf(abuseMonitor.getFlags().size()))));
            }
        });
    }

    /** Reads (or creates) the player's row on the database thread and caches it on the main thread if they are still online. */
    public CompletableFuture<BankAccount> loadAccount(Player player) {
        BankDatabase.NewAccount key = personal(player);
        // online check stops a load racing a quit from resurrecting an evicted entry
        return onDb("load bank account " + key.uuid(), () -> database.getOrCreateAccount(key))
                .thenApply(row -> player.isOnline() ? cache.put(row) : row);
    }

    public void unloadAccount(UUID uuid) {
        cache.remove(uuid);
        preloaded.remove(uuid);
    }

    /** Runs then on the main thread once the player's account is cached; loads it first when it is not. */
    public void ensureLoaded(Player player, Runnable then) {
        if (cache.get(player.getUniqueId()).isPresent()) {
            then.run();
            return;
        }
        loadAccount(player).whenComplete((account, failure) -> {
            if (player.isOnline()) {
                then.run();
            }
        });
    }

    // the cached row, for display; money decisions re-read the locked row inside their transaction
    private CompletableFuture<BankAccount> accountFor(Player player) {
        Optional<BankAccount> cached = cache.get(player.getUniqueId());
        return cached.isPresent() ? done(cached.get()) : loadAccount(player);
    }

    /**
     * For display only, never reads the database: the cached row, or an empty starting account while it is
     * still loading.
     */
    public BankAccount getAccount(Player player) {
        return cache.get(player.getUniqueId()).orElseGet(() -> new BankAccount(player.getUniqueId(),
                player.getName(), 0.0, levelManager.getStartingLevel(), 0L, false));
    }

    /** Cache only, no database fallback. Safe from any thread. */
    public Optional<BankAccount> getCachedAccount(UUID uuid) {
        return cache.get(uuid);
    }

    private void cacheIfOnline(BankAccount account) {
        if (Bukkit.getPlayer(account.uuid()) != null) {
            cache.put(account);
        }
    }

    private BankDatabase.NewAccount personal(OfflinePlayer player) {
        String name = player.getName();
        return new BankDatabase.NewAccount(player.getUniqueId(), name == null ? "Unknown" : name,
                levelManager.getStartingLevel(), name != null);
    }

    private BankDatabase.NewAccount shared(UUID accountId, String label) {
        return new BankDatabase.NewAccount(accountId, label == null ? "Account" : label, levelManager.getStartingLevel(), false);
    }

    private static BankDatabase.Ledger ledger(BankAccount account, String type, double amount, double balanceAfter, String note) {
        return new BankDatabase.Ledger(account.uuid(), type, amount, balanceAfter, note);
    }

    // what a locked rule decided: the message, and the balances before and after for the audit report
    private record Outcome(OperationResult result, double before, double after, double extra) {
        static Outcome rejected(String key, String... placeholders) {
            return new Outcome(OperationResult.fail(key, placeholders), 0.0, 0.0, 0.0);
        }

        static Outcome of(OperationResult result) {
            return new Outcome(result, 0.0, 0.0, 0.0);
        }
    }

    // a withdrawal saved before its purse payment: what putting it back needs
    private record Taken(OperationResult result, double before, double after, double minBefore, long claimBefore) {
        static Taken rejected(String key) {
            return new Taken(OperationResult.fail(key), 0.0, 0.0, 0.0, 0L);
        }
    }

    private boolean compensateDeposit(Player player, double amount, String operation) {
        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().severe("RoyalBank: FAILED to refund " + money(amount) + " to " + player.getName()
                    + " (" + player.getUniqueId() + ") after a failed " + operation + " save. MANUAL RECONCILIATION NEEDED. Vault: " + response.errorMessage);
            return false;
        }
        return true;
    }

    // money taken from the bank that could not reach the purse goes back, and so does the interest minimum it lowered
    private BankDatabase.Change<Boolean> returnToBank(BankAccount current, double amount, double minBefore, long claimBefore,
                                                      String type, String note) {
        double newBalance = round(current.balance() + amount);
        BankAccount restored = current.withBalance(newBalance);
        if (current.lastInterestClaim() == claimBefore) {
            restored = restored.withMinBalanceSinceClaim(Math.min(minBefore, current.minBalanceSinceClaim() + amount));
        }
        return BankDatabase.Change.write(restored, List.of(ledger(current, type, amount, newBalance, note)), Boolean.TRUE);
    }

    private void logLostPayout(Player player, double amount, String operation) {
        plugin.getLogger().severe("RoyalBank: FAILED to put back " + money(amount) + " for " + player.getName()
                + " (" + player.getUniqueId() + ") after the purse payment of a " + operation
                + " failed. MANUAL RECONCILIATION NEEDED.");
    }

    public CompletableFuture<OperationResult> deposit(Player player, double amount) {
        return guarded(player.getUniqueId(), "deposit", () -> {
            double sanitized = Amounts.sanitize(plugin, amount);
            double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
            if (sanitized < minimum) {
                return done(OperationResult.fail("amount.too-small", "minimum", money(minimum)));
            }
            return accountFor(player).thenCompose(account -> depositFrom(player, account, sanitized));
        });
    }

    private CompletableFuture<OperationResult> depositFrom(Player player, BankAccount account, double amount) {
        BankLevel level = getEffectiveLevel(account.level());
        double availableSpace = level.maxBalance() - account.balance();
        if (availableSpace <= 0) {
            return done(OperationResult.fail("deposit.bank-full"));
        }

        // both the bank credit and the wallet debit come from the rounded post-deposit balance, so they
        // match to the unit
        double principalBalance = round(Math.min(account.balance() + Math.min(amount, availableSpace), level.maxBalance()));
        double charge = round(principalBalance - account.balance());
        if (charge <= 0.0) {
            return done(OperationResult.fail("deposit.bank-full"));
        }

        if (!vaultHook.getEconomy().has(player, charge)) {
            return done(OperationResult.fail("deposit.not-enough-money"));
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, charge);
        if (!response.transactionSuccess()) {
            return done(OperationResult.fail("deposit.failed", "reason", String.valueOf(response.errorMessage)));
        }

        BankDatabase.NewAccount key = personal(player);
        return this.<Outcome>save(false, "deposit for " + key.username(), () -> database.change(key, current -> {
            BankLevel currentLevel = getEffectiveLevel(current.level());
            double principal = round(current.balance() + charge);
            if (principal > currentLevel.maxBalance()) {
                return BankDatabase.Change.none(Outcome.rejected("deposit.bank-full"));
            }
            boolean bonusEligible = !current.bonusClaimed() && plugin.getConfig().getBoolean("settings.first-deposit-bonus.enabled", true);
            double bonusAmount = bonusEligible ? Math.max(0.0, plugin.getConfig().getDouble("settings.first-deposit-bonus.amount", 10.0)) : 0.0;
            double newBalance = round(Math.min(principal + bonusAmount, currentLevel.maxBalance()));
            double paidBonus = round(Math.max(0.0, newBalance - principal));
            BankAccount updated = current.withBalance(newBalance);
            List<BankDatabase.Ledger> rows = new ArrayList<>();
            rows.add(ledger(current, "DEPOSIT", charge, principal, messages.get("notes.deposit")));
            if (paidBonus > 0.0) {
                updated = updated.withBonusClaimed(true);
                rows.add(ledger(current, "BONUS", paidBonus, newBalance, messages.get("notes.bonus")));
            }
            OperationResult result = paidBonus > 0.0
                    ? OperationResult.success("deposit.success-bonus", "amount", money(charge), "bonus", money(paidBonus))
                    : OperationResult.success("deposit.success", "amount", money(charge));
            return BankDatabase.Change.write(updated, rows, new Outcome(result, current.balance(), principal, paidBonus));
        })).thenApply(saved -> {
            if (saved.isEmpty() || !saved.get().written()) {
                boolean refunded = compensateDeposit(player, charge, "deposit");
                if (!refunded) {
                    return OperationResult.fail("deposit.needs-staff");
                }
                return saved.isEmpty() ? OperationResult.fail("deposit.refunded") : saved.get().result().result();
            }
            Outcome outcome = saved.get().result();
            cacheIfOnline(saved.get().account());
            // principal and bonus as separate audit events, so each balance-after stays consistent.
            // The principal is the player's own purse money, not new wealth, so it is not incoming.
            report(player.getUniqueId(), player.getName(), "deposited", charge, outcome.before(), outcome.after(), false, null, null);
            if (outcome.extra() > 0.0) {
                report(player.getUniqueId(), player.getName(), "bonus", outcome.extra(), outcome.after(),
                        saved.get().account().balance(), true, null, null);
            }
            return outcome.result();
        });
    }

    public CompletableFuture<OperationResult> withdraw(Player player, double amount) {
        return guarded(player.getUniqueId(), "withdrawal", () -> withdrawFlow(player, amount));
    }

    private CompletableFuture<OperationResult> withdrawFlow(Player player, double amount) {
        double withdrawn = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (withdrawn < minimum) {
            return done(OperationResult.fail("amount.too-small", "minimum", money(minimum)));
        }

        BankDatabase.NewAccount key = personal(player);
        return this.<Taken>save(false, "withdrawal for " + key.username(), () -> database.change(key, current -> {
            if (current.balance() < withdrawn) {
                return BankDatabase.Change.none(Taken.rejected("withdraw.not-enough-money"));
            }
            double newBalance = round(current.balance() - withdrawn);
            return BankDatabase.Change.write(current.withBalance(newBalance),
                    List.of(ledger(current, "WITHDRAW", withdrawn, newBalance, messages.get("notes.withdraw"))),
                    new Taken(OperationResult.success("withdraw.success", "amount", money(withdrawn)), current.balance(), newBalance,
                            current.minBalanceSinceClaim(), current.lastInterestClaim()));
        })).thenCompose(saved -> {
            if (saved.isEmpty()) {
                return done(OperationResult.fail("withdraw.reverted"));
            }
            if (!saved.get().written()) {
                return done(saved.get().result().result());
            }
            Taken taken = saved.get().result();
            cacheIfOnline(saved.get().account());
            EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, withdrawn);
            if (response.transactionSuccess()) {
                report(player.getUniqueId(), player.getName(), "withdrew", withdrawn, taken.before(), taken.after(), false, null, null);
                return done(taken.result());
            }
            OperationResult failed = OperationResult.fail("withdraw.failed", "reason", String.valueOf(response.errorMessage));
            return this.<Boolean>save(false, "put back withdrawal for " + key.username(), () -> database.change(key, current ->
                    returnToBank(current, withdrawn, taken.minBefore(), taken.claimBefore(), "WITHDRAW_REVERTED",
                            messages.get("notes.withdraw-reverted")))).thenApply(back -> {
                if (back.isPresent() && back.get().written()) {
                    cacheIfOnline(back.get().account());
                    return failed;
                }
                logLostPayout(player, withdrawn, "withdrawal");
                return OperationResult.fail("withdraw.needs-staff");
            });
        });
    }

    /** Bank to bank, no Vault. Rejected outright if the full amount won't fit under the recipient's cap. */
    public CompletableFuture<OperationResult> transfer(Player sender, OfflinePlayer recipient, double amount) {
        return guarded(sender.getUniqueId(), "transfer", () -> transferFlow(sender, recipient, amount));
    }

    private CompletableFuture<OperationResult> transferFlow(Player sender, OfflinePlayer recipient, double amount) {
        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            return done(OperationResult.fail("transfer.self"));
        }
        // same playtime gate as RoyalTrade, or alt-account RMT just moves from trades to bank transfers
        double minHours = plugin.getConfig().getDouble("settings.transfer.min-playtime-hours", 0.0);
        if (minHours > 0) {
            String required = String.format(Locale.US, "%.0f", minHours);
            if (playtimeHours(sender) < minHours) {
                return done(OperationResult.fail("transfer.sender-playtime", "hours", required));
            }
            if (playtimeHours(recipient) < minHours) {
                String name = recipient.getName() != null ? recipient.getName() : messages.get("transfer.unknown-recipient");
                return done(OperationResult.fail("transfer.recipient-playtime", "player", name, "hours", required));
            }
        }
        double sent = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (sent < minimum) {
            return done(OperationResult.fail("amount.too-small", "minimum", money(minimum)));
        }

        String senderName = sender.getName();
        UUID senderId = sender.getUniqueId();
        UUID recipientId = recipient.getUniqueId();
        BankDatabase.NewAccount from = personal(sender);
        BankDatabase.NewAccount to = personal(recipient);
        return this.<Outcome>save(false, "transfer " + from.uuid() + " -> " + to.uuid(), () -> database.transfer(from, to, (payer, payee) -> {
            if (payer.balance() < sent) {
                return BankDatabase.Change.none(Outcome.rejected("transfer.not-enough-money"));
            }
            BankLevel recipientLevel = getEffectiveLevel(payee.level());
            double recipientSpace = round(recipientLevel.maxBalance() - payee.balance());
            if (sent > recipientSpace) {
                return BankDatabase.Change.none(Outcome.rejected("transfer.recipient-full", "player", payee.username(),
                        "space", money(Math.max(0.0, recipientSpace))));
            }
            double senderNew = round(payer.balance() - sent);
            double recipientNew = round(payee.balance() + sent);
            List<BankDatabase.Ledger> rows = List.of(
                    ledger(payer, "TRANSFER_OUT", sent, senderNew, messages.get("notes.transfer-out", "player", payee.username())),
                    ledger(payee, "TRANSFER_IN", sent, recipientNew, messages.get("notes.transfer-in", "player", senderName)));
            return new BankDatabase.Change<>(List.of(payer.withBalance(senderNew), payee.withBalance(recipientNew)), rows, List.of(),
                    new Outcome(OperationResult.success("transfer.success", "amount", money(sent), "player", payee.username()),
                            payer.balance(), payee.balance(), 0.0));
        })).thenApply(saved -> {
            if (saved.isEmpty()) {
                return OperationResult.fail("transfer.not-saved");
            }
            BankDatabase.Change<Outcome> change = saved.get();
            if (!change.written()) {
                return change.result().result();
            }
            BankAccount senderUpdated = change.accounts().get(0);
            BankAccount recipientUpdated = change.accounts().get(1);
            cacheIfOnline(senderUpdated);
            cacheIfOnline(recipientUpdated);

            // both ends carry the counterparty so EconGuard can link the two players
            String recipientName = recipientUpdated.username();
            report(senderId, senderName, "transfer-sent", sent,
                    change.result().before(), senderUpdated.balance(), false, recipientId, recipientName);
            report(recipientId, recipientName, "transfer-received", sent,
                    change.result().after(), recipientUpdated.balance(), true, senderId, senderName);
            return change.result().result();
        });
    }

    @Override
    public BankSnapshot exportAccount(UUID playerId) {
        return onDbBlocking("export bank account " + playerId, () -> database.getAccount(playerId)).join()
                .map(a -> new BankSnapshot(a.balance(), a.level(), a.lastInterestClaim(), a.bonusClaimed()))
                .orElse(new BankSnapshot(0.0, levelManager.getStartingLevel(), 0L, false));
    }

    @Override
    public void importAccount(UUID playerId, BankSnapshot snapshot) {
        String name = onDbBlocking("read bank account " + playerId, () -> database.getAccount(playerId)).join()
                .map(BankAccount::username).orElse(nameOf(playerId));
        BankAccount updated = new BankAccount(playerId, name, round(snapshot.balance()),
                snapshot.level(), snapshot.lastInterestClaim(), snapshot.bonusClaimed());
        BankDatabase.NewAccount key = new BankDatabase.NewAccount(playerId, name, updated.level(), false);
        onDbBlocking("import bank account " + playerId,
                () -> database.change(key, current -> BankDatabase.Change.write(updated, List.of(), Boolean.TRUE))).join()
                .ifPresent(saved -> cacheIfOnline(saved.account()));
    }

    @Override
    public void resetAccount(UUID playerId) {
        importAccount(playerId, new BankSnapshot(0.0, levelManager.getStartingLevel(), 0L, false));
    }

    private String nameOf(UUID playerId) {
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name != null ? name : "Account";
    }

    // a fresh unsaved account at the starting level if none exists; database thread
    private BankAccount loadAccountById(UUID accountId, String label) {
        return database.getAccount(accountId).orElseGet(() ->
                new BankAccount(accountId, label == null ? "Account" : label, 0.0,
                        levelManager.getStartingLevel(), 0L, false));
    }

    @Override
    public double getAccountBalance(UUID accountId) {
        return round(onDbBlocking("read shared account " + accountId, () -> database.getAccount(accountId)).join()
                .map(BankAccount::balance).orElse(0.0));
    }

    @Override
    public AccountView getAccountView(UUID accountId, String label) {
        BankAccount account = onDbBlocking("read shared account " + accountId, () -> loadAccountById(accountId, label)).join();
        BankLevel level = getEffectiveLevel(account.level());
        return new AccountView(round(account.balance()), account.level(), level.name(), level.maxBalance());
    }

    @Override
    public String accountDeposit(Player purse, UUID accountId, String label, double amount) {
        return apiResult(resultOf(guarded(purse.getUniqueId(), "shared account deposit",
                () -> depositToAccount(purse, accountId, label, amount, true)), "account.deposit.refunded"));
    }

    @Override
    public CompletableFuture<String> accountDepositAsync(Player purse, UUID accountId, String label, double amount) {
        return guarded(purse.getUniqueId(), "shared account deposit",
                () -> depositToAccount(purse, accountId, label, amount, false)).thenApply(this::apiResult);
    }

    @Override
    public String accountWithdraw(Player purse, UUID accountId, String label, double amount) {
        return apiResult(resultOf(guarded(purse.getUniqueId(), "shared account withdrawal",
                () -> withdrawFromAccount(purse, accountId, label, amount, true)), "withdraw.reverted"));
    }

    @Override
    public CompletableFuture<String> accountWithdrawAsync(Player purse, UUID accountId, String label, double amount) {
        return guarded(purse.getUniqueId(), "shared account withdrawal",
                () -> withdrawFromAccount(purse, accountId, label, amount, false)).thenApply(this::apiResult);
    }

    @Override
    public UpgradeView getUpgradeView(UUID accountId, String label) {
        BankAccount account = onDbBlocking("read shared account " + accountId, () -> loadAccountById(accountId, label)).join();
        BankLevel next = levelManager.getNextLevel(account.level()).orElse(null);
        if (next == null) {
            return new UpgradeView(true, account.level(), "", 0.0, "", getEffectiveLevel(account.level()).maxBalance());
        }
        return new UpgradeView(false, next.level(), next.name(), next.upgradeMoneyCost(),
                itemCostText(next.itemRequirements()), next.maxBalance());
    }

    @Override
    public String accountUpgrade(Player purse, UUID accountId, String label) {
        return apiResult(resultOf(guarded(purse.getUniqueId(), "shared account upgrade",
                () -> upgradeAccount(purse, accountId, label, true)), "upgrade.refunded"));
    }

    @Override
    public CompletableFuture<String> accountUpgradeAsync(Player purse, UUID accountId, String label) {
        return guarded(purse.getUniqueId(), "shared account upgrade",
                () -> upgradeAccount(purse, accountId, label, false)).thenApply(this::apiResult);
    }

    // the API contract: null on success, otherwise the rendered message without the prefix
    private String apiResult(OperationResult result) {
        return result.success() ? null : messages.render(result);
    }

    private CompletableFuture<OperationResult> depositToAccount(Player purse, UUID accountId, String label, double amount,
                                                                boolean blocking) {
        double requested = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (requested < minimum) {
            return done(OperationResult.fail("amount.too-small", "minimum", money(minimum)));
        }
        return onDb(blocking, "read shared account " + accountId, () -> loadAccountById(accountId, label))
                .thenCompose(account -> depositToAccount(purse, accountId, label, account, requested, blocking));
    }

    private CompletableFuture<OperationResult> depositToAccount(Player purse, UUID accountId, String label, BankAccount account,
                                                                double amount, boolean blocking) {
        BankLevel level = getEffectiveLevel(account.level());
        double space = level.maxBalance() - account.balance();
        if (space <= 0) {
            return done(OperationResult.fail("account.deposit.bank-full"));
        }
        double principal = round(Math.min(account.balance() + Math.min(amount, space), level.maxBalance()));
        double charge = round(principal - account.balance());
        if (charge <= 0.0) {
            return done(OperationResult.fail("account.deposit.bank-full"));
        }
        if (!vaultHook.getEconomy().has(purse, charge)) {
            return done(OperationResult.fail("account.deposit.not-enough-money"));
        }
        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(purse, charge);
        if (!response.transactionSuccess()) {
            return done(OperationResult.fail("deposit.failed", "reason", String.valueOf(response.errorMessage)));
        }
        UUID purseId = purse.getUniqueId();
        String purseName = purse.getName();
        BankDatabase.NewAccount key = shared(accountId, label);
        return this.<Outcome>save(blocking, "shared account deposit " + accountId, () -> database.changeCoop(key, (current, depositors, nextSeq) -> {
            double newBalance = round(current.balance() + charge);
            if (newBalance > getEffectiveLevel(current.level()).maxBalance()) {
                return BankDatabase.Change.none(Outcome.rejected("account.deposit.bank-full"));
            }
            CoopContribution contribution = new CoopContribution(accountId, purseId, purseName, charge, nextSeq);
            return new BankDatabase.Change<>(List.of(current.withBalance(newBalance)),
                    List.of(ledger(current, "COOP_DEPOSIT", charge, newBalance,
                            messages.get("notes.account-deposit", "player", purseName))),
                    List.of(contribution),
                    new Outcome(OperationResult.silent(), current.balance(), newBalance, 0.0));
        })).thenApply(saved -> {
            if (saved.isEmpty() || !saved.get().written()) {
                compensateDeposit(purse, charge, "account deposit");
                return saved.isEmpty() ? OperationResult.fail("account.deposit.refunded") : saved.get().result().result();
            }
            Outcome outcome = saved.get().result();
            report(purseId, purseName, "coop-deposited", charge, outcome.before(), outcome.after(), false, null, null);
            return outcome.result();
        });
    }

    private record CoopWithdrawal(OperationResult result, double before, List<CoopContributions.Share> shares,
                                  double minBefore, long claimBefore) {
        static CoopWithdrawal rejected(String key) {
            return new CoopWithdrawal(OperationResult.fail(key), 0.0, List.of(), 0.0, 0L);
        }
    }

    private CompletableFuture<OperationResult> withdrawFromAccount(Player purse, UUID accountId, String label, double amount,
                                                                   boolean blocking) {
        double taken = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (taken < minimum) {
            return done(OperationResult.fail("amount.too-small", "minimum", money(minimum)));
        }
        UUID purseId = purse.getUniqueId();
        String purseName = purse.getName();
        BankDatabase.NewAccount key = shared(accountId, label);
        return this.<CoopWithdrawal>save(blocking, "shared account withdrawal " + accountId, () -> database.changeCoop(key, (current, depositors, nextSeq) -> {
            if (current.balance() < taken) {
                return BankDatabase.Change.none(CoopWithdrawal.rejected("account.withdraw.not-enough-money"));
            }
            double newBalance = round(current.balance() - taken);
            List<CoopContributions.Share> shares = CoopContributions.shares(depositors, purseId, taken);
            return new BankDatabase.Change<>(List.of(current.withBalance(newBalance)),
                    List.of(ledger(current, "COOP_WITHDRAW", taken, newBalance,
                            messages.get("notes.account-withdraw", "player", purseName))),
                    CoopContributions.withdrawalChanges(accountId, shares),
                    new CoopWithdrawal(OperationResult.silent(), current.balance(), shares,
                            current.minBalanceSinceClaim(), current.lastInterestClaim()));
        })).thenCompose(saved -> {
            if (saved.isEmpty()) {
                return done(OperationResult.fail("withdraw.reverted"));
            }
            if (!saved.get().written()) {
                return done(saved.get().result().result());
            }
            CoopWithdrawal withdrawal = saved.get().result();
            EconomyResponse response = vaultHook.getEconomy().depositPlayer(purse, taken);
            if (response.transactionSuccess()) {
                reportCoopWithdrawal(purse, withdrawal.shares(), withdrawal.before());
                return done(withdrawal.result());
            }
            OperationResult failed = OperationResult.fail("withdraw.failed", "reason", String.valueOf(response.errorMessage));
            return this.<Boolean>save(blocking, "put back shared account withdrawal " + accountId, () -> database.changeCoop(key, (current, depositors, nextSeq) -> {
                BankDatabase.Change<Boolean> back = returnToBank(current, taken, withdrawal.minBefore(), withdrawal.claimBefore(),
                        "COOP_WITHDRAW_REVERTED", messages.get("notes.account-withdraw-reverted", "player", purseName));
                return new BankDatabase.Change<>(back.accounts(), back.ledger(),
                        CoopContributions.returnedChanges(accountId, withdrawal.shares()), Boolean.TRUE);
            })).thenApply(back -> {
                if (back.isPresent() && back.get().written()) {
                    return failed;
                }
                logLostPayout(purse, taken, "shared account withdrawal");
                return OperationResult.fail("withdraw.needs-staff");
            });
        });
    }

    // Money a member paid in and takes back is an internal move. Money another member paid in is
    // incoming from that member, and money with no known depositor is incoming with no counterparty.
    private void reportCoopWithdrawal(Player purse, List<CoopContributions.Share> shares, double oldBalance) {
        double balance = oldBalance;
        for (CoopContributions.Share share : shares) {
            double after = round(balance - share.amount());
            if (purse.getUniqueId().equals(share.depositor())) {
                report(purse.getUniqueId(), purse.getName(), "coop-withdrew", share.amount(), balance, after, false, null, null);
            } else if (share.depositor() != null) {
                report(purse.getUniqueId(), purse.getName(), "coop-received", share.amount(), balance, after, true,
                        share.depositor(), share.depositorName());
            } else {
                report(purse.getUniqueId(), purse.getName(), "coop-withdrew-untracked", share.amount(), balance, after, true, null, null);
            }
            balance = after;
        }
    }

    // runs after the money has moved, so a failed report is logged and never fails the bank action
    private void report(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                        boolean incoming, UUID counterparty, String counterpartyName) {
        try {
            auditSink.record(uuid, username, action, amount, oldBalance, newBalance, incoming, counterparty, counterpartyName);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not report bank action '" + action + "' for " + username, e);
        }
    }

    // the level paid for must still be the next one: another server may have upgraded the account meanwhile
    private BankDatabase.Change<Outcome> levelUp(BankAccount current, int paidFrom, BankLevel next, String type,
                                                 String maxLevelKey, OperationResult success) {
        if (current.level() != paidFrom) {
            return BankDatabase.Change.none(levelManager.getNextLevel(current.level()).isEmpty()
                    ? Outcome.rejected(maxLevelKey)
                    : Outcome.rejected("upgrade.refunded"));
        }
        BankAccount updated = current.withLevel(next.level());
        return BankDatabase.Change.write(updated,
                List.of(ledger(current, type, next.upgradeMoneyCost(), updated.balance(),
                        messages.get("notes.upgrade", "level_name", next.name()))),
                Outcome.of(success));
    }

    // takes back a saved upgrade whose items were gone by the time they were to be removed
    private BankDatabase.Change<Boolean> undoLevelUp(BankAccount current, int paidFrom, BankLevel next, String type) {
        if (current.level() != next.level()) {
            return BankDatabase.Change.none(Boolean.FALSE);
        }
        return BankDatabase.Change.write(current.withLevel(paidFrom),
                List.of(ledger(current, type, next.upgradeMoneyCost(), current.balance(),
                        messages.get("notes.upgrade-reverted", "level_name", next.name()))),
                Boolean.TRUE);
    }

    private boolean stillHasItems(Player player, List<ItemRequirement> requirements) {
        return requirements.isEmpty() || (player.isOnline() && getMissingItems(player, requirements).isEmpty());
    }

    private CompletableFuture<OperationResult> upgradeAccount(Player purse, UUID accountId, String label, boolean blocking) {
        return onDb(blocking, "read shared account " + accountId, () -> loadAccountById(accountId, label))
                .thenCompose(account -> upgradeAccount(purse, accountId, label, account, blocking));
    }

    private CompletableFuture<OperationResult> upgradeAccount(Player purse, UUID accountId, String label, BankAccount account,
                                                              boolean blocking) {
        BankLevel next = levelManager.getNextLevel(account.level()).orElse(null);
        if (next == null) {
            return done(OperationResult.fail("account.upgrade.max-level"));
        }
        if (!vaultHook.getEconomy().has(purse, next.upgradeMoneyCost())) {
            return done(OperationResult.fail("account.upgrade.not-enough-money", "cost", money(next.upgradeMoneyCost())));
        }
        List<String> missing = getMissingItems(purse, next.itemRequirements());
        if (!missing.isEmpty()) {
            return done(OperationResult.fail("upgrade.missing-items", "items", joinItems(missing)));
        }
        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(purse, next.upgradeMoneyCost());
        if (!response.transactionSuccess()) {
            return done(OperationResult.fail("upgrade.failed", "reason", String.valueOf(response.errorMessage)));
        }
        BankDatabase.NewAccount key = shared(accountId, label);
        return this.<Outcome>save(blocking, "shared account upgrade " + accountId, () -> database.change(key, current ->
                levelUp(current, account.level(), next, "COOP_UPGRADE", "account.upgrade.max-level", OperationResult.silent())))
                .thenCompose(saved -> {
                    if (saved.isEmpty() || !saved.get().written()) {
                        compensateDeposit(purse, next.upgradeMoneyCost(), "account upgrade");
                        return done(saved.isEmpty() ? OperationResult.fail("upgrade.refunded") : saved.get().result().result());
                    }
                    if (stillHasItems(purse, next.itemRequirements())) {
                        removeItems(purse, next.itemRequirements());
                        return done(OperationResult.silent());
                    }
                    return this.<Boolean>save(blocking, "undo shared account upgrade " + accountId, () -> database.change(key, current ->
                            undoLevelUp(current, account.level(), next, "COOP_UPGRADE_REVERTED"))).thenApply(undone -> {
                        if (undone.isEmpty() || !undone.get().written()) {
                            logKeptUpgrade(purse, accountId);
                            return OperationResult.silent();
                        }
                        compensateDeposit(purse, next.upgradeMoneyCost(), "account upgrade");
                        return OperationResult.fail("upgrade.missing-items", "items", joinItems(getMissingItems(purse, next.itemRequirements())));
                    });
                });
    }

    private void logKeptUpgrade(Player player, UUID accountId) {
        plugin.getLogger().severe("RoyalBank: bank account " + accountId + " was upgraded and paid for by " + player.getName()
                + " (" + player.getUniqueId() + "), but the upgrade items were gone before they could be taken and the"
                + " upgrade could not be undone. MANUAL RECONCILIATION NEEDED.");
    }

    @Override
    public List<TransactionView> getAccountTransactions(UUID accountId, int limit) {
        List<TransactionView> out = new ArrayList<>();
        for (BankTransaction t : onDbBlocking("read shared account transactions " + accountId,
                () -> database.getRecentTransactions(accountId, limit)).join()) {
            out.add(new TransactionView(t.type(), t.amount(), t.balanceAfter(), t.createdAt(), t.note()));
        }
        return out;
    }

    public CompletableFuture<OperationResult> upgrade(Player player) {
        return guarded(player.getUniqueId(), "upgrade", () -> accountFor(player).thenCompose(account -> upgradeFrom(player, account)));
    }

    private CompletableFuture<OperationResult> upgradeFrom(Player player, BankAccount account) {
        BankLevel nextLevel = levelManager.getNextLevel(account.level()).orElse(null);
        if (nextLevel == null) {
            return done(OperationResult.fail("upgrade.max-level"));
        }

        if (!upgradeUnlocked(player, account)) {
            return done(OperationResult.fail("upgrade.locked", "required", money(getUpgradeUnlockCombinedBalance())));
        }

        if (!vaultHook.getEconomy().has(player, nextLevel.upgradeMoneyCost())) {
            return done(OperationResult.fail("upgrade.not-enough-money", "cost", money(nextLevel.upgradeMoneyCost())));
        }

        List<String> missingItems = getMissingItems(player, nextLevel.itemRequirements());
        if (!missingItems.isEmpty()) {
            return done(OperationResult.fail("upgrade.missing-items", "items", joinItems(missingItems)));
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, nextLevel.upgradeMoneyCost());
        if (!response.transactionSuccess()) {
            return done(OperationResult.fail("upgrade.failed", "reason", String.valueOf(response.errorMessage)));
        }

        BankDatabase.NewAccount key = personal(player);
        return this.<Outcome>save(false, "upgrade for " + key.username(), () -> database.change(key, current ->
                levelUp(current, account.level(), nextLevel, "UPGRADE", "upgrade.max-level",
                        OperationResult.success("upgrade.success", "level_name", nextLevel.name())))).thenCompose(saved -> {
            if (saved.isEmpty() || !saved.get().written()) {
                // items have not been removed yet, so refunding the money is enough
                boolean refunded = compensateDeposit(player, nextLevel.upgradeMoneyCost(), "upgrade");
                if (!refunded) {
                    return done(OperationResult.fail("upgrade.needs-staff"));
                }
                return done(saved.isEmpty() ? OperationResult.fail("upgrade.refunded") : saved.get().result().result());
            }

            // the items were checked before the save, a tick or two ago; check again before taking them
            if (stillHasItems(player, nextLevel.itemRequirements())) {
                removeItems(player, nextLevel.itemRequirements());
                cacheIfOnline(saved.get().account());
                return done(saved.get().result().result());
            }
            return this.<Boolean>save(false, "undo upgrade for " + key.username(), () -> database.change(key, current ->
                    undoLevelUp(current, account.level(), nextLevel, "UPGRADE_REVERTED"))).thenApply(undone -> {
                if (undone.isEmpty() || !undone.get().written()) {
                    logKeptUpgrade(player, player.getUniqueId());
                    cacheIfOnline(saved.get().account());
                    return saved.get().result().result();
                }
                cacheIfOnline(undone.get().account());
                if (!compensateDeposit(player, nextLevel.upgradeMoneyCost(), "upgrade")) {
                    return OperationResult.fail("upgrade.needs-staff");
                }
                return OperationResult.fail("upgrade.missing-items", "items", joinItems(getMissingItems(player, nextLevel.itemRequirements())));
            });
        });
    }

    // payout: interest to hand to the purse; claimBefore and minBefore put the claim back if that payment fails
    private record InterestClaim(OperationResult result, double before, double after, double paid, double payout,
                                 long claimBefore, double minBefore) {
        static InterestClaim of(OperationResult result) {
            return new InterestClaim(result, 0.0, 0.0, 0.0, 0.0, 0L, 0.0);
        }
    }

    // what a claim does to this account
    private BankDatabase.Change<InterestClaim> interestRule(BankAccount account, long now, boolean manual, boolean payToBank) {
        long cooldownSeconds = interestCooldownHours() * 3600L;
        long nextAllowed = account.lastInterestClaim() + cooldownSeconds;
        if (account.lastInterestClaim() > 0 && now < nextAllowed) {
            return BankDatabase.Change.none(InterestClaim.of(
                    OperationResult.fail("interest.cooldown", "time", formatDuration(nextAllowed - now))));
        }

        BankLevel level = getEffectiveLevel(account.level());
        double interest = round(nextInterest(account, level));

        if (interest <= 0) {
            // keep the cooldown when the balance itself earns nothing, so an auto-claim on an empty bank costs nothing
            if (round(calculateInterest(account.balance(), level)) <= 0) {
                return BankDatabase.Change.none(InterestClaim.of(
                        manual ? OperationResult.success("interest.nothing-earned") : OperationResult.silent()));
            }
            // money paid in since the last claim starts a full period now, or claiming twice would pay on it at once
            return BankDatabase.Change.write(account.withInterestClaimed(now), List.of(), InterestClaim.of(manual
                    ? OperationResult.success("interest.period-started", "time", formatDuration(cooldownSeconds))
                    : OperationResult.silent()));
        }

        if (payToBank) {
            double newBalance = round(Math.min(account.balance() + interest, level.maxBalance()));
            double actuallyPaid = round(newBalance - account.balance());
            if (actuallyPaid <= 0) {
                return BankDatabase.Change.none(InterestClaim.of(
                        manual ? OperationResult.success("interest.bank-full") : OperationResult.silent()));
            }
            return BankDatabase.Change.write(account.withBalance(newBalance).withInterestClaimed(now),
                    List.of(ledger(account, "INTEREST", actuallyPaid, newBalance, messages.get("notes.interest"))),
                    new InterestClaim(OperationResult.success("interest.paid-to-bank", "amount", money(actuallyPaid)),
                            account.balance(), newBalance, actuallyPaid, 0.0, account.lastInterestClaim(), account.minBalanceSinceClaim()));
        }

        return BankDatabase.Change.write(account.withInterestClaimed(now),
                List.of(ledger(account, "INTEREST", interest, account.balance(), messages.get("notes.interest-to-purse"))),
                new InterestClaim(OperationResult.success("interest.paid-to-purse", "amount", money(interest)),
                        account.balance(), account.balance(), interest, interest, account.lastInterestClaim(), account.minBalanceSinceClaim()));
    }

    // only while the claim is still this one; a later claim started its own period
    private BankDatabase.Change<Boolean> restoreClaim(BankAccount current, InterestClaim claim, long claimedAt) {
        if (current.lastInterestClaim() != claimedAt) {
            return BankDatabase.Change.none(Boolean.FALSE);
        }
        return BankDatabase.Change.write(
                current.withClaimRestored(claim.claimBefore(), Math.min(claim.minBefore(), current.minBalanceSinceClaim())),
                List.of(ledger(current, "INTEREST_REVERTED", claim.payout(), current.balance(), messages.get("notes.interest-reverted"))),
                Boolean.TRUE);
    }

    public CompletableFuture<OperationResult> claimInterest(Player player, boolean manual) {
        return guarded(player.getUniqueId(), "interest claim", () -> claimFlow(player, manual));
    }

    private CompletableFuture<OperationResult> claimFlow(Player player, boolean manual) {
        if (manual && !plugin.getConfig().getBoolean("settings.interest-command-enabled", true)) {
            return done(OperationResult.fail("interest.disabled"));
        }

        long now = Instant.now().getEpochSecond();
        boolean payToBank = plugin.getConfig().getBoolean("settings.pay-interest-to-bank", true);
        BankDatabase.NewAccount key = personal(player);
        return this.<InterestClaim>save(false, "interest claim for " + key.username(),
                () -> database.change(key, current -> interestRule(current, now, manual, payToBank))).thenCompose(saved -> {
            if (saved.isEmpty()) {
                return done(OperationResult.fail("interest.not-saved"));
            }
            if (!saved.get().written()) {
                return done(saved.get().result().result());
            }
            cacheIfOnline(saved.get().account());
            InterestClaim claim = saved.get().result();
            if (claim.payout() > 0.0) {
                EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, claim.payout());
                if (!response.transactionSuccess()) {
                    return putBackClaim(player, key, claim, now,
                            OperationResult.fail("interest.failed", "reason", String.valueOf(response.errorMessage)));
                }
            }
            if (claim.paid() > 0.0) {
                // incoming: compounding on a parked balance is part of the parking signal
                report(player.getUniqueId(), player.getName(), payToBank ? "interest" : "interest-to-purse", claim.paid(),
                        claim.before(), claim.after(), true, null, null);
            }
            return done(claim.result());
        });
    }

    // the purse payment failed, so the claim never happened: the next claim may pay this period again
    private CompletableFuture<OperationResult> putBackClaim(Player player, BankDatabase.NewAccount key, InterestClaim claim,
                                                            long claimedAt, OperationResult failed) {
        return this.<Boolean>save(false, "put back interest claim for " + key.username(),
                () -> database.change(key, current -> restoreClaim(current, claim, claimedAt))).thenApply(back -> {
            if (back.isPresent() && back.get().written()) {
                cacheIfOnline(back.get().account());
                return failed;
            }
            plugin.getLogger().severe("RoyalBank: the interest of " + money(claim.payout()) + " for " + player.getName()
                    + " (" + player.getUniqueId() + ") was recorded but not paid, and the claim could not be put back."
                    + " MANUAL RECONCILIATION NEEDED.");
            return OperationResult.fail("interest.needs-staff");
        });
    }

    public BankLevel getEffectiveLevel(int level) {
        if (levelManager.getLevels().isEmpty()) {
            throw new IllegalStateException("No valid bank levels are configured.");
        }
        return levelManager.getLevel(level)
                .or(() -> levelManager.getLevel(levelManager.getStartingLevel()))
                .or(() -> levelManager.getLevels().values().stream().findFirst())
                .orElseThrow();
    }

    private CompletableFuture<OperationResult> adminChange(OfflinePlayer player, OperationResult notSaved,
                                                           BankDatabase.Rule<OperationResult> rule) {
        BankDatabase.NewAccount key = personal(player);
        return this.<OperationResult>save(false, "admin change for " + key.username(), () -> database.change(key, rule))
                .thenApply(saved -> {
                    if (saved.isEmpty()) {
                        return notSaved;
                    }
                    if (saved.get().written()) {
                        cacheIfOnline(saved.get().account());
                    }
                    return saved.get().result();
                });
    }

    /** The stored row for /bank admin balance, created at the starting level when missing. */
    public CompletableFuture<BankAccount> adminAccount(OfflinePlayer player) {
        BankDatabase.NewAccount key = personal(player);
        return onDb("admin read of " + key.username(), () -> database.getOrCreateAccount(key));
    }

    public CompletableFuture<List<BankTransaction>> getRecentTransactions(UUID account, int limit) {
        return onDb("read transactions of " + account, () -> database.getRecentTransactions(account, limit));
    }

    // queued behind bank actions, so on SQLite's single connection they wait instead of freezing the server
    public CompletableFuture<Integer> pruneTransactions(int maxPerPlayer) {
        return onDb("prune bank transactions", () -> database.pruneTransactions(maxPerPlayer));
    }

    public CompletableFuture<Boolean> backup(Path target) {
        return onDb("back up the bank database", () -> database.backupTo(target));
    }

    public CompletableFuture<OperationResult> setBalance(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0.0) {
            return done(OperationResult.fail("admin.result.invalid-amount"));
        }
        double sanitized = Amounts.roundNonNegative(plugin, amount);
        double maxTransaction = plugin.getConfig().getDouble("settings.amount-limits.max-transaction", 1_000_000_000_000.0);
        if (maxTransaction > 0.0 && sanitized > maxTransaction) {
            return done(OperationResult.fail("admin.result.above-limit"));
        }
        return adminChange(player, OperationResult.fail("admin.result.balance-not-saved"), account -> {
            BankLevel level = getEffectiveLevel(account.level());
            double newBalance = round(Math.min(sanitized, level.maxBalance()));
            double discarded = round(sanitized - newBalance);
            String note = discarded > 0
                    ? messages.get("notes.admin-set-balance-capped", "discarded", money(discarded))
                    : messages.get("notes.admin-set-balance");
            BankAccount updated = account.withBalance(newBalance);
            return BankDatabase.Change.write(updated, List.of(ledger(account, "ADMIN_SET", newBalance, newBalance, note)),
                    OperationResult.success(discarded > 0 ? "admin.result.set-balance-capped" : "admin.result.set-balance",
                            "player", updated.username(), "balance", money(newBalance), "discarded", money(discarded)));
        });
    }

    public CompletableFuture<OperationResult> addBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return done(OperationResult.fail("admin.result.invalid-amount"));
        }
        return adminChange(player, OperationResult.fail("admin.result.balance-not-saved"), account -> {
            BankLevel level = getEffectiveLevel(account.level());
            double newBalance = round(Math.min(account.balance() + sanitized, level.maxBalance()));
            double added = round(newBalance - account.balance());
            double discarded = round(sanitized - added);
            String note = discarded > 0
                    ? messages.get("notes.admin-add-balance-capped", "discarded", money(discarded))
                    : messages.get("notes.admin-add-balance");
            BankAccount updated = account.withBalance(newBalance);
            return BankDatabase.Change.write(updated, List.of(ledger(account, "ADMIN_ADD", added, newBalance, note)),
                    OperationResult.success(discarded > 0 ? "admin.result.add-balance-capped" : "admin.result.add-balance",
                            "player", updated.username(), "amount", money(added), "discarded", money(discarded)));
        });
    }

    public CompletableFuture<OperationResult> removeBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return done(OperationResult.fail("admin.result.invalid-amount"));
        }
        return adminChange(player, OperationResult.fail("admin.result.balance-not-saved"), account -> {
            double newBalance = round(Math.max(0.0, account.balance() - sanitized));
            double removed = round(account.balance() - newBalance);
            BankAccount updated = account.withBalance(newBalance);
            return BankDatabase.Change.write(updated,
                    List.of(ledger(account, "ADMIN_REMOVE", removed, newBalance, messages.get("notes.admin-remove-balance"))),
                    OperationResult.success("admin.result.remove-balance", "player", updated.username(), "amount", money(removed)));
        });
    }

    public CompletableFuture<OperationResult> setLevel(OfflinePlayer player, int level) {
        BankLevel bankLevel = levelManager.getLevel(level).orElse(null);
        if (bankLevel == null) {
            return done(OperationResult.fail("admin.result.unknown-level", "level", String.valueOf(level)));
        }
        return adminChange(player, OperationResult.fail("admin.result.level-not-saved"), account -> {
            double clampedBalance = round(Math.min(account.balance(), bankLevel.maxBalance()));
            double discarded = round(account.balance() - clampedBalance);
            String note = discarded > 0
                    ? messages.get("notes.admin-set-level-capped", "level_name", bankLevel.name(), "discarded", money(discarded))
                    : messages.get("notes.admin-set-level", "level_name", bankLevel.name());
            BankAccount updated = account.withLevel(level).withBalance(clampedBalance);
            return BankDatabase.Change.write(updated, List.of(ledger(account, "ADMIN_LEVEL", discarded, clampedBalance, note)),
                    OperationResult.success(discarded > 0 ? "admin.result.set-level-capped" : "admin.result.set-level",
                            "player", updated.username(), "level", String.valueOf(bankLevel.level()),
                            "level_name", bankLevel.name(), "discarded", money(discarded)));
        });
    }

    public CompletableFuture<OperationResult> resetAccount(OfflinePlayer player) {
        return adminChange(player, OperationResult.fail("admin.result.reset-not-saved"), account -> {
            BankAccount updated = new BankAccount(account.uuid(), account.username(), 0.0, levelManager.getStartingLevel(), 0L, false);
            return BankDatabase.Change.write(updated, List.of(ledger(account, "ADMIN_RESET", 0.0, 0.0, messages.get("notes.admin-reset"))),
                    OperationResult.success("admin.result.reset", "player", updated.username()));
        });
    }

    public List<String> describeBalance(Player player) {
        BankAccount account = getAccount(player);
        BankLevel level = getEffectiveLevel(account.level());
        return messages.lines("info.balance",
                "level", String.valueOf(level.level()),
                "level_name", level.name(),
                "balance", money(account.balance()),
                "max_balance", money(level.maxBalance()),
                "max_interest", money(level.maxInterest()),
                "next_interest", money(nextInterest(account, level)));
    }

    public List<String> describeNextUpgrade(Player player) {
        BankAccount account = getAccount(player);
        BankLevel nextLevel = levelManager.getNextLevel(account.level()).orElse(null);
        if (nextLevel == null) {
            return messages.lines("info.max-level");
        }
        if (!hasUpgradeUnlocked(player)) {
            return messages.lines("info.upgrades-locked",
                    "required", money(getUpgradeUnlockCombinedBalance()),
                    "combined", money(getCombinedBalance(player)));
        }
        return messages.lines("info.next-upgrade",
                "level", String.valueOf(nextLevel.level()),
                "level_name", nextLevel.name(),
                "money_cost", money(nextLevel.upgradeMoneyCost()),
                "item_cost", itemCostText(nextLevel.itemRequirements()),
                "max_balance", money(nextLevel.maxBalance()),
                "max_interest", money(nextLevel.maxInterest()));
    }

    private String itemCostText(List<ItemRequirement> requirements) {
        if (requirements.isEmpty()) {
            return messages.get("format.no-items");
        }
        return joinItems(requirements.stream().map(this::itemName).toList());
    }

    private String itemName(ItemRequirement requirement) {
        return messages.get("format.item-cost", "amount", String.valueOf(requirement.amount()), "item", requirement.prettyName());
    }

    private String joinItems(List<String> items) {
        return String.join(messages.get("format.item-separator"), items);
    }

    /** Empty if maxed or the next tier needs no items. */
    public List<ItemRequirement> nextUpgradeItemRequirements(Player player) {
        return levelManager.getNextLevel(getAccount(player).level())
                .map(BankLevel::itemRequirements).orElse(List.of());
    }

    public int heldItemCount(Player player, ItemRequirement requirement) {
        return countItems(player.getInventory(), requirement);
    }

    public String money(double amount) {
        return Text.money(amount, plugin.getConfig().getString("settings.currency-symbol", "$"));
    }

    public double getWalletBalance(Player player) {
        return vaultHook.getEconomy().getBalance(player);
    }

    // same statistic RoyalTrade's gate reads; works for offline players
    private double playtimeHours(OfflinePlayer player) {
        return player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20.0 / 3600.0;
    }


    public String getInterestTimeRemaining(Player player) {
        BankAccount account = getAccount(player);
        long cooldownSeconds = interestCooldownHours() * 3600L;
        long nextAllowed = account.lastInterestClaim() + cooldownSeconds;
        long now = Instant.now().getEpochSecond();
        if (account.lastInterestClaim() <= 0 || now >= nextAllowed) {
            return messages.get("format.ready-now");
        }
        return formatDuration(nextAllowed - now);
    }

    // what the next claim pays: interest on the lowest balance held since the last claim
    public double nextInterest(BankAccount account, BankLevel level) {
        return calculateInterest(account.minBalanceSinceClaim(), level);
    }

    public double calculateInterest(double balance, BankLevel level) {
        double interest = 0.0;
        for (InterestTranche tranche : level.interestTranches()) {
            interest += tranche.calculate(balance);
        }
        // A negative max-interest means "no per-level cap"; 0 means "no interest for this tier".
        double levelCap = level.maxInterest() < 0.0 ? Double.MAX_VALUE : level.maxInterest();
        if (plugin.getConfig().getBoolean("settings.max-daily-interest.enabled", true)) {
            double globalCap = plugin.getConfig().getDouble("settings.max-daily-interest.amount", Double.MAX_VALUE);
            if (globalCap >= 0.0) {
                levelCap = Math.min(levelCap, globalCap);
            }
        }
        return Math.min(interest, levelCap);
    }

    public boolean hasUpgradeUnlocked(Player player) {
        return upgradeUnlocked(player, getAccount(player));
    }

    private boolean upgradeUnlocked(Player player, BankAccount account) {
        return account.level() > levelManager.getStartingLevel()
                || account.balance() + getWalletBalance(player) >= getUpgradeUnlockCombinedBalance();
    }

    public double getCombinedBalance(Player player) {
        return getAccount(player).balance() + getWalletBalance(player);
    }

    public double getUpgradeUnlockCombinedBalance() {
        return plugin.getConfig().getDouble("settings.upgrade-unlock-combined-balance", 10_000_000.0);
    }

    private long interestCooldownHours() {
        return plugin.getConfig().getLong("settings.interest-cooldown-hours", DEFAULT_INTEREST_COOLDOWN_HOURS);
    }

    private double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        int decimals = Math.max(0, Math.min(6, plugin.getConfig().getInt("settings.amount-limits.decimal-places", 2)));
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    private List<String> getMissingItems(Player player, List<ItemRequirement> requirements) {
        List<String> missing = new ArrayList<>();
        for (ItemRequirement requirement : requirements) {
            int count = countItems(player.getInventory(), requirement);
            if (count < requirement.amount()) {
                missing.add(messages.get("format.item-have", "item", itemName(requirement), "count", String.valueOf(count)));
            }
        }
        return missing;
    }

    private int countItems(PlayerInventory inventory, ItemRequirement requirement) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (matchesRequirement(item, requirement)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, List<ItemRequirement> requirements) {
        for (ItemRequirement requirement : requirements) {
            int remaining = requirement.amount();
            ItemStack[] contents = player.getInventory().getContents();
            for (int index = 0; index < contents.length; index++) {
                ItemStack item = contents[index];
                if (!matchesRequirement(item, requirement)) {
                    continue;
                }

                int remove = Math.min(item.getAmount(), remaining);
                item.setAmount(item.getAmount() - remove);
                remaining -= remove;
                if (item.getAmount() <= 0) {
                    contents[index] = null;
                }
                if (remaining <= 0) {
                    break;
                }
            }
            player.getInventory().setContents(contents);
        }
    }

    private boolean matchesRequirement(ItemStack item, ItemRequirement requirement) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (requirement.type() == RequirementType.VANILLA) {
            return item.getType() == requirement.material() && !hasKnownCustomItemTag(item);
        }
        return matchesEcoItem(item, requirement.customItemId());
    }

    private boolean hasKnownCustomItemTag(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();

        // only EcoItems' own keys, so other plugins tagging vanilla items don't block vanilla upgrades
        return hasPdcString(container, "ecoitems", "item")
                || hasPdcString(container, "ecoweapons", "weapon");
    }

    private boolean matchesEcoItem(ItemStack item, String customItemId) {
        if (!item.hasItemMeta() || customItemId == null) {
            return false;
        }

        String normalized = customItemId.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "ecoitems";
        String wantedId = parts.length == 2 ? parts[1] : normalized;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();

        // ecoitems:item = <id>
        if (matchesPdcString(container, namespace, "item", wantedId)) {
            return true;
        }

        // legacy EcoWeapons tag (see EcoItems' ItemUtils.kt)
        if (namespace.equals("ecoitems") && matchesPdcString(container, "ecoweapons", "weapon", wantedId)) {
            return true;
        }

        // "ecoitem" alias used in some configs
        if (namespace.equals("ecoitem")) {
            return matchesEcoItem(item, "ecoitems:" + wantedId);
        }
        return false;
    }

    private boolean hasPdcString(PersistentDataContainer container, String namespace, String key) {
        NamespacedKey namespacedKey = new NamespacedKey(namespace, key);
        return container.has(namespacedKey, PersistentDataType.STRING);
    }

    private boolean matchesPdcString(PersistentDataContainer container, String namespace, String key, String wantedId) {
        NamespacedKey namespacedKey = new NamespacedKey(namespace, key);
        String value = container.get(namespacedKey, PersistentDataType.STRING);
        if (value == null) {
            return false;
        }
        String normalizedValue = value.toLowerCase(Locale.ROOT);
        return normalizedValue.equals(wantedId) || normalizedValue.equals(namespace + ":" + wantedId);
    }

    private String formatDuration(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return messages.get("format.duration-hours", "hours", String.valueOf(hours), "minutes", String.valueOf(minutes));
        }
        return messages.get("format.duration-minutes", "minutes", String.valueOf(minutes));
    }
}
