package com.mystipixel.royalbank.command;

import com.mystipixel.royalbank.RoyalBankPlugin;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.gui.BankGui;
import com.mystipixel.royalbank.message.MessageManager;
import com.mystipixel.royalbank.security.AbuseMonitor;
import com.mystipixel.royalbank.service.BankService;
import com.mystipixel.royalbank.util.Amounts;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BankCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final RoyalBankPlugin plugin;
    private final BankService bankService;
    private final LevelManager levelManager;
    private final BankGui bankGui;
    private final MessageManager messages;

    // keyed by sender; expired entries are ignored
    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    private record PendingTransfer(UUID recipient, double amount, long createdAt) {
    }

    public BankCommand(RoyalBankPlugin plugin, BankService bankService, LevelManager levelManager, BankGui bankGui) {
        this.plugin = plugin;
        this.bankService = bankService;
        this.levelManager = levelManager;
        this.bankGui = bankGui;
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            return handleAdmin(sender, args);
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }

        if (!player.hasPermission("royalbank.use")) {
            messages.send(player, "permission.bank");
            return true;
        }

        if (args.length == 0) {
            bankGui.openMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("balance")) {
            messages.sendEach(player, bankService.describeBalance(player));
            messages.send(player, "info.balance-hint");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "deposit" -> handleDeposit(player, args);
            case "withdraw" -> handleWithdraw(player, args);
            case "transfer" -> handleTransfer(player, args);
            case "upgrade" -> handleUpgrade(player);
            case "interest" -> handleInterest(player);
            case "info" -> messages.sendEach(player, bankService.describeNextUpgrade(player));
            default -> messages.send(player, "unknown-command");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("royalbank.admin")) {
            messages.send(sender, "permission.admin");
            return true;
        }
        plugin.reloadRoyalBank();
        messages.send(sender, "reload-success", "levels", String.valueOf(levelManager.getOrderedLevelNumbers()));
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalbank.admin")) {
            messages.send(sender, "permission.admin");
            return true;
        }

        if (args.length < 2) {
            sendAdminUsage(sender);
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "balance" -> handleAdminBalance(sender, args);
            case "setbalance" -> handleAdminSetBalance(sender, args);
            case "addbalance" -> handleAdminAddBalance(sender, args);
            case "removebalance" -> handleAdminRemoveBalance(sender, args);
            case "setlevel" -> handleAdminSetLevel(sender, args);
            case "reset" -> handleAdminReset(sender, args);
            case "transactions" -> handleAdminTransactions(sender, args);
            case "backup" -> handleAdminBackup(sender);
            case "flags" -> handleAdminFlags(sender, args);
            default -> sendAdminUsage(sender);
        }
        return true;
    }

    private void handleDeposit(Player player, String[] args) {
        if (!player.hasPermission("royalbank.deposit")) {
            messages.send(player, "permission.deposit");
            return;
        }
        Double amount = parseAmount(player, args, "deposit.usage");
        if (amount == null) {
            return;
        }
        messages.sendResult(player, bankService.deposit(player, amount));
    }

    private void handleWithdraw(Player player, String[] args) {
        if (!player.hasPermission("royalbank.withdraw")) {
            messages.send(player, "permission.withdraw");
            return;
        }
        Double amount = parseAmount(player, args, "withdraw.usage");
        if (amount == null) {
            return;
        }
        messages.sendResult(player, bankService.withdraw(player, amount));
    }

    private void handleInterest(Player player) {
        messages.sendResult(player, bankService.claimInterest(player, true));
    }

    private void handleTransfer(Player player, String[] args) {
        if (!player.hasPermission("royalbank.transfer")) {
            messages.send(player, "permission.transfer");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            confirmTransfer(player);
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            if (pendingTransfers.remove(player.getUniqueId()) != null) {
                messages.send(player, "transfer.cancelled");
            } else {
                messages.send(player, "transfer.cancel-nothing-pending");
            }
            return;
        }
        if (args.length < 3) {
            messages.send(player, "transfer.usage");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        // Reject names that have never joined, so a typo cannot silently create a junk "ghost" account.
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            messages.send(player, "admin.unknown-player", "player", args[1]);
            return;
        }
        Double amount = Amounts.parse(plugin, args[2]);
        if (amount == null) {
            sendInvalidAmount(player);
            return;
        }

        // catches a fat-fingered amount (5b instead of 5m)
        double threshold = plugin.getConfig().getDouble("settings.transfer.confirm-threshold", 100_000_000.0);
        if (threshold > 0 && amount >= threshold) {
            pendingTransfers.put(player.getUniqueId(), new PendingTransfer(target.getUniqueId(), amount, System.currentTimeMillis()));
            String targetName = target.getName() == null ? args[1] : target.getName();
            messages.send(player, "transfer.confirm-large", "amount", bankService.money(amount), "player", targetName);
            messages.send(player, "transfer.confirm-hint", "seconds", String.valueOf(confirmTimeoutSeconds()));
            return;
        }
        messages.sendResult(player, bankService.transfer(player, target, amount));
    }

    private void confirmTransfer(Player player) {
        PendingTransfer pending = pendingTransfers.remove(player.getUniqueId());
        if (pending == null) {
            messages.send(player, "transfer.confirm-nothing-pending");
            return;
        }
        if (System.currentTimeMillis() - pending.createdAt() > confirmTimeoutSeconds() * 1000L) {
            messages.send(player, "transfer.confirm-expired");
            return;
        }
        // full transfer path again so balance and cap are re-validated at confirm time
        messages.sendResult(player, bankService.transfer(player, Bukkit.getOfflinePlayer(pending.recipient()), pending.amount()));
    }

    private long confirmTimeoutSeconds() {
        return Math.max(5L, plugin.getConfig().getLong("settings.transfer.confirm-timeout-seconds", 30L));
    }

    private void handleUpgrade(Player player) {
        if (!player.hasPermission("royalbank.upgrade")) {
            messages.send(player, "permission.upgrade");
            return;
        }
        bankGui.openConfirmUpgrade(player);
    }

    private void handleAdminBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        BankAccount account = plugin.getDatabase().getOrCreateAccount(target, levelManager.getStartingLevel());
        messages.send(sender, "admin.balance-line",
                "player", String.valueOf(account.username()),
                "balance", bankService.money(account.balance()),
                "level", String.valueOf(account.level()));
    }

    private void handleAdminSetBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        Double amount = parseAdminAmount(sender, args, 3, "admin.usage.setbalance", true);
        if (target == null || amount == null) {
            return;
        }
        messages.sendResult(sender, bankService.setBalance(target, amount));
    }

    private void handleAdminAddBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        Double amount = parseAdminAmount(sender, args, 3, "admin.usage.addbalance", false);
        if (target == null || amount == null) {
            return;
        }
        messages.sendResult(sender, bankService.addBalance(target, amount));
    }

    private void handleAdminRemoveBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        Double amount = parseAdminAmount(sender, args, 3, "admin.usage.removebalance", false);
        if (target == null || amount == null) {
            return;
        }
        messages.sendResult(sender, bankService.removeBalance(target, amount));
    }

    private void handleAdminSetLevel(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        if (args.length < 4) {
            messages.send(sender, "admin.usage.setlevel");
            return;
        }
        try {
            messages.sendResult(sender, bankService.setLevel(target, Integer.parseInt(args[3])));
        } catch (NumberFormatException exception) {
            messages.send(sender, "admin.level-number");
        }
    }

    private void handleAdminReset(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        messages.sendResult(sender, bankService.resetAccount(target));
    }

    private void handleAdminTransactions(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        List<BankTransaction> transactions = plugin.getDatabase().getRecentTransactions(target.getUniqueId(), 10);
        if (transactions.isEmpty()) {
            messages.send(sender, "admin.transactions-empty", "player", String.valueOf(target.getName()));
            return;
        }
        messages.send(sender, "admin.transactions-header", "player", String.valueOf(target.getName()));
        for (BankTransaction transaction : transactions) {
            messages.send(sender, "admin.transactions-line",
                    "id", String.valueOf(transaction.id()),
                    "type", transaction.type(),
                    "amount", bankService.money(transaction.amount()),
                    "balance_after", bankService.money(transaction.balanceAfter()),
                    "note", String.valueOf(transaction.note()));
        }
    }

    private void handleAdminFlags(CommandSender sender, String[] args) {
        if (bankService.usesExternalAudit()) {
            messages.send(sender, "admin.flags-external");
            return;
        }
        AbuseMonitor monitor = bankService.getAbuseMonitor();
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            monitor.clearFlags();
            messages.send(sender, "admin.flags-cleared");
            return;
        }
        Collection<AbuseMonitor.Flag> flags = monitor.getFlags();
        if (flags.isEmpty()) {
            messages.send(sender, "admin.flags-empty");
            return;
        }
        messages.send(sender, "admin.flags-header", "count", String.valueOf(flags.size()));
        long now = Instant.now().getEpochSecond();
        for (AbuseMonitor.Flag flag : flags) {
            long minutesAgo = Math.max(0, (now - flag.timestamp()) / 60L);
            messages.send(sender, "admin.flags-line", "player", flag.username(), "reason", flag.reason(),
                    "minutes", String.valueOf(minutesAgo));
        }
    }

    private void handleAdminBackup(CommandSender sender) {
        File databaseFile = plugin.getDatabase().getDatabaseFile();
        if (databaseFile == null || !databaseFile.exists()) {
            messages.send(sender, "admin.backup-missing-db");
            return;
        }

        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            messages.send(sender, "admin.backup-folder-failed");
            return;
        }

        File backupFile = new File(backupFolder, "bank-" + BACKUP_TIME.format(Instant.now()) + "-" + System.currentTimeMillis() + ".db");
        // VACUUM INTO can take a while on a large database
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.getDatabase().backupTo(backupFile.toPath());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    messages.send(sender, "admin.backup-success", "path", backupFile.getAbsolutePath());
                } else {
                    messages.send(sender, "admin.backup-failed");
                }
            });
        });
    }

    private OfflinePlayer target(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            messages.send(sender, "admin.missing-player");
            return null;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(args[index]);
        // Reject names that have never joined, so a typo cannot silently create a junk "ghost" account.
        if (!player.hasPlayedBefore() && !player.isOnline()) {
            messages.send(sender, "admin.unknown-player", "player", args[index]);
            return null;
        }
        return player;
    }

    private Double parseAmount(Player player, String[] args, String usageKey) {
        if (args.length < 2) {
            messages.send(player, usageKey);
            return null;
        }
        Double amount = Amounts.parse(plugin, args[1]);
        if (amount == null) {
            sendInvalidAmount(player);
        }
        return amount;
    }

    private void sendInvalidAmount(Player player) {
        messages.send(player, "amount.invalid",
                "minimum", bankService.money(plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01)));
    }

    private Double parseAdminAmount(CommandSender sender, String[] args, int index, String usageKey, boolean allowZero) {
        if (args.length <= index) {
            messages.send(sender, usageKey);
            return null;
        }
        Double amount = Amounts.parse(plugin, args[index], allowZero);
        if (amount == null) {
            messages.send(sender, "admin.invalid-number");
        }
        return amount;
    }

    private void sendAdminUsage(CommandSender sender) {
        messages.send(sender, "admin.usage-header");
        messages.sendLines(sender, "admin.usage-lines");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("balance", "deposit", "withdraw", "transfer", "upgrade", "interest", "info"));
            if (sender.hasPermission("royalbank.admin")) {
                options.add("reload");
                options.add("admin");
            }
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("royalbank.admin")) {
            return filter(Arrays.asList("balance", "setbalance", "addbalance", "removebalance", "setlevel", "reset", "transactions", "backup", "flags"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("flags") && sender.hasPermission("royalbank.admin")) {
            return filter(Collections.singletonList("clear"), args[2]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("deposit") || args[0].equalsIgnoreCase("withdraw"))) {
            return filter(Arrays.asList("1000", "100000", "1m", "10m", "100m"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("transfer")) {
            List<String> options = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            if (sender instanceof Player p && pendingTransfers.containsKey(p.getUniqueId())) {
                options.add("confirm");
                options.add("cancel");
            }
            return filter(options, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("transfer")) {
            return filter(Arrays.asList("1000", "100000", "1m", "10m", "100m"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("setlevel")) {
            return filter(levelManager.getOrderedLevelNumbers().stream().map(String::valueOf).toList(), args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(option -> option.toLowerCase().startsWith(lower)).toList();
    }
}
