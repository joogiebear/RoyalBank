package com.mystipixel.royalbank.data;

import java.util.UUID;

public record BankAccount(
        UUID uuid,
        String username,
        double balance,
        int level,
        long lastInterestClaim,
        boolean bonusClaimed
) {
    public BankAccount withBalance(double newBalance) {
        return new BankAccount(uuid, username, newBalance, level, lastInterestClaim, bonusClaimed);
    }

    public BankAccount withLevel(int newLevel) {
        return new BankAccount(uuid, username, balance, newLevel, lastInterestClaim, bonusClaimed);
    }

    public BankAccount withLastInterestClaim(long timestamp) {
        return new BankAccount(uuid, username, balance, level, timestamp, bonusClaimed);
    }

    public BankAccount withBonusClaimed(boolean claimed) {
        return new BankAccount(uuid, username, balance, level, lastInterestClaim, claimed);
    }

    public BankAccount withUsername(String newUsername) {
        return new BankAccount(uuid, newUsername, balance, level, lastInterestClaim, bonusClaimed);
    }
}
