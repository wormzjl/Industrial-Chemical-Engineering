package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1's line gate (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.3 and 3.5): each service at both sides of
 * each of its limits, Auto against the rate function, and what no size can meet.
 */
final class LineSizingTest {
    private static final double LIQUID = 1e-3, GAS = 1.5e-5, BAR = 1e5, ATMOSPHERE = 101325, NOZZLE = .8, LENGTH = 50, SPEED = 100;

    private static LineSizing.Line line(Service service, double mass, double density, double viscosity, double pressure) {
        return new LineSizing.Line(service, mass, mass / density, density, viscosity, pressure, LENGTH, NOZZLE, SPEED, null);
    }

    /**
     * One limit of one service.
     *
     * @param density chosen so this limit is the one that binds at this bore
     */
    private record Side(Service service, Limit row, double bore, double density, double viscosity, double pressure) {
    }

    private static double gauge(double barg) {
        return ATMOSPHERE + barg * BAR;
    }

    private static final List<Side> SIDES = List.of(
            new Side(Service.SUCTION_BOILING, Limit.SUCTION_BOILING_SPEED, .2, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_BOILING, Limit.SUCTION_BOILING_SPEED, .25, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_BOILING, Limit.SUCTION_BOILING_SPEED, .5, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_BOILING, Limit.SUCTION_BOILING_SPEED, .6, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_BOILING, Limit.SUCTION_BOILING_DROP, .05, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_COLD, Limit.SUCTION_COLD_SPEED, .2, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_COLD, Limit.SUCTION_COLD_SPEED, .3, 800, LIQUID, gauge(2)),
            new Side(Service.SUCTION_COLD, Limit.SUCTION_COLD_DROP, .05, 800, LIQUID, gauge(2)),
            new Side(Service.DRAW, Limit.DRAW_SPEED, .05, 400, LIQUID, gauge(2)),
            new Side(Service.DRAW, Limit.DRAW_SPEED, .1, 800, LIQUID, gauge(2)),
            new Side(Service.DRAW, Limit.DRAW_DROP, .025, 800, LIQUID, gauge(2)),
            new Side(Service.LIQUID, Limit.LIQUID_SPEED, .5, 300, LIQUID, gauge(10)),
            new Side(Service.LIQUID, Limit.LIQUID_DROP, .1, 800, LIQUID, gauge(10)),
            new Side(Service.WATER, Limit.WATER_SPEED, .5, 300, LIQUID, gauge(3)),
            new Side(Service.WATER, Limit.WATER_DROP, .1, 1000, LIQUID, gauge(3)),
            new Side(Service.GAS, Limit.GAS_SPEED, .8, 1, GAS, gauge(.05)),
            new Side(Service.GAS, Limit.GAS_SPEED, .8, 1.5, GAS, gauge(.5)),
            new Side(Service.GAS, Limit.GAS_SPEED, .8, 4, GAS, gauge(3)),
            new Side(Service.GAS, Limit.GAS_SPEED, .8, 12, GAS, gauge(10)),
            new Side(Service.GAS, Limit.GAS_DROP, .1, 5, GAS, gauge(.5)),
            new Side(Service.GAS, Limit.GAS_MOMENTUM, .8, 80, GAS, gauge(10)),
            new Side(Service.GAS, Limit.GAS_MOMENTUM, .8, 80, GAS, gauge(30)),
            new Side(Service.GAS, Limit.GAS_MOMENTUM, .8, 80, GAS, gauge(60)),
            new Side(Service.GAS, Limit.GAS_MOMENTUM, .8, 80, GAS, gauge(90)),
            new Side(Service.STEAM_SATURATED, Limit.STEAM_SATURATED_SPEED, .5, 2, GAS, gauge(2)),
            new Side(Service.STEAM_SATURATED, Limit.STEAM_SATURATED_DROP, .1, 2, GAS, gauge(2)),
            new Side(Service.STEAM_SATURATED, Limit.STEAM_SATURATED_MOMENTUM, .8, 30, GAS, gauge(40)),
            new Side(Service.STEAM_SUPERHEATED, Limit.STEAM_SUPERHEATED_SPEED, .5, 2, GAS, gauge(2)),
            new Side(Service.STEAM_SUPERHEATED, Limit.STEAM_SUPERHEATED_DROP, .05, 2, GAS, gauge(2)),
            new Side(Service.STEAM_SUPERHEATED, Limit.STEAM_SUPERHEATED_MOMENTUM, .8, 30, GAS, gauge(40)),
            // 1 kPa: the row's smooth rise, 60 (5 / 1)^¼ = 89.7 m/s (batch 2026-11-10-steam-ejector-flow). Below 0.65 kPa it passes the
            // world's 100 m/s cap, which then holds the line first.
            new Side(Service.VACUUM_VAPOUR, Limit.VACUUM_SPEED, .8, .002, GAS, .01 * BAR),
            new Side(Service.VACUUM_VAPOUR, Limit.VACUUM_SPEED, .8, .03, GAS, .03 * BAR),
            new Side(Service.VACUUM_VAPOUR, Limit.VACUUM_SPEED, .8, .5, GAS, .5 * BAR),
            new Side(Service.VACUUM_VAPOUR, Limit.VACUUM_DROP, .1, .5, GAS, .5 * BAR),
            new Side(Service.TWO_PHASE, Limit.TWO_PHASE_SPEED, .5, 100, GAS, gauge(4)),
            new Side(Service.TWO_PHASE, Limit.TWO_PHASE_MOMENTUM, .5, 100, GAS, gauge(4)));

