package com.wormzjl.icheme.science.fluid.thermo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CrystalPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.PhaseState;
import com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences;
import com.wormzjl.icheme.science.thermo.phase.SolidPhaseEvaluator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O11 probe (batch 2026-11-04-open-thermo-data; scratch, not a gate): the CO2-I crystal anchored at its triple point to
 * the crude_pr78 family's CO2 (the tools/compound-import dataset in tmp/open-thermo-o11/import) on the coefficient
 * (fit) ideal gas, against Span and Wagner 1996; the pilot family beside it as the solver's check. Also: the catalog's
 * answer to a refinery package that declares the crystal, and the identity of a crude view before and after CO2 joins.
 */
class O11CrystalAnchorProbe {
    static final String CRYSTAL = "icheme:crystal_carbon_dioxide_i";
    static final String REFINERY = "icheme:refinery";
    static final Path RESOURCES = Path.of("src/main/resources"), IMPORT = Path.of("tmp/open-thermo-o11/import");
    static final List<String> CRUDE = List.of("Nitrogen", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane",
            "crude_pc01", "crude_pc02", "crude_pc03", "crude_pc04", "crude_pc05", "crude_pc06", "crude_pc07", "crude_pc08", "crude_pc09",
            "crude_pc10", "crude_pc11", "crude_pc12");

    static LinkedHashMap<String, String> resources(boolean crystal) throws Exception {
        var map = new LinkedHashMap<String, String>();
        for (var e : JsonParser.parseString(Files.readString(RESOURCES.resolve("materials-index.json"))).getAsJsonArray())
            map.put(e.getAsString(), Files.readString(RESOURCES.resolve(e.getAsString())));
        for (String rel : List.of("properties/chemsep_carbon_dioxide.json", "interactions/dwsim_refinery_pr78_co2.json",
                "transport/chemsep_carbon_dioxide_liquid_reference.json"))
            map.put("data/icheme/materials/" + rel, Files.readString(IMPORT.resolve(rel)));
        String methodKey = "data/icheme/materials/methods/crude_pr78.json";
        var method = JsonParser.parseString(map.get(methodKey)).getAsJsonObject();
        method.getAsJsonArray("interactions").add("icheme:dwsim_refinery_pr78_co2");
        map.put(methodKey, method.toString());
        String packageKey = "data/icheme/materials/packages/refinery.json";
        var pkg = JsonParser.parseString(map.get(packageKey)).getAsJsonObject();
        var co2 = new JsonObject(); co2.addProperty("id", "CarbonDioxide"); co2.addProperty("properties", "icheme:chemsep_carbon_dioxide");
        pkg.getAsJsonArray("components").add(co2);
        if (crystal) { var c = new JsonArray(); c.add(CRYSTAL); pkg.add("crystals", c); }
        map.put(packageKey, pkg.toString());
        return map;
    }

    /** Pure-species coexistence pressure at T: g_a(T, p) = g_b(T, p), Newton in p on dg/dp = v. */
    interface Side { double[] gv(double t, double p); }
    static double coexistence(Side a, Side b, double t, double start) {
        double p = start;
        for (int k = 0; k < 200; k++) {
            double[] x = a.gv(t, p), y = b.gv(t, p);
            double step = (x[0] - y[0]) / (x[1] - y[1]);
            double next = p - step;
            if (!(next > 0) || !Double.isFinite(next)) next = 0.5 * p; else if (next > 2 * p) next = 2 * p;
            if (Math.abs(next / p - 1) < 1e-13) return next;
            p = next;
        }
        throw new IllegalStateException("no coexistence at " + t);
    }

