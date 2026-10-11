package com.mystipixel.royalbank.data;

import java.util.UUID;

// minBalanceSinceClaim: the lowest balance held since the last interest claim; interest is paid on it.
// It never exceeds balance: lowering the balance lowers it, raising the balance leaves it alone.
public record BankAccount(
        UUID uuid,
        String username,
        double balance,
        int level,
        long lastInterestClaim,
        boolean bonusClaimed,
        double minBalanceSinceClaim
) {
    // a new or reset account, or one swapped in from a profile snapshot: the minimum starts at the balance
    public BankAccount(UUID uuid, String username, double balance, int level, long lastInterestClaim, boolean bonusClaimed) {
        this(uuid, username, balance, level, lastInterestClaim, bonusClaimed, balance);
    }

    public BankAccount withBalance(double newBalance) {
        return new BankAccount(uuid, username, newBalance, level, lastInterestClaim, bonusClaimed,
                Math.min(minBalanceSinceClaim, newBalance));
    }

    public BankAccount withLevel(int newLevel) {
        return new BankAccount(uuid, username, balance, newLevel, lastInterestClaim, bonusClaimed, minBalanceSinceClaim);
    }

    // starts a new interest period: the cooldown and the minimum both restart from now
    public BankAccount withInterestClaimed(long timestamp) {
        return new BankAccount(uuid, username, balance, level, timestamp, bonusClaimed, balance);
    }

    public BankAccount withBonusClaimed(boolean claimed) {
        return new BankAccount(uuid, username, balance, level, lastInterestClaim, claimed, minBalanceSinceClaim);
    }

    public BankAccount withUsername(String newUsername) {
        return new BankAccount(uuid, newUsername, balance, level, lastInterestClaim, bonusClaimed, minBalanceSinceClaim);
    }
}
