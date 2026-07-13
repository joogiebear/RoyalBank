package com.mystipixel.royalbank.security;

import com.mystipixel.royalbank.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RMT / laundering detection for money entering and leaving the bank.
 *
 * It cannot see purse-to-purse transfers (handle those in your economy plugin), but it watches the
 * bank for two strong signals: unusually large single transactions / balances, and abnormally fast
 * balance growth (money being parked). Alerts go to the console, online staff (royalbank.alerts),
 * and an optional Discord webhook. Velocity hits are recorded as flags for later review.
 *
 * All record/flag access happens on the main server thread; only the webhook POST runs async.
 */
public final class AbuseMonitor {
    public record Flag(UUID uuid, String username, String reason, long timestamp) {
    }

    private record Sample(long time, double delta) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Deque<Sample>> velocity = new ConcurrentHashMap<>();
    private final Map<UUID, Flag> flags = new ConcurrentHashMap<>();
    private volatile HttpClient httpClient;

    public AbuseMonitor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Call after a successful balance change. {@code amount} is the money moved; old/new are bank balances. */
    public void recordTransaction(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance) {
        checkLargeTransaction(username, action, amount, oldBalance, newBalance);
        checkVelocity(uuid, username, oldBalance, newBalance);
    }

    private void checkLargeTransaction(String username, String action, double amount, double oldBalance, double newBalance) {
        if (!plugin.getConfig().getBoolean("anti-abuse.large-transaction-alerts.enabled", true)) {
            return;
        }
        double txnThreshold = plugin.getConfig().getDouble("anti-abuse.large-transaction-alerts.transaction-threshold", 100_000_000.0);
        if (txnThreshold > 0 && amount >= txnThreshold) {
            alert("&c[Anti-Abuse] &e" + safe(username) + " &7" + action + " &f" + money(amount) + " &7(bank now " + money(newBalance) + ")");
        }
        double balThreshold = plugin.getConfig().getDouble("anti-abuse.large-transaction-alerts.balance-threshold", 500_000_000.0);
        if (balThreshold > 0 && oldBalance < balThreshold && newBalance >= balThreshold) {
            alert("&c[Anti-Abuse] &e" + safe(username) + " &7bank balance crossed &f" + money(balThreshold) + " &7(now " + money(newBalance) + ")");
        }
    }

    private void checkVelocity(UUID uuid, String username, double oldBalance, double newBalance) {
        if (!plugin.getConfig().getBoolean("anti-abuse.velocity-flagging.enabled", true)) {
            return;
        }
        double delta = newBalance - oldBalance;
        if (delta <= 0) {
            return;
        }
        long windowMinutes = Math.max(1L, plugin.getConfig().getLong("anti-abuse.velocity-flagging.window-minutes", 10L));
        long windowSeconds = windowMinutes * 60L;
        double growthThreshold = plugin.getConfig().getDouble("anti-abuse.velocity-flagging.growth-threshold", 1_000_000_000.0);
        long now = Instant.now().getEpochSecond();

        Deque<Sample> samples = velocity.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        samples.addLast(new Sample(now, delta));
        long cutoff = now - windowSeconds;
        while (!samples.isEmpty() && samples.peekFirst().time() < cutoff) {
            samples.removeFirst();
        }
        double total = samples.stream().mapToDouble(Sample::delta).sum();

        if (growthThreshold > 0 && total >= growthThreshold && !flags.containsKey(uuid)) {
            String reason = "Gained " + money(total) + " in bank within " + windowMinutes + "m";
            flags.put(uuid, new Flag(uuid, safe(username), reason, now));
            alert("&4[Anti-Abuse] FLAGGED &e" + safe(username) + " &7" + reason + ". Review with &f/bank admin flags");
        }
    }

    public void alert(String message) {
        plugin.getLogger().warning(ChatColor.stripColor(Text.color(message)));
        if (plugin.getConfig().getBoolean("anti-abuse.notify-staff", true)) {
            String formatted = Text.color(message);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("royalbank.alerts")) {
                    player.sendMessage(formatted);
                }
            }
        }
        sendWebhook(ChatColor.stripColor(Text.color(message)));
    }

    public Collection<Flag> getFlags() {
        return flags.values();
    }

    public boolean hasFlags() {
        return !flags.isEmpty();
    }

    public void clearFlags() {
        flags.clear();
    }

    public boolean clearFlag(UUID uuid) {
        return flags.remove(uuid) != null;
    }

    /** Drops a player's transient velocity window (e.g. on quit). Flags are kept for review. */
    public void clearVelocity(UUID uuid) {
        velocity.remove(uuid);
    }

    private void sendWebhook(String content) {
        String url = plugin.getConfig().getString("anti-abuse.discord-webhook", "");
        if (url == null || url.isBlank()) {
            return;
        }
        String json = "{\"content\":\"" + jsonEscape("[RoyalBank] " + content) + "\"}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                httpClient().send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not send anti-abuse Discord webhook: " + exception.getMessage());
            }
        });
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newHttpClient();
                }
            }
        }
        return httpClient;
    }

    private String money(double amount) {
        return Text.money(amount, plugin.getConfig().getString("settings.currency-symbol", "$"));
    }

    private static String safe(String username) {
        return username == null ? "Unknown" : username;
    }

    private static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }
}
