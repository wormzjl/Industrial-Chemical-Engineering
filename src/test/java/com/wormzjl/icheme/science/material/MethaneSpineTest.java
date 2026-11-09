package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Methane's reference spine r2 (batch 2026-09-24-coolprop-low-temperature, P3 WP9a, decision D13): the Setzmann-Wagner
 * segment to 425 K, the GERG-2008 ideal-gas function of Jaeschke and Schley 1995 from 425 to 1200 K, nothing above.
 * Holds the spine to the ExoMol-derived line-list reference (a holdout: nothing derived from it is bundled), the GERG
 * segment to a transcription of NIST's GERG2008.cpp, the {@code planck_einstein_cosh} term's closed forms to numerical
 * calculus, and the mapping of GERG's sinh terms onto {@code planck_einstein} with doubled theta.
 */
class MethaneSpineTest {
    /**
     * The line-list reference of P3_REFERENCES_WP8_WP10.md part B (tables B.3 and B.4): the ideal-gas Cp of 12CH4 by
     * direct summation over the 9.16 million levels of the ExoMol MM line list (Yurchenko, Owens, Kefala and Tennyson
     * 2024) plus a power-law tail. Columns: T (K), Cp (J/(mol K)), declared error of the reference below and above (%),
     * h(T) - h(298.15 K) (J/mol, the {@code dh_reference} column of the WP10 output {@code methane_cp_table.json},
     * rounded to 0.1 J/mol).
     */
    private static final double[][] LINE_LIST = {
        {300, 35.767, 0.02, 0.02, 66.1},
        {375, 39.233, 0.02, 0.02, 2871.2},
        {500, 46.519, 0.02, 0.02, 8221.7},
        {600, 52.512, 0.02, 0.02, 13174.7},
        {700, 58.222, 0.02, 0.02, 18714.4},
        {800, 63.532, 0.02, 0.02, 24805.7},
        {900, 68.397, 0.02, 0.03, 31405.9},
        {1000, 72.796, 0.02, 0.07, 38469.5},
        {1100, 76.725, 0.04, 0.21, 45949.4},
        {1200, 80.188, 0.10, 0.62, 53799.0},
    };
    /**
     * NASA CEA thermo.inp CH4 (g 8/99, Gurvich 1991; Apache-2.0), interval 1000..6000 K, a1..a7 with R = 8.314510: the
     * documented disagreement of a rigid-rotor-like table (the NIST-JANAF C-067 table until batch 2026-11-04-open-thermo-data,
     * 1.4 to 1.8 % below the spine; CEA lies 1.2 to 1.7 % above it).
     */
    private static final double[] CEA_CH4 = {3.730042760e+06, -1.383501485e+04, 2.049107091e+01, -1.961974759e-03, 4.727313040e-07,
            -3.728814690e-11, 1.623737207e-15};

    private static ReferenceSpine methane() { return com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().requireSpine(PilotCryogenicTestCatalog.PACKAGE_ID, "Methane"); }

    @Test
    void recordShapeGradeAndJoin() {
        var spine = methane();
        assertEquals("spine-methane-r3", spine.revision());
        assertEquals(MaterialCoverage.Grade.QUALIFIED, spine.coverage().grade());
        assertEquals(2, spine.segments().size());
        var setzmannWagner = spine.segments().get(0);
        var gerg = spine.segments().get(1);
        assertEquals(90.6941, setzmannWagner.minimumTemperature(), 0.0);
        assertEquals(425, setzmannWagner.maximumTemperature(), 0.0);
        assertEquals("coolprop-ae81610e-methane-alpha0", setzmannWagner.revision());
        assertEquals(425, gerg.minimumTemperature(), 0.0);
        assertEquals(1200, gerg.maximumTemperature(), 0.0);
        assertEquals("gerg2008-aga8-methane-ideal-jaeschke-schley-1995", gerg.revision());
        assertEquals(8.31451, gerg.gasConstant(), 0.0);
        assertEquals(1200, spine.maximumTemperature(), 0.0);
        var join = spine.joins().getFirst();
        System.out.printf("P3 WP9a methane join at %.1f K: Setzmann-Wagner Cp %.6f -> GERG-2008 %.6f J/(mol K), step %+.3e%n",
                join.temperature(), join.lowerHeatCapacity(), join.upperHeatCapacity(), join.upperHeatCapacity() / join.lowerHeatCapacity() - 1);
        // Measured by tools/spine-join-measurement/build-spine-records.mjs: +5.82e-5 (the smallest step on the 25 K
        // grid is at 400 K, -2.2e-6; D13 fixed the join at 425 K).
        assertTrue(join.heatCapacityStepFraction() > 5.7e-5 && join.heatCapacityStepFraction() < 5.9e-5, "join step moved: " + join.heatCapacityStepFraction());
        // CEA's standard entropy with R = 8.314510 (P2 had 186.3702 with 8.314472); ATcT formation enthalpy unchanged.
        assertEquals(186.3711, spine.standardEntropy().value(), 0.0);
        assertEquals(-74513, spine.formationEnthalpy().value(), 0.0);
    }

