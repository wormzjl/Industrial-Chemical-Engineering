package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.HazardStandard;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The pollution releases of the fluid network (batch 2026-11-15-pollution-system, WP4) on hand-made spans: a span's boundary
 * transfers as a commit or a replay gives them, on a ledger whose devices stand where {@code FluidWorldAuthority} places them,
 * into a recording sink. The hazard standard and the formulas are the test's own, on the network model's component axis:
 * compound 0 a 100 ppm toxic gas with sulphur (H2S-like), 1 a methane-like fuel, 2 a light liquid (propane's constants), 3 a
 * heavy one (hexadecane's).
 */
class ReleasesTest {
    private final MaterialCatalog catalog = MaterialCatalog.bundled();
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private final List<String> axis = model.components();
    private final int n = axis.size();
    private static final PipeResistance.Geometry LINE = new PipeResistance.Geometry(1, .2, .000045, 0);
    private static final long TANK = 1, DRUM = 2, POT = 3, CHIMNEY = 4, FLARE = 5;
    private static final int TOXIC_GAS = 0, FUEL = 1, LIGHT = 2, HEAVY = 3;
    private static final double LEVEL = 5.5;
    /** 400 ticks online: 20 s of process time at factor 1. */
    private static final long SPAN = 400;
    private static final double SECONDS = 20;

    private record Booked(PhysicalFluidTopology.Position at, Channel channel, double amount) {}

    private final List<Booked> booked = new ArrayList<>();
    private Releases.Flame flame = Releases.Flame.OUT;

    private static PhysicalFluidTopology.Position at(int x, int y) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, 0);
    }

    private HazardStandard standard() {
        double[] toxic = new double[n], corrosive = new double[n], lfl = new double[n], tc = new double[n], pc = new double[n], omega = new double[n];
        Arrays.fill(tc, Double.NaN);
        Arrays.fill(pc, Double.NaN);
        Arrays.fill(omega, Double.NaN);
        toxic[TOXIC_GAS] = 100e-6;
        lfl[TOXIC_GAS] = .04;
        lfl[FUEL] = .05;
        tc[LIGHT] = 369.83;
        pc[LIGHT] = 4.248e6;
        omega[LIGHT] = .152;
        tc[HEAVY] = 723;
        pc[HEAVY] = 1.4e6;
        omega[HEAVY] = .718;
        return new HazardStandard(toxic, corrosive, lfl, tc, pc, omega);
    }

    private double[][] formulas() {
        var f = new double[n][5];
        f[TOXIC_GAS] = new double[]{0, 2, 0, 0, 1};
        f[FUEL] = new double[]{1, 4, 0, 0, 0};
        f[LIGHT] = new double[]{3, 8, 0, 0, 0};
        f[HEAVY] = new double[]{16, 34, 0, 0, 0};
        return f;
    }

    private FluidThermodynamics.State nitrogen() {
        double[] moles = new double[model.componentCount()];
        moles[axis.indexOf("Nitrogen")] = 40;
        return model.flashTP(298.15, 101325, moles, () -> {
        });
    }

    private static WorldTopologyLedger.Registration registration(PhysicalFluidTopology.Device device, double[] n2) {
        return new WorldTopologyLedger.Registration(device, new FluidDeviceSpec(1, 298.15, 101325, n2), 0);
    }

    /** The ledger: an open tank at (0, 10), a blanketed drum at (1, 10), a still's pot at (2, 10), a chimney's tip at (3, 30) and a flare's at (5, 0). */
    private WorldTopologyLedger ledger() {
        assertTrue(n >= 5, "the network model has the compounds the test names");
        var n2 = FluidDeviceSpec.nitrogen(catalog).composition();
        var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
        active.put(TANK, registration(PhysicalFluidTopology.Device.builder(TANK, at(0, 10), Kind.RESERVOIR, NORTH, LINE, new FlowControl.Passive()).build(), n2));
        active.put(DRUM, registration(PhysicalFluidTopology.Device.builder(DRUM, at(1, 10), Kind.RESERVOIR, NORTH, LINE, new FlowControl.Passive()).build(), n2));
        var pot = PhysicalFluidTopology.Device.builder(POT, at(2, 10), Kind.RESERVOIR, NORTH, LINE, new FlowControl.Passive())
                .heat(new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.POT, 1, 0)).build();
        assertTrue(pot.stillPot());
        active.put(POT, registration(pot, n2));
        var chimney = PhysicalFluidTopology.Device.builder(CHIMNEY, at(3, 30), Kind.VOID, UP, new PipeResistance.Geometry(30.5, 1, PipeResistance.DEFAULT_ROUGHNESS_METRES, Chimney.STACK_LOSS),
                new FlowControl.Passive()).ports(List.of(new PhysicalFluidTopology.Port(at(3, 0), WEST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL))).build();
        assertTrue(chimney.chimney());
        active.put(CHIMNEY, registration(chimney, n2));
        var flare = PhysicalFluidTopology.Device.builder(FLARE, at(5, 0), Kind.VOID, NORTH, new PipeResistance.Geometry(1, .3, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0), FlowControl.ControlValve.flare())
                .ports(List.of(new PhysicalFluidTopology.Port(at(5, 0), WEST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL))).build();
        assertTrue(flare.flare());
        active.put(FLARE, registration(flare, n2));
        var empty = WorldTopologyLedger.Snapshot.empty(catalog);
        return new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
    }

    private PassiveNetwork.Reservoir vessel(long id, boolean open, double blanket) {
        var state = nitrogen();
        var inventory = new PassiveNetwork.Inventory(10, PhaseLayout.totalAmounts(state), state.internalEnergy(), SolidInventory.EMPTY, LEVEL, open,
                VesselGeometry.prism(10, LEVEL), null, null, false, PhaseLayout.AMBIENT_PRESSURE, blanket);
        return new PassiveNetwork.Reservoir(id, 10, state, PassiveNetwork.NodeKind.RESERVOIR, inventory);
    }

    /** The island's graph: the three vessels and the two tips. */
    private PassiveNetwork graph() {
        var state = nitrogen();
        return new PassiveNetwork(List.of(vessel(TANK, true, 0), vessel(DRUM, false, 2e5), vessel(POT, true, 0),
                new PassiveNetwork.Reservoir(CHIMNEY, 30, state, PassiveNetwork.NodeKind.VOID), new PassiveNetwork.Reservoir(FLARE, 0, state, PassiveNetwork.NodeKind.VOID)), List.of());
    }

    private PassiveIntervalSolver.Result result(double seconds, ConservativeTransport.BoundaryTransfer... boundaries) {
        return new PassiveIntervalSolver.Result(graph(), seconds, new double[0], 1, 0, 0, List.of(boundaries), Map.of(), List.of(), new double[0], new double[0], List.of());
    }

    private Releases releases(Releases.Sink sink, List<String> islandAxis) {
        return new Releases(ledger(), axis, standard(), formulas(), Releases.SO2_REFERENCE, id -> flame, id -> islandAxis, () -> SimulationTime.ONLINE, sink);
    }

    private Releases releases() {
        return releases((position, channel, amount) -> booked.add(new Booked(position, channel, amount)), axis);
    }

    /** {@code amount} mol of compound {@code compound} leaving (negative). */
    private double[] out(int compound, double amount) {
        var m = new double[n];
        m[compound] = -amount;
        return m;
    }

    private static ConservativeTransport.BoundaryTransfer transfer(long node, double[] moles, ConservativeTransport.BoundaryTransfer.Kind kind) {
        return new ConservativeTransport.BoundaryTransfer(node, moles, 0, SolidInventory.EMPTY, 0, kind);
    }

    private double sum(Channel channel) {
        double s = 0;
        for (var b : booked) if (b.channel() == channel) s += b.amount();
        return s;
    }

    @Test
    void anOpenTanksVentBooksItsGasAtTheRoof() {
        releases().booked(7, 0, SPAN, result(SECONDS, transfer(TANK, out(TOXIC_GAS, 3), VENT)));
        assertEquals(3 / 100e-6, sum(Channel.TOXIC), 1e-9 * 3 / 100e-6);
        assertEquals(3 / .04, sum(Channel.COMBUSTIBLE), 1e-9);
        assertEquals(0, sum(Channel.CORROSIVE));
        for (var b : booked) assertEquals(at(0, 10 + (int) Math.ceil(LEVEL)), b.at(), "at the roof");
    }

    @Test
    void aReplayedSpanTwiceAsLongBooksTwice() {
        // A replay's transfers are its span's own (the interval's scaled by the span's share): a span of twice the ticks
        // carries twice the moles over twice the seconds.
        releases().booked(7, 0, 2 * SPAN, result(2 * SECONDS, transfer(TANK, out(TOXIC_GAS, 6), VENT)));
        assertEquals(2 * 3 / 100e-6, sum(Channel.TOXIC), 1e-9 * 6 / 100e-6);
        // A result that advanced half the span's seconds is stretched to the span (the rate times the span).
        booked.clear();
        releases().booked(7, 0, 2 * SPAN, result(SECONDS, transfer(TANK, out(TOXIC_GAS, 3), VENT)));
        assertEquals(2 * 3 / 100e-6, sum(Channel.TOXIC), 1e-9 * 6 / 100e-6);
    }

    @Test
    void aBlanketedDrumAndAStillsPotBookNothing() {
        releases().booked(7, 0, SPAN, result(SECONDS, transfer(DRUM, out(TOXIC_GAS, 3), VENT), transfer(POT, out(TOXIC_GAS, 3), VENT), transfer(POT, out(LIGHT, 3), SPILL)));
        assertEquals(List.of(), booked);
    }

    @Test
    void aSpillBooksOnlyItsVolatileShareAtTheFoot() {
        var s = standard();
        assertEquals(1, s.volatileShare(LIGHT), "propane boils at 298 K: it goes whole");
        assertTrue(s.volatileShare(HEAVY) < .01, "a heavy oil gives the air under 1 %: " + s.volatileShare(HEAVY));
        var spilled = new double[n];
        spilled[TOXIC_GAS] = -2;
        spilled[HEAVY] = -50;
        releases().booked(7, 0, SPAN, result(SECONDS, transfer(TANK, spilled, SPILL)));
        assertEquals(2 / 100e-6, sum(Channel.TOXIC), 1e-6);
        // The toxic gas has no critical constants here: its whole liquid counts as volatile; the heavy oil has no hazard.
        assertEquals(2 / .04, sum(Channel.COMBUSTIBLE), 1e-9);
        for (var b : booked) assertEquals(at(0, 10), b.at(), "at the foot");
    }

    @Test
    void aChimneyBooksItsGasAndItsSolidsAboveItsTop() {
        var sand = new SolidMaterialCatalog.SolidMaterial("icheme:sand", "1", 2000, 1000);
        var solids = new SolidInventory(List.of(new SolidInventory.Population(sand, new ParticleSize(".001"), .2)));
        var b = new ConservativeTransport.BoundaryTransfer(CHIMNEY, out(TOXIC_GAS, 1), 0, solids, -1, EXCHANGE);
        releases().booked(7, 0, SPAN, result(SECONDS, b));
        assertEquals(HazardStandard.particulate(.2), sum(Channel.PARTICULATE), 1e-9 * HazardStandard.particulate(.2));
        assertEquals(1 / 100e-6, sum(Channel.TOXIC), 1e-6);
        for (var r : booked) assertEquals(at(3, 31), r.at(), "the tip is the stack's top cell; the plume leaves above it");
    }

    /** 2 mol of the H2S-like gas and 3 of methane into the flare's tip. */
    private PassiveIntervalSolver.Result flared() {
        var m = new double[n];
        m[TOXIC_GAS] = -2;
        m[FUEL] = -3;
        return result(SECONDS, transfer(FLARE, m, EXCHANGE));
    }

    @Test
    void aBurningFlareBooksItsSulphurDioxideAndSootOnly() {
        flame = new Releases.Flame(true, .5);
        releases().booked(7, 0, SPAN, flared());
        assertEquals(2 / Releases.SO2_REFERENCE, sum(Channel.CORROSIVE), 1e-6);
        assertEquals(HazardStandard.particulate(.5 * Releases.SOOT_YIELD * 3 * Releases.CARBON_MOLAR_MASS), sum(Channel.PARTICULATE), 1e-12);
        assertEquals(0, sum(Channel.TOXIC));
        assertEquals(0, sum(Channel.COMBUSTIBLE));
        for (var b : booked) assertEquals(at(5, 1), b.at());
    }

    @Test
    void aFlareWithItsFlameOutBooksItsGasUnburnt() {
        flame = Releases.Flame.OUT;
        releases().booked(7, 0, SPAN, flared());
        assertEquals(2 / 100e-6, sum(Channel.TOXIC), 1e-6);
        assertEquals(2 / .04 + 3 / .05, sum(Channel.COMBUSTIBLE), 1e-9);
        assertEquals(0, sum(Channel.CORROSIVE));
        assertEquals(0, sum(Channel.PARTICULATE));
    }

    @Test
    void aFlushOnAnIslandsOwnListBooksByName() {
        // The island carries only compound 0 and the water: its transfers have two entries, mapped onto the world's axis by name.
        var own = List.of(axis.get(TOXIC_GAS), axis.getLast());
        var r = releases((position, channel, amount) -> booked.add(new Booked(position, channel, amount)), own);
        r.booked(7, 0, SPAN, result(SECONDS, transfer(TANK, new double[]{-1e-3, 0}, FLUSH)));
        assertEquals(1e-3 / 100e-6, sum(Channel.TOXIC), 1e-9);
        for (var b : booked) assertEquals(at(0, 10), b.at());
    }

    @Test
    void whatEntersTheIslandIsNoRelease() {
        var in = new double[n];
        in[TOXIC_GAS] = 3;
        releases().booked(7, 0, SPAN, result(SECONDS, transfer(TANK, in, VENT), transfer(CHIMNEY, in, EXCHANGE)));
        assertEquals(List.of(), booked);
    }

    @Test
    void aFailingSinkStopsNothing() {
        var r = releases((position, channel, amount) -> {
            throw new IllegalStateException("the service is broken");
        }, axis);
        assertDoesNotThrow(() -> r.booked(7, 0, SPAN, result(SECONDS, transfer(TANK, out(TOXIC_GAS, 3), VENT))));
        assertEquals(1, r.failures());
    }

    @Test
    void theArithmetic() {
        assertEquals(1, Releases.scale(20, 20));
        assertEquals(2, Releases.scale(40, 20));
        assertEquals(0, Releases.scale(0, 20));
        assertEquals(0, Releases.scale(20, 0));
        assertArrayEquals(new double[]{2, 0, 0}, Releases.leaving(new double[]{-1, 1, 0}, 2));
        var f = formulas();
        var m = new double[n];
        m[TOXIC_GAS] = 2;
        m[FUEL] = 3;
        var products = Releases.flareProducts(m, f, 1, 50e-6);
        assertEquals(2 / 50e-6, products[0], 1e-6);
        assertEquals(HazardStandard.particulate(Releases.SOOT_YIELD * 3 * Releases.CARBON_MOLAR_MASS), products[1], 1e-12);
        assertEquals(0, Releases.flareProducts(m, f, 0, 50e-6)[1], "no soot index, no soot");
        // SO2's reference: the S1 O2 compound's corrosive one, else NIOSH's 100 ppm.
        var corrosive = new double[n];
        assertEquals(Releases.SO2_REFERENCE, Releases.so2Reference(corrosive, f));
        f[LIGHT] = new double[]{0, 0, 0, 2, 1};
        corrosive[LIGHT] = 80e-6;
        assertEquals(80e-6, Releases.so2Reference(corrosive, f));
        assertEquals(at(1, 7), Releases.above(at(1, 2), 4.2));
        assertEquals(at(1, 2), Releases.above(at(1, 2), Double.NaN));
    }

    @Test
    void theWorldsStandardIsOnTheModelsAxis() {
        var columns = FluidWorldAuthority.hazardColumns(catalog, model);
        for (var column : columns) assertEquals(n, column.length);
        var s = new HazardStandard(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5]);
        assertEquals(n, s.size());
        int so2 = axis.indexOf("SulfurDioxide");
        if (so2 >= 0) assertEquals(100e-6, Releases.so2Reference(columns[1], FluidWorldAuthority.formulas(catalog, model)), 1e-12);
    }
}
