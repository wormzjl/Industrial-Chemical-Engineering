package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A regulating valve cold-started under a drop leg (batch 2026-11-11-drum-operation-holds, defect 3). A 7.95 m3 drum
 * holding nothing, vented to a void, is fed 198.1 kg/s of water through a flow valve; its liquid outlet runs down a 0.25 m
 * line through a flow valve set to 592.8 kg/s to a 1 atm water void 7 m below. Everything is built at once. The leg's
 * foot junction (2 m down, 5 m above the void) is minted as the feed's water, and nothing flows through it at the start:
 * the drum's end gives nothing, the void gives nothing back. Expected: it stands on its column to the void, 101.3 kPa less
 * 5 m of water, about 52 kPa, with no flow, and the drum then fills and drains. Before the fix its balance put it on the
 * pressure hull's floor, 300 Pa, as vapour, and the island held in its first tick (Newton line search stalled at 5.587).
 */
class DropLegColdStartTest {
    static PhysicalFluidTopology.Position at(int x, int y, int z) {return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z);}

    @Test void aLegNothingFlowsThroughStandsOnItsColumn() {
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
        w.place(0, 4, 0, Kind.VOID, UP, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, EventPathWorld.nitrogen().composition()), .25);
        for (int y = -1; y >= -6; y--)
            if (y == -2) w.place(0, y, 0, Kind.PIPE, DOWN, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 592.8, new UUID(1, 2), 1), null, .25);
            else w.place(0, y, 0, Kind.PIPE, DOWN, .25);
        w.place(0, -7, 0, Kind.VOID, DOWN, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, water), .25);
        w.tick();
        assertTrue(w.held.isEmpty(), "the first tick holds nothing: " + w.held);
        var island = w.registry.owner(drum);
        var foot = w.coordinator.observe(island).graph().reservoirs().stream().filter(n -> n.junction() && n.elevation() < 0).findFirst().orElseThrow();
        assertTrue(foot.state().pressure() > 40_000 && foot.state().pressure() < 110_000, "the foot stands on its column, about 52 kPa: " + w.describe(island));
        w.runTo(w.ledger.onlineTick() + 1200);
        assertTrue(w.held.isEmpty(), "no hold in 60 s: " + w.held);
    }
}
