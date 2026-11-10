package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A chimney in the engine (batch 2026-11-10-chimney, CHIMNEY_PLAN.md): a gas generator at grade (y 0) stands at the
 * atmosphere there and feeds two pipes into a steel stack whose tip is a void 30 m up, at the atmosphere of its height (owner
 * C1 B), as {@code FluidWorldAuthority.placeChimney} places it. A hot flue gas is lighter than the air: its column draws and
 * it vents. A cold gas denser than the air cannot lift its own column: nothing flows, and the void, one way, takes nothing
 * back (NO DRAFT, owner C6 A).
 */
class ChimneyRuntimeTest {
    private final MaterialCatalog catalog = MaterialCatalog.bundled();
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry LINE = new PipeResistance.Geometry(1, .5, .000045, 0);
    private static final long GAS = 1, TIP = 9;
    private static final int TOP = 30;
    private static final double D = 1;

    private static PhysicalFluidTopology.Position at(int x, int y) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, 0);
    }

    private static PhysicalFluidTopology.Device plain(long id, int x, Kind kind) {
        return new PhysicalFluidTopology.Device(id, at(x, 0), kind, EAST, LINE, new FlowControl.Passive());
    }

    /** The steel stack's tip at (3, 30, 0), its inlet at (3, 0, 0) from the west. */
    private static PhysicalFluidTopology.Device chimney() {
        return new PhysicalFluidTopology.Device(TIP, at(3, TOP), Kind.VOID, UP, new PipeResistance.Geometry(TOP + .5, D, PipeResistance.DEFAULT_ROUGHNESS_METRES, Chimney.STACK_LOSS),
                new FlowControl.Passive(), List.of(new PhysicalFluidTopology.Port(at(3, 0), WEST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL)), true,
                PhysicalFluidTopology.Cutoff.NONE, false, PhysicalFluidTopology.ALL_FACES, 0, 0, null);
    }

    private FluidThermodynamics.State gas(String name, double temperature, double pressure) {
        double[] n = new double[model.componentCount()];
        int c = model.components().indexOf(name);
        assertTrue(c >= 0, name);
        n[c] = 1;
        double volume = model.flashTP(temperature, pressure, n, () -> {
        }).volume();
        n[c] /= volume;
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    private Chimneys.View run(FluidThermodynamics.State supply) {
        var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
        var device = chimney();
        assertTrue(device.chimney() && device.atmospheric());
        active.put(TIP, new WorldTopologyLedger.Registration(device, new FluidDeviceSpec(1, 298.15, PhaseLayout.atmosphere(TOP), FluidDeviceSpec.nitrogen(catalog).composition()), 0));
        var empty = WorldTopologyLedger.Snapshot.empty(catalog);
        var ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
        var chimneys = new Chimneys(ledger, model.molecularWeights());
        var compiled = PhysicalFluidTopology.compile(List.of(plain(GAS, 0, Kind.GENERATOR), plain(110, 1, Kind.PIPE), plain(111, 2, Kind.PIPE), device),
                Map.of(GAS, new PassiveNetwork.Reservoir(GAS, 0, supply, PassiveNetwork.NodeKind.GENERATOR),
                        TIP, new PassiveNetwork.Reservoir(TIP, TOP, gas("Nitrogen", 298.15, PhaseLayout.atmosphere(TOP)), PassiveNetwork.NodeKind.VOID)));
        assertEquals(1, compiled.islands().size());
        var graph = compiled.islands().getFirst().graph();
        var solver = new PassiveIntervalSolver(model);
        long tick = 0;
        for (int i = 0; i < 4; i++) {
            var result = solver.solve(graph, 20, PassiveIntervalSolver.Settings.defaults(), () -> {
            });
            chimneys.booked(tick, tick + 400, result);
            graph = result.graph();
            tick += 400;
        }
        var v = chimneys.view(TIP);
        System.out.printf(Locale.ROOT, "%s: %s, %.3f kg/s, %.2f m/s, draft %.0f Pa%n", supply.temperature(), v.status(), v.massFlow(), v.exitVelocity(), v.draft());
        return v;
    }

    @Test
    void aHotFlueGasDrawsUpTheStack() {
        // At grade the air stands 30 blocks of air column over the tip's: 30 * 11.6 = 349 Pa; a 473 K gas weighs 220 Pa of it.
        assertEquals(30 * 1.185 * 9.80665, PhaseLayout.atmosphere(0) - PhaseLayout.atmosphere(TOP), 1e-6);
        var v = run(gas("Nitrogen", 473.15, PhaseLayout.atmosphere(0)));
        assertEquals(Chimney.Status.VENTING, v.status());
        assertTrue(v.massFlow() > .05, "the draft moves it: " + v.massFlow());
        assertTrue(v.draft() > 100, "a hot column draws: " + v.draft());
        assertEquals(0, v.solids());
        assertEquals(0, v.opacity());
        assertEquals(List.of(), v.alarms());
        assertTrue(v.line().startsWith("VENTING"));
    }

    @Test
    void aColdDenseGasHasNoDraft() {
        var v = run(gas("CarbonDioxide", 298.15, PhaseLayout.atmosphere(0)));
        assertEquals(Chimney.Status.NO_DRAFT, v.status());
        assertEquals(0, v.massFlow(), 1e-6, "the void is one way: nothing flows");
        assertTrue(v.draft() < 0, "its column is heavier than the air's");
        assertEquals(List.of(EquipmentAlarm.NO_DRAFT), v.alarms());
    }
}
