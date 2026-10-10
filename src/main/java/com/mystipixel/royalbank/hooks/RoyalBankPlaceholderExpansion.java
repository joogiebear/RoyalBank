package com.mystipixel.royalbank.hooks;

import com.mystipixel.royalbank.service.BankService;
import com.mystipixel.royalbank.config.BankLevel;
import com.mystipixel.royalbank.data.BankAccount;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public final class RoyalBankPlaceholderExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final BankService bankService;

    public RoyalBankPlaceholderExpansion(JavaPlugin plugin, BankService bankService) {
        this.plugin = plugin;
        this.bankService = bankService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "royalbank";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (!(offlinePlayer instanceof Player player)) {
            return "";
        }

        // PlaceholderAPI may call this async: serve from the cache, and only fall back to the DB on the
        // main thread
        try {
            Optional<BankAccount> cached = bankService.getCachedAccount(player.getUniqueId());
            if (cached.isEmpty()) {
                if (!Bukkit.isPrimaryThread()) {
                    return "";
                }
                cached = Optional.of(bankService.getAccount(player));
            }
            BankAccount account = cached.get();
            BankLevel level = bankService.getEffectiveLevel(account.level());
            double bankSpace = Math.max(0.0, level.maxBalance() - account.balance());

            return switch (params.toLowerCase()) {
                case "balance" -> bankService.money(account.balance());
                case "balance_raw" -> String.valueOf(account.balance());
                case "level" -> String.valueOf(level.level());
                case "level_name" -> level.name();
                case "max_balance" -> bankService.money(level.maxBalance());
                case "max_balance_raw" -> String.valueOf(level.maxBalance());
                case "bank_space" -> bankService.money(bankSpace);
                case "bank_space_raw" -> String.valueOf(bankSpace);
                case "next_interest" -> bankService.money(bankService.calculateInterest(account.balance(), level));
                case "next_interest_raw" -> String.valueOf(bankService.calculateInterest(account.balance(), level));
                case "interest_time" -> bankService.getInterestTimeRemaining(player);
                case "max_interest" -> bankService.money(level.maxInterest());
                // Vault read; common providers allow it async, and a failure renders as empty
                case "combined_balance" -> bankService.money(account.balance() + bankService.getWalletBalance(player));
                default -> null;
            };
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
