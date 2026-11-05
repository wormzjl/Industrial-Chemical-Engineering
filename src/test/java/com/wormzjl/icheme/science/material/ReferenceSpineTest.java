package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.wormzjl.icheme.science.thermo.reference.AlphaDerivatives;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reference tests of the reference-spine records (batch 2026-09-24-coolprop-low-temperature, decisions D6, D7 and
 * D13): the CoolProp segments against the Java Helmholtz oracle, the CEA segments against the CoolProp alpha0 above
 * the join (the JANAF / Gurvich holdouts until batch 2026-11-04-open-thermo-data), the anchors, the joins,
 * determinism, and the loader's refusals. Methane's GERG-2008 segment and its line-list holdout are in
 * {@link MethaneSpineTest}.
 */
class ReferenceSpineTest {
    private static final String[] SPECIES = {"nitrogen", "methane", "ethane", "carbon_dioxide"};
    /** The species with NASA CEA segments; methane has none since spine r2 (D13). */
    private static final String[] CEA_SPECIES = {"nitrogen", "ethane", "carbon_dioxide"};
    private static final Map<String, String> FLUID = Map.of("nitrogen", "Nitrogen", "methane", "Methane", "ethane", "Ethane",
            "carbon_dioxide", "CarbonDioxide");

    /** The spine records are bundled since P3 (main resources). */
    private static ReferenceSpine spine(String species) {
        return com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().requireSpine(PilotCryogenicTestCatalog.PACKAGE_ID, FLUID.get(species));
    }

    private static String path(String species) { return "data/icheme/materials/spine/" + species + ".json"; }

    /**
     * Every CoolProp (Helmholtz) segment equals the oracle's ideal part; each spine starts at its fluid's triple point
     * except CO2, whose alpha0 is continued down to 90 K as its own segment (P3 WP3). Methane's GERG-2008 segment is a
     * Helmholtz segment of another source and is held to its own transcription in {@link MethaneSpineTest}.
     */
    @Test
    void coolPropSegmentEqualsTheHelmholtzOracle() {
        var derivatives = new AlphaDerivatives();
        double p = 1e5;
        for (String species : SPECIES) {
            var spine = spine(species);
            var fluid = HelmholtzFluid.load(FLUID.get(species));
            assertEquals(species.equals("carbon_dioxide") ? 90 : fluid.tripleTemperature(), spine.minimumTemperature(), 0.0, species);
            for (var segment : spine.segments()) {
                if (!segment.type().equals("helmholtz_ideal_terms") || !segment.revision().startsWith("coolprop-")) continue;
                double r = fluid.gasConstant(), h0 = oracleEnthalpy(fluid, 298.15, derivatives), s0 = oracleEntropy(fluid, 298.15, p, derivatives);
                double worstCp = 0, worstH = 0, worstS = 0;
                for (int i = 0; i <= 60; i++) {
                    double t = segment.minimumTemperature() + i * (segment.maximumTemperature() - segment.minimumTemperature()) / 60;
                    fluid.idealDerivatives(fluid.reducingTemperature() / t, 1, derivatives);
                    double tau = fluid.reducingTemperature() / t, cp = r * (1 - tau * tau * derivatives.dTau2);
                    assertEquals(cp, spine.heatCapacity(t), 1e-9 * cp, species + " Cp at " + t);
                    double dh = oracleEnthalpy(fluid, t, derivatives) - h0;
                    assertEquals(dh, spine.enthalpy(t) - spine.enthalpy(298.15), 1e-9 * Math.max(Math.abs(dh), r * 298.15), species + " h at " + t);
                    double ds = oracleEntropy(fluid, t, p, derivatives) - s0;
                    assertEquals(ds, spine.entropy(t, p) - spine.entropy(298.15, p), 1e-9 * Math.max(Math.abs(ds), r), species + " s at " + t);
                    worstCp = Math.max(worstCp, Math.abs(spine.heatCapacity(t) / cp - 1));
                    worstH = Math.max(worstH, Math.abs(spine.enthalpy(t) - spine.enthalpy(298.15) - dh) / Math.max(Math.abs(dh), r * 298.15));
                    worstS = Math.max(worstS, Math.abs(spine.entropy(t, p) - spine.entropy(298.15, p) - ds) / Math.max(Math.abs(ds), r));
                }
                System.out.printf("P2 CoolProp segment %s %.3f..%.3f K, worst relative deviation from the oracle: Cp %.2e, h %.2e, s %.2e%n",
                        species, segment.minimumTemperature(), segment.maximumTemperature(), worstCp, worstH, worstS);
            }
        }
    }

