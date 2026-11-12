package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.TiaJuanaExtension.class)
class V3RegroupedTransformerTest {
    private static final Gson JSON = new Gson();
    private static V3ColumnInput input(String packageId) {
        var p = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog().requirePackage(packageId);
        double[] feed = new double[p.components().size()];
        feed[feed.length-2] = 50; feed[feed.length-8] = 50;
        return V3TestInputs.dry(1, packageId, "test:reader", new V3ComponentBasis(p.components()),
                feed, 550, 2, 1, 250000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(300),
                new V3ColumnSpecification.OrganicRefluxRatio(2), new V3ColumnSpecification.ReboilerDuty(0)));
    }
    static JsonObject document(V3ColumnInput input) {
        int c=input.componentBasis().componentCount(), nodes=76+c, global=54+c, out=4*c+5;
        var d=new JsonObject();
        d.addProperty("featureRevision","v3-anchor-augmented-regrouped-2");
        d.addProperty("modelType","anchor-augmented"); d.addProperty("anchorLayout","full");
        d.addProperty("baselineRevision",V3AnchorTransformerInitializer.NativeAnchor.REVISION); d.addProperty("modelId","test:synthetic");
        d.add("physics",physics(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog(),input.packageId()));
        d.add("components",JSON.toJsonTree(input.componentBasis().componentIds()));
        double[] z=input.feedComponentMolarFlowsMolPerSecond(); double total=Arrays.stream(z).sum();
        for(int i=0;i<z.length;i++)z[i]/=total;
        // One E7 family holding exactly this feed (sidecar schema 5): its mean, one unit axis and the origin as its only point.
        double[] axis=new double[z.length];axis[0]=1;
        var family=new JsonObject();family.addProperty("name","cdu");family.add("mean",JSON.toJsonTree(z));
        family.add("basis",JSON.toJsonTree(new double[][]{axis}));family.add("points",JSON.toJsonTree(new double[][]{{0}}));
        family.addProperty("inPlaneLimit",1e-8);family.addProperty("outOfPlaneLimit",1e-8);
        var families=new JsonArray();families.add(family);d.add("families",families);
        d.addProperty("offAxisLimit",0);d.add("lumpMap",new JsonObject());
        // A model is trained on the label without the derived-pairs suffix (W5 D1 of batch 2026-11-11-aqueous-solvents): the seed label.
        d.add("formulationRevisions",JSON.toJsonTree(List.of(V3ColumnCalculator.seedFormulationRevision(input))));
        d.add("branchesSeen",JSON.toJsonTree(new boolean[]{true,true,false}));
        d.addProperty("presenceThreshold",.02); d.addProperty("traceFloorFraction",V3TruncationSupport.TRACE_FLOOR_FRACTION);
        d.add("globalMin",JSON.toJsonTree(fill(global,-1e6))); d.add("globalMax",JSON.toJsonTree(fill(global,1e6)));
        d.add("designConstraints",JSON.toJsonTree(Map.of("minimumNodePressurePascal",50000,"maximumNodePressurePascal",300000,"steamAtSumpOnly",true,"pumparoundSplits",List.of("UNIFORM"))));
        var norms=new JsonObject();
        for(var prefix:List.of("x","g","y","b")) {
            int width=prefix.equals("x")?nodes:prefix.equals("g")?global:out;
            norms.add(prefix+"m",JSON.toJsonTree(fill(width,0))); norms.add(prefix+"scale",JSON.toJsonTree(fill(width,1)));
        }
        d.add("normalization",norms);
        var weights=new JsonObject(); linear(weights,"embed",nodes+out+4,64);
        norm(weights,"output.0"); linear(weights,"output.1",64,out);
        linear(weights,"branch.0",global,64); linear(weights,"branch.2",64,3);
        for(int i=0;i<2;i++) {
            String a="blocks."+i+".";
            norm(weights,a+"norm1"); norm(weights,a+"norm2");
            tensor(weights,a+"self_attn.in_proj_weight",0,192,64); tensor(weights,a+"self_attn.in_proj_bias",0,192);
            linear(weights,a+"self_attn.out_proj",64,64); linear(weights,a+"linear1",64,128); linear(weights,a+"linear2",128,64);
        }
        var bias=weights.getAsJsonObject("output.1.bias").getAsJsonArray("values");
        bias.set(0,new JsonPrimitive(370)); bias.set(1,new JsonPrimitive(1)); bias.set(2,new JsonPrimitive(1));
        d.add("weights",weights); return d;
    }
    /**
     * The physics a model trained on package {@code packageId} stores (the sidecar's physics, O7 of batch
     * 2026-11-04-open-thermo-data): the package's values, each compound's PR78 constants and ideal-gas fit, its non-zero
     * pairs and its water, over the bundled model's trained range.
     */
    static JsonObject physics(MaterialCatalog catalog,String packageId) {
        var p=catalog.requirePackage(packageId);var physics=new JsonObject();
        physics.addProperty("model",p.model());physics.addProperty("source","test: the values of package "+packageId);
        physics.addProperty("temperatureMinimumKelvin",298.15);physics.addProperty("temperatureMaximumKelvin",765.7654003015762);
        var compounds=new JsonArray();var pairs=new JsonArray();
        for(int i=0;i<p.components().size();i++) {
            var r=p.properties().get(i);var c=new JsonObject();c.addProperty("id",p.components().get(i));
            c.addProperty("criticalTemperatureKelvin",r.pr().criticalTemperature());c.addProperty("criticalPressurePascal",r.pr().criticalPressure());
            c.addProperty("acentricFactor",r.pr().acentricFactor());c.add("idealGasCp",JSON.toJsonTree(r.cp()));compounds.add(c);
            for(int j=i+1;j<p.components().size();j++)if(p.interactions().get(i).get(j)!=0) {
                var pair=new JsonObject();pair.addProperty("first",p.components().get(i));pair.addProperty("second",p.components().get(j));
                pair.addProperty("kij",p.interactions().get(i).get(j));pairs.add(pair);
            }
        }
        var w=p.water();var water=new JsonObject();
        water.addProperty("criticalTemperatureKelvin",w.criticalTemperature());water.addProperty("criticalPressurePascal",w.criticalPressure());
        water.add("saturationCoefficients",JSON.toJsonTree(w.saturation()));water.addProperty("boilingTemperatureKelvin",w.boilingTemperature());
        water.addProperty("vaporizationEnthalpyJoulesPerMol",w.vaporizationEnthalpy());water.addProperty("watsonExponent",w.watsonExponent());
        water.add("shomateCoefficients",JSON.toJsonTree(w.shomate()));
        physics.add("compounds",compounds);physics.add("pairs",pairs);physics.add("water",water);
        return physics;
    }
    private static double[] fill(int n,double value) { double[] a=new double[n]; Arrays.fill(a,value); return a; }
    private static void tensor(JsonObject w,String name,double v,int... shape) {
        int count=1; for(int n:shape)count*=n;
        w.add(name,JSON.toJsonTree(Map.of("shape",shape,"values",fill(count,v))));
    }
    private static void linear(JsonObject w,String name,int in,int out) {tensor(w,name+".weight",0,out,in);tensor(w,name+".bias",0,out);}
    private static void norm(JsonObject w,String name) {tensor(w,name+".weight",1,64);tensor(w,name+".bias",0,64);}
    private static InputStream stream(JsonObject d) {return new ByteArrayInputStream(d.toString().getBytes(StandardCharsets.UTF_8));}
    @Test void widthsFollowBothActiveHydrocarbonAxes() throws Exception {
        for(String p:List.of("icheme:tjl19_dwsim","icheme:tjl20_methane")) {
            var input=input(p); var model=V3AnchorTransformerInitializer.read(stream(document(input)), V3FactorizedNeuralFeatures.DecodeOptions.NONE);
            assertTrue(model.supported(input));
            assertEquals(input.componentBasis().componentCount()==19?88852:88208,model.parameterCount());
            assertEquals(4*input.componentBasis().componentCount()+5,model.raw(input,V3SolveControl.UNBOUNDED).values()[0].length);
        }
    }
    @Test void rejectsRetiredEncodingAndWrongTensors() {
        var d=document(input("icheme:tjl20_methane")); d.addProperty("featureRevision","v3-anchor-augmented-1");
        assertThrows(IllegalArgumentException.class,()->V3AnchorTransformerInitializer.read(stream(d), V3FactorizedNeuralFeatures.DecodeOptions.NONE));
        d.addProperty("featureRevision","v3-anchor-augmented-regrouped-2");
        d.getAsJsonObject("weights").getAsJsonObject("embed.weight").add("shape",JSON.toJsonTree(new int[]{64,185}));
        assertThrows(IllegalArgumentException.class,()->V3AnchorTransformerInitializer.read(stream(d), V3FactorizedNeuralFeatures.DecodeOptions.NONE));
    }
    @Test void cancellationAndSharedWeightsAreDeterministic() throws Exception {
        var input=input("icheme:tjl20_methane"); var model=V3AnchorTransformerInitializer.read(stream(document(input)), V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        assertThrows(CancellationException.class,()->model.raw(input,()->{throw new CancellationException();}));
        var expected=model.raw(input,V3SolveControl.UNBOUNDED);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->model.raw(input,V3SolveControl.UNBOUNDED));
            var b=workers.submit(()->model.raw(input,V3SolveControl.UNBOUNDED));
            for(var result:List.of(a.get(),b.get())) {
                assertArrayEquals(expected.branchLogits(),result.branchLogits());
                for(int n=0;n<expected.values().length;n++)assertArrayEquals(expected.values()[n],result.values()[n]);
            }
        }
    }
    @Test void editedPhysicsIsExcludedWhileCapturedCatalogRemainsEligible() throws Exception {
        var input=input("icheme:tjl20_methane"); var original=MaterialCatalog.bundled();
        var model=V3AnchorTransformerInitializer.read(stream(document(input)), V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        var registry=new V3NeuralRegistry(List.of(new V3NeuralRegistry.Entry(1,model,"0".repeat(64))));
        // crude_pc05 is fed, crude_pc08 is not: the binding compares the fed compounds' values (O7, decision E8).
        for(String fraction:List.of("crude_pc05","crude_pc08")) {
            var records=new HashMap<>(original.resources());
            String key=records.keySet().stream().filter(k->k.endsWith("properties/"+fraction+".json")).findFirst().orElseThrow();
            var edited=JsonParser.parseString(records.get(key)).getAsJsonObject();
            var pr=edited.getAsJsonObject("models").getAsJsonObject("pr78");
            pr.addProperty("critical_temperature_kelvin",pr.get("critical_temperature_kelvin").getAsDouble()*1.02);
            // Nothing is hashed (owner decisions 2026-10-26): the edit declares itself with the record's own revision.
            edited.addProperty("revision",edited.get("revision").getAsString()+"-edited");
            records.put(key,edited.toString());
            var changed=MaterialCatalog.parse(records);
            if(fraction.equals("crude_pc05"))assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(changed,input),"Tc +2 % on a fed compound");
            else assertEquals(model.modelId(),registry.bind(changed,input).modelId(),"a compound the request does not feed");
        }
        assertEquals(model.modelId(),registry.bind(original,input).modelId());
    }
    @Test void sidecarPinsPayloadAndRejectsOverlappingFields() throws Exception {
        var d=document(input("icheme:tjl20_methane")); var payload=new JsonObject();
        for(String key:List.of("featureRevision","modelType","anchorLayout","baselineRevision","components","normalization","weights"))payload.add(key,d.remove(key));
        payload.addProperty("schemaVersion",1); d.addProperty("schemaVersion",5);
        d.addProperty("payloadSha256",V3NeuralRegistry.TransformerArtifact.sha256(payload.toString().getBytes(StandardCharsets.UTF_8)));
        d.addProperty("decoder","ZERO_PHASE_FLOOR_10"); d.addProperty("candidateRule","SINGLE"); d.addProperty("correctionRule","PROGRESS");
        assertEquals(88852,V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(d)).initializer().parameterCount());
        // Schema 5 only: a schema 3 sidecar (the trained records by id@revision) and a schema 4 one (blend edges) are refused, the
        // physics holds the model's compounds in order, and the sidecar holds exactly its fields.
        for(int schema:new int[]{3,4}) {
            var old=d.deepCopy(); old.addProperty("schemaVersion",schema);
            assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(old)));
        }
        var edges=d.deepCopy(); edges.remove("families"); edges.add("compositionEdges",new JsonArray());
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(edges)));
        var named=d.deepCopy(); named.getAsJsonObject("physics").addProperty("water","icheme:water@water-iapws95-shomate-r3");
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(named)));
        var shuffled=d.deepCopy(); var compounds=shuffled.getAsJsonObject("physics").getAsJsonArray("compounds");
        var first=compounds.remove(0); compounds.add(first);
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(shuffled)));
        var unfit=d.deepCopy(); unfit.getAsJsonObject("physics").getAsJsonArray("compounds").get(0).getAsJsonObject().add("idealGasCp",new JsonArray());
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(unfit)));
        payload.addProperty("baselineRevision","changed");
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(d)));
        payload.addProperty("baselineRevision",V3AnchorTransformerInitializer.NativeAnchor.REVISION); d.add("weights",payload.get("weights"));
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.TransformerArtifact.read(stream(payload),stream(d)));
    }
}
