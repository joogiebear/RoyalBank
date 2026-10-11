package com.mystipixel.royalbank.data;

import java.util.UUID;

// A row of coop_contributions: money a member paid into a shared account and has not taken back.
// Passed to saveCoopMovement as a change, amount is the delta (negative when taken back) and seq only
// matters for a member's first row, where it orders the depositors.
public record CoopContribution(UUID account, UUID member, String memberName, double amount, long seq) {
}
