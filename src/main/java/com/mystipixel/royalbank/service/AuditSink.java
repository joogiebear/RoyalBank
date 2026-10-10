package com.mystipixel.royalbank.service;

import java.util.UUID;

/**
 * {@code incoming} is true when the bank balance grows. {@code counterparty}/{@code counterpartyName}
 * are set only for player-to-player transfers.
 */
public interface AuditSink {
    void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                boolean incoming, UUID counterparty, String counterpartyName);
}
