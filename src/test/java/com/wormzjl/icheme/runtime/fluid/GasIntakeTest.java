package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.Air;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The gas intake (batch 2026-11-15-fluid-interfaces, W2): the atmosphere as a generator, the ambient air ({@link Air#wet}) at
 * 25 °C and 101 325 Pa, its entry loss K = 3 on its link. Behind a compressor it charges a closed drum: the drum rises to
 * what the compressor makes of the air and holds there; the drum holds the air, and what the intake gave is what the drum
 * holds. With no compressor, a drum above the atmosphere sends nothing out through the intake.
 */
class GasIntakeTest {
    private static final PhysicalFluidTopology.Direction SOUTH = PhysicalFluidTopology.Direction.SOUTH;
    private static final int Y = 64;
    /** The network's model: the list the intake's spec is on. */
    private static final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics MODEL = com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.forNetwork(EventPathWorld.CATALOG, FluidPresetCatalog.NETWORK_PACKAGE);

    /** What a charge run reports: the drum's last pressure, its composition by id, the intake's moles by id, the drum's moles by id, the commits seen, and the solver's counters. */
    record Charge(double pressure, Map<String, Double> drum, Map<String, Double> given, Map<String, Double> held, int commits, boolean rest, long newton, long factorizations, double lastFlow) {}

    private static long intake(EventPathWorld w, int x, int y, int z) {
        long id = w.ledger.nextIdentity();
        var record = Placement.intake(id, new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z), SOUTH, .05, w.model);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id, record)), id + 1, null);
        return id;
    }

    /**
     * A source at (0, Y, 0) ({@code source}), two pipes, the mover {@code mover} on a pipe, a pipe, and a closed one-block drum
     * of {@code volume} m³ at (0, Y, 5), run until the drum's island rests or has committed {@code intervals} intervals.
     */
    private static Charge charge(Function<EventPathWorld, Long> source, FlowControl mover, double volume, int intervals) {
        boolean enabled = SolverDiagnostics.ENABLED;
        SolverDiagnostics.reset();
        SolverDiagnostics.ENABLED = true;
        try {
            var w = new EventPathWorld();
            w.runTo(20);
            long from = source.apply(w);
            w.place(0, Y, 1, Kind.PIPE, SOUTH, .05);
            w.place(0, Y, 2, Kind.PIPE, SOUTH, .05);
            w.place(0, Y, 3, Kind.COMPRESSOR, SOUTH, mover, null, .05);
            w.place(0, Y, 4, Kind.PIPE, SOUTH, .05);
            long drum = w.place(0, Y, 5, Kind.RESERVOIR, SOUTH, new FluidDeviceSpec(volume, 298.15, 101325, EventPathWorld.nitrogen().composition()), .05);
            var given = new HashMap<String, Double>();
            Optional<PassiveIntervalSolver.Result> seen = Optional.empty();
            int commits = 0;
            Map<String, Double> start = null;
            double lastFlow = Double.NaN;
            long limit = w.ledger.onlineTick() + 400L * intervals;
            boolean rest = false;
            while (commits < intervals && !rest) {
                w.tick();
                if (w.ledger.onlineTick() >= limit) {
                    var b = new StringBuilder("the island commits: registered " + w.registry.registrations().keySet() + " owner " + w.registry.owner(drum) + " events " + w.ledger.events().size() + " held " + w.held + " ");
                    for (var snapshot : w.coordinator.snapshots()) b.append(w.describe(snapshot.id()));
                    fail(b.toString());
                }
                var island = w.registry.owner(drum);
                if (island == null) continue;
                var s = w.coordinator.observe(island);
                var ids = w.coordinator.model(s.id()).components();
                if (start == null) {
                    // The account starts at the first state seen; a result already committed then is in it.
                    start = held(s.graph(), ids);
                    seen = s.lastResult();
                    continue;
                }
                // A drum at the compressor's limit rests (STEADY, no flow): the island commits no more intervals.
                rest = commits > 0 && s.status().startsWith("STEADY");
                if (s.lastResult().isEmpty() || s.lastResult().equals(seen)) continue;
                seen = s.lastResult();
                commits++;
                var result = seen.get();
                for (var t : result.boundaries()) {
                    if (t.nodeId() != from || t.kind() != ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE) continue;
                    for (int c = 0; c < ids.size(); c++) given.merge(ids.get(c), t.moles()[c], Double::sum);
                }
                double[] flows = result.averageMassFlows();
                lastFlow = flows.length == 0 ? 0 : Math.abs(flows[0]);
            }
            w.assertKeptUp("intake charge");
            var island = w.registry.owner(drum);
            var s = w.coordinator.observe(island);
            var ids = w.coordinator.model(s.id()).components();
            var end = held(s.graph(), ids);
            var gained = new HashMap<String, Double>();
            for (var id : end.keySet()) gained.put(id, end.get(id) - start.getOrDefault(id, 0.));
            PassiveNetwork.Reservoir node = null;
            for (var n : s.graph().reservoirs()) if (n.id() == drum) node = n;
            var drumMoles = Objects.requireNonNull(node).inventory().moles();
            double total = Arrays.stream(drumMoles).sum();
            var composition = new LinkedHashMap<String, Double>();
            for (int c = 0; c < ids.size(); c++) composition.put(ids.get(c), drumMoles[c] / total);
            return new Charge(node.state().pressure(), composition, given, gained, commits, rest, SolverDiagnostics.newtonIterations.sum(), SolverDiagnostics.luFactorizations.sum(), lastFlow);
        } finally {
            SolverDiagnostics.ENABLED = enabled;
        }
    }

    /** The moles every vessel and junction of {@code graph} holds, by compound id: the island's inventory (its sources and sinks are boundaries). */
    private static Map<String, Double> held(PassiveNetwork graph, List<String> ids) {
        var out = new HashMap<String, Double>();
        for (var n : graph.reservoirs()) {
            if (n.kind() != PassiveNetwork.NodeKind.RESERVOIR && n.kind() != PassiveNetwork.NodeKind.JUNCTION) continue;
            var moles = n.inventory().moles();
            for (int c = 0; c < ids.size(); c++) out.merge(ids.get(c), moles[c], Double::sum);
        }
        return out;
    }

    private static void assertCloses(Charge run, String label) {
        double scale = run.held().values().stream().mapToDouble(Math::abs).sum();
        assertTrue(scale > 0, label + ": the drum took air");
        for (var id : run.held().keySet()) {
            double given = Math.abs(run.given().getOrDefault(id, 0.)), held = run.held().get(id);
            assertEquals(held, given, 1e-9 * scale, label + ": what the intake gave of " + id + " is what the island holds: " + run);
        }
    }

    /** The air: the standard atmosphere's dry fractions times 1 - y_w, y_w = 0.5 p_sat(25 °C) / 101 325 Pa, and nothing else. */
    @Test void theAirIsDampDryAirWithItsRareGases() {
        var model = MODEL;
        double[] y = Air.wet(model);
        var ids = model.components();
        assertEquals(1, Arrays.stream(y).sum(), 1e-14);
        double water = y[ids.indexOf("Water")];
        assertEquals(.5 * model.saturationPressure(298.15) / 101325, water, 1e-15);
        assertEquals(.0156, water, 2e-4, "50 % relative humidity at 25 °C");
        double dry = 1 - water;
        assertEquals(.20946 * dry, y[ids.indexOf("Oxygen")], 1e-15);
        assertEquals(.00934 * dry, y[ids.indexOf("Argon")], 1e-15);
        assertEquals(18.18e-6 * dry, y[ids.indexOf("Neon")], 1e-18);
        assertEquals(5.24e-6 * dry, y[ids.indexOf("Helium")], 1e-18);
        assertEquals(1.14e-6 * dry, y[ids.indexOf("Krypton")], 1e-18);
        assertEquals(.087e-6 * dry, y[ids.indexOf("Xenon")], 1e-19);
        assertEquals(314e-6 * dry, y[ids.indexOf("CarbonDioxide")], 1e-18, "314 ppm of dry air (U.S. Standard Atmosphere 1976)");
        assertEquals((1 - .20946 - .00934 - 314e-6 - 18.18e-6 - 5.24e-6 - 1.14e-6 - .087e-6) * dry, y[ids.indexOf("Nitrogen")], 1e-15, "nitrogen the balance");
        int nonzero = 0;
        for (double v : y) if (v > 0) nonzero++;
        assertEquals(9, nonzero, "nitrogen, oxygen, carbon dioxide, five rare gases and water");
    }

    /** (a) Behind a set-flow compressor of ratio 3 the drum rises to three atmospheres of air and stops; the account closes. */
    @Test void anIntakeBehindACompressorChargesADrumWithAir() {
        var air = charge(w -> intake(w, 0, Y, 0), new FlowControl.Compressor(.05, 3), 2, 200);
        var nitrogen = charge(w -> w.place(0, Y, 0, Kind.GENERATOR, SOUTH, EventPathWorld.nitrogen(), .05), new FlowControl.Compressor(.05, 3), 2, 200);
        System.out.println("GAS_INTAKE air " + air);
        System.out.println("GAS_INTAKE nitrogen " + nitrogen);
        System.out.println("GAS_INTAKE counters air newton=" + air.newton() + " lu=" + air.factorizations() + " nitrogen newton=" + nitrogen.newton() + " lu=" + nitrogen.factorizations());
        assertTrue(air.rest(), "the drum comes to rest at the limit within 200 intervals: " + air);
        assertTrue(air.pressure() > 2.7e5 && air.pressure() < 3.05e5, "the drum stands near the compressor's ratio limit, 3 x the suction: " + air.pressure());
        assertTrue(air.lastFlow() < .01 * air.given().values().stream().mapToDouble(Math::abs).sum(), "and the flow has stopped: " + air.lastFlow());
        var y = Air.wet(MODEL);
        var ids = MODEL.components();
        for (var id : List.of("Nitrogen", "Oxygen", "Argon", "CarbonDioxide", "Neon", "Helium", "Krypton", "Xenon", "Water"))
            assertEquals(y[ids.indexOf(id)], air.drum().getOrDefault(id, 0.), 1e-9, "the drum holds the air: " + id);
        assertCloses(air, "air");
    }

    /** (b) The same behind a constructed centrifugal compressor's machine law: the drum rises above the atmosphere and holds there. */
    @Test void anIntakeBehindACentrifugalMachineChargesADrum() {
        var law = new CentrifugalCompressor(.02, 24400, CentrifugalCompressor.DEFAULT_EFFICIENCY);
        var run = charge(w -> intake(w, 0, Y, 0), new FlowControl.Machine(law, 1, true), 2, 200);
        System.out.println("GAS_INTAKE machine " + run);
        assertTrue(run.rest(), "the drum comes to rest within 200 intervals: " + run);
        assertTrue(run.pressure() > 1.1e5, "the machine raises the drum above the atmosphere: " + run.pressure());
        assertCloses(run, "machine");
    }

    /** (c) A drum at 3 bar against an intake with no mover sends nothing out: an intake only takes air in. */
    @Test void nothingFlowsBackOutOfAnIntake() {
        var w = new EventPathWorld();
        w.runTo(20);
        long from = intake(w, 0, Y, 0);
        w.place(0, Y, 1, Kind.PIPE, SOUTH, .05);
        long drum = w.place(0, Y, 2, Kind.RESERVOIR, SOUTH, new FluidDeviceSpec(2, 298.15, 101325, EventPathWorld.nitrogen().composition()), .05);
        // The drum is charged from nitrogen at 3 bar on its other side; the source is then removed.
        w.place(0, Y, 3, Kind.PIPE, SOUTH, .05);
        long feed = w.place(0, Y, 4, Kind.GENERATOR, SOUTH, new FluidDeviceSpec(1, 298.15, 3e5, EventPathWorld.nitrogen().composition()), .05);
        w.runTo(4000);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(feed, null)), w.ledger.nextIdentity(), null);
        w.runTo(4040);
        var island = w.registry.owner(drum);
        var s = w.coordinator.observe(island);
        double before = Arrays.stream(drumNode(s.graph(), drum).inventory().moles()).sum();
        double out = 0;
        Optional<PassiveIntervalSolver.Result> seen = Optional.empty();
        for (int t = 0; t < 2000; t++) {
            w.tick();
            s = w.coordinator.observe(w.registry.owner(drum));
            if (s.lastResult().isEmpty() || s.lastResult().equals(seen)) continue;
            seen = s.lastResult();
            for (var b : seen.get().boundaries()) if (b.nodeId() == from) out += Math.abs(Arrays.stream(b.moles()).sum());
        }
        w.assertKeptUp("intake back pressure");
        double after = Arrays.stream(drumNode(s.graph(), drum).inventory().moles()).sum();
        assertEquals(0, out, 0, "no exchange through the intake above the atmosphere");
        assertTrue(drumNode(s.graph(), drum).state().pressure() > 2.5e5, "the drum stays charged: " + drumNode(s.graph(), drum).state().pressure());
        assertEquals(before, after, 1e-9 * before, "the drum keeps its gas");
    }

    private static PassiveNetwork.Reservoir drumNode(PassiveNetwork graph, long drum) {
        for (var n : graph.reservoirs()) if (n.id() == drum) return n;
        throw new AssertionError("no drum");
    }
}
