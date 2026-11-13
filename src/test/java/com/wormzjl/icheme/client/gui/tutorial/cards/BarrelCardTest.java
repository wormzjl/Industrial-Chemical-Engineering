package com.wormzjl.icheme.client.gui.tutorial.cards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The barrel and products cards' sliders (moved from {@code CrudeBarrelTest} with the cards, code review finding 8). */
class BarrelCardTest {
    @Test
    void theProductsSliderMeetsEveryBandInOrderAndTheBarrelMarkSpansItsRange() {
        var products = new Products();
        var seen = new java.util.ArrayList<Integer>();
        for (int i = 0; i <= 1000; i++) {
            int band = products.band(i / 1000.0);
            if (seen.isEmpty() || seen.getLast() != band) seen.add(band);
        }
        assertEquals(java.util.List.of(0, 1, 2, 3, 4, 5), seen);
        assertEquals(273.15, Barrel.kelvin(0), 1e-12);
        assertEquals(873.15, Barrel.kelvin(1), 1e-12);
        var barrel = new Barrel();
        assertEquals(4, barrel.options().size());
        assertEquals("medium_crude", barrel.grade(0).id());
        assertEquals(barrel.grade(0).curve()[0], barrel.grade(0).boiled(250), 1e-12);
        assertEquals(.005, barrel.grade(0).boiled(250), .0005, "methane, ethane and propane boil below 250 K");
        assertEquals("1.5 %", Barrel.percent(1.5));
        assertEquals("3 %", Barrel.percent(3.0));
    }
}
