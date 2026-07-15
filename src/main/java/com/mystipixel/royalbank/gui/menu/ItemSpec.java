package com.mystipixel.royalbank.gui.menu;

import com.mystipixel.royalbank.util.Text;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the EcoMenus inline item syntax used across the Royal suite, e.g.
 * <pre>gold_block hide_attributes name:"&6Bank Upgrades"</pre>
 * The first token is a vanilla {@link Material}; the rest are flags ({@code hide_enchants},
 * {@code hide_attributes}) and {@code name:"..."}. Lore is supplied separately from the slot's
 * {@code lore:} list. Names/lore may contain {@code %placeholders%}, filled at render time via
 * {@link #build}.
 */
public final class ItemSpec {

    private final Material material;
    private final String rawName;      // may be null
    private final boolean hideEnchants;
    private final boolean hideAttributes;

    private ItemSpec(Material material, String rawName, boolean hideEnchants, boolean hideAttributes) {
        this.material = material;
        this.rawName = rawName;
        this.hideEnchants = hideEnchants;
        this.hideAttributes = hideAttributes;
    }

    public static ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ItemSpec(Material.STONE, null, false, false);
        }
        List<String> tokens = tokenize(raw.trim());
        Material material = matchMaterial(tokens.isEmpty() ? "stone" : tokens.get(0));
        String name = null;
        boolean hideEnch = false;
        boolean hideAttr = false;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("hide_enchants")) {
                hideEnch = true;
            } else if (t.equalsIgnoreCase("hide_attributes")) {
                hideAttr = true;
            } else if (t.regionMatches(true, 0, "name:", 0, 5)) {
                name = stripQuotes(t.substring(5));
            }
        }
        return new ItemSpec(material, name, hideEnch, hideAttr);
    }

    /** Build the stack, filling {@code %placeholders%} in name/lore. */
    public ItemStack build(Map<String, String> placeholders, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (rawName != null) {
                meta.setDisplayName(Text.color(apply(rawName, placeholders)));
            }
            if (lore != null && !lore.isEmpty()) {
                List<String> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(Text.color(apply(line, placeholders)));
                }
                meta.setLore(lines);
            }
            if (hideEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            if (hideAttributes) {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Material matchMaterial(String raw) {
        Material material = Material.matchMaterial(raw);
        return material == null || material.isAir() ? Material.STONE : material;
    }

    /** Split on spaces but keep quoted segments (so name:"a b c" stays one token). */
    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                cur.append(ch);
            } else if (ch == ' ' && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("%" + e.getKey() + "%", e.getValue());
        }
        return result;
    }
}
