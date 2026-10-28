package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * P3 work package WP6a: the second-order finish on the states P2 left not converged, the critical band and merging,
 * phase labels from the phase identification parameter, and free water under the fluid network's rule. WP7d: an
 * unresolved stability test repeated on the longer budget (the G3 F5 state), a reused workspace across feeds with other
 * components, the band with D14's corner and D15's widened box 2, and the liquid-liquid label.
 */
class FluidTpEquilibriumP3Test {
    private static final List<String> BINARY = List.of("Methane", "Nitrogen");
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    /**
     * The 122 methane/nitrogen states the P2 flash left {@code NOT_CONVERGED} in its two binary scans
     * ({@code P2_PHASE_CONTRACTS.md} section 5.2): temperature K, pressure kPa, nitrogen mole fraction, and the P2
     * reason (b substitution budget, c collapse onto the feed, l lost split, s split outside 0 &lt; beta &lt; 1).
     */
    private static final String P2_FAILURES = ""
            + "140 4200 0.82 b;155 4700 0.58 b;155 4700 0.62 b;155 4800 0.62 b;160 4900 0.54 b;165 4900 0.42 b;165 4900 0.46 b;165 5000 0.46 b;"
            + "170 5000 0.38 b;175 4900 0.30 b;130 3580 0.95 c;135 3880 0.89 b;135 3920 0.89 c;140 4140 0.81 b;140 4140 0.83 b;140 4180 0.81 b;"
            + "140 4180 0.83 c;140 4200 0.83 c;145 4080 0.75 c;145 4360 0.73 b;145 4360 0.75 b;145 4360 0.77 b;145 4380 0.73 b;145 4380 0.75 b;"
            + "145 4380 0.77 b;145 4420 0.77 c;145 4440 0.75 b;150 4540 0.65 b;150 4540 0.67 b;150 4540 0.69 b;150 4540 0.71 b;150 4560 0.65 b;"
            + "150 4560 0.67 b;150 4560 0.69 b;150 4560 0.71 b;150 4580 0.65 b;150 4620 0.67 b;150 4620 0.69 b;150 4640 0.67 c;150 4640 0.69 b;"
            + "150 4660 0.69 c;155 4700 0.57 b;155 4700 0.59 b;155 4700 0.61 b;155 4700 0.63 b;155 4720 0.57 b;155 4720 0.59 b;155 4720 0.61 b;"
            + "155 4720 0.63 b;155 4740 0.59 b;155 4740 0.61 b;155 4740 0.63 b;155 4780 0.59 c;155 4800 0.59 b;155 4800 0.61 b;155 4800 0.63 b;"
            + "155 4820 0.61 b;155 4820 0.63 c;160 4820 0.49 b;160 4820 0.51 b;160 4820 0.53 b;160 4820 0.55 b;160 4840 0.51 b;160 4840 0.53 b;"
            + "160 4840 0.55 b;160 4860 0.51 b;160 4860 0.53 b;160 4860 0.55 b;160 4900 0.51 b;160 4900 0.53 b;160 4900 0.55 b;160 4920 0.53 c;"
            + "160 4920 0.55 b;160 4940 0.53 b;160 4940 0.55 b;165 4880 0.43 b;165 4880 0.45 b;165 4880 0.47 b;165 4900 0.43 b;165 4900 0.45 b;"
            + "165 4900 0.47 c;165 4920 0.43 b;165 4920 0.45 b;165 4920 0.47 b;165 4940 0.43 b;165 4940 0.45 b;165 4940 0.47 b;165 4980 0.45 b;"
            + "165 4980 0.47 s;165 5000 0.45 b;165 5000 0.47 b;170 4920 0.35 b;170 4920 0.37 b;170 4920 0.39 b;170 4940 0.35 c;170 4940 0.37 b;"
            + "170 4940 0.39 b;170 4960 0.35 b;170 4960 0.37 b;170 4960 0.39 b;170 5000 0.37 b;170 5000 0.39 b;170 5020 0.37 c;170 5020 0.39 b;"
            + "175 4900 0.27 b;175 4900 0.29 b;175 4900 0.31 b;175 4920 0.27 c;175 4920 0.29 b;175 4920 0.31 l;175 4940 0.31 b;175 4980 0.29 c;"
            + "175 4980 0.31 b;175 5000 0.29 b;175 5000 0.31 b;180 4840 0.19 b;180 4840 0.21 b;180 4860 0.19 b;180 4860 0.21 b;180 4900 0.21 b;"
            + "180 4920 0.21 c;185 4760 0.11 b;";

    record State(double temperature, double pressure, double nitrogen, char reason) {}

    static List<State> p2Failures() {
        List<State> states = new ArrayList<>();
        for (String entry : P2_FAILURES.split(";")) {
            String[] field = entry.trim().split(" ");
            states.add(new State(Double.parseDouble(field[0]), 1000.0 * Double.parseDouble(field[1]),
                    Double.parseDouble(field[2]), field[3].charAt(0)));
        }
        return states;
    }

