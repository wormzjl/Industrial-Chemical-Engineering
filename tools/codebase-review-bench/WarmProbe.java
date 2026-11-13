import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * B1 warm-route probe (PROFILE_REVIEW.md, finding D3 of COLUMN_REVIEW.md): why a warm re-solve after a 2 % feed change costs more
 * than a cold one on the literature preset. Per case (medium, literature), after 3 untimed rounds: the cold base's seed (free water,
 * wet trays), then three solves of the +2 % input and their path, Newton iterations, closure and checkpoint count (every flash
 * and solver step calls the checkpoint):
 * <ol>
 * <li>{@code ColumnUnit.solve(target, seed)}: the simulator's warm route (what ColumnBench's warm phase times);</li>
 * <li>{@code calculateWithStrategy(target, AGGRESSIVE, seed)}: the strategy entry with the seed (strict policy {@code OFF});</li>
 * <li>{@code calculateWithStrategy(target, AGGRESSIVE, null)}: the same entry with no seed ({@code OFF_FAST}), as
 * {@code ColumnSolve} runs a wet seed (it drops wet seeds);</li>
 * <li>{@code ColumnUnit.solve(target, null)}: the learned cold route ColumnBench's cold phase times.</li>
 * </ol>
 * Uses ColumnBench's cases and scaling. Counters, with wall times for orientation only.
 */
public final class WarmProbe {
    // Copied from ColumnBench.java (the source launcher compiles one file).
    record Case(V3ColumnInput input, MaterialCatalog catalog) {}

    static Case of(String name) {
        return switch (name) {
            case "medium" -> {
                var catalog = MaterialRuntime.current();
                yield new Case(V3TestInputs.crude(catalog, "medium_crude"), catalog);
            }
            case "literature" -> {
                var current = MaterialRuntime.current();
                var catalog = current.presets().columns().containsKey("literature_tia_juana") ? current : TiaJuanaTestCatalog.catalog();
                yield new Case(V3TestInputs.literatureCduInput(), catalog);
            }
            default -> throw new IllegalArgumentException("case " + name);
        };
    }

    /** Every extensive input times alpha: feed, steam, draws, pumparound duties; the reboiler duty spec stays as authored. */
    static V3ColumnInput scaled(V3ColumnInput input, double alpha) {
        var steam = input.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * alpha, s.temperatureKelvin())).toList();
        var draws = input.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * alpha)).toList();
        var pas = input.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * alpha, p.split())).toList();
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < feed.length; i++) feed[i] *= alpha;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(), feed,
                input.feedTemperatureKelvin(), input.stageCount(), input.feedStageNumber(), input.topPressurePascal(),
                input.stagePressureDropPascal(), input.specifications(), draws, steam, pas, input.columnDiameterMetres());
    }

    record Run(String path, int newton, double closure, long checkpoints, double seconds, String status) {}

    static long[] counter = {0};

    static Run run(Supplier<V3ColumnOutcome> solve) {
        counter[0] = 0;
        long t0 = System.nanoTime();
        V3ColumnOutcome outcome = solve.get();
        double s = (System.nanoTime() - t0) / 1e9;
        if (outcome instanceof V3ColumnOutcome.Success ok)
            return new Run(ok.diagnostics().solvePath(), ok.diagnostics().newtonIterations(), ok.result().closureTolerance(), counter[0], s, "SUCCESS");
        var f = (V3ColumnOutcome.Failure) outcome;
        return new Run(f.diagnostics().solvePath(), f.diagnostics().newtonIterations(), Double.NaN, counter[0], s, "FAILURE " + f.code());
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        var lines = new ArrayList<String>();
        double screen = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        for (String name : new String[] {"medium", "literature"}) {
            var c = of(name);
            V3ColumnInput target = scaled(c.input(), 1.02);
            var base = (V3ColumnOutcome.Success) ColumnUnit.solve(c.input(), null, c.catalog(), () -> counter[0]++);
            V3NeuralSeed seed = base.result().warmSeed().orElseThrow();
            double water = 0;
            for (double w : seed.freeWater()) water += Math.abs(w);
            int wet = 0;
            for (boolean b : seed.wetTrays()) wet += b ? 1 : 0;
            lines.add(String.format(Locale.ROOT, "%s: cold base path %s n=%d; seed free water sum %.3g, wet trays %d", name,
                    base.diagnostics().solvePath(), base.diagnostics().newtonIterations(), water, wet));
            for (int round = 0; round < 4; round++) {
                Run unit = run(() -> ColumnUnit.solve(target, seed, c.catalog(), () -> counter[0]++));
                Run seeded = run(() -> MaterialRuntime.with(c.catalog(), target.packageId(), () -> V3ColumnCalculator.calculateWithStrategy(target,
                        () -> counter[0]++, V3SolveStrategy.AGGRESSIVE, seed, screen)));
                Run unseeded = run(() -> MaterialRuntime.with(c.catalog(), target.packageId(), () -> V3ColumnCalculator.calculateWithStrategy(target,
                        () -> counter[0]++, V3SolveStrategy.AGGRESSIVE, null, screen)));
                Run learned = run(() -> ColumnUnit.solve(target, null, c.catalog(), () -> counter[0]++));
                if (round < 3) continue; // rounds 0-2 warm the JIT
                for (var e : new Object[][] {{"ColumnUnit.solve(seed)", unit}, {"strategy AGGRESSIVE + seed", seeded}, {"strategy AGGRESSIVE, no seed", unseeded}, {"ColumnUnit.solve(no seed)", learned}}) {
                    Run r = (Run) e[1];
                    lines.add(String.format(Locale.ROOT, "  %-30s %s path %s, Newton %d, closure %.0e, checkpoints %d, %.3f s", e[0], r.status(), r.path(), r.newton(), r.closure(), r.checkpoints(), r.seconds()));
                }
            }
        }
        lines.forEach(System.out::println);
        Files.write(out.resolve("warm-probe.txt"), lines);
        System.exit(0);
    }
}
