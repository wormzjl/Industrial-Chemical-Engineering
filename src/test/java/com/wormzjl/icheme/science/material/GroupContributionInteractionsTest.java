package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * P3 WP2 (batch 2026-09-24-coolprop-low-temperature, plan {@code P3_PILOT_ENGINE_PLAN.md} sections 4.1 and 4.3): the
 * {@code group_interactions} record kind, the E-PPR78 {@code rule} of an interactions record, the per-pair terms it
 * resolves from the spines' group counts, the loader's refusals, and the physics the package compares.
 */
class GroupContributionInteractionsTest {
    private static final String MATRIX = "data/icheme/materials/group_interactions/eppr78_2022.json";
    private static final String INTERACTIONS = "data/icheme/materials/interactions/pilot_cryogenic.json";
    private static final String ETHANE_SPINE = "data/icheme/materials/spine/ethane.json";
    private static final double R = 8.31446261815324;

    /** Appendix A of the P3 design: the six pilot pairs' kij at 100, 150, 200, 250, 300, 400, 600 and 900 K. */
    private static final double[] APPENDIX_TEMPERATURES = {100, 150, 200, 250, 300, 400, 600, 900};
    private static final Map<String, double[]> APPENDIX_A = Map.of(
            "Methane/Ethane", new double[] {0.0072, 0.0063, 0.0059, 0.0057, 0.0057, 0.0060, 0.0075, 0.0121},
            "Methane/CarbonDioxide", new double[] {0.1213, 0.1074, 0.1040, 0.1057, 0.1104, 0.1259, 0.1747, 0.3010},
            "Nitrogen/Methane", new double[] {0.0337, 0.0315, 0.0295, 0.0276, 0.0256, 0.0205, 0.0030, -0.0729},
            "Ethane/CarbonDioxide", new double[] {0.1734, 0.1453, 0.1331, 0.1279, 0.1267, 0.1308, 0.1543, 0.2184},
            "Nitrogen/Ethane", new double[] {0.0610, 0.0492, 0.0405, 0.0332, 0.0262, 0.0122, -0.0244, -0.1472},
            "Nitrogen/CarbonDioxide", new double[] {0.0955, 0.0371, 0.0050, -0.0158, -0.0306, -0.0505, -0.0712, -0.0675});
    /**
     * A_kl and B_kl (MPa) of the six pilot pairs, Table S4 of Jaubert et al. 2022: the same values as the Clapeyron.jl
     * transcription the design's appendix A quoted (WP2b found all six identical).
     */
    private static final Map<String, double[]> PILOT_AB = Map.of(
            "Methane/Ethane", new double[] {9.951, 13.73},
            "Methane/CarbonDioxide", new double[] {136.6, 214.8},
            "Nitrogen/Methane", new double[] {30.88, 37.06},
            "Ethane/CarbonDioxide", new double[] {136.2, 235.7},
            "Nitrogen/Ethane", new double[] {61.59, 84.92},
            "Nitrogen/CarbonDioxide", new double[] {113.9, 212.4});

    /** The bundled matrix record, read as the loader reads it. */
    private static GroupInteractionMatrix bundledMatrix() {
        return GroupInteractionMatrix.read(JsonParser.parseString(MaterialCatalog.bundled().resources().get(MATRIX)).getAsJsonObject());
    }

    private static MaterialCatalog.Package pilot(MaterialCatalog catalog) { return catalog.requirePackage(PilotCryogenicTestCatalog.PACKAGE_ID); }

    private static String name(MaterialCatalog.Package p, GroupContributionInteractions.Pair pair) {
        return p.components().get(pair.first()) + "/" + p.components().get(pair.second());
    }

