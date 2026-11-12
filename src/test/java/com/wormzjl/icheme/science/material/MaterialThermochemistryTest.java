package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code thermochemistry} and {@code flammability} sub-records (O9 of batch 2026-11-04-open-thermo-data, owner decision
 * E23, form S2; data only): how they are read and refused, the data the world list ships, the heats Hess's law gives from
 * them, and that they are in no identity.
 */
class MaterialThermochemistryTest {
    private static final String ROOT="data/icheme/materials/";
    private static final String WORLD="icheme:refinery";
    private static final String METHANE="properties/tjl20_methane.json";
    private static final String WATER="water/water.json";
    private static final Set<String> NOT_COMBUSTIBLE=Set.of("Oxygen","SulfurDioxide","Nitrogen","CarbonDioxide");
    /**
     * Every compound of the world list and the water: its ChemSep v8.3 ideal-gas heat of formation at 298.15 K (J/mol;
     * O2 and N2 are 0 by definition) and its atoms of C, H, N, O and S.
     */
    private static final Map<String,double[]> FORMULA=Map.ofEntries(
            Map.entry("Methane",new double[]{-74520,1,4,0,0,0}),Map.entry("Ethane",new double[]{-83820,2,6,0,0,0}),
            Map.entry("Propane",new double[]{-104680,3,8,0,0,0}),Map.entry("Isobutane",new double[]{-134990,4,10,0,0,0}),
            Map.entry("N-butane",new double[]{-125790,4,10,0,0,0}),Map.entry("Isopentane",new double[]{-153700,5,12,0,0,0}),
            Map.entry("N-pentane",new double[]{-146760,5,12,0,0,0}),Map.entry("Hydrogen",new double[]{0,0,2,0,0,0}),
            Map.entry("HydrogenSulfide",new double[]{-20630,0,2,0,0,1}),Map.entry("CarbonMonoxide",new double[]{-110530,1,0,0,1,0}),
            Map.entry("Ammonia",new double[]{-45898,0,3,1,0,0}),Map.entry("CarbonylSulfide",new double[]{-138410,1,0,0,1,1}),
            Map.entry("Ethylene",new double[]{52510,2,4,0,0,0}),Map.entry("Propylene",new double[]{20230,3,6,0,0,0}),
            Map.entry("Butene-1",new double[]{-500,4,8,0,0,0}),Map.entry("Isobutene",new double[]{-17100,4,8,0,0,0}),
            Map.entry("Cis-2-butene",new double[]{-7400,4,8,0,0,0}),Map.entry("Trans-2-butene",new double[]{-11000,4,8,0,0,0}),
            Map.entry("Butadiene",new double[]{109240,4,6,0,0,0}),Map.entry("N-hexane",new double[]{-166940,6,14,0,0,0}),
            Map.entry("Cyclohexane",new double[]{-123300,6,12,0,0,0}),Map.entry("Benzene",new double[]{82880,6,6,0,0,0}),
            Map.entry("Toluene",new double[]{50170,7,8,0,0,0}),Map.entry("Ethylbenzene",new double[]{29920,8,10,0,0,0}),
            Map.entry("O-xylene",new double[]{19080,8,10,0,0,0}),Map.entry("M-xylene",new double[]{17320,8,10,0,0,0}),
            Map.entry("P-xylene",new double[]{18030,8,10,0,0,0}),Map.entry("MethylMercaptan",new double[]{-22600,1,4,0,0,1}),
            Map.entry("EthylMercaptan",new double[]{-46300,2,6,0,0,1}),Map.entry("Ethanol",new double[]{-234950,2,6,0,1,0}),
            Map.entry("Oxygen",new double[]{0,0,0,0,2,0}),Map.entry("Nitrogen",new double[]{0,0,0,2,0,0}),
            Map.entry("CarbonDioxide",new double[]{-393510,1,0,0,2,0}),Map.entry("SulfurDioxide",new double[]{-296840,0,0,0,2,1}),
            Map.entry("Water",new double[]{-241814,0,2,0,1,0}));
    private static final String[] ELEMENTS={"C","H","N","O","S"};
    /** The largest relative gap between a shipped formula's molar mass and its record's (the import tools check 0.02 g/mol). */
    private static final double MOLAR_MASS_TOLERANCE=1e-3;
    /** Net heats of combustion by Hess on these values, J/mol (as form S1 stored them). */
    private static final Map<String,Double> LHV=Map.of("Methane",802618.0,"Hydrogen",241814.0,"Ammonia",316823.0,
            "CarbonylSulfide",551940.0,"Benzene",3169382.0,"EthylMercaptan",1763002.0);
    /** Bulletin 627 (Zabetakis 1965) Table A-1 limits (lower, upper volume fraction, NaN for none; printed page), read 2026-11-08. */
    private static final Map<String,double[]> BULLETIN_627=Map.of(
            "Methane",new double[]{.05,.15,115},"Ethane",new double[]{.03,.124,115},"Hydrogen",new double[]{.04,.75,115},
            "Ethylene",new double[]{.027,.36,115},"Propylene",new double[]{.024,.11,115},"Butene-1",new double[]{.016,.10,114},
            "Isobutene",new double[]{.018,.096,115},"Cis-2-butene",new double[]{.017,.097,114},"Trans-2-butene",new double[]{.017,.097,114},
            "Isopentane",new double[]{.014,Double.NaN,115});

