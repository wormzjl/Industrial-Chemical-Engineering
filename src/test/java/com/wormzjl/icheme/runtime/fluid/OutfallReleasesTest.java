package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE;
import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.*;
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

/**
 * A waste outfall's pollution (batch 2026-11-18-water-intake-outfall, owner: "Pollute at the outfall") on the bundled
 * catalog's own hazard data ({@code FluidWorldAuthority.hazardColumns}): the spill law on all it discharges, the liquid the law
 * leaves on the ground counted in the toxic and corrosive channels, solids as particulate, at the outfall's own cube. The hand
 * numbers of the review: 1 kg/s of water with 1 mol % hydrogen sulfide, and 1 kg/s of the heavy cut {@code crude_pc08}.
 */
class OutfallReleasesTest {
    private final MaterialCatalog catalog = MaterialCatalog.bundled();
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private final List<String> axis = model.components();
    private final int n = axis.size();
    private static final long OUTFALL = 6, VOID = 7;
    /** 400 ticks online: 20 s of process time at factor 1. */
    private static final long SPAN = 400;
    private static final double SECONDS = 20;

    private record Booked(PhysicalFluidTopology.Position at, Channel channel, double amount) {}

    private final List<Booked> booked = new ArrayList<>();

    private static PhysicalFluidTopology.Position at(int x, int y) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, 0);
    }

    private HazardStandard standard() {
        var h = FluidWorldAuthority.hazardColumns(catalog, model);
        return new HazardStandard(h[0], h[1], h[2], h[3], h[4], h[5]);
    }

    /** An outfall at (6, 40) and a plain void at (7, 40). */
    private WorldTopologyLedger ledger() {
        var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
        active.put(OUTFALL, Placement.outfall(OUTFALL, at(6, 40), PhysicalFluidTopology.Direction.NORTH, .05, catalog));
        var plain = PhysicalFluidTopology.Device.builder(VOID, at(7, 40), Kind.VOID, PhysicalFluidTopology.Direction.NORTH, new PipeResistance.Geometry(1, .05, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0), new FlowControl.Passive()).build();
        active.put(VOID, new WorldTopologyLedger.Registration(plain, FluidDeviceSpec.nitrogen(catalog), 0));
        var empty = WorldTopologyLedger.Snapshot.empty(catalog);
        return new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
    }

    private PassiveIntervalSolver.Result result(ConservativeTransport.BoundaryTransfer... boundaries) {
        double[] moles = new double[n];
        moles[axis.indexOf("Nitrogen")] = 40;
        var state = model.flashTP(298.15, 101325, moles, () -> {});
        var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(OUTFALL, 40, state, PassiveNetwork.NodeKind.VOID), new PassiveNetwork.Reservoir(VOID, 40, state, PassiveNetwork.NodeKind.VOID)), List.of());
        return new PassiveIntervalSolver.Result(graph, SECONDS, new double[0], 1, 0, 0, List.of(boundaries), Map.of(), List.of(), new double[0], new double[0], List.of());
    }

    private Releases releases() {
        var h = FluidWorldAuthority.hazardColumns(catalog, model);
        return new Releases(ledger(), axis, standard(), new double[n][5], Releases.so2Reference(h[1], new double[n][5]), id -> Releases.Flame.OUT, id -> axis, () -> SimulationTime.ONLINE,
                (position, channel, amount) -> booked.add(new Booked(position, channel, amount)));
    }

    private double sum(Channel channel) {
        double s = 0;
        for (var b : booked) if (b.channel() == channel) s += b.amount();
        return s;
    }

    /** {@code kg} of a stream of mole fractions {@code fractions} (by compound id) leaving through a boundary: moles, negative. */
    private double[] leaving(double kg, Map<String, Double> fractions) {
        var w = model.molecularWeights();
        double mw = 0;
        for (var e : fractions.entrySet()) mw += e.getValue() * w[index(e.getKey())];
        var m = new double[n];
        for (var e : fractions.entrySet()) m[index(e.getKey())] = -kg / mw * e.getValue();
        return m;
    }

    private int index(String id) {
        int i = axis.indexOf(id);
        assertTrue(i >= 0, id + " is on the network model's list: " + axis);
        return i;
    }

    @Test void cleanWaterBooksNothing() {
        var s = standard();
        var water = new double[n];
        water[index("Water")] = 1;
        assertEquals(0, s.toxic(water), "water has no toxic reference");
        assertEquals(0, s.corrosive(water), "nor a corrosive one");
        assertEquals(0, s.combustible(water), "nor a flammability limit");
        assertArrayEquals(new double[3], Releases.outfall(s, water, 1));
        releases().booked(7, 0, SPAN, result(new ConservativeTransport.BoundaryTransfer(OUTFALL, leaving(20, Map.of("Water", 1.)), 0, SolidInventory.EMPTY, 0, EXCHANGE)));
        assertEquals(List.of(), booked, "1 kg/s of clean water for 20 s books nothing");
    }

    @Test void waterWithHydrogenSulfideBooksItsGasWhole() {
        var stream = leaving(20, Map.of("Water", .99, "HydrogenSulfide", .01));
        double h2s = -stream[index("HydrogenSulfide")];
        var s = standard();
        assertEquals(1, s.volatileShare(index("HydrogenSulfide")), "H2S is a gas at 25 C: it goes whole");
        releases().booked(7, 0, SPAN, result(new ConservativeTransport.BoundaryTransfer(OUTFALL, stream, 0, SolidInventory.EMPTY, 0, EXCHANGE)));
        // Hand numbers: 55.02 mol/s, 0.5502 mol/s of H2S; toxic 0.5502 / 100 ppm = 5 502 air-mol per s, combustible 0.5502 / 0.04.
        assertEquals(h2s / 100e-6, sum(Channel.TOXIC), 1e-9 * h2s / 100e-6);
        assertEquals(h2s / .04, sum(Channel.COMBUSTIBLE), 1e-9 * h2s / .04);
        assertEquals(0, sum(Channel.CORROSIVE));
        assertEquals(5502, sum(Channel.TOXIC) / SECONDS, 5, "about 5 502 air-mol per second");
        for (var b : booked) assertEquals(at(6, 40), b.at(), "at the outfall's own cube");
        System.out.printf(Locale.ROOT, "OUTFALL h2s water: toxic %.4e /s combustible %.4e /s%n", sum(Channel.TOXIC) / SECONDS, sum(Channel.COMBUSTIBLE) / SECONDS);
    }

    @Test void aHeavyCutBooksTheLiquidItLeavesOnTheGroundAsToxicOnly() {
        var stream = leaving(20, Map.of("crude_pc08", 1.));
        int pc = index("crude_pc08");
        double moles = -stream[pc];
        var s = standard();
        double share = s.volatileShare(pc);
        assertTrue(share < 1e-6, "a heavy cut barely evaporates at 25 C: " + share);
        releases().booked(7, 0, SPAN, result(new ConservativeTransport.BoundaryTransfer(OUTFALL, stream, 0, SolidInventory.EMPTY, 0, EXCHANGE)));
        // Hand numbers: 2.792 mol/s of a 358 g/mol cut at 1 100 ppm: toxic 2 538 air-mol per s, nearly all of it the liquid left on the ground.
        assertEquals(moles / 1100e-6, sum(Channel.TOXIC), 1e-9 * moles / 1100e-6, "the volatile share and the rest, both toxic");
        assertEquals(2538, sum(Channel.TOXIC) / SECONDS, 3);
        double flammable = s.combustible(s.volatileMoles(negated(stream)));
        assertEquals(flammable, sum(Channel.COMBUSTIBLE), 1e-12, "liquid that did not evaporate makes no flammable cloud: its volatile share only");
        assertTrue(flammable / SECONDS < 1e-3, "a negligible cloud: " + flammable / SECONDS);
        // An open tank's spill of the same stream books its volatile share only.
        assertEquals(moles * share / 1100e-6, s.toxic(s.volatileMoles(negated(stream))), 1e-9 * moles / 1100e-6);
        System.out.printf(Locale.ROOT, "OUTFALL heavy cut: toxic %.4e /s, of which volatile %.4e /s (share %.3e), combustible %.4e /s%n", sum(Channel.TOXIC) / SECONDS, moles * share / 1100e-6 / SECONDS, share, flammable / SECONDS);
    }

    @Test void theFourRowsAndAPlainVoidThatBooksNothing() {
        var sand = new SolidMaterialCatalog.SolidMaterial("icheme:sand", "1", 2000, 1000);
        var solids = new SolidInventory(List.of(new SolidInventory.Population(sand, new ParticleSize(".001"), .2)));
        var m = new double[n];
        m[index("HydrogenSulfide")] = -1;
        m[index("crude_pc08")] = -2;
        m[index("Ammonia")] = -3;
        m[index("Water")] = -50;
        var s = standard();
        releases().booked(7, 0, SPAN, result(new ConservativeTransport.BoundaryTransfer(OUTFALL, m, 0, solids, -1, EXCHANGE),
                new ConservativeTransport.BoundaryTransfer(VOID, m.clone(), 0, solids, -1, EXCHANGE)));
        double ammoniaShare = s.volatileShare(index("Ammonia"));
        // Gas (H2S) whole; the cut's volatile share and its rest, toxic; ammonia's volatile share and rest, corrosive at 300 ppm; the sand.
        assertEquals(1 / 100e-6 + 2 / 1100e-6, sum(Channel.TOXIC), 1e-9 * 1e4);
        assertEquals(3 / 300e-6, sum(Channel.CORROSIVE), 1e-9 * 1e4, "ammonia (share " + ammoniaShare + "): all of it, volatile or not");
        double combustible = s.combustible(s.volatileMoles(negated(m)));
        assertEquals(combustible, sum(Channel.COMBUSTIBLE), 1e-12 + 1e-9 * combustible, "the volatile share only");
        assertEquals(HazardStandard.particulate(.2), sum(Channel.PARTICULATE), 1e-9 * HazardStandard.particulate(.2));
        for (var b : booked) assertEquals(at(6, 40), b.at(), "only the outfall books; a plain void does not");
    }

    @Test void theYieldScalesOnlyTheRest() {
        var s = standard();
        var m = new double[n];
        m[index("crude_pc08")] = 2;
        m[index("HydrogenSulfide")] = 1;
        var all = Releases.outfall(s, m, 1);
        var none = Releases.outfall(s, m, 0);
        assertEquals(s.toxic(s.volatileMoles(m)), none[0], 1e-12, "yield 0: the spill law alone");
        assertEquals(1 / 100e-6 + 2 / 1100e-6, all[0], 1e-6);
        assertEquals(none[1], all[1], "the combustible channel never takes the rest");
    }

    private static double[] negated(double[] m) {
        var out = new double[m.length];
        for (int i = 0; i < m.length; i++) out[i] = -m[i];
        return out;
    }
}
