package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import org.junit.jupiter.api.Test;

/**
 * The CO2-I crystal on the pilot fluid family (P4 stage 1, batch 2026-09-24-coolprop-low-temperature): the translated
 * PR78 with the Soave alpha and the spine of {@code icheme:pilot_cryogenic}, the crystal anchored to its pure CO2
 * liquid at the triple point with the record's fusion enthalpy (9019 J/mol, chosen by measurement; the last test keeps
 * the comparison with Jaeger and Span's 8875 J/mol). Measured against the P0 targets (sublimation pressure within 5 %
 * of the holdouts from 150 K to T_t, sublimation enthalpy within 2 % of 25.2 kJ/mol at 195 K) with the declared bounds
 * below; every number is printed.
 */
class SolidCarbonDioxideQualificationTest {
    static final String PILOT = CrystalPhaseEvaluatorTest.PILOT;
    static final String CRYSTAL = CrystalPhaseEvaluatorTest.CRYSTAL;

    /** The pilot fluid family and its anchored crystal, with the record's or an edited fusion enthalpy. */
    record Family(CubicPhaseEvaluator fluid, CrystalPhaseEvaluator solid, SolidPhaseEvaluator.TriplePointAnchor anchor) {
        static Family of(MaterialCatalog catalog) {
            var fluid = CubicPhaseEvaluator.forPackage(catalog, PILOT);
            var solid = CrystalPhaseEvaluator.forPackage(catalog, PILOT, CRYSTAL);
            return new Family(fluid, solid, solid.anchor(fluid));
        }

        static Family withFusionEnthalpy(double fusion) {
            return of(PilotCryogenicTestCatalog.parseWith(CrystalPhaseEvaluatorTest.CRYSTAL_PATH,
                    o -> o.getAsJsonObject("anchor").addProperty("fusion_enthalpy_j_per_mol", fusion)));
        }

        double sublimation(double t) { return familyCoexistence(fluid, solid, anchor, t, Root.VAPOR, spanWagnerSublimationPressure(t)); }

        double melting(double t) { return familyCoexistence(fluid, solid, anchor, t, Root.LIQUID, spanWagnerMeltingPressure(t)); }

        double sublimationEnthalpy(double t) {
            double p = sublimation(t);
            double[] pure = new double[fluid.componentCount()];
            pure[fluid.components().indexOf("CarbonDioxide")] = 1;
            PhaseState vapour = fluid.evaluate(t, p, pure, Root.VAPOR, fluid.newWorkspace(), fluid.newState());
            return vapour.molarEnthalpy() - solid.evaluate(t, p, anchor, new PhaseState(1)).molarEnthalpy();
        }

        /** Worst |p/p_ref - 1| from 150 K to T_t against Span-Wagner 3.12 and Fray-Schmitt. */
        double worstSublimationError() {
            double worst = 0;
            for (double t : TARGET_TEMPERATURES) {
                double p = sublimation(t);
                worst = Math.max(worst, Math.max(Math.abs(p / spanWagnerSublimationPressure(t) - 1), Math.abs(p / fraySchmittSublimationPressure(t) - 1)));
            }
            return worst;
        }
    }

    static final double[] TARGET_TEMPERATURES = {150, 155, 160, 165, 170, 175, 180, 185, 190, 194.6855, 194.7, 195, 200, 205, 210, 212, 214, 216, TRIPLE_TEMPERATURE};

