package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.thermo.WaterRegion1;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Water in the equation of state with its IF97 share (batch 2026-11-11-aqueous-solvents, W3a D2 and the pure-water gates
 * of D7) on the pilot list {@code icheme:pilot_aqueous}: pure water's saturation pressure, liquid density, liquid enthalpy
 * and latent heat against IAPWS-IF97 (Region 4, Region 1, Region 2 in full: {@link If97Region2}), the share's
 * derivatives against central differences, and the two books (the share moves the balance book only).
 */
class WaterShareTest {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** IF97's specific gas constant, J/(kg K), and the molar mass it implies, kg/mol. */
    private static final double RS = 461.526, MOLAR = R / RS;
    /** IF97 Region 2's ideal-gas part (table 10). */
    private static final int[] J0 = {0, 1, -5, -4, -3, -2, -1, 2, 3};
    private static final double[] N0 = {-0.96927686500217e1, 0.10086655968018e2, -0.56087911283020e-2, 0.71452738081455e-1,
            -0.40710498223928, 0.14240819171444e1, -0.43839511319450e1, -0.28408632460772, 0.21268463753307e-1};

    private static final FluidTpEquilibrium ENGINE = FluidTpEquilibrium.forDerivedPackage(PilotAqueousTestCatalog.catalog(),
            PilotAqueousTestCatalog.PACKAGE_ID);

    /** IF97's ideal-gas enthalpy of steam, J/kg, on the formulation's own reference. */
    private static double idealEnthalpy(double t) {
        double tau = 540.0 / t, sum = 0.0;
        for (int k = 0; k < J0.length; k++) if (J0[k] != 0) sum += N0[k] * J0[k] * Math.pow(tau, J0[k] - 1);
        return RS * t * tau * sum;
    }

    /** The cubic's own saturation pressure of pure water at {@code t}, from equal fugacities, with its two phases. */
    private static double saturation(double t, PhaseState liquid, PhaseState vapour) {
        var ev = ENGINE.evaluator();
        double[] pure = new double[ev.componentCount()];
        int w = ev.waterComponent();
        pure[w] = 1.0;
        var ws = ev.newWorkspace();
        double p = If97Region2.saturationPressure(Math.max(t, If97Region2.FLOOR));
        for (int k = 0; k < 80; k++) {
            ev.evaluate(t, p, pure, Root.LIQUID, ws, liquid);
            ev.evaluate(t, p, pure, Root.VAPOR, ws, vapour);
            double gap = liquid.logFugacityCoefficient(w) - vapour.logFugacityCoefficient(w);
            p *= Math.exp(gap);
            if (Math.abs(gap) < 1.0e-14) break;
        }
        ev.evaluate(t, p, pure, Root.LIQUID, ws, liquid);
        ev.evaluate(t, p, pure, Root.VAPOR, ws, vapour);
        return p;
    }

