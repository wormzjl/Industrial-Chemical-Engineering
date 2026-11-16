package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * the phases' own on the column's roots. Further cases: an engine split into two liquids whose lighter liquid has a
 * metastable vapour root is not taken as a split; a wet feed unstable only towards a second liquid is one liquid (rule B).
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
        MaterialCatalog catalog = catalogWithCarbonDioxide();
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

    /**
     * Rule D decides an engine split by the engine's own lighter phase, not by a re-evaluation on the column's vapour root.
     * Carbon dioxide with 10 % heavy cut at 220 K, 0.7 MPa: the engine splits it into two liquids, the lighter (82 % of the
     * feed, almost pure carbon dioxide) at reduced density 2.9; that composition also has a metastable vapour root there,
     * reduced density 0.05. Read on that root the split would pass as vapour-liquid, the lighter liquid's enthalpy taken on
     * the vapour root. It is two liquids: at the Wilson endpoint the endpoint's one liquid stands, as the frozen flash.
     */
    @Test
    void anEngineSplitIntoTwoLiquidsIsNotReadOnTheVapourRoot() {
        double t = 220.0;
        double p = 0.7e6;
        MaterialRuntime.with(catalogWithCarbonDioxide(), CO2, () -> {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(CO2);
            var ids = thermo.componentBasis().componentIds();
            double[] z = new double[ids.size()];
            z[ids.indexOf("CarbonDioxide")] = 0.9;
            z[ids.indexOf("crude_pc03")] = 0.1;
            V3ThermoWorkspace engineWorkspace = thermo.newWorkspace();
            System.arraycopy(z, 0, engineWorkspace.normalizedOverall, 0, z.length);
            V3FeedEngine.Split split = thermo.engineSplit(t, p, engineWorkspace);
            assertTrue(split != null && split.lighterShare() > 0.5 && split.lighterShare() < 1.0, String.valueOf(split));
            assertFalse(split.lighterIsVapour());
            // The trap: the lighter liquid's composition read on the forced vapour root looks like a vapour.
            double[] lighter = engineWorkspace.vaporComposition.clone();
            thermo.evaluateInto(t, p, lighter, Root.VAPOR, engineWorkspace);
            assertTrue(thermo.phaseTraits(Root.VAPOR, t, p, engineWorkspace).reducedDensity() < 1.0);

            V3ThermoWorkspace frozenWorkspace = thermo.newWorkspace();
            System.arraycopy(z, 0, frozenWorkspace.normalizedOverall, 0, z.length);
            V3FlashResult frozen = V3FeedFlashEquivalenceTest.V3FeedFlashReference.resolve(thermo, t, p, frozenWorkspace);
            V3FlashResult flash = thermo.flashTP(t, p, z, thermo.newWorkspace());
            assertEquals(V3FeedPhase.LIQUID, flash.phase(), flash.detail());
            assertEquals("all-liquid Wilson endpoint classification", flash.detail());
            assertEquals(frozen.phase(), flash.phase());
            assertEquals(frozen.molarEnthalpyJoulesPerMol(), flash.molarEnthalpyJoulesPerMol(), 0.0);
            return null;
        });
    }

    /**
     * Rule B (batch 2026-11-16-supercritical-followups, LANE_FIXES_REVIEW.md): the kind of an instability is read from the
     * stability test before any restart. The ethanol, water and n-hexane column's distillate, hexane 0.604, ethanol 0.300,
     * water 0.095 at 320 K and 1 atm, is unstable towards a hexane-rich liquid (the trial at reduced density 3.2, the feed at
     * 3.2): it would settle into two liquids, no vapour (the ternary heteroazeotrope boils near 329 K). It is one liquid, the
     * frozen flash's answer to the bit; P3's restart from that liquid trial took 10 % "vapour" with 39 % water, a stationary
     * point of the one-liquid model where water's vapour pressure is 10.6 kPa. The column's feed, ethanol 40, water 50,
     * hexane 10 at 345 K, unstable with no vapour-liquid split found, keeps the Wilson endpoint's one liquid (rule c), not
     * a refusal. The W5 wash, ethanol 10, water 90 at 359.84 K, is unstable towards a vapour (the trial's ethanol 0.43) and
     * keeps its small split.
     */
    @Test
    void aFeedUnstableOnlyTowardsASecondLiquidIsOneLiquid() {
        String refinery = "icheme:refinery";
        // The column's catalog on a wash island with hexane (FluidThermodynamics.on): the network's package with its cubic
        // water, restricted to the island's compounds and water.
        MaterialCatalog bundled = MaterialCatalog.bundled().withWater(refinery);
        List<String> island = new ArrayList<>(bundled.requirePackage(refinery).components().stream()
                .filter(id -> id.equals("N-hexane") || id.equals("Ethanol")).toList());
        island.add("Water");
        MaterialRuntime.with(bundled.restrict(refinery, island), refinery, () -> {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(refinery);
            var ids = thermo.componentBasis().componentIds();
            double[] distillate = new double[ids.size()];
            distillate[ids.indexOf("N-hexane")] = 0.604308531919573;
            distillate[ids.indexOf("Ethanol")] = 0.30049540454608265;
            distillate[ids.indexOf("Water")] = 0.09519606353434426;
            V3ThermoWorkspace test = thermo.newWorkspace();
            System.arraycopy(distillate, 0, test.normalizedOverall, 0, distillate.length);
            var stability = thermo.feedStability(320.0, 101_325.0, test);
            assertEquals(com.wormzjl.icheme.science.thermo.TangentPlaneStability.Verdict.UNSTABLE, stability.verdict());
            thermo.evaluateInto(320.0, 101_325.0, stability.trialComposition(), stability.trialRoot(), test);
            var trial = thermo.phaseTraits(stability.trialRoot(), 320.0, 101_325.0, test);
            assertTrue(trial.reducedDensity() > 1.0 && !trial.supercriticalIsotherm(), "a liquid trial");
            assertSameAsFrozen(thermo, 320.0, distillate, "all-liquid Wilson endpoint classification");

            double[] feed = new double[ids.size()];
            feed[ids.indexOf("N-hexane")] = 0.1;
            feed[ids.indexOf("Ethanol")] = 0.4;
            feed[ids.indexOf("Water")] = 0.5;
            assertSameAsFrozen(thermo, 345.0, feed, "all-liquid Wilson endpoint classification");

            double[] wash = new double[ids.size()];
            wash[ids.indexOf("Ethanol")] = 0.1;
            wash[ids.indexOf("Water")] = 0.9;
            V3FlashResult split = thermo.flashTP(359.84, 101_325.0, wash, thermo.newWorkspace());
            assertEquals(V3FeedPhase.TWO_PHASE, split.phase(), split.detail());
            assertEquals(7.6675e-5, split.vaporFraction(), 1.0e-9);
            assertTrue(split.vaporComposition()[ids.indexOf("Ethanol")] > 0.4, "the vapour is ethanol-rich");
            return null;
        });
    }

    private static void assertSameAsFrozen(V3PengRobinsonThermo thermo, double t, double[] z, String detail) {
        V3ThermoWorkspace frozenWorkspace = thermo.newWorkspace();
        System.arraycopy(z, 0, frozenWorkspace.normalizedOverall, 0, z.length);
        V3FlashResult frozen = V3FeedFlashEquivalenceTest.V3FeedFlashReference.resolve(thermo, t, 101_325.0, frozenWorkspace);
        V3FlashResult flash = thermo.flashTP(t, 101_325.0, z, thermo.newWorkspace());
        System.out.printf(Locale.ROOT, "LANE_FIXES one liquid at %.2f K: %s, H %.6f J/mol%n", t, flash.detail(), flash.molarEnthalpyJoulesPerMol());
        assertEquals(V3FeedPhase.LIQUID, flash.phase(), flash.detail());
        assertEquals(detail, flash.detail());
        assertEquals(frozen.detail(), flash.detail());
        assertEquals(frozen.molarEnthalpyJoulesPerMol(), flash.molarEnthalpyJoulesPerMol(), 0.0);
    }

    /**
     * Carbon dioxide is only in the refinery package, which the column refuses for its crystal: a test package adds it to
     * the network's methane package, with the same method and pairs and no crystal (as the review's probe did).
     */
    private static MaterialCatalog catalogWithCarbonDioxide() {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/packages/test_methane_co2.json",
                "{\"schema_version\":1,\"id\":\"" + CO2 + "\",\"revision\":\"p3-test-r1\",\"method\":\"icheme:crude_pr78\","
                        + "\"extends\":\"" + NETWORK + "\",\"components\":[{\"id\":\"CarbonDioxide\",\"properties\":\"icheme:chemsep_carbon_dioxide\"}]}");
        return MaterialCatalog.parse(resources);
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
