package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.CrystalReference;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import org.junit.jupiter.api.Test;

/**
 * The crystal evaluator (P4 stage 1, batch 2026-09-24-coolprop-low-temperature): the CO2-I crystal anchored to the pilot
 * family's liquid (the P2 contract: g_s = mu_L and h_s = h_L - dH_fus at the triple point, ln phi against the spine),
 * and the port and the anchoring validated independently of any reference-state convention: anchored to the Span-Wagner
 * oracle's liquid, foreign anchors refused, and the state continued past its data range. The oracle's published figures
 * are reproduced by the material package's JaegerSpanGibbsFunctionTest.
 */
class CrystalPhaseEvaluatorTest {
    static final String PILOT = "icheme:pilot_cryogenic";
    static final String CRYSTAL = "icheme:crystal_carbon_dioxide_i";
    static final String CRYSTAL_PATH = "data/icheme/materials/crystals/carbon_dioxide_i.json";

    /** The CO2-I record with Jaeger and Span's own fusion enthalpy, the value they fitted with the Span-Wagner fluid. */
    static CrystalReference jaegerSpanRecord() {
        return PilotCryogenicTestCatalog.parseWith(CRYSTAL_PATH, o -> o.getAsJsonObject("anchor").addProperty("fusion_enthalpy_j_per_mol", 8875))
                .crystals().get(CRYSTAL);
    }

    @Test
    void anchorsToThePilotLiquidAndFillsAConsistentSolidState() {
        MaterialCatalog catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var fluid = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var solid = CrystalPhaseEvaluator.forPackage(catalog, PILOT, CRYSTAL);
        assertEquals("crystal_jaeger_span_2012_triple_point_anchored_v1", solid.family());
        assertEquals(CRYSTAL, solid.crystal());
        assertEquals("CarbonDioxide", solid.component());
        var anchor = solid.anchor(fluid);
        int co2 = fluid.components().indexOf("CarbonDioxide");
        double[] pure = new double[fluid.componentCount()];
        pure[co2] = 1;
        PhaseState liquid = fluid.evaluate(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, pure, Root.LIQUID, fluid.newWorkspace(), fluid.newState());
        assertEquals(PhaseKind.LIQUID, liquid.kind());
        assertEquals(liquid.chemicalPotential(co2), anchor.chemicalPotential(), 0.0);
        assertEquals(liquid.molarEnthalpy(), anchor.liquidEnthalpy(), 0.0);
        double fusion = solid.record().anchor().fusionEnthalpy();

        // At the anchor: g_s = mu_L, h_s = h_L - dH_fus, and the state's chemical potential is g_s.
        PhaseState at = solid.evaluate(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, anchor, new PhaseState(1));
        assertEquals(PhaseKind.SOLID, at.kind());
        assertEquals(CRYSTAL, at.crystal());
        assertNull(at.root());
        assertEquals(anchor.chemicalPotential(), at.molarGibbsEnergy(), 1e-9);
        assertEquals(anchor.chemicalPotential(), at.chemicalPotential(0), 1e-9);
        assertEquals(anchor.liquidEnthalpy() - fusion, at.molarEnthalpy(), 1e-9);
        assertEquals((anchor.liquidEnthalpy() - anchor.chemicalPotential() - fusion) / TRIPLE_TEMPERATURE, at.molarEntropy(), 1e-12);
        System.out.printf("CO2-I on %s: mu_L %.6f J/mol, h_L %.6f J/mol, dH_fus %.0f J/mol, g0 %.9f, g1 %.9f%n", PILOT, anchor.chemicalPotential(),
                anchor.liquidEnthalpy(), fusion, solid.record().integrationConstants(anchor.chemicalPotential(), anchor.liquidEnthalpy())[0],
                solid.record().integrationConstants(anchor.chemicalPotential(), anchor.liquidEnthalpy())[1]);

        // Away from the anchor: dg/dT = -s, dg/dP = v, g = h - T s, u = h - P v, ln phi against the spine's ideal gas.
        var spine = catalog.requireSpine(PILOT, "CarbonDioxide");
        for (double[] state : new double[][] {{95, 1e3}, {180, 1e5}, {216.592, 5e6}, {250, 1e8}, {299, 4.9e8}}) {
            double t = state[0], p = state[1];
            PhaseState s = solid.evaluate(t, p, anchor, new PhaseState(1)).copy();
            double dt = 1e-3, dp = Math.max(1e-4 * p, 10);
            double dgdt = (solid.chemicalPotential(t + dt, p, anchor) - solid.chemicalPotential(t - dt, p, anchor)) / (2 * dt);
            double dgdp = (solid.chemicalPotential(t, p + dp, anchor) - solid.chemicalPotential(t, p - dp, anchor)) / (2 * dp);
            assertEquals(-s.molarEntropy(), dgdt, 1e-6 * Math.abs(s.molarEntropy()), "dg/dT at " + t + " K");
            assertEquals(s.molarVolume(), dgdp, 1e-6 * s.molarVolume(), "dg/dP at " + t + " K, " + p + " Pa");
            assertEquals(s.molarEnthalpy() - t * s.molarEntropy(), s.molarGibbsEnergy(), 1e-9 * Math.abs(s.molarGibbsEnergy()));
            assertEquals(s.molarEnthalpy() - p * s.molarVolume(), s.molarInternalEnergy(), 1e-12 * Math.abs(s.molarEnthalpy()));
            double gIdeal = spine.enthalpy(t) - t * spine.entropy(t, p);
            assertEquals((s.molarGibbsEnergy() - gIdeal) / (PengRobinsonKernel.GAS_CONSTANT * t), s.logFugacityCoefficient(0), 1e-12);
            assertEquals(spine.enthalpy(t), s.idealGasEnthalpy(), 0.0);
            assertEquals(s.molarGibbsEnergy(), s.chemicalPotential(0), 1e-9 * Math.abs(s.molarGibbsEnergy()));
            var properties = solid.properties(t, p, anchor);
            assertEquals(solid.record().heatCapacity(t, p), properties.heatCapacity(), 1e-12 * properties.heatCapacity());
        }
    }

