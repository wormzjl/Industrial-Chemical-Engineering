package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences.GIAUQUE_EGAN_SUBLIMATION_ENTHALPY;
import static com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences.TRIPLE_PRESSURE;
import static com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences.TRIPLE_TEMPERATURE;
import static com.wormzjl.icheme.science.thermo.phase.SolidCarbonDioxideReferences.spanWagnerSublimationPressure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.CoefficientIdealGas;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The CO2-I crystal on the world's package {@code icheme:refinery} (O11 of batch 2026-11-04-open-thermo-data, owner
 * decisions E25 and E27): the crude_pr78 family (PR78 with the {@code shifted_polynomial_5} ideal gas,
 * {@link CoefficientIdealGas}, and the ChemSep CO2 dataset), the crystal anchored to its pure CO2 liquid at the record's
 * triple point, against Span and Wagner 1996. The numbers are O11's probe ({@code tools/open-thermo-data/o11/}), which
 * anchored the crystal the same way before the engine admitted it: model triple point 216.5915 K at 515508 Pa (-0.47 %),
 * sublimation at 101325 Pa 194.6359 K (Span-Wagner 194.6855 K), worst sublimation pressure error 3.836 % at 150 K from
 * 150 K to T_t (the pilot's spine family: 2.838 %; the fit's c_p is held below its 216.58 K data start), sublimation
 * enthalpy at 195 K 25173.9 J/mol (-0.104 % from 25.2 kJ/mol). On the network the crystal is on the network's datum with
 * no formation offset: its latent heat at its normal sublimation point is the family's.
 */
class RefineryCarbonDioxideCrystalTest {
    static final String REFINERY = "icheme:refinery";
    static final String CRYSTAL = "icheme:crystal_carbon_dioxide_i";
    /** The probe's sublimation grid. */
    static final double[] GRID = {150, 160, 170, 180, 185, 190, 194.6855, 200, 205, 210, 214, 216};

    record Family(CubicPhaseEvaluator fluid, CrystalPhaseEvaluator solid, SolidPhaseEvaluator.TriplePointAnchor anchor) {
        static Family of(MaterialCatalog catalog) { return of(catalog, REFINERY); }
        static Family of(MaterialCatalog catalog, String packageId) {
            var fluid = CubicPhaseEvaluator.forPackage(catalog, packageId);
            var solid = CrystalPhaseEvaluator.forPackage(catalog, packageId, CRYSTAL);
            return new Family(fluid, solid, solid.anchor(fluid));
        }
        /** {triple-point T, its pressure, sublimation T at 101325 Pa, worst p_sub error on the grid, its T, dH_sub at 195 K}. */
        double[] measure(String name) {
            double triple = bisect(t -> Math.log(sublimation(t) / saturation(t)), 216.0, 217.2);
            double triplePressure = saturation(triple);
            double normal = bisect(t -> sublimation(t) - 101325, 180, 210);
            double worst = 0, worstAt = 0;
            for (double t : GRID) {
                double e = sublimation(t) / spanWagnerSublimationPressure(t) - 1;
                System.out.printf("%s %9.4f K: p_sub %.5e Pa, %.3f %% from Span-Wagner 3.12%n", name, t, sublimation(t), 100 * e);
                if (Math.abs(e) > Math.abs(worst)) { worst = e; worstAt = t; }
            }
            double enthalpy = sublimationEnthalpy(195);
            System.out.printf("%s CO2-I: triple point %.4f K, %.1f Pa (%.3f %%); sublimation at 101325 Pa %.4f K; worst %.3f %% at %.1f K; "
                    + "sublimation enthalpy at 195 K %.1f J/mol (%.3f %%)%n", name, triple, triplePressure, 100 * (triplePressure / TRIPLE_PRESSURE - 1), normal,
                    100 * worst, worstAt, enthalpy, 100 * (enthalpy / GIAUQUE_EGAN_SUBLIMATION_ENTHALPY - 1));
            return new double[] {triple, triplePressure, normal, worst, worstAt, enthalpy};
        }
        double[] pure() { double[] x = new double[fluid.componentCount()]; x[fluid.components().indexOf("CarbonDioxide")] = 1; return x; }
        int index() { return fluid.components().indexOf("CarbonDioxide"); }
        /** {g, v} of the pure fluid on {@code root}, or of the crystal ({@code root} null), at (t, p). */
        double[] gv(Root root, double t, double p) {
            if (root == null) { var s = solid.evaluate(t, p, anchor, new PhaseState(1)); return new double[] {s.molarGibbsEnergy(), s.molarVolume()}; }
            var s = fluid.evaluate(t, p, pure(), root, fluid.newWorkspace(), fluid.newState());
            return new double[] {s.chemicalPotential(index()), s.molarVolume()};
        }
        /** The coexistence pressure of two sides at t: g_a = g_b, Newton in p on dg/dp = v. */
        double coexistence(Root a, Root b, double t, double start) {
            double p = start;
            for (int k = 0; k < 200; k++) {
                double[] x = gv(a, t, p), y = gv(b, t, p);
                double next = p - (x[0] - y[0]) / (x[1] - y[1]);
                if (!(next > 0) || !Double.isFinite(next)) next = 0.5 * p; else if (next > 2 * p) next = 2 * p;
                if (Math.abs(next / p - 1) < 1e-13) return next;
                p = next;
            }
            throw new AssertionError("no coexistence at " + t + " K");
        }
        double sublimation(double t) { return coexistence(null, Root.VAPOR, t, spanWagnerSublimationPressure(Math.min(t, TRIPLE_TEMPERATURE))); }
        double saturation(double t) { return coexistence(Root.LIQUID, Root.VAPOR, t, TRIPLE_PRESSURE); }
        double sublimationEnthalpy(double t) {
            double p = sublimation(t);
            return fluid.evaluate(t, p, pure(), Root.VAPOR, fluid.newWorkspace(), fluid.newState()).molarEnthalpy()
                    - solid.evaluate(t, p, anchor, new PhaseState(1)).molarEnthalpy();
        }
        static double bisect(DoubleUnaryOperator f, double lo, double hi) {
            for (int k = 0; k < 80; k++) { double mid = 0.5 * (lo + hi); if (f.applyAsDouble(mid) < 0) lo = mid; else hi = mid; }
            return 0.5 * (lo + hi);
        }
    }

