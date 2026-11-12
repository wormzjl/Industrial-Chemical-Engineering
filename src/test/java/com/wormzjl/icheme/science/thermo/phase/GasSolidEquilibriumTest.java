package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Gas-solid equilibrium with the CO2-I crystal in the equilibrium engine (P4 stage 2a, batch
 * 2026-09-24-coolprop-low-temperature, {@code P4_GAS_SOLID_EQUILIBRIUM.md}) on the pilot package
 * {@code icheme:pilot_cryogenic}: the crystal competition as a whole set, TP with a pure solid (conservation and
 * equal chemical potentials), the onset functions with the partial-pressure approximation beside them, PH and UV with
 * the solid's enthalpy and volume, the crystal's derivatives, and the typed refusals. Every number the stage document
 * quotes is printed here. Since P5 ({@code P5_SOLID_LIQUID_EQUILIBRIUM.md}) the states P4 held typed (a crystal beside a
 * liquid) are answers; the grid of (b) counts them apart, and the remaining holds are {@code CRYSTAL_PHASES}.
 */
class GasSolidEquilibriumTest {
    static final String PILOT = "icheme:pilot_cryogenic";
    static final String CRYSTAL = "icheme:crystal_carbon_dioxide_i";
    static final FluidTpEquilibrium SERVICE = FluidTpEquilibrium.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
    static final List<String> BASIS = SERVICE.contract().components();
    static final int CO2 = BASIS.indexOf("CarbonDioxide");
    static final double R = PhaseTestSupport.R;
    static final PhaseCompetition CRYSTALS = PhaseCompetition.FLUID_AND_CRYSTALS;

    /** A feed over the pilot basis [N2, CH4, C2H6, CO2]. */
    static double[] feed(double n2, double ch4, double c2h6, double co2) { return new double[] {n2, ch4, c2h6, co2}; }

    static double[] inNitrogen(double y) { return feed(1.0 - y, 0.0, 0.0, y); }

    static EquilibriumResult tp(double t, double p, double[] z, FluidTpEquilibrium.Workspace ws) {
        return SERVICE.tp(EquilibriumRequest.tp(t, p, BASIS, z, CRYSTALS), ws);
    }

    static PhaseAmounts solid(EquilibriumResult result) {
        for (PhaseAmounts phase : result.phases()) if (phase.kind() == PhaseKind.SOLID) return phase;
        return null;
    }

    static PhaseAmounts vapour(EquilibriumResult result) {
        for (PhaseAmounts phase : result.phases()) if (phase.kind() != PhaseKind.SOLID) return phase;
        return null;
    }

    /** The grid of (b): y_CO2 in N2 x T x P. */
    static final double[] GRID_Y = {0.001, 0.01, 0.05, 0.1, 0.2, 0.3, 0.5};
    static final double[] GRID_T = {130, 150, 170, 190, 210};
    static final double[] GRID_P = {1.0e5, 5.0e5, 1.0e6, 2.0e6, 5.0e6};

