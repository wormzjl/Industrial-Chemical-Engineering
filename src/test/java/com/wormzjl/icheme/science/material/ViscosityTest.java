package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

class ViscosityTest {
    private static final String PACKAGE = "icheme:tjl20_methane";
    private static final String WATER = "data/icheme/materials/water/water.json";

    @Test void liquidWaterMatchesIndependentIapwsTableEight() {
        var v = MaterialCatalog.bundled().viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow();
        // IAPWS SR6-08(2011), table 8: values are published in microPa.s (including metastable liquid).
        assertEquals(3058.36075e-6, v.dynamicViscosityPascalSeconds(260, ViscosityCorrelation.Phase.LIQUID), 5e-12);
        assertEquals(889.996774e-6, v.dynamicViscosityPascalSeconds(298.15, ViscosityCorrelation.Phase.LIQUID), 5e-13);
        assertEquals(276.207245e-6, v.dynamicViscosityPascalSeconds(375, ViscosityCorrelation.Phase.LIQUID), 5e-13);
        assertFalse(v.estimated());
        assertTrue(v.source().contains("IAPWS"));
    }

    @Test void validityLimitsAndMissingDataAreExplicit() {
        var catalog = MaterialCatalog.bundled();
        assertTrue(catalog.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.VAPOR).isEmpty());
        assertTrue(Cdu17TestCatalog.catalog().viscosity("icheme:cdu17_tjl_acs2018", "cdu17_pc01", ViscosityCorrelation.Phase.LIQUID).isEmpty());
        assertThrows(IllegalArgumentException.class,()->catalog.viscosity(PACKAGE,"TJL_PC07",ViscosityCorrelation.Phase.LIQUID));
        assertThrows(IllegalArgumentException.class, () -> catalog.viscosity(PACKAGE, "NotAComponent", ViscosityCorrelation.Phase.LIQUID));
        var v = catalog.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow();
        // Past its data the correlation continues (owner rule of 2026-10-16): only a temperature that is not positive and
        // finite is refused. SR6's own sum continues itself just past both ends of 253.15..383.15 K.
        for (double t : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> v.dynamicViscosityPascalSeconds(t, ViscosityCorrelation.Phase.LIQUID));
        assertTrue(v.dynamicViscosityPascalSeconds(253.14, ViscosityCorrelation.Phase.LIQUID) > v.dynamicViscosityPascalSeconds(253.15, ViscosityCorrelation.Phase.LIQUID));
        assertTrue(v.dynamicViscosityPascalSeconds(383.16, ViscosityCorrelation.Phase.LIQUID) < v.dynamicViscosityPascalSeconds(383.15, ViscosityCorrelation.Phase.LIQUID));
        assertTrue(v.dynamicViscosityPascalSeconds(253.15, ViscosityCorrelation.Phase.LIQUID) > v.dynamicViscosityPascalSeconds(383.15, ViscosityCorrelation.Phase.LIQUID));
        assertThrows(UnsupportedOperationException.class, () -> v.coefficients().set(0, 1.0));
    }

    @Test void liquidAndGasFitsLoadForTheSameComponentWithoutChangingEquilibriumData() {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        String path = "data/icheme/materials/properties/tjl20_methane.json";
        var property = JsonParser.parseString(resources.get(path)).getAsJsonObject();
        var fits = new JsonObject();
        fits.add("liquid", fit("andrade", 1e-6, 1500));
        fits.add("vapor", fit("sutherland", 1.1e-5, 110));
        property.add("viscosity", fits);
        resources.put(path, property.toString());
        var catalog = MaterialCatalog.parse(resources);
        var liquid = catalog.viscosity(PACKAGE, "Methane", ViscosityCorrelation.Phase.LIQUID).orElseThrow();
        var vapor = catalog.viscosity(PACKAGE, "Methane", ViscosityCorrelation.Phase.VAPOR).orElseThrow();
        assertEquals(1e-6 * Math.exp(5), liquid.dynamicViscosityPascalSeconds(300, ViscosityCorrelation.Phase.LIQUID), 1e-18);
        assertEquals(1.1e-5, vapor.dynamicViscosityPascalSeconds(300, ViscosityCorrelation.Phase.VAPOR), 1e-20);
        assertTrue(vapor.dynamicViscosityPascalSeconds(600, ViscosityCorrelation.Phase.VAPOR) > vapor.dynamicViscosityPascalSeconds(300, ViscosityCorrelation.Phase.VAPOR));
        assertTrue(liquid.estimated());
        var axis = catalog.requirePackage(PACKAGE).components();
        assertEquals(MaterialCatalog.bundled().physicsContent(PACKAGE, axis), catalog.physicsContent(PACKAGE, axis), "transport is not phase physics");
        assertNotEquals(MaterialCatalog.bundled().viscosity(PACKAGE, "Methane", ViscosityCorrelation.Phase.LIQUID), Optional.of(liquid));
    }

    @Test void malformedFitsFailCatalogLoadingWithAFieldPath() {
        for (var edit : List.<java.util.function.Consumer<JsonObject>>of(
                v -> v.addProperty("type", "unregistered"),
                v -> v.remove("source"),
                v -> v.addProperty("temperature_max_kelvin", 200),
                v -> v.addProperty("pressure_max_pascal", 1),
                v -> v.getAsJsonArray("coefficients").set(0, new JsonPrimitive(-1)),
                v -> v.add("exponents", new JsonArray()),
                v -> v.addProperty("reference_temperature_kelvin", 0))) {
            var resources = waterChange(edit);
            var error = assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(resources));
            assertTrue(error.getMessage().contains("water/water.json"));
            assertTrue(error.getMessage().contains("viscosity.liquid"));
        }
        var resources = waterChange(v -> {
            var invalid = fit("andrade", 1, 1e9);
            for (String key : List.copyOf(v.keySet())) v.remove(key);
            invalid.entrySet().forEach(e -> v.add(e.getKey(), e.getValue()));
        });
        assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(resources));
    }

    @Test void transportSnapshotTracksViscosityAndLeavesThePhasePhysics() {
        var original = MaterialCatalog.bundled();
        var replacement = MaterialCatalog.parse(waterChange(v -> v.getAsJsonArray("coefficients").set(0, new JsonPrimitive(.0003))));
        var water = ViscosityCorrelation.Phase.LIQUID;
        assertNotEquals(original.viscosity(PACKAGE, "Water", water), replacement.viscosity(PACKAGE, "Water", water));
        var axis = original.requirePackage(PACKAGE).components();
        assertEquals(original.physicsContent(PACKAGE, axis), replacement.physicsContent(PACKAGE, axis), "the phase physics the column compares");
        var citationOnly = MaterialCatalog.parse(waterChange(v -> v.addProperty("source", "Corrected source citation")));
        assertEquals(original.viscosity(PACKAGE, "Water", water).orElseThrow().coefficients(), citationOnly.viscosity(PACKAGE, "Water", water).orElseThrow().coefficients());
        try {
            MaterialRuntime.with(original, PACKAGE, () -> {
                MaterialRuntime.publish(replacement);
                assertSame(original.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow(),
                        MaterialRuntime.current().viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow());
                return null;
            });
            assertSame(replacement.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow(),
                    MaterialRuntime.current().viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow());
            var invalid = waterChange(v -> v.addProperty("temperature_min_kelvin", -1));
            assertThrows(IllegalArgumentException.class, () -> MaterialRuntime.publish(MaterialCatalog.parse(invalid)));
            assertSame(replacement, MaterialRuntime.active());
        } finally { MaterialRuntime.reset(); }
    }

    private static Map<String, String> waterChange(java.util.function.Consumer<JsonObject> edit) {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        var water = JsonParser.parseString(resources.get(WATER)).getAsJsonObject();
        edit.accept(water.getAsJsonObject("viscosity").getAsJsonObject("liquid"));
        resources.put(WATER, water.toString()); return resources;
    }

    @Test void unchangedChemicalCurvesMatchIndependentDwsimApiCheckPoints() throws Exception {
        JsonObject report;
        try (var in = getClass().getResourceAsStream("/materials/dwsim-viscosity-api.json")) {
            assertNotNull(in);
            report = JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var catalog=MaterialCatalog.bundled();
        Map<String,JsonObject> exported=new HashMap<>();
        for (var row:report.getAsJsonArray("records")) {
            var record=row.getAsJsonObject();exported.put(record.get("property_id").getAsString(),record);
        }
        int verified=0;
        for (var p:catalog.packages().values()) for (var property:p.properties()) {
            var row=exported.get(property.id());
            // Nitrogen's curves are CoolProp tables, not DWSIM API samples, so the exported
            // comparison report carries no record for that property at all.
            if(row==null||!row.has("checks"))continue;
            for(var phase:ViscosityCorrelation.Phase.values()) {
                var curve=catalog.viscosity(p.id(),property.component(),phase).orElseThrow();
                assertEquals(ViscosityCorrelation.Model.LOG_TABLE,curve.model());
                if(property.component().startsWith("tjl19_pc"))assertTrue(curve.estimated());
                var checks=row.getAsJsonObject("checks").getAsJsonArray(phase.name().toLowerCase(Locale.ROOT));
                for(var value:checks) {
                    var point=value.getAsJsonObject();double t=point.get("temperature_kelvin").getAsDouble();
                    double expected=point.get("viscosity_pascal_seconds").getAsDouble();
                    assertEquals(expected,curve.dynamicViscosityPascalSeconds(t,phase),expected*0.000500001,
                            ()->property.id()+" "+phase+" at "+t+" K diverges from DWSIM");
                    verified++;
                }
            }
        }
        assertTrue(verified>1000);
        for(String id:catalog.requirePackage(PACKAGE).components())for(var phase:ViscosityCorrelation.Phase.values())
            assertTrue(catalog.viscosity(PACKAGE,id,phase).isPresent(),id+" "+phase);
    }

    @Test void tableNodesAreExactAndMalformedTablesAreRejected() {
        var table=new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,300,400,100000,100000,300,
                List.of(.001,.00025),List.of(),"test","manufactured interpolation fixture",true,List.of(300.,400.));
        assertEquals(.001,table.dynamicViscosityPascalSeconds(300,ViscosityCorrelation.Phase.LIQUID));
        assertEquals(.00025,table.dynamicViscosityPascalSeconds(400,ViscosityCorrelation.Phase.LIQUID));
        assertEquals(.0005,table.dynamicViscosityPascalSeconds(350,ViscosityCorrelation.Phase.LIQUID),1e-18);
        assertThrows(IllegalArgumentException.class,()->new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,300,400,100000,100000,300,
                List.of(.001,.00025),List.of(),"test","fixture",true,List.of(300.,300.)));
        assertThrows(IllegalArgumentException.class,()->new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,300,400,100000,100000,300,
                List.of(.001,-.00025),List.of(),"test","fixture",true,List.of(300.,400.)));
    }

    private static JsonObject fit(String type, double a, double b) {
        // Manufactured fixtures exercise the equations; these are not published methane estimates.
        var fit = JsonParser.parseString("""
                {"temperature_min_kelvin":250,"temperature_max_kelvin":700,
                 "pressure_min_pascal":100000,"pressure_max_pascal":300000,"reference_temperature_kelvin":300,
                 "revision":"test-r1","source":"Manufactured equation fixture","estimated":true}
                """).getAsJsonObject();
        fit.addProperty("type", type); var c = new JsonArray(); c.add(a); c.add(b); fit.add("coefficients", c);
        return fit;
    }

    // ---------------- from AmbientViscosityTest ----------------
    @Test void addedAmbientSamplesMatchIndependentDwsimCheckpoints() throws Exception {
        var catalog=MaterialCatalog.bundled();int checked=0;var properties=new java.util.HashSet<String>();
        try(var input=getClass().getResourceAsStream("/materials/dwsim-ambient-viscosity-api.json")) {
            var report=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var record:report.getAsJsonArray("records")) {
                var row=record.getAsJsonObject();if(!row.has("checks"))continue;
                String id=row.get("property_id").getAsString();
                if(id.contains("tjl19_pc")||id.contains("cdu17"))continue; // These liquid curves were intentionally replaced by the global Dalia family.
                var p=catalog.packages().values().stream().filter(pkg->pkg.properties().stream().anyMatch(v->v.id().equals(id))).findFirst().orElseThrow();
                properties.add(id);
                String component=p.properties().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow().component();
                for(var phase:row.getAsJsonObject("checks").entrySet()) {
                    var kind=ViscosityCorrelation.Phase.valueOf(phase.getKey().toUpperCase(java.util.Locale.ROOT));
                    var correlation=catalog.viscosity(p.id(),component,kind).orElseThrow();
                    for(var value:phase.getValue().getAsJsonArray()) {
                        var point=value.getAsJsonObject();double t=point.get("temperature_kelvin").getAsDouble();
                        double expected=point.get("viscosity_pascal_seconds").getAsDouble();
                        assertEquals(expected,correlation.dynamicViscosityPascalSeconds(t,kind),expected*.000500001,id+" at "+t);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked>0);
        assertEquals(java.util.Set.of("icheme:tjl19_ethane","icheme:tjl19_isobutane","icheme:tjl19_isopentane","icheme:tjl19_n_butane","icheme:tjl19_n_pentane","icheme:tjl19_propane","icheme:tjl20_methane"),properties);
    }
}