    @Test void pureWaterAgainstIf97() {
        var ev = ENGINE.evaluator();
        var water = PilotAqueousTestCatalog.catalog().requirePackage(PilotAqueousTestCatalog.PACKAGE_ID).water();
        double[] pure = new double[ev.componentCount()];
        pure[ev.waterComponent()] = 1.0;
        var ws = ev.newWorkspace();
        PhaseState liquid = ev.newState(), vapour = ev.newState(), at = ev.newState();
        double worstPressure = 0.0, worstDensity = 0.0, worstEnthalpy = 0.0;
        for (double c : new double[] {0.01, 25, 50, 100, 150, 200, 250, 300}) {
            double t = c + 273.15;
            double reference = If97Region2.saturationPressure(Math.max(t, If97Region2.FLOOR));
            double p = saturation(t, liquid, vapour);
            worstPressure = Math.max(worstPressure, Math.abs(p / reference - 1.0));
            // The liquid at IF97's saturation pressure against Region 1: density, and enthalpy less the ideal gas.
            ev.evaluate(t, reference, pure, Root.LIQUID, ws, at);
            var r1 = WaterRegion1.evaluate(water, t, reference);
            worstDensity = Math.max(worstDensity, Math.abs(r1.specificVolume() * MOLAR / at.balanceVolume() - 1.0));
            double residual = MOLAR * (r1.specificEnthalpy() - idealEnthalpy(t));
            double model = at.balanceEnthalpy() - at.idealGasEnthalpy();
            worstEnthalpy = Math.max(worstEnthalpy, Math.abs(model / residual - 1.0));
            if (c == 100 || c == 200) {
                double latent = vapour.balanceEnthalpy() - liquid.balanceEnthalpy();
                double truth = MOLAR * (RS * t * If97Region2.residualEnthalpy(t, reference) + idealEnthalpy(t) - r1.specificEnthalpy());
                double error = Math.abs(latent / truth - 1.0);
                System.out.printf(Locale.ROOT, "pure water latent heat at %.0f C: %.4f %% of IF97%n", c, 100 * error);
                assertTrue(error <= (c == 100 ? 1.0e-3 : 1.0e-2), "latent heat at " + c + " C: " + error);
            }
        }
        System.out.printf(Locale.ROOT, "pure water 0.01-300 C: p_sat %.3f %%, liquid density %.4f %%, liquid residual enthalpy %.4f %% of IF97%n",
                100 * worstPressure, 100 * worstDensity, 100 * worstEnthalpy);
        assertTrue(worstPressure <= 6.0e-3, "saturation pressure " + worstPressure);
        assertTrue(worstDensity <= 1.0e-4, "liquid density " + worstDensity);
        assertTrue(worstEnthalpy <= 1.0e-4, "liquid enthalpy " + worstEnthalpy);
    }

    @Test void shareDerivativesMatchCentralDifferences() {
        var ev = ENGINE.evaluator();
        List<String> ids = ev.components();
        int n = ev.componentCount(), w = ev.waterComponent(), e = ids.indexOf("Ethanol"), h = ids.indexOf("N-hexane");
        // Liquid, vapour, the fade below Tc, below the triple point, on the saturation branch, a dense gas.
        double[][] states = {{300, 101325, 0.9, 0}, {300, 101325, 0.02, 1}, {500, 3e6, 0.5, 1}, {630, 25e6, 0.8, 0}, {640, 3e6, 0.3, 1},
                {268, 101325, 0.95, 0}, {450, 0.2e6, 0.99, 0}, {600, 12e6, 0.7, 1}};
        var ws = ev.newWorkspace();
        var d = ev.newDerivatives();
        PhaseState a = ev.newState(), b = ev.newState();
        double worst = 0.0;
        for (double[] s : states) {
            double[] x = new double[n];
            x[w] = s[2];
            x[e] = (1 - s[2]) * 0.6;
            x[h] = (1 - s[2]) * 0.4;
            Root root = s[3] == 0 ? Root.LIQUID : Root.VAPOR;
            double t = s[0], p = s[1];
            ev.derivatives(t, p, x, root, ws, d);
            // Steps above the rounding of the cubic's own root solve (some 1e-12 of v), within the share's smooth pieces.
            // Richardson's extrapolation of two central differences (steps h and h/2) removes their h^2 error: at 630 K and
            // 25 MPa the faded cells' curvature (W3b item 9) put the plain difference 2e-5 off the analytic value.
            double dt = 1.0e-4 * t, dp = 1.0e-3 * p;
            double[] byT = new double[3], byP = new double[3];
            for (int half = 0; half < 2; half++) {
                double step = half == 0 ? dt : 0.5 * dt, weight = half == 0 ? -1.0 / 3.0 : 4.0 / 3.0;
                ev.evaluate(t + step, p, x, root, ws, a);
                ev.evaluate(t - step, p, x, root, ws, b);
                byT[0] += weight * (a.shareEnthalpy() - b.shareEnthalpy()) / (2 * step);
                byT[1] += weight * (a.shareEntropy() - b.shareEntropy()) / (2 * step);
                byT[2] += weight * (a.shareVolume() - b.shareVolume()) / (2 * step);
                double q = half == 0 ? dp : 0.5 * dp;
                ev.evaluate(t, p + q, x, root, ws, a);
                ev.evaluate(t, p - q, x, root, ws, b);
                byP[0] += weight * (a.shareEnthalpy() - b.shareEnthalpy()) / (2 * q);
                byP[1] += weight * (a.shareEntropy() - b.shareEntropy()) / (2 * q);
                byP[2] += weight * (a.shareVolume() - b.shareVolume()) / (2 * q);
            }
            double[] analytic = {d.shareEnthalpyTemperatureDerivative(), d.shareEntropyTemperatureDerivative(), d.shareVolumeTemperatureDerivative(),
                    d.shareEnthalpyPressureDerivative(), d.shareEntropyPressureDerivative(), d.shareVolumePressureDerivative()};
            double[] numeric = {byT[0], byT[1], byT[2], byP[0], byP[1], byP[2]};
            // Each derivative against its own scale: the share's T and P derivatives of h, s and v at this state.
            double[] scale = {R, R / t, R / p, R * t / p, R / p, R / (p * p)};
            for (int k = 0; k < 6; k++) {
                double error = Math.abs(analytic[k] - numeric[k]) / Math.max(Math.abs(numeric[k]), 1.0e-6 * scale[k]);
                worst = Math.max(worst, error);
                assertTrue(error <= 1.0e-6, "derivative " + k + " at " + t + " K, " + p + " Pa, x_w " + s[2] + ": " + analytic[k] + " vs " + numeric[k]);
            }
        }
        System.out.printf(Locale.ROOT, "water share derivatives against central differences: worst %.2e relative%n", worst);
    }

