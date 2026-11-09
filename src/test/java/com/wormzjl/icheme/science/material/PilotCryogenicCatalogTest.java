package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The bundled pilot package {@code icheme:pilot_cryogenic} (batch 2026-09-24-coolprop-low-temperature; P2 record
 * kinds, P3 WP3 bundling): it loads from the main resources, it is the only package with a reference spine, the test
 * network override selects it, the loader enforces its rules (spine and crystals, D6's single ideal-gas source, the
 * {@code volume_translation} field), and the eight column and network packages keep their declared revisions and the
 * physics they share.
 */
class PilotCryogenicCatalogTest {
    private static final String PACKAGE = "data/icheme/materials/packages/pilot_cryogenic.json";
    private static final String NITROGEN = "data/icheme/materials/spine/nitrogen.json";
    private static final String METHANE = "data/icheme/materials/spine/methane.json";
    private static final String CRYSTAL = "data/icheme/materials/crystals/carbon_dioxide_i.json";
    private static final String PILOT_METHANE = "data/icheme/materials/properties/pilot_methane.json";
    private static final String TJL20_METHANE = "data/icheme/materials/properties/tjl20_methane.json";
    private static final String CALIBRATION = "data/icheme/materials/transport/liquid_calibration.json";

    /**
     * Package id and record of every bundled column and network package: the package's id and its record's own declared
     * revision. Since option B of the varying-components review (2026-11-04) the package record is in no identity: an
     * identity is the method and the records it names, so a package record edit alone moves none. Until 2026-10-26 these
     * pins were hashes of the data, with a physics fingerprint per package (their history is in this file's git log);
     * since then nothing is hashed and each record declares its own revision (owner decisions): the rest of an identity
     * is every record's revision (MaterialCatalogTest checks the composition), and the physics the packages share is
     * compared directly ({@link #TJL20_PHYSICS}). The refinery record is r3 since O11 of batch 2026-11-04-open-thermo-data
     * (decisions E25, E27): it holds CO2 on its ChemSep dataset and admits the CO2-I crystal, the only column or network
     * package with a crystal.
     */
    private static final String[][] BUNDLED_PINS = {
        {"icheme:tjl20_methane", "icheme:tjl20_methane@crude-regrouped-r3"},
        {"icheme:tjl20_methane_nitrogen", "icheme:tjl20_methane_nitrogen@crude-regrouped-r3"},
        {"icheme:refinery", "icheme:refinery@refinery-tier1-r3"},
    };
    private static final String TJL20 = "icheme:tjl20_methane";
    /**
     * The packages with tjl20_methane's physics on its axis: the old network package, whose nitrogen lies outside that
     * axis, and the network package icheme:refinery, which extends it (W6).
     */
    private static final List<String> TJL20_PHYSICS = List.of("icheme:tjl20_methane_nitrogen", "icheme:refinery");

    /**
     * The pilot package as committed in P3 WP3 (pilot records r1, the E-PPR78 rule on icheme:eppr78_2022 r1), with
     * the spine records of P3 WP9a (N2, CH4 and C2H6 r2, CO2 r3) and the CO2-I crystal of P4 stage 1 (r2): the package's
     * identity, every record it names for its phase physics by its declared revision (its viscosities are in the fluid
     * identity), and the spine identity of its spine and crystal records' declared revisions. An edit of a record bumps
     * the record's revision, which moves the pin. Until 2026-10-26 these were hashes of the data (history in this file's
     * git log). Since batch 2026-11-04-refinery-compounds the package names its method (r2), which holds the model, the
     * pair libraries and the water model; since option B (2026-11-04) the package record leaves the identity and the method leads;
     * since D7 (2026-11-04) the method is named by its physics fields, not by its record's revision.
     */
    private static final String PILOT_SCIENTIFIC_REVISION = String.join(";",
            "method=model:pr78,ideal-gas:spine,missing-interactions:error,pressure-pascal:100..10000000",
            "properties=" + String.join(",",
                    "icheme:pilot_nitrogen@pilot-nitrogen-p3-r1",
                    "icheme:pilot_methane@pilot-methane-p3-r1",
                    "icheme:pilot_ethane@pilot-ethane-p3-r1",
                    "icheme:pilot_carbon_dioxide@pilot-carbon-dioxide-p3-r1"),
            "interactions=icheme:pilot_cryogenic@pilot-cryogenic-eppr78-r1",
            "group-interactions=icheme:eppr78_2022@eppr78-2022-si-table-s4-r2",
            "water=icheme:water@water-iapws95-shomate-r3");
    private static final String PILOT_SPINE_REVISION = "spine:icheme:spine_nitrogen@spine-nitrogen-r3,icheme:spine_methane@spine-methane-r3,"
            + "icheme:spine_ethane@spine-ethane-r3,icheme:spine_carbon_dioxide@spine-carbon_dioxide-r4,icheme:crystal_carbon_dioxide_i@crystal-carbon_dioxide-i-r2";

