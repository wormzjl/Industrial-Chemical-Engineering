package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code hazard} sub-record (batch 2026-11-15-pollution-system, plan section 2; data for the pollution field): how it
 * is read and refused, the records the world list ships, the per-package arrays, and that it is in no identity.
 */
class MaterialHazardTest {
    private static final String ROOT="data/icheme/materials/";
    private static final String WORLD="icheme:refinery";
    private static final String H2S="properties/chemsep_hydrogen_sulfide.json";
    private static final String METHANE="properties/tjl20_methane.json";
    /** The IDLH, ppm, of each compound of the world list that has one, as read from its NIOSH page on 2026-11-15. */
    private static final Map<String,Double> TOXIC=Map.ofEntries(
            Map.entry("HydrogenSulfide",100.),Map.entry("CarbonMonoxide",1200.),Map.entry("MethylMercaptan",150.),
            Map.entry("EthylMercaptan",500.),Map.entry("Benzene",500.),Map.entry("Toluene",500.),Map.entry("Ethylbenzene",800.),
            Map.entry("O-xylene",900.),Map.entry("M-xylene",900.),Map.entry("P-xylene",900.),Map.entry("N-hexane",1100.),
            Map.entry("Cyclohexane",1300.),Map.entry("N-pentane",1500.),Map.entry("N-butane",1600.),Map.entry("Butadiene",2000.),
            Map.entry("Propane",2100.),Map.entry("Ethanol",3300.),Map.entry("CarbonDioxide",40000.),
            Map.entry("CarbonylSulfide",150.));
    /** GHS H314 compounds (owner decision): the corrosive channel. */
    private static final Map<String,Double> CORROSIVE=Map.of("SulfurDioxide",100.,"Ammonia",300.);

    private static Map<String,String> changed(String path,Consumer<JsonObject> edit) { return MaterialCatalogTest.changed(path,edit); }

    /** The sub-record reads back what the record holds; a dataset without one parses as before. */
    @Test void theHazardRoundTripsAndIsOptional() {
        var bundled=MaterialCatalog.bundled();
        var json=JsonParser.parseString(bundled.resources().get(ROOT+H2S)).getAsJsonObject().getAsJsonObject("hazard");
        var h=bundled.hazard(WORLD,"HydrogenSulfide").orElseThrow();
        assertEquals(MaterialCatalog.Hazard.Channel.TOXIC,h.channel());
        assertEquals(json.get("reference_ppm").getAsDouble(),h.referencePpm());
        assertEquals(json.get("estimated").getAsBoolean(),h.estimated());
        assertEquals(json.get("source").getAsString(),h.source());
        assertEquals(json.get("revision").getAsString(),h.revision());
        assertTrue(bundled.hazard(WORLD,"Methane").isEmpty(),"a simple asphyxiant has none");
        assertTrue(bundled.hazard(WORLD,"Water").isEmpty());
        assertEquals(.04,bundled.flammability(WORLD,"HydrogenSulfide").orElseThrow().lowerLimit());
        var without=MaterialCatalog.parse(changed(H2S,o->o.remove("hazard")));
        assertTrue(without.hazard(WORLD,"HydrogenSulfide").isEmpty());
        assertEquals(bundled.requirePackage(WORLD).properties(),without.requirePackage(WORLD).properties());
        var corrosive=MaterialCatalog.parse(changed(H2S,o->o.getAsJsonObject("hazard").addProperty("channel","corrosive")));
        assertEquals(MaterialCatalog.Hazard.Channel.CORROSIVE,corrosive.hazard(WORLD,"HydrogenSulfide").orElseThrow().channel());
        assertThrows(IllegalArgumentException.class,()->bundled.hazard(WORLD,"Missing"));
    }

