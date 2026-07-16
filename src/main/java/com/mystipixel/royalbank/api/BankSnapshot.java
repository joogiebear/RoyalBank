package com.mystipixel.royalbank.api;

/**
 * A portable copy of an account's mutable state — everything except its id/owner name. Used by
 * RoyalSkyblock to stash a player's bank per profile and swap it on profile switch, so the personal
 * bank (balance, level, interest timer, first-deposit flag) is per-profile.
 */
public record BankSnapshot(double balance, int level, long lastInterestClaim, boolean bonusClaimed) {
}