    private static Map<String,String> changed(String path,Consumer<JsonObject> edit) { return MaterialCatalogTest.changed(path,edit); }
    private static Map<String,Double> formula(double[] row) {
        var map=new HashMap<String,Double>();for(int i=0;i<ELEMENTS.length;i++)if(row[i+1]>0)map.put(ELEMENTS[i],row[i+1]);return map;
    }
    private static double atoms(MaterialCatalog.Thermochemistry t,String element) { return t.elements().getOrDefault(element,0.0); }
    /** Hess's law: the net heat of combustion to CO2, H2O (gas), SO2 and N2 on the package's own product records, J/mol. */
    private static double hess(MaterialCatalog catalog,MaterialCatalog.Thermochemistry fuel) {
        double co2=SubRecords.thermochemistry(catalog,WORLD,"CarbonDioxide").orElseThrow().formationEnthalpy();
        double h2o=SubRecords.thermochemistry(catalog,WORLD,"Water").orElseThrow().formationEnthalpy();
        double so2=SubRecords.thermochemistry(catalog,WORLD,"SulfurDioxide").orElseThrow().formationEnthalpy();
        return fuel.formationEnthalpy()-atoms(fuel,"C")*co2-atoms(fuel,"H")/2*h2o-atoms(fuel,"S")*so2;
    }

    /** The sub-records read back what the records hold; a dataset without them parses as before. */
    @Test void theSubRecordsRoundTripAndAreOptional() {
        var bundled=MaterialCatalog.bundled();
        var json=JsonParser.parseString(bundled.resources().get(ROOT+METHANE)).getAsJsonObject();
        var t=SubRecords.thermochemistry(bundled,WORLD,"Methane").orElseThrow();var tj=json.getAsJsonObject("thermochemistry");
        assertEquals(tj.get("formation_enthalpy_j_per_mol").getAsDouble(),t.formationEnthalpy());
        assertEquals(Map.of("C",1.0,"H",4.0),t.elements());
        assertEquals(tj.get("estimated").getAsBoolean(),t.estimated());
        assertEquals(tj.get("source").getAsString(),t.source());
        assertEquals(tj.get("revision").getAsString(),t.revision());
        var f=SubRecords.flammability(bundled,WORLD,"Methane").orElseThrow();var fj=json.getAsJsonObject("flammability");
        assertEquals(fj.get("lfl_volume_fraction").getAsDouble(),f.lowerLimit());
        assertEquals(fj.get("ufl_volume_fraction").getAsDouble(),f.upperLimit().doubleValue());
        assertEquals(fj.get("estimated").getAsBoolean(),f.estimated());
        assertEquals(fj.get("source").getAsString(),f.source());
        assertEquals(fj.get("revision").getAsString(),f.revision());
        // The water model carries its own; the water has no flammability.
        assertEquals(-241814,SubRecords.thermochemistry(bundled,WORLD,"Water").orElseThrow().formationEnthalpy());
        assertTrue(SubRecords.flammability(bundled,WORLD,"Water").isEmpty());
        // Without the sub-records, the records parse as they did before O9.
        var without=MaterialCatalog.parse(changed(METHANE,o->{o.remove("thermochemistry");o.remove("flammability");}));
        assertTrue(SubRecords.thermochemistry(without,WORLD,"Methane").isEmpty());assertTrue(SubRecords.flammability(without,WORLD,"Methane").isEmpty());
        assertEquals(bundled.requirePackage(WORLD).properties(),without.requirePackage(WORLD).properties());
        var dryWater=MaterialCatalog.parse(changed(WATER,o->o.remove("thermochemistry")));
        assertTrue(SubRecords.thermochemistry(dryWater,WORLD,"Water").isEmpty());
        var lowerOnly=MaterialCatalog.parse(changed(METHANE,o->o.getAsJsonObject("flammability").remove("ufl_volume_fraction")));
        assertNull(SubRecords.flammability(lowerOnly,WORLD,"Methane").orElseThrow().upperLimit());
        assertThrows(IllegalArgumentException.class,()->SubRecords.thermochemistry(bundled,WORLD,"Missing"));
        assertThrows(IllegalArgumentException.class,()->SubRecords.flammability(bundled,WORLD,"Missing"));
    }

