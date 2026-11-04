package com.wormzjl.icheme.science.material;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiquidMixtureCorrectionTest {
    private static final double G=1.0956;
    private static final String METHOD="data/icheme/materials/methods/crude_pr78.json";
    /** Compounds 0, 1 and 2 take part; compound 3 only dilutes them. */
    private static LiquidMixtureCorrection model() { return new LiquidMixtureCorrection(G,List.of(0,1,2),4); }
    @Test void factoredRuleMatchesIndependentPairSum() {
        var model=model();var random=new Random(19278);
        for (int sample=0;sample<100;sample++) {
            double[] n=random.doubles(4,.0001,10).toArray();double sum=java.util.Arrays.stream(n).sum(),expected=0;
            for (int i=0;i<3;i++) for (int j=i+1;j<3;j++) expected+=2*G*n[i]/sum*n[j]/sum;
            assertEquals(expected,model.logCorrection(n),2e-14);
        }
    }
    @Test void pureLimitsAndInertDilutionDoNotChangePureTransport() {
        for (int i=0;i<4;i++) {double[] n=new double[4];n[i]=7;assertEquals(0,model().logCorrection(n),1e-14);}
        double base=model().logCorrection(new double[]{1,1,0,0});
        assertEquals(G/2,base,1e-15);
        assertEquals(base/4,model().logCorrection(new double[]{1,1,0,2}),1e-14);
        assertEquals(base,model().logCorrection(new double[]{5,5,0,0}),1e-14);
        assertEquals(0,new LiquidMixtureCorrection(0,List.of(),4).logCorrection(new double[]{1,2,3,4}));
    }
    @Test void malformedTermsAndMixturesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new LiquidMixtureCorrection(Double.NaN,List.of(0),2));
        assertThrows(IllegalArgumentException.class,()->new LiquidMixtureCorrection(G,List.of(2),2));
        assertThrows(IllegalArgumentException.class,()->new LiquidMixtureCorrection(G,List.of(1,0),2));
        assertThrows(IllegalArgumentException.class,()->model().logCorrection(new double[4]));
        assertThrows(IllegalArgumentException.class,()->model().logCorrection(new double[]{1,0,-1,0}));
        assertThrows(IllegalArgumentException.class,()->model().logCorrection(new double[3]));
    }
    @Test void theCrudeMethodTermTakesTheTwelveCutsOnEachPackageAxis() {
        var catalog=MaterialCatalog.bundled();
        for (String id:List.of("icheme:tjl20_methane","icheme:tjl19_dwsim","icheme:refinery")) {
            var axis=catalog.requirePackage(id).components();var term=catalog.liquidMixture(id);
            assertEquals(G,term.coefficient());assertEquals(axis.size(),term.components());
            assertEquals(java.util.stream.IntStream.rangeClosed(1,12).mapToObj("crude_pc%02d"::formatted).toList(),
                    term.participants().stream().map(axis::get).toList(),id);
        }
        assertTrue(catalog.liquidMixture("icheme:pilot_cryogenic").participants().isEmpty());
    }
    @Test void transportEditChangesOnlyTransportAndNamesResourceOnError() {
        // The liquid-mixture term is the crude method's: an edit there moves every crude package's transport alone.
        String packageId="icheme:tjl20_methane";
        var baseline=MaterialCatalog.bundled();
        var changed=MaterialCatalog.parse(edited(baseline,o->{o.addProperty("coefficient",2.0);o.addProperty("revision","edited-r1");}));
        var axis=baseline.requirePackage(packageId).components();
        assertEquals(baseline.physicsContent(packageId,axis),changed.physicsContent(packageId,axis),"the phase physics is untouched");
        assertNotEquals(baseline.liquidMixture(packageId),changed.liquidMixture(packageId));
        assertEquals(2.0,changed.liquidMixture(packageId).coefficient());
        for (Consumer<com.google.gson.JsonObject> bad:List.<Consumer<com.google.gson.JsonObject>>of(
                o->o.getAsJsonArray("components").add("NotACompound"),
                o->o.getAsJsonArray("components").add("crude_pc01"),
                o->o.addProperty("type","symmetric_pair_groups_v1"),
                o->o.remove("revision"))) {
            var resources=edited(baseline,bad);
            assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources)).getMessage().contains(METHOD));
        }
        // The old per-record descriptor is refused, naming the record: participation is the method's list.
        var resources=new HashMap<>(baseline.resources());String cut="data/icheme/materials/properties/crude_pc05.json";
        var record=JsonParser.parseString(resources.get(cut)).getAsJsonObject();
        record.add("liquid_mixture_descriptor",JsonParser.parseString("{\"participation\":1,\"residue_retention\":0,\"activation_slope\":0}"));
        resources.put(cut,record.toString());
        var refused=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources)).getMessage();
        assertTrue(refused.contains(cut) && refused.contains("liquid_mixture.components"),refused);
    }
    private static HashMap<String,String> edited(MaterialCatalog baseline,Consumer<com.google.gson.JsonObject> edit) {
        var resources=new HashMap<>(baseline.resources());
        resources.keySet().removeIf(key->key.contains("/networks/")); // Isolated column transport override, not a shared network preset.
        var record=JsonParser.parseString(resources.get(METHOD)).getAsJsonObject();
        edit.accept(record.getAsJsonObject("liquid_mixture"));
        resources.put(METHOD,record.toString());
        return resources;
    }
}