    /**
     * P3 WP3: CO2's alpha0 below its triple point against open references (batch 2026-11-04-open-thermo-data; the
     * NIST-JANAF table C-095 until then): at 100 K the rigid-rotor harmonic-oscillator Cp, a linear molecule's 7/2 R plus
     * Einstein functions of the fundamentals 1333, 667.4 (twice) and 2349.1 cm-1, where anharmonicity and Fermi resonance
     * are negligible; at 200 K NASA CEA's CO2 polynomial (thermo.inp g 9/99, Gurvich 1991, Apache-2.0; its first interval
     * starts there), Cp, h(T) - h(298.15) and s(T, 1 bar). Cp within 0.2 % (D7), h within 0.1 % or 20 J/mol, s within
     * 0.02 J/(mol K) (the spine's S° is CEA's). The segment carries its own grade, estimated_declared_error; the record's
     * qualified grade applies from the triple point.
     */
    @Test
    void carbonDioxideBelowTheTriplePointAgainstOpenReferences() {
        var co2 = spine("carbon_dioxide");
        var below = co2.segments().getFirst();
        assertEquals(90, below.minimumTemperature(), 0.0);
        assertEquals(216.592, below.maximumTemperature(), 0.0);
        assertEquals(MaterialCoverage.Grade.ESTIMATED_DECLARED_ERROR, below.coverage().grade());
        assertEquals(MaterialCoverage.Grade.ESTIMATED_DECLARED_ERROR, co2.coverageAt(150).grade());
        assertEquals(MaterialCoverage.Grade.QUALIFIED, co2.coverageAt(250).grade());
        assertEquals(MaterialCoverage.Grade.QUALIFIED, co2.coverage().grade());
        // 100 K: the rigid-rotor harmonic-oscillator Cp (second radiation constant 1.438776877 cm K, R = 8.314462618).
        double rrho = 8.314462618 * (3.5 + einstein(1333 * 1.438776877 / 100) + 2 * einstein(667.4 * 1.438776877 / 100)
                + einstein(2349.1 * 1.438776877 / 100));
        double cp100 = co2.heatCapacity(100);
        System.out.printf("CO2 below the triple point at 100 K: Cp %.5f (rigid rotor, harmonic oscillators %.4f, %+.3f %%)%n", cp100, rrho, 100 * (cp100 / rrho - 1));
        assertEquals(rrho, cp100, 0.002 * rrho, "Cp at 100 K");
        // 200 K: NASA CEA CO2, first interval, R = 8.314510: Cp, h - h(298.15), s(1 bar) (tools/open-thermo-data/spine-checks.mjs).
        double[] cea = {4.943650540e+04, -6.264116010e+02, 5.301725240e+00, 2.503813816e-03, -2.127308728e-07, -7.689988780e-10,
                2.849677801e-13, -4.528198460e+04, -7.048279440e+00};
        double t = 200, cp = co2.heatCapacity(t), dh = co2.enthalpy(t) - co2.enthalpy(298.15), s = co2.entropy(t, 1e5);
        double ceaCp = ceaHeatCapacity(cea, t), ceaDh = ceaEnthalpy(cea, t) - ceaEnthalpy(cea, 298.15), ceaS = ceaEntropy(cea, t);
        System.out.printf("CO2 below the triple point at 200 K: Cp %.5f (CEA %.4f, %+.3f %%), h - h(298.15) %.2f J/mol (CEA %.2f), "
                + "s %.4f J/(mol K) (CEA %.4f)%n", cp, ceaCp, 100 * (cp / ceaCp - 1), dh, ceaDh, s, ceaS);
        assertEquals(ceaCp, cp, 0.002 * ceaCp, "Cp at 200 K");
        assertEquals(ceaDh, dh, Math.max(20, 0.001 * Math.abs(ceaDh)), "h at 200 K");
        assertEquals(ceaS, s, 0.02, "s at 200 K");
    }

