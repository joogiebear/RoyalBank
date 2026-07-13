package com.mystipixel.royalbank.hooks;

import com.mystipixel.econguard.api.EconGuard;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.api.Sources;
import com.mystipixel.royalbank.bank.AuditSink;

import java.util.UUID;

/**
 * Reports bank activity to the EconGuard core.
 *
 * IMPORTANT: this class references EconGuard classes, so it must only be loaded when EconGuard is
 * installed. RoyalBankPlugin instantiates it solely after checking the plugin is present.
 */
public final class EconGuardAuditSink implements AuditSink {
    @Override
    public void record(UUID uuid, String username, String action, double amount, double oldBalance, double newBalance,
                       boolean incoming, UUID counterparty, String counterpartyName) {
        // Auditing is fire-and-forget and runs after the transaction has committed; never let an
        // EconGuard error (or an API mismatch) bubble back into the bank operation.
        try {
            EconGuard.get().ifPresent(api -> {
                MoneyEvent.Builder builder = MoneyEvent.builder(uuid, username)
                        .source(Sources.BANK)
                        .action(action)
                        .amount(amount)
                        .incoming(incoming)
                        .balanceAfter(newBalance);
                // A counterparty is what lets EconGuard's collusion / RMT analysis link the two players.
                if (counterparty != null) {
                    builder.counterparty(counterparty, counterpartyName);
                }
                api.record(builder.build());
            });
        } catch (Throwable ignored) {
            // Intentionally swallowed: audit failures must not affect committed money.
        }
    }
}
