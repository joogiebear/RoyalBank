package com.mystipixel.royalbank.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public final class Text {
    // DecimalFormat is not thread-safe, and placeholders are resolved from async threads
    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    // For item names and lore: turns off the default italic. Chat goes through color(), where
    // authored italics should survive.
    public static Component item(String text) {
        return AMP.deserialize(text == null ? "" : text).decoration(TextDecoration.ITALIC, false);
    }

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
