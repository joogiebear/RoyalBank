package com.mystipixel.royalbank.message;

import com.mystipixel.royalbank.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class MessageManager {
    private final JavaPlugin plugin;
    private File file;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefault();
        reload();
    }

    public void reload() {
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        this.messages = YamlConfiguration.loadConfiguration(file);
    }

    private void saveDefault() {
        File target = new File(plugin.getDataFolder(), "messages.yml");
        if (!target.exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    public String prefix() {
        return messages.getString("prefix", plugin.getConfig().getString("settings.messages-prefix", "&6&lRoyalBank &8» &r"));
    }

    public String raw(String key, String fallback) {
        return messages.getString(key, fallback);
    }

    public String format(String key, String fallback, Map<String, String> placeholders) {
        String value = raw(key, fallback);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    public void send(CommandSender sender, String key, String fallback) {
        sender.sendMessage(Text.color(prefix() + raw(key, fallback)));
    }

    public void sendRaw(CommandSender sender, String rawMessage) {
        sender.sendMessage(Text.color(prefix() + rawMessage));
    }

    public void send(CommandSender sender, String key, String fallback, Map<String, String> placeholders) {
        sender.sendMessage(Text.color(prefix() + format(key, fallback, placeholders)));
    }
}