    private static double einstein(double x) { return x * x * Math.exp(x) / Math.pow(Math.expm1(x), 2); }
    private static double ceaHeatCapacity(double[] a, double t) {
        return ReferenceSpine.CEA_GAS_CONSTANT * (a[0] / (t * t) + a[1] / t + a[2] + a[3] * t + a[4] * t * t + a[5] * t * t * t + a[6] * t * t * t * t);
    }
    private static double ceaEnthalpy(double[] a, double t) {
        return ReferenceSpine.CEA_GAS_CONSTANT * t * (-a[0] / (t * t) + a[1] * Math.log(t) / t + a[2] + a[3] * t / 2 + a[4] * t * t / 3
                + a[5] * t * t * t / 4 + a[6] * t * t * t * t / 5 + a[7] / t);
    }
    private static double ceaEntropy(double[] a, double t) {
        return ReferenceSpine.CEA_GAS_CONSTANT * (-a[0] / (2 * t * t) - a[1] / t + a[2] * Math.log(t) + a[3] * t + a[4] * t * t / 2
                + a[5] * t * t * t / 3 + a[6] * t * t * t * t / 4 + a[8]);
    }
    private static double oracleHeatCapacity(HelmholtzFluid fluid, double t, AlphaDerivatives d) {
        double tau = fluid.reducingTemperature() / t;
        fluid.idealDerivatives(tau, 1, d);
        return fluid.gasConstant() * (1 - tau * tau * d.dTau2);
    }

    private static double oracleEnthalpy(HelmholtzFluid fluid, double t, AlphaDerivatives d) {
        double tau = fluid.reducingTemperature() / t;
        fluid.idealDerivatives(tau, 1, d);
        return fluid.gasConstant() * t * (1 + tau * d.dTau);
    }

    private static double oracleEntropy(HelmholtzFluid fluid, double t, double p, AlphaDerivatives d) {
        double tau = fluid.reducingTemperature() / t, delta = p / (fluid.gasConstant() * t * fluid.reducingDensity());
        fluid.idealDerivatives(tau, delta, d);
        return fluid.gasConstant() * (tau * d.dTau - d.alpha);
    }

    /**
     * D7: the CEA segments' Cp within 0.2 % of the CoolProp alpha0 at 1000, 1100 and 1200 K (above each join, where the
     * spine does not use it; measured -0.011 to +0.014 %), evaluated with CEA's own gas constant 8.314510 (P3 WP9a; every
     * CEA segment of every record carries it). Until batch 2026-11-04-open-thermo-data the holdouts were the NIST-JANAF and
     * WebBook Gurvich tables.
     */
    @Test
    void ceaSegmentAgainstCoolProp() {
        double[] temperatures = {1000, 1100, 1200};
        for (String species : SPECIES) {
            for (var segment : spine(species).segments())
                if (segment.revision().startsWith("cea-"))
                    assertEquals(ReferenceSpine.CEA_GAS_CONSTANT, segment.gasConstant(), 0.0, species + " " + segment.revision());
        }
        assertTrue(spine("methane").segments().stream().noneMatch(s -> s.type().equals("nasa9")), "methane r2 has no CEA segment (D13)");
        var derivatives = new AlphaDerivatives();
        for (String species : CEA_SPECIES) {
            var spine = spine(species);
            var fluid = HelmholtzFluid.load(FLUID.get(species));
            for (int i = 0; i < temperatures.length; i++) {
                double reference = oracleHeatCapacity(fluid, temperatures[i], derivatives), deviation = spine.heatCapacity(temperatures[i]) / reference - 1;
                System.out.printf("CEA Cp %s at %.0f K: %.5f J/(mol K), CoolProp alpha0 %.5f, deviation %+.3f %%%n", species, temperatures[i],
                        spine.heatCapacity(temperatures[i]), reference, 100 * deviation);
                assertTrue(Math.abs(deviation) <= 0.002, species + " at " + temperatures[i] + " K: " + deviation);
            }
        }
    }