    /**
     * (a) Sublimation pressure. P0 target: 5 % against the holdouts from 150 K to T_t. Measured on the pilot family:
     * worst 2.84 % against Span-Wagner (150 K) and 2.94 % against Fray-Schmitt (150 K), -0.36 % at T_t (the cubic's own
     * saturation pressure at T_t, 0.34 % low). Declared: 3 % from 150 K to T_t; below 150 K against Fray-Schmitt 4 %
     * (3.93 % at 130 K; Span-Wagner's 3.12 lies outside its data there and is printed only); against the same crystal on
     * the Span-Wagner oracle 2.5 %; as a temperature error 0.45 K.
     */
    @Test
    void sublimationPressureOnThePilotFamily() {
        var family = Family.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot());
        var co2 = HelmholtzFluid.load("CarbonDioxide");
        var reference = CrystalPhaseEvaluatorTest.jaegerSpanRecord();
        double[] oracle = oracleConstants(reference, co2);
        System.out.println("T K | p pilot Pa | vs SW 3.12 % | vs Fray-Schmitt % | vs Trusler 48 % | vs JS+SW oracle % | T_SW(p) - T K");
        double worstTarget = 0, worstCold = 0, worstOracle = 0, worstKelvin = 0;
        for (double t : new double[] {130, 135, 140, 145, 150, 155, 160, 165, 170, 175, 180, 185, 190, 194.6855, 195, 200, 205, 210, 212, 214, 216, TRIPLE_TEMPERATURE}) {
            double p = family.sublimation(t);
            double sw = p / spanWagnerSublimationPressure(t) - 1;
            double fs = p / fraySchmittSublimationPressure(t) - 1;
            double js = p / oracleCoexistence(reference.gibbsFunction(), oracle, co2, t, HelmholtzFluid.Root.VAPOUR, spanWagnerSublimationPressure(t)) - 1;
            double kelvin = Math.log(1 + sw) / spanWagnerSublimationSlope(t);
            System.out.printf("%8.4f | %12.5e | %8.3f | %8.3f | %8.3f | %8.3f | %7.4f%n", t, p, 100 * sw, 100 * fs,
                    t >= 150 ? 100 * (p / truslerSublimationPressure(t) - 1) : Double.NaN, 100 * js, kelvin);
            if (t >= 150) worstTarget = Math.max(worstTarget, Math.max(Math.abs(sw), Math.abs(fs)));
            else worstCold = Math.max(worstCold, Math.abs(fs));
            worstOracle = Math.max(worstOracle, Math.abs(js));
            worstKelvin = Math.max(worstKelvin, Math.abs(kelvin));
        }
        System.out.printf("Pilot sublimation: worst %.3f %% from 150 K to T_t (P0 5 %%, declared 3 %%), %.3f %% vs Fray-Schmitt below 150 K (declared 4 %%), "
                + "%.3f %% vs the oracle-anchored crystal (declared 2.5 %%), %.3f K (declared 0.45 K)%n", 100 * worstTarget, 100 * worstCold, 100 * worstOracle, worstKelvin);
        assertTrue(worstTarget <= 0.03, "sublimation 150 K to T_t");
        assertTrue(worstCold <= 0.04, "sublimation below 150 K");
        assertTrue(worstOracle <= 0.025, "against the oracle-anchored crystal");
        assertTrue(worstKelvin <= 0.45, "as a temperature");
    }

    /**
     * (b) Melting, to the family's 10 MPa ceiling (about 2 K above T_t: the melting line rises 4.7 MPa/K). No P0 row for
     * pure melting; measured melting temperature within 0.125 K of Span-Wagner's 3.10 at every pressure (the slope
     * (p - p_t)/(T - T_t) is 5.3 to 6.6 % steeper: the family's liquid volume at T_t is 1.08 % small, which shortens the
     * volume of fusion by 4.6 %, and the fusion enthalpy is 1.6 % above Jaeger and Span's). Declared: 0.13 K, slope 7 %.
     */
    @Test
    void meltingOnThePilotFamilyUpToItsPressureCeiling() {
        var family = Family.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot());
        double worstKelvin = 0, worstSlope = 0, previous = TRIPLE_TEMPERATURE;
        System.out.println("T K | p pilot Pa | p SW Pa | slope ratio % | T_SW(p) - T K");
        for (double t = 216.6; ; t += 0.2) {
            double p = family.melting(t);
            if (p > 1e7) {
                double lo = previous, hi = t;
                for (int i = 0; i < 60; i++) { double mid = 0.5 * (lo + hi); if (family.melting(mid) > 1e7) hi = mid; else lo = mid; }
                double kelvin = lo - spanWagnerMeltingTemperature(1e7);
                System.out.printf("melting temperature at 10 MPa: pilot %.4f K, Span-Wagner %.4f K, difference %.4f K%n", lo, spanWagnerMeltingTemperature(1e7), kelvin);
                worstKelvin = Math.max(worstKelvin, Math.abs(kelvin));
                break;
            }
            previous = t;
            double slope = (p - TRIPLE_PRESSURE) / (spanWagnerMeltingPressure(t) - TRIPLE_PRESSURE) - 1;
            double kelvin = spanWagnerMeltingTemperature(p) - t;
            System.out.printf("%8.3f | %12.5e | %12.5e | %7.3f | %8.4f%n", t, p, spanWagnerMeltingPressure(t), 100 * slope, kelvin);
            worstKelvin = Math.max(worstKelvin, Math.abs(kelvin));
            worstSlope = Math.max(worstSlope, Math.abs(slope));
        }
        System.out.printf("Pilot melting to 10 MPa: worst %.4f K (declared 0.13 K), slope %.3f %% (declared 7 %%)%n", worstKelvin, 100 * worstSlope);
        assertTrue(worstKelvin <= 0.13);
        assertTrue(worstSlope <= 0.07);
    }

    /**
     * (c) Sublimation enthalpy. P0 target: 2 % of 25.2 kJ/mol (Giauque and Egan 1937) at 195 K. Measured -0.060 % at
     * 195 K and -0.021 % at 194.67 K. Declared 0.25 %.
     */
    @Test
    void sublimationEnthalpyOnThePilotFamily() {
        var family = Family.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot());
        for (double t : new double[] {140, 170, 194.67, 195}) {
            double dh = family.sublimationEnthalpy(t);
            System.out.printf("Pilot sublimation enthalpy at %.2f K: %.1f J/mol (%.3f %% from 25.2 kJ/mol)%n", t, dh, 100 * (dh / GIAUQUE_EGAN_SUBLIMATION_ENTHALPY - 1));
        }
        assertEquals(GIAUQUE_EGAN_SUBLIMATION_ENTHALPY, family.sublimationEnthalpy(195), 0.0025 * GIAUQUE_EGAN_SUBLIMATION_ENTHALPY);
        assertEquals(GIAUQUE_EGAN_SUBLIMATION_ENTHALPY, family.sublimationEnthalpy(194.67), 0.0025 * GIAUQUE_EGAN_SUBLIMATION_ENTHALPY);
    }

    /**
     * (d) The crystal's own c_p, v, alpha and kappa (independent of the anchor, so the same on every family) against
     * Trusler 2011, used as an independent cross-check (his equation is not implemented): molar volume on the
     * sublimation curve within 0.26 % of his equation 50 (Jaeger-Span is 0.23 % larger from 80 to 160 K and 0.26 %
     * smaller at T_t); expansion coefficient within 3.1 % of d ln V/dT of equation 50 from 80 to 160 K, then falling to
     * -22 % at T_t (Trusler's anharmonic term raises it near the triple point; Jaeger and Span fitted it to data below
     * 140 K): a declared disagreement of the two reference equations; c_p within 0.3 % of the two Giauque and Egan points
     * quoted by the P2 record (secondary). kappa is printed; Trusler gives it only as a figure.
     */
    @Test
    void crystalPropertiesAgainstTrusler() {
        var js = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().crystals().get(CRYSTAL).gibbsFunction();
        double worstVolume = 0, worstColdExpansion = 0, worstExpansion = 0;
        System.out.println("T K | v JS m3/mol | v Trusler 50 | % | alpha JS 1/K | alpha Trusler 50 | % | kappa JS 1/Pa | cp JS J/(mol K)");
        for (double t : new double[] {80, 100, 120, 140, 160, 180, 194.67, 200, 210, TRIPLE_TEMPERATURE}) {
            var s = js.evaluate(t, spanWagnerSublimationPressure(t), 0, 0);
            double volume = s.volume() / truslerSublimationVolume(t) - 1;
            double expansion = s.expansion() / truslerSublimationExpansion(t) - 1;
            System.out.printf("%8.3f | %.6e | %.6e | %7.3f | %.5e | %.5e | %7.2f | %.4e | %8.4f%n", t, s.volume(), truslerSublimationVolume(t), 100 * volume,
                    s.expansion(), truslerSublimationExpansion(t), 100 * expansion, s.compressibility(), s.heatCapacity());
            worstVolume = Math.max(worstVolume, Math.abs(volume));
            if (t <= 160) worstColdExpansion = Math.max(worstColdExpansion, Math.abs(expansion));
            worstExpansion = Math.max(worstExpansion, Math.abs(expansion));
        }
        System.out.printf("Against Trusler 2011: volume %.3f %% (declared 0.3 %%), expansion %.2f %% to 160 K (declared 3.5 %%), %.2f %% to T_t (declared 25 %%)%n",
                100 * worstVolume, 100 * worstColdExpansion, 100 * worstExpansion);
        assertTrue(worstVolume <= 0.003);
        assertTrue(worstColdExpansion <= 0.035);
        assertTrue(worstExpansion <= 0.25);
        for (double[] point : new double[][] {{146.48, 47.11}, {189.78, 54.55}}) {
            double cp = js.evaluate(point[0], spanWagnerSublimationPressure(point[0]), 0, 0).heatCapacity();
            System.out.printf("c_p at %.2f K: %.4f J/(mol K) against %.2f (%.3f %%)%n", point[0], cp, point[1], 100 * (cp / point[1] - 1));
            assertEquals(point[1], cp, 0.004 * point[1]);
        }
    }

    /**
     * The fusion enthalpy of the pilot anchor, decided by measurement (P4_SOLID_CO2_MODEL.md section 5): the record's
     * 9019 J/mol against Jaeger and Span's 8875 J/mol on (a) the worst sublimation error from 150 K to T_t (2.94 %
     * against 6.66 %, the P0 target is 5 %) and (c) the sublimation enthalpy at 195 K (-0.06 % against -0.63 %); (b) the
     * melting temperature at 10 MPa is slightly better with 8875 (-0.094 K against -0.125 K).
     */
    @Test
    void fusionEnthalpyChoiceIsTheMeasuredBetterOne() {
        var chosen = Family.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot());
        var jaegerSpan = Family.withFusionEnthalpy(8875);
        assertEquals(9019, chosen.solid().record().anchor().fusionEnthalpy(), 0.0);
        double chosenError = chosen.worstSublimationError(), jaegerSpanError = jaegerSpan.worstSublimationError();
        double chosenEnthalpy = chosen.sublimationEnthalpy(195) / GIAUQUE_EGAN_SUBLIMATION_ENTHALPY - 1;
        double jaegerSpanEnthalpy = jaegerSpan.sublimationEnthalpy(195) / GIAUQUE_EGAN_SUBLIMATION_ENTHALPY - 1;
        System.out.printf("dH_fus 9019: sublimation %.3f %%, enthalpy %.3f %%; dH_fus 8875: sublimation %.3f %%, enthalpy %.3f %%%n",
                100 * chosenError, 100 * chosenEnthalpy, 100 * jaegerSpanError, 100 * jaegerSpanEnthalpy);
        assertTrue(chosenError < 0.05 && jaegerSpanError > 0.05, "only the chosen value meets the P0 sublimation target");
        assertTrue(Math.abs(chosenEnthalpy) < Math.abs(jaegerSpanEnthalpy));
    }
}