    /**
     * The bundled matrix is Table S4 of the supporting information of Jaubert et al. 2022 (P3 WP2b): 356 numeric
     * off-diagonal cells, 424 NA. The pairs below are ones the earlier Clapeyron.jl transcription (revision r1) had
     * wrong: A = 0 with the printed B (CH3/CH_cyclic), a shifted row (CH_cyclic/CO2 carried CH_cyclic/N2's B, C/CF2
     * sat under C/CF_double_bond), a lost decimal point (CH3/C2H2F4).
     */
    @Test
    void bundledMatrixCarriesTheFortyGroupsOfTheSchemeAndTheTableS4Pairs() {
        var matrix = bundledMatrix();
        assertEquals("eppr78-2022-si-table-s4-r2", matrix.revision());
        assertEquals(Eppr78Groups.SCHEME, matrix.scheme());
        assertEquals(40, matrix.groups().size());
        for (int i = 0; i < 40; i++) assertEquals(Eppr78Groups.groups().get(i).name().orElseThrow(), matrix.groups().get(i));
        assertEquals(356, matrix.pairCount(), "the numeric off-diagonal cells of Table S4");
        assertEquals(new GroupInteractionMatrix.Parameters(30.88, 37.06), matrix.parameters("N2", "CH4").orElseThrow());
        assertEquals(new GroupInteractionMatrix.Parameters(30.88, 37.06), matrix.parameters("CH4", "N2").orElseThrow());
        assertEquals(new GroupInteractionMatrix.Parameters(-497.6, -824.2), matrix.parameters("CH_alkynic", "C_alkynic").orElseThrow());
        assertTrue(matrix.parameters("CH4", "C2F6").isEmpty(), "Table S4 prints NA for 424 of the 780 group pairs");
        assertEquals(new GroupInteractionMatrix.Parameters(293.4, 170.9), matrix.parameters("CH3", "CH_cyclic").orElseThrow());
        assertEquals(new GroupInteractionMatrix.Parameters(216.2, -132.8), matrix.parameters("CH_cyclic", "CO2").orElseThrow());
        assertEquals(new GroupInteractionMatrix.Parameters(331.5, 389.8), matrix.parameters("CH_cyclic", "N2").orElseThrow());
        assertEquals(new GroupInteractionMatrix.Parameters(479.0, 1430), matrix.parameters("C", "CF2").orElseThrow());
        assertTrue(matrix.parameters("C", "CF_double_bond").isEmpty(), "NA in Table S4");
        assertEquals(new GroupInteractionMatrix.Parameters(158.5, 356.5), matrix.parameters("CH3", "C2H2F4").orElseThrow());
        // No pair has A = 0 with B != 0 any more; one pair, CF3/CF2, is printed A = B = 0 (a zero term the rule drops).
        int zeroA = 0;
        for (int k = 0; k < 40; k++) for (int l = k + 1; l < 40; l++) {
            var found = matrix.parameters(matrix.groups().get(k), matrix.groups().get(l));
            if (found.isPresent() && found.get().aMegapascal() == 0.0) {
                zeroA++;
                assertEquals(0.0, found.get().bMegapascal(), 0.0, matrix.groups().get(k) + " / " + matrix.groups().get(l));
            }
        }
        assertEquals(1, zeroA);
        assertEquals(new GroupInteractionMatrix.Parameters(0.0, 0.0), matrix.parameters("CF3", "CF2").orElseThrow());
        // No bundled column or network package has a rule: their constants are what they were.
        for (var p : MaterialCatalog.bundled().packages().values()) {
            if (!p.id().equals(PilotCryogenicTestCatalog.PACKAGE_ID)) assertNull(p.groupContributions(), p.id());
        }
    }

