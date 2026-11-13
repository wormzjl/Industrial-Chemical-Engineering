package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.PhaseTestSupport.R;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.thermo.TranslatedPengRobinson;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.PhaseTestSupport.StubIdealGas;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The cubic adapter against a directly evaluated {@link TranslatedPengRobinson} with the network package's methane
 * and nitrogen constants at the state pressure, against its own ideal-gas functions, and against central differences.
 */
class CubicPhaseEvaluatorTest {
    private static final double[][] COMPOSITIONS = {{1.0, 0.0}, {0.0, 1.0}, {0.3, 0.7}, {0.7, 0.3}};

    /** The network's constructor path: the package's cp fits (each the polynomial at every temperature) and the same translations. */
    private static TranslatedPengRobinson reference() {
        var pkg = PhaseTestSupport.network();
        List<ThermoComponent> components = new ArrayList<>();
        int n = 2;
        double[][] cp = new double[n][6];
        String[] names = {"Methane", "Nitrogen"};
        for (int a = 0; a < n; a++) {
            var property = pkg.properties().get(pkg.components().indexOf(names[a]));
            components.add(new ThermoComponent(names[a], property.pr().criticalTemperature(), property.pr().criticalPressure(),
                    property.pr().acentricFactor(), property.molecularWeight()));
            for (int j = 0; j < 6; j++) cp[a][j] = property.cp().get(j);
        }
        return new TranslatedPengRobinson(components, new double[n][n], cp,
                new double[] {PhaseTestSupport.METHANE_TRANSLATION, PhaseTestSupport.NITROGEN_TRANSLATION});
    }

