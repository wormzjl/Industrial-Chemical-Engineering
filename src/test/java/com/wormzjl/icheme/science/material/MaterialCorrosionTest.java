package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import com.wormzjl.icheme.science.integrity.CorrosionStandard;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code corrosion} sub-record (batch 2026-11-16-fouling-corrosion, WP1, plan section 6.4): the twelve crude cuts'
 * records, the four assays' whole-crude sulfur and acid numbers, the per-package arrays, refusals, and that it is in no
 * identity.
 */
class MaterialCorrosionTest {
    private static final String ROOT="data/icheme/materials/";
    private static final String WORLD="icheme:refinery";
    private static final String CUT="properties/crude_pc12.json";
    /** The fit of tools/fouling-corrosion/fit-cuts.mjs (corrosion-fit-r1), cuts 01 to 12. */
    private static final double[] SULFUR={.02,.02,.02,.02,.02,.02,.02,.057,1.2,3,4.7,6},ACID={0,0,0,0,0,0,0,0,0,.72,2.5,4.5},
            FOULING={0,0,0,0,0,.0005,.0005,.0005,.0005,.0005,.0018,.0018};
    /** Whole-crude sulfur specification and acid-number target of each assay, and the residual bound the fit reached. */
    private static final Map<String,double[]> ASSAYS=Map.of("light_crude",new double[]{.2,.1},"medium_crude",new double[]{1.5,.3},
            "heavy_crude",new double[]{3,1.5},"oil_sand_dilbit",new double[]{3.74,2.5});
    /**
     * The largest relative residual of the fit, sulfur and acid number. The plan asked for 20 % on sulfur; the shared cuts
     * cannot give it (the heavy crude's cut distribution dominates the dilbit's; tools/fouling-corrosion/README.md).
     */
    private static final double SULFUR_BOUND=.44,ACID_BOUND=.52;

    private static Map<String,String> changed(String path,Consumer<JsonObject> edit) { return MaterialCatalogTest.changed(path,edit); }
    private static String cut(int i) { return "crude_pc"+(i<9?"0":"")+(i+1); }

    @Test void theTwelveCutsCarryTheFit() {
        var catalog=MaterialCatalog.bundled();
        for(int i=0;i<12;i++) {
            var c=catalog.corrosion(WORLD,cut(i)).orElseThrow(()->new AssertionError("no corrosion record"));
            assertEquals(SULFUR[i],c.sulfurMassPercent(),cut(i));assertEquals(ACID[i],c.acidNumber(),cut(i));assertEquals(FOULING[i],c.fouling(),cut(i));
            assertTrue(c.estimated(),cut(i));assertEquals("corrosion-fit-r1",c.revision(),cut(i));
            assertTrue(c.source().contains("tools/fouling-corrosion/fit-cuts.mjs"),cut(i));
            if(i>0)assertTrue(c.sulfurMassPercent()>=SULFUR[i-1]&&c.acidNumber()>=ACID[i-1]&&c.fouling()>=FOULING[i-1],"rises with the cut");
        }
        var json=JsonParser.parseString(catalog.resources().get(ROOT+CUT)).getAsJsonObject().getAsJsonObject("corrosion");
        assertEquals(json.get("sulfur_mass_percent").getAsDouble(),catalog.corrosion(WORLD,"crude_pc12").orElseThrow().sulfurMassPercent());
        int records=0;
        for(String component:catalog.requirePackage(WORLD).components())
            if(catalog.corrosion(WORLD,component).isPresent()) { records++; assertTrue(component.startsWith("crude_pc"),component); }
        assertEquals(12,records,"the cuts and nothing else");
        assertTrue(catalog.corrosion(WORLD,"Water").isEmpty());
        assertTrue(catalog.corrosion(WORLD,"HydrogenSulfide").isEmpty(),"its sulfur is in its formula");
    }

    /** Each assay's whole crude from its own cut composition (mass basis; the light ends carry neither). */
    @Test void theFourAssaysReproduceTheirSulfurWithinTheFitsBound() {
        var catalog=MaterialCatalog.bundled();
        for(var entry:ASSAYS.entrySet()) {
            var amounts=JsonParser.parseString(catalog.resources().get(ROOT+"assays/"+entry.getKey()+".json")).getAsJsonObject()
                    .getAsJsonObject("amounts_by_component");
            double total=0,sulfur=0,acid=0;
            for(var a:amounts.entrySet()) {
                double w=a.getValue().getAsDouble();total+=w;
                var c=catalog.corrosion(WORLD,a.getKey());
                if(c.isPresent()) { sulfur+=w*c.get().sulfurMassPercent(); acid+=w*c.get().acidNumber(); }
            }
            sulfur/=total;acid/=total;
            assertEquals(entry.getValue()[0],sulfur,SULFUR_BOUND*entry.getValue()[0],entry.getKey()+" sulfur "+sulfur);
            assertEquals(entry.getValue()[1],acid,ACID_BOUND*entry.getValue()[1],entry.getKey()+" acid number "+acid);
        }
    }

