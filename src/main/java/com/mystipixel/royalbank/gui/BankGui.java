package com.mystipixel.royalbank.gui;

import com.mystipixel.royalbank.RoyalBankPlugin;
import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.config.ItemRequirement;
import com.mystipixel.royalbank.config.RequirementType;
import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankTransaction;
import com.mystipixel.royalbank.gui.menu.MenuEffect;
import com.mystipixel.royalbank.gui.menu.MenuManager;
import com.mystipixel.royalbank.gui.menu.MenuSlot;
import com.mystipixel.royalbank.gui.menu.MenuTemplate;
import com.mystipixel.royalbank.service.BankService;
import com.mystipixel.royalbank.service.OperationResult;
import com.mystipixel.royalbank.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bank menus, rendered from EcoMenus-dialect {@code gui/*.yml} templates via the shared
 * {@link MenuManager}/{@link MenuTemplate} engine (consistent with RoyalAuctions/RoyalBazaar). Clicks
 * run the slot's {@code left-click}/{@code right-click} effect list; this class dispatches those bank
 * effects (open_menu, deposit, withdraw, upgrade, confirm_upgrade, claim_interest, close_inventory,
 * play_sound) and owns the chat "custom amount" flow.
 */
public final class BankGui implements Listener {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final RoyalBankPlugin plugin;
    private final BankService bankService;
    private final MenuManager menus;
    private final com.mystipixel.royalbank.hooks.EcoHook eco;
    private final Map<UUID, PendingAmount> pendingChatActions = new ConcurrentHashMap<>();

    public BankGui(RoyalBankPlugin plugin, BankService bankService) {
        this.plugin = plugin;
        this.bankService = bankService;
        this.menus = new MenuManager(plugin);
        this.eco = new com.mystipixel.royalbank.hooks.EcoHook();
    }

    public void reload() {
        menus.reload();
    }

    // ------------------------------------------------------------------ opening

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
        for (int i = 0; i < slots.size(); i++) {
            if (i < transactions.size()) {
                inventory.setItem(slots.get(i), transactionItem(transactions.get(i)));
            }
        }
    }

    /** Fill the confirm-upgrade content slots with an icon per required item, auto-generated from the tier. */
    private void fillUpgradeCosts(Player player, Inventory inventory, MenuTemplate template) {
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        List<ItemRequirement> requirements = bankService.nextUpgradeItemRequirements(player);
        for (int i = 0; i < slots.size() && i < requirements.size(); i++) {
            inventory.setItem(slots.get(i), costIcon(player, requirements.get(i)));
        }
    }

    /** The real (eco-resolved) item for a requirement, with have/need counts appended to its lore. */
    private ItemStack costIcon(Player player, ItemRequirement requirement) {
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
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            // Couldn't resolve a custom item — name the icon with its id so a wrong/not-yet-created id is obvious.
            if (!resolved && requirement.type() == RequirementType.ECOITEMS) {
                meta.setDisplayName(Text.color("&f" + requirement.customItemId()));
                lore.add(Text.color("&8(not loaded in eco)"));
            }
            lore.add(Text.color("&7Required: &f" + requirement.amount()));
            lore.add(Text.color((enough ? "&aYou have: &f" : "&cYou have: &f") + held + "&7/&f" + requirement.amount()));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BankHolder holder)) {
            return;
        }
        event.setCancelled(true); // bank menus are read-only surfaces; nothing can be moved

        MenuTemplate template = menus.get(holder.menuId());
        if (template == null) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= template.size()) {
            return; // a click in the player's own inventory
        }
        MenuSlot slot = template.slotAt(raw);
        if (slot == null) {
            return; // filler or a content (transaction) slot
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
                if (requirePermission(player, menuId, "royalbank.upgrade", "permission.upgrade",
                        "&cYou do not have permission to upgrade your bank.")) {
                    openConfirmUpgrade(player);
                }
            }
            case "confirm_upgrade" -> {
                if (requirePermission(player, menuId, "royalbank.upgrade", "permission.upgrade",
                        "&cYou do not have permission to upgrade your bank.")) {
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
        if (!requirePermission(player, menuId, "royalbank.deposit", "permission.deposit",
                "&cYou do not have permission to deposit.")) {
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
        if (!requirePermission(player, menuId, "royalbank.withdraw", "permission.withdraw",
                "&cYou do not have permission to withdraw.")) {
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
        pendingChatActions.put(player.getUniqueId(), new PendingAmount(kind, System.currentTimeMillis()));
        player.closeInventory();
        playSound(menus.get(menuId), player, "sounds.prompt");
        if (kind == PendingAmount.Kind.DEPOSIT) {
            msg(player, "prompt-deposit", "&aType the amount you want to deposit in chat. Type &ecancel &ato stop.");
        } else {
            msg(player, "prompt-withdraw", "&cType the amount you want to withdraw in chat. Type &ecancel &cto stop.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingAmount pending = pendingChatActions.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        // Only consume the chat line while the prompt is still fresh, so a stale prompt never swallows
        // a normal message or moves money later.
        if (System.currentTimeMillis() - pending.createdAt() > promptTimeoutMillis()) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleCustomAmount(player, pending.kind(), message));
    }

    private void handleCustomAmount(Player player, PendingAmount.Kind kind, String message) {
        if (!player.isOnline()) {
            return;
        }
        if (message.equalsIgnoreCase("cancel")) {
            msg(player, "custom-cancelled", "&eCustom bank action cancelled.");
            openMain(player);
            return;
        }
        String permission = kind == PendingAmount.Kind.DEPOSIT ? "royalbank.deposit" : "royalbank.withdraw";
        if (!player.hasPermission(permission)) {
            msg(player, kind == PendingAmount.Kind.DEPOSIT ? "permission.deposit" : "permission.withdraw",
                    kind == PendingAmount.Kind.DEPOSIT ? "&cYou do not have permission to deposit."
                            : "&cYou do not have permission to withdraw.");
            return;
        }
        Double parsed = com.mystipixel.royalbank.util.Amounts.parse(plugin, message);
        if (parsed == null) {
            msg(player, "custom-invalid-amount", "&cThat was not a valid amount. Use a number like &f5000&c or shorthand like &f5m&c (k/m/b/t), or type cancel next time.");
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
        // Nothing to track on close; the bank menus are stateless per-open.
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingChatActions.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ helpers

    private boolean requirePermission(Player player, String menuId, String permission, String messageKey, String fallback) {
        if (player.hasPermission(permission)) {
            return true;
        }
        msg(player, messageKey, fallback);
        playSound(menus.get(menuId), player, "sounds.fail");
        player.closeInventory();
        return false;
    }

    private void runAndRefresh(Player player, String menuId, OperationResult result) {
        if (result.message() != null && !result.message().isBlank()) {
            send(player, result.message());
        }
        playSound(menus.get(menuId), player, result.success() ? "sounds.success" : "sounds.fail");
        Bukkit.getScheduler().runTask(plugin, () -> openMain(player));
    }

    /** Expand a lore list, turning a {@code %upgrade_info%} line into one line per upgrade-description line. */
    private List<String> expandLore(Player player, List<String> lore) {
        List<String> out = new ArrayList<>();
        for (String line : lore) {
            if (line.contains("%upgrade_info%")) {
                for (String upgradeLine : bankService.describeNextUpgrade(player).split("\\n")) {
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
        map.put("next_interest", bankService.money(bankService.calculateInterest(account.balance(), level)));
        map.put("interest_time", bankService.getInterestTimeRemaining(player));
        map.put("max_interest", bankService.money(level.maxInterest()));
        map.put("upgrade_unlock_balance", bankService.money(bankService.getUpgradeUnlockCombinedBalance()));
        map.put("combined_balance", bankService.money(account.balance() + purse));
        return map;
    }

    private ItemStack transactionItem(BankTransaction transaction) {
        String type = transaction.type().toUpperCase(Locale.ROOT);
        Material material = switch (type) {
            case "DEPOSIT" -> Material.LIME_DYE;
            case "WITHDRAW" -> Material.RED_DYE;
            case "INTEREST" -> Material.CLOCK;
            case "BONUS" -> Material.EMERALD;
            case "UPGRADE" -> Material.GOLD_BLOCK;
            default -> Material.PAPER;
        };
        String title = switch (type) {
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
        return simpleItem(material, title, lore);
    }

    private ItemStack simpleItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Text.color(name));
        List<String> colored = new ArrayList<>();
        for (String line : lore) {
            colored.add(Text.color(line));
        }
        meta.setLore(colored);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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

    private void send(Player player, String message) {
        plugin.getMessageManager().sendRaw(player, message);
    }

    private void msg(Player player, String key, String fallback) {
        plugin.getMessageManager().send(player, key, fallback);
    }

    private record PendingAmount(Kind kind, long createdAt) {
        enum Kind { DEPOSIT, WITHDRAW }
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
