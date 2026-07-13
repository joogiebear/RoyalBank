package com.mystipixel.royalbank.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterestTrancheTest {
    private static final double EPS = 1e-9;

    @Test
    void taxesBalanceWithinRange() {
        assertEquals(50.0, new InterestTranche(0, 1000, 10).calculate(500), EPS);
    }

    @Test
    void capsAtUpperBound() {
        assertEquals(100.0, new InterestTranche(0, 1000, 10).calculate(5000), EPS);
    }

    @Test
    void zeroBelowFrom() {
        assertEquals(0.0, new InterestTranche(1000, 2000, 5).calculate(500), EPS);
    }

    @Test
    void onlyTaxesPortionAboveFrom() {
        assertEquals(25.0, new InterestTranche(1000, 2000, 5).calculate(1500), EPS);
    }

    @Test
    void capsPortionAtTo() {
        assertEquals(50.0, new InterestTranche(1000, 2000, 5).calculate(3000), EPS);
    }

    @Test
    void negativeBalanceYieldsZero() {
        assertEquals(0.0, new InterestTranche(0, 1000, 10).calculate(-100), EPS);
    }
}
