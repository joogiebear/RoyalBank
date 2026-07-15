package com.mystipixel.royalbank.service;

public record OperationResult(boolean success, String message) {
    public static OperationResult success(String message) {
        return new OperationResult(true, message);
    }

    public static OperationResult fail(String message) {
        return new OperationResult(false, message);
    }
}
