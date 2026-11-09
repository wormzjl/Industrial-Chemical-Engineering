package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * D21 (batch 2026-09-24-coolprop-low-temperature, {@code DECISION_LOG.md}; {@code P6B_RUNTIME_FAILURE_DIAGNOSIS.md}
 * section 1): a CO2 feed with traces of at most 1e-5 (1e-6 until the owner's follow-up of 2026-10-15) takes the pure
 * species' crystal steps, its traces in the fluid, before the pseudo-binary rule; anything that rule does not answer is
 * the typed {@link EquilibriumResult#CRYSTAL_STEP} hold, at once. Before D21 the row A vessel failed for every trace from
 * 1e-12 to 1e-7 after 7 k to 94 k kernel calls.
 */
class NearPureCrystalStepTest {
    static final FluidTpEquilibrium SERVICE = FluidTpEquilibrium.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), "icheme:pilot_cryogenic");
    static final List<String> BASIS = SERVICE.contract().components();
    static final int N2 = BASIS.indexOf("Nitrogen");
    static final int CO2 = BASIS.indexOf("CarbonDioxide");
    static final PhaseCompetition CRYSTALS = PhaseCompetition.FLUID_AND_CRYSTALS;
    /** The rule's bound on the traces. */
    static final double THETA = 1e-5;
    /** Row A's traces, to the bound. */
    static final double[] ROW_A_TRACES = {0.0, 1e-12, 1e-9, 1e-7, 1e-6, THETA};
    /**
     * The PH traces. 2e-6 is the regression row: with the bound at 1e-6 its target 1000 J below the vapour closed on a
     * 0.57 J step of H(T) over 6e-10 K and failed. The bound itself is no PH row: there the 95 % crystal's vapour share
     * (0.054) lies below the rule's smallest fluid, which keeps the traces within 1e-4 of it (0.1 of the feed), and the
     * general solve answers it.
     */
    static final double[] PH_TRACES = {0.0, 1e-12, 1e-9, 1e-7, 1e-6, 2e-6};

    /** {@code co2} mol of CO2 with nitrogen at fraction {@code f} of the feed. */
    static double[] feed(double co2, double f) {
        double[] z = new double[BASIS.size()];
        z[CO2] = co2;
        z[N2] = f / (1.0 - f) * co2;
        return z;
    }

    static double crystal(EquilibriumResult r) {
        double sum = 0.0;
        for (PhaseAmounts phase : r.phases()) if (phase.kind() == PhaseKind.SOLID) sum += phase.amount(CO2);
        return sum;
    }

    /** The nitrogen of a converged answer in its crystal-free phases: every trace stays in the fluid. */
    static double fluidNitrogen(EquilibriumResult r) {
        double sum = 0.0;
        for (PhaseAmounts phase : r.phases()) if (phase.kind() != PhaseKind.SOLID) sum += phase.amount(N2);
        return sum;
    }

    static boolean rule(EquilibriumResult r) { return r.detail().contains("near-pure (D21)"); }

    /** Row A's vessel (1 m3, 249.1435 mol of CO2 at its sublimation line, 212.22 K): continuous in the trace. */
    @Test
    void rowAVesselIsContinuousInTheTraceFraction() {
        var ws = SERVICE.newWorkspace();
        double u = -9.972099514183599e7, v = 1.0;
        EquilibriumResult pure = null;
        for (double f : ROW_A_TRACES) {
            double[] z = feed(249.1435, f);
            var r = SERVICE.uv(EquilibriumRequest.uv(u, v, BASIS, z, CRYSTALS), ws);
            double t = r.converged() ? r.phases().get(0).temperature() : Double.NaN, p = r.converged() ? r.phases().get(0).pressure() : Double.NaN;
            System.out.printf(Locale.ROOT, "row A f=%.0e: %s %s T %.9f K P %.4f Pa crystal %.9f mol, %d kernel%n", f, r.status(),
                    r.classification(), t, p, r.converged() ? crystal(r) : Double.NaN, r.diagnostics().kernelEvaluations());
            assertEquals(Status.CONVERGED, r.status(), r.toString());
            assertEquals(Classification.VAPOR_SOLID, r.classification(), r.toString());
            assertEquals(0.0, r.conservationDefect(z), 1e-12);
            assertEquals(u, r.internalEnergy(), 1e-9 * Math.abs(u));
            assertEquals(v, r.volume(), 1e-9 * v);
            if (f == 0.0) {
                pure = r;
                continue;
            }
            assertTrue(rule(r), r.detail());
            assertEquals(z[N2], fluidNitrogen(r), 1e-12 * z[N2]);
            // D21: the pressure about 1.3 f off the pure answer's (1.37 f at 1e-6 and 1e-5), the crystal about 2 f, down to
            // the search's own resolution: each answer meets U and V to 1e-9 (above), which places a point of the
            // sublimation line to about 1e-10 in P and, through the step's lever, the crystal to about 1e-9 (since the
            // numerical box, the pure feed's search restarts from the crystals' ceiling and lands 9e-11 and 1e-9 off).
            assertEquals(pure.phases().get(0).pressure(), p, Math.max(1.5 * f, 1e-9) * pure.phases().get(0).pressure());
            assertEquals(crystal(pure), crystal(r), Math.max(3.0 * f, 1e-8) * crystal(pure));
        }
    }

    /**
     * PH at 101.325 kPa across the sublimation line, from the vapour above it to a 95 % crystal: on the line every trace
     * takes the pure step's temperature, its crystal within the trace of the pure answer's (2e-6 included, see
     * {@link #PH_TRACES}).
     */
    @Test
    void ambientPressureCrossesTheSublimationLineAtThePureTemperature() {
        var ws = SERVICE.newWorkspace();
        double p = 101325.0;
        double[] steps = {-150, -1000, -12000, -24000};
        EquilibriumResult[] pure = new EquilibriumResult[steps.length];
        for (double f : PH_TRACES) {
            double[] z = feed(1.0, f);
            double h0 = SERVICE.tp(EquilibriumRequest.tp(200.0, p, BASIS, z, CRYSTALS), ws).enthalpy();
            for (int k = 0; k < steps.length; k++) {
                var r = SERVICE.ph(EquilibriumRequest.ph(p, h0 + steps[k], BASIS, z, CRYSTALS), ws);
                System.out.printf(Locale.ROOT, "PH f=%.0e dh=%.0f: %s %s T %.9f K crystal %.9f mol, %d kernel%n", f, steps[k], r.status(),
                        r.classification(), r.converged() ? r.phases().get(0).temperature() : Double.NaN,
                        r.converged() ? crystal(r) : Double.NaN, r.diagnostics().kernelEvaluations());
                assertEquals(Status.CONVERGED, r.status(), r.toString());
                assertEquals(0.0, r.conservationDefect(z), 1e-12);
                if (f == 0.0) {
                    pure[k] = r;
                    continue;
                }
                assertEquals(pure[k].classification(), r.classification(), r.toString());
                if (k == 0) {
                    assertFalse(rule(r), r.detail());
                    assertEquals(pure[k].phases().get(0).temperature(), r.phases().get(0).temperature(), 1e-4);
                    continue;
                }
                assertTrue(rule(r), r.detail());
                assertEquals(z[N2], fluidNitrogen(r), 1e-12 * z[N2]);
                assertEquals(pure[k].phases().get(0).temperature(), r.phases().get(0).temperature(), 1e-9);
                assertEquals(crystal(pure[k]), crystal(r), 3.0 * f);
            }
        }
    }

    /** Just above the threshold (2e-5) the general solve answers, without the rule and next to its answer at 1e-5. */
    @Test
    void justAboveTheThresholdTheGeneralSolveAnswers() {
        var ws = SERVICE.newWorkspace();
        double u = -9.972099514183599e7, v = 1.0;
        double f = 2.0 * THETA;
        var atTheta = SERVICE.uv(EquilibriumRequest.uv(u, v, BASIS, feed(249.1435, THETA), CRYSTALS), ws);
        var above = SERVICE.uv(EquilibriumRequest.uv(u, v, BASIS, feed(249.1435, f), CRYSTALS), ws);
        System.out.println("row A at 1e-5: " + atTheta.phases().get(0).pressure() + " Pa; at 2e-5: " + above);
        assertTrue(rule(atTheta), atTheta.detail());
        assertEquals(Status.CONVERGED, above.status(), above.toString());
        assertFalse(rule(above), above.detail());
        assertEquals(Classification.VAPOR_SOLID, above.classification());
        assertEquals(atTheta.phases().get(0).pressure(), above.phases().get(0).pressure(), f * atTheta.phases().get(0).pressure());
        double p = 101325.0;
        double[] z = feed(1.0, f);
        double h0 = SERVICE.tp(EquilibriumRequest.tp(200.0, p, BASIS, z, CRYSTALS), ws).enthalpy();
        var ph = SERVICE.ph(EquilibriumRequest.ph(p, h0 - 5000.0, BASIS, z, CRYSTALS), ws);
        System.out.println("PH at 2e-5: " + ph);
        assertEquals(Status.CONVERGED, ph.status(), ph.toString());
        assertFalse(rule(ph), ph.detail());
        assertEquals(Classification.VAPOR_SOLID, ph.classification());
    }

    /**
     * Inside the triple point's triangle (vapour 2e-3, liquid 3e-3 and crystal 0.995 mol at the pure point) the answer
     * is the triple point before any search: the traces in the larger fluid while they are at most 1e-4 of it (1e-7);
     * beyond that (1e-6) the typed hold, at once.
     */
    @Test
    void nearPureTriplePointAndItsTypedHold() {
        var ws = SERVICE.newWorkspace();
        double[] pure = feed(1.0, 0.0);
        // The triple point's phases, from a pure vessel inside its triangle (G5F1's).
        var corners = SERVICE.uv(EquilibriumRequest.uv(-4.1337e5, 3.0e-4, BASIS, pure, CRYSTALS), ws);
        assertEquals(Classification.VAPOR_LIQUID_SOLID, corners.classification(), corners.toString());
        double[] u = new double[3], v = new double[3];
        for (PhaseAmounts phase : corners.phases()) {
            int k = phase.kind() == PhaseKind.VAPOR ? 0 : phase.kind() == PhaseKind.LIQUID ? 1 : 2;
            u[k] = phase.state().molarInternalEnergy();
            v[k] = phase.state().molarVolume();
        }
        double[] n = {2e-3, 3e-3, 0.995};
        double uTarget = n[0] * u[0] + n[1] * u[1] + n[2] * u[2];
        double vTarget = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
        for (double f : new double[] {0.0, 1e-7, 1e-6}) {
            double[] z = feed(1.0, f);
            var r = SERVICE.uv(EquilibriumRequest.uv(uTarget, vTarget, BASIS, z, CRYSTALS), ws);
            System.out.printf(Locale.ROOT, "triple f=%.0e: %s, %d kernel, %d TP equilibria%n", f, r, r.diagnostics().kernelEvaluations(),
                    r.diagnostics().outerIterations());
            assertTrue(r.diagnostics().kernelEvaluations() <= 20, r.toString());
            assertEquals(0, r.diagnostics().outerIterations());
            if (f > 1e-7) {
                assertEquals(Status.NOT_CONVERGED, r.status(), r.toString());
                assertEquals(EquilibriumRequest.Specification.UV, r.specification());
                assertTrue(r.detail().startsWith(EquilibriumResult.CRYSTAL_STEP + ": "), r.detail());
                continue;
            }
            assertEquals(Classification.VAPOR_LIQUID_SOLID, r.classification(), r.toString());
            assertEquals(0.0, r.conservationDefect(z), 1e-12);
            assertEquals(uTarget, r.internalEnergy(), 1e-9 * Math.abs(uTarget));
            assertEquals(vTarget, r.volume(), 1e-9 * vTarget);
            assertEquals(n[2], crystal(r), 1e-9 + 3.0 * f);
            if (f > 0.0) {
                assertTrue(rule(r), r.detail());
                for (PhaseAmounts phase : r.phases()) {
                    if (phase.kind() == PhaseKind.LIQUID) assertEquals(z[N2], phase.amount(N2), 1e-12 * z[N2]);
                }
            }
        }
    }
}