    /** Each invalid value or missing field refuses the catalog, naming the record's file and the field. */
    @Test void anInvalidHazardRefusesTheCatalog() {
        List<Consumer<JsonObject>> edits=List.of(
                h->h.addProperty("channel","fatal"),h->h.addProperty("channel","TOXIC"),h->h.remove("channel"),h->h.addProperty("channel",1),
                h->h.addProperty("reference_ppm",0),h->h.addProperty("reference_ppm",-100),h->h.addProperty("reference_ppm",2e6),
                h->h.addProperty("reference_ppm","x"),h->h.remove("reference_ppm"),
                h->h.remove("estimated"),h->h.addProperty("estimated","no"),h->h.remove("source"),h->h.addProperty("source"," "),
                h->h.addProperty("revision","npg idlh"),h->h.addProperty("revision","r".repeat(65)),h->h.remove("revision"));
        for(int i=0;i<edits.size();i++) {
            var edit=edits.get(i);
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(H2S,o->edit.accept(o.getAsJsonObject("hazard")))),"hazard edit "+i);
            assertTrue(error.getMessage().contains(H2S)&&error.getMessage().contains("hazard."),"edit "+i+": "+error.getMessage());
        }
        assertDoesNotThrow(()->MaterialCatalog.parse(changed(H2S,o->o.getAsJsonObject("hazard").addProperty("reference_ppm",1e6))));
    }

    /**
     * The data gate: every compound of the world list with an IDLH carries it on its channel, the cuts carry the naphtha
     * value as an estimate, carbonyl sulfide its AEGL-3 as an estimate, and nothing else has one; every record cites a source.
     */
    @Test void theWorldListCarriesTheHazardsOfItsCompounds() {
        var catalog=MaterialCatalog.bundled();var world=catalog.requirePackage(WORLD);int records=0;
        for(String component:world.components()) {
            var h=catalog.hazard(WORLD,component);
            if(component.startsWith("crude_pc")) {
                var cut=h.orElseThrow(()->new AssertionError(component+" has no hazard"));records++;
                assertEquals(MaterialCatalog.Hazard.Channel.TOXIC,cut.channel(),component);assertEquals(1100,cut.referencePpm(),component);
                assertTrue(cut.estimated(),component);assertEquals("npg-idlh-naphtha-r1",cut.revision(),component);
                assertTrue(cut.source().contains("Petroleum distillates (naphtha)"),component);
                continue;
            }
            Double toxic=TOXIC.get(component),corrosive=CORROSIVE.get(component);
            if(toxic==null&&corrosive==null) { assertTrue(h.isEmpty(),component+" has no IDLH and no record"); continue; }
            var record=h.orElseThrow(()->new AssertionError(component+" has no hazard"));records++;
            assertEquals(toxic!=null?MaterialCatalog.Hazard.Channel.TOXIC:MaterialCatalog.Hazard.Channel.CORROSIVE,record.channel(),component);
            assertEquals((toxic!=null?toxic:corrosive).doubleValue(),record.referencePpm(),component);
            assertFalse(record.source().isBlank(),component);
            if(component.equals("CarbonylSulfide")) {
                assertTrue(record.estimated());assertEquals("aegl3-60min-r1",record.revision());assertTrue(record.source().contains("AEGL-3"));
            } else {
                assertFalse(record.estimated(),component);assertEquals("npg-idlh-r1",record.revision(),component);
                assertTrue(record.source().contains("NIOSH Pocket Guide")&&record.source().contains("https://www.cdc.gov/niosh/npg/"),component);
            }
        }
        assertEquals(TOXIC.size()+CORROSIVE.size()+12,records);
        assertEquals(MaterialCatalog.Hazard.Channel.CORROSIVE,catalog.hazard(WORLD,"SulfurDioxide").orElseThrow().channel());
        assertEquals(MaterialCatalog.Hazard.Channel.CORROSIVE,catalog.hazard(WORLD,"Ammonia").orElseThrow().channel());
    }

    /** The arrays in the conserved order: references as mole fractions, one channel each, the LFLs, the water last and inert. */
    @Test void thePackageArraysFollowTheConservedOrder() {
        var catalog=MaterialCatalog.bundled();var names=catalog.conservedComponents(WORLD);
        double[] toxic=catalog.toxicReferences(WORLD),corrosive=catalog.corrosiveReferences(WORLD),lfl=catalog.lowerFlammabilityLimits(WORLD);
        double[] tc=catalog.criticalTemperatures(WORLD),pc=catalog.criticalPressures(WORLD),omega=catalog.acentricFactors(WORLD);
        for(var a:List.of(toxic,corrosive,lfl,tc,pc,omega))assertEquals(names.size(),a.length);
        assertEquals(100e-6,toxic[names.indexOf("HydrogenSulfide")],1e-18);assertEquals(0,corrosive[names.indexOf("HydrogenSulfide")]);
        assertEquals(300e-6,corrosive[names.indexOf("Ammonia")],1e-18);assertEquals(0,toxic[names.indexOf("Ammonia")]);
        assertEquals(0,toxic[names.indexOf("Methane")]);assertEquals(.05,lfl[names.indexOf("Methane")]);
        assertEquals(0,lfl[names.indexOf("Nitrogen")]);
        int water=names.size()-1;
        assertEquals(0,toxic[water]);assertEquals(0,corrosive[water]);assertEquals(0,lfl[water]);
        assertEquals(catalog.requirePackage(WORLD).water().criticalTemperature(),tc[water]);
        var world=catalog.requirePackage(WORLD);
        for(int k=0;k<water;k++) {
            var pr=world.properties().get(world.components().indexOf(names.get(k))).pr();
            assertEquals(pr.criticalTemperature(),tc[k],names.get(k));assertEquals(pr.criticalPressure(),pc[k],names.get(k));assertEquals(pr.acentricFactor(),omega[k],names.get(k));
        }
    }

    /** The sub-record is in no identity: every package's identities and physics content are the same with and without it. */
    @Test void theHazardIsInNoIdentity() {
        var bundled=MaterialCatalog.bundled();var resources=new HashMap<>(bundled.resources());int hazards=0;
        for(var entry:resources.entrySet())if(entry.getKey().startsWith(ROOT+"properties/")) {
            var o=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            if(o.remove("hazard")!=null) { hazards++; entry.setValue(o.toString()); }
        }
        assertEquals(TOXIC.size()+CORROSIVE.size()+12,hazards,"the world list's records and nothing else");
        var without=MaterialCatalog.parse(resources);
        var edited=MaterialCatalog.parse(changed(H2S,o->{var h=o.getAsJsonObject("hazard");
            h.addProperty("reference_ppm",50);h.addProperty("revision",h.get("revision").getAsString()+"-edited");}));
        for(var other:List.of(without,edited))for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),other.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),other.fluidRevision(p.id()),p.id());
            assertEquals(bundled.spineRevision(p.id()),other.spineRevision(p.id()),p.id());
            assertEquals(bundled.physicsContent(p.id(),p.components()),other.physicsContent(p.id(),p.components()),p.id());
        }
        assertEquals(50,edited.hazard(WORLD,"HydrogenSulfide").orElseThrow().referencePpm());
        assertTrue(MaterialCatalog.parse(changed(METHANE,o->o.remove("flammability"))).flammability(WORLD,"Methane").isEmpty());
    }
}
