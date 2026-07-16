package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.ItemRequirement;
import com.mystipixel.royalbank.config.InterestTranche;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.api.RoyalBankAPI;
import com.mystipixel.royalbank.config.RequirementType;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.hooks.VaultHook;
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

public final class BankService implements Listener, RoyalBankAPI {
    private static final long DEFAULT_INTEREST_COOLDOWN_HOURS = 31L;

    private final JavaPlugin plugin;
    private final BankDatabase database;
    private final LevelManager levelManager;
    private final VaultHook vaultHook;
    private final AbuseMonitor abuseMonitor;
    private AuditSink auditSink;

    // Authoritative in-memory copy for online players. Write-through: every mutation persists to the
    // DB first, then refreshes this cache. Read paths (GUI rendering, placeholders) never touch the DB.
    private final Map<UUID, BankAccount> cache = new ConcurrentHashMap<>();

    public BankService(JavaPlugin plugin, BankDatabase database, LevelManager levelManager, VaultHook vaultHook) {
        this.plugin = plugin;
        this.database = database;
        this.levelManager = levelManager;
        this.vaultHook = vaultHook;
        this.abuseMonitor = new AbuseMonitor(plugin);
        this.auditSink = new LocalAuditSink(abuseMonitor);
    }

    public AbuseMonitor getAbuseMonitor() {
        return abuseMonitor;
    }

    /** Swap the audit destination (e.g. to EconGuard when present). */
    public void setAuditSink(AuditSink auditSink) {
        this.auditSink = auditSink;
    }

    /** True when anti-abuse is handled by an external core (EconGuard) rather than the built-in monitor. */
    public boolean usesExternalAudit() {
        return !(auditSink instanceof LocalAuditSink);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadAccount(event.getPlayer());
        if (plugin.getConfig().getBoolean("settings.interest-on-join", true)) {
            // Result intentionally ignored: cooldown/empty notices are not pushed at join.
            claimInterest(event.getPlayer(), false);
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
        // Deferred a tick so it lands after join messages and once permissions have settled.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.hasPermission("royalbank.alerts") && abuseMonitor.hasFlags()) {
                player.sendMessage(Text.color("&c[RoyalBank] &e" + abuseMonitor.getFlags().size()
                        + " anti-abuse flag(s) pending review. &7Use &f/bank admin flags"));
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
        // Only cache for a player still online, so a load racing a quit cannot resurrect an evicted entry.
        if (player.isOnline()) {
            cache.put(player.getUniqueId(), loaded);
        }
        return loaded;
    }

    /** Cache-only lookup with no database fallback. Safe to call from any thread (e.g. async placeholders). */
    public java.util.Optional<BankAccount> getCachedAccount(UUID uuid) {
        return java.util.Optional.ofNullable(cache.get(uuid));
    }

    private void cacheIfOnline(BankAccount account) {
        if (Bukkit.getPlayer(account.uuid()) != null) {
            cache.put(account.uuid(), account);
        }
    }

    /** Re-deposits money to a player after a failed DB write, logging loudly if the refund itself fails. */
    private boolean compensateDeposit(Player player, double amount, String operation) {
        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().severe("RoyalBank: FAILED to refund " + money(amount) + " to " + player.getName()
                    + " (" + player.getUniqueId() + ") after a failed " + operation + " save. MANUAL RECONCILIATION NEEDED. Vault: " + response.errorMessage);
            return false;
        }
        return true;
    }