    record Family(String name, CubicPhaseEvaluator fluid, CrystalPhaseEvaluator solid, SolidPhaseEvaluator.TriplePointAnchor anchor) {
        Side fluidSide(Root root) {
            int i = fluid.components().indexOf("CarbonDioxide");
            double[] pure = new double[fluid.componentCount()]; pure[i] = 1;
            var ws = fluid.newWorkspace(); var st = fluid.newState();
            return (t, p) -> { var s = fluid.evaluate(t, p, pure, root, ws, st); return new double[] {s.chemicalPotential(i), s.molarVolume()}; };
        }
        Side solidSide() {
            var st = new PhaseState(1);
            return (t, p) -> { var s = solid.evaluate(t, p, anchor, st); return new double[] {s.molarGibbsEnergy(), s.molarVolume()}; };
        }
        double sublimation(double t) { return coexistence(solidSide(), fluidSide(Root.VAPOR), t, SolidCarbonDioxideReferences.spanWagnerSublimationPressure(Math.min(t, SolidCarbonDioxideReferences.TRIPLE_TEMPERATURE))); }
        double saturation(double t) { return coexistence(fluidSide(Root.LIQUID), fluidSide(Root.VAPOR), t, SolidCarbonDioxideReferences.TRIPLE_PRESSURE); }
        double melting(double t) { return coexistence(solidSide(), fluidSide(Root.LIQUID), t, SolidCarbonDioxideReferences.spanWagnerMeltingPressure(t)); }
        double sublimationEnthalpy(double t) {
            double p = sublimation(t);
            int i = fluid.components().indexOf("CarbonDioxide");
            double[] pure = new double[fluid.componentCount()]; pure[i] = 1;
            double hv = fluid.evaluate(t, p, pure, Root.VAPOR, fluid.newWorkspace(), fluid.newState()).molarEnthalpy();
            return hv - solid.evaluate(t, p, anchor, new PhaseState(1)).molarEnthalpy();
        }
        double vaporizationEnthalpy(double t, double p) {
            int i = fluid.components().indexOf("CarbonDioxide");
            double[] pure = new double[fluid.componentCount()]; pure[i] = 1;
            return fluid.evaluate(t, p, pure, Root.VAPOR, fluid.newWorkspace(), fluid.newState()).molarEnthalpy()
                    - fluid.evaluate(t, p, pure, Root.LIQUID, fluid.newWorkspace(), fluid.newState()).molarEnthalpy();
        }
        /** The model's triple point: sublimation and saturation pressures equal, by bisection in T. */
        double[] triple() {
            double lo = 216.0, hi = 217.2;
            for (int k = 0; k < 80; k++) {
                double mid = 0.5 * (lo + hi);
                if (Math.log(sublimation(mid) / saturation(mid)) < 0) lo = mid; else hi = mid;
            }
            double t = 0.5 * (lo + hi);
            return new double[] {t, saturation(t)};
        }
        /** The sublimation temperature at p, by bisection. */
        double sublimationTemperature(double p) {
            double lo = 180, hi = 210;
            for (int k = 0; k < 80; k++) { double mid = 0.5 * (lo + hi); if (sublimation(mid) < p) lo = mid; else hi = mid; }
            return 0.5 * (lo + hi);
        }
        void report() {
            double sw = SolidCarbonDioxideReferences.spanWagnerSublimationPressure(SolidCarbonDioxideReferences.TRIPLE_TEMPERATURE);
            double[] tp = triple();
            double ps = saturation(SolidCarbonDioxideReferences.TRIPLE_TEMPERATURE);
            System.out.printf("%s: PR78 p_sat(216.592 K) %.1f Pa (%.3f %% vs 517950); dH_vap there %.1f J/mol%n", name, ps, 100 * (ps / 517950 - 1),
                    vaporizationEnthalpy(216.592, ps));
            System.out.printf("%s: model triple point %.4f K (%+.4f K), %.1f Pa (%+.3f %%); SW 3.12 at T_t %.1f Pa%n", name, tp[0], tp[0] - 216.592, tp[1],
                    100 * (tp[1] / 517950 - 1), sw);
            double ts = sublimationTemperature(101325);
            System.out.printf("%s: sublimation at 101325 Pa: %.4f K (%+.4f K vs 194.6855)%n", name, ts, ts - 194.6855);
            double worst = 0, worstAt = 0;
            System.out.println("T K | p_sub Pa | vs SW 3.12 % | as K");
            for (double t : new double[] {150, 160, 170, 180, 185, 190, 194.6855, 200, 205, 210, 214, 216}) {
                double p = sublimation(t), e = p / SolidCarbonDioxideReferences.spanWagnerSublimationPressure(t) - 1;
                double dlnp = (Math.log(SolidCarbonDioxideReferences.spanWagnerSublimationPressure(t + 0.01)) - Math.log(SolidCarbonDioxideReferences.spanWagnerSublimationPressure(t - 0.01))) / 0.02;
                System.out.printf("%9.4f | %12.5e | %8.3f | %8.4f%n", t, p, 100 * e, Math.log(1 + e) / dlnp);
                if (Math.abs(e) > Math.abs(worst)) { worst = e; worstAt = t; }
            }
            System.out.printf("%s: worst sublimation error 150 K..T_t %.3f %% at %.1f K%n", name, 100 * worst, worstAt);
            for (double t : new double[] {195})
                System.out.printf("%s: sublimation enthalpy at %.0f K %.1f J/mol (%.3f %% vs 25.2 kJ/mol)%n", name, t, sublimationEnthalpy(t), 100 * (sublimationEnthalpy(t) / 25200 - 1));
            for (double t : new double[] {216.8, 217.0, 217.5}) {
                double p = melting(t), ref = SolidCarbonDioxideReferences.spanWagnerMeltingPressure(t);
                System.out.printf("%s: melting at %.2f K %.4e Pa (SW %.4e)%n", name, t, p, ref);
            }
        }
    }

