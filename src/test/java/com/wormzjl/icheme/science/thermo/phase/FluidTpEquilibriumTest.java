package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability.Verdict;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import com.wormzjl.icheme.science.thermo.phase.PhaseTestSupport.StubIdealGas;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/** The minimal TP equilibrium: verdicts, splits, conservation, pure coexistence, refusals and determinism. */
class FluidTpEquilibriumTest {
    private static final List<String> BINARY = List.of("Methane", "Nitrogen");

    /**
     * The 60 methane/nitrogen states of {@code TangentPlaneStabilityTest}: the service's feed verdict agrees with the
     * stability test on an independently built kernel (the dense mixing plan that test uses); every unstable feed
     * splits into two phases that conserve each component to 1e-13, have equal fugacities to 1e-9 and are both stable.
     */
    @Test
    void methaneNitrogenSplitsAtTheSixtyStabilityStates() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, FluidTpEquilibrium.Settings.DEFAULT);
        var workspace = service.newWorkspace();
        var independent = new TangentPlaneStability(PhaseTestSupport.classicalKernel("Methane", "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        int states = 0;
        int split = 0;
        double worstDefect = 0.0;
        double worstFugacity = 0.0;
        int maximumIterations = 0;
        int iterationSum = 0;
        int maximumKernelCalls = 0;
        for (double t : new double[] {110.0, 120.0}) {
            for (double p : new double[] {0.5e6, 1.0e6, 1.5e6, 2.0e6, 2.5e6, 3.0e6}) {
                for (double nitrogen : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
                    double[] z = {1.0 - nitrogen, nitrogen};
                    String at = t + " K, " + p + " Pa, x_N2 " + nitrogen;
                    var result = service.tp(EquilibriumRequest.tp(t, p, BINARY, z, PhaseCompetition.FLUID_ONLY), workspace);
                    Verdict expected = independent.test(t, p, z).verdict();
                    assertEquals(Status.CONVERGED, result.status(), at + ": " + result);
                    assertEquals(expected, result.diagnostics().feedVerdict(), at);
                    assertFalse(result.solidsAssessed());
                    double defect = result.conservationDefect(z);
                    worstDefect = Math.max(worstDefect, defect);
                    assertTrue(defect < 1.0e-13, at + ": defect " + defect);
                    if (expected == Verdict.UNSTABLE) {
                        split++;
                        assertEquals(Classification.VAPOR_LIQUID, result.classification(), at);
                        assertEquals(2, result.phases().size(), at);
                        PhaseAmounts vapour = result.phases().get(0);
                        PhaseAmounts liquid = result.phases().get(1);
                        assertEquals(PhaseKind.VAPOR, vapour.kind());
                        assertEquals(PhaseKind.LIQUID, liquid.kind());
                        assertTrue(liquid.state().molarVolume() < vapour.state().molarVolume(), at);
                        double fugacity = 0.0;
                        for (int i = 0; i < 2; i++) {
                            double lnL = Math.log(liquid.state().moleFraction(i)) + liquid.state().logFugacityCoefficient(i);
                            double lnV = Math.log(vapour.state().moleFraction(i)) + vapour.state().logFugacityCoefficient(i);
                            fugacity = Math.max(fugacity, Math.abs(lnL - lnV));
                            // Equal chemical potentials on the spine's reference, too.
                            assertEquals(liquid.state().chemicalPotential(i), vapour.state().chemicalPotential(i),
                                    1.0e-9 * PhaseTestSupport.R * t, at);
                        }
                        worstFugacity = Math.max(worstFugacity, fugacity);
                        assertTrue(fugacity < 1.0e-9, at + ": fugacity " + fugacity);
                        assertTrue(result.diagnostics().fugacityResidual() < 1.0e-9, at);
                        // Both products are stable by the independent test.
                        assertEquals(Verdict.STABLE, independent.test(t, p, liquid.amounts()).verdict(), at);
                        assertEquals(Verdict.STABLE, independent.test(t, p, vapour.amounts()).verdict(), at);
                        for (double amount : liquid.amountsView()) assertTrue(amount >= 0.0);
                        for (double amount : vapour.amountsView()) assertTrue(amount >= 0.0);
                        maximumIterations = Math.max(maximumIterations, result.diagnostics().flashIterations());
                        iterationSum += result.diagnostics().flashIterations();
                    } else {
                        assertEquals(1, result.phases().size(), at);
                        assertEquals(0.0, defect, at);
                        assertTrue(Set.of(Classification.SINGLE_VAPOR, Classification.SINGLE_LIQUID, Classification.SINGLE_FLUID)
                                .contains(result.classification()), at + " " + result.classification());
                    }
                    maximumKernelCalls = Math.max(maximumKernelCalls, result.diagnostics().kernelEvaluations());
                    states++;
                }
            }
        }
        assertEquals(60, states);
        assertEquals(12, split, "the stability test's 12 unstable states");
        System.out.println("P2 TP binary: " + states + " states, " + split + " two-phase; worst conservation defect "
                + worstDefect + ", worst |ln f_L - ln f_V| " + worstFugacity + ", flash iterations mean "
                + (double) iterationSum / split + " max " + maximumIterations + ", kernel calls max " + maximumKernelCalls);
    }

    /** Pure nitrogen at its normal boiling point, around the kernel's own equal-fugacity pressure. */
    @Test
    void pureNitrogenIsSinglePhaseAwayFromCoexistenceAndUnderdeterminedAtIt() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        var kernel = service.evaluator().kernel();
        double t = 77.355;
        double[] pure = new double[contract.components().size()];
        pure[contract.index("Nitrogen")] = 1.0;
        double[] eos = new double[kernel.componentCount()];
        eos[contract.index("Nitrogen")] = 1.0;
        int k = contract.index("Nitrogen");
        var mixture = kernel.newWorkspace();
        var liquid = kernel.newEvaluation();
        var vapour = kernel.newEvaluation();
        DoubleUnaryOperator gap = p -> {
            kernel.evaluate(t, p, eos, PengRobinsonKernel.Root.LIQUID, mixture, liquid);
            kernel.evaluate(t, p, eos, PengRobinsonKernel.Root.VAPOR, mixture, vapour);
            return liquid.logFugacityCoefficient(k) - vapour.logFugacityCoefficient(k);
        };
        double below = 90_000.0;
        double above = 120_000.0;
        for (int iteration = 0; iteration < 200 && Math.nextUp(below) < above; iteration++) {
            double middle = 0.5 * (below + above);
            if (gap.applyAsDouble(middle) > 0.0) below = middle; else above = middle;
        }
        double saturation = 0.5 * (below + above);
        assertEquals(101_325.0 * 1.0123, saturation, 0.002 * 101_325.0);
        var workspace = service.newWorkspace();
        for (double p : new double[] {90_000.0, 95_000.0, 100_000.0, saturation * (1.0 - 2.0e-9)}) {
            var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertEquals(Classification.SINGLE_VAPOR, result.classification(), "p = " + p);
            assertEquals(Root.VAPOR, result.phases().get(0).state().root());
            assertEquals(0.0, result.conservationDefect(pure));
        }
        for (double p : new double[] {saturation * (1.0 + 2.0e-9), 105_000.0, 110_000.0, 120_000.0}) {
            var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertEquals(Classification.SINGLE_LIQUID, result.classification(), "p = " + p);
            assertEquals(Root.LIQUID, result.phases().get(0).state().root());
        }
        for (double p : new double[] {saturation * (1.0 - 0.5e-9), saturation, saturation * (1.0 + 0.5e-9)}) {
            var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), pure, PhaseCompetition.FLUID_ONLY), workspace);
            assertEquals(Status.CONVERGED, result.status());
            assertEquals(Classification.PURE_COEXISTENCE_UNDERDETERMINED, result.classification(), "p = " + p);
            assertTrue(result.phases().isEmpty(), "no invented split");
            assertEquals(2, result.coexisting().size());
            PhaseState v = result.coexisting().get(0);
            PhaseState l = result.coexisting().get(1);
            assertEquals(PhaseKind.VAPOR, v.kind());
            assertEquals(PhaseKind.LIQUID, l.kind());
            assertEquals(v.chemicalPotential(k), l.chemicalPotential(k), 1.0e-8 * PhaseTestSupport.R * t);
            assertThrows(IllegalStateException.class, () -> result.conservationDefect(pure));
            assertThrows(IllegalStateException.class, result::enthalpy);
        }
        // Above its critical point pure nitrogen is a supercritical fluid: on the open research contract, and since WP7
        // (nitrogen and the network envelope to 10 MPa) on the network contract too.
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var open = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, FluidTpEquilibrium.Settings.DEFAULT);
        var supercritical = open.tp(EquilibriumRequest.tp(150.0, 6.0e6, BINARY, new double[] {0.0, 1.0}, PhaseCompetition.FLUID_ONLY));
        assertEquals(Classification.SUPERCRITICAL_FLUID, supercritical.classification());
        assertEquals(PhaseKind.FLUID, supercritical.phases().get(0).kind());
        var dense = open.tp(EquilibriumRequest.tp(400.0, 3.0e6, BINARY, new double[] {0.5, 0.5}, PhaseCompetition.FLUID_ONLY));
        assertEquals(Classification.SINGLE_FLUID, dense.classification());
        var network = service.tp(EquilibriumRequest.tp(150.0, 6.0e6, contract.components(), pure, PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, network.status(), network.toString());
        assertEquals(Classification.SUPERCRITICAL_FLUID, network.classification());
        assertEquals(PhaseKind.FLUID, network.phases().get(0).kind());
        // Above the 10 MPa envelope the open contract answers, and the network contract answers the same state graded
        // extrapolated, the envelope named (owner rule of 2026-10-16; it refused the state before).
        var beyond = open.tp(EquilibriumRequest.tp(150.0, 12.0e6, BINARY, new double[] {0.0, 1.0}, PhaseCompetition.FLUID_ONLY));
        assertEquals(Classification.SUPERCRITICAL_FLUID, beyond.classification());
        var extrapolated = service.tp(EquilibriumRequest.tp(150.0, 12.0e6, contract.components(), pure, PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, extrapolated.status(), extrapolated.toString());
        assertEquals(Classification.SUPERCRITICAL_FLUID, extrapolated.classification());
        assertEquals(CoverageGrade.EXTRAPOLATED, extrapolated.coverage().grade());
        assertTrue(extrapolated.coverage().evidence().contains("extrapolated past the package envelope"), extrapolated.coverage().evidence());
    }

    /** The 20-component network basis: Tia Juana light assay plus 5 mol% nitrogen, as in the stability test. */
    @Test
    void networkBasisSplitsAsTheStabilityTestSays() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        double[] z = PhaseTestSupport.crudeWithNitrogen(contract);
        assertEquals(20, z.length);
        var workspace = service.newWorkspace();
        var two = service.tp(EquilibriumRequest.tp(350.0, 0.5e6, contract.components(), z, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, two.status(), two.toString());
        assertEquals(Classification.VAPOR_LIQUID, two.classification());
        assertEquals(Verdict.UNSTABLE, two.diagnostics().feedVerdict());
        double defect = two.conservationDefect(z);
        assertTrue(defect < 1.0e-13, "defect " + defect);
        assertTrue(two.diagnostics().fugacityResidual() < 1.0e-9, "fugacity " + two.diagnostics().fugacityResidual());
        assertEquals(CoverageGrade.ESTIMATED_DECLARED_ERROR, two.coverage().grade());
        assertTrue(two.coverage().evidence().contains("solid phases not assessed"));
        // Aggregates are the phase sums.
        double h = 0.0;
        double v = 0.0;
        for (PhaseAmounts phase : two.phases()) {
            h += phase.total() * phase.state().molarEnthalpy();
            v += phase.total() * phase.state().molarVolume();
        }
        assertEquals(h, two.enthalpy(), 1.0e-12 * Math.abs(h));
        assertEquals(v, two.volume(), 1.0e-12 * v);
        assertEquals(PhaseTestSupport.sum(z), two.totalAmount(), 1.0e-13);

        var one = service.tp(EquilibriumRequest.tp(900.0, 0.1e6, contract.components(), z, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, one.status(), one.toString());
        assertEquals(Verdict.STABLE, one.diagnostics().feedVerdict());
        assertEquals(1, one.phases().size());
        assertEquals(0.0, one.conservationDefect(z));
        assertEquals(one.phases().get(0).state().molarEnthalpy(), one.molarEnthalpy(), 1.0e-12 * Math.abs(one.molarEnthalpy()));
        System.out.println("P2 TP network: 350 K 0.5 MPa " + two.classification() + " defect " + defect + " fugacity "
                + two.diagnostics().fugacityResidual() + " iterations " + two.diagnostics().flashIterations()
                + " kernel calls " + two.diagnostics().kernelEvaluations() + "; 900 K 0.1 MPa " + one.classification());
    }

    /**
     * A light gas (10 mol% each of C1 to nC5, 30 % nitrogen, 1e-4 of each cut) over the network range: nearly all vapour
     * with a trace liquid, so a component's liquid share is many orders below its vapour share. The trace must keep its
     * relative precision (the smaller share is computed directly, never as a difference), or the fugacities disagree.
     */
    @Test
    void traceSharesKeepTheirPrecision() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        double[] gas = new double[contract.components().size()];
        for (String id : List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) {
            gas[contract.index(id)] = 0.10;
        }
        gas[contract.index("Nitrogen")] = 0.30;
        for (int i = 0; i < gas.length; i++) if (contract.components().get(i).startsWith("crude_pc")) gas[i] = 1.0e-4;
        var workspace = service.newWorkspace();
        int split = 0;
        double worst = 0.0;
        for (double t = 300.0; t <= 900.0; t += 100.0) {
            for (double p : new double[] {0.5e6, 1.0e6, 2.0e6}) {
                var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), gas, PhaseCompetition.FLUID_ONLY), workspace);
                assertEquals(Status.CONVERGED, result.status(), t + " K " + p + " Pa: " + result);
                assertTrue(result.conservationDefect(gas) < 1.0e-13, t + " K " + p + " Pa");
                if (result.classification() != Classification.VAPOR_LIQUID) continue;
                split++;
                PhaseState vapour = result.phases().get(0).state();
                PhaseState liquid = result.phases().get(1).state();
                for (int i = 0; i < contract.components().size() - 1; i++) {
                    double lnL = Math.log(liquid.moleFraction(i)) + liquid.logFugacityCoefficient(i);
                    double lnV = Math.log(vapour.moleFraction(i)) + vapour.logFugacityCoefficient(i);
                    worst = Math.max(worst, Math.abs(lnL - lnV));
                }
            }
        }
        assertTrue(split >= 10, "two-phase states exercised: " + split);
        assertTrue(worst < 1.0e-9, "worst |ln f_L - ln f_V| " + worst);
        System.out.println("P2 TP light gas: " + split + " two-phase of 21 states, worst |ln f_L - ln f_V| " + worst);
    }

    @Test
    void contractRefusalsAreTypedAndCarryNoPhases() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        List<EquilibriumResult> refused = new ArrayList<>();

        // A crystal the package is not qualified for: refused, not answered fluid-only.
        var crystal = service.tp(EquilibriumRequest.tp(300.0, 1.0e6, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0),
                PhaseCompetition.withCrystals("co2_solid_i")));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, crystal.reason());
        refused.add(crystal);

        // Outside a data range nothing is refused (owner rule of 2026-10-16): methane below its 293.15 K ideal-gas fit and
        // above the 10 MPa envelope are answered, graded extrapolated with the range named.
        var cold = service.tp(EquilibriumRequest.tp(200.0, 1.0e6, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0, "Nitrogen", 1.0),
                PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, cold.status(), cold.toString());
        assertEquals(CoverageGrade.EXTRAPOLATED, cold.coverage().grade());
        assertTrue(cold.coverage().evidence().contains("the ideal gas of Methane 293.15"), cold.coverage().evidence());
        var inside = service.tp(EquilibriumRequest.tp(350.0, 3.0e6, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0), PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, inside.status(), inside.toString());
        assertNotEquals(CoverageGrade.EXTRAPOLATED, inside.coverage().grade());
        var dense = service.tp(EquilibriumRequest.tp(350.0, 12.0e6, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0), PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, dense.status(), dense.toString());
        assertEquals(CoverageGrade.EXTRAPOLATED, dense.coverage().grade());
        // PH and UV (P3 WP6b) run the same contract checks first.
        var ph = service.ph(EquilibriumRequest.ph(1.0e6, -1.0e4, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0),
                PhaseCompetition.withCrystals("co2_solid_i")));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, ph.reason());
        var uv = service.uv(EquilibriumRequest.uv(-1.0e4, 1.0e-3, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0),
                PhaseCompetition.withCrystals("co2_solid_i")));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, uv.reason());
        refused.add(ph);
        refused.add(uv);
        assertThrows(IllegalArgumentException.class, () -> service.tp(EquilibriumRequest.ph(1.0e6, 0.0, contract.components(), PhaseTestSupport.over(contract, "Methane", 1.0), PhaseCompetition.FLUID_ONLY)));

        for (EquilibriumResult result : refused) {
            assertTrue(result.phases().isEmpty(), result.toString());
            assertEquals(Classification.NONE, result.classification());
            assertFalse(result.solidsAssessed());
            assertEquals(contract.identity(), result.identity());
            assertEquals(CoverageGrade.UNAVAILABLE, result.coverage().grade(), result.toString());
            assertThrows(IllegalStateException.class, () -> result.conservationDefect(new double[contract.components().size()]));
            if (result.status() == Status.UNSUPPORTED) assertNotNull(result.reason()); else assertNull(result.reason());
        }
    }

    /** A package that declares a water-reactive species and a qualified crystal competition. */
    @Test
    void waterChemistryAndQualifiedCrystalsFollowTheContract() {
        var pkg = PhaseTestSupport.network();
        var methane = pkg.properties().get(pkg.components().indexOf("Methane")).pr();
        List<ThermoComponent> components = List.of(
                new ThermoComponent("Methane", methane.criticalTemperature(), methane.criticalPressure(), methane.acentricFactor(), 0.0160428),
                new ThermoComponent("Ammonia", 405.4, 11.333e6, 0.256, 0.017031),
                new ThermoComponent("Water", 647.096, 22.064e6, 0.3443, 0.018015));
        var evaluator = new CubicPhaseEvaluator("test:ammonia", components, new double[3][3], new double[3],
                List.of(StubIdealGas.constant(35.7, 100, 1000), StubIdealGas.constant(35.1, 100, 1000), StubIdealGas.constant(33.6, 100, 1000)));
        List<String> basis = List.of("Methane", "Ammonia", "Water");
        var identity = new ThermoIdentity("test:ammonia", evaluator.family(), ThermoIdentity.NO_SPINE,
                ThermoIdentity.WATER_IN_EOS_FORMULATION + ":test");
        var contract = new PhaseContract("test:ammonia", identity, basis, WaterParticipation.IN_EQUATION_OF_STATE, "Water",
                Set.of(PhaseCompetition.FLUID_ONLY, PhaseCompetition.withCrystals("ammonia_solid")), Set.of("Ammonia"),
                PhaseDomain.open("test:ammonia", basis),
                new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "test"));
        var service = new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT);
        var withWater = service.tp(EquilibriumRequest.tp(300.0, 1.0e6, basis, new double[] {1.0, 0.2, 1.0}, PhaseCompetition.FLUID_ONLY));
        assertEquals(UnsupportedReason.WATER_CHEMISTRY_NOT_MODELLED, withWater.reason());
        assertTrue(withWater.detail().contains("Ammonia"));
        var dry = service.tp(EquilibriumRequest.tp(300.0, 1.0e6, basis, new double[] {1.0, 0.2, 0.0}, PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, dry.status());
        assertEquals(CoverageGrade.RESEARCH_ONLY, dry.coverage().grade());
        var qualifiedCrystal = service.tp(EquilibriumRequest.tp(300.0, 1.0e6, basis, new double[] {1.0, 0.2, 0.0},
                PhaseCompetition.withCrystals("ammonia_solid")));
        assertEquals(UnsupportedReason.NOT_IMPLEMENTED, qualifiedCrystal.reason());
        assertTrue(qualifiedCrystal.detail().contains("P4"));
        var otherCrystal = service.tp(EquilibriumRequest.tp(300.0, 1.0e6, basis, new double[] {1.0, 0.2, 0.0},
                PhaseCompetition.withCrystals("ammonia_solid", "methane_solid")));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, otherCrystal.reason());

        // The contract refuses what no water participation can back.
        assertThrows(IllegalArgumentException.class, () -> new PhaseContract("x", identity, basis, WaterParticipation.NONE,
                "Water", Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), PhaseDomain.open("x", basis),
                new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "")));
        assertThrows(IllegalArgumentException.class, () -> new PhaseContract("x", identity, basis,
                WaterParticipation.IN_EQUATION_OF_STATE, "Water", Set.of(PhaseCompetition.FLUID_ONLY), Set.of("Water"),
                PhaseDomain.open("x", basis), new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "")));
        assertThrows(IllegalArgumentException.class, () -> new PhaseContract("x", identity, basis,
                WaterParticipation.IN_EQUATION_OF_STATE, "Water", Set.of(PhaseCompetition.FLUID_ONLY), Set.of(),
                PhaseDomain.open("x", List.of("Methane", "Water")), new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "")));
        assertThrows(IllegalArgumentException.class, () -> new PhaseContract("x", identity, basis,
                WaterParticipation.IN_EQUATION_OF_STATE, "Water", Set.of(PhaseCompetition.FLUID_ONLY), Set.of(),
                PhaseDomain.open("x", basis), new EquilibriumResult.Coverage(CoverageGrade.UNAVAILABLE, "")));
    }

    /** A reused workspace, in reverse and forward order, gives results bitwise equal to fresh workspaces. */
    @Test
    void repeatedCallsAreBitwiseIdentical() {
        var binaryEvaluator = PhaseTestSupport.methaneNitrogen();
        var binary = new FluidTpEquilibrium(PhaseTestSupport.openContract(binaryEvaluator), binaryEvaluator, FluidTpEquilibrium.Settings.DEFAULT);
        var network = PhaseTestSupport.networkService();
        double[] crude = PhaseTestSupport.crudeWithNitrogen(network.contract());
        double[] nitrogen = new double[network.contract().components().size()];
        nitrogen[network.contract().index("Nitrogen")] = 1.0;
        record Case(FluidTpEquilibrium service, EquilibriumRequest request) {}
        List<Case> cases = List.of(
                new Case(binary, EquilibriumRequest.tp(110.0, 0.5e6, BINARY, new double[] {0.5, 0.5}, PhaseCompetition.FLUID_ONLY)),
                new Case(binary, EquilibriumRequest.tp(120.0, 3.0e6, BINARY, new double[] {0.3, 0.7}, PhaseCompetition.FLUID_ONLY)),
                new Case(binary, EquilibriumRequest.tp(120.0, 2.5e6, BINARY, new double[] {0.7, 0.3}, PhaseCompetition.FLUID_ONLY)),
                new Case(network, EquilibriumRequest.tp(350.0, 0.5e6, network.contract().components(), crude, PhaseCompetition.FLUID_ONLY)),
                new Case(network, EquilibriumRequest.tp(900.0, 0.1e6, network.contract().components(), crude, PhaseCompetition.FLUID_ONLY)),
                new Case(network, EquilibriumRequest.tp(77.355, 90_000.0, network.contract().components(), nitrogen, PhaseCompetition.FLUID_ONLY)));
        List<EquilibriumResult> fresh = new ArrayList<>();
        for (Case c : cases) fresh.add(c.service().tp(c.request()));
        var reused = new java.util.IdentityHashMap<FluidTpEquilibrium, FluidTpEquilibrium.Workspace>();
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < cases.size(); k++) {
                int index = pass == 0 ? cases.size() - 1 - k : k;
                Case c = cases.get(index);
                var workspace = reused.computeIfAbsent(c.service(), FluidTpEquilibrium::newWorkspace);
                assertBitwiseEqual(fresh.get(index), c.service().tp(c.request(), workspace));
            }
        }
    }

    private static void assertBitwiseEqual(EquilibriumResult expected, EquilibriumResult actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.classification(), actual.classification());
        assertEquals(expected.phases().size(), actual.phases().size());
        for (int p = 0; p < expected.phases().size(); p++) {
            PhaseAmounts a = expected.phases().get(p);
            PhaseAmounts b = actual.phases().get(p);
            assertEquals(a.kind(), b.kind());
            assertArrayEquals(bits(a.amountsView()), bits(b.amountsView()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarVolume()), Double.doubleToRawLongBits(b.state().molarVolume()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarEnthalpy()), Double.doubleToRawLongBits(b.state().molarEnthalpy()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarEntropy()), Double.doubleToRawLongBits(b.state().molarEntropy()));
            assertArrayEquals(bits(a.state().logFugacityCoefficientsView()), bits(b.state().logFugacityCoefficientsView()));
        }
        var d = expected.diagnostics();
        var e = actual.diagnostics();
        assertEquals(d.flashIterations(), e.flashIterations());
        assertEquals(d.extrapolations(), e.extrapolations());
        assertEquals(d.rachfordRiceIterations(), e.rachfordRiceIterations());
        assertEquals(d.kernelEvaluations(), e.kernelEvaluations());
        assertEquals(d.derivativeEvaluations(), e.derivativeEvaluations());
        assertEquals(Double.doubleToRawLongBits(d.feedTangentPlaneDistance()), Double.doubleToRawLongBits(e.feedTangentPlaneDistance()));
        assertEquals(Double.doubleToRawLongBits(d.fugacityResidual()), Double.doubleToRawLongBits(e.fugacityResidual()));
    }

    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Double.doubleToRawLongBits(values[i]);
        return bits;
    }
}
