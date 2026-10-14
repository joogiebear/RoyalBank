package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.data.CoopContribution;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Splits a shared account withdrawal over the members who paid the money in, so it can be reported
// against them. Works on the account's coop_contributions rows, read in the withdrawal's own transaction.
final class CoopContributions {

    // depositor null: no known depositor for this part of the withdrawal; seq is the depositor's row order
    record Share(UUID depositor, String depositorName, double amount, long seq) {
    }

    static final double EPSILON = 1e-6;

    private CoopContributions() {
    }

    // The withdrawer's own deposits are used first, then other members' in the order they first paid in.
    // depositors: the account's rows ordered by seq.
    static List<Share> shares(List<CoopContribution> depositors, UUID withdrawer, double amount) {
        List<Share> shares = new ArrayList<>();
        double remaining = amount;
        for (CoopContribution own : depositors) {
            if (own.member().equals(withdrawer)) {
                remaining = take(own, remaining, shares);
            }
        }
        for (CoopContribution other : depositors) {
            if (remaining <= EPSILON) {
                break;
            }
            if (!other.member().equals(withdrawer)) {
                remaining = take(other, remaining, shares);
            }
        }
        if (remaining > EPSILON) {
            shares.add(new Share(null, null, remaining, 0L));
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

    // undoes withdrawalChanges; a depositor row the withdrawal emptied comes back in its old place
    static List<CoopContribution> returnedChanges(UUID account, List<Share> shares) {
        List<CoopContribution> changes = new ArrayList<>();
        for (Share share : shares) {
            if (share.depositor() != null) {
                changes.add(new CoopContribution(account, share.depositor(), share.depositorName(), share.amount(), share.seq()));
            }
        }
        return changes;
    }

    private static double take(CoopContribution deposit, double remaining, List<Share> shares) {
        if (deposit.amount() <= EPSILON || remaining <= EPSILON) {
            return remaining;
        }
        double taken = Math.min(remaining, deposit.amount());
        shares.add(new Share(deposit.member(), deposit.memberName(), taken, deposit.seq()));
        return remaining - taken;
    }
}
