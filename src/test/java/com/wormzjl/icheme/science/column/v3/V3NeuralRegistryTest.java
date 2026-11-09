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
 * lie in the model's list, and the request's values on its active compounds are the model's trained values within the
 * tolerances of decision E9 (O7 of batch 2026-11-04-open-thermo-data). Package ids, record ids and revisions play no part.
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
     * Values decide, never record ids or revisions (owner decision E8, O7 of batch 2026-11-04-open-thermo-data): a package
     * record edit, an assay edit, a dataset re-revisioned or renamed with equal values, a pair library's or the water's
     * revision bump all leave the model on; a fed compound's value within 1 % or a fed pair within 0.001 too (decision E9).
     */
    @Test void valuesDecideAndNoRecordIdOrRevisionTakesPart() throws Exception {
        var input=input();var registry=registry("test:values");
        var packageEdit=edit("packages/tjl20.json",o->{bump(o);o.getAsJsonArray("advisory_evidence").add("TEST: an edited package record");});
        // The package record is in no identity (option B, 2026-11-04): a seed's propertyRevision stands as well.
        assertEquals(CATALOG.requirePackage(MAIN).scientificRevision(),packageEdit.requirePackage(MAIN).scientificRevision());
        assertEquals("test:values",registry.bind(packageEdit,input).modelId());
        var assayEdit=edit("assays/medium_crude.json",o->{var amounts=o.getAsJsonObject("amounts_by_component");amounts.addProperty("Methane",amounts.get("Methane").getAsDouble()*2);});
        assertEquals("test:values",registry.bind(assayEdit,input).modelId());
        // crude_pc05 is fed, crude_pc08 is not: a revision bump of either dataset, the pair library or the water is no change.
        for(String path:List.of("properties/crude_pc05.json","properties/crude_pc08.json","interactions/tjl20.json","water/water.json"))
            assertEquals("test:values",registry.bind(edit(path,V3NeuralRegistryTest::bump),input).modelId(),path);
        // The fed dataset under another id (its compound's default names the new id), with its values.
        var resources=new HashMap<>(CATALOG.resources());
        var record=JsonParser.parseString(resources.get(ROOT+"properties/crude_pc05.json")).getAsJsonObject();
        record.addProperty("id","icheme:crude_pc05_renamed");bump(record);resources.put(ROOT+"properties/crude_pc05.json",record.toString());
        var component=JsonParser.parseString(resources.get(ROOT+"components/crude_pc05.json")).getAsJsonObject();
        component.addProperty("properties","icheme:crude_pc05_renamed");resources.put(ROOT+"components/crude_pc05.json",component.toString());
        var renamed=MaterialCatalog.parse(resources);
        assertEquals("icheme:crude_pc05_renamed",renamed.requirePackage(MAIN).properties().get(renamed.requirePackage(MAIN).components().indexOf("crude_pc05")).id());
        assertEquals("test:values",registry.bind(renamed,input).modelId(),"a renamed dataset with the same values");
        // Within the tolerance: Tc +0.5 % on the fed compound, a fed pair at 0.0005; beyond it: Tc +2 %, a pair at 0.002.
        assertEquals("test:values",registry.bind(edit("properties/crude_pc05.json",o->criticalTemperature(o,1.005)),input).modelId());
        assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(edit("properties/crude_pc05.json",o->criticalTemperature(o,1.02)),input));
        assertEquals("test:values",registry.bind(edit("properties/crude_pc08.json",o->criticalTemperature(o,1.02)),input).modelId(),"not fed");
        assertEquals("test:values",registry.bind(edit("interactions/tjl20.json",o->pair(o,0.0005)),input).modelId());
        assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(edit("interactions/tjl20.json",o->pair(o,0.002)),input));
    }
    /** A compound of the model that neither the request package nor the catalog holds makes the model unavailable. */
    @Test void aModelCompoundWithoutARecordIsUnavailable() throws Exception {
        var main=input();
        // TJL19 is a test fixture (O3 of batch 2026-11-04-open-thermo-data).
        var catalog=com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        var tjl19=on(main,"icheme:tjl19_dwsim",catalog.requirePackage("icheme:tjl19_dwsim").components());
        // The request package lacks methane: the lens takes the catalog's default methane record.
        assertEquals("test:padded",registry("test:padded").bind(catalog,tjl19).modelId());
        var d=V3RegroupedTransformerTest.document(main);d.addProperty("modelId","test:unknown");
        var ids=d.getAsJsonArray("components");assertEquals("Methane",ids.get(0).getAsString());ids.set(0,new JsonPrimitive("Unobtainium"));
        d.getAsJsonObject("physics").getAsJsonArray("compounds").get(0).getAsJsonObject().addProperty("id","Unobtainium");
        var unknown=V3AnchorTransformerInitializer.read(stream(d.toString()),V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        assertSame(V3NeuralInitializer.UNAVAILABLE,new V3NeuralRegistry(List.of(new V3NeuralRegistry.Entry(1,unknown,"0".repeat(64)))).bind(catalog,tjl19));
    }
    /** The comparison evaluates the water's functions as the column does ({@link V3WaterProperties}). */
    @Test void theComparisonEvaluatesTheColumnsWaterForms() {
        var water=CATALOG.requirePackage(MAIN).water();var values=TrainedPhysics.Water.of(water);
        for(double t=water.triplePoint();t<=900;t+=7.3) {
            if(t<water.criticalTemperature()) {
                assertEquals(com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.saturationPressurePascal(water,t),values.saturationPressure(t),1e-12*values.saturationPressure(t));
                assertEquals(com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.vaporizationEnthalpy(water,t),values.vaporizationEnthalpy(t),1e-9);
            }
            assertEquals(com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.vaporMolarEnthalpy(water,t),values.vaporEnthalpy(t),1e-9);
            assertEquals(com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.dVaporMolarEnthalpyDT(water,t),values.vaporHeatCapacity(t),1e-12);
        }
    }
    private static void criticalTemperature(JsonObject record,double factor) {
        var pr=record.getAsJsonObject("models").getAsJsonObject("pr78");
        pr.addProperty("critical_temperature_kelvin",pr.get("critical_temperature_kelvin").getAsDouble()*factor);bump(record);
    }
    /** A pair between the two fed fractions, which the bundled library leaves zero. */
    private static void pair(JsonObject library,double kij) {
        var pair=new JsonObject();pair.addProperty("first","crude_pc05");pair.addProperty("second","crude_pc11");pair.addProperty("kij",kij);
        library.getAsJsonArray("pairs").add(pair);bump(library);
    }
    @Test void admissionSnapshotSurvivesReload() throws Exception {
        var catalog=CATALOG;var registry=registry("test:captured");var input=input();
        var bound=registry.bind(catalog,input);var expected=bound.predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
        // A fed compound's value moved past the tolerance (Tc +2 %, decision E9) turns the model off for new bindings.
        var changed=edit("properties/crude_pc05.json",o->criticalTemperature(o,1.02));
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

    // ------------------------------------------------ unknown compounds and the E7 family check (batch 2026-11-05-neural-retrain, N5)

    /**
     * A schema-5 model: one family around {@link #input()}'s feed (its two fed compounds, the plane their split moves in),
     * its trained composition at the family's origin, and the given off-axis policy.
     */
    private static V3NeuralRegistry.Entry unknowns(double inPlane,double outOfPlane,double limit,Map<String,String> lumps) throws Exception {
        var d=family(inPlane,outOfPlane,limit,lumps);
        return new V3NeuralRegistry.Entry(1,V3AnchorTransformerInitializer.read(stream(d.toString()),V3FactorizedNeuralFeatures.DecodeOptions.NONE),"0".repeat(64));
    }
    private static JsonObject family(double inPlane,double outOfPlane,double limit,Map<String,String> lumps) {
        var input=input();var d=V3RegroupedTransformerTest.document(input);d.addProperty("modelId","test:unknowns");
        int c=input.componentBasis().componentCount();double[] mean=new double[c],u=new double[c];
        mean[c-2]=.5;mean[c-8]=.5;u[c-2]=Math.sqrt(.5);u[c-8]=-Math.sqrt(.5);
        var json=new Gson();var family=new JsonObject();family.addProperty("name","cdu");
        family.add("mean",json.toJsonTree(mean));family.add("basis",json.toJsonTree(new double[][]{u}));
        family.add("points",json.toJsonTree(new double[][]{{0}}));
        family.addProperty("inPlaneLimit",inPlane);family.addProperty("outOfPlaneLimit",outOfPlane);
        var families=new JsonArray();families.add(family);d.add("families",families);
        d.addProperty("offAxisLimit",limit);d.add("lumpMap",json.toJsonTree(lumps));
        return d;
    }
    /** {@link #input()} on the network package: {@code a} and {@code b} mol/s on its two compounds, {@code third} on a third, nitrogen. */
    private static V3ColumnInput network(double a,double b,double third,double nitrogen) {
        var p=CATALOG.requirePackage(NETWORK);int c=p.components().size()-1;double[] feed=new double[c+1];
        feed[c-2]=a;feed[c-8]=b;feed[c-5]=third;feed[c]=nitrogen;
        return V3TestInputs.dry(1,NETWORK,"test:reader",new V3ComponentBasis(p.components()),feed,550,2,1,250000,750,input().specifications());
    }

    /** U1: each lump member takes the lump's flow by its share of the lump's feed; a lump of one keeps the value exactly. */
    @Test void aLumpSplitsByFeedShareAndOneMemberKeepsItsValueExactly() {
        var ids=List.of("A","X","B","Y");var axis=List.of("A","B");var lumps=Map.of("X","B");
        var lumping=V3NeuralRegistry.Lumping.of(ids,new double[]{60,4,36,0},axis,lumps,.05);
        assertArrayEquals(new double[]{60,40},lumping.lumped());assertArrayEquals(new double[]{60,36},lumping.knownFeed());
        assertEquals(List.of("A","B"),lumping.known());
        double a=0.123456789;double[] out=lumping.delump(new double[]{a,10});
        assertEquals(a,out[0]);assertEquals(1,out[1],1e-12);assertEquals(9,out[2],1e-12);assertEquals(0,out[3]);
        assertNull(V3NeuralRegistry.Lumping.of(ids,new double[]{60,6,34,0},axis,lumps,.05),"6 % off the axis");
        assertNull(V3NeuralRegistry.Lumping.of(ids,new double[]{60,4,36,1},axis,lumps,.05),"Y has no lump");
        assertNull(V3NeuralRegistry.Lumping.of(ids,new double[]{0,4,0,0},axis,lumps,1),"nothing known is fed");
        assertNull(V3NeuralRegistry.Lumping.of(ids,new double[]{60,1e-100,36,0},axis,lumps,0),"a model with no off-axis share takes no unknowns");
    }

    /** An unknown compound binds through its lump up to the model's share; the seed carries it on the request's own list. */
    @Test void anUnknownCompoundBindsThroughItsLumpUpToTheModelsShare() throws Exception {
        var registry=new V3NeuralRegistry(List.of(unknowns(1e-6,1e-9,.05,Map.of("Nitrogen","Methane"))));
        var four=network(50,50,0,100*.04/.96);
        var bound=registry.bind(CATALOG,four);assertEquals("test:unknowns",bound.modelId());
        var seed=bound.predict(four,V3SolveControl.UNBOUNDED).orElseThrow();
        assertEquals(four,seed.input());
        var ids=four.componentBasis().componentIds();int methane=ids.indexOf("Methane"),nitrogen=ids.indexOf("Nitrogen");
        for(int n=0;n<seed.liquid().length;n++) {
            // Methane is not fed, so its lump is all nitrogen: methane's slot goes to nitrogen.
            assertEquals(0,seed.liquid()[n][methane]);assertEquals(0,seed.vapor()[n][methane]);
            assertTrue(seed.liquid()[n][nitrogen]>=0&&Double.isFinite(seed.vapor()[n][nitrogen]));
        }
        assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(CATALOG,network(50,50,0,100*.06/.94)),"6 % is over the model's 5 %");
        assertSame(V3NeuralInitializer.UNAVAILABLE,new V3NeuralRegistry(List.of(unknowns(1e-6,1e-9,.05,Map.of()))).bind(CATALOG,four),
                "nitrogen has no lump");
    }

    /** E7 reads the known part renormalised: the in-plane distance to a trained composition and the residual off the plane. */
    @Test void theFamilyCheckReadsTheKnownPartRenormalised() throws Exception {
        var tight=new V3NeuralRegistry(List.of(unknowns(.05,1e-9,.05,Map.of("Nitrogen","Methane"))));
        assertNotSame(V3NeuralInitializer.UNAVAILABLE,tight.bind(CATALOG,network(50,50,0,4)),"the known part is the trained composition");
        // A 60/40 split lies in the plane, 0.1414 from the trained point.
        assertSame(V3NeuralInitializer.UNAVAILABLE,tight.bind(CATALOG,network(60,40,0,0)));
        var wide=new V3NeuralRegistry(List.of(unknowns(.2,1e-9,.05,Map.of("Nitrogen","Methane"))));
        assertNotSame(V3NeuralInitializer.UNAVAILABLE,wide.bind(CATALOG,network(60,40,0,0)));
        // A third known compound at 1 % leaves the plane: another crude.
        assertSame(V3NeuralInitializer.UNAVAILABLE,wide.bind(CATALOG,network(50,49,1,0)));
        assertNotSame(V3NeuralInitializer.UNAVAILABLE,new V3NeuralRegistry(List.of(unknowns(.2,.02,.05,Map.of()))).bind(CATALOG,network(50,49,1,0)));
    }

    /** Schema 5 reads only a complete off-axis policy within the owner's 5 %, an orthonormal basis and its composition families. */
    @Test void schemaFiveRejectsAnIncompleteOrWiderPolicy() {
        Map<String,String> lumps=Map.of("Nitrogen","Methane");
        for(Consumer<JsonObject> damage:List.<Consumer<JsonObject>>of(
                d->d.addProperty("offAxisLimit",.06),
                d->d.add("lumpMap",new Gson().toJsonTree(Map.of("Nitrogen","Argon"))),
                d->d.add("lumpMap",new Gson().toJsonTree(Map.of("Methane","Ethane"))),
                d->d.remove("lumpMap"),
                d->d.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("basis").get(0).getAsJsonArray().set(0,new JsonPrimitive(.5)),
                d->d.remove("offAxisLimit"),
                d->d.remove("families"))) {
            var d=family(.05,1e-9,.05,lumps);damage.accept(d);
            assertThrows(IllegalArgumentException.class,()->V3AnchorTransformerInitializer.read(stream(d.toString()),V3FactorizedNeuralFeatures.DecodeOptions.NONE));
        }
    }

    /** The bundled pair is schema 5: the artifact reader takes its families and off-axis policy, and refuses it as schema 4. */
    @Test void theArtifactReaderTakesSchemaFive() throws Exception {
        byte[] payload;String sidecar;
        try(var p=getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-open.payload.json");
            var s=getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-open.sidecar.json")) {
            payload=p.readAllBytes();sidecar=new String(s.readAllBytes(),StandardCharsets.UTF_8);
        }
        var side=JsonParser.parseString(sidecar).getAsJsonObject();
        assertEquals(5,side.get("schemaVersion").getAsInt());
        var loaded=V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload),stream(sidecar));
        assertEquals(.05,loaded.initializer().offAxisLimit());
        assertEquals(24,loaded.initializer().lumpMap().size());
        assertTrue(loaded.initializer().lumpMap().values().stream().allMatch(loaded.initializer().componentIds()::contains));
        side.addProperty("schemaVersion",4);
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload),stream(side.toString())));
    }

    /** A result is current only for the exact resolved scientific dataset (moved from MaterialRuntime, which had no other caller). */
    private static boolean isCurrent(String packageId, String scientificRevision) {
        var p = MaterialRuntime.current().packages().get(packageId);
        return p != null && p.scientificRevision().equals(scientificRevision);
    }
}
