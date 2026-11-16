package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;

/** Shared fixtures of the V3 column tests. */
public final class V3TestInputs {
    /** The learned route at the shipped walls (16 iterations, the default poll budget) and the qualified progress rule. */
    static final V3InitializationOptions LEARNED = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
            V3InitializationOptions.WetStart.AUTO, 16, V3InitializationOptions.DEFAULT_BUDGET_POLLS, V3InitializationOptions.Correction.PROGRESS);

    /** The registered TJL19 DWSIM package of the literature CDU preset. */
    public static final String LITERATURE_PACKAGE = "icheme:tjl19_dwsim";

    /** The package of the game's medium crude preset ({@code medium_crude}). */
    public static final String MEDIUM_CRUDE_PACKAGE = "icheme:tjl20_methane";
    /** The assay of the game's medium crude preset. */
    public static final String MEDIUM_CRUDE_ASSAY = "icheme:medium_crude";

    private V3TestInputs() {}

    /**
     * The game's default column preset {@code medium_crude} on the current catalog (the bundled one where the current
     * catalog lacks it): 40 stages, feed stage 37, 250 kPa, draws on trays 10, 18 and 28 each under its pumparound, steam at
     * stage 41, condenser 332.15 K, reflux ratio 4.17.
     */
    public static V3ColumnInput mediumCrudeInput() {
        var catalog = mediumCrudeCatalog();
        return V3ColumnPresets.input(catalog.presets().column("medium_crude"), catalog);
    }

    /**
     * A dry three-draw crude column fed with the medium crude: 29 stages, feed stage 24, 150 kPa top, 750 Pa per tray,
     * condenser 400 K, reflux ratio 2, reboiler 8 MW, draws on trays 13, 17 and 22 (92.3, 131.85 and 32.96 kmol/h), the
     * crude at 2,610.7 kmol/h and 638.15 K. No steam and no pumparounds.
     */
    public static V3ColumnInput mediumCrudeDry() {
        V3PengRobinsonThermo thermo = mediumCrudeThermo();
        var crude = thermo.crudeFeed(MEDIUM_CRUDE_ASSAY);
        double[] feed = crude.moleFractions();
        for (int i = 0; i < feed.length; i++) feed[i] *= 2610.7 / 3.6;
        return new V3ColumnInput(1, thermo.packageId(), crude.assayId(), crude.componentBasis(), feed, 638.15, 29, 24, 150000, 750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(400), new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(8e6)),
                List.of(new V3SideDrawSpec(13, 92.3 / 3.6), new V3SideDrawSpec(17, 131.85 / 3.6), new V3SideDrawSpec(22, 32.96 / 3.6)));
    }

    /** The registered thermodynamics of the medium crude's package, on the current catalog (else the bundled one). */
    public static V3PengRobinsonThermo mediumCrudeThermo() {
        var catalog = mediumCrudeCatalog();
        return com.wormzjl.icheme.science.material.MaterialRuntime.with(catalog, MEDIUM_CRUDE_PACKAGE,
                () -> V3PengRobinsonThermo.fromRegisteredPackage(MEDIUM_CRUDE_PACKAGE));
    }

    /** The index of component {@code id} on {@code thermo}'s basis. */
    public static int index(V3PengRobinsonThermo thermo, String id) {
        int i = thermo.componentBasis().componentIds().indexOf(id);
        if (i < 0) throw new IllegalArgumentException(id + " is not on " + thermo.packageId());
        return i;
    }

    private static com.wormzjl.icheme.science.material.MaterialCatalog mediumCrudeCatalog() {
        var catalog = com.wormzjl.icheme.science.material.MaterialRuntime.current();
        return catalog.presets().columns().containsKey("medium_crude") ? catalog : com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
    }

    /**
     * Original methane-free reference: the Ledezma-Martinez (2019) no-preflash atmospheric column (preset
     * {@code literature_tia_juana}). Forty trays plus the steam-stripped bottom stage, three direct side products at
     * stages 10/18/28 (491/515/165 kmol/h), each paired with a pumparound cooler drawn at the draw stage and returned
     * two stages above at the published 12.84/17.89/11.20 MW, 1,200 kmol/h of stripping steam at the bottom,
     * condenser 59 C, reflux ratio 4.17, no external reboiler.
     *
     * <p>This reconstruction carries no side-stripper reboiler heat (18.1 MW in the source), so its top is about
     * 10 C colder than the source and tray 1 sits below the water dew point of its steam-laden overhead; tray 1
     * carries its free water over to the drum (condenser carry-over, E31 of batch 2026-11-04-open-thermo-data).</p>
     */
    public static V3ColumnInput literatureCduInput() {
        var catalog = tiaJuana("literature_tia_juana");
        return V3ColumnPresets.input(catalog.presets().column("literature_tia_juana"), catalog);
    }

    /** The methane-enriched Tia Juana CDU preset ({@code tia_juana}). */
    public static V3ColumnInput methaneCduInput() {
        var catalog = tiaJuana("tia_juana");
        return V3ColumnPresets.input(catalog.presets().column("tia_juana"), catalog);
    }

    /**
     * The catalog that holds a Tia Juana preset: the test-scoped one where it does (a frozen reference re-states it), else
     * the Tia Juana test fixture (O3 of batch 2026-11-04-open-thermo-data: the game ships the generated grades).
     */
    private static com.wormzjl.icheme.science.material.MaterialCatalog tiaJuana(String preset) {
        var catalog = com.wormzjl.icheme.science.material.MaterialRuntime.current();
        return catalog.presets().columns().containsKey(preset) ? catalog : com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
    }

    /**
     * A crude as a player builds it (process simulator plan, section 3.7): its stream template's mixture, flow and
     * temperature under its tower template, on the default preset's package. {@code name} names both files; the assay is the crude's where it has its own.
     */
    public static V3ColumnInput crude(com.wormzjl.icheme.science.material.MaterialCatalog catalog, String name) {
        var base = V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog);
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
     * The registered {@code crude_pc03}/{@code crude_pc10} binary of the medium crude's package (50 mol/s each) at 550 K:
     * two interior trays, feed on tray 1, 250 kPa top, 750 Pa per tray, a 300 K condenser, reflux ratio 2 and no reboiler
     * duty.
     */
    public static V3ColumnInput crudeBinary(String assayId) {
        V3PengRobinsonThermo thermo = mediumCrudeThermo();
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[index(thermo, "crude_pc03")] = 50.0;
        feed[index(thermo, "crude_pc10")] = 50.0;
        return dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), assayId, thermo.componentBasis(),
                feed, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }

    /** Removed with the cdu17 fixture (batch 2026-11-16-cdu17-fixture-removal); kept until its users have moved. */
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
    public static V3ColumnInput crudeBoilingBinary(String assayId) {
        var original = crudeBinary(assayId);
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
                V3ColumnLabels.formulationRevision(problem.input(), problem.truncationSupport().cutoffMoleFraction()),
                null, null, null);
    }
}
