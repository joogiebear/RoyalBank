package com.mystipixel.royalbank.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registered with Bukkit's {@code ServicesManager}. {@link #exportAccount}/{@link #importAccount}/
 * {@link #resetAccount} swap a player's own account per profile; the {@code account*} methods run a
 * full bank on an arbitrary account id (e.g. a skyblock coop), with a member as the Vault counterparty.
 *
 * <p>Main-thread only. Deposit-style methods return {@code null} on success or a colour-coded,
 * player-facing error string.
 *
 * <p>RoyalBank does its database work on its own thread. The methods without {@code Async} wait for that
 * thread, so they block the main thread for as long as the database takes (and behind any queued bank
 * work); prefer the {@code Async} variants, whose futures complete on the main thread.
 */
public interface RoyalBankAPI {

    /** Copy a player's current account state (creating a fresh one if absent). Blocks; see the class notes. */
    BankSnapshot exportAccount(UUID playerId);

    /** Overwrite a player's account with the given snapshot (and refresh RoyalBank's cache). Blocks. */
    void importAccount(UUID playerId, BankSnapshot snapshot);

    /** Reset a player's account to a fresh starting account (new profile). Blocks. */
    void resetAccount(UUID playerId);

    /** Blocks; {@link #getAccountBalanceAsync} does not. */
    double getAccountBalance(UUID accountId);

    /** Blocks; {@link #getAccountViewAsync} does not. */
    AccountView getAccountView(UUID accountId, String label);

    /** Blocks; {@link #accountDepositAsync} does not. */
    String accountDeposit(Player purse, UUID accountId, String label, double amount);

    /** Blocks; {@link #accountWithdrawAsync} does not. */
    String accountWithdraw(Player purse, UUID accountId, String label, double amount);

    /** Blocks; {@link #getUpgradeViewAsync} does not. */
    UpgradeView getUpgradeView(UUID accountId, String label);

    /** Blocks; {@link #accountUpgradeAsync} does not. */
    String accountUpgrade(Player purse, UUID accountId, String label);

    /** Blocks; {@link #getAccountTransactionsAsync} does not. */
    List<TransactionView> getAccountTransactions(UUID accountId, int limit);

    /** {@link #getAccountBalance} without blocking; completes on the main thread. */
    default CompletableFuture<Double> getAccountBalanceAsync(UUID accountId) {
        return CompletableFuture.completedFuture(getAccountBalance(accountId));
    }

    /** {@link #getAccountView} without blocking; completes on the main thread. */
    default CompletableFuture<AccountView> getAccountViewAsync(UUID accountId, String label) {
        return CompletableFuture.completedFuture(getAccountView(accountId, label));
    }

    /** {@link #getUpgradeView} without blocking; completes on the main thread. */
    default CompletableFuture<UpgradeView> getUpgradeViewAsync(UUID accountId, String label) {
        return CompletableFuture.completedFuture(getUpgradeView(accountId, label));
    }

    /** {@link #getAccountTransactions} without blocking; completes on the main thread. */
    default CompletableFuture<List<TransactionView>> getAccountTransactionsAsync(UUID accountId, int limit) {
        return CompletableFuture.completedFuture(getAccountTransactions(accountId, limit));
    }

    /** {@link #accountDeposit} without blocking; completes on the main thread with the same result. */
    default CompletableFuture<String> accountDepositAsync(Player purse, UUID accountId, String label, double amount) {
        return CompletableFuture.completedFuture(accountDeposit(purse, accountId, label, amount));
    }

    /** {@link #accountWithdraw} without blocking; completes on the main thread with the same result. */
    default CompletableFuture<String> accountWithdrawAsync(Player purse, UUID accountId, String label, double amount) {
        return CompletableFuture.completedFuture(accountWithdraw(purse, accountId, label, amount));
    }

    /** {@link #accountUpgrade} without blocking; completes on the main thread with the same result. */
    default CompletableFuture<String> accountUpgradeAsync(Player purse, UUID accountId, String label) {
        return CompletableFuture.completedFuture(accountUpgrade(purse, accountId, label));
    }
}
