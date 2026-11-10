package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The limits as one table, and the one rule that reads them (S1 of batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md}). */
final class LimitTest {
    private static final double EXACT = 0;

    @Test void everyRowHasItsSourceAndAMustIsTheWorlds() {
        for (var row : Limit.values()) {
            assertFalse(row.source().isBlank(), row.name());
            if (row.kind() == Limit.Kind.MUST) assertTrue(row.source().startsWith("World"), row.name() + ": red is for what the world refuses, D14");
        }
    }

    @Test void aWorldRowIsTheWorldsOwnNumber() {
        assertEquals(PipeSizes.MAX_BORE, Limit.LINE_BORE.value(), EXACT);
        assertEquals(36000, Limit.PUMP_FLOW.value(), EXACT);
        assertEquals(2e6, Limit.PUMP_HEAD.value(), EXACT);
        assertEquals(FluidNetwork.MAXIMUM_PRESSURE_RATIO, Limit.COMPRESSOR_RATIO.value(), EXACT);
        assertEquals(FluidDeviceSpec.MAX_VOLUME, Limit.TANK_VOLUME.value(), EXACT);
        assertEquals(EquipmentShape.MAX_COLUMN_DIAMETER, Limit.COLUMN_DIAMETER.value(), EXACT);
        assertEquals(ColumnTrays.MAX_TRAYS, Limit.COLUMN_TRAYS.value(), EXACT);
        assertEquals(EquipmentShape.MAX_DIAMETER, Limit.DRUM_DIAMETER.value(), EXACT);
        assertEquals(EquipmentShape.MAX_TANGENT_LENGTH, Limit.DRUM_LENGTH.value(), EXACT);
        assertEquals(PhaseLayout.AMBIENT_PRESSURE / 1e5, Limit.ROOF_BUBBLE_WORLD.value(), EXACT);
        assertEquals(PhaseLayout.AMBIENT_PRESSURE / 1e5, Limit.SERVICE_VACUUM.value(), EXACT);
        assertEquals(54.9, Limit.HEATER_VERTICAL.value(), .05, "plan section 3.10");
        assertEquals(112.7, Limit.TYPE_CABIN_LARGEST.value(), .05, "plan section 3.10");
        assertEquals(7000, Limit.TYPE_TURBO.value(), 50, "plan section 2");
        assertEquals(40, Limit.TANK_DIAMETER.value(), EXACT);
        assertEquals(4, Limit.TANK_DIAMETER.bound().low(), EXACT);
        assertEquals(24, Limit.TANK_SHELL.value(), EXACT);
        assertEquals(24, Limit.SPHERE_DIAMETER.value(), EXACT);
    }

    @Test void stepsHoldTheirEdgesAsThePlanWrites() {
        assertEquals(1, Limit.SUCTION_BOILING_SPEED.value(.2), EXACT);
        assertEquals(1.25, Limit.SUCTION_BOILING_SPEED.value(.25), EXACT, "under 250 mm");
        assertEquals(1.25, Limit.SUCTION_BOILING_SPEED.value(.5), EXACT, "to 500");
        assertEquals(1.5, Limit.SUCTION_BOILING_SPEED.value(.6), EXACT);
        assertEquals(1.8, Limit.SUCTION_COLD_SPEED.value(.25), EXACT);
        assertEquals(.6, Limit.DRAW_SPEED.value(.05), EXACT, "to 50 mm");
        assertEquals(.9, Limit.DRAW_SPEED.value(.075), EXACT);
        assertEquals(60, Limit.GAS_SPEED.value(.1), EXACT, "at most 0.1 barg");
        assertEquals(20, Limit.GAS_SPEED.value(5.1), EXACT);
        assertEquals(7500, Limit.GAS_MOMENTUM.value(20), EXACT, "under 20 barg");
        assertEquals(15000, Limit.GAS_MOMENTUM.value(80), EXACT, "over 80");
        assertEquals(60 * Math.pow(5, .25), Limit.VACUUM_SPEED.value(.01), 1e-12, "60 (0.05 / 0.01)^0.25 at 0.01 bara: 89.7");
        assertEquals(68.17, Limit.VACUUM_SPEED.value(.03), .01, "68 m/s at 3 kPa");
        assertEquals(60, Limit.VACUUM_SPEED.value(.05), EXACT, "60 from 0.05 bara up");
    }

