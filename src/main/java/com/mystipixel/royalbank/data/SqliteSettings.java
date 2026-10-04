package com.mystipixel.royalbank.data;

import java.util.Properties;

/**
 * Connection settings for the SQLite store, handed to the driver as connection properties so it
 * applies every one of them to every connection it opens.
 *
 * <p>They used to be a single {@code connectionInitSql} string of four {@code PRAGMA} statements.
 * sqlite-jdbc runs only the first statement of a multi-statement string, so journal mode was set and
 * the other three never were: {@code synchronous} stayed at the driver's FULL, {@code busy_timeout}
 * at the driver's 3000 ms, and {@code foreign_keys} stayed OFF. Nothing read the pragmas back, so
 * none of that was visible. {@link SqliteSettingsTest} now reads them off a real connection.
 */
final class SqliteSettings {

    /** Milliseconds a connection waits for another's write lock before giving up. */
    static final int BUSY_TIMEOUT_MS = 5000;

    /**
     * Connections in the pool. SQLite takes one writer at a time, and RoyalBank's money-critical
     * writes hold a transaction open across two statements, so a single connection serialises them
     * and avoids SQLITE_BUSY outright. Raise this only together with a real concurrency test —
     * {@link #BUSY_TIMEOUT_MS} is what a second connection would then depend on.
     */
    static final int POOL_SIZE = 1;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        // FULL, not NORMAL: with WAL, NORMAL can lose the last committed transactions on a power loss
        // or OS crash. RoyalBank moves money, so it keeps the durable setting (also the driver default).
        props.setProperty("synchronous", "FULL");
        props.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        props.setProperty("foreign_keys", "true");
        return props;
    }
}
