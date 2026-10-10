package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.security.AbuseMonitor;

import java.util.UUID;

public final class LocalAuditSink implements AuditSink {
    private final AbuseMonitor monitor;

    public LocalAuditSink(AbuseMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                       boolean incoming, UUID counterparty, String counterpartyName) {
        // the built-in monitor does not model counterparties
        monitor.recordTransaction(uuid, username, action, amount, oldBalance, newBalance);
    }
}
