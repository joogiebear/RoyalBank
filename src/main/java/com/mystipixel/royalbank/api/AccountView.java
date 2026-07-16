package com.mystipixel.royalbank.api;

/** A read-only snapshot of an id-keyed account for rendering (e.g. a coop bank menu). */
public record AccountView(double balance, int level, String levelName, double maxBalance) {
}
