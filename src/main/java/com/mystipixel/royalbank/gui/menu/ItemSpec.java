package com.mystipixel.royalbank.gui.menu;

import com.mystipixel.royalbank.hooks.EcoHook;
import com.mystipixel.royalbank.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses the EcoMenus inline item syntax used across the Royal suite, e.g.
 * <pre>gold_block hide_attributes name:"&6Bank Upgrades"</pre>
 * The first token is an item lookup — a vanilla {@link Material}, or an {@code ecoitems:...} id resolved
 * through eco when it's installed. The rest are flags ({@code hide_enchants}, {@code hide_attributes})
 * and {@code key:"value"} modifiers.
 *
 * <p>Player heads follow the eco convention:
 * <pre>player_head texture:&lt;base64&gt;      # a custom head from a base64 texture value
 * player_head head:&lt;player&gt;             # a specific player's head (e.g. head:%player%)</pre>
 *
 * <p>Names/lore/head-owner may contain {@code %placeholders%}, filled at render time via {@link #build}.
 */
public final class ItemSpec {

    private final String lookupId;
    private final String rawName;      // may be null
    private final String texture;      // base64 head texture, may be null
    private final String head;         // player-head owner name/%placeholder%, may be null
    private final boolean hideEnchants;
    private final boolean hideAttributes;

    private ItemSpec(String lookupId, String rawName, String texture, String head,
                     boolean hideEnchants, boolean hideAttributes) {
        this.lookupId = lookupId;
        this.rawName = rawName;
        this.texture = texture;
        this.head = head;
        this.hideEnchants = hideEnchants;
        this.hideAttributes = hideAttributes;
    }

    public static ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ItemSpec("stone", null, null, null, false, false);
        }
        List<String> tokens = tokenize(raw.trim());
        String lookup = tokens.isEmpty() ? "stone" : tokens.get(0);
        String name = null;
        String texture = null;
        String head = null;
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
            } else if (t.regionMatches(true, 0, "texture:", 0, 8)) {
                texture = stripQuotes(t.substring(8));
            } else if (t.regionMatches(true, 0, "head:", 0, 5)) {
                head = stripQuotes(t.substring(5));
            }
        }
        return new ItemSpec(lookup, name, texture, head, hideEnch, hideAttr);
    }

    /** Vanilla-only build (no eco resolution); used for mask filler and any context without an EcoHook. */
    public ItemStack build(Map<String, String> placeholders, List<String> lore) {
        return build(null, placeholders, lore);
    }

    /** Build the stack, resolving eco item ids via {@code eco} when present, and filling {@code %placeholders%}. */
    public ItemStack build(EcoHook eco, Map<String, String> placeholders, List<String> lore) {
        String id = apply(lookupId, placeholders);
        ItemStack item = eco != null ? eco.resolve(id, 1) : null;
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(vanillaMaterial(id));
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (rawName != null) {
                meta.displayName(Text.item(apply(rawName, placeholders)));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(Text.item(apply(line, placeholders)));
                }
                meta.lore(lines);
            }
            if (hideEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            if (hideAttributes) {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            }
            applyHeadTexture(item, meta, placeholders);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Apply a base64 {@code texture:} or a {@code head:} owner to a player-head, the eco-suite way. */
    private void applyHeadTexture(ItemStack item, ItemMeta meta, Map<String, String> placeholders) {
        if (item.getType() != Material.PLAYER_HEAD || !(meta instanceof SkullMeta skull)) {
            return;
        }
        try {
            if (texture != null && !texture.isBlank()) {
                com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
            } else if (head != null && !head.isBlank()) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(apply(head, placeholders)));
            }
        } catch (Throwable ignored) {
            // a malformed texture must never break the menu
        }
    }

    private static Material vanillaMaterial(String id) {
        String raw = id;
        if (id.contains(":")) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!ns.equalsIgnoreCase("minecraft")) {
                return Material.STONE; // unknown custom id and no eco to resolve it
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
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
