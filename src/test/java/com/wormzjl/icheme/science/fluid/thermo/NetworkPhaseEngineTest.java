package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseIdentification;
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
        for (int i = 0; i < n - 1; i++) totals[i] = charge.liquidView()[i] + charge.vaporView()[i];
        totals[water] = wet ? charge.waterLiquid() + charge.waterVapor() : 0;
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
            double[] liquid = state.liquidView(), vapor = state.vaporView();
            for (int i = 0; i < n - 1; i++) {
                assertEquals(c.overall()[i], liquid[i] + vapor[i], 1e-13 * Math.max(1, c.overall()[i]), c.name() + " conserves " + model.components().get(i));
                assertTrue(liquid[i] >= 0 && vapor[i] >= 0, c.name());
            }
            assertEquals(c.overall()[water], state.waterLiquid() + state.waterVapor(), 1e-13 * Math.max(1, c.overall()[water]), c.name() + " conserves water");
            // The network's own direct evaluation of those amounts, bit for bit.
            var again = model.state(c.t(), c.p(), liquid, vapor, state.waterLiquid(), state.waterVapor(), state.hydrocarbonPartialPressure());
            assertEquals(Double.doubleToLongBits(again.volume()), Double.doubleToLongBits(state.volume()), c.name());
            assertEquals(Double.doubleToLongBits(again.enthalpy()), Double.doubleToLongBits(state.enthalpy()), c.name());
            // The free-water rule's closure: steam at its partial pressure beside the hydrocarbon vapour at pc.
            if (state.waterVapor() > 0 && state.waterLiquid() == 0 && state.vaporProperties() != null) {
                assertEquals(c.p(), state.hydrocarbonPartialPressure() + state.waterPartialPressure(), 1e-6 * c.p(), c.name());
            }
            System.out.printf("%s: liquid %.6g, vapour %.6g, free water %.6g, steam %.6g mol, pc %.3f Pa%n", c.name(),
                    Arrays.stream(liquid).sum(), Arrays.stream(vapor).sum(), state.waterLiquid(), state.waterVapor(), state.hydrocarbonPartialPressure());
        }
        // The slots: two phases where the engine splits, the one-root phase where its phase identification parameter says.
        var list = cases();
        assertTrue(sum(model.flashTP(350, 5e5, list.get(1).overall(), () -> {}).liquidView()) > 0);
        assertTrue(sum(model.flashTP(350, 5e5, list.get(1).overall(), () -> {}).vaporView()) > 0);
        var denseNitrogen = model.flashTP(130, 6e6, list.get(7).overall(), () -> {});
        assertEquals(40, sum(denseNitrogen.liquidView()), 0, "liquid-like supercritical nitrogen takes the liquid slot");
        var values = model.hydrocarbon.translated().evaluateValues(130, 6e6, denseNitrogen.liquidView(), Root.LIQUID, model.hydrocarbon.prepare(130));
        assertTrue(PhaseIdentification.liquidLike(values.phaseIdentificationParameter(), values.compressibility()),
                "PIP " + values.phaseIdentificationParameter() + " Z " + values.compressibility());
        assertEquals(40, sum(model.flashTP(400, 1e7, list.get(9).overall(), () -> {}).vaporView()), 0, "vapour-like methane takes the vapour slot");
        assertEquals(40, sum(model.flashTP(298.15, 101325, list.get(5).overall(), () -> {}).vaporView()), 0);
        assertEquals(40, sum(model.flashTP(77.355, 5e5, list.get(6).overall(), () -> {}).liquidView()), 0);
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
                    model.crystalsCompete(c.t(), c.overall())), null);
            var other = FluidTestSupport.networkModel().flashTP(c.t(), c.p(), c.overall(), () -> {});
            for (var candidate : List.of(fresh, other)) {
                assertArrayEquals(bits(reused.liquidView()), bits(candidate.liquidView()), c.name());
                assertArrayEquals(bits(reused.vaporView()), bits(candidate.vaporView()), c.name());
                assertEquals(Double.doubleToLongBits(reused.waterLiquid()), Double.doubleToLongBits(candidate.waterLiquid()), c.name());
                assertEquals(Double.doubleToLongBits(reused.waterVapor()), Double.doubleToLongBits(candidate.waterVapor()), c.name());
                assertEquals(Double.doubleToLongBits(reused.hydrocarbonPartialPressure()), Double.doubleToLongBits(candidate.hydrocarbonPartialPressure()), c.name());
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
        var pilot = FluidTestSupport.model(MaterialCatalog.bundled(), PILOT);
        double[] feed = {0, 0.8, 0, 0.2, 0};
        var state = pilot.flashTP(230, 2e6, feed, () -> {});
        for (int i = 0; i < 4; i++) assertEquals(feed[i], state.liquidView()[i] + state.vaporView()[i], 1e-15);
        System.out.println("CH4/CO2 80/20 at 230 K, 2 MPa: liquid " + Arrays.toString(state.liquidView()) + ", vapour " + Arrays.toString(state.vaporView()));
        int twoPhase = 0, states = 0;
        for (double t = 222; t <= 238; t += 2) {
            for (double p = 1.6e6; p <= 2.4e6 + 1; p += 1e5) {
                var s = pilot.flashTP(t, p, feed, () -> {});
                states++;
                if (sum(s.liquidView()) > 0 && sum(s.vaporView()) > 0) twoPhase++;
                for (int i = 0; i < 4; i++) assertEquals(feed[i], s.liquidView()[i] + s.vaporView()[i], 1e-15, t + " K " + p + " Pa");
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
        var pilot = FluidTestSupport.model(MaterialCatalog.bundled(), PILOT);
        var limited = new FluidTpEquilibrium.Settings(1.0e-10, 1000, 5, 1.0e-9, 1.0e-8, TangentPlaneStability.Settings.DEFAULT,
                1, 1, 1.0e-8, 1.0e-2, 100, 1.0e-11);
        var engine = NetworkPhaseEngine.of(pilot, MaterialCatalog.bundled(), PILOT, com.wormzjl.icheme.science.material.MaterialRuntime.with(
                MaterialCatalog.bundled(), PILOT, com.wormzjl.icheme.science.material.MaterialRuntime::water), limited);
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
        assertEquals(40, sum(flash.liquidView()), 0, "a standalone flash at coexistence states the liquid");
        double[] liquid = new double[n - 1], vapor = new double[n - 1], none = new double[n - 1];
        liquid[nitrogen] = 30;vapor[nitrogen] = 10;
        var both = model.state(t, p, liquid, vapor, 0, 0, p);
        assertSame(both, model.phaseCheck(both, false, null, null, 0, null, null, () -> {}), "a node holding both phases keeps its layout");
        // A junction: the PH answer at its pressure and enthalpy.
        double above = 1.01 * p;
        vapor[nitrogen] = 40;
        var supersaturated = model.state(t, above, none, vapor, 0, 0, above);
        var condensed = model.phaseCheck(supersaturated, true, null, null, 0, null, null, () -> {});
        System.out.printf("N2 vapour at %.6f Pa, 100 K: junction seed %.6f K, liquid %.6e of 40 mol%n", above, condensed.temperature(), sum(condensed.liquidView()));
        assertTrue(sum(condensed.liquidView()) > 0 && sum(condensed.vaporView()) > 0, "the liquid its enthalpy condenses beside the vapour");
        assertEquals(above, condensed.pressure(), 0, "at the junction's pressure");
        assertEquals(above, saturationPressure(condensed.temperature()), 1e-7 * above, "at the saturation temperature of that pressure");
        assertEquals(supersaturated.enthalpy(), condensed.enthalpy(), 1e-9 * Math.abs(supersaturated.enthalpy()), "at the junction's enthalpy");
        assertEquals(40, sum(condensed.liquidView()) + sum(condensed.vaporView()), 1e-12, "the junction's own amount");
        // A vessel: its own split starts from its own state at the crossing (D40 A), and a failed closure proposes nothing.
        var answer = model.flashTP(t, above, pure(nitrogen, 40, 0), () -> {});
        var starts = new ArrayList<FluidThermodynamics.State>();int[] record = {0};
        assertSame(supersaturated, model.phaseCheck(supersaturated, false, null, record, 0, null, start -> {starts.add(start);return null;}, () -> {}),
                "a failed closure proposes nothing");
        assertEquals(1, starts.size(), "the vessel's own split is asked once");
        var crossing = starts.getFirst();
        System.out.printf("N2 vapour at %.6f Pa, 100 K: vessel crossing %.6f K %.6f Pa, liquid %.6e of 40 mol in %.9e m3%n", above, crossing.temperature(), crossing.pressure(),
                sum(crossing.liquidView()), crossing.volume());
        assertEquals(supersaturated.volume(), crossing.volume(), 1e-6 * supersaturated.volume(), "in the vessel's own volume");
        assertEquals(supersaturated.internalEnergy(), crossing.internalEnergy(), 1e-6 * Math.abs(supersaturated.internalEnergy()), "at the vessel's own energy");
        assertTrue(crossing.temperature() > t, "the liquid's condensing heat warms it: " + crossing.temperature());
        assertEquals(crossing.pressure(), saturationPressure(crossing.temperature()), 1e-6 * crossing.pressure(), "at its saturation");
        assertTrue(sum(crossing.liquidView()) > 0 && sum(crossing.liquidView()) < 1, "the liquid its volume no longer holds as vapour: " + sum(crossing.liquidView()));
        assertEquals(40, sum(crossing.liquidView()) + sum(crossing.vaporView()), 1e-9, "the vessel's own amount");
        assertEquals(FluidThermodynamics.phaseBits(answer) & ~FluidThermodynamics.phaseBits(supersaturated), record[0], "the liquid is refused for the solve");
        var gas = model.state(t, p, none, vapor, 0, 0, p);
        assertSame(gas, model.phaseCheck(gas, false, null, new int[]{FluidThermodynamics.phaseBits(flash)}, 0, null, null, () -> {}),
                "a phase the trace rule removed in this solve is not added back");
        double tw = 350, saturation = model.saturationPressure(tw);
        assertEquals(0, model.flashTP(tw, .99 * saturation, pure(nitrogen, 0, 1), () -> {}).waterLiquid(), 0, "the flash: all steam");
        var superheated = model.state(tw, .99 * saturation, none, none, 1, 0, .99 * saturation);
        var boiled = model.phaseCheck(superheated, true, null, null, 0, null, null, () -> {});
        System.out.printf("water at %.3f Pa, 350 K: junction seed %.6f K, steam %.6e of 1 mol%n", .99 * saturation, boiled.temperature(), boiled.waterVapor());
        assertTrue(boiled.waterLiquid() > 0 && boiled.waterVapor() > 0, "the liquid stays and the steam its enthalpy gives enters");
        assertEquals(.99 * saturation, model.saturationPressure(boiled.temperature()), 1e-7 * saturation, "at the saturation temperature");
        assertEquals(superheated.enthalpy(), boiled.enthalpy(), 1e-9 * Math.abs(superheated.enthalpy()), "at the junction's enthalpy");
        assertEquals(1, boiled.waterLiquid() + boiled.waterVapor(), 1e-15, "the water its own");
        var barely = model.state(tw, saturation * (1 - 1e-9), none, none, 1, 0, saturation * (1 - 1e-9));
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
        int answered = 0, steamBeyond = 0, wetGas = 0;
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
                        if (waterAmount > 0 && state.waterPartialPressure() > FluidThermodynamics.waterVaporPressureLimit(t)) steamBeyond++;
                        for (int i = 0; i < hydrocarbons; i++) {
                            assertEquals(overall[i], state.liquidView()[i] + state.vaporView()[i], 1e-13, at + " conserves " + model.components().get(i));
                        }
                        if (feed.gas()) {
                            assertEquals(0, sum(state.liquidView()), 0, at + ": a hot gas has nothing in the liquid slot");
                            // The engine's own label and the network model's flags of the same root.
                            var result = model.phaseEngine().tp(t, p, Arrays.copyOf(feed.x(), n), false);
                            assertEquals(1, result.phases().size(), at);
                            var kind = result.phases().get(0).kind();
                            assertTrue(kind == com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR
                                    || kind == com.wormzjl.icheme.science.thermo.phase.PhaseKind.FLUID, at + ": " + kind);
                            var eos = model.hydrocarbon.translated();
                            var prepared = terms.computeIfAbsent(t, model.hydrocarbon::prepare);
                            var values = eos.evaluateValues(t, p, feed.x(), Root.VAPOR, prepared);
                            assertTrue(HydrocarbonModel.liquidLike(values) == false
                                    && PhaseIdentification.vapourLike(values.phaseIdentificationParameter(), values.compressibility()), at);
                            assertTrue(model.hydrocarbon.phase(t, p, feed.x(), Root.VAPOR, prepared).vaporLike(), at);
                            assertThrows(HydrocarbonModel.RootAbsent.class, () -> model.hydrocarbon.phase(t, p, feed.x(), Root.LIQUID, prepared, true), at);
                        }
                        if (waterAmount > 0) {
                            assertEquals(0, state.waterLiquid(), 0, at + ": all steam");
                            assertEquals(p, state.hydrocarbonPartialPressure() + state.waterPartialPressure(), 1e-6 * p, at);
                            assertTrue(state.hydrocarbonPartialPressure() > 0.5 * p, at + ": pc " + state.hydrocarbonPartialPressure());
                            // The virial steam (B p_w/(R T) at most 2 % here) in a gas whose Z is within a few per cent of one: p_w close to y_w P (the heavy
                            // mixture's gas has the lowest Z: p_w/(y_w P) 1.05 at 750 K and 2 MPa).
                            double steamFraction = state.waterVapor() / (state.waterVapor() + sum(state.vaporView()));
                            double ratio = state.waterPartialPressure() / (steamFraction * p);
                            assertEquals(1, ratio, 0.06, at + ": p_w " + state.waterPartialPressure() + " against y_w P " + steamFraction * p);
                            if (feed.gas()) {
                                wetGas++;
                                worstRatio = Math.max(worstRatio, Math.abs(ratio - 1));
                            }
                            if (t == 750 && p == 1e6) System.out.printf("WP7c %s: pc %.3f Pa, p_w %.3f Pa, p_w/(y_w P) %.6f%n", at,
                                    state.hydrocarbonPartialPressure(), state.waterPartialPressure(), ratio);
                        }
                    }
                }
            }
        }
        System.out.printf("WP7c hot network states: %d answered, %d past the steam's qualified partial pressure; the gases' p_w within %.4f of y_w P over %d wet states%n",
                answered, steamBeyond, worstRatio, wetGas);
        assertTrue(answered > 100 && wetGas > 40, answered + " answered, " + wetGas + " wet gases");
        // The gases' Z lies within 4 % of one here (nitrogen 1.036 at 600 K and 10 MPa, the light gas 0.977 at 2 MPa), and the
        // virial steam's B p_w/(R T) within 2 % (-1.8 % in that nitrogen); the two add there: 0.051 (under 4 % with ideal steam).
        assertTrue(worstRatio < 0.06, "the gases' steam partial pressure is the ideal-mixing one to 6 %: " + worstRatio);
        // The pinned 700 K states of the WP7b note: pure N2 with 0.1 mol water at 0.1 MPa, and the lean gas at 0.1 MPa.
        var wetNitrogen = model.flashTP(700, 1e5, pure(nitrogen, 1, 0.1), () -> {});
        assertEquals(1e5 / 11, wetNitrogen.waterPartialPressure(), 1e-3 * 1e5 / 11, "p_w of 0.1 mol steam in 1.1 mol gas");
        double[] leanWet = Arrays.copyOf(lean, n);leanWet[water] = 0.1;
        assertTrue(model.flashTP(700, 1e5, leanWet, () -> {}).hydrocarbonPartialPressure() > 9e4);
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
    void aLiquidLiquidSplitIsRefusedTypedByTheNetwork() {
        var pilot = FluidTestSupport.model(MaterialCatalog.bundled(), PILOT);
        double[] typeThree = {0.3, 0, 0.7, 0, 0};
        var answer = pilot.phaseEngine().tp(95, 2e6, typeThree, false);
        assertEquals(EquilibriumResult.Classification.LIQUID_LIQUID, answer.classification(), answer.toString());
        var refused = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.flashTP(95, 2e6, typeThree, () -> {}));
        // The service's reason, the kind, the state and the package are the hold's message (main's hold has no accessors).
        assertTrue(refused.getMessage().startsWith("Unsupported phases: PHASE_COMPETITION_NOT_QUALIFIED (LIQUID_LIQUID) at 95.0 K, 2000000.0 Pa (package "
                + PILOT + "): "), refused.getMessage());
        assertTrue(refused.getMessage().contains("liquid-liquid") && refused.getMessage().contains("one hydrocarbon liquid"), refused.getMessage());
        var direct = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.adopt(95, 2e6, typeThree, answer, null));
        assertEquals(refused.getMessage(), direct.getMessage());

        double[] threePhase = {0.94, 0, 0.06, 0, 0};
        var indication = pilot.phaseEngine().tp(125, 3e6, threePhase, false);
        assertEquals(EquilibriumResult.Status.NOT_CONVERGED, indication.status(), indication.toString());
        assertFalse(indication.diagnostics().criticalBand(), "outside the band");
        assertTrue(indication.detail().startsWith(EquilibriumResult.UNSTABLE_PRODUCT), indication.detail());
        var third = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.flashTP(125, 3e6, threePhase, () -> {}));
        assertTrue(third.getMessage().startsWith("Unsupported phases: PHASE_COMPETITION_NOT_QUALIFIED (THREE_PHASES) at 125.0 K, 3000000.0 Pa"),
                third.getMessage());
        assertTrue(third.getMessage().contains("third fluid phase"), third.getMessage());
        double[] atTheBandRule = {0.95, 0, 0.05, 0, 0};
        var band = assertThrows(FluidThermodynamics.CriticalBandHold.class, () -> pilot.flashTP(125, 3e6, atTheBandRule, () -> {}));
        assertTrue(band.getMessage().contains(EquilibriumResult.UNSTABLE_PRODUCT), band.getMessage());

        double[] denseGas = {0.55, 0, 0.45, 0, 0};
        var dense = pilot.phaseEngine().tp(195, 1e7, denseGas, false);
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, dense.classification(), dense.toString());
        var denseState = pilot.flashTP(195, 1e7, denseGas, () -> {});
        assertTrue(sum(denseState.liquidView()) > 0 && sum(denseState.vaporView()) > 0, "a dense gas beside a liquid takes both slots");

        double[] nearCritical = {0.67, 0.33, 0, 0, 0};
        var split = pilot.phaseEngine().tp(150, 4.64e6, nearCritical, false);
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, split.classification(), split.toString());
        assertTrue(split.diagnostics().criticalBand());
        var state = pilot.flashTP(150, 4.64e6, nearCritical, () -> {});
        assertTrue(sum(state.liquidView()) > 0 && sum(state.vaporView()) > 0, "both slots");
    }

    /**
     * The design review's E9 (cold fuzz seed 55 under its form A): a junction's arrival mixture, methane with 5.3 mol of CO2
     * per 17.8 of methane and traces of nitrogen and ethane, at 186 K and 2.86 MPa under the CO2 crystal's competition. The
     * engine converges a two-phase split, but the stability re-check leaves both products unresolved: a third fluid phase is
     * not excluded, so the split is no answer. That is the proved indication's typed hold short of the proof
     * ({@code THREE_PHASES}), never a plain refusal: a seed that meets it keeps its guess, a substep counts it under its key,
     * and an island holding on it names the node.
     */
    @Test
    void aSplitWhoseProductStabilityIsUnresolvedIsHeldTyped() {
        var pilot = FluidTestSupport.model(MaterialCatalog.bundled(), PILOT);
        double t = 185.98296668278303, p = 2861811.264201872;
        double[] arrival = {0.3324767803012572, 17.82807635831462, 0.2742249567265616, 5.342048217743187, 0};
        var answer = pilot.phaseEngine().tp(t, p, arrival, pilot.crystalsCompete(t, arrival));
        assertEquals(EquilibriumResult.Status.NOT_CONVERGED, answer.status(), answer.toString());
        assertFalse(answer.diagnostics().criticalBand(), "outside the band");
        assertTrue(answer.detail().startsWith(EquilibriumResult.UNRESOLVED_PRODUCT), answer.detail());
        var hold = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.flashTP(t, p, arrival, () -> {}));
        assertEquals(FluidThermodynamics.UnsupportedPhases.Kind.THREE_PHASES, hold.kind());
        assertTrue(hold.getMessage().contains("not excluded"), hold.getMessage());
        assertEquals(22, hold.at(22).node(), "a caller that knows the node names it");
    }

    /** The model's own saturation pressure of pure nitrogen, where its liquid and vapour fugacities agree. */
    private double saturationPressure(double t) {
        double[] pure = new double[n - 1];pure[nitrogen] = 1;
        var terms = model.hydrocarbon.prepare(t);
        double low = 1e4, high = 3.3e6;
        for (int k = 0; k < 200 && high - low > 1e-14 * high; k++) {
            double mid = Math.sqrt(low * high);
            var l = model.hydrocarbon.phase(t, mid, pure, Root.LIQUID, terms);
            var v = model.hydrocarbon.phase(t, mid, pure, Root.VAPOR, terms);
            // Below the saturation pressure the vapour has the lower fugacity.
            if (l.logFugacityView()[nitrogen] > v.logFugacityView()[nitrogen]) low = mid; else high = mid;
        }
        return 0.5 * (low + high);
    }
    private static double sum(double[] values) { double s = 0; for (double v : values) s += v; return s; }
    private static long[] bits(double[] values) { return Arrays.stream(values).mapToLong(Double::doubleToLongBits).toArray(); }
}
