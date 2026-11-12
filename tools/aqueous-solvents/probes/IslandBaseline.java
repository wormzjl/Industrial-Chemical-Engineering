package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.runtime.fluid.RetainedSolver;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * W3b baseline (orchestrator decision 3, 2026-11-11): three fixed islands on the network package, replayed through one
 * retained solver handle over five-second intervals, as a coordinator job does. Each island is a chain of six 1 m3
 * reservoirs joined by 100 m pipes of 5 cm, pressures 1.5 bar +- 3 kPa and temperatures 320 K +- 6 K (cosine along
 * the chain), so mass and heat move.
 *
 * <ul>
 *   <li>{@code dry}: 13 compounds, no water;</li>
 *   <li>{@code wet}: the same with water (25 % of the moles): gas, oil and free water in every node;</li>
 *   <li>{@code polar}: the dry list with hydrogen sulfide and methyl mercaptan (15 compounds), no water.</li>
 * </ul>
 *
 * <p>Usage: {@code IslandBaseline list | count <scenario> [intervals] | time <scenario> [intervals] [warm-s] [measure-s]}.
 * {@code count} prints the {@link SolverDiagnostics} counters of one replay (and the kernel's evaluation count when the
 * counting overlay of {@code kernel-count.mjs} is first on the class path); {@code time} replays from the start again and
 * again, discards {@code warm-s} seconds, and prints the wall time per accepted step over {@code measure-s} seconds.
 * Compile into this package against {@code build/classes/java/main}; run each timing alone.
 */
public final class IslandBaseline {
    private static final List<String> DRY = List.of("Nitrogen", "Methane", "Ethane", "Propane", "N-butane",
            "N-pentane", "N-hexane", "Benzene", "Toluene", "crude_pc03", "crude_pc06", "crude_pc09", "CarbonDioxide");
    private static final double[] DRY_MOLES = {0.06, 0.12, 0.03, 0.03, 0.05, 0.05, 0.04, 0.16, 0.10, 0.08, 0.12, 0.08, 0.08};
    private static final Runnable NOOP = () -> {};

