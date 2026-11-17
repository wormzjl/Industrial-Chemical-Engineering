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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The review plant's column (the network's default column template at 1/16, as {@link ReviewPlantProbe} builds it) solved
 * directly by the V3 calculator with 0, 8 % and 26 % of its decanted water returned with the reflux (batch
 * 2026-11-17-three-phase-separator, {@code REFLUX_WATER_REVIEW.md}). Each case is a cold solve. Prints the condenser duty,
 * the top temperatures, the products, tray one's free water and the solver's counters; no time.
 */
public final class RefluxWaterProbe {
    private static final double SCALE = 1 / 16.0;

    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String network = FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog, network, () -> {
            run(catalog);
            return null;
        });
    }

    private static void run(MaterialCatalog catalog) {
        var input = scaled(V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog), SCALE);
        double feed = 0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) feed += flow;
        double steam = input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        System.out.printf(Locale.ROOT, "feed %.2f mol/s, top %.0f kPa, steam %.2f mol/s, %d trays%n", feed, input.topPressurePascal() / 1e3,
                steam, input.stageCount());
        Double decanted = null;
        for (double fraction : new double[] {0.0, 0.08, 0.26}) {
            double returned = decanted == null ? 0.0 : fraction * decanted;
            var trial = input.withRefluxFreeWater(returned);
            AtomicLong polls = new AtomicLong();
            AtomicReference<V3NeuralSeed> profile = new AtomicReference<>();
            V3ColumnOutcome outcome = V3ColumnCalculator.calculateWithAcceptedProfile(trial, polls::incrementAndGet, profile::set);
            if (!(outcome instanceof V3ColumnOutcome.Success success)) {
                var failure = (V3ColumnOutcome.Failure) outcome;
                System.out.printf(Locale.ROOT, "%2.0f %%: R_w %.3f mol/s FAILED %s; newton %d, polls %d; path %s; events %s%n", 100 * fraction,
                        returned, failure.code(), failure.diagnostics().newtonIterations(), polls.get(), failure.diagnostics().solvePath(),
                        failure.diagnostics().events());
                continue;
            }
            var result = success.result();
            var ledger = result.dutyLedger().orElseThrow();
            double water = flow(success, "free_water");
            if (decanted == null) decanted = water;
            double[] t = profile.get().temperatures();
            System.out.printf(Locale.ROOT, "%2.0f %%: R_w %.3f mol/s; condenser %.1f kW; drum %.2f K, tray 1 %.2f K, tray 2 %.2f K; tray 1 wet %b,"
                            + " carries %.3f mol/s; free water product %.3f mol/s (%.3f m3/h); distillate %.3f mol/s; overhead vapour %.3f mol/s;"
                            + " bottoms %.3f mol/s; newton %d, residual evaluations %d, linear solves %d, polls %d; path %s%n",
                    100 * fraction, returned, ledger.condenserWatts() / 1e3, t[0], t[1], t[2], profile.get().wetTrays()[1], profile.get().freeWater()[1],
                    water, water * 0.018015 / 1000 * 3600 / 0.985, flow(success, "distillate_liquid"), flow(success, "overhead_vapor"),
                    flow(success, "bottoms_liquid"), success.diagnostics().newtonIterations(), success.diagnostics().residualEvaluations(),
                    success.diagnostics().linearSolves(), polls.get(), success.diagnostics().solvePath());
            for (V3ColumnStreamProperties stream : result.streams()) {
                if (stream.streamId().startsWith("side_")) {
                    System.out.printf(Locale.ROOT, "      %s %.3f mol/s%n", stream.streamId(), stream.molarFlowMolPerSecond());
                }
            }
            System.out.println("      " + result.acceptanceAudit().advisoryEvidence());
        }
    }

    private static double flow(V3ColumnOutcome.Success success, String id) {
        return success.result().streams().stream().filter(s -> s.streamId().equals(id)).mapToDouble(V3ColumnStreamProperties::molarFlowMolPerSecond).sum();
    }

    /** {@code ReviewPlantProbe.scaled}: the design at {@code scale} of its feed, draws, steam and duties, its area in step. */
    private static V3ColumnInput scaled(V3ColumnInput in, double scale) {
        double[] feed = in.feedComponentMolarFlowsMolPerSecond().clone();
        for (int c = 0; c < feed.length; c++) feed[c] *= scale;
        var draws = in.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * scale)).toList();
        var steam = in.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * scale, s.temperatureKelvin())).toList();
        var specifications = in.specifications().stream().map(sp -> sp instanceof V3ColumnSpecification.ReboilerDuty r ? (V3ColumnSpecification) new V3ColumnSpecification.ReboilerDuty(r.watts() * scale) : sp).toList();
        var pumparounds = in.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * scale, p.split())).toList();
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), feed, in.feedTemperatureKelvin(), in.stageCount(), in.feedStageNumber(),
                in.topPressurePascal(), in.stagePressureDropPascal(), specifications, draws, steam, pumparounds, in.columnDiameterMetres() * Math.sqrt(scale));
    }
}
