package com.mystipixel.royalbank.service;

import java.util.UUID;

/**
 * Where RoyalBank reports bank money movements for anti-abuse / audit.
 *
 * Default is {@link LocalAuditSink} (RoyalBank's built-in monitor). When the EconGuard core is present,
 * the plugin swaps in an EconGuard-backed sink so detection is centralized. {@code incoming} is true
 * when the bank balance grows (deposit) so parking detection can see it, false for withdrawals.
 *
 * {@code counterparty}/{@code counterpartyName} name the other party in a player-to-player transfer
 * (RMT / collusion analysis needs both ends). They are {@code null} for movements with no counterparty
 * such as deposits, withdrawals, and interest.
 */
public interface AuditSink {
    void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                boolean incoming, UUID counterparty, String counterpartyName);
}
