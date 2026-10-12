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
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BankService implements Listener, RoyalBankAPI {
    private static final long DEFAULT_INTEREST_COOLDOWN_HOURS = 31L;

    private final JavaPlugin plugin;
    private final BankDatabase database;
    private final LevelManager levelManager;
    private final VaultHook vaultHook;
    private final AbuseMonitor abuseMonitor;
    private final MessageManager messages;
    private AuditSink auditSink;

    // online players only, for display; every change writes the DB first, then refreshes this from the committed row
    private final Map<UUID, BankAccount> cache = new ConcurrentHashMap<>();

    public BankService(JavaPlugin plugin, BankDatabase database, LevelManager levelManager, VaultHook vaultHook,
                       MessageManager messages) {
        this.plugin = plugin;
        this.database = database;
        this.levelManager = levelManager;
        this.vaultHook = vaultHook;
        this.messages = messages;
        this.abuseMonitor = new AbuseMonitor(plugin, messages);
        this.auditSink = new LocalAuditSink(abuseMonitor);
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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadAccount(event.getPlayer());
        if (plugin.getConfig().getBoolean("settings.interest-on-join", true)) {
            // only a payout is announced at join (silent interest reads as a bug), delayed past join messages
            OperationResult interest = claimInterest(event.getPlayer(), false);
            if (interest.success() && interest.key() != null) {
                Player player = event.getPlayer();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Text.color(
                                plugin.getConfig().getString("settings.messages-prefix", "")
                                        + messages.render(interest)));
                    }
                }, 40L);
            }
        }
        notifyPendingFlags(event.getPlayer());
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

    public void loadAccount(Player player) {
        cache.put(player.getUniqueId(), database.getOrCreateAccount(player, levelManager.getStartingLevel()));
    }

    public void unloadAccount(UUID uuid) {
        cache.remove(uuid);
    }

    // for display only: money decisions re-read the locked row inside their transaction
    public BankAccount getAccount(Player player) {
        BankAccount cached = cache.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        BankAccount loaded = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        // online check stops a load racing a quit from resurrecting an evicted entry
        if (player.isOnline()) {
            cache.put(player.getUniqueId(), loaded);
        }
        return loaded;
    }

    /** Cache only, no database fallback. Safe from any thread. */
    public java.util.Optional<BankAccount> getCachedAccount(UUID uuid) {
        return java.util.Optional.ofNullable(cache.get(uuid));
    }

    // the stored row, for the checks made before money moves through Vault; also refreshes the cache
    private BankAccount freshAccount(Player player) {
        BankAccount account = database.getAccount(player.getUniqueId())
                .orElseGet(() -> database.getOrCreateAccount(player, levelManager.getStartingLevel()));
        if (player.isOnline()) {
            cache.put(player.getUniqueId(), account);
        }
        return account;
    }

    private void cacheIfOnline(BankAccount account) {
        if (Bukkit.getPlayer(account.uuid()) != null) {
            cache.put(account.uuid(), account);
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

    private boolean compensateDeposit(Player player, double amount, String operation) {
        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().severe("RoyalBank: FAILED to refund " + money(amount) + " to " + player.getName()
                    + " (" + player.getUniqueId() + ") after a failed " + operation + " save. MANUAL RECONCILIATION NEEDED. Vault: " + response.errorMessage);
            return false;
        }
        return true;
    }

    private boolean compensateWithdraw(Player player, double amount, String operation) {
        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().severe("RoyalBank: FAILED to reclaim " + money(amount) + " from " + player.getName()
                    + " (" + player.getUniqueId() + ") after a failed " + operation + " save. MANUAL RECONCILIATION NEEDED. Vault: " + response.errorMessage);
            return false;
        }
        return true;
    }

    public OperationResult deposit(Player player, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }

        BankAccount account = freshAccount(player);
        BankLevel level = getEffectiveLevel(account.level());
        double availableSpace = level.maxBalance() - account.balance();
        if (availableSpace <= 0) {
            return OperationResult.fail("deposit.bank-full");
        }

        // both the bank credit and the wallet debit come from the rounded post-deposit balance, so they
        // match to the unit
        double principalBalance = round(Math.min(account.balance() + Math.min(amount, availableSpace), level.maxBalance()));
        double charge = round(principalBalance - account.balance());
        if (charge <= 0.0) {
            return OperationResult.fail("deposit.bank-full");
        }

        if (!vaultHook.getEconomy().has(player, charge)) {
            return OperationResult.fail("deposit.not-enough-money");
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, charge);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("deposit.failed", "reason", String.valueOf(response.errorMessage));
        }

        Optional<BankDatabase.Change<Outcome>> saved = database.change(personal(player), current -> {
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
        });
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
    }

    public OperationResult withdraw(Player player, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }

        if (freshAccount(player).balance() < amount) {
            return OperationResult.fail("withdraw.not-enough-money");
        }

        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("withdraw.failed", "reason", String.valueOf(response.errorMessage));
        }

        double withdrawn = amount;
        Optional<BankDatabase.Change<Outcome>> saved = database.change(personal(player), current -> {
            if (current.balance() < withdrawn) {
                return BankDatabase.Change.none(Outcome.rejected("withdraw.not-enough-money"));
            }
            double newBalance = round(current.balance() - withdrawn);
            return BankDatabase.Change.write(current.withBalance(newBalance),
                    List.of(ledger(current, "WITHDRAW", withdrawn, newBalance, messages.get("notes.withdraw"))),
                    new Outcome(OperationResult.success("withdraw.success", "amount", money(withdrawn)), current.balance(), newBalance, 0.0));
        });
        if (saved.isEmpty() || !saved.get().written()) {
            // take back the cash just handed out, or the money is duplicated
            boolean clawedBack = compensateWithdraw(player, amount, "withdrawal");
            if (!clawedBack) {
                return OperationResult.fail("withdraw.needs-staff");
            }
            return saved.isEmpty() ? OperationResult.fail("withdraw.reverted") : saved.get().result().result();
        }
        Outcome outcome = saved.get().result();
        cacheIfOnline(saved.get().account());
        report(player.getUniqueId(), player.getName(), "withdrew", amount, outcome.before(), outcome.after(), false, null, null);
        return outcome.result();
    }

    /** Bank to bank, no Vault. Rejected outright if the full amount won't fit under the recipient's cap. */
    public OperationResult transfer(Player sender, OfflinePlayer recipient, double amount) {
        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            return OperationResult.fail("transfer.self");
        }
        // same playtime gate as RoyalTrade, or alt-account RMT just moves from trades to bank transfers
        double minHours = plugin.getConfig().getDouble("settings.transfer.min-playtime-hours", 0.0);
        if (minHours > 0) {
            String required = String.format(Locale.US, "%.0f", minHours);
            if (playtimeHours(sender) < minHours) {
                return OperationResult.fail("transfer.sender-playtime", "hours", required);
            }
            if (playtimeHours(recipient) < minHours) {
                String name = recipient.getName() != null ? recipient.getName() : messages.get("transfer.unknown-recipient");
                return OperationResult.fail("transfer.recipient-playtime", "player", name, "hours", required);
            }
        }
        double sent = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (sent < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }

        String senderName = sender.getName();
        Optional<BankDatabase.Change<Outcome>> saved = database.transfer(personal(sender), personal(recipient), (from, to) -> {
            if (from.balance() < sent) {
                return BankDatabase.Change.none(Outcome.rejected("transfer.not-enough-money"));
            }
            BankLevel recipientLevel = getEffectiveLevel(to.level());
            double recipientSpace = round(recipientLevel.maxBalance() - to.balance());
            if (sent > recipientSpace) {
                return BankDatabase.Change.none(Outcome.rejected("transfer.recipient-full", "player", to.username(),
                        "space", money(Math.max(0.0, recipientSpace))));
            }
            double senderNew = round(from.balance() - sent);
            double recipientNew = round(to.balance() + sent);
            List<BankDatabase.Ledger> rows = List.of(
                    ledger(from, "TRANSFER_OUT", sent, senderNew, messages.get("notes.transfer-out", "player", to.username())),
                    ledger(to, "TRANSFER_IN", sent, recipientNew, messages.get("notes.transfer-in", "player", senderName)));
            return new BankDatabase.Change<>(List.of(from.withBalance(senderNew), to.withBalance(recipientNew)), rows, List.of(),
                    new Outcome(OperationResult.success("transfer.success", "amount", money(sent), "player", to.username()),
                            from.balance(), to.balance(), 0.0));
        });
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
        report(sender.getUniqueId(), senderName, "transfer-sent", sent,
                change.result().before(), senderUpdated.balance(), false, recipient.getUniqueId(), recipientName);
        report(recipient.getUniqueId(), recipientName, "transfer-received", sent,
                change.result().after(), recipientUpdated.balance(), true, sender.getUniqueId(), senderName);

        return change.result().result();
    }

    @Override
    public BankSnapshot exportAccount(UUID playerId) {
        return database.getAccount(playerId)
                .map(a -> new BankSnapshot(a.balance(), a.level(), a.lastInterestClaim(), a.bonusClaimed()))
                .orElse(new BankSnapshot(0.0, levelManager.getStartingLevel(), 0L, false));
    }

    @Override
    public void importAccount(UUID playerId, BankSnapshot snapshot) {
        String name = database.getAccount(playerId).map(BankAccount::username).orElse(nameOf(playerId));
        BankAccount updated = new BankAccount(playerId, name, round(snapshot.balance()),
                snapshot.level(), snapshot.lastInterestClaim(), snapshot.bonusClaimed());
        if (database.saveAccount(updated)) {
            cacheIfOnline(updated);
        }
    }

    @Override
    public void resetAccount(UUID playerId) {
        importAccount(playerId, new BankSnapshot(0.0, levelManager.getStartingLevel(), 0L, false));
    }

    private String nameOf(UUID playerId) {
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name != null ? name : "Account";
    }

    // a fresh unsaved account at the starting level if none exists
    private BankAccount loadAccountById(UUID accountId, String label) {
        return database.getAccount(accountId).orElseGet(() ->
                new BankAccount(accountId, label == null ? "Account" : label, 0.0,
                        levelManager.getStartingLevel(), 0L, false));
    }

    @Override
    public double getAccountBalance(UUID accountId) {
        return round(database.getAccount(accountId).map(BankAccount::balance).orElse(0.0));
    }

    @Override
    public AccountView getAccountView(UUID accountId, String label) {
        BankAccount account = loadAccountById(accountId, label);
        BankLevel level = getEffectiveLevel(account.level());
        return new AccountView(round(account.balance()), account.level(), level.name(), level.maxBalance());
    }

    @Override
    public String accountDeposit(Player purse, UUID accountId, String label, double amount) {
        return apiResult(depositToAccount(purse, accountId, label, amount));
    }

    @Override
    public String accountWithdraw(Player purse, UUID accountId, String label, double amount) {
        return apiResult(withdrawFromAccount(purse, accountId, label, amount));
    }

    @Override
    public UpgradeView getUpgradeView(UUID accountId, String label) {
        BankAccount account = loadAccountById(accountId, label);
        BankLevel next = levelManager.getNextLevel(account.level()).orElse(null);
        if (next == null) {
            return new UpgradeView(true, account.level(), "", 0.0, "", getEffectiveLevel(account.level()).maxBalance());
        }
        return new UpgradeView(false, next.level(), next.name(), next.upgradeMoneyCost(),
                itemCostText(next.itemRequirements()), next.maxBalance());
    }

    @Override
    public String accountUpgrade(Player purse, UUID accountId, String label) {
        return apiResult(upgradeAccount(purse, accountId, label));
    }

    // the API contract: null on success, otherwise the rendered message without the prefix
    private String apiResult(OperationResult result) {
        return result.success() ? null : messages.render(result);
    }

    private OperationResult depositToAccount(Player purse, UUID accountId, String label, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }
        BankAccount account = loadAccountById(accountId, label);
        BankLevel level = getEffectiveLevel(account.level());
        double space = level.maxBalance() - account.balance();
        if (space <= 0) {
            return OperationResult.fail("account.deposit.bank-full");
        }
        double principal = round(Math.min(account.balance() + Math.min(amount, space), level.maxBalance()));
        double charge = round(principal - account.balance());
        if (charge <= 0.0) {
            return OperationResult.fail("account.deposit.bank-full");
        }
        if (!vaultHook.getEconomy().has(purse, charge)) {
            return OperationResult.fail("account.deposit.not-enough-money");
        }
        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(purse, charge);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("deposit.failed", "reason", String.valueOf(response.errorMessage));
        }
        Optional<BankDatabase.Change<Outcome>> saved = database.changeCoop(shared(accountId, label), (current, depositors, nextSeq) -> {
            double newBalance = round(current.balance() + charge);
            if (newBalance > getEffectiveLevel(current.level()).maxBalance()) {
                return BankDatabase.Change.none(Outcome.rejected("account.deposit.bank-full"));
            }
            CoopContribution contribution = new CoopContribution(accountId, purse.getUniqueId(), purse.getName(), charge, nextSeq);
            return new BankDatabase.Change<>(List.of(current.withBalance(newBalance)),
                    List.of(ledger(current, "COOP_DEPOSIT", charge, newBalance,
                            messages.get("notes.account-deposit", "player", purse.getName()))),
                    List.of(contribution),
                    new Outcome(OperationResult.silent(), current.balance(), newBalance, 0.0));
        });
        if (saved.isEmpty() || !saved.get().written()) {
            compensateDeposit(purse, charge, "account deposit");
            return saved.isEmpty() ? OperationResult.fail("account.deposit.refunded") : saved.get().result().result();
        }
        Outcome outcome = saved.get().result();
        report(purse.getUniqueId(), purse.getName(), "coop-deposited", charge, outcome.before(), outcome.after(), false, null, null);
        return outcome.result();
    }

    private record CoopWithdrawal(OperationResult result, double before, List<CoopContributions.Share> shares) {
    }

    private OperationResult withdrawFromAccount(Player purse, UUID accountId, String label, double amount) {
        double taken = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (taken < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }
        if (loadAccountById(accountId, label).balance() < taken) {
            return OperationResult.fail("account.withdraw.not-enough-money");
        }
        EconomyResponse response = vaultHook.getEconomy().depositPlayer(purse, taken);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("withdraw.failed", "reason", String.valueOf(response.errorMessage));
        }
        Optional<BankDatabase.Change<CoopWithdrawal>> saved = database.changeCoop(shared(accountId, label), (current, depositors, nextSeq) -> {
            if (current.balance() < taken) {
                return BankDatabase.Change.none(new CoopWithdrawal(OperationResult.fail("account.withdraw.not-enough-money"), 0.0, List.of()));
            }
            double newBalance = round(current.balance() - taken);
            List<CoopContributions.Share> shares = CoopContributions.shares(depositors, purse.getUniqueId(), taken);
            return new BankDatabase.Change<>(List.of(current.withBalance(newBalance)),
                    List.of(ledger(current, "COOP_WITHDRAW", taken, newBalance,
                            messages.get("notes.account-withdraw", "player", purse.getName()))),
                    CoopContributions.withdrawalChanges(accountId, shares),
                    new CoopWithdrawal(OperationResult.silent(), current.balance(), shares));
        });
        if (saved.isEmpty() || !saved.get().written()) {
            compensateWithdraw(purse, taken, "account withdrawal");
            return saved.isEmpty() ? OperationResult.fail("withdraw.reverted") : saved.get().result().result();
        }
        CoopWithdrawal withdrawal = saved.get().result();
        reportCoopWithdrawal(purse, withdrawal.shares(), withdrawal.before());
        return withdrawal.result();
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

    private OperationResult upgradeAccount(Player purse, UUID accountId, String label) {
        BankAccount account = loadAccountById(accountId, label);
        BankLevel next = levelManager.getNextLevel(account.level()).orElse(null);
        if (next == null) {
            return OperationResult.fail("account.upgrade.max-level");
        }
        if (!vaultHook.getEconomy().has(purse, next.upgradeMoneyCost())) {
            return OperationResult.fail("account.upgrade.not-enough-money", "cost", money(next.upgradeMoneyCost()));
        }
        List<String> missing = getMissingItems(purse, next.itemRequirements());
        if (!missing.isEmpty()) {
            return OperationResult.fail("upgrade.missing-items", "items", joinItems(missing));
        }
        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(purse, next.upgradeMoneyCost());
        if (!response.transactionSuccess()) {
            return OperationResult.fail("upgrade.failed", "reason", String.valueOf(response.errorMessage));
        }
        Optional<BankDatabase.Change<Outcome>> saved = database.change(shared(accountId, label), current ->
                levelUp(current, account.level(), next, "COOP_UPGRADE", "account.upgrade.max-level", OperationResult.silent()));
        if (saved.isEmpty() || !saved.get().written()) {
            compensateDeposit(purse, next.upgradeMoneyCost(), "account upgrade");
            return saved.isEmpty() ? OperationResult.fail("upgrade.refunded") : saved.get().result().result();
        }
        removeItems(purse, next.itemRequirements());
        return OperationResult.silent();
    }

    @Override
    public List<TransactionView> getAccountTransactions(UUID accountId, int limit) {
        List<TransactionView> out = new ArrayList<>();
        for (BankTransaction t : database.getRecentTransactions(accountId, limit)) {
            out.add(new TransactionView(t.type(), t.amount(), t.balanceAfter(), t.createdAt(), t.note()));
        }
        return out;
    }

    public OperationResult upgrade(Player player) {
        BankAccount account = freshAccount(player);
        BankLevel nextLevel = levelManager.getNextLevel(account.level()).orElse(null);
        if (nextLevel == null) {
            return OperationResult.fail("upgrade.max-level");
        }

        if (!upgradeUnlocked(player, account)) {
            return OperationResult.fail("upgrade.locked", "required", money(getUpgradeUnlockCombinedBalance()));
        }

        if (!vaultHook.getEconomy().has(player, nextLevel.upgradeMoneyCost())) {
            return OperationResult.fail("upgrade.not-enough-money", "cost", money(nextLevel.upgradeMoneyCost()));
        }

        List<String> missingItems = getMissingItems(player, nextLevel.itemRequirements());
        if (!missingItems.isEmpty()) {
            return OperationResult.fail("upgrade.missing-items", "items", joinItems(missingItems));
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, nextLevel.upgradeMoneyCost());
        if (!response.transactionSuccess()) {
            return OperationResult.fail("upgrade.failed", "reason", String.valueOf(response.errorMessage));
        }

        Optional<BankDatabase.Change<Outcome>> saved = database.change(personal(player), current ->
                levelUp(current, account.level(), nextLevel, "UPGRADE", "upgrade.max-level",
                        OperationResult.success("upgrade.success", "level_name", nextLevel.name())));
        if (saved.isEmpty() || !saved.get().written()) {
            // items have not been removed yet, so refunding the money is enough
            boolean refunded = compensateDeposit(player, nextLevel.upgradeMoneyCost(), "upgrade");
            if (!refunded) {
                return OperationResult.fail("upgrade.needs-staff");
            }
            return saved.isEmpty() ? OperationResult.fail("upgrade.refunded") : saved.get().result().result();
        }

        // consume items only after the level grant is committed
        removeItems(player, nextLevel.itemRequirements());
        cacheIfOnline(saved.get().account());
        return saved.get().result().result();
    }

    // payout: interest to hand to the purse through Vault before the claim can be written
    private record InterestClaim(OperationResult result, double before, double after, double paid, double payout) {
        static InterestClaim of(OperationResult result) {
            return new InterestClaim(result, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // what a claim does to this account; alreadyPaid is what the purse has already been given for it
    private BankDatabase.Change<InterestClaim> interestRule(BankAccount account, long now, boolean manual, boolean payToBank,
                                                           double alreadyPaid) {
        long cooldownSeconds = interestCooldownHours() * 3600L;
        long nextAllowed = account.lastInterestClaim() + cooldownSeconds;
        if (account.lastInterestClaim() > 0 && now < nextAllowed) {
            return BankDatabase.Change.none(InterestClaim.of(
                    OperationResult.fail("interest.cooldown", "time", formatDuration(nextAllowed - now))));
        }

        BankLevel level = getEffectiveLevel(account.level());
        double interest = round(nextInterest(account, level));

        if (interest <= 0) {
            if (alreadyPaid > 0.0) {
                return BankDatabase.Change.none(InterestClaim.of(OperationResult.fail("interest.reverted")));
            }
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
                            account.balance(), newBalance, actuallyPaid, 0.0));
        }

        if (Math.abs(alreadyPaid - interest) > 1e-9) {
            return BankDatabase.Change.none(new InterestClaim(OperationResult.fail("interest.reverted"), 0.0, 0.0, 0.0, interest));
        }
        return BankDatabase.Change.write(account.withInterestClaimed(now),
                List.of(ledger(account, "INTEREST", interest, account.balance(), messages.get("notes.interest-to-purse"))),
                new InterestClaim(OperationResult.success("interest.paid-to-purse", "amount", money(interest)),
                        account.balance(), account.balance(), interest, 0.0));
    }

    public OperationResult claimInterest(Player player, boolean manual) {
        if (manual && !plugin.getConfig().getBoolean("settings.interest-command-enabled", true)) {
            return OperationResult.fail("interest.disabled");
        }

        long now = Instant.now().getEpochSecond();
        boolean payToBank = plugin.getConfig().getBoolean("settings.pay-interest-to-bank", true);
        BankDatabase.Change<InterestClaim> plan = interestRule(freshAccount(player), now, manual, payToBank, 0.0);
        double payout = plan.result().payout();
        if (!plan.written() && payout <= 0.0) {
            return plan.result().result();
        }

        if (payout > 0.0) {
            EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, payout);
            if (!response.transactionSuccess()) {
                return OperationResult.fail("interest.failed", "reason", String.valueOf(response.errorMessage));
            }
        }
        Optional<BankDatabase.Change<InterestClaim>> saved = database.change(personal(player),
                current -> interestRule(current, now, manual, payToBank, payout));
        if (saved.isEmpty() || !saved.get().written()) {
            if (payout <= 0.0) {
                return saved.isEmpty() || saved.get().result().payout() > 0.0
                        ? OperationResult.fail("interest.not-saved")
                        : saved.get().result().result();
            }
            // or the next claim pays the interest twice
            boolean clawedBack = compensateWithdraw(player, payout, "interest payout");
            if (!clawedBack) {
                return OperationResult.fail("interest.needs-staff");
            }
            return saved.isEmpty() ? OperationResult.fail("interest.reverted") : saved.get().result().result();
        }
        InterestClaim claim = saved.get().result();
        cacheIfOnline(saved.get().account());
        if (claim.paid() > 0.0) {
            // incoming: compounding on a parked balance is part of the parking signal
            report(player.getUniqueId(), player.getName(), payToBank ? "interest" : "interest-to-purse", claim.paid(),
                    claim.before(), claim.after(), true, null, null);
        }
        return claim.result();
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

    private OperationResult adminChange(OfflinePlayer player, OperationResult notSaved, BankDatabase.Rule<OperationResult> rule) {
        Optional<BankDatabase.Change<OperationResult>> saved = database.change(personal(player), rule);
        if (saved.isEmpty()) {
            return notSaved;
        }
        if (saved.get().written()) {
            cacheIfOnline(saved.get().account());
        }
        return saved.get().result();
    }

    public OperationResult setBalance(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0.0) {
            return OperationResult.fail("admin.result.invalid-amount");
        }
        double sanitized = Amounts.roundNonNegative(plugin, amount);
        double maxTransaction = plugin.getConfig().getDouble("settings.amount-limits.max-transaction", 1_000_000_000_000.0);
        if (maxTransaction > 0.0 && sanitized > maxTransaction) {
            return OperationResult.fail("admin.result.above-limit");
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

    public OperationResult addBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return OperationResult.fail("admin.result.invalid-amount");
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

    public OperationResult removeBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return OperationResult.fail("admin.result.invalid-amount");
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

    public OperationResult setLevel(OfflinePlayer player, int level) {
        BankLevel bankLevel = levelManager.getLevel(level).orElse(null);
        if (bankLevel == null) {
            return OperationResult.fail("admin.result.unknown-level", "level", String.valueOf(level));
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

    public OperationResult resetAccount(OfflinePlayer player) {
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

    public List<BankTransaction> getRecentTransactions(Player player, int limit) {
        return database.getRecentTransactions(player.getUniqueId(), limit);
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