    /**
     * P3 WP9a: a NASA polynomial segment without {@code gas_constant_j_per_mol_kelvin} takes CEA's 8.314510 (the same
     * spine, bit for bit, as the record that states it); a Helmholtz segment must state its constant.
     */
    @Test
    void nasaSegmentsDefaultToTheCeaGasConstant() {
        var stated = spine("ethane");
        var omitted = PilotCryogenicTestCatalog.parseWith(path("ethane"), o -> {
            segment(o, 1).remove("gas_constant_j_per_mol_kelvin");
            segment(o, 2).remove("gas_constant_j_per_mol_kelvin");
        }).requireSpine(PilotCryogenicTestCatalog.PACKAGE_ID, "Ethane");
        assertEquals(ReferenceSpine.CEA_GAS_CONSTANT, omitted.segments().get(2).gasConstant(), 0.0);
        for (double t = stated.minimumTemperature(); t <= stated.maximumTemperature(); t += 13.7) {
            assertEquals(Double.doubleToRawLongBits(stated.heatCapacity(t)), Double.doubleToRawLongBits(omitted.heatCapacity(t)));
            assertEquals(Double.doubleToRawLongBits(stated.enthalpy(t)), Double.doubleToRawLongBits(omitted.enthalpy(t)));
            assertEquals(Double.doubleToRawLongBits(stated.entropy(t, 2e5)), Double.doubleToRawLongBits(omitted.entropy(t, 2e5)));
        }
        refused("ethane", o -> segment(o, 0).remove("gas_constant_j_per_mol_kelvin"), "gas_constant_j_per_mol_kelvin: required field");
    }

    @Test
    void anchorsAreExact() {
        for (String species : SPECIES) {
            var spine = spine(species);
            assertEquals(spine.formationEnthalpy().value(), spine.enthalpy(298.15), 0.0, species);
            assertEquals(spine.standardEntropy().value(), spine.entropy(298.15, 1e5), 0.0, species);
            assertEquals(-ReferenceSpine.GAS_CONSTANT * Math.log(2), spine.entropy(450, 2e5) - spine.entropy(450, 1e5), 1e-12, species);
            System.out.printf("P2 reference values %s: h(298.15) %.1f J/mol, s(298.15, 1e5) %.4f J/(mol K), Cp(298.15) %.4f J/(mol K), "
                    + "h(1000) - h(298.15) %.1f J/mol, s(1000, 1e5) %.4f J/(mol K)%n", species, spine.enthalpy(298.15), spine.entropy(298.15, 1e5),
                    spine.heatCapacity(298.15), spine.enthalpy(1000) - spine.enthalpy(298.15), spine.entropy(1000, 1e5));
        }
        assertEquals(-74513, spine("methane").formationEnthalpy().value(), 0.0);
        assertEquals(43, spine("methane").formationEnthalpy().uncertainty(), 0.0);
        assertTrue(!spine("ethane").standardEntropy().uncertaintyStated());
    }

    @Test
    void joinsAreContinuousWithASmallCpStep() {
        for (String species : SPECIES) {
            var spine = spine(species);
            assertTrue(spine.joins().size() >= (species.equals("methane") ? 1 : 2), species);
            for (var join : spine.joins()) {
                System.out.printf("P3 WP9a join %s at %.3f K: Cp %.6f -> %.6f J/(mol K), step %.3e%n", species, join.temperature(),
                        join.lowerHeatCapacity(), join.upperHeatCapacity(), join.heatCapacityStepFraction());
                assertTrue(join.heatCapacityStepFraction() <= spine.maximumCpStepFraction(), species);
                assertEquals(join.lowerEnthalpy(), join.upperEnthalpy(), 1e-9 * Math.max(Math.abs(join.lowerEnthalpy()), 1), species);
                assertEquals(join.lowerEntropy(), join.upperEntropy(), 1e-9 * Math.abs(join.lowerEntropy()), species);
                double t = join.temperature(), dt = 1e-6;
                assertEquals(spine.enthalpy(t - dt), spine.enthalpy(t + dt), 2 * dt * join.upperHeatCapacity() + 1e-9 * Math.abs(join.lowerEnthalpy()), species);
                assertEquals(spine.entropy(t - dt, 1e5), spine.entropy(t + dt, 1e5), 2 * dt * join.upperHeatCapacity() / t + 1e-9 * join.lowerEntropy(), species);
            }
        }
        // The CoolProp-to-CEA joins chosen in P2 by tools/spine-join-measurement (build-spine-records.mjs), kept in P3
        // WP9a with the steps re-measured with R = 8.314510; methane's Setzmann-Wagner-to-GERG join at 425 K (D13).
        assertEquals(600, spine("nitrogen").joins().getFirst().temperature(), 0.0);
        assertEquals(425, spine("methane").joins().getFirst().temperature(), 0.0);
        assertEquals(1, spine("methane").joins().size());
        assertEquals(500, spine("ethane").joins().getFirst().temperature(), 0.0);
        // CO2 (P3): the sub-triple continuation joins its own terms at the triple point with no step at all.
        assertEquals(216.592, spine("carbon_dioxide").joins().getFirst().temperature(), 0.0);
        assertEquals(0.0, spine("carbon_dioxide").joins().getFirst().heatCapacityStepFraction(), 0.0);
        assertEquals(975, spine("carbon_dioxide").joins().get(1).temperature(), 0.0);
    }

