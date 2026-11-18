package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.fluid.network.Separation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sizer's settling rows (batch 2026-11-17-three-phase-separator, package 4): a booted drum is read against the
 * world's own separation law at design flow, on the review plant's receiver (oil 4.2 L/s at 666 kg/m³ and 0.30 mPa s,
 * water 0.374 L/s at 984 kg/m³ and 0.47 mPa s, gas 34.3 L/s at 5.22 kg/m³).
 */
class DrumSettlingTest {
    private static DrumSizing.Load receiver(double scale) {
        return new DrumSizing.Load(scale * .0343, scale * .0042, scale * 3.74e-4, 5.22, 666.35, 5, 666.35, 983.79, 2.999e-4, 4.732e-4);
    }

    @Test void theGasRowsAreTheWorldsNumbers() {
        assertEquals(Separation.GAS_K, Limit.DRUM_GAS_STANDING.value());
        assertEquals(Separation.LYING * Separation.GAS_K, Limit.DRUM_GAS_LYING.value());
        assertEquals(.107 / 2, Limit.DRUM_GAS_STANDING.value());
        assertEquals(1.25 * .107 / 2, Limit.DRUM_GAS_LYING.value());
    }

    @Test void aShortStandingDrumTakesLessGas() {
        // Half full, a 2:1-head drum's gas space is half its height, L + D/2: the factor (gas space / 1.524 m)^0.56.
        double full = Separation.gasLimit(false, 700, 5);
        for (double[] row : new double[][]{{.5, 1, .607}, {.5, 2, .844}, {1, 2, .895}, {.5, 3, 1}, {1, 3, 1}, {2, 6, 1}}) {
            var size = new DrumSizing.Size(UnitType.STANDING_DRUM, row[0], (int) row[1], false);
            var reading = DrumSizing.rate(new DrumSizing.Load(.01, 0, 0, 5, 700, 5), size).reading(Limit.DRUM_GAS_STANDING);
            assertEquals(row[2], reading.high() / full, 1e-3, row[0] + " by " + row[1]);
        }
        // At 0.7 of the full limit through 0.5 m: the 1 m drum fails what its diameter alone would pass; 1 m longer passes.
        double gas = .7 * full * Math.PI * .25 / 4;
        var load = new DrumSizing.Load(gas, 0, 0, 5, 700, 5);
        assertFalse(DrumSizing.rate(load, new DrumSizing.Size(UnitType.STANDING_DRUM, .5, 1, false)).reading(Limit.DRUM_GAS_STANDING).met());
        assertTrue(DrumSizing.rate(load, new DrumSizing.Size(UnitType.STANDING_DRUM, .5, 2, false)).reading(Limit.DRUM_GAS_STANDING).met());
        // A lying drum's limit does not read its gas space.
        var lying = DrumSizing.rate(load, new DrumSizing.Size(UnitType.LYING_DRUM, .5, 1, false)).reading(Limit.DRUM_GAS_LYING);
        assertEquals(Limit.DRUM_GAS_LYING.value() * Math.sqrt(695 / 5.), lying.high());
    }

    @Test void aDemisterLetsANarrowerDrumPassAndAutoNeverProposesOne() {
        // 1.5 m by 5 m standing (gas space 2.875 m at half full: factor 1): at 1.5 times its bare limit it fails, with a pad it passes.
        double full = Separation.gasLimit(false, 700, 5), gas = 1.5 * full * Math.PI * 1.5 * 1.5 / 4;
        var load = new DrumSizing.Load(gas, 0, 0, 5, 700, 5);
        var bare = new DrumSizing.Size(UnitType.STANDING_DRUM, 1.5, 5, false);
        var padded = bare.withDemister(true);
        assertFalse(DrumSizing.rate(load, bare).reading(Limit.DRUM_GAS_STANDING).met());
        var reading = DrumSizing.rate(load, padded).reading(Limit.DRUM_GAS_STANDING);
        assertTrue(reading.met());
        assertEquals(2 * full, reading.high(), 1e-12);
        assertTrue(padded.shape().demister() && !bare.shape().demister());
        // A lying drum's pad doubles its limit too.
        var lying = new DrumSizing.Size(UnitType.LYING_DRUM, 1.5, 5, false);
        assertEquals(2 * DrumSizing.rate(load, lying).reading(Limit.DRUM_GAS_LYING).high(), DrumSizing.rate(load, lying.withDemister(true)).reading(Limit.DRUM_GAS_LYING).high(), 1e-12);
        // Auto picks a larger bare drum, never a pad.
        for (var type : new UnitType[]{UnitType.STANDING_DRUM, UnitType.LYING_DRUM, null}) {
            var choice = DrumSizing.auto(load, type);
            assertFalse(choice.size().demister(), String.valueOf(type));
        }
        assertFalse(DrumSizing.auto(receiver(1), null).size().demister());
    }

