package com.mystipixel.royalbank.hooks;

import com.mystipixel.royalbank.service.AuditSink;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

// Calls EconGuard.record(...) by reflection so there is no build-time dependency; a no-op if
// EconGuard is absent or predates that bridge.
public final class EconGuardAuditSink implements AuditSink {

    private static final String SOURCE_BANK = "bank";

    private final Method bridge;
    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public EconGuardAuditSink(Logger logger) {
        this.logger = logger;
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
        } catch (Throwable throwable) {
            // runs after the money has committed, so an audit failure must not reach the bank operation;
            // logged once so a broken bridge doesn't go unnoticed or flood the console
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Could not report bank activity to EconGuard; further failures are not logged", throwable);
            }
        }
    }
}
