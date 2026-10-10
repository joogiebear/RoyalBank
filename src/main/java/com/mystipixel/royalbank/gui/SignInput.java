package com.mystipixel.royalbank.gui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Text entry on a throwaway sign at the player's feet (Paper {@code openSign}, no NMS); the original
 * block is always put back. The callback runs on the main thread exactly once per opened prompt, with
 * the typed text or {@code null} when no answer is coming: the sign could not be placed, or the prompt
 * was abandoned (timeout, out of sign-edit range, world change, sign broken, another inventory opened).
 */
public final class SignInput implements Listener {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private static final long TIMEOUT_MILLIS = 60_000L;
    // past roughly this distance the server discards the sign's update
    private static final double MAX_DISTANCE_SQUARED = 8.0 * 8.0;

    private record Pending(UUID player, BlockData original, Consumer<String> callback, long deadline) {
    }

    private final JavaPlugin plugin;
    private final Map<Location, Pending> pending = new ConcurrentHashMap<>();

    public SignInput(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 10L, 10L);
    }

    /** {@code hints} fill lines 2-4; line 1 is what the player types. */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        // a new prompt supersedes an old one, whose sign still has to come down
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (entry.getValue().player().equals(player.getUniqueId())
                    && pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
        // opening a sign editor over a chest inventory is unreliable: close first, open next tick
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> openNow(player, hints, callback));
    }

    private void openNow(Player player, List<String> hints, Consumer<String> callback) {
        if (!player.isOnline()) {
            return;
        }
        Block block = signSpot(player);
        if (block == null) {
            callback.accept(null);
            return;
        }
        Location loc = block.getLocation();
        BlockData original = block.getBlockData();

        block.setType(Material.OAK_SIGN, false);
        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(original, false);
            callback.accept(null);
            return;
        }
        for (int i = 0; i < hints.size() && i < 3; i++) {
            sign.getSide(Side.FRONT).line(i + 1, AMP.deserialize(hints.get(i)));
        }
        sign.update(true, false);
        pending.put(loc, new Pending(player.getUniqueId(), original, callback,
                System.currentTimeMillis() + TIMEOUT_MILLIS));
        player.openSign(sign, Side.FRONT);
    }

    // Feet, else head; air preferred. Never a block entity (restore only puts back block data) or a
    // block another prompt is already using (its "original" would be that prompt's sign).
    private Block signSpot(Player player) {
        Block feet = player.getLocation().getBlock();
        Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block fallback = null;
        for (Block candidate : List.of(feet, head)) {
            if (pending.containsKey(candidate.getLocation())
                    || candidate.getY() < candidate.getWorld().getMinHeight()
                    || candidate.getY() >= candidate.getWorld().getMaxHeight()
                    || candidate.getState(false) instanceof org.bukkit.block.TileState) {
                continue;
            }
            if (candidate.getType().isAir()) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    // only over our own sign or air: anything else was placed since and would be destroyed
    private static void restore(Location loc, Pending p) {
        Block block = loc.getBlock();
        Material now = block.getType();
        if (now == Material.OAK_SIGN || now.isAir()) {
            block.setBlockData(p.original(), false);
        }
    }

    private void abandon(Location loc, Pending p) {
        if (!pending.remove(loc, p)) {
            return;                                  // answered or cleaned up meanwhile
        }
        restore(loc, p);
        Player player = Bukkit.getPlayer(p.player());
        if (player == null) {
            return;
        }
        // close a sign editor still on screen (timeout), but not another plugin's menu that replaced it
        if (showingOwnInventory(player)) {
            player.closeInventory();
        }
        p.callback().accept(null);
    }

    /** True when no menu other than the player's own inventory is open. */
    public static boolean showingOwnInventory(Player player) {
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            Location loc = entry.getKey();
            Pending p = entry.getValue();
            Player player = Bukkit.getPlayer(p.player());
            // distance before the block lookup, so a far chunk isn't loaded every half second
            if (player == null
                    || now > p.deadline()
                    || !player.getWorld().equals(loc.getWorld())
                    || player.getLocation().distanceSquared(loc.clone().add(0.5, 0.5, 0.5))
                            > MAX_DISTANCE_SQUARED
                    || loc.getBlock().getType() != Material.OAK_SIGN) {
                abandon(loc, p);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Location loc = event.getBlock().getLocation();
        Pending p = pending.remove(loc);
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            restore(loc, p);
            Player player = Bukkit.getPlayer(p.player());
            if (player != null) {
                p.callback().accept(input);
            }
        });
    }

    // another inventory replaces the sign editor client-side; abandon next tick, once it is open,
    // so the caller can see it
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (entry.getValue().player().equals(id)) {
                Bukkit.getScheduler().runTask(plugin, () -> abandon(entry.getKey(), entry.getValue()));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().player().equals(id)) {
                restore(entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });
    }

    /** Call on disable, or a stop mid-prompt leaves the sign in the world. */
    public void shutdown() {
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
    }
}
