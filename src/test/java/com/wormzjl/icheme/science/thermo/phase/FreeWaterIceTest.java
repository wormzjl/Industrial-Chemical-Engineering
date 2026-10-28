package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * WP4 of batch 2026-09-24-coolprop-low-temperature, gates (a) and (b) (RANGE_EXTRAPOLATION_PLAN.md sections 1 and 6):
 * free water below its melting line is ice Ih, decided by water's own rule.
 *
 * <p>Physics. Water freezes below {@code T_m = 273.16 K - 7.42e-8 K/Pa (P - 611.657 Pa)} (Clapeyron with the ice
 * record's volume and fusion enthalpy): 273.1526 K at 1 bar, 272.418 K at 10 MPa. Over ice the steam keeps IAPWS
 * R14-08's sublimation pressure: 259.87 Pa at 263.15 K (a supercooled liquid would keep 287 Pa) and 5.6e-12 Pa at
 * 111.6 K. Nitrogen at 1 bar holding 1170 Pa of water (50 % humidity at 20 °C) cooled to -10 °C keeps 260 Pa: 0.91 % of
 * its moles, 78 % of its water, leave as ice. Water in LNG at 111.6 K is all ice. Across the freeze a PH or UV
 * specification lands on the melting line with the ice fraction its balance gives.</p>
 */
class FreeWaterIceTest {
    private static final String ICE = "icheme:crystal_ice_ih";

    private static FluidTpEquilibrium service() {
        return PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(FluidTestSupport.networkModel()));
    }

    private static PhaseAmounts icePhase(EquilibriumResult result) {
        PhaseAmounts ice = null;
        for (PhaseAmounts phase : result.phases()) {
            if (phase.kind() == PhaseKind.SOLID) {
                assertEquals(ICE, phase.crystal(), "the solid is the free water's ice");
                ice = phase;
            }
        }
        return ice;
    }

    /** Gate (a): humid nitrogen at 1 bar cooled to -10 °C keeps the ice's sublimation pressure; the rest of its water is ice. */
    @Test
    void humidNitrogenBelowItsFrostPointKeepsTheSublimationPressure() {
        var service = service();
        var contract = service.contract();
        int water = contract.waterIndex();
        double p = 1.0e5;
        double t = 263.15;
        double[] feed = new double[contract.components().size()];
        feed[water] = 1170.0 / p;
        feed[contract.index("Nitrogen")] = 1.0 - feed[water];
        var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, result.status(), result.toString());
        assertEquals(Classification.FREE_WATER, result.classification(), result.toString());
        var split = result.freeWater();
        assertEquals(0.0, split.liquidWater(), "below the melting line the condensed water is ice");
        var ice = icePhase(result);
        assertNotNull(ice, result.toString());
        assertEquals(split.ice(), ice.amount(water), 0.0);
        // R14-08 at 263.15 K: 259.874 Pa, scaled by 1 + 7.3e-5 to meet the liquid on the melting line at 1 bar.
        assertEquals(259.89, split.waterPartialPressure(), 0.02, "the steam over ice");
        assertTrue(split.waterPartialPressure() < 0.95 * service.freeWater().saturationPressure(t), "below the supercooled liquid's 287 Pa");
        double share = split.ice() / (feed[water] + feed[contract.index("Nitrogen")]);
        assertEquals(0.00912, share, 0.00005, "0.91 % of the moles leave as ice");
        assertEquals(0.780, split.ice() / feed[water], 0.003, "78 % of the water");
        assertTrue(result.conservationDefect(feed) < 1e-13);
        System.out.printf(Locale.ROOT, "WP4 gate (a) humid N2 at 263.15 K, 1 bar: p_w %.3f Pa (supercooled liquid %.3f Pa), ice %.5f of the moles, %.4f of the water%n",
                split.waterPartialPressure(), service.freeWater().saturationPressure(t), share, split.ice() / feed[water]);
    }

    /** Gate (a): water in LNG (methane with 5 % nitrogen) at 111.6 K and 1 atm is all ice; its steam is at 5.6e-12 Pa. */
    @Test
    void waterInLngIsAllIce() {
        var service = service();
        var contract = service.contract();
        int water = contract.waterIndex();
        double p = 101325.0;
        double t = 111.6;
        double[] feed = new double[contract.components().size()];
        feed[contract.index("Methane")] = 0.95;
        feed[contract.index("Nitrogen")] = 0.05;
        feed[water] = 0.1;
        var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, result.status(), result.toString());
        assertEquals(Classification.FREE_WATER, result.classification(), result.toString());
        var split = result.freeWater();
        assertEquals(0.0, split.liquidWater());
        assertNotNull(icePhase(result), result.toString());
        assertTrue(split.ice() > feed[water] * (1.0 - 1.0e-12), "all of the water is ice: " + split.ice());
        var ice = service.freeWater().ice();
        assertEquals(5.63e-12, ice.fugacity(t, p), 0.02e-12, "R14-08's sublimation pressure at 111.6 K");
        // The ice's enthalpy (R10-06 through the record): water from 20 °C to ice at 111.6 K gives up 1.5 + 6.0 + 4.4 kJ/mol.
        double[] liquid = new double[2];
        service.freeWater().liquid(293.15, p, liquid);
        double given = liquid[1] - ice.properties(t, p).enthalpy();
        assertEquals(11.94e3, given, 0.1e3, "liquid at 20 °C to ice at 111.6 K");
        System.out.printf(Locale.ROOT, "WP4 gate (a) water in LNG at 111.6 K: ice %.12f of the water, f_ice %.4e Pa, %s; water from 20 °C to ice gives up %.1f J/mol%n",
                split.ice() / feed[water], ice.fugacity(t, p), result.classification(), given);
    }

    /**
     * Gate (b): pure water across its freeze, PH at 1 bar and 10 MPa and UV at 1 bar, with 40 % of it ice by the balance:
     * the answer lies on the melting line (273.1526 K and 272.418 K) with that ice fraction.
     */
    @Test
    void phAndUvAcrossTheFreezeLandOnTheMeltingLine() {
        var service = service();
        var contract = service.contract();
        var workspace = service.newWorkspace();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        var model = service.freeWater();
        var ice = model.ice();
        double[] pure = new double[species.size()];
        pure[water] = 1.0;
        double beta = 0.4;
        assertEquals(273.1526, ice.meltingTemperature(1.0e5), 2e-4, "Clapeyron at 1 bar");
        assertEquals(272.418, ice.meltingTemperature(1.0e7), 2e-3, "Clapeyron at 10 MPa");
        for (double p : new double[] {1.0e5, 1.0e7}) {
            double melting = ice.meltingTemperature(p);
            double[] liquid = new double[2];
            model.liquid(melting, p, liquid);
            var solid = ice.properties(melting, p);
            double h = (1.0 - beta) * liquid[1] + beta * solid.enthalpy();
            var ph = service.ph(EquilibriumRequest.ph(p, h, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertFreezing(ph, melting, p, beta, pure, water, "PH at " + p + " Pa");
            assertEquals(h, ph.enthalpy(), 1e-9 * Math.abs(h));
            if (p == 1.0e5) {
                double v = (1.0 - beta) * liquid[0] + beta * solid.volume();
                var uv = service.uv(EquilibriumRequest.uv(h - p * v, v, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
                assertFreezing(uv, melting, p, beta, pure, water, "UV at " + p + " Pa");
                System.out.printf(Locale.ROOT, "WP4 gate (b) UV at 1 bar: %.6f K, %.3f Pa, ice %.8f (%d TP calls)%n",
                        uv.phases().get(0).temperature(), uv.phases().get(0).pressure(), uv.freeWater().ice(), uv.diagnostics().outerIterations());
                // Answered on the line itself (FluidTpEquilibrium.freezingAtVolume) once the search's first step from its
                // 273.16 K start crosses it: 11 TP calls of volume solves at the start, 4 on the line (85 before).
                assertTrue(uv.diagnostics().outerIterations() <= 15, "TP calls: " + uv.diagnostics().outerIterations());
            }
            System.out.printf(Locale.ROOT, "WP4 gate (b) PH at %.0f Pa: %.6f K (T_m %.6f K), ice %.8f (%d TP calls)%n", p,
                    ph.phases().get(0).temperature(), melting, ph.freeWater().ice(), ph.diagnostics().outerIterations());
        }
    }

    /**
     * The free water's freeze at a fixed volume (the lead's direction of 2026-10-17): a rigid 1 m3 vessel of nitrogen over
     * water, equal moles at 1 atm, whose water was cooled as a liquid to 263.15 K or 272 K. Physics: it freezes on its
     * melting line, ice and liquid water at T_m(P); the latent heat of the ice warms the vessel back to the line, so the ice
     * fraction is the energy balance's, about C (T_m - T) / (n_w L) with C about 4.4 kJ/K and L 6.0 kJ/mol: 0.16 from
     * 263.15 K, 0.02 from 272 K. The ice's 9 % more volume compresses the gas cushion, which sets P (a few pascals), and
     * T_m moves with P at -7.42e-8 K/Pa. Answered on the line in a handful of TP equilibria; before, the UV search closed
     * on the freeze as a step of U(T) and refused it.
     */
    @Test
    void aRigidVesselOfNitrogenOverWaterFreezesOnTheMeltingLine() {
        var service = service();
        var contract = service.contract();
        var workspace = service.newWorkspace();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        var model = service.freeWater();
        var ice = model.ice();
        double p0 = 101325.0;
        for (double t0 : new double[] {263.15, 272.0}) {
            double[] feed = new double[species.size()];
            feed[contract.index("Nitrogen")] = 0.5;
            feed[water] = 0.5;
            double scale = 1.0 / service.tp(EquilibriumRequest.tp(t0, p0, species, feed, PhaseCompetition.FLUID_ONLY)).volume();
            for (int i = 0; i < feed.length; i++) feed[i] *= scale;
            var cold = service.tp(EquilibriumRequest.tp(t0, p0, species, feed, PhaseCompetition.FLUID_ONLY));
            // The vessel with its water cooled as a liquid: the TP answer's ice as supercooled liquid water at (t0, p0).
            double frozen = cold.freeWater().ice();
            double[] liquid = new double[2];
            model.liquid(t0, p0, liquid);
            var solid = ice.properties(t0, p0);
            double u = cold.internalEnergy() + frozen * (liquid[1] - p0 * liquid[0] - solid.enthalpy() + p0 * solid.volume());
            double v = cold.volume() + frozen * (liquid[0] - solid.volume());
            var uv = service.uv(EquilibriumRequest.uv(u, v, species, feed, PhaseCompetition.FLUID_ONLY), workspace);
            String at = "UV from " + t0 + " K";
            assertEquals(Status.CONVERGED, uv.status(), at + ": " + uv);
            assertEquals(Classification.FREE_WATER, uv.classification(), at);
            assertTrue(uv.diagnostics().coexistenceFromBalance(), at);
            double t = uv.phases().get(0).temperature();
            double p = uv.phases().get(0).pressure();
            assertEquals(ice.meltingTemperature(p), t, 1e-9, at + ": on the melting line at its pressure");
            var split = uv.freeWater();
            assertNotNull(icePhase(uv), at);
            assertTrue(split.ice() > 0.0 && split.liquidWater() > 0.0, at + ": ice and liquid water coexist");
            double total = uv.totalAmount();
            assertEquals(u, uv.internalEnergy(), 1e-6 * total * 8.314462618 * t, at + ": the energy");
            assertEquals(v, uv.volume(), 1e-6 * v, at + ": the volume");
            // The ice fraction is the energy balance's at the answer's pressure: the warm side there, TP on the line.
            var warm = service.tp(EquilibriumRequest.tp(t, p, species, feed, PhaseCompetition.FLUID_ONLY));
            double condensed = warm.freeWater().liquidWater();
            model.liquid(t, p, liquid);
            var line = ice.properties(t, p);
            double beta = (u - warm.internalEnergy()) / (condensed * (line.enthalpy() - p * line.volume() - liquid[1] + p * liquid[0]));
            assertEquals(beta * condensed, split.ice(), 1e-9 * condensed, at + ": the ice from the energy balance");
            double expected = t0 < 270 ? 0.16 : 0.02;
            assertEquals(expected, beta, 0.3 * expected, at + ": C (T_m - T) / (n_w L)");
            assertTrue(uv.diagnostics().outerIterations() <= 10, at + ": TP calls " + uv.diagnostics().outerIterations());
            System.out.printf(Locale.ROOT, "rigid vessel of N2 over water from %.2f K: %.9f K (T_m %.9f K) at %.3f Pa, ice fraction %.6f, %d TP calls%n",
                    t0, t, ice.meltingTemperature(p), p, beta, uv.diagnostics().outerIterations());
        }
    }

    /**
     * Gate (b): vacuum freezing. Liquid water at 25 °C let down to 100 Pa, below its triple point's 611.657 Pa, at its
     * enthalpy: it boils until it is cold, then freezes, and stands on the sublimation line where the ice's vapour pressure
     * is 100 Pa (252.9 K); the balance gives the steam {@code (h - h_ice) / (h_steam - h_ice)}, about 17 %, the rest ice.
     */
    @Test
    void liquidWaterLetDownBelowItsTriplePointFreezes() {
        var service = service();
        var contract = service.contract();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        var model = service.freeWater();
        var ice = model.ice();
        double[] pure = new double[species.size()];
        pure[water] = 1.0;
        double p = 100.0;
        double[] liquid = new double[2];
        model.liquid(298.15, 101325.0, liquid);
        var ph = service.ph(EquilibriumRequest.ph(p, liquid[1], species, pure, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
        assertEquals(Status.CONVERGED, ph.status(), ph.toString());
        assertEquals(Classification.FREE_WATER, ph.classification(), ph.toString());
        double t = ph.phases().get(0).temperature();
        assertEquals(p, ice.fugacity(t, p), 1e-6 * p, "on the sublimation line");
        assertEquals(252.9, t, 0.1, "where the ice's vapour pressure is 100 Pa");
        var split = ph.freeWater();
        assertEquals(0.0, split.liquidWater(), "no liquid is left below the triple point");
        double steam = (liquid[1] - ice.properties(t, p).enthalpy()) / (model.vapor().enthalpy(t) + SteamVirial.residualEnthalpy(t, p) - ice.properties(t, p).enthalpy());
        assertEquals(steam, split.waterVapor(), 1e-6, "the steam from the balance");
        assertEquals(1.0 - steam, split.ice(), 1e-6, "the rest is ice");
        assertEquals(liquid[1], ph.enthalpy(), 1e-9 * Math.abs(liquid[1]));
        assertTrue(ph.conservationDefect(pure) < 1e-13);
        System.out.printf(Locale.ROOT, "WP4 gate (b) liquid water at 25 °C let down to 100 Pa: %.4f K, steam %.5f, ice %.5f (%d TP calls)%n",
                t, split.waterVapor(), split.ice(), ph.diagnostics().outerIterations());
    }

    /**
     * Pure water with only its own steam at its triple point (the lead's direction of 2026-10-17): 1 mol in a rigid vessel
     * twice its liquid's volume there, its energy at ice fractions 0.1, 0.5 and 0.9 with the steam the volume leaves.
     * Physics: no gas holds the pressure above the steam's, so the freeze stands at the triple point, 273.16 K and
     * 611.657 Pa, ice, liquid and steam together; the amounts follow from the mole, energy and volume balances (1.66e-5
     * to 1.79e-5 m3 of vapour space at 3.71 m3 per mole of steam: 4.5e-6 to 4.8e-6 mol). Answered there before any
     * search; without the rule the UV search refuses the first target as a step of U(T) after 489 TP calls and does not
     * converge on the others in 2798.
     */
    @Test
    void pureWaterWithItsOwnSteamInARigidVesselStandsAtTheTriplePoint() {
        var service = service();
        var contract = service.contract();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        var ice = service.freeWater().ice();
        double t = ice.tripleTemperature();
        double p = ice.triplePressure();
        double[] c = tripleCorners(service);
        double[] pure = new double[species.size()];
        pure[water] = 1.0;
        double v = 2.0 * c[3];
        for (double beta : new double[] {0.1, 0.5, 0.9}) {
            double steam = (v - c[3] - beta * (c[5] - c[3])) / (c[1] - c[3]);
            double liquid = 1.0 - steam - beta;
            double u = steam * c[0] + liquid * c[2] + beta * c[4];
            var uv = service.uv(EquilibriumRequest.uv(u, v, species, pure, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
            String at = "ice fraction " + beta;
            assertEquals(Status.CONVERGED, uv.status(), at + ": " + uv);
            assertEquals(Classification.FREE_WATER, uv.classification(), at);
            assertTrue(uv.diagnostics().coexistenceFromBalance(), at);
            for (PhaseAmounts phase : uv.phases()) {
                assertEquals(t, phase.temperature(), 0.0, at + ": the triple temperature");
                assertEquals(p, phase.pressure(), 0.0, at + ": the triple pressure");
            }
            assertNotNull(icePhase(uv), at);
            var split = uv.freeWater();
            assertEquals(beta, split.ice(), 1e-12, at + ": the ice");
            assertEquals(liquid, split.liquidWater(), 1e-12, at + ": the liquid");
            assertEquals(steam, split.waterVapor(), 1e-9 * steam, at + ": the steam");
            assertTrue(uv.conservationDefect(pure) < 1e-13, at + ": the moles");
            assertEquals(u, uv.internalEnergy(), 1e-9 * PhaseTestSupport.R * t, at + ": the energy");
            assertEquals(v, uv.volume(), 1e-9 * v, at + ": the volume");
            assertTrue(uv.diagnostics().outerIterations() <= 2, at + ": TP calls " + uv.diagnostics().outerIterations());
            System.out.printf(Locale.ROOT, "pure water at its triple point, ice fraction %.1f: steam %.6e, liquid %.8f, ice %.8f mol (%d TP calls)%n",
                    beta, split.waterVapor(), split.liquidWater(), split.ice(), uv.diagnostics().outerIterations());
        }
    }

    /**
     * Just outside pure water's triple-point triangle the existing paths answer (the lead's direction of 2026-10-17), on
     * the line beside the edge crossed: the same vessel 1e-5 mol beyond the edge without ice boils on its saturation line
     * just above 273.16 K; 1e-5 mol beyond the edge without liquid sublimes just below it; a vessel 2e-5 smaller than its
     * ice and liquid at the triple point (1e-10 mol of steam short) compresses them on the melting line to about 53 kPa.
     * The triangle's test is the specification's in energy and volume: a tolerance of 1e-6 in moles took the last target
     * in at the triple point with its volume 2e-5 off, the steam's molar volume being 2e5 times the condensed water's.
     */
    @Test
    void pureWaterJustOutsideItsTriplePointIsAnsweredOnTheNeighbouringLine() {
        var service = service();
        var contract = service.contract();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        var model = service.freeWater();
        var ice = model.ice();
        double t = ice.tripleTemperature();
        double p = ice.triplePressure();
        double[] c = tripleCorners(service);
        double[] pure = new double[species.size()];
        pure[water] = 1.0;
        double[][] targets = {{1e-5, 1.0, -1e-5}, {1e-5, -1e-5, 1.0}, {-1e-10, 0.5 + 1e-10, 0.5}};
        String[] edges = {"no ice", "no liquid", "no steam"};
        for (int k = 0; k < targets.length; k++) {
            double[] a = targets[k];
            double u = a[0] * c[0] + a[1] * c[2] + a[2] * c[4];
            double v = a[0] * c[1] + a[1] * c[3] + a[2] * c[5];
            var uv = service.uv(EquilibriumRequest.uv(u, v, species, pure, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
            String at = "beyond the edge with " + edges[k];
            assertEquals(Status.CONVERGED, uv.status(), at + ": " + uv);
            double tk = uv.phases().get(0).temperature();
            double pk = uv.phases().get(0).pressure();
            var split = uv.freeWater();
            assertEquals(u, uv.internalEnergy(), 1e-6 * PhaseTestSupport.R * tk, at + ": the energy");
            assertEquals(v, uv.volume(), 1e-6 * v, at + ": the volume");
            assertTrue(uv.conservationDefect(pure) < 1e-13, at + ": the moles");
            if (k == 0) {
                assertTrue(split.ice() == 0.0 && split.liquidWater() > 0.0 && split.waterVapor() > 0.0, at + ": liquid and steam");
                assertTrue(tk > t && tk < t + 0.01, at + ": boiling just above the triple point, " + tk + " K");
                assertEquals(model.saturationPressure(tk), pk, 1e-9 * pk, at + ": on the saturation line");
            } else if (k == 1) {
                assertTrue(split.liquidWater() == 0.0 && split.ice() > 0.0 && split.waterVapor() > 0.0, at + ": ice and steam");
                assertTrue(tk < t && tk > t - 0.01, at + ": subliming just below the triple point, " + tk + " K");
                assertEquals(ice.fugacity(tk, pk), pk, 1e-6 * pk, at + ": on the sublimation line");
            } else {
                assertTrue(split.waterVapor() == 0.0 && split.ice() > 0.0 && split.liquidWater() > 0.0, at + ": ice and liquid");
                assertTrue(pk > 2.0e4 && pk < 2.0e5, at + ": compressed on the melting line, " + pk + " Pa");
                assertEquals(ice.meltingTemperature(pk), tk, 1e-9, at + ": on the melting line");
            }
            System.out.printf(Locale.ROOT, "pure water just beyond its triple point's edge with %s: %.6f K, %.4f Pa, steam %.6e, liquid %.8f, ice %.8f (%d TP calls)%n",
                    edges[k], tk, pk, split.waterVapor(), split.liquidWater(), split.ice(), uv.diagnostics().outerIterations());
        }
    }

    /** {@code (u_V, v_V, u_L, v_L, u_s, v_s)}: pure water's steam, liquid and ice at the ice record's triple point. */
    private static double[] tripleCorners(FluidTpEquilibrium service) {
        var model = service.freeWater();
        var ice = model.ice();
        double t = ice.tripleTemperature();
        double p = ice.triplePressure();
        double[] liquid = new double[2];
        model.liquid(t, p, liquid);
        var solid = ice.properties(t, p);
        double rt = PhaseTestSupport.R * t;
        // The virial steam: u = h_ig - R T - p T dB/dT, v = R T / p + B.
        return new double[] {model.vapor().enthalpy(t) - rt - p * t * SteamVirial.temperatureDerivative(t), SteamVirial.molarVolume(t, p),
                liquid[1] - p * liquid[0], liquid[0],
                solid.enthalpy() - p * solid.volume(), solid.volume()};
    }

    private static void assertFreezing(EquilibriumResult result, double melting, double p, double beta, double[] overall, int water, String at) {
        assertEquals(Status.CONVERGED, result.status(), at + ": " + result);
        assertEquals(Classification.FREE_WATER, result.classification(), at);
        assertTrue(result.diagnostics().coexistenceFromBalance(), at);
        assertEquals(melting, result.phases().get(0).temperature(), 1e-6, at + " on the melting line");
        assertEquals(p, result.phases().get(0).pressure(), 1e-3 * p, at);
        assertEquals(beta * overall[water], result.freeWater().ice(), 1e-6, at + " ice fraction from the balance");
        assertEquals((1.0 - beta) * overall[water], result.freeWater().liquidWater(), 1e-6, at);
        assertNotNull(icePhase(result), at);
        assertTrue(result.conservationDefect(overall) < 1e-13, at);
    }
}
