package com.mystipixel.royalbank.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Who paid into each shared account and has not taken it back yet, so a withdrawal can be reported
// against the members who funded it. Memory only: money deposited before a restart has no known depositor.
// Main thread only.
final class CoopContributions {

    // depositor null: no known depositor for this part of the withdrawal
    record Share(UUID depositor, String depositorName, double amount) {
    }

    private record Deposit(String name, double amount) {
    }

    private static final double EPSILON = 1e-6;

    private final Map<UUID, LinkedHashMap<UUID, Deposit>> byAccount = new HashMap<>();

    void deposited(UUID account, UUID depositor, String depositorName, double amount) {
        if (amount <= 0.0) {
            return;
        }
        byAccount.computeIfAbsent(account, k -> new LinkedHashMap<>())
                .merge(depositor, new Deposit(depositorName, amount),
                        (old, added) -> new Deposit(added.name(), old.amount() + added.amount()));
    }

    // The withdrawer's own deposits are used first, then other members' in the order they first paid in.
    List<Share> withdrawn(UUID account, UUID withdrawer, double amount) {
        List<Share> shares = new ArrayList<>();
        double remaining = amount;
        LinkedHashMap<UUID, Deposit> deposits = byAccount.get(account);
        if (deposits != null) {
            remaining = take(deposits, withdrawer, remaining, shares);
            Iterator<UUID> others = new ArrayList<>(deposits.keySet()).iterator();
            while (remaining > EPSILON && others.hasNext()) {
                remaining = take(deposits, others.next(), remaining, shares);
            }
            if (deposits.isEmpty()) {
                byAccount.remove(account);
            }
        }
        if (remaining > EPSILON) {
            shares.add(new Share(null, null, remaining));
        }
        return shares;
    }

    private static double take(Map<UUID, Deposit> deposits, UUID depositor, double remaining, List<Share> shares) {
        Deposit deposit = deposits.get(depositor);
        if (deposit == null || remaining <= EPSILON) {
            return remaining;
        }
        double taken = Math.min(remaining, deposit.amount());
        shares.add(new Share(depositor, deposit.name(), taken));
        double left = deposit.amount() - taken;
        if (left > EPSILON) {
            deposits.put(depositor, new Deposit(deposit.name(), left));
        } else {
            deposits.remove(depositor);
        }
        return remaining - taken;
    }
}