    @Test void aSizedReceiverSettlesBothLiquids() {
        var size = new DrumSizing.Size(UnitType.LYING_DRUM, 2, 5, true);
        var rating = DrumSizing.rate(receiver(1), size);
        var water = rating.reading(Limit.DRUM_SETTLE_WATER);
        var oil = rating.reading(Limit.DRUM_SETTLE_OIL);
        assertNotNull(water);
        assertNotNull(oil);
        assertTrue(water.met() && oil.met(), "water " + water.value() + ", oil " + oil.value());
        // The same number the world reads: the layer's settling speed times its plan area over its outflow.
        var geometry = size.shape().fluidGeometry();
        var oilLayer = new Separation.Layer(geometry.volumeAt(1) - geometry.bootCapacity(), 666.35, 2.999e-4, .0042, .0042);
        var waterLayer = new Separation.Layer(geometry.bootCapacity(), 983.79, 4.732e-4, 3.74e-4, 3.74e-4);
        double[] numbers = Separation.numbers(geometry, Separation.DROPS, oilLayer, waterLayer);
        assertEquals(numbers[0], water.value());
        assertEquals(numbers[1], oil.value());
        assertTrue(Separation.of(geometry, Separation.DROPS, oilLayer, waterLayer, true, 0, 0).none());
        // What Auto chooses for the same load is green on both rows.
        var choice = DrumSizing.auto(receiver(1), null);
        assertTrue(choice.size().boot());
        assertTrue(choice.rating().reading(Limit.DRUM_SETTLE_WATER).met() && choice.rating().reading(Limit.DRUM_SETTLE_OIL).met());
    }

    @Test void theSameDrumUnderALargerFlowDoesNot() {
        // At sixteen times the flow the boot no longer lets the oil drops (0.2 mm) rise; the oil layer still settles its
        // water drops (0.5 mm).
        var rating = DrumSizing.rate(receiver(16), new DrumSizing.Size(UnitType.LYING_DRUM, 2, 5, true));
        assertTrue(rating.reading(Limit.DRUM_SETTLE_WATER).met(), "water " + rating.reading(Limit.DRUM_SETTLE_WATER).value());
        assertFalse(rating.reading(Limit.DRUM_SETTLE_OIL).met(), "oil " + rating.reading(Limit.DRUM_SETTLE_OIL).value());
        // At 128 times neither does.
        rating = DrumSizing.rate(receiver(128), new DrumSizing.Size(UnitType.LYING_DRUM, 2, 5, true));
        assertFalse(rating.reading(Limit.DRUM_SETTLE_WATER).met(), "water " + rating.reading(Limit.DRUM_SETTLE_WATER).value());
        assertFalse(rating.reading(Limit.DRUM_SETTLE_OIL).met(), "oil " + rating.reading(Limit.DRUM_SETTLE_OIL).value());
        assertEquals(Limit.Kind.SHOULD, Limit.DRUM_SETTLE_OIL.kind());
    }

    @Test void aLoadThatDoesNotSayHowItsLiquidsSettleHasNoSettlingRow() {
        var size = new DrumSizing.Size(UnitType.LYING_DRUM, 2, 5, true);
        var rating = DrumSizing.rate(new DrumSizing.Load(.0343, .0042, 3.74e-4, 5.22, 666.35, 5), size);
        assertNull(rating.reading(Limit.DRUM_SETTLE_WATER));
        assertNull(rating.reading(Limit.DRUM_SETTLE_OIL));
        // A drum without a boot has one liquid outlet: nothing to keep apart.
        var bare = DrumSizing.rate(receiver(1), new DrumSizing.Size(UnitType.LYING_DRUM, 2, 5, false));
        assertNull(bare.reading(Limit.DRUM_SETTLE_WATER));
    }
}
