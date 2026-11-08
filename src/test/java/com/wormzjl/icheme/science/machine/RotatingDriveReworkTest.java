package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class RotatingDriveReworkTest {
    @Test
    void vfdHasFiniteLowSpeedTorqueAndHighSpeedPower() {
        assertEquals(11000, Motor.availablePower(22000, 3000, 1500), 1e-9);
        assertEquals(22000, Motor.availablePower(22000, 1500, 3000), 1e-9);
        assertEquals(Motor.torque(22000, 3000), Motor.torque(11000, 1500), 1e-9);
        assertEquals(0, Motor.availablePower(22000, 3000, 0));
    }

    @Test
    void manualAndAutomaticMotorsNeverExceed3000Rpm() {
        for (double base : new double[] {1500, 3000, 12000})
            for (var mode : List.of(ShaftTrain.Mode.SPEED, ShaftTrain.Mode.REGULATE_FLOW)) {
                var driver =
                        new ShaftTrain.Driver(
                                ShaftTrain.Drive.VARIABLE_MOTOR,
                                22000,
                                base,
                                4,
                                true,
                                mode,
                                10,
                                0,
                                ShaftTrain.Trip.NONE,
                                false);
                var measure = new ShaftTrain.Measure(0, 0, false, 1, 100, Double.NaN, Double.NaN);
                assertEquals(3000, ShaftTrain.step(driver, 2950, measure).speed(), 1e-9);
                assertTrue(driver.setSpeed() <= Motor.MAXIMUM_RPM);
            }
    }

    @Test
    void inlineRatingsFollowBoreAreaAndKeepTheRatedHead() {
        var small = InlineMachines.design(.025, true);
        var large = InlineMachines.design(.05, true);
        assertEquals(4 * small.law().ratedFlow(), large.law().ratedFlow(), 1e-12);
        assertEquals(25, ((CentrifugalPump) large.law()).head(large.law().ratedFlow(), 1), 1e-9);
        assertTrue(
                InlineMachines.ratedDraw(large)
                        > large.law()
                                .fluidPower(
                                        large.law().ratedFlow() * 1000,
                                        25 * 1000 * 9.80665,
                                        1000,
                                        100000,
                                        1.4));
    }

    @Test
    void demandInversionFindsTheRatedPointAndClampsUnreachableDuties() {
        var pump = CentrifugalPump.rated(.01, 25);
        assertEquals(
                1,
                SpeedControl.requiredSpeed(
                        pump, .01, 25 * 1000 * 9.80665, 1000, 100000, 1.4, .1, 1.5),
                1e-9);
        assertEquals(1.5, SpeedControl.requiredSpeed(pump, .01, 1e9, 1000, 100000, 1.4, .1, 1.5));
    }

    @Test
    void pistonDemandSurvivesAPlannedDisplacementChange() {
        var design =
                new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(
                        .01, 3, true, .008, 220000);
        var planned = design.withDisplacement(.005).withEnabled(false);
        assertEquals(.008, planned.setFlow());
        assertEquals(220000, planned.setPressure());
        assertEquals(.01, design.requestedVolumeFlow());
    }

    @Test
    void nativeCreateShaftCanOwnTheTrainWithoutChangingItsSavedKind() {
        var cell = new ShaftNetwork.Cell("minecraft:overworld", 0, 0, 0);
        var shaft = ShaftNetwork.Part.shaft(1, cell, 0).withRole(ShaftNetwork.Role.SOURCE, 256);
        var casing =
                new ShaftNetwork.Part(
                        2,
                        ShaftNetwork.Kind.CASING,
                        null,
                        -1,
                        List.of(
                                new ShaftNetwork.End(
                                        new ShaftNetwork.Cell("minecraft:overworld", 1, 0, 0),
                                        -1,
                                        0,
                                        0,
                                        false)),
                        0,
                        256);
        var train = ShaftNetwork.compile(List.of(shaft, casing)).getFirst();
        assertEquals(ShaftNetwork.Fault.NONE, train.fault());
        assertEquals(1, train.driver());
        assertEquals(ShaftNetwork.Kind.SHAFT, shaft.kind());
        assertEquals(2, train.driven().getFirst().id());
    }

    @Test
    void exportedCreateEndpointIsALoadBesideAnIndustrialMotor() {
        var shaft =
                ShaftNetwork.Part.shaft(1, new ShaftNetwork.Cell("minecraft:overworld", 0, 0, 0), 0)
                        .withRole(ShaftNetwork.Role.LOAD, 0);
        var motor =
                new ShaftNetwork.Part(
                        2,
                        ShaftNetwork.Kind.MOTOR,
                        null,
                        -1,
                        List.of(
                                new ShaftNetwork.End(
                                        new ShaftNetwork.Cell("minecraft:overworld", 1, 0, 0),
                                        -1,
                                        0,
                                        0,
                                        true)),
                        0,
                        256);
        var train = ShaftNetwork.compile(List.of(shaft, motor)).getFirst();
        assertEquals(ShaftNetwork.Fault.NONE, train.fault());
        assertEquals(2, train.driver());
        assertEquals(1, train.driven().getFirst().id());
    }
}
