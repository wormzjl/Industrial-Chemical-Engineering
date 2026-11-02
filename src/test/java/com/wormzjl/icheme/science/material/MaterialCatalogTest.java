package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.column.v3.thermo.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialCatalogTest {
    private static final String ID="icheme:tjl20_methane";
    private static final String ROOT="data/icheme/materials/";
    @AfterEach void reset(){MaterialRuntime.reset();}
    static Map<String,String> changed(String path,java.util.function.Consumer<JsonObject> edit) {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());
        var json=JsonParser.parseString(resources.get(ROOT+path)).getAsJsonObject(); edit.accept(json);
        resources.put(ROOT+path,json.toString()); return resources;
    }
    @Test void immutableCatalogRetainsSeparateDatasetsForSharedChemicalIdentity() {
        var c=MaterialCatalog.bundled(); var pilot=Cdu17TestCatalog.catalog().requirePackage("icheme:cdu17_tjl_acs2018"); var literature=c.requirePackage(ID);
        assertEquals("Ethane",pilot.properties().get(1).component()); assertEquals("Ethane",literature.properties().get(1).component());
        assertNotEquals(pilot.properties().get(1).molecularWeight(),literature.properties().get(1).molecularWeight());
        assertThrows(UnsupportedOperationException.class,()->literature.properties().clear());
        assertThrows(UnsupportedOperationException.class,()->literature.interactions().get(0).set(0,9.0));
        assertEquals("Ethane",c.name("Ethane").english());
        assertTrue(c.name("crude_pc01").english().contains("below"));
        assertTrue(c.name("crude_pc12").english().contains("above"));
        assertTrue(c.name("crude_pc07").english().endsWith("(estimated)"));
    }
    /**
     * Data are identified by their declared revisions, nothing is hashed, and each record declares its own (owner
     * decisions 2026-10-26): a numerical edit declares itself with its record's revision bump, which moves the identity of
     * every package that names the record, makes earlier results stale and turns the neural initializer off until a model
     * is trained on the new identity; a package that does not name the record keeps its identity; a name edit changes none.
     */
    @Test void aRecordsRevisionBumpMakesEarlierResultsStaleButNamesDoNot() {
        var bundled=MaterialCatalog.bundled();var before=bundled.requirePackage(ID);
        var names=MaterialCatalog.parse(changed("components/crude_pc07.json",o->o.addProperty("fallback","Petroleum")));
        assertEquals(before.scientificRevision(),names.requirePackage(ID).scientificRevision());
        var science=MaterialCatalog.parse(changed("properties/crude_pc07.json",o->{o.addProperty("molecular_weight_kg_per_mol",.31);
            o.addProperty("revision",o.get("revision").getAsString()+"-edited");}));
        for(var p:bundled.packages().values()) {
            boolean names07=p.properties().stream().anyMatch(property->property.id().equals("icheme:crude_pc07"));
            assertEquals(!names07,p.scientificRevision().equals(science.requirePackage(p.id()).scientificRevision()),p.id());
        }
        assertNotEquals(before.scientificRevision(),science.requirePackage(ID).scientificRevision());
        assertFalse(MaterialRuntime.with(science,ID,()->MaterialRuntime.isCurrent(ID,before.scientificRevision())));
        assertTrue(MaterialRuntime.with(names,ID,()->MaterialRuntime.isCurrent(ID,before.scientificRevision())));
        var input=com.wormzjl.icheme.science.column.v3.V3TestInputs.methaneCduInput();
        assertTrue(MaterialRuntime.with(science,ID,()->V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).isEmpty()));
    }
    /**
     * The identities an owner reads off the files (owner decision 2026-10-26). The column's: the package's own revision,
     * then every record it names for its phase physics, each with its declared revision. The fluid network's adds what
     * only it reads: each viscosity by its own revision, the water's ice and the fluid reference records. Rebuilt here
     * from the raw JSON, not through the parser.
     */
    @Test void everyPackageIdentityListsTheDeclaredRevisionOfEachRecordItNames() {
        var catalog=MaterialCatalog.bundled();var records=new HashMap<String,Map<String,JsonObject>>();var transport=new ArrayList<String>();
        for(var resource:catalog.resources().entrySet()) {
            String path=resource.getKey();int folder=path.indexOf('/',ROOT.length());
            if(!path.startsWith(ROOT)||folder<0)continue;
            var element=JsonParser.parseString(resource.getValue());if(!element.isJsonObject())continue;var o=element.getAsJsonObject();
            if(path.startsWith(ROOT+"transport/"))transport.add(o.get("type").getAsString()+"@"+o.get("revision").getAsString());
            else if(o.has("id"))records.computeIfAbsent(path.substring(ROOT.length(),folder),k->new HashMap<>()).put(o.get("id").getAsString(),o);
        }
        Collections.sort(transport);
        java.util.function.BiFunction<String,String,String> declared=(kind,id)->id+"@"+records.get(kind).get(id).get("revision").getAsString();
        java.util.function.BiFunction<String,String,List<String>> viscosities=(kind,id)->{
            var o=records.get(kind).get(id);var parts=new ArrayList<String>();
            if(o.has("viscosity"))for(String phase:List.of("liquid","vapor"))if(o.getAsJsonObject("viscosity").has(phase))
                parts.add(id+"/"+phase+"@"+o.getAsJsonObject("viscosity").getAsJsonObject(phase).get("revision").getAsString());
            return parts;
        };
        for(var o:records.get("packages").values()) {
            String id=o.get("id").getAsString(),water=o.get("water_model").getAsString();var interactions=records.get("interactions").get(o.get("interactions").getAsString());
            var properties=propertyReferences(o,records.get("bases"));
            var parts=new ArrayList<>(List.of(declared.apply("packages",id),"properties="+String.join(",",properties.stream().map(ref->declared.apply("properties",ref)).toList()),
                    "interactions="+declared.apply("interactions",interactions.get("id").getAsString())));
            if(interactions.has("rule"))parts.add("group-interactions="+declared.apply("group_interactions",interactions.getAsJsonObject("rule").get("group_interactions").getAsString()));
            parts.add("water="+declared.apply("water",water));
            String identity=String.join(";",parts);
            assertEquals(identity,catalog.requirePackage(id).scientificRevision(),id);
            var transportParts=new ArrayList<String>();for(String ref:properties)transportParts.addAll(viscosities.apply("properties",ref));
            transportParts.addAll(viscosities.apply("water",water));var waterRecord=records.get("water").get(water);
            assertEquals(identity+";viscosity="+String.join(",",transportParts)
                    +(waterRecord.has("ice")?";ice="+declared.apply("crystals",waterRecord.getAsJsonObject("ice").get("crystal").getAsString()):"")
                    +";fluid-data="+String.join(",",transport),catalog.fluidRevision(id),id);
        }
    }
    /** A package's property references: its own list or its basis's, a basis extending its parent's. */
    private static List<String> propertyReferences(JsonObject record,Map<String,JsonObject> bases) {
        if(record.has("basis"))return propertyReferences(bases.get(record.get("basis").getAsString()),bases);
        var references=new ArrayList<String>();
        if(record.has("extends"))references.addAll(propertyReferences(bases.get(record.get("extends").getAsString()),bases));
        for(var reference:record.getAsJsonArray("properties"))references.add(reference.getAsString());
        return references;
    }
    /**
     * Only the fluid network reads viscosities, so a viscosity edit, declared with the viscosity's own revision, moves the
     * fluid identity of every package that names the record and leaves the column's identity, results and neural models.
     */
    @Test void aViscosityRevisionBumpMovesOnlyTheFluidIdentity() {
        var bundled=MaterialCatalog.bundled();
        var edited=MaterialCatalog.parse(changed("properties/tjl20_methane.json",o->{var liquid=o.getAsJsonObject("viscosity").getAsJsonObject("liquid");
            var table=liquid.getAsJsonArray("coefficients");table.set(0,new JsonPrimitive(table.get(0).getAsDouble()*1.01));
            liquid.addProperty("revision",liquid.get("revision").getAsString()+"-edited");}));
        for(var p:bundled.packages().values()) {
            boolean namesMethane=p.properties().stream().anyMatch(property->property.id().equals("icheme:tjl20_methane"));
            assertEquals(p.scientificRevision(),edited.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(!namesMethane,bundled.fluidRevision(p.id()).equals(edited.fluidRevision(p.id())),p.id());
        }
        assertTrue(MaterialRuntime.with(edited,ID,()->MaterialRuntime.isCurrent(ID,bundled.requirePackage(ID).scientificRevision())));
        var input=com.wormzjl.icheme.science.column.v3.V3TestInputs.methaneCduInput();
        assertTrue(MaterialRuntime.with(edited,ID,()->V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).isPresent()));
    }
    /** A revision is a short token, so an identity stays readable and splits at its separators; a refusal names the record's file. */
    @Test void aRevisionThatIsNotATokenOrIsMissingIsRefusedNamingItsRecord() {
        for(String bad:List.of("crude pc07 r2","crude-pc07;r2","-r2","r".repeat(65))) {
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("revision",bad))),bad);
            assertTrue(error.getMessage().contains("properties/crude_pc07.json")&&error.getMessage().contains("revision"),error.getMessage());
        }
        assertDoesNotThrow(()->MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("revision","r".repeat(64)))));
        var viscosity=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("properties/crude_pc07.json",
                o->o.getAsJsonObject("viscosity").getAsJsonObject("vapor").addProperty("revision","dwsim viscosity"))));
        assertTrue(viscosity.getMessage().contains("properties/crude_pc07.json")&&viscosity.getMessage().contains("viscosity.vapor"),viscosity.getMessage());
        var missing=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("interactions/tjl20.json",o->o.remove("revision"))));
        assertTrue(missing.getMessage().contains("interactions/tjl20.json")&&missing.getMessage().contains("revision"),missing.getMessage());
    }
    @Test void invalidReloadCannotPublishPartialCatalog() {
        MaterialRuntime.publish(MaterialCatalog.bundled());
        var bad=changed("properties/crude_pc07.json",o->o.addProperty("molecular_weight_kg_per_mol",-1));
        var error=assertThrows(IllegalArgumentException.class,()->MaterialRuntime.publish(MaterialCatalog.parse(bad)));
        assertTrue(error.getMessage().contains("properties/crude_pc07.json"));
        assertTrue(error.getMessage().contains("molecular_weight_kg_per_mol"));
        assertSame(MaterialCatalog.bundled(),MaterialRuntime.active());
    }
    @Test void rejectsDuplicateIdsMissingReferencesAndAmbiguousAliases() {
        var duplicate=new HashMap<>(MaterialCatalog.bundled().resources());
        duplicate.put(ROOT+"components/duplicate.json",duplicate.get(ROOT+"components/ethane.json"));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(duplicate));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("component","Missing"))));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("packages/tjl20.json",o->o.getAsJsonObject("aliases").addProperty("Ethane","Methane"))));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("packages/tjl20.json",o->o.addProperty("missing_interactions","error"))));
    }
    @Test void massAssaysConvertUsingSelectedMolecularWeightsAndVolumeReferencesAreChecked() {
        var original=MaterialCatalog.bundled();var p=original.requirePackage(ID);var assay=p.assays().get("icheme:tia_juana_light_methane");
        var resources=changed("assays/tjl20.json",o->{
            o.addProperty("basis","mass");var mass=new JsonArray();
            for(int i=0;i<p.components().size();i++)mass.add(assay.amounts().get(i)*p.properties().get(i).molecularWeight());
            o.remove("amounts_by_component");o.add("components",new Gson().toJsonTree(p.components()));o.add("amounts",mass);
        });
        var changed=MaterialCatalog.parse(resources);
        double[] expected=V3PengRobinsonThermo.fromRegisteredPackage(ID).crudeFeed(assay.id()).moleFractions();
        double[] actual=MaterialRuntime.with(changed,ID,()->V3PengRobinsonThermo.fromRegisteredPackage(ID).crudeFeed(assay.id()).moleFractions());
        assertArrayEquals(expected,actual,1e-16);
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("assays/tjl19.json",o->{o.addProperty("basis","standard_liquid_volume");o.addProperty("standard_temperature_kelvin",300);o.addProperty("standard_pressure_pascal",101325);})));
    }
    @Test void nrtlPairsAreDirectedCompleteAndNotAnImplementedSolver() {
        var r=new HashMap<>(MaterialCatalog.bundled().resources());
        r.put(ROOT+"interactions/nrtl.json","""
                {"schema_version":1,"id":"example:nrtl","model":"nrtl","revision":"example-nrtl-r1","source":"manufactured validation fixture",
                 "pairs":[{"first":"Ethane","second":"Propane","a12":1,"b12_kelvin":100,"a21":2,"b21_kelvin":200,
                 "alpha":0.3,"temperature_min_kelvin":298.15,"temperature_max_kelvin":900}]}
                """);
        var p=JsonParser.parseString(r.get(ROOT+"packages/tjl19.json")).getAsJsonObject();
        p.remove("basis");p.addProperty("id","example:nrtl");p.addProperty("model","nrtl");p.addProperty("interactions","example:nrtl");p.addProperty("missing_interactions","error");
        p.add("components",JsonParser.parseString("[\"Ethane\",\"Propane\"]"));
        p.add("properties",JsonParser.parseString("[\"icheme:tjl19_ethane\",\"icheme:tjl19_propane\"]"));p.add("aliases",new JsonObject());
        r.put(ROOT+"packages/nrtl.json",p.toString());
        var c=MaterialCatalog.parse(r);var pair=c.requirePackage("example:nrtl").nrtlPairs().getFirst();
        assertEquals(100,pair.b12());assertEquals(200,pair.b21());
        var error=assertThrows(IllegalArgumentException.class,()->MaterialRuntime.with(c,"example:nrtl",()->V3PengRobinsonThermo.fromRegisteredPackage("example:nrtl")));
        assertTrue(error.getMessage().contains("solver unavailable: nrtl"));
        var interactions=JsonParser.parseString(r.get(ROOT+"interactions/nrtl.json")).getAsJsonObject();
        interactions.getAsJsonArray("pairs").get(0).getAsJsonObject().remove("b21_kelvin");r.put(ROOT+"interactions/nrtl.json",interactions.toString());
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(r));
    }
    @Test void runningContextKeepsBothPrAndWaterAcrossPublicationAndRestoresAfterFailure() throws Exception {
        var original=MaterialCatalog.bundled();
        var replacement=MaterialCatalog.parse(changed("water/water.json",o->o.addProperty("molar_mass",.019)));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->MaterialRuntime.with(original,ID,()-> {
                entered.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("test release timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                assertEquals(.01801528,V3WaterProperties.molarMass());
                assertEquals(original.requirePackage(ID).scientificRevision(),V3PengRobinsonThermo.fromRegisteredPackage(ID).datasetRevision());
                return MaterialRuntime.current();
            }));
            assertTrue(entered.await(10,TimeUnit.SECONDS));MaterialRuntime.publish(replacement);release.countDown();
            assertSame(original,future.get(10,TimeUnit.SECONDS));
            assertSame(replacement,executor.submit(MaterialRuntime::current).get(10,TimeUnit.SECONDS));
        } finally {release.countDown();}
        assertThrows(IllegalStateException.class,()->MaterialRuntime.with(original,ID,()->{throw new IllegalStateException();}));
        assertSame(replacement,MaterialRuntime.current());assertEquals(.019,V3WaterProperties.molarMass());
    }
}
