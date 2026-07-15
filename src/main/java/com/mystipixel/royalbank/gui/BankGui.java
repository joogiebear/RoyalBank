package com.mystipixel.royalbank.gui;

import com.mystipixel.royalbank.RoyalBankPlugin;
import com.mystipixel.royalbank.service.BankService;
import com.mystipixel.royalbank.service.OperationResult;
import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BankGui implements Listener {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final RoyalBankPlugin plugin;
    private final BankService bankService;
    private final Map<UUID, PendingAmount> pendingChatActions = new ConcurrentHashMap<>();
    private final Map<UUID, FileConfiguration> openGuiConfigs = new ConcurrentHashMap<>();
    private FileConfiguration mainGui;
    private FileConfiguration depositGui;
    private FileConfiguration withdrawGui;
    private FileConfiguration transactionsGui;
    private FileConfiguration confirmUpgradeGui;

    public BankGui(RoyalBankPlugin plugin, BankService bankService) {
        this.plugin = plugin;
        this.bankService = bankService;
        reload();
    }

    public void reload() {
        this.mainGui = loadGuiFile("main.yml");
        this.depositGui = loadGuiFile("deposit.yml");
        this.withdrawGui = loadGuiFile("withdraw.yml");
        this.transactionsGui = loadGuiFile("transactions.yml");
        this.confirmUpgradeGui = loadGuiFile("confirm-upgrade.yml");
    }

    public void openMain(Player player) {
        openConfiguredMenu(player, BankGuiType.MAIN, mainGui);
    }

    public void openDeposit(Player player) {
        openConfiguredMenu(player, BankGuiType.DEPOSIT, depositGui);
    }

    public void openWithdraw(Player player) {
        openConfiguredMenu(player, BankGuiType.WITHDRAW, withdrawGui);
    }

    public void openConfirmUpgrade(Player player) {
        openConfiguredMenu(player, BankGuiType.CONFIRM_UPGRADE, confirmUpgradeGui);
    }

    public void openTransactions(Player player) {
        Inventory inventory = createInventory(BankGuiType.TRANSACTIONS, transactionsGui);
        fillFiller(inventory, transactionsGui);

        List<Integer> slots = transactionsGui.getIntegerList("transaction-slots");
        if (slots.isEmpty()) {
            slots = List.of(10, 11, 12, 13, 14, 15, 16, 20, 21, 22);
        }

        List<BankTransaction> transactions = bankService.getRecentTransactions(player, slots.size());
        GuiContext context = context(player);
        if (transactions.isEmpty()) {
            ConfigurationSection empty = transactionsGui.getConfigurationSection("empty-item");
            if (empty != null) {
                setItemAt(inventory, empty.getInt("slot", 14), configuredItem(player, context, empty, BankGuiAction.NONE));
            }
        } else {
            for (int index = 0; index < transactions.size() && index < slots.size(); index++) {
                setItemAt(inventory, slots.get(index), transactionItem(transactions.get(index)));
            }
        }

        placeConfiguredItems(player, inventory, transactionsGui, context);
        player.openInventory(inventory);
        openGuiConfigs.put(player.getUniqueId(), transactionsGui);
        playSound(player, transactionsGui, "sounds.open");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BankInventoryHolder)) {
            return;
        }

        event.setCancelled(true);
        BankGuiAction action = getAction(event.getCurrentItem());
        if (action == null || action == BankGuiAction.NONE) {
            return;
        }

        switch (action) {
            case OPEN_DEPOSIT -> openDeposit(player);
            case OPEN_WITHDRAW -> openWithdraw(player);
            case DEPOSIT_ALL -> {
                if (requirePermission(player, "royalbank.deposit", "&cYou do not have permission to deposit.")) {
                    runAndRefresh(player, bankService.deposit(player, bankService.getWalletBalance(player)));
                }
            }
            case DEPOSIT_HALF -> {
                if (requirePermission(player, "royalbank.deposit", "&cYou do not have permission to deposit.")) {
                    runAndRefresh(player, bankService.deposit(player, bankService.getWalletBalance(player) / 2.0));
                }
            }
            case DEPOSIT_CUSTOM -> {
                if (requirePermission(player, "royalbank.deposit", "&cYou do not have permission to deposit.")) {
                    promptForAmount(player, BankGuiAction.DEPOSIT_CUSTOM);
                }
            }
            case WITHDRAW_ALL -> {
                if (requirePermission(player, "royalbank.withdraw", "&cYou do not have permission to withdraw.")) {
                    runAndRefresh(player, bankService.withdraw(player, bankService.getAccount(player).balance()));
                }
            }
            case WITHDRAW_HALF -> {
                if (requirePermission(player, "royalbank.withdraw", "&cYou do not have permission to withdraw.")) {
                    runAndRefresh(player, bankService.withdraw(player, bankService.getAccount(player).balance() / 2.0));
                }
            }
            case WITHDRAW_TWENTY -> {
                if (requirePermission(player, "royalbank.withdraw", "&cYou do not have permission to withdraw.")) {
                    runAndRefresh(player, bankService.withdraw(player, bankService.getAccount(player).balance() * 0.20));
                }
            }
            case WITHDRAW_CUSTOM -> {
                if (requirePermission(player, "royalbank.withdraw", "&cYou do not have permission to withdraw.")) {
                    promptForAmount(player, BankGuiAction.WITHDRAW_CUSTOM);
                }
            }
            case UPGRADE -> {
                if (requirePermission(player, "royalbank.upgrade", "&cYou do not have permission to upgrade your bank.")) {
                    openConfirmUpgrade(player);
                }
            }
            case CONFIRM_UPGRADE -> {
                if (requirePermission(player, "royalbank.upgrade", "&cYou do not have permission to upgrade your bank.")) {
                    runAndRefresh(player, bankService.upgrade(player));
                }
            }
            case CLAIM_INTEREST -> runAndRefresh(player, bankService.claimInterest(player, true));
            case RECENT_TRANSACTIONS -> openTransactions(player);
            case BACK_MAIN -> openMain(player);
            case CLOSE -> player.closeInventory();
            case NONE -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingAmount pending = pendingChatActions.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        // A stale prompt (player walked away, then chatted normally later) must NOT swallow their
        // message or move money. Only consume the chat line while the prompt is still fresh.
        if (System.currentTimeMillis() - pending.createdAt() > promptTimeoutMillis()) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleCustomAmount(player, pending.action(), message));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof BankInventoryHolder) {
            openGuiConfigs.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openGuiConfigs.remove(uuid);
        pendingChatActions.remove(uuid);
    }

    private void openConfiguredMenu(Player player, BankGuiType type, FileConfiguration config) {
        Inventory inventory = createInventory(type, config);
        fillFiller(inventory, config);
        placeConfiguredItems(player, inventory, config, context(player));
        player.openInventory(inventory);
        openGuiConfigs.put(player.getUniqueId(), config);
        playSound(player, config, "sounds.open");
    }

    private Inventory createInventory(BankGuiType type, FileConfiguration config) {
        int rows = Math.max(1, Math.min(6, config.getInt("rows", 4)));
        String title = Text.color(config.getString("title", "&6&lRoyalBank"));
        return Bukkit.createInventory(new BankInventoryHolder(type), rows * 9, title);
    }

    private void placeConfiguredItems(Player player, Inventory inventory, FileConfiguration config, GuiContext context) {
        ConfigurationSection section = config.getConfigurationSection("items");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }
            BankGuiAction action = parseAction(itemSection.getString("action", "NONE"));
            setItemAt(inventory, itemSection.getInt("slot", 1), configuredItem(player, context, itemSection, action));
        }
    }

    private ItemStack configuredItem(Player player, GuiContext context, ConfigurationSection section, BankGuiAction action) {
        Material material = material(section.getString("material", "STONE"));
        if (action == BankGuiAction.UPGRADE && !context.upgradeUnlocked()) {
            material = material(section.getString("locked-material", material.name()));
        }

        String name = section.getString("name", "&fItem");
        if (action == BankGuiAction.UPGRADE && !context.upgradeUnlocked()) {
            name = section.getString("locked-name", name);
        }

        List<String> lore = new ArrayList<>();
        for (String line : section.getStringList("lore")) {
            lore.addAll(expandLine(player, line));
        }

        ItemStack item = item(material, replacePlaceholders(player, context, name), lore.stream().map(line -> replacePlaceholders(player, context, line)).toList());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(BankKeys.ACTION, PersistentDataType.STRING, action.name());
        item.setItemMeta(meta);
        return item;
    }

    private List<String> expandLine(Player player, String line) {
        if (!line.contains("{upgrade_info}")) {
            return List.of(line);
        }
        List<String> expanded = new ArrayList<>();
        for (String upgradeLine : bankService.describeNextUpgrade(player).split("\\n")) {
            expanded.add(line.replace("{upgrade_info}", upgradeLine));
        }
        return expanded;
    }

    private String replacePlaceholders(Player player, GuiContext context, String text) {
        BankAccount account = context.account();
        BankLevel level = context.level();
        double purse = context.purse();
        double bankSpace = Math.max(0.0, level.maxBalance() - account.balance());

        return text
                .replace("{player}", player.getName())
                .replace("{balance}", bankService.money(account.balance()))
                .replace("{max_balance}", bankService.money(level.maxBalance()))
                .replace("{bank_space}", bankService.money(bankSpace))
                .replace("{purse}", bankService.money(purse))
                .replace("{half_purse}", bankService.money(purse / 2.0))
                .replace("{half_balance}", bankService.money(account.balance() / 2.0))
                .replace("{twenty_balance}", bankService.money(account.balance() * 0.20))
                .replace("{account_level}", String.valueOf(level.level()))
                .replace("{account_name}", level.name())
                .replace("{next_interest}", bankService.money(bankService.calculateInterest(account.balance(), level)))
                .replace("{interest_time}", bankService.getInterestTimeRemaining(player))
                .replace("{max_interest}", bankService.money(level.maxInterest()))
                .replace("{upgrade_unlock_balance}", bankService.money(bankService.getUpgradeUnlockCombinedBalance()))
                .replace("{combined_balance}", bankService.money(account.balance() + purse));
    }

    private void handleCustomAmount(Player player, BankGuiAction action, String message) {
        if (!player.isOnline()) {
            return;
        }
        if (message.equalsIgnoreCase("cancel")) {
            msg(player, "custom-cancelled", "&eCustom bank action cancelled.");
            openMain(player);
            return;
        }

        // Re-check permission at execution time: it may have been revoked between the prompt and the reply.
        String permission = action == BankGuiAction.DEPOSIT_CUSTOM ? "royalbank.deposit" : "royalbank.withdraw";
        if (!player.hasPermission(permission)) {
            if (action == BankGuiAction.DEPOSIT_CUSTOM) {
                msg(player, "permission.deposit", "&cYou do not have permission to deposit.");
            } else {
                msg(player, "permission.withdraw", "&cYou do not have permission to withdraw.");
            }
            return;
        }

        Double parsedAmount = com.mystipixel.royalbank.util.Amounts.parse(plugin, message);
        if (parsedAmount == null) {
            msg(player, "custom-invalid-amount", "&cThat was not a valid amount. Use a number like &f5000&c or shorthand like &f5m&c (k/m/b/t), or type cancel next time.");
            openMain(player);
            return;
        }
        double amount = parsedAmount;

        OperationResult result;
        if (action == BankGuiAction.DEPOSIT_CUSTOM) {
            result = bankService.deposit(player, amount);
        } else if (action == BankGuiAction.WITHDRAW_CUSTOM) {
            result = bankService.withdraw(player, amount);
        } else {
            return;
        }
        runAndRefresh(player, result);
    }

    private boolean requirePermission(Player player, String permission, String deniedMessage) {
        if (player.hasPermission(permission)) {
            return true;
        }
        send(player, deniedMessage);
        playSound(player, currentConfig(player), "sounds.fail");
        player.closeInventory();
        return false;
    }

    private void promptForAmount(Player player, BankGuiAction action) {
        pendingChatActions.put(player.getUniqueId(), new PendingAmount(action, System.currentTimeMillis()));
        player.closeInventory();
        playSound(player, currentConfig(player), "sounds.prompt");
        if (action == BankGuiAction.DEPOSIT_CUSTOM) {
            msg(player, "prompt-deposit", "&aType the amount you want to deposit in chat. Type &ecancel &ato stop.");
        } else {
            msg(player, "prompt-withdraw", "&cType the amount you want to withdraw in chat. Type &ecancel &cto stop.");
        }
    }

    private void runAndRefresh(Player player, OperationResult result) {
        if (result.message() != null && !result.message().isBlank()) {
            send(player, result.message());
        }
        playSound(player, currentConfig(player), result.success() ? "sounds.success" : "sounds.fail");
        Bukkit.getScheduler().runTask(plugin, () -> openMain(player));
    }

    private ItemStack transactionItem(BankTransaction transaction) {
        Material material = switch (transaction.type().toUpperCase(Locale.ROOT)) {
            case "DEPOSIT" -> Material.LIME_DYE;
            case "WITHDRAW" -> Material.RED_DYE;
            case "INTEREST" -> Material.CLOCK;
            case "BONUS" -> Material.EMERALD;
            case "UPGRADE" -> Material.GOLD_BLOCK;
            default -> Material.PAPER;
        };
        String title = switch (transaction.type().toUpperCase(Locale.ROOT)) {
            case "DEPOSIT" -> "&aDeposit";
            case "WITHDRAW" -> "&cWithdraw";
            case "INTEREST" -> "&bInterest";
            case "BONUS" -> "&aBonus";
            case "UPGRADE" -> "&eUpgrade";
            default -> "&fTransaction";
        };
        List<String> lore = new ArrayList<>();
        lore.add("&7Amount: &f" + bankService.money(transaction.amount()));
        lore.add("&7Balance After: &f" + bankService.money(transaction.balanceAfter()));
        lore.add("&7Time: &f" + TIME_FORMAT.format(Instant.ofEpochSecond(transaction.createdAt())));
        if (transaction.note() != null && !transaction.note().isBlank()) {
            lore.add("&7Note: &f" + transaction.note());
        }
        return item(material, title, lore);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Text.color(name));
        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) {
            coloredLore.add(Text.color(line));
        }
        meta.setLore(coloredLore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private void fillFiller(Inventory inventory, FileConfiguration config) {
        if (!config.getBoolean("filler.enabled", true)) {
            return;
        }
        Material material = material(config.getString("filler.material", "GRAY_STAINED_GLASS_PANE"));
        String name = config.getString("filler.name", "&8");
        List<String> lore = config.getStringList("filler.lore");
        ItemStack filler = item(material, name, lore);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private BankGuiAction getAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String rawAction = item.getItemMeta().getPersistentDataContainer().get(BankKeys.ACTION, PersistentDataType.STRING);
        if (rawAction == null) {
            return null;
        }
        return parseAction(rawAction);
    }

    private FileConfiguration currentConfig(Player player) {
        return openGuiConfigs.getOrDefault(player.getUniqueId(), mainGui);
    }

    private GuiContext context(Player player) {
        BankAccount account = bankService.getAccount(player);
        BankLevel level = bankService.getEffectiveLevel(account.level());
        double purse = bankService.getWalletBalance(player);
        boolean upgradeUnlocked = bankService.hasUpgradeUnlocked(player);
        return new GuiContext(account, level, purse, upgradeUnlocked);
    }

    private long promptTimeoutMillis() {
        return Math.max(5L, plugin.getConfig().getLong("settings.custom-amount-timeout-seconds", 60L)) * 1000L;
    }

    private void playSound(Player player, FileConfiguration config, String path) {
        if (config == null || !config.getBoolean(path + ".enabled", false)) {
            return;
        }
        String rawSound = config.getString(path + ".name", "UI_BUTTON_CLICK");
        Sound sound;
        try {
            sound = Sound.valueOf(rawSound == null ? "UI_BUTTON_CLICK" : rawSound.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown GUI sound at " + path + ": " + rawSound);
            return;
        }
        float volume = (float) config.getDouble(path + ".volume", 1.0);
        float pitch = (float) config.getDouble(path + ".pitch", 1.0);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private BankGuiAction parseAction(String rawAction) {
        try {
            return BankGuiAction.valueOf(rawAction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown bank GUI action: " + rawAction);
            return BankGuiAction.NONE;
        }
    }

    private Material material(String rawMaterial) {
        Material material = Material.matchMaterial(rawMaterial == null ? "STONE" : rawMaterial);
        return material == null || material.isAir() ? Material.STONE : material;
    }

    /** Places an item at a 1-based configured slot, skipping out-of-range slots instead of clamping them. */
    private void setItemAt(Inventory inventory, int configuredSlot, ItemStack item) {
        int index = configuredSlot - 1;
        if (index < 0 || index >= inventory.getSize()) {
            return;
        }
        inventory.setItem(index, item);
    }

    private FileConfiguration loadGuiFile(String fileName) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "gui/" + fileName));
    }

    private void send(Player player, String message) {
        plugin.getMessageManager().sendRaw(player, message);
    }

    private void msg(Player player, String key, String fallback) {
        plugin.getMessageManager().send(player, key, fallback);
    }

    private record PendingAmount(BankGuiAction action, long createdAt) {
    }

    private record GuiContext(BankAccount account, BankLevel level, double purse, boolean upgradeUnlocked) {
    }

    private enum BankGuiType {
        MAIN,
        DEPOSIT,
        WITHDRAW,
        TRANSACTIONS,
        CONFIRM_UPGRADE
    }

    private record BankInventoryHolder(BankGuiType type) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class BankKeys {
        private static final org.bukkit.NamespacedKey ACTION = new org.bukkit.NamespacedKey("royalbank", "gui_action");
    }
}
