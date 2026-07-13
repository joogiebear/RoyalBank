package com.mystipixel.royalbank.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AmountsTest {
    private static final double EPS = 1e-9;
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        // Return each config call's supplied default, so we exercise the shipped defaults
        // (max-transaction 1e12, decimal-places 2, min-transaction 0.01).
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(plugin.getConfig()).thenReturn(config);
    }

    @Test
    void sanitizeRejectsNegative() {
        assertEquals(0.0, Amounts.sanitize(plugin, -5), EPS);
    }

    @Test
    void sanitizeRejectsNaNAndInfinity() {
        assertEquals(0.0, Amounts.sanitize(plugin, Double.NaN), EPS);
        assertEquals(0.0, Amounts.sanitize(plugin, Double.POSITIVE_INFINITY), EPS);
    }

    @Test
    void sanitizeRejectsAboveMax() {
        assertEquals(0.0, Amounts.sanitize(plugin, 2_000_000_000_000.0), EPS);
    }

    @Test
    void sanitizeTruncatesToTwoDecimals() {
        assertEquals(100.99, Amounts.sanitize(plugin, 100.999), EPS);
    }

    @Test
    void roundNonNegativeRejectsNegativeWithSentinel() {
        assertEquals(-1.0, Amounts.roundNonNegative(plugin, -5), EPS);
    }

    @Test
    void roundNonNegativeTruncates() {
        assertEquals(100.99, Amounts.roundNonNegative(plugin, 100.999), EPS);
    }

    @Test
    void parseAcceptsPlainAndDecimal() {
        assertEquals(100.0, Amounts.parse(plugin, "100"));
        assertEquals(100.5, Amounts.parse(plugin, "100.5"));
    }

    @Test
    void parseRejectsGarbageNegativeCommaAndNull() {
        assertNull(Amounts.parse(plugin, "abc"));
        assertNull(Amounts.parse(plugin, "-5"));
        assertNull(Amounts.parse(plugin, "1,000"));
        assertNull(Amounts.parse(plugin, null));
    }

    @Test
    void parseRejectsBelowMinimum() {
        assertNull(Amounts.parse(plugin, "0"));
    }

    @Test
    void parseAcceptsMagnitudeSuffixes() {
        assertEquals(2_000.0, Amounts.parse(plugin, "2k"));
        assertEquals(5_000_000.0, Amounts.parse(plugin, "5m"));
        assertEquals(50_000_000_000.0, Amounts.parse(plugin, "50b"));
        assertEquals(1_500_000_000.0, Amounts.parse(plugin, "1.5b"));
        assertEquals(1_000_000_000_000.0, Amounts.parse(plugin, "1t"));
    }

    @Test
    void parseSuffixesAreCaseInsensitive() {
        assertEquals(5_000_000.0, Amounts.parse(plugin, "5M"));
        assertEquals(3_000_000_000.0, Amounts.parse(plugin, "3B"));
    }

    @Test
    void parseStillAcceptsPlainAmountsUnderAThousand() {
        assertEquals(999_000.0, Amounts.parse(plugin, "999000"));
        assertEquals(500.0, Amounts.parse(plugin, "500"));
    }

    @Test
    void parseRejectsSuffixWithoutNumberBadSuffixAndOverMax() {
        assertNull(Amounts.parse(plugin, "m"));            // suffix with no number
        assertNull(Amounts.parse(plugin, "5x"));           // unknown suffix
        assertNull(Amounts.parse(plugin, "5.m"));          // malformed mantissa
        assertNull(Amounts.parse(plugin, "2t"));           // 2e12 > 1e12 max-transaction default
    }
}
