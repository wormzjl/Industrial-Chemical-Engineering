package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.CrystalPhasesUnavailable;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P6 (batch 2026-09-24-coolprop-low-temperature, plan P6 "make columns and other consumers explicit about supported
 * phases"): every consumer of a package's thermodynamics either answers the crystal competition (the equilibrium engine
 * and the fluid network, P4/P5) or refuses a package that admits crystals typed ({@link CrystalPhasesUnavailable}), never
 * running it fluid-only where a crystal could form, and never zero-padding or projecting its species away.
 *
 * <ul>
 * <li>The V3 column (a vapour-liquid algorithm) refuses the pilot {@code icheme:pilot_cryogenic} with the crystal type,
 * on the bundled catalog and on the dev override that selects the pilot as the network package; column packages build.</li>
 * <li>The neural binding is classical for a crystal package (no model is bound), and a projected inference view of it is
 * refused typed rather than dropping its crystals.</li>
 * <li>The loader refuses a package that declares crystals without a reference spine (the crystal's anchor), so the
 * network, whose crystal competition needs the spine, can never run such a package fluid-only.</li>
 * <li>Presets: a network configuration that selects the pilot cannot carry a crude-assay fluid preset (the assay holds compounds the pilot lacks);
 * the pilot has no assay, so no column preset can name it.</li>
 * </ul>
 *
 * The eight bundled packages admit no crystal, so none of these guards changes them (their pins are unmoved:
 * {@code bundledPackageRevisionsAndSharedPhysicsAreUnchanged}, {@code LegacyNetworkPathPinTest}). Ported from the branch's
 * {@code 7bf22842} in milestone M4 of the unified thermo port (main's registry is {@link V3NeuralRegistry}).
 */
class CrystalPackageConsumersTest {
    private static final String PILOT = "icheme:pilot_cryogenic";
    private static final String CO2_I = "icheme:crystal_carbon_dioxide_i";
    private static final String PACKAGE = "data/icheme/materials/packages/pilot_cryogenic.json";

    @AfterEach void reset() { MaterialRuntime.reset(); }

    @Test void theColumnRefusesAPackageWithCrystalsTyped() {
        for (MaterialCatalog catalog : List.of(MaterialCatalog.bundled(), PilotCryogenicTestCatalog.catalog())) {
            var refused = assertThrows(CrystalPhasesUnavailable.class,
                    () -> MaterialRuntime.with(catalog, PILOT, () -> V3PengRobinsonThermo.fromRegisteredPackage(PILOT)));
            assertEquals(PILOT, refused.packageId());
            assertEquals("the V3 column", refused.consumer());
            assertEquals(List.of(CO2_I), refused.crystals());
            assertTrue(refused.getMessage().contains("crystal"), refused.getMessage());
        }
        // A column package builds as before.
        var column = MaterialRuntime.with(MaterialCatalog.bundled(), "icheme:tjl20_methane",
                () -> V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane"));
        assertEquals("icheme:tjl20_methane", column.packageId());
        // No bundled package other than the pilot admits a crystal.
        for (var p : MaterialCatalog.bundled().packages().values()) assertEquals(p.id().equals(PILOT), !p.crystals().isEmpty(), p.id());
    }

    @Test void theNeuralBindingIsClassicalForAPackageWithCrystalsAndItsProjectionIsRefused() {
        var catalog = PilotCryogenicTestCatalog.catalog();
        var pilot = catalog.requirePackage(PILOT);
        double[] feed = {10, 80, 5, 5};
        var input = new V3ColumnInput(1, PILOT, "test:reader", new V3ComponentBasis(pilot.components()), feed, 250, 2, 1, 250000, 750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(200), new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(0)), List.of());
        assertSame(V3NeuralInitializer.UNAVAILABLE, V3NeuralRegistry.bundled().bind(catalog, input), "no model binds a crystal package");
        var projection = assertThrows(CrystalPhasesUnavailable.class,
                () -> catalog.inferenceView(PILOT, List.of("Nitrogen", "Methane"), "icheme:tjl20_methane_nitrogen", true));
        assertEquals("a neural inference projection", projection.consumer());
    }

    @Test void theLoaderRefusesCrystalsWithoutTheirSpine() {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        String path = "data/icheme/materials/packages/tjl20_nitrogen.json";
        var record = JsonParser.parseString(resources.get(path)).getAsJsonObject();
        assertFalse(record.has("spine"));
        var list = new com.google.gson.JsonArray();
        list.add(CO2_I);
        record.add("crystals", list);
        resources.put(path, record.toString());
        var refused = assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(resources));
        assertTrue(refused.getMessage().contains("declares crystals without a reference spine"), refused.getMessage());
    }

    @Test void aPilotNetworkCarriesNoCrudeAssayPreset() {
        var refused = assertThrows(IllegalArgumentException.class, () -> PilotCryogenicTestCatalog.parseWith(PilotCryogenicTestCatalog.NETWORK,
                o -> o.getAsJsonArray("fluid_presets").add("icheme:tia_juana_light_methane")));
        assertTrue(refused.getMessage().contains("holds compounds outside the network package"), refused.getMessage());
        assertTrue(PilotCryogenicTestCatalog.catalog().requirePackage(PILOT).assays().isEmpty(), "no assay: no column preset can name the pilot");
        assertTrue(PilotCryogenicTestCatalog.parseWith(PACKAGE, o -> {}).requirePackage(PILOT).crystals().contains(CO2_I));
    }
}