    /**
     * D13 holdout: Cp within the D7 0.2 % of the line-list reference at every point from 300 to 1200 K, and
     * h(T) - h(298.15 K) within 0.1 % or 20 J/mol. NASA CEA lies 1.2 to 1.7 % above the spine at 1000 to 1200 K (JANAF
     * 1.4 to 1.8 % below it), the rigid-rotor-like tables the line list refutes (P3_REFERENCES_WP8_WP10.md B.5).
     */
    @Test
    void cpAndEnthalpyAgainstTheLineListReference() {
        var spine = methane();
        double h298 = spine.enthalpy(298.15);
        for (double[] row : LINE_LIST) {
            double t = row[0], cp = spine.heatCapacity(t), deviation = cp / row[1] - 1, dh = spine.enthalpy(t) - h298;
            boolean inside = 100 * deviation >= -row[2] - 5e-4 && 100 * deviation <= row[3] + 5e-4;
            System.out.printf("P3 WP9a methane at %.0f K: Cp %.4f, line list %.3f (declared -%.2f/+%.2f %%), deviation %+.3f %% (%s); "
                    + "h - h(298.15) %.1f, line list %.1f, deviation %+.1f J/mol%n", t, cp, row[1], row[2], row[3], 100 * deviation,
                    inside ? "inside the reference's declared error" : "outside the reference's declared error", dh, row[4], dh - row[4]);
            assertTrue(Math.abs(deviation) <= 0.002, "Cp at " + t + " K: " + deviation);
            assertEquals(row[4], dh, Math.max(20, 0.001 * Math.abs(row[4])), "h at " + t + " K");
        }
        for (double t : new double[] {1000, 1100, 1200}) {
            double[] a = CEA_CH4;
            double cea = ReferenceSpine.CEA_GAS_CONSTANT * (a[0] / (t * t) + a[1] / t + a[2] + a[3] * t + a[4] * t * t + a[5] * t * t * t + a[6] * t * t * t * t);
            double deviation = spine.heatCapacity(t) / cea - 1;
            System.out.printf("methane against NASA CEA CH4 at %.0f K: %+.3f %% (documented disagreement)%n", t, 100 * deviation);
            assertTrue(deviation < -0.010 && deviation > -0.018, "CEA disagreement moved at " + t + " K: " + deviation);
        }
    }