    public static void main(String[] args) {
        var network = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), FluidMaterialCatalog.NETWORK_PACKAGE);
        String mode = args[0];
        if (mode.equals("list")) { System.out.println(network.components()); return; }
        String scenario = args[1];
        int intervals = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        var ids = new ArrayList<>(DRY);
        var amounts = new ArrayList<Double>();
        for (double m : DRY_MOLES) amounts.add(m);
        if (scenario.equals("polar")) { ids.add("HydrogenSulfide"); amounts.add(0.04); ids.add("MethylMercaptan"); amounts.add(0.02); }
        // W3b option A: an island no water reaches runs on the model without water.
        boolean wet = scenario.equals("wet");
        var model = network.on(ids, wet);
        if (wet) { ids.add("Water"); amounts.add(0.33); }
        var start = chain(model, ids, amounts);
        if (mode.equals("count")) count(model, start, scenario, intervals);
        else time(model, start, scenario, intervals, Double.parseDouble(args.length > 3 ? args[3] : "60"),
                Double.parseDouble(args.length > 4 ? args[4] : "60"));
    }

    static PassiveNetwork chain(FluidThermodynamics model, List<String> ids, List<Double> amounts) {
        var nodes = new ArrayList<PassiveNetwork.Reservoir>();
        var pipes = new ArrayList<PassiveNetwork.Pipe>();
        for (int i = 0; i < 6; i++) {
            double pressure = 150000 + 3000 * DeterministicMath.cos(i * 0.9);
            double temperature = 320 + 6 * DeterministicMath.cos(i * 1.3);
            double[] n = new double[model.componentCount()];
            for (int c = 0; c < ids.size(); c++) n[model.components().indexOf(ids.get(c))] = amounts.get(c);
            var unit = model.flashTP(temperature, pressure, n, NOOP);
            for (int c = 0; c < n.length; c++) n[c] /= unit.volume();
            var state = model.flashTP(temperature, pressure, n, NOOP);
            nodes.add(new PassiveNetwork.Reservoir(i + 1, 0, state, PassiveNetwork.NodeKind.RESERVOIR,
                    new PassiveNetwork.Inventory(1, n, state.internalEnergy())));
            if (i > 0) pipes.add(new PassiveNetwork.Pipe(i, i - 1, i, new PipeResistance.Geometry(100, .05, .000045, 0)));
        }
        return new PassiveNetwork(nodes, pipes);
    }

    /** The node's phase volumes; the one place that reads the State's phase layout (edit after step 5). */
    static String phases(FluidThermodynamics.State s) {
        return String.format(Locale.ROOT, "T %.2f P %.0f L %.4f A %.4f V %.4f", s.temperature(), s.pressure(),
                s.liquidSlotVolume(), s.aqueousSlotVolume(), s.vaporVolume());
    }

    static void count(FluidThermodynamics model, PassiveNetwork start, String scenario, int intervals) {
        System.out.println(scenario + ": " + model.componentCount() + " compounds " + model.components());
        for (var r : start.reservoirs()) System.out.println("  start node " + r.id() + ": " + phases(r.state()));
        var retained = new RetainedSolver();
        var graph = start;
        long accepted = 0, rejected = 0, kernelBefore = kernelEvaluations();
        SolverDiagnostics.reset();
        SolverDiagnostics.ENABLED = true;
        try {
            for (int interval = 0; interval < intervals; interval++) {
                var result = retained.solve(model, graph, 5.0, PassiveIntervalSolver.Settings.defaults(), NOOP);
                graph = result.graph();
                accepted += result.acceptedSubsteps();
                rejected += result.rejectedSubsteps();
            }
        } finally { SolverDiagnostics.ENABLED = false; }
        var sample = SolverDiagnostics.sample();
        long kernel = kernelEvaluations() - kernelBefore;
        System.out.printf(Locale.ROOT, "  %d intervals: accepted %d rejected %d newtonIterations %d newtonSolves %d stateCalls %d flashCalls %d residualEvaluations %d kernelEvaluations %s%n",
                intervals, accepted, rejected, sample.value("newtonIterations"), sample.value("newtonSolves"),
                sample.value("stateCalls"), sample.value("flashCalls"), sample.value("residualEvaluations"),
                kernel < 0 ? "n/a" : Long.toString(kernel));
        System.out.printf(Locale.ROOT, "  jacobian: blockBuilds %d blockColumns %d blockFallbacks %d stateCallsInJacobian %d tangentsBuilt %s tangentPhases %s exactPhases %s%n",
                sample.value("jacobianBlockBuilds"), sample.value("jacobianBlockColumns"), sample.value("jacobianBlockFallbacks"),
                sample.value("stateCallsInJacobian"), optional(sample, "jacobianTangentsBuilt"), optional(sample, "jacobianTangentPhases"),
                optional(sample, "jacobianExactPhases"));
        for (var r : graph.reservoirs()) System.out.println("  end node " + r.id() + ": " + phases(r.state()));
        for (var r : graph.reservoirs()) System.out.printf(Locale.ROOT, "  end state %d: T %.12e P %.12e V %.12e H %.12e%n", r.id(),
                r.state().temperature(), r.state().pressure(), r.state().volume(), r.state().enthalpy());
    }

    /** A counter a later form adds: its value, or n/a on a form without it. */
    static String optional(SolverDiagnostics.Sample sample, String name) {
        try { return Long.toString(sample.value(name)); } catch (RuntimeException absent) { return "n/a"; }
    }

    static void time(FluidThermodynamics model, PassiveNetwork start, String scenario, int intervals, double warm, double measure) {
        long warmEnd = System.nanoTime() + (long) (warm * 1e9);
        while (System.nanoTime() < warmEnd) replay(model, start, intervals);
        long steps = 0, replays = 0, began = System.nanoTime(), end = began + (long) (measure * 1e9);
        while (System.nanoTime() < end) { steps += replay(model, start, intervals); replays++; }
        double seconds = (System.nanoTime() - began) / 1e9;
        System.out.printf(Locale.ROOT, "%s: %d compounds, %d replays of %d intervals, %d accepted steps, %.3f ms per accepted step%n",
                scenario, model.componentCount(), replays, intervals, steps, 1e3 * seconds / steps);
    }

    static long replay(FluidThermodynamics model, PassiveNetwork start, int intervals) {
        var retained = new RetainedSolver();
        var graph = start;
        long accepted = 0;
        for (int interval = 0; interval < intervals; interval++) {
            var result = retained.solve(model, graph, 5.0, PassiveIntervalSolver.Settings.defaults(), NOOP);
            graph = result.graph();
            accepted += result.acceptedSubsteps();
        }
        return accepted;
    }

    /** The counting overlay's static counter, or -1 without it. */
    static long kernelEvaluations() {
        try {
            var field = Class.forName("com.wormzjl.icheme.science.thermo.PengRobinsonKernel").getField("EVALUATIONS");
            return ((java.util.concurrent.atomic.LongAdder) field.get(null)).sum();
        } catch (ReflectiveOperationException absent) { return -1; }
    }
}
