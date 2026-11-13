package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.PhaseTestSupport.StubIdealGas;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The solid contract with a test double (the real crystal evaluator is {@link CrystalPhaseEvaluator}, P4): anchoring
 * at the triple point, the state's own consistency, and a solid phase in a result's conservation and aggregates.
 */
class SolidPhaseEvaluatorTest {
    /** A pure crystal with constant heat capacity and volume, anchored to the caller's triple-point chemical potential. */
    static final class ConstantCrystal implements SolidPhaseEvaluator {
        private final String crystal;
        private final String component;
        private final double heatCapacity;
        private final double volume;
        /** The solid's entropy at the anchor, from its own record (the fluid's minus the sublimation entropy). */
        private final double anchorEntropy;
        private final IdealGasFunction idealGas;

        ConstantCrystal(String crystal, String component, double heatCapacity, double volume, double anchorEntropy,
                        IdealGasFunction idealGas) {
            this.crystal = crystal;
            this.component = component;
            this.heatCapacity = heatCapacity;
            this.volume = volume;
            this.anchorEntropy = anchorEntropy;
            this.idealGas = idealGas;
        }

        @Override
        public PhaseState evaluate(double t, double p, TriplePointAnchor anchor, PhaseState out) {
            if (!anchor.component().equals(component)) throw new IllegalArgumentException("Anchor of another species");
            double tt = anchor.temperature();
            double s = anchorEntropy + heatCapacity * Math.log(t / tt);
            double h = anchor.chemicalPotential() + tt * anchorEntropy + heatCapacity * (t - tt) + volume * (p - anchor.pressure());
            double g = h - t * s;
            double hIdeal = idealGas.enthalpy(t);
            double sIdeal = idealGas.entropy(t, p);
            double gIdeal = hIdeal - t * sIdeal;
            out.set(PhaseKind.SOLID, crystal, null, 0, t, p, volume, hIdeal, h - hIdeal, sIdeal, s - sIdeal, g,
                    new double[] {1.0}, new double[] {(g - gIdeal) / (PhaseTestSupport.R * t)}, new double[] {gIdeal});
            return out;
        }
    }

    private static final SolidPhaseEvaluator.TriplePointAnchor ANCHOR =
            new SolidPhaseEvaluator.TriplePointAnchor("CO2", 216.592, 517_950.0, -452_000.0, -423_500.0);

    private static ConstantCrystal crystal() {
        return new ConstantCrystal("co2_solid_i", "CO2", 55.0, 2.85e-5, 90.0,
                StubIdealGas.constant(37.0, 100.0, 1000.0).withReference(-393_510.0, 213.8));
    }

    @Test
    void theAnchorIsReproducedAndTheStateIsConsistent() {
        var solid = crystal();
        PhaseState at = solid.evaluate(ANCHOR.temperature(), ANCHOR.pressure(), ANCHOR, new PhaseState(1));
        assertEquals(ANCHOR.chemicalPotential(), at.molarGibbsEnergy(), 1.0e-9 * Math.abs(ANCHOR.chemicalPotential()));
        assertEquals(ANCHOR.chemicalPotential(), at.chemicalPotential(0), 1.0e-9 * Math.abs(ANCHOR.chemicalPotential()));
        assertEquals(ANCHOR.chemicalPotential(), solid.chemicalPotential(ANCHOR.temperature(), ANCHOR.pressure(), ANCHOR),
                1.0e-9 * Math.abs(ANCHOR.chemicalPotential()));
        assertEquals(PhaseKind.SOLID, at.kind());
        assertEquals("co2_solid_i", at.crystal());
        assertEquals(null, at.root());
        // dg/dT = -s and dg/dP = v away from the anchor.
        double t = 180.0;
        double p = 1.0e5;
        PhaseState s = solid.evaluate(t, p, ANCHOR, new PhaseState(1));
        double dt = 1.0e-3;
        double dp = 10.0;
        double dgdt = (solid.chemicalPotential(t + dt, p, ANCHOR) - solid.chemicalPotential(t - dt, p, ANCHOR)) / (2 * dt);
        double dgdp = (solid.chemicalPotential(t, p + dp, ANCHOR) - solid.chemicalPotential(t, p - dp, ANCHOR)) / (2 * dp);
        assertEquals(-s.molarEntropy(), dgdt, 1.0e-6 * Math.abs(s.molarEntropy()));
        assertEquals(s.molarVolume(), dgdp, 1.0e-4 * s.molarVolume());
        assertThrows(IllegalArgumentException.class, () -> solid.evaluate(t, p,
                new SolidPhaseEvaluator.TriplePointAnchor("Methane", 90.7, 11_700.0, -80_000.0, -85_000.0), new PhaseState(1)));
    }

