package com.mystipixel.royalbank.service;

import com.mystipixel.royalcommon.storage.DbExecutor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

// A server's database thread plus its main thread, which is whichever test thread calls run() or settle().
public final class TestThreads {

    public final MainQueue main = new MainQueue(task -> { });
    public final DbExecutor db;

    public TestThreads(String name) {
        db = new DbExecutor(name, main, Logger.getLogger(name));
    }

    // waits for queued database work and runs what it handed to the main thread, until both are idle
    public void settle() {
        do {
            db.submit("settle", () -> null).join();
        } while (main.drain());
    }

    /** Lets the database thread and the main queue work until the action finished, then returns its result. */
    public <T> T run(CompletableFuture<T> action) {
        for (int round = 0; !action.isDone(); round++) {
            assertTrue(round < 10_000, "the action never finished");
            db.submit("settle", () -> null).join();
            main.drain();
        }
        return action.join();
    }

    public void shutdown() {
        main.finish(db, Duration.ofSeconds(10));
    }
}
