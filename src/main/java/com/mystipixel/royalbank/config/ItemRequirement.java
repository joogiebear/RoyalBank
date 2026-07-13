package com.mystipixel.royalbank.config;

import org.bukkit.Material;

public record ItemRequirement(
        RequirementType type,
        Material material,
        String customItemId,
        int amount
) {
    public String displayName() {
        if (type == RequirementType.VANILLA) {
            return amount + "x " + material.name();
        }
        return amount + "x " + customItemId;
    }
}
