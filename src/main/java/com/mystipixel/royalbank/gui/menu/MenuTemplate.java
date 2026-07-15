package com.mystipixel.royalbank.gui.menu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A single bank menu loaded from a {@code gui/*.yml} file in the EcoMenus dialect shared with the rest
 * of the Royal suite: {@code title}, {@code rows}, a page {@code mask} (filler pattern where {@code 0}
 * marks dynamic-content slots, e.g. the transaction list), and fixed {@code slots} — each with a
 * {@code location: {row, column}} (1-based), an inline {@code item} spec, {@code lore}, and
 * {@code left-click}/{@code right-click} effect lists.
 *
 * <p>A slot may also carry a semantic {@code id} plus a {@code locked-item}/{@code locked-lore}
 * alternate appearance (used by the upgrade button before it is unlocked).
 */
public final class MenuTemplate {

    private final String title;
    private final int rows;
    private final ItemStack maskFiller;        // null when no mask / all-content
    private final List<Integer> contentSlots;  // indices marked 0 in the mask
    private final List<MenuSlot> slots;
    private final FileConfiguration source;    // retained for the bank-specific `sounds:` block

    private MenuTemplate(String title, int rows, ItemStack maskFiller, List<Integer> contentSlots,
                         List<MenuSlot> slots, FileConfiguration source) {
        this.title = title;
        this.rows = rows;
        this.maskFiller = maskFiller;
        this.contentSlots = contentSlots;
        this.slots = slots;
        this.source = source;
    }

    /** The raw menu config, for bank-specific extras (e.g. the {@code sounds:} section). */
    public FileConfiguration config() {
        return source;
    }

    public static MenuTemplate load(File file, String defaultTitle, int defaultRows) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String title = cfg.getString("title", defaultTitle);
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", defaultRows)));
        int size = rows * 9;

        // --- mask (filler + content slots) ---
        ItemStack filler = null;
        List<Integer> contentSlots = new ArrayList<>();
        ConfigurationSection mask = firstPageMask(cfg);
        if (mask != null) {
            List<String> maskItems = mask.getStringList("items");
            List<String> pattern = mask.getStringList("pattern");
            if (!maskItems.isEmpty()) {
                filler = ItemSpec.parse(maskItems.get(0) + " name:\" \"").build(Map.of(), List.of());
            }
            for (int r = 0; r < pattern.size() && r < rows; r++) {
                String line = pattern.get(r);
                for (int c = 0; c < 9 && c < line.length(); c++) {
                    if (line.charAt(c) == '0') {
                        contentSlots.add(r * 9 + c);
                    }
                }
            }
        }

        // --- fixed slots ---
        List<MenuSlot> slots = new ArrayList<>();
        for (Map<?, ?> raw : cfg.getMapList("slots")) {
            MenuSlot slot = parseSlot(raw, size);
            if (slot != null) {
                slots.add(slot);
            }
        }

        return new MenuTemplate(title, rows, filler, contentSlots, slots, cfg);
    }

    private static ConfigurationSection firstPageMask(FileConfiguration cfg) {
        List<Map<?, ?>> pages = cfg.getMapList("pages");
        if (pages.isEmpty()) {
            return null;
        }
        Object mask = pages.get(0).get("mask");
        if (mask instanceof ConfigurationSection cs) {
            return cs;
        }
        if (mask instanceof Map<?, ?> m) {
            YamlConfiguration tmp = new YamlConfiguration();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                tmp.set(String.valueOf(e.getKey()), e.getValue());
            }
            return tmp;
        }
        return null;
    }

    private static MenuSlot parseSlot(Map<?, ?> raw, int size) {
        Object itemObj = raw.get("item");
        if (itemObj == null) {
            return null;
        }
        int index = slotIndex(raw, size);
        if (index < 0) {
            return null;
        }
        String id = raw.get("id") == null ? null : String.valueOf(raw.get("id"));
        Object lockedItemObj = raw.get("locked-item");
        ItemSpec lockedItem = lockedItemObj == null ? null : ItemSpec.parse(String.valueOf(lockedItemObj));
        return new MenuSlot(index, id,
                ItemSpec.parse(String.valueOf(itemObj)), stringList(raw.get("lore")),
                lockedItem, stringList(raw.get("locked-lore")),
                MenuEffect.parseList(castMapList(raw.get("left-click"))),
                MenuEffect.parseList(castMapList(raw.get("right-click"))));
    }

    /**
     * Resolve a slot's 1-based {@code row}/{@code column} into a 0-based inventory index. Row/column sit
     * directly on the slot (the eco-menus convention); a legacy nested {@code location: {row, column}}
     * is still accepted as a fallback.
     */
    private static int slotIndex(Map<?, ?> raw, int size) {
        Object rowObj = raw.get("row");
        Object colObj = raw.get("column");
        if (rowObj == null && colObj == null) {
            Object loc = raw.get("location");
            if (loc instanceof ConfigurationSection cs) {
                rowObj = cs.get("row");
                colObj = cs.get("column");
            } else if (loc instanceof Map<?, ?> m) {
                rowObj = m.get("row");
                colObj = m.get("column");
            }
        }
        int index = (intOf(rowObj, 1) - 1) * 9 + (intOf(colObj, 1) - 1);
        return index >= 0 && index < size ? index : -1;
    }

    // ------------------------------------------------------------------ accessors / rendering

    public String title() {
        return title;
    }

    public int size() {
        return rows * 9;
    }

    public List<Integer> contentSlots() {
        return contentSlots;
    }

    public List<MenuSlot> slots() {
        return slots;
    }

    public MenuSlot slotAt(int index) {
        for (MenuSlot slot : slots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
    }

    /** Paint the mask filler across every non-content slot. */
    public void applyFiller(Inventory inv) {
        if (maskFiller == null) {
            return;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (!contentSlots.contains(i)) {
                inv.setItem(i, maskFiller.clone());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                out.add(String.valueOf(e));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> castMapList(Object o) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
