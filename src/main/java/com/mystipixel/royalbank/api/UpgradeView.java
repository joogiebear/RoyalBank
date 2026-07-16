package com.mystipixel.royalbank.api;

/** The next-level upgrade offer for an id-keyed account ({@code maxed} true means no further levels). */
public record UpgradeView(boolean maxed, int nextLevel, String nextName,
                          double moneyCost, String itemsText, double nextMaxBalance) {
}
