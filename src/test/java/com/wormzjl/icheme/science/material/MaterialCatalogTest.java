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
        return changed(MaterialCatalog.bundled().resources(),path,edit);
    }
    static Map<String,String> changed(Map<String,String> base,String path,java.util.function.Consumer<JsonObject> edit) {
        var resources=new HashMap<>(base);
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
        assertFalse(MaterialRuntime.with(science,ID,()->isCurrent(ID,before.scientificRevision())));
        assertTrue(MaterialRuntime.with(names,ID,()->isCurrent(ID,before.scientificRevision())));
        var input=boundCdu();
        // A molar mass is outside what the neural model computes with (O7 of batch 2026-11-04-open-thermo-data): the model
        // still binds, and its seed names the new identity.
        assertEquals(science.requirePackage(ID).scientificRevision(),MaterialRuntime.with(science,ID,
                ()->V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).orElseThrow().propertyRevision()));
    }
    /**
     * The identities an owner reads off the files (owner decision 2026-10-26). The column's: every record the package names
     * for its phase physics, each with its declared revision, and not the package record itself (option B, 2026-11-04). The
     * fluid network's adds what only it reads: each viscosity by its own revision, the water's ice and the fluid reference
     * records that hold an entry for one of its compounds or the water. Rebuilt here from the raw JSON, not through the parser.
     */
    @Test void everyPackageIdentityListsTheDeclaredRevisionOfEachRecordItNames() {
        var catalog=MaterialCatalog.bundled();var records=new HashMap<String,Map<String,JsonObject>>();var transport=new TreeMap<String,Set<String>>();
        for(var resource:catalog.resources().entrySet()) {
            String path=resource.getKey();int folder=path.indexOf('/',ROOT.length());
            if(!path.startsWith(ROOT)||folder<0)continue;
            var element=JsonParser.parseString(resource.getValue());if(!element.isJsonObject())continue;var o=element.getAsJsonObject();
            if(path.startsWith(ROOT+"transport/")) {
                var held=transport.computeIfAbsent(o.get("type").getAsString()+"@"+o.get("revision").getAsString(),k->new HashSet<>());
                for(String list:List.of("points","curves"))if(o.has(list))for(var entry:o.getAsJsonArray(list))held.add(entry.getAsJsonObject().get("component").getAsString());
            }
            else if(o.has("id"))records.computeIfAbsent(path.substring(ROOT.length(),folder),k->new HashMap<>()).put(o.get("id").getAsString(),o);
        }
        java.util.function.BiFunction<String,String,String> declared=(kind,id)->id+"@"+records.get(kind).get(id).get("revision").getAsString();
        java.util.function.BiFunction<String,String,List<String>> viscosities=(kind,id)->{
            var o=records.get(kind).get(id);var parts=new ArrayList<String>();
            if(o.has("viscosity"))for(String phase:List.of("liquid","vapor"))if(o.getAsJsonObject("viscosity").has(phase))
                parts.add(id+"/"+phase+"@"+o.getAsJsonObject("viscosity").getAsJsonObject(phase).get("revision").getAsString());
            return parts;
        };
        for(var o:records.get("packages").values()) {
            String id=o.get("id").getAsString();var method=records.get("methods").get(o.get("method").getAsString());String water=method.get("water_model").getAsString();
            var members=members(o,records.get("packages"),records.get("components"));
            var components=members.stream().map(member->member[0]).toList();var properties=members.stream().map(member->member[1]).toList();
            // The method's pair libraries in order, each listed when it gives a pair of the package's compounds that no earlier
            // one gave; the group-contribution rule's library when a pair is left for it.
            var seen=new HashSet<String>();var libraries=new ArrayList<String>();String rule=null,groups=null;
            for(var name:method.getAsJsonArray("interactions")) {
                var library=records.get("interactions").get(name.getAsString());boolean gave=false;
                for(var value:library.getAsJsonArray("pairs")) {
                    String a=value.getAsJsonObject().get("first").getAsString(),b=value.getAsJsonObject().get("second").getAsString();
                    if(components.contains(a)&&components.contains(b)&&seen.add(a.compareTo(b)<0?a+"/"+b:b+"/"+a))gave=true;
                }
                if(gave)libraries.add(declared.apply("interactions",name.getAsString()));
                if(library.has("rule"))rule=name.getAsString();
            }
            if(rule!=null&&seen.size()<components.size()*(components.size()-1)/2) {
                String token=declared.apply("interactions",rule);if(!libraries.contains(token))libraries.add(token);
                groups=declared.apply("group_interactions",records.get("interactions").get(rule).getAsJsonObject("rule").get("group_interactions").getAsString());
            }
            // The method by its physics fields (D7), never by its record's revision.
            var pressure=new java.math.BigDecimal[]{method.get("pressure_min_pascal").getAsBigDecimal(),method.get("pressure_max_pascal").getAsBigDecimal()};
            String head="method=model:"+method.get("model").getAsString()+",ideal-gas:"+method.get("ideal_gas").getAsString()
                    +",missing-interactions:"+method.get("missing_interactions").getAsString()
                    +",pressure-pascal:"+pressure[0].stripTrailingZeros().toPlainString()+".."+pressure[1].stripTrailingZeros().toPlainString()
                    +(method.has("liquid_mixture")?",liquid-mixture:"+method.getAsJsonObject("liquid_mixture").get("revision").getAsString():"");
            var parts=new ArrayList<>(List.of(head,
                    "properties="+String.join(",",properties.stream().map(ref->declared.apply("properties",ref)).toList()),"interactions="+String.join(",",libraries)));
            if(groups!=null)parts.add("group-interactions="+groups);
            parts.add("water="+declared.apply("water",water));
            String identity=String.join(";",parts);
            assertEquals(identity,catalog.requirePackage(id).scientificRevision(),id);
            var transportParts=new ArrayList<String>();for(String ref:properties)transportParts.addAll(viscosities.apply("properties",ref));
            transportParts.addAll(viscosities.apply("water",water));var waterRecord=records.get("water").get(water);
            assertEquals(identity+";viscosity="+String.join(",",transportParts)
                    +(waterRecord.has("ice")?";ice="+declared.apply("crystals",waterRecord.getAsJsonObject("ice").get("crystal").getAsString()):"")
                    +";fluid-data="+String.join(",",transport.entrySet().stream().filter(e->e.getValue().contains("Water")||e.getValue().stream().anyMatch(components::contains)).map(Map.Entry::getKey).toList()),
                    catalog.fluidRevision(id),id);
        }
    }
    /**
     * The methane CDU without its stripping steam, a column the bundled model binds. Its wet formulation moved to r32/r33
     * (E30 of batch 2026-11-04-open-thermo-data, the network's water), which the bundled sidecar does not list; the
     * dry formulations it lists did not move.
     */
    /** A column the bundled model covers: the game's medium-crude preset (steam, side draws and pumparounds, batch 2026-11-05-neural-retrain). */
    private static V3ColumnInput boundCdu() {
        var bundled=MaterialCatalog.bundled();
        return bundled.presets().column("medium_crude").input(bundled);
    }
    /** A package's compounds and their datasets: its parent's first, then its own, each its default dataset unless the entry names one. */
    private static List<String[]> members(JsonObject record,Map<String,JsonObject> packages,Map<String,JsonObject> components) {
        var members=new ArrayList<String[]>();
        if(record.has("extends"))members.addAll(members(packages.get(record.get("extends").getAsString()),packages,components));
        for(var entry:record.getAsJsonArray("components")) {
            String component=entry.isJsonObject()?entry.getAsJsonObject().get("id").getAsString():entry.getAsString();
            var named=entry.isJsonObject()?entry.getAsJsonObject().get("properties"):null;
            members.add(new String[]{component,named!=null?named.getAsString():components.get(component).get("properties").getAsString()});
        }
        return members;
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
        assertTrue(MaterialRuntime.with(edited,ID,()->isCurrent(ID,bundled.requirePackage(ID).scientificRevision())));
        var input=boundCdu();
        assertTrue(MaterialRuntime.with(edited,ID,()->V3NeuralRegistry.bundled().predict(input,V3SolveControl.UNBOUNDED).isPresent()));
    }
    /**
     * A dataset's freezing sub-record (plan section 3.6, D5) follows the viscosity rule: its own revision, and no identity
     * until a model reads it. Every package's column, fluid and spine identity and its physics content are the same with
     * and without the sub-records, so they move no world, result or neural model. Every bundled dataset carries one; the
     * water has none (its water model holds its triple point and its ice).
     */
    @Test void freezingSubRecordsAreInNoIdentity() {
        // With the pilot test overlay (O5 of batch 2026-11-04-open-thermo-data), so the spine package and its datasets stay covered.
        var bundled=PilotCryogenicTestCatalog.withPilot();var resources=new HashMap<>(bundled.resources());int stripped=0;
        for(var entry:resources.entrySet())if(entry.getKey().startsWith(ROOT+"properties/")) {
            var o=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            assertTrue(o.has("freezing"),entry.getKey());o.remove("freezing");entry.setValue(o.toString());stripped++;
        }
        assertEquals(49,stripped,"20 bundled datasets and the pilot's 4, the 24 Tier 1 imports (W6) and the crude_pr78 CO2 (O11 of batch 2026-11-04-open-thermo-data)");
        var without=MaterialCatalog.parse(resources);
        for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),without.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),without.fluidRevision(p.id()),p.id());
            assertEquals(bundled.spineRevision(p.id()),without.spineRevision(p.id()),p.id());
            assertEquals(bundled.physicsContent(p.id(),p.components()),without.physicsContent(p.id(),p.components()),p.id());
            for(String component:p.components()) {
                assertTrue(bundled.freezingPoint(p.id(),component).isPresent(),p.id()+" "+component);
                assertTrue(without.freezingPoint(p.id(),component).isEmpty(),p.id()+" "+component);
            }
            assertTrue(bundled.freezingPoint(p.id(),"Water").isEmpty(),p.id());
        }
        for(String id:List.of("icheme:pilot_cryogenic","icheme:refinery")) {
            var co2=bundled.freezingPoint(id,"CarbonDioxide").orElseThrow();
            assertEquals(bundled.crystals().get("icheme:crystal_carbon_dioxide_i").anchor().temperature(),co2.triplePointTemperature().doubleValue(),id);
        }
        var pc01=bundled.freezingPoint(ID,"crude_pc01").orElseThrow();
        assertTrue(pc01.estimated());assertNull(pc01.triplePointTemperature());
        assertFalse(bundled.freezingPoint(ID,"Methane").orElseThrow().estimated());
    }
    /**
     * A freezing sub-record is validated as it is read (positive finite numbers, a revision token, the required fields), and
     * a compound with a crystal record agrees with that record's anchor within 0.005 K; a refusal names both records.
     */
    @Test void aFreezingSubRecordIsValidatedAndAgreesWithItsCrystalAnchor() {
        String co2="properties/pilot_carbon_dioxide.json";var pilot=PilotCryogenicTestCatalog.withPilotResources();
        var far=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(pilot,co2,o->o.getAsJsonObject("freezing").addProperty("triple_point_kelvin",216.6))));
        assertTrue(far.getMessage().contains(co2)&&far.getMessage().contains("icheme:pilot_carbon_dioxide")&&far.getMessage().contains("icheme:crystal_carbon_dioxide_i"),far.getMessage());
        var near=MaterialCatalog.parse(changed(pilot,co2,o->o.getAsJsonObject("freezing").addProperty("triple_point_kelvin",216.596)));
        assertEquals(216.596,near.freezingPoint("icheme:pilot_cryogenic","CarbonDioxide").orElseThrow().anchorTemperature());
        // Without a triple point, the melting point is compared with the anchor.
        var melting=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(pilot,co2,o->{var f=o.getAsJsonObject("freezing");
            f.remove("triple_point_kelvin");f.addProperty("normal_melting_kelvin",194.7);})));
        assertTrue(melting.getMessage().contains("icheme:crystal_carbon_dioxide_i"),melting.getMessage());
        String pc07="properties/crude_pc07.json";
        for(var edit:List.<java.util.function.Consumer<JsonObject>>of(f->f.addProperty("normal_melting_kelvin",-1),f->f.addProperty("triple_point_pascal",0),
                f->f.addProperty("fusion_enthalpy_j_per_mol","x"),f->f.addProperty("revision","riazi 1995"),f->f.remove("estimated"),f->f.remove("source"))) {
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(pc07,o->edit.accept(o.getAsJsonObject("freezing")))));
            assertTrue(error.getMessage().contains(pc07)&&error.getMessage().contains("freezing."),error.getMessage());
        }
    }
    /**
     * A package is a method and a compound list, and its identity names those records, not the package record (option B,
     * 2026-11-04): an edit of the package record alone (its revision, its evidence, its list written out in place of
     * {@code extends}, in the same order) moves no identity, of the package or of a view.
     */
    @Test void aPackageRecordEditAloneMovesNoIdentity() {
        var bundled=MaterialCatalog.bundled();String network="icheme:tjl20_methane_nitrogen";
        var components=new JsonArray();for(String id:bundled.requirePackage(network).components())components.add(id);
        var edited=MaterialCatalog.parse(changed("packages/tjl20_nitrogen.json",o->{o.addProperty("revision",o.get("revision").getAsString()+"-edited");
            o.remove("extends");o.add("components",components);o.getAsJsonArray("advisory_evidence").add("EDITED: a package record edit");}));
        assertNotEquals(bundled.requirePackage(network).revision(),edited.requirePackage(network).revision());
        assertNotEquals(bundled.requirePackage(network).evidence(),edited.requirePackage(network).evidence());
        for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),edited.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),edited.fluidRevision(p.id()),p.id());
            assertEquals(bundled.spineRevision(p.id()),edited.spineRevision(p.id()),p.id());
        }
        for(var view:List.of(List.of("Nitrogen"),List.of("Methane","Ethane","crude_pc07"))) {
            assertEquals(bundled.restrict(network,view).requirePackage(network).scientificRevision(),edited.restrict(network,view).requirePackage(network).scientificRevision(),view.toString());
            assertEquals(bundled.restrict(network,view).fluidRevision(network),edited.restrict(network,view).fluidRevision(network),view.toString());
        }
        // The method is named by its physics fields (D7): its record's revision and evidence alone move nothing; its
        // liquid-mixture correction's own revision moves every package on it.
        var method=MaterialCatalog.parse(changed("methods/crude_pr78.json",o->{o.addProperty("revision",o.get("revision").getAsString()+"-edited");
            var evidence=new JsonArray();evidence.add("EDITED: a method record edit");o.add("advisory_evidence",evidence);}));
        var mixture=MaterialCatalog.parse(changed("methods/crude_pr78.json",o->o.getAsJsonObject("liquid_mixture").addProperty("revision","eccc-cut-pair-edited")));
        for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),method.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),method.fluidRevision(p.id()),p.id());
            boolean crude=p.scientificRevision().contains("liquid-mixture:eccc-cut-pair-r1");
            assertEquals(!crude,p.scientificRevision().equals(mixture.requirePackage(p.id()).scientificRevision()),p.id());
        }
    }
    /** A dataset's revision bump moves the identity of every view that holds its compound, and of no other. */
    @Test void aDatasetRevisionBumpMovesEveryViewThatHoldsItsCompoundAndNoOther() {
        var bundled=MaterialCatalog.bundled();String network="icheme:tjl20_methane_nitrogen";
        var edited=MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("revision",o.get("revision").getAsString()+"-edited")));
        var all=bundled.requirePackage(network).components();
        for(var view:List.of(List.of("crude_pc07"),List.of("Nitrogen","crude_pc07"),List.of("Nitrogen"),List.of("Methane","Ethane","crude_pc06","crude_pc08"),
                all.stream().filter(id->!id.equals("crude_pc07")).toList(),all)) {
            boolean holds=view.contains("crude_pc07");
            assertEquals(!holds,bundled.restrict(network,view).requirePackage(network).scientificRevision().equals(edited.restrict(network,view).requirePackage(network).scientificRevision()),view.toString());
            assertEquals(!holds,bundled.restrict(network,view).fluidRevision(network).equals(edited.restrict(network,view).fluidRevision(network)),view.toString());
        }
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
        // Aliases are catalog-wide, on the compounds: one alias for two compounds, or an alias that is a compound's id, is refused.
        var ambiguous=changed("components/methane.json",o->o.add("aliases",JsonParser.parseString("[\"C1\"]")));
        var ethane=JsonParser.parseString(ambiguous.get(ROOT+"components/ethane.json")).getAsJsonObject();ethane.add("aliases",JsonParser.parseString("[\"C1\"]"));
        ambiguous.put(ROOT+"components/ethane.json",ethane.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(ambiguous)).getMessage().contains("names two components"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("components/methane.json",o->o.add("aliases",JsonParser.parseString("[\"Ethane\"]")))))
                .getMessage().contains("is itself a component id"));
        // The error policy is the method's: the crude packages' methane pairs are not in their library.
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("methods/crude_pr78.json",o->o.addProperty("missing_interactions","error"))))
                .getMessage().contains("Missing interaction"));
        // A package names its method and compounds; a field of the method on it is refused, never silently read.
        var moved=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("packages/tjl20.json",o->o.addProperty("water_model","icheme:water"))));
        assertTrue(moved.getMessage().contains("packages/tjl20.json")&&moved.getMessage().contains("water_model"),moved.getMessage());
    }
    @Test void massAssaysConvertUsingSelectedMolecularWeightsAndVolumeReferencesAreChecked() {
        // Tia Juana is a mole-basis assay of the test fixture (O3 of batch 2026-11-04-open-thermo-data): rewritten here by mass.
        var original=TiaJuanaTestCatalog.catalog();var p=original.requirePackage(ID);var assay=p.assays().get(TiaJuanaTestCatalog.ASSAY_ID);
        var resources=changed(TiaJuanaTestCatalog.resources(),"assays/tjl20.json",o->{
            o.addProperty("basis","mass");var mass=new JsonObject();
            for(int i=0;i<p.components().size();i++)mass.addProperty(p.components().get(i),assay.amounts().get(i)*p.properties().get(i).molecularWeight());
            o.add("amounts_by_component",mass);
        });
        var changed=MaterialCatalog.parse(resources);
        double[] expected=TiaJuanaTestCatalog.thermo(ID).crudeFeed(assay.id()).moleFractions();
        double[] actual=MaterialRuntime.with(changed,ID,()->V3PengRobinsonThermo.fromRegisteredPackage(ID).crudeFeed(assay.id()).moleFractions());
        assertArrayEquals(expected,actual,1e-16);
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(TiaJuanaTestCatalog.resources(),"assays/tjl19.json",o->{o.addProperty("basis","standard_liquid_volume");o.addProperty("standard_temperature_kelvin",300);o.addProperty("standard_pressure_pascal",101325);})));
    }
    @Test void nrtlPairsAreDirectedCompleteAndNotAnImplementedSolver() {
        var r=new HashMap<>(MaterialCatalog.bundled().resources());
        r.put(ROOT+"interactions/nrtl.json","""
                {"schema_version":1,"id":"example:nrtl","model":"nrtl","revision":"example-nrtl-r1","source":"manufactured validation fixture",
                 "pairs":[{"first":"Ethane","second":"Propane","a12":1,"b12_kelvin":100,"a21":2,"b21_kelvin":200,
                 "alpha":0.3,"temperature_min_kelvin":298.15,"temperature_max_kelvin":900}]}
                """);
        r.put(ROOT+"methods/nrtl.json","""
                {"schema_version":1,"id":"example:nrtl_method","revision":"example-nrtl-method-r1","model":"nrtl","ideal_gas":"fit",
                 "interactions":["example:nrtl"],"missing_interactions":"error","water_model":"icheme:water",
                 "pressure_min_pascal":1000,"pressure_max_pascal":2000000}
                """);
        r.put(ROOT+"packages/nrtl.json","""
                {"schema_version":1,"id":"example:nrtl","revision":"example-nrtl-r1","method":"example:nrtl_method","components":["Ethane","Propane"]}
                """);
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

    /** A result is current only for the exact resolved scientific dataset (moved from MaterialRuntime, which had no other caller). */
    private static boolean isCurrent(String packageId, String scientificRevision) {
        var p = MaterialRuntime.current().packages().get(packageId);
        return p != null && p.scientificRevision().equals(scientificRevision);
    }
}
