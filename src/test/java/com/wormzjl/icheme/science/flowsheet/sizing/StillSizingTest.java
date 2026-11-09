package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch still's sizing (batch 2026-11-09-batch-distiller, W7), on loads by hand: Auto's pot holds the charge at the usual fill and
 * finishes the batch with the owner's 20 % spare on its cycle; the pot below it misses; Fixed reads the same limits; the heater's
 * rating, the worm's area and the column's diameter ride beside them; and a charge no pot holds is red.
 */
final class StillSizingTest {
    /** 300 L at the design flow, 60 MJ a batch on a 2 h cycle, the worm tub taking all and no column. */
    private static final StillSizing.Load LOAD = new StillSizing.Load(BatchStillOptions.DEFAULT, .3, 60e6, 1, 7200, 1, 0, 0);

    @Test void autoTakesTheSmallestPotThatHoldsTheChargeAndTheSpare() {
        var choice = StillSizing.auto(LOAD);
        var size = choice.size();
        assertNotNull(size);
        assertEquals(Lamp.GREEN, choice.rating().lamp(), () -> "rating " + choice.rating());
        assertEquals(StillSizing.rate(LOAD, size), choice.rating(), "Fixed reads the same limits");
        assertTrue(LOAD.fill(size) <= BatchStill.FILL, "the usual fill: " + LOAD.fill(size));
        assertTrue(LOAD.cycle() / LOAD.batch(size) >= 1.2 - 1e-9, "the cycle's spare");
        int at = StillSizing.pots().indexOf(size.pot());
        assertTrue(at >= 0, "a pot of the constructor's list");
        if (at > 0) assertTrue(StillSizing.rate(LOAD, size.withPot(StillSizing.pots().get(at - 1))).lamp() != Lamp.GREEN, "the pot below misses");
        var shape = size.shape();
        assertNotNull(shape);
        assertEquals(EquipmentShape.Kind.BATCH_STILL, shape.kind());
        var design = size.design();
        assertEquals(design.rating(), choice.rating().values().get(StillSizing.RATED));
        assertEquals(design.condenserArea(), choice.rating().values().get(StillSizing.CONDENSER));
    }

    @Test void aColumnReadsItsFloodAndItsFFactor() {
        var options = new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.BUILT_IN, 4);
        var load = new StillSizing.Load(options, .3, 60e6, 1, 7200, 1, .25, 1.7);
        var size = new StillSizing.Size(StillSizing.pots().get(StillSizing.pots().size() - 1), options);
        var rating = StillSizing.rate(load, size);
        assertEquals(Lamp.RED, rating.lamp(), "a column that floods a quarter of its batch");
        assertTrue(rating.values().get(StillSizing.COLUMN) > 0, "the column's diameter beside the limits");
        var dry = StillSizing.rate(new StillSizing.Load(options, .3, 60e6, 1, 7200, 1, 0, 1.4), size);
        assertTrue(dry.lamp() != Lamp.RED, () -> "under its design F-factor and never flooded: " + dry);
    }

    @Test void aChargeNoPotHoldsIsRed() {
        double largest = StillSizing.pots().get(StillSizing.pots().size() - 1);
        var load = new StillSizing.Load(BatchStillOptions.DEFAULT, 2 * largest / 1000, 60e6, 1, 7200, 1, 0, 0);
        var choice = StillSizing.auto(load);
        assertNotNull(choice.size(), "Auto offers the largest pot");
        assertEquals(Lamp.RED, choice.rating().lamp());
    }

    @Test void noChargeNoSize() {
        assertNull(StillSizing.auto(StillSizing.Load.NONE).size());
        assertEquals(Lamp.NONE, StillSizing.rate(StillSizing.Load.NONE, new StillSizing.Size(StillSizing.pots().get(0), BatchStillOptions.DEFAULT)).lamp());
    }
}