    /**
     * The GERG segment against a transcription of NIST AGA8 GERG2008.cpp (research copy,
     * research/2026-09-24-coolprop-low-temperature/sources/nist-aga8): SetupGERG lines 665-667 (RGERG = 8.314472,
     * Rs = 8.31451, Rsr = Rs / RGERG), line 1256 (n0i[1][1..7]), line 1277 (th0i[1][4..7]), the ideal-gas set-up lines
     * 1536-1546, Alpha0GERG lines 402-458 and the property lines 319-338 (Cp0 = Cv0 + R = R (1 - a0[2]),
     * H0 = R T (1 + a0[1]), S0 = R (a0[1] - a0[0])). GERG2008.cpp carries the ln delta part of alpha0 with RGERG and
     * the rest with Rs, so its Cp0 = RGERG + Rs (B0 - 1 + sum); the spine uses the Jaeschke-Schley form
     * Cp0 = Rs (B0 + sum) with one gas constant. The two differ by exactly Rs - RGERG = 3.8e-5 J/(mol K) in Cp, by
     * (Rs - RGERG) (T - T1) in h and by (Rs - RGERG) ln(T/T1) in s, which this test holds to rounding.
     */
    @Test
    void gergSegmentEqualsTheGerg2008CppTranscription() {
        var spine = methane();
        double rGerg = 8.314472, rs = 8.31451, offset = rs - rGerg, t1 = 425;
        double worstCp = 0;
        for (double t = 437.5; t <= 1200; t += 12.5) { // at 425 K itself the lower (Setzmann-Wagner) segment is evaluated
            double[] a = gerg2008Alpha0(t, 100 / (rGerg * t)), a1 = gerg2008Alpha0(t1, 100 / (rGerg * t1));
            double cpCpp = rGerg * (1 - a[2]);
            double dhCpp = rGerg * t * (1 + a[1]) - rGerg * t1 * (1 + a1[1]);
            double dsCpp = rGerg * (a[1] - a[0]) - rGerg * (a1[1] - a1[0]);
            double dh = spine.enthalpy(t) - spine.enthalpy(t1), ds = spine.entropy(t, 1e5) - spine.entropy(t1, 1e5);
            assertEquals(offset, spine.heatCapacity(t) - cpCpp, 1e-11, "Cp at " + t);
            assertEquals(offset * (t - t1), dh - dhCpp, 1e-9 * Math.max(Math.abs(dh), 1), "h at " + t);
            assertEquals(offset * Math.log(t / t1), ds - dsCpp, 1e-11, "s at " + t);
            worstCp = Math.max(worstCp, Math.abs(spine.heatCapacity(t) / cpCpp - 1));
        }
        System.out.printf("P3 WP9a GERG segment against GERG2008.cpp: Cp differs by Rs - RGERG = %.2e J/(mol K), at most %.2e relative%n", offset, worstCp);
    }

    /** GERG2008.cpp's Alpha0GERG for pure methane after SetupGERG, transcribed; returns a0[0..2] at T (K), D (mol/l). */
    private static double[] gerg2008Alpha0(double t, double d) {
        double rGerg = 8.314472, rs = 8.31451, rsr = rs / rGerg, t0 = 298.15, d0 = 101.325 / rGerg / t0;
        double[] n = {0, 29.83843397, -15999.69151, 4.00088, 0.76315, 0.0046, 8.74432, -4.46921};
        double[] th = {0, 0, 0, 0, 820.659, 178.41, 1062.82, 1090.53};
        n[3] = n[3] - 1;
        n[2] = n[2] + t0;
        for (int j = 1; j <= 7; j++) n[j] = rsr * n[j];
        n[2] = n[2] - t0;
        n[1] = n[1] - Math.log(d0);
        double sum0 = 0, sum1 = 0, sum2 = 0;
        for (int j = 4; j <= 7; j++) {
            double th0T = th[j] / t, ep = Math.exp(th0T), em = 1 / ep, hsn = (ep - em) / 2, hcn = (ep + em) / 2;
            if (j == 4 || j == 6) {
                sum0 += n[j] * Math.log(Math.abs(hsn));
                sum1 += n[j] * th0T * hcn / hsn;
                sum2 += n[j] * (th0T / hsn) * (th0T / hsn);
            } else {
                sum0 -= n[j] * Math.log(Math.abs(hcn));
                sum1 -= n[j] * th0T * hsn / hcn;
                sum2 += n[j] * (th0T / hcn) * (th0T / hcn);
            }
        }
        return new double[] {Math.log(d) + n[1] + n[2] / t - n[3] * Math.log(t) + sum0, n[3] + n[2] / t + sum1, -(n[3] + sum2)};
    }

