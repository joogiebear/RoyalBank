package com.mystipixel.royalbank.config;

public record InterestTranche(
        double from,
        double to,
        double percent
) {
    public double calculate(double balance) {
        double taxable = Math.max(0.0, Math.min(balance, to) - from);
        return taxable * (percent / 100.0);
    }

    public String displayRange() {
        return format(from) + "-" + format(to);
    }

    private String format(double value) {
        if (value >= 1_000_000_000.0) {
            return trim(value / 1_000_000_000.0) + "B";
        }
        if (value >= 1_000_000.0) {
            return trim(value / 1_000_000.0) + "M";
        }
        if (value >= 1_000.0) {
            return trim(value / 1_000.0) + "K";
        }
        return trim(value);
    }

    private String trim(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
