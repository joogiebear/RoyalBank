package com.mystipixel.royalbank.message;

import com.mystipixel.royalbank.service.OperationResult;
import com.mystipixel.royalbank.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-facing text from {@code messages.yml}, with {@code {name}} placeholders. The jar's copy backs
 * every key missing from the server's file, so the code carries no text of its own.
 */
public final class MessageManager {
    private final JavaPlugin plugin;
    private volatile YamlConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read the bundled messages.yml: " + exception.getMessage());
        }
        this.messages = loaded;
    }

    // a server file without a prefix keeps using config.yml's settings.messages-prefix, as it always has
    public String prefix() {
        YamlConfiguration current = messages;
        String prefix = current.getString("prefix", "");
        if (current.isSet("prefix")) {
            return prefix;
        }
        String bundled = current.getString("prefix");
        return plugin.getConfig().getString("settings.messages-prefix", bundled == null ? "" : bundled);
    }

    /** One line, placeholders given as alternating name/value pairs (names without braces). */
    public String get(String key, String... placeholders) {
        return get(key, pairs(placeholders));
    }

    public String get(String key, Map<String, String> placeholders) {
        // getString(key, def) would skip the bundled defaults
        return apply(messages.getString(key), placeholders);
    }

    public List<String> lines(String key, String... placeholders) {
        return lines(key, pairs(placeholders));
    }

    public List<String> lines(String key, Map<String, String> placeholders) {
        List<String> out = new ArrayList<>();
        for (String line : messages.getStringList(key)) {
            out.add(apply(line, placeholders));
        }
        return out;
    }

    public String render(OperationResult result) {
        return result.key() == null ? "" : get(result.key(), result.placeholders());
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(Text.color(prefix() + get(key, placeholders)));
    }

    public void sendLines(CommandSender sender, String key, String... placeholders) {
        sendEach(sender, lines(key, placeholders));
    }

    // already-rendered lines, one chat message each
    public void sendEach(CommandSender sender, List<String> lines) {
        String prefix = prefix();
        for (String line : lines) {
            sender.sendMessage(Text.color(prefix + line));
        }
    }

    // a result without a key (an auto-claim that paid nothing) sends nothing
    public void sendResult(CommandSender sender, OperationResult result) {
        if (result.key() == null) {
            return;
        }
        sender.sendMessage(Text.color(prefix() + render(result)));
    }

    public static Map<String, String> pairs(String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            map.put(placeholders[i], placeholders[i + 1]);
        }
        return map;
    }

    private static String apply(String text, Map<String, String> placeholders) {
        String out = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return out;
    }
}