    /**
     * The {@code planck_einstein_cosh} term alpha = -n ln cosh(t tau) on a synthetic record (two cosh terms, one of
     * them methane's GERG term 7 with t = 1090.53 K / 190.564 K, reducing temperature 190.564 K), 100 to 3000 K:
     * Cp = dh/dT and Cp/T = ds/dT by a five-point stencil, h and s differences equal the Gauss-Legendre integrals of Cp
     * and Cp/T, and h and s equal their definitions from alpha (H/(RT) = 1 + tau alpha_tau, S/R = tau alpha_tau - alpha
     * + ln T up to a constant) with alpha_tau by a five-point stencil in tau; all to 1e-10 relative.
     */
    @Test
    void coshTermClosedFormsAgainstNumericalCalculus() {
        double tr = 190.564, a = 3, r = 8.31451;
        double[] n = {-4.46921, 0.8}, theta = {1090.53 / tr, 2.1};
        var spine = synthetic(tr, r, "{\"type\":\"log_tau\",\"a\":" + a + "},{\"type\":\"planck_einstein_cosh\",\"n\":[" + n[0] + "," + n[1]
                + "],\"t\":[" + theta[0] + "," + theta[1] + "]}", 100, 3000);
        DoubleUnaryOperator alpha = tau -> a * Math.log(tau) - n[0] * Math.log(Math.cosh(theta[0] * tau)) - n[1] * Math.log(Math.cosh(theta[1] * tau));
        double[] temperatures = {110, 150, 200, 298.15, 425, 700, 1000, 1500, 2200, 2900};
        double previous = Double.NaN;
        for (double t : temperatures) {
            double cp = spine.heatCapacity(t), dt = 1e-3 * t;
            assertEquals(cp, fivePoint(spine::enthalpy, t, dt), 1e-10 * cp, "dh/dT at " + t);
            assertEquals(cp / t, fivePoint(x -> spine.entropy(x, 1e5), t, dt), 1e-10 * cp / t, "ds/dT at " + t);
            // Closed form Cp/R against its terms written out directly.
            double direct = 1 + a;
            for (int i = 0; i < n.length; i++) { double y = theta[i] * tr / t; direct += n[i] * y * y / (Math.cosh(y) * Math.cosh(y)); }
            assertEquals(r * direct, cp, 1e-13 * cp, "Cp at " + t);
            if (!Double.isNaN(previous)) {
                double t1 = previous, lo = t1;
                double integralCp = gaussLegendre(spine::heatCapacity, lo, t), integralCpT = gaussLegendre(x -> spine.heatCapacity(x) / x, lo, t);
                double dh = spine.enthalpy(t) - spine.enthalpy(t1), ds = spine.entropy(t, 1e5) - spine.entropy(t1, 1e5);
                assertEquals(integralCp, dh, 1e-10 * Math.abs(integralCp), "integral of Cp from " + t1 + " to " + t);
                assertEquals(integralCpT, ds, 1e-10 * Math.abs(integralCpT), "integral of Cp/T from " + t1 + " to " + t);
                // Definitions from alpha(tau), alpha_tau by a five-point stencil in tau.
                double tau = tr / t, tau1 = tr / t1;
                double at = fivePoint(alpha, tau, 1e-3 * tau), at1 = fivePoint(alpha, tau1, 1e-3 * tau1);
                double hDefinition = r * t * (1 + tau * at) - r * t1 * (1 + tau1 * at1);
                double sDefinition = r * (tau * at - alpha.applyAsDouble(tau) + Math.log(t)) - r * (tau1 * at1 - alpha.applyAsDouble(tau1) + Math.log(t1));
                assertEquals(hDefinition, dh, 1e-10 * Math.abs(dh), "h from alpha at " + t);
                assertEquals(sDefinition, ds, 1e-10 * Math.abs(ds), "s from alpha at " + t);
            }
            previous = t;
        }
    }

