package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;

/** Shared fixtures of the V3 column tests. */
public final class V3TestInputs {
    /** The learned route at the shipped walls (16 iterations, two seconds) and the qualified progress rule. */
    static final V3InitializationOptions LEARNED = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
            V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);

    /** The registered TJL19 DWSIM package of the literature CDU preset. */
    public static final String LITERATURE_PACKAGE = "icheme:tjl19_dwsim";

    private V3TestInputs() {}

    /**
     * Original methane-free reference: the Ledezma-Martinez (2019) no-preflash atmospheric column (preset
     * {@code literature_tia_juana}). Forty trays plus the steam-stripped bottom stage, three direct side products at
     * stages 10/18/28 (491/515/165 kmol/h), each paired with a pumparound cooler drawn at the draw stage and returned
     * two stages above at the published 12.84/17.89/11.20 MW, 1,200 kmol/h of stripping steam at the bottom,
     * condenser 59 C, reflux ratio 4.17, no external reboiler.
     *
     * <p>This reconstruction carries no side-stripper reboiler heat (18.1 MW in the source), so its top is about
     * 10 C colder than the source and tray 1 sits below the water dew point of its steam-laden overhead; the
     * condition is a result warning, not a rejection.</p>
     */
    public static V3ColumnInput literatureCduInput() {
        var catalog = com.wormzjl.icheme.science.material.MaterialRuntime.current();
        return catalog.presets().column("literature_tia_juana").input(catalog);
    }

    /** The methane-enriched Tia Juana CDU preset ({@code tia_juana}). */
    public static V3ColumnInput methaneCduInput() {
        var catalog = com.wormzjl.icheme.science.material.MaterialRuntime.current();
        return catalog.presets().column("tia_juana").input(catalog);
    }

    /**
     * A crude as a player builds it (process simulator plan, section 3.7): its stream template's mixture, flow and
     * temperature under its tower template. {@code name} names both files; the assay is the crude's where it has its own.
     */
    public static V3ColumnInput crude(com.wormzjl.icheme.science.material.MaterialCatalog catalog, String name) {
        var base = catalog.presets().column("tia_juana").input(catalog);
        var stream = bundled("assets/icheme/mixturepreset/" + name + ".json");
        double rate = stream.get("flow_kmol_h").getAsDouble();
        var tower = V3ColumnTemplate.read(bundled("assets/icheme/columnpreset/" + name + ".json")).scaledTo(rate);
        var ids = base.componentBasis().componentIds();
        double[] feed = new double[ids.size()];
        double total = 0;
        for (var row : stream.getAsJsonArray("components")) {
            double amount = row.getAsJsonObject().get("amount").getAsDouble();
            feed[ids.indexOf(row.getAsJsonObject().get("id").getAsString())] = amount;
            total += amount;
        }
        for (int i = 0; i < feed.length; i++) feed[i] *= rate / 3.6 / total;
        String assay = catalog.requirePackage(base.packageId()).assays().containsKey("icheme:" + name) ? "icheme:" + name : base.assayId();
        return new V3ColumnInput(1, base.packageId(), assay, base.componentBasis(), feed, stream.get("temperature_kelvin").getAsDouble(),
                tower.trays() - 2, tower.feedTray() - 1, tower.topPressure() * 1e5, base.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(tower.topTemperature()),
                        new V3ColumnSpecification.OrganicRefluxRatio(tower.refluxRatio()), new V3ColumnSpecification.ReboilerDuty(tower.reboilerDuty() * 1e6)),
                tower.draws().stream().map(d -> new V3SideDrawSpec(d.tray() - 1, d.flow() / 3.6)).toList(),
                tower.steam().stream().map(s -> new V3SteamFeedSpec(s.tray() - 1, s.flow() / 3.6, s.temperature())).toList(),
                tower.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray() - 1, p.drawTray() - 1, -p.cooling() * 1e6, p.split())).toList(),
                tower.diameter());
    }

    private static com.google.gson.JsonObject bundled(String path) {
        try (var stream = V3TestInputs.class.getClassLoader().getResourceAsStream(path)) {
            return com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The unique authored column preset of a package. */
    static com.wormzjl.icheme.science.material.MaterialPresets.Column packagePreset(
            com.wormzjl.icheme.science.material.MaterialCatalog catalog, String packageId) {
        catalog.requirePackage(packageId);
        var choices = catalog.presets().columns().values().stream()
                .filter(c -> c.descriptor().packageId().equals(packageId)).toList();
        if (choices.size() != 1) throw new IllegalArgumentException("Select an explicit column preset for " + packageId);
        return choices.getFirst();
    }

    /** A dry request: no side draws, steam, pumparounds or tray hydraulics (prescribed uniform drop). */
    public static V3ColumnInput dry(int schemaVersion, String packageId, String assayId, V3ComponentBasis componentBasis,
            double[] feedComponentMolarFlowsMolPerSecond, double feedTemperatureKelvin, int stageCount,
            int feedStageNumber, double topPressurePascal, double stagePressureDropPascal,
            List<V3ColumnSpecification> specifications) {
        return new V3ColumnInput(schemaVersion, packageId, assayId, componentBasis, feedComponentMolarFlowsMolPerSecond,
                feedTemperatureKelvin, stageCount, feedStageNumber, topPressurePascal, stagePressureDropPascal,
                specifications, List.of(), List.of(), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    /**
     * The default classical request: unbounded control, no stage-trace cutoff, the frozen closure and the
     * calibrated liquid-supply screen.
     */
    public static V3ColumnOutcome calculate(V3ColumnInput input) {
        return V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0.0, 0.0,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
    }

    /**
     * The registered cdu17 PC03/PC10 binary (50 mol/s each) at 550 K: two interior trays, feed on tray 1,
     * 250 kPa top, 750 Pa per tray, a 300 K condenser, reflux ratio 2 and no reboiler duty.
     */
    public static V3ColumnInput cdu17Binary(String assayId) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[6] = 50.0; // PC03
        feed[13] = 50.0; // PC10
        return dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), assayId, thermo.componentBasis(),
                feed, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }

    /** A live-boilup binary whose interior phases pass the independent identity check. */
    public static V3ColumnInput cdu17BoilingBinary(String assayId) {
        var original = cdu17Binary(assayId);
        return dry(original.schemaVersion(), original.packageId(), assayId, original.componentBasis(),
                original.feedComponentMolarFlowsMolPerSecond(), 600, 2, 1, 250000, 750, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300),
                        new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(500000)));
    }

    /** An accepted certificate without a state: no streams, duty ledger, tray hydraulics or inspection. */
    static V3ColumnResult stateless(V3ColumnProblem problem, V3InputDigest digest, V3AcceptanceAudit audit,
            V3ConvergenceEvidence evidence) {
        return new V3ColumnResult(problem, digest, audit, evidence, List.of(),
                V3ColumnCalculator.formulationRevision(problem.input(), problem.truncationSupport().cutoffMoleFraction()),
                null, null, null);
    }
}
