package com.mystipixel.royalbank.gui;

import com.mystipixel.royalbank.gui.menu.MenuTemplate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuLayoutTest {

    private static final List<String> MENUS = List.of("main", "deposit", "withdraw", "transactions", "confirm-upgrade");

    @Test
    void everyMenuParsesWithATitleAndSlots() throws IOException {
        for (String menu : MENUS) {
            YamlConfiguration yaml = load("gui/" + menu + ".yml");
            assertNotNull(yaml.getString("title"), menu + ".yml title");
            assertFalse(yaml.getMapList("slots").isEmpty(), menu + ".yml slots");
        }
    }

    // two fixed buttons on one slot: the later one hides the earlier
    @Test
    void shippedMenusNeverPutTwoButtonsOnOneSlot() throws IOException {
        List<String> clashes = new ArrayList<>();
        for (String menu : MENUS) {
            YamlConfiguration yaml = load("gui/" + menu + ".yml");
            Map<String, String> taken = new HashMap<>();
            for (Map<?, ?> slot : yaml.getMapList("slots")) {
                String at = slot.get("row") + "," + slot.get("column");
                String previous = taken.putIfAbsent(at, String.valueOf(slot.get("item")));
                if (previous != null) {
                    clashes.add(menu + ".yml row/column " + at + ": " + previous + " and " + slot.get("item"));
                }
            }
        }
        assertTrue(clashes.isEmpty(), "buttons sharing a slot: " + clashes);
    }

    // the transaction history used to be built in BankGui; these are its exact materials, names and lore
    @Test
    void transactionEntryMatchesThePreviousHardCodedItems() throws IOException {
        ConfigurationSection entry = load("gui/transactions.yml").getConfigurationSection("entry");
        assertNotNull(entry);
        assertEquals("MM/dd HH:mm", entry.getString("time-format"));
        assertEquals("lime_dye hide_attributes name:\"&aDeposit\"", entry.getString("items.deposit"));
        assertEquals("red_dye hide_attributes name:\"&cWithdraw\"", entry.getString("items.withdraw"));
        assertEquals("clock hide_attributes name:\"&bInterest\"", entry.getString("items.interest"));
        assertEquals("emerald hide_attributes name:\"&aBonus\"", entry.getString("items.bonus"));
        assertEquals("gold_block hide_attributes name:\"&eUpgrade\"", entry.getString("items.upgrade"));
        assertEquals("paper hide_attributes name:\"&fTransaction\"", entry.getString("items.default"));
        assertEquals(List.of("&7Amount: &f%amount%", "&7Balance After: &f%balance_after%", "&7Time: &f%time%",
                "&7Note: &f%note%"), entry.getStringList("lore"));
    }

    @Test
    void costItemTextMatchesThePreviousHardCodedLore() throws IOException {
        ConfigurationSection text = load("gui/confirm-upgrade.yml").getConfigurationSection("cost-item");
        assertNotNull(text);
        assertEquals("&f%id%", text.getString("unresolved-name"));
        assertEquals(List.of("&8(not loaded in eco)"), text.getStringList("unresolved-lore"));
        assertEquals(List.of("&7Required: &f%amount%", "&aYou have: &f%held%&7/&f%amount%"),
                text.getStringList("lore-enough"));
        assertEquals(List.of("&7Required: &f%amount%", "&cYou have: &f%held%&7/&f%amount%"),
                text.getStringList("lore-missing"));
    }

    // a server's transactions.yml from before the entry: section still gets the bundled text, but its
    // own slots and filler are never topped up from the jar
    @Test
    void oldMenuFileFallsBackToBundledTextButKeepsItsOwnLayout(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("transactions.yml");
        Files.writeString(file, "rows: 6\n");
        MenuTemplate template = MenuTemplate.load(file.toFile(), load("gui/transactions.yml"), 4);

        assertEquals("&8Bank &7» &fTransactions", template.title());
        assertEquals("lime_dye hide_attributes name:\"&aDeposit\"",
                template.config().getConfigurationSection("entry").getString("items.deposit"));
        assertEquals(List.of("&7Required: &f%amount%", "&cYou have: &f%held%&7/&f%amount%"),
                MenuTemplate.load(file.toFile(), load("gui/confirm-upgrade.yml"), 4).config()
                        .getConfigurationSection("cost-item").getStringList("lore-missing"));
        assertTrue(template.slots().isEmpty());
        assertTrue(template.contentSlots().isEmpty());
    }

    private static YamlConfiguration load(String path) throws IOException {
        try (InputStream in = MenuLayoutTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is not bundled");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