    /**
     * GERG's sinh terms n ln|sinh(theta tau)| are planck_einstein terms n ln(1 - exp(-t tau)) with t = 2 theta: Cp, and h
     * and s up to constants, equal the sinh forms Cp/R = n (x / sinh x)^2, H/(RT) = n x coth x, S/R = n (x coth x -
     * ln sinh x), x = theta tau, on a synthetic record with methane's two GERG sinh terms (theta 820.659 and 1062.82 K,
     * reducing temperature 1 K), 100 to 3000 K.
     */
    @Test
    void sinhTermsArePlanckEinsteinTermsWithDoubledTheta() {
        double a = 3.00088, r = 8.31451;
        double[] n = {0.76315, 8.74432}, theta = {820.659, 1062.82};
        var spine = synthetic(1, r, "{\"type\":\"log_tau\",\"a\":" + a + "},{\"type\":\"planck_einstein\",\"n\":[" + n[0] + "," + n[1]
                + "],\"t\":[" + 2 * theta[0] + "," + 2 * theta[1] + "]}", 100, 3000);
        DoubleUnaryOperator h = t -> {
            double sum = 1 + a;
            for (int i = 0; i < n.length; i++) { double x = theta[i] / t; sum += n[i] * x / Math.tanh(x); }
            return r * t * sum;
        };
        DoubleUnaryOperator s = t -> {
            double sum = (1 + a) * Math.log(t);
            for (int i = 0; i < n.length; i++) { double x = theta[i] / t; sum += n[i] * (x / Math.tanh(x) - Math.log(Math.sinh(x))); }
            return r * sum;
        };
        double t1 = 298.15, worst = 0;
        for (double t = 100; t <= 3000; t += 36.7) {
            double cp = r * (1 + a);
            for (int i = 0; i < n.length; i++) { double x = theta[i] / t; cp += r * n[i] * (x / Math.sinh(x)) * (x / Math.sinh(x)); }
            assertEquals(cp, spine.heatCapacity(t), 1e-13 * cp, "Cp at " + t);
            double dh = h.applyAsDouble(t) - h.applyAsDouble(t1), ds = s.applyAsDouble(t) - s.applyAsDouble(t1);
            assertEquals(dh, spine.enthalpy(t) - spine.enthalpy(t1), 1e-12 * Math.max(Math.abs(dh), r * t1), "h at " + t);
            assertEquals(ds, spine.entropy(t, 1e5) - spine.entropy(t1, 1e5), 1e-12 * Math.max(Math.abs(ds), r), "s at " + t);
            worst = Math.max(worst, Math.abs(spine.heatCapacity(t) / cp - 1));
        }
        System.out.printf("P3 WP9a sinh terms as planck_einstein with doubled theta: worst Cp deviation %.1e%n", worst);
    }

    /** Five-point central difference. */
    private static double fivePoint(DoubleUnaryOperator f, double x, double h) {
        return (-f.applyAsDouble(x + 2 * h) + 8 * f.applyAsDouble(x + h) - 8 * f.applyAsDouble(x - h) + f.applyAsDouble(x - 2 * h)) / (12 * h);
    }

    private static final double[] GL_X = {0, -0.5384693101056831, 0.5384693101056831, -0.9061798459386640, 0.9061798459386640};
    private static final double[] GL_W = {0.5688888888888889, 0.4786286704993665, 0.4786286704993665, 0.2369268850561891, 0.2369268850561891};

    /** Composite five-point Gauss-Legendre over 400 panels. */
    private static double gaussLegendre(DoubleUnaryOperator f, double lo, double hi) {
        int panels = 400;
        double width = (hi - lo) / panels, sum = 0;
        for (int p = 0; p < panels; p++) {
            double mid = lo + (p + 0.5) * width;
            for (int k = 0; k < 5; k++) sum += GL_W[k] * f.applyAsDouble(mid + 0.5 * width * GL_X[k]);
        }
        return 0.5 * width * sum;
    }

    /** A synthetic one-segment record (not a real species) with the given Helmholtz terms. */
    private static ReferenceSpine synthetic(double reducingTemperature, double gasConstant, String terms, double min, double max) {
        String json = "{\"schema_version\":1,\"id\":\"icheme:spine_synthetic\",\"component\":\"Methane\",\"revision\":\"synthetic-r1\","
                + "\"source\":\"synthetic test record\",\"molar_mass_kg_per_mol\":0.016,"
                + "\"formation_enthalpy\":{\"value_j_per_mol\":-1000,\"uncertainty_j_per_mol\":1,\"temperature_kelvin\":298.15,\"source\":\"synthetic\"},"
                + "\"standard_entropy\":{\"value_j_per_mol_kelvin\":200,\"pressure_pascal\":100000,\"source\":\"synthetic\"},"
                + "\"ideal_gas\":{\"segments\":[{\"type\":\"helmholtz_ideal_terms\",\"temperature_min_kelvin\":" + min + ",\"temperature_max_kelvin\":" + max
                + ",\"gas_constant_j_per_mol_kelvin\":" + gasConstant + ",\"source\":\"synthetic\",\"revision\":\"synthetic-r1\",\"reducing_temperature_kelvin\":"
                + reducingTemperature + ",\"terms\":[" + terms + "]}]},"
                + "\"groups\":{\"scheme\":\"eppr78-2022\",\"counts\":{\"CH4\":1}},\"coverage\":{\"grade\":\"research_only\",\"evidence\":\"synthetic\"}}";
        return ReferenceSpine.read(JsonParser.parseString(json).getAsJsonObject());
    }
}