    /** The mass flow at which {@code side}'s row reads {@code load}: 1 is on the limit, and a floor's load falls with the flow. */
    private static double flow(Side side, double load) {
        var size = new LineSizing.Size(side.bore(), 1);
        double low = 1e-6, high = 1e6;
        boolean floor = side.row().bound().sense() == Bound.Sense.LEAST;
        for (int i = 0; i < 200; i++) {
            double middle = Math.sqrt(low * high);
            double read = LineSizing.rate(line(side.service(), middle, side.density(), side.viscosity(), side.pressure()), size).reading(side.row()).load();
            if (read < load != floor) low = middle;
            else high = middle;
        }
        return Math.sqrt(low * high);
    }

    private static Rating rated(Side side, double load) {
        return LineSizing.rate(line(side.service(), flow(side, load), side.density(), side.viscosity(), side.pressure()), new LineSizing.Size(side.bore(), 1));
    }

    @Test void everyServiceStandsAtBothSidesOfEachOfItsLimits() {
        var seen = new ArrayList<Service>();
        for (var side : SIDES) {
            String name = side.service() + " " + side.row() + " at " + side.bore() + " m, " + side.pressure() + " Pa";
            var under = rated(side, .99);
            assertEquals(Lamp.GREEN, under.lamp(), name);
            assertSame(side.row(), under.closest().limit(), name);
            var over = rated(side, 1.01);
            assertEquals(Lamp.YELLOW, over.lamp(), name);
            assertSame(side.row(), over.closest().limit(), name);
            assertFalse(over.reading(side.row()).met(), name);
            if (!seen.contains(side.service())) seen.add(side.service());
        }
        assertEquals(List.of(Service.values()), seen, "all ten rows");
    }

    @Test void aServiceAsksOnlyTheLimitsItsRowHas() {
        assertNull(Service.WATER.momentum());
        assertNull(Service.TWO_PHASE.drop());
        assertNull(Service.VACUUM_VAPOUR.momentum());
        var size = new LineSizing.Size(.3, 1);
        var rating = LineSizing.rate(line(Service.TWO_PHASE, 50, 100, GAS, gauge(4)), size);
        assertNull(rating.reading(Limit.GAS_DROP));
        assertEquals(Bound.Sense.LEAST, Limit.TWO_PHASE_SPEED.bound().sense(), "a floor");
        assertEquals(Limit.Kind.SHOULD, Limit.TWO_PHASE_SPEED.kind());
    }

