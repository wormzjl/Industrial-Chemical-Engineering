package com.wormzjl.icheme.science.pollution;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The hazard standard (batch 2026-11-15-pollution-system, plan sections 2 and 3): indices linear in the moles, the volatile share. */
class HazardStandardTest {
    private static final double NONE = Double.NaN;

    /** One compound per row: toxic, corrosive and LFL references, then Tc, Pc, omega. */
    private static HazardStandard standard(double[]... rows) {
        int n = rows.length;
        double[][] columns = new double[6][n];
        for (int i = 0; i < n; i++) for (int c = 0; c < 6; c++) columns[c][i] = rows[i][c];
        return new HazardStandard(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5]);
    }

    @Test void aHundredPpmReferenceGivesMolesOverOneInTenThousand() {
        var s = standard(new double[]{100e-6, 0, .04, 373.53, 8.96291e6, .0941677});
        assertEquals(3 / 1e-4, s.toxic(new double[]{3}), 1e-9);
        assertEquals(0, s.corrosive(new double[]{3}));
        // At 100 ppm in air the index is 1: 1e-4 mol of H2S per mol of air.
        double air = 50;
        assertEquals(1, s.toxic(new double[]{air * 1e-4}) / air, 1e-12);
    }

    @Test void combustiblesAddByLeChatelier() {
        var s = standard(new double[]{0, 0, .05, 190.56, 4.599e6, .011}, new double[]{2100e-6, 0, .021, 369.83, 4.248e6, .152});
        double[] moles = {.5, .3};
        assertEquals(.5 / .05 + .3 / .021, s.combustible(moles), 1e-12);
        // A mixture at the Le Chatelier limit is at index 1.
        double share = .4, limit = 1 / (share / .05 + (1 - share) / .021), air = 10;
        assertEquals(1, s.combustible(new double[]{air * limit * share, air * limit * (1 - share)}) / air, 1e-12);
    }

    @Test void theParticulateReferenceIsCarbonBlack() {
        assertEquals(HazardStandard.AIR_MOLAR_DENSITY, HazardStandard.particulate(1.75e-3), 1e-12 * HazardStandard.AIR_MOLAR_DENSITY);
        assertEquals(40.874, HazardStandard.AIR_MOLAR_DENSITY, 1e-3, "mol/m3 at 298.15 K and 1 atm");
        assertEquals(HazardStandard.particulate(2e-3), HazardStandard.particulate(-2e-3));
    }

    @Test void wilsonGivesTheVolatileShare() {
        var s = standard(new double[]{2100e-6, 0, .021, 369.83, 4.248e6, .152},
                new double[]{500e-6, 0, .012, 562.05, 4.895e6, .210},
                new double[]{1100e-6, 0, .006, 800, 1.5e6, .9},
                new double[]{0, 0, 0, NONE, NONE, NONE});
        assertEquals(1, s.volatileShare(0), "propane evaporates whole");
        assertTrue(s.volatileShare(1) > .08 && s.volatileShare(1) < .2, "benzene: " + s.volatileShare(1));
        assertTrue(s.volatileShare(2) < .01, "a heavy cut: " + s.volatileShare(2));
        assertEquals(1, s.volatileShare(3), "a record without critical constants counts whole");
        var air = s.volatileMoles(new double[]{2, 2, 2, 2});
        assertEquals(2, air[0]);assertEquals(2 * s.volatileShare(1), air[1]);assertEquals(2 * s.volatileShare(2), air[2]);
    }

    @Test void aZeroReferenceContributesNothingAndNegativeMolesCountByMagnitude() {
        var s = standard(new double[]{0, 300e-6, 0, 405.4, 1.1333e7, .257}, new double[]{100e-6, 0, .04, 373.53, 8.96291e6, .0941677});
        assertEquals(0, s.toxic(new double[]{1e6, 0}));
        assertEquals(s.toxic(new double[]{7, 2}), s.toxic(new double[]{-7, -2}));
        assertEquals(2 / 1e-4, s.toxic(new double[]{-7, -2}), 1e-9);
        assertEquals(1 / 300e-6, s.corrosive(new double[]{-1, 5}), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> s.toxic(new double[]{1}));
    }

    @Test void invalidArraysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HazardStandard(new double[1], new double[1], new double[2], new double[1], new double[1], new double[1]));
        assertThrows(IllegalArgumentException.class, () -> standard(new double[]{-1e-4, 0, 0, 300, 1e6, .1}));
        assertThrows(IllegalArgumentException.class, () -> standard(new double[]{0, 0, 1.5, 300, 1e6, .1}));
        assertThrows(IllegalArgumentException.class, () -> standard(new double[]{0, 0, 0, 300, NONE, .1}));
    }

    /** Built from the bundled catalog's network package: H2S toxic at 100 ppm, SO2 corrosive, water inert and last. */
    @Test void theCatalogArraysBuildTheStandard() {
        var catalog = MaterialCatalog.bundled();String world = "icheme:refinery";
        var names = catalog.conservedComponents(world);
        var s = new HazardStandard(catalog.toxicReferences(world), catalog.corrosiveReferences(world), catalog.lowerFlammabilityLimits(world),
                catalog.criticalTemperatures(world), catalog.criticalPressures(world), catalog.acentricFactors(world));
        assertEquals(names.size(), s.size());
        double[] moles = new double[names.size()];
        moles[names.indexOf("HydrogenSulfide")] = 1e-4;
        assertEquals(1, s.toxic(moles), 1e-12);
        moles = new double[names.size()];moles[names.indexOf("SulfurDioxide")] = 1e-4;
        assertEquals(1, s.corrosive(moles), 1e-12);assertEquals(0, s.toxic(moles));
        int water = names.size() - 1;assertEquals("Water", names.get(water));
        assertEquals(0, catalog.toxicReferences(world)[water]);assertEquals(0, catalog.lowerFlammabilityLimits(world)[water]);
        assertEquals(.344, catalog.acentricFactors(world)[water], 5e-3, "the water's acentric factor on its own saturation line");
        assertEquals(1, s.volatileShare(names.indexOf("Propane")));
        assertTrue(s.volatileShare(names.indexOf("crude_pc12")) < .01);
    }
}
