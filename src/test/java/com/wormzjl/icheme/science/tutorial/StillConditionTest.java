package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.guide.StillCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The still steps' matching rule (batch 2026-11-12-tutorial-system, P2b; criteria {@code still_state} and {@code still_stopped}). */
class StillConditionTest {
    private static final Optional<Double> NONE = Optional.empty();

    @Test
    void aConditionLeftOutHolds() {
        var any = new StillCondition(Optional.empty(), NONE, NONE);
        assertTrue(any.matches("IDLE", 0, null));
        assertTrue(any.matches("NO_FUEL", 1, 400.0));
    }

    @Test
    void eachConditionMustHold() {
        var charge = new StillCondition(Optional.empty(), Optional.of(.5), NONE);
        assertFalse(charge.matches("IDLE", .49, null));
        assertTrue(charge.matches("IDLE", .5, null), "half full is enough");
        var cut = new StillCondition(Optional.of(List.of("DISTILLING", "COOLING_LIMITED")), NONE, Optional.of(506.0));
        assertFalse(cut.matches("DISTILLING", .3, 505.9));
        assertTrue(cut.matches("DISTILLING", .3, 506.0));
        assertTrue(cut.matches("COOLING_LIMITED", .3, 520.0));
        assertFalse(cut.matches("BOILING", .3, 520.0), "not taking product over");
        assertFalse(cut.matches("DISTILLING", .3, null), "no temperature before the first answer");
        assertFalse(charge.matches("IDLE", Double.NaN, null));
    }

    @Test
    void aStopCountsOnlyForAFiringStill() {
        for (var status : List.of("HEATING", "BOILING", "DISTILLING", "COOLING_LIMITED")) assertTrue(StillCondition.firing(status), status);
        for (var status : List.of("IDLE", "NO_FUEL", "OVERHEAT", "BOIL_DRY", "FLOODING")) assertFalse(StillCondition.firing(status), status);
        assertFalse(StillCondition.firing(null));
    }

    @Test
    void itRefusesAShareOrTemperatureOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new StillCondition(Optional.empty(), Optional.of(1.5), NONE));
        assertThrows(IllegalArgumentException.class, () -> new StillCondition(Optional.empty(), NONE, Optional.of(0.0)));
    }
}
