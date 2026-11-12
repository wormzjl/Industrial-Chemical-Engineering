package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 WP7: the network's phase decisions come from the P2/P3 equilibrium engine ({@link NetworkPhaseEngine}), stated as
 * network states by {@link FluidThermodynamics#flashTP}. The adapter conserves, states the engine's amounts with the
 * network's own evaluations, takes a one-root slot from the phase identification parameter, types a critical-band
 * failure, resolves the WP5 open case, and is bit for bit the same with a reused and a fresh workspace.
 */
class NetworkPhaseEngineTest {
    private static final String PILOT = "icheme:pilot_cryogenic";
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private final int n = model.componentCount(), water = n - 1, nitrogen = model.components().indexOf("Nitrogen"),
            methane = model.components().indexOf("Methane");

    /** A named state: temperature, pressure, conserved amounts. */
    private record Case(String name, double t, double p, double[] overall) {}

    private double[] crude(boolean wet) {
        var charge = FluidTestSupport.oneCubicMetreState(model, FluidTestSupport.Mixture.WET_CRUDE, 350, 150000);
        double[] totals = new double[n];
        for (int i = 0; i < n - 1; i++) totals[i] = TestPhases.liquid(charge)[i] + TestPhases.vapor(charge)[i];
        totals[water] = wet ? TestPhases.waterLiquid(charge) + TestPhases.waterVapor(charge) : 0;
        return totals;
    }
    private double[] pure(int component, double amount, double waterAmount) {
        double[] totals = new double[n];totals[component] = amount;totals[water] = waterAmount;return totals;
    }
    private List<Case> cases() {
        return List.of(
                new Case("wet crude + N2, 350 K, 150 kPa (gas, liquid, free water)", 350, 150000, crude(true)),
                new Case("dry crude + N2, 350 K, 500 kPa (gas, liquid)", 350, 500000, crude(false)),
                new Case("N2 + 1e-12 water, 298.15 K, 1 atm (all steam)", 298.15, 101325, pure(nitrogen, 40, 40e-12)),
                new Case("N2 + 1 % water, 298.15 K, 1 atm (all steam)", 298.15, 101325, pure(nitrogen, 40, .4)),
                new Case("N2 + water, 298.15 K, 1 atm (saturated gas, free water)", 298.15, 101325, pure(nitrogen, 40, 5)),
                new Case("pure N2, 298.15 K, 1 atm", 298.15, 101325, pure(nitrogen, 40, 0)),
                new Case("liquid N2, 77.355 K, 500 kPa", 77.355, 5e5, pure(nitrogen, 40, 0)),
                new Case("supercritical N2, 130 K, 6 MPa (liquid-like)", 130, 6e6, pure(nitrogen, 40, 0)),
                new Case("supercritical N2, 150 K, 10 MPa (liquid-like)", 150, 1e7, pure(nitrogen, 40, 0)),
                new Case("supercritical CH4, 400 K, 10 MPa (vapour-like)", 400, 1e7, pure(methane, 40, 0)));
    }

    @Test
    void theNetworkFlashStatesTheEnginesAnswerWithTheNetworksOwnEvaluations() {
        for (var c : cases()) {
            var state = model.flashTP(c.t(), c.p(), c.overall(), () -> {});
            double[] liquid = TestPhases.liquid(state), vapor = TestPhases.vapor(state);
            for (int i = 0; i < n - 1; i++) {
                assertEquals(c.overall()[i], liquid[i] + vapor[i], 1e-13 * Math.max(1, c.overall()[i]), c.name() + " conserves " + model.components().get(i));
                assertTrue(liquid[i] >= 0 && vapor[i] >= 0, c.name());
            }
            assertEquals(c.overall()[water], TestPhases.waterLiquid(state) + TestPhases.waterVapor(state), 1e-13 * Math.max(1, c.overall()[water]), c.name() + " conserves water");
            // The network's own direct evaluation of those amounts, bit for bit.
            var again = model.state(c.t(), c.p(), state.vaporAmountsView().clone(), state.liquidAmountsView().clone(), state.aqueousAmountsView().clone());
            assertEquals(Double.doubleToLongBits(again.volume()), Double.doubleToLongBits(state.volume()), c.name());
            assertEquals(Double.doubleToLongBits(again.enthalpy()), Double.doubleToLongBits(state.enthalpy()), c.name());
            System.out.printf("%s: liquid %.6g, vapour %.6g, free water %.6g, steam %.6g mol, pc %.3f Pa%n", c.name(),
                    Arrays.stream(liquid).sum(), Arrays.stream(vapor).sum(), TestPhases.waterLiquid(state), TestPhases.waterVapor(state), TestPhases.hydrocarbonPartialPressure(state));
        }
        // The slots: two phases where the engine splits, the one-root phase where its phase identification parameter says.
        var list = cases();
        assertTrue(sum(TestPhases.liquid(model.flashTP(350, 5e5, list.get(1).overall(), () -> {}))) > 0);
        assertTrue(sum(TestPhases.vapor(model.flashTP(350, 5e5, list.get(1).overall(), () -> {}))) > 0);
        var denseNitrogen = model.flashTP(130, 6e6, list.get(7).overall(), () -> {});
        assertEquals(40, sum(TestPhases.liquid(denseNitrogen)), 0, "liquid-like supercritical nitrogen takes the liquid slot");
        var values = model.hydrocarbon.translated().evaluateValues(130, 6e6, TestPhases.oil(denseNitrogen), Root.LIQUID, model.hydrocarbon.prepare(130));
        assertTrue(PhaseIdentification.liquidLike(values.phaseIdentificationParameter(), values.compressibility()),
                "PIP " + values.phaseIdentificationParameter() + " Z " + values.compressibility());
        assertEquals(40, sum(TestPhases.vapor(model.flashTP(400, 1e7, list.get(9).overall(), () -> {}))), 0, "vapour-like methane takes the vapour slot");
        assertEquals(40, sum(TestPhases.vapor(model.flashTP(298.15, 101325, list.get(5).overall(), () -> {}))), 0);
        assertEquals(40, sum(TestPhases.liquid(model.flashTP(77.355, 5e5, list.get(6).overall(), () -> {}))), 0);
    }

    /** The thread-local engine workspace, reused forwards then backwards, against a fresh workspace per state: bitwise. */
    @Test
    void aReusedAndAFreshWorkspaceGiveTheSameBits() {
        var list = new ArrayList<>(cases());
        var reversed = new ArrayList<>(list);java.util.Collections.reverse(reversed);
        var order = new ArrayList<>(list);order.addAll(reversed);
        for (var c : order) {
            var reused = model.flashTP(c.t(), c.p(), c.overall(), () -> {});
            // A fresh workspace: a new model's engine, whose per-thread workspace this first request creates.
            var fresh = model.adopt(c.t(), c.p(), c.overall(), FluidTestSupport.networkModel().phaseEngine().tp(c.t(), c.p(), c.overall(),
                    model.stepCompetition(c.t(), c.overall())), null);
            var other = FluidTestSupport.networkModel().flashTP(c.t(), c.p(), c.overall(), () -> {});
            for (var candidate : List.of(fresh, other)) {
                assertArrayEquals(bits(TestPhases.liquid(reused)), bits(TestPhases.liquid(candidate)), c.name());
                assertArrayEquals(bits(TestPhases.vapor(reused)), bits(TestPhases.vapor(candidate)), c.name());
                assertEquals(Double.doubleToLongBits(TestPhases.waterLiquid(reused)), Double.doubleToLongBits(TestPhases.waterLiquid(candidate)), c.name());
                assertEquals(Double.doubleToLongBits(TestPhases.waterVapor(reused)), Double.doubleToLongBits(TestPhases.waterVapor(candidate)), c.name());
                assertEquals(Double.doubleToLongBits(TestPhases.hydrocarbonPartialPressure(reused)), Double.doubleToLongBits(TestPhases.hydrocarbonPartialPressure(candidate)), c.name());
                assertEquals(Double.doubleToLongBits(reused.volume()), Double.doubleToLongBits(candidate.volume()), c.name());
                assertEquals(Double.doubleToLongBits(reused.enthalpy()), Double.doubleToLongBits(candidate.enthalpy()), c.name());
            }
        }
    }

    /**
     * WP5's open case: the retired Wilson flash did not converge for CH4/CO2 80/20 at 230 K and 2 MPa next to its dew
     * point. The engine answers it and its neighbourhood, and the WP5 grid of the pilot package has no failure.
     */
    @Test
    void theWp5OpenCaseAndItsNeighbourhoodResolve() {
        var pilot = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        double[] feed = {0, 0.8, 0, 0.2, 0};
        var state = pilot.flashTP(230, 2e6, feed, () -> {});
        for (int i = 0; i < 4; i++) assertEquals(feed[i], TestPhases.liquid(state)[i] + TestPhases.vapor(state)[i], 1e-15);
        System.out.println("CH4/CO2 80/20 at 230 K, 2 MPa: liquid " + Arrays.toString(TestPhases.liquid(state)) + ", vapour " + Arrays.toString(TestPhases.vapor(state)));
        int twoPhase = 0, states = 0;
        for (double t = 222; t <= 238; t += 2) {
            for (double p = 1.6e6; p <= 2.4e6 + 1; p += 1e5) {
                var s = pilot.flashTP(t, p, feed, () -> {});
                states++;
                if (sum(TestPhases.liquid(s)) > 0 && sum(TestPhases.vapor(s)) > 0) twoPhase++;
                for (int i = 0; i < 4; i++) assertEquals(feed[i], TestPhases.liquid(s)[i] + TestPhases.vapor(s)[i], 1e-15, t + " K " + p + " Pa");
            }
        }
        System.out.println("CH4/CO2 80/20, 222-238 K x 1.6-2.4 MPa: " + states + " states, " + twoPhase + " two-phase, none refused");
    }

    /**
     * A failure the engine types as inside the critical band is the network's typed {@link FluidThermodynamics.CriticalBandHold}
     * (it names the node once the solver attaches it); any other failure stays a plain refusal. The engine here is limited
     * to one substitution and one Newton step, so the P2 near-critical state (N2/CH4 150 K, 4.56 MPa, x_N2 0.67) fails.
     */
    @Test
    void aCriticalBandFailureIsTyped() {
        var pilot = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        var limited = new FluidTpEquilibrium.Settings(1.0e-10, 1000, 5, 1.0e-9, 1.0e-8, TangentPlaneStability.Settings.DEFAULT,
                1, 1, 1.0e-8, 1.0e-2, 100, 1.0e-11);
        var engine = NetworkPhaseEngine.of(pilot, com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT, limited);
        double[] nearCritical = {0.67, 0.33, 0, 0, 0}, ordinary = {0.5, 0.5, 0, 0, 0};
        var inside = engine.tp(150, 4.56e6, nearCritical, false);
        assertEquals(EquilibriumResult.Status.NOT_CONVERGED, inside.status(), inside.toString());
        assertTrue(inside.diagnostics().criticalBand());
        var hold = assertThrows(FluidThermodynamics.CriticalBandHold.class, () -> pilot.adopt(150, 4.56e6, nearCritical, inside, null));
        assertTrue(hold.getMessage().startsWith("Critical band: the phases at 150.0 K, 4560000.0 Pa (package " + PILOT + ")"), hold.getMessage());
        assertTrue(hold.getMessage().contains(EquilibriumResult.CRITICAL_BAND), hold.getMessage());
        var outside = engine.tp(110, 0.5e6, ordinary, false);
        assertEquals(EquilibriumResult.Status.NOT_CONVERGED, outside.status(), outside.toString());
        assertFalse(outside.diagnostics().criticalBand());
        var plain = assertThrows(IllegalArgumentException.class, () -> pilot.adopt(110, 0.5e6, ordinary, outside, null));
        assertFalse(plain instanceof FluidThermodynamics.CriticalBandHold);
        assertTrue(plain.getMessage().startsWith("Fluid TP equilibrium did not converge"), plain.getMessage());
        // The default engine converges both.
        assertDoesNotThrow(() -> pilot.flashTP(150, 4.56e6, nearCritical, () -> {}));
        assertDoesNotThrow(() -> pilot.flashTP(110, 0.5e6, ordinary, () -> {}));
    }

    /**
     * Owner decisions D25 and D26 where T and P leave the split undetermined. At a pure component's coexistence pressure a
     * standalone flash states the liquid; the network's outer phase check ({@link FluidThermodynamics#phaseCheck}) keeps a
     * node's populated phases, and a phase that answer finds missing enters at the node's own split, for one component as
     * for any material. A node holding both phases keeps itself. A pipe junction's own split is its PH answer: nitrogen
     * vapour held 1 % above its saturation pressure at 100 K condenses the liquid its enthalpy gives, at the saturation
     * temperature of its pressure, and liquid water 1 % below its saturation pressure at 350 K boils off the steam its
     * enthalpy gives; 1e-9 below, that steam (about 4e-11 of the water) is a trace the fold removes, so nothing is added.
     * A vessel's own split starts from its own state at the crossing (owner decision D40 A): the same nitrogen vapour in a
     * taut vessel starts from the engine's equilibrium at its own energy and volume, the liquid its volume no longer holds
     * as vapour beside the vapour at the saturation pressure, a little warmer for the condensing heat; where its closure
     * fails, it proposes nothing and the liquid is refused for the solve. A phase the trace rule removed in this solve is
     * not added back.
     */
    @Test
    void theOuterCheckKeepsThePopulatedPhasesAndSeedsTheMissingOneAtTheNodesOwnSplit() {
        double t = 100, p = saturationPressure(t);
        var flash = model.flashTP(t, p, pure(nitrogen, 40, 0), () -> {});
        assertEquals(40, sum(TestPhases.liquid(flash)), 0, "a standalone flash at coexistence states the liquid");
        double[] liquid = new double[n - 1], vapor = new double[n - 1], none = new double[n - 1];
        liquid[nitrogen] = 30;vapor[nitrogen] = 10;
        var both = TestPhases.state(model,t, p, liquid, vapor, 0, 0);
        assertSame(both, model.phaseCheck(both, false, null, null, 0, null, null, () -> {}), "a node holding both phases keeps its layout");
        // A junction: the PH answer at its pressure and enthalpy.
        double above = 1.01 * p;
        vapor[nitrogen] = 40;
        var supersaturated = TestPhases.state(model,t, above, none, vapor, 0, 0);
        var condensed = model.phaseCheck(supersaturated, true, null, null, 0, null, null, () -> {});
        System.out.printf("N2 vapour at %.6f Pa, 100 K: junction seed %.6f K, liquid %.6e of 40 mol%n", above, condensed.temperature(), sum(TestPhases.liquid(condensed)));
        assertTrue(sum(TestPhases.liquid(condensed)) > 0 && sum(TestPhases.vapor(condensed)) > 0, "the liquid its enthalpy condenses beside the vapour");
        assertEquals(above, condensed.pressure(), 0, "at the junction's pressure");
        assertEquals(above, saturationPressure(condensed.temperature()), 1e-7 * above, "at the saturation temperature of that pressure");
        assertEquals(supersaturated.enthalpy(), condensed.enthalpy(), 1e-9 * Math.abs(supersaturated.enthalpy()), "at the junction's enthalpy");
        assertEquals(40, sum(TestPhases.liquid(condensed)) + sum(TestPhases.vapor(condensed)), 1e-12, "the junction's own amount");
        // A vessel: its own split starts from its own state at the crossing (D40 A), and a failed closure proposes nothing.
        var answer = model.flashTP(t, above, pure(nitrogen, 40, 0), () -> {});
        var starts = new ArrayList<FluidThermodynamics.State>();int[] record = {0};
        assertSame(supersaturated, model.phaseCheck(supersaturated, false, null, record, 0, null, start -> {starts.add(start);return null;}, () -> {}),
                "a failed closure proposes nothing");
        assertEquals(1, starts.size(), "the vessel's own split is asked once");
        var crossing = starts.getFirst();
        System.out.printf("N2 vapour at %.6f Pa, 100 K: vessel crossing %.6f K %.6f Pa, liquid %.6e of 40 mol in %.9e m3%n", above, crossing.temperature(), crossing.pressure(),
                sum(TestPhases.liquid(crossing)), crossing.volume());
        assertEquals(supersaturated.volume(), crossing.volume(), 1e-6 * supersaturated.volume(), "in the vessel's own volume");
        assertEquals(supersaturated.internalEnergy(), crossing.internalEnergy(), 1e-6 * Math.abs(supersaturated.internalEnergy()), "at the vessel's own energy");
        assertTrue(crossing.temperature() > t, "the liquid's condensing heat warms it: " + crossing.temperature());
        assertEquals(crossing.pressure(), saturationPressure(crossing.temperature()), 1e-6 * crossing.pressure(), "at its saturation");
        assertTrue(sum(TestPhases.liquid(crossing)) > 0 && sum(TestPhases.liquid(crossing)) < 1, "the liquid its volume no longer holds as vapour: " + sum(TestPhases.liquid(crossing)));
        assertEquals(40, sum(TestPhases.liquid(crossing)) + sum(TestPhases.vapor(crossing)), 1e-9, "the vessel's own amount");
        assertEquals(FluidThermodynamics.phaseBits(answer) & ~FluidThermodynamics.phaseBits(supersaturated), record[0], "the liquid is refused for the solve");
        var gas = TestPhases.state(model,t, p, none, vapor, 0, 0);
        assertSame(gas, model.phaseCheck(gas, false, null, new int[]{FluidThermodynamics.phaseBits(flash)}, 0, null, null, () -> {}),
                "a phase the trace rule removed in this solve is not added back");
        // W3b: water boils at the cubic's own saturation pressure (41.21 kPa at 350 K, Wagner-Pruss 41.27 kPa).
        double tw = 350, saturation = saturationPressure(water, tw, 1e3, 1e6);
        assertEquals(0, TestPhases.waterLiquid(model.flashTP(tw, .99 * saturation, pure(nitrogen, 0, 1), () -> {})), 0, "the flash: all steam");
        var superheated = TestPhases.state(model,tw, .99 * saturation, none, none, 1, 0);
        var boiled = model.phaseCheck(superheated, true, null, null, 0, null, null, () -> {});
        System.out.printf("water at %.3f Pa, 350 K: junction seed %.6f K, steam %.6e of 1 mol%n", .99 * saturation, boiled.temperature(), TestPhases.waterVapor(boiled));
        assertTrue(TestPhases.waterLiquid(boiled) > 0 && TestPhases.waterVapor(boiled) > 0, "the liquid stays and the steam its enthalpy gives enters");
        assertEquals(.99 * saturation, saturationPressure(water, boiled.temperature(), 1e3, 1e6), 1e-7 * saturation, "at the saturation temperature");
        assertEquals(superheated.enthalpy(), boiled.enthalpy(), 1e-9 * Math.abs(superheated.enthalpy()), "at the junction's enthalpy");
        assertEquals(1, TestPhases.waterLiquid(boiled) + TestPhases.waterVapor(boiled), 1e-15, "the water its own");
        var barely = TestPhases.state(model,tw, saturation * (1 - 1e-9), none, none, 1, 0);
        assertSame(barely, model.phaseCheck(barely, true, null, null, 0, null, null, () -> {}), "a trace of steam is not added");
    }

    /**
     * P3 WP7c: hot gases of the network basis take the vapour slot, dry and wet, and the free-water rule gives the steam
     * its physical partial pressure. Before WP7c the bare {@code PIP > 1} labelled nitrogen above about 620 K liquid:
     * the adapter put it in the liquid slot, with water the gas had no vapour volume (the hydrocarbon partial pressure at
     * the 1e-6 Pa floor and all the pressure on the steam, or the steam limit's refusal), and the lean N2/CH4 90/10 gas
     * was refused {@code CRITICAL_BAND}. Compositions: pure nitrogen and the lean N2/CH4 to 10 MPa (their domain), the
     * eight light components (nitrogen to n-pentane) and the 20-component mixture of the legacy digest (every crude cut:
     * a real split, whose heavy liquid stays in the liquid slot) to 2 MPa; 600, 750 and 900 K; dry and with 0.1 mol water
     * per mol. Every state is answered: a steam partial pressure past the steam approximation's qualified limit was a
     * typed refusal and is a notice since the owner rule of 2026-10-16, and at 1200 K, beyond the package's 900 K, every
     * composition is carried on its ideal-gas cp held at 900 K.
     */
    @Test
    void hotGasesTakeTheVapourSlotDryAndWet() {
        int hydrocarbons = n - 1;
        double[] pureNitrogen = new double[hydrocarbons];pureNitrogen[nitrogen] = 1;
        double[] lean = new double[hydrocarbons];lean[nitrogen] = 0.9;lean[methane] = 0.1;
        double[] light = new double[hydrocarbons];
        for (String id : List.of("Nitrogen", "Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) {
            light[model.components().indexOf(id)] = 0.125;
        }
        double[] mixture = new double[hydrocarbons];
        for (int i = 0; i < hydrocarbons; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
        record Feed(String name, double[] x, double maximumPressure, boolean gas) {}
        List<Feed> feeds = List.of(new Feed("N2", pureNitrogen, 1e7, true), new Feed("N2/CH4 90/10", lean, 1e7, true),
                new Feed("light gas", light, 2e6, true), new Feed("20-component mixture", mixture, 2e6, false));
        double[] pressures = {1e5, 5e5, 1e6, 2e6, 5e6, 1e7};
        int answered = 0, wetGas = 0;
        double worstRatio = 0;
        var terms = new java.util.HashMap<Double, TranslatedPengRobinson.Workspace>();
        for (double t : new double[] {600, 750, 900}) {
            for (Feed feed : feeds) {
                for (double p : pressures) {
                    if (p > feed.maximumPressure()) continue;
                    for (double waterAmount : new double[] {0, 0.1}) {
                        double[] overall = Arrays.copyOf(feed.x(), n);
                        overall[water] = waterAmount;
                        String at = feed.name() + (waterAmount > 0 ? " wet" : " dry") + " at " + t + " K, " + p + " Pa";
                        FluidThermodynamics.State state = model.flashTP(t, p, overall, () -> {});
                        answered++;
                        for (int i = 0; i < hydrocarbons; i++) {
                            assertEquals(overall[i], TestPhases.liquid(state)[i] + TestPhases.vapor(state)[i], 1e-13, at + " conserves " + model.components().get(i));
                        }
                        if (feed.gas()) {
                            assertEquals(0, sum(TestPhases.liquid(state)), 0, at + ": a hot gas has nothing in the liquid slot");
                            // The engine's own label and the network model's flags of the same root.
                            var result = model.phaseEngine().tp(t, p, Arrays.copyOf(feed.x(), n), false);
                            assertEquals(1, result.phases().size(), at);
                            var kind = result.phases().get(0).kind();
                            assertTrue(kind == com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR
                                    || kind == com.wormzjl.icheme.science.thermo.phase.PhaseKind.FLUID, at + ": " + kind);
                            var eos = model.hydrocarbon.translated();
                            var prepared = terms.computeIfAbsent(t, model.hydrocarbon::prepare);
                            var values = eos.evaluateValues(t, p, Arrays.copyOf(feed.x(), n), Root.VAPOR, prepared);
                            assertTrue(HydrocarbonModel.liquidLike(values) == false
                                    && PhaseIdentification.vapourLike(values.phaseIdentificationParameter(), values.compressibility()), at);
                            assertTrue(model.hydrocarbon.phase(t, p, TestPhases.conserved(model.hydrocarbon, feed.x()), Root.VAPOR, prepared, false).vaporLike(), at);
                            assertThrows(HydrocarbonModel.RootAbsent.class, () -> model.hydrocarbon.phase(t, p, TestPhases.conserved(model.hydrocarbon, feed.x()), Root.LIQUID, prepared, true), at);
                        }
                        if (waterAmount > 0) {
                            // W3b: a gas has no liquid; beside the mixture's heavy liquid some water dissolves: at 600 K and 500 kPa 2.58e-3
                            // of 0.1 mol since the fade of item 9 (water dissolving in hot oil; 1.2e-3 at 1 MPa before), under 5 %.
                            assertEquals(0, TestPhases.waterLiquid(state), feed.gas() ? 0 : 5e-2 * waterAmount, at + ": all steam");
                            assertEquals(p, TestPhases.hydrocarbonPartialPressure(state) + TestPhases.waterPartialPressure(state), 1e-6 * p, at);
                            assertTrue(TestPhases.hydrocarbonPartialPressure(state) > 0.5 * p, at + ": pc " + TestPhases.hydrocarbonPartialPressure(state));
                            // The virial steam (B p_w/(R T) at most 2 % here) in a gas whose Z is within a few per cent of one: p_w close to y_w P (the heavy
                            // mixture's gas has the lowest Z: p_w/(y_w P) 1.05 at 750 K and 2 MPa).
                            double steamFraction = TestPhases.waterVapor(state) / (TestPhases.waterVapor(state) + sum(TestPhases.vapor(state)));
                            double ratio = TestPhases.waterPartialPressure(state) / (steamFraction * p);
                            assertEquals(1, ratio, 0.06, at + ": p_w " + TestPhases.waterPartialPressure(state) + " against y_w P " + steamFraction * p);
                            if (feed.gas()) {
                                wetGas++;
                                worstRatio = Math.max(worstRatio, Math.abs(ratio - 1));
                            }
                            if (t == 750 && p == 1e6) System.out.printf("WP7c %s: pc %.3f Pa, p_w %.3f Pa, p_w/(y_w P) %.6f%n", at,
                                    TestPhases.hydrocarbonPartialPressure(state), TestPhases.waterPartialPressure(state), ratio);
                        }
                    }
                }
            }
        }
        System.out.printf("WP7c hot network states: %d answered; the gases' p_w within %.4f of y_w P over %d wet states%n",
                answered, worstRatio, wetGas);
        assertTrue(answered > 100 && wetGas > 40, answered + " answered, " + wetGas + " wet gases");
        // The gases' Z lies within 4 % of one here (nitrogen 1.036 at 600 K and 10 MPa, the light gas 0.977 at 2 MPa), and the
        // virial steam's B p_w/(R T) within 2 % (-1.8 % in that nitrogen); the two add there: 0.051 (under 4 % with ideal steam).
        assertTrue(worstRatio < 0.06, "the gases' steam partial pressure is the ideal-mixing one to 6 %: " + worstRatio);
        // The pinned 700 K states of the WP7b note: pure N2 with 0.1 mol water at 0.1 MPa, and the lean gas at 0.1 MPa.
        var wetNitrogen = model.flashTP(700, 1e5, pure(nitrogen, 1, 0.1), () -> {});
        assertEquals(1e5 / 11, TestPhases.waterPartialPressure(wetNitrogen), 1e-3 * 1e5 / 11, "p_w of 0.1 mol steam in 1.1 mol gas");
        double[] leanWet = Arrays.copyOf(lean, n);leanWet[water] = 0.1;
        assertTrue(TestPhases.hydrocarbonPartialPressure(model.flashTP(700, 1e5, leanWet, () -> {})) > 9e4);
        for (Feed feed : feeds) {
            var hot = assertDoesNotThrow(() -> model.flashTP(1200, 1e5, Arrays.copyOf(feed.x(), n), () -> {}), feed.name());
            assertTrue(Double.isFinite(hot.enthalpy()) && hot.vaporVolume() > 0, feed.name() + " at 1200 K");
        }
    }

    /**
     * P3 WP7d: a liquid-liquid split is refused by the network, typed {@code UNSUPPORTED(PHASE_COMPETITION_NOT_QUALIFIED)}
     * ({@link FluidThermodynamics.UnsupportedPhases}), because a node carries one hydrocarbon liquid: N2/C2H6 at 95 K and
     * 2 MPa on the pilot network, an ethane-rich and a nitrogen-rich liquid (before WP7d the lighter liquid took the vapour
     * slot). The adapter refuses the engine's {@code LIQUID_LIQUID} answer explicitly, before any slot is filled. A
     * near-critical N2/CH4 split inside the band (both phases liquid-like by the parameter, tie line 0.010) is still a
     * vapour-liquid split with both slots.
     *
     * <p>P3 WP11: the refusal is a typed {@link ThermoHold} (a phase hold) with its own rejection key (the island
     * holds with it, {@code UnsupportedPhasesIslandTest}), and so is a split whose products the stability re-check proves
     * unstable, the engine's three-phase indication: N2/C2H6 at x_N2 0.94, 125 K and 3 MPa (type III, outside the band;
     * before WP11 a plain rejection). At x_N2 exactly 0.95 the same state is a pure fluid by the band's 0.95 rule (nitrogen
     * at Tr 0.99, Pr 0.88, box 1), so its failure is typed {@code CRITICAL_BAND} first and held as the band's. A dense gas
     * beside a liquid at 10 MPa (N2/C2H6 at 195 K) is a vapour-liquid split again and takes both slots.</p>
     */
    @Test
    void aLiquidLiquidSplitIsTwoLiquidsOfTheNetwork() {
        var pilot = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        double[] typeThree = {0.3, 0, 0.7, 0, 0};
        var answer = pilot.phaseEngine().tp(95, 2e6, typeThree, false);
        assertEquals(EquilibriumResult.Classification.LIQUID_LIQUID, answer.classification(), answer.toString());
        // W3b: the two liquids take the two liquid slots (three fluid phases come from the physics, never a hold).
        var liquids = assertDoesNotThrow(() -> pilot.flashTP(95, 2e6, typeThree, () -> {}));
        assertTrue(liquids.liquidSlotVolume() > 0 && liquids.aqueousSlotVolume() > 0 && liquids.vaporVolume() == 0, "two liquids");
        assertArrayEquals(typeThree, liquids.totals(), 1e-12);
        var direct = assertDoesNotThrow(() -> pilot.adopt(95, 2e6, typeThree, answer, null));
        assertEquals(liquids.volume(), direct.volume(), 1e-12 * liquids.volume());

        double[] threePhase = {0.94, 0, 0.06, 0, 0};
        var indication = pilot.phaseEngine().tp(125, 3e6, threePhase, false);
        assertEquals(EquilibriumResult.Status.NOT_CONVERGED, indication.status(), indication.toString());
        assertFalse(indication.diagnostics().criticalBand(), "outside the band");
        assertTrue(indication.detail().startsWith(EquilibriumResult.UNSTABLE_PRODUCT), indication.detail());
        var third = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.flashTP(125, 3e6, threePhase, () -> {}));
        assertTrue(third.getMessage().startsWith("Unsupported phases: PHASE_COMPETITION_NOT_QUALIFIED (THREE_PHASES) at 125.0 K, 3000000.0 Pa"),
                third.getMessage());
        // W3b: the network carries a vapour and two liquids; an engine answer left unstable is still the typed hold.
        assertTrue(third.getMessage().contains("further fluid phase"), third.getMessage());
        double[] atTheBandRule = {0.95, 0, 0.05, 0, 0};
        var band = assertThrows(FluidThermodynamics.CriticalBandHold.class, () -> pilot.flashTP(125, 3e6, atTheBandRule, () -> {}));
        assertTrue(band.getMessage().contains(EquilibriumResult.UNSTABLE_PRODUCT), band.getMessage());

        double[] denseGas = {0.55, 0, 0.45, 0, 0};
        var dense = pilot.phaseEngine().tp(195, 1e7, denseGas, false);
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, dense.classification(), dense.toString());
        var denseState = pilot.flashTP(195, 1e7, denseGas, () -> {});
        assertTrue(sum(TestPhases.liquid(denseState)) > 0 && sum(TestPhases.vapor(denseState)) > 0, "a dense gas beside a liquid takes both slots");

        double[] nearCritical = {0.67, 0.33, 0, 0, 0};
        var split = pilot.phaseEngine().tp(150, 4.64e6, nearCritical, false);
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, split.classification(), split.toString());
        assertTrue(split.diagnostics().criticalBand());
        var state = pilot.flashTP(150, 4.64e6, nearCritical, () -> {});
        assertTrue(sum(TestPhases.liquid(state)) > 0 && sum(TestPhases.vapor(state)) > 0, "both slots");
    }

    /**
     * The design review's E9 (cold fuzz seed 55 under its form A): a junction's arrival mixture, methane with 5.3 mol of CO2
     * per 17.8 of methane and traces of nitrogen and ethane, at 186 K and 2.86 MPa under the CO2 crystal's competition. The
     * engine converges a two-phase split, but the stability re-check leaves both products unresolved: a third fluid phase is
     * not excluded, so the split is no answer. That is the proved indication's typed hold short of the proof
     * ({@code THREE_PHASES}), never a plain refusal: a seed that meets it keeps its guess, a substep counts it under its key,
     * and an island holding on it names the node. The third phase there is a crystal's stand-in, so a step, which carries its
     * crystals, answers vapour and one liquid; the fluid phases alone still reach the hold.
     */
    @Test
    void aSplitWhoseProductStabilityIsUnresolvedIsHeldTyped() {
        var pilot = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        double t = 185.98296668278303, p = 2861811.264201872;
        double[] arrival = {0.3324767803012572, 17.82807635831462, 0.2742249567265616, 5.342048217743187, 0};
        var answer = pilot.phaseEngine().tp(t, p, arrival, false);
        assertEquals(EquilibriumResult.Status.NOT_CONVERGED, answer.status(), answer.toString());
        assertFalse(answer.diagnostics().criticalBand(), "outside the band");
        assertTrue(answer.detail().startsWith(EquilibriumResult.UNRESOLVED_PRODUCT), answer.detail());
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, pilot.phaseEngine().tp(t, p, arrival, pilot.stepCompetition(t, arrival)).classification());
        var hold = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.adopt(t, p, arrival, answer, null));
        assertEquals(FluidThermodynamics.UnsupportedPhases.Kind.THREE_PHASES, hold.kind());
        assertTrue(hold.getMessage().contains("not excluded"), hold.getMessage());
        assertEquals(22, hold.at(22).node(), "a caller that knows the node names it");
    }

    /**
     * Free water boils alone only where the hydrocarbons hold next to none of the gas. Crude's light ends share it, so H(T)
     * and U(T) of wet crude are continuous through its three-phase zone: a state comes back from its enthalpy, and from its
     * energy and volume, at its own temperature and pressure, with its hydrocarbon vapour. The balance at water's saturation
     * also met the enthalpy, with no hydrocarbon vapour and 7 to 17 K off, so nothing flagged it; the last state is UV's:
     * 2 bar instead of 5.
     */
    @Test
    void wetCrudeComesBackWithItsHydrocarbonVapour() {
        record Point(double waterRatio, double t, double p) {}
        var engine = model.phaseEngine();
        for (var at : List.of(new Point(.2, 376, 2e5), new Point(.2, 379, 2e5), new Point(.2, 382, 2e5), new Point(.2, 500, 30e5),
                new Point(.01, 390, 2e5), new Point(.01, 393, 5e5))) {
            double[] feed = crudeWith(at.waterRatio());
            var tp = engine.tp(at.t(), at.p(), feed, false);
            assertTrue(tp.converged(), at + ": " + tp.detail());
            double vapour = hydrocarbonVapour(tp);
            // W3b: on the cubic the oil keeps more of its light ends; 0.94 of the 100 mol in the gas at 393 K, 5 bar.
            assertTrue(vapour > .5, at + " holds hydrocarbon vapour: " + vapour);
            var ph = engine.ph(at.p(), tp.enthalpy(), feed, false);
            var uv = engine.crystalUv(tp.internalEnergy(), tp.volume(), feed, false, Double.NaN, Double.NaN);
            for (var answer : List.of(ph, uv)) {
                assertTrue(answer.converged(), at + ": " + answer.detail());
                var state = answer.phases().get(0);
                assertEquals(at.t(), state.temperature(), 1e-6, at + " " + answer.specification());
                assertEquals(at.p(), state.pressure(), 1e-6 * at.p(), at + " " + answer.specification());
                assertEquals(vapour, hydrocarbonVapour(answer), 1e-6 * vapour, at + " " + answer.specification());
            }
        }
    }

    /**
     * A clean heavy cut has next to no vapour pressure, so its water's boiling stays a step: the balance answers it at
     * water's saturation with no hydrocarbon vapour, from the bracket's first two TP answers, where waiting for a closed
     * bracket costs 35 and can end on no state at all (the wet liquid's bubble point lies off the saturation). A trace of
     * methane gives the liquid a vapour pressure, about 1 % of the state pressure here, and ends that: the gas holds it.
     */
    @Test
    void aHeavyCutsWaterBoilsAloneUntilTheLiquidHasAVapourPressure() {
        var engine = model.phaseEngine();
        double p = 2e5;
        double[] clean = new double[n];
        double[] crude = bundledCrude();
        for (int c = 8; c <= 12; c++) {
            int i = model.components().indexOf("crude_pc%02d".formatted(c));
            clean[i] = crude[i];
        }
        double total = sum(clean);
        for (int i = 0; i < n; i++) clean[i] *= 100 / total;
        clean[water] = 30;
        // W3b: the cubic's three phases at one temperature, where water's vapour pressure and the cut's next to none make
        // the pressure (the free-water rule's balance at water's saturation before).
        var alone = engine.ph(p, midway(clean, p), clean, false);
        assertTrue(alone.converged(), alone.detail());
        double t = alone.phases().get(0).temperature();
        assertEquals(p, saturationPressure(water, t, 1e3, 1e7), 1e-3 * p, "at water's saturation, the cut's vapour pressure aside");
        assertTrue(hydrocarbonVapour(alone) < 1e-3 * steam(alone), "next to no hydrocarbon vapour: " + hydrocarbonVapour(alone));
        assertTrue(steam(alone) > 0 && condensedWater(alone) > 0, alone.detail());

        double[] traced = clean.clone();
        traced[methane] = .1;
        var shared = engine.ph(p, midway(traced, p), traced, false);
        assertTrue(shared.converged(), shared.detail());
        assertTrue(hydrocarbonVapour(shared) > 0, shared.detail());
    }

    /**
     * Water holding a trace of crude boils alone as well, whatever the crude's vapour pressure: the gas cannot hold more
     * hydrocarbon than there is, 2e-4 of it here, so the balance at water's saturation stands, 6 mK over the exact answer,
     * from two TP answers where the search takes 24. With 100 mol of water per mol of crude the gas is 2 % hydrocarbon and
     * the search answers.
     */
    @Test
    void waterWithATraceOfCrudeBoilsAlone() {
        var engine = model.phaseEngine();
        double p = 2e5;
        double[] trace = crudeWith(1e4);
        // W3b: on the cubic's water, at water's saturation to the crude's share of the pressure.
        var alone = engine.ph(p, midway(trace, p), trace, false);
        assertTrue(alone.converged(), alone.detail());
        assertEquals(p, saturationPressure(water, alone.phases().get(0).temperature(), 1e3, 1e7), 1e-3 * p, "at water's saturation");

        double[] more = crudeWith(100);
        var shared = engine.ph(p, midway(more, p), more, false);
        assertTrue(shared.converged(), shared.detail());
        assertTrue(hydrocarbonVapour(shared) > 0, shared.detail());
    }

    /**
     * One hydrocarbon with too little water to saturate the gas, the textbook steam distillation: the gas holds the
     * hydrocarbon at its own vapour pressure and the steam at the rest of the pressure, under water's saturation, and the
     * rest of the hydrocarbon stays liquid, n_V = n_w P_v / (P - P_v). The gas volume has no root in the hydrocarbon's
     * partial pressure, all of the hydrocarbon under P_v and none over it, so the steam's own volume closes it. These states
     * come back from their enthalpy, and from their energy and volume, at their own temperature and pressure.
     */
    @Test
    void aPureHydrocarbonDistilsWithItsSteam() {
        record Point(double t, double p) {}
        var engine = model.phaseEngine();
        int cut = model.components().indexOf("crude_pc03");
        double[] feed = pure(cut, 100, 30);
        for (var at : List.of(new Point(320, 5e3), new Point(370, 1e5), new Point(382, 1e5))) {
            var tp = engine.tp(at.t(), at.p(), feed, false);
            assertTrue(tp.converged(), at + ": " + tp.detail());
            // W3b: read from the engine's gas on the cubic's water.
            // W3b: the cubic's oil dissolves some water (0.04 of the 30 mol at 320 K and 5 kPa); the rest is steam.
            assertEquals(30, steam(tp), .01 * 30, at + " the water is steam but what the oil dissolves");
            assertEquals(30, steam(tp) + condensedWater(tp), 1e-9 * 30, at + " water conserved");
            double pc = hydrocarbonPressure(tp, at.p());
            double vapourPressure = saturationPressure(cut, at.t(), 1e2, 3e6);
            // W3b: the cubic's mixing rule holds in the gas too; beside the steam the cut's partial pressure is 2.9 % over its
            // vapour pressure at 370 K and 1 bar.
            assertEquals(vapourPressure, pc, .05 * vapourPressure, at + " at the hydrocarbon's own vapour pressure");
            assertTrue(at.p() - pc < saturationPressure(water, at.t(), 1e2, 1e7), at + " the steam under water's saturation");
            double vapour = hydrocarbonVapour(tp);
            assertEquals(steam(tp) * pc / (at.p() - pc), vapour, .01 * vapour, at + " the two gases fill one volume");
            assertEquals(100 - vapour, hydrocarbonLiquid(tp), 1e-9, at + " the rest stays liquid");
            var ph = engine.ph(at.p(), tp.enthalpy(), feed, false);
            var uv = engine.crystalUv(tp.internalEnergy(), tp.volume(), feed, false, Double.NaN, Double.NaN);
            for (var answer : List.of(ph, uv)) {
                assertTrue(answer.converged(), at + ": " + answer.detail());
                var state = answer.phases().get(0);
                assertEquals(at.t(), state.temperature(), 1e-6, at + " " + answer.specification());
                assertEquals(at.p(), state.pressure(), 1e-6 * at.p(), at + " " + answer.specification());
                assertEquals(vapour, hydrocarbonVapour(answer), 1e-6 * vapour, at + " " + answer.specification());
            }
        }
    }

    /**
     * Where water's vapour pressure and one hydrocarbon's add up to the state pressure the two boil together at one
     * temperature, and H(T) steps there by the heat of what the gas takes: no gas below it, and above it the water is gone
     * (ratio .3) or the hydrocarbon liquid is (ratio 30). A target inside the step is all three phases at that temperature,
     * the steam and the vapour in the ratio of their pressures, and comes back from its energy and volume.
     */
    @Test
    void aPureHydrocarbonBoilsWithItsWaterAtOneTemperature() {
        var engine = model.phaseEngine();
        int cut = model.components().indexOf("crude_pc03");
        double p = 1e5;
        for (double ratio : new double[] {.3, 30}) {
            double[] feed = pure(cut, 100, 100 * ratio);
            double target = .5 * (engine.tp(367, p, feed, false).enthalpy() + engine.tp(369, p, feed, false).enthalpy());
            var ph = engine.ph(p, target, feed, false);
            assertTrue(ph.converged(), ph.detail());
            assertEquals(target, ph.enthalpy(), 1e-8 * Math.abs(target), "the energy balance");
            double t = ph.phases().get(0).temperature();
            // W3b: on the cubic's water; the two liquids next to immiscible, so the two vapour pressures add up to 2 % (98.7 kPa
            // of 100: their mutual solubility and the gas's mixing rule).
            double waterPressure = saturationPressure(water, t, 1e3, 1e7), cutPressure = saturationPressure(cut, t, 1e2, 3e6);
            assertEquals(p, waterPressure + cutPressure, .02 * p, "the two vapour pressures add up");
            assertTrue(condensedWater(ph) > 0 && steam(ph) > 0, ph.detail());
            double vapour = hydrocarbonVapour(ph);
            assertTrue(hydrocarbonLiquid(ph) > 0 && vapour > 0, ph.detail());
            double ideal = cutPressure / waterPressure;
            // The liquids' mutual solubility and the gas's nonideality: 2.8 % at ratio 0.3.
            assertEquals(ideal, vapour / steam(ph), .05 * ideal, "the gas holds them as their pressures");
            assertTrue(t > 367 && t < 369, ph.detail());
            assertEquals(0, hydrocarbonVapour(engine.tp(t - 1e-6, p, feed, false)), "no gas under it");
            assertTrue(hydrocarbonVapour(engine.tp(t + 1e-6, p, feed, false)) > vapour, "over it the step's top end");
            // W3b section 14 item 7: from the three-phase point to 5 K above it every TP answers a vapour (at ratio 30 the
            // audit missed it 0.5 K above, each trial on its liquid root, before the vapour trial was held on its own root).
            for (int k = 1; k <= 50; k++) {
                double above = t + 0.1 * k;
                var r = engine.tp(above, p, feed, false);
                assertTrue(r.converged() && steam(r) + hydrocarbonVapour(r) > 0, above + " K at ratio " + ratio + ": " + r.detail());
            }
            var uv = engine.crystalUv(ph.internalEnergy(), ph.volume(), feed, false, Double.NaN, Double.NaN);
            assertTrue(uv.converged(), uv.detail());
            assertEquals(t, uv.phases().get(0).temperature(), 1e-6, "UV");
            assertEquals(p, uv.phases().get(0).pressure(), 1e-6 * p, "UV");
            assertEquals(vapour, hydrocarbonVapour(uv), 1e-5 * vapour, "UV");
            assertEquals(steam(ph), steam(uv), 1e-5 * steam(ph), "UV");
        }
    }

    /**
     * Water short of a heavy oil at a stripper's temperature: all of it is steam at the state pressure, and the oil stays
     * liquid with next to no vapour pressure. The vapour that fills the gas lies under the flash's resolution, about 5e-9 of
     * the feed, in a heavy cut with a trace of the next one as in one hydrocarbon: its gas volume jumps from that to none,
     * and the steam closes the step. The state comes back from its enthalpy at its own temperature.
     */
    @Test
    void aHeavyOilBesideSteamStaysLiquid() {
        record Point(double trace, double t, double p) {}
        var engine = model.phaseEngine();
        int heavy = model.components().indexOf("crude_pc12");
        for (var at : List.of(new Point(.01, 550, 1e5), new Point(0, 700, 5e5))) {
            double[] feed = pure(heavy, 100, 30);
            feed[heavy - 1] = at.trace();
            var tp = engine.tp(at.t(), at.p(), feed, false);
            assertTrue(tp.converged(), at + ": " + tp.detail());
            // W3b: the oil dissolves some water, the rest is steam. Since the fade of item 9 (the water-cut cells fade from 373 K
            // to 809 K, water dissolving in hot oil as a gas does): 0.36 of the 30 mol at 550 K and 1 bar (0.06 before), 11.88 at
            // 700 K and 5 bar (x_w 0.106 in the oil; under 0.3 before).
            double steamExpected = at.t() > 600 ? 18.1246 : 29.640;
            assertEquals(steamExpected, steam(tp), 1e-3 * steamExpected, at + " the water is steam but what the oil dissolves");
            assertEquals(at.p(), at.p() - hydrocarbonPressure(tp, at.p()), 1e-4 * at.p(), at + " at the state pressure");
            assertTrue(hydrocarbonVapour(tp) < 1e-3, at + ": " + tp.detail());
            var ph = engine.ph(at.p(), tp.enthalpy(), feed, false);
            assertTrue(ph.converged(), at + ": " + ph.detail());
            assertEquals(at.t(), ph.phases().get(0).temperature(), 1e-6, at + ": " + ph.detail());
        }
    }

    /** The enthalpy halfway between 12 K under water's boiling at 2 bar and 12 K over it. */
    private double midway(double[] feed, double p) {
        var engine = model.phaseEngine();
        return .5 * (engine.tp(381, p, feed, false).enthalpy() + engine.tp(405, p, feed, false).enthalpy());
    }

    private double[] bundledCrude() {
        return com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.worldCrude();
    }

    /** The bundled crude with {@code ratio} mol of water per mol of crude, 100 mol in all. */
    private double[] crudeWith(double ratio) {
        double[] feed = bundledCrude();
        feed[water] = ratio;
        double total = sum(feed);
        for (int i = 0; i < n; i++) feed[i] *= 100 / total;
        return feed;
    }

    /** Water in an answer's gas, mol. */
    private double steam(EquilibriumResult answer) {
        double steam = 0;
        for (var phase : answer.phases()) if (phase.kind() == PhaseKind.VAPOR) steam += phase.amount(water);
        return steam;
    }

    /** Water in an answer's liquids, mol. */
    private double condensedWater(EquilibriumResult answer) {
        double condensed = 0;
        for (var phase : answer.phases()) if (phase.kind() == PhaseKind.LIQUID) condensed += phase.amount(water);
        return condensed;
    }

    /** The hydrocarbons' partial pressure in an answer's gas, {@code y_hc P}, Pa (0 without a gas). */
    private double hydrocarbonPressure(EquilibriumResult answer, double p) {
        double gas = hydrocarbonVapour(answer) + steam(answer);
        return gas > 0 ? p * hydrocarbonVapour(answer) / gas : 0;
    }

    /** The hydrocarbons of an answer's gas, mol. */
    private double hydrocarbonVapour(EquilibriumResult answer) {
        double vapour = 0;
        for (var phase : answer.phases()) {
            if (phase.kind() != PhaseKind.VAPOR) continue;
            for (int i = 0; i < n; i++) if (i != water) vapour += phase.amount(i);
        }
        return vapour;
    }

    /** The hydrocarbons of an answer's liquid, mol. */
    private double hydrocarbonLiquid(EquilibriumResult answer) {
        double liquid = 0;
        for (var phase : answer.phases()) {
            if (phase.kind() != PhaseKind.LIQUID) continue;
            for (int i = 0; i < n; i++) if (i != water) liquid += phase.amount(i);
        }
        return liquid;
    }

    /** The model's own saturation pressure of pure nitrogen, where its liquid and vapour fugacities agree. */
    private double saturationPressure(double t) {
        return saturationPressure(nitrogen, t, 1e4, 3.3e6);
    }

    /** The model's own saturation pressure of a pure component (water included, W3b) inside {@code [low, high]}. */
    private double saturationPressure(int component, double t, double low, double high) {
        double[] pure = new double[n];pure[component] = 1;
        var terms = model.hydrocarbon.prepare(t);
        for (int k = 0; k < 200 && high - low > 1e-14 * high; k++) {
            double mid = Math.sqrt(low * high);
            var l = model.hydrocarbon.phase(t, mid, pure, Root.LIQUID, terms, false);
            var v = model.hydrocarbon.phase(t, mid, pure, Root.VAPOR, terms, false);
            // Below the saturation pressure the vapour has the lower fugacity.
            if (l.logFugacityView()[component] > v.logFugacityView()[component]) low = mid; else high = mid;
        }
        return 0.5 * (low + high);
    }
    private static double sum(double[] values) { double s = 0; for (double v : values) s += v; return s; }
    private static long[] bits(double[] values) { return Arrays.stream(values).mapToLong(Double::doubleToLongBits).toArray(); }
}
