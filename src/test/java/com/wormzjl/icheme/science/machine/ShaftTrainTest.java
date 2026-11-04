package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.machine.ShaftTrain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A shaft train's driver step (batch 2026-11-03-rotating-machinery W3, build plan section 2): the build plan's two cases
 * to 1e-3, a gearbox train, the creative motor's limit and a generator's load.
 */
class ShaftTrainTest {
    /** The pump of case 1: 100 m³/h at 80 m, 70 %, on liquid of 800 kg/m³. */
    private static final CentrifugalPump PUMP = new CentrifugalPump(100 / 3600.0, 80, .7, 2950);
    private static final double RHO = 800, RATED = PUMP.shaftPower(100 / 3600.0, 1, RHO);

    private static Driver motor(Drive drive, double rated) {
        return new Driver(drive, rated, 2950, 2, true, Mode.SPEED, 0, 0, Trip.NONE, false);
    }

    private static Driver turbine(boolean governed) {
        return new Driver(Drive.TURBINE, 55e3, 6000, 0, true, Mode.SPEED, 0, 0, Trip.NONE, governed);
    }

    /** Case 1: the pump's 24.9 kW on a 30 kW 2-pole motor, 26.7 kW drawn (534 FE/t at 2.5 J/FE, shown, not booked). */
    @Test
    void case1APumpOnAFixedMotorRunsAndDrawsItsElectricPower() {
        assertEquals(24.906e3, RATED, .001 * 24.906e3, "ρ g Q H / η");
        var step = ShaftTrain.step(motor(Drive.FIXED_MOTOR, 30e3), 2950, Measure.of(RATED));
        assertEquals(2950, step.speed(), 0);
        assertEquals(Trip.NONE, step.trip());
        assertEquals("RUNNING", step.status());
        assertEquals(26.694e3, step.electricPower(), .001 * 26.694e3, "24.9 kW over the IE3 93.3 %");
        assertEquals(534, step.electricPower() / 2.5 / 20, 1, "FE per tick");
    }

    /** At 150 % flow the pump takes 1.325 times its rated power, 33.0 kW: the 30 kW fixed motor trips and stands. */
    @Test
    void case1AFixedMotorTripsOnOverloadAndStaysTripped() {
        double demand = PUMP.shaftPower(1.5 * 100 / 3600.0, 1, RHO);
        assertEquals(33.000e3, demand, .001 * 33e3);
        var step = ShaftTrain.step(motor(Drive.FIXED_MOTOR, 30e3), 2950, Measure.of(demand));
        assertEquals(Trip.OVERLOAD, step.trip());
        assertEquals(0, step.speed(), 0);
        assertEquals("TRIPPED: OVERLOAD", step.status());
        // Latched: a step with the trip set stands at zero whatever the load, until the player resets it.
        var tripped = new Driver(Drive.FIXED_MOTOR, 30e3, 2950, 2, true, Mode.SPEED, 0, 0, Trip.OVERLOAD, false);
        assertEquals(0, ShaftTrain.step(tripped, 0, Measure.of(0)).speed(), 0);
        assertEquals("TRIPPED: OVERLOAD", ShaftTrain.step(tripped, 0, Measure.of(0)).status());
    }

    /** A variable-speed motor at its current limit on a friction line (P ∝ s³): 0.984, 0.976, 0.973 toward 0.969, the error halving. */
    @Test
    void case1AVariableSpeedMotorAtItsLimitStepsDownToItsBalance() {
        var driver = motor(Drive.VARIABLE_MOTOR, 30e3);
        double full = 1.325 * RATED, speed = 2950;
        var expected = List.of(.98424, .97645, .97258);
        for (double share : expected) {
            var step = ShaftTrain.step(driver, speed, Measure.of(full * cube(speed / 2950)));
            assertEquals("CURRENT LIMITED", step.status());
            speed = step.speed();
            assertEquals(share, speed / 2950, 1e-3 * share);
        }
        assertEquals(.96873, DeterministicCube.root(30e3 / full), 1e-5, "the balance");
        // The error to the balance halves each step (1 − 3/6).
        double e1 = .98424 - .96873, e2 = .97645 - .96873;
        assertEquals(.5, e2 / e1, .03);
    }