    @Test void vacuumVapoursDropReadsTheLinesLength() {
        var size = new LineSizing.Size(.3, 1);
        double mass = .3;
        var short_ = LineSizing.rate(new LineSizing.Line(Service.VACUUM_VAPOUR, mass, mass / .5, .5, GAS, .5 * BAR, 25, NOZZLE, SPEED, null), size).reading(Limit.VACUUM_DROP);
        var long_ = LineSizing.rate(new LineSizing.Line(Service.VACUUM_VAPOUR, mass, mass / .5, .5, GAS, .5 * BAR, 50, NOZZLE, SPEED, null), size).reading(Limit.VACUUM_DROP);
        assertEquals(2, long_.value() / short_.value(), 1e-9);
        double drop = PipeResistance.pressureDrop(new PipeResistance.Geometry(50, .3, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0), mass, .5, GAS);
        assertEquals(100 * drop / (.5 * BAR), long_.value(), 1e-9, "the world's law, over the line, as a share of the pressure");
    }

    @Test void aServicePricedPerHundredMetresDoesNotReadTheLength() {
        var size = new LineSizing.Size(.3, 1);
        var short_ = new LineSizing.Line(Service.LIQUID, 80, .1, 800, LIQUID, 5 * BAR, 10, NOZZLE, SPEED, null);
        var long_ = new LineSizing.Line(Service.LIQUID, 80, .1, 800, LIQUID, 5 * BAR, 500, NOZZLE, SPEED, null);
        assertEquals(LineSizing.rate(short_, size).reading(Limit.LIQUID_DROP).value(), LineSizing.rate(long_, size).reading(Limit.LIQUID_DROP).value(), 0);
    }

    @Test void aLineHoldsItsOwnLength() {
        assertEquals(50, DesignBasis.DEFAULT.length(null), 0);
        assertEquals(75, DesignBasis.DEFAULT.length(75.), 0);
        assertEquals(1.10, DesignBasis.DEFAULT.designFlow(), 0);
        assertEquals(60, DesignBasis.DEFAULT.paFall(), 0);
        assertThrows(IllegalArgumentException.class, () -> new DesignBasis(0, 50, 60));
    }

    @Test void runsShareTheFlowEvenly() {
        var one = new LineSizing.Line(Service.WATER, 100, .1, 1000, LIQUID, 5 * BAR, 50, NOZZLE, SPEED, null);
        var half = new LineSizing.Line(Service.WATER, 50, .05, 1000, LIQUID, 5 * BAR, 50, NOZZLE, SPEED, null);
        var rating = LineSizing.rate(one, new LineSizing.Size(.3, 2));
        assertEquals(LineSizing.drop(half, new LineSizing.Size(.3, 1), 100), rating.reading(Limit.WATER_DROP).value(), 1e-12);
        assertEquals(.1 / (2 * Math.PI * .3 * .3 / 4), rating.reading(Limit.WATER_SPEED).value(), 1e-12);
    }

    @Test void autoIsTheSmallestSizeTheRateFunctionPasses() {
        var bores = List.of(.025, .05, .075, .1, .15, .2, .25, .3, .35, .4, .45, .5, .6, .7, .8);
        for (double mass : new double[]{3, 30, 300}) {
            var line = line(Service.LIQUID, mass, 800, LIQUID, 5 * BAR);
            var choice = LineSizing.auto(line);
            assertEquals(Lamp.GREEN, choice.rating().lamp(), mass + " kg/s");
            assertEquals(1, choice.size().runs());
            assertEquals(LineSizing.rate(line, choice.size()), choice.rating(), "one rule");
            int at = bores.indexOf(choice.size().bore());
            assertTrue(at > 0);
            assertEquals(Lamp.YELLOW, LineSizing.rate(line, new LineSizing.Size(bores.get(at - 1), 1)).lamp(), "one step down fails");
        }
    }