    @Test
    void reproducesTheTranslatedModelAtTheStatePressure() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var reference = reference();
        var workspace = evaluator.newWorkspace();
        var state = evaluator.newState();
        var derivatives = evaluator.newDerivatives();
        var pkg = PhaseTestSupport.network();
        int states = 0;
        double worstEnthalpy = 0.0;
        for (double t : new double[] {100.0, 110.0, 120.0, 150.0, 200.0, 298.15, 400.0, 600.0}) {
            var terms = reference.prepare(t);
            for (double p : new double[] {0.1e6, 1.0e6, 3.0e6, 6.0e6}) {
                for (double[] n : COMPOSITIONS) {
                    for (Root root : Root.values()) {
                        var direct = reference.evaluate(t, p, n, root, terms);
                        evaluator.evaluate(t, p, n, root, workspace, state);
                        String at = t + " K " + p + " Pa " + n[0] + "/" + n[1] + " " + root;
                        assertEquals(bits(direct.molarVolume()), bits(state.molarVolume()), at);
                        for (int i = 0; i < 2; i++) {
                            assertEquals(bits(direct.logFugacityCoefficients()[i]), bits(state.logFugacityCoefficient(i)), at);
                        }
                        evaluator.derivatives(t, p, n, root, workspace, derivatives);
                        assertEquals(bits(direct.volumeTemperatureDerivative()), bits(derivatives.volumeTemperatureDerivative()), at);
                        assertEquals(bits(direct.volumePressureDerivative()), bits(derivatives.volumePressureDerivative()), at);
                        assertEquals(bits(direct.enthalpyPressureDerivative()), bits(derivatives.enthalpyPressureDerivative()), at);
                        assertEquals(bits(state.molarVolume()), bits(derivatives.state().molarVolume()), at);
                        // The reference's ideal part is each main fit at every temperature, as the stubs' inside 50-1500 K.
                        double delta = t - 298.15;
                        double ideal = 0.0;
                        for (int i = 0; i < 2; i++) {
                            var cp = pkg.properties().get(pkg.components().indexOf(i == 0 ? "Methane" : "Nitrogen")).cp();
                            double h = 0.0;
                            for (int term = 5; term >= 0; term--) h = h * delta + cp.get(term) / (term + 1);
                            ideal += n[i] * (h * delta);
                        }
                        double residual = direct.molarEnthalpy() - ideal;
                        double error = Math.abs(residual - state.residualEnthalpy());
                        worstEnthalpy = Math.max(worstEnthalpy, error / Math.max(1.0, Math.abs(direct.molarEnthalpy())));
                        assertTrue(error <= 1.0e-14 * Math.max(1.0, Math.abs(direct.molarEnthalpy())), at + ": " + error);
                        if (t == 298.15) assertEquals(bits(residual), bits(state.residualEnthalpy()), at);
                        // With the stubs' zero formation values the whole enthalpy is the reference's to rounding.
                        assertEquals(direct.molarEnthalpy(), state.molarEnthalpy(), 1.0e-12 * Math.max(1.0, Math.abs(direct.molarEnthalpy())), at);
                        states++;
                    }
                }
            }
        }
        assertEquals(256, states);
        System.out.println("P2 adapter parity: " + states + " states bitwise (v, ln phi, dv/dT, dv/dP, dh/dP); residual enthalpy "
                + states + " states, worst " + worstEnthalpy + " of |h|");
    }

    @Test
    void enthalpyEntropyAndGibbsFollowTheSpineConvention() {
        var base = PhaseTestSupport.methaneNitrogen();
        var pkg = PhaseTestSupport.network();
        // Stubs with formation values: the ideal parts move by exactly them; the residual parts do not move.
        var methane = StubIdealGas.of(pkg.properties().get(pkg.components().indexOf("Methane")), 50, 1500).withReference(-74_600.0, 186.3);
        var nitrogen = StubIdealGas.of(pkg.properties().get(pkg.components().indexOf("Nitrogen")), 50, 1500).withReference(0.0, 191.6);
        List<IdealGasFunction> gas = List.of(methane, nitrogen);
        var evaluator = PackageBinding.fromPackage(pkg, List.of("Methane", "Nitrogen"),
                new double[] {PhaseTestSupport.METHANE_TRANSLATION, PhaseTestSupport.NITROGEN_TRANSLATION}, gas);
        var w = evaluator.newWorkspace();
        var wb = base.newWorkspace();
        for (double[] st : new double[][] {{110.0, 2.0e6}, {150.0, 0.5e6}, {400.0, 3.0e6}}) {
            double t = st[0];
            double p = st[1];
            for (double[] n : new double[][] {{0.3, 0.7}, {2.0, 0.0}}) {
                for (Root root : Root.values()) {
                    PhaseState s = evaluator.evaluate(t, p, n, root, w, evaluator.newState());
                    PhaseState b = base.evaluate(t, p, n, root, wb, base.newState());
                    double total = n[0] + n[1];
                    double hIdeal = 0.0;
                    double sIdeal = 0.0;
                    double gibbs = 0.0;
                    double weightedLogPhi = 0.0;
                    for (int i = 0; i < 2; i++) {
                        double x = n[i] / total;
                        if (x == 0.0) {
                            assertEquals(Double.NEGATIVE_INFINITY, s.chemicalPotential(i));
                            continue;
                        }
                        hIdeal += x * gas.get(i).enthalpy(t);
                        sIdeal += x * (gas.get(i).entropy(t, p) - R * Math.log(x));
                        weightedLogPhi += x * s.logFugacityCoefficient(i);
                        gibbs += x * s.chemicalPotential(i);
                    }
                    String at = t + " K " + p + " Pa " + root + " " + n[0] + "/" + n[1];
                    assertEquals(bits(hIdeal), bits(s.idealGasEnthalpy()), at);
                    assertEquals(hIdeal + s.residualEnthalpy(), s.molarEnthalpy(), 0.0, at);
                    assertEquals(sIdeal, s.idealGasEntropy(), 1.0e-12 * Math.abs(sIdeal), at);
                    assertEquals(bits(b.residualEnthalpy()), bits(s.residualEnthalpy()), at);
                    assertEquals(bits(b.residualEntropy()), bits(s.residualEntropy()), at);
                    // s^R = (h^R - g^R)/T with g^R = R T sum x ln phi; g = h - T s = sum x mu.
                    assertEquals((s.residualEnthalpy() - R * t * weightedLogPhi) / t, s.residualEntropy(), 1.0e-12, at);
                    assertEquals(s.molarEnthalpy() - t * s.molarEntropy(), s.molarGibbsEnergy(), 1.0e-9 * Math.abs(s.molarGibbsEnergy()) + 1e-9, at);
                    assertEquals(gibbs, s.molarGibbsEnergy(), 1.0e-9 * Math.abs(gibbs), at);
                    assertEquals(s.molarEnthalpy() - p * s.molarVolume(), s.molarInternalEnergy(), 0.0, at);
                    assertEquals(p * s.molarVolume() / (R * t), s.compressibilityFactor(), 0.0, at);
                    // Formation values shift h by sum x dHf and s by sum x S0, exactly as the ideal parts say.
                    double shiftH = n[0] / total * -74_600.0;
                    double shiftS = n[0] / total * 186.3 + n[1] / total * 191.6;
                    assertEquals(b.molarEnthalpy() + shiftH, s.molarEnthalpy(), 1.0e-9 * Math.abs(s.molarEnthalpy()), at);
                    assertEquals(b.molarEntropy() + shiftS, s.molarEntropy(), 1.0e-9 * Math.abs(s.molarEntropy()), at);
                }
            }
        }
    }

    @Test
    void phaseKindStatesTheRootSituation() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var w = evaluator.newWorkspace();
        // Methane-rich at 110 K and 0.5 MPa: the cubic has three roots, and each preference names its own.
        PhaseState liquid = evaluator.evaluate(110.0, 0.5e6, new double[] {0.9, 0.1}, Root.LIQUID, w, evaluator.newState());
        PhaseState vapour = evaluator.evaluate(110.0, 0.5e6, new double[] {0.9, 0.1}, Root.VAPOR, w, evaluator.newState());
        assertEquals(3, liquid.physicalRootCount());
        assertEquals(PhaseKind.LIQUID, liquid.kind());
        assertEquals(PhaseKind.VAPOR, vapour.kind());
        assertTrue(liquid.molarVolume() < vapour.molarVolume());
        // Far above both critical temperatures: one root, the same state whatever the preference, not classified.
        PhaseState a = evaluator.evaluate(400.0, 3.0e6, new double[] {0.5, 0.5}, Root.LIQUID, w, evaluator.newState());
        PhaseState b = evaluator.evaluate(400.0, 3.0e6, new double[] {0.5, 0.5}, Root.VAPOR, w, evaluator.newState());
        assertEquals(1, a.physicalRootCount());
        assertEquals(PhaseKind.FLUID, a.kind());
        assertEquals(PhaseKind.FLUID, b.kind());
        assertEquals(bits(a.molarVolume()), bits(b.molarVolume()));
        assertSame(Root.LIQUID, a.root());
    }

    /** Every derivative block against central differences of the adapter's own values; 1e-6 relative. */
    @Test
    void derivativesMatchCentralDifferences() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var w = evaluator.newWorkspace();
        var d = evaluator.newDerivatives();
        var plus = evaluator.newState();
        var minus = evaluator.newState();
        Object[][] cases = {
                {250.0, 1.0e6, new double[] {0.5, 0.5}, Root.VAPOR},
                {110.0, 2.0e6, new double[] {0.7, 0.3}, Root.LIQUID},
                {300.0, 6.0e6, new double[] {0.4, 0.6}, Root.VAPOR},
                {90.0, 1.0e6, new double[] {0.0, 1.0}, Root.LIQUID},
                {150.0, 0.2e6, new double[] {0.9, 0.1}, Root.VAPOR}};
        double worst = 0.0;
        for (Object[] c : cases) {
            double t = (double) c[0];
            double p = (double) c[1];
            double[] n = (double[]) c[2];
            Root root = (Root) c[3];
            String at = t + " K " + p + " Pa " + root;
            evaluator.derivatives(t, p, n, root, w, d);
            PhaseState s = d.state();
            double dt = 1.0e-3;
            double dp = 1.0e-5 * p;
            evaluator.evaluate(t + dt, p, n, root, w, plus);
            evaluator.evaluate(t - dt, p, n, root, w, minus);
            worst = Math.max(worst, close((plus.molarVolume() - minus.molarVolume()) / (2 * dt), d.volumeTemperatureDerivative(), 0.0, at + " dv/dT"));
            worst = Math.max(worst, close((plus.molarEnthalpy() - minus.molarEnthalpy()) / (2 * dt), d.enthalpyTemperatureDerivative(), 0.0, at + " cp"));
            worst = Math.max(worst, close(-(plus.molarGibbsEnergy() - minus.molarGibbsEnergy()) / (2 * dt), s.molarEntropy(), 0.0, at + " -dg/dT = s"));
            for (int i = 0; i < 2; i++) {
                if (n[i] == 0.0) continue;
                worst = Math.max(worst, close((plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * dt),
                        d.logFugacityTemperatureDerivativeView()[i], 1.0e-12, at + " dln phi/dT " + i));
            }
            evaluator.evaluate(t, p + dp, n, root, w, plus);
            evaluator.evaluate(t, p - dp, n, root, w, minus);
            worst = Math.max(worst, close((plus.molarVolume() - minus.molarVolume()) / (2 * dp), d.volumePressureDerivative(), 0.0, at + " dv/dP"));
            worst = Math.max(worst, close((plus.molarEnthalpy() - minus.molarEnthalpy()) / (2 * dp), d.enthalpyPressureDerivative(), 0.0, at + " dh/dP"));
            worst = Math.max(worst, close((plus.molarGibbsEnergy() - minus.molarGibbsEnergy()) / (2 * dp), s.molarVolume(), 0.0, at + " dg/dP = v"));
            for (int i = 0; i < 2; i++) {
                if (n[i] == 0.0) continue;
                worst = Math.max(worst, close((plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * dp),
                        d.logFugacityPressureDerivativeView()[i], 1.0e-14, at + " dln phi/dP " + i));
            }
            // d ln phi_i / d n_j at one mole: perturb the normalised composition's mole numbers.
            double total = n[0] + n[1];
            double[] x = {n[0] / total, n[1] / total};
            double dn = 1.0e-6;
            for (int j = 0; j < 2; j++) {
                if (x[j] == 0.0) continue;
                double[] up = x.clone();
                double[] down = x.clone();
                up[j] += dn;
                down[j] -= dn;
                evaluator.evaluate(t, p, up, root, w, plus);
                evaluator.evaluate(t, p, down, root, w, minus);
                for (int i = 0; i < 2; i++) {
                    if (x[i] == 0.0) continue;
                    double scale = Math.max(Math.abs(d.logFugacityCompositionDerivative(i, 0)), Math.abs(d.logFugacityCompositionDerivative(i, 1)));
                    worst = Math.max(worst, close((plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * dn),
                            d.logFugacityCompositionDerivative(i, j), 1.0e-6 * scale, at + " dln phi_" + i + "/dn_" + j));
                }
            }
        }
        System.out.println("P2 adapter derivatives: worst deviation " + worst + " of the scale (" + worstWhat + ")");
    }

    /**
     * The fugacity state derivatives over the whole network basis (20 components, every declared pair, translations of
     * either sign) against central differences of the evaluator's own {@code ln phi}: {@code d ln phi_i/dT} carries the
     * translation's {@code -P c_i/(R T^2)} and {@code d ln phi_i/dP} is {@code v_i/(RT) - 1/P} with the translated
     * partial molar volume. A milli-kelvin and a part in ten thousand of the pressure; each probe stays on the base
     * root; 1e-6 of the largest magnitude the quantity reaches at the state.
     */
    @Test
    void networkBasisFugacityStateDerivativesMatchCentralDifferences() {
        var pkg = PhaseTestSupport.network();
        int n = pkg.components().size();
        double[] translations = new double[n];
        List<IdealGasFunction> gas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            translations[i] = (i % 5 - 2) * 1.0e-6;
            gas.add(StubIdealGas.of(pkg.properties().get(i), 50.0, 1500.0));
        }
        var evaluator = PackageBinding.fromPackage(pkg, pkg.components(), translations, gas);
        var w = evaluator.newWorkspace();
        var d = evaluator.newDerivatives();
        var plus = evaluator.newState();
        var minus = evaluator.newState();
        var random = new Random(20260916L);
        List<double[]> states = new ArrayList<>();
        for (double[] point : new double[][] {{300, 101325}, {330, 250000}, {350, 500000}, {400, 1000000}, {450, 2000000}, {520, 250000}}) {
            double[] state = new double[n + 2];
            state[0] = point[0];
            state[1] = point[1];
            for (int i = 0; i < n; i++) state[i + 2] = Math.pow(10, -2 * random.nextDouble());
            states.add(state);
        }
        double[] heavy = new double[n + 2];
        for (int i = 0; i < n; i++) heavy[i + 2] = Math.exp(-i / 6.0);
        double[][] more = {{360, 300000}, {480, 900000}};
        for (double[] point : more) {
            double[] state = heavy.clone();
            state[0] = point[0];
            state[1] = point[1];
            states.add(state);
        }
        double[] light = new double[n + 2];
        light[0] = 310;
        light[1] = 700000;
        light[2] = .7;
        light[3] = .2;
        light[4] = .1;
        states.add(light);
        int evaluated = 0, temperatureRows = 0, pressureRows = 0;
        double worstT = 0, worstP = 0;
        for (double[] state : states) {
            double t = state[0], p = state[1];
            double[] amounts = java.util.Arrays.copyOfRange(state, 2, n + 2);
            for (Root root : Root.values()) {
                try {
                    evaluator.derivatives(t, p, amounts, root, w, d);
                } catch (RuntimeException unavailable) { continue; }
                evaluated++;
                double v = d.state().molarVolume();
                double scaleT = 0, scaleP = 0;
                for (int i = 0; i < n; i++) {
                    scaleT = Math.max(scaleT, Math.abs(d.logFugacityTemperatureDerivativeView()[i]));
                    scaleP = Math.max(scaleP, Math.abs(d.logFugacityPressureDerivativeView()[i]));
                }
                double dt = 1.0e-3;
                if (sameRoot(evaluator, t + dt, p, amounts, root, w, plus, v) && sameRoot(evaluator, t - dt, p, amounts, root, w, minus, v)) {
                    for (int i = 0; i < n; i++) {
                        if (amounts[i] == 0.0) continue;
                        double difference = (plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * dt);
                        double deviation = Math.abs(difference - d.logFugacityTemperatureDerivativeView()[i]) / scaleT;
                        worstT = Math.max(worstT, deviation);
                        assertTrue(deviation <= 1.0e-6, t + " K " + p + " Pa " + root + " dln phi_" + i + "/dT: " + difference
                                + " vs " + d.logFugacityTemperatureDerivativeView()[i]);
                        temperatureRows++;
                    }
                }
                double dp = 1.0e-4 * p;
                if (sameRoot(evaluator, t, p + dp, amounts, root, w, plus, v) && sameRoot(evaluator, t, p - dp, amounts, root, w, minus, v)) {
                    for (int i = 0; i < n; i++) {
                        if (amounts[i] == 0.0) continue;
                        double difference = (plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * dp);
                        double deviation = Math.abs(difference - d.logFugacityPressureDerivativeView()[i]) / scaleP;
                        worstP = Math.max(worstP, deviation);
                        assertTrue(deviation <= 1.0e-6, t + " K " + p + " Pa " + root + " dln phi_" + i + "/dP: " + difference
                                + " vs " + d.logFugacityPressureDerivativeView()[i]);
                        pressureRows++;
                    }
                }
            }
        }
        System.out.printf(java.util.Locale.ROOT, "Network basis fugacity state derivatives: %d phase evaluations, d ln phi/dT %d rows"
                + " (worst %.2e), d ln phi/dP %d rows (worst %.2e)%n", evaluated, temperatureRows, worstT, pressureRows, worstP);
        assertTrue(evaluated >= 16, "too few phase evaluations: " + evaluated);
        assertTrue(temperatureRows > 100 && pressureRows > 100, "the derivative rows must actually be compared");
    }

    /** Evaluates into {@code out} and reports whether the root stayed on the base volume's branch. */
    private static boolean sameRoot(CubicPhaseEvaluator evaluator, double t, double p, double[] amounts, Root root,
                                    CubicPhaseEvaluator.Workspace w, PhaseState out, double baseVolume) {
        try {
            evaluator.evaluate(t, p, amounts, root, w, out);
        } catch (RuntimeException unavailable) {
            return false;
        }
        return Math.abs(out.molarVolume() - baseVolume) < 1.0e-3 * Math.abs(baseVolume);
    }

    @Test
    void continuesStatesOutsideTheIdealGasRangeAndRefusesForeignWorkspaces() {
        var pkg = PhaseTestSupport.network();
        List<IdealGasFunction> gas = List.of(StubIdealGas.constant(35.7, 200.0, 1000.0), StubIdealGas.constant(29.1, 60.0, 1000.0));
        var evaluator = PackageBinding.fromPackage(pkg, List.of("Methane", "Nitrogen"), new double[2], gas);
        var w = evaluator.newWorkspace();
        // Below methane's 200 K data the state is evaluated on the held cp (owner rule of 2026-10-16), not refused: its
        // ideal part is the stub's continuation, so the residual is the same as with a range that reaches 150 K.
        var wide = PackageBinding.fromPackage(pkg, List.of("Methane", "Nitrogen"), new double[2],
                List.of(StubIdealGas.constant(35.7, 100.0, 1000.0), StubIdealGas.constant(29.1, 60.0, 1000.0)));
        var cold = assertDoesNotThrow(() -> evaluator.evaluate(150.0, 1.0e6, new double[] {0.5, 0.5}, Root.VAPOR, w, evaluator.newState()));
        var reference = wide.evaluate(150.0, 1.0e6, new double[] {0.5, 0.5}, Root.VAPOR, wide.newWorkspace(), wide.newState());
        assertEquals(reference.molarEnthalpy(), cold.molarEnthalpy(), 1.0e-9 * Math.abs(reference.molarEnthalpy()) + 1.0e-9);
        assertEquals(reference.molarVolume(), cold.molarVolume(), 1.0e-12 * reference.molarVolume());
        var other = PhaseTestSupport.methaneNitrogen();
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(300.0, 1.0e6, new double[] {0.5, 0.5}, Root.VAPOR, other.newWorkspace(), evaluator.newState()));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(300.0, 1.0e6, new double[] {0.5, -0.1}, Root.VAPOR, w, evaluator.newState()));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(300.0, 1.0e6, new double[] {0.0, 0.0}, Root.VAPOR, w, evaluator.newState()));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(300.0, 1.0e6, new double[] {1.0}, Root.VAPOR, w, evaluator.newState()));
        // A frozen copy refuses a refill.
        PhaseState frozen = evaluator.evaluate(300.0, 1.0e6, new double[] {0.5, 0.5}, Root.VAPOR, w, evaluator.newState()).copy();
        assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(300.0, 1.0e6, new double[] {0.5, 0.5}, Root.VAPOR, w, frozen));
    }

    private static String worstWhat = "";
    private static double worstSeen = 0.0;

    /** Asserts |difference - analytic| <= 1e-6 max(|difference|, floor/1e-6); returns the deviation over that scale. */
    private static double close(double expected, double actual, double absoluteFloor, String what) {
        double deviation = Math.abs(expected - actual);
        double scale = Math.max(Math.abs(expected), absoluteFloor / 1.0e-6);
        assertTrue(deviation <= 1.0e-6 * scale, what + ": difference " + expected + " analytic " + actual);
        double relative = deviation / scale;
        if (relative > worstSeen) {
            worstSeen = relative;
            worstWhat = what;
        }
        return relative;
    }

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }
}
