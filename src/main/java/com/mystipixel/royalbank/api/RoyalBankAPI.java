package com.mystipixel.royalbank.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Registered with Bukkit's {@code ServicesManager}. {@link #exportAccount}/{@link #importAccount}/
 * {@link #resetAccount} swap a player's own account per profile; the {@code account*} methods run a
 * full bank on an arbitrary account id (e.g. a skyblock coop), with a member as the Vault counterparty.
 *
 * <p>Main-thread only. Deposit-style methods return {@code null} on success or a colour-coded,
 * player-facing error string.
 */
public interface RoyalBankAPI {

    /** Copy a player's current account state (creating a fresh one if absent). */
    BankSnapshot exportAccount(UUID playerId);

    /** Overwrite a player's account with the given snapshot (and refresh RoyalBank's cache). */
    void importAccount(UUID playerId, BankSnapshot snapshot);

    /** Reset a player's account to a fresh starting account (new profile). */
    void resetAccount(UUID playerId);

    double getAccountBalance(UUID accountId);

    AccountView getAccountView(UUID accountId, String label);

    String accountDeposit(Player purse, UUID accountId, String label, double amount);

    String accountWithdraw(Player purse, UUID accountId, String label, double amount);

    UpgradeView getUpgradeView(UUID accountId, String label);

    String accountUpgrade(Player purse, UUID accountId, String label);

    List<TransactionView> getAccountTransactions(UUID accountId, int limit);
}