    /** Each invalid value or missing field refuses the catalog, naming the record's file and the field. */
    @Test void anInvalidSubRecordRefusesTheCatalog() {
        List<Consumer<JsonObject>> thermo=List.of(
                t->t.addProperty("formation_enthalpy_j_per_mol","x"),t->t.add("formation_enthalpy_j_per_mol",new JsonPrimitive(new BigDecimal("1e400"))),
                t->t.remove("formation_enthalpy_j_per_mol"),t->t.remove("elements"),t->t.add("elements",new JsonObject()),
                t->t.addProperty("elements",1),t->t.getAsJsonObject("elements").addProperty("Fe",1),
                t->t.getAsJsonObject("elements").addProperty("C",-1),t->t.getAsJsonObject("elements").addProperty("C",0),
                t->t.getAsJsonObject("elements").addProperty("C","x"),
                t->t.remove("estimated"),t->t.addProperty("estimated","no"),t->t.remove("source"),t->t.addProperty("source",""),
                t->t.addProperty("revision","chemsep hf"),t->t.addProperty("revision","r".repeat(65)),t->t.remove("revision"));
        for(int i=0;i<thermo.size();i++) {
            var edit=thermo.get(i);
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(METHANE,o->edit.accept(o.getAsJsonObject("thermochemistry")))),"thermochemistry edit "+i);
            assertTrue(error.getMessage().contains(METHANE)&&error.getMessage().contains("thermochemistry."),"edit "+i+": "+error.getMessage());
        }
        var water=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(WATER,o->o.getAsJsonObject("thermochemistry").remove("source"))));
        assertTrue(water.getMessage().contains(WATER)&&water.getMessage().contains("thermochemistry.source"),water.getMessage());
        // The formula is not checked against the record's molar mass at load: a dataset edit is never refused for it.
        assertDoesNotThrow(()->MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("molecular_weight_kg_per_mol",.31))));
        List<Consumer<JsonObject>> limits=List.of(
                f->f.addProperty("lfl_volume_fraction",0),f->f.addProperty("lfl_volume_fraction",1),f->f.remove("lfl_volume_fraction"),
                f->f.addProperty("ufl_volume_fraction",.05),f->f.addProperty("ufl_volume_fraction",.04),f->f.addProperty("ufl_volume_fraction",1),
                f->f.remove("estimated"),f->f.addProperty("source"," "),f->f.addProperty("revision","npg b627"),f->f.remove("revision"));
        for(int i=0;i<limits.size();i++) {
            var edit=limits.get(i);
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(METHANE,o->edit.accept(o.getAsJsonObject("flammability")))),"flammability edit "+i);
            assertTrue(error.getMessage().contains(METHANE)&&error.getMessage().contains("flammability."),"edit "+i+": "+error.getMessage());
        }
    }

    /**
     * The world list ships thermochemistry on every compound and the water, and flammability on what burns (carbonyl sulfide
     * has no verified limits); the compounds' values are ChemSep's; Hess's law on them gives the net heats and the
     * stoichiometry; the cuts are estimated (Cragoe 1929 formula and heat, Burgess-Wheeler lower limit, no upper limit);
     * the Bulletin 627 limits are pinned; no value cites CAMEO.
     */
    @Test void theWorldListCarriesThermochemistryAndTheLimitsOfWhatBurns() {
        var catalog=MaterialCatalog.bundled();var world=catalog.requirePackage(WORLD);
        assertEquals(46,world.components().size(),"7 light ends, 12 cuts, N2, the 24 Tier 1 compounds, CO2 and ethanol (W6: ethanol joins icheme:refinery)");
        var water=SubRecords.thermochemistry(catalog,WORLD,"Water").orElseThrow();
        assertEquals(formula(FORMULA.get("Water")),water.elements());assertEquals(-241814,water.formationEnthalpy());
        assertEquals(.01801528,SubRecords.molarMass(water),.01801528*MOLAR_MASS_TOLERANCE,"the water's formula");
        int compounds=0,cuts=0,limits=0;
        for(String component:world.components()) {
            var t=SubRecords.thermochemistry(catalog,WORLD,component).orElseThrow(()->new AssertionError(component+" has no thermochemistry"));
            var f=SubRecords.flammability(catalog,WORLD,component);
            assertFalse(t.source().contains("CAMEO")||f.isPresent()&&f.get().source().contains("CAMEO"),component);
            double mass=world.properties().get(world.components().indexOf(component)).molecularWeight();
            assertEquals(mass,SubRecords.molarMass(t),mass*MOLAR_MASS_TOLERANCE,component+": the formula's molar mass");
            if(component.startsWith("crude_pc")) {
                cuts++;limits++;
                assertTrue(t.estimated(),component);assertEquals(Set.of("C","H"),t.elements().keySet(),component);
                double sg=world.properties().get(world.components().indexOf(component)).density()/999.016,h=26-15*sg;
                double cragoe=(51.916-8.792*sg*sg-0.2122*h)*mass*1e6;
                assertEquals(cragoe,hess(catalog,t),cragoe*1e-9,component+": Hess reproduces Cragoe's net heat");
                assertEquals(h/100*mass*1000/1.008,atoms(t,"H"),1e-9,component+": Cragoe's hydrogen");
                var limit=f.orElseThrow();
                assertTrue(limit.estimated(),component);assertNull(limit.upperLimit(),component);
                assertEquals(4319,limit.lowerLimit()*100*hess(catalog,t)/1000,1e-6,component+": Burgess-Wheeler");
                continue;
            }
            compounds++;
            var row=FORMULA.get(component);assertNotNull(row,component);
            assertFalse(t.estimated(),component);
            assertEquals(row[0],t.formationEnthalpy(),component);assertEquals(formula(row),t.elements(),component);
            if(NOT_COMBUSTIBLE.contains(component)) { assertTrue(f.isEmpty(),component); continue; }
            assertTrue(hess(catalog,t)>0,component);
            if(LHV.containsKey(component))assertEquals(LHV.get(component).doubleValue(),hess(catalog,t),1e-6,component+": Hess");
            var bulletin=BULLETIN_627.get(component);
            if(component.equals("CarbonylSulfide")) { assertTrue(f.isEmpty(),"Bulletin 627 has no carbonyl sulfide entry"); continue; }
            var limit=f.orElseThrow(()->new AssertionError(component+" has no flammability"));limits++;
            assertFalse(limit.estimated(),component);
            if(bulletin!=null) {
                assertEquals(bulletin[0],limit.lowerLimit(),component);
                if(Double.isNaN(bulletin[1]))assertNull(limit.upperLimit(),component);
                else assertEquals(bulletin[1],limit.upperLimit().doubleValue(),component);
                assertTrue(limit.source().contains("Bulletin 627")&&limit.source().contains("p. "+(int)bulletin[2]),limit.source());
            } else {
                assertTrue(limit.source().contains("NIOSH Pocket Guide"),component);assertNotNull(limit.upperLimit(),component);
            }
        }
        // W6: ethanol joins icheme:refinery (one more compound with a formula and a NIOSH limit).
        assertEquals(34,compounds);assertEquals(12,cuts);assertEquals(41,limits);
        // What the formula adds: oxygen demand and flue gas per mole of fuel (ammonia: 0.75 O2, 1.5 H2O, 0.5 N2).
        var ammonia=SubRecords.thermochemistry(catalog,WORLD,"Ammonia").orElseThrow();
        assertEquals(.75,atoms(ammonia,"C")+atoms(ammonia,"H")/4+atoms(ammonia,"S")-atoms(ammonia,"O")/2);
        var cos=SubRecords.thermochemistry(catalog,WORLD,"CarbonylSulfide").orElseThrow();
        assertEquals(1.5,atoms(cos,"C")+atoms(cos,"H")/4+atoms(cos,"S")-atoms(cos,"O")/2,"COS + 1.5 O2 -> CO2 + SO2");
    }

    /**
     * The sub-records are in no identity: every package's column, fluid and spine identity and its physics content are the
     * same with and without them, and an edit declared with a sub-record's own revision moves none either.
     */
    @Test void thermochemistryAndFlammabilityAreInNoIdentity() {
        var bundled=MaterialCatalog.bundled();var resources=new HashMap<>(bundled.resources());int thermo=0,limits=0;
        for(var entry:resources.entrySet())if(entry.getKey().startsWith(ROOT+"properties/")||entry.getKey().startsWith(ROOT+"water/")) {
            var o=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            boolean t=o.remove("thermochemistry")!=null,f=o.remove("flammability")!=null,c=o.remove("combustion")!=null;
            if(t)thermo++;if(f)limits++;if(t||f||c)entry.setValue(o.toString());
        }
        // W6: ethanol's main record (icheme:chemsep_ethanol) has thermochemistry and flammability: 46 and 40 before it.
        assertEquals(47,thermo,"the 45 compounds of the world list, ethanol and the water");assertEquals(41,limits,"29 compounds and 12 cuts");
        var without=MaterialCatalog.parse(resources);
        var edited=MaterialCatalog.parse(changed(METHANE,o->{var t=o.getAsJsonObject("thermochemistry");
            t.addProperty("formation_enthalpy_j_per_mol",-75000);t.addProperty("revision",t.get("revision").getAsString()+"-edited");
            var f=o.getAsJsonObject("flammability");f.addProperty("lfl_volume_fraction",.044);f.addProperty("revision",f.get("revision").getAsString()+"-edited");}));
        for(var other:List.of(without,edited))for(var p:bundled.packages().values()) {
            assertEquals(p.scientificRevision(),other.requirePackage(p.id()).scientificRevision(),p.id());
            assertEquals(bundled.fluidRevision(p.id()),other.fluidRevision(p.id()),p.id());
            assertEquals(bundled.spineRevision(p.id()),other.spineRevision(p.id()),p.id());
            assertEquals(bundled.physicsContent(p.id(),p.components()),other.physicsContent(p.id(),p.components()),p.id());
        }
        assertEquals(-75000,SubRecords.thermochemistry(edited,WORLD,"Methane").orElseThrow().formationEnthalpy());
        assertEquals(.044,SubRecords.flammability(edited,WORLD,"Methane").orElseThrow().lowerLimit());
    }

    /**
     * The {@code combustion} sub-record (batch 2026-11-09-furnace-fuel-power, owner decision F2): every record of the world
     * list and the water carries the lower heating value Hess's law gives on its own thermochemistry, in the fluid network's
     * conserved order; the inerts give none; an invalid one refuses the catalog; an edit moves no identity (above).
     */
    @Test void everyRecordCarriesItsLowerHeatingValue() {
        var catalog=MaterialCatalog.bundled();var world=catalog.requirePackage(WORLD);
        var values=catalog.heatingValues(WORLD);
        assertEquals(world.components().size()+1,values.length,"the components, then the water");
        for(int i=0;i<world.components().size();i++) {
            String component=world.components().get(i);
            double hess=Math.max(0,hess(catalog,SubRecords.thermochemistry(catalog,WORLD,component).orElseThrow()));
            assertEquals(hess,values[i],1e-6*Math.max(1,hess),component);
            if(NOT_COMBUSTIBLE.contains(component))assertEquals(0,values[i],component+" gives no heat");
        }
        assertEquals(0,values[values.length-1],"the water gives none");
        int methane=world.components().indexOf("Methane");
        assertEquals(802618,values[methane],1e-6);
        assertEquals(5.003e7,values[methane]/world.properties().get(methane).molecularWeight(),1e5,"50.0 MJ/kg");
        var without=MaterialCatalog.parse(changed(METHANE,o->o.remove("combustion")));
        assertEquals(0,without.heatingValues(WORLD)[methane],"without the sub-record a record burns as an inert");
        List<Consumer<JsonObject>> edits=List.of(c->c.addProperty("lower_heating_value_j_per_mol",-1),c->c.addProperty("lower_heating_value_j_per_mol","x"),
                c->c.remove("lower_heating_value_j_per_mol"),c->c.remove("estimated"),c->c.remove("source"),c->c.addProperty("revision","hess lhv"),c->c.remove("revision"));
        for(int i=0;i<edits.size();i++) {
            var edit=edits.get(i);
            var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed(METHANE,o->edit.accept(o.getAsJsonObject("combustion")))),"combustion edit "+i);
            assertTrue(error.getMessage().contains(METHANE)&&error.getMessage().contains("combustion."),"edit "+i+": "+error.getMessage());
        }
    }
}
