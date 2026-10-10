package com.mystipixel.royalbank.hooks;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

// com.willfp.* types are only touched behind the `present` guard, so the class loads without eco
public final class EcoHook {

    private final boolean present;

    public EcoHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /** {@code null} if the id can't be resolved. Vanilla ids (bare or {@code minecraft:}) skip eco. */
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
                }
            }
        }
        return null;
    }

    // eco config ids vary: ecoitems:, ecoitem: or the bare id
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
                return null; // custom namespace, resolved via eco
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
        Material material = Material.matchMaterial(raw);
        return (material != null && !material.isAir()) ? material : null;
    }
}
