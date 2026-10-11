package com.mystipixel.royalbank.service;

import java.util.UUID;

/**
 * {@code incoming} is true when the player gains wealth they did not already own (a transfer or coop
 * withdrawal funded by someone else, interest, a bonus), not when money only moves between their own
 * purse and bank. {@code counterparty}/{@code counterpartyName} name the other player, when there is one.
 */
public interface AuditSink {
    void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                boolean incoming, UUID counterparty, String counterpartyName);
}
