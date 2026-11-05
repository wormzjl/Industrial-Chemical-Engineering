package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P3 WP3 gate (batch 2026-09-24-coolprop-low-temperature, plan {@code P3_PILOT_ENGINE_PLAN.md} section 3): every
 * {@code volume_translation} of the pilot property records is recomputed from the Java Helmholtz oracle and held to
 * 1e-12 relative, so the committed numbers cannot drift from the reference equations; the shifts they imply reproduce
 * the P1 study's rule-(b) translations (section 2.1 of {@code P1_ALPHA_AND_LIQUID_PATH_STUDY.md}).
 */
class PilotVolumeAnchorTest {
    private static final double R = 8.31446261815324;
    private static final Map<String, String> FLUID = Map.of("Nitrogen", "Nitrogen", "Methane", "Methane", "Ethane", "Ethane",
            "CarbonDioxide", "CarbonDioxide");
    /** P1 study section 2.1, rule (b), cm3/mol (computed with CoolProp's critical constants). */
    private static final Map<String, Double> P1_SHIFT = Map.of("Nitrogen", 3.519, "Methane", 3.389, "Ethane", 3.454, "CarbonDioxide", 1.170);

    private static MaterialCatalog.Package pilot() { return com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().requirePackage(PilotCryogenicTestCatalog.PACKAGE_ID); }

    @Test
    void anchorsEqualTheOracle() {
        var p = pilot();
        for (int i = 0; i < p.components().size(); i++) {
            String component = p.components().get(i);
            var property = p.properties().get(i);
            var anchor = property.volumeTranslation();
            double tc = property.pr().criticalTemperature();
            assertEquals(0.8, anchor.reducedTemperature(), 0.0, component);
            assertEquals(0.8 * tc, anchor.temperatureKelvin(), 1e-12 * anchor.temperatureKelvin(), component + " T");
            var saturation = HelmholtzFluid.load(FLUID.get(component)).saturation(anchor.temperatureKelvin());
            double volume = 1.0 / saturation.liquid().density();
            assertEquals(saturation.pressure(), anchor.pressurePascal(), 1e-12 * saturation.pressure(), component + " Psat");
            assertEquals(volume, anchor.molarVolumeCubicMetresPerMol(), 1e-12 * volume, component + " v_L");
            double shift = anchor.shift(property.pr());
            System.out.printf("P3 anchor %s: Tc %.4f K, T %.6f K, Psat %.3f Pa, v_L %.6e m3/mol, c %.6f cm3/mol (P1 %.3f)%n", component, tc,
                    anchor.temperatureKelvin(), anchor.pressurePascal(), anchor.molarVolumeCubicMetresPerMol(), shift * 1e6, P1_SHIFT.get(component));
        }
    }

    /**
     * The shift is v_ref minus the untranslated PR78 liquid root, checked against an independent cubic solve; with the
     * critical constants P1 used, all four reproduce P1 to 1e-3 cm3/mol. The pilot ethane record keeps the bundled
     * ethane constants (305.32 K, 4.872 MPa, 0.099) instead of CoolProp's (305.322 K, 4.8722 MPa), which moves its
     * anchor temperature by 1.6 mK and its shift by 0.0023 cm3/mol.
     */
    @Test
    void shiftsReproduceTheP1Translations() {
        var p = pilot();
        for (int i = 0; i < p.components().size(); i++) {
            String component = p.components().get(i);
            var property = p.properties().get(i);
            var anchor = property.volumeTranslation();
            double shift = anchor.shift(property.pr());
            double independent = anchor.molarVolumeCubicMetresPerMol() - liquidRoot(property.pr(), anchor.temperatureKelvin(), anchor.pressurePascal());
            assertEquals(independent, shift, 1e-12 * anchor.molarVolumeCubicMetresPerMol(), component + " shift against an independent cubic");
            if (component.equals("Ethane")) {
                assertEquals(P1_SHIFT.get(component), shift * 1e6, 3e-3, "the pilot ethane record's own constants");
                var coolProp = new MaterialCatalog.Pr78(305.322, 4872200, 0.0990);
                double t = 0.8 * coolProp.criticalTemperature();
                var saturation = HelmholtzFluid.load("Ethane").saturation(t);
                double p1 = new VolumeTranslation(0.8, t, saturation.pressure(), 1.0 / saturation.liquid().density()).shift(coolProp);
                System.out.printf("P3 anchor Ethane with P1's CoolProp constants: c %.6f cm3/mol; with the record's %.6f cm3/mol%n", p1 * 1e6, shift * 1e6);
                assertEquals(P1_SHIFT.get(component), p1 * 1e6, 1e-3, "P1's ethane translation");
            } else {
                assertEquals(P1_SHIFT.get(component), shift * 1e6, 1e-3, component);
            }
            assertTrue(shift > 0, component);
        }
    }

    /** Smallest root above b of the PR78 cubic in Z, by Newton from Z = b P / (R T) upward; returns v in m3/mol. */
    private static double liquidRoot(MaterialCatalog.Pr78 pr, double t, double p) {
        double tc = pr.criticalTemperature(), w = pr.acentricFactor();
        double m = w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
        double alpha = 1 + m * (1 - Math.sqrt(t / tc));
        double a = 0.45724 * R * R * tc * tc / pr.criticalPressure() * alpha * alpha, b = 0.07780 * R * tc / pr.criticalPressure();
        double capA = a * p / (R * R * t * t), capB = b * p / (R * t);
        double z = capB * 1.000001;
        for (int k = 0; k < 200; k++) {
            double f = ((z - (1 - capB)) * z + (capA - 3 * capB * capB - 2 * capB)) * z - (capA * capB - capB * capB - capB * capB * capB);
            double d = (3 * z - 2 * (1 - capB)) * z + (capA - 3 * capB * capB - 2 * capB);
            double step = f / d;
            z -= step;
            if (Math.abs(step) < 1e-16 * z) break;
        }
        return z * R * t / p;
    }
}