    @Test void theShareMovesTheBalanceBookOnly() {
        var ev = ENGINE.evaluator();
        List<String> basis = ENGINE.contract().components();
        double[] wet = new double[basis.size()];
        wet[basis.indexOf("Water")] = 0.5;
        wet[basis.indexOf("N-hexane")] = 0.5;
        var result = ENGINE.tp(EquilibriumRequest.tp(298.15, 101325.0, basis, wet, PhaseCompetition.FLUID_ONLY));
        assertTrue(result.converged(), result.detail());
        double h = 0.0, g = 0.0, v = 0.0, share = 0.0;
        for (PhaseAmounts phase : result.phases()) {
            PhaseState s = phase.state();
            assertEquals(s.molarEnthalpy() + s.shareEnthalpy(), s.balanceEnthalpy(), 1.0e-12 * Math.abs(s.balanceEnthalpy()));
            assertEquals(s.molarVolume() + s.shareVolume(), s.balanceVolume(), 1.0e-15 * s.balanceVolume());
            // The equilibrium book alone: g = sum x_i mu_i.
            double mu = 0.0;
            for (int i = 0; i < s.componentCount(); i++) if (s.moleFraction(i) > 0.0) mu += s.moleFraction(i) * s.chemicalPotential(i);
            assertEquals(mu, s.molarGibbsEnergy(), 1.0e-9 * R * s.temperature());
            h += phase.total() * s.balanceEnthalpy();
            g += phase.total() * s.molarGibbsEnergy();
            v += phase.total() * s.balanceVolume();
            share += phase.total() * Math.abs(s.shareEnthalpy());
        }
        assertTrue(share > 0.0, "the water-rich liquid carries a share");
        assertEquals(h, result.enthalpy(), 1.0e-9 * Math.abs(h));
        assertEquals(v, result.volume(), 1.0e-12 * v);
        assertEquals(g, result.gibbsEnergy(), 1.0e-9 * Math.abs(g));
        // A list without water: no share at all.
        double[] dry = new double[basis.size()];
        dry[basis.indexOf("Methane")] = 0.5;
        dry[basis.indexOf("N-hexane")] = 0.5;
        var plain = ENGINE.tp(EquilibriumRequest.tp(298.15, 101325.0, basis, dry, PhaseCompetition.FLUID_ONLY));
        assertTrue(plain.converged(), plain.detail());
        for (PhaseAmounts phase : plain.phases()) {
            assertEquals(0.0, phase.state().shareEnthalpy());
            assertEquals(phase.state().molarEnthalpy(), phase.state().balanceEnthalpy());
        }
    }
}