    /** Claws back money from a player after a failed DB write, logging loudly if the clawback itself fails. */
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
            return OperationResult.fail("&cAmount must be at least " + money(minimum) + ".");
        }

        BankAccount account = getAccount(player);
        BankLevel level = getEffectiveLevel(account.level());
        double availableSpace = level.maxBalance() - account.balance();
        if (availableSpace <= 0) {
            return OperationResult.fail("&cYour bank is already full. Upgrade it to store more money.");
        }

        // The principal that actually lands in the bank, and the exact amount to debit from the purse,
        // are both derived from the rounded post-deposit balance so the wallet debit and bank credit
        // always match to the unit (no sub-unit creation/destruction, even with odd max-balance precision).
        double principalBalance = round(Math.min(account.balance() + Math.min(amount, availableSpace), level.maxBalance()));
        double charge = round(principalBalance - account.balance());
        if (charge <= 0.0) {
            return OperationResult.fail("&cYour bank is already full. Upgrade it to store more money.");
        }

        boolean bonusEligible = !account.bonusClaimed() && plugin.getConfig().getBoolean("settings.first-deposit-bonus.enabled", true);
        double bonusAmount = bonusEligible ? Math.max(0.0, plugin.getConfig().getDouble("settings.first-deposit-bonus.amount", 10.0)) : 0.0;

        if (!vaultHook.getEconomy().has(player, charge)) {
            return OperationResult.fail("&cYou do not have enough money to deposit that amount.");
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, charge);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("&cDeposit failed: " + response.errorMessage);
        }

        double newBalance = round(Math.min(principalBalance + bonusAmount, level.maxBalance()));
        double paidBonus = round(Math.max(0.0, newBalance - principalBalance));

        BankAccount updated = account.withBalance(newBalance);
        if (paidBonus > 0.0) {
            updated = updated.withBonusClaimed(true);
        }

        if (!database.saveAccountWithTransaction(updated, "DEPOSIT", charge, principalBalance, "Purse to bank")) {
            boolean refunded = compensateDeposit(player, charge, "deposit");
            return OperationResult.fail(refunded
                    ? "&cDeposit could not be saved. Your money was refunded; please try again."
                    : "&cDeposit could not be saved and your balance needs staff attention. Please contact an admin.");
        }
        cacheIfOnline(updated);
        // Report the principal deposit (incoming=true so parking detection sees it) and any bonus as
        // separate audit events, so the ledger's balance-after stays internally consistent.
        auditSink.record(player.getUniqueId(), player.getName(), "deposited", charge, account.balance(), principalBalance, true, null, null);

        if (paidBonus > 0.0) {
            database.addTransaction(player.getUniqueId(), "BONUS", paidBonus, newBalance, "First deposit bonus");
            auditSink.record(player.getUniqueId(), player.getName(), "bonus", paidBonus, principalBalance, newBalance, true, null, null);
            return OperationResult.success("&aDeposited " + money(charge) + " &aand received a first deposit bonus of &e" + money(paidBonus) + "&a!");
        }
        return OperationResult.success("&aDeposited " + money(charge) + " &ainto your bank.");
    }

    public OperationResult withdraw(Player player, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("&cAmount must be at least " + money(minimum) + ".");
        }

        BankAccount account = getAccount(player);
        if (account.balance() < amount) {
            return OperationResult.fail("&cYour bank does not have that much money.");
        }

        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("&cWithdraw failed: " + response.errorMessage);
        }

        double newBalance = round(account.balance() - amount);
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "WITHDRAW", amount, newBalance, "Bank to purse")) {
            // DB write failed: take back the cash we just handed out so money is not duplicated.
            boolean clawedBack = compensateWithdraw(player, amount, "withdrawal");
            return OperationResult.fail(clawedBack
                    ? "&cWithdrawal could not be saved and was reverted; please try again."
                    : "&cWithdrawal could not be saved and your balance needs staff attention. Please contact an admin.");
        }
        cacheIfOnline(updated);
        // Withdrawals shrink the bank balance (incoming=false): audit + large-transaction alert only.
        auditSink.record(player.getUniqueId(), player.getName(), "withdrew", amount, account.balance(), newBalance, false, null, null);
        return OperationResult.success("&aWithdrew " + money(amount) + " &afrom your bank.");
    }

    /**
     * Moves money bank-to-bank from an online sender to another player (online or offline). No Vault
     * involvement: the total in the bank system is conserved. Emits two audit events carrying the
     * counterparty on each end, so EconGuard can link the two players for RMT / collusion detection.
     * If the full amount will not fit under the recipient's level cap, the whole transfer is rejected.
     */
    public OperationResult transfer(Player sender, OfflinePlayer recipient, double amount) {
        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            return OperationResult.fail("&cYou cannot transfer money to yourself.");
        }
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return OperationResult.fail("&cAmount must be at least " + money(minimum) + ".");
        }

        BankAccount senderAccount = getAccount(sender);
        if (senderAccount.balance() < amount) {
            return OperationResult.fail("&cYour bank does not have that much money to transfer.");
        }

        BankAccount recipientAccount = database.getOrCreateAccount(recipient, levelManager.getStartingLevel());
        BankLevel recipientLevel = getEffectiveLevel(recipientAccount.level());
        double recipientSpace = round(recipientLevel.maxBalance() - recipientAccount.balance());
        if (amount > recipientSpace) {
            return OperationResult.fail("&c" + recipientAccount.username() + "'s bank cannot hold that much - they only have "
                    + money(Math.max(0.0, recipientSpace)) + " of space.");
        }

        double senderNew = round(senderAccount.balance() - amount);
        double recipientNew = round(recipientAccount.balance() + amount);
        BankAccount senderUpdated = senderAccount.withBalance(senderNew);
        BankAccount recipientUpdated = recipientAccount.withBalance(recipientNew);

        String senderName = sender.getName();
        String recipientName = recipientAccount.username();
        if (!database.saveTransfer(senderUpdated, recipientUpdated, amount,
                "TRANSFER_OUT", "Transfer to " + recipientName,
                "TRANSFER_IN", "Transfer from " + senderName)) {
            return OperationResult.fail("&cThe transfer could not be saved; no money was moved. Please try again.");
        }
        cacheIfOnline(senderUpdated);
        cacheIfOnline(recipientUpdated);

        // Report both ends with the counterparty set. The recipient's incoming event is what drives
        // EconGuard's young-incoming / collusion signals; the sender's is audited too (and flagged as
        // the other end of any collusion ring).
        auditSink.record(sender.getUniqueId(), senderName, "transfer-sent", amount,
                senderAccount.balance(), senderNew, false, recipient.getUniqueId(), recipientName);
        auditSink.record(recipient.getUniqueId(), recipientName, "transfer-received", amount,
                recipientAccount.balance(), recipientNew, true, sender.getUniqueId(), senderName);

        return OperationResult.success("&aTransferred " + money(amount) + " &ato " + recipientName + "&a's bank.");
    }

    // ── shared accounts (RoyalBankAPI) ───────────────────────────────────────────

    private double sharedMaxBalance() {
        return plugin.getConfig().getDouble("shared-accounts.max-balance", -1.0);
    }

    @Override
    public double getSharedBalance(UUID accountId) {
        return round(database.getSharedBalance(accountId));
    }

    @Override
    public String sharedDeposit(Player from, UUID accountId, String label, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return "&cAmount must be at least " + money(minimum) + ".";
        }
        double balance = database.getSharedBalance(accountId);
        double cap = sharedMaxBalance();
        double charge;
        if (cap >= 0.0) {
            double space = round(cap - balance);
            if (space <= 0.0) {
                return "&cThe coop bank is full.";
            }
            charge = round(Math.min(amount, space));
        } else {
            charge = round(amount);
        }
        if (charge <= 0.0) {
            return "&cThe coop bank is full.";
        }
        if (!vaultHook.getEconomy().has(from, charge)) {
            return "&cYou don't have enough money to deposit that.";
        }
        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(from, charge);
        if (!response.transactionSuccess()) {
            return "&cDeposit failed: " + response.errorMessage;
        }
        double newBalance = round(balance + charge);
        if (!database.saveSharedWithTransaction(accountId, label, newBalance, "COOP_DEPOSIT", charge,
                from.getName() + " deposited")) {
            compensateDeposit(from, charge, "coop deposit");
            return "&cDeposit could not be saved; your money was refunded.";
        }
        return null;
    }

    @Override
    public String sharedWithdraw(Player to, UUID accountId, String label, double amount) {
        amount = Amounts.sanitize(plugin, amount);
        double minimum = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (amount < minimum) {
            return "&cAmount must be at least " + money(minimum) + ".";
        }
        double balance = database.getSharedBalance(accountId);
        if (balance < amount) {
            return "&cThe coop bank doesn't have that much money.";
        }
        EconomyResponse response = vaultHook.getEconomy().depositPlayer(to, amount);
        if (!response.transactionSuccess()) {
            return "&cWithdraw failed: " + response.errorMessage;
        }
        double newBalance = round(balance - amount);
        if (!database.saveSharedWithTransaction(accountId, label, newBalance, "COOP_WITHDRAW", amount,
                to.getName() + " withdrew")) {
            compensateWithdraw(to, amount, "coop withdrawal");
            return "&cWithdrawal could not be saved and was reverted; please try again.";
        }
        return null;
    }

    public OperationResult upgrade(Player player) {
        BankAccount account = getAccount(player);
        BankLevel nextLevel = levelManager.getNextLevel(account.level()).orElse(null);
        if (nextLevel == null) {
            return OperationResult.fail("&eYour bank is already at the maximum level.");
        }

        if (!hasUpgradeUnlocked(player)) {
            return OperationResult.fail("&cBank upgrades unlock once your purse and bank have at least " + money(getUpgradeUnlockCombinedBalance()) + " &ccombined.");
        }

        if (!vaultHook.getEconomy().has(player, nextLevel.upgradeMoneyCost())) {
            return OperationResult.fail("&cYou need " + money(nextLevel.upgradeMoneyCost()) + " &cto upgrade.");
        }

        List<String> missingItems = getMissingItems(player, nextLevel.itemRequirements());
        if (!missingItems.isEmpty()) {
            return OperationResult.fail("&cMissing upgrade items: &f" + String.join(", ", missingItems));
        }

        EconomyResponse response = vaultHook.getEconomy().withdrawPlayer(player, nextLevel.upgradeMoneyCost());
        if (!response.transactionSuccess()) {
            return OperationResult.fail("&cUpgrade payment failed: " + response.errorMessage);
        }

        BankAccount updated = account.withLevel(nextLevel.level());
        if (!database.saveAccountWithTransaction(updated, "UPGRADE", nextLevel.upgradeMoneyCost(), updated.balance(), "Upgraded to " + nextLevel.name())) {
            // Refund money; items have NOT been removed yet, so the player loses nothing.
            boolean refunded = compensateDeposit(player, nextLevel.upgradeMoneyCost(), "upgrade");
            return OperationResult.fail(refunded
                    ? "&cUpgrade could not be saved. Your money was refunded; please try again."
                    : "&cUpgrade could not be saved and your payment needs staff attention. Please contact an admin.");
        }

        // Items are consumed only after the level grant is durably committed.
        removeItems(player, nextLevel.itemRequirements());
        cacheIfOnline(updated);
        return OperationResult.success("&aYour bank was upgraded to &e" + nextLevel.name() + "&a!");
    }

    public OperationResult claimInterest(Player player, boolean manual) {
        if (manual && !plugin.getConfig().getBoolean("settings.interest-command-enabled", true)) {
            return OperationResult.fail("&cManual interest claiming is disabled.");
        }

        BankAccount account = getAccount(player);
        long now = Instant.now().getEpochSecond();
        long cooldownSeconds = interestCooldownHours() * 3600L;
        long nextAllowed = account.lastInterestClaim() + cooldownSeconds;
        if (account.lastInterestClaim() > 0 && now < nextAllowed) {
            long remaining = nextAllowed - now;
            return OperationResult.fail("&eYou can claim interest again in " + formatDuration(remaining) + ".");
        }

        BankLevel level = getEffectiveLevel(account.level());
        double interest = round(calculateInterest(account.balance(), level));

        // Do NOT consume the cooldown when nothing is actually paid (empty/full bank), so players
        // are not penalised by an auto-claim at join or a no-op manual claim.
        if (interest <= 0) {
            return OperationResult.success(manual ? "&eNo interest was earned because your bank balance is empty." : "");
        }

        boolean payToBank = plugin.getConfig().getBoolean("settings.pay-interest-to-bank", true);
        if (payToBank) {
            double newBalance = round(Math.min(account.balance() + interest, level.maxBalance()));
            double actuallyPaid = round(newBalance - account.balance());
            if (actuallyPaid <= 0) {
                return OperationResult.success(manual ? "&eYour bank is full, so no interest could be deposited." : "");
            }
            BankAccount updated = account.withBalance(newBalance).withLastInterestClaim(now);
            if (!database.saveAccountWithTransaction(updated, "INTEREST", actuallyPaid, newBalance, "Daily interest")) {
                return OperationResult.fail("&cInterest could not be saved; please try again.");
            }
            cacheIfOnline(updated);
            // Interest grows the bank balance (incoming=true): compounding on a parked balance is part
            // of the same parking signal, and it keeps the audit ledger complete.
            auditSink.record(player.getUniqueId(), player.getName(), "interest", actuallyPaid, account.balance(), newBalance, true, null, null);
            return OperationResult.success("&aDaily interest paid to your bank: &e" + money(actuallyPaid));
        }

        EconomyResponse response = vaultHook.getEconomy().depositPlayer(player, interest);
        if (!response.transactionSuccess()) {
            return OperationResult.fail("&cInterest payout failed: " + response.errorMessage);
        }
        BankAccount updated = account.withLastInterestClaim(now);
        if (!database.saveAccountWithTransaction(updated, "INTEREST", interest, account.balance(), "Daily interest to purse")) {
            // Roll back the wallet payout so we do not pay interest twice on the next claim.
            boolean clawedBack = compensateWithdraw(player, interest, "interest payout");
            return OperationResult.fail(clawedBack
                    ? "&cInterest could not be saved and was reverted; please try again."
                    : "&cInterest could not be saved and your balance needs staff attention. Please contact an admin.");
        }
        cacheIfOnline(updated);
        // Interest paid to the purse leaves the bank (incoming=false): audit + large-alert only.
        auditSink.record(player.getUniqueId(), player.getName(), "interest-to-purse", interest, account.balance(), account.balance(), false, null, null);
        return OperationResult.success("&aDaily interest paid to your wallet: &e" + money(interest));
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
            return OperationResult.fail("&cInvalid balance amount.");
        }
        double sanitized = Amounts.roundNonNegative(plugin, amount);
        double maxTransaction = plugin.getConfig().getDouble("settings.amount-limits.max-transaction", 1_000_000_000_000.0);
        if (maxTransaction > 0.0 && sanitized > maxTransaction) {
            return OperationResult.fail("&cAmount is above the configured transaction limit.");
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        BankLevel level = getEffectiveLevel(account.level());
        double newBalance = round(Math.min(sanitized, level.maxBalance()));
        double discarded = round(sanitized - newBalance);
        String note = discarded > 0 ? "Admin set balance (capped at level max, discarded " + money(discarded) + ")" : "Admin set balance";
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_SET", newBalance, newBalance, note)) {
            return OperationResult.fail("&cCould not save balance change; please try again.");
        }
        cacheIfOnline(updated);
        String message = "&aSet " + updated.username() + "'s bank balance to &e" + money(newBalance) + "&a.";
        if (discarded > 0) {
            message += " &7(" + money(discarded) + " above the level cap was discarded.)";
        }
        return OperationResult.success(message);
    }

    public OperationResult addBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return OperationResult.fail("&cInvalid balance amount.");
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        BankLevel level = getEffectiveLevel(account.level());
        double newBalance = round(Math.min(account.balance() + sanitized, level.maxBalance()));
        double added = round(newBalance - account.balance());
        double discarded = round(sanitized - added);
        String note = discarded > 0 ? "Admin added balance (capped, discarded " + money(discarded) + ")" : "Admin added balance";
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_ADD", added, newBalance, note)) {
            return OperationResult.fail("&cCould not save balance change; please try again.");
        }
        cacheIfOnline(updated);
        String message = "&aAdded &e" + money(added) + " &ato " + updated.username() + "'s bank.";
        if (discarded > 0) {
            message += " &7(" + money(discarded) + " could not fit under the level cap and was discarded.)";
        }
        return OperationResult.success(message);
    }

    public OperationResult removeBalance(OfflinePlayer player, double amount) {
        double sanitized = Amounts.sanitize(plugin, amount);
        if (sanitized <= 0.0) {
            return OperationResult.fail("&cInvalid balance amount.");
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        double newBalance = round(Math.max(0.0, account.balance() - sanitized));
        double removed = round(account.balance() - newBalance);
        BankAccount updated = account.withBalance(newBalance);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_REMOVE", removed, newBalance, "Admin removed balance")) {
            return OperationResult.fail("&cCould not save balance change; please try again.");
        }
        cacheIfOnline(updated);
        return OperationResult.success("&aRemoved &e" + money(removed) + " &afrom " + updated.username() + "'s bank.");
    }

    public OperationResult setLevel(OfflinePlayer player, int level) {
        BankLevel bankLevel = levelManager.getLevel(level).orElse(null);
        if (bankLevel == null) {
            return OperationResult.fail("&cUnknown bank level: " + level);
        }
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        double clampedBalance = round(Math.min(account.balance(), bankLevel.maxBalance()));
        double discarded = round(account.balance() - clampedBalance);
        String note = discarded > 0
                ? "Admin set level to " + bankLevel.name() + " (balance capped, discarded " + money(discarded) + ")"
                : "Admin set level to " + bankLevel.name();
        BankAccount updated = new BankAccount(account.uuid(), account.username(), clampedBalance, level, account.lastInterestClaim(), account.bonusClaimed());
        if (!database.saveAccountWithTransaction(updated, "ADMIN_LEVEL", discarded, clampedBalance, note)) {
            return OperationResult.fail("&cCould not save level change; please try again.");
        }
        cacheIfOnline(updated);
        String message = "&aSet " + updated.username() + "'s bank level to &e" + bankLevel.level() + " - " + bankLevel.name() + "&a.";
        if (discarded > 0) {
            message += " &7(Balance reduced by " + money(discarded) + " to fit the new level cap.)";
        }
        return OperationResult.success(message);
    }

    public OperationResult resetAccount(OfflinePlayer player) {
        BankAccount account = database.getOrCreateAccount(player, levelManager.getStartingLevel());
        BankAccount updated = new BankAccount(account.uuid(), account.username(), 0.0, levelManager.getStartingLevel(), 0L, false);
        if (!database.saveAccountWithTransaction(updated, "ADMIN_RESET", 0.0, 0.0, "Admin reset account")) {
            return OperationResult.fail("&cCould not reset account; please try again.");
        }
        cacheIfOnline(updated);
        return OperationResult.success("&aReset " + updated.username() + "'s bank account.");
    }

    public String describeBalance(Player player) {
        BankAccount account = getAccount(player);
        BankLevel level = getEffectiveLevel(account.level());
        return "&eBank Level: &f" + level.level() + " - " + level.name()
                + "\n&eBalance: &f" + money(account.balance()) + " / " + money(level.maxBalance())
                + "\n&eMax Interest: &f" + money(level.maxInterest())
                + "\n&eNext Interest: &f" + money(calculateInterest(account.balance(), level));
    }

    public String describeNextUpgrade(Player player) {
        BankAccount account = getAccount(player);
        BankLevel nextLevel = levelManager.getNextLevel(account.level()).orElse(null);
        if (nextLevel == null) {
            return "&eYour bank is already max level.";
        }
        if (!hasUpgradeUnlocked(player)) {
            return "&cBank Upgrades Locked"
                    + "\n&7Requires bank + purse total: &f" + money(getUpgradeUnlockCombinedBalance())
                    + "\n&7Current combined total: &f" + money(getCombinedBalance(player));
        }

        List<String> items = nextLevel.itemRequirements().stream().map(ItemRequirement::displayName).toList();
        String itemText = items.isEmpty() ? "None" : String.join(", ", items);
        return "&eNext Level: &f" + nextLevel.level() + " - " + nextLevel.name()
                + "\n&eMoney Cost: &f" + money(nextLevel.upgradeMoneyCost())
                + "\n&eItem Cost: &f" + itemText
                + "\n&eMax Balance: &f" + money(nextLevel.maxBalance())
                + "\n&eMax Interest: &f" + money(nextLevel.maxInterest());
    }

    /** The item requirements for the player's next tier (empty if maxed or none). Used for GUI cost icons. */
    public List<ItemRequirement> nextUpgradeItemRequirements(Player player) {
        return levelManager.getNextLevel(getAccount(player).level())
                .map(BankLevel::itemRequirements).orElse(List.of());
    }

    /** How many matching items the player currently holds toward a requirement (vanilla or eco). */
    public int heldItemCount(Player player, ItemRequirement requirement) {
        return countItems(player.getInventory(), requirement);
    }

    public String money(double amount) {
        return Text.money(amount, plugin.getConfig().getString("settings.currency-symbol", "$"));
    }

    public double getWalletBalance(Player player) {
        return vaultHook.getEconomy().getBalance(player);
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
            return "Ready now";
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

    /** Rounds a money value to the configured decimal precision, cleaning up binary-fraction drift. */
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
                missing.add(requirement.displayName() + " (have " + count + ")");
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

        // EcoItems' stable identity tag is ecoitems:item = <id>. If present, this is a custom item,
        // not a vanilla material. We deliberately match only EcoItems' specific, documented keys here
        // so unrelated plugins that tag vanilla items (shops, cosmetics, etc.) do not block vanilla upgrades.
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

        // EcoItems currently tags items as ecoitems:item = <id>. This is the most important stable path.
        if (matchesPdcString(container, namespace, "item", wantedId)) {
            return true;
        }

        // Legacy EcoWeapons/EcoItems migration path visible in EcoItems' ItemUtils.kt.
        if (namespace.equals("ecoitems") && matchesPdcString(container, "ecoweapons", "weapon", wantedId)) {
            return true;
        }

        // Tolerate the "ecoitem" namespace alias used in some configs.
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
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
