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
    private final CoopContributions coopContributions = new CoopContributions();

    // online players only; every mutation writes the DB first, then this cache, and reads never hit the DB
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

    private void cacheIfOnline(BankAccount account) {
        if (Bukkit.getPlayer(account.uuid()) != null) {
            cache.put(account.uuid(), account);
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

        BankAccount account = getAccount(player);
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

        boolean bonusEligible = !account.bonusClaimed() && plugin.getConfig().getBoolean("settings.first-deposit-bonus.enabled", true);
        double bonusAmount = bonusEligible ? Math.max(0.0, plugin.getConfig().getDouble("settings.first-deposit-bonus.amount", 10.0)) : 0.0;

        if (!vaultHook.getEconomy().has(player, charge)) {
            return OperationResult.fail("deposit.not-enough-money");
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, charge);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("deposit.failed", "reason", String.valueOf(response.errorMessage));
        }

        double newBalance = round(Math.min(principalBalance + bonusAmount, level.maxBalance()));
        double paidBonus = round(Math.max(0.0, newBalance - principalBalance));

        BankAccount updated = account.withBalance(newBalance);
        if (paidBonus > 0.0) {
            updated = updated.withBonusClaimed(true);
        }

        if (!database.saveAccountWithTransaction(updated, "DEPOSIT", charge, principalBalance, messages.get("notes.deposit"))) {
            boolean refunded = compensateDeposit(player, charge, "deposit");
            return OperationResult.fail(refunded ? "deposit.refunded" : "deposit.needs-staff");
        }
        cacheIfOnline(updated);
        // principal and bonus as separate audit events, so each balance-after stays consistent.
        // The principal is the player's own purse money, not new wealth, so it is not incoming.
        report(player.getUniqueId(), player.getName(), "deposited", charge, account.balance(), principalBalance, false, null, null);

        if (paidBonus > 0.0) {
            database.addTransaction(player.getUniqueId(), "BONUS", paidBonus, newBalance, messages.get("notes.bonus"));
            report(player.getUniqueId(), player.getName(), "bonus", paidBonus, principalBalance, newBalance, true, null, null);
            return OperationResult.success("deposit.success-bonus", "amount", money(charge), "bonus", money(paidBonus));
        }
        return OperationResult.success("deposit.success", "amount", money(charge));
    }

    public OperationResult withdraw(Player player, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }

        BankAccount account = getAccount(player);
        if (account.balance() < amount) {
            return OperationResult.fail("withdraw.not-enough-money");
        }

        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("withdraw.failed", "reason", String.valueOf(response.errorMessage));
        }

        double newBalance = round(account.balance() - amount);
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "WITHDRAW", amount, newBalance, messages.get("notes.withdraw"))) {
            // take back the cash just handed out, or the money is duplicated
            boolean clawedBack = compensateWithdraw(player, amount, "withdrawal");
            return OperationResult.fail(clawedBack ? "withdraw.reverted" : "withdraw.needs-staff");
        }
        cacheIfOnline(updated);
        report(player.getUniqueId(), player.getName(), "withdrew", amount, account.balance(), newBalance, false, null, null);
        return OperationResult.success("withdraw.success", "amount", money(amount));
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
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }

        BankAccount senderAccount = getAccount(sender);
        if (senderAccount.balance() < amount) {
            return OperationResult.fail("transfer.not-enough-money");
        }

        BankAccount recipientAccount = database.getOrCreateAccount(recipient, levelManager.getStartingLevel());
        BankLevel recipientLevel = getEffectiveLevel(recipientAccount.level());
        double recipientSpace = round(recipientLevel.maxBalance() - recipientAccount.balance());
        if (amount > recipientSpace) {
            return OperationResult.fail("transfer.recipient-full", "player", recipientAccount.username(),
                    "space", money(Math.max(0.0, recipientSpace)));
        }

        double senderNew = round(senderAccount.balance() - amount);
        double recipientNew = round(recipientAccount.balance() + amount);
        BankAccount senderUpdated = senderAccount.withBalance(senderNew);
        BankAccount recipientUpdated = recipientAccount.withBalance(recipientNew);

        String senderName = sender.getName();
        String recipientName = recipientAccount.username();
        if (!database.saveTransfer(senderUpdated, recipientUpdated, amount,
                "TRANSFER_OUT", messages.get("notes.transfer-out", "player", recipientName),
                "TRANSFER_IN", messages.get("notes.transfer-in", "player", senderName))) {
            return OperationResult.fail("transfer.not-saved");
        }
        cacheIfOnline(senderUpdated);
        cacheIfOnline(recipientUpdated);

        // both ends carry the counterparty so EconGuard can link the two players
        report(sender.getUniqueId(), senderName, "transfer-sent", amount,
                senderAccount.balance(), senderNew, false, recipient.getUniqueId(), recipientName);
        report(recipient.getUniqueId(), recipientName, "transfer-received", amount,
                recipientAccount.balance(), recipientNew, true, sender.getUniqueId(), senderName);

        return OperationResult.success("transfer.success", "amount", money(amount), "player", recipientName);
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
        database.saveAccount(updated);
        cacheIfOnline(updated);
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
        if (!database.saveAccountWithTransaction(account.withBalance(principal), "COOP_DEPOSIT", charge, principal,
                messages.get("notes.account-deposit", "player", purse.getName()))) {
            compensateDeposit(purse, charge, "account deposit");
            return OperationResult.fail("account.deposit.refunded");
        }
        coopContributions.deposited(accountId, purse.getUniqueId(), purse.getName(), charge);
        report(purse.getUniqueId(), purse.getName(), "coop-deposited", charge, account.balance(), principal, false, null, null);
        return OperationResult.silent();
    }

    private OperationResult withdrawFromAccount(Player purse, UUID accountId, String label, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("amount.too-small", "minimum", money(minimum));
        }
        BankAccount account = loadAccountById(accountId, label);
        if (account.balance() < amount) {
            return OperationResult.fail("account.withdraw.not-enough-money");
        }
        EconomyResponse response = vaultHook.getEconomy().depositPlayer(purse, amount);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("withdraw.failed", "reason", String.valueOf(response.errorMessage));
        }
        double newBalance = round(account.balance() - amount);
        if (!database.saveAccountWithTransaction(account.withBalance(newBalance), "COOP_WITHDRAW", amount, newBalance,
                messages.get("notes.account-withdraw", "player", purse.getName()))) {
            compensateWithdraw(purse, amount, "account withdrawal");
            return OperationResult.fail("withdraw.reverted");
        }
        reportCoopWithdrawal(purse, accountId, amount, account.balance());
        return OperationResult.silent();
    }

    // Money a member paid in and takes back is an internal move. Money another member paid in is
    // incoming from that member, and money with no known depositor is incoming with no counterparty.
    private void reportCoopWithdrawal(Player purse, UUID accountId, double amount, double oldBalance) {
        double balance = oldBalance;
        for (CoopContributions.Share share : coopContributions.withdrawn(accountId, purse.getUniqueId(), amount)) {
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
        BankAccount updated = account.withLevel(next.level());
        if (!database.saveAccountWithTransaction(updated, "COOP_UPGRADE", next.upgradeMoneyCost(), updated.balance(),
                messages.get("notes.upgrade", "level_name", next.name()))) {
            compensateDeposit(purse, next.upgradeMoneyCost(), "account upgrade");
            return OperationResult.fail("upgrade.refunded");
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
        BankAccount account = getAccount(player);
        BankLevel nextLevel = levelManager.getNextLevel(account.level()).orElse(null);
        if (nextLevel == null) {
            return OperationResult.fail("upgrade.max-level");
        }

        if (!hasUpgradeUnlocked(player)) {
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

        BankAccount updated = account.withLevel(nextLevel.level());
        if (!database.saveAccountWithTransaction(updated, "UPGRADE", nextLevel.upgradeMoneyCost(), updated.balance(),
                messages.get("notes.upgrade", "level_name", nextLevel.name()))) {
            // items have not been removed yet, so refunding the money is enough
            boolean refunded = compensateDeposit(player, nextLevel.upgradeMoneyCost(), "upgrade");
            return OperationResult.fail(refunded ? "upgrade.refunded" : "upgrade.needs-staff");
        }

        // consume items only after the level grant is committed
        removeItems(player, nextLevel.itemRequirements());
        cacheIfOnline(updated);
        return OperationResult.success("upgrade.success", "level_name", nextLevel.name());
    }

    public OperationResult claimInterest(Player player, boolean manual) {
        if (manual && !plugin.getConfig().getBoolean("settings.interest-command-enabled", true)) {
            return OperationResult.fail("interest.disabled");
        }

        BankAccount account = getAccount(player);
        long now = Instant.now().getEpochSecond();
        long cooldownSeconds = interestCooldownHours() * 3600L;
        long nextAllowed = account.lastInterestClaim() + cooldownSeconds;
        if (account.lastInterestClaim() > 0 && now < nextAllowed) {
            long remaining = nextAllowed - now;
            return OperationResult.fail("interest.cooldown", "time", formatDuration(remaining));
        }

        BankLevel level = getEffectiveLevel(account.level());
        double interest = round(calculateInterest(account.balance(), level));

        // keep the cooldown when nothing is paid, so an auto-claim at join or a no-op claim costs nothing
        if (interest <= 0) {
            return manual ? OperationResult.success("interest.nothing-earned") : OperationResult.silent();
        }

        boolean payToBank = plugin.getConfig().getBoolean("settings.pay-interest-to-bank", true);
        if (payToBank) {
            double newBalance = round(Math.min(account.balance() + interest, level.maxBalance()));
            double actuallyPaid = round(newBalance - account.balance());
            if (actuallyPaid <= 0) {
                return manual ? OperationResult.success("interest.bank-full") : OperationResult.silent();
            }
            BankAccount updated = account.withBalance(newBalance).withLastInterestClaim(now);
            if (!database.saveAccountWithTransaction(updated, "INTEREST", actuallyPaid, newBalance, messages.get("notes.interest"))) {
                return OperationResult.fail("interest.not-saved");
            }
            cacheIfOnline(updated);
            // incoming: compounding on a parked balance is part of the parking signal
            report(player.getUniqueId(), player.getName(), "interest", actuallyPaid, account.balance(), newBalance, true, null, null);
            return OperationResult.success("interest.paid-to-bank", "amount", money(actuallyPaid));
        }

        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, interest);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("interest.failed", "reason", String.valueOf(response.errorMessage));
        }
        BankAccount updated = account.withLastInterestClaim(now);
        if (!database.saveAccountWithTransaction(updated, "INTEREST", interest, account.balance(), messages.get("notes.interest-to-purse"))) {
            // or the next claim pays the interest twice
            boolean clawedBack = compensateWithdraw(player, interest, "interest payout");
            return OperationResult.fail(clawedBack ? "interest.reverted" : "interest.needs-staff");
        }
        cacheIfOnline(updated);
        report(player.getUniqueId(), player.getName(), "interest-to-purse", interest, account.balance(), account.balance(), true, null, null);
        return OperationResult.success("interest.paid-to-purse", "amount", money(interest));
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

    public OperationResult setBalance(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0.0) {
            return OperationResult.fail("admin.result.invalid-amount");
        }
        double sanitized = Amounts.roundNonNegative(plugin, amount);
        double maxTransaction = plugin.getConfig().getDouble("settings.amount-limits.max-transaction", 1_000_000_000_000.0);
        if (maxTransaction > 0.0 && sanitized > maxTransaction) {
            return OperationResult.fail("admin.result.above-limit");
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        BankLevel level = getEffectiveLevel(account.level());
        double newBalance = round(Math.min(sanitized, level.maxBalance()));
        double discarded = round(sanitized - newBalance);
        String note = discarded > 0
                ? messages.get("notes.admin-set-balance-capped", "discarded", money(discarded))
                : messages.get("notes.admin-set-balance");
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_SET", newBalance, newBalance, note)) {
            return OperationResult.fail("admin.result.balance-not-saved");
        }
        cacheIfOnline(updated);
        return OperationResult.success(discarded > 0 ? "admin.result.set-balance-capped" : "admin.result.set-balance",
                "player", updated.username(), "balance", money(newBalance), "discarded", money(discarded));
    }

    public OperationResult addBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return OperationResult.fail("admin.result.invalid-amount");
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        BankLevel level = getEffectiveLevel(account.level());
        double newBalance = round(Math.min(account.balance() + sanitized, level.maxBalance()));
        double added = round(newBalance - account.balance());
        double discarded = round(sanitized - added);
        String note = discarded > 0
                ? messages.get("notes.admin-add-balance-capped", "discarded", money(discarded))
                : messages.get("notes.admin-add-balance");
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_ADD", added, newBalance, note)) {
            return OperationResult.fail("admin.result.balance-not-saved");
        }
        cacheIfOnline(updated);
        return OperationResult.success(discarded > 0 ? "admin.result.add-balance-capped" : "admin.result.add-balance",
                "player", updated.username(), "amount", money(added), "discarded", money(discarded));
    }

    public OperationResult removeBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return OperationResult.fail("admin.result.invalid-amount");
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        double newBalance = round(Math.max(0.0, account.balance() - sanitized));
        double removed = round(account.balance() - newBalance);
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_REMOVE", removed, newBalance, messages.get("notes.admin-remove-balance"))) {
            return OperationResult.fail("admin.result.balance-not-saved");
        }
        cacheIfOnline(updated);
        return OperationResult.success("admin.result.remove-balance", "player", updated.username(), "amount", money(removed));
    }

    public OperationResult setLevel(OfflinePlayer player, int level) {
        BankLevel bankLevel = levelManager.getLevel(level).orElse(null);
        if (bankLevel == null) {
            return OperationResult.fail("admin.result.unknown-level", "level", String.valueOf(level));
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        double clampedBalance = round(Math.min(account.balance(), bankLevel.maxBalance()));
        double discarded = round(account.balance() - clampedBalance);
        String note = discarded > 0
                ? messages.get("notes.admin-set-level-capped", "level_name", bankLevel.name(), "discarded", money(discarded))
                : messages.get("notes.admin-set-level", "level_name", bankLevel.name());
        BankAccount updated = new BankAccount(account.uuid(), account.username(), clampedBalance, level, account.lastInterestClaim(), account.bonusClaimed());
        if (!database.saveAccountWithTransaction(updated, "ADMIN_LEVEL", discarded, clampedBalance, note)) {
            return OperationResult.fail("admin.result.level-not-saved");
        }
        cacheIfOnline(updated);
        return OperationResult.success(discarded > 0 ? "admin.result.set-level-capped" : "admin.result.set-level",
                "player", updated.username(), "level", String.valueOf(bankLevel.level()),
                "level_name", bankLevel.name(), "discarded", money(discarded));
    }

    public OperationResult resetAccount(OfflinePlayer player) {
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        BankAccount updated = new BankAccount(account.uuid(), account.username(), 0.0, levelManager.getStartingLevel(), 0L, false);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_RESET", 0.0, 0.0, messages.get("notes.admin-reset"))) {
            return OperationResult.fail("admin.result.reset-not-saved");
        }
        cacheIfOnline(updated);
        return OperationResult.success("admin.result.reset", "player", updated.username());
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
                "next_interest", money(calculateInterest(account.balance(), level)));
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
        BankAccount account = getAccount(player);
        return account.level() > levelManager.getStartingLevel() || getCombinedBalance(player) >= getUpgradeUnlockCombinedBalance();
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
