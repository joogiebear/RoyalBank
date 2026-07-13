package com.mystipixel.royalbank.bank;

import com.mystipixel.royalbank.RoyalBankPlugin;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.gui.BankGui;
import com.mystipixel.royalbank.security.AbuseMonitor;
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

    /** Large transfers awaiting a /bank transfer confirm, keyed by sender. Expired entries are ignored. */
    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    private record PendingTransfer(UUID recipient, double amount, long createdAt) {
    }

    public BankCommand(RoyalBankPlugin plugin, BankService bankService, LevelManager levelManager, BankGui bankGui) {
        this.plugin = plugin;
        this.bankService = bankService;
        this.levelManager = levelManager;
        this.bankGui = bankGui;
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
            msg(sender, "player-only", "&7Only players can use player bank commands. Use /bank admin for staff tools.");
            return true;
        }

        if (!player.hasPermission("royalbank.use")) {
            msg(player, "permission.bank", "&cYou do not have permission to use the bank.");
            return true;
        }

        if (args.length == 0) {
            bankGui.openMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("balance")) {
            sendMultiline(player, bankService.describeBalance(player));
            send(player, "&7Use &e/bank &7to open the GUI, or &e/bank deposit <amount>&7, &e/bank withdraw <amount>&7, &e/bank upgrade&7.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "deposit" -> handleDeposit(player, args);
            case "withdraw" -> handleWithdraw(player, args);
            case "transfer" -> handleTransfer(player, args);
            case "upgrade" -> handleUpgrade(player);
            case "interest" -> handleInterest(player);
            case "info" -> sendMultiline(player, bankService.describeNextUpgrade(player));
            default -> msg(player, "unknown-command", "&cUnknown subcommand. Use /bank, /bank deposit, /bank withdraw, /bank transfer, /bank upgrade, /bank interest, /bank info, or /bank admin.");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("royalbank.admin")) {
            msg(sender, "permission.admin", "&cYou do not have permission to use RoyalBank admin commands.");
            return true;
        }
        plugin.reloadRoyalBank();
        msg(sender, "reload-success", "&aRoyalBank reloaded. Loaded levels: {levels}",
                Map.of("levels", String.valueOf(levelManager.getOrderedLevelNumbers())));
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalbank.admin")) {
            msg(sender, "permission.admin", "&cYou do not have permission to use RoyalBank admin commands.");
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
            msg(player, "permission.deposit", "&cYou do not have permission to deposit.");
            return;
        }
        Double amount = parseAmount(player, args, "deposit");
        if (amount == null) {
            return;
        }
        sendResult(player, bankService.deposit(player, amount));
    }

    private void handleWithdraw(Player player, String[] args) {
        if (!player.hasPermission("royalbank.withdraw")) {
            msg(player, "permission.withdraw", "&cYou do not have permission to withdraw.");
            return;
        }
        Double amount = parseAmount(player, args, "withdraw");
        if (amount == null) {
            return;
        }
        sendResult(player, bankService.withdraw(player, amount));
    }

    private void handleInterest(Player player) {
        sendResult(player, bankService.claimInterest(player, true));
    }

    private void handleTransfer(Player player, String[] args) {
        if (!player.hasPermission("royalbank.transfer")) {
            msg(player, "permission.transfer", "&cYou do not have permission to transfer money.");
            return;
        }
        // /bank transfer confirm | cancel — act on a pending large transfer.
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            confirmTransfer(player);
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            send(player, pendingTransfers.remove(player.getUniqueId()) != null
                    ? "&7Pending transfer cancelled."
                    : "&7You have no transfer awaiting confirmation.");
            return;
        }
        if (args.length < 3) {
            send(player, "&cUsage: /bank transfer <player> <amount>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        // Reject names that have never joined, so a typo cannot silently create a junk "ghost" account.
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            msg(player, "admin.unknown-player", "&cNo bank account exists for '{player}'. They must join the server first.",
                    Map.of("player", args[1]));
            return;
        }
        Double amount = Amounts.parse(plugin, args[2]);
        if (amount == null) {
            send(player, "&cThat is not a safe valid number. Minimum: " + bankService.money(plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01)) + ". &7Tip: use k/m/b/t, e.g. &f5m&7 = 5,000,000.");
            return;
        }

        // Large transfers require confirmation so a fat-fingered amount (e.g. 5b instead of 5m) is caught.
        double threshold = plugin.getConfig().getDouble("settings.transfer.confirm-threshold", 100_000_000.0);
        if (threshold > 0 && amount >= threshold) {
            pendingTransfers.put(player.getUniqueId(), new PendingTransfer(target.getUniqueId(), amount, System.currentTimeMillis()));
            String targetName = target.getName() == null ? args[1] : target.getName();
            send(player, "&eLarge transfer: &f" + bankService.money(amount) + " &eto &f" + targetName + "&e.");
            send(player, "&7Type &f/bank transfer confirm &7within " + confirmTimeoutSeconds() + "s to send, or &f/bank transfer cancel&7.");
            return;
        }
        sendResult(player, bankService.transfer(player, target, amount));
    }

    private void confirmTransfer(Player player) {
        PendingTransfer pending = pendingTransfers.remove(player.getUniqueId());
        if (pending == null) {
            send(player, "&cYou have no transfer awaiting confirmation.");
            return;
        }
        if (System.currentTimeMillis() - pending.createdAt() > confirmTimeoutSeconds() * 1000L) {
            send(player, "&cThat transfer expired. Please run it again.");
            return;
        }
        // Re-run through the full transfer path so balance/cap are re-validated at confirm time.
        sendResult(player, bankService.transfer(player, Bukkit.getOfflinePlayer(pending.recipient()), pending.amount()));
    }

    private long confirmTimeoutSeconds() {
        return Math.max(5L, plugin.getConfig().getLong("settings.transfer.confirm-timeout-seconds", 30L));
    }

    private void handleUpgrade(Player player) {
        if (!player.hasPermission("royalbank.upgrade")) {
            msg(player, "permission.upgrade", "&cYou do not have permission to upgrade your bank.");
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
        msg(sender, "admin.balance-line", "&e{player} &7Bank: &f{balance} &7Level: &f{level}",
                Map.of(
                        "player", String.valueOf(account.username()),
                        "balance", bankService.money(account.balance()),
                        "level", String.valueOf(account.level())));
    }

    private void handleAdminSetBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        Double amount = parseAdminAmount(sender, args, 3, "setbalance <player> <amount>", true);
        if (target == null || amount == null) {
            return;
        }
        sendResult(sender, bankService.setBalance(target, amount));
    }

    private void handleAdminAddBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        Double amount = parseAdminAmount(sender, args, 3, "addbalance <player> <amount>", false);
        if (target == null || amount == null) {
            return;
        }
        sendResult(sender, bankService.addBalance(target, amount));
    }

    private void handleAdminRemoveBalance(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        Double amount = parseAdminAmount(sender, args, 3, "removebalance <player> <amount>", false);
        if (target == null || amount == null) {
            return;
        }
        sendResult(sender, bankService.removeBalance(target, amount));
    }

    private void handleAdminSetLevel(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        if (args.length < 4) {
            send(sender, "&cUsage: /bank admin setlevel <player> <level>");
            return;
        }
        try {
            sendResult(sender, bankService.setLevel(target, Integer.parseInt(args[3])));
        } catch (NumberFormatException exception) {
            msg(sender, "admin.level-number", "&cLevel must be a whole number.");
        }
    }

    private void handleAdminReset(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        sendResult(sender, bankService.resetAccount(target));
    }

    private void handleAdminTransactions(CommandSender sender, String[] args) {
        OfflinePlayer target = target(sender, args, 2);
        if (target == null) {
            return;
        }
        List<BankTransaction> transactions = plugin.getDatabase().getRecentTransactions(target.getUniqueId(), 10);
        if (transactions.isEmpty()) {
            msg(sender, "admin.transactions-empty", "&eNo transactions found for {player}.",
                    Map.of("player", String.valueOf(target.getName())));
            return;
        }
        msg(sender, "admin.transactions-header", "&eRecent transactions for {player}:",
                Map.of("player", String.valueOf(target.getName())));
        for (BankTransaction transaction : transactions) {
            send(sender, "&7#" + transaction.id() + " &f" + transaction.type() + " &e" + bankService.money(transaction.amount())
                    + " &7after &f" + bankService.money(transaction.balanceAfter()) + " &8- &7" + transaction.note());
        }
    }

    private void handleAdminFlags(CommandSender sender, String[] args) {
        if (bankService.usesExternalAudit()) {
            send(sender, "&eAnti-abuse is handled by EconGuard on this server. Use &f/econguard flags&e.");
            return;
        }
        AbuseMonitor monitor = bankService.getAbuseMonitor();
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            monitor.clearFlags();
            send(sender, "&aCleared all anti-abuse flags.");
            return;
        }
        Collection<AbuseMonitor.Flag> flags = monitor.getFlags();
        if (flags.isEmpty()) {
            send(sender, "&aNo anti-abuse flags. All clear.");
            return;
        }
        send(sender, "&eAnti-abuse flags (" + flags.size() + "): &7clear with /bank admin flags clear");
        long now = Instant.now().getEpochSecond();
        for (AbuseMonitor.Flag flag : flags) {
            long minutesAgo = Math.max(0, (now - flag.timestamp()) / 60L);
            send(sender, "&c• &e" + flag.username() + " &7- " + flag.reason() + " &8(" + minutesAgo + "m ago)");
        }
    }

    private void handleAdminBackup(CommandSender sender) {
        File databaseFile = plugin.getDatabase().getDatabaseFile();
        if (databaseFile == null || !databaseFile.exists()) {
            msg(sender, "admin.backup-missing-db", "&cDatabase file does not exist yet.");
            return;
        }

        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            msg(sender, "admin.backup-folder-failed", "&cCould not create backup folder.");
            return;
        }

        File backupFile = new File(backupFolder, "bank-" + BACKUP_TIME.format(Instant.now()) + "-" + System.currentTimeMillis() + ".db");
        // VACUUM INTO can take a moment on a large database, so run it off the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.getDatabase().backupTo(backupFile.toPath());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    msg(sender, "admin.backup-success", "&aCreated database backup: &f{path}",
                            Map.of("path", backupFile.getAbsolutePath()));
                } else {
                    msg(sender, "admin.backup-failed", "&cBackup failed. Check console for details.");
                }
            });
        });
    }

    private OfflinePlayer target(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            msg(sender, "admin.missing-player", "&cMissing player name.");
            return null;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(args[index]);
        // Reject names that have never joined, so a typo cannot silently create a junk "ghost" account.
        if (!player.hasPlayedBefore() && !player.isOnline()) {
            msg(sender, "admin.unknown-player", "&cNo bank account exists for '{player}'. They must join the server first.",
                    Map.of("player", args[index]));
            return null;
        }
        return player;
    }

    private Double parseAmount(Player player, String[] args, String usage) {
        if (args.length < 2) {
            send(player, "&cUsage: /bank " + usage + " <amount>");
            return null;
        }
        Double amount = Amounts.parse(plugin, args[1]);
        if (amount == null) {
            send(player, "&cThat is not a safe valid number. Minimum: " + bankService.money(plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01)) + ". &7Tip: use k/m/b/t, e.g. &f5m&7 = 5,000,000.");
        }
        return amount;
    }

    private Double parseAdminAmount(CommandSender sender, String[] args, int index, String usage, boolean allowZero) {
        if (args.length <= index) {
            send(sender, "&cUsage: /bank admin " + usage);
            return null;
        }
        Double amount = Amounts.parse(plugin, args[index], allowZero);
        if (amount == null) {
            msg(sender, "admin.invalid-number", "&cThat is not a safe valid number.");
        }
        return amount;
    }

    private void sendResult(CommandSender sender, OperationResult result) {
        if (result.message() == null || result.message().isBlank()) {
            return;
        }
        send(sender, result.message());
    }

    private void send(CommandSender sender, String message) {
        plugin.getMessageManager().sendRaw(sender, message);
    }

    private void msg(CommandSender sender, String key, String fallback) {
        plugin.getMessageManager().send(sender, key, fallback);
    }

    private void msg(CommandSender sender, String key, String fallback, Map<String, String> placeholders) {
        plugin.getMessageManager().send(sender, key, fallback, placeholders);
    }

    private void sendMultiline(CommandSender sender, String message) {
        for (String line : message.split("\n")) {
            send(sender, line);
        }
    }

    private void sendAdminUsage(CommandSender sender) {
        msg(sender, "admin.usage-header", "&eRoyalBank Admin Commands:");
        send(sender, "&7/bank admin balance <player>");
        send(sender, "&7/bank admin setbalance <player> <amount>");
        send(sender, "&7/bank admin addbalance <player> <amount>");
        send(sender, "&7/bank admin removebalance <player> <amount>");
        send(sender, "&7/bank admin setlevel <player> <level>");
        send(sender, "&7/bank admin reset <player>");
        send(sender, "&7/bank admin transactions <player>");
        send(sender, "&7/bank admin backup");
        send(sender, "&7/bank admin flags [clear]");
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