    @Test
    void refusesForeignAnchorsAndContinuesPastItsRange() {
        MaterialCatalog catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var fluid = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var solid = CrystalPhaseEvaluator.forPackage(catalog, PILOT, CRYSTAL);
        var anchor = solid.anchor(fluid);
        var other = new SolidPhaseEvaluator.TriplePointAnchor("Methane", TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, anchor.chemicalPotential(), anchor.liquidEnthalpy());
        assertThrows(IllegalArgumentException.class, () -> solid.evaluate(180, 1e5, other, new PhaseState(1)));
        var elsewhere = new SolidPhaseEvaluator.TriplePointAnchor("CarbonDioxide", 216.0, TRIPLE_PRESSURE, anchor.chemicalPotential(), anchor.liquidEnthalpy());
        var moved = assertThrows(IllegalArgumentException.class, () -> solid.evaluate(180, 1e5, elsewhere, new PhaseState(1)));
        assertTrue(moved.getMessage().contains("not at the record's triple point"), moved.getMessage());
        // Past the crystal's range (80 to 300 K, to 500 MPa) and the spine's (CO2 from 90 K) both continue (owner rule of
        // 2026-10-16); only a state that is not positive is refused.
        for (double[] state : new double[][] {{79.9, 1e5}, {301, 1e5}, {200, 5.1e8}, {85, 1e-3}}) {
            var continued = solid.evaluate(state[0], state[1], anchor, new PhaseState(1));
            assertTrue(Double.isFinite(continued.chemicalPotential(0)), "continued at " + state[0] + " K, " + state[1] + " Pa");
        }
        assertThrows(IllegalArgumentException.class, () -> solid.evaluate(200, 0, anchor, new PhaseState(1)));
        assertThrows(IllegalArgumentException.class, () -> solid.evaluate(0, 1e5, anchor, new PhaseState(1)));
        assertThrows(IllegalArgumentException.class, () -> solid.evaluate(180, 1e5, anchor, new PhaseState(2)));
        assertThrows(IllegalArgumentException.class, () -> CrystalPhaseEvaluator.forPackage(catalog, "icheme:tjl20_methane_nitrogen", CRYSTAL));
        // An unusable record is refused at construction: its Gibbs function's range ends below the triple-point anchor. The
        // anchor stays where the compound's freezing point is (the catalog's crystal-anchor check, batch 2026-11-04-refinery-compounds W5b).
        var incomplete = PilotCryogenicTestCatalog.parseWith(CRYSTAL_PATH, o -> {
            o.getAsJsonObject("gibbs_function").addProperty("temperature_max_kelvin", 200);
            o.getAsJsonObject("coverage").addProperty("grade", "research_only");
        });
        assertThrows(IllegalStateException.class, () -> CrystalPhaseEvaluator.forPackage(incomplete, PILOT, CRYSTAL));
    }
}
