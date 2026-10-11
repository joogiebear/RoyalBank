package com.mystipixel.royalbank.gui;

import com.mystipixel.royalbank.RoyalBankPlugin;
import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.ItemRequirement;
import com.mystipixel.royalbank.config.RequirementType;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.gui.menu.ItemSpec;
import com.mystipixel.royalbank.gui.menu.MenuEffect;
import com.mystipixel.royalbank.gui.menu.MenuManager;
import com.mystipixel.royalbank.gui.menu.MenuSlot;
import com.mystipixel.royalbank.gui.menu.MenuTemplate;
import com.mystipixel.royalbank.message.MessageManager;
import com.mystipixel.royalbank.service.BankService;
import com.mystipixel.royalbank.service.OperationResult;
import com.mystipixel.royalbank.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Effects dispatched here: open_menu, deposit, withdraw, upgrade, confirm_upgrade, claim_interest,
// close_inventory, play_sound.
public final class BankGui implements Listener {

    private final RoyalBankPlugin plugin;
    private final BankService bankService;
    private final MenuManager menus;
    private final com.mystipixel.royalbank.hooks.EcoHook eco;
    private final TextInput textInput;
    private final MessageManager messages;

    public BankGui(RoyalBankPlugin plugin, BankService bankService, TextInput textInput) {
        this.plugin = plugin;
        this.bankService = bankService;
        this.messages = plugin.getMessageManager();
        this.menus = new MenuManager(plugin);
        this.eco = new com.mystipixel.royalbank.hooks.EcoHook();
        this.textInput = textInput;
    }

    public void reload() {
        menus.reload();
    }

    public void openMain(Player player) {
        open(player, MenuManager.MAIN);
    }

    public void openDeposit(Player player) {
        open(player, MenuManager.DEPOSIT);
    }

    public void openWithdraw(Player player) {
        open(player, MenuManager.WITHDRAW);
    }

    public void openConfirmUpgrade(Player player) {
        open(player, MenuManager.CONFIRM_UPGRADE);
    }

    public void openTransactions(Player player) {
        open(player, MenuManager.TRANSACTIONS);
    }

    private void open(Player player, String menuId) {
        MenuTemplate template = menus.get(menuId);
        if (template == null) {
            plugin.getLogger().warning("Bank menu '" + menuId + "' is not loaded.");
            return;
        }
        Inventory inventory = Bukkit.createInventory(new BankHolder(menuId), template.size(), Text.color(template.title()));
        template.applyFiller(inventory);

        GuiContext context = context(player);
        Map<String, String> placeholders = placeholders(player, context);

        for (MenuSlot slot : template.slots()) {
            inventory.setItem(slot.index(), renderSlot(player, context, placeholders, slot));
        }

        if (menuId.equals(MenuManager.TRANSACTIONS)) {
            fillTransactions(player, inventory, template);
        } else if (menuId.equals(MenuManager.CONFIRM_UPGRADE)) {
            fillUpgradeCosts(player, inventory, template);
        }

        player.openInventory(inventory);
        playSound(template, player, "sounds.open");
    }

    private ItemStack renderSlot(Player player, GuiContext context, Map<String, String> placeholders, MenuSlot slot) {
        boolean locked = "upgrade".equalsIgnoreCase(slot.id()) && !context.upgradeUnlocked();
        var spec = locked && slot.lockedItem() != null ? slot.lockedItem() : slot.item();
        List<String> lore = locked && slot.lockedItem() != null ? slot.lockedLore() : slot.lore();
        return spec.build(eco, placeholders, expandLore(player, lore));
    }

    private void fillTransactions(Player player, Inventory inventory, MenuTemplate template) {
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        List<BankTransaction> transactions = bankService.getRecentTransactions(player, slots.size());
        ConfigurationSection entry = template.config().getConfigurationSection("entry");
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern(entry.getString("time-format"))
                .withZone(ZoneId.systemDefault());
        for (int i = 0; i < slots.size(); i++) {
            if (i < transactions.size()) {
                inventory.setItem(slots.get(i), transactionItem(entry, timeFormat, transactions.get(i)));
            }
        }
    }

