package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.thermo.HuronVidalExcess;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3b of batch 2026-11-11-aqueous-solvents, section 14 item 4: a gas with water is a classical pair with {@code k_ij}
 * linear in T, fitted to the Henry constant at 298.15 K and 373.15 K, so a hot wet gas keeps a nonnegative attraction (the
 * local-composition cell on the gas centre that carried the solubility before made it negative). Wet nitrogen, nitrogen
 * with methane and a flue gas answer from 300 K to the package ceiling at 1 atm and 10 MPa, one gas above their dew point
 * and close to the ideal gas above 900 K at 1 atm; every remaining local-composition pair of the network's list evaluates
 * on both roots from 90 K to the ceiling without a kernel refusal.
 */
class WetGasGatesTest {
    private static final double ATM = 101325;
    private final FluidThermodynamics model = FluidTestSupport.networkModel();

    private double[] feed(Object... pairs) {
        double[] n = new double[model.componentCount()];
        for (int k = 0; k < pairs.length; k += 2) n[model.components().indexOf((String) pairs[k])] = ((Number) pairs[k + 1]).doubleValue();
        return n;
    }

    @Test void hotWetGasesAnswerAsOneGas() {
        double ceiling = model.domain().envelope().maximumTemperature();
        Object[][] gases = {{"wet nitrogen 0.1", feed("Nitrogen", 1, "Water", 0.1)}, {"wet nitrogen 0.5", feed("Nitrogen", 1, "Water", 0.5)},
                {"nitrogen with methane, water 0.1", feed("Nitrogen", 0.5, "Methane", 0.5, "Water", 0.1)},
                {"nitrogen with methane, water 0.5", feed("Nitrogen", 0.5, "Methane", 0.5, "Water", 0.5)},
                {"flue gas", feed("Nitrogen", 0.72, "CarbonDioxide", 0.12, "Water", 0.16)}};
        int w = model.componentCount() - 1;
        for (Object[] gas : gases) {
            double[] n = (double[]) gas[1];
            double total = 0;
            for (double v : n) total += v;
            double y = n[w] / total;
            for (double p : new double[] {ATM, 1e7}) for (double t = 300; t <= ceiling + 1e-9; t += 25) {
                String at = gas[0] + " at " + t + " K, " + p + " Pa";
                var s = model.flashTP(t, p, n, () -> {});
                // One gas where the water's partial pressure is under its saturation pressure with a margin (5 % for the
                // gas's nonideality and the dissolved gas).
                if (y * p < 0.95 * model.saturationPressure(Math.min(t, 647.0)) || t > 647.1) {
                    assertEquals(0, s.liquidSlotVolume() + s.aqueousSlotVolume(), at + ": one gas");
                    if (t > 900 && p == ATM) {
                        double ideal = total * PengRobinsonKernel.GAS_CONSTANT * t / p;
                        assertEquals(1, s.volume() / ideal, 0.02, at + ": near the ideal gas");
                    }
                }
            }
        }
    }

    /** Item 9: the scan on the network's list and on the pilot list (water, ethanol, n-hexane). */
    @Test void everyLocalCompositionPairScansWithoutAKernelRefusal() {
        scan(model);
        scan(FluidThermodynamics.forNetwork(com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.catalog(),
                com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.PACKAGE_ID));
    }

    private static void scan(FluidThermodynamics model) {
        var kernel = model.hydrocarbon.translated().kernel();
        List<String> species = model.hydrocarbon.species();
        var derivation = CubicPhaseEvaluator.derived(model.catalog(), model.hydrocarbon.propertyPackage().id(), species).derivation();
        double ceiling = model.domain().envelope().maximumTemperature();
        var ws = kernel.newWorkspace();
        var ev = kernel.newEvaluation();
        var refusals = new ArrayList<String>();
        int pairs = 0;
        for (var pair : derivation.pairs()) {
            if (pair.source() != DerivedPairs.Source.ACTIVITY) continue;
            pairs++;
            int a = species.indexOf(pair.first()), b = species.indexOf(pair.second());
            for (double t = 90; t <= ceiling + 1e-9; t += 30) for (double p : new double[] {ATM, 1e7})
                for (double x = 0.02; x <= 0.98 + 1e-12; x += 0.12) for (var root : PengRobinsonKernel.Root.values()) {
                    double[] z = new double[species.size()];
                    z[a] = x;
                    z[b] = 1 - x;
                    try {
                        kernel.evaluate(t, p, z, root, ws, ev);
                        for (double v : ev.logFugacityCoefficientsView()) if (!Double.isFinite(v)) throw new IllegalStateException("nonfinite ln phi");
                    } catch (IllegalStateException | IllegalArgumentException refused) {
                        refusals.add(pair.first() + " / " + pair.second() + " at " + t + " K, " + p + " Pa, x " + x + ", " + root + ": " + refused.getMessage());
                    }
                }
        }
        assertTrue(pairs > 0, "the list has local-composition pairs");
        var byPair = new java.util.TreeMap<String, String>();
        for (String r : refusals) { String key = r.substring(0, r.indexOf(" at ")); String t = r.substring(r.indexOf(" at ") + 4, r.indexOf(" K")); byPair.merge(key, t, (o, v) -> Double.parseDouble(v) < Double.parseDouble(o) ? v : o); }
        var counts = new java.util.TreeMap<String, Integer>();
        for (String r : refusals) counts.merge(r.substring(0, r.indexOf(" at ")), 1, Integer::sum);
        var summary = new StringBuilder();
        byPair.forEach((k, v) -> summary.append("\n  ").append(k).append(": ").append(counts.get(k)).append(" refusals from ").append(v).append(" K"));
        assertTrue(refusals.isEmpty(), refusals.size() + " refusals over " + pairs + " pairs:" + summary + "\n first: " + refusals.subList(0, Math.min(3, refusals.size())));
    }

