package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.security.AbuseMonitor;

import java.util.UUID;

/** Default sink: routes to RoyalBank's built-in {@link AbuseMonitor} (standalone, bank-only detection). */
public final class LocalAuditSink implements AuditSink {
    private final AbuseMonitor monitor;

    public LocalAuditSink(AbuseMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                       boolean incoming, UUID counterparty, String counterpartyName) {
        // The built-in monitor is bank-only and does not model counterparties; ignore them here.
        monitor.recordTransaction(uuid, username, action, amount, oldBalance, newBalance);
    }
}
