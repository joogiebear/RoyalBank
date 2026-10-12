package com.mystipixel.royalbank.service;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Registry;

import static org.mockito.Mockito.mock;

// Registries with nothing in them, so Material.isAir() works without a server: no test item is air.
public final class EmptyRegistryAccess implements RegistryAccess {

    @Override
    @SuppressWarnings({"unchecked", "removal"})
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return mock(Registry.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return mock(Registry.class);
    }
}
