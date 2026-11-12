package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * Headless rig (batch 2026-11-11-drum-operation-holds, defect 1), the level loop's setup sequence: a 7.95 m3 drum holding
 * nothing, vented to a 1 atm nitrogen generator (or void), fed by a water generator charged at 42.4 kPa (no flow) through a
 * flow valve at 19.81 kg/s; its liquid outlet 2 m down to a flow valve, then 3 m to a 1 atm water void. After -Ddrain.settle ticks (1, as the level loop's ticks 83 and 84) the
 * feed generator is charged to 3 bar, as the level loop's "feed law and 3 bar charge". Prints the foot and the drum before
 * the charge, then GAS_VENT_SEQ lines and the result. Measures; asserts nothing. -Ddrain.case=<label prefix> runs one case.
 */
class GasVentSequenceTest {
    static PhysicalFluidTopology.Position at(int x, int y, int z) {return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z);}
    @Test void sequence() {
        String only = System.getProperty("drain.case", "");
        for (var c : new String[][]{{"generator vent, outlet 0.01 m3/s", "gen", "v"}, {"generator vent, outlet 10 kg/s", "gen", "m"}, {"void vent, outlet 0.01 m3/s", "void", "v"}})
            if (only.isEmpty() || c[0].startsWith(only)) run(c[0], c[1].equals("gen"), c[2].equals("v"));
    }
    static void run(String label, boolean generator, boolean volume) {
        var w = new EventPathWorld();
        w.runTo(100);
        long drum = w.placeVessel(at(0, 0, 0), List.of(
                new PhysicalFluidTopology.Port(at(0, 2, 0), UP, PassiveNetwork.PhasePort.VAPOR),
                new PhysicalFluidTopology.Port(at(0, 0, 0), DOWN, PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(at(0, 1, 0), EAST, PassiveNetwork.PhasePort.BULK)), 7.952, 1.5);
        var water = FluidDeviceSpec.water(w.catalog).composition();
        w.place(1, 1, 0, Kind.PIPE, EAST, .25);
        w.place(2, 1, 0, Kind.PIPE, WEST, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 19.81, new UUID(1, 1), 1), null, .25);
        w.place(3, 1, 0, Kind.PIPE, EAST, .25);
        long feed = w.place(4, 1, 0, Kind.GENERATOR, EAST, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325 - 6 * 9.81 * 998, water), .25);
        w.place(0, 3, 0, Kind.PIPE, UP, .25);
        w.place(0, 4, 0, generator ? Kind.GENERATOR : Kind.VOID, UP, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, EventPathWorld.nitrogen().composition()), .25);
        var valve = volume ? new FlowControl.ControlValve(FlowControl.ValveSource.VOLUME_FLOW, .01, new UUID(1, 2), 1) : new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 10, new UUID(1, 2), 1);
        for (int y = -1; y >= -4; y--)
            if (y == -2) w.place(0, y, 0, Kind.PIPE, DOWN, valve, null, .25);
            else w.place(0, y, 0, Kind.PIPE, DOWN, .25);
        w.place(0, -5, 0, Kind.VOID, DOWN, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, water), .25);
        w.runTo(100 + Long.getLong("drain.settle", 1));
        System.out.println("GAS_VENT_SEQ_BEFORE " + label + " held=" + w.held + " " + w.describe(w.registry.owner(drum)));
        var old = w.ledger.snapshot().active().get(feed);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(feed, new WorldTopologyLedger.Registration(old.device(), new FluidDeviceSpec(1, 300, 300_000, water), old.revision() + 1))), w.ledger.nextIdentity(), null);
        long start = w.ledger.onlineTick();
        while (w.held.isEmpty() && w.ledger.onlineTick() < start + 1200) {
            w.tick();
            if (w.ledger.onlineTick() % 100 == 0 || w.ledger.onlineTick() < start + 4) System.out.println("GAS_VENT_SEQ " + label + " tick=" + w.ledger.onlineTick() + " share=" + GasVentDrainTest.DrumShare.share(w, drum) + " " + w.describe(w.registry.owner(drum)).lines().findFirst().orElse(""));
        }
        System.out.println("GAS_VENT_SEQ_ISLAND " + label + " " + w.describe(w.registry.owner(drum)));
        System.out.println("GAS_VENT_SEQ_RESULT case=\"" + label + "\" ticks=" + (w.ledger.onlineTick() - start) + " held=" + (w.held.isEmpty() ? "clean" : w.held.getFirst().status().lines().findFirst().orElse("")));
    }
}
