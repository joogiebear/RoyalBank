package com.mystipixel.royalbank.gui.menu;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads (and, on first run, writes out) the bank's {@code gui/*.yml} menu templates. Reloadable. */
public final class MenuManager {

    /** Menu ids, matching the gui/<id>.yml file names and the {@code open_menu} effect's {@code menu} arg. */
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
        return MenuTemplate.load(file, "&6&lBank", 4);
    }

    public MenuTemplate get(String id) {
        return byId.get(id);
    }
}
