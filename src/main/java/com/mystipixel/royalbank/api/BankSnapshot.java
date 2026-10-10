package com.mystipixel.royalbank.api;

/**
 * An account's mutable state, without its id or owner name. RoyalSkyblock stores one per profile and
 * swaps it in on profile switch.
 */
public record BankSnapshot(double balance, int level, long lastInterestClaim, boolean bonusClaimed) {
}