    static Family coefficient(MaterialCatalog catalog, String packageId, String name) {
        var model = new HydrocarbonModel(catalog, packageId);
        var translated = model.translated();
        var pkg = catalog.requirePackage(packageId);
        int n = pkg.components().size();
        double[] translations = new double[n];
        List<IdealGasFunction> gas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            translations[i] = translated.translation(i);
            gas.add(new NetworkPhaseEngine.CoefficientIdealGas(pkg.properties().get(i), translated.idealGasMinimumTemperature(i), translated.idealGasMaximumTemperature(i)));
        }
        var fluid = CubicPhaseEvaluator.fromPackage(pkg, pkg.components(), translations, gas);
        int co2 = pkg.components().indexOf("CarbonDioxide");
        System.out.printf("%s: CO2 translation %.6e m3/mol, ideal-gas fit %.3f..%.1f K%n", name, translations[co2],
                translated.idealGasMinimumTemperature(co2), translated.idealGasMaximumTemperature(co2));
        var solid = CrystalPhaseEvaluator.of(catalog.crystals().get(CRYSTAL), gas.get(co2));
        return new Family(name, fluid, solid, solid.anchor(fluid));
    }

    @Test
    void probe() throws Exception {
        // 1. The catalog's answer to the refinery package with the crystal.
        try {
            MaterialCatalog.parse(resources(true));
            System.out.println("refinery with the crystal: loaded");
        } catch (RuntimeException e) {
            System.out.println("refinery with the crystal: refused: " + e.getMessage());
        }
        var catalog = MaterialCatalog.parse(resources(false));
        var bundled = MaterialCatalog.bundled();
        var pkg = catalog.requirePackage(REFINERY);
        System.out.println("refinery with CO2: " + pkg.components().size() + " components; non-zero CO2 pairs:");
        int c = pkg.components().indexOf("CarbonDioxide");
        for (int j = 0; j < pkg.components().size(); j++) if (pkg.interactions().get(c).get(j) != 0)
            System.out.println("  CO2/" + pkg.components().get(j) + " " + pkg.interactions().get(c).get(j));
        // 2. Identity of a crude view and of a light-end view, before and after.
        for (var subset : List.of(CRUDE, List.of("Methane", "Ethane", "HydrogenSulfide", "Benzene"))) {
            var before = bundled.restrict(REFINERY, subset); var after = catalog.restrict(REFINERY, subset);
            System.out.println("view " + subset.size() + " compounds: scientific same=" + before.requirePackage(REFINERY).scientificRevision().equals(after.requirePackage(REFINERY).scientificRevision())
                    + " fluid same=" + before.fluidRevision(REFINERY).equals(after.fluidRevision(REFINERY)));
        }
        var co2View = catalog.restrict(REFINERY, List.of("Methane", "CarbonDioxide"));
        System.out.println("CH4+CO2 view scientific: " + co2View.requirePackage(REFINERY).scientificRevision());
        System.out.println("CH4+CO2 view fluid: " + co2View.fluidRevision(REFINERY));
        // 3. The pilot family (spine, the solver's check) and the crude family (coefficient path).
        var pilotFluid = CubicPhaseEvaluator.forPackage(bundled, "icheme:pilot_cryogenic");
        var pilotSolid = CrystalPhaseEvaluator.forPackage(bundled, "icheme:pilot_cryogenic", CRYSTAL);
        new Family("pilot", pilotFluid, pilotSolid, pilotSolid.anchor(pilotFluid)).report();
        coefficient(catalog, REFINERY, "crude_pr78").report();
    }
}
