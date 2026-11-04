package com.wormzjl.icheme.science.column.v3.thermo;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ViscosityCorrelation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O4): the whole-crude liquid viscosity of the bundled assays at 15,
 * 40 and 100 C through the game's code ({@link MixtureViscosity#liquid} on {@code icheme:tjl20_methane}, the assays' mole
 * fractions from the column package), with the cut-only log mean, the pair term exp(delta) and each cut's pure value.
 * Run it on the parent commit and on the O4 commit; system property {@code o4.label} names the output,
 * {@code tmp/open-thermo-o4/viscosity-<label>.json}. Never committed under {@code src/test}: copy it to
 * {@code src/test/java/com/wormzjl/icheme/science/column/v3/thermo/}, run
 * {@code ./gradlew columnGates --offline --tests com.wormzjl.icheme.science.column.v3.thermo.O4ViscosityProbe}, move it back.
 */
class O4ViscosityProbe {
    private static final String PACKAGE = "icheme:tjl20_methane";
    private static final String[] ASSAYS = {"icheme:tia_juana_light_methane", "icheme:wti_light_export", "icheme:cold_lake_blend",
            "icheme:dalia", "icheme:bonga", "icheme:upper_zakum"};
    private static final double[] KELVIN = {288.15, 313.15, 373.15};

    private static double delta(Object correction, double t, double[] x) throws Exception {
        for (var m : correction.getClass().getMethods()) if (m.getName().equals("logCorrection"))
            return (double) (m.getParameterCount() == 2 ? m.invoke(correction, t, x) : m.invoke(correction, (Object) x));
        throw new IllegalStateException("no logCorrection");
    }

    @Test void wholeCrudeLiquidViscosity() throws Exception {
        String label = System.getProperty("o4.label", Files.exists(Path.of("tmp/open-thermo-o4/viscosity-before.json")) ? "after" : "before");
        var catalog = MaterialCatalog.bundled();
        var pack = catalog.requirePackage(PACKAGE);
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
        var viscosity = new MixtureViscosity(catalog, PACKAGE);
        var out = new JsonObject();
        out.addProperty("package", PACKAGE);
        out.addProperty("fluidRevision", viscosity.revision());
        out.addProperty("scientificRevision", pack.scientificRevision());
        var crudes = new JsonObject();
        for (String assay : ASSAYS) {
            double[] x = thermo.crudeFeed(assay).moleFractions();
            var row = new JsonObject();
            for (double t : KELVIN) {
                double mu = viscosity.liquid(t, x).pascalSeconds();
                // The pair term, read from the package's correction by reflection: its signature is (T, x) before O4 and (x) after.
                double delta = delta(catalog.liquidMixture(PACKAGE), t, x);
                double mean = mu / Math.exp(delta);
                var cell = new JsonObject();
                cell.addProperty("mPas", mu * 1e3);
                cell.addProperty("logMeanMPas", mean * 1e3);
                cell.addProperty("pairFactor", mu / mean);
                row.add(Double.toString(t), cell);
            }
            crudes.add(assay, row);
        }
        out.add("crudes", crudes);
        var cuts = new JsonObject();
        for (int k = 1; k <= 12; k++) {
            String cut = "crude_pc%02d".formatted(k);
            var row = new JsonObject();
            var c = catalog.viscosity(PACKAGE, cut, ViscosityCorrelation.Phase.LIQUID).orElseThrow();
            for (double t : new double[]{288.15, 293.15, 310.9278, 313.15, 372.0389, 373.15, 500, 700, 900})
                row.addProperty(Double.toString(t), c.dynamicViscosityPascalSeconds(t, ViscosityCorrelation.Phase.LIQUID) * 1e3);
            cuts.add(cut, row);
        }
        out.add("cutsMPas", cuts);
        var file = Path.of("tmp/open-thermo-o4/viscosity-" + label + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }
}
