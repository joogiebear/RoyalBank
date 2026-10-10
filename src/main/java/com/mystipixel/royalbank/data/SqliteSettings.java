package com.mystipixel.royalbank.data;

import java.util.Properties;

// Driver connection properties, applied to every connection. Not connectionInitSql: sqlite-jdbc runs
// only the first statement of a multi-statement string.
final class SqliteSettings {

    static final int BUSY_TIMEOUT_MS = 5000;

    // one connection serialises the two-statement money transactions and avoids SQLITE_BUSY; raise it
    // only with a real concurrency test, since a second connection then depends on BUSY_TIMEOUT_MS
    static final int POOL_SIZE = 1;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        // FULL, not NORMAL: with WAL, NORMAL can lose the last commits on a power loss or OS crash
        props.setProperty("synchronous", "FULL");
        props.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        props.setProperty("foreign_keys", "true");
        return props;
    }
}
