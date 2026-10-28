package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * P3 work package WP6b: PH and UV over TP equilibria. Round trips TP -> (H, U, V) -> PH and UV -> (T, P); a pure
 * component's coexistence and free water boiling, whose phase fraction TP leaves undetermined, from the balances;
 * continuity of {@code H(T)} across bubble and dew points; conservation; determinism; typed refusals.
 */
class FluidTpEquilibriumPhUvTest {
    private static final List<String> BINARY = List.of("Methane", "Nitrogen");
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** Temperature and pressure recovered by a round trip, relative. */
    private static final double ROUND_TRIP = 1.0e-9;
    private static final double CONSERVATION = 1.0e-13;

    private static FluidTpEquilibrium binary() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        return new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
    }

    private static FluidTpEquilibrium wet() {
        return PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(FluidTestSupport.networkModel()));
    }

    private static double temperature(EquilibriumResult result) { return result.phases().get(0).temperature(); }

    private static double pressure(EquilibriumResult result) { return result.phases().get(0).pressure(); }

    /** Outer iterations (TP equilibria per search) and the worst numbers of a set of round trips. */
    private static final class Tally {
        final String name;
        int count, phSum, phMax, uvSum, uvMax;
        double worstT, worstP, worstDefect;

        Tally(String name) { this.name = name; }

        void add(EquilibriumResult ph, EquilibriumResult uv, double t, double p, double[] overall) {
            count++;
            int a = ph.diagnostics().outerIterations();
            int b = uv.diagnostics().outerIterations();
            phSum += a;
            phMax = Math.max(phMax, a);
            uvSum += b;
            uvMax = Math.max(uvMax, b);
            worstT = Math.max(worstT, Math.max(Math.abs(temperature(ph) / t - 1.0), Math.abs(temperature(uv) / t - 1.0)));
            worstP = Math.max(worstP, Math.abs(pressure(uv) / p - 1.0));
            worstDefect = Math.max(worstDefect, Math.max(ph.conservationDefect(overall), uv.conservationDefect(overall)));
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "WP6b %s: %d round trips; PH TP calls mean %.1f max %d; UV TP calls mean "
                            + "%.1f max %d; worst |dT/T| %.2e, |dP/P| %.2e, conservation %.2e", name, count,
                    (double) phSum / count, phMax, (double) uvSum / count, uvMax, worstT, worstP, worstDefect);
        }
    }

    /**
     * TP at (t, p), then PH at (p, H) and UV at (U, V) of its answer: both converged, the same phases, T and P recovered
     * to {@link #ROUND_TRIP}, each component conserved to {@link #CONSERVATION}, the specification met.
     */
    private static EquilibriumResult[] roundTrip(FluidTpEquilibrium service, FluidTpEquilibrium.Workspace workspace,
                                                 List<String> species, double[] amounts, double t, double p, Tally tally) {
        String at = t + " K, " + p + " Pa";
        var tp = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, tp.status(), at + ": " + tp);
        double h = tp.enthalpy();
        double u = tp.internalEnergy();
        double v = tp.volume();
        double n = tp.totalAmount();
        var ph = service.ph(EquilibriumRequest.ph(p, h, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, ph.status(), at + " PH: " + ph);
        assertEquals(EquilibriumRequest.Specification.PH, ph.specification());
        assertEquals(tp.classification(), ph.classification(), at + " PH");
        assertEquals(tp.phases().size(), ph.phases().size(), at + " PH");
        assertEquals(t, temperature(ph), ROUND_TRIP * t, at + " PH temperature");
        assertEquals(p, pressure(ph));
        assertEquals(h, ph.enthalpy(), 1.0e-6 * n * R * t, at + " PH enthalpy");
        assertTrue(ph.diagnostics().specificationResidual() <= FluidTpEquilibrium.SPECIFICATION_TOLERANCE, at);
        assertTrue(ph.diagnostics().outerIterations() > 0, at);
        assertFalse(ph.diagnostics().coexistenceFromBalance(), at);
        var uv = service.uv(EquilibriumRequest.uv(u, v, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, uv.status(), at + " UV: " + uv);
        assertEquals(EquilibriumRequest.Specification.UV, uv.specification());
        assertEquals(tp.classification(), uv.classification(), at + " UV");
        assertEquals(t, temperature(uv), ROUND_TRIP * t, at + " UV temperature");
        // The pressure follows from the recovered temperature at the specified volume: a stiff answer (a liquid) moves its
        // pressure by T (dP/dT)_V / P times the temperature's relative error (about 230 for liquid water at 320 K, 1 MPa).
        assertEquals(p, pressure(uv), isochoricStiffness(service, workspace, species, amounts, t, p) * ROUND_TRIP * p, at + " UV pressure");
        assertEquals(u, uv.internalEnergy(), 1.0e-6 * n * R * t, at + " UV energy");
        assertEquals(v, uv.volume(), 1.0e-6 * v, at + " UV volume");
        assertTrue(uv.diagnostics().specificationResidual() <= FluidTpEquilibrium.SPECIFICATION_TOLERANCE, at);
        double[] overall = new double[service.contract().components().size()];
        for (int s = 0; s < species.size(); s++) overall[service.contract().index(species.get(s))] = amounts[s];
        assertTrue(ph.conservationDefect(overall) < CONSERVATION, at + " PH defect " + ph.conservationDefect(overall));
        assertTrue(uv.conservationDefect(overall) < CONSERVATION, at + " UV defect " + uv.conservationDefect(overall));
        tally.add(ph, uv, t, p, overall);
        return new EquilibriumResult[] {tp, ph, uv};
    }

    /**
     * {@code max(1, T (dP/dT)_V / P)} of the TP answer at (t, p), from central differences of its volume (0.01 %); 1 where
     * they do not give a positive finite slope (a step of V across a phase boundary).
     */
    private static double isochoricStiffness(FluidTpEquilibrium service, FluidTpEquilibrium.Workspace workspace, List<String> species,
                                             double[] amounts, double t, double p) {
        double dt = 1.0e-4 * t, dp = 1.0e-4 * p;
        double[] v = new double[4];
        double[][] states = {{t + dt, p}, {t - dt, p}, {t, p + dp}, {t, p - dp}};
        for (int k = 0; k < 4; k++) {
            var r = service.tp(EquilibriumRequest.tp(states[k][0], states[k][1], species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
            if (!r.converged()) return 1.0;
            v[k] = r.volume();
        }
        double stiffness = -t / p * ((v[0] - v[1]) / (2.0 * dt)) / ((v[2] - v[3]) / (2.0 * dp));
        return Double.isFinite(stiffness) ? Math.max(1.0, stiffness) : 1.0;
    }

    /** The 60 methane/nitrogen states of the P2 test (110 and 120 K, 0.5 to 3 MPa, x_N2 0.1 to 0.9). */
    @Test
    void methaneNitrogenRoundTripsAtTheSixtyStates() {
        var service = binary();
        var workspace = service.newWorkspace();
        var tally = new Tally("binary (60 P2 states)");
        int split = 0;
        for (double t : new double[] {110.0, 120.0}) {
            for (double p : new double[] {0.5e6, 1.0e6, 1.5e6, 2.0e6, 2.5e6, 3.0e6}) {
                for (double nitrogen : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
                    var results = roundTrip(service, workspace, BINARY, new double[] {1.0 - nitrogen, nitrogen}, t, p, tally);
                    if (results[0].classification() == Classification.VAPOR_LIQUID) split++;
                }
            }
        }
        assertEquals(60, tally.count);
        assertEquals(12, split);
        System.out.println(tally);
    }

    /** The 20-component network basis (Tia Juana light assay plus nitrogen): two phases at 350 K, one at 900 K. */
    @Test
    void networkBasisRoundTrips() {
        var service = PhaseTestSupport.networkService();
        var workspace = service.newWorkspace();
        List<String> species = service.contract().components();
        double[] crude = PhaseTestSupport.crudeWithNitrogen(service.contract());
        var tally = new Tally("20-component basis");
        var cold = roundTrip(service, workspace, species, crude, 350.0, 0.5e6, tally);
        assertEquals(Classification.VAPOR_LIQUID, cold[0].classification());
        var hot = roundTrip(service, workspace, species, crude, 900.0, 0.1e6, tally);
        assertEquals(Classification.SINGLE_FLUID, hot[0].classification());
        System.out.println(tally);
    }

    /** The kernel's own equal-fugacity temperature of pure nitrogen at {@code p}, by bisection. */
    private static double nitrogenSaturationTemperature(FluidTpEquilibrium service, double p) {
        var kernel = service.evaluator().kernel();
        int k = service.contract().index("Nitrogen");
        double[] eos = new double[kernel.componentCount()];
        eos[k] = 1.0;
        var mixture = kernel.newWorkspace();
        var liquid = kernel.newEvaluation();
        var vapour = kernel.newEvaluation();
        DoubleUnaryOperator gap = t -> {
            kernel.evaluate(t, p, eos, Root.LIQUID, mixture, liquid);
            kernel.evaluate(t, p, eos, Root.VAPOR, mixture, vapour);
            return liquid.logFugacityCoefficient(k) - vapour.logFugacityCoefficient(k);
        };
        double below = 85.0;
        double above = 100.0;
        assertTrue(gap.applyAsDouble(below) < 0.0 && gap.applyAsDouble(above) > 0.0);
        for (int iteration = 0; iteration < 200 && Math.nextUp(below) < above; iteration++) {
            double middle = 0.5 * (below + above);
            if (gap.applyAsDouble(middle) < 0.0) below = middle; else above = middle;
        }
        return 0.5 * (below + above);
    }

    /**
     * Pure nitrogen at 0.5 MPa: TP leaves the split at its equal-fugacity temperature undetermined; PH (and UV) with an
     * enthalpy (energy and volume) between the saturated liquid's and vapour's return the equal-fugacity temperature
     * (and pressure) and the vapour fraction from the balance, classified {@code VAPOR_LIQUID}.
     */
    @Test
    void pureNitrogenCoexistenceTakesItsVapourFractionFromTheBalance() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        var workspace = service.newWorkspace();
        double p = 0.5e6;
        double saturation = nitrogenSaturationTemperature(service, p);
        int k = contract.index("Nitrogen");
        double[] eos = new double[service.evaluator().componentCount()];
        eos[k] = 1.0;
        var ws = service.evaluator().newWorkspace();
        PhaseState liquid = service.evaluator().evaluate(saturation, p, eos, Root.LIQUID, ws, service.evaluator().newState()).copy();
        PhaseState vapour = service.evaluator().evaluate(saturation, p, eos, Root.VAPOR, ws, service.evaluator().newState()).copy();
        double n = 2.0;
        double[] pure = new double[contract.components().size()];
        pure[k] = n;
        List<String> species = contract.components();
        for (double beta : new double[] {0.0005, 0.3, 0.7, 0.9995}) {
            String at = "beta " + beta;
            double h = n * ((1.0 - beta) * liquid.molarEnthalpy() + beta * vapour.molarEnthalpy());
            var ph = service.ph(EquilibriumRequest.ph(p, h, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertCoexistence(ph, saturation, 1.0e-12, p, beta, n, k, pure, at + " PH");
            assertEquals(h, ph.enthalpy(), 1.0e-9 * Math.abs(h), at);
            double u = n * ((1.0 - beta) * liquid.molarInternalEnergy() + beta * vapour.molarInternalEnergy());
            double v = n * ((1.0 - beta) * liquid.molarVolume() + beta * vapour.molarVolume());
            var uv = service.uv(EquilibriumRequest.uv(u, v, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            // PH solves the equal-fugacity temperature at its fixed pressure directly (to 1e-14); UV's temperature is the
            // outer energy search's last TP-verified point, which stops once the next secant step is at most
            // outerTolerance T = 1e-11 T without taking it. Measured since WP7's 10 MPa window (the window changes the
            // search's path): beta 5e-4 stops one 7.6e-12 T step short of the root (the 2 MPa path took one more step,
            // 3e-16). The tolerance is 1e-10, ten times the search's own step tolerance.
            assertCoexistence(uv, saturation, 1.0e-10, p, beta, n, k, pure, at + " UV");
            System.out.println("WP6b pure N2 coexistence beta " + beta + ": PH " + ph.diagnostics().outerIterations()
                    + " TP calls, T error " + (temperature(ph) / saturation - 1.0) + "; UV " + uv.diagnostics().outerIterations()
                    + " TP calls, T error " + (temperature(uv) / saturation - 1.0) + ", P error " + (pressure(uv) / p - 1.0));
        }
        // Just outside the saturated enthalpies: one phase on the expected side.
        double above = n * vapour.molarEnthalpy() + n * 5.0;
        var vapourSide = service.ph(EquilibriumRequest.ph(p, above, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, vapourSide.status(), vapourSide.toString());
        assertEquals(1, vapourSide.phases().size());
        assertEquals(PhaseKind.VAPOR, vapourSide.phases().get(0).kind());
        assertTrue(temperature(vapourSide) > saturation);
        assertFalse(vapourSide.diagnostics().coexistenceFromBalance());
        double below = n * liquid.molarEnthalpy() - n * 5.0;
        var liquidSide = service.ph(EquilibriumRequest.ph(p, below, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, liquidSide.status(), liquidSide.toString());
        assertEquals(PhaseKind.LIQUID, liquidSide.phases().get(0).kind());
        assertTrue(temperature(liquidSide) < saturation);
        assertEquals(below, liquidSide.enthalpy(), 1.0e-6 * n * R * saturation);
    }

    private static void assertCoexistence(EquilibriumResult result, double saturation, double temperatureTolerance, double p,
                                          double beta, double n, int k, double[] overall, String at) {
        assertEquals(Status.CONVERGED, result.status(), at + ": " + result);
        assertEquals(Classification.VAPOR_LIQUID, result.classification(), at);
        assertTrue(result.diagnostics().coexistenceFromBalance(), at);
        assertTrue(result.detail().contains("balance"), at + ": " + result.detail());
        assertEquals(2, result.phases().size(), at);
        assertEquals(PhaseKind.VAPOR, result.phases().get(0).kind(), at);
        assertEquals(PhaseKind.LIQUID, result.phases().get(1).kind(), at);
        assertEquals(saturation, temperature(result), temperatureTolerance * saturation, at + " temperature");
        assertEquals(p, pressure(result), 1.0e-9 * p, at + " pressure");
        assertEquals(beta * n, result.phases().get(0).amount(k), 1.0e-8 * n, at + " vapour amount");
        assertTrue(result.conservationDefect(overall) < CONSERVATION, at);
        assertEquals(CoverageGrade.ESTIMATED_DECLARED_ERROR, result.coverage().grade(), at);
    }

    /**
     * Methane/nitrogen 0.5/0.5 at 1 MPa: {@code H(T)} increases along the isobar through the two-phase region; across
     * the bubble and the dew point it is continuous (no step beyond the local slope times the spacing on a 1e-4 K grid)
     * with the slope jumping up at the bubble point and down at the dew point, and PH recovers every scanned
     * temperature.
     */
    @Test
    void enthalpyIsContinuousAcrossBubbleAndDewPoints() {
        var service = binary();
        var workspace = service.newWorkspace();
        double p = 1.0e6;
        double[] z = {0.5, 0.5};
        DoubleUnaryOperator enthalpy = t -> service.tp(EquilibriumRequest.tp(t, p, BINARY, z, PhaseCompetition.FLUID_ONLY),
                workspace).enthalpy();
        java.util.function.DoublePredicate split = t -> service.tp(EquilibriumRequest.tp(t, p, BINARY, z,
                PhaseCompetition.FLUID_ONLY), workspace).phases().size() == 2;
        // The isobar: monotone, and the two boundaries located on a 0.5 K grid.
        double previous = Double.NEGATIVE_INFINITY;
        double bubble = Double.NaN;
        double dew = Double.NaN;
        boolean wasSplit = false;
        for (double t = 90.0; t <= 170.0; t += 0.5) {
            double h = enthalpy.applyAsDouble(t);
            assertTrue(h > previous, "H(T) increases at " + t + " K");
            previous = h;
            boolean isSplit = split.test(t);
            if (isSplit && !wasSplit) bubble = t;
            if (!isSplit && wasSplit) dew = t;
            wasSplit = isSplit;
        }
        assertTrue(bubble > 90.0 && dew > bubble && dew < 170.0, bubble + " " + dew);
        List<String> report = new ArrayList<>();
        int phCalls = 0;
        int phStates = 0;
        int phMax = 0;
        for (double[] boundary : new double[][] {{bubble - 0.5, bubble, 1.0}, {dew - 0.5, dew, -1.0}}) {
            // Refine the boundary: the last one-phase and the first two-phase temperature (or the reverse at the dew point).
            double a = boundary[0];
            double b = boundary[1];
            boolean splitAtB = split.test(b);
            for (int i = 0; i < 60; i++) {
                double m = 0.5 * (a + b);
                if (split.test(m) == splitAtB) b = m; else a = m;
            }
            double edge = 0.5 * (a + b);
            double delta = 1.0e-4;
            double[] temperatures = new double[41];
            double[] enthalpies = new double[41];
            for (int i = 0; i < 41; i++) {
                temperatures[i] = edge + (i - 20) * delta;
                enthalpies[i] = enthalpy.applyAsDouble(temperatures[i]);
                if (i > 0) assertTrue(enthalpies[i] > enthalpies[i - 1], "monotone at " + temperatures[i]);
            }
            double left = (enthalpies[19] - enthalpies[0]) / (19 * delta);
            double right = (enthalpies[40] - enthalpies[21]) / (19 * delta);
            double across = (enthalpies[21] - enthalpies[19]) / (2 * delta);
            double tolerance = 1.0e-6 * R * edge / delta;
            assertTrue(across <= Math.max(left, right) * (1.0 + 1.0e-3) + tolerance
                            && across >= Math.min(left, right) * (1.0 - 1.0e-3) - tolerance,
                    "no step at " + edge + " K: slopes " + left + " / " + across + " / " + right);
            // The expected slope jump: up at the bubble point (liquid -> two phases), down at the dew point.
            if (boundary[2] > 0.0) assertTrue(right > 1.5 * left, "bubble slope jump " + left + " -> " + right);
            else assertTrue(left > 1.5 * right, "dew slope jump " + left + " -> " + right);
            double worst = 0.0;
            double lastT = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 41; i++) {
                var ph = service.ph(EquilibriumRequest.ph(p, enthalpies[i], BINARY, z, PhaseCompetition.FLUID_ONLY), workspace);
                assertEquals(Status.CONVERGED, ph.status(), temperatures[i] + " K: " + ph);
                double t = temperature(ph);
                assertTrue(t > lastT, "T(H) increases at " + temperatures[i]);
                lastT = t;
                worst = Math.max(worst, Math.abs(t / temperatures[i] - 1.0));
                assertTrue(ph.conservationDefect(z) < CONSERVATION);
                phCalls += ph.diagnostics().outerIterations();
                phMax = Math.max(phMax, ph.diagnostics().outerIterations());
                phStates++;
            }
            assertTrue(worst <= ROUND_TRIP, "PH across " + edge + " K: " + worst);
            report.add(String.format(java.util.Locale.ROOT, "%s %.9f K slopes %.2f / %.2f / %.2f J/K, PH worst |dT/T| %.2e",
                    boundary[2] > 0.0 ? "bubble" : "dew", edge, left, across, right, worst));
        }
        System.out.println("WP6b continuity at 1 MPa, x_N2 0.5: " + String.join("; ", report) + "; PH TP calls mean "
                + (double) phCalls / phStates + " max " + phMax);
    }

    /** Free water under the network's rule: wet round trips, and pure water boiling at the state pressure. */
    @Test
    void freeWaterRoundTripsAndBoiling() {
        var service = wet();
        var contract = service.contract();
        var workspace = service.newWorkspace();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        var tally = new Tally("free water");
        int threePhase = 0;
        int allSteam = 0;
        // The WP6a fixture's crude + N2 with water/hydrocarbon 0.01 and 1 on a coarse grid.
        for (double ratio : new double[] {0.01, 1.0}) {
            for (double t : new double[] {340.0, 460.0, 580.0}) {
                for (double p : new double[] {0.5e6, 1.4e6}) {
                    double[] amounts = PhaseTestSupport.crudeWithNitrogen(contract);
                    amounts[water] = ratio * PhaseTestSupport.sum(amounts);
                    var tp = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
                    // Every state is answered: steam past its qualified partial pressure is graded extrapolated (owner rule of
                    // 2026-10-16; out of domain before), and the round trips stay pinned on the qualified states as before.
                    assertTrue(tp.converged(), t + " K " + p + " Pa: " + tp.detail());
                    if (tp.coverage().grade() == CoverageGrade.EXTRAPOLATED) {
                        assertTrue(tp.coverage().evidence().contains("the steam's qualified partial pressure"), tp.coverage().evidence());
                        continue;
                    }
                    var results = roundTrip(service, workspace, species, amounts, t, p, tally);
                    var split = results[0].freeWater();
                    assertNotNull(split);
                    assertEquals(split.liquidWater(), results[1].freeWater().liquidWater(), 1.0e-8 * amounts[water]);
                    assertEquals(split.liquidWater(), results[2].freeWater().liquidWater(), 1.0e-8 * amounts[water]);
                    if (split.liquidWater() > 0.0) threePhase++;
                    else allSteam++;
                }
            }
        }
        // WP6a's two cost states: three phases, and too little water to saturate the gas (all steam).
        double[] wetCrude = PhaseTestSupport.crudeWithNitrogen(contract);
        wetCrude[water] = 1.0;
        roundTrip(service, workspace, species, wetCrude, 350.0, 1.0e6, tally);
        double[] dampCrude = PhaseTestSupport.crudeWithNitrogen(contract);
        dampCrude[water] = 1.0e-3;
        roundTrip(service, workspace, species, dampCrude, 350.0, 1.0e6, tally);
        // Pure water: liquid above its saturation pressure, steam below.
        double[] pure = new double[species.size()];
        pure[water] = 1.5;
        roundTrip(service, workspace, species, pure, 320.0, 1.0e6, tally);
        roundTrip(service, workspace, species, pure, 450.0, 0.1e6, tally);
        assertTrue(threePhase > 0 && allSteam > 0, threePhase + " " + allSteam);
        System.out.println(tally + " (" + threePhase + " with free water, " + allSteam + " all steam, plus 4 fixed states)");

        // Pure water boiling at 0.1 MPa: TP has all liquid at T_sat and all steam above; PH and UV between the two take
        // the steam fraction from the balance at the water model's saturation temperature.
        FreeWaterModel model = service.freeWater();
        double p = 0.1e6;
        double below = 350.0;
        double above = 400.0;
        for (int i = 0; i < 200 && Math.nextUp(below) < above; i++) {
            double middle = 0.5 * (below + above);
            if (model.saturationPressure(middle) < p) below = middle; else above = middle;
        }
        double boiling = 0.5 * (below + above);
        double[] liquid = new double[2];
        model.liquid(boiling, p, liquid);
        double steam = model.vapor().enthalpy(boiling) + SteamVirial.residualEnthalpy(boiling, p);
        for (double beta : new double[] {0.25, 0.8}) {
            String at = "boiling, steam fraction " + beta;
            double h = pure[water] * ((1.0 - beta) * liquid[1] + beta * steam);
            var ph = service.ph(EquilibriumRequest.ph(p, h, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertBoiling(ph, boiling, p, beta, pure, water, at + " PH");
            assertEquals(h, ph.enthalpy(), 1.0e-9 * Math.abs(h), at);
            double v = pure[water] * ((1.0 - beta) * liquid[0] + beta * SteamVirial.molarVolume(boiling, p));
            double u = h - p * v;
            var uv = service.uv(EquilibriumRequest.uv(u, v, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertBoiling(uv, boiling, p, beta, pure, water, at + " UV");
            System.out.println("WP6b water boiling beta " + beta + ": PH " + ph.diagnostics().outerIterations()
                    + " TP calls, T error " + (temperature(ph) / boiling - 1.0) + "; UV " + uv.diagnostics().outerIterations()
                    + " TP calls, T error " + (temperature(uv) / boiling - 1.0) + ", P error " + (pressure(uv) / p - 1.0));
        }
    }

    private static void assertBoiling(EquilibriumResult result, double boiling, double p, double beta, double[] overall,
                                      int water, String at) {
        assertEquals(Status.CONVERGED, result.status(), at + ": " + result);
        assertEquals(Classification.FREE_WATER, result.classification(), at);
        assertTrue(result.diagnostics().coexistenceFromBalance(), at);
        assertEquals(2, result.phases().size(), at);
        assertEquals(PhaseKind.VAPOR, result.phases().get(0).kind(), at);
        assertEquals(PhaseKind.FREE_WATER, result.phases().get(1).kind(), at);
        assertEquals(boiling, temperature(result), 1.0e-9 * boiling, at + " temperature");
        assertEquals(p, pressure(result), 1.0e-9 * p, at + " pressure");
        assertEquals(beta * overall[water], result.freeWater().waterVapor(), 1.0e-7 * overall[water], at + " steam");
        assertEquals(result.freeWater().waterVapor(), result.phases().get(0).amount(water), 0.0, at);
        assertTrue(result.conservationDefect(overall) < CONSERVATION, at);
    }

    /**
     * Refusals: the critical point of nitrogen (a converged answer there is research-only, a failure is typed
     * {@code CRITICAL_BAND}), states beyond the domain (typed out of domain, naming the component), requests outside
     * the contract (typed unsupported), and a search whose budget runs out (typed not converged, the count reported).
     */
    @Test
    void refusalsAreTyped() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        var workspace = service.newWorkspace();
        List<String> species = contract.components();
        int k = contract.index("Nitrogen");
        double[] nitrogen = new double[species.size()];
        nitrogen[k] = 1.0;
        var constants = service.evaluator().component(k);
        double tc = constants.criticalTemperatureKelvin();
        double pc = constants.criticalPressurePascal();
        // The critical point and its neighbourhood: the enthalpy and energy/volume of TP answers around it.
        List<String> critical = new ArrayList<>();
        var binaryService = binary();
        var binaryWorkspace = binaryService.newWorkspace();
        for (double[] reduced : new double[][] {{1.0, 1.0}, {1.001, 1.0}, {0.999, 1.0}, {1.0, 1.001}, {1.01, 1.2}}) {
            double t = reduced[0] * tc;
            double p = reduced[1] * pc;
            var tp = binaryService.tp(EquilibriumRequest.tp(t, p, BINARY, new double[] {0.0, 1.0}, PhaseCompetition.FLUID_ONLY),
                    binaryWorkspace);
            if (!tp.converged() || tp.phases().isEmpty()) {
                assertTrue(tp.detail().startsWith(EquilibriumResult.CRITICAL_BAND) || tp.converged(), tp.toString());
                critical.add(reduced[0] + "/" + reduced[1] + " TP " + tp.status());
                continue;
            }
            assertEquals(CoverageGrade.RESEARCH_ONLY, tp.coverage().grade(), tp.toString());
            var ph = binaryService.ph(EquilibriumRequest.ph(p, tp.enthalpy(), BINARY, new double[] {0.0, 1.0},
                    PhaseCompetition.FLUID_ONLY), binaryWorkspace);
            var uv = binaryService.uv(EquilibriumRequest.uv(tp.internalEnergy(), tp.volume(), BINARY, new double[] {0.0, 1.0},
                    PhaseCompetition.FLUID_ONLY), binaryWorkspace);
            for (var result : List.of(ph, uv)) {
                if (result.converged()) {
                    assertEquals(CoverageGrade.RESEARCH_ONLY, result.coverage().grade(), result.toString());
                } else {
                    assertEquals(Status.NOT_CONVERGED, result.status(), result.toString());
                    assertTrue(result.detail().startsWith(EquilibriumResult.CRITICAL_BAND), result.detail());
                    assertTrue(result.diagnostics().criticalBand());
                }
            }
            critical.add(String.format(java.util.Locale.ROOT, "%s/%s PH %s %.1e (%d) UV %s %.1e/%.1e (%d)", reduced[0], reduced[1],
                    ph.status(), ph.converged() ? temperature(ph) / t - 1.0 : Double.NaN, ph.diagnostics().outerIterations(),
                    uv.status(), uv.converged() ? temperature(uv) / t - 1.0 : Double.NaN,
                    uv.converged() ? pressure(uv) / p - 1.0 : Double.NaN, uv.diagnostics().outerIterations()));
        }
        System.out.println("WP6b nitrogen critical point (T/Tc / P/Pc: status, relative error, TP calls): " + String.join("; ", critical));
        // Searches that run out of budget at the critical point: typed CRITICAL_BAND exactly when their last TP answer
        // lies in the band, and at least one does.
        var atCritical = binaryService.tp(EquilibriumRequest.tp(tc, pc, BINARY, new double[] {0.0, 1.0}, PhaseCompetition.FLUID_ONLY),
                binaryWorkspace);
        int typed = 0;
        for (int budget = 2; budget <= 12; budget++) {
            var limited = new FluidTpEquilibrium(binaryService.contract(), binaryService.evaluator(),
                    PhaseTestSupport.withOuterSearch(FluidTpEquilibrium.Settings.DEFAULT, budget, 1.0e-11), null);
            var ph = limited.ph(EquilibriumRequest.ph(pc, atCritical.enthalpy(), BINARY, new double[] {0.0, 1.0},
                    PhaseCompetition.FLUID_ONLY));
            if (ph.converged()) {
                assertEquals(CoverageGrade.RESEARCH_ONLY, ph.coverage().grade(), ph.toString());
                continue;
            }
            assertEquals(Status.NOT_CONVERGED, ph.status(), ph.toString());
            assertEquals(CoverageGrade.UNAVAILABLE, ph.coverage().grade());
            assertTrue(ph.diagnostics().outerIterations() <= budget, ph.toString());
            assertEquals(ph.diagnostics().criticalBand(), ph.detail().startsWith(EquilibriumResult.CRITICAL_BAND), ph.detail());
            if (ph.diagnostics().criticalBand()) typed++;
        }
        assertTrue(typed > 0, "no budget ended inside the band");

        // Beyond the data (owner rule of 2026-10-16: answered, graded extrapolated): methane's 293.15 K ideal-gas fit (an
        // enthalpy only a colder state has), the 10 MPa envelope (WP7 widened methane, nitrogen and the network envelope
        // from 2 MPa), a volume only a pressure above the envelope gives.
        double[] methane = new double[species.size()];
        methane[contract.index("Methane")] = 1.0;
        var reference = service.tp(EquilibriumRequest.tp(300.0, 1.0e6, species, methane, PhaseCompetition.FLUID_ONLY), workspace);
        var cold = service.ph(EquilibriumRequest.ph(1.0e6, reference.enthalpy() - 5.0e3, species, methane,
                PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, cold.status(), cold.toString());
        assertTrue(temperature(cold) < 293.15, "colder than the fit: " + temperature(cold));
        assertEquals(CoverageGrade.EXTRAPOLATED, cold.coverage().grade());
        assertTrue(cold.coverage().evidence().contains("the ideal gas of Methane"), cold.coverage().evidence());
        assertTrue(cold.diagnostics().outerIterations() > 0);
        // The examples before WP7 (3 MPa, a fifth of the volume) lie inside the widened domain and are answered.
        var inside = service.ph(EquilibriumRequest.ph(3.0e6, reference.enthalpy(), species, methane, PhaseCompetition.FLUID_ONLY),
                workspace);
        assertEquals(Status.CONVERGED, inside.status(), inside.toString());
        var insideUv = service.uv(EquilibriumRequest.uv(reference.internalEnergy(), 0.2 * reference.volume(), species, methane,
                PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, insideUv.status(), insideUv.toString());
        var dense = service.ph(EquilibriumRequest.ph(12.0e6, reference.enthalpy(), species, methane, PhaseCompetition.FLUID_ONLY),
                workspace);
        assertEquals(Status.CONVERGED, dense.status(), dense.toString());
        assertEquals(CoverageGrade.EXTRAPOLATED, dense.coverage().grade(), dense.toString());
        var small = service.uv(EquilibriumRequest.uv(reference.internalEnergy(), 0.05 * reference.volume(), species, methane,
                PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, small.status(), small.toString());
        assertTrue(pressure(small) > 1.0e7, "above the envelope: " + pressure(small));
        assertEquals(CoverageGrade.EXTRAPOLATED, small.coverage().grade());

        // Outside the contract: before any search.
        var crystal = service.ph(EquilibriumRequest.ph(1.0e6, 0.0, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0),
                PhaseCompetition.withCrystals("co2_solid_i")), workspace);
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, crystal.reason());
        var waterWithoutModel = service.uv(EquilibriumRequest.uv(0.0, 1.0e-3, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0, "Water", 1.0), PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(UnsupportedReason.NOT_IMPLEMENTED, waterWithoutModel.reason());
        for (var result : List.of(crystal, waterWithoutModel)) {
            assertEquals(Status.UNSUPPORTED, result.status());
            assertEquals(0, result.diagnostics().outerIterations());
        }

        // A budget of two TP equilibria: typed not converged, the count reported.
        var tight = new FluidTpEquilibrium(binaryService.contract(), binaryService.evaluator(),
                PhaseTestSupport.withOuterSearch(FluidTpEquilibrium.Settings.DEFAULT, 2, 1.0e-11), null);
        var tp = binaryService.tp(EquilibriumRequest.tp(115.0, 1.0e6, BINARY, new double[] {0.5, 0.5}, PhaseCompetition.FLUID_ONLY),
                binaryWorkspace);
        var starved = tight.ph(EquilibriumRequest.ph(1.0e6, tp.enthalpy(), BINARY, new double[] {0.5, 0.5},
                PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.NOT_CONVERGED, starved.status(), starved.toString());
        assertEquals(2, starved.diagnostics().outerIterations());
        assertEquals(CoverageGrade.UNAVAILABLE, starved.coverage().grade());
        var starvedUv = tight.uv(EquilibriumRequest.uv(tp.internalEnergy(), tp.volume(), BINARY, new double[] {0.5, 0.5},
                PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.NOT_CONVERGED, starvedUv.status(), starvedUv.toString());
    }

    /** PH and UV with fresh workspaces against one reused in reverse, then forward order: bitwise equal. */
    @Test
    void searchesAreBitwiseRepeatable() {
        var binaryService = binary();
        var network = PhaseTestSupport.networkService();
        var water = wet();
        record Case(FluidTpEquilibrium service, EquilibriumRequest request) {}
        List<Case> cases = new ArrayList<>();
        for (double[] state : new double[][] {{110.0, 0.5e6, 0.5}, {120.0, 3.0e6, 0.7}, {115.0, 1.0e6, 0.5}}) {
            double[] z = {1.0 - state[2], state[2]};
            var tp = binaryService.tp(EquilibriumRequest.tp(state[0], state[1], BINARY, z, PhaseCompetition.FLUID_ONLY));
            cases.add(new Case(binaryService, EquilibriumRequest.ph(state[1], tp.enthalpy(), BINARY, z, PhaseCompetition.FLUID_ONLY)));
            cases.add(new Case(binaryService, EquilibriumRequest.uv(tp.internalEnergy(), tp.volume(), BINARY, z, PhaseCompetition.FLUID_ONLY)));
        }
        double[] crude = PhaseTestSupport.crudeWithNitrogen(network.contract());
        var hot = network.tp(EquilibriumRequest.tp(350.0, 0.5e6, network.contract().components(), crude, PhaseCompetition.FLUID_ONLY));
        cases.add(new Case(network, EquilibriumRequest.ph(0.5e6, hot.enthalpy(), network.contract().components(), crude,
                PhaseCompetition.FLUID_ONLY)));
        cases.add(new Case(network, EquilibriumRequest.uv(hot.internalEnergy(), hot.volume(), network.contract().components(),
                crude, PhaseCompetition.FLUID_ONLY)));
        // Pure nitrogen at coexistence (a balance) and wet crude (three phases).
        double[] nitrogen = new double[network.contract().components().size()];
        nitrogen[network.contract().index("Nitrogen")] = 1.0;
        var liquid = network.tp(EquilibriumRequest.tp(90.0, 0.5e6, network.contract().components(), nitrogen, PhaseCompetition.FLUID_ONLY));
        var vapour = network.tp(EquilibriumRequest.tp(100.0, 0.5e6, network.contract().components(), nitrogen, PhaseCompetition.FLUID_ONLY));
        cases.add(new Case(network, EquilibriumRequest.ph(0.5e6, 0.5 * (liquid.enthalpy() + vapour.enthalpy()),
                network.contract().components(), nitrogen, PhaseCompetition.FLUID_ONLY)));
        double[] wetCrude = PhaseTestSupport.crudeWithNitrogen(water.contract());
        wetCrude[water.contract().waterIndex()] = 1.0;
        var three = water.tp(EquilibriumRequest.tp(350.0, 1.0e6, water.contract().components(), wetCrude, PhaseCompetition.FLUID_ONLY));
        cases.add(new Case(water, EquilibriumRequest.ph(1.0e6, three.enthalpy(), water.contract().components(), wetCrude,
                PhaseCompetition.FLUID_ONLY)));
        cases.add(new Case(water, EquilibriumRequest.uv(three.internalEnergy(), three.volume(), water.contract().components(),
                wetCrude, PhaseCompetition.FLUID_ONLY)));
        List<EquilibriumResult> fresh = new ArrayList<>();
        for (Case c : cases) fresh.add(solve(c.service(), c.request(), c.service().newWorkspace()));
        for (EquilibriumResult result : fresh) assertEquals(Status.CONVERGED, result.status(), result.toString());
        assertTrue(fresh.get(fresh.size() - 3).diagnostics().coexistenceFromBalance());
        var reused = new java.util.IdentityHashMap<FluidTpEquilibrium, FluidTpEquilibrium.Workspace>();
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < cases.size(); k++) {
                int index = pass == 0 ? cases.size() - 1 - k : k;
                Case c = cases.get(index);
                var workspace = reused.computeIfAbsent(c.service(), FluidTpEquilibrium::newWorkspace);
                assertBitwiseEqual(fresh.get(index), solve(c.service(), c.request(), workspace));
            }
        }
    }

    /**
     * P3 WP7d, since the owner rule of 2026-10-16: a closed vessel whose state lies above the package's 10 MPa envelope is
     * answered and recovered, graded {@code EXTRAPOLATED}, well inside the search budget. It was {@code OUT_OF_DOMAIN} in
     * pressure before (the G3 F8 finding: the liquid-full CH4/C2H6 0.5/0.5 vessel heated at fixed volume passes 10 MPa at
     * 282.2 K, and the next UV request came back {@code NOT_CONVERGED} "a step of U(T)", or {@code OUT_OF_DOMAIN},
     * depending on which branch saw the closed bracket against the window). Just outside and just inside, as the G3 F9
     * family places its boundaries: the {@code (U, V)} of CH4/C2H6 0.5/0.5 at 283 K and 10.05 MPa, at 285 K and 10.3 MPa and
     * at 300 K and 12 MPa (one liquid-like phase each, on an open research contract over the same evaluator) are answered on
     * the pilot's network contract, graded extrapolated; at 283 K and 9.95 MPa the state is answered and recovered as before.
     */
    @Test
    void aClosedVesselBeyondThePressureEnvelopeIsAnsweredExtrapolated() {
        var catalog = MaterialCatalog.bundled();
        String pilot = "icheme:pilot_cryogenic";
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, pilot);
        var network = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, pilot), evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
        var open = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
        double[] z = {0.0, 0.5, 0.5, 0.0};
        double[] zNetwork = {0.0, 0.5, 0.5, 0.0, 0.0};
        var ws = network.newWorkspace();
        for (double[] s : new double[][] {{283.0, 10.05e6}, {285.0, 10.3e6}, {300.0, 12.0e6}}) {
            var state = open.tp(EquilibriumRequest.tp(s[0], s[1], open.contract().components(), z, PhaseCompetition.FLUID_ONLY));
            assertEquals(Status.CONVERGED, state.status(), state.detail());
            assertEquals(1, state.phases().size());
            var uv = network.uv(EquilibriumRequest.uv(state.internalEnergy(), state.volume(), network.contract().components(), zNetwork,
                    PhaseCompetition.FLUID_ONLY), ws);
            assertEquals(Status.CONVERGED, uv.status(), uv.detail());
            assertEquals(s[0], temperature(uv), s[0] * ROUND_TRIP);
            assertEquals(s[1], pressure(uv), s[1] * ROUND_TRIP);
            assertEquals(CoverageGrade.EXTRAPOLATED, uv.coverage().grade());
            assertTrue(uv.coverage().evidence().contains("extrapolated past the package envelope"), uv.coverage().evidence());
            assertTrue(uv.diagnostics().outerIterations() < network.settings().maximumOuterIterations(),
                    "inside the budget: " + uv.diagnostics().outerIterations());
            System.out.println("WP7d UV beyond the envelope, " + s[0] + " K " + s[1] + " Pa: " + uv.status() + " " + uv.coverage().grade()
                    + " after " + uv.diagnostics().outerIterations() + " TP equilibria");
        }
        var inside = open.tp(EquilibriumRequest.tp(283.0, 9.95e6, open.contract().components(), z, PhaseCompetition.FLUID_ONLY));
        var uv = network.uv(EquilibriumRequest.uv(inside.internalEnergy(), inside.volume(), network.contract().components(), zNetwork,
                PhaseCompetition.FLUID_ONLY), ws);
        assertEquals(Status.CONVERGED, uv.status(), uv.detail());
        assertEquals(283.0, temperature(uv), 283.0 * ROUND_TRIP);
        assertEquals(9.95e6, pressure(uv), 9.95e6 * ROUND_TRIP);
    }

    private static EquilibriumResult solve(FluidTpEquilibrium service, EquilibriumRequest request, FluidTpEquilibrium.Workspace workspace) {
        return request.specification() == EquilibriumRequest.Specification.PH ? service.ph(request, workspace)
                : service.uv(request, workspace);
    }

    private static void assertBitwiseEqual(EquilibriumResult expected, EquilibriumResult actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.classification(), actual.classification());
        assertEquals(expected.detail(), actual.detail());
        assertEquals(expected.phases().size(), actual.phases().size());
        for (int p = 0; p < expected.phases().size(); p++) {
            PhaseAmounts a = expected.phases().get(p);
            PhaseAmounts b = actual.phases().get(p);
            assertEquals(a.kind(), b.kind());
            assertEquals(Double.doubleToRawLongBits(a.temperature()), Double.doubleToRawLongBits(b.temperature()));
            assertEquals(Double.doubleToRawLongBits(a.pressure()), Double.doubleToRawLongBits(b.pressure()));
            assertArrayEquals(bits(a.amountsView()), bits(b.amountsView()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarVolume()), Double.doubleToRawLongBits(b.state().molarVolume()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarEnthalpy()), Double.doubleToRawLongBits(b.state().molarEnthalpy()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarEntropy()), Double.doubleToRawLongBits(b.state().molarEntropy()));
            assertArrayEquals(bits(a.state().logFugacityCoefficientsView()), bits(b.state().logFugacityCoefficientsView()));
        }
        var d = expected.diagnostics();
        var e = actual.diagnostics();
        assertEquals(d.outerIterations(), e.outerIterations());
        assertEquals(d.kernelEvaluations(), e.kernelEvaluations());
        assertEquals(d.derivativeEvaluations(), e.derivativeEvaluations());
        assertEquals(d.flashIterations(), e.flashIterations());
        assertEquals(d.newtonIterations(), e.newtonIterations());
        assertEquals(d.coexistenceFromBalance(), e.coexistenceFromBalance());
        assertEquals(Double.doubleToRawLongBits(d.specificationResidual()), Double.doubleToRawLongBits(e.specificationResidual()));
        assertEquals(Double.doubleToRawLongBits(d.fugacityResidual()), Double.doubleToRawLongBits(e.fugacityResidual()));
        assertEquals(expected.freeWater(), actual.freeWater());
    }

    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Double.doubleToRawLongBits(values[i]);
        return bits;
    }
}