    /** dh/dT = Cp and ds/dT = Cp/T by central differences inside every segment. */
    @Test
    void enthalpyAndEntropyIntegrateTheHeatCapacity() {
        for (String species : SPECIES) {
            var spine = spine(species);
            for (var segment : spine.segments()) {
                for (double f : new double[] {0.1, 0.5, 0.9}) {
                    double t = segment.minimumTemperature() + f * (segment.maximumTemperature() - segment.minimumTemperature()), dt = 1e-3 * t;
                    double cp = spine.heatCapacity(t);
                    assertEquals(cp, (spine.enthalpy(t + dt) - spine.enthalpy(t - dt)) / (2 * dt), 1e-6 * cp, species + " dh/dT at " + t);
                    assertEquals(cp / t, (spine.entropy(t + dt, 1e5) - spine.entropy(t - dt, 1e5)) / (2 * dt), 1e-6 * cp / t, species + " ds/dT at " + t);
                }
            }
        }
    }

    @Test
    void evaluationOutsideTheSegmentsContinuesOnTheEndsHeatCapacity() {
        // Past its segments (63.151..6000 K for nitrogen) the spine holds the end's cp and integrates it (owner rule of
        // 2026-10-16): h and s continue with value and slope; only a state that is not positive and finite is refused.
        var nitrogen = spine("nitrogen");
        assertEquals(63.151, nitrogen.minimumTemperature(), 0.0);
        assertEquals(6000, nitrogen.maximumTemperature(), 0.0);
        double cpLow = nitrogen.heatCapacity(63.151);
        assertEquals(cpLow, nitrogen.heatCapacity(50), 0.0);
        assertEquals(nitrogen.enthalpy(63.151) + cpLow * (50 - 63.151), nitrogen.enthalpy(50), 1e-9);
        assertEquals(nitrogen.entropy(63.151, 1e5) + cpLow * Math.log(50 / 63.151), nitrogen.entropy(50, 1e5), 1e-9);
        assertEquals(nitrogen.heatCapacity(6000), nitrogen.heatCapacity(6000.5), 0.0);
        assertThrows(IllegalArgumentException.class, () -> nitrogen.entropy(Double.NaN, 1e5));
        assertThrows(IllegalArgumentException.class, () -> nitrogen.entropy(300, 0));
        assertThrows(IllegalArgumentException.class, () -> nitrogen.heatCapacity(0));
        // Methane's ideal-gas data end at 1200 K (D13): past it the cp is held, a notice and no refusal.
        var methane = spine("methane");
        assertEquals(1200, methane.maximumTemperature(), 0.0);
        assertEquals(methane.heatCapacity(1200), methane.heatCapacity(1200.5), 0.0);
        assertEquals(methane.enthalpy(1200) + 0.5 * methane.heatCapacity(1200), methane.enthalpy(1200.5), 1e-9 * Math.abs(methane.enthalpy(1200)));
    }

    @Test
    void twoLoadsAreBitwiseIdentical() {
        var a = MaterialCatalog.parse(PilotCryogenicTestCatalog.resources());
        var b = MaterialCatalog.parse(PilotCryogenicTestCatalog.resources());
        for (String species : SPECIES) {
            var x = a.requireSpine(PilotCryogenicTestCatalog.PACKAGE_ID, FLUID.get(species));
            var y = b.requireSpine(PilotCryogenicTestCatalog.PACKAGE_ID, FLUID.get(species));
            for (double t = x.minimumTemperature(); t <= x.maximumTemperature(); t += 7.3) {
                assertEquals(Double.doubleToRawLongBits(x.heatCapacity(t)), Double.doubleToRawLongBits(y.heatCapacity(t)));
                assertEquals(Double.doubleToRawLongBits(x.enthalpy(t)), Double.doubleToRawLongBits(y.enthalpy(t)));
                assertEquals(Double.doubleToRawLongBits(x.entropy(t, 3e5)), Double.doubleToRawLongBits(y.entropy(t, 3e5)));
            }
        }
    }

