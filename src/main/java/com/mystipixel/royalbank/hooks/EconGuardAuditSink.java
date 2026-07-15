package com.mystipixel.royalbank.hooks;

import com.mystipixel.royalbank.service.AuditSink;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reports bank activity to the EconGuard core.
 *
 * <p>Integration is by reflection against EconGuard's flat {@code EconGuard.record(...)} bridge, so
 * RoyalBank carries no build-time dependency on EconGuard and builds standalone. The bridge method is
 * resolved once at construction; if EconGuard is absent or too old to expose it, every {@link #record}
 * call is a safe no-op. RoyalBankPlugin only installs this sink after confirming EconGuard is enabled.
 */
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
            // EconGuard missing or predates the bridge - degrade to a no-op sink.
        }
        this.bridge = resolved;
    }

    @Override
    public void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                       boolean incoming, UUID counterparty, String counterpartyName) {
        if (bridge == null) {
            return;
        }
        // Auditing is fire-and-forget and runs after the transaction has committed; never let an
        // EconGuard error (or an API mismatch) bubble back into the bank operation.
        try {
            bridge.invoke(null, uuid, username, SOURCE_BANK, action, amount, incoming, newBalance,
                    counterparty, counterpartyName, null, null);
        } catch (Throwable ignored) {
            // Intentionally swallowed: audit failures must not affect committed money.
        }
    }
}
