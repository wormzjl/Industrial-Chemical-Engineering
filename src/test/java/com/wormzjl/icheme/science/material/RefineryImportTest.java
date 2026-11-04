package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Tier 1 import of batch 2026-11-04-refinery-compounds (W6, D8): 24 compounds in the new package
 * {@code icheme:refinery}, the pair library {@code icheme:dwsim_refinery_pr78} added to method {@code crude_pr78}, the
 * network preset on the new package, and the {@code estimated} flag on the isobutane and isopentane liquid viscosity
 * tables. Plan section 3.2 and owner decision D7 promise that a library for new compounds moves nothing else: the
 * catalog before the import is rebuilt here from the bundled resources (the imported records removed, the method,
 * the network preset and the two tables as they were), and every identity and every number is compared.
 */
class RefineryImportTest {
    private static final String ROOT="data/icheme/materials/";
    private static final String NETWORK="icheme:tjl20_methane_nitrogen", REFINERY="icheme:refinery", LIBRARY="icheme:dwsim_refinery_pr78";
    private static final List<String> OLD_PACKAGES=List.of("icheme:tjl19_dwsim","icheme:tjl20_methane",NETWORK,"icheme:pilot_cryogenic");
    private static final List<String> VISCOSITY_FLAGS=List.of("icheme:tjl19_isobutane","icheme:tjl19_isopentane");
    @AfterEach void reset(){MaterialRuntime.reset();}

    /**
     * The bundled resources as they stood before the import. Read with the Tia Juana test fixture, which holds
     * {@code tjl19_dwsim} since O3 of batch 2026-11-04-open-thermo-data.
     */
    private static MaterialCatalog before() {
        var resources=new HashMap<>(TiaJuanaTestCatalog.catalog().resources());
        resources.entrySet().removeIf(e->{
            String path=e.getKey();if(!path.startsWith(ROOT))return false;
            if(path.equals(ROOT+"transport/chemsep_liquid_reference.json")||path.equals(ROOT+"interactions/dwsim_refinery_pr78.json")||path.equals(ROOT+"packages/refinery.json"))return true;
            if(path.startsWith(ROOT+"properties/chemsep_"))return true;
            if(!path.startsWith(ROOT+"components/"))return false;
            var o=JsonParser.parseString(e.getValue()).getAsJsonObject();
            return o.has("properties")&&o.get("properties").getAsString().startsWith("icheme:chemsep_");
        });
        edit(resources,"methods/crude_pr78.json",o->{var libraries=new JsonArray();libraries.add("icheme:tjl20");o.add("interactions",libraries);o.addProperty("revision","crude-pr78-r1");});
        edit(resources,"networks/default.json",o->o.addProperty("package",NETWORK));
        for(String file:List.of("properties/tjl19_isobutane.json","properties/tjl19_isopentane.json"))
            edit(resources,file,o->{var liquid=o.getAsJsonObject("viscosity").getAsJsonObject("liquid");liquid.addProperty("revision","dwsim-viscosity-api-r1");liquid.addProperty("estimated",false);});
        return MaterialCatalog.parse(resources);
    }
    private static void edit(Map<String,String> resources,String path,java.util.function.Consumer<JsonObject> change) {
        var o=JsonParser.parseString(Objects.requireNonNull(resources.get(ROOT+path),path)).getAsJsonObject();change.accept(o);resources.put(ROOT+path,o.toString());
    }
    /** {@code identity} with the two liquid viscosity tables at their revisions before the import. */
    private static String withOldViscosities(String identity) {
        for(String id:VISCOSITY_FLAGS)identity=identity.replace(id+"/liquid@dwsim-viscosity-api-r2",id+"/liquid@dwsim-viscosity-api-r1");
        return identity;
    }