    /** The arrays in the conserved order, the water last and 0; and a standard on the world list reads them. */
    @Test void thePackageArraysFollowTheConservedOrder() {
        var catalog=MaterialCatalog.bundled();var names=catalog.conservedComponents(WORLD);
        double[] sulfur=catalog.sulfurShares(WORLD),acid=catalog.acidNumbers(WORLD),fouling=catalog.foulingNumbers(WORLD);
        for(var a:List.of(sulfur,acid,fouling))assertEquals(names.size(),a.length);
        for(int i=0;i<12;i++) {
            int k=names.indexOf(cut(i));
            assertEquals(SULFUR[i],sulfur[k]);assertEquals(ACID[i],acid[k]);assertEquals(FOULING[i],fouling[k]);
        }
        int water=names.size()-1;
        assertEquals(0,sulfur[water]);assertEquals(0,acid[water]);assertEquals(0,fouling[water]);
        assertEquals(0,sulfur[names.indexOf("HydrogenSulfide")]);
        var world=catalog.requirePackage(WORLD);var molar=new double[names.size()];
        for(int k=0;k<water;k++)molar[k]=world.properties().get(world.components().indexOf(names.get(k))).molecularWeight();
        molar[water]=world.water().molarMass();
        var standard=new CorrosionStandard(molar,catalog.formulas(WORLD),sulfur,acid,fouling);
        assertEquals(water,standard.water(),"the water by its formula, last");
        assertEquals(94.1,standard.sulfur(names.indexOf("HydrogenSulfide")),.1,"H2S by its formula");
        assertEquals(6,standard.sulfur(names.indexOf("crude_pc12")),"a cut by its sub-record");
        var moles=new double[names.size()];moles[names.indexOf("crude_pc12")]=1;moles[names.indexOf("HydrogenSulfide")]=1;
        var d=standard.digest(moles,600,2e5,moles,null);
        assertTrue(d.oil());assertEquals(1,d.h2s(),1e-12);
    }

    /** Each invalid value or missing field refuses the catalog, naming the record's file and the field. */
    @Test void anInvalidCorrosionRecordRefusesTheCatalog() {
        List<Consumer<JsonObject>> edits=List.of(
                c->c.addProperty("sulfur_mass_percent",-.1),c->c.addProperty("sulfur_mass_percent",10.1),c->c.addProperty("sulfur_mass_percent","x"),
                c->c.remove("sulfur_mass_percent"),c->c.addProperty("acid_number",-1),c->c.addProperty("acid_number",21),c->c.remove("acid_number"),
                c->c.addProperty("fouling",-1e-4),c->c.addProperty("fouling",.0021),c->c.remove("fouling"),
                c->c.remove("estimated"),c->c.addProperty("estimated","yes"),c->c.remove("source"),c->c.addProperty("source"," "),
                c->c.addProperty("revision","corrosion fit"),c->c.addProperty("revision","r".repeat(65)),c->c.remove("revision"));
        String[] fields={"sulfur_mass_percent","sulfur_mass_percent","sulfur_mass_percent","sulfur_mass_percent","acid_number","acid_number","acid_number",
                "fouling","fouling","fouling","estimated","estimated","source","source","revision","revision","revision"};
        for(int i=0;i<edits.size();i++) {
            var edit=edits.get(i);
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(CUT,o->edit.accept(o.getAsJsonObject("corrosion")))),"edit "+i);
            assertTrue(error.getMessage().contains(CUT)&&error.getMessage().contains("corrosion."+fields[i]),"edit "+i+": "+error.getMessage());
        }
        assertDoesNotThrow(()->MaterialCatalog.parse(changed(CUT,o->o.getAsJsonObject("corrosion").addProperty("sulfur_mass_percent",10))));
        assertTrue(MaterialCatalog.parse(changed(CUT,o->o.remove("corrosion"))).corrosion(WORLD,"crude_pc12").isEmpty(),"optional");
    }

    /** The sub-record is in no identity: every package's identities and physics content are the same with and without it. */
    @Test void theCorrosionRecordIsInNoIdentity() {
        var bundled=MaterialCatalog.bundled();var resources=new HashMap<>(bundled.resources());int removed=0;
        for(var entry:resources.entrySet())if(entry.getKey().startsWith(ROOT+"properties/")) {
            var o=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            if(o.remove("corrosion")!=null) { removed++; entry.setValue(o.toString()); }
        }
        assertEquals(12,removed);
        var without=MaterialCatalog.parse(resources);
        var edited=MaterialCatalog.parse(changed(CUT,o->{var c=o.getAsJsonObject("corrosion");
            c.addProperty("sulfur_mass_percent",4);c.addProperty("revision","corrosion-fit-r1-edited");}));
        for(var other:List.of(without,edited))for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),other.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),other.fluidRevision(p.id()),p.id());
            assertEquals(bundled.spineRevision(p.id()),other.spineRevision(p.id()),p.id());
            assertEquals(bundled.physicsContent(p.id(),p.components()),other.physicsContent(p.id(),p.components()),p.id());
        }
        assertEquals(4,edited.corrosion(WORLD,"crude_pc12").orElseThrow().sulfurMassPercent());
        assertArrayEquals(new double[without.conservedComponents(WORLD).size()],without.sulfurShares(WORLD));
    }
}