    /** Case 2: a governed 55 kW turbine (η_is 0.45) across 524 kJ/kg feeds the 24.9 kW forward: 0.1056 kg/s, 15.3 kg/kWh. */
    @Test
    void case2AGovernedTurbineFeedsItsDemandForward() {
        var design = SteamTurbine.rated(55e3);
        assertEquals(.45, design.efficiency(), 1e-12);
        var step = ShaftTrain.step(turbine(true), 6000, Measure.of(RATED));
        assertEquals(6000, step.speed(), 0);
        assertEquals("RUNNING", step.status());
        assertEquals(RATED, step.governorPower(), 1e-9 * RATED, "at the set speed, the demand there");
        double steam = SteamTurbineTest.steamFor(design, step.governorPower(), 524e3);
        assertEquals(.10562, steam, 1e-3 * .10562);
        assertEquals(15.27, steam * 3600 / (RATED / 1000), .01, "kg/kWh");
        // Below its set speed, the governor feeds the demand at the set speed forward: the cube law.
        assertEquals(RATED, ShaftTrain.step(turbine(true), 5400, Measure.of(RATED * cube(.9))).governorPower(), 1e-9 * RATED);
    }

    /** Case 2 short of steam: 0.080 kg/s at most gives 18.9 kW; the shaft goes 0.955, 0.933 toward 0.912. */
    @Test
    void case2AGovernedTurbineShortOfSteamSlowsToItsBalance() {
        double available = .080 * .45 * 524e3, speed = 6000;
        assertEquals(18.864e3, available, 1);
        for (double share : List.of(.95475, .93290)) {
            var step = ShaftTrain.step(turbine(true), speed, new Measure(RATED * cube(speed / 6000), available, true, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
            assertEquals("STEAM LIMITED", step.status());
            speed = step.speed();
            assertEquals(share, speed / 6000, 1e-3 * share);
        }
        assertEquals(.91154, DeterministicCube.root(available / RATED), 1e-5, "the balance");
    }

    /** An ungoverned turbine whose pump's discharge closes: demand 8.7 kW (35 %), s' = (24.9/8.7)^(1/6) = 1.19, past 1.10: it trips. */
    @Test
    void case2AnUngovernedTurbineOverspeedsAndTrips() {
        double shutoff = PUMP.shaftPower(0, 1, RHO);
        assertEquals(.35 * RATED, shutoff, 1e-9 * RATED);
        var step = ShaftTrain.step(turbine(false), 6000, new Measure(shutoff, RATED, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
        assertEquals(Trip.OVERSPEED, step.trip());
        assertEquals("TRIPPED: OVERSPEED", step.status());
        assertEquals(1.19121, SpeedControl.balance(1, RATED, shutoff), 1e-3 * 1.19121);
        // A governed one shuts its valve and holds its set speed.
        var governed = ShaftTrain.step(turbine(true), 6000, new Measure(shutoff, RATED, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
        assertEquals(6000, governed.speed(), 0);
        assertEquals(shutoff, governed.governorPower(), 1e-9 * shutoff);
    }

    /** A 796 kW compressor through a 4.27 gearbox at 0.98 demands 812 kW of a 1 MW 2-pole motor, which draws 848 kW. */
    @Test
    void aGearboxTrainDemandsItsMachinesPowerOverTheGearEfficiency() {
        var trains = ShaftNetwork.compile(List.of(
                new ShaftNetwork.Part(1, ShaftNetwork.Kind.MOTOR, null, -1, List.of(new ShaftNetwork.End(new ShaftNetwork.Cell("d", 0, 0, 0), 1, 0, 0, true)), 0, 2950),
                ShaftNetwork.Part.shaft(2, new ShaftNetwork.Cell("d", 1, 0, 0), 0),
                new ShaftNetwork.Part(3, ShaftNetwork.Kind.GEARBOX, null, -1, List.of(new ShaftNetwork.End(new ShaftNetwork.Cell("d", 2, 0, 0), -1, 0, 0, false),
                        new ShaftNetwork.End(new ShaftNetwork.Cell("d", 2, 0, 0), 1, 0, 0, true)), 4.27, 0),
                ShaftNetwork.Part.shaft(4, new ShaftNetwork.Cell("d", 3, 0, 0), 0),
                new ShaftNetwork.Part(5, ShaftNetwork.Kind.CASING, null, -1, List.of(new ShaftNetwork.End(new ShaftNetwork.Cell("d", 4, 0, 0), -1, 0, 0, false)), 0, 12597)));
        var driven = trains.getFirst().driven().getFirst();
        assertEquals(4.27, driven.ratio(), 0);
        double demand = 796e3 / driven.efficiency();
        assertEquals(812.24e3, demand, .001 * 812.24e3);
        var step = ShaftTrain.step(new Driver(Drive.FIXED_MOTOR, 1e6, 2950, 2, true, Mode.SPEED, 0, 0, Trip.NONE, false), 2950, Measure.of(demand));
        assertEquals("RUNNING", step.status());
        assertEquals(847.85e3, step.electricPower(), .001 * 847.85e3, "over the IE3 95.8 % of a 1 MW motor");
    }

    /** The creative motor holds its set speed up to its limit (default 1 MW), then steps down as a drive at its limit does. */
    @Test
    void theCreativeMotorHoldsItsSpeedUpToItsLimit() {
        var creative = new Driver(Drive.CREATIVE, 0, ShaftTrain.CREATIVE_SPEED, 0, true, Mode.SPEED, 0, 0, Trip.NONE, false);
        assertEquals(2950, creative.setSpeed(), 0);
        assertEquals(1e6, creative.limit(), 0);
        var easy = ShaftTrain.step(creative, 2950, Measure.of(5e5));
        assertEquals(2950, easy.speed(), 0);
        assertEquals("RUNNING", easy.status());
        assertEquals(0, easy.electricPower(), 0, "no electricity");
        var hard = ShaftTrain.step(creative, 2950, Measure.of(2e6));
        assertEquals("POWER LIMITED", hard.status());
        assertEquals(2628.15, hard.speed(), .001 * 2628.15, "2950 (1/2)^(1/6)");
        var set = new Driver(Drive.CREATIVE, 0, ShaftTrain.CREATIVE_SPEED, 0, true, Mode.SPEED, 1500, 5e4, Trip.NONE, false);
        assertEquals(1500, ShaftTrain.step(set, 1500, Measure.of(1e4)).speed(), 0);
    }

    /** A generator takes no power until W5: its train's demand is 0, the motor runs drawing nothing. */
    @Test
    void aGeneratorIsALoadOfNothing() {
        var trains = ShaftNetwork.compile(List.of(
                new ShaftNetwork.Part(1, ShaftNetwork.Kind.MOTOR, null, -1, List.of(new ShaftNetwork.End(new ShaftNetwork.Cell("d", 0, 0, 0), 1, 0, 0, true)), 0, 2950),
                ShaftNetwork.Part.shaft(2, new ShaftNetwork.Cell("d", 1, 0, 0), 0),
                new ShaftNetwork.Part(3, ShaftNetwork.Kind.GENERATOR, null, -1, List.of(new ShaftNetwork.End(new ShaftNetwork.Cell("d", 2, 0, 0), -1, 0, 0, false)), 0, 3000)));
        assertEquals(List.of(new ShaftNetwork.Driven(3, 1, 1)), trains.getFirst().driven());
        var step = ShaftTrain.step(motor(Drive.FIXED_MOTOR, 30e3), 2950, Measure.of(0));
        assertEquals("RUNNING", step.status());
        assertEquals(0, step.electricPower(), 0);
    }

    /** A stopped driver stands. */
    @Test
    void aStoppedDriverStands() {
        var stopped = new Driver(Drive.VARIABLE_MOTOR, 30e3, 2950, 2, false, Mode.SPEED, 0, 0, Trip.NONE, false);
        var step = ShaftTrain.step(stopped, 2950, Measure.of(RATED));
        assertEquals(0, step.speed(), 0);
        assertEquals("STOPPED", step.status());
    }

    private static double cube(double x) {
        return x * x * x;
    }

    /** The cube root, for the balances the tests state. */
    private static final class DeterministicCube {
        static double root(double x) {
            return com.wormzjl.icheme.science.math.DeterministicMath.cbrt(x);
        }
    }
}
