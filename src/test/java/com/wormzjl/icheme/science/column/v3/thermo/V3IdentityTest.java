package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.material.CrystalPhasesUnavailable;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.IdealGasFitUnavailable;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ModifiedUnifac;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import org.junit.jupiter.api.Test;

/**
 * The column's identity names the pairs it evaluates (F1 of batch 2026-11-13-codebase-review): its scientific revision and,
 * but for a learned model's lens, the derivation's identity, so a derivation edit moves the column's seeds and digests as it
 * moves the network's identity.
 */
class V3IdentityTest {
    private static final String ETHANOL_RECORD = "data/icheme/materials/properties/chemsep_ethanol.json";

    /** Every shipped package the column accepts: one statement of its identity, the derivation named, inside the wire's cap. */
    @Test void everyShippedColumnPackageNamesItsDerivationInsideTheCap() {
        MaterialCatalog bundled = MaterialCatalog.bundled();
        int accepted = 0, longest = 0;
        String longestId = "";
        for (String id : bundled.packages().keySet()) {
            V3CatalogPropertyPackage column;
            try {
                column = new V3CatalogPropertyPackage(bundled, id);
            } catch (CrystalPhasesUnavailable | IdealGasFitUnavailable notAColumnPackage) {
                continue;
            } catch (IllegalArgumentException refused) {
                if (refused.getMessage() != null && refused.getMessage().startsWith("Thermodynamic solver unavailable")) continue;
                throw refused;
            }
            accepted++;
            String identity = column.datasetRevision();
            assertEquals(V3PengRobinsonThermo.columnIdentity(bundled, id), identity, id);
            var data = bundled.requirePackage(id);
            if (bundled.lens(id)) {
                assertEquals(data.scientificRevision(), identity, id + ": a lens keeps the bare revision");
            } else {
                assertTrue(identity.startsWith(data.scientificRevision() + ";derived-pairs=" + DerivedPairs.REVISION + ";"), id + ": " + identity);
                assertEquals(0, DerivedPairs.derive(bundled, id, data.components()).fitsRun(), id + ": the catalog's fits answer a second derivation");
            }
            assertTrue(identity.length() <= MaterialCatalog.MAX_IDENTITY, id + ": " + identity.length());
            if (identity.length() > longest) {
                longest = identity.length();
                longestId = id;
            }
        }
        assertTrue(accepted > 0, "the column accepts a shipped package");
        System.out.println("V3 column identity: " + accepted + " shipped packages, longest " + longest + " characters (" + longestId + ") of "
                + MaterialCatalog.MAX_IDENTITY);
    }

    /** A record the derivation reads, with a bumped revision, gives another column identity on the same scientific revision. */
    @Test void aDerivationRecordsRevisionMovesTheColumnIdentity() {
        String packageId = PilotAqueousTestCatalog.PACKAGE_ID;
        MaterialCatalog base = PilotAqueousTestCatalog.catalog();
        MaterialCatalog bumped = PilotAqueousTestCatalog.parseWith(ETHANOL_RECORD, o -> {
            var counts = o.getAsJsonObject("group_counts").getAsJsonObject(ModifiedUnifac.SCHEME);
            counts.addProperty("revision", counts.get("revision").getAsString() + "-edited");
        });
        assertEquals(base.requirePackage(packageId).scientificRevision(), bumped.requirePackage(packageId).scientificRevision(),
                "the package's own revision does not name the group counts");
        String before = V3PengRobinsonThermo.columnIdentity(base, packageId), after = V3PengRobinsonThermo.columnIdentity(bumped, packageId);
        assertTrue(before.contains(DerivedPairs.REVISION), before);
        assertNotEquals(before, after, "the column identity names the derivation's records");
    }
}