    @Test void aRowReadsBySense() {
        assertEquals(Lamp.GREEN, Limit.LINE_BORE.read(.8).lamp());
        assertEquals(Lamp.RED, Limit.LINE_BORE.read(.81).lamp());
        assertEquals(Lamp.GREEN, Limit.PUMP_NPSH.read(1).lamp(), "at least: on it meets it");
        assertEquals(Lamp.YELLOW, Limit.PUMP_NPSH.read(.9).lamp());
        assertEquals(Lamp.RED, Limit.PUMP_NPSH_WORLD.against(2, 2).lamp(), "above: on it misses it");
        assertEquals(Lamp.GREEN, Limit.PUMP_NPSH_WORLD.against(2.1, 2).lamp());
        assertEquals(Lamp.YELLOW, Limit.PUMP_RATED.read(69).lamp());
        assertEquals(Lamp.GREEN, Limit.PUMP_RATED.read(96).lamp());
        assertEquals(Lamp.YELLOW, Limit.PUMP_RATED.read(121).lamp());
        assertEquals(Lamp.RED, Limit.EXCHANGER_SURFACE.against(253, 444).lamp(), "the rating gives this limit");
        assertEquals(444 / 253., Limit.EXCHANGER_SURFACE.against(253, 444).load(), 1e-12);
        assertEquals(Lamp.RED, Limit.LINE_BORE.read(Double.NaN).lamp(), "no number meets nothing");
    }

    @Test void aRuleOrAGivenRowCannotBeReadAlone() {
        assertThrows(IllegalArgumentException.class, () -> Limit.SERVICE_BOILING.read(.2));
        assertThrows(IllegalStateException.class, () -> Limit.LINE_NOZZLE.read(.2));
        assertThrows(IllegalStateException.class, () -> Limit.LINE_SPEED.read(20), "the line's own state gives this one");
    }

    @Test void theLampIsTheWorstRowAndClosestIsWhatLimits() {
        assertEquals(Lamp.NONE, Rating.NONE.lamp());
        assertNull(Rating.NONE.closest());
        var met = new Rating(List.of(Limit.LINE_BORE.read(.8), Limit.LIQUID_SPEED.read(2.4), Limit.LIQUID_DROP.read(.4)));
        assertEquals(Lamp.GREEN, met.lamp());
        assertSame(Limit.LIQUID_DROP, met.closest().limit(), "a quiet row at its limit is not what limits a size that meets it");
        var both = new Rating(List.of(Limit.LIQUID_SPEED.read(30), Limit.LINE_BORE.read(.9)));
        assertEquals(Lamp.RED, both.lamp());
        assertSame(Limit.LINE_BORE, both.closest().limit(), "a Must missed by little outranks a Should missed by more");
    }

    private static Rating rating(double speed, int runs) {
        return new Rating(List.of(Limit.LIQUID_SPEED.read(speed), Limit.LINE_RUNS.read(runs), Limit.LINE_BORE.read(.5)));
    }

    @Test void theSearchTakesTheFirstThatMeetsEveryLimit() {
        var choice = Selection.smallest(List.of(9., 4., 2., 1.), speed -> rating(speed, 1));
        assertEquals(2, choice.size());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
    }

    @Test void whereNoneDoesItTakesTheNearestTheShoulds() {
        var choice = Selection.smallest(List.of(9., 4., 3.5, 3.5), speed -> rating(speed, 1));
        assertEquals(3.5, choice.size());
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
    }

    @Test void whereNoMustCanBeMetItIsRedAndStillAnswers() {
        var choice = Selection.smallest(List.of(1.2, 1.1, 1.3), bore -> new Rating(List.of(Limit.LINE_BORE.read(bore))));
        assertEquals(1.1, choice.size());
        assertEquals(Lamp.RED, choice.rating().lamp());
    }

    @Test void aPaidRowDoesNotStopTheSearchAndLightsTheLamp() {
        var choice = Selection.smallest(List.of(1, 2, 3), runs -> rating(8. / runs, runs));
        assertEquals(3, choice.size(), "the first that meets the speed");
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
        assertSame(Limit.LINE_RUNS, choice.rating().closest().limit());
    }

    @Test void aPaidRowIsPaidOnlyWhenItBuysEveryOtherLimit() {
        var choice = Selection.smallest(List.of(1, 3), runs -> rating(runs == 1 ? 3.1 : 3.05, runs));
        assertEquals(1, choice.size(), "three runs for 2 % nearer is no bargain");
    }

    @Test void noSizesAnswerNoSize() {
        var choice = Selection.smallest(List.<Double>of(), speed -> rating(speed, 1));
        assertNull(choice.size());
        assertEquals(Lamp.NONE, choice.rating().lamp());
    }
}