    /** (1) The contract: the package's crystal set qualified as a whole, requested by name or as the package's. */
    @Test
    void theCrystalCompetitionIsQualifiedAsAWholeSet() {
        MaterialCatalog catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var contract = SERVICE.contract();
        var named = PhaseCompetition.withCrystals(CRYSTAL);
        assertEquals(Set.of(PhaseCompetition.FLUID_ONLY, named), contract.qualifiedCompetitions());
        assertEquals(named, contract.crystalCompetition());
        assertEquals(named, contract.resolve(CRYSTALS));
        assertEquals(PhaseCompetition.FLUID_ONLY, contract.resolve(PhaseCompetition.FLUID_ONLY));
        assertEquals(List.of(CRYSTAL), PhaseContract.admittedCrystals(catalog, PILOT));
        // The fluid-only form of the contract, with the same identity and domain.
        var network = PhaseTestSupport.fluidOnly(PhaseContract.forPackage(catalog, PILOT));
        assertEquals(Set.of(PhaseCompetition.FLUID_ONLY), network.qualifiedCompetitions());
        assertEquals(network.identity(), contract.identity());
        assertNull(network.crystalCompetition());
        assertEquals(CRYSTALS, network.resolve(CRYSTALS));
        assertTrue(CRYSTALS.crystalsCompete() && !CRYSTALS.fluidOnly() && CRYSTALS.crystals().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new PhaseCompetition(new java.util.TreeSet<>(Set.of(CRYSTAL)), true));

        // Named or the package's: the same answer, which names the resolved set.
        var ws = SERVICE.newWorkspace();
        var byName = SERVICE.tp(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), named), ws);
        var byPackage = tp(150.0, 1.0e6, inNitrogen(0.01), ws);
        assertEquals(Classification.VAPOR_SOLID, byPackage.classification(), byPackage.toString());
        assertEquals(named, byPackage.competition());
        assertEquals(byName.phases().get(1).amount(CO2), byPackage.phases().get(1).amount(CO2), 0.0);
        assertTrue(byPackage.solidsAssessed());
        assertEquals(CoverageGrade.ESTIMATED_DECLARED_ERROR, byPackage.coverage().grade());
        assertTrue(byPackage.coverage().evidence().contains("solids assessed") && byPackage.coverage().evidence().contains("crystal-carbon_dioxide-i-r2")
                && byPackage.coverage().evidence().contains("-22 %"), byPackage.coverage().evidence());

        // A package without crystals, and the pilot with a research-only crystal record: the request is refused.
        assertEquals(Set.of(PhaseCompetition.FLUID_ONLY), PhaseContract.forPackage(catalog, PhaseTestSupport.NETWORK).qualifiedCompetitions());
        var bundled = PhaseTestSupport.networkService();
        var none = bundled.tp(EquilibriumRequest.tp(150.0, 1.0e6, bundled.contract().components(), PhaseTestSupport.over(bundled.contract(), "Nitrogen", 1.0), CRYSTALS));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, none.reason(), none.toString());
        var researchCatalog = PilotCryogenicTestCatalog.parseWith(CrystalPhaseEvaluatorTest.CRYSTAL_PATH,
                o -> o.getAsJsonObject("coverage").addProperty("grade", "research_only"));
        assertEquals(List.of(), PhaseContract.admittedCrystals(researchCatalog, PILOT));
        var research = FluidTpEquilibrium.forPackage(researchCatalog, PILOT);
        var refused = research.tp(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), CRYSTALS));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, refused.reason(), refused.toString());
        var namedRefused = research.tp(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), named));
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, namedRefused.reason());
        // A crystal the contract names without its evaluator here: not implemented (P2 rule kept), at 150 K as at 250 K (no
        // fluid range of CO2 is checked first since the owner rule of 2026-10-16).
        var withoutEvaluator = new FluidTpEquilibrium(contract, SERVICE.evaluator(), FluidTpEquilibrium.Settings.DEFAULT);
        var cold = withoutEvaluator.tp(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), CRYSTALS));
        assertEquals(UnsupportedReason.NOT_IMPLEMENTED, cold.reason(), cold.toString());
        var missing = withoutEvaluator.tp(EquilibriumRequest.tp(250.0, 1.0e6, BASIS, inNitrogen(0.01), CRYSTALS));
        assertEquals(UnsupportedReason.NOT_IMPLEMENTED, missing.reason());
        assertTrue(missing.detail().contains("P4"), missing.detail());
    }

    /** Fluid-only requests on the crystal contract's service answer bit for bit as on the network contract's. */
    @Test
    void fluidOnlyRequestsAreUnchanged() {
        MaterialCatalog catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var network = new FluidTpEquilibrium(PhaseTestSupport.fluidOnly(PhaseContract.forPackage(catalog, PILOT)), CubicPhaseEvaluator.forPackage(catalog, PILOT), FluidTpEquilibrium.Settings.DEFAULT);
        var a = SERVICE.newWorkspace();
        var b = network.newWorkspace();
        int compared = 0;
        for (double t : new double[] {100, 150, 220, 300, 600}) {
            for (double p : new double[] {1.0e5, 1.0e6, 5.0e6}) {
                for (double[] z : new double[][] {feed(0.5, 0.5, 0, 0), feed(0.2, 0.5, 0.2, 0.1), feed(0, 0, 0, 1), feed(0.9, 0, 0, 0.1)}) {
                    var x = SERVICE.tp(EquilibriumRequest.tp(t, p, BASIS, z, PhaseCompetition.FLUID_ONLY), a);
                    var y = network.tp(EquilibriumRequest.tp(t, p, BASIS, z, PhaseCompetition.FLUID_ONLY), b);
                    assertEquals(y.toString(), x.toString());
                    assertEquals(y.coverage(), x.coverage());
                    assertEquals(y.phases().size(), x.phases().size());
                    for (int k = 0; k < x.phases().size(); k++) {
                        assertTrue(Arrays.equals(y.phases().get(k).amountsView(), x.phases().get(k).amountsView()));
                        assertEquals(y.phases().get(k).state().toString(), x.phases().get(k).state().toString());
                    }
                    if (x.converged() && !x.phases().isEmpty()) {
                        var h = SERVICE.ph(EquilibriumRequest.ph(p, x.enthalpy(), BASIS, z, PhaseCompetition.FLUID_ONLY), a);
                        var g = network.ph(EquilibriumRequest.ph(p, y.enthalpy(), BASIS, z, PhaseCompetition.FLUID_ONLY), b);
                        assertEquals(g.toString(), h.toString());
                    }
                    compared++;
                }
            }
        }
        // A fluid-only request answers CO2 below its triple point as a fluid (no freezing-point floor since the owner rule of
        // 2026-10-16); only the crystal competition lets it freeze.
        var cold = SERVICE.tp(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), PhaseCompetition.FLUID_ONLY), a);
        assertEquals(Status.CONVERGED, cold.status(), cold.toString());
        assertFalse(cold.solidsAssessed());
        System.out.println("fluid-only requests compared bit for bit: " + compared);
    }

    /** The pure crystal species' sublimation temperature on the pilot family at pressure p (the stage-1 pure path). */
    static double pureSublimationTemperature(double p) {
        var family = SolidCarbonDioxideQualificationTest.Family.of(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot());
        double t0 = 190.0, t1 = 200.0;
        double f0 = Math.log(family.sublimation(t0) / p), f1 = Math.log(family.sublimation(t1) / p);
        for (int k = 0; k < 60 && Math.abs(t1 - t0) > 1e-12; k++) {
            double t2 = t1 - f1 * (t1 - t0) / (f1 - f0);
            t0 = t1;
            f0 = f1;
            t1 = t2;
            f1 = Math.log(family.sublimation(t1) / p);
        }
        return t1;
    }

    /** (a) The pure limit: CO2 with a 1e-6 trace of N2 frosts at the pure sublimation temperature (1e-3 K). */
    @Test
    void thePureLimitIsThePureSublimationLine() {
        var ws = SERVICE.newWorkspace();
        System.out.println("P MPa | T pure path K | onset z_N2=1e-6 K | diff K | onset pure K | diff K | partial-pressure T K");
        double worst = 0;
        for (double p : new double[] {1.0e5, 3.0e5, 5.0e5}) {
            double pure = pureSublimationTemperature(p);
            var trace = SERVICE.depositionTemperature(null, p, BASIS, feed(1.0e-6, 0, 0, 1.0 - 1.0e-6), ws);
            var alone = SERVICE.depositionTemperature(null, p, BASIS, feed(0, 0, 0, 1.0), ws);
            assertTrue(trace.converged() && alone.converged(), trace + " / " + alone);
            System.out.printf(Locale.ROOT, "%.1f | %.9f | %.9f | %+.2e | %.9f | %+.2e | %.9f%n", p / 1e6, pure, trace.temperature(),
                    trace.temperature() - pure, alone.temperature(), alone.temperature() - pure, trace.approximation());
            worst = Math.max(worst, Math.abs(trace.temperature() - pure));
            assertEquals(pure, alone.temperature(), 1.0e-8);
            assertEquals(CRYSTAL, trace.crystal());
            assertEquals(Classification.SINGLE_VAPOR, trace.fluid().classification(), trace.fluid().toString());
        }
        System.out.printf(Locale.ROOT, "worst |onset - pure| %.3e K (bound 1e-3 K)%n", worst);
        assertTrue(worst <= 1.0e-3);
    }

    /**
     * (b) Conservation and equal chemical potentials at the deposition states of the grid (CO2 in N2, y 0.001 to 0.5,
     * 130 to 210 K, 0.1 to 5 MPa), independently checked from the returned phases: the conservation defect, the vapour's
     * CO2 chemical potential against the crystal's, the vapour's own stability (a fluid-only flash of its amounts on an
     * open contract is one phase), and a Gibbs energy below the fluid-only answer's; plus the complete depositions of
     * pure CO2.
     */
    @Test
    void depositionConservesAndEqualisesTheChemicalPotentials() {
        var ws = SERVICE.newWorkspace();
        var open = new FluidTpEquilibrium(PhaseTestSupport.openContract(SERVICE.evaluator()), SERVICE.evaluator(), FluidTpEquilibrium.Settings.DEFAULT);
        var ows = open.newWorkspace();
        List<String> eos = SERVICE.evaluator().components();
        int deposits = 0, noSolid = 0, holds = 0, fromMetastableLiquid = 0, besideLiquid = 0;
        double worstConservation = 0, worstMu = 0, worstGibbs = Double.NEGATIVE_INFINITY;
        int maxIterations = 0;
        List<String> heldStates = new ArrayList<>();
        System.out.println("y | T K | P MPa | outcome | solid mol | |mu_V - mu_s|/RT | conservation | Newton | fluid-only answer");
        for (double y : GRID_Y) {
            for (double t : GRID_T) {
                for (double p : GRID_P) {
                    double[] z = inNitrogen(y);
                    var r = tp(t, p, z, ws);
                    var fluidOnly = open.tp(EquilibriumRequest.tp(t, p, eos, Arrays.copyOf(z, 4), PhaseCompetition.FLUID_ONLY), ows);
                    if (r.status() == Status.UNSUPPORTED) {
                        assertTrue(r.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES), r.toString());
                        assertEquals(UnsupportedReason.NOT_IMPLEMENTED, r.reason());
                        holds++;
                        heldStates.add(y + "/" + t + "/" + p / 1e6);
                        continue;
                    }
                    assertEquals(Status.CONVERGED, r.status(), r.toString());
                    if (r.classification() == Classification.LIQUID_SOLID || r.classification() == Classification.VAPOR_LIQUID_SOLID) {
                        // P5: a crystal beside a liquid (P4's hold): conserved, every fluid phase at the crystal's chemical potential.
                        besideLiquid++;
                        PhaseAmounts crystal = solid(r);
                        worstConservation = Math.max(worstConservation, r.conservationDefect(z));
                        for (PhaseAmounts phase : r.phases()) {
                            if (phase.kind() == PhaseKind.SOLID) continue;
                            worstMu = Math.max(worstMu, Math.abs(phase.state().chemicalPotential(CO2) - crystal.state().molarGibbsEnergy()) / (R * t));
                        }
                        System.out.printf(Locale.ROOT, "%.3f | %.0f | %.1f | %s | %.10g | | %.1e | %d | %s%n", y, t, p / 1e6, r.classification(),
                                crystal.amount(CO2), r.conservationDefect(z), r.deposition().iterations(), fluidOnly.classification());
                        continue;
                    }
                    if (r.classification() != Classification.VAPOR_SOLID) {
                        assertNull(solid(r));
                        noSolid++;
                        continue;
                    }
                    deposits++;
                    PhaseAmounts s = solid(r), v = vapour(r);
                    assertEquals(PhaseKind.SOLID, s.kind());
                    assertTrue(v.kind() == PhaseKind.VAPOR || v.kind() == PhaseKind.FLUID, v.toString());
                    double conservation = r.conservationDefect(z);
                    double mu = Math.abs(v.state().chemicalPotential(CO2) - s.state().molarGibbsEnergy()) / (R * t);
                    worstConservation = Math.max(worstConservation, conservation);
                    worstMu = Math.max(worstMu, mu);
                    maxIterations = Math.max(maxIterations, r.deposition().iterations());
                    assertEquals(s.amount(CO2), r.deposition().solidAmount(), 0.0);
                    assertFalse(r.deposition().complete());
                    assertTrue(r.deposition().feedDrivingForce() > 0.0);
                    // The vapour is a stable single phase of the fluid model (the reason the fluid-only liquid is metastable).
                    var alone = open.tp(EquilibriumRequest.tp(t, p, eos, Arrays.copyOf(v.amountsView(), 4), PhaseCompetition.FLUID_ONLY), ows);
                    assertEquals(1, alone.phases().size(), alone.toString());
                    // The crystal lowers the Gibbs energy below the fluid-only answer.
                    double gain = (r.gibbsEnergy() - fluidOnly.gibbsEnergy()) / (R * t);
                    worstGibbs = Math.max(worstGibbs, gain);
                    if (fluidOnly.phases().size() > 1) fromMetastableLiquid++;
                    System.out.printf(Locale.ROOT, "%.3f | %.0f | %.1f | %s | %.10g | %.2e | %.1e | %d | %s%n", y, t, p / 1e6, r.classification(),
                            s.amount(CO2), mu, conservation, r.deposition().iterations(), fluidOnly.classification());
                }
            }
        }
        // Complete deposition: pure CO2 below its sublimation line is its crystal alone, the fluid vanishes.
        int complete = 0;
        for (double[] state : new double[][] {{150, 1.0e5}, {180, 1.0e5}, {190, 1.0e5}, {200, 1.0e6}, {120, 5.0e6}}) {
            double[] z = feed(0, 0, 0, 2.5);
            var r = tp(state[0], state[1], z, ws);
            assertEquals(Classification.SOLID_PRESENT, r.classification(), r.toString());
            assertEquals(1, r.phases().size());
            assertTrue(r.deposition().complete() && Double.isNaN(r.deposition().residual()));
            assertEquals(2.5, r.deposition().solidAmount(), 0.0);
            assertEquals(0.0, r.conservationDefect(z), 0.0);
            complete++;
        }
        System.out.printf(Locale.ROOT, "deposits %d, crystal beside a liquid (P5) %d, no solid %d, typed holds %d (%s), complete depositions of pure"
                        + " CO2 %d; deposits whose fluid-only answer held a (metastable) liquid %d%n", deposits, besideLiquid, noSolid, holds, heldStates,
                complete, fromMetastableLiquid);
        System.out.printf(Locale.ROOT, "worst conservation %.2e (bound 1e-13), worst |mu_V - mu_s|/RT %.2e (bound 1e-9), Newton steps at most %d, "
                + "worst (G - G_fluid-only)/RT %.3e%n", worstConservation, worstMu, maxIterations, worstGibbs);
        assertTrue(deposits >= 40, "at least 40 deposition states, got " + deposits);
        assertEquals(0, holds, "no crystal-phase hold on the grid of (b) since P5: " + heldStates);
        assertTrue(worstConservation <= 1.0e-13);
        assertTrue(worstMu <= 1.0e-9);
        assertTrue(worstGibbs < 0.0);
        assertTrue(maxIterations <= 8);
    }

    /** (d) The partial-pressure approximation against the fugacity onset at the (y, P) of the grid of (b). */
    @Test
    void thePartialPressureApproximationAgainstTheFugacityOnset() {
        var ws = SERVICE.newWorkspace();
        System.out.println("y | P MPa | onset T K (fugacity) | partial-pressure T K | approx - onset K | status");
        double sum = 0, worst = 0;
        int n = 0, holds = 0, outside = 0;
        for (double y : GRID_Y) {
            for (double p : GRID_P) {
                var onset = SERVICE.depositionTemperature(null, p, BASIS, inNitrogen(y), ws);
                if (onset.converged() && Double.isNaN(onset.approximation())) {
                    // y P above the triple pressure: the pure species has no sublimation point there, no approximation.
                    System.out.printf(Locale.ROOT, "%.3f | %.1f | %.4f | n/a (y P above p_t) | | %s%n", y, p / 1e6, onset.temperature(),
                            onset.fluid().classification());
                    continue;
                }
                if (!onset.converged()) {
                    System.out.printf(Locale.ROOT, "%.3f | %.1f | | %.4f | | %s: %s%n", y, p / 1e6, onset.approximation(), onset.status(), onset.detail());
                    if (onset.status() == Status.UNSUPPORTED) holds++; else outside++;
                    assertTrue(onset.status() == Status.UNSUPPORTED && onset.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES)
                            || onset.status() == Status.NOT_CONVERGED && onset.detail().contains("window"), onset.toString());
                    continue;
                }
                double d = onset.approximation() - onset.temperature();
                System.out.printf(Locale.ROOT, "%.3f | %.1f | %.4f | %.4f | %+.4f | %s%n", y, p / 1e6, onset.temperature(), onset.approximation(), d,
                        onset.fluid().classification());
                assertTrue(onset.residual() <= FluidTpEquilibrium.DEPOSITION_TOLERANCE);
                assertTrue(Double.isFinite(d));
                sum += Math.abs(d);
                worst = Math.max(worst, Math.abs(d));
                n++;
            }
        }
        System.out.printf(Locale.ROOT, "onsets %d, holds %d, beyond the window %d; |approx - onset| mean %.3f K, worst %.3f K%n", n, holds, outside, sum / n, worst);
        assertTrue(n >= 25);
    }

    /**
     * (e) Jaeger and Span 2012 Figure 13: CO2 with 0.1, 1 and 5 % N2, the fugacity-based sublimation pressure against the
     * ideal {@code p_sub,pure(T)/x_CO2}, {@code 100 (p - p_ideal)/p}, 150 to 216 K. Their figure (Span-Wagner, Span et
     * al. N2, GERG-2004 mixing) is positive, below about 0.35 % for 5 % N2 at 216 K, 0.06 % for 1 % and 0.01 % for
     * 0.1 %, and grows with temperature and with N2.
     */
    @Test
    void jaegerSpanFigureThirteenMixtures() {
        var ws = SERVICE.newWorkspace();
        double[] fractions = {0.999, 0.99, 0.95};
        double[] temperatures = {150, 160, 170, 180, 190, 200, 205, 210, 214, 216};
        double[][] deviation = new double[fractions.length][temperatures.length];
        System.out.println("T K | 100 dp/p x=0.999 | x=0.99 | x=0.95 | p x=0.95 kPa");
        for (int j = 0; j < temperatures.length; j++) {
            double lastPressure = Double.NaN;
            for (int k = 0; k < fractions.length; k++) {
                double x = fractions[k];
                var onset = SERVICE.depositionPressure(null, temperatures[j], BASIS, inNitrogen(x), ws);
                assertTrue(onset.converged(), onset.toString());
                deviation[k][j] = 100.0 * (onset.pressure() - onset.approximation()) / onset.pressure();
                lastPressure = onset.pressure();
            }
            System.out.printf(Locale.ROOT, "%.0f | %+.5f | %+.5f | %+.5f | %.3f%n", temperatures[j], deviation[0][j], deviation[1][j], deviation[2][j],
                    lastPressure / 1e3);
        }
        for (int k = 0; k < fractions.length; k++) {
            for (int j = 0; j < temperatures.length; j++) {
                assertTrue(deviation[k][j] > 0.0, "positive as in the figure");
                assertTrue(deviation[k][j] < 1.0, "below one percent, the figure's order of magnitude");
                if (j > 0) assertTrue(deviation[k][j] > deviation[k][j - 1], "grows with temperature");
                if (k > 0) assertTrue(deviation[k][j] > deviation[k - 1][j], "grows with N2");
            }
        }
    }

    /**
     * (c) PH and UV with the crystal: round trips from TP deposition states, a vessel cooled below its frost point and
     * warmed back (reversibility), and exhaustion (the solid shrinks to nothing at the frost point as the vessel warms).
     */
    @Test
    void phAndUvRoundTripsReversibilityAndExhaustion() {
        var ws = SERVICE.newWorkspace();
        double worstT = 0, worstP = 0, worstSolid = 0, worstH = 0, worstU = 0, worstHScaled = 0;
        int trips = 0, phCalls = 0, uvCalls = 0, phMax = 0, uvMax = 0;
        System.out.println("round trips: y | T K | P MPa | PH TP calls | UV TP calls | dT/T PH | dT/T UV | dP/P UV | d n_s / z_CO2");
        for (double y : new double[] {0.001, 0.01, 0.1, 0.3, 0.5}) {
            for (double[] state : new double[][] {{130, 1.0e5}, {150, 1.0e6}, {170, 5.0e5}, {190, 5.0e5}, {170, 2.0e6}, {210, 5.0e6}}) {
                double t = state[0], p = state[1];
                double[] z = inNitrogen(y);
                var r = tp(t, p, z, ws);
                if (r.classification() != Classification.VAPOR_SOLID) continue;
                var ph = SERVICE.ph(EquilibriumRequest.ph(p, r.enthalpy(), BASIS, z, CRYSTALS), ws);
                var uv = SERVICE.uv(EquilibriumRequest.uv(r.internalEnergy(), r.volume(), BASIS, z, CRYSTALS), ws);
                assertEquals(Classification.VAPOR_SOLID, ph.classification(), ph.toString());
                assertEquals(Classification.VAPOR_SOLID, uv.classification(), uv.toString());
                double tPh = ph.phases().get(0).temperature(), tUv = uv.phases().get(0).temperature(), pUv = uv.phases().get(0).pressure();
                double solid0 = solid(r).amount(CO2);
                double dSolid = Math.max(Math.abs(solid(ph).amount(CO2) - solid0), Math.abs(solid(uv).amount(CO2) - solid0)) / z[CO2];
                worstT = Math.max(worstT, Math.max(Math.abs(tPh / t - 1), Math.abs(tUv / t - 1)));
                worstP = Math.max(worstP, Math.abs(pUv / p - 1));
                worstSolid = Math.max(worstSolid, dSolid);
                worstH = Math.max(worstH, Math.abs(ph.enthalpy() - r.enthalpy()) / Math.abs(r.enthalpy()));
                worstU = Math.max(worstU, Math.abs(uv.internalEnergy() - r.internalEnergy()) / Math.abs(r.internalEnergy()));
                worstHScaled = Math.max(worstHScaled, Math.abs(ph.enthalpy() - r.enthalpy()) / (R * t));
                assertTrue(ph.conservationDefect(z) <= 1.0e-13 && uv.conservationDefect(z) <= 1.0e-13);
                phCalls += ph.diagnostics().outerIterations();
                uvCalls += uv.diagnostics().outerIterations();
                phMax = Math.max(phMax, ph.diagnostics().outerIterations());
                uvMax = Math.max(uvMax, uv.diagnostics().outerIterations());
                trips++;
                System.out.printf(Locale.ROOT, "%.3f | %.0f | %.1f | %d | %d | %.1e | %.1e | %.1e | %.1e%n", y, t, p / 1e6,
                        ph.diagnostics().outerIterations(), uv.diagnostics().outerIterations(), tPh / t - 1, tUv / t - 1, pUv / p - 1, dSolid);
            }
        }
        System.out.printf(Locale.ROOT, "%d round trips: worst dT/T %.1e, dP/P %.1e, d n_s/z_CO2 %.1e, |dH|/|H| %.1e, |dU|/|U| %.1e, |dH|/(RT) %.1e; "
                + "PH TP calls mean %.1f max %d, UV mean %.1f max %d%n", trips, worstT, worstP, worstSolid, worstH, worstU, worstHScaled,
                (double) phCalls / trips, phMax, (double) uvCalls / trips, uvMax);
        assertTrue(trips >= 15, "round trips " + trips);
        assertTrue(worstT <= 1.0e-9 && worstP <= 1.0e-9 && worstSolid <= 1.0e-9);
        assertTrue(worstH <= 1.0e-11 && worstU <= 1.0e-11);

        // Reversibility: a vessel of 10 % CO2 in N2 at 1 MPa, 200 K (vapour) cooled at fixed P and at fixed V, then warmed back.
        double[] z = inNitrogen(0.1);
        var a = tp(200.0, 1.0e6, z, ws);
        assertTrue(a.phases().size() == 1 && a.phases().get(0).kind() == PhaseKind.VAPOR, a.toString());
        var frost = SERVICE.depositionTemperature(null, 1.0e6, BASIS, z, ws);
        assertTrue(frost.converged());
        double heat = 3000.0;
        var cooled = SERVICE.ph(EquilibriumRequest.ph(1.0e6, a.enthalpy() - heat, BASIS, z, CRYSTALS), ws);
        assertEquals(Classification.VAPOR_SOLID, cooled.classification(), cooled.toString());
        var warmed = SERVICE.ph(EquilibriumRequest.ph(1.0e6, cooled.enthalpy() + heat, BASIS, z, CRYSTALS), ws);
        assertEquals(a.classification(), warmed.classification(), warmed.toString());
        assertTrue(solid(warmed) == null && warmed.phases().get(0).kind() == PhaseKind.VAPOR);
        assertEquals(200.0, warmed.phases().get(0).temperature(), 200.0 * 1.0e-9);
        var cooledV = SERVICE.uv(EquilibriumRequest.uv(a.internalEnergy() - heat, a.volume(), BASIS, z, CRYSTALS), ws);
        assertEquals(Classification.VAPOR_SOLID, cooledV.classification(), cooledV.toString());
        var warmedV = SERVICE.uv(EquilibriumRequest.uv(cooledV.internalEnergy() + heat, cooledV.volume(), BASIS, z, CRYSTALS), ws);
        assertEquals(a.classification(), warmedV.classification(), warmedV.toString());
        assertNull(solid(warmedV));
        assertEquals(200.0, warmedV.phases().get(0).temperature(), 200.0 * 1.0e-9);
        assertEquals(1.0e6, warmedV.phases().get(0).pressure(), 1.0e6 * 1.0e-9);
        System.out.printf(Locale.ROOT, "reversibility: frost %.6f K; cooled by %.0f J at 1 MPa to %.6f K with %.8f mol solid; at fixed V to %.6f K, %.6f MPa "
                        + "with %.8f mol; warmed back to %.12f K (PH) and %.12f K, %.12f MPa (UV)%n", frost.temperature(), heat,
                cooled.phases().get(0).temperature(), solid(cooled).amount(CO2), cooledV.phases().get(0).temperature(),
                cooledV.phases().get(0).pressure() / 1e6, solid(cooledV).amount(CO2), warmed.phases().get(0).temperature(),
                warmedV.phases().get(0).temperature(), warmedV.phases().get(0).pressure() / 1e6);

        // Exhaustion: warming at 1 MPa the solid shrinks monotonically and vanishes at the frost point; T(H) increases.
        double hFrost = frost.fluid().enthalpy();
        double previousT = 0, previousSolid = Double.POSITIVE_INFINITY;
        System.out.println("exhaustion at 1 MPa: H - H_frost J | T K | solid mol");
        for (int k = -12; k <= 4; k++) {
            double h = hFrost + 250.0 * k;
            var r = SERVICE.ph(EquilibriumRequest.ph(1.0e6, h, BASIS, z, CRYSTALS), ws);
            assertTrue(r.converged(), r.toString());
            double tk = r.phases().get(0).temperature();
            PhaseAmounts s = solid(r);
            double amount = s == null ? 0.0 : s.amount(CO2);
            System.out.printf(Locale.ROOT, "%+.0f | %.6f | %.10f%n", 250.0 * k, tk, amount);
            assertTrue(tk > previousT && amount <= previousSolid);
            if (k > 0) assertNull(s, "exhausted above the frost point");
            if (k < 0) assertNotNull(s);
            previousT = tk;
            previousSolid = amount;
        }
        var atFrost = SERVICE.ph(EquilibriumRequest.ph(1.0e6, hFrost, BASIS, z, CRYSTALS), ws);
        System.out.printf(Locale.ROOT, "PH at the frost point's enthalpy: %.9f K (onset %.9f K), solid %s%n", atFrost.phases().get(0).temperature(),
                frost.temperature(), solid(atFrost) == null ? "none" : solid(atFrost).amount(CO2));
        assertEquals(frost.temperature(), atFrost.phases().get(0).temperature(), 1.0e-6);
    }

    /**
     * Pure CO2: its sublimation step in PH (fixed P) and UV (fixed T) from the balance, the sublimation enthalpy from
     * the step (D16 declares 0.25 % at 195 K against 25.2 kJ/mol), and round trips through it.
     */
    @Test
    void pureCarbonDioxideSublimatesThroughTheBalance() {
        var ws = SERVICE.newWorkspace();
        double[] z = feed(0, 0, 0, 1.0);
        double p = 1.0e5;
        var solidEnd = tp(190.0, p, z, ws);
        var vapourEnd = tp(200.0, p, z, ws);
        assertEquals(Classification.SOLID_PRESENT, solidEnd.classification());
        assertEquals(Classification.SINGLE_VAPOR, vapourEnd.classification());
        double tSub = pureSublimationTemperature(p);
        EquilibriumResult half = null;
        var low = SERVICE.ph(EquilibriumRequest.ph(p, solidEnd.enthalpy(), BASIS, z, CRYSTALS), ws);
        assertEquals(190.0, low.phases().get(0).temperature(), 190.0 * 1.0e-9);
        // The two saturated states at T_sub: their enthalpy gap is the sublimation enthalpy.
        var sat = SERVICE.ph(EquilibriumRequest.ph(p, 0.5 * (solidEnd.enthalpy() + vapourEnd.enthalpy()), BASIS, z, CRYSTALS), ws);
        assertEquals(Classification.VAPOR_SOLID, sat.classification(), sat.toString());
        assertTrue(sat.diagnostics().coexistenceFromBalance());
        double t = sat.phases().get(0).temperature();
        assertEquals(tSub, t, 1.0e-9);
        double hV = vapour(sat).state().molarEnthalpy(), hS = solid(sat).state().molarEnthalpy();
        double sublimation = hV - hS;
        System.out.printf(Locale.ROOT, "pure CO2 at 0.1 MPa: T_sub %.9f K (pure path %.9f K), sublimation enthalpy %.1f J/mol (%+.3f %% of 25.2 kJ/mol)%n",
                t, tSub, sublimation, 100 * (sublimation / 25200 - 1));
        assertEquals(25200.0, sublimation, 0.003 * 25200.0);
        for (double beta : new double[] {1.0e-3, 0.25, 0.5, 0.75, 0.999}) {
            double h = hS + beta * (hV - hS);
            var r = SERVICE.ph(EquilibriumRequest.ph(p, h, BASIS, z, CRYSTALS), ws);
            assertEquals(Classification.VAPOR_SOLID, r.classification(), r.toString());
            assertEquals(beta, vapour(r).amount(CO2), 1.0e-9);
            assertEquals(h, r.enthalpy(), 1.0e-9 * Math.abs(h));
            assertEquals(0.0, r.conservationDefect(z), 1.0e-15);
            // UV at the same state: the same split from the volume balance at the fixed temperature.
            var uv = SERVICE.uv(EquilibriumRequest.uv(r.internalEnergy(), r.volume(), BASIS, z, CRYSTALS), ws);
            assertEquals(Classification.VAPOR_SOLID, uv.classification(), uv.toString());
            assertEquals(t, uv.phases().get(0).temperature(), 1.0e-9 * t);
            assertEquals(p, uv.phases().get(0).pressure(), 1.0e-8 * p);
            assertEquals(beta, vapour(uv).amount(CO2), 1.0e-8);
            System.out.printf(Locale.ROOT, "beta %.3f: PH T %.9f (%d TP calls), UV T %.9f P %.3f Pa (%d TP calls)%n", beta,
                    r.phases().get(0).temperature(), r.diagnostics().outerIterations(), uv.phases().get(0).temperature(),
                    uv.phases().get(0).pressure(), uv.diagnostics().outerIterations());
            half = r;
        }
        assertNotNull(half);
        // Above the triple pressure the step is melting (P5: answered from the energy balance, never a vapour-solid one; 1 MPa,
        // between the solid at 216 K and the liquid at 225 K; the liquid boils at 233.26 K, an ordinary VL coexistence).
        var solid216 = tp(216.0, 1.0e6, z, ws);
        var liquid225 = tp(225.0, 1.0e6, z, ws);
        assertEquals(Classification.SOLID_PRESENT, solid216.classification(), solid216.toString());
        assertEquals(PhaseKind.LIQUID, liquid225.phases().get(0).kind(), liquid225.toString());
        var melting = SERVICE.ph(EquilibriumRequest.ph(1.0e6, 0.5 * (solid216.enthalpy() + liquid225.enthalpy()), BASIS, z, CRYSTALS), ws);
        assertEquals(Classification.LIQUID_SOLID, melting.classification(), melting.toString());
        assertTrue(melting.diagnostics().coexistenceFromBalance());
        var meltingPoint = SERVICE.freezingTemperature(null, 1.0e6, BASIS, z, ws);
        assertEquals(meltingPoint.temperature(), melting.phases().get(0).temperature(), 1.0e-8);
        System.out.println("PH across the melting of pure CO2 at 1 MPa: " + melting);
    }

    /** (4) The crystal's derivatives against central differences, its capability flags, and the anchored view. */
    @Test
    void theCrystalsDerivativesAndItsAnchoredView() {
        MaterialCatalog catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var fluid = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var crystal = CrystalPhaseEvaluator.forPackage(catalog, PILOT, CRYSTAL);
        var anchor = crystal.anchor(fluid);
        var view = crystal.anchored(anchor);
        double worst = 0;
        for (double[] state : new double[][] {{100, 1.0e5}, {150, 1.0e5}, {194.7, 1.0e5}, {216, 5.0e6}, {250, 1.0e8}}) {
            double t = state[0], p = state[1];
            var d = crystal.derivatives(t, p, anchor, new PhaseDerivatives(1));
            assertEquals(crystal.family(), d.family());
            // The same state as the evaluator's (bit for bit through the anchored view and the record's own path).
            PhaseState direct = crystal.evaluate(t, p, anchor, new PhaseState(1));
            assertEquals(direct.toString(), d.state().toString());
            var record = crystal.record().state(t, p, anchor.chemicalPotential(), anchor.liquidEnthalpy());
            assertEquals(record.gibbs(), view.chemicalPotential(t, p), 0.0);
            assertEquals(record.enthalpy(), direct.molarEnthalpy(), Math.ulp(record.enthalpy()) * 4);
            double ht = 1.0e-4 * t, hp = 1.0e-4 * p;
            PhaseState tPlus = crystal.evaluate(t + ht, p, anchor, new PhaseState(1)), tMinus = crystal.evaluate(t - ht, p, anchor, new PhaseState(1));
            PhaseState pPlus = crystal.evaluate(t, p + hp, anchor, new PhaseState(1)), pMinus = crystal.evaluate(t, p - hp, anchor, new PhaseState(1));
            double[][] pairs = {
                    {d.logFugacityTemperatureDerivativeView()[0], (tPlus.logFugacityCoefficient(0) - tMinus.logFugacityCoefficient(0)) / (2 * ht)},
                    {d.logFugacityPressureDerivativeView()[0], (pPlus.logFugacityCoefficient(0) - pMinus.logFugacityCoefficient(0)) / (2 * hp)},
                    {d.volumeTemperatureDerivative(), (tPlus.molarVolume() - tMinus.molarVolume()) / (2 * ht)},
                    {d.volumePressureDerivative(), (pPlus.molarVolume() - pMinus.molarVolume()) / (2 * hp)},
                    {d.enthalpyTemperatureDerivative(), (tPlus.molarEnthalpy() - tMinus.molarEnthalpy()) / (2 * ht)},
                    {d.enthalpyPressureDerivative(), (pPlus.molarEnthalpy() - pMinus.molarEnthalpy()) / (2 * hp)},
                    // d mu/dT = -s and d mu/dP = v.
                    {-direct.molarEntropy(), (tPlus.molarGibbsEnergy() - tMinus.molarGibbsEnergy()) / (2 * ht)},
                    {direct.molarVolume(), (pPlus.molarGibbsEnergy() - pMinus.molarGibbsEnergy()) / (2 * hp)}};
            for (double[] pair : pairs) {
                double error = Math.abs(pair[0] - pair[1]) / Math.max(Math.abs(pair[0]), 1e-300);
                worst = Math.max(worst, error);
            }
        }
        System.out.printf(Locale.ROOT, "crystal derivatives against central differences: worst relative %.2e%n", worst);
        assertTrue(worst <= 1.0e-6);
        // The anchored view: constants solved once, the same numbers as the per-call path.
        assertTrue(Arrays.equals(view.integrationConstants(), crystal.anchored(anchor).integrationConstants()));
        double[] constants = crystal.record().integrationConstants(anchor.chemicalPotential(), anchor.liquidEnthalpy());
        assertTrue(Arrays.equals(constants, view.integrationConstants()));
        // The service's anchor is the evaluator's own: the same chemical potential at every state.
        var ws = SERVICE.newWorkspace();
        var r = tp(150.0, 1.0e6, inNitrogen(0.01), ws);
        assertEquals(view.chemicalPotential(150.0, 1.0e6), solid(r).state().molarGibbsEnergy(), 0.0);
    }

    /**
     * (f) Typed refusals: the crystal-phase holds that remain after P5 (two liquids, three fluid phases beside the crystal),
     * packages without crystals, water; past the data the answers continue, graded extrapolated (owner rule of 2026-10-16).
     * P4's solid-liquid holds are answers since P5 (checked here as such).
     */
    @Test
    void refusalsAreTyped() {
        var ws = SERVICE.newWorkspace();
        // P4's holds, answered since P5: CO2 freezing out of liquid methane (5 % CO2 at 130 K, 1 MPa) is a liquid beside the
        // crystal; 0.1 % CO2 dissolved in liquid methane stays one liquid (below its liquidus); pure CO2 at 217 K and 8 MPa,
        // above its melting pressure there (about 2.4 MPa), is its crystal alone.
        var fromLiquid = tp(130.0, 1.0e6, feed(0, 0.95, 0, 0.05), ws);
        assertEquals(Classification.LIQUID_SOLID, fromLiquid.classification(), fromLiquid.toString());
        var dissolved = tp(130.0, 1.0e6, feed(0, 0.999, 0, 0.001), ws);
        assertEquals(Classification.SINGLE_LIQUID, dissolved.classification(), dissolved.toString());
        assertTrue(dissolved.solidsAssessed() && dissolved.detail().contains("no crystal forms"), dissolved.detail());
        var frozen = tp(217.0, 8.0e6, feed(0, 0, 0, 1), ws);
        assertEquals(Classification.SOLID_PRESENT, frozen.classification(), frozen.toString());
        // The holds that remain: the fluid the crystal leaves is two liquids (N2/C2H6 type III at 95 K, 2 MPa), or a split
        // with a product proved unstable at the crystal's chemical potential (three fluid phases: N2-rich at 125 K, 3 MPa).
        var twoLiquids = tp(95.0, 2.0e6, feed(0.4, 0, 0.55, 0.05), ws);
        assertEquals(Status.UNSUPPORTED, twoLiquids.status(), twoLiquids.toString());
        assertEquals(UnsupportedReason.NOT_IMPLEMENTED, twoLiquids.reason());
        assertTrue(twoLiquids.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES) && twoLiquids.detail().contains("two liquids"), twoLiquids.detail());
        assertTrue(twoLiquids.phases().isEmpty() && twoLiquids.diagnostics().kernelEvaluations() > 0);
        var threeFluids = tp(125.0, 3.0e6, feed(0.94, 0, 0.05, 0.01), ws);
        assertEquals(Status.UNSUPPORTED, threeFluids.status(), threeFluids.toString());
        assertTrue(threeFluids.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES) && threeFluids.detail().contains("three fluid phases"),
                threeFluids.detail());
        var melting = twoLiquids;
        // Without CO2 the crystal cannot form, and a liquid is fine: solids assessed.
        var methane = tp(130.0, 1.0e6, feed(0, 1, 0, 0), ws);
        assertEquals(Classification.SINGLE_LIQUID, methane.classification(), methane.toString());
        assertTrue(methane.solidsAssessed() && methane.detail().contains("no competing crystal's species"));
        // Past the data of the crystal competition (CO2's spine and crystal record from 90 K, the 10 MPa envelope) the answers
        // continue, graded extrapolated (owner rule of 2026-10-16; out of domain before). Above the crystal's 300 K ceiling,
        // where it ceases to exist, the fluid answers alone.
        for (double[] state : new double[][] {{85, 1.0e5}, {150, 1.2e7}}) {
            var r = tp(state[0], state[1], inNitrogen(0.01), ws);
            assertEquals(Status.CONVERGED, r.status(), r.toString());
            assertEquals(CoverageGrade.EXTRAPOLATED, r.coverage().grade(), r.coverage().evidence());
            System.out.println("extrapolated at " + state[0] + " K, " + state[1] + " Pa: " + r.coverage().evidence());
        }
        var warm = tp(310.0, 1.0e6, inNitrogen(0.01), ws);
        assertEquals(Status.CONVERGED, warm.status(), warm.toString());
        assertTrue(warm.phases().stream().noneMatch(phase -> phase.kind() == PhaseKind.SOLID), warm.toString());
        // The onsets: past the data an extrapolated answer; a fixed temperature above the ceiling has no crystal to search.
        var ceiling = SERVICE.depositionTemperature(null, 1.2e7, BASIS, inNitrogen(0.01), ws);
        assertEquals(Status.CONVERGED, ceiling.status(), ceiling.toString());
        assertEquals(CoverageGrade.EXTRAPOLATED, ceiling.coverage().grade());
        var tooCold = SERVICE.depositionTemperature(null, 1.0e5, BASIS, inNitrogen(1.0e-9), ws);
        assertEquals(Status.CONVERGED, tooCold.status(), tooCold.toString());
        assertTrue(tooCold.temperature() < 90.0, "below CO2's 90 K data: " + tooCold.temperature());
        assertEquals(CoverageGrade.EXTRAPOLATED, tooCold.coverage().grade());
        var tooWarm = SERVICE.depositionPressure(null, 305.0, BASIS, inNitrogen(0.5), ws);
        assertEquals(Status.NOT_CONVERGED, tooWarm.status(), tooWarm.toString());
        assertTrue(tooWarm.detail().contains("crystals cease to exist"), tooWarm.detail());
        var compressed = SERVICE.depositionPressure(null, 250.0, BASIS, inNitrogen(0.5), ws);
        System.out.println("50 % CO2 in N2 at 250 K, the onset past 10 MPa: " + compressed);
        assertTrue(compressed.converged() ? compressed.pressure() > 1.0e7 && compressed.coverage().grade() == CoverageGrade.EXTRAPOLATED
                : compressed.detail().contains("window"), compressed.toString());
        // A crystal the package does not have.
        var foreign = SERVICE.tp(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), PhaseCompetition.withCrystals("icheme:crystal_ammonia")), ws);
        assertEquals(UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, foreign.reason());
        // An onset without a crystal species is a malformed question.
        assertThrows(IllegalArgumentException.class, () -> SERVICE.depositionTemperature(null, 1.0e5, BASIS, feed(1, 0, 0, 0), ws));
        System.out.println("answered (P4 hold): " + fromLiquid);
        System.out.println("answered (P4 hold): " + dissolved);
        System.out.println("answered (P4 hold): " + frozen);
        System.out.println("hold: " + twoLiquids.detail());
        System.out.println("hold: " + threeFluids.detail() + " / " + melting.status());
    }

    /** A reused workspace gives crystal answers bit for bit equal to fresh workspaces, in any order. */
    @Test
    void crystalAnswersAreBitwiseRepeatable() {
        List<EquilibriumRequest> requests = new ArrayList<>();
        requests.add(EquilibriumRequest.tp(150.0, 1.0e6, BASIS, inNitrogen(0.01), CRYSTALS));
        requests.add(EquilibriumRequest.tp(190.0, 5.0e5, BASIS, inNitrogen(0.5), CRYSTALS));
        requests.add(EquilibriumRequest.tp(180.0, 1.0e5, BASIS, feed(0, 0, 0, 1), CRYSTALS));
        requests.add(EquilibriumRequest.tp(130.0, 1.0e6, BASIS, feed(0, 0.95, 0, 0.05), CRYSTALS));
        var reference = new ArrayList<String>();
        for (EquilibriumRequest request : requests) reference.add(describe(SERVICE.tp(request, SERVICE.newWorkspace())));
        var ws = SERVICE.newWorkspace();
        for (int pass = 0; pass < 2; pass++) {
            for (int k = requests.size() - 1; k >= 0; k--) assertEquals(reference.get(k), describe(SERVICE.tp(requests.get(k), ws)));
            for (int k = 0; k < requests.size(); k++) assertEquals(reference.get(k), describe(SERVICE.tp(requests.get(k), ws)));
        }
    }

    /**
     * The general crystal solve stops on what its answer is judged on: every fluid phase at the crystal's chemical
     * potential. A vapour-liquid split's phases agree on {@code mu_i} only to the flash's fugacity tolerance (1e-10), so the
     * search, stopped on its first phase alone, left the other up to that much past {@link FluidTpEquilibrium#DEPOSITION_TOLERANCE}
     * and the answer was refused: cold fuzz seed 183's LNG at 140.9 K and 620 kPa freezing its CO2 (the vapour at
     * 9.9998e-10, the liquid at 1.0046e-9), refused at every step size of the island. The real mixture freezes 1.87 of its
     * 2.05 mol of CO2 beside its vapour and its liquid.
     */
    @Test
    void aCrystalBesideAVapourAndALiquidMeetsTheToleranceInEach() {
        double t = 140.90028704290555, p = 619923.0283660815;
        double[] z = feed(0.17685241566161913, 54.73778258807344, 2.531240191239479, 2.0505124775177914);
        var r = tp(t, p, z, SERVICE.newWorkspace());
        assertEquals(Status.CONVERGED, r.status(), r.toString());
        assertEquals(Classification.VAPOR_LIQUID_SOLID, r.classification(), r.toString());
        PhaseAmounts crystal = solid(r);
        double worst = 0.0;
        for (PhaseAmounts phase : r.phases()) {
            if (phase.kind() == PhaseKind.SOLID) continue;
            worst = Math.max(worst, Math.abs(phase.state().chemicalPotential(CO2) - crystal.state().molarGibbsEnergy()) / (R * t));
        }
        assertTrue(worst <= FluidTpEquilibrium.DEPOSITION_TOLERANCE, "every fluid phase at the crystal: " + worst);
        assertEquals(1.87, crystal.amount(CO2), 0.01, describe(r));
        assertTrue(r.conservationDefect(z) < 1e-12, describe(r));
        System.out.printf(Locale.ROOT, "seed 183's crystal: %.6f mol of CO2 beside a vapour and a liquid, worst |mu_i - mu_s|/RT %.3e after %d re-flashes%n",
                crystal.amount(CO2), worst, r.deposition().iterations());
    }

    static String describe(EquilibriumResult r) {
        StringBuilder s = new StringBuilder(r.toString()).append(r.coverage()).append(r.diagnostics());
        for (PhaseAmounts phase : r.phases()) s.append(Arrays.toString(phase.amountsView())).append(phase.state());
        if (r.deposition() != null) s.append(r.deposition());
        return s.toString();
    }
}
