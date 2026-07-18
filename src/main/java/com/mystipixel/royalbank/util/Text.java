package com.mystipixel.royalbank.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public final class Text {
    // DecimalFormat is not thread-safe. Placeholders can be resolved from async threads
    // (TAB/scoreboard/chat plugins), so each thread gets its own formatter instance.
    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    /**
     * A legacy '&' string as an item name or lore line, with the default italic turned off.
     *
     * <p>Minecraft renders item display names and lore in italic unless told otherwise, so every menu
     * in this plugin looked slanted next to the rest of the suite. Item text must go through here
     * rather than {@link #color(String)}, which is for chat, where authored italics should survive.
     */
    public static Component item(String text) {
        return AMP.deserialize(text == null ? "" : text).decoration(TextDecoration.ITALIC, false);
    }

    /** {@link #item(String)} over a list, for lore. */
    public static List<Component> items(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(item(line));
        }
        return out;
    }

    public static String money(double amount, String symbol) {
        return symbol + MONEY.get().format(amount);
    }
}
