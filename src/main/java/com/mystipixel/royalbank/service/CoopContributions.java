package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.data.CoopContribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Who paid into each shared account and has not taken it back yet, so a withdrawal can be reported
// against the members who funded it. A copy of the coop_contributions table: callers write the table
// in the same transaction as the money, then apply the same change here. Main thread only.
final class CoopContributions {

    // depositor null: no known depositor for this part of the withdrawal
    record Share(UUID depositor, String depositorName, double amount) {
    }

    private record Deposit(String name, double amount) {
    }

    static final double EPSILON = 1e-6;

    private final Map<UUID, LinkedHashMap<UUID, Deposit>> byAccount = new HashMap<>();
    private long nextSeq = 1;

    // rows in table order: by account, then by seq
    void load(List<CoopContribution> rows) {
        byAccount.clear();
        nextSeq = 1;
        for (CoopContribution row : rows) {
            if (row.amount() > EPSILON) {
                byAccount.computeIfAbsent(row.account(), k -> new LinkedHashMap<>())
                        .put(row.member(), new Deposit(row.memberName(), row.amount()));
            }
            nextSeq = Math.max(nextSeq, row.seq() + 1);
        }
    }

    CoopContribution depositChange(UUID account, UUID depositor, String depositorName, double amount) {
        return new CoopContribution(account, depositor, depositorName, amount, nextSeq++);
    }

    void deposited(UUID account, UUID depositor, String depositorName, double amount) {
        if (amount <= 0.0) {
            return;
        }
        byAccount.computeIfAbsent(account, k -> new LinkedHashMap<>())
                .merge(depositor, new Deposit(depositorName, amount),
                        (old, added) -> new Deposit(added.name(), old.amount() + added.amount()));
    }

    // Read-only. The withdrawer's own deposits are used first, then other members' in the order they first paid in.
    List<Share> shares(UUID account, UUID withdrawer, double amount) {
        List<Share> shares = new ArrayList<>();
        double remaining = amount;
        LinkedHashMap<UUID, Deposit> deposits = byAccount.get(account);
        if (deposits != null) {
            remaining = take(deposits, withdrawer, remaining, shares);
            for (UUID other : deposits.keySet()) {
                if (remaining <= EPSILON) {
                    break;
                }
                if (!other.equals(withdrawer)) {
                    remaining = take(deposits, other, remaining, shares);
                }
            }
        }
        if (remaining > EPSILON) {
            shares.add(new Share(null, null, remaining));
        }
        return shares;
    }

    static List<CoopContribution> withdrawalChanges(UUID account, List<Share> shares) {
        List<CoopContribution> changes = new ArrayList<>();
        for (Share share : shares) {
            if (share.depositor() != null) {
                changes.add(new CoopContribution(account, share.depositor(), share.depositorName(), -share.amount(), 0L));
            }
        }
        return changes;
    }

    void withdrawn(UUID account, List<Share> shares) {
        LinkedHashMap<UUID, Deposit> deposits = byAccount.get(account);
        if (deposits == null) {
            return;
        }
        for (Share share : shares) {
            if (share.depositor() == null) {
                continue;
            }
            Deposit deposit = deposits.get(share.depositor());
            double left = deposit == null ? 0.0 : deposit.amount() - share.amount();
            if (left > EPSILON) {
                deposits.put(share.depositor(), new Deposit(deposit.name(), left));
            } else {
                deposits.remove(share.depositor());
            }
        }
        if (deposits.isEmpty()) {
            byAccount.remove(account);
        }
    }

    private static double take(Map<UUID, Deposit> deposits, UUID depositor, double remaining, List<Share> shares) {
        Deposit deposit = deposits.get(depositor);
        if (deposit == null || remaining <= EPSILON) {
            return remaining;
        }
        double taken = Math.min(remaining, deposit.amount());
        shares.add(new Share(depositor, deposit.name(), taken));
        return remaining - taken;
    }
}
