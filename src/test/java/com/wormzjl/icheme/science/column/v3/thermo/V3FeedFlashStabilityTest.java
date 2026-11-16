package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The feed flash classifies a feed by the equilibrium engine's stability test (batch 2026-11-16-supercritical-followups, P3,
 * form F3; P3_COLUMN_FEED_REVIEW.md). Three sets of the review's dense grid (COLUMN_FEED_REVIEW.md), recorded by the P3
 * probe in {@code feed-flash-stability-states.tsv}:
 * <ul>
 *   <li>{@code endpoint}: the 181 real splits the frozen flash answered as one liquid at the Wilson endpoint, now split;</li>
 *   <li>{@code slow}: the 102 one-phase feeds the D1 flash still refused, now answered as one phase;</li>
 *   <li>{@code stalled}: every twentieth of the 721 real splits successive substitution does not converge, now split.</li>
 * </ul>
 * A split's vapour fraction is the probe's to its six printed decimals, its material balance closes, and its enthalpy is
 * the phases' own on the column's roots.
 */
class V3FeedFlashStabilityTest {
    private static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    private static final String CO2 = "test:methane_co2";
    private static final Map<String, Object[]> MIXTURES = Map.of(
            "C1/C2 50/50", new Object[] {NETWORK, Map.of("Methane", 0.5, "Ethane", 0.5)},
            "CO2/C1 90/10", new Object[] {CO2, Map.of("CarbonDioxide", 0.9, "Methane", 0.1)},
            "natural gas", new Object[] {NETWORK, Map.of("Methane", 0.90, "Ethane", 0.06, "Propane", 0.03, "Nitrogen", 0.01)},
            "C1/pc03 90/10", new Object[] {NETWORK, Map.of("Methane", 0.9, "crude_pc03", 0.1)},
            "C1/C2/C3 40/30/30", new Object[] {NETWORK, Map.of("Methane", 0.4, "Ethane", 0.3, "Propane", 0.3)},
            "C1/C3 70/30", new Object[] {NETWORK, Map.of("Methane", 0.7, "Propane", 0.3)});

    private record Row(String set, String mixture, double temperature, double pressure, String engineClass, String answer) {}

