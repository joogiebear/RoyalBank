package com.mystipixel.royalbank.config;

import org.bukkit.Material;

import java.util.Locale;

public record ItemRequirement(
        RequirementType type,
        Material material,
        String customItemId,
        int amount
) {
    /** Human-readable cost, e.g. "100x Enchanted Gold Block" or "64x Diamond" (never the raw id). */
    public String displayName() {
        String base = type == RequirementType.VANILLA ? material.name() : customItemId;
        return amount + "x " + prettify(base);
    }

    /** Turn a namespaced id or enum name (e.g. "ecoitems:enchanted_gold_block", "GOLD_BLOCK") into title case. */
    private static String prettify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Item";
        }
        String base = raw;
        int colon = base.lastIndexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        StringBuilder out = new StringBuilder();
        for (String part : base.replace('-', '_').split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? raw : out.toString();
    }
}
