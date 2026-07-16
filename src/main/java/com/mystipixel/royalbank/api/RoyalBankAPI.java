package com.mystipixel.royalbank.api;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Public, dependency-light API for <b>shared bank accounts</b> — balances owned by an arbitrary id
 * (e.g. a group, guild, or skyblock coop) rather than a single player. Registered with Bukkit's
 * {@code ServicesManager} so other plugins can soft-depend on RoyalBank and fetch it via
 * {@code getServicesManager().getRegistration(RoyalBankAPI.class)}.
 *
 * <p>Shared accounts are entirely separate from personal player accounts: no levels, no interest, an
 * optional flat cap. Money still moves through Vault, so the server-wide economy total is conserved.
 * All methods are main-thread only.
 *
 * <p>Deposit/withdraw return {@code null} on success or a colour-coded, player-facing error string.
 */
public interface RoyalBankAPI {

    /** The shared account's current balance, or {@code 0} if it has never been funded. */
    double getSharedBalance(UUID accountId);

    /**
     * Move {@code amount} from the player's Vault purse into the shared account (creating/labelling it
     * on first use). Returns {@code null} on success, else an error message.
     */
    String sharedDeposit(Player from, UUID accountId, String label, double amount);

    /**
     * Move {@code amount} from the shared account into the player's Vault purse. Returns {@code null} on
     * success, else an error message.
     */
    String sharedWithdraw(Player to, UUID accountId, String label, double amount);
}