    /** A vapour and a crystal of the same species conserve through the shared basis; aggregates are phase sums. */
    @Test
    void aSolidPhaseConservesThroughTheSharedBasis() {
        var pkg = PhaseTestSupport.network();
        var solid = crystal();
        double t = 180.0;
        double p = 1.0e5;
        // A shared basis of nitrogen and CO2 (CO2 evaluated as a fluid on a constant-cp stub).
        var co2 = new com.wormzjl.icheme.science.thermo.ThermoComponent("CO2", 304.128, 7.3773e6, 0.2239, 0.04401);
        var nitrogenPr = pkg.properties().get(pkg.components().indexOf("Nitrogen")).pr();
        var nitrogen = new com.wormzjl.icheme.science.thermo.ThermoComponent("Nitrogen", nitrogenPr.criticalTemperature(),
                nitrogenPr.criticalPressure(), nitrogenPr.acentricFactor(), 0.0280134);
        var evaluator = new CubicPhaseEvaluator("test:frost", List.of(nitrogen, co2), new double[2][2], new double[2],
                List.of(StubIdealGas.constant(29.1, 60, 1000), StubIdealGas.constant(37.0, 100, 1000)));
        double[] overall = {0.9, 0.1};
        double[] vapourAmounts = {0.9, 0.02};
        double[] solidAmounts = {0.0, 0.08};
        PhaseState vapourState = evaluator.evaluate(t, p, vapourAmounts, Root.VAPOR, evaluator.newWorkspace(), evaluator.newState()).copy();
        PhaseState solidState = solid.evaluate(t, p, ANCHOR, new PhaseState(1)).copy();
        var competition = PhaseCompetition.withCrystals("co2_solid_i");
        var identity = new ThermoIdentity("test:frost", evaluator.family(), ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
        var vapour = new PhaseAmounts(PhaseKind.VAPOR, null, t, p, vapourAmounts, vapourState, vapourState.traits());
        var frost = new PhaseAmounts(PhaseKind.SOLID, "co2_solid_i", t, p, solidAmounts, solidState, null);
        var result = new EquilibriumResult(EquilibriumRequest.Specification.TP, EquilibriumResult.Status.CONVERGED, null,
                "test", identity, competition, Classification.SOLID_PRESENT, List.of(vapour, frost), List.of(),
                new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "test double"), EquilibriumResult.Diagnostics.NONE);
        assertTrue(result.solidsAssessed());
        assertEquals(0.0, result.conservationDefect(overall), 1.0e-16);
        assertEquals(1.0, result.totalAmount(), 1.0e-15);
        double h = 0.92 * vapourState.molarEnthalpy() + 0.08 * solidState.molarEnthalpy();
        assertEquals(h, result.enthalpy(), 1.0e-12 * Math.abs(h));
        double v = 0.92 * vapourState.molarVolume() + 0.08 * solidState.molarVolume();
        assertEquals(v, result.volume(), 1.0e-12 * v);
        double u = 0.92 * vapourState.molarInternalEnergy() + 0.08 * solidState.molarInternalEnergy();
        assertEquals(u, result.internalEnergy(), 1.0e-12 * Math.abs(u));
        // A conservation defect is measured, not hidden.
        assertEquals(0.5, result.conservationDefect(new double[] {0.9, 0.2}), 1.0e-12);

        // A solid outside the competition, a solid in a fluid-only answer, and a fluid-only SOLID_PRESENT are refused.
        assertThrows(IllegalArgumentException.class, () -> new EquilibriumResult(EquilibriumRequest.Specification.TP,
                EquilibriumResult.Status.CONVERGED, null, "test", identity, PhaseCompetition.FLUID_ONLY,
                Classification.SOLID_PRESENT, List.of(vapour, frost), List.of(),
                new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "t"), EquilibriumResult.Diagnostics.NONE));
        assertThrows(IllegalArgumentException.class, () -> new EquilibriumResult(EquilibriumRequest.Specification.TP,
                EquilibriumResult.Status.CONVERGED, null, "test", identity, competition,
                Classification.SOLID_PRESENT, List.of(vapour), List.of(),
                new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "t"), EquilibriumResult.Diagnostics.NONE));
        // Phase amounts are nonnegative and a phase holds material.
        assertThrows(IllegalArgumentException.class, () -> new PhaseAmounts(PhaseKind.VAPOR, null, t, p, new double[] {0.9, -1e-18}, vapourState, null));
        assertThrows(IllegalArgumentException.class, () -> new PhaseAmounts(PhaseKind.VAPOR, null, t, p, new double[] {0.0, 0.0}, vapourState, null));
        assertThrows(IllegalArgumentException.class, () -> new PhaseAmounts(PhaseKind.SOLID, "co2_solid_i", t, p, solidAmounts, vapourState, null));
        assertThrows(IllegalArgumentException.class, () -> new PhaseAmounts(PhaseKind.VAPOR, null, t + 1.0, p, vapourAmounts, vapourState, null));
        assertFalse(new PhaseState(1).filled());
    }
}
