package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The identity string moves with every part and with nothing else. */
class ThermoIdentityTest {
    @Test
    void revisionChangesWhenAnyPartChanges() {
        var base = new ThermoIdentity("pkg-a", CubicPhaseEvaluator.FAMILY, "spine-1", "water-1");
        List<ThermoIdentity> variants = List.of(
                new ThermoIdentity("pkg-b", CubicPhaseEvaluator.FAMILY, "spine-1", "water-1"),
                new ThermoIdentity("pkg-a", "pr78_translated_v2", "spine-1", "water-1"),
                new ThermoIdentity("pkg-a", CubicPhaseEvaluator.FAMILY, "spine-2", "water-1"),
                new ThermoIdentity("pkg-a", CubicPhaseEvaluator.FAMILY, "spine-1", "water-2"));
        Set<String> revisions = new HashSet<>();
        revisions.add(base.revision());
        for (ThermoIdentity variant : variants) {
            assertNotEquals(base.revision(), variant.revision());
            assertNotEquals(base, variant);
            revisions.add(variant.revision());
        }
        assertEquals(5, revisions.size());
        // Equal parts, equal revision.
        var again = new ThermoIdentity("pkg-a", CubicPhaseEvaluator.FAMILY, "spine-1", "water-1");
        assertEquals(base.revision(), again.revision());
        assertEquals(base, again);
        assertEquals(base.hashCode(), again.hashCode());
        // Moving characters between parts changes the revision: the parts are length-prefixed.
        var a = new ThermoIdentity("x;1:y", "f", "s", "w");
        var b = new ThermoIdentity("x", "y;1:f", "s", "w");
        assertNotEquals(a.revision(), b.revision());
        assertThrows(IllegalArgumentException.class, () -> new ThermoIdentity(" ", "f", "s", "w"));
        assertThrows(NullPointerException.class, () -> new ThermoIdentity("p", null, "s", "w"));
    }

    @Test
    void catalogIdentityFollowsThePackageAndTheSpine() {
        var catalog = MaterialCatalog.bundled();
        String network = PhaseTestSupport.NETWORK;
        var none = ThermoIdentity.of(catalog, network, CubicPhaseEvaluator.FAMILY, ThermoIdentity.NO_SPINE);
        assertEquals(catalog.fluidRevision(network), none.packageRevision());
        assertEquals(ThermoIdentity.NO_WATER, none.waterModelRevision());
        var spine = ThermoIdentity.of(catalog, network, CubicPhaseEvaluator.FAMILY, "spine:test-1");
        var other = ThermoIdentity.of(catalog, "icheme:tjl20_methane", CubicPhaseEvaluator.FAMILY, ThermoIdentity.NO_SPINE);
        assertNotEquals(none.revision(), spine.revision());
        assertNotEquals(none.revision(), other.revision());
        // Deterministic across catalog loads.
        assertEquals(none.revision(), ThermoIdentity.of(MaterialCatalog.bundled(), network, CubicPhaseEvaluator.FAMILY,
                ThermoIdentity.NO_SPINE).revision());
        // The package's contract carries it with the package's spine.
        String packageSpine = catalog.spineRevision(network);
        var contract = PhaseContract.forPackage(catalog, network);
        assertEquals(ThermoIdentity.of(catalog, network, CubicPhaseEvaluator.FAMILY,
                packageSpine.isEmpty() ? ThermoIdentity.NO_SPINE : packageSpine), contract.identity());
    }
}
