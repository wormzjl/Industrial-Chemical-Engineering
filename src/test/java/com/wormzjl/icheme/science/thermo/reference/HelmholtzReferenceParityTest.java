package com.wormzjl.icheme.science.thermo.reference;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity of the Java Helmholtz oracle with CoolProp 8.0.0 (gate G1, decision D4) on the fixtures written by
 * {@code tools/coolprop-parity/generate_parity.py} (main checkout): every single-phase state of the (T, P) grid and
 * the review probe, and every saturation state. Worst cases per fluid and quantity are printed.
 *
 * <p>Deviations are relative, |mine - ref| / max(|ref|, floor). The floor is zero except for enthalpy (R T) and
 * entropy (R): the files' reference states put zeros of h and s inside the range (water's triple-point liquid,
 * methane's and ethane's normal-boiling-point liquid), where a plain relative deviation has no meaning.
 *
 * <p>Single-phase rows, all at 1e-10: (a) "EOS ..." compares the oracle with CoolProp's EOS at the same (T, rho),
 * rho being the density CoolProp's PT flash reported (CoolProp's values from a DmolarT update there); (b) "density"
 * compares the oracle's density solve at (T, P), on the root CoolProp's phase string names, with that density.
 * (c) "PT ..." compares the oracle's properties at its own (T, P) solution with the values CoolProp's PT flash
 * returned. These are loosened: CoolProp's PT flash does not return exactly its EOS at the density it reports; its
 * outputs differ from a DmolarT evaluation at that density by up to 3.3e-6 on cp and 2.4e-7 on the fugacity
 * coefficient for ethane at 305.4 K and 4.9 MPa, 0.08 K above Tc, and by up to 1.3e-8 at the other supercritical
 * states. The oracle agrees with the DmolarT evaluation to 1e-11 and better (rows (a)), so the loosened tolerances
 * measure CoolProp's flash, not the port.
 *
 * <p>Saturation rows, all at 1e-10 except "Psat (superancillary)": CoolProp 8 takes the saturation pressure from a
 * superancillary expansion, which disagrees with CoolProp's own EOS pressure at its own saturated-vapour density by
 * up to 7.3e-8 at ethane's triple point (1.1 Pa) and 1.0e-10 at water's; "Psat vs EOS p(rhoV)" checks the oracle's
 * saturation pressure against that EOS pressure at 1e-10.
 *
 * <p>Loosened tolerances are the tightest value of the 1-2-5 series above the measured worst case. Batch
 * 2026-11-15-supercritical-fluids (S0) added seven fluids (n-propane, ethylene, n-hexane, n-heptane, toluene, n-decane,
 * n-dodecane) and the supercritical grid of {@code G3F11SupercriticalRegionTest} (to 30 MPa) as single-phase rows; every
 * (a) and (b) row and every saturation row but the superancillary one stayed at 1e-10, and the PT and superancillary
 * tolerances were re-set by the same rule: the PT flash differs from CoolProp's own EOS by up to 1.9e-9 (h) for nitrogen at
 * 145 K and 30 MPa and 1.0e-7 (fugacity coefficient) for liquid toluene at 275 K and 10 kPa; the superancillary Psat
 * differs from the EOS at the triple points of the new fluids, by up to 5.7e-4 for propane at 85.5 K (1.7e-4 Pa).
 */
