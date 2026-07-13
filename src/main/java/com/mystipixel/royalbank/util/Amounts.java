package com.mystipixel.royalbank.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class Amounts {
    private static final Pattern NORMAL_DECIMAL = Pattern.compile("^(?:0|[1-9]\\d*)(?:\\.\\d+)?$");

    private Amounts() {
    }

    public static double sanitize(JavaPlugin plugin, double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return 0.0;
        }

        double max = plugin.getConfig().getDouble("settings.amount-limits.max-transaction", 1_000_000_000_000.0);
        if (max > 0.0 && amount > max) {
            return 0.0;
        }

        int decimals = Math.max(0, Math.min(6, plugin.getConfig().getInt("settings.amount-limits.decimal-places", 2)));
        return BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.DOWN).doubleValue();
    }

    public static double roundNonNegative(JavaPlugin plugin, double amount) {
        if (!Double.isFinite(amount) || amount < 0.0) {
            return -1.0;
        }
        int decimals = Math.max(0, Math.min(6, plugin.getConfig().getInt("settings.amount-limits.decimal-places", 2)));
        return BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.DOWN).doubleValue();
    }

    public static Double parse(JavaPlugin plugin, String raw) {
        return parse(plugin, raw, false);
    }

    public static Double parse(JavaPlugin plugin, String raw, boolean allowZero) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.trim();
        if (normalized.isEmpty() || normalized.contains(",") || !NORMAL_DECIMAL.matcher(normalized).matches()) {
            return null;
        }

        double amount;
        try {
            amount = Double.parseDouble(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }

        if (allowZero && amount == 0.0) {
            return 0.0;
        }

        double sanitized = sanitize(plugin, amount);
        double min = plugin.getConfig().getDouble("settings.amount-limits.min-transaction", 0.01);
        if (sanitized < min) {
            return null;
        }
        return sanitized;
    }
}
