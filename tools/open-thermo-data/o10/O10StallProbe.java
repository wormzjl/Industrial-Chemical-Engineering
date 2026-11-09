package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O10 probe (batch 2026-11-04-open-thermo-data): the medium grade's classical-start stall. Needs the scratch
 * instrumentation (V3SimultaneousColumnSolver.O10, V3ColumnCalculator.o10Warm). Writes to
 * tmp/open-thermo-o10/ of the session worktree (system property o10.out) and prints "o10" lines.
 */
class O10StallProbe {
    static final String OUT = System.getProperty("o10.out",
            "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa/tmp/open-thermo-o10");

    static V3ColumnInput at(V3ColumnInput i, double t) {
        return O3FeedTemperatureProbe.at(i, t, 1.0, 1.0, i.feedStageNumber());
    }

    static String summary(V3ColumnOutcome o, long polls) {
        if (o instanceof V3ColumnOutcome.Success s) {
            StringBuilder b = new StringBuilder("ok(" + polls / 1000 + "k it=" + s.diagnostics().newtonIterations() + ")");
            for (var st : s.result().streams()) b.append(String.format(" %s=%.2fkg/s@%.1fK", st.streamId(), st.massFlowKgPerSecond(), st.temperatureKelvin()));
            s.result().dutyLedger().ifPresent(d -> b.append(String.format(" Qc=%.3fMW Qfeed=%.3fMW", d.condenserWatts() / 1e6, d.feedEnthalpyWatts() / 1e6)));
            return b.toString();
        }
        var f = (V3ColumnOutcome.Failure) o;
        return "FAIL(" + polls / 1000 + "k) " + f.code() + " " + f.summary() + " path=" + f.diagnostics().solvePath();
    }

    static V3ColumnOutcome classical(MaterialCatalog c, V3ColumnInput input, long[] polls) {
        var p = new java.util.concurrent.atomic.AtomicLong();
        var o = MaterialRuntime.with(c, input.packageId(), () -> V3ColumnCalculator.calculate(input, p::incrementAndGet, 0, 0,
                V3InitializationOptions.CURRENT, V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        polls[0] = p.get();
        return o;
    }

    @Test void trace() throws Exception {
        var c = MaterialCatalog.bundled();
        String preset = System.getProperty("o10.preset", "medium_crude");
        var base = c.presets().column(preset).input(c);
        for (String ts : System.getProperty("o10.temps", "636").split(",")) {
            double t = Double.parseDouble(ts);
            try (PrintWriter w = new PrintWriter(new FileWriter(OUT + "/trace-f" + V3SimultaneousColumnSolver.O10_FORM + "-" + preset + "-" + ts + ".log"))) {
                V3SimultaneousColumnSolver.O10 = w::println;
                long[] polls = {0};
                var o = classical(c, at(base, t), polls);
                V3SimultaneousColumnSolver.O10 = null;
                w.println("RESULT " + summary(o, polls[0]));
                System.out.println("o10 trace " + preset + " " + ts + " " + summary(o, polls[0]));
            } finally {
                V3SimultaneousColumnSolver.O10 = null;
            }
        }
    }

    /** Continuation in feed temperature from the converged authored point, warm with the wet set kept. */
    @Test void existence() {
        var c = MaterialCatalog.bundled();
        String preset = System.getProperty("o10.preset", "medium_crude");
        var base = c.presets().column(preset).input(c);
        double t0 = base.feedTemperatureKelvin();
        long[] polls = {0};
        var anchor = classical(c, base, polls);
        System.out.println("o10 exist anchor " + t0 + " " + summary(anchor, polls[0]));
        if (!(anchor instanceof V3ColumnOutcome.Success s0)) return;
        double[][] legs = {{636, 634, 633, 631.28, 630, 626, 622}, {640, 642, 644}};
        for (double[] leg : legs) {
            V3NeuralSeed seed = s0.result().warmSeed().orElseThrow();
            for (double t : leg) {
                var input = at(base, t);
                final V3NeuralSeed from = seed; var o = MaterialRuntime.with(c, input.packageId(), () -> V3ColumnCalculator.o10Warm(input, from));
                System.out.println("o10 exist warm " + t + " from " + seed.input().feedTemperatureKelvin() + " " + summary(o, 0));
                if (o instanceof V3ColumnOutcome.Success s) seed = s.result().warmSeed().orElseThrow();
            }
        }
    }
}
