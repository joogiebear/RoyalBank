package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.data.BankAccount;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online players' accounts, for display. An entry is only replaced by a row with an equal or higher version,
 * so a reload that finishes late never undoes a newer write made on this server.
 */
public final class AccountCache {

    /** Told about every version the cache now holds, and about players dropped from it. */
    public interface Listener {
        void stored(UUID id, long version);

        void removed(UUID id);
    }

    private static final Listener NONE = new Listener() {
        @Override
        public void stored(UUID id, long version) {
        }

        @Override
        public void removed(UUID id) {
        }
    };

    private final Map<UUID, BankAccount> accounts = new ConcurrentHashMap<>();
    private volatile Listener listener = NONE;

    public void setListener(Listener listener) {
        this.listener = listener == null ? NONE : listener;
    }

    public Optional<BankAccount> get(UUID id) {
        return Optional.ofNullable(accounts.get(id));
    }

    /** Adds or updates the entry; returns what the cache holds afterwards, which may be a newer row. */
    public BankAccount put(BankAccount account) {
        BankAccount kept = accounts.merge(account.uuid(), account, AccountCache::newer);
        listener.stored(kept.uuid(), kept.version());
        return kept;
    }

    /**
     * Updates an entry that is still cached and leaves a missing one missing, so a reload landing after
     * the player quit does not bring them back. True when the cached row changed.
     */
    public boolean refresh(BankAccount account) {
        BankAccount before = accounts.get(account.uuid());
        BankAccount kept = accounts.computeIfPresent(account.uuid(), (id, cached) -> newer(cached, account));
        if (kept == null) {
            return false;
        }
        listener.stored(kept.uuid(), kept.version());
        return kept != before;
    }

    public void remove(UUID id) {
        accounts.remove(id);
        listener.removed(id);
    }

    public Set<UUID> ids() {
        return Set.copyOf(accounts.keySet());
    }

    private static BankAccount newer(BankAccount cached, BankAccount fresh) {
        return fresh.version() >= cached.version() ? fresh : cached;
    }
}
