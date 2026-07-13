package com.mystipixel.royalbank.data;

public record BankTransaction(
        long id,
        String type,
        double amount,
        double balanceAfter,
        long createdAt,
        String note
) {
}
