package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * Check 2 of the feed-step hold (FEED_STEP_HOLD_REVIEW.md, parent's hypothesis H1): factor 1, no server, no control. A
 * generator feeds 198.1 kg/s of water through a flow valve into a 7.95 m3 drum; the drum's liquid outlet is open (no valve)
 * through a 0.25 m line to a void 7 cells down; its vent goes to a void. Case "empty": everything placed at once (the drum
 * holds its 1 atm nitrogen charge). Case "1 %": the outlet's void is placed once the drum holds 1 % of its capacity in
 * liquid. Each runs 60 s of process time and prints held or clean with the first hold. Measures; asserts nothing.
 * Run: ./gradlew --offline --no-configuration-cache --init-script tools/minecraft-time-delta/worlds/worlds.init.gradle test --tests '*DrumDrainTest'
 */
class DrumDrainTest {
    static final double BORE = .25, FEED = 198.1, VOLUME = 7.952, HEIGHT = 1.5;
    static PhysicalFluidTopology.Position at(int x, int y, int z) {return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z);}

    @Test void anOpenOutletDrainsTheDrum() {
        run("empty", 0, 0);
        run("1 %", .01, 0);
        // The probed hold's outlet: a flow valve regulating 592.8 kg/s (three times the feed) on the drained drum.
        run("empty, outlet valve at 592.8 kg/s", 0, 592.8);
    }

    static void run(String label, double startShare, double outletTarget) {
        var w = new EventPathWorld();
        w.runTo(100);
        long drum = w.placeVessel(at(0, 0, 0), List.of(
                new PhysicalFluidTopology.Port(at(0, 2, 0), UP, PassiveNetwork.PhasePort.VAPOR),
                new PhysicalFluidTopology.Port(at(0, 0, 0), DOWN, PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(at(0, 1, 0), EAST, PassiveNetwork.PhasePort.BULK)), VOLUME, HEIGHT);
        var water = FluidDeviceSpec.water(w.catalog).composition();
        // The feed: generator at 3 bar, then a flow valve on its own law (as the level loop's feed valve), into the side nozzle.
        w.place(1, 1, 0, Kind.PIPE, EAST, BORE);
        w.place(2, 1, 0, Kind.PIPE, WEST, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, FEED, new UUID(1, 1), 1), null, BORE);
        w.place(3, 1, 0, Kind.PIPE, EAST, BORE);
        w.place(4, 1, 0, Kind.GENERATOR, EAST, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 300_000, water), BORE);
        // The vent to a 1 atm nitrogen void.
        w.place(0, 3, 0, Kind.PIPE, UP, BORE);
        w.place(0, 4, 0, Kind.VOID, UP, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, EventPathWorld.nitrogen().composition()), BORE);
        // The liquid outlet: 6 cells of 0.25 m line down to a 1 atm water void.
        for (int y = -1; y >= -6; y--)
            if (y == -2 && outletTarget > 0) w.place(0, y, 0, Kind.PIPE, DOWN, new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, outletTarget, new UUID(1, 2), 1), null, BORE);
            else w.place(0, y, 0, Kind.PIPE, DOWN, BORE);
        boolean[] outlet = {false};
        Runnable sink = () -> {w.place(0, -7, 0, Kind.VOID, DOWN, new FlowControl.Passive(), new FluidDeviceSpec(1, 300, 101_325, water), BORE);outlet[0] = true;};
        if (startShare <= 0) sink.run();
        long start = w.ledger.onlineTick(), placedAt = startShare <= 0 ? start : -1;
        double low = Double.POSITIVE_INFINITY, high = 0;
        // 60 s of process time (1200 ticks at factor 1) from the outlet's placement; at most 300 s to fill to the start share.
        while (w.held.isEmpty() && (placedAt < 0 ? w.ledger.onlineTick() < start + 6000 : w.ledger.onlineTick() < placedAt + 1200)) {
            w.tick();
            double share = share(w, drum);
            if (!outlet[0] && share >= startShare) {sink.run();placedAt = w.ledger.onlineTick();}
            if (outlet[0] && !Double.isNaN(share)) {low = Math.min(low, share);high = Math.max(high, share);}
            if (w.ledger.onlineTick() % 100 == 0) System.out.println("DRUM_DRAIN " + label + " tick=" + w.ledger.onlineTick() + " share=" + share + " " + status(w, drum));
        }
        for (var s : w.coordinator.snapshots()) System.out.println("DRUM_DRAIN_ISLAND " + label + " " + w.describe(s.id()));
        String held = w.held.isEmpty() ? "clean" : w.held.getFirst().status();
        System.out.println("DRUM_DRAIN_RESULT case=\"" + label + "\" factor=1 outletPlacedAtTick=" + placedAt + " ticks=" + (w.ledger.onlineTick() - start)
                + " share=" + low + " to " + high + " held=" + held.lines().findFirst().orElse(held));
    }

    /** The drum's liquid (water and hydrocarbon) over its capacity, at its island's committed state. */
    static double share(EventPathWorld w, long drum) {
        var island = w.registry.owner(drum);
        if (island == null) return Double.NaN;
        for (var n : w.coordinator.observe(island).graph().reservoirs())
            if (n.id() == drum) return (n.state().liquidVolume() + n.state().waterVolume()) / n.inventory().volume();
        return Double.NaN;
    }
    static String status(EventPathWorld w, long drum) {
        var island = w.registry.owner(drum);
        return island == null ? "no island" : w.coordinator.observe(island).status();
    }
}
