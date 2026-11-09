package com.wormzjl.icheme.science.thermo.reference;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O2): evaluates the CoolProp 8.0.0 reference equations of state
 * (MIT; the Java Helmholtz oracle) at the states whose NIST WebBook values the bundled data and test references
 * carried, and writes them to {@code tmp/open-thermo-nist/oracle.json} for {@code tools/open-thermo-data/derive.mjs}.
 * Never committed under {@code src/test}: copy it to {@code src/test/java/com/wormzjl/icheme/science/thermo/reference/},
 * run {@code ./gradlew test --offline --tests com.wormzjl.icheme.science.thermo.reference.OpenThermoOracleProbe}, and
 * move it back. The fluid files are the main checkout's research copies (CoolProp ae81610e), which hold the five
 * C3 to C5 fluids the test resources lack; the six test copies are byte-identical to them (MANIFEST.md).
 */
class OpenThermoOracleProbe {
    private static final String FLUIDS = "D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/sources/coolprop/fluids/";
    private static final Path OUT = Path.of("tmp/open-thermo-nist/oracle.json");

    private static HelmholtzFluid fluid(String name) throws Exception {
        return HelmholtzFluid.parse(JsonParser.parseString(Files.readString(Path.of(FLUIDS + name + ".json"))).getAsJsonObject());
    }

    private static JsonObject state(HelmholtzState s) {
        var o = new JsonObject();
        o.addProperty("temperatureKelvin", s.temperature());
        o.addProperty("pressurePascal", s.pressure());
        o.addProperty("molarDensity", s.density());
        o.addProperty("molarVolume", 1 / s.density());
        o.addProperty("cp", s.isobaricHeatCapacity());
        o.addProperty("dPdRho", s.pressureDensityDerivative());
        o.addProperty("isothermalCompressibility", 1 / (s.density() * s.pressureDensityDerivative()));
        return o;
    }

    @Test
    void writeOracleValues() throws Exception {
        var out = new JsonObject();
        // The eight liquid calibration points, 2 MPa (transport/liquid_calibration.json).
        var liquids = new JsonArray();
        Object[][] points = {{"Methane", "Methane", 150.0}, {"Ethane", "Ethane", 240.0}, {"Propane", "n-Propane", 295.0},
                {"Isobutane", "IsoButane", 300.0}, {"N-butane", "n-Butane", 300.0}, {"Isopentane", "Isopentane", 300.0},
                {"N-pentane", "n-Pentane", 300.0}, {"Nitrogen", "Nitrogen", 90.0}};
        for (Object[] p : points) {
            var f = fluid((String) p[1]);
            double t = (Double) p[2], rho = f.density(t, 2e6, HelmholtzFluid.Root.LIQUID);
            var o = state(f.state(t, rho));
            o.addProperty("component", (String) p[0]);
            o.addProperty("coolpropFluid", (String) p[1]);
            liquids.add(o);
        }
        out.add("liquidCalibration", liquids);
        // Water vapour at the reference points (fluid/reference/water-vapor-checks.json): IAPWS-95.
        var water = fluid("Water");
        var steam = new JsonArray();
        try (var in = getClass().getResourceAsStream("/fluid/reference/water-vapor-checks.json")) {
            for (var value : JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonArray()) {
                var row = value.getAsJsonObject();
                double t = row.get("temperatureKelvin").getAsDouble(), p = row.get("partialPressurePascal").getAsDouble();
                steam.add(state(water.state(t, water.density(t, p, HelmholtzFluid.Root.VAPOUR))));
            }
        }
        out.add("waterVapour", steam);
        // Nitrogen (Span et al. 2000): the normal boiling point, the 1 atm vapour at 100 and 200 K.
        var nitrogen = fluid("Nitrogen");
        var n2 = new JsonObject();
        var sat = nitrogen.saturation(77.355);
        n2.addProperty("saturationPressure77355", sat.pressure());
        n2.add("saturatedLiquid77355", state(sat.liquid()));
        n2.addProperty("saturatedLiquidMassDensity77355", sat.liquid().density() * 0.02801348);
        var vapour = new JsonArray();
        for (double t : new double[] {100, 200, 350}) vapour.add(state(nitrogen.state(t, nitrogen.density(t, 101325, HelmholtzFluid.Root.VAPOUR))));
        n2.add("vapour1atm", vapour);
        out.add("nitrogen", n2);
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(out));
        System.out.println("wrote " + OUT.toAbsolutePath());
    }
}