    @Test
    void pilotPairsResolveFromTheSpineGroupCounts() {
        var p = pilot(PilotCryogenicTestCatalog.catalog());
        var rule = p.groupContributions();
        assertEquals("eppr78", rule.rule());
        assertEquals("eppr78-2022", rule.scheme());
        assertEquals("icheme:eppr78_2022", rule.matrixId());
        assertEquals(6, rule.pairs().size());
        for (var pair : rule.pairs()) {
            assertTrue(pair.first() < pair.second());
            assertEquals(1, pair.terms().size(), "single-group molecules have one term");
            var term = pair.terms().getFirst();
            assertEquals(1.0, term.weight(), 0.0);
            double[] ab = PILOT_AB.get(name(p, pair));
            assertEquals(ab[0], term.aMegapascal(), 0.0, name(p, pair));
            assertEquals(ab[1], term.bMegapascal(), 0.0, name(p, pair));
            assertEquals(0.0, p.interactions().get(pair.first()).get(pair.second()), 0.0, "the constant entry of a rule pair is 0");
            assertTrue(rule.pair(pair.second(), pair.first()).orElseThrow() == pair);
            for (double t = 60; t <= 1500; t += 7.3) {
                // Independent direct evaluation of E = A (298.15/T)^(B/A - 1), A in Pa.
                double direct = ab[0] * 1e6 * Math.pow(298.15 / t, ab[1] / ab[0] - 1);
                assertEquals(direct, pair.energy(t), 1e-12 * Math.abs(direct), name(p, pair) + " at " + t);
                double h1 = 1e-4 * t, h2 = 1e-3 * t;
                double d1 = (pair.energy(t + h1) - pair.energy(t - h1)) / (2 * h1);
                double d2 = (pair.energy(t + h2) - 2 * pair.energy(t) + pair.energy(t - h2)) / (h2 * h2);
                assertEquals(d1, pair.energyDerivative(t), 1e-7 * Math.abs(d1), name(p, pair) + " dE/dT at " + t);
                assertEquals(d2, pair.energySecondDerivative(t), 1e-5 * Math.abs(d2), name(p, pair) + " d2E/dT2 at " + t);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> rule.pairs().getFirst().energy(0));
        assertThrows(IllegalArgumentException.class, () -> rule.pairs().getFirst().energy(Double.NaN));
    }

    /**
     * Appendix A of the design, from the resolved terms and the kernel's PR78 constants (0.45724, 0.07780, R CODATA
     * 2018). The appendix was computed with CoolProp's ethane constants (305.322 K, 4.8722 MPa); with them every cell
     * rounds to the printed four decimals. With the pilot record's own ethane constants (305.32 K, 4.872 MPa, the
     * bundled record's) two ethane/nitrogen cells land one unit of the fourth decimal away (0.04056 and 0.02625).
     */
    @Test
    void reproducesAppendixA() {
        var p = pilot(PilotCryogenicTestCatalog.catalog());
        var rule = p.groupContributions();
        for (boolean appendixConstants : new boolean[] {true, false}) {
            int exact = 0;
            for (var pair : rule.pairs()) {
                double[] expected = APPENDIX_A.get(name(p, pair));
                StringBuilder row = new StringBuilder();
                for (int k = 0; k < APPENDIX_TEMPERATURES.length; k++) {
                    double t = APPENDIX_TEMPERATURES[k];
                    double[] i = attraction(p, pair.first(), t, appendixConstants), j = attraction(p, pair.second(), t, appendixConstants);
                    double kij = kij(pair.energy(t), i[0], i[1], j[0], j[1]);
                    row.append(String.format(" %.4f", kij));
                    if (Math.abs(kij - expected[k]) <= 5e-5 + 1e-12) exact++;
                    assertEquals(expected[k], kij, appendixConstants ? 5e-5 + 1e-12 : 1e-4, name(p, pair) + " at " + t + " K");
                }
                System.out.printf("P3 appendix A %s (%s constants):%s%n", name(p, pair), appendixConstants ? "appendix" : "pilot record", row);
            }
            System.out.printf("P3 appendix A: %d of 48 cells round to the printed value with the %s constants%n", exact,
                    appendixConstants ? "appendix" : "pilot record");
            assertEquals(appendixConstants ? 48 : 46, exact);
        }
    }

    /**
     * k_ij from E_ij and the pure-component attraction and co-volume, the formula's second line (plan section 4.1; the
     * kernel evaluates the equivalent cross term). The reference for appendix A; only this test reads it.
     */
    private static double kij(double energyPascal, double ai, double bi, double aj, double bj) {
        double difference = Math.sqrt(ai) / bi - Math.sqrt(aj) / bj;
        return (energyPascal - difference * difference) / (2.0 * Math.sqrt(ai * aj) / (bi * bj));
    }

    /** Soave PR78 a(T) and b of a package component, with the ethane constants of the appendix when asked. */
    private static double[] attraction(MaterialCatalog.Package p, int component, double t, boolean appendixConstants) {
        var pr = p.properties().get(component).pr();
        double tc = pr.criticalTemperature(), pc = pr.criticalPressure(), w = pr.acentricFactor();
        if (appendixConstants && p.components().get(component).equals("Ethane")) { tc = 305.322; pc = 4872200; w = 0.0990; }
        double m = 0.37464 + 1.54226 * w - 0.26992 * w * w, alpha = 1 + m * (1 - Math.sqrt(t / tc));
        return new double[] {0.45724 * R * R * tc * tc / pc * alpha * alpha, 0.07780 * R * tc / pc};
    }

    /** A molecule of several groups: the terms are the double sum of section 4.1 over unordered group pairs. */
    @Test
    void severalGroupsGiveTheDoubleSumWeights() {
        // Clapeyron.jl's ethane decomposition (2 CH3, its EPPR78_groups.csv) and a synthetic {CH3: 1, CH2: 1} "ethane"
        // against methane.
        var twoCh3 = PilotCryogenicTestCatalog.parseWith(ETHANE_SPINE, o -> counts(o, Map.of("CH3", 2)));
        var p = pilot(twoCh3);
        int methane = p.components().indexOf("Methane"), ethane = p.components().indexOf("Ethane");
        var single = p.groupContributions().pair(methane, ethane).orElseThrow();
        assertEquals(List.of(new GroupContributionInteractions.Term("CH3", "CH4", 28.48, 20.25, 1.0)), single.terms());

        var mixed = pilot(PilotCryogenicTestCatalog.parseWith(ETHANE_SPINE, o -> counts(o, Map.of("CH3", 1, "CH2", 1))));
        var pair = mixed.groupContributions().pair(methane, ethane).orElseThrow();
        var matrix = bundledMatrix();
        // alpha(methane) = {CH4: 1}, alpha(ethane) = {CH3: 1/2, CH2: 1/2}; weights -(d_k d_l).
        assertEquals(List.of(new GroupContributionInteractions.Term("CH3", "CH2", 65.54, 105.7, -0.25),
                new GroupContributionInteractions.Term("CH3", "CH4", 28.48, 20.25, 0.5),
                new GroupContributionInteractions.Term("CH2", "CH4", matrix.parameters("CH2", "CH4").orElseThrow().aMegapascal(),
                        matrix.parameters("CH2", "CH4").orElseThrow().bMegapascal(), 0.5)), pair.terms());
        // Independent evaluation of -1/2 sum_k sum_l (a_ik - a_jk)(a_il - a_jl) A_kl (298.15/T)^(B_kl/A_kl - 1).
        String[] groups = {"CH3", "CH2", "CH4"};
        double[] d = {-0.5, -0.5, 1.0};
        for (double t : new double[] {90, 150, 298.15, 500, 1100}) {
            double sum = 0;
            for (int k = 0; k < 3; k++) for (int l = 0; l < 3; l++) {
                if (k == l) continue;
                var ab = matrix.parameters(groups[k], groups[l]).orElseThrow();
                sum += d[k] * d[l] * ab.aMegapascal() * 1e6 * Math.pow(298.15 / t, ab.bMegapascal() / ab.aMegapascal() - 1);
            }
            assertEquals(-0.5 * sum, pair.energy(t), 1e-12 * Math.abs(sum), "at " + t);
        }
    }

    @Test
    void anExplicitConstantKeepsItsPairOutOfTheRule() {
        var catalog = PilotCryogenicTestCatalog.parseWith(INTERACTIONS, o -> {
            var pair = new JsonObject();
            pair.addProperty("first", "Methane"); pair.addProperty("second", "Nitrogen"); pair.addProperty("kij", 0.03);
            o.getAsJsonArray("pairs").add(pair);
        });
        var p = pilot(catalog);
        int n = p.components().indexOf("Nitrogen"), m = p.components().indexOf("Methane");
        assertEquals(5, p.groupContributions().pairs().size());
        assertTrue(p.groupContributions().pair(n, m).isEmpty());
        assertEquals(0.03, p.interactions().get(n).get(m), 0.0);
    }

    @Test
    void loaderRefusals() {
        // An unresolved group pair: the matrix does not carry CH4/N2.
        refused(MATRIX, o -> removePair(o, "CH4", "N2"), "unresolved group pair CH4 / N2 for Nitrogen / Methane");
        // Unknown groups in the record itself.
        refused(MATRIX, o -> firstPair(o).addProperty("first", "CH5"), "pairs[0].first: unknown group CH5");
        refused(MATRIX, o -> o.getAsJsonArray("groups").get(4).getAsJsonObject().addProperty("name", "Methane"), "scheme eppr78-2022 names group 5 CH4");
        // Scheme mismatch between the spines (eppr78-2022) and the matrix.
        refused(MATRIX, o -> o.addProperty("scheme", "eppr78-2017"), "scheme mismatch for Nitrogen");
        refused(MATRIX, o -> o.addProperty("units", "Pa"), "units: expected MPa");
        refused(MATRIX, o -> o.getAsJsonArray("pairs").add(firstPair(o).deepCopy()), "duplicate pair CH3 / CH2");
        refused(MATRIX, o -> firstPair(o).addProperty("second", "CH3"), "self pair CH3");
        // A term the formula does not define (Table S4 has no such pair; the guard stays for other records).
        refused(MATRIX, o -> pair(o, "CH4", "N2").addProperty("a_mpa", 0), "undefined term CH4 / N2 for Nitrogen / Methane");
        // The rule itself.
        refused(INTERACTIONS, o -> o.getAsJsonObject("rule").addProperty("type", "ppr78"), "rule.type: expected eppr78");
        refused(INTERACTIONS, o -> o.getAsJsonObject("rule").addProperty("group_interactions", "icheme:eppr78_2017"),
                "rule.group_interactions: unresolved icheme:eppr78_2017");
        // A rule in the pair library of the crude method, which takes the datasets' fits and no spines.
        refused("data/icheme/materials/interactions/tjl20.json", o -> {
            var rule = new JsonObject();
            rule.addProperty("type", "eppr78"); rule.addProperty("group_interactions", "icheme:eppr78_2022");
            o.add("rule", rule);
        }, "this method takes no spines");
        // missing_interactions: error still refuses a pair neither listed nor resolvable (no rule).
        refused(INTERACTIONS, o -> o.remove("rule"), "Missing interaction: Nitrogen / Methane");
    }

    @Test
    void physicsFollowsTheValuesAPackageUses() {
        var base = PilotCryogenicTestCatalog.catalog();
        var p = pilot(base);
        String physics = base.physicsContent(p.id(), p.components());
        var nitrogenMethane = List.of("Nitrogen", "Methane");
        String subAxis = base.physicsContent(p.id(), nitrogenMethane);
        // A and B of a pair the pilot uses: the physics on both axes moves.
        for (String field : new String[] {"a_mpa", "b_mpa"}) {
            var edited = PilotCryogenicTestCatalog.parseWith(MATRIX, o -> pair(o, "CH4", "N2").addProperty(field, field.equals("a_mpa") ? 30.89 : 37.07));
            assertNotEquals(physics, edited.physicsContent(p.id(), p.components()), field);
            assertNotEquals(subAxis, edited.physicsContent(p.id(), nitrogenMethane), field);
            assertBundledPackagesHold(edited);
        }
        // A pair outside the sub-axis moves the full axis, not the sub-axis.
        var co2 = PilotCryogenicTestCatalog.parseWith(MATRIX, o -> pair(o, "CO2", "N2").addProperty("a_mpa", 114.0));
        assertNotEquals(physics, co2.physicsContent(p.id(), p.components()));
        assertEquals(subAxis, co2.physicsContent(p.id(), nitrogenMethane));
        // A pair no package uses, or a text: nothing moves.
        var unused = PilotCryogenicTestCatalog.parseWith(MATRIX, o -> { firstPair(o).addProperty("a_mpa", 65.55); o.addProperty("source", "edited text"); });
        assertEquals(physics, unused.physicsContent(p.id(), p.components()));
        assertBundledPackagesHold(unused);
        // A lens carries constant pairs only: a model trained on a constant-pair package's values (the network package's
        // nitrogen and methane) reads no request whose view resolves a pair by the rule, so no lens is built.
        var network = base.requirePackage("icheme:tjl20_methane_nitrogen");
        var compounds = nitrogenMethane.stream().map(id -> {
            var r = network.properties().get(network.components().indexOf(id));
            return new TrainedPhysics.Compound(id, r.pr().criticalTemperature(), r.pr().criticalPressure(), r.pr().acentricFactor(), r.cp());
        }).toList();
        var constant = new TrainedPhysics("pr78", 298.15, 700, compounds, List.of(), TrainedPhysics.Water.of(p.water()));
        assertTrue(base.inferenceView(p.id(), nitrogenMethane, constant).isEmpty());
    }

    /** Every bundled package but the pilot is untouched by a group-matrix edit: its record and its physics. */
    private static void assertBundledPackagesHold(MaterialCatalog catalog) {
        var bundled = MaterialCatalog.bundled();
        for (var p : bundled.packages().values()) {
            if (p.id().equals(PilotCryogenicTestCatalog.PACKAGE_ID)) continue;
            assertEquals(p, catalog.requirePackage(p.id()), p.id());
            assertEquals(bundled.physicsContent(p.id(), p.components()), catalog.physicsContent(p.id(), p.components()), p.id());
        }
    }

    private static void counts(JsonObject spine, Map<String, Integer> counts) {
        var object = new JsonObject();
        counts.forEach(object::addProperty);
        spine.getAsJsonObject("groups").add("counts", object);
    }

    private static JsonObject firstPair(JsonObject matrix) { return matrix.getAsJsonArray("pairs").get(0).getAsJsonObject(); }

    private static JsonObject pair(JsonObject matrix, String a, String b) {
        for (var element : matrix.getAsJsonArray("pairs")) {
            var pair = element.getAsJsonObject();
            String first = pair.get("first").getAsString(), second = pair.get("second").getAsString();
            if (first.equals(a) && second.equals(b) || first.equals(b) && second.equals(a)) return pair;
        }
        throw new AssertionError("no pair " + a + "/" + b);
    }

    private static void removePair(JsonObject matrix, String a, String b) {
        JsonArray pairs = matrix.getAsJsonArray("pairs");
        pairs.remove(pair(matrix, a, b));
    }

    private static void refused(String path, Consumer<JsonObject> edit, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> PilotCryogenicTestCatalog.parseWith(path, edit));
        assertTrue(error.getMessage().contains(message), () -> "expected '" + message + "' in: " + error.getMessage());
    }
}
