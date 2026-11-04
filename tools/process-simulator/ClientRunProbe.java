import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a client can run the simulator's work (batch 2026-11-02-process-simulator, plan section 5). A plain JVM with no
 * Minecraft classes on its class path takes the catalog from the mod's own resources, as a client on a dedicated server
 * does, and runs the default column preset, a warm column re-solve at another feed and the network thermo's flashes.
 * Run from the worktree root after {@code ./gradlew compileJava processResources}:
 * <pre>java -Dfile.encoding=UTF-8 -cp "build/classes/java/main;build/resources/main;&lt;gson jar&gt;" tools/process-simulator/ClientRunProbe.java</pre>
 */
public final class ClientRunProbe {
    private static long mark;

    private static void lap(String what) {
        long now = System.nanoTime();
        System.out.printf(Locale.ROOT, "%-46s %9.1f ms%n", what, (now - mark) / 1e6);
        mark = System.nanoTime();
    }

    public static void main(String[] args) {
        mark = System.nanoTime();
        var catalog = MaterialCatalog.bundled();
        lap("catalog from the mod's resources");
        var input = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        var options = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);
        var model = V3NeuralRegistry.bundled().bind(catalog, input);
        lap("neural model bound");
        double ratio = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        V3ColumnResult first = null;
        for (int i = 1; i <= 3; i++) {
            var outcome = MaterialRuntime.with(catalog, input.packageId(), () -> V3ColumnCalculator.calculate(input, () -> {}, 0, 0, options, model, ratio));
            lap("column preset, learned seed, run " + i + (outcome.isSuccess() ? "" : " FAILED"));
            if (outcome instanceof V3ColumnOutcome.Success s) first = s.result();
            else System.out.println(((V3ColumnOutcome.Failure) outcome).summary());
        }
        if (first == null) return;
        for (var s : first.streams())
            System.out.printf(Locale.ROOT, "  %-24s %-8s %9.1f kmol/h %8.2f t/h %7.1f C %6.3f bar vf %.3f%n", s.displayName(), s.phase(), s.molarFlowMolPerSecond() * 3.6,
                    s.massFlowKgPerSecond() * 3.6, s.temperatureKelvin() - 273.15, s.pressurePascal() / 1e5, s.vaporMoleFraction());
        first.dutyLedger().ifPresent(l -> System.out.println("  " + l));
        first.trayHydraulics().ifPresent(h -> System.out.println("  " + h));
        // A recycle's pass: the same column at a feed 1 % higher, from the last accepted state.
        var seed = first.warmSeed().orElse(null);
        System.out.println("warm seed present: " + (seed != null));
        double[] moved = input.feedComponentMolarFlowsMolPerSecond().clone();
        for (int i = 0; i < moved.length; i++) moved[i] *= 1.01;
        var shifted = new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(), moved, input.feedTemperatureKelvin() + 2, input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(), input.specifications(), input.sideDraws(), input.steamFeeds(), input.pumparounds(), input.columnDiameterMetres());
        mark = System.nanoTime();
        for (int i = 1; i <= 3; i++) {
            var warm = new AtomicReference<V3NeuralSeed>(seed);
            var outcome = MaterialRuntime.with(catalog, input.packageId(), () -> V3ColumnCalculator.calculateWithStrategy(shifted, () -> {}, V3SolveStrategy.AGGRESSIVE, warm.get(), ratio));
            lap("column, feed +1 %, +2 K, warm, run " + i + (outcome.isSuccess() ? "" : " FAILED: " + ((V3ColumnOutcome.Failure) outcome).summary()));
        }
        // The stream thermo on the network package, as the world's generator and tanks use it.
        String network = catalog.presets().network().packageId();
        var thermo = MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
        lap("network thermo for " + network);
        var names = thermo.components();
        var ids = input.componentBasis().componentIds();
        var feed = input.feedComponentMolarFlowsMolPerSecond();
        double[] overall = new double[names.size()];
        for (int i = 0; i < ids.size(); i++) overall[names.indexOf(ids.get(i))] = feed[i];
        System.out.println("axis: " + names.size() + " components, last " + names.getLast() + "; " + Arrays.stream(overall).sum() * 3.6 + " kmol/h");
        FluidThermodynamics.State[] kept = new FluidThermodynamics.State[3];
        int rounds = 200;
        for (int pass = 0; pass < 2; pass++) {
            mark = System.nanoTime();
            for (int i = 0; i < rounds; i++) kept[0] = MaterialRuntime.with(catalog, network, () -> thermo.flashTP(638.15, 5e5, overall, () -> {}));
            lap(rounds + " TP flashes, 365 C, 5 bar, pass " + (pass + 1));
            double h = kept[0].enthalpy();
            mark = System.nanoTime();
            for (int i = 0; i < rounds; i++) kept[1] = MaterialRuntime.with(catalog, network, () -> thermo.equilibriumPH(630, 2.5e5, h, overall, () -> {}));
            lap(rounds + " PH flashes, valve to 2.5 bar, pass " + (pass + 1));
        }
        var cold = MaterialRuntime.with(catalog, network, () -> thermo.flashTP(298.15, 8e5, overall, () -> {}));
        var hot = kept[0];
        var valve = kept[1];
        System.out.printf(Locale.ROOT, "crude 25 C 8 bar: mass %.1f t/h, volume %.1f m3/h, density %.1f kg/m3, mean weight %.1f%n", cold.mass() * 3.6, cold.volume() * 3600, cold.mass() / cold.volume(),
                cold.mass() / Arrays.stream(overall).sum() * 1000);
        double vapour = Arrays.stream(hot.vaporView()).sum() + hot.waterVapor(), all = Arrays.stream(overall).sum();
        System.out.printf(Locale.ROOT, "crude 365 C 5 bar: vapour %.1f mole %%, %.1f kg/m3, duty from 25 C %.1f MW%n", 100 * vapour / all, hot.mass() / hot.volume(), (hot.enthalpy() - cold.enthalpy()) / 1e6);
        if (valve != null) {
            double v2 = Arrays.stream(valve.vaporView()).sum() + valve.waterVapor();
            System.out.printf(Locale.ROOT, "after a valve to 2.5 bar: %.2f C, vapour %.1f mole %%%n", valve.temperature() - 273.15, 100 * v2 / all);
        }
        heatGap(thermo, catalog, network, first, overall, input);
    }

    /**
     * The column's energy balance read in the stream thermo: its products as the column published them (T, P, composition),
     * flashed on the network package, against the feed, the steam and the column's own duties. What is left is the gap two
     * thermo stacks leave at a column's boundary, which the flowsheet's heat balance has to book.
     */
    private static void heatGap(FluidThermodynamics thermo, MaterialCatalog catalog, String network, V3ColumnResult result, double[] feed, V3ColumnInput input) {
        var names = thermo.components();
        var ledger = result.dutyLedger().orElseThrow();
        double trayPressure = result.streams().stream().filter(s -> s.displayName().startsWith("Bottoms")).findFirst().orElseThrow().pressurePascal();
        var in = MaterialRuntime.with(catalog, network, () -> thermo.flashTP(input.feedTemperatureKelvin(), trayPressure, feed, () -> {}));
        var steamSpec = input.steamFeeds().getFirst();
        double[] steam = new double[names.size()];
        steam[names.indexOf("Water")] = steamSpec.molarFlowMolPerSecond();
        var steamState = MaterialRuntime.with(catalog, network, () -> thermo.flashTP(steamSpec.temperatureKelvin(), trayPressure, steam, () -> {}));
        System.out.printf(Locale.ROOT, "feed enthalpy: column %.3f MW, streams %.3f MW; steam: column %.3f MW, streams %.3f MW%n", ledger.feedEnthalpyWatts() / 1e6, in.enthalpy() / 1e6,
                ledger.steamEnthalpyWatts() / 1e6, steamState.enthalpy() / 1e6);
        double out = 0;
        var rows = new ArrayList<String>();
        for (var s : result.streams()) {
            double[] amounts = new double[names.size()];
            for (var c : s.moleFractions()) amounts[names.indexOf(c.componentId())] += c.moleFraction() * s.molarFlowMolPerSecond();
            var state = MaterialRuntime.with(catalog, network, () -> thermo.flashTP(s.temperatureKelvin(), s.pressurePascal(), amounts, () -> {}));
            out += state.enthalpy();
            double vapour = Arrays.stream(state.vaporView()).sum() + state.waterVapor(), total = Arrays.stream(amounts).sum();
            rows.add(String.format(Locale.ROOT, "  %-24s %8.3f MW, column says vapour %.3f, streams %.3f, %.1f kg/m3", s.displayName(), state.enthalpy() / 1e6, s.vaporMoleFraction(), vapour / total, state.mass() / state.volume()));
        }
        rows.forEach(System.out::println);
        double duties = ledger.condenserWatts() + ledger.reboilerWatts() + ledger.stageHeatTotalWatts();
        double gap = in.enthalpy() + steamState.enthalpy() + duties - out;
        System.out.printf(Locale.ROOT, "column duties %.3f MW; in the stream thermo: in %.3f, out %.3f, gap %.3f MW, %.3f %% of the duties%n", duties / 1e6, (in.enthalpy() + steamState.enthalpy()) / 1e6, out / 1e6, gap / 1e6,
                100 * Math.abs(gap / duties));
    }
}
