import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnPresets;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The network's default column solved directly by the V3 calculator at falling feed temperatures (batch
 * 2026-11-17-column-cold-feed, {@code COLD_FEED_REVIEW.md}): what the tray solver does with a feed that makes little or no
 * vapour. Three forms of each case: the design as it is (draws, pumparounds and reboiler at their design ratios); the draws
 * and pumparounds at a share of the design ({@code -Dshare}, default 0.01; {@code -DdrawShare} gives the draws their own
 * share, so the pumparound duties alone can be cut); neither draws nor pumparounds (the lists empty).
 * {@code -Diterate=<relaxation>} runs the duty loop of the review's section 9 instead: each pumparound's duty is what a
 * cooler of fixed conductance exchanges at the solved draw tray temperature, from {@code -Dstart} of the design (default
 * 0.01), {@code -Dsteps} solves (default 8), at the first of the temperatures.
 * Each solve is cold. Prints the outcome, the products with their temperatures, the condenser duty and the solver's
 * counters; no time.
 */
public final class ColdFeedProbe {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String network = FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog, network, () -> {
            run(catalog);
            return null;
        });
    }

    private static void run(MaterialCatalog catalog) {
        var design = V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog);
        double feed = 0;
        for (double flow : design.feedComponentMolarFlowsMolPerSecond()) feed += flow;
        double steam = design.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        System.out.printf(Locale.ROOT, "feed %.1f mol/s at %.1f K, top %.0f kPa, steam %.1f mol/s at %s K, %d trays, feed stage %d, specs %s%n", feed,
                design.feedTemperatureKelvin(), design.topPressurePascal() / 1e3, steam,
                design.steamFeeds().stream().map(s -> String.format(Locale.ROOT, "%.0f", s.temperatureKelvin())).toList(), design.stageCount(),
                design.feedStageNumber(), design.specifications());
        double share = Double.parseDouble(System.getProperty("share", "0.01"));
        double drawShare = Double.parseDouble(System.getProperty("drawShare", Double.toString(share)));
        String only = System.getProperty("temperatures", "638.15,600,550,500,450,400,350,300");
        if (System.getProperty("iterate") != null) {
            iterate(design, Double.parseDouble(only.split(",")[0].trim()), Double.parseDouble(System.getProperty("iterate")),
                    Double.parseDouble(System.getProperty("start", "0.01")), Integer.getInteger("steps", 8));
            return;
        }
        for (String text : only.split(",")) {
            double t = Double.parseDouble(text.trim());
            if (drawShare != share) {
                solve(String.format(Locale.ROOT, "pa x%.3f draws x%.3f ", share, drawShare), at(design, t, share, drawShare, false));
                continue;
            }
            solve("design ", at(design, t, 1, 1, false));
            solve(String.format(Locale.ROOT, "x%.3f ", share), at(design, t, share, share, false));
            solve("bare   ", at(design, t, 0, 0, true));
        }
    }

    /**
     * The duty loop: Q_j(n+1) = Q_j(n) + relaxation (E_j(T_draw_j(Q(n))) - Q_j(n)), E_j(T) = k_j (T - cold_j), k_j from the
     * design point (the design duty at the design draw temperature), cold_j the cooler's cold inlet (-Dcold, K, one a pumparound).
     */
    private static void iterate(V3ColumnInput design, double feedTemperature, double relaxation, double start, int steps) {
        int count = design.pumparounds().size();
        double[] cold = new double[count];
        String[] given = System.getProperty("cold", "300,340,400").split(",");
        for (int j = 0; j < count; j++) cold[j] = Double.parseDouble(given[Math.min(j, given.length - 1)].trim());
        double[] designDraw = draws(design, design.feedTemperatureKelvin(), null);
        if (designDraw == null) throw new IllegalStateException("The design does not solve");
        double[] k = new double[count], duty = new double[count];
        for (int j = 0; j < count; j++) {
            k[j] = design.pumparounds().get(j).dutyWatts() / (designDraw[j] - cold[j]);
            duty[j] = design.pumparounds().get(j).dutyWatts() * start;
        }
        System.out.printf(Locale.ROOT, "duty loop at %.1f K, relaxation %.2f; design draws %s K, cold inlets %s K, k %s kW/K%n", feedTemperature, relaxation,
                java.util.Arrays.toString(round(designDraw, 1)), java.util.Arrays.toString(cold), java.util.Arrays.toString(round(scale(k, -1e-3), 1)));
        for (int n = 0; n < steps; n++) {
            double[] draw = draws(design, feedTemperature, duty);
            if (draw == null) {
                System.out.printf(Locale.ROOT, "  step %d: duties %s MW: FAILED%n", n, java.util.Arrays.toString(round(scale(duty, -1e-6), 2)));
                return;
            }
            double[] exchanged = new double[count];
            for (int j = 0; j < count; j++) exchanged[j] = Math.min(-1, k[j] * (draw[j] - cold[j]));
            System.out.printf(Locale.ROOT, "  step %d: solved at %s MW; draws %s K; exchanged %s MW%n", n, java.util.Arrays.toString(round(scale(duty, -1e-6), 2)),
                    java.util.Arrays.toString(round(draw, 1)), java.util.Arrays.toString(round(scale(exchanged, -1e-6), 2)));
            for (int j = 0; j < count; j++) duty[j] += relaxation * (exchanged[j] - duty[j]);
        }
    }

    /** The draw tray temperatures of {@code design} at feed temperature {@code t} with the pumparound duties {@code duty} (W; null: the design's), or null where it fails. */
    private static double[] draws(V3ColumnInput design, double t, double[] duty) {
        var pumparounds = new java.util.ArrayList<V3PumparoundSpec>();
        for (int j = 0; j < design.pumparounds().size(); j++) {
            var p = design.pumparounds().get(j);
            pumparounds.add(new V3PumparoundSpec(p.returnTray(), p.drawTray(), duty == null ? p.dutyWatts() : duty[j], p.split()));
        }
        var input = new V3ColumnInput(design.schemaVersion(), design.packageId(), design.assayId(), design.componentBasis(), design.feedComponentMolarFlowsMolPerSecond(), t,
                design.stageCount(), design.feedStageNumber(), design.topPressurePascal(), design.stagePressureDropPascal(), design.specifications(), design.sideDraws(),
                design.steamFeeds(), pumparounds, design.columnDiameterMetres());
        AtomicReference<V3NeuralSeed> profile = new AtomicReference<>();
        V3ColumnOutcome outcome;
        try {
            outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> { }, profile::set);
        } catch (RuntimeException thrown) {
            System.out.println("  threw " + thrown);
            return null;
        }
        if (!(outcome instanceof V3ColumnOutcome.Success)) {
            System.out.println("  " + ((V3ColumnOutcome.Failure) outcome).summary());
            return null;
        }
        double[] temperatures = profile.get().temperatures(), out = new double[pumparounds.size()];
        for (int j = 0; j < out.length; j++) out[j] = temperatures[pumparounds.get(j).drawTray()];
        return out;
    }

    private static double[] scale(double[] values, double factor) {
        double[] out = values.clone();
        for (int i = 0; i < out.length; i++) out[i] *= factor;
        return out;
    }

    private static double[] round(double[] values, int digits) {
        double[] out = values.clone();
        double p = Math.pow(10, digits);
        for (int i = 0; i < out.length; i++) out[i] = Math.round(out[i] * p) / p;
        return out;
    }

    private static void solve(String form, V3ColumnInput input) {
        AtomicLong polls = new AtomicLong();
        AtomicReference<V3NeuralSeed> profile = new AtomicReference<>();
        V3ColumnOutcome outcome;
        try {
            outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, polls::incrementAndGet, profile::set);
        } catch (RuntimeException thrown) {
            System.out.printf(Locale.ROOT, "%5.1f K %s THREW %s%n", input.feedTemperatureKelvin(), form, thrown);
            return;
        }
        if (!(outcome instanceof V3ColumnOutcome.Success success)) {
            var failure = (V3ColumnOutcome.Failure) outcome;
            System.out.printf(Locale.ROOT, "%5.1f K %s FAILED %s: %s; newton %d, polls %d; path %s%n", input.feedTemperatureKelvin(), form, failure.code(),
                    failure.summary(), failure.diagnostics().newtonIterations(), polls.get(), failure.diagnostics().solvePath());
            if (Boolean.getBoolean("events")) failure.diagnostics().events().forEach(event -> System.out.println("      event: " + event));
            return;
        }
        var result = success.result();
        var ledger = result.dutyLedger().orElseThrow();
        double[] t = profile.get().temperatures();
        var text = new StringBuilder();
        for (V3ColumnStreamProperties stream : result.streams())
            if (stream.molarFlowMolPerSecond() > 1e-9)
                text.append(String.format(Locale.ROOT, " %s %.1f mol/s %.1f K;", stream.streamId(), stream.molarFlowMolPerSecond(), stream.temperatureKelvin()));
        System.out.printf(Locale.ROOT, "%5.1f K %s ok: condenser %.0f kW; top tray %.1f K, bottom %.1f K;%s newton %d, polls %d; path %s%n",
                input.feedTemperatureKelvin(), form, ledger.condenserWatts() / 1e3, t[1], t[t.length - 1], text, success.diagnostics().newtonIterations(),
                polls.get(), success.diagnostics().solvePath());
        if (Boolean.getBoolean("advisories")) System.out.println("      " + result.acceptanceAudit().advisoryEvidence());
    }

    /** The design at feed temperature {@code t}, its pumparound duties at {@code share} and its draws at {@code drawShare} of the design, or without them. */
    private static V3ColumnInput at(V3ColumnInput in, double t, double share, double drawShare, boolean bare) {
        List<V3SideDrawSpec> draws = bare ? List.of() : in.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * drawShare)).toList();
        List<V3PumparoundSpec> pumparounds = bare ? List.of()
                : in.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * share, p.split())).toList();
        List<V3ColumnSpecification> specifications = in.specifications();
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), in.feedComponentMolarFlowsMolPerSecond(), t, in.stageCount(),
                in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), specifications, draws, in.steamFeeds(), pumparounds,
                in.columnDiameterMetres());
    }
}