    @Test
    void segmentRulesAreRefused() {
        refused("nitrogen", o -> segment(o, 1).addProperty("temperature_min_kelvin", 599), "ideal_gas.segments[1]: overlaps segment 0");
        refused("nitrogen", o -> segment(o, 1).addProperty("temperature_min_kelvin", 601), "ideal_gas.segments[1]: gap after segment 0");
        refused("methane", o -> o.getAsJsonObject("ideal_gas").addProperty("maximum_cp_step_fraction", 1e-6),
                "at the join at 425.0 K exceeds maximum_cp_step_fraction 1.0E-6");
        // P3 WP9a: a CEA segment carries CEA's gas constant (P2's 8.314472 is refused); the cosh term's rules.
        refused("ethane", o -> segment(o, 1).addProperty("gas_constant_j_per_mol_kelvin", 8.314472), "in a NASA CEA segment (revision cea-3f4441d2-ethane-300-1000)");
        refused("methane", o -> segment(o, 1).getAsJsonArray("terms").get(2).getAsJsonObject().getAsJsonArray("t").set(0, new JsonPrimitive(0)),
                "planck_einstein_cosh: t must be positive");
        refused("methane", o -> segment(o, 1).getAsJsonArray("terms").get(2).getAsJsonObject().getAsJsonArray("n").remove(1),
                "planck_einstein_cosh: n and t differ in length");
        refused("methane", o -> o.getAsJsonObject("groups").getAsJsonObject("counts").addProperty("CH5", 1), "unknown E-PPR78 group CH5");
        refused("methane", o -> o.getAsJsonObject("groups").getAsJsonObject("counts").addProperty("CH4", 1.5), "expected a positive integer");
        refused("methane", o -> o.getAsJsonObject("groups").addProperty("scheme", "ppr78-2008"), "groups.scheme: expected eppr78-2022");
        refused("ethane", o -> segment(o, 0).addProperty("type", "shomate"), "unsupported ideal-gas segment shomate");
        refused("ethane", o -> segment(o, 0).getAsJsonArray("terms").get(0).getAsJsonObject().addProperty("type", "gao_b"), "unsupported Helmholtz term gao_b");
        refused("ethane", o -> segment(o, 1).getAsJsonArray("coefficients").remove(8), "coefficients: expected 9 to 9 numbers");
        refused("ethane", o -> segment(o, 1).addProperty("gas_constant_j_per_mol_kelvin", 0.008314472), "is not a molar gas constant");
        refused("carbon_dioxide", o -> {
            JsonArray segments = o.getAsJsonObject("ideal_gas").getAsJsonArray("segments");
            segments.remove(0);
            segments.remove(0);
        }, "must contain 298.15 K");
        refused("carbon_dioxide", o -> segment(o, 0).getAsJsonObject("coverage").addProperty("grade", "good"), "ideal_gas.segments[0]: coverage.grade: expected");
        refused("carbon_dioxide", o -> o.getAsJsonObject("formation_enthalpy").addProperty("temperature_kelvin", 298), "must be 298.15");
        refused("carbon_dioxide", o -> o.getAsJsonObject("standard_entropy").addProperty("pressure_pascal", 101325), "must be 100000");
        refused("nitrogen", o -> o.getAsJsonObject("ideal_gas").add("segments", new JsonArray()), "expected 1 to 16 segments");
        refused("nitrogen", o -> o.getAsJsonObject("ideal_gas").addProperty("maximum_cp_step_fraction", 1.5), "must lie in (0, 1)");
        refused("nitrogen", o -> segment(o, 0).getAsJsonArray("terms").get(0).getAsJsonObject().addProperty("a", -10), "is not positive");
        refused("nitrogen", o -> segment(o, 0).getAsJsonArray("terms").get(2).getAsJsonObject().getAsJsonArray("v").set(0, new JsonPrimitive(-1)),
                "v must be positive");
        refused("nitrogen", o -> o.getAsJsonObject("groups").add("counts", new JsonObject()), "at least one group required");
        refused("methane", o -> o.getAsJsonObject("formation_enthalpy").addProperty("uncertainty_j_per_mol", -1), "must not be negative");
    }

