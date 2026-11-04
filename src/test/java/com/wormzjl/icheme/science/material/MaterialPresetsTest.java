package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.thermo.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.IOException;
import java.io.InputStreamReader;

class MaterialPresetsTest {
    private static final String ROOT="data/icheme/materials/";
    /** Every crude from its stream and tower templates (process simulator plan 3.7) is its qualification input to rounding. */
    @Test void allSixInputsRemainTheQualificationInputs() throws Exception {
        try(var stream=getClass().getResourceAsStream("/materials/column-preset-inputs.json")) {
            var expected=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(6,expected.size());
            for(var row:expected) {
                var fixture=row.getAsJsonObject();var actual=com.wormzjl.icheme.science.column.v3.V3TestInputs.crude(MaterialCatalog.bundled(),fixture.get("id").getAsString());
                assertClose(fixture.get("input"),new Gson().toJsonTree(actual),fixture.get("id").getAsString());
            }
        }
    }
    private static void assertClose(JsonElement expected,JsonElement actual,String path) {
        if(expected.isJsonArray()) {
            assertEquals(expected.getAsJsonArray().size(),actual.getAsJsonArray().size(),path);
            for(int i=0;i<expected.getAsJsonArray().size();i++)assertClose(expected.getAsJsonArray().get(i),actual.getAsJsonArray().get(i),path+"["+i+"]");
        } else if(expected.isJsonObject()) {
            assertEquals(expected.getAsJsonObject().keySet(),actual.getAsJsonObject().keySet(),path);
            for(String key:expected.getAsJsonObject().keySet())assertClose(expected.getAsJsonObject().get(key),actual.getAsJsonObject().get(key),path+"."+key);
        } else if(expected.isJsonPrimitive()&&expected.getAsJsonPrimitive().isNumber()) {
            assertEquals(expected.getAsDouble(),actual.getAsDouble(),Math.abs(expected.getAsDouble())*1e-12,path);
        } else assertEquals(expected,actual,path);
    }
    @Test void exampleWholeRecordOverridePreservesAllExistingTransportData() throws Exception {
        var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());String path=ROOT+"properties/tjl20_methane.json";
        String example=java.nio.file.Files.readString(java.nio.file.Path.of("examples/material-override/"+path));
        // The example is the bundled record with its density, source and revision edited, nothing else, so it never drifts
        // into an older copy (t44d found one whose fluid_domain ended at 2 MPa where the bundled record ends at 10 MPa).
        var edited=JsonParser.parseString(example).getAsJsonObject();var bundled=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        for(var field:new String[]{"standard_liquid_density_kg_per_m3","source","revision"}){edited.remove(field);bundled.remove(field);}
        assertEquals(bundled,edited,"the example differs from the bundled record only in its density, source and revision");
        resources.put(path,example);
        var replaced=MaterialCatalog.parse(resources);String id="icheme:tjl20_methane";
        assertEquals(357,replaced.requirePackage(id).properties().getFirst().density());
        var component=replaced.requirePackage(id).properties().getFirst().component();
        for(var phase:ViscosityCorrelation.Phase.values())assertEquals(original.viscosity(id,component,phase),replaced.viscosity(id,component,phase),phase.name());
        assertEquals(original.liquidMixture(id),replaced.liquidMixture(id));
        assertNotEquals(original.physicsContent(id,original.requirePackage(id).components()),replaced.physicsContent(id,replaced.requirePackage(id).components()));
        // The edit declares itself with its record's revision bump (owner decisions 2026-10-26), which moves the identities.
        assertNotEquals(original.requirePackage(id).scientificRevision(),replaced.requirePackage(id).scientificRevision());
        assertNotEquals(original.fluidRevision(id),replaced.fluidRevision(id));
    }
    @Test void customPresetIsServerDefinedAndInvalidOverridesFailBeforePublication() {
        var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());String path=ROOT+"presets/column_custom.json";
        var preset=JsonParser.parseString(resources.get(ROOT+"presets/column_tia_juana.json")).getAsJsonObject();
        preset.addProperty("id","custom_crude");preset.addProperty("label","Server crude");preset.addProperty("translation_key","server.crude");
        preset.getAsJsonObject("operating").addProperty("feedTemperatureKelvin",650);resources.put(path,preset.toString());
        var changed=MaterialCatalog.parse(resources);var choice=changed.presets().column("custom_crude");
        assertEquals(650,choice.input(changed).feedTemperatureKelvin());
        assertThrows(IllegalArgumentException.class,()->original.presets().column("custom_crude"));
        assertEquals(original.physicsContent("icheme:tjl20_methane",original.requirePackage("icheme:tjl20_methane").components()),changed.physicsContent("icheme:tjl20_methane",changed.requirePackage("icheme:tjl20_methane").components()));
        preset.getAsJsonObject("operating").addProperty("stageCount",2);resources.put(path,preset.toString());
        var failure=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources));assertTrue(failure.getMessage().contains(path));
        preset.addProperty("assay","missing:assay");resources.put(path,preset.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources)).getMessage().contains("package/assay"));
    }
    @Test void addingAQualifiedFixtureComponentAndMovingNitrogenNeedsNoFixedWidthEdits() {
        var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());
        var name=JsonParser.parseString(resources.get(ROOT+"components/nitrogen.json")).getAsJsonObject();
        name.addProperty("id","Testgas");name.addProperty("fallback","Testgas");name.addProperty("translation_key","test.gas");name.addProperty("properties","test:testgas");
        resources.put(ROOT+"components/testgas.json",name.toString());
        var property=JsonParser.parseString(resources.get(ROOT+"properties/nitrogen.json")).getAsJsonObject();
        property.addProperty("id","test:testgas");property.addProperty("component","Testgas");property.addProperty("source","Synthetic structural fixture using nitrogen reference data");resources.put(ROOT+"properties/testgas.json",property.toString());
        // The network package lists a new compound before nitrogen: the package names compounds, each brings its dataset.
        var network=JsonParser.parseString(resources.get(ROOT+"packages/tjl20_nitrogen.json")).getAsJsonObject();
        network.add("components",new Gson().toJsonTree(List.of("Testgas","Nitrogen")));resources.put(ROOT+"packages/tjl20_nitrogen.json",network.toString());
        var changed=MaterialCatalog.parse(resources);String pkg=FluidMaterialCatalog.networkPackage(changed);var axis=changed.requirePackage(pkg).components();
        assertEquals(original.requirePackage(pkg).components().size()+1,axis.size());
        var nitrogen=FluidDeviceSpec.nitrogen(changed);assertEquals(1,nitrogen.composition()[axis.indexOf("Nitrogen")]);assertEquals(0,nitrogen.composition()[axis.indexOf("Testgas")]);
        var presets=FluidPresetCatalog.resolve(changed);var previous=FluidPresetCatalog.resolve(original);
        assertEquals(previous.size(),presets.size());
        var target=new ArrayList<>(axis);target.add("Water");var source=new ArrayList<>(original.requirePackage(pkg).components());source.add("Water");
        for(int i=0;i<presets.size();i++)assertArrayEquals(new MaterialAxis(target).project(new MaterialAxis(source),previous.get(i).moleFractions()),presets.get(i).moleFractions());
        var model=FluidThermodynamics.forNetwork(changed,pkg);assertEquals(target,model.components());
        var state=model.initialNitrogenCharge(1,298.15,101325,()->{});assertTrue(Double.isFinite(state.mass())&&state.mass()>0);
        assertSame(original,MaterialRuntime.current());
    }

    // ---------------- from CrudeAssayConversionTest ----------------
    @Test
    void allFivePublishedConversionsLoadWithTheSameOrderAndMassBasis() throws IOException {
        JsonObject report;
        try (var stream = getClass().getResourceAsStream("/materials/crude-assay-conversion-fixture.json")) {
            assertNotNull(stream, "Missing independent assay conversion fixture");
            report = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var catalog = MaterialCatalog.bundled();
        var original = catalog.requirePackage("icheme:tjl20_methane");
        assertEquals(5, report.getAsJsonArray("crudes").size());
        for (var element : report.getAsJsonArray("crudes")) {
            var row = element.getAsJsonObject();
            String packageId = row.get("package_id").getAsString();
            String assayId = row.get("assay_id").getAsString();
            var resolved = catalog.requirePackage(packageId);
            assertEquals(original.components(), resolved.components());
            assertEquals(original.properties(), resolved.properties());
            assertEquals("mass", resolved.assays().get(assayId).basis());
            MaterialRuntime.with(catalog, packageId, () -> {
                var thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
                double[] feed = thermo.crudeFeed(assayId).moleFractions();
                assertArrayEquals(vector(row.getAsJsonArray("mole_fractions")), feed, 1e-14);
                double totalMass = 0;
                for (int i = 0; i < feed.length; i++) totalMass += feed[i] * resolved.properties().get(i).molecularWeight();
                for (int i = 0; i < feed.length; i++) {
                    assertEquals(row.getAsJsonArray("mass_fractions").get(i).getAsDouble(),
                            feed[i] * resolved.properties().get(i).molecularWeight() / totalMass, 1e-14);
                }
                // The crude's own evidence stays with its data (the package's lines are the shared basis's).
                String file = catalog.resources().get(ROOT + "assays/" + assayId.substring("icheme:".length()) + ".json");
                var evidence = JsonParser.parseString(file).getAsJsonObject().getAsJsonObject("provenance").getAsJsonArray("advisory_evidence").toString();
                for (String line : List.of("SHARED_COMPONENT_SURROGATE:", "ASSAY_SOURCE:", "C1_C2_ALLOCATION:", "ESTIMATED_HEAVY_TAIL:", "CONTAMINANTS:", "TRANSPORT_SURROGATE:"))
                    assertTrue(evidence.contains(line), assayId + " " + line);
                var flash = thermo.flashTP(638.15, 267_250, feed, thermo.newWorkspace());
                assertEquals(V3FeedPhase.TWO_PHASE, flash.phase(), packageId + ": " + flash.detail());
                assertTrue(Double.isFinite(flash.molarEnthalpyJoulesPerMol()));
                for (int i = 0; i < feed.length; i++) {
                    assertEquals(feed[i], (1 - flash.vaporFraction()) * flash.liquidComposition()[i]
                            + flash.vaporFraction() * flash.vaporComposition()[i], 1e-9, packageId);
                }
                return null;
            });
        }
    }

    private static double[] vector(JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsDouble();
        return values;
    }
}
