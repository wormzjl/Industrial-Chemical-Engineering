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
