package com.mystipixel.royalbank.hooks;

import com.mystipixel.royalbank.service.AuditSink;

import java.lang.reflect.Method;
import java.util.UUID;

// Calls EconGuard.record(...) by reflection so there is no build-time dependency; a no-op if
// EconGuard is absent or predates that bridge.
public final class EconGuardAuditSink implements AuditSink {

    private static final String SOURCE_BANK = "bank";

    private final Method bridge;

    public EconGuardAuditSink() {
        Method resolved = null;
        try {
            Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
            resolved = econGuard.getMethod("record",
                    UUID.class, String.class, String.class, String.class,
                    double.class, boolean.class, double.class,
                    UUID.class, String.class, String.class, String.class);
        } catch (Throwable ignored) {
            // no-op sink
        }
        this.bridge = resolved;
    }

    @Override
    public void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                       boolean incoming, UUID counterparty, String counterpartyName) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.invoke(null, uuid, username, SOURCE_BANK, action, amount, incoming, newBalance,
                    counterparty, counterpartyName, null, null);
        } catch (Throwable ignored) {
            // runs after the money has committed, so an audit failure must not reach the bank operation
        }
    }
}
