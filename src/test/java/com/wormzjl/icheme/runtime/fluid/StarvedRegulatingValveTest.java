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
 * A flow valve that regulates more than reaches it from a draining drum (batch 2026-11-11-minecraft-time-delta,
 * FEED_STEP_HOLD_REVIEW.md, owner option 1). A 7.95 m3 drum is fed 198.1 kg/s of water through a flow valve; its liquid
 * outlet runs down a 0.25 m line to a flow valve beside a void. The valve stays closed while the drum fills to 5 %, then
 * is set to 592.8 kg/s, three times the feed, as the level loop's LIC-1 set it. The drum runs dry and the valve passes
 * what reaches it, the feed. Before the fix the island held as the drum ran dry: the valve's regulation row had no root at
 * any step size (line search stalled at the valve's flow, 20 rejections).
 */
class StarvedRegulatingValveTest {
    static final double BORE = .25, FEED = 198.1, OUTLET = 592.8;
    static PhysicalFluidTopology.Position at(int x, int y, int z) {return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z);}
    static FlowControl.ControlValve outlet(double target) {return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, target, new UUID(1, 2), 1);}
    /** The drum's liquid over its capacity at its island's committed state. */
    static double share(EventPathWorld w, long drum) {
        var island = w.registry.owner(drum);
        if (island == null) return Double.NaN;
        for (var n : w.coordinator.observe(island).graph().reservoirs())
            if (n.id() == drum) return (n.state().liquidVolume() + n.state().waterVolume()) / n.inventory().volume();
        return Double.NaN;
    }

    @Test void aValveRegulatingMoreThanReachesItPassesTheFeed() {
        var w = new EventPathWorld();
        w.runTo(100);
        long drum = w.placeVessel(at(0, 0, 0), List.of(
                new PhysicalFluidTopology.Port(at(0, 2, 0), UP, PassiveNetwork.PhasePort.VAPOR),
                new PhysicalFluidTopology.Port(at(0, 0, 0), DOWN, PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(at(0, 1, 0), EAST, PassiveNetwork.PhasePort.BULK)), 7.952, 1.5);
        var water = FluidDeviceSpec.water(w.catalog).composition();
        w.place(1, 1, 0, Kind.PIPE, EAST, BORE);
        w.place(2, 1, 0, Kind.PIPE, WEST, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, FEED, new UUID(1, 1), 1), null, BORE);
        w.place(3, 1, 0, Kind.PIPE, EAST, BORE);
        w.place(4, 1, 0, Kind.GENERATOR, EAST, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 300_000, water), BORE);
        w.place(0, 3, 0, Kind.PIPE, UP, BORE);
        w.place(0, 4, 0, Kind.VOID, UP, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, EventPathWorld.nitrogen().composition()), BORE);
        long valve = -1;
        for (int y = -1; y >= -6; y--)
            if (y == -6) valve = w.place(0, y, 0, Kind.PIPE, DOWN, outlet(0), null, BORE);
            else w.place(0, y, 0, Kind.PIPE, DOWN, BORE);
        w.place(0, -7, 0, Kind.VOID, DOWN, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, water), BORE);
        while (w.held.isEmpty() && !(share(w, drum) >= .05) && w.ledger.onlineTick() < 2000) w.tick();
        assertTrue(w.held.isEmpty() && share(w, drum) >= .05, "the drum fills: " + w.held + " " + w.describe(w.registry.owner(drum)));
        var registration = w.ledger.snapshot().active().get(valve);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(valve, new WorldTopologyLedger.Registration(registration.device().withControl(outlet(OUTLET)), registration.spec(), registration.revision() + 1))), w.ledger.nextIdentity(), null);
        w.registry.applyPending();
        // Twelve slices after the first two past the change. The drum drains within a slice at the target, the valve goes open on its dry supply and
        // the drum refills, so the outlet averages the feed slice by slice, the drum standing at a few percent of its capacity.
        double outflow = 0;
        for (int k = -2; k < 12; k++) {
            w.runTo(w.ledger.onlineTick() + 100);
            assertTrue(w.held.isEmpty(), "no hold: " + w.held);
            var island = w.coordinator.observe(w.registry.owner(drum));var graph = island.graph();var result = island.lastResult().orElseThrow();
            assertTrue(share(w, drum) < .05, "the drum stays drained: " + share(w, drum));
            for (int e = 0; e < graph.pipes().size(); e++)
                if (k >= 0 && graph.pipes().get(e).control() instanceof FlowControl.ControlValve c && c.massTarget() == OUTLET) {
                    double q = result.averageMassFlows()[e];
                    assertTrue(q > .8 * FEED && q < 1.2 * FEED, "the outlet passes about the feed: " + q + " " + w.describe(island.id()));
                    outflow += q / 12;
                }
        }
        assertEquals(FEED, outflow, .02 * FEED, "over twelve slices the outlet passes the feed");
        w.assertKeptUp("a valve regulating more than reaches it");
    }
}