    private static FluidTpEquilibrium binary() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        return new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
    }

    private static EquilibriumRequest request(State s) {
        return EquilibriumRequest.tp(s.temperature(), s.pressure(), BINARY, new double[] {1.0 - s.nitrogen(), s.nitrogen()},
                PhaseCompetition.FLUID_ONLY);
    }

    private static FluidTpEquilibrium.Settings settings(int substitutionLimit, int newtonIterations, double mergeTieLine,
                                                        double mergeVolumeGap) {
        return new FluidTpEquilibrium.Settings(1.0e-10, 1000, 5, 1.0e-9, 1.0e-8, TangentPlaneStability.Settings.DEFAULT,
                substitutionLimit, newtonIterations, mergeTieLine, mergeVolumeGap, 100, 1.0e-11);
    }

    /**
     * Every state P2 left typed-not-converged next to the methane/nitrogen critical locus now converges through the
     * Newton finish to two phases that conserve each component, have equal fugacities and chemical potentials, and are
     * both stable by the stability test on an independently built kernel; every one of them lies inside the declared
     * critical band (a tie line below 0.1) and is graded research-only. None remains typed inside the band.
     */
    @Test
    void theP2NearCriticalFailuresConvergeInsideTheCriticalBand() {
        var service = binary();
        var workspace = service.newWorkspace();
        var independent = new TangentPlaneStability(PhaseTestSupport.classicalKernel("Methane", "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        List<State> states = p2Failures();
        assertEquals(122, states.size());
        int[] reasons = new int[128];
        int newtonMax = 0;
        int newtonSum = 0;
        int kernelMax = 0;
        double tieMin = Double.POSITIVE_INFINITY;
        double tieMax = 0.0;
        double worstFugacity = 0.0;
        for (State s : states) {
            reasons[s.reason()]++;
            double[] z = {1.0 - s.nitrogen(), s.nitrogen()};
            var result = service.tp(request(s), workspace);
            String at = s.toString();
            assertEquals(Status.CONVERGED, result.status(), at + ": " + result);
            assertEquals(Classification.VAPOR_LIQUID, result.classification(), at);
            var d = result.diagnostics();
            assertTrue(d.newtonIterations() > 0, at + ": the Newton finish converged it");
            assertTrue(d.criticalBand(), at);
            assertEquals(CoverageGrade.RESEARCH_ONLY, result.coverage().grade(), at);
            assertTrue(d.tieLine() < FluidTpEquilibrium.BAND_TIE_LINE, at + " tie " + d.tieLine());
            assertTrue(result.conservationDefect(z) < 1.0e-13, at);
            assertTrue(d.fugacityResidual() < 1.0e-9, at + " fugacity " + d.fugacityResidual());
            PhaseState vapour = result.phases().get(0).state();
            PhaseState liquid = result.phases().get(1).state();
            assertTrue(liquid.molarVolume() < vapour.molarVolume(), at);
            for (int i = 0; i < 2; i++) {
                assertEquals(liquid.chemicalPotential(i), vapour.chemicalPotential(i), 1.0e-8 * R * s.temperature(), at);
            }
            for (PhaseAmounts phase : result.phases()) {
                assertEquals(TangentPlaneStability.Verdict.STABLE,
                        independent.test(s.temperature(), s.pressure(), phase.amounts()).verdict(), at);
            }
            newtonMax = Math.max(newtonMax, d.newtonIterations());
            newtonSum += d.newtonIterations();
            kernelMax = Math.max(kernelMax, d.kernelEvaluations());
            tieMin = Math.min(tieMin, d.tieLine());
            tieMax = Math.max(tieMax, d.tieLine());
            worstFugacity = Math.max(worstFugacity, d.fugacityResidual());
        }
        assertEquals(103, reasons['b']);
        assertEquals(17, reasons['c']);
        assertEquals(1, reasons['l']);
        assertEquals(1, reasons['s']);
        System.out.println("P3 Newton finish: 122/122 P2 failures converged (103 budget, 17 collapse, 1 lost split, 1 beta); "
                + "Newton steps mean " + (double) newtonSum / states.size() + " max " + newtonMax + "; kernel calls max "
                + kernelMax + "; tie line " + tieMin + ".." + tieMax + "; worst fugacity residual " + worstFugacity);
    }

    /**
     * A failure is typed by where it happens: inside the critical band its detail starts with {@code CRITICAL_BAND} and
     * its diagnostics say so (the network holds the island); outside it does not. Forced here by allowing substitution
     * one step and the Newton finish one step.
     */
    @Test
    void aFailureInsideTheBandIsTypedCriticalBand() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, settings(1, 1, 1.0e-8, 1.0e-2), null);
        var nearCritical = new State(150.0, 4.56e6, 0.67, 'b');
        var ordinary = new State(110.0, 0.5e6, 0.5, 'x');
        var inside = service.tp(request(nearCritical));
        assertEquals(Status.NOT_CONVERGED, inside.status(), inside.toString());
        assertTrue(inside.detail().startsWith(EquilibriumResult.CRITICAL_BAND), inside.detail());
        assertTrue(inside.diagnostics().criticalBand());
        assertTrue(inside.phases().isEmpty());
        assertEquals(CoverageGrade.UNAVAILABLE, inside.coverage().grade());
        var outside = service.tp(request(ordinary));
        assertEquals(Status.NOT_CONVERGED, outside.status(), outside.toString());
        assertFalse(outside.detail().startsWith(EquilibriumResult.CRITICAL_BAND), outside.detail());
        assertFalse(outside.diagnostics().criticalBand());
        // The default service converges both; the ordinary one is outside the band and keeps the contract's grade.
        assertEquals(Status.CONVERGED, binary().tp(request(nearCritical)).status());
        var converged = binary().tp(request(ordinary));
        assertEquals(Status.CONVERGED, converged.status());
        assertFalse(converged.diagnostics().criticalBand());
    }

    /** Fresh workspaces against one reused in reverse, then forward order: the Newton finish and free water are bitwise. */
    @Test
    void theNewtonFinishAndFreeWaterAreBitwiseRepeatable() {
        var service = binary();
        List<EquilibriumRequest> requests = new ArrayList<>();
        List<FluidTpEquilibrium> services = new ArrayList<>();
        List<State> failures = p2Failures();
        for (int k = 0; k < failures.size(); k += 10) {
            requests.add(request(failures.get(k)));
            services.add(service);
        }
        var wet = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(FluidTestSupport.networkModel()));
        double[] crude = PhaseTestSupport.crudeWithNitrogen(wet.contract());
        for (double[] state : new double[][] {{350.0, 1.0e6, 1.0}, {350.0, 1.0e6, 1.0e-3}, {700.0, 1.3e6, 1.0e-3}}) {
            double[] feed = crude.clone();
            feed[wet.contract().waterIndex()] = state[2];
            requests.add(EquilibriumRequest.tp(state[0], state[1], wet.contract().components(), feed, PhaseCompetition.FLUID_ONLY));
            services.add(wet);
        }
        List<EquilibriumResult> fresh = new ArrayList<>();
        for (int k = 0; k < requests.size(); k++) fresh.add(services.get(k).tp(requests.get(k)));
        var reused = new java.util.IdentityHashMap<FluidTpEquilibrium, FluidTpEquilibrium.Workspace>();
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < requests.size(); j++) {
                int k = pass == 0 ? requests.size() - 1 - j : j;
                var workspace = reused.computeIfAbsent(services.get(k), FluidTpEquilibrium::newWorkspace);
                assertBitwiseEqual(fresh.get(k), services.get(k).tp(requests.get(k), workspace));
            }
        }
        for (EquilibriumResult result : fresh) assertEquals(Status.CONVERGED, result.status(), result.toString());
    }

    /**
     * Merging needs both thresholds: a split whose tie line and molar volumes are both within them is one fluid
     * ({@code SINGLE_FLUID} labelled {@code FLUID}, in the band, its detail saying it was merged, the feed verdict kept
     * unstable); the same split with either threshold unmet stays two phases. Forced here with widened thresholds, since
     * no scanned split came within the default 1e-8 tie line.
     */
    @Test
    void mergingNeedsBothTheTieLineAndTheVolumeThreshold() {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var contract = PhaseTestSupport.openContract(evaluator);
        var state = new State(150.0, 4.56e6, 0.67, 'b');
        double[] z = {1.0 - state.nitrogen(), state.nitrogen()};
        var merged = new FluidTpEquilibrium(contract, evaluator, settings(50, 50, 1.0, 10.0), null).tp(request(state));
        assertEquals(Status.CONVERGED, merged.status(), merged.toString());
        assertEquals(Classification.SINGLE_FLUID, merged.classification());
        assertEquals(1, merged.phases().size());
        assertEquals(PhaseKind.FLUID, merged.phases().get(0).kind());
        assertTrue(merged.detail().contains("merged near-critical split"), merged.detail());
        assertTrue(merged.diagnostics().criticalBand());
        assertEquals(TangentPlaneStability.Verdict.UNSTABLE, merged.diagnostics().feedVerdict(), "the test's verdict is kept");
        assertEquals(CoverageGrade.RESEARCH_ONLY, merged.coverage().grade());
        assertEquals(0.0, merged.conservationDefect(z));
        var noVolume = new FluidTpEquilibrium(contract, evaluator, settings(50, 50, 1.0, 1.0e-6), null).tp(request(state));
        assertEquals(Classification.VAPOR_LIQUID, noVolume.classification());
        var noTieLine = new FluidTpEquilibrium(contract, evaluator, settings(50, 50, 1.0e-8, 10.0), null).tp(request(state));
        assertEquals(Classification.VAPOR_LIQUID, noTieLine.classification());
    }

    /**
     * Single phases with one physical root carry the label of their phase identification parameter (liquid-like above
     * one and above the root's {@code Z}, P3 WP7c); a pure component above its critical temperature and pressure is
     * {@code SUPERCRITICAL_FLUID} labelled {@code FLUID}, whose parameter still says which side it is on. The states:
     * nitrogen liquid at 77 K and 0.5 MPa and vapour at 300 K; the supercritical and dense states of the P1 stability
     * test (c), unchanged since WP6a; and (WP7c) hot nitrogen, whose parameter is just above one while its {@code Z} is
     * above that: a vapour, not the liquid the bare {@code PIP > 1} made it.
     */
    @Test
    void singlePhasesAreLabelledByThePhaseIdentificationParameter() {
        var service = binary();
        var kernel = service.evaluator().kernel();
        record Case(double t, double p, double methane, Classification classification, PhaseKind label, boolean liquidLike,
                    boolean parameterAboveOne) {}
        List<Case> cases = List.of(
                new Case(77.0, 0.5e6, 0.0, Classification.SINGLE_LIQUID, PhaseKind.LIQUID, true, true),
                new Case(300.0, 0.1e6, 0.0, Classification.SINGLE_FLUID, PhaseKind.VAPOR, false, false),
                new Case(130.0, 6.0e6, 0.0, Classification.SUPERCRITICAL_FLUID, PhaseKind.FLUID, true, true),
                new Case(150.0, 10.0e6, 0.0, Classification.SUPERCRITICAL_FLUID, PhaseKind.FLUID, true, true),
                new Case(250.0, 10.0e6, 1.0, Classification.SUPERCRITICAL_FLUID, PhaseKind.FLUID, false, false),
                new Case(130.0, 6.0e6, 0.5, Classification.SINGLE_FLUID, PhaseKind.LIQUID, true, true),
                new Case(150.0, 10.0e6, 0.5, Classification.SINGLE_FLUID, PhaseKind.LIQUID, true, true),
                new Case(250.0, 10.0e6, 0.5, Classification.SINGLE_FLUID, PhaseKind.VAPOR, false, false),
                new Case(400.0, 3.0e6, 0.5, Classification.SINGLE_FLUID, PhaseKind.VAPOR, false, false),
                // WP7c: hot nitrogen, PIP just above one, Z above PIP.
                new Case(700.0, 0.1e6, 0.0, Classification.SINGLE_FLUID, PhaseKind.VAPOR, false, true),
                new Case(700.0, 0.5e6, 0.0, Classification.SINGLE_FLUID, PhaseKind.VAPOR, false, true),
                new Case(700.0, 5.0e6, 0.0, Classification.SUPERCRITICAL_FLUID, PhaseKind.FLUID, false, true));
        var workspace = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        StringBuilder summary = new StringBuilder("P3 phase identification:");
        for (Case c : cases) {
            double[] z = {c.methane(), 1.0 - c.methane()};
            var result = service.tp(EquilibriumRequest.tp(c.t(), c.p(), BINARY, z, PhaseCompetition.FLUID_ONLY));
            String at = c.toString();
            assertEquals(c.classification(), result.classification(), at);
            assertEquals(c.label(), result.phases().get(0).kind(), at);
            Root root = result.phases().get(0).state().root() == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
            kernel.evaluate(c.t(), c.p(), z, root, workspace, evaluation);
            double parameter = PhaseIdentification.parameter(evaluation, c.t(), c.p());
            double compressibility = evaluation.compressibility();
            assertEquals(c.parameterAboveOne(), parameter > 1.0, at + " PIP " + parameter);
            assertEquals(c.liquidLike(), PhaseIdentification.liquidLike(parameter, compressibility), at + " PIP " + parameter + " Z " + compressibility);
            assertEquals(c.liquidLike(), PhaseIdentification.liquidLike(evaluation, c.t(), c.p()), at);
            assertEquals(!c.liquidLike(), PhaseIdentification.vapourLike(evaluation, c.t(), c.p()), at);
            if (c.liquidLike()) assertTrue(compressibility < 1.0, at + ": every liquid-like state of the table has Z < 1, Z " + compressibility);
            summary.append(String.format(" %s K %s MPa x_CH4 %s: %s %s PIP %.6f Z %.6f;", c.t(), c.p() / 1e6, c.methane(),
                    result.classification(), result.phases().get(0).kind(), parameter, compressibility));
        }
        System.out.println(summary);
    }

    /**
     * WP7c, the hot-gas grid: nitrogen and methane on the network contract (300 to 900 K, their domain) and on the pilot
     * contract (nitrogen 300 to 1200 K), ethane and carbon dioxide on the pilot contract, methane, ethane and carbon
     * dioxide from the first 50 K step above their critical temperatures to 1200 K, each at 0.1, 0.5, 1, 2, 5 and 10 MPa
     * up to the contract's 10 MPa envelope. Every state converges to one phase, never labelled {@code LIQUID}
     * ({@code VAPOR} below the critical pressure, {@code FLUID} above it), and is vapour-like by the rule except where it
     * is denser than the cubic's critical volume ({@code v < 3.95 b}): only methane at 200 K and ethane at 350 K, both
     * at 10 MPa, dense supercritical fluids that are liquid-like by their parameter as nitrogen at 130 K and 6 MPa is.
     * Before WP7c the bare {@code PIP > 1} labelled 95 of these states {@code LIQUID} (nitrogen from 650 K, methane on
     * the pilot kernel from 1000 K, carbon dioxide at 1150 and 1200 K). Each isotherm's parameter and {@code Z} are
     * printed, and each species' largest parameter.
     */
    @Test
    void hotGasesAreVapourLikeOverTheGrid() {
        var catalog = com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
        String pilotId = "icheme:pilot_cryogenic";
        var network = PhaseTestSupport.networkService();
        var pilot = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, pilotId), CubicPhaseEvaluator.forPackage(catalog, pilotId), FluidTpEquilibrium.Settings.DEFAULT, null);
        record Grid(String name, FluidTpEquilibrium service, String species, double from, double to) {}
        List<Grid> grids = List.of(
                new Grid("network", network, "Nitrogen", 300.0, 900.0),
                new Grid("network", network, "Methane", 300.0, 900.0),
                new Grid("pilot", pilot, "Nitrogen", 300.0, 1200.0),
                new Grid("pilot", pilot, "Methane", 200.0, 1200.0),
                new Grid("pilot", pilot, "Ethane", 350.0, 1200.0),
                new Grid("pilot", pilot, "CarbonDioxide", 350.0, 1200.0));
        double[] megapascals = {0.1, 0.5, 1.0, 2.0, 5.0, 10.0};
        double criticalVolumeRatio = 0.30740131 / 0.07779607;
        List<String> dense = new ArrayList<>();
        int states = 0;
        int aboveOne = 0;
        for (Grid g : grids) {
            var contract = g.service().contract();
            var kernel = g.service().evaluator().kernel();
            var workspace = kernel.newWorkspace();
            var evaluation = kernel.newEvaluation();
            var tc = g.service().evaluator().component(g.service().evaluator().components().indexOf(g.species())).criticalTemperatureKelvin();
            double ceiling = contract.domain().envelope()[3];
            assertTrue(g.from() > tc, g + " starts above Tc " + tc);
            double worstParameter = Double.NEGATIVE_INFINITY;
            double worstZ = Double.NaN;
            String worstAt = "";
            for (double t = g.from(); t <= g.to() + 1.0e-9; t += 50.0) {
                StringBuilder isotherm = new StringBuilder(String.format("WP7c grid %s %s %.0f K:", g.name(), g.species(), t));
                for (double megapascal : megapascals) {
                    double p = megapascal * 1.0e6;
                    if (p > ceiling) continue;
                    double[] z = new double[contract.components().size()];
                    z[contract.index(g.species())] = 1.0;
                    var result = g.service().tp(EquilibriumRequest.tp(t, p, contract.components(), z, PhaseCompetition.FLUID_ONLY));
                    String at = g.name() + " " + g.species() + " " + t + " K " + megapascal + " MPa";
                    assertEquals(Status.CONVERGED, result.status(), at + ": " + result.detail());
                    assertEquals(1, result.phases().size(), at);
                    var phase = result.phases().get(0);
                    assertEquals(result.classification() == Classification.SUPERCRITICAL_FLUID ? PhaseKind.FLUID : PhaseKind.VAPOR, phase.kind(), at);
                    assertTrue(result.classification() == Classification.SUPERCRITICAL_FLUID || result.classification() == Classification.SINGLE_FLUID, at);
                    kernel.evaluate(t, p, phase.state().compositionView(), phase.state().root() == Root.VAPOR ? Root.VAPOR : Root.LIQUID,
                            workspace, evaluation);
                    assertEquals(1, evaluation.physicalRootCount(), at);
                    double parameter = PhaseIdentification.parameter(evaluation, t, p);
                    double compressibility = evaluation.compressibility();
                    double volumeRatio = compressibility * R * t / p / evaluation.bMix();
                    boolean vapourLike = PhaseIdentification.vapourLike(parameter, compressibility);
                    assertEquals(volumeRatio > criticalVolumeRatio, vapourLike, at + ": PIP " + parameter + " Z " + compressibility + " v/b " + volumeRatio);
                    if (!vapourLike) dense.add(g.species() + " " + t + " K " + megapascal + " MPa");
                    if (parameter > 1.0 && vapourLike) {
                        aboveOne++;
                        assertTrue(compressibility > parameter, at + ": a hot gas with PIP above one has Z above PIP");
                    }
                    if (parameter > worstParameter && vapourLike) {
                        worstParameter = parameter;
                        worstZ = compressibility;
                        worstAt = t + " K " + megapascal + " MPa";
                    }
                    states++;
                    isotherm.append(String.format(" %s MPa %.6f/%.6f", megapascal, parameter, compressibility));
                }
                System.out.println(isotherm + " (PIP/Z)");
            }
            System.out.printf("WP7c grid %s %s %.0f-%.0f K: largest PIP of a vapour-like state %.6f (Z %.6f) at %s%n", g.name(), g.species(),
                    g.from(), g.to(), worstParameter, worstZ, worstAt);
        }
        assertEquals(List.of("Methane 200.0 K 10.0 MPa", "Ethane 350.0 K 10.0 MPa"), dense);
        System.out.printf("WP7c grid: %d states, %d vapour-like with PIP above one (Z above PIP in each), liquid-like only %s%n", states,
                aboveOne, dense);
        assertTrue(aboveOne > 100, "the grid reaches the hot-gas regime: " + aboveOne);
    }

    /**
     * The WP7c label rule, {@code PIP > max(1, Z)}: the ideal gas (both exactly one) and an undefined parameter are not
     * liquid-like; a hot gas with both just above one and {@code PIP < Z} is vapour-like; a liquid with {@code Z < 1} and
     * a compressed liquid whose {@code Z} passed one are liquid-like. Its physics on the cubic itself: in the dilute limit
     * {@code PIP - Z = -T (dB/dT)/v} with {@code B = b - a/(R T)}, checked at 700 K and 1 kPa for nitrogen, where the
     * parameter is above one; and the network's heaviest cut, {@code crude_pc12} at 293.15 K and 2 MPa, a one-root
     * compressed liquid with {@code Z > 1}, which the engine labels {@code LIQUID} (a bare {@code Z < 1} guard would not).
     */
    @Test
    void theLabelRuleSeparatesAHotGasFromACompressedLiquid() {
        assertFalse(PhaseIdentification.liquidLike(1.0, 1.0) || !PhaseIdentification.vapourLike(1.0, 1.0));
        assertFalse(PhaseIdentification.liquidLike(1.000079, 1.000338), "hot N2 at 700 K, 0.1 MPa");
        assertTrue(PhaseIdentification.vapourLike(1.000079, 1.000338));
        assertTrue(PhaseIdentification.liquidLike(4.97, 0.39) && !PhaseIdentification.vapourLike(4.97, 0.39));
        assertTrue(PhaseIdentification.liquidLike(92.0, 1.15) && !PhaseIdentification.vapourLike(92.0, 1.15));
        assertTrue(PhaseIdentification.vapourLike(0.72, 0.67) && !PhaseIdentification.liquidLike(0.72, 0.67));
        assertFalse(PhaseIdentification.liquidLike(Double.NaN, 0.5) || PhaseIdentification.vapourLike(Double.NaN, 0.5));

        // Dilute limit on the cubic: PIP - 1 = (B - T B')/v, Z - 1 = B/v, so PIP - Z = -T B'/v.
        var kernel = PhaseTestSupport.classicalKernel("Nitrogen");
        var workspace = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        double t = 700.0;
        double p = 1.0e3;
        kernel.evaluate(t, p, new double[] {1.0}, Root.VAPOR, workspace, evaluation);
        double v = evaluation.compressibility() * R * t / p;
        double parameter = PhaseIdentification.parameter(evaluation, t, p);
        double slope = evaluation.aMix() / (R * t * t) - evaluation.daMixDt() / (R * t);
        double second = evaluation.bMix() - evaluation.aMix() / (R * t);
        assertTrue(parameter > 1.0 && second - t * slope > 0.0, "hot nitrogen: B - T dB/dT > 0, PIP " + parameter);
        assertTrue(second > 0.0 && slope > 0.0, "B > 0 (above the Boyle temperature) and dB/dT > 0");
        assertEquals(-t * slope / v, parameter - evaluation.compressibility(), 1.0e-3 * t * slope / v, "PIP - Z = -T B'/v");
        assertEquals((second - t * slope) / v, parameter - 1.0, 1.0e-3 * Math.abs(second - t * slope) / v, "PIP - 1 = (B - T B')/v");
        assertTrue(PhaseIdentification.vapourLike(evaluation, t, p));

        // The compressed heavy liquid of the network package.
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        double[] amounts = new double[contract.components().size()];
        amounts[contract.index("crude_pc12")] = 1.0;
        var result = service.tp(EquilibriumRequest.tp(293.15, 2.0e6, contract.components(), amounts, PhaseCompetition.FLUID_ONLY));
        assertEquals(Classification.SINGLE_FLUID, result.classification());
        assertEquals(PhaseKind.LIQUID, result.phases().get(0).kind());
        var heavy = service.evaluator().kernel();
        var heavyEvaluation = heavy.newEvaluation();
        heavy.evaluate(293.15, 2.0e6, result.phases().get(0).state().compositionView(), Root.LIQUID, heavy.newWorkspace(), heavyEvaluation);
        double heavyParameter = PhaseIdentification.parameter(heavyEvaluation, 293.15, 2.0e6);
        double heavyZ = heavyEvaluation.compressibility();
        double heavyVolume = heavyZ * R * 293.15 / 2.0e6;
        assertEquals(1, heavyEvaluation.physicalRootCount());
        assertTrue(heavyZ > 1.0 && heavyParameter > 50.0 && heavyVolume < 1.1 * heavyEvaluation.bMix(),
                "crude_pc12 at 293.15 K, 2 MPa: Z " + heavyZ + ", PIP " + heavyParameter + ", v/b " + heavyVolume / heavyEvaluation.bMix());
        System.out.printf("WP7c rule: N2 700 K 1 kPa PIP-1 %.3e, Z-1 %.3e, PIP-Z %.3e (-T B'/v %.3e); crude_pc12 293.15 K 2 MPa Z %.4f PIP %.2f v/b %.4f: %s%n",
                parameter - 1.0, evaluation.compressibility() - 1.0, parameter - evaluation.compressibility(), -t * slope / v, heavyZ,
                heavyParameter, heavyVolume / heavyEvaluation.bMix(), result.phases().get(0).kind());
    }

    /**
     * The closed form against central differences of the cubic {@code P(T, v) = R T/(v - b) - a(T)/D} with the
     * mixture's {@code a(T)} from the kernel itself, and the ideal-gas limit {@code PIP -> 1} from below.
     */
    @Test
    void thePhaseIdentificationParameterIsTheCubicsOwnDerivativeRatio() {
        var kernel = PhaseTestSupport.classicalKernel("Methane", "Nitrogen");
        var workspace = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        double[][] states = {{77.0, 0.5e6, 0.0}, {110.0, 2.0e6, 0.9}, {130.0, 6.0e6, 0.5}, {250.0, 10.0e6, 0.5}, {300.0, 0.1e6, 0.3}};
        for (double[] s : states) {
            double t = s[0];
            double p = s[1];
            double[] z = {s[2], 1.0 - s[2]};
            for (Root root : Root.values()) {
                double h = 1.0e-3 * t;
                kernel.evaluate(t + h, p, z, root, workspace, evaluation);
                double aUp = evaluation.aMix();
                kernel.evaluate(t - h, p, z, root, workspace, evaluation);
                double aDown = evaluation.aMix();
                kernel.evaluate(t, p, z, root, workspace, evaluation);
                double a = evaluation.aMix();
                double b = evaluation.bMix();
                double v = evaluation.compressibility() * R * t / p;
                double closed = PhaseIdentification.parameter(evaluation, t, p);
                double dv = 1.0e-4 * (v - b);
                DoubleBinaryOperator pressure = (temperature, volume) -> {
                    double attraction = temperature == t ? a : temperature > t ? aUp : aDown;
                    return R * temperature / (volume - b) - attraction / (volume * volume + 2.0 * b * volume - b * b);
                };
                double pv = (pressure.applyAsDouble(t, v + dv) - pressure.applyAsDouble(t, v - dv)) / (2.0 * dv);
                double pvv = (pressure.applyAsDouble(t, v + dv) - 2.0 * pressure.applyAsDouble(t, v)
                        + pressure.applyAsDouble(t, v - dv)) / (dv * dv);
                double pt = (pressure.applyAsDouble(t + h, v) - pressure.applyAsDouble(t - h, v)) / (2.0 * h);
                double ptv = (pressure.applyAsDouble(t + h, v + dv) - pressure.applyAsDouble(t + h, v - dv)
                        - pressure.applyAsDouble(t - h, v + dv) + pressure.applyAsDouble(t - h, v - dv)) / (4.0 * h * dv);
                double differenced = v * (ptv / pt - pvv / pv);
                assertEquals(differenced, closed, 1.0e-4 * Math.max(1.0, Math.abs(closed)), Arrays.toString(s) + " " + root);
            }
        }
        kernel.evaluate(300.0, 1.0e-3, new double[] {0.5, 0.5}, Root.VAPOR, workspace, evaluation);
        double dilute = PhaseIdentification.parameter(evaluation, 300.0, 1.0e-3);
        assertTrue(dilute < 1.0 && dilute > 1.0 - 1.0e-9, "ideal-gas limit " + dilute);
        assertTrue(PhaseIdentification.vapourLike(1.0) && !PhaseIdentification.liquidLike(1.0));
        assertFalse(PhaseIdentification.vapourLike(Double.NaN) || PhaseIdentification.liquidLike(Double.NaN));
    }

    /**
     * The network's free-water rule on the network contract: water above its saturation pressure with enough of it is
     * free liquid water beside a gas whose steam sits at the saturation pressure and whose hydrocarbons sit at
     * {@code P - p_sat}; too little water is all steam with {@code pc + p_w = P}; pure water is liquid above and steam
     * below its saturation pressure; a steam partial pressure above the approximation's limit is out of domain, as the
     * network refuses it. Every component, water included, conserves; water's chemical potential in the gas equals the
     * free water's, and each hydrocarbon's in the gas equals the hydrocarbon liquid's.
     */
    @Test
    void freeWaterFollowsTheNetworkRule() {
        FluidThermodynamics fluid = FluidTestSupport.networkModel();
        var service = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(fluid));
        var contract = service.contract();
        int water = contract.waterIndex();
        double[] crude = PhaseTestSupport.crudeWithNitrogen(contract);
        double t = 350.0;
        double p = 1.0e6;
        double saturation = fluid.saturationPressure(t);
        var workspace = service.newWorkspace();

        double[] wet = crude.clone();
        wet[water] = 1.0;
        var three = service.tp(EquilibriumRequest.tp(t, p, contract.components(), wet, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Status.CONVERGED, three.status(), three.toString());
        assertEquals(Classification.FREE_WATER, three.classification());
        assertEquals(List.of(PhaseKind.VAPOR, PhaseKind.LIQUID, PhaseKind.FREE_WATER),
                three.phases().stream().map(PhaseAmounts::kind).toList());
        var split = three.freeWater();
        assertNotNull(split);
        assertEquals(Classification.VAPOR_LIQUID, split.hydrocarbon());
        assertEquals(saturation, split.saturationPressure());
        assertEquals(p - saturation, split.hydrocarbonPressure());
        assertEquals(saturation, split.waterPartialPressure(), 1.0e-12 * saturation);
        assertEquals(1.0, split.liquidWater() + split.waterVapor(), 1.0e-15);
        assertTrue(three.conservationDefect(wet) < 1.0e-13, "defect " + three.conservationDefect(wet));
        PhaseState gas = three.phases().get(0).state();
        PhaseState liquid = three.phases().get(1).state();
        PhaseState free = three.phases().get(2).state();
        assertEquals(contract.components().size(), gas.componentCount());
        assertEquals(1, free.componentCount());
        assertEquals(PhaseKind.FREE_WATER, free.kind());
        double rt = R * t;
        assertEquals(free.chemicalPotential(0), gas.chemicalPotential(water), 1.0e-9 * rt);
        for (int i = 0; i < water; i++) {
            assertEquals(liquid.chemicalPotential(i), gas.chemicalPotential(i), 1.0e-8 * rt, contract.components().get(i));
        }
        double volume = 0.0;
        double enthalpy = 0.0;
        for (PhaseAmounts phase : three.phases()) {
            volume += phase.total() * phase.state().molarVolume();
            enthalpy += phase.total() * phase.state().molarEnthalpy();
        }
        assertEquals(volume, three.volume(), 1.0e-12 * volume);
        assertEquals(enthalpy, three.enthalpy(), 1.0e-9 * Math.abs(enthalpy));
        assertEquals(fluid.waterLiquid(t, p).molarVolume(), free.molarVolume());

        double[] damp = crude.clone();
        damp[water] = 1.0e-3;
        var steamOnly = service.tp(EquilibriumRequest.tp(t, p, contract.components(), damp, PhaseCompetition.FLUID_ONLY), workspace);
        assertEquals(Classification.FREE_WATER, steamOnly.classification(), steamOnly.toString());
        assertEquals(List.of(PhaseKind.VAPOR, PhaseKind.LIQUID), steamOnly.phases().stream().map(PhaseAmounts::kind).toList());
        assertEquals(0.0, steamOnly.freeWater().liquidWater());
        assertEquals(1.0e-3, steamOnly.freeWater().waterVapor());
        assertTrue(steamOnly.freeWater().waterPartialPressure() < saturation);
        assertEquals(p, steamOnly.freeWater().hydrocarbonPressure() + steamOnly.freeWater().waterPartialPressure(),
                FluidTpEquilibrium.PARTIAL_PRESSURE_ACCEPTANCE * p);
        assertTrue(steamOnly.conservationDefect(damp) < 1.0e-13);

        double[] pure = new double[contract.components().size()];
        pure[water] = 2.0;
        double cold = 320.0;
        double coldSaturation = fluid.saturationPressure(cold);
        var liquidWater = service.tp(EquilibriumRequest.tp(cold, 2.0 * coldSaturation, contract.components(), pure,
                PhaseCompetition.FLUID_ONLY));
        assertEquals(List.of(PhaseKind.FREE_WATER), liquidWater.phases().stream().map(PhaseAmounts::kind).toList());
        var steam = service.tp(EquilibriumRequest.tp(cold, 0.5 * coldSaturation, contract.components(), pure,
                PhaseCompetition.FLUID_ONLY));
        assertEquals(List.of(PhaseKind.VAPOR), steam.phases().stream().map(PhaseAmounts::kind).toList());
        assertEquals(SteamVirial.molarVolume(cold, 0.5 * coldSaturation), steam.phases().get(0).state().molarVolume(),
                1.0e-12 * R * cold / coldSaturation, "the virial steam at the state pressure");

        double[] hot = crude.clone();
        hot[water] = 10.0;
        // Steam past the virial steam's qualified partial pressure (p_sat 2.64 MPa at 500 K, the limit 2.2 MPa): answered,
        // graded extrapolated with the limit named (owner rule of 2026-10-16; refused before), and the network carries the
        // same state.
        var wetHot = service.tp(EquilibriumRequest.tp(500.0, 4.0e6, contract.components(), hot, PhaseCompetition.FLUID_ONLY));
        assertEquals(Status.CONVERGED, wetHot.status(), wetHot.toString());
        assertEquals(CoverageGrade.EXTRAPOLATED, wetHot.coverage().grade());
        assertTrue(wetHot.coverage().evidence().contains("the steam's qualified partial pressure"), wetHot.coverage().evidence());
        assertDoesNotThrow(() -> fluid.flashTP(500.0, 4.0e6, hot.clone(), () -> { }), "the network carries the same steam partial pressure");

        // The model must be the one the contract identity names.
        FreeWaterModel inner = PhaseTestSupport.networkFreeWater(fluid);
        FreeWaterModel other = new FreeWaterModel() {
            @Override public String revision() { return "another-water"; }
            @Override public double saturationPressure(double temperature) { return inner.saturationPressure(temperature); }
            @Override public double vaporPartialPressureLimit(double temperature) { return inner.vaporPartialPressureLimit(temperature); }
            @Override public void liquid(double temperature, double pressure, double[] out) { inner.liquid(temperature, pressure, out); }
            @Override public IdealGasFunction vapor() { return inner.vapor(); }
            @Override public FreeWaterIce ice() { return inner.ice(); }
        };
        assertThrows(IllegalArgumentException.class, () -> new FluidTpEquilibrium(contract, service.evaluator(),
                FluidTpEquilibrium.Settings.DEFAULT, other));
    }

    /**
     * The engine's phase count with free water equals the network's {@code FluidThermodynamics.flashTP} (its gas, its
     * hydrocarbon liquid, its free water) on the P2 network grid with water added, pinned to the states where the water
     * rule decides: the two agree on the hydrocarbon phases (which the liquid path, not the water rule, decides) and the
     * rule's decision has a margin (free water holding more than 5 % of the water, or unsaturated steam below
     * 0.95 {@code p_sat}, and the state pressure more than 5 % from {@code p_sat}). Both refusing is agreement; since the
     * owner rule of 2026-10-16 neither refuses a state of the grid.
     */
    @Test
    void freeWaterPhaseCountEqualsTheNetworkFlash() {
        FluidThermodynamics fluid = FluidTestSupport.networkModel();
        var service = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(fluid));
        var contract = service.contract();
        int water = contract.waterIndex();
        double[] crude = PhaseTestSupport.crudeWithNitrogen(contract);
        double[] gas = new double[contract.components().size()];
        for (String id : List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) {
            gas[contract.index(id)] = 0.10;
        }
        gas[contract.index("Nitrogen")] = 0.30;
        for (int i = 0; i < gas.length; i++) if (contract.components().get(i).startsWith("crude_pc")) gas[i] = 1.0e-4;
        var workspace = service.newWorkspace();
        int states = 0;
        int answered = 0;
        int refusedByBoth = 0;
        int pinned = 0;
        int hydrocarbonDiffers = 0;
        int threePhase = 0;
        for (double[] base : new double[][] {crude, gas}) {
            double hydrocarbons = PhaseTestSupport.sum(base);
            for (double ratio : new double[] {0.01, 1.0, 10.0}) {
                for (int it = 0; it <= 15; it++) {
                    double t = 300.0 + 40.0 * it;
                    for (int ip = 0; ip < 7; ip++) {
                        double p = 0.2e6 + 0.3e6 * ip;
                        double[] feed = base.clone();
                        feed[water] = ratio * hydrocarbons;
                        states++;
                        var result = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY),
                                workspace);
                        FluidThermodynamics.State flash;
                        try {
                            flash = fluid.flashTP(t, p, feed.clone(), () -> { });
                        } catch (IllegalArgumentException refused) {
                            flash = null;
                        }
                        String at = t + " K " + p + " Pa water/hydrocarbon " + ratio;
                        if (flash == null || !result.converged()) {
                            assertEquals(flash == null, !result.converged(), at + ": " + result);
                            refusedByBoth++;
                            continue;
                        }
                        answered++;
                        assertEquals(Classification.FREE_WATER, result.classification(), at);
                        assertTrue(result.conservationDefect(feed) < 1.0e-13, at);
                        boolean networkLiquid = PhaseTestSupport.sum(flash.liquidView()) > 0.0;
                        boolean networkVapour = PhaseTestSupport.sum(flash.vaporView()) > 0.0;
                        boolean networkGas = networkVapour || flash.waterVapor() > 0.0;
                        boolean networkWater = flash.waterLiquid() > 0.0;
                        boolean engineLiquid = false;
                        boolean engineVapour = false;
                        boolean engineGas = false;
                        boolean engineWater = false;
                        for (PhaseAmounts phase : result.phases()) {
                            if (phase.kind() == PhaseKind.LIQUID) engineLiquid = true;
                            if (phase.kind() == PhaseKind.FREE_WATER) engineWater = true;
                            if (phase.kind() == PhaseKind.VAPOR) {
                                engineGas = true;
                                for (int i = 0; i < water; i++) engineVapour |= phase.amount(i) > 0.0;
                            }
                        }
                        if (result.phases().size() == 3) threePhase++;
                        if (networkLiquid != engineLiquid || networkVapour != engineVapour) {
                            hydrocarbonDiffers++;
                            continue;
                        }
                        var split = result.freeWater();
                        double saturation = split.saturationPressure();
                        boolean margin = Math.abs(p - saturation) > 0.05 * p && (split.liquidWater() > 0.0
                                ? split.liquidWater() > 0.05 * (split.liquidWater() + split.waterVapor())
                                : !(p > saturation) || split.waterPartialPressure() < 0.95 * saturation);
                        if (!margin) continue;
                        pinned++;
                        int network = (networkGas ? 1 : 0) + (networkLiquid ? 1 : 0) + (networkWater ? 1 : 0);
                        assertEquals(network, result.phases().size(), at + ": " + result.phases().stream().map(PhaseAmounts::kind).toList());
                        assertEquals(networkGas, engineGas, at);
                        assertEquals(networkWater, engineWater, at);
                    }
                }
            }
        }
        assertEquals(672, states);
        assertTrue(pinned >= 0.9 * answered, "pinned " + pinned + " of " + answered);
        assertTrue(threePhase > 0, "three phases " + threePhase);
        // Since the owner rule of 2026-10-16 no state of the grid is refused (the steam partial-pressure limit refused some).
        assertEquals(0, refusedByBoth, "refused " + refusedByBoth);
        System.out.println("P3 free water vs flashTP: " + states + " states, " + answered + " answered by both (" + threePhase
                + " with gas, hydrocarbon liquid and free water), " + refusedByBoth + " refused by both; phase counts equal on all "
                + pinned + " pinned states; hydrocarbon phases differ on " + hydrocarbonDiffers);
    }

    // P3 WP7d.

    private static final String PILOT = "icheme:pilot_cryogenic";

    /** The pilot package's engine on its network contract (the G3 families' engine). */
    private static FluidTpEquilibrium pilot() {
        var catalog = MaterialCatalog.bundled();
        return new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, PILOT), CubicPhaseEvaluator.forPackage(catalog, PILOT), FluidTpEquilibrium.Settings.DEFAULT, null);
    }

    /** A TP request on {@code engine}'s contract basis; {@code amounts} in basis order (missing trailing entries zero). */
    private static EquilibriumRequest request(FluidTpEquilibrium engine, double t, double p, double... amounts) {
        var basis = engine.contract().components();
        return EquilibriumRequest.tp(t, p, basis, Arrays.copyOf(amounts, basis.size()), PhaseCompetition.FLUID_ONLY);
    }

    /**
     * The G3 F5 state (N2/CH4 0.67/0.33 at 150 K and 4.69 MPa, just above the mixture's critical pressure on that
     * isotherm): the feed's stability test with the default budget of 100 iterations per trial ends unresolved, because
     * the Wilson liquid-like trial crawls towards the trivial solution on the liquid root of compositions whose own
     * Hessian is indefinite (the test's Newton step refused at every point; it reaches the trivial exit after 186
     * iterations). The engine repeats the test with ten times the budget, which resolves it stable: one phase,
     * converged, outside the band by its rules (no stationary point other than the feed). A brute-force tangent-plane scan
     * confirms the phase count: {@code D(w)} is nowhere negative over 19,999 trial compositions on both roots and is least
     * at the feed. WP9b's finer scan around it (150 K, x_N2 0.55 to 0.80 by 0.01, 4.0 to 5.2 MPa by 10 kPa) keeps no
     * {@code NOT_CONVERGED}.
     */
    @Test
    void anUnresolvedStabilityTestIsRepeatedOnTheLongerBudget() {
        var engine = pilot();
        var kernel = engine.evaluator().kernel();
        double t = 150.0, p = 4.69e6, x = 0.67;
        double[] feed = {x, 1.0 - x, 0.0, 0.0};
        var d = TangentPlaneStability.Settings.DEFAULT;
        var plain = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT).test(t, p, feed);
        assertEquals(TangentPlaneStability.Verdict.UNRESOLVED, plain.verdict(), "the default budget leaves this feed unresolved");
        var patient = new TangentPlaneStability(kernel, new TangentPlaneStability.Settings(d.instabilityTolerance(),
                d.convergenceTolerance(), d.newtonSwitch(), d.maximumSuccessiveSubstitutions(), d.accelerationCycle(),
                FluidTpEquilibrium.UNRESOLVED_STABILITY_BUDGET_FACTOR * d.maximumIterations(), d.trivialDistance(),
                d.pureComponentTrialLimit(), d.exhaustive())).test(t, p, feed);
        assertEquals(TangentPlaneStability.Verdict.STABLE, patient.verdict());

        var result = engine.tp(request(engine, t, p, x, 1.0 - x), engine.newWorkspace());
        assertEquals(Status.CONVERGED, result.status(), result.detail());
        assertEquals(Classification.SINGLE_FLUID, result.classification());
        assertEquals(1, result.phases().size());
        assertEquals(TangentPlaneStability.Verdict.STABLE, result.diagnostics().feedVerdict());
        assertTrue(result.diagnostics().kernelEvaluations() >= plain.kernelEvaluations() + patient.kernelEvaluations(),
                "both tests counted: " + result.diagnostics().kernelEvaluations());
        assertFalse(result.diagnostics().criticalBand(), "no stationary point next to the feed: outside the band by its rules");
        assertEquals(CoverageGrade.ESTIMATED_DECLARED_ERROR, result.coverage().grade());
        assertTrue(result.conservationDefect(new double[] {x, 1.0 - x, 0.0, 0.0, 0.0}) < 1.0e-15);

        // Brute force: D(w) = sum_i w_i (ln w_i + ln phi_i(w) - d_i) on the lower of both roots at every trial composition.
        var ws = kernel.newWorkspace();
        var first = kernel.newEvaluation();
        var second = kernel.newEvaluation();
        kernel.evaluate(t, p, feed, Root.VAPOR, ws, first);
        kernel.evaluate(t, p, feed, Root.LIQUID, ws, second);
        var feedRoot = x * first.logFugacityCoefficient(0) + (1 - x) * first.logFugacityCoefficient(1)
                <= x * second.logFugacityCoefficient(0) + (1 - x) * second.logFugacityCoefficient(1) ? first : second;
        double d0 = Math.log(x) + feedRoot.logFugacityCoefficient(0);
        double d1 = Math.log(1.0 - x) + feedRoot.logFugacityCoefficient(1);
        double least = Double.POSITIVE_INFINITY;
        double at = Double.NaN;
        double[] trial = new double[4];
        for (int k = 1; k < 20000; k++) {
            double w = k / 20000.0;
            trial[0] = w;
            trial[1] = 1.0 - w;
            double best = Double.POSITIVE_INFINITY;
            for (Root root : new Root[] {Root.VAPOR, Root.LIQUID}) {
                kernel.evaluate(t, p, trial, root, ws, first);
                best = Math.min(best, w * (Math.log(w) + first.logFugacityCoefficient(0) - d0)
                        + (1.0 - w) * (Math.log(1.0 - w) + first.logFugacityCoefficient(1) - d1));
            }
            if (best < least) {
                least = best;
                at = w;
            }
        }
        assertTrue(least >= -1.0e-12, "a trial phase below the tangent plane: D " + least + " at x_N2 " + at);
        assertEquals(x, at, 1.0e-4, "the tangent-plane distance is least at the feed");

        // WP9b's finer scan around the state: every answer converged or typed; none untyped outside the band.
        var scan = engine.newWorkspace();
        int states = 0;
        List<String> untyped = new ArrayList<>();
        for (int ix = 0; ix <= 25; ix++) {
            for (int ip = 0; ip <= 120; ip++) {
                double nitrogen = 0.55 + 0.01 * ix;
                double pressure = 4.0e6 + 1.0e4 * ip;
                var r = engine.tp(request(engine, t, pressure, nitrogen, 1.0 - nitrogen), scan);
                states++;
                if (!r.converged() && !r.diagnostics().criticalBand()) untyped.add(pressure + " Pa x_N2 " + nitrogen + ": " + r.detail());
            }
        }
        assertEquals(3146, states);
        assertEquals(List.of(), untyped);
        System.out.println("WP7d F5 state: default stability test " + plain.verdict() + " after " + plain.iterations()
                + " iterations, longer budget " + patient.verdict() + " after " + patient.iterations() + "; engine "
                + result.classification() + " (" + result.phases().get(0).kind() + "), " + result.diagnostics().kernelEvaluations()
                + " kernel calls; brute force min D " + least + " at x_N2 " + at + "; WP9b finer scan " + states + " states, 0 untyped");
    }

    /**
     * A reused workspace carries no mole numbers of a component the current feed lacks (WP7d). The Newton finish hands its
     * vapour and liquid mole numbers to the kernel over the whole basis; before WP7d a component absent from the feed kept
     * the amount an earlier split had left in the workspace. After an N2/CH4 split, CH4/CO2 at 250 K and 8 MPa ended "no
     * convergence in 50 Newton steps", and after CH4/C2H6, N2/C2H6 at 100 K and 4 MPa found "no Gibbs-energy decrease";
     * with a fresh workspace both converge. Every answer on one reused workspace, forwards (the failing order) and
     * backwards, is the fresh workspace's bit for bit (five splits and two single phases).
     */
    @Test
    void aReusedWorkspaceCarriesNoAmountsOfAbsentComponents() {
        var engine = pilot();
        double[][] states = {
                {150.0, 4.56e6, 0.67, 0.33, 0.0, 0.0}, {250.0, 8.0e6, 0.0, 0.55, 0.0, 0.45},
                {220.0, 6.0e6, 0.0, 0.85, 0.15, 0.0}, {100.0, 4.0e6, 0.85, 0.0, 0.15, 0.0},
                {220.0, 6.0e6, 0.0, 0.55, 0.0, 0.45}, {120.0, 2.0e6, 0.3, 0.7, 0.0, 0.0}, {165.0, 4.98e6, 0.47, 0.53, 0.0, 0.0}};
        List<EquilibriumRequest> requests = new ArrayList<>();
        List<EquilibriumResult> fresh = new ArrayList<>();
        int splits = 0;
        for (double[] s : states) {
            var request = request(engine, s[0], s[1], s[2], s[3], s[4], s[5]);
            requests.add(request);
            var answer = engine.tp(request, engine.newWorkspace());
            assertEquals(Status.CONVERGED, answer.status(), answer.detail());
            if (answer.phases().size() == 2) splits++;
            fresh.add(answer);
        }
        // Five splits, the two formerly failing ones among them, and two single phases (N2/CH4 at 120 K and 2 MPa, and at
        // 165 K and 4.98 MPa next to the feed's incipient phase).
        assertEquals(5, splits);
        assertEquals(2, fresh.get(1).phases().size());
        assertEquals(2, fresh.get(3).phases().size());
        var workspace = engine.newWorkspace();
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < requests.size(); j++) {
                int k = pass == 0 ? j : requests.size() - 1 - j;
                assertBitwiseEqual(fresh.get(k), engine.tp(requests.get(k), workspace));
            }
        }
    }

    /**
     * The pure-fluid band with D14's corner (box 4, Tr 1.0-1.1 x Pr 1.5-2) and D15's widening of box 2 (Tr 0.95-0.97 x
     * Pr 0.58-0.8, the liquid between boxes 1 and 2 below Pr 0.8), closed intervals: the boxes by reduced values, and the
     * engine's grade on pure nitrogen of the pilot package (research-only inside, estimated-with-declared-error just
     * outside), including the two G3 evidence states: 131.19 K and 5.10 MPa (F5's corner, -8.02 %) and 121.19 K and
     * 2.70 MPa (F5's band gap, -9.06 %).
     */
    @Test
    void theBandCarriesD14sCornerAndD15sWidenedBoxTwo() {
        assertEquals(1, FluidTpEquilibrium.pureBandBox(1.0, 1.0));
        assertEquals(2, FluidTpEquilibrium.pureBandBox(0.92, 0.60));
        assertEquals(2, FluidTpEquilibrium.pureBandBox(0.96, 0.795));
        assertEquals(2, FluidTpEquilibrium.pureBandBox(0.97, 0.58));
        assertEquals(0, FluidTpEquilibrium.pureBandBox(0.971, 0.79), "box 2 reaches Tr 0.97");
        assertEquals(0, FluidTpEquilibrium.pureBandBox(0.93, 0.76), "below Tr 0.95 box 2 keeps Pr 0.74");
        assertEquals(0, FluidTpEquilibrium.pureBandBox(0.96, 0.57));
        assertEquals(3, FluidTpEquilibrium.pureBandBox(1.1, 2.5));
        assertEquals(4, FluidTpEquilibrium.pureBandBox(1.05, 1.75));
        assertEquals(4, FluidTpEquilibrium.pureBandBox(1.0, 2.0));
        assertEquals(1, FluidTpEquilibrium.pureBandBox(1.05, 1.5), "a shared edge reports the lower box");
        assertEquals(0, FluidTpEquilibrium.pureBandBox(0.99, 1.75));
        assertEquals(0, FluidTpEquilibrium.pureBandBox(1.11, 1.75));

        var engine = pilot();
        var ws = engine.newWorkspace();
        var nitrogen = engine.evaluator().component(0);
        double tc = nitrogen.criticalTemperatureKelvin();
        double pc = nitrogen.criticalPressurePascal();
        double[][] inside = {{131.19, 5.10e6}, {121.19, 2.70e6}, {1.04 * tc, 1.75 * pc}, {0.965 * tc, 0.79 * pc}, {tc, 1.9 * pc}};
        for (double[] s : inside) {
            var r = engine.tp(request(engine, s[0], s[1], 1.0), ws);
            assertEquals(Status.CONVERGED, r.status(), r.detail());
            assertTrue(r.diagnostics().criticalBand(), s[0] + " K " + s[1] + " Pa in the band");
            assertEquals(CoverageGrade.RESEARCH_ONLY, r.coverage().grade());
        }
        double[][] outside = {{1.12 * tc, 1.75 * pc}, {0.99 * tc, 1.75 * pc}, {0.93 * tc, 0.77 * pc}, {1.04 * tc, 2.05 * pc}};
        for (double[] s : outside) {
            var r = engine.tp(request(engine, s[0], s[1], 1.0), ws);
            assertEquals(Status.CONVERGED, r.status(), r.detail());
            assertFalse(r.diagnostics().criticalBand(), s[0] + " K " + s[1] + " Pa outside the band");
            assertEquals(CoverageGrade.ESTIMATED_DECLARED_ERROR, r.coverage().grade());
        }
    }

    /**
     * Two liquid-like phases outside the critical band are {@code LIQUID_LIQUID} (WP7d; the WP7c rule
     * {@code PIP > max(1, Z)} on each product's own root), both labelled {@code LIQUID}, the lighter first: CO2 in liquid
     * methane at 1 % (the state G3 F7 found, a CO2-rich liquid beside the methane liquid, on an open research contract over
     * the pilot evaluator); N2/C2H6 at 95 K and 2 MPa on the pilot network contract (type III: an ethane-rich and a
     * nitrogen-rich liquid); a synthetic binary of methane-like and ethane-like constants with {@code k_ij = 0.3}, which
     * with {@code k_ij = 0} is one liquid. A near-critical N2/CH4 split inside the band whose two phases are both
     * liquid-like by the parameter (150 K, 4.64 MPa, tie line 0.010) stays {@code VAPOR_LIQUID}: next to a critical point
     * the parameter is no label to trust.
     */
    @Test
    void twoLiquidLikePhasesOutsideTheBandAreLiquidLiquid() {
        var catalog = MaterialCatalog.bundled();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var research = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
        for (double[] s : new double[][] {{91.0, 2.0e6}, {95.0, 0.5e6}, {100.0, 2.0e6}}) {
            var q = request(research, s[0], s[1], 0.0, 0.99, 0.0, 0.01);
            var r = research.tp(q);
            assertLiquidLiquid(q, r, evaluator);
            assertTrue(r.phases().get(1).amount(3) / r.phases().get(1).total() > 0.99, "the denser liquid is CO2-rich");
            assertTrue(r.phases().get(0).amount(1) / r.phases().get(0).total() > 0.99, "the lighter liquid is methane-rich");
        }
        var engine = pilot();
        var typeThreeRequest = request(engine, 95.0, 2.0e6, 0.3, 0.0, 0.7);
        var typeThree = engine.tp(typeThreeRequest);
        assertLiquidLiquid(typeThreeRequest, typeThree, evaluator);
        assertEquals(CoverageGrade.ESTIMATED_DECLARED_ERROR, typeThree.coverage().grade());

        var components = List.of(new ThermoComponent("LightLiquid", 190.564, 4.5992e6, 0.01142, 0.0160428),
                new ThermoComponent("HeavyLiquid", 305.32, 4.8722e6, 0.0995, 0.0300690));
        var gas = List.of(PhaseTestSupport.StubIdealGas.constant(35.7, 50.0, 1000.0), PhaseTestSupport.StubIdealGas.constant(52.5, 50.0, 1000.0));
        for (double kij : new double[] {0.3, 0.0}) {
            var synthetic = new CubicPhaseEvaluator("test:synthetic-lle", components, new double[][] {{0.0, kij}, {kij, 0.0}},
                    new double[2], gas);
            var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(synthetic), synthetic, FluidTpEquilibrium.Settings.DEFAULT, null);
            var q = request(service, 110.0, 3.0e6, 0.5, 0.5);
            var r = service.tp(q);
            if (kij > 0.0) {
                assertLiquidLiquid(q, r, synthetic);
            } else {
                assertEquals(Status.CONVERGED, r.status(), r.detail());
                assertEquals(1, r.phases().size(), "miscible without the interaction: " + r);
                assertEquals(PhaseKind.LIQUID, r.phases().get(0).kind());
            }
        }

        var nearCritical = engine.tp(request(engine, 150.0, 4.64e6, 0.67, 0.33));
        assertEquals(Classification.VAPOR_LIQUID, nearCritical.classification(), nearCritical.toString());
        assertTrue(nearCritical.diagnostics().criticalBand() && nearCritical.diagnostics().tieLine() < 0.1);
        assertEquals(PhaseKind.VAPOR, nearCritical.phases().get(0).kind());
        assertEquals(PhaseKind.LIQUID, nearCritical.phases().get(1).kind());
        for (PhaseAmounts phase : nearCritical.phases()) {
            assertTrue(liquidLike(phase, evaluator), "both near-critical phases are liquid-like by the parameter");
        }
    }

    private static void assertLiquidLiquid(EquilibriumRequest request, EquilibriumResult r, CubicPhaseEvaluator evaluator) {
        assertEquals(Status.CONVERGED, r.status(), r.detail());
        assertEquals(Classification.LIQUID_LIQUID, r.classification(), r.toString());
        assertEquals(2, r.phases().size());
        assertEquals(PhaseKind.LIQUID, r.phases().get(0).kind());
        assertEquals(PhaseKind.LIQUID, r.phases().get(1).kind());
        assertTrue(r.phases().get(0).state().molarVolume() > r.phases().get(1).state().molarVolume(), "the lighter first");
        for (PhaseAmounts phase : r.phases()) {
            assertTrue(liquidLike(phase, evaluator), "liquid-like: " + phase.kind());
            assertTrue(volumeRatio(phase, evaluator) < PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO,
                    "denser than the cubic's critical volume: v/b " + volumeRatio(phase, evaluator));
        }
        assertFalse(r.diagnostics().criticalBand());
        assertTrue(r.diagnostics().tieLine() >= 0.1, "outside the band: tie line " + r.diagnostics().tieLine());
        assertTrue(r.conservationDefect(request.amountsView()) < 1.0e-15, "conserved");
    }

    /** The WP7c label of a phase's own root: {@code PIP > max(1, Z)} on the untranslated cubic. */
    private static boolean liquidLike(PhaseAmounts phase, CubicPhaseEvaluator evaluator) {
        return PhaseIdentification.liquidLike(ownRoot(phase, evaluator), phase.temperature(), phase.pressure());
    }

    /** A phase's untranslated {@code v/b} on its own root. */
    private static double volumeRatio(PhaseAmounts phase, CubicPhaseEvaluator evaluator) {
        var evaluation = ownRoot(phase, evaluator);
        return evaluation.compressibility() * PengRobinsonKernel.GAS_CONSTANT * phase.temperature() / phase.pressure() / evaluation.bMix();
    }

    private static PengRobinsonKernel.Evaluation ownRoot(PhaseAmounts phase, CubicPhaseEvaluator evaluator) {
        var kernel = evaluator.kernel();
        var evaluation = kernel.newEvaluation();
        double[] x = Arrays.copyOf(phase.amountsView(), evaluator.componentCount());
        kernel.evaluate(phase.temperature(), phase.pressure(), x, phase.state().root() == Root.VAPOR ? Root.VAPOR : Root.LIQUID,
                kernel.newWorkspace(), evaluation);
        return evaluation;
    }

    /**
     * P3 WP11: a split whose lighter phase is a dense gas, liquid-like by the parameter by a small margin but less dense
     * than the cubic's critical volume ({@code v > 3.9514 b}), is {@code VAPOR_LIQUID}, not {@code LIQUID_LIQUID}: N2/C2H6
     * at 195 K and 10 MPa (WP7d measured the lighter phase at PIP 1.048, Z 0.694, v/b 4.38) and CH4/CO2 at 245 K and
     * 8 MPa (PIP 1.203, v/b 3.96), on the pilot network contract, both outside the band. Each case asserts the premise
     * (the lighter phase liquid-like by {@code PIP > max(1, Z)} and above the volume ratio), so the volume guard is what
     * decides. The liquid-liquid splits of the WP7d test keep their label (their lighter liquid has v/b about 1.1, held
     * there by {@code assertLiquidLiquid}); the guard's constant is the kernel's own critical {@code v/b}.
     */
    @Test
    void aDenseGasBesideALiquidIsVapourLiquid() {
        assertEquals(0.307401 / 0.0777960739, PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO, 1.0e-5,
                "the constant is PR78's critical Z_c / Omega_b");
        var catalog = MaterialCatalog.bundled();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var engine = pilot();
        // {T, P, amounts in basis order N2, CH4, C2H6, CO2}
        double[][] states = {{195.0, 10.0e6, 0.55, 0.0, 0.45, 0.0}, {195.0, 10.0e6, 0.35, 0.0, 0.65, 0.0},
                {195.0, 10.0e6, 0.85, 0.0, 0.15, 0.0}, {245.0, 8.0e6, 0.0, 0.45, 0.0, 0.55}, {245.0, 8.0e6, 0.0, 0.55, 0.0, 0.45}};
        for (double[] s : states) {
            var q = request(engine, s[0], s[1], s[2], s[3], s[4], s[5]);
            var r = engine.tp(q);
            String where = s[0] + " K " + s[1] + " Pa " + Arrays.toString(Arrays.copyOfRange(s, 2, 6)) + ": " + r;
            assertEquals(Status.CONVERGED, r.status(), where);
            assertEquals(Classification.VAPOR_LIQUID, r.classification(), where);
            assertFalse(r.diagnostics().criticalBand(), where);
            assertEquals(PhaseKind.VAPOR, r.phases().get(0).kind(), where);
            assertEquals(PhaseKind.LIQUID, r.phases().get(1).kind(), where);
            var lighter = r.phases().get(0);
            assertTrue(liquidLike(lighter, evaluator), "premise: the dense gas is liquid-like by the parameter; " + where);
            assertTrue(volumeRatio(lighter, evaluator) > PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO,
                    "premise: the dense gas is less dense than the critical volume, v/b " + volumeRatio(lighter, evaluator) + "; " + where);
            assertTrue(liquidLike(r.phases().get(1), evaluator), where);
            assertTrue(r.conservationDefect(q.amountsView()) < 1.0e-15, "conserved; " + where);
        }
    }

    /**
     * P3 WP11: a converged split whose products the stability re-check proves unstable (a third fluid phase: N2/C2H6 at
     * x_N2 0.93 and 0.94, 125 K and 3 MPa, the type III three-phase region, outside the band) is a not-converged result
     * whose detail starts with {@link EquilibriumResult#UNSTABLE_PRODUCT}, the typed indication the network holds on. At
     * x_N2 exactly 0.95 the feed is a pure fluid by the band's rule (nitrogen at Tr 0.99, Pr 0.88: box 1), so the same
     * failure is typed {@link EquilibriumResult#CRITICAL_BAND} first, the indication after it.
     */
    @Test
    void anUnstableProductIsTheTypedThreePhaseIndication() {
        var engine = pilot();
        for (double nitrogen : new double[] {0.93, 0.94}) {
            var r = engine.tp(request(engine, 125.0, 3.0e6, nitrogen, 0.0, 1.0 - nitrogen));
            assertEquals(Status.NOT_CONVERGED, r.status(), r.toString());
            assertFalse(r.diagnostics().criticalBand(), r.detail());
            assertTrue(r.detail().startsWith(EquilibriumResult.UNSTABLE_PRODUCT + ": a product phase is not stable"), r.detail());
            assertTrue(r.diagnostics().productTangentPlaneDistance() < 0.0, "a product's tangent-plane distance is negative");
        }
        var band = engine.tp(request(engine, 125.0, 3.0e6, 0.95, 0.0, 0.05));
        assertEquals(Status.NOT_CONVERGED, band.status(), band.toString());
        assertTrue(band.diagnostics().criticalBand(), band.detail());
        assertTrue(band.detail().startsWith(EquilibriumResult.CRITICAL_BAND) && band.detail().contains(EquilibriumResult.UNSTABLE_PRODUCT), band.detail());
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
            assertArrayEquals(bits(a.amountsView()), bits(b.amountsView()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarVolume()), Double.doubleToRawLongBits(b.state().molarVolume()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarEnthalpy()), Double.doubleToRawLongBits(b.state().molarEnthalpy()));
            assertEquals(Double.doubleToRawLongBits(a.state().molarEntropy()), Double.doubleToRawLongBits(b.state().molarEntropy()));
            assertArrayEquals(bits(a.state().logFugacityCoefficientsView()), bits(b.state().logFugacityCoefficientsView()));
        }
        var d = expected.diagnostics();
        var e = actual.diagnostics();
        assertEquals(d.flashIterations(), e.flashIterations());
        assertEquals(d.newtonIterations(), e.newtonIterations());
        assertEquals(d.extrapolations(), e.extrapolations());
        assertEquals(d.rachfordRiceIterations(), e.rachfordRiceIterations());
        assertEquals(d.kernelEvaluations(), e.kernelEvaluations());
        assertEquals(d.derivativeEvaluations(), e.derivativeEvaluations());
        assertEquals(Double.doubleToRawLongBits(d.tieLine()), Double.doubleToRawLongBits(e.tieLine()));
        assertEquals(Double.doubleToRawLongBits(d.fugacityResidual()), Double.doubleToRawLongBits(e.fugacityResidual()));
        if (expected.freeWater() != null) assertEquals(expected.freeWater(), actual.freeWater());
    }

    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Double.doubleToRawLongBits(values[i]);
        return bits;
    }
}
