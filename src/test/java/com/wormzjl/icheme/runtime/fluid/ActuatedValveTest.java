package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.control.ControlFacts;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Device;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The automated shutoff valve (batch 2026-11-15-fluid-interfaces, W7, plan section 4.2 and decisions D3 and D4): open it is
 * the line's own law, the bare valve's flow bit for bit; closed it passes nothing and its island rests; its loop's on/off
 * rule switches outside a band of 2 % of the target and holds inside it; a hand is refused while an enabled loop holds it.
 */
class ActuatedValveTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final String DIM = "minecraft:overworld";

    /** Open: no regulation, the bare valve's law, bit for bit, and the passive pipe's flow within the solver's tolerance. */
    @Test
    void anOpenValveIsTheLinesOwnLaw() {
        var open = FlowControl.ControlValve.onOff();
        assertTrue(open.bare(), "an open valve regulates nothing");
        assertTrue(open.massControl() && !open.pressureControl() && !open.source().planned() && !open.source().sink());
        double q = flow(open), bare = flow(FlowControl.ControlValve.unconfigured()), passive = flow(new FlowControl.Passive());
        assertTrue(q > 0, "it flows from the high vessel: " + q);
        assertEquals(bare, q, 0, "the bare valve's flow bit for bit");
        assertEquals(passive, q, 1e-6 * passive, "the passive pipe's flow");
    }

    /** Closed: a mass target of 0 is the existing closed rule; nothing passes either way and the island is at rest. */
    @Test
    void aClosedValvePassesNothingAndItsIslandRests() {
        var closed = FlowControl.ControlValve.onOff().withMassTarget(0);
        assertFalse(closed.bare(), "a closed valve is no open line");
        assertEquals(0, flow(closed), 0, "no flow");
        // Both vessels above the ambient: a bag below it is slack, and its wall alone would keep the island moving.
        int water = waterIndex();
        assertTrue(NetworkReduction.atRest(line(160000, 120000, water, closed), model), "nothing in the island can drive a flow");
        assertFalse(NetworkReduction.atRest(line(160000, 120000, water, FlowControl.ControlValve.onOff()), model), "open, the drive moves it");
    }

    /**
     * A level loop with action OUTLET at 50 % (decision D4): open above 51 %, closed below 49 %, and no switch inside the
     * band whichever way the level crosses it; INLET is the reverse.
     */
    @Test
    void aLevelLoopSwitchesOutsideItsBandOnly() {
        var outlet = settings(ControlSettings.Action.OUTLET);
        double[] levels = {50, 50.9, 51.1, 50, 49.1, 48.9, 50, 50.99};
        boolean[] expected = {false, false, true, true, true, false, false, false};
        boolean open = false;
        for (int i = 0; i < levels.length; i++) {
            open = outlet.onOff(open, levels[i]);
            assertEquals(expected[i], open, "OUTLET at " + levels[i] + " %");
        }
        var inlet = settings(ControlSettings.Action.INLET);
        assertTrue(inlet.onOff(false, 48.9), "INLET opens below the band");
        assertTrue(inlet.onOff(true, 50.9), "and holds inside it");
        assertFalse(inlet.onOff(true, 51.1), "and closes above it");
        assertFalse(inlet.onOff(false, 49.1), "a closed valve stays closed inside the band");
    }

    /** By hand: an enabled loop that is not held holds the valve; the toggle flips its mass target as one queued edit. */
    @Test
    void aHandIsRefusedWhileAnEnabledLoopHoldsTheValve() {
        long valve = 3;
        var loop = new ControlFacts.Panel(new UUID(7, 1), DIM, BlockPos.ZERO, false, 0, settings(ControlSettings.Action.OUTLET));
        var off = new ControlFacts.Panel(new UUID(7, 2), DIM, BlockPos.ZERO, false, 0, new ControlSettings("LC-2", false, new UUID(9, 9), 3, Sensor.Unit.HEIGHT_PERCENT, 50, ControlSettings.Action.OUTLET));
        var console = new ControlFacts.Panel(new UUID(7, 3), DIM, BlockPos.ZERO, true, 0, ControlSettings.EMPTY);
        assertTrue(ControlFacts.holds(List.of(loop), Set.of(), valve), "an enabled loop holds its output");
        assertFalse(ControlFacts.holds(List.of(loop), Set.of(loop.id()), valve), "a held controller is off");
        assertFalse(ControlFacts.holds(List.of(off, console), Set.of(), valve), "a switched-off loop or a console holds nothing");
        assertFalse(ControlFacts.holds(List.of(loop), Set.of(), valve + 1), "another output is free");
        var world = line(Direction.EAST);
        assertFalse(world.registry.toggleActuatedValve(valve), "a plain pipe is no actuated valve");
        assertFalse(world.registry.installPipeActuatedValve(valve, Direction.UP), "across the run's joints");
        assertTrue(world.registry.installPipeActuatedValve(valve, Direction.EAST));
        world.registry.applyPending();
        assertEquals(FlowControl.ControlValve.onOff(), pipe(world).control(), "installed open");
        assertTrue(world.registry.toggleActuatedValve(valve));
        assertTrue(world.registry.toggleActuatedValve(valve), "two clicks compose on the queued state");
        world.registry.applyPending();
        var law = (FlowControl.ControlValve) pipe(world).control();
        assertEquals(1, law.massTarget(), "closed, then open again");
        assertEquals(2, law.revision());
        assertTrue(world.registry.removePipeAccessory(valve));
        world.registry.applyPending();
        assertTrue(pipe(world).control() instanceof FlowControl.Passive, "the wrench leaves a plain pipe");
    }

    private static ControlSettings settings(ControlSettings.Action action) {
        return new ControlSettings("LC-1", true, new UUID(9, 9), 3, Sensor.Unit.HEIGHT_PERCENT, 50, action);
    }

    private double flow(FlowControl control) {
        var result = new PassiveStepSolver(model).solve(line(160000, 100000, waterIndex(), control), .05, () -> {});
        return result.massFlows()[0];
    }

    private static Device pipe(EventPathWorld world) {
        return world.registry.registrations().get(3L).device();
    }

    private int waterIndex() {
        double[] water = FluidDeviceSpec.water(EventPathWorld.CATALOG).composition();
        for (int c = 0; c < water.length; c++) if (water[c] > 0) return c;
        throw new AssertionError("no water");
    }

    /** Two 100 m3 vessels of one compound at 298.15 K and {@code pa}, {@code pb}, joined by one 50 mm pipe with {@code control}. */
    private PassiveNetwork line(double pa, double pb, int component, FlowControl control) {
        var nodes = new ArrayList<PassiveNetwork.Reservoir>();
        for (double p : new double[]{pa, pb}) {
            double[] n = new double[model.componentCount()];
            n[component] = 1;
            var unit = model.flashTP(298.15, p, n, () -> {});
            n[component] *= 100 / unit.volume();
            nodes.add(new PassiveNetwork.Reservoir(nodes.size(), 0, model.flashTP(298.15, p, n, () -> {})));
        }
        var geometry = new PipeResistance.Geometry(10, .05, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
        return new PassiveNetwork(nodes, List.of(new PassiveNetwork.Pipe(5, 0, 1, geometry, control)));
    }

    /** Two 1 m3 nitrogen reservoirs joined by three pipes along {@code axis}; the valve goes on device 3. */
    private EventPathWorld line(Direction axis) {
        var records = new LinkedHashMap<Long, WorldTopologyLedger.Registration>();
        var stock = new HashMap<Long, PassiveNetwork.Reservoir>();
        for (int i = 0; i < 5; i++) {
            long id = i + 1;
            var kind = i == 0 || i == 4 ? Kind.RESERVOIR : Kind.PIPE;
            var p = new Position(DIM, axis.x * i, 64 + axis.y * i, axis.z * i);
            var d = Device.builder(id, p, kind, axis, new PipeResistance.Geometry(1, .05, .000045, 0), new FlowControl.Passive()).build();
            var spec = FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG);
            records.put(id, new WorldTopologyLedger.Registration(d, spec, 0));
            if (kind == Kind.RESERVOIR) stock.put(id, new PassiveNetwork.Reservoir(id, p.y(), model.initialNitrogenCharge(spec.volume(), 298.15, i == 0 ? 200000 : 100000, () -> {})));
        }
        var compiled = PhysicalFluidTopology.compile(records.values().stream().map(WorldTopologyLedger.Registration::device).toList(), stock);
        var island = new IslandCoordinator.Snapshot(10, 0, compiled.islands().getFirst().graph(), new IslandClock.Snapshot(0, 0, 100, 100), Optional.empty(), "READY");
        var empty = WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG);
        var total = WorldTopologyLedger.MaterialTotal.empty(model.components().size()).plus(stock.values(), model.components(), model.molecularWeights());
        return new EventPathWorld(0, new WorldTopologyLedger.Snapshot(0, 11, records, List.of(), total, empty.destroyed(), empty.basis()), EventPathWorld.whole(List.of(island)));
    }
}
