package com.mystipixel.royalbank.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Public API registered with Bukkit's {@code ServicesManager} so other plugins can soft-depend on
 * RoyalBank and drive two things beyond a personal player account:
 *
 * <ol>
 *   <li><b>Per-profile personal banks</b> — {@link #exportAccount}/{@link #importAccount}/
 *       {@link #resetAccount} let a caller (e.g. RoyalSkyblock) swap a player's own account row on a
 *       profile switch, so the personal bank is per-profile while native {@code /bank} keeps working.</li>
 *   <li><b>Id-keyed shared accounts</b> (e.g. a skyblock coop) — the {@code account*} methods run the
 *       full bank (balance, level, upgrades, ledger) on an arbitrary account id, with a member as the
 *       Vault/inventory counterparty.</li>
 * </ol>
 *
 * <p>Money always moves through Vault, so the server economy total is conserved. Main-thread only.
 * {@code account*}/{@code personal} deposit-style methods return {@code null} on success or a
 * colour-coded, player-facing error string.
 */
public interface RoyalBankAPI {

    // ── per-profile personal bank (operates the player's own account row) ──────────

    /** Copy a player's current account state (creating a fresh one if absent). */
    BankSnapshot exportAccount(UUID playerId);

    /** Overwrite a player's account with the given snapshot (and refresh RoyalBank's cache). */
    void importAccount(UUID playerId, BankSnapshot snapshot);

    /** Reset a player's account to a fresh starting account (new profile). */
    void resetAccount(UUID playerId);

    // ── id-keyed accounts (shared / coop) ──────────────────────────────────────────

    double getAccountBalance(UUID accountId);

    AccountView getAccountView(UUID accountId, String label);

    String accountDeposit(Player purse, UUID accountId, String label, double amount);

    String accountWithdraw(Player purse, UUID accountId, String label, double amount);

    UpgradeView getUpgradeView(UUID accountId, String label);

    String accountUpgrade(Player purse, UUID accountId, String label);

    List<TransactionView> getAccountTransactions(UUID accountId, int limit);
}
