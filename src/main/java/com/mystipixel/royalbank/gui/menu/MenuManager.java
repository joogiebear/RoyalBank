package com.mystipixel.royalbank.gui.menu;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads (and, on first run, writes out) the bank's {@code gui/*.yml} menu templates. Reloadable. */
public final class MenuManager {

    // match the gui/<id>.yml file names and the open_menu effect's menu arg
    public static final String MAIN = "main";
    public static final String DEPOSIT = "deposit";
    public static final String WITHDRAW = "withdraw";
    public static final String TRANSACTIONS = "transactions";
    public static final String CONFIRM_UPGRADE = "confirm-upgrade";

    private static final String[] MENUS = {MAIN, DEPOSIT, WITHDRAW, TRANSACTIONS, CONFIRM_UPGRADE};

    private final JavaPlugin plugin;
    private final Map<String, MenuTemplate> byId = new LinkedHashMap<>();

    public MenuManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        byId.clear();
        for (String id : MENUS) {
            byId.put(id, load(id + ".yml"));
        }
    }

    private MenuTemplate load(String fileName) {
        File file = new File(plugin.getDataFolder(), "gui/" + fileName);
        if (!file.exists()) {
            plugin.saveResource("gui/" + fileName, false);
        }
        return MenuTemplate.load(file, bundled("gui/" + fileName), 4);
    }

    private YamlConfiguration bundled(String path) {
        try (InputStream in = plugin.getResource(path)) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read the bundled " + path + ": " + exception.getMessage());
            return null;
        }
    }

    public MenuTemplate get(String id) {
        return byId.get(id);
    }
}
