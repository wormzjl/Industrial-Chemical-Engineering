package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.guide.StillCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The still steps' matching rule (batch 2026-11-12-tutorial-system, P2b; criteria {@code still_state} and {@code still_stopped}). */
class StillConditionTest {
    private static final Optional<Double> NONE = Optional.empty();
    private static final Optional<Boolean> ANY = Optional.empty(), LIQUID = Optional.of(true);

    @Test
    void aConditionLeftOutHolds() {
        var any = new StillCondition(Optional.empty(), NONE, NONE, ANY);
        assertTrue(any.matches("IDLE", 0, null, 0));
        assertTrue(any.matches("NO_FUEL", 1, 400.0, 0));
    }

    @Test
    void eachConditionMustHold() {
        var charge = new StillCondition(Optional.empty(), Optional.of(.5), NONE, ANY);
        assertFalse(charge.matches("IDLE", .49, null, 0));
        assertTrue(charge.matches("IDLE", .5, null, 0), "half full is enough");
        var cut = new StillCondition(Optional.empty(), NONE, Optional.of(506.0), LIQUID);
        assertFalse(cut.matches("DISTILLING", .3, 505.9, .02));
        assertTrue(cut.matches("DISTILLING", .3, 506.0, .02));
        assertTrue(cut.matches("COOLING_LIMITED", .3, 520.0, .01), "a short condenser that still sends liquid to the spout");
        assertFalse(cut.matches("BOILING", .3, 520.0, 0), "not taking product over");
        assertFalse(cut.matches("COOLING_LIMITED", .3, 520.0, 0), "a short condenser whose vapour all vents");
        assertFalse(cut.matches("DISTILLING", .3, null, .02), "no temperature before the first answer");
        assertFalse(charge.matches("IDLE", Double.NaN, null, 0));
    }

    @Test
    void theFirstDropIsLiquidAtTheSpoutNotVentedGas() {
        var first = new StillCondition(Optional.empty(), NONE, NONE, LIQUID);
        assertFalse(first.matches("BOILING", .6, 305.0, 0), "a head that only vents its gas");
        assertFalse(first.matches("HEATING", .6, 300.0, 0));
        assertTrue(first.matches("DISTILLING", .6, 318.0, 1e-7), "the first trace of liquid");
        assertFalse(first.matches("DISTILLING", .6, 318.0, Double.NaN));
    }

    /** P4b, chapter 3: a still with a column, and its valve open at a least reflux ratio; a closed valve or no column meets none. */
    @Test
    void theColumnAndTheRefluxAreReadFromTheView() {
        var column = new StillCondition(Optional.empty(), NONE, NONE, ANY, Optional.of(true), NONE);
        assertTrue(column.matches("HEATING", .6, 300.0, 0, 4, 0, true));
        assertFalse(column.matches("HEATING", .6, 300.0, 0, 0, 0, true), "a pot still");
        var reflux = new StillCondition(Optional.empty(), NONE, NONE, ANY, ANY, Optional.of(2.0));
        assertTrue(reflux.matches("DISTILLING", .6, 400.0, 1e-3, 4, 2, false));
        assertFalse(reflux.matches("DISTILLING", .6, 400.0, 1e-3, 4, 1.5, false), "under the ratio");
        assertFalse(reflux.matches("BOILING", .6, 400.0, 0, 4, 2, true), "a closed valve");
        assertFalse(reflux.matches("DISTILLING", .6, 400.0, 1e-3, 0, 2, false), "no column");
        // Chapter 2's conditions hold for any still, with or without a column.
        var first = new StillCondition(Optional.empty(), NONE, NONE, LIQUID);
        assertTrue(first.matches("DISTILLING", .6, 318.0, 1e-7, 4, 2, false));
        assertThrows(IllegalArgumentException.class, () -> new StillCondition(Optional.empty(), NONE, NONE, ANY, Optional.of(true), Optional.of(-1.0)));
    }

    @Test
    void aStopCountsOnlyForAFiringStill() {
        for (var status : List.of("HEATING", "BOILING", "DISTILLING", "COOLING_LIMITED")) assertTrue(StillCondition.firing(status), status);
        for (var status : List.of("IDLE", "NO_FUEL", "OVERHEAT", "BOIL_DRY", "FLOODING")) assertFalse(StillCondition.firing(status), status);
        assertFalse(StillCondition.firing(null));
    }

    @Test
    void itRefusesAShareOrTemperatureOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new StillCondition(Optional.empty(), Optional.of(1.5), NONE, ANY));
        assertThrows(IllegalArgumentException.class, () -> new StillCondition(Optional.empty(), NONE, Optional.of(0.0), ANY));
    }
}