class HelmholtzReferenceParityTest {
    private static final double TARGET = 1.0e-10;
    /** Loosened tolerances, keyed "fluid/quantity"; every other pair uses {@link #TARGET}. */
    private static final Map<String, Double> LOOSENED = Map.ofEntries(
            Map.entry("Nitrogen/PT h", 2.0e-9),
            Map.entry("CarbonDioxide/PT h", 5.0e-9),
            Map.entry("CarbonDioxide/PT s", 5.0e-9),
            Map.entry("CarbonDioxide/PT cp", 5.0e-8),
            Map.entry("CarbonDioxide/PT cv", 2.0e-8),
            Map.entry("CarbonDioxide/PT fugacity coefficient", 2.0e-8),
            Map.entry("Methane/PT h", 2.0e-9),
            Map.entry("Methane/PT s", 1.0e-9),
            Map.entry("Methane/PT cp", 2.0e-8),
            Map.entry("Methane/PT cv", 5.0e-10),
            Map.entry("Methane/PT fugacity coefficient", 5.0e-9),
            Map.entry("Ethane/PT h", 5.0e-8),
            Map.entry("Ethane/PT s", 2.0e-8),
            Map.entry("Ethane/PT cp", 5.0e-6),
            Map.entry("Ethane/PT cv", 2.0e-7),
            Map.entry("Ethane/PT fugacity coefficient", 5.0e-7),
            Map.entry("Hydrogen/PT h", 2.0e-9),
            Map.entry("Hydrogen/PT s", 2.0e-9),
            Map.entry("Hydrogen/PT cp", 1.0e-8),
            Map.entry("Hydrogen/PT cv", 1.0e-9),
            Map.entry("Hydrogen/PT fugacity coefficient", 5.0e-9),
            Map.entry("Water/PT h", 1.0e-9),
            Map.entry("Water/PT s", 1.0e-9),
            Map.entry("Water/PT cp", 2.0e-9),
            Map.entry("Water/PT cv", 5.0e-9),
            Map.entry("Water/PT fugacity coefficient", 5.0e-9),
            Map.entry("Nitrogen/PT s", 5.0e-10),
            Map.entry("Nitrogen/PT cp", 2.0e-9),
            Map.entry("Nitrogen/PT cv", 5.0e-10),
            Map.entry("Nitrogen/PT fugacity coefficient", 1.0e-9),
            Map.entry("Ethane/Psat (superancillary)", 1.0e-7),
            Map.entry("Water/Psat (superancillary)", 2.0e-10),
            Map.entry("n-Propane/PT h", 2.0e-9),
            Map.entry("n-Propane/PT s", 1.0e-9),
            Map.entry("n-Propane/PT cp", 2.0e-8),
            Map.entry("n-Propane/PT cv", 5.0e-9),
            Map.entry("n-Propane/PT fugacity coefficient", 1.0e-8),
            Map.entry("n-Propane/Psat (superancillary)", 1.0e-3),
            Map.entry("Ethylene/PT h", 1.0e-9),
            Map.entry("Ethylene/PT s", 5.0e-10),
            Map.entry("Ethylene/PT cp", 1.0e-8),
            Map.entry("Ethylene/PT cv", 2.0e-9),
            Map.entry("Ethylene/PT fugacity coefficient", 5.0e-9),
            Map.entry("Ethylene/Psat (superancillary)", 5.0e-10),
            Map.entry("n-Hexane/PT h", 5.0e-10),
            Map.entry("n-Hexane/PT s", 2.0e-10),
            Map.entry("n-Hexane/PT cp", 1.0e-9),
            Map.entry("n-Hexane/PT cv", 2.0e-10),
            Map.entry("n-Hexane/PT fugacity coefficient", 5.0e-8),
            Map.entry("n-Hexane/Psat (superancillary)", 2.0e-9),
            Map.entry("n-Heptane/PT h", 1.0e-9),
            Map.entry("n-Heptane/PT s", 5.0e-10),
            Map.entry("n-Heptane/PT cp", 1.0e-8),
            Map.entry("n-Heptane/PT cv", 5.0e-10),
            Map.entry("n-Heptane/PT fugacity coefficient", 1.0e-8),
            Map.entry("n-Heptane/Psat (superancillary)", 1.0e-6),
            Map.entry("Toluene/PT h", 1.0e-9),
            Map.entry("Toluene/PT s", 5.0e-10),
            Map.entry("Toluene/PT cp", 2.0e-9),
            Map.entry("Toluene/PT cv", 5.0e-10),
            Map.entry("Toluene/PT fugacity coefficient", 2.0e-7),
            Map.entry("Toluene/Psat (superancillary)", 5.0e-6),
            Map.entry("n-Decane/PT h", 1.0e-9),
            Map.entry("n-Decane/PT s", 5.0e-10),
            Map.entry("n-Decane/PT cp", 1.0e-8),
            Map.entry("n-Decane/PT cv", 5.0e-10),
            Map.entry("n-Decane/PT fugacity coefficient", 1.0e-8),
            Map.entry("n-Decane/Psat (superancillary)", 1.0e-9),
            Map.entry("n-Dodecane/PT h", 2.0e-10),
            Map.entry("n-Dodecane/PT s", 2.0e-10),
            Map.entry("n-Dodecane/PT cp", 2.0e-8),
            Map.entry("n-Dodecane/PT fugacity coefficient", 5.0e-9),
            Map.entry("n-Dodecane/Psat (superancillary)", 2.0e-7));

