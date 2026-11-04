package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Independently exported float64 predictions, not solver-generated expected answers. */
class V3BundledRegroupedModelTest {
    private static final Gson JSON=new Gson();
    @Test void packagedWeightsAndDecoderMatchIndependentFloat64Fixtures() throws Exception {
        JsonObject fixture;
        try(var stream=getClass().getResourceAsStream("/science/column/v3/neural/regrouped-predictions.json")) {
            assertNotNull(stream);fixture=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        byte[] payload,sidecar;
        try(var p=getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.payload.json");var s=getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.sidecar.json")) {
            assertNotNull(p);assertNotNull(s);payload=p.readAllBytes();sidecar=s.readAllBytes();
        }
        assertEquals(fixture.get("payloadSha256").getAsString(),V3NeuralRegistry.TransformerArtifact.sha256(payload));
        var model=V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload),new ByteArrayInputStream(sidecar)).initializer();
        assertEquals(88852,model.parameterCount());
        assertEquals(fixture.get("modelId").getAsString(),model.modelId());
        for(var entry:fixture.getAsJsonArray("fixtures")) {
            var row=entry.getAsJsonObject();var input=input(row.getAsJsonObject("input"));
            assertTrue(model.supported(input));var raw=model.raw(input,V3SolveControl.UNBOUNDED);
            var expectedRaw=JSON.fromJson(row.get("raw"),double[][].class);
            for(int n=0;n<expectedRaw.length;n++)assertArrayEquals(expectedRaw[n],raw.values()[n],1e-4);
            assertArrayEquals(JSON.fromJson(row.get("branchLogits"),double[].class),raw.branchLogits(),1e-5);
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
        JsonObject fixture;
        try(var stream=getClass().getResourceAsStream("/science/column/v3/neural/regrouped-predictions.json")) {
            assertNotNull(stream);fixture=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var base=input(fixture.getAsJsonArray("fixtures").get(0).getAsJsonObject().getAsJsonObject("input"));
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
    /** The model has three draw slots; a column with the fourth draw the input allows is left to the classical seed. */
    @Test void aFourthSideDrawIsAValidInputTheBundledModelDeclines() throws Exception {
        JsonObject fixture;
        try(var stream=getClass().getResourceAsStream("/science/column/v3/neural/regrouped-predictions.json")) {
            assertNotNull(stream);fixture=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        V3ColumnInput base=null;
        for(var entry:fixture.getAsJsonArray("fixtures")){var candidate=input(entry.getAsJsonObject().getAsJsonObject("input"));if(candidate.stageCount()>=4){base=candidate;break;}}
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
    private static V3ColumnInput input(JsonObject json) {
        var specs=new ArrayList<V3ColumnSpecification>();
        for(var value:json.getAsJsonArray("specifications")) {
            var s=value.getAsJsonObject();
            if(s.has("kelvin"))specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if(s.has("ratio"))specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(),json.get("packageId").getAsString(),json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"),String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"),double[].class),json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(),json.get("feedStageNumber").getAsInt(),json.get("topPressurePascal").getAsDouble(),
                json.get("stagePressureDropPascal").getAsDouble(),specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"),V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"),V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"),V3PumparoundSpec[].class)), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }
}
