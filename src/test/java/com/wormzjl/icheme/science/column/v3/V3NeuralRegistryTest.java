package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.icheme.science.material.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;

/**
 * A model binds by compound list (batch 2026-11-04-refinery-compounds, plan section 3.3): the request's active compounds
 * lie in the model's list, the records the model was trained on are current, and the request's physics on its active
 * compounds is the model's, compared by content. Package ids and package records play no part.
 */
class V3NeuralRegistryTest {
    private static final String MAIN="icheme:tjl20_methane",NETWORK="icheme:tjl20_methane_nitrogen";
    private static final String ROOT="data/icheme/materials/";
    private static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    @AfterEach void reset(){MaterialRuntime.reset();}
    private static V3ColumnInput input() {
        var p=CATALOG.requirePackage(MAIN);double[] feed=new double[p.components().size()];
        feed[feed.length-2]=50;feed[feed.length-8]=50;
        return V3TestInputs.dry(1,MAIN,"test:reader",new V3ComponentBasis(p.components()),feed,550,2,1,250000,750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(0)));
    }
    private static V3ColumnInput on(V3ColumnInput input,String packageId,List<String> ids) {
        double[] feed=new MaterialAxis(ids).project(new MaterialAxis(input.componentBasis().componentIds()),input.feedComponentMolarFlowsMolPerSecond());
        return new V3ColumnInput(1,packageId,input.assayId(),new V3ComponentBasis(ids),feed,input.feedTemperatureKelvin(),input.stageCount(),
                input.feedStageNumber(),input.topPressurePascal(),input.stagePressureDropPascal(),input.specifications(),input.sideDraws(),input.steamFeeds(),input.pumparounds(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }
    /** A synthetic model trained on MAIN's records (its physics names them by their declared revisions). */
    private static V3NeuralRegistry.Entry entry(String name,int priority) throws Exception {
        var d=V3RegroupedTransformerTest.document(input());d.addProperty("modelId",name);
        var model=V3AnchorTransformerInitializer.read(stream(d.toString()), V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        return new V3NeuralRegistry.Entry(priority,model,"0".repeat(64));
    }
    private static V3NeuralRegistry registry(String name) throws Exception { return new V3NeuralRegistry(List.of(entry(name,1))); }
    private static InputStream stream(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));}
    private static MaterialCatalog edit(String path,Consumer<JsonObject> change) {
        var resources=new HashMap<>(CATALOG.resources());
        var data=JsonParser.parseString(resources.get(ROOT+path)).getAsJsonObject();change.accept(data);
        resources.put(ROOT+path,data.toString());
        return MaterialCatalog.parse(resources);
    }
    private static void bump(JsonObject record){record.addProperty("revision",record.get("revision").getAsString()+"-edited");}
    @Test void deterministicPriorityAndSecondFixtureModelNeedNoProductionConstants() throws Exception {
        var a=entry("test:a",100);var b=entry("test:b",101);
        var catalog=CATALOG;var input=input();
        assertEquals("test:b",new V3NeuralRegistry(List.of(a,b)).bind(catalog,input).modelId());
        assertEquals("test:a",new V3NeuralRegistry(List.of(entry("test:b",100),a)).bind(catalog,input).modelId());
        assertSame(V3NeuralInitializer.UNAVAILABLE,new V3NeuralRegistry(List.of()).bind(catalog,input));
        assertThrows(IllegalArgumentException.class,()->new V3NeuralRegistry(List.of(a,a)));
    }
    /**
     * The network package (nitrogen at zero), a package on fewer compounds and one in another order all bind the model,
     * and the seed is the base seed on the request's own axis; a non-zero compound outside the model's list declines.
     */
    @Test void aRequestBindsByItsActiveCompoundsAndNeverDiscardsNonzeroFeed() throws Exception {
        var catalog=CATALOG;var input=input();var registry=registry("test:list");
        var base=registry.bind(catalog,input).predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
        var network=on(input,NETWORK,catalog.requirePackage(NETWORK).components());
        var prediction=registry.bind(catalog,network).predict(network,V3SolveControl.UNBOUNDED).orElseThrow();
        assertEquals(network,prediction.input());assertArrayEquals(base.temperatures(),prediction.temperatures());
        assertEquals(catalog.requirePackage(NETWORK).scientificRevision(),prediction.propertyRevision());
        var mainAxis=new MaterialAxis(input.componentBasis().componentIds());var networkAxis=new MaterialAxis(network.componentBasis().componentIds());
        for(int n=0;n<base.liquid().length;n++) {
            assertArrayEquals(networkAxis.project(mainAxis,base.liquid()[n]),prediction.liquid()[n]);
            assertArrayEquals(networkAxis.project(mainAxis,base.vapor()[n]),prediction.vapor()[n]);
        }
        double[] contaminated=network.feedComponentMolarFlowsMolPerSecond();contaminated[contaminated.length-1]=1e-100;
        var bad=V3TestInputs.dry(1,NETWORK,network.assayId(),network.componentBasis(),contaminated,550,2,1,250000,750,network.specifications());
        assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(catalog,bad),"nitrogen is outside the model's compounds");
        // A package on fewer compounds (MAIN without its zero methane): the model's other slots stay zero.
        var axis=new ArrayList<>(input.componentBasis().componentIds());axis.remove("Methane");
        var reduced=catalog.restrict(MAIN,axis);var fewer=on(input,MAIN,axis);
        var padded=registry.bind(reduced,fewer).predict(fewer,V3SolveControl.UNBOUNDED).orElseThrow();
        assertArrayEquals(base.temperatures(),padded.temperatures());assertEquals(fewer,padded.input());
        // A package listing the same compounds in another order: the lens reads them in the model's order.
        var reversed=new ArrayList<>(input.componentBasis().componentIds());Collections.reverse(reversed);
        var resources=new HashMap<>(catalog.resources());
        var record=JsonParser.parseString(resources.get(ROOT+"packages/tjl20.json")).getAsJsonObject();
        record.addProperty("id","test:reversed");record.add("components",new Gson().toJsonTree(reversed));
        resources.put(ROOT+"packages/reversed.json",record.toString());
        var reorder=MaterialCatalog.parse(resources);var reordered=on(input,"test:reversed",reversed);
        var reorderedSeed=registry.bind(reorder,reordered).predict(reordered,V3SolveControl.UNBOUNDED).orElseThrow();
        for(int n=0;n<base.liquid().length;n++)assertArrayEquals(new MaterialAxis(reversed).project(mainAxis,base.liquid()[n]),reorderedSeed.liquid()[n]);
    }
    /**
     * What a model reads is its records: an edit of a package record alone (its revision, its evidence) leaves it on, an
     * assay edit too; a revision bump of one crude dataset turns it off, whether or not the request feeds that compound.
     */
    @Test void onlyTheTrainedRecordsDecideAndAPackageRecordEditLeavesTheModelOn() throws Exception {
        var input=input();var registry=registry("test:records");
        var packageEdit=edit("packages/tjl20.json",o->{bump(o);o.getAsJsonArray("advisory_evidence").add("TEST: an edited package record");});
        // The package record is in no identity (option B, 2026-11-04): a seed's propertyRevision stands as well.
        assertEquals(CATALOG.requirePackage(MAIN).scientificRevision(),packageEdit.requirePackage(MAIN).scientificRevision());
        assertEquals("test:records",registry.bind(packageEdit,input).modelId());
        var assayEdit=edit("assays/tjl20.json",o->{var amounts=o.getAsJsonObject("amounts_by_component");amounts.addProperty("Methane",amounts.get("Methane").getAsDouble()*2);});
        assertEquals("test:records",registry.bind(assayEdit,input).modelId());
        // crude_pc05 is fed, crude_pc08 is not: a bump of either dataset's revision is a stale model.
        for(String fraction:List.of("crude_pc05","crude_pc08"))
            assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(edit("properties/"+fraction+".json",V3NeuralRegistryTest::bump),input),fraction);
        // A pair library's bump, or the water model's, is a stale model too.
        assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(edit("interactions/tjl20.json",V3NeuralRegistryTest::bump),input));
    }
    @Test void admissionSnapshotSurvivesReload() throws Exception {
        var catalog=CATALOG;var registry=registry("test:captured");var input=input();
        var bound=registry.bind(catalog,input);var expected=bound.predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
        // Nothing is hashed, and each record declares its own revision (owner decisions 2026-10-26): the edit of the shared
        // methane record bumps that record alone, which the model was trained on.
        var changed=edit("properties/tjl20_methane.json",o->{o.addProperty("molecular_weight_kg_per_mol",.017);bump(o);});
        assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(changed,input));
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var pending=worker.submit(()->{ready.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release");return bound.predict(input,V3SolveControl.UNBOUNDED).orElseThrow();});
            assertTrue(ready.await(5,TimeUnit.SECONDS));MaterialRuntime.publish(changed);release.countDown();
            var actual=pending.get(10,TimeUnit.SECONDS);assertArrayEquals(expected.temperatures(),actual.temperatures());
            assertEquals(catalog.requirePackage(MAIN).scientificRevision(),actual.propertyRevision());
            assertFalse(isCurrent(MAIN,actual.propertyRevision()));
        } finally {release.countDown();}
        assertThrows(CancellationException.class,()->bound.predict(input,()->{throw new CancellationException();}));
    }
    @Test void boundedRegistryRejectsPathsAndMismatchedPipelineAndAcceptsEmptyFallback() throws Exception {
        assertSame(V3NeuralInitializer.UNAVAILABLE,V3NeuralRegistry.read(stream("{\"schemaVersion\":1,\"entries\":[]}"),p->null).bind(CATALOG,input()));
        String manifest;
        try(var s=getClass().getResourceAsStream(V3NeuralRegistry.REGISTRY)){manifest=new String(s.readAllBytes(),StandardCharsets.UTF_8);}
        var data=JsonParser.parseString(manifest).getAsJsonObject();var row=data.getAsJsonArray("entries").get(0).getAsJsonObject();
        row.addProperty("pipelineSha256","0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.read(stream(data.toString()),getClass()::getResourceAsStream));
        row.addProperty("payload","/data/icheme/neural/../escape.json");
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.read(stream(data.toString()),p->{throw new AssertionError("Unsafe path was opened");}));
    }

    /** A result is current only for the exact resolved scientific dataset (moved from MaterialRuntime, which had no other caller). */
    private static boolean isCurrent(String packageId, String scientificRevision) {
        var p = MaterialRuntime.current().packages().get(packageId);
        return p != null && p.scientificRevision().equals(scientificRevision);
    }
}
