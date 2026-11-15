package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Device;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The restriction orifice plate (batch 2026-11-15-fluid-interfaces, W6, plan section 4.1): its loss coefficient
 * K(beta) = (1 - beta^4)(1 - beta^1.9) / (C^2 beta^4) at the plan's ratios, the loss it adds to its pipe's law and in a
 * solve, and its registry edits: install, a resize that keeps it, and a removal that gives the pipe its own loss back.
 */
class OrificeTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final String DIM = "minecraft:overworld";

    /** The plan's table: K to 0.5 %. */
    @Test
    void theLossCoefficientIsThePlansAtEachRatio() {
        double[][] table = {{.20, 1598}, {.30, 296}, {.50, 29.5}, {.70, 4.19}, {.80, 1.34}};
        for (var row : table) assertEquals(row[1], Orifice.loss(row[0]), .005 * row[1], "beta " + row[0]);
        for (double beta = Orifice.MINIMUM; beta <= Orifice.MAXIMUM + 1e-9; beta = Orifice.next(beta)) {
            assertTrue(Orifice.valid(beta), "on the grid: " + beta);
            if (beta > Orifice.MINIMUM) assertTrue(Orifice.loss(beta) < Orifice.loss(beta - Orifice.STEP), "a wider bore loses less: " + beta);
            if (beta >= Orifice.MAXIMUM - 1e-9) break;
        }
        assertEquals(Orifice.MINIMUM, Orifice.next(Orifice.MAXIMUM), "the step wraps to the smallest");
        assertEquals(.55, Orifice.next(.5));
        for (double invalid : new double[]{.15, .85, .52, Double.NaN})
            assertThrows(IllegalArgumentException.class, () -> Orifice.loss(invalid), "refused: " + invalid);
    }

    /**
     * The pipe's law with the plate loses K rho v^2/2 more than without, at the plan's states: water at 2 m/s in a 50 mm
     * pipe (head 2.0 kPa: beta 0.5 59 kPa, 0.7 8.4 kPa, 0.3 590 kPa) and air at 5 bar, 300 K and 20 m/s (5.81 kg/m3, head 1.16 kPa: beta 0.5
     * 34 kPa).
     */
    @Test
    void thePlateAddsItsHeadToThePipeLaw() {
        double d = .05, area = Math.PI * d * d / 4;
        var bare = new PipeResistance.Geometry(10, d, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
        record Case(double density, double viscosity, double velocity, double beta, double plan) {}
        for (var c : List.of(new Case(997, 8.9e-4, 2, .5, 59e3), new Case(997, 8.9e-4, 2, .7, 8.4e3), new Case(997, 8.9e-4, 2, .3, 590e3),
                new Case(5.81, 1.8e-5, 20, .5, 34e3))) {
            double k = Orifice.loss(c.beta()), q = c.density() * c.velocity() * area, head = .5 * c.density() * c.velocity() * c.velocity();
            var plated = new PipeResistance.Geometry(10, d, PipeResistance.DEFAULT_ROUGHNESS_METRES, k);
            double added = PipeResistance.pressureDrop(plated, q, c.density(), c.viscosity()) - PipeResistance.pressureDrop(bare, q, c.density(), c.viscosity());
            assertEquals(k * head, added, 1e-9 * k * head, c.toString());
            assertEquals(c.plan(), added, .02 * c.plan(), "the plan's number: " + c);
        }
    }

    /**
     * In a solve, a line between two large vessels, the plate's pipe row holds its pressure difference as the friction
     * of the pipe plus K rho v^2/2 at the flow the solve accepted (the friction from water's viscosity; with the plate it
     * is under 2 % of the loss, so the viscosity's uncertainty stays far inside the bound).
     */
    @Test
    void aSolveLosesThePlatesHeadAtItsFlow() {
        int water = waterIndex();
        double k = Orifice.loss(.5);
        var geometry = new PipeResistance.Geometry(1, .05, PipeResistance.DEFAULT_ROUGHNESS_METRES, k);
        var result = new PassiveStepSolver(model).solve(line(160000, 100000, water, geometry), .05, () -> {});
        double q = result.massFlows()[0];
        assertTrue(q > 0, "it flows from the high vessel: " + q);
        var up = result.states().get(0);
        var down = result.states().get(1);
        double rho = up.mass() / up.volume(), drive = up.pressure() - down.pressure();
        double area = Math.PI * .05 * .05 / 4, v = q / rho / area, head = .5 * rho * v * v;
        double friction = PipeResistance.pressureDrop(new PipeResistance.Geometry(1, .05, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0), q, rho, 8.9e-4);
        assertEquals(drive, friction + k * head, .005 * drive, "the row: " + drive + " Pa against " + friction + " + " + k * head);
        assertTrue(k * head > .95 * drive, "the plate takes nearly all of the drive");
        var plain = new PassiveStepSolver(model).solve(line(160000, 100000, water, new PipeResistance.Geometry(1, .05, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0)), .05, () -> {});
        assertTrue(plain.massFlows()[0] > 3 * q, "without the plate the same drive moves far more: " + plain.massFlows()[0] + " kg/s against " + q);
    }

    /** Install, resize (K kept), remove (the pipe's own minor loss back) on the registry; the wrong axis is refused. */
    @Test
    void theRegistryAddsKeepsAndRemovesThePlatesLoss() {
        var world = line(Direction.EAST);
        double k = Orifice.loss(.35);
        assertFalse(world.registry.installPipeOrifice(3, Direction.UP, .35), "across the run's joints");
        assertTrue(world.registry.installPipeOrifice(3, Direction.EAST, .35));
        world.registry.applyPending();
        var g = pipe(world).geometry();
        assertEquals(k, g.minorLoss(), "the plate's K on the pipe");
        assertEquals(.05, g.diameter());
        assertTrue(pipe(world).control() instanceof FlowControl.Passive, "the pipe stays a passive pipe");
        assertTrue(world.registry.resizeLine(3, .1, 64, d -> 1) > 0);
        world.registry.applyPending();
        assertEquals(.1, pipe(world).geometry().diameter());
        assertEquals(k, pipe(world).geometry().minorLoss(), "a resize keeps K: it does not depend on the bore");
        assertTrue(world.registry.removePipeAccessory(3, k));
        world.registry.applyPending();
        assertEquals(0, pipe(world).geometry().minorLoss(), "the pipe's own minor loss back");
        assertEquals(.1, pipe(world).geometry().diameter());
        assertEquals(1, world.registry.registrations().get(2L).device().geometry().length(), "its neighbours untouched");
    }

    private static Device pipe(EventPathWorld world) {
        return world.registry.registrations().get(3L).device();
    }

    private int waterIndex() {
        double[] water = FluidDeviceSpec.water(EventPathWorld.CATALOG).composition();
        for (int c = 0; c < water.length; c++) if (water[c] > 0) return c;
        throw new AssertionError("no water");
    }

    /** Two 100 m3 vessels of one compound at 298.15 K and {@code pa}, {@code pb}, joined by one pipe of {@code geometry}. */
    private PassiveNetwork line(double pa, double pb, int component, PipeResistance.Geometry geometry) {
        var nodes = new ArrayList<PassiveNetwork.Reservoir>();
        for (double p : new double[]{pa, pb}) {
            double[] n = new double[model.componentCount()];
            n[component] = 1;
            var unit = model.flashTP(298.15, p, n, () -> {});
            n[component] *= 100 / unit.volume();
            nodes.add(new PassiveNetwork.Reservoir(nodes.size(), 0, model.flashTP(298.15, p, n, () -> {})));
        }
        return new PassiveNetwork(nodes, List.of(new PassiveNetwork.Pipe(5, 0, 1, geometry, new FlowControl.Passive())));
    }

    /** Two 1 m3 nitrogen reservoirs joined by three pipes along {@code axis}; the plate goes on device 3. */
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
