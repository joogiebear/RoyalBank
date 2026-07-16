package com.mystipixel.royalbank.api;

/** One ledger entry for an id-keyed account. {@code timestamp} is epoch seconds. */
public record TransactionView(String type, double amount, double balanceAfter, long timestamp, String note) {
}