    @Test
    void theCrystalOnTheFitFamilyReproducesTheProbe() {
        var family = Family.of(MaterialCatalog.bundled());
        assertTrue(family.solid().idealGas() instanceof CoefficientIdealGas, "the crystal is split against the family's own fit");
        assertTrue(family.fluid().idealGas(family.index()) instanceof CoefficientIdealGas);
        double[] m = family.measure("refinery");
        assertEquals(216.5915, m[0], 5e-5, "model triple point");
        assertEquals(515507.9, m[1], 0.5, "model triple-point pressure (-0.471 %)");
        assertEquals(194.6359, m[2], 5e-5, "sublimation at 1 atm (Span-Wagner 194.6855 K)");
        assertEquals(0.03836, m[3], 5e-6, "worst sublimation pressure error 150 K to T_t");
        assertEquals(150, m[4], 0.0);
        assertEquals(25173.9, m[5], 0.05, "sublimation enthalpy at 195 K (-0.104 %)");
    }

    /** The pilot's spine family keeps the numbers the probe measured beside it (the spine path is unchanged by E27). */
    @Test
    void thePilotSpineFamilyDoesNotMove() {
        var family = Family.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), "icheme:pilot_cryogenic");
        assertTrue(!(family.solid().idealGas() instanceof CoefficientIdealGas), "the pilot's crystal is split against its spine");
        double[] m = family.measure("pilot");
        assertEquals(216.5916, m[0], 5e-5, "model triple point");
        assertEquals(516047.8, m[1], 0.5, "model triple-point pressure (-0.367 %)");
        assertEquals(194.6278, m[2], 5e-5, "sublimation at 1 atm");
        assertEquals(0.02838, m[3], 5e-6, "worst sublimation pressure error 150 K to T_t");
        assertEquals(150, m[4], 0.0);
        assertEquals(25185.0, m[5], 0.05, "sublimation enthalpy at 195 K (-0.060 %)");
    }

    /**
     * The network's crystal (NetworkCrystals) on a view that holds CO2: its constants are taken at the pure crystal's
     * normal sublimation point, which the engine finds at the probe's 194.636 K, and its energy is on the network's datum:
     * the network's own vapour minus the crystal there is the family's sublimation enthalpy, with no formation offset.
     */
    @Test
    void theNetworkCrystalIsOnTheNetworkDatum() {
        var catalog = MaterialCatalog.bundled();
        var model = FluidThermodynamics.forNetwork(catalog, REFINERY).on(List.of("Methane", "CarbonDioxide"));
        var crystals = model.crystalSolids().all();
        assertEquals(2, crystals.size(), "CO2-I and the free water's ice");
        var crystal = crystals.getFirst();
        assertEquals(CRYSTAL, crystal.material().id());
        double t = crystal.referenceTemperature(), p = PhaseLayout.AMBIENT_PRESSURE;
        assertEquals(194.6359, t, 1e-3, "the pure crystal's normal sublimation point");
        int i = model.compounds().indexOf("CarbonDioxide");
        double[] pure = new double[model.compounds().size()];
        pure[i] = 1;
        double vapour = model.hydrocarbon.phase(t, p, pure, Root.VAPOR).molarEnthalpy();
        var material = crystal.material();
        double molarMass = crystal.molarMass();
        double solid = (material.specificInternalEnergy(t) + material.crystal().energyOffset()) * molarMass + p * molarMass / material.density();
        var family = Family.of(catalog);
        double expected = family.sublimationEnthalpy(t);
        System.out.printf("network CO2-I at %.4f K: sublimation enthalpy %.2f J/mol, the family's %.2f J/mol%n", t, vapour - solid, expected);
        assertEquals(expected, vapour - solid, 0.5, "the network's latent heat is the family's (formation offset 0)");
        assertEquals(GIAUQUE_EGAN_SUBLIMATION_ENTHALPY, vapour - solid, 0.0025 * GIAUQUE_EGAN_SUBLIMATION_ENTHALPY);
    }
}
