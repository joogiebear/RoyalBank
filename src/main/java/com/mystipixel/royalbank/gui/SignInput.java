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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sign-based text entry, ported from RoyalTrade (the suite's shared pattern — the bazaar uses it
 * too): a throwaway sign is placed at the player's feet, opened with Paper's {@code openSign}, and
 * the top line read back through {@link SignChangeEvent}. The original block is always put back.
 *
 * <p>Only official Paper API, no NMS or packets, so it survives version changes.
 *
 * <p>The callback runs on the main thread and receives the typed text, or {@code null} if the sign
 * could not be opened — callers need that to put the player back where they were rather than leave
 * them staring at nothing.
 */
public final class SignInput implements Listener {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private record Pending(UUID player, BlockData original, Consumer<String> callback) {
    }

    private final JavaPlugin plugin;
    private final Map<Location, Pending> pending = new ConcurrentHashMap<>();

    public SignInput(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Open a sign editor. {@code hints} fill lines 2-4; line 1 is what the player types. */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        pending.values().removeIf(p -> p.player().equals(player.getUniqueId()));
        // Opening a sign editor while a chest inventory is open is unreliable, so close first and
        // open the sign a tick later.
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
        pending.put(loc, new Pending(player.getUniqueId(), original, callback));
        player.openSign(sign, Side.FRONT);
    }

    /**
     * Where to put the throwaway sign: the player's feet, else the block at their head. Putting the
     * original back only restores block data, not a block entity's contents, so a block with one (a
     * sign's text, a banner's patterns) is never borrowed. Nor is a block another player's prompt is
     * already using, since its "original" would then be that prompt's sign. Air is preferred, so
     * nothing visible changes. {@code null} if neither spot will do.
     */
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Pending p = pending.remove(event.getBlock().getLocation());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> {
            block.setBlockData(p.original(), false);
            Player player = Bukkit.getPlayer(p.player());
            if (player != null) {
                p.callback().accept(input);
            }
        });
    }

    /** Never leave a sign behind because someone logged out mid-prompt. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().player().equals(id)) {
                entry.getKey().getBlock().setBlockData(entry.getValue().original(), false);
                return true;
            }
            return false;
        });
    }
}
