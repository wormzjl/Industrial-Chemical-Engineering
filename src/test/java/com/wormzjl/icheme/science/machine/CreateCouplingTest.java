package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.machine.ShaftNetwork.*;
import com.wormzjl.icheme.science.machine.ShaftTrain.Drive;
import com.wormzjl.icheme.science.machine.ShaftTrain.Driver;
import com.wormzjl.icheme.science.machine.ShaftTrain.Measure;
import com.wormzjl.icheme.science.machine.ShaftTrain.Mode;
import com.wormzjl.icheme.science.machine.ShaftTrain.Trip;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Create coupling (batch 2026-11-03-rotating-machinery W6b, form A, decision D40): its driver step at Create's speed
 * within the power its network spares, NO ROTATION at rest, the stress rate (D7) and its impact, its deadbands, and the
 * gearbox ratio up to 64 only on the line a coupling drives (plan 5.3).
 */
class CreateCouplingTest {
    private static final double WPS = CreateCoupling.wattsPerStress(2.5, CreateCoupling.STRESS_PER_FE);

    private static Driver coupling(double rpm, double spareStress) {
        return new Driver(Drive.CREATE, 0, CreateCoupling.DESIGN_SPEED, 0, true, Mode.SPEED, rpm, spareStress * WPS, Trip.NONE, false);
    }

    /** D7: 1 su is 20 x 2.5 / 34.13 = 1.465 W; the 30 kW pump train takes 20,500 su; 100 kW at 256 rpm is 267 su per rpm. */
    @Test
    void theStressRateAndTheImpact() {
        assertEquals(1.46499, WPS, 1e-5);
        assertEquals(20478, 30e3 / WPS, 1);
        assertEquals(266.6, CreateCoupling.impact(100e3, 1e9, 256, WPS), .1);
        assertEquals(0, CreateCoupling.impact(100e3, 1e9, 0, WPS), "at rest it draws nothing");
        // Short of spare it draws at most the spare (less the float margin), never more.
        double spare = 5000;
        assertTrue(CreateCoupling.impact(100e3, spare * WPS, 256, WPS) * 256 <= spare);
        assertEquals(spare, CreateCoupling.impact(100e3, spare * WPS, 256, WPS) * 256, 1);
    }

    /** It turns at Create's |rpm| while the network spares the train's power, as the creative motor at its set speed. */
    @Test
    void itTurnsAtCreatesSpeedWithinTheSpare() {
        var step = ShaftTrain.step(coupling(256, 20480), 256, Measure.of(25.41e3));
        assertEquals(256, step.speed(), 0);
        assertEquals("RUNNING", step.status());
        assertEquals(0, step.electricPower(), 0, "no electricity");
        // A new Create speed is the set speed at once (quasi-steady).
        assertEquals(128, ShaftTrain.step(coupling(128, 20480), 256, Measure.of(25.41e3)).speed(), 0);
        // From rest it starts at Create's speed.
        assertEquals(256, ShaftTrain.step(coupling(256, 20480), 0, Measure.of(0)).speed(), 0);
    }

    /** Short of spare the train slows by the damped step (POWER LIMITED); no spare at all stands it. */
    @Test
    void shortOfSpareItSlowsAndTheNetworkKeepsItsCapacity() {
        double limit = 12288 * WPS;
        var step = ShaftTrain.step(coupling(256, 12288), 256, Measure.of(25.41e3));
        assertEquals("POWER LIMITED", step.status());
        assertEquals(256 * Math.pow(limit / 25.41e3, 1 / 6.0), step.speed(), 1e-6, "s (available/demand)^(1/6)");
        // Iterated on a cube-law load it settles where the train takes the spare.
        double speed = 256;
        for (int i = 0; i < 60; i++) speed = ShaftTrain.step(coupling(256, 12288), speed, Measure.of(25.41e3 * Math.pow(speed / 256, 3))).speed();
        assertEquals(limit, 25.41e3 * Math.pow(speed / 256, 3), 1e-3 * limit);
        var none = ShaftTrain.step(coupling(256, 0), 256, Measure.of(25.41e3));
        assertEquals(0, none.speed(), 0, "no spare stands the train, not a restart each slice");
        assertEquals("POWER LIMITED", none.status());
    }

    @Test
    void createAtRestStandsTheTrain() {
        var step = ShaftTrain.step(coupling(0, 20480), 256, Measure.of(25.41e3));
        assertEquals(0, step.speed(), 0);
        assertEquals("NO ROTATION", step.status());
        assertEquals(Trip.NONE, step.trip());
    }

