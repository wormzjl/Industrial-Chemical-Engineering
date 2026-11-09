package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CO2 in the world's package (O11 of batch 2026-11-04-open-thermo-data, owner decisions E25, E27 and E28): the package
 * names the ChemSep dataset and the CO2-I crystal, its pairs come from their own library after the others, and a view
 * without CO2 keeps its identity and its numbers. The comparison catalog is the bundled one with CO2 taken out again
 * (the package's compound and crystal, the method's CO2 library, the CO2 fluid preset), so it isolates CO2's joining from
 * every other change.
 */
class RefineryCarbonDioxideCatalogTest {
    static final String REFINERY = "icheme:refinery";
    static final String CRYSTAL = "icheme:crystal_carbon_dioxide_i";
    static final List<String> CRUDE = List.of("Nitrogen", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane",
            "crude_pc01", "crude_pc02", "crude_pc03", "crude_pc04", "crude_pc05", "crude_pc06", "crude_pc07", "crude_pc08", "crude_pc09",
            "crude_pc10", "crude_pc11", "crude_pc12");
    static final List<String> LIGHT = List.of("Methane", "Ethane", "HydrogenSulfide", "Benzene");

    @Test
    void theRefineryPackageNamesTheDatasetAndTheCrystal() {
        var catalog = MaterialCatalog.bundled();
        var refinery = catalog.requirePackage(REFINERY);
        int co2 = refinery.components().indexOf("CarbonDioxide");
        assertEquals(refinery.components().size() - 1, co2, "CO2 joins at the end of the world list");
        assertEquals("icheme:chemsep_carbon_dioxide", refinery.properties().get(co2).id());
        assertEquals(List.of(CRYSTAL), refinery.crystals());
        assertEquals(List.of(CRYSTAL), com.wormzjl.icheme.science.thermo.phase.PhaseContract.admittedCrystals(catalog, REFINERY));
        // The pilot is a test overlay since O5 (E25): the game's CO2 record defaults to the game's dataset, the overlay's to the pilot's.
        assertFalse(catalog.packages().containsKey(PilotCryogenicTestCatalog.PACKAGE_ID));
        var pilot = PilotCryogenicTestCatalog.withPilot().requirePackage("icheme:pilot_cryogenic");
        assertEquals("icheme:pilot_carbon_dioxide", pilot.properties().get(pilot.components().indexOf("CarbonDioxide")).id());
        // The CO2 fluid preset is on the network's list.
        assertEquals("CarbonDioxide", catalog.presets().fluid("carbon_dioxide").component());
        assertEquals(true, catalog.presets().network().fluidPresets().contains("carbon_dioxide"));
    }

    /** The 16 CO2 pairs (8 from icheme:dwsim_refinery_pr78, 8 from icheme:dwsim_refinery_pr78_co2) and the N2 pairs of E28. */
    @Test
    void thePairsFollowD3AndTheNitrogenErratum() {
        var refinery = MaterialCatalog.bundled().requirePackage(REFINERY);
        Map<String, Double> co2 = Map.ofEntries(Map.entry("HydrogenSulfide", 0.0967), Map.entry("Ethylene", 0.0541), Map.entry("Propylene", 0.0933),
                Map.entry("Butene-1", 0.0593), Map.entry("N-hexane", 0.11), Map.entry("Cyclohexane", 0.1052), Map.entry("Benzene", 0.0774),
                Map.entry("Toluene", 0.1056), Map.entry("Methane", 0.0885), Map.entry("Ethane", 0.13), Map.entry("Propane", 0.0978),
                Map.entry("Isobutane", 0.13), Map.entry("N-butane", 0.1352), Map.entry("Isopentane", 0.1219), Map.entry("N-pentane", 0.1252),
                Map.entry("Nitrogen", -0.0193));
        int c = refinery.components().indexOf("CarbonDioxide");
        for (int j = 0; j < refinery.components().size(); j++) {
            String other = refinery.components().get(j);
            double expected = j == c ? 0 : co2.getOrDefault(other, 0.0);
            assertEquals(expected, refinery.interactions().get(c).get(j), 0.0, "CarbonDioxide/" + other);
            assertEquals(expected, refinery.interactions().get(j).get(c), 0.0, other + "/CarbonDioxide");
        }
        int n = refinery.components().indexOf("Nitrogen");
        assertEquals(0.1652, refinery.interactions().get(n).get(refinery.components().indexOf("HydrogenSulfide")), 0.0, "N2/H2S (DECHEMA p318; was 0.1641)");
        assertEquals(0.1641, refinery.interactions().get(n).get(refinery.components().indexOf("Benzene")), 0.0, "N2/benzene");
    }

