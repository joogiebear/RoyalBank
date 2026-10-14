package com.mystipixel.royalbank.service;

import com.mystipixel.royalbank.data.BankAccount;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.VersionPoller;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the account cache in step with changes another server made to the same database. Every few seconds
 * one query reads the version of each cached account on the database thread; only rows whose version went up
 * are re-read, and the cache is updated on the main thread.
 */
public final class BalanceRefresher {

    private final AccountCache cache;
    private final BankDatabase database;
    private final DbExecutor db;
    private final VersionPoller<UUID> poller;

    public BalanceRefresher(AccountCache cache, BankDatabase database, DbExecutor db) {
        this.cache = cache;
        this.database = database;
        this.db = db;
        this.poller = new VersionPoller<>(database.dataSource(), db,
                VersionPoller.byColumn("player_banks", "uuid", "version", UUID::fromString), this::reload);
        cache.setListener(new AccountCache.Listener() {
            @Override
            public void stored(UUID id, long version) {
                poller.seen(id, version);
            }

            @Override
            public void removed(UUID id) {
                poller.forget(id);
            }
        });
        for (UUID id : cache.ids()) {
            cache.get(id).ifPresent(account -> poller.seen(id, account.version()));
        }
    }

    public void start(Plugin plugin, int seconds) {
        poller.start(plugin, seconds * 20L, cache::ids);
    }

    public void stop() {
        poller.stop();
    }

    /** One poll of every cached account; completes with the ids whose version went up. */
    public CompletableFuture<Set<UUID>> poll() {
        return poller.poll(cache.ids());
    }

    // main thread
    private void reload(Set<UUID> changed) {
        db.submit("reload changed bank accounts", () -> database.getAccounts(changed))
                .whenCompleteAsync((rows, failure) -> {
                    if (failure != null) {
                        retryNextPoll(changed);
                        return;
                    }
                    rows.forEach(cache::refresh);
                }, db.mainThread());
    }

    // the poller already counts these versions as seen; put back the cached ones so the next poll reports them again
    private void retryNextPoll(Set<UUID> changed) {
        for (UUID id : changed) {
            cache.get(id).ifPresent(account -> {
                poller.forget(id);
                poller.seen(id, account.version());
            });
        }
    }

    /**
     * Re-reads one player's row on the database thread, then runs then on the main thread if the player is
     * still online. A failed read still runs then, with the cached row.
     */
    public void refresh(Player player, Runnable then) {
        UUID id = player.getUniqueId();
        db.submit("refresh bank account " + id, () -> database.getAccounts(List.of(id)))
                .whenCompleteAsync((rows, failure) -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (rows != null) {
                        for (BankAccount row : rows) {
                            cache.put(row);
                        }
                    }
                    then.run();
                }, db.mainThread());
    }
}
