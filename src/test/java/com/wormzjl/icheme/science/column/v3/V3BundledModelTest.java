package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The bundled model (batch 2026-11-05-neural-retrain, N6): its raw outputs against independent PyTorch float64 inference, and
 * its decoded seed against the phase-floor decoder on those outputs ({@code open-predictions.json},
 * {@code NeuralRetrainProbe.fixtures}).
 */
class V3BundledModelTest {
    private static final Gson JSON=new Gson();
    private static final String PAYLOAD="/data/icheme/neural/v3-column-transformer-open.payload.json";
    private static final String SIDECAR="/data/icheme/neural/v3-column-transformer-open.sidecar.json";
    private static JsonObject fixture() throws IOException {
        try(var stream=V3BundledModelTest.class.getResourceAsStream("/science/column/v3/neural/open-predictions.json")) {
            assertNotNull(stream);return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static V3AnchorTransformerInitializer model() throws IOException {
        try(var p=V3BundledModelTest.class.getResourceAsStream(PAYLOAD);var s=V3BundledModelTest.class.getResourceAsStream(SIDECAR)) {
            assertNotNull(p);assertNotNull(s);
            return V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(p.readAllBytes()),new ByteArrayInputStream(s.readAllBytes())).initializer();
        }
    }
    @Test void packagedWeightsAndDecoderMatchIndependentFloat64Fixtures() throws Exception {
        var fixture=fixture();
        byte[] payload,sidecar;
        try(var p=getClass().getResourceAsStream(PAYLOAD);var s=getClass().getResourceAsStream(SIDECAR)) {payload=p.readAllBytes();sidecar=s.readAllBytes();}
        assertEquals(fixture.get("payloadSha256").getAsString(),V3NeuralRegistry.TransformerArtifact.sha256(payload));
        assertEquals(fixture.get("sidecarSha256").getAsString(),V3NeuralRegistry.TransformerArtifact.sha256(sidecar));
        var model=model();
        assertEquals(88852,model.parameterCount());
        assertEquals(fixture.get("modelId").getAsString(),model.modelId());
        for(var entry:fixture.getAsJsonArray("fixtures")) {
            var row=entry.getAsJsonObject();var input=input(row.getAsJsonObject("input"));
            assertTrue(model.supported(input),row.get("id").getAsString());var raw=model.raw(input,V3SolveControl.UNBOUNDED);
            var expectedRaw=JSON.fromJson(row.get("raw"),double[][].class);
            for(int n=0;n<expectedRaw.length;n++)assertArrayEquals(expectedRaw[n],raw.values()[n],1e-4);
            assertArrayEquals(JSON.fromJson(row.get("branchLogits"),double[].class),raw.branchLogits(),1e-5);
            assertEquals(model.modelId(),V3NeuralRegistry.bundled().bind(com.wormzjl.icheme.science.material.MaterialCatalog.bundled(),input).modelId());
            var actual=V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
            var expected=row.getAsJsonObject("expected");
            assertEquals(expected.get("branch").getAsString(),actual.branch().name());
            assertArrayEquals(JSON.fromJson(expected.get("temperatures"),double[].class),actual.temperatures(),1e-4);
            assertArrayEquals(JSON.fromJson(expected.get("wetTrays"),boolean[].class),actual.wetTrays());
            double total=Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
            checkFlows(JSON.fromJson(expected.get("liquid"),double[][].class),actual.liquid(),total);
            checkFlows(JSON.fromJson(expected.get("vapor"),double[][].class),actual.vapor(),total);
            checkFlows(new double[][]{JSON.fromJson(expected.get("freeWater"),double[].class)},new double[][]{actual.freeWater()},total);
            var checkpoints=new AtomicInteger();
            assertThrows(CancellationException.class,()->model.predict(input,()->{if(checkpoints.incrementAndGet()==100)throw new CancellationException("fixture checkpoint");}));
            assertArrayEquals(actual.temperatures(),model.predict(input,V3SolveControl.UNBOUNDED).orElseThrow().temperatures());
        }
    }
    /**
     * The network package holds the model's 19 compounds and nitrogen (plan section 3.3 of batch
     * 2026-11-04-refinery-compounds): with nitrogen at zero its request binds the bundled model, and the seed is the
     * base seed with a zero nitrogen slot.
     */
    @Test void theNetworkPackageWithZeroNitrogenBindsTheBundledModel() throws Exception {
        var base=input(fixture().getAsJsonArray("fixtures").get(0).getAsJsonObject().getAsJsonObject("input"));
        var network=com.wormzjl.icheme.science.material.MaterialCatalog.bundled().requirePackage("icheme:tjl20_methane_nitrogen");
        var from=new com.wormzjl.icheme.science.material.MaterialAxis(base.componentBasis().componentIds());
        var to=new com.wormzjl.icheme.science.material.MaterialAxis(network.components());
        assertTrue(to.ids().containsAll(from.ids())&&to.size()==from.size()+1);
        var input=new V3ColumnInput(base.schemaVersion(),network.id(),base.assayId(),new V3ComponentBasis(network.components()),
                to.project(from,base.feedComponentMolarFlowsMolPerSecond()),base.feedTemperatureKelvin(),base.stageCount(),base.feedStageNumber(),
                base.topPressurePascal(),base.stagePressureDropPascal(),base.specifications(),base.sideDraws(),base.steamFeeds(),base.pumparounds(),
                base.columnDiameterMetres());
        var expected=V3NeuralRegistry.bundled().predict(base,V3SolveControl.UNBOUNDED).orElseThrow();
        var actual=V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
        assertEquals(input,actual.input());
        assertArrayEquals(expected.temperatures(),actual.temperatures());
        for(int n=0;n<expected.liquid().length;n++) {
            assertArrayEquals(to.project(from,expected.liquid()[n]),actual.liquid()[n]);
            assertArrayEquals(to.project(from,expected.vapor()[n]),actual.vapor()[n]);
        }
    }
    /**
     * The bundled model carries the values it was trained on (sidecar schema 5): the values of {@code icheme:tjl20_methane}
     * when its labels were solved (r34), so the default package binds it at zero distance; its ceiling is the hottest TRAIN feed.
     */
    @Test void theDefaultPackageIsTheTrainedPhysics() throws Exception {
        var physics=model().physics();
        assertEquals(298.15,physics.minimumTemperature());assertEquals(682.9973857719451,physics.maximumTemperature());
        var p=com.wormzjl.icheme.science.material.MaterialCatalog.bundled().requirePackage("icheme:tjl20_methane");
        for(var d:physics.compare(p,p.components()))assertEquals(0,d.value(),d.toString());
    }
    /**
     * What the binding leaves out of its comparison (O7 of batch 2026-11-04-open-thermo-data, {@code TrainedPhysics}) moves
     * no prediction: with every compound's molar mass, normal boiling point and standard liquid density 5 % higher, the
     * model's raw output (the native anchor, the network and the branch) is the same bit for bit, and the model binds.
     */
    @Test void theValuesTheBindingLeavesOutMoveNoPrediction() throws Exception {
        var bundled=com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
        var resources=new HashMap<>(bundled.resources());int edited=0;
        for(var property:bundled.requirePackage("icheme:tjl20_methane").properties())for(var entry:resources.entrySet()) {
            if(!entry.getKey().contains("/materials/properties/"))continue;
            var o=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            if(!o.get("id").getAsString().equals(property.id()))continue;
            for(String field:List.of("molecular_weight_kg_per_mol","normal_boiling_point_kelvin","standard_liquid_density_kg_per_m3"))
                o.addProperty(field,o.get(field).getAsDouble()*1.05);
            o.addProperty("revision",o.get("revision").getAsString()+"-edited");entry.setValue(o.toString());edited++;
        }
        assertEquals(19,edited);
        var catalog=com.wormzjl.icheme.science.material.MaterialCatalog.parse(resources);
        var model=model();
        for(var entry:fixture().getAsJsonArray("fixtures")) {
            var input=input(entry.getAsJsonObject().getAsJsonObject("input"));
            var expected=com.wormzjl.icheme.science.material.MaterialRuntime.with(bundled,input.packageId(),()->model.raw(input,V3SolveControl.UNBOUNDED));
            var actual=com.wormzjl.icheme.science.material.MaterialRuntime.with(catalog,input.packageId(),()->model.raw(input,V3SolveControl.UNBOUNDED));
            assertArrayEquals(expected.branchLogits(),actual.branchLogits());
            for(int n=0;n<expected.values().length;n++)assertArrayEquals(expected.values()[n],actual.values()[n]);
            assertNotSame(V3NeuralInitializer.UNAVAILABLE,V3NeuralRegistry.bundled().bind(catalog,input));
        }
    }
    /** The model has three draw slots; a column with the fourth draw the input allows is left to the classical seed. */
    @Test void aFourthSideDrawIsAValidInputTheBundledModelDeclines() throws Exception {
        V3ColumnInput base=null;
        for(var entry:fixture().getAsJsonArray("fixtures")){var candidate=input(entry.getAsJsonObject().getAsJsonObject("input"));if(candidate.stageCount()>=4){base=candidate;break;}}
        assertNotNull(base);
        double rate=Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum()*0.01;
        var draws=new ArrayList<V3SideDrawSpec>();for(int tray=1;tray<=V3ColumnInput.MAX_SIDE_DRAWS;tray++)draws.add(new V3SideDrawSpec(tray,rate));
        assertEquals(4,V3ColumnInput.MAX_SIDE_DRAWS);
        var input=new V3ColumnInput(base.schemaVersion(),base.packageId(),base.assayId(),base.componentBasis(),base.feedComponentMolarFlowsMolPerSecond(),
                base.feedTemperatureKelvin(),base.stageCount(),base.feedStageNumber(),base.topPressurePascal(),base.stagePressureDropPascal(),
                base.specifications(),draws,base.steamFeeds(),base.pumparounds(),base.columnDiameterMetres());
        assertTrue(V3NeuralRegistry.bundled().predict(base,V3SolveControl.UNBOUNDED).isPresent());
        assertTrue(V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).isEmpty());
    }
    private static void checkFlows(double[][] expected,double[][] actual,double total) {
        assertEquals(expected.length,actual.length);
        for(int n=0;n<expected.length;n++) {
            assertArrayEquals(expected[n],actual[n],total*1e-5);
            for(int c=0;c<expected[n].length;c++)assertEquals(expected[n][c]==0,actual[n][c]==0,"Decoded support changed");
        }
    }
    /** A fixture input as {@code NeuralRetrainProbe.inputJson} writes it. */
    private static V3ColumnInput input(JsonObject json) {
        var specs=new ArrayList<V3ColumnSpecification>();
        for(var value:json.getAsJsonArray("specifications")) {
            var s=value.getAsJsonObject();
            if(s.has("kelvin"))specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if(s.has("ratio"))specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(),json.get("packageId").getAsString(),json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.get("componentIds"),String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"),double[].class),json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(),json.get("feedStageNumber").getAsInt(),json.get("topPressurePascal").getAsDouble(),
                json.get("stagePressureDropPascal").getAsDouble(),specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"),V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"),V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"),V3PumparoundSpec[].class)),json.get("columnDiameterMetres").getAsDouble());
    }
}
