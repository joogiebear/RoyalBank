package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.message.MessageManager;

import java.util.Map;

// key is a messages.yml path, or null when there is nothing to tell the player
public record OperationResult(boolean success, String key, Map<String, String> placeholders) {
    public static OperationResult success(String key, String... placeholders) {
        return new OperationResult(true, key, MessageManager.pairs(placeholders));
    }

    public static OperationResult fail(String key, String... placeholders) {
        return new OperationResult(false, key, MessageManager.pairs(placeholders));
    }

    public static OperationResult silent() {
        return new OperationResult(true, null, Map.of());
    }
}
