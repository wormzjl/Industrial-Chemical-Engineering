package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.machine.VacuumPump;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A vacuum pump on its row in the fluid network (batch 2026-11-10-vacuum-pump, VACUUM_PUMP_PLAN.md section 2.3): the
 * {@code MOVER_LAW} row is a displacement row, so the machine takes its capacity at the suction, {@code s S_n φ(P_s)}, less the
 * row's slip (0.4 % per relief rise: 0.3 % here), whatever the discharge short of its relief; a liquid ring books no heat into the gas.
 */
class VacuumPumpMoverTest extends PortFixture {
    private static final PipeResistance.Geometry LINE = new PipeResistance.Geometry(2, .15, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);

    private PassiveStepSolver.Result run(VacuumPump law, double suction, double speed) {
        var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, nitrogenAt(suction), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, nitrogenAt(101325), PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10, 0, 1, LINE, new FlowControl.Machine(law, speed, true))));
        return new PassiveStepSolver(model).solve(graph, .5, NOOP);
    }

    /** An LR1-500 on nitrogen at 20 kPa to the atmosphere: φ (1 − 3.17/20)² = 0.708 of 500 m³/h; at half speed half that. */
    @Test void aLiquidRingTakesItsCapacityAtTheSuction() {
        var law = new VacuumPump(VacuumPump.Type.LIQUID_RING_ONE_STAGE, 500 / 3600., 1450);
        for (double speed : new double[]{1, .5}) {
            var result = run(law, 20000, speed);
            var suction = result.states().getFirst();
            double rho = suction.mass() / suction.volume(), q = result.massFlows()[0] / rho;
            System.out.printf("VACUUM_RUN ring speed=%.2f flow=%.1f m3/h head=%.0f Pa work=%.1f J%n", speed, 3600 * q, result.devicePressureChanges()[0], result.pumpWorkJoule());
            assertEquals(FlowControl.Mode.MOVER_LAW, result.modes().getFirst());
            double head = result.devicePressureChanges()[0];
            assertEquals(law.row(speed, suction.pressure()).flow(head, 1), q, 1e-6 * q, "the row takes the capacity at the suction less its slip");
            assertEquals(speed * 500 / 3600. * Math.pow(1 - VacuumPump.SEAL_VAPOUR_PRESSURE / suction.pressure(), 2), q, 5e-3 * q);
            assertEquals(0, result.pumpWorkJoule(), 1e-9, "the ring takes the compression heat: none into the gas");
        }
    }

    /** A DS-300 at 1 kPa: flat, 0.999 of 300 m³/h, and its booked heat warms the gas. */
    @Test void aDryScrewIsFlatAndBooksItsHeat() {
        var law = new VacuumPump(VacuumPump.Type.DRY_SCREW, 300 / 3600., 2950);
        var result = run(law, 1000, 1);
        var suction = result.states().getFirst();
        double rho = suction.mass() / suction.volume(), q = result.massFlows()[0] / rho;
        assertEquals(FlowControl.Mode.MOVER_LAW, result.modes().getFirst());
        assertEquals(law.row(1, suction.pressure()).flow(result.devicePressureChanges()[0], 1), q, 1e-6 * q);
        assertEquals(.999 * 300 / 3600., q, 5e-3 * q);
        assertTrue(result.pumpWorkJoule() > 0, "the screw's heat goes into the gas");
    }
}