    /**
     * Item 9 (option B): a cell's held energy fades from its fitted end (373.15 K) to 1.25 times its polar compound's
     * critical temperature (808.9 K for water); bit for bit the held value below the fitted end, the remaining factor below
     * 0.01 above 780 K, value, slope and second derivative continuous at both ends.
     */
    @Test void aCellFadesFromItsFittedEndToAboveItsPolarCompoundsCriticalTemperature() {
        double critical = 647.096, low = 298.15, high = 373.15, intercept = 4000, slope = -6, end = HuronVidalExcess.FADE_END * critical;
        double[] plain = new double[3], faded = new double[3];
        for (double t = 90; t <= high; t += 0.37) {
            HuronVidalExcess.held(intercept, slope, low, high, t, plain);
            HuronVidalExcess.held(intercept, slope, low, high, critical, t, faded);
            assertArrayEquals(plain, faded, 0.0, "bit for bit at " + t + " K");
        }
        for (double t = 780; t <= 1200; t += 5) {
            HuronVidalExcess.held(intercept, slope, low, high, t, plain);
            HuronVidalExcess.held(intercept, slope, low, high, critical, t, faded);
            assertTrue(Math.abs(faded[0]) < 0.01 * Math.abs(plain[0]), "the remaining factor at " + t + " K: " + faded[0] / plain[0]);
        }
        for (double at : new double[] {high, end}) {
            double d = 1e-6;
            double[] below = new double[3], above = new double[3];
            HuronVidalExcess.held(intercept, slope, low, high, critical, at - d, below);
            HuronVidalExcess.held(intercept, slope, low, high, critical, at + d, above);
            for (int k = 0; k < 3; k++) assertEquals(below[k], above[k], 1e-6 * (1 + Math.abs(below[k])), "derivative " + k + " at " + at + " K");
        }
    }

    /** Item 9: a hot hydrocarbon with steam is one gas from 700 K to the ceiling at 1 atm and 3 MPa. */
    @Test void hotHydrocarbonsWithSteamAreOneGas() {
        double ceiling = model.domain().envelope().maximumTemperature();
        for (String hydrocarbon : List.of("N-hexane", "crude_pc05", "Isobutane")) for (double water : new double[] {0.3, 3})
            for (double p : new double[] {ATM, 3e6}) for (double t = 700; t <= ceiling + 1e-9; t += 20) {
                var s = model.flashTP(t, p, feed(hydrocarbon, 1, "Water", water), () -> {});
                String at = hydrocarbon + " with " + water + " water at " + t + " K, " + p + " Pa";
                // One fluid phase: a gas, except the cut at 3 MPa, which alone is one dense fluid up to 760 K (near its own
                // critical point), with the steam dissolved in it.
                int phases = (s.vaporVolume() > 0 ? 1 : 0) + (s.liquidSlotVolume() > 0 ? 1 : 0) + (s.aqueousSlotVolume() > 0 ? 1 : 0);
                assertEquals(1, phases, at + ": one fluid phase");
                if (!hydrocarbon.startsWith("crude") || p == ATM) assertEquals(0, s.liquidSlotVolume() + s.aqueousSlotVolume(), at + ": one gas");
            }
    }

    /**
     * Item 9: water with ethanol at x = 0.5 on the liquid root keeps a positive heat capacity from 373 to 640 K at 10, 20
     * and 30 MPa (the fade's own heat capacity, -T E f'', at most 35.5 J/mol/K there).
     */
    @Test void waterWithEthanolKeepsAPositiveHeatCapacityThroughTheFade() {
        var pilot = FluidThermodynamics.forNetwork(com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.catalog(),
                com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.PACKAGE_ID);
        var eos = pilot.hydrocarbon.translated();
        List<String> species = pilot.hydrocarbon.species();
        double[] x = new double[species.size()];
        x[species.indexOf("Water")] = 0.5;
        x[species.indexOf("Ethanol")] = 0.5;
        for (double p : new double[] {1e7, 2e7, 3e7}) for (double t = 373; t <= 640; t += 1) {
            double cp = eos.evaluateValues(t, p, x, PengRobinsonKernel.Root.LIQUID, eos.prepare(t)).heatCapacity();
            assertTrue(cp > 0, "Cp " + cp + " at " + t + " K, " + p + " Pa");
        }
    }
}