    private void fillUpgradeCosts(Player player, Inventory inventory, MenuTemplate template) {
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        List<ItemRequirement> requirements = bankService.nextUpgradeItemRequirements(player);
        ConfigurationSection text = template.config().getConfigurationSection("cost-item");
        for (int i = 0; i < slots.size() && i < requirements.size(); i++) {
            inventory.setItem(slots.get(i), costIcon(player, text, requirements.get(i)));
        }
    }

    private ItemStack costIcon(Player player, ConfigurationSection text, ItemRequirement requirement) {
        String id = requirement.type() == RequirementType.VANILLA
                ? requirement.material().name()
                : requirement.customItemId();
        ItemStack icon = eco.resolve(id, requirement.amount());
        boolean resolved = icon != null && !icon.getType().isAir();
        if (!resolved) {
            Material fallback = requirement.type() == RequirementType.VANILLA && requirement.material() != null
                    ? requirement.material() : Material.PAPER;
            icon = new ItemStack(fallback);
        }
        icon.setAmount(Math.max(1, Math.min(64, requirement.amount())));
        int held = bankService.heldItemCount(player, requirement);
        boolean enough = held >= requirement.amount();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            Map<String, String> values = MessageManager.pairs(
                    "id", String.valueOf(requirement.customItemId()),
                    "amount", String.valueOf(requirement.amount()),
                    "held", String.valueOf(held));
            // show the raw id so a wrong or not-yet-created id is obvious
            if (!resolved && requirement.type() == RequirementType.ECOITEMS) {
                meta.displayName(Text.item(placeholders(text.getString("unresolved-name"), values)));
                lore.addAll(Text.items(placeholders(text.getStringList("unresolved-lore"), values)));
            }
            lore.addAll(Text.items(placeholders(text.getStringList(enough ? "lore-enough" : "lore-missing"), values)));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BankHolder holder)) {
            return;
        }
        event.setCancelled(true);

        MenuTemplate template = menus.get(holder.menuId());
        if (template == null) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= template.size()) {
            return;
        }
        MenuSlot slot = template.slotAt(raw);
        if (slot == null) {
            return;
        }
        List<MenuEffect> effects = event.isRightClick() && !slot.rightClick().isEmpty()
                ? slot.rightClick() : slot.leftClick();
        for (MenuEffect effect : effects) {
            dispatch(player, holder.menuId(), effect);
        }
    }

    private void dispatch(Player player, String menuId, MenuEffect effect) {
        switch (effect.id().toLowerCase(Locale.ROOT)) {
            case "open_menu" -> open(player, effect.argString("menu", MenuManager.MAIN));
            case "deposit" -> handleDeposit(player, menuId, effect.argString("amount", "custom"));
            case "withdraw" -> handleWithdraw(player, menuId, effect.argString("amount", "custom"));
            case "upgrade" -> {
                if (requirePermission(player, menuId, "royalbank.upgrade", "permission.upgrade")) {
                    openConfirmUpgrade(player);
                }
            }
            case "confirm_upgrade" -> {
                if (requirePermission(player, menuId, "royalbank.upgrade", "permission.upgrade")) {
                    runAndRefresh(player, menuId, bankService.upgrade(player));
                }
            }
            case "claim_interest" -> runAndRefresh(player, menuId, bankService.claimInterest(player, true));
            case "close_inventory", "close" -> player.closeInventory();
            case "play_sound" -> playRawSound(player, effect.argString("sound", "ui_button_click"),
                    (float) effect.argDouble("volume", 1.0), (float) effect.argDouble("pitch", 1.0));
            default -> plugin.getLogger().warning("Unknown bank menu effect: " + effect.id());
        }
    }

    private void handleDeposit(Player player, String menuId, String amountArg) {
        if (!requirePermission(player, menuId, "royalbank.deposit", "permission.deposit")) {
            return;
        }
        double wallet = bankService.getWalletBalance(player);
        Double amount = switch (amountArg.toLowerCase(Locale.ROOT)) {
            case "all" -> wallet;
            case "half" -> wallet / 2.0;
            case "custom" -> null; // prompt below
            default -> com.mystipixel.royalbank.util.Amounts.parse(plugin, amountArg);
        };
        if ("custom".equalsIgnoreCase(amountArg)) {
            promptForAmount(player, menuId, PendingAmount.Kind.DEPOSIT);
            return;
        }
        if (amount == null) {
            plugin.getLogger().warning("Bank deposit effect had an invalid amount: " + amountArg);
            return;
        }
        runAndRefresh(player, menuId, bankService.deposit(player, amount));
    }

    private void handleWithdraw(Player player, String menuId, String amountArg) {
        if (!requirePermission(player, menuId, "royalbank.withdraw", "permission.withdraw")) {
            return;
        }
        double balance = bankService.getAccount(player).balance();
        Double amount = switch (amountArg.toLowerCase(Locale.ROOT)) {
            case "all" -> balance;
            case "half" -> balance / 2.0;
            case "twenty" -> balance * 0.20;
            case "custom" -> null;
            default -> com.mystipixel.royalbank.util.Amounts.parse(plugin, amountArg);
        };
        if ("custom".equalsIgnoreCase(amountArg)) {
            promptForAmount(player, menuId, PendingAmount.Kind.WITHDRAW);
            return;
        }
        if (amount == null) {
            plugin.getLogger().warning("Bank withdraw effect had an invalid amount: " + amountArg);
            return;
        }
        runAndRefresh(player, menuId, bankService.withdraw(player, amount));
    }

    private void promptForAmount(Player player, String menuId, PendingAmount.Kind kind) {
        playSound(menus.get(menuId), player, "sounds.prompt");
        List<String> title = kind == PendingAmount.Kind.DEPOSIT
                ? messages.lines("input.deposit")
                : messages.lines("input.withdraw");
        textInput.request(player, title, typed -> {
            if (typed == null) {
                // nothing was moved; only put the bank back if nothing else has the screen
                if (canReopen(player)) {
                    messages.send(player, "custom-cancelled");
                    openMain(player);
                }
                return;
            }
            handleCustomAmount(player, kind, typed);
        });
    }

    // after a null answer the player may have quit, died, opened another menu or started a newer prompt
    private boolean canReopen(Player player) {
        if (!player.isOnline() || player.isDead() || textInput.isWaiting(player)) {
            return false;
        }
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    private void handleCustomAmount(Player player, PendingAmount.Kind kind, String message) {
        if (!player.isOnline()) {
            return;
        }
        if (message.isBlank() || message.equalsIgnoreCase("cancel")) {
            messages.send(player, "custom-cancelled");
            openMain(player);
            return;
        }
        if (kind == PendingAmount.Kind.DEPOSIT && !player.hasPermission("royalbank.deposit")) {
            messages.send(player, "permission.deposit");
            return;
        }
        if (kind == PendingAmount.Kind.WITHDRAW && !player.hasPermission("royalbank.withdraw")) {
            messages.send(player, "permission.withdraw");
            return;
        }
        Double parsed = com.mystipixel.royalbank.util.Amounts.parse(plugin, message);
        if (parsed == null) {
            messages.send(player, "custom-invalid-amount");
            openMain(player);
            return;
        }
        OperationResult result = kind == PendingAmount.Kind.DEPOSIT
                ? bankService.deposit(player, parsed)
                : bankService.withdraw(player, parsed);
        runAndRefresh(player, MenuManager.MAIN, result);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // bank menus are stateless per open
    }

    private boolean requirePermission(Player player, String menuId, String permission, String messageKey) {
        if (player.hasPermission(permission)) {
            return true;
        }
        messages.send(player, messageKey);
        playSound(menus.get(menuId), player, "sounds.fail");
        player.closeInventory();
        return false;
    }

    private void runAndRefresh(Player player, String menuId, OperationResult result) {
        messages.sendResult(player, result);
        playSound(menus.get(menuId), player, result.success() ? "sounds.success" : "sounds.fail");
        Bukkit.getScheduler().runTask(plugin, () -> openMain(player));
    }

    // a %upgrade_info% line becomes one line per upgrade-description line
    private List<String> expandLore(Player player, List<String> lore) {
        List<String> out = new ArrayList<>();
        for (String line : lore) {
            if (line.contains("%upgrade_info%")) {
                for (String upgradeLine : bankService.describeNextUpgrade(player)) {
                    out.add(line.replace("%upgrade_info%", upgradeLine));
                }
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private Map<String, String> placeholders(Player player, GuiContext context) {
        BankAccount account = context.account();
        BankLevel level = context.level();
        double purse = context.purse();
        double bankSpace = Math.max(0.0, level.maxBalance() - account.balance());

        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", player.getName());
        map.put("balance", bankService.money(account.balance()));
        map.put("max_balance", bankService.money(level.maxBalance()));
        map.put("bank_space", bankService.money(bankSpace));
        map.put("purse", bankService.money(purse));
        map.put("half_purse", bankService.money(purse / 2.0));
        map.put("half_balance", bankService.money(account.balance() / 2.0));
        map.put("twenty_balance", bankService.money(account.balance() * 0.20));
        map.put("account_level", String.valueOf(level.level()));
        map.put("account_name", level.name());
        map.put("next_interest", bankService.money(bankService.nextInterest(account, level)));
        map.put("interest_time", bankService.getInterestTimeRemaining(player));
        map.put("max_interest", bankService.money(level.maxInterest()));
        map.put("upgrade_unlock_balance", bankService.money(bankService.getUpgradeUnlockCombinedBalance()));
        map.put("combined_balance", bankService.money(account.balance() + purse));
        return map;
    }

    // the item comes from entry.items.<type>, or entry.items.default for types not listed
    private ItemStack transactionItem(ConfigurationSection entry, DateTimeFormatter timeFormat, BankTransaction transaction) {
        String type = transaction.type().toLowerCase(Locale.ROOT);
        String spec = entry.getString(entry.isString("items." + type) ? "items." + type : "items.default");
        boolean hasNote = transaction.note() != null && !transaction.note().isBlank();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("amount", bankService.money(transaction.amount()));
        values.put("balance_after", bankService.money(transaction.balanceAfter()));
        values.put("time", timeFormat.format(Instant.ofEpochSecond(transaction.createdAt())));
        values.put("note", hasNote ? transaction.note() : "");
        List<String> lore = new ArrayList<>();
        for (String line : entry.getStringList("lore")) {
            if (hasNote || !line.contains("%note%")) {
                lore.add(line);
            }
        }
        return ItemSpec.parse(spec).build(values, lore);
    }

    private static List<String> placeholders(List<String> lines, Map<String, String> values) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(placeholders(line, values));
        }
        return out;
    }

    private static String placeholders(String line, Map<String, String> values) {
        String out = line == null ? "" : line;
        for (Map.Entry<String, String> value : values.entrySet()) {
            out = out.replace("%" + value.getKey() + "%", value.getValue());
        }
        return out;
    }

    private GuiContext context(Player player) {
        BankAccount account = bankService.getAccount(player);
        BankLevel level = bankService.getEffectiveLevel(account.level());
        double purse = bankService.getWalletBalance(player);
        boolean upgradeUnlocked = bankService.hasUpgradeUnlocked(player);
        return new GuiContext(account, level, purse, upgradeUnlocked);
    }

    private void playSound(MenuTemplate template, Player player, String path) {
        if (template == null) {
            return;
        }
        FileConfiguration config = template.config();
        if (!config.getBoolean(path + ".enabled", false)) {
            return;
        }
        playRawSound(player, config.getString(path + ".name", "ui_button_click"),
                (float) config.getDouble(path + ".volume", 1.0), (float) config.getDouble(path + ".pitch", 1.0));
    }

    private void playRawSound(Player player, String rawSound, float volume, float pitch) {
        try {
            Sound sound = Sound.valueOf(rawSound.toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown GUI sound: " + rawSound);
        }
    }

    private static final class PendingAmount {
        enum Kind { DEPOSIT, WITHDRAW }

        private PendingAmount() {
        }
    }

    private record GuiContext(BankAccount account, BankLevel level, double purse, boolean upgradeUnlocked) {
    }

    private record BankHolder(String menuId) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
