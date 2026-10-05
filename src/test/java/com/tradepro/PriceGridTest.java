package com.tradepro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PriceGridTest {

    private static Locale saved;

    @BeforeAll
    static void usLocale() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.US);       // séparateur décimal fixe pour comparer les textes
    }

    @AfterAll
    static void restoreLocale() { Locale.setDefault(saved); }

    @Test
    void quarterTickKeepsTwoDecimals() {
        PriceGrid g = new PriceGrid(0.25);
        assertEquals(2, g.decimals);
        assertEquals("5000.25", g.format(20001));       // et non 5000.3
        assertEquals("5000.75", g.format(20003));
    }

    @Test
    void decimalsFollowTheTick() {
        assertEquals(3, new PriceGrid(0.125).decimals);
        assertEquals(1, new PriceGrid(0.5).decimals);
        assertEquals(1, new PriceGrid(0.1).decimals);
        assertEquals(2, new PriceGrid(0.01).decimals);
        assertEquals(0, new PriceGrid(1).decimals);
        assertEquals(0, new PriceGrid(5).decimals);
        assertEquals(0, new PriceGrid(10).decimals);
        assertEquals(4, new PriceGrid(0.0001).decimals);
    }

    @Test
    void priceIsExactOnNonBinaryTicks() {
        assertEquals(0.3, new PriceGrid(0.1).price(3));         // 3 * 0.1 en double donnerait 0.30000000000000004
        assertEquals(5000.25, new PriceGrid(0.25).price(20001));
        assertEquals(1.2345, new PriceGrid(0.0001).price(12345));
    }

    @Test
    void levelIsTheInverseOfPrice() {
        PriceGrid g = new PriceGrid(0.25);
        assertEquals(20001, g.level(5000.25), 1e-9);
        assertEquals(20001, g.level(g.price(20001)), 1e-9);
    }
}
