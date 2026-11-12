package com.wormzjl.icheme.science.material;

/**
 * A network package with a water-miscible solvent (batch 2026-11-11-aqueous-solvents, W5 test only): {@code icheme:pilot_solvent},
 * the bundled network package {@code icheme:refinery} plus the pilot ethanol ({@link PilotAqueousTestCatalog}, its homomorph
 * propane), named the network's package. It does not name Water: the network gives it water as a compound of the cubic
 * ({@link MaterialCatalog#withWater}), as a network preset requires (W6 brings ethanol to the main data). A sheet or an island
 * holding a wash runs on it.
 */
public final class PilotSolventTestCatalog {
    public static final String PACKAGE_ID = "icheme:pilot_solvent";
    private static final MaterialCatalog CATALOG = build();

    private PilotSolventTestCatalog() {}

    public static MaterialCatalog catalog() { return CATALOG; }

    private static MaterialCatalog build() {
        var resources = PilotAqueousTestCatalog.resources();
        resources.put("data/icheme/materials/packages/pilot_solvent.json", """
                {"schema_version": 1, "id": "icheme:pilot_solvent", "revision": "pilot-solvent-r1", "method": "icheme:crude_pr78",
                 "extends": "icheme:refinery", "components": ["Ethanol"], "crystals": ["icheme:crystal_carbon_dioxide_i"],
                 "advisory_evidence": ["PILOT_SOLVENT: the refinery package with the pilot ethanol, a test reference only (W5)"]}""");
        String network = "data/icheme/materials/networks/default.json";
        var record = com.google.gson.JsonParser.parseString(resources.get(network)).getAsJsonObject();
        record.addProperty("package", PACKAGE_ID);
        resources.put(network, record.toString());
        return MaterialCatalog.parse(resources);
    }
}