    @Test
    void theReviewsStatesAreClassifiedByTheStabilityTest() throws Exception {
        List<Row> rows = rows();
        // Carbon dioxide is only in the refinery package, which the column refuses for its crystal: a test package adds it
        // to the network's methane package, with the same method and pairs and no crystal (as the review's probe did).
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/packages/test_methane_co2.json",
                "{\"schema_version\":1,\"id\":\"" + CO2 + "\",\"revision\":\"p3-test-r1\",\"method\":\"icheme:crude_pr78\","
                        + "\"extends\":\"" + NETWORK + "\",\"components\":[{\"id\":\"CarbonDioxide\",\"properties\":\"icheme:chemsep_carbon_dioxide\"}]}");
        MaterialCatalog catalog = MaterialCatalog.parse(resources);
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, double[]> enthalpyMoves = new LinkedHashMap<>();
        for (String mixture : MIXTURES.keySet().stream().sorted().toList()) {
            String packageId = (String) MIXTURES.get(mixture)[0];
            @SuppressWarnings("unchecked") Map<String, Double> parts = (Map<String, Double>) MIXTURES.get(mixture)[1];
            MaterialRuntime.with(catalog, packageId, () -> {
                V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
                double[] z = new double[thermo.componentBasis().componentCount()];
                for (int i = 0; i < z.length; i++) z[i] = parts.getOrDefault(thermo.componentBasis().componentId(i), 0.0);
                for (Row row : rows) {
                    if (row.mixture().equals(mixture)) check(thermo, z, row, counts, enthalpyMoves);
                }
                return null;
            });
        }
        counts.forEach((key, value) -> System.out.printf(Locale.ROOT, "P3 %s: %d states, %d by the engine's split%n", key, value[0], value[1]));
        enthalpyMoves.forEach((key, range) -> System.out.printf(Locale.ROOT,
                "P3 endpoint %s: H(split) - H(one liquid) from %.2f to %.2f J/mol; vapour fraction %.6f to %.6f%n",
                key, range[0], range[1], range[2], range[3]));
        assertEquals(181, rows.stream().filter(r -> r.set().equals("endpoint")).count());
        assertEquals(102, rows.stream().filter(r -> r.set().equals("slow")).count());
        assertEquals(37, rows.stream().filter(r -> r.set().equals("stalled")).count());
    }

    private static void check(V3PengRobinsonThermo thermo, double[] z, Row row, Map<String, int[]> counts,
                              Map<String, double[]> enthalpyMoves) {
        double t = row.temperature();
        double p = row.pressure() * 1.0e6;
        String at = row.set() + " " + row.mixture() + " at " + t + " K, " + row.pressure() + " MPa";
        V3ThermoWorkspace frozenWorkspace = thermo.newWorkspace();
        System.arraycopy(z, 0, frozenWorkspace.normalizedOverall, 0, z.length);
        V3FlashResult frozen = null;
        if (row.set().equals("endpoint")) {
            frozen = V3FeedFlashEquivalenceTest.V3FeedFlashReference.resolve(thermo, t, p, frozenWorkspace);
            assertEquals(V3FeedPhase.LIQUID, frozen.phase(), at);
            assertEquals("all-liquid Wilson endpoint classification", frozen.detail(), at);
        } else {
            assertThrows(V3ThermoException.class, () -> V3FeedFlashEquivalenceTest.V3FeedFlashReference.resolve(thermo, t, p, frozenWorkspace), at);
        }
        V3FlashResult flash = thermo.flashTP(t, p, z, thermo.newWorkspace());
        int[] count = counts.computeIfAbsent(row.set() + " " + row.mixture(), key -> new int[2]);
        count[0]++;
        if (row.set().equals("slow")) {
            assertTrue(V3FeedFlashOnePhaseTest.isOnePhaseRule(flash), at + ": " + flash.detail());
            assertEquals(row.answer(), flash.phase() == V3FeedPhase.LIQUID ? "liquid" : "vapor", at);
            return;
        }
        // A real split: two phases, never one; the probe's vapour fraction; the balance closes; the enthalpy is the phases'.
        assertEquals(V3FeedPhase.TWO_PHASE, flash.phase(), () -> at + ": " + flash.detail());
        if (flash.detail().equals(V3FeedFlash.ENGINE_SPLIT)) count[1]++;
        double beta = flash.vaporFraction();
        assertEquals(Double.parseDouble(row.answer()), beta, 1.0e-6, at);
        double[] x = flash.liquidComposition();
        double[] y = flash.vaporComposition();
        for (int i = 0; i < z.length; i++) assertEquals(z[i], (1.0 - beta) * x[i] + beta * y[i], 1.0e-12, at);
        double enthalpy = (1.0 - beta) * thermo.molarEnthalpy(t, p, x, Root.LIQUID, thermo.newWorkspace())
                + beta * thermo.molarEnthalpy(t, p, y, Root.VAPOR, thermo.newWorkspace());
        assertEquals(enthalpy, flash.molarEnthalpyJoulesPerMol(), 1.0e-9 * Math.abs(enthalpy), at);
        if (frozen != null) {
            double move = flash.molarEnthalpyJoulesPerMol() - frozen.molarEnthalpyJoulesPerMol();
            double[] range = enthalpyMoves.computeIfAbsent(row.mixture(), key -> new double[] {
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
            range[0] = Math.min(range[0], move);
            range[1] = Math.max(range[1], move);
            range[2] = Math.min(range[2], beta);
            range[3] = Math.max(range[3], beta);
        }
    }

    private static List<Row> rows() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                V3FeedFlashStabilityTest.class.getResourceAsStream("feed-flash-stability-states.tsv")), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                rows.add(new Row(f[0], f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]), f[4], f[5]));
            }
        }
        return rows;
    }
}