    @ParameterizedTest
    @ValueSource(strings = {"Nitrogen", "CarbonDioxide", "Methane", "Ethane", "Hydrogen", "Water",
            "n-Propane", "Ethylene", "n-Hexane", "n-Heptane", "Toluene", "n-Decane", "n-Dodecane"})
    void singlePhaseStatesMatchCoolProp(String fluidName) {
        HelmholtzFluid fluid = HelmholtzFluid.load(fluidName);
        JsonObject fixture = JsonFluidFiles.resource("parity-" + fluidName + ".json");
        Report report = new Report(fluidName);
        long start = System.nanoTime();
        int count = 0;
        for (JsonElement element : fixture.getAsJsonArray("single_phase")) {
            JsonObject row = element.getAsJsonObject();
            double t = JsonFluidFiles.number(row, "T");
            double p = JsonFluidFiles.number(row, "P");
            String phase = row.get("phase").getAsString();
            String where = String.format(Locale.ROOT, "T=%.4f K P=%.4g Pa %s", t, p, phase);
            double rhoRef = JsonFluidFiles.number(row, "Dmolar");
            double r = fluid.gasConstant();
            JsonObject eos = row.getAsJsonObject("eos_at_Dmolar");

            // (a) The equation of state itself: the oracle and CoolProp at the same (T, rho).
            HelmholtzState same = fluid.state(t, rhoRef);
            report.add("EOS p", same.pressure(), JsonFluidFiles.number(eos, "P"), 0.0, where);
            report.add("EOS h", same.enthalpy(), JsonFluidFiles.number(eos, "Hmolar"), r * t, where);
            report.add("EOS s", same.entropy(), JsonFluidFiles.number(eos, "Smolar"), r, where);
            report.add("EOS cp", same.isobaricHeatCapacity(), JsonFluidFiles.number(eos, "Cpmolar"), 0.0, where);
            report.add("EOS cv", same.isochoricHeatCapacity(), JsonFluidFiles.number(eos, "Cvmolar"), 0.0, where);
            report.add("EOS fugacity coefficient", same.fugacityCoefficient(),
                    JsonFluidFiles.number(eos, "fugacity_coefficient"), 0.0, where);

            // (b) The density solve at (T, P) on the root CoolProp's phase names.
            HelmholtzState state = fluid.stateAtPressure(t, p, root(phase));
            report.add("density", state.density(), rhoRef, 0.0, where);

            // (c) End to end at (T, P) against CoolProp's PT flash outputs.
            report.add("PT h", state.enthalpy(), JsonFluidFiles.number(row, "Hmolar"), r * t, where);
            report.add("PT s", state.entropy(), JsonFluidFiles.number(row, "Smolar"), r, where);
            report.add("PT cp", state.isobaricHeatCapacity(), JsonFluidFiles.number(row, "Cpmolar"), 0.0, where);
            report.add("PT cv", state.isochoricHeatCapacity(), JsonFluidFiles.number(row, "Cvmolar"), 0.0, where);
            report.add("PT fugacity coefficient", state.fugacityCoefficient(),
                    JsonFluidFiles.number(row, "fugacity_coefficient"), 0.0, where);
            count++;
        }
        report.print(String.format(Locale.ROOT, "single phase, %d states, %.1f ms", count,
                (System.nanoTime() - start) / 1.0e6));
        report.assertWithinTolerance();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Nitrogen", "CarbonDioxide", "Methane", "Ethane", "Hydrogen", "Water",
            "n-Propane", "Ethylene", "n-Hexane", "n-Heptane", "Toluene", "n-Decane", "n-Dodecane"})
    void saturationStatesMatchCoolProp(String fluidName) {
        HelmholtzFluid fluid = HelmholtzFluid.load(fluidName);
        JsonObject fixture = JsonFluidFiles.resource("parity-" + fluidName + ".json");
        Report report = new Report(fluidName);
        long start = System.nanoTime();
        int count = 0;
        for (JsonElement element : fixture.getAsJsonArray("saturation")) {
            JsonObject row = element.getAsJsonObject();
            double t = JsonFluidFiles.number(row, "T");
            String where = String.format(Locale.ROOT, "T=%.4f K", t);
            double r = fluid.gasConstant();
            SaturationState sat = fluid.saturation(t);
            report.add("Psat (superancillary)", sat.pressure(), JsonFluidFiles.number(row, "Psat"), 0.0, where);
            report.add("Psat vs EOS p(rhoV)", sat.pressure(), JsonFluidFiles.number(row, "pV_eos"), 0.0, where);
            report.add("rhoL", sat.liquid().density(), JsonFluidFiles.number(row, "rhoL"), 0.0, where);
            report.add("rhoV", sat.vapour().density(), JsonFluidFiles.number(row, "rhoV"), 0.0, where);
            report.add("hL", sat.liquid().enthalpy(), JsonFluidFiles.number(row, "hL"), r * t, where);
            report.add("hV", sat.vapour().enthalpy(), JsonFluidFiles.number(row, "hV"), r * t, where);
            report.add("sL", sat.liquid().entropy(), JsonFluidFiles.number(row, "sL"), r, where);
            report.add("sV", sat.vapour().entropy(), JsonFluidFiles.number(row, "sV"), r, where);
            count++;
        }
        report.print(String.format(Locale.ROOT, "saturation, %d states, %.1f ms", count,
                (System.nanoTime() - start) / 1.0e6));
        report.assertWithinTolerance();
    }

    /** The density root CoolProp's phase string names; above the critical temperature the root is unique. */
    private static HelmholtzFluid.Root root(String phase) {
        return switch (phase) {
            case "liquid", "supercritical_liquid", "supercritical" -> HelmholtzFluid.Root.LIQUID;
            case "gas", "supercritical_gas" -> HelmholtzFluid.Root.VAPOUR;
            default -> throw new IllegalArgumentException("unexpected CoolProp phase " + phase);
        };
    }

    /** Worst relative deviation per quantity for one fluid. */
    private static final class Report {
        private final String fluid;
        private final Map<String, double[]> worst = new LinkedHashMap<>();
        private final Map<String, String> worstWhere = new LinkedHashMap<>();

        Report(String fluid) {
            this.fluid = fluid;
        }

        void add(String quantity, double mine, double reference, double floor, String where) {
            double deviation = Math.abs(mine - reference) / Math.max(Math.abs(reference), floor);
            if (!Double.isFinite(mine)) {
                deviation = Double.POSITIVE_INFINITY;
            }
            double[] current = worst.computeIfAbsent(quantity, k -> new double[] {-1.0});
            if (deviation > current[0]) {
                current[0] = deviation;
                worstWhere.put(quantity, where + String.format(Locale.ROOT, " (mine %.17g, CoolProp %.17g)",
                        mine, reference));
            }
        }

        double tolerance(String quantity) {
            return LOOSENED.getOrDefault(fluid + "/" + quantity, TARGET);
        }

        void print(String header) {
            System.out.printf(Locale.ROOT, "[helmholtz-parity] %s %s%n", fluid, header);
            for (Map.Entry<String, double[]> entry : worst.entrySet()) {
                System.out.printf(Locale.ROOT, "[helmholtz-parity]   %-14s %-30s worst %.3e (tol %.0e) at %s%n",
                        fluid, entry.getKey(), entry.getValue()[0], tolerance(entry.getKey()),
                        worstWhere.get(entry.getKey()));
            }
        }

        void assertWithinTolerance() {
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, double[]> entry : worst.entrySet()) {
                double tolerance = tolerance(entry.getKey());
                if (!(entry.getValue()[0] <= tolerance)) {
                    failures.add(String.format(Locale.ROOT, "%s %s: worst %.3e > %.0e at %s", fluid, entry.getKey(),
                            entry.getValue()[0], tolerance, worstWhere.get(entry.getKey())));
                }
            }
            assertTrue(failures.isEmpty(), String.join("\n", failures));
        }
    }
}
