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
        var assayEdit=edit("assays/tjl20.json",o->{var amounts=o.getAsJsonObject("amounts_by_component");amounts.addProperty("Methane",amounts.get("Methane").getAsDouble()*2);});
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
        var tjl19=on(main,"icheme:tjl19_dwsim",CATALOG.requirePackage("icheme:tjl19_dwsim").components());
        // The request package lacks methane: the lens takes the catalog's default methane record.
        assertEquals("test:padded",registry("test:padded").bind(CATALOG,tjl19).modelId());
        var d=V3RegroupedTransformerTest.document(main);d.addProperty("modelId","test:unknown");
        var ids=d.getAsJsonArray("components");assertEquals("Methane",ids.get(0).getAsString());ids.set(0,new JsonPrimitive("Unobtainium"));
        d.getAsJsonObject("physics").getAsJsonArray("compounds").get(0).getAsJsonObject().addProperty("id","Unobtainium");
        var unknown=V3AnchorTransformerInitializer.read(stream(d.toString()),V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        assertSame(V3NeuralInitializer.UNAVAILABLE,new V3NeuralRegistry(List.of(new V3NeuralRegistry.Entry(1,unknown,"0".repeat(64)))).bind(CATALOG,tjl19));
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

    /** A result is current only for the exact resolved scientific dataset (moved from MaterialRuntime, which had no other caller). */
    private static boolean isCurrent(String packageId, String scientificRevision) {
        var p = MaterialRuntime.current().packages().get(packageId);
        return p != null && p.scientificRevision().equals(scientificRevision);
    }
}