    /** Deadbands: a speed change or 1 % and 1 su of spare wakes the engine; an impact moves past 0.1 %, or starts or stops. */
    @Test
    void deadbands() {
        assertTrue(CreateCoupling.moved(Double.NaN, Double.NaN, 0, 0), "the first report");
        assertFalse(CreateCoupling.moved(256, 20000, 256, 20100));
        assertTrue(CreateCoupling.moved(256, 20000, 256, 20300));
        assertFalse(CreateCoupling.moved(256, 50, 256, 50.9), "1 su at least");
        assertTrue(CreateCoupling.moved(256, 20000, 128, 20000));
        assertFalse(CreateCoupling.impactMoved(100, 100.05));
        assertTrue(CreateCoupling.impactMoved(100, 100.2));
        assertTrue(CreateCoupling.impactMoved(0, 1e-6));
        assertTrue(CreateCoupling.impactMoved(1e-6, 0));
    }

    private static Cell at(int x) {
        return new Cell("minecraft:overworld", x, 0, 0);
    }

    private static End end(int x, int dx, boolean output) {
        return new End(at(x), dx, 0, 0, output);
    }

    /** {@code driver} at x 0 driving east, a shaft at 1, a gearbox at 2 (slow end west, fast end east, {@code ratio}), a shaft at 3, a casing at 4. */
    private static Train geared(Kind driver, double driverSpeed, double ratio, double casingSpeed) {
        return ShaftNetwork.compile(List.of(new Part(1, driver, null, -1, List.of(end(0, 1, true)), 0, driverSpeed), Part.shaft(2, at(1), 0),
                new Part(3, Kind.GEARBOX, null, -1, List.of(end(2, -1, false), end(2, 1, true)), ratio, 0), Part.shaft(4, at(3), 0),
                new Part(5, Kind.CASING, null, -1, List.of(end(4, -1, false)), 0, casingSpeed))).getFirst();
    }

    /** Plan 5.3: a gearbox on the line a coupling drives steps up to 64; behind any other driver it stays within 20. */
    @Test
    void aGearboxStepsUpTo64OnlyBehindACoupling() {
        assertEquals(Fault.NONE, geared(Kind.CREATE_COUPLING, 256, 40, 2950).fault());
        assertEquals(40, geared(Kind.CREATE_COUPLING, 256, 40, 2950).ratios().get(3L), 0, "a set 40 holds behind a coupling");
        assertEquals(ShaftNetwork.MAXIMUM_RATIO, geared(Kind.CREATIVE_MOTOR, 256, 40, 2950).ratios().get(3L), 0, "behind a creative motor it is cut to 20");
        assertEquals(ShaftNetwork.MAXIMUM_RATIO, geared(Kind.MOTOR, 256, 40, 2950).ratios().get(3L), 0, "and behind a motor");
        // Auto: 2950 / 256 = 11.52; a casing at 16,384 rpm takes 64, at 20,000 rpm the gearbox stops at 64.
        assertEquals(2950 / 256.0, geared(Kind.CREATE_COUPLING, 256, 0, 2950).ratios().get(3L), 1e-12);
        assertEquals(64, geared(Kind.CREATE_COUPLING, 256, 0, 16384).ratios().get(3L), 1e-12);
        assertEquals(ShaftNetwork.CREATE_RATIO, geared(Kind.CREATE_COUPLING, 256, 0, 20000).ratios().get(3L), 0);
        assertEquals(16384, 256 * geared(Kind.CREATE_COUPLING, 256, 0, 16384).driven().getFirst().ratio(), 1e-9);
        // A gearbox whose fast end faces the coupling (a step down) keeps 1/20.
        var down = ShaftNetwork.compile(List.of(new Part(1, Kind.CREATE_COUPLING, null, -1, List.of(end(0, 1, true)), 0, 256), Part.shaft(2, at(1), 0),
                new Part(3, Kind.GEARBOX, null, -1, List.of(end(2, -1, true), end(2, 1, false)), 40, 0), Part.shaft(4, at(3), 0),
                new Part(5, Kind.CASING, null, -1, List.of(end(4, -1, false)), 0, 5))).getFirst();
        assertEquals(ShaftNetwork.MAXIMUM_RATIO, down.ratios().get(3L), 0);
        assertEquals(1 / ShaftNetwork.MAXIMUM_RATIO, down.driven().getFirst().ratio(), 1e-12);
    }
}