    @Test void theImportMovesNoColumnIdentityAndNoNumberOfTheOldPackages() {
        var before=before();var after=TiaJuanaTestCatalog.catalog();
        assertEquals(NETWORK,before.presets().network().packageId());
        assertEquals(REFINERY,after.presets().network().packageId());
        for(String id:OLD_PACKAGES) {
            var old=before.requirePackage(id);var now=after.requirePackage(id);
            assertEquals(old.scientificRevision(),now.scientificRevision(),id+": the column identity");
            assertEquals(before.physicsContent(id,old.components()),after.physicsContent(id,now.components()),id+": every number on its axis");
            boolean flagged=old.properties().stream().anyMatch(p->VISCOSITY_FLAGS.contains(p.id()));
            assertEquals(!flagged,before.fluidRevision(id).equals(after.fluidRevision(id)),id+": the fluid identity moves only with a flagged table");
            assertEquals(before.fluidRevision(id),withOldViscosities(after.fluidRevision(id)),id+": only the two viscosity tokens move");
            assertEquals(before.spineRevision(id),after.spineRevision(id),id);
        }
        // The new network package holds the old one first, with the same physics on its axis.
        var axis=before.requirePackage(NETWORK).components();var refinery=after.requirePackage(REFINERY);
        assertEquals(axis,refinery.components().subList(0,axis.size()));
        assertEquals(before.physicsContent(NETWORK,axis),after.physicsContent(REFINERY,axis),"the refinery package on the old network's axis");
        assertEquals(axis.size()+24,refinery.components().size());
        assertTrue(refinery.components().size()+1<=MaterialAxis.MAX_CONSERVED_COMPONENTS,"the world list stays under the cap");
    }

    /**
     * On the network, a view that holds neither isobutane nor isopentane keeps its column and fluid identity (an island
     * of such a world loads EXACT); a view holding either moves in those two tokens only.
     */
    @Test void aNetworkViewKeepsItsIdentityUnlessItHoldsAFlaggedTable() {
        var before=before();var after=TiaJuanaTestCatalog.catalog();var all=before.requirePackage(NETWORK).components();
        var views=List.of(List.of("Nitrogen","Methane"),List.of("Nitrogen","Ethane"),List.of("Nitrogen"),List.of("Methane","Ethane","Propane","N-butane","N-pentane"),
                List.of("Nitrogen","crude_pc03","crude_pc12"),List.of("Nitrogen","Isobutane"),List.of("Nitrogen","Isopentane","crude_pc01"),all);
        for(var view:views) {
            var old=before.restrict(NETWORK,view);var now=after.restrict(REFINERY,view);
            assertEquals(old.requirePackage(NETWORK).scientificRevision(),now.requirePackage(REFINERY).scientificRevision(),view+": the column identity");
            assertEquals(old.physicsContent(NETWORK,view),now.physicsContent(REFINERY,view),view+": every number");
            boolean flagged=view.contains("Isobutane")||view.contains("Isopentane");
            assertEquals(!flagged,old.fluidRevision(NETWORK).equals(now.fluidRevision(REFINERY)),view+": the fluid identity");
            assertEquals(old.fluidRevision(NETWORK),withOldViscosities(now.fluidRevision(REFINERY)),view+": only the two viscosity tokens move");
        }
    }

    /** The pair library (D3): every pair names a new compound, no pair names hydrogen, and Methane/HydrogenSulfide is ChemSep's 2009 value. */
    @Test void thePairLibraryAddsOnlyPairsWithANewCompound() {
        var after=TiaJuanaTestCatalog.catalog();var before=before();
        var added=new HashSet<>(after.requirePackage(REFINERY).components());added.removeAll(before.requirePackage(NETWORK).components());
        assertEquals(24,added.size());
        var library=JsonParser.parseString(after.resources().get(ROOT+"interactions/dwsim_refinery_pr78.json")).getAsJsonObject();
        assertEquals(LIBRARY,library.get("id").getAsString());
        double methaneSulfide=Double.NaN;
        for(var value:library.getAsJsonArray("pairs")) {
            var pair=value.getAsJsonObject();String a=pair.get("first").getAsString(),b=pair.get("second").getAsString();
            assertTrue(added.contains(a)||added.contains(b),a+"/"+b+" names no new compound");
            assertFalse(a.equals("Hydrogen")||b.equals("Hydrogen"),a+"/"+b+": every hydrogen pair is zero by rule");
            if(Set.of(a,b).equals(Set.of("Methane","HydrogenSulfide")))methaneSulfide=pair.get("kij").getAsDouble();
        }
        assertEquals(0.08125,methaneSulfide);
        var refinery=after.requirePackage(REFINERY);var c=refinery.components();
        assertEquals(0.08125,refinery.interactions().get(c.indexOf("Methane")).get(c.indexOf("HydrogenSulfide")));
        for(String other:c)assertEquals(0.0,refinery.interactions().get(c.indexOf("Hydrogen")).get(c.indexOf(other)),"Hydrogen/"+other);
    }
}