    /** A two-range NASA 7-coefficient record evaluates the same as the equivalent NASA 9 form (a1 = a2 = 0). */
    @Test
    void nasa7SegmentsMatchTheEquivalentNasa9Form() {
        double[] a = {3.5, 1.2e-4, -3.1e-8, 4.0e-12, -1.5e-16};
        double[] b = {3.3, 5.0e-4, -1.0e-7, 9.0e-12, -3.0e-16};
        String nasa7 = synthetic("nasa7", a, b, false), nasa9 = synthetic("nasa9", a, b, true);
        var x = ReferenceSpine.read(JsonParser.parseString(nasa7).getAsJsonObject());
        var y = ReferenceSpine.read(JsonParser.parseString(nasa9).getAsJsonObject());
        for (double t = 200; t <= 3000; t += 97) {
            assertEquals(y.heatCapacity(t), x.heatCapacity(t), 1e-12 * y.heatCapacity(t));
            assertEquals(y.enthalpy(t), x.enthalpy(t), 1e-9 * Math.abs(y.enthalpy(t)) + 1e-9);
            assertEquals(y.entropy(t, 2e5), x.entropy(t, 2e5), 1e-9 * y.entropy(t, 2e5));
        }
        assertEquals(-1000, x.enthalpy(298.15), 0.0);
        assertEquals(200, x.entropy(298.15, 1e5), 0.0);
    }

    /** A synthetic record (not a real species) with two ranges 200..1000..3000 K; the upper range starts at the lower's Cp. */
    private static String synthetic(String type, double[] low, double[] high, boolean nine) {
        double r = ReferenceSpine.GAS_CONSTANT, join = 1000;
        double cpLow = low[0] + join * (low[1] + join * (low[2] + join * (low[3] + join * low[4])));
        double cpHigh = high[0] + join * (high[1] + join * (high[2] + join * (high[3] + join * high[4])));
        double[] shifted = high.clone();
        shifted[0] += cpLow - cpHigh; // continuous Cp at the join
        return "{\"schema_version\":1,\"id\":\"icheme:spine_synthetic\",\"component\":\"Nitrogen\",\"revision\":\"synthetic-r1\","
                + "\"source\":\"synthetic test record\",\"molar_mass_kg_per_mol\":0.028,"
                + "\"formation_enthalpy\":{\"value_j_per_mol\":-1000,\"uncertainty_j_per_mol\":1,\"temperature_kelvin\":298.15,\"source\":\"synthetic\"},"
                + "\"standard_entropy\":{\"value_j_per_mol_kelvin\":200,\"pressure_pascal\":100000,\"source\":\"synthetic\"},"
                + "\"ideal_gas\":{\"segments\":[" + segmentJson(type, 200, join, r, low, nine) + "," + segmentJson(type, join, 3000, r, shifted, nine) + "]},"
                + "\"groups\":{\"scheme\":\"eppr78-2022\",\"counts\":{\"N2\":1}},\"coverage\":{\"grade\":\"research_only\",\"evidence\":\"synthetic\"}}";
    }

    private static String segmentJson(String type, double min, double max, double r, double[] a, boolean nine) {
        var coefficients = new StringBuilder();
        if (nine) coefficients.append("0,0,");
        for (double v : a) coefficients.append(v).append(',');
        coefficients.append(nine ? "0,0" : "0,0");
        return "{\"type\":\"" + type + "\",\"temperature_min_kelvin\":" + min + ",\"temperature_max_kelvin\":" + max
                + ",\"gas_constant_j_per_mol_kelvin\":" + r + ",\"source\":\"synthetic\",\"revision\":\"synthetic-r1\",\"coefficients\":[" + coefficients + "]}";
    }

    private static JsonObject segment(JsonObject record, int index) {
        return record.getAsJsonObject("ideal_gas").getAsJsonArray("segments").get(index).getAsJsonObject();
    }

    private static void refused(String species, java.util.function.Consumer<JsonObject> edit, String message) {
        PilotCryogenicCatalogTest.refused(path(species), edit, message);
    }
}
