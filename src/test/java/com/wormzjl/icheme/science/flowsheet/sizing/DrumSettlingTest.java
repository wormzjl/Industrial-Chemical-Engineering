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
        double[] numbers = Separation.numbers(geometry, Separation.DROP, oilLayer, waterLayer);
        assertEquals(numbers[0], water.value());
        assertEquals(numbers[1], oil.value());
        assertTrue(Separation.of(geometry, Separation.DROP, oilLayer, waterLayer, true, 0, 0).none());
        // What Auto chooses for the same load is green on both rows.
        var choice = DrumSizing.auto(receiver(1), null);
        assertTrue(choice.size().boot());
        assertTrue(choice.rating().reading(Limit.DRUM_SETTLE_WATER).met() && choice.rating().reading(Limit.DRUM_SETTLE_OIL).met());
    }

    @Test void theSameDrumAtSixteenTimesTheFlowDoesNot() {
        var rating = DrumSizing.rate(receiver(16), new DrumSizing.Size(UnitType.LYING_DRUM, 2, 5, true));
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
