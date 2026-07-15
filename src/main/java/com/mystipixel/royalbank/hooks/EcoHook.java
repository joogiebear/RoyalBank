package com.mystipixel.royalbank.hooks;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Optional item resolution via the eco platform, so eco item ids (e.g. {@code ecoitems:corrupt_soil})
 * can be shown as GUI icons — most notably the auto-generated upgrade-cost icons.
 *
 * <p>Every {@code com.willfp.*} type is only touched after the {@link #present} guard, so if eco is
 * absent the JVM never links it and this class degrades to vanilla-only.
 */
public final class EcoHook {

    private final boolean present;

    public EcoHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /**
     * Resolve an id to a display {@link ItemStack}, or {@code null} if it can't be resolved. Vanilla ids
     * (bare or {@code minecraft:}) go straight to Bukkit; custom namespaces resolve through eco.
     */
    public ItemStack resolve(String id, int amount) {
        if (id == null) {
            return null;
        }
        Material vanilla = vanillaMaterial(id);
        if (vanilla != null) {
            return new ItemStack(vanilla, Math.max(1, amount));
        }
        if (present) {
            for (String candidate : lookupCandidates(id)) {
                try {
                    TestableItem test = Items.lookup(candidate);
                    ItemStack item = test.getItem();
                    if (item != null && !item.getType().isAir()) {
                        item = item.clone();
                        item.setAmount(Math.max(1, amount));
                        return item;
                    }
                } catch (Throwable ignored) {
                    // try the next candidate form
                }
            }
        }
        return null;
    }

    /** eco config ids vary (ecoitems: vs ecoitem: vs the bare id); try the common forms. */
    private static String[] lookupCandidates(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) {
            return new String[]{id};
        }
        String bare = id.substring(colon + 1);
        return new String[]{id, "ecoitem:" + bare, "ecoitems:" + bare, bare};
    }

    private Material vanillaMaterial(String id) {
        String raw = id;
        if (id.contains(":")) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!ns.equalsIgnoreCase("minecraft")) {
                return null; // custom namespace — resolve via eco
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
        Material material = Material.matchMaterial(raw);
        return (material != null && !material.isAir()) ? material : null;
    }
}