    /**
     * tjl19_dwsim, a test fixture since O3 of batch 2026-11-04-open-thermo-data, had a fingerprint of its own for its
     * 18-component axis; on that axis its physics is tjl20's.
     */
    @Test
    void theTiaJuanaLiteraturePackageHasTjl20PhysicsOnItsAxis() {
        var catalog = TiaJuanaTestCatalog.catalog();
        var dwsim = catalog.requirePackage("icheme:tjl19_dwsim");
        assertEquals("icheme:tjl19_dwsim@crude-regrouped-r2", dwsim.id() + "@" + dwsim.revision());
        assertTrue(catalog.requirePackage(TJL20).components().containsAll(dwsim.components()));
        assertEquals(catalog.physicsContent(TJL20, dwsim.components()), catalog.physicsContent(dwsim.id(), dwsim.components()), "tjl19_dwsim on its own axis");
    }

    @Test
    void bundledPackageRevisionsAndSharedPhysicsAreUnchanged() {
        for (MaterialCatalog catalog : List.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PilotCryogenicTestCatalog.catalog())) {
            for (String[] pin : BUNDLED_PINS) {
                var p = catalog.requirePackage(pin[0]);
                assertEquals(pin[1], p.id() + "@" + p.revision(), pin[0]);
                assertTrue(p.scientificRevision().startsWith("method=") && !p.scientificRevision().contains(pin[1]), p.scientificRevision());
                boolean crystal = pin[0].equals("icheme:refinery");
                assertTrue(p.spine().isEmpty() && p.groupContributions() == null, pin[0]);
                assertEquals(crystal ? List.of("icheme:crystal_carbon_dioxide_i") : List.of(), p.crystals(), pin[0]);
                assertEquals(crystal ? "spine:icheme:crystal_carbon_dioxide_i@crystal-carbon_dioxide-i-r2" : "", catalog.spineRevision(pin[0]), pin[0]);
            }
            var axis = catalog.requirePackage(TJL20).components();
            for (String id : TJL20_PHYSICS) assertEquals(catalog.physicsContent(TJL20, axis), catalog.physicsContent(id, axis), id);
            // The pilot is the fourth bundled package and the only one with a reference spine.
            assertEquals(BUNDLED_PINS.length + 1, catalog.packages().size());
            assertEquals(List.of(PilotCryogenicTestCatalog.PACKAGE_ID), catalog.packages().values().stream()
                    .filter(p -> !p.spine().isEmpty()).map(MaterialCatalog.Package::id).toList());
            var pilot = catalog.requirePackage(PilotCryogenicTestCatalog.PACKAGE_ID);
            assertEquals(4, pilot.components().stream().map(c -> catalog.requireSpine(pilot.id(), c)).distinct().count());
            // CO2-I, and the free water's ice Ih (WP4), which every package's water record names.
            assertEquals(2, catalog.crystals().size());
        }
    }

    @Test
    void pilotPackageIsBundled() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var p = catalog.requirePackage(PilotCryogenicTestCatalog.PACKAGE_ID);
        System.out.printf("P3 pilot identity: scientific revision %s, spine %s, fluid %s%n", p.scientificRevision(),
                catalog.spineRevision(p.id()), catalog.fluidRevision(p.id()));
        assertEquals(PILOT_SCIENTIFIC_REVISION, p.scientificRevision());
        assertEquals(PILOT_SPINE_REVISION, catalog.spineRevision(p.id()));
        assertEquals(List.of("Nitrogen", "Methane", "Ethane", "CarbonDioxide"), p.components());
        assertEquals(List.of("icheme:pilot_nitrogen", "icheme:pilot_methane", "icheme:pilot_ethane", "icheme:pilot_carbon_dioxide"),
                p.properties().stream().map(MaterialCatalog.Property::id).toList());
        assertEquals(List.of("icheme:spine_nitrogen", "icheme:spine_methane", "icheme:spine_ethane",
                "icheme:spine_carbon_dioxide"), p.spine());
        assertEquals(List.of("icheme:crystal_carbon_dioxide_i"), p.crystals());
        assertEquals("pr78", p.model());
        for (int i = 0; i < p.components().size(); i++) {
            var property = p.properties().get(i);
            assertTrue(property.cp().isEmpty(), property.id() + ": the spine is the only ideal-gas source (D6)");
            assertNotNull(property.volumeTranslation(), property.id());
            assertEquals(catalog.volumeTranslation(p.id(), p.components().get(i)).orElseThrow(), property.volumeTranslation());
            IdealGasFunction function = catalog.requireSpine(p.id(), p.components().get(i));
            assertTrue(function.minimumTemperature() <= catalog.fluidValidity(p.id(), p.components().get(i)).orElseThrow().minimumTemperature());
            // Methane's ideal gas ends at 1200 K (D13, spine r2), the package's upper temperature; the others reach 6000 K.
            assertEquals(p.components().get(i).equals("Methane") ? 1200 : 6000, function.maximumTemperature(), 0.0);
            // The vapour table spans the component's fluid range, and for the species of the admitted crystal the gas
            // floor of the crystal competition (CO2 from 90 K, record r2 of P4 stage 2b); the liquid table runs from the
            // triple point to Tc, and for the crystal's species from the competition's floor (CO2's subcooled liquid from
            // 90 K, dissolved in liquid methane: record r2 of P5).
            var vapor = catalog.viscosity(p.id(), p.components().get(i), ViscosityCorrelation.Phase.VAPOR).orElseThrow();
            var liquid = catalog.viscosity(p.id(), p.components().get(i), ViscosityCorrelation.Phase.LIQUID).orElseThrow();
            var domain = catalog.fluidValidity(p.id(), p.components().get(i)).orElseThrow();
            assertEquals(p.components().get(i).equals("CarbonDioxide") ? 90 : domain.minimumTemperature(), vapor.minimumTemperatureKelvin(), 0.0);
            assertEquals(1200, vapor.maximumTemperatureKelvin(), 0.0);
            assertEquals(p.components().get(i).equals("CarbonDioxide") ? 90 : domain.minimumTemperature(), liquid.minimumTemperatureKelvin(), 0.0);
            assertEquals(1e7, domain.maximumPressure(), 0.0);
        }
        var envelope = catalog.fluidValidity(p.id()).orElseThrow();
        assertEquals(63.151, envelope.minimumTemperature(), 0.0);
        assertEquals(1200, envelope.maximumTemperature(), 0.0);
        assertEquals(1e7, envelope.maximumPressure(), 0.0);
        assertEquals(6, p.groupContributions().pairs().size());
        assertEquals(0.0, p.interactions().stream().flatMap(List::stream).mapToDouble(Double::doubleValue).map(Math::abs).sum(),
                "every pair is rule-resolved; the constant entries are 0 and are not the kij");
        assertEquals(MaterialCoverage.Grade.QUALIFIED, catalog.requireSpine(p.id(), "Nitrogen").coverage().grade());
        assertEquals(MaterialCoverage.Grade.QUALIFIED, catalog.requireSpine(p.id(), "Methane").coverage().grade());
        assertEquals(90, catalog.requireSpine(p.id(), "CarbonDioxide").minimumTemperature(), 0.0);
        assertTrue(catalog.crystals().get("icheme:crystal_carbon_dioxide_i").usable(), "the CO2-I crystal is the Jaeger-Span function since P4 stage 1");
        assertEquals("Carbon dioxide", JsonParser.parseString(PilotCryogenicTestCatalog.read("assets/icheme/lang/en_us.json"))
                .getAsJsonObject().get("material.icheme.carbon_dioxide").getAsString());
        assertEquals("material.icheme.carbon_dioxide", catalog.name("CarbonDioxide").translationKey());
    }

    @Test
    void theTestNetworkOverrideSelectsThePilot() {
        var bundled = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var pilot = PilotCryogenicTestCatalog.catalog();
        assertEquals("icheme:refinery", bundled.presets().network().packageId(), "the bundled network is unchanged");
        var network = pilot.presets().network();
        assertEquals(PilotCryogenicTestCatalog.PACKAGE_ID, network.packageId());
        assertEquals(List.of("water", "nitrogen", "methane", "ethane", "carbon_dioxide"), network.fluidPresets());
        assertEquals("CarbonDioxide", pilot.presets().fluid("carbon_dioxide").component());
        String id = PilotCryogenicTestCatalog.PACKAGE_ID;
        var axis = pilot.requirePackage(id).components();
        assertEquals(bundled.requirePackage(id).scientificRevision(), pilot.requirePackage(id).scientificRevision());
        assertEquals(bundled.physicsContent(id, axis), pilot.physicsContent(id, axis), "the override selects, it does not change, the package");
        // One anchor per species per package: the pilot's liquids are anchored by their volume_translation, so the
        // global calibration points do not move its anchors.
        var edited = PilotCryogenicTestCatalog.parseWith(CALIBRATION, o -> o.getAsJsonArray("points").get(0).getAsJsonObject()
                .addProperty("molarVolumeCubicMetres", 4.46e-5));
        assertEquals(com.wormzjl.icheme.science.fluid.thermo.SpinePackageData.of(pilot, id).anchors(),
                com.wormzjl.icheme.science.fluid.thermo.SpinePackageData.of(edited, id).anchors());
    }

    @Test
    void spineIdentityIsTheRecordsDeclaredRevisions() {
        String id = PilotCryogenicTestCatalog.PACKAGE_ID;
        var catalog = MaterialCatalog.parse(PilotCryogenicTestCatalog.resources());
        assertEquals(PILOT_SPINE_REVISION, catalog.spineRevision(id));
        assertEquals(catalog.spineRevision(id), MaterialCatalog.parse(PilotCryogenicTestCatalog.resources()).spineRevision(id));
        // Nothing is hashed (owner decision 2026-10-26): a spine or crystal edit declares itself with its record's
        // revision, which moves the spine identity and leaves the package's.
        var edited = PilotCryogenicTestCatalog.parseWith(NITROGEN, o -> {
            o.getAsJsonObject("standard_entropy").addProperty("value_j_per_mol_kelvin", 191.6); PilotCryogenicTestCatalog.bump(o); });
        assertNotEquals(catalog.spineRevision(id), edited.spineRevision(id));
        assertEquals(catalog.requirePackage(id).scientificRevision(), edited.requirePackage(id).scientificRevision());
        var crystal = PilotCryogenicTestCatalog.parseWith(CRYSTAL, o -> {
            o.getAsJsonObject("anchor").addProperty("fusion_enthalpy_j_per_mol", 9000); PilotCryogenicTestCatalog.bump(o); });
        assertNotEquals(catalog.spineRevision(id), crystal.spineRevision(id));
        // A volume_translation number is package physics, compared with the property record; its texts are not.
        var axis = catalog.requirePackage(id).components();
        var anchor = PilotCryogenicTestCatalog.parseWith(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").addProperty("molar_volume_m3_per_mol", 4.55e-5));
        assertNotEquals(catalog.physicsContent(id, axis), anchor.physicsContent(id, axis));
        var anchorText = PilotCryogenicTestCatalog.parseWith(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").addProperty("source", "edited"));
        assertEquals(catalog.physicsContent(id, axis), anchorText.physicsContent(id, axis));
    }

    @Test
    void packageSpineRulesAreEnforced() {
        // Each compound takes its only reference-spine record; with two, its package entry names one.
        var resources = PilotCryogenicTestCatalog.resources();
        var copy = JsonParser.parseString(resources.get(NITROGEN)).getAsJsonObject();
        copy.addProperty("id", "icheme:spine_nitrogen_copy");
        resources.put("data/icheme/materials/spine/nitrogen_copy.json", copy.toString());
        var two = assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(resources));
        assertTrue(two.getMessage().contains("spine: Nitrogen has 2 reference-spine records; name one"), two.getMessage());
        var named = JsonParser.parseString(resources.get(PACKAGE)).getAsJsonObject();
        spineOf(named, 0, "icheme:spine_nitrogen_copy");
        resources.put(PACKAGE, named.toString());
        assertEquals("icheme:spine_nitrogen_copy", MaterialCatalog.parse(resources).requirePackage(PilotCryogenicTestCatalog.PACKAGE_ID).spine().getFirst());
        refused(PACKAGE, o -> spineOf(o, 1, "icheme:spine_ethane"), "is for Ethane, not component Methane");
        refused(PACKAGE, o -> spineOf(o, 0, "icheme:spine_argon"), "spine: unresolved icheme:spine_argon");
        refused(METHANE, o -> o.addProperty("molar_mass_kg_per_mol", 0.01605), "above 1e-4");
        refused(PACKAGE, o -> o.getAsJsonArray("crystals").add("icheme:crystal_carbon_dioxide_i"), "crystals: duplicate crystal record");
        refused(PACKAGE, o -> o.getAsJsonArray("crystals").set(0, new JsonPrimitive("icheme:crystal_water_ih")), "crystals: unresolved");
        refused(CRYSTAL, o -> o.addProperty("component", "Water"), "is for Water, outside the package components");
    }

    /** D6: one ideal-gas source per species; ideal_gas_cp is optional only under a method that takes the reference spines. */
    @Test
    void idealGasSourceRules() {
        // A property record without a Cp fit under a method that takes the datasets' fits.
        refused(PACKAGE, o -> o.addProperty("method", "icheme:crude_pr78"), "Property icheme:pilot_nitrogen has no ideal_gas_cp: a method without a reference spine needs a shifted_polynomial_5 fit");
        // A Cp fit under a method that takes the spines.
        var fit = JsonParser.parseString(PilotCryogenicTestCatalog.read(TJL20_METHANE)).getAsJsonObject().getAsJsonObject("ideal_gas_cp");
        refused(PILOT_METHANE, o -> o.add("ideal_gas_cp", fit), "Property icheme:pilot_methane carries ideal_gas_cp in a method with a reference spine");
        // The record itself stays loadable without a fit; only its use is refused.
        assertTrue(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().requirePackage(PilotCryogenicTestCatalog.PACKAGE_ID).properties().get(1).cp().isEmpty());
    }

    @Test
    void volumeTranslationRules() {
        refused(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").addProperty("temperature_kelvin", 152.46),
                "volume_translation.temperature_kelvin: 152.46 K is not reduced_temperature x Tc");
        refused(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").addProperty("type", "standard_density"),
                "volume_translation.type: expected saturated_liquid_reduced_temperature");
        refused(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").addProperty("reduced_temperature", 1.0),
                "volume_translation.reduced_temperature: expected a value in (0, 1)");
        refused(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").remove("oracle"), "volume_translation.oracle: required field");
        refused(PILOT_METHANE, o -> o.getAsJsonObject("volume_translation").addProperty("molar_volume_m3_per_mol", -1), "volume_translation.molar_volume_m3_per_mol: must be positive");
        refused(PILOT_METHANE, o -> o.getAsJsonObject("models").remove("pr78"), "volume_translation.models.pr78: required");
    }

    @Test
    void spineAndCrystalRecordsNeedCoverageAndAKnownComponent() {
        refused(NITROGEN, o -> o.remove("coverage"), "coverage: required field");
        refused(NITROGEN, o -> o.getAsJsonObject("coverage").addProperty("grade", "good"), "coverage.grade: expected");
        refused(NITROGEN, o -> o.addProperty("component", "Argon"), "component: unresolved Argon");
        refused(CRYSTAL, o -> o.remove("coverage"), "coverage: required field");
        refused(NITROGEN, o -> o.addProperty("revision", "spine nitrogen r1"), "revision: \"spine nitrogen r1\" must be a token");
    }

    /** Names a reference spine on the package's entry {@code index}, an object that carries the compound's dataset. */
    private static void spineOf(com.google.gson.JsonObject record, int index, String spine) {
        record.getAsJsonArray("components").get(index).getAsJsonObject().addProperty("spine", spine);
    }

    static void refused(String path, Consumer<com.google.gson.JsonObject> edit, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> PilotCryogenicTestCatalog.parseWith(path, edit));
        assertTrue(error.getMessage().contains(message), () -> "expected '" + message + "' in: " + error.getMessage());
    }
}