    /**
     * A view without CO2 keeps its scientific and fluid identity, its network revision and its numbers; a view with CO2
     * holds the crystal and its identity names the crystal record.
     */
    @Test
    void aViewWithoutCarbonDioxideKeepsItsIdentityAndNumbers() {
        var bundled = MaterialCatalog.bundled();
        var without = withoutCarbonDioxide(bundled);
        for (var compounds : List.of(CRUDE, LIGHT)) {
            var after = bundled.restrict(REFINERY, compounds);
            var before = without.restrict(REFINERY, compounds);
            assertEquals(before.requirePackage(REFINERY).scientificRevision(), after.requirePackage(REFINERY).scientificRevision(), compounds.toString());
            assertEquals(before.fluidRevision(REFINERY), after.fluidRevision(REFINERY), compounds.toString());
            var modelAfter = FluidThermodynamics.forNetwork(bundled, REFINERY).on(compounds);
            var modelBefore = FluidThermodynamics.forNetwork(without, REFINERY).on(compounds);
            assertEquals(modelBefore.revision(), modelAfter.revision(), compounds.toString());
            assertEquals(List.of(), modelAfter.crystalSolids().all().subList(0, modelAfter.crystalSolids().all().size() - 1), "only the ice");
            double[] overall = new double[compounds.size() + 1];
            for (int i = 0; i < compounds.size(); i++) overall[i] = 1.0 + i;
            overall[compounds.size()] = 0.5;
            for (double[] tp : new double[][] {{300, 2e6}, {450, 5e5}, {350, 1e5}}) {
                var a = modelAfter.flashTP(tp[0], tp[1], overall, () -> {});
                var b = modelBefore.flashTP(tp[0], tp[1], overall, () -> {});
                assertEquals(b.temperature(), a.temperature(), 0.0);
                assertArrayEquals(b.liquid(), a.liquid(), 0.0, compounds + " liquid at " + tp[0] + " K");
                assertArrayEquals(b.vapor(), a.vapor(), 0.0, compounds + " vapour at " + tp[0] + " K");
                assertEquals(b.waterLiquid(), a.waterLiquid(), 0.0);
                assertEquals(b.waterVapor(), a.waterVapor(), 0.0);
            }
        }
        var co2 = bundled.restrict(REFINERY, List.of("Methane", "CarbonDioxide"));
        assertEquals(List.of(CRYSTAL), co2.requirePackage(REFINERY).crystals());
        var model = FluidThermodynamics.forNetwork(bundled, REFINERY).on(List.of("Methane", "CarbonDioxide"));
        assertNotEquals(-1, model.thermodynamicRevision().indexOf(CRYSTAL + "@" + bundled.crystals().get(CRYSTAL).revision()),
                "a CO2 view's network revision names the crystal record");
    }

    /** The bundled catalog with CO2 taken out of the world's package, its method and its network presets. */
    static MaterialCatalog withoutCarbonDioxide(MaterialCatalog bundled) {
        var resources = new HashMap<>(bundled.resources());
        String pkg = "data/icheme/materials/packages/refinery.json", method = "data/icheme/materials/methods/crude_pr78.json",
                network = "data/icheme/materials/networks/default.json";
        var record = JsonParser.parseString(resources.get(pkg)).getAsJsonObject();
        var components = new JsonArray();
        for (var e : record.getAsJsonArray("components"))
            if (!(e.isJsonObject() && e.getAsJsonObject().get("id").getAsString().equals("CarbonDioxide"))) components.add(e);
        record.add("components", components);
        record.remove("crystals");
        resources.put(pkg, record.toString());
        var m = JsonParser.parseString(resources.get(method)).getAsJsonObject();
        var libraries = new JsonArray();
        for (var e : m.getAsJsonArray("interactions")) if (!e.getAsString().equals("icheme:dwsim_refinery_pr78_co2")) libraries.add(e);
        m.add("interactions", libraries);
        resources.put(method, m.toString());
        var n = JsonParser.parseString(resources.get(network)).getAsJsonObject();
        var presets = new JsonArray();
        for (var e : n.getAsJsonArray("fluid_presets")) if (!e.getAsString().equals("carbon_dioxide")) presets.add(e);
        n.add("fluid_presets", presets);
        resources.put(network, n.toString());
        var without = MaterialCatalog.parse(resources);
        assertEquals(-1, without.requirePackage(REFINERY).components().indexOf("CarbonDioxide"));
        return without;
    }
}
