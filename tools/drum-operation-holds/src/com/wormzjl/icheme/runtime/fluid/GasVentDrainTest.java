package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/** Headless rig (batch 2026-11-11-drum-operation-holds, defect 1): DrumDrainTest's drum with its vent on a 1 atm nitrogen generator. Measures. */
class GasVentDrainTest {
    static PhysicalFluidTopology.Position at(int x, int y, int z) {return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z);}
    @Test void gasVent() {
        String only = System.getProperty("drain.case", "");
        for (var c : new String[][]{{"void vent, valve 10 kg/s", "void", "10"}, {"generator vent, valve 10 kg/s", "gen", "10"}, {"generator vent, open leg", "gen", "0"}})
            if (only.isEmpty() || c[0].startsWith(only)) run(c[0], c[1].equals("gen"), Double.parseDouble(c[2]));
    }
    static void run(String label, boolean generator, double outlet) {
        var w = new EventPathWorld();
        w.runTo(100);
        long drum = w.placeVessel(at(0, 0, 0), List.of(
                new PhysicalFluidTopology.Port(at(0, 2, 0), UP, PassiveNetwork.PhasePort.VAPOR),
                new PhysicalFluidTopology.Port(at(0, 0, 0), DOWN, PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(at(0, 1, 0), EAST, PassiveNetwork.PhasePort.BULK)), 7.952, 1.5);
        var water = FluidDeviceSpec.water(w.catalog).composition();
        w.place(1, 1, 0, Kind.PIPE, EAST, .25);
        w.place(2, 1, 0, Kind.PIPE, WEST, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 198.1, new UUID(1, 1), 1), null, .25);
        w.place(3, 1, 0, Kind.PIPE, EAST, .25);
        w.place(4, 1, 0, Kind.GENERATOR, EAST, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 300_000, water), .25);
        w.place(0, 3, 0, Kind.PIPE, UP, .25);
        w.place(0, 4, 0, generator ? Kind.GENERATOR : Kind.VOID, UP, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, EventPathWorld.nitrogen().composition()), .25);
        for (int y = -1; y >= -6; y--)
            if (y == -2 && outlet > 0) w.place(0, y, 0, Kind.PIPE, DOWN, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, outlet, new UUID(1, 2), 1), null, .25);
            else w.place(0, y, 0, Kind.PIPE, DOWN, .25);
        w.place(0, -7, 0, Kind.VOID, DOWN, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, water), .25);
        long start = w.ledger.onlineTick();
        while (w.held.isEmpty() && w.ledger.onlineTick() < start + 1200) {
            w.tick();
            if (w.ledger.onlineTick() % 100 == 0 || w.ledger.onlineTick() < start + 4) System.out.println("GAS_VENT " + label + " tick=" + w.ledger.onlineTick() + " share=" + DrumShare.share(w, drum) + " " + w.describe(w.registry.owner(drum)).lines().findFirst().orElse(""));
        }
        System.out.println("GAS_VENT_ISLAND " + label + " " + w.describe(w.registry.owner(drum)));
        System.out.println("GAS_VENT_RESULT case=\"" + label + "\" ticks=" + (w.ledger.onlineTick() - start) + " held=" + (w.held.isEmpty() ? "clean" : w.held.getFirst().status().lines().findFirst().orElse("")));
    }
    static final class DrumShare {
        static double share(EventPathWorld w, long drum) {
            var island = w.registry.owner(drum);if (island == null) return Double.NaN;
            for (var n : w.coordinator.observe(island).graph().reservoirs()) if (n.id() == drum) return (n.state().liquidVolume() + n.state().waterVolume()) / n.inventory().volume();
            return Double.NaN;
        }
    }
}