    @Test void pastOneRunAutoTakesParallelRunsWithAYellowLamp() {
        var line = line(Service.WATER, 3000, 1000, LIQUID, 5 * BAR);
        var choice = LineSizing.auto(line);
        assertEquals(new LineSizing.Size(.8, 2), choice.size(), "2 × 0.7 m runs 3.9 m/s, 1 × 0.8 m 6.0 m/s");
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
        assertSame(Limit.LINE_RUNS, choice.rating().closest().limit());
    }

    @Test void whereNoSizeMeetsAShouldItTakesTheNearest() {
        // 3.8 m/s is the most ρv² allows and 3 m/s the least the floor does: 0.3 m runs 3.96 m/s and 0.35 m 2.91 m/s.
        var line = line(Service.TWO_PHASE, 196, 700, GAS, 5 * BAR);
        var choice = LineSizing.auto(line);
        assertEquals(new LineSizing.Size(.35, 1), choice.size(), "3 % under the floor, not 10 % over ρv²");
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
    }

    @Test void whereNoMustCanBeMetItIsRedWithoutAnException() {
        var over = line(Service.GAS, 300, 1, GAS, 5 * BAR);
        var choice = LineSizing.auto(over);
        assertEquals(new LineSizing.Size(.8, 4), choice.size(), "as near as the sizes come");
        assertEquals(Lamp.RED, choice.rating().lamp());
        assertSame(Limit.LINE_SPEED, choice.rating().closest().limit());
    }

    @Test void aNozzleBoundsTheBore() {
        var free = LineSizing.auto(line(Service.LIQUID, 200, 800, LIQUID, 5 * BAR));
        assertEquals(.35, free.size().bore());
        var tight = new LineSizing.Line(Service.LIQUID, 200, .25, 800, LIQUID, 5 * BAR, 50, .3, SPEED, null);
        var choice = LineSizing.auto(tight);
        assertEquals(new LineSizing.Size(.25, 2), choice.size(), "each run on what the nozzle takes");
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
        var none = new LineSizing.Line(Service.LIQUID, 200, .25, 800, LIQUID, 5 * BAR, 50, .02, SPEED, null);
        assertEquals(Lamp.RED, LineSizing.auto(none).rating().lamp(), "no usual bore fits");
    }

    @Test void aFixedLineRatesAnyBoreAndAnyCount() {
        var line = line(Service.LIQUID, 200, 800, LIQUID, 5 * BAR);
        var wide = LineSizing.rate(line, new LineSizing.Size(1, 1));
        assertEquals(Lamp.RED, wide.lamp());
        assertSame(Limit.LINE_BORE, wide.closest().limit());
        var odd = LineSizing.rate(line, new LineSizing.Size(.33, 6));
        assertEquals(Lamp.YELLOW, odd.lamp());
        assertSame(Limit.LINE_RUNS, odd.closest().limit());
        var pin = LineSizing.rate(line, new LineSizing.Size(1e-5, 1));
        assertEquals(Lamp.RED, pin.lamp(), "a bore the wall fills rates, and it fails");
        assertThrows(IllegalArgumentException.class, () -> new LineSizing.Size(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LineSizing.Size(.1, 0));
    }

    @Test void aLineWithNoFlowHasNoSizeAndNoLamp() {
        var choice = LineSizing.auto(LineSizing.Line.NONE);
        assertNull(choice.size());
        assertEquals(Lamp.NONE, choice.rating().lamp());
        assertEquals(Lamp.NONE, LineSizing.rate(LineSizing.Line.NONE, new LineSizing.Size(.1, 1)).lamp(), "a Fixed size on it is not red");
    }
}
