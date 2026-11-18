import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.plant.column.ColumnSolve;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * B1 column scenario (batch 2026-11-13-codebase-review, PROFILE_REVIEW.md). One phase per JVM:
 * <ul>
 * <li>{@code cold:<case>}: {@code ColumnSolve.calculate(input, null, ...)} again and again, the block's cold route (learned start);</li>
 * <li>{@code warm:<case>}: one cold base solve, then {@code ColumnSolve.calculate(input * 1.02, baseSeed, ...)} again and again,
 * the block's warm route after a 2 % feed change (every extensive input times 1.02).</li>
 * </ul>
 * Cases: {@code medium} (the medium_crude stream under its tower template, as V3TestInputs.crude builds it) and
 * {@code literature} (the literature_tia_juana preset). {@code -Dwarm} seconds of warm-up, then {@code -Dmeasure} seconds with a
 * JFR "profile" recording of that window only. Prints and writes {@code <phase>.txt}: the first solve in the fresh JVM, solves in
 * the window, wall and CPU per solve, bytes allocated per solve (all threads), GC count and time in the window.
 * <pre>java -cp "build/classes/java/main;build/resources/main;build/classes/java/test;build/resources/test;gson.jar;merged.jar" tools/codebase-review-bench/ColumnBench.java &lt;phase&gt; &lt;out&gt;</pre>
 */
public final class ColumnBench {
    static final double WARM = Double.parseDouble(System.getProperty("warm", "60")), MEASURE = Double.parseDouble(System.getProperty("measure", "60"));
    static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

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

    static long allocated() {
        long sum = 0;
        for (long id : THREADS.getAllThreadIds()) {
            long b = THREADS.getThreadAllocatedBytes(id);
            if (b > 0) sum += b;
        }
        return sum;
    }

    static long[] gc() {
        long count = 0, millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            millis += Math.max(0, bean.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    static String describe(V3ColumnOutcome outcome) {
        if (outcome instanceof V3ColumnOutcome.Success s) return "SUCCESS";
        if (outcome instanceof V3ColumnOutcome.Failure f) return "FAILURE " + f.code();
        return String.valueOf(outcome);
    }

    public static void main(String[] args) throws Exception {
        String phase = args[0];
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        String[] parts = phase.split(":");
        boolean warm = parts[0].equals("warm");
        Case c = of(parts[1]);
        var lines = new ArrayList<String>();
        lines.add("phase " + phase + " java " + System.getProperty("java.version"));
        lines.add("stages " + c.input().stageCount() + " components " + c.input().componentBasis().componentIds().size() + " draws " + c.input().sideDraws().size()
                + " steam " + c.input().steamFeeds().size() + " pumparounds " + c.input().pumparounds().size());
        long t0 = System.nanoTime(), a0 = allocated();
        V3ColumnOutcome base = ColumnSolve.calculate(c.input(), null, c.catalog(), ColumnUnit.SCREEN, ColumnSolve.ColdStart.LEARNED, () -> {});
        double firstWall = (System.nanoTime() - t0) / 1e9;
        long firstBytes = allocated() - a0;
        lines.add(String.format(Locale.ROOT, "first cold solve in a fresh JVM: %s, %.3f s wall, %.1f MB", describe(base), firstWall, firstBytes / 1e6));
        V3NeuralSeed seed = null;
        V3ColumnInput target = c.input();
        if (warm) {
            if (!(base instanceof V3ColumnOutcome.Success s)) throw new IllegalStateException("cold base failed: " + describe(base));
            seed = s.result().warmSeed().orElseThrow(() -> new IllegalStateException("no warm seed"));
            target = scaled(c.input(), 1.02);
        }
        final V3NeuralSeed fseed = seed;
        final V3ColumnInput ftarget = target;
        // Warm-up window.
        long end = System.nanoTime() + (long) (WARM * 1e9);
        int warmups = 0;
        String status = null;
        while (System.nanoTime() < end || warmups == 0) {
            status = describe(ColumnSolve.calculate(ftarget, fseed, c.catalog(), ColumnUnit.SCREEN, ColumnSolve.ColdStart.LEARNED, () -> {}));
            warmups++;
        }
        lines.add("warm-up solves " + warmups + " last " + status);
        // Measured window, with JFR.
        var recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName(phase);
        recording.start();
        long[] g0 = gc();
        long cpu0 = THREADS.getCurrentThreadCpuTime();
        long b0 = allocated();
        long start = System.nanoTime();
        end = start + (long) (MEASURE * 1e9);
        int solves = 0, successes = 0;
        List<Double> walls = new ArrayList<>();
        while (System.nanoTime() < end || solves == 0) {
            long s0 = System.nanoTime();
            var outcome = ColumnSolve.calculate(ftarget, fseed, c.catalog(), ColumnUnit.SCREEN, ColumnSolve.ColdStart.LEARNED, () -> {});
            walls.add((System.nanoTime() - s0) / 1e9);
            if (outcome instanceof V3ColumnOutcome.Success) successes++;
            status = describe(outcome);
            solves++;
        }
        double window = (System.nanoTime() - start) / 1e9;
        long bytes = allocated() - b0;
        long cpu = THREADS.getCurrentThreadCpuTime() - cpu0;
        long[] g1 = gc();
        recording.stop();
        String file = phase.replace(':', '-');
        recording.dump(out.resolve(file + ".jfr"));
        recording.close();
        walls.sort(Double::compare);
        lines.add(String.format(Locale.ROOT, "window %.2f s, solves %d (success %d, last %s)", window, solves, successes, status));
        lines.add(String.format(Locale.ROOT, "wall per solve: mean %.4f s, median %.4f s, min %.4f s, max %.4f s", window / solves,
                walls.get(walls.size() / 2), walls.getFirst(), walls.getLast()));
        lines.add(String.format(Locale.ROOT, "bench-thread CPU per solve %.4f s", cpu / 1e9 / solves));
        lines.add(String.format(Locale.ROOT, "allocated per solve (all threads) %.1f MB, total %.2f GB, rate %.0f MB/s", bytes / 1e6 / solves, bytes / 1e9, bytes / 1e6 / window));
        lines.add(String.format(Locale.ROOT, "GC in window: %d collections, %d ms (%.2f %% of the window)", g1[0] - g0[0], g1[1] - g0[1], 100.0 * (g1[1] - g0[1]) / 1000 / window));
        lines.forEach(System.out::println);
        Files.write(out.resolve(file + ".txt"), lines);
        System.exit(0);
    }
}
