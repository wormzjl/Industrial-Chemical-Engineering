package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.equipment.ExchangerType;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.integrity.FoulingLaw;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A heat side's fouling deposit in its laws (batch 2026-11-16-fouling-corrosion, WP5, plan section 3.2): the films behind the
 * deposit in series, {@code h' = h/(1 + h Rf)}, the passage narrowed by twice its thickness, each against the plan's table; a
 * clean side's laws the same instances or equal values, bit for bit.
 */
class FoulingEffectTest {
    /** The plan's table: Rf, then the liquid, two-phase and gas films behind it, the thickness (m) and the drop's ratio on a 20 mm passage. */
    private static final double[][] TABLE = {
            {0, 2000, 5000, 100, 0, 1.00},
            {.0002, 1429, 2500, 98, .0002, 1.11},
            {.0005, 1000, 1429, 95, .0005, 1.29},
            {.001, 667, 833, 91, .001, 1.69},
            {.002, 400, 455, 83, .002, 3.05}};

    @Test
    void filmsBehindTheDepositFollowThePlansTable() {
        var clean = HeatCurve.Films.DEFAULT;
        assertSame(clean, clean.fouled(0), "a clean side keeps its films, the same instance");
        for (var row : TABLE) {
            var fouled = clean.fouled(row[0]);
            assertEquals(row[1], fouled.liquid(), .005 * row[1], "liquid at " + row[0]);
            assertEquals(row[2], fouled.twoPhase(), .005 * row[2], "two-phase at " + row[0]);
            assertEquals(row[3], fouled.gas(), .005 * row[3], "gas at " + row[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> clean.fouled(-1e-4));
        assertThrows(IllegalArgumentException.class, () -> clean.fouled(Double.NaN));
    }

    @Test
    void theDepositNarrowsThePassageAndRaisesItsDrop() {
        var clean = new PipeResistance.Geometry(6, .02, HeatSides.TUBE_ROUGHNESS, 1.5, 100);
        assertSame(clean, clean.narrowed(0));
        for (var row : TABLE) {
            var fouled = clean.narrowed(FoulingLaw.thickness(row[0]));
            assertEquals(row[4], FoulingLaw.thickness(row[0]), 1e-15);
            assertEquals(.02 - 2 * row[4], fouled.diameter(), 1e-15);
            assertEquals(clean.length(), fouled.length());
            assertEquals(clean.channels(), fouled.channels());
            // At the same flow, a fully rough turbulent drop scales as d^-5.
            assertEquals(row[5], Math.pow(clean.diameter() / fouled.diameter(), 5), .01, "drop ratio at " + row[0]);
        }
        // Never below half the clean bore: a thin shell-side passage keeps a channel.
        var thin = new PipeResistance.Geometry(1, .003, 0, 0);
        assertEquals(.0015, thin.narrowed(FoulingLaw.thickness(FoulingLaw.CAP)).diameter(), 1e-15);
    }

    @Test
    void eachHeatLawReadsTheDepositOnItsProcessSide() {
        double area = 450, rf = 5 * FoulingLaw.STEP;
        // An exchanger's side: its films behind the deposit.
        var clean = (HeatSide) HeatSides.heatSide(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, area, 0, 0, 7, null, ExchangerType.STANDARD);
        var atZero = (HeatSide) HeatSides.heatSide(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, area, 0, 0, 7, null, ExchangerType.STANDARD, 0);
        assertEquals(clean, atZero);
        assertSame(HeatCurve.Films.DEFAULT, atZero.films());
        var fouled = (HeatSide) HeatSides.heatSide(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, area, 0, 0, 7, null, ExchangerType.STANDARD, rf);
        assertEquals(HeatCurve.Films.DEFAULT.fouled(rf), fouled.films());
        assertNotEquals(clean.shape(), fouled.shape(), "a new level is a new shape: its island compiles again");
        // An air cooler: the tubes' process films behind the deposit, in series with the air.
        var air = (AirCooled) HeatSides.heatSide(HeatSides.Unit.AIR_COOLER, HeatSides.Side.PROCESS, area, 1, 0, 0, null, ExchangerType.STANDARD, 0);
        assertSame(HeatSides.AIR, air.air());
        var dirty = (AirCooled) HeatSides.heatSide(HeatSides.Unit.AIR_COOLER, HeatSides.Side.PROCESS, area, 1, 0, 0, null, ExchangerType.STANDARD, rf);
        assertEquals(1000, dirty.air().process().liquid(), 1e-9);
        assertEquals(air.draught(), dirty.draught());
        assertTrue(dirty.films().wallLiquid() < air.films().wallLiquid());
        assertEquals(HeatSides.airFilms(1, rf), dirty.films());
        assertEquals(HeatSides.airFilms(1), HeatSides.airFilms(1, 0));
        // A convection coil: 1/U' = 1/U + Rf on its overall coefficient (54.9 W/m²K: 53.4 at 5 steps).
        var coil = (ConvectionCoil) HeatSides.heatSide(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.CONVECTION, area, 1, 600, 3, null, ExchangerType.STANDARD, 0);
        var sooted = (ConvectionCoil) HeatSides.heatSide(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.CONVECTION, area, 1, 600, 3, null, ExchangerType.STANDARD, rf);
        assertEquals(coil.conductance() / (1 + coil.conductance() / area * rf), sooted.conductance(), 1e-9 * coil.conductance());
        assertEquals(53.44, sooted.conductance() / area, .01);
        // A radiant coil's heat is its burners': the deposit moves no heat there (it acts on the passage alone).
        assertEquals(HeatSides.heatSide(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT, area, 1, 600, 3, null, ExchangerType.STANDARD, 0),
                HeatSides.heatSide(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT, area, 1, 600, 3, null, ExchangerType.STANDARD, rf));
    }

    @Test
    void aFouledAirCoolerRoundTripsItsAirAndItsFilmsMarchLessHeat() {
        var air = HeatSides.AIR.fouled(.001);
        assertSame(HeatSides.AIR, HeatSides.AIR.fouled(0));
        assertEquals(new AirCooled.Air(air.temperature(), air.barePerFace(), air.film(), air.capacity(), air.natural(), HeatCurve.Films.DEFAULT.fouled(.001)), air);
        // A liquid film of 2000 behind 1 m²K/kW leaves 667: less conductance per face at every draught.
        for (double draught : new double[]{.25, .6, 1}) assertTrue(air.films(draught).liquid() < HeatSides.AIR.films(draught).liquid());
    }
}
