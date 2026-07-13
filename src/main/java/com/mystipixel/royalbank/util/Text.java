package com.mystipixel.royalbank.util;

import org.bukkit.ChatColor;

import java.text.DecimalFormat;

public final class Text {
    // DecimalFormat is not thread-safe. Placeholders can be resolved from async threads
    // (TAB/scoreboard/chat plugins), so each thread gets its own formatter instance.
    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static String money(double amount, String symbol) {
        return symbol + MONEY.get().format(amount);
    }
}
