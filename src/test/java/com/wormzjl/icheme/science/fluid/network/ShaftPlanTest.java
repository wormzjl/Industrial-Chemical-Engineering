package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A shaft train's slice inputs (batch 2026-11-03-rotating-machinery W3, build plan section 2): applied at dispatch to the
 * graph the slice solves, a machine's speed share stands it closed at 0 and moves it along its speed line, a frame takes
 * its displacement, a governor valve its mass target outside the valve deadband; the solved graph's controls differ from
 * the dispatched ones only by such plans ({@link ShaftPlan#planned}).
 */
class ShaftPlanTest extends PortFixture {
    private static final PipeResistance.Geometry FAT = new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    /** 36 m³/h at 50 m, 70 %. */
    private static final CentrifugalPump PUMP = new CentrifugalPump(.01, 50, .7);

    private static PassiveNetwork line(FluidThermodynamics.State from, FluidThermodynamics.State to, FlowControl control) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, from, PassiveNetwork.NodeKind.GENERATOR), new PassiveNetwork.Reservoir(2, 0, to, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10, 0, 1, FAT, control)));
    }

    private static double density(FluidThermodynamics.State state) {
        return state.mass() / state.volume();
    }

    /** The registered machine stands at speed 0 (a casing with no train: NO DRIVER); its train's share 0 keeps it closed. */
    @Test
    void aMachineAtNoSpeedIsClosed() {
        var supply = waterSupply(101325);
        var registered = line(supply, waterSupply(101325 + 1e5), new FlowControl.Machine(PUMP, 0, true));
        var dispatched = ShaftPlan.apply(registered, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.SPEED, 0)));
        assertSame(registered, dispatched, "nothing to change");
        var result = new PassiveStepSolver(model).solve(dispatched, 1, NOOP);
        assertEquals(FlowControl.Mode.CLOSED, result.modes().getFirst());
        assertEquals(0, result.massFlows()[0], 1e-10);
    }

    /** At share 0.81 against the 0.81 speed line's own rise at 70 % of the rated flow, the pump delivers 70 %: the affinity laws. */
    @Test
    void aShareOfPointEightyOneRunsTheMachineOnItsSpeedLine() {
        var supply = waterSupply(101325);
        double rho = density(supply), rise = PUMP.rise(.007, .81, rho, supply.pressure(), 1.4);
        var registered = line(supply, waterSupply(101325 + rise), new FlowControl.Machine(PUMP, 0, true));
        var dispatched = ShaftPlan.apply(registered, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.SPEED, .81)));
        assertEquals(.81, ((FlowControl.Machine) dispatched.pipes().getFirst().control()).speed(), 0);
        var result = new PassiveStepSolver(model).solve(dispatched, 1, NOOP);
        assertEquals(FlowControl.Mode.MOVER_LAW, result.modes().getFirst());
        assertEquals(.007, result.massFlows()[0] / rho, 5e-5, "70 % of the rated flow, less the line's loss");
        assertTrue(ShaftPlan.planned(registered.pipes().getFirst().control(), dispatched.pipes().getFirst().control()));
    }

    /** A frame's displacement and a governor valve's target: applied, the valve's inside its deadband kept, the plans only. */
    @Test
    void aFrameAndAGovernorValveTakeTheirPlans() {
        var supply = nitrogenAt(150000);
        var frame = line(supply, nitrogenAt(180000), new FlowControl.Compressor(.05, 3, true));
        var moved = ShaftPlan.apply(frame, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.DISPLACEMENT, .04)));
        var c = (FlowControl.Compressor) moved.pipes().getFirst().control();
        assertEquals(.04, c.requestedVolumeFlow(), 0);
        assertEquals(3, c.maximumPressureRatio(), 0);
        assertTrue(ShaftPlan.planned(frame.pipes().getFirst().control(), c));
        var sensor = new UUID(3, 4);
        var valve = new FlowControl.ControlValve(FlowControl.ValveSource.SHAFT_SPEED, 200000, .1, .01, sensor, 7);
        var governed = line(supply, nitrogenAt(101325), valve);
        assertSame(governed, ShaftPlan.apply(governed, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.GOVERNOR, .10005))), "inside the 0.1 % deadband");
        var steered = (FlowControl.ControlValve) ShaftPlan.apply(governed, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.GOVERNOR, .1056))).pipes().getFirst().control();
        assertEquals(.1056, steered.massTarget(), 0);
        assertEquals(.1056, steered.target(), 0, "a governor valve's target is its mass target");
        assertEquals(7, steered.revision(), "a plan is no new setting");
        assertTrue(ShaftPlan.planned(valve, steered));
        // A plan of another kind, or a valve on another source, changes nothing; a changed law is no plan.
        var plain = line(supply, nitrogenAt(101325), valve.withSource(FlowControl.ValveSource.MASS_FLOW, sensor, 7));
        assertSame(plain, ShaftPlan.apply(plain, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.GOVERNOR, .2))));
        assertFalse(ShaftPlan.planned(new FlowControl.Machine(PUMP, 1, true), new FlowControl.Machine(new CentrifugalPump(.02, 50, .7), 1, true)));
        assertFalse(ShaftPlan.planned(new FlowControl.Machine(PUMP, 1, true), new FlowControl.Machine(PUMP, 1, false)));
    }
}
