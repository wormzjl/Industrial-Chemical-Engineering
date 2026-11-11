package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The data of batch 2026-11-11-aqueous-solvents, work package W2a (plan M3 to M5): the {@code group_counts},
 * {@code henry_in_water} and {@code models.pr78.alpha} fields of a dataset, the modified UNIFAC (Dortmund) table with its
 * water-hydrocarbon block, the activity model and the Henry evaluator. The pinned values come from
 * {@code tools/aqueous-solvents/dortmund-values.mjs} (run on DWSIM 10.2.5's modfac.txt and modfac_ip.txt).
 */
class AqueousSolventDataTest {
    private static final String ROOT="data/icheme/materials/";
    private static final String WORLD="icheme:refinery";
    private static final String HEXANE="properties/chemsep_n_hexane.json";
    private static final String CO2="properties/chemsep_carbon_dioxide.json";
    private static final String TABLE="group_interactions/modified_unifac_dortmund.json";
    private static final Map<String,Integer> ETHANOL=Map.of("CH3",1,"CH2",1,"OH",1),WATER=Map.of("H2O",1),N_HEXANE=Map.of("CH3",2,"CH2",4);
    private static final Map<String,Map<String,Integer>> GROUPS=Map.of("ethanol",ETHANOL,"water",WATER,"hexane",N_HEXANE);
    /** first, second, block, T K, x1, ln gamma1, ln gamma2: dortmund-values.mjs. */
    private static final Object[][] LN_GAMMA={
            {"ethanol","water",false,298.15,0.1,1.1123603898848837,0.023018040727706503},
            {"ethanol","water",false,298.15,0.5,0.22551158253726855,0.36451557682927960},
            {"ethanol","water",false,298.15,0.9,0.0065375089325970873,0.83789627704381742},
            {"ethanol","water",false,373.15,0.1,1.1691504813796252,0.030139636406019580},
            {"ethanol","water",false,373.15,0.5,0.20250654466774792,0.38531042854379083},
            {"ethanol","water",false,373.15,0.9,0.0055123741088012670,0.80446708081164964},
            {"ethanol","hexane",false,298.15,0.1,2.0384012587498050,0.073012403268489817},
            {"ethanol","hexane",false,298.15,0.5,0.51740318514383277,0.63978105310220312},
            {"ethanol","hexane",false,298.15,0.9,0.025758051856625330,1.8312157740180222},
            {"ethanol","hexane",false,373.15,0.1,1.6009077221639494,0.033300936524528577},
            {"ethanol","hexane",false,373.15,0.5,0.43012657670500054,0.49294877915747731},
            {"ethanol","hexane",false,373.15,0.9,0.019892909959201760,1.4725181460773686},
            {"water","hexane",false,298.15,0.1,4.1387474909063924,0.040515445268593540},
            {"water","hexane",false,298.15,0.5,1.7696944903159872,1.0886604995344449},
            {"water","hexane",false,298.15,0.9,0.15615629718148480,5.5049410605334002},
            {"water","hexane",false,373.15,0.1,2.6122538263668642,0.014707619923269752},
            {"water","hexane",false,373.15,0.5,1.4137622116625865,0.58975173272217229},
            {"water","hexane",false,373.15,0.9,0.15239857029493584,4.2590835407083194},
            {"water","hexane",true,298.15,0.1,5.7618823558872601,0.087833399779082499},
            {"water","hexane",true,298.15,0.5,2.2401466488664128,1.5354454587420006},
            {"water","hexane",true,298.15,0.9,0.25161384358986544,7.1221371559537916},
            {"water","hexane",true,373.15,0.1,5.0341670676275747,0.061195807299365912},
            {"water","hexane",true,373.15,0.5,2.0439415718464078,1.3358158628669745},
            {"water","hexane",true,373.15,0.9,0.20300943017351464,6.4393539547640994}};

    private static Map<String,String> changed(String path,Consumer<JsonObject> edit) { return MaterialCatalogTest.changed(path,edit); }
    private static ModifiedUnifac table() { return MaterialCatalog.bundled().modifiedUnifacTables().get("icheme:modified_unifac_dortmund"); }
    private static void refused(String path,Consumer<JsonObject> edit,String field,String label) {
        var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(path,edit)),label);
        assertTrue(error.getMessage().contains(path)&&error.getMessage().contains(field),label+": "+error.getMessage());
    }

    /** The shipped records: both tables, the counts of a hydrocarbon in both schemes, a gas's Henry constant; crude cuts carry none. */
    @Test void theBundledRecordsCarryTheGroupData() {
        var catalog=MaterialCatalog.bundled();var dortmund=table();
        assertEquals("dwsim-10.2.5-modfac-r1",dortmund.revision());assertEquals(108,dortmund.subgroups().size());
        assertEquals(7,dortmund.subgroup("H2O").orElseThrow().mainGroup());assertTrue(dortmund.block().orElseThrow().estimated());
        var counts=catalog.groupCounts(WORLD,"N-hexane");
        assertEquals(Map.of("CH3",2,"CH2",4),counts.get(ModifiedUnifac.SCHEME).counts());
        assertEquals(Map.of("CH3",2,"CH2",4),counts.get(Eppr78Groups.SCHEME).counts());
        assertEquals("chemsep-8.3-modfac-r1",counts.get(ModifiedUnifac.SCHEME).revision());
        assertEquals(Set.of(Eppr78Groups.SCHEME),catalog.groupCounts(WORLD,"CarbonDioxide").keySet(),"ChemSep gives CO2 no Dortmund groups");
        // ChemSep's PPR78 ring and alkenic carbons and its Dortmund ethylene are refused by the import; W2b authored them
        // from the group definitions (tools/compound-import/selection-aqueous-authored.json).
        var cyclohexane=catalog.groupCounts(WORLD,"Cyclohexane");
        assertEquals(Map.of("CH2_cyclic",6),cyclohexane.get(Eppr78Groups.SCHEME).counts());
        assertEquals("eppr78-group-definitions-r1",cyclohexane.get(Eppr78Groups.SCHEME).revision());
        assertEquals("chemsep-8.3-modfac-r1",cyclohexane.get(ModifiedUnifac.SCHEME).revision());
        assertEquals(Map.of("CH3",2,"CH2_alkenic",1,"C_alkenic",1),catalog.groupCounts(WORLD,"Isobutene").get(Eppr78Groups.SCHEME).counts());
        assertEquals(Map.of("CH2=CH",1),catalog.groupCounts(WORLD,"Ethylene").get(ModifiedUnifac.SCHEME).counts());
        for(String compound:List.of("Propylene","Butene-1","Butadiene"))
            assertEquals("eppr78-group-definitions-r1",catalog.groupCounts(WORLD,compound).get(Eppr78Groups.SCHEME).revision(),compound);
        assertTrue(catalog.groupCounts(WORLD,"crude_pc05").isEmpty());assertTrue(catalog.groupCounts(WORLD,"Water").isEmpty());
        var henry=catalog.henryInWater(WORLD,"CarbonDioxide").orElseThrow();
        assertEquals(List.of(-8.55445,4.01195,9.52345),List.of(henry.a(),henry.b(),henry.c()));
        assertTrue(catalog.henryInWater(WORLD,"N-hexane").isEmpty());
        int gases=0;for(String gas:List.of("Hydrogen","Nitrogen","Oxygen","CarbonMonoxide","CarbonDioxide","HydrogenSulfide","Methane","Ethane"))
            if(catalog.henryInWater(WORLD,gas).isPresent())gases++;
        assertEquals(8,gases);
        for(var p:catalog.packages().values())for(var property:p.properties())assertNull(property.pr()==null?null:property.pr().alpha(),property.id());
    }

    /**
     * The new fields are in no identity: every package's column, fluid and spine identity and its physics content are the
     * same with and without them, and so with the E-PPR78 matrix and the Dortmund table taken away.
     */
    @Test void theGroupDataAreInNoIdentity() {
        var bundled=MaterialCatalog.bundled();var resources=new HashMap<>(bundled.resources());int counts=0,henry=0;
        for(var entry:resources.entrySet())if(entry.getKey().startsWith(ROOT+"properties/")) {
            var o=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            boolean g=o.remove("group_counts")!=null,h=o.remove("henry_in_water")!=null;
            if(g)counts++;if(h)henry++;if(g||h)entry.setValue(o.toString());
        }
        assertEquals(33,counts,"the 33 defined compounds of the world list");assertEquals(8,henry);
        resources.remove(ROOT+TABLE);resources.remove(ROOT+"group_interactions/eppr78_2022.json");
        var without=MaterialCatalog.parse(resources);
        var edited=MaterialCatalog.parse(changed(HEXANE,o->{var d=o.getAsJsonObject("group_counts").getAsJsonObject(ModifiedUnifac.SCHEME);
            d.getAsJsonObject("counts").addProperty("CH2",5);d.addProperty("revision",d.get("revision").getAsString()+"-edited");}));
        for(var other:List.of(without,edited))for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),other.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),other.fluidRevision(p.id()),p.id());
            assertEquals(bundled.spineRevision(p.id()),other.spineRevision(p.id()),p.id());
            assertEquals(bundled.physicsContent(p.id(),p.components()),other.physicsContent(p.id(),p.components()),p.id());
        }
        assertEquals(5,edited.groupCounts(WORLD,"N-hexane").get(ModifiedUnifac.SCHEME).counts().get("CH2"));
    }

    /** Each invalid group count, scheme or field refuses the catalog, naming the record's file and the field. */
    @Test void anInvalidGroupCountRefusesTheCatalog() {
        Consumer<JsonObject> none=o->{};
        List<Consumer<JsonObject>> dortmund=List.of(
                d->d.getAsJsonObject("counts").addProperty("CH9",1),d->d.getAsJsonObject("counts").addProperty("CH2",0),
                d->d.getAsJsonObject("counts").addProperty("CH2",-1),d->d.getAsJsonObject("counts").addProperty("CH2",1.5),
                d->d.getAsJsonObject("counts").addProperty("CH2","4"),d->d.add("counts",new JsonObject()),d->d.addProperty("counts",4),
                d->d.remove("estimated"),d->d.remove("source"),d->d.addProperty("revision","chemsep modfac"),d->d.remove("revision"));
        for(int i=0;i<dortmund.size();i++) {
            var edit=dortmund.get(i);
            refused(HEXANE,o->edit.accept(o.getAsJsonObject("group_counts").getAsJsonObject(ModifiedUnifac.SCHEME)),"group_counts."+ModifiedUnifac.SCHEME,"Dortmund edit "+i);
        }
        refused(HEXANE,o->o.getAsJsonObject("group_counts").getAsJsonObject(Eppr78Groups.SCHEME).getAsJsonObject("counts").addProperty("CH2_ring",1),
                "unknown E-PPR78 group CH2_ring","E-PPR78 unknown group");
        refused(HEXANE,o->o.getAsJsonObject("group_counts").getAsJsonObject(Eppr78Groups.SCHEME).getAsJsonObject("counts").addProperty("CH3",0),
                "group_counts."+Eppr78Groups.SCHEME+".counts.CH3","E-PPR78 zero count");
        refused(HEXANE,o->o.getAsJsonObject("group_counts").add("unifac-1975",o.getAsJsonObject("group_counts").get(ModifiedUnifac.SCHEME)),
                "group_counts.unifac-1975","unknown scheme");
        refused(HEXANE,o->o.add("group_counts",new JsonObject()),"group_counts.","no scheme");
        refused(HEXANE,o->o.addProperty("group_counts",1),"group_counts: expected object","wrong type");
        // Dortmund names need a Dortmund table: without one, the counts are refused.
        var tableless=changed(HEXANE,none);tableless.remove(ROOT+TABLE);
        var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(tableless));
        assertTrue(error.getMessage().contains("no group_interactions record of scheme "+ModifiedUnifac.SCHEME),error.getMessage());
    }

    /** {@code models.pr78.alpha} and {@code henry_in_water}: read when valid, refused by field otherwise. */
    @Test void alphaAndHenryParseAndRefuse() {
        Consumer<JsonObject> alpha=o->{var a=new JsonObject();a.addProperty("type","mathias_copeman");var c=new JsonArray();c.add(.7);c.add(-.1);c.add(.2);a.add("coefficients",c);
            var pr=o.getAsJsonObject("models").getAsJsonObject("pr78");pr.add("alpha",a);o.addProperty("revision",o.get("revision").getAsString()+"-edited");};
        var with=MaterialCatalog.parse(changed(HEXANE,alpha));
        var p=with.requirePackage(WORLD);var pr=p.properties().get(p.components().indexOf("N-hexane")).pr();
        assertEquals(List.of(.7,-.1,.2),pr.alpha().coefficients());
        assertNotEquals(MaterialCatalog.bundled().requirePackage(WORLD).scientificRevision(),p.scientificRevision(),"an alpha is physics: the dataset's revision bump moves the identity");
        List<Consumer<JsonObject>> alphas=List.of(a->a.addProperty("type","soave"),a->a.add("coefficients",new JsonArray()),
                a->{var c=a.getAsJsonArray("coefficients");c.add(1);},a->a.getAsJsonArray("coefficients").set(0,new JsonPrimitive("x")),
                a->a.getAsJsonArray("coefficients").set(0,new JsonPrimitive(new java.math.BigDecimal("1e400"))),a->a.remove("coefficients"),a->a.remove("type"));
        for(int i=0;i<alphas.size();i++) {
            var edit=alphas.get(i);
            refused(HEXANE,o->{alpha.accept(o);edit.accept(o.getAsJsonObject("models").getAsJsonObject("pr78").getAsJsonObject("alpha"));},"models.pr78.alpha.","alpha edit "+i);
        }
        refused(HEXANE,o->o.getAsJsonObject("models").getAsJsonObject("pr78").addProperty("alpha",1),"alpha: expected object","alpha type");
        List<Consumer<JsonObject>> henry=List.of(h->h.addProperty("type","sander"),h->h.getAsJsonArray("coefficients").remove(2),
                h->h.getAsJsonArray("coefficients").add(1),h->h.getAsJsonArray("coefficients").set(1,new JsonPrimitive("x")),
                h->h.remove("source"),h->h.addProperty("revision","iapws g7"),h->h.remove("revision"));
        for(int i=0;i<henry.size();i++) {
            var edit=henry.get(i);
            refused(CO2,o->edit.accept(o.getAsJsonObject("henry_in_water")),"henry_in_water.","Henry edit "+i);
        }
    }

    /** The Dortmund model against the offline reference, with and without the water-hydrocarbon block. */
    @Test void dortmundMatchesTheOfflineReference() {
        var dortmund=table();
        for(var row:LN_GAMMA) {
            var mixture=dortmund.mixture(List.of(GROUPS.get((String)row[0]),GROUPS.get((String)row[1])),(Boolean)row[2]);
            double t=(Double)row[3],x=(Double)row[4];
            var g=mixture.lnGamma(t,new double[]{x,1-x});
            String label=row[0]+"/"+row[1]+(((Boolean)row[2])?" block":"")+" at "+t+" K, x1 "+x;
            assertEquals((Double)row[5],g[0],1e-12*Math.max(1,Math.abs((Double)row[5])),label);
            assertEquals((Double)row[6],g[1],1e-12*Math.max(1,Math.abs((Double)row[6])),label);
            assertEquals((Boolean)row[2],mixture.blockApplied(),label);
        }
        // The block applies to water with a compound of hydrocarbon groups only, never with a polar compound.
        assertFalse(dortmund.mixture(List.of(ETHANOL,WATER),true).blockApplied());
        assertFalse(dortmund.mixture(List.of(ETHANOL,WATER,N_HEXANE),true).blockApplied());
        assertTrue(dortmund.mixture(List.of(Map.of("ACH",6),WATER),true).blockApplied());
        // A pure compound is ideal; the order of the counts maps does not change a bit.
        assertEquals(0.0,dortmund.mixture(List.of(ETHANOL,WATER),false).lnGamma(320,new double[]{1,0})[0],1e-15);
        var a=dortmund.mixture(List.of(new TreeMap<>(ETHANOL),WATER),false).lnGamma(350,new double[]{.3,.7});
        var b=dortmund.mixture(List.of(new LinkedHashMap<>(Map.of("OH",1,"CH2",1,"CH3",1)),WATER),false).lnGamma(350,new double[]{.3,.7});
        assertArrayEquals(a,b);
        assertThrows(IllegalArgumentException.class,()->dortmund.mixture(List.of(Map.of("CH9",1),WATER),false));
        assertThrows(IllegalArgumentException.class,()->dortmund.mixture(List.of(Map.of("CH2",0),WATER),false));
        assertThrows(IllegalArgumentException.class,()->dortmund.mixture(List.of(ETHANOL,WATER),false).lnGamma(300,new double[]{.5}));
    }

    /** IAPWS G7-04 for CO2 and N2 on the bundled coefficients and water model: dortmund-values.mjs. */
    @Test void henryConstantsMatchTheOfflineReference() {
        var catalog=MaterialCatalog.bundled();var water=catalog.requirePackage(WORLD).water();
        var co2=catalog.henryInWater(WORLD,"CarbonDioxide").orElseThrow();var n2=catalog.henryInWater(WORLD,"Nitrogen").orElseThrow();
        assertEquals(165644589.17920497,co2.pascal(water,298.15),165644589.17920497*1e-12,"CO2 at 25 C, about 165 MPa");
        assertEquals(507685187.05802822,co2.pascal(water,373.15),507685187.05802822*1e-12);
        assertEquals(8559982180.2324600,n2.pascal(water,298.15),8559982180.2324600*1e-12);
        assertEquals(11717936385.022171,n2.pascal(water,373.15),11717936385.022171*1e-12);
        assertThrows(IllegalArgumentException.class,()->co2.lnRatio(700));
    }
}
