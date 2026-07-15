package com.mystipixel.royalbank.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigValidator {
    private final JavaPlugin plugin;
    private final LevelManager levelManager;

    public ConfigValidator(JavaPlugin plugin, LevelManager levelManager) {
        this.plugin = plugin;
        this.levelManager = levelManager;
    }

    public void validate() {
        validateMainConfig();
        // Menu layouts (gui/*.yml) are validated leniently by the menu engine itself at load time.
    }

    private void validateMainConfig() {
        FileConfiguration config = plugin.getConfig();
        int startingLevel = config.getInt("settings.starting-level", 1);
        if (levelManager.getLevel(startingLevel).isEmpty()) {
            warn("settings.starting-level points to missing level " + startingLevel + ".");
        }
        if (config.getDouble("settings.amount-limits.min-transaction", 0.01) <= 0.0) {
            warn("settings.amount-limits.min-transaction should be greater than 0.");
        }
        if (config.getDouble("settings.amount-limits.max-transaction", 0.0) <= 0.0) {
            warn("settings.amount-limits.max-transaction should be greater than 0.");
        }
        if (config.getInt("transactions.max-per-player", 100) < 10) {
            warn("transactions.max-per-player is very low; players may lose visible history quickly.");
        }

        double previousMaxBalance = -1.0;
        for (BankLevel level : levelManager.getLevels().values()) {
            if (level.maxBalance() <= 0.0) {
                warn("Level " + level.level() + " has max-balance <= 0.");
            }
            if (previousMaxBalance > level.maxBalance()) {
                warn("Level " + level.level() + " has lower max-balance than the previous configured level.");
            }
            previousMaxBalance = level.maxBalance();
            if (level.upgradeMoneyCost() < 0.0) {
                warn("Level " + level.level() + " has negative upgrade-cost.money.");
            }
            validateTranches(level);
        }
    }

    private void validateTranches(BankLevel level) {
        double lastTo = -1.0;
        double potentialInterest = 0.0;
        boolean hasTranches = !level.interestTranches().isEmpty();
        for (InterestTranche tranche : level.interestTranches()) {
            if (tranche.from() < lastTo) {
                warn("Level " + level.level() + " has overlapping interest tranches near " + tranche.from() + ".");
            }
            if (tranche.percent() <= 0.0) {
                warn("Level " + level.level() + " has an interest tranche with percent <= 0.");
            }
            potentialInterest += Math.max(0.0, tranche.to() - tranche.from()) * (tranche.percent() / 100.0);
            lastTo = tranche.to();
        }

        // Flag a real "dead earning zone": tranches stop below max-balance AND they cannot even reach the
        // interest cap, so balances above the top tranche silently earn nothing extra. A small slack keeps
        // this quiet for configs whose tranches already (nearly) reach the cap.
        if (hasTranches && lastTo < level.maxBalance()) {
            double cap = effectiveInterestCap(level);
            if (potentialInterest < cap * 0.95) {
                warn("Level " + level.level() + " interest tranches stop at " + lastTo
                        + " but max-balance is " + level.maxBalance()
                        + "; balances above " + lastTo + " earn no extra interest. Extend the top tranche to cover max-balance.");
            }
        }
    }

    private double effectiveInterestCap(BankLevel level) {
        double cap = level.maxInterest() < 0.0 ? Double.MAX_VALUE : level.maxInterest();
        if (plugin.getConfig().getBoolean("settings.max-daily-interest.enabled", true)) {
            double globalCap = plugin.getConfig().getDouble("settings.max-daily-interest.amount", Double.MAX_VALUE);
            if (globalCap >= 0.0) {
                cap = Math.min(cap, globalCap);
            }
        }
        return cap;
    }

    private void warn(String message) {
        plugin.getLogger().warning("Config warning: " + message);
    }
}
