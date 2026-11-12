package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The derived pairs of batch 2026-11-11-aqueous-solvents (W2b, plan M4) on the pilot list {@code icheme:pilot_aqueous}
 * ({@link PilotAqueousTestCatalog}), through the kernel the derivation builds, against section 2 of the plan: ethanol and
 * water at 1 atm against Perry's 15 points, ethanol and n-hexane, water and n-hexane as two liquids, gases in water from
 * their Henry constants and in ethanol by the homomorph rule; and the bookkeeping: one source per pair, hydrogen pairs zero,
 * authored library pairs first, the identity by declared revisions, fits kept for the catalog's life. The bubble-point and
 * two-liquid solvers are those of {@code tools/aqueous-solvents/group-derived-pairs.mjs}. Bounds are loose on purpose: the
 * reference used a slightly different water alpha and ethanol's constants of another source.
 */
class DerivedPairsTest {
    private static final String PACKAGE = PilotAqueousTestCatalog.PACKAGE_ID;
    // W6: ethanol joins the main data; the pilot package names the main record icheme:chemsep_ethanol (was icheme:pilot_ethanol).
    private static final String ETHANOL_RECORD = "data/icheme/materials/properties/chemsep_ethanol.json";
    private static final double ATM = 101325.0;
    private static final double ROOM = 298.15;
    /** Perry's table at 101.325 kPa: x ethanol, y ethanol, T in C. */
    private static final double[][] PERRY = {{0.0190, 0.1700, 95.5}, {0.0721, 0.3891, 89.0}, {0.0966, 0.4375, 86.7}, {0.1238, 0.4704, 85.3},
            {0.1661, 0.5089, 84.1}, {0.2337, 0.5445, 82.7}, {0.2608, 0.5580, 82.3}, {0.3273, 0.5826, 81.5}, {0.3965, 0.6122, 80.7},
            {0.5079, 0.6564, 79.8}, {0.5198, 0.6599, 79.7}, {0.5732, 0.6841, 79.3}, {0.6763, 0.7385, 78.74}, {0.7472, 0.7815, 78.41},
            {0.8943, 0.8943, 78.15}};

    private static PengRobinsonKernel kernel(String... components) {
        return DerivedPairs.derive(PilotAqueousTestCatalog.catalog(), PACKAGE, List.of(components)).kernel(1.0, 3000.0, 1.0, 1.0e9);
    }

    @Test void ethanolAndWaterAtOneAtmosphere() {
        var kernel = kernel("Ethanol", "Water");
        double temperature = 0.0, vapour = 0.0;
        for (double[] point : PERRY) {
            var bubble = bubble(kernel, point[0], ATM);
            temperature += Math.abs(bubble.t - 273.15 - point[2]);
            vapour += Math.abs(bubble.y - point[1]);
        }
        temperature /= PERRY.length;
        vapour /= PERRY.length;
        double azeotrope = azeotrope(kernel, ATM, 0.5, 0.999);
        System.out.printf(Locale.ROOT, "ethanol and water at 1 atm: AAD T %.3f K, AAD y %.4f, azeotrope %.3f%n", temperature, vapour, azeotrope);
        assertTrue(temperature <= 0.25, "AAD T " + temperature);
        assertTrue(vapour <= 0.008, "AAD y " + vapour);
        assertTrue(azeotrope >= 0.88 && azeotrope <= 0.93, "azeotrope " + azeotrope);
    }

    @Test void ethanolAndHexaneAreOneLiquidWithAnAzeotrope() {
        var kernel = kernel("Ethanol", "N-hexane");
        double drop = 0.0, last = Double.NEGATIVE_INFINITY;
        for (int k = 1; k < 200; k++) {
            double x = k * 0.005;
            double value = Math.log(x) + logPhi(kernel, ROOM, ATM, new double[] {x, 1.0 - x}, PengRobinsonKernel.Root.LIQUID)[0];
            if (value < last) drop += last - value;
            last = Math.max(last, value);
        }
        double azeotrope = azeotrope(kernel, ATM, 0.05, 0.999);
        System.out.printf(Locale.ROOT, "ethanol and n-hexane: fall of ln a at 25 C %.2e; azeotrope at 1 atm %.3f%n", drop, azeotrope);
        assertEquals(0.0, drop, "one liquid at 298.15 K");
        assertTrue(azeotrope >= 0.28 && azeotrope <= 0.36, "azeotrope " + azeotrope);
    }

    @Test void waterAndHexaneAreTwoLiquids() {
        var kernel = kernel("Water", "N-hexane");
        double[][] split = twoLiquids(kernel, new double[] {0.5, 0.5}, ROOM, ATM, new double[] {1 - 1e-6, 1e-6}, new double[] {1e-3, 1 - 1e-3});
        assertNotNull(split, "two liquids at 298.15 K");
        double hexaneInWater = split[0][1], waterInHexane = split[1][0];
        System.out.printf(Locale.ROOT, "water and n-hexane at 25 C: hexane in water %.2e, water in hexane %.2e%n", hexaneInWater, waterInHexane);
        assertTrue(hexaneInWater >= 1e-6 && hexaneInWater <= 5e-6, "hexane in water " + hexaneInWater);
        assertTrue(waterInHexane >= 4e-4 && waterInHexane <= 2e-3, "water in hexane " + waterInHexane);
    }

    @Test void gasesInWaterFollowTheirHenryConstants() {
        var catalog = PilotAqueousTestCatalog.catalog();
        var water = catalog.requirePackage(PACKAGE).water();
        for (String gas : List.of("CarbonDioxide", "Nitrogen")) {
            var kernel = kernel(gas, "Water");
            double henry = catalog.henryInWater(PACKAGE, gas).orElseThrow().pascal(water, ROOM);
            double pressure = ATM + com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.wagnerPruss(water, ROOM);
            double dissolved = dissolved(kernel, ROOM, pressure);
            double expected = ATM / henry;
            System.out.printf(Locale.ROOT, "%s in water at 25 C, 101.325 kPa of gas: %.3e (1/kH %.3e, ratio %.4f)%n", gas, dissolved, expected, dissolved / expected);
            assertEquals(1.0, dissolved / expected, 0.05, gas);
        }
    }

    @Test void gasesInEthanolFollowTheHomomorphRule() {
        Map<String, Double> reference = Map.of("Nitrogen", 3.1e-4, "Methane", 1.2e-3, "CarbonDioxide", 5.5e-3);
        for (var entry : reference.entrySet()) {
            var kernel = kernel(entry.getKey(), "Ethanol");
            double dissolved = Math.exp(-logPhi(kernel, ROOM, ATM, new double[] {0.0, 1.0}, PengRobinsonKernel.Root.LIQUID)[0]);
            double ratio = dissolved / entry.getValue();
            System.out.printf(Locale.ROOT, "%s in ethanol at 25 C: %.3e (reference %.1e, ratio %.3f)%n", entry.getKey(), dissolved, entry.getValue(), ratio);
            assertTrue(ratio >= 1 / 1.3 && ratio <= 1.3, entry.getKey() + " ratio " + ratio);
        }
    }

    @Test void everyPairHasOneSourceInOrder() {
        var catalog = PilotAqueousTestCatalog.catalog();
        var result = DerivedPairs.derive(catalog, PACKAGE);
        var ids = result.components();
        int n = ids.size();
        assertEquals(n * (n - 1) / 2, result.pairs().size());
        assertTrue(result.missing().isEmpty(), "missing " + result.missing());
        var seen = new HashSet<String>();
        var excess = result.excess();
        for (var pair : result.pairs()) {
            assertNotNull(pair.source());
            assertTrue(seen.add(pair.first() + "/" + pair.second()), "one source per pair");
            int i = ids.indexOf(pair.first()), j = ids.indexOf(pair.second());
            if (pair.first().equals("Hydrogen") || pair.second().equals("Hydrogen")) {
                assertEquals(DerivedPairs.Source.HYDROGEN, pair.source(), pair.toString());
                assertTrue(excess.classical(i, j) && excess.interaction(i, j) == 0.0 && excess.interactionSlope(i, j) == 0.0
                        && excess.interactionPair(i, j) < 0, "hydrogen pairs are zero: " + pair);
            }
        }
        assertEquals(DerivedPairs.Source.ACTIVITY, source(result, "Water", "Ethanol"));
        assertEquals(DerivedPairs.Source.ACTIVITY, source(result, "Water", "N-hexane"));
        assertEquals(DerivedPairs.Source.ACTIVITY, source(result, "Ethanol", "crude_pc03"));
        assertEquals(DerivedPairs.Source.HENRY, source(result, "Water", "Nitrogen"));
        assertEquals(DerivedPairs.Source.HOMOMORPH, source(result, "Ethanol", "Nitrogen"));
        // Option B (2026-11-11): a gas pair is E-PPR78 at 298.15 K as a constant; two non-polar non-gases are zero.
        assertEquals(DerivedPairs.Source.EPPR78, source(result, "Methane", "Nitrogen"));
        assertEquals(DerivedPairs.Source.EPPR78, source(result, "Methane", "crude_pc03"));
        assertEquals(DerivedPairs.Source.NONPOLAR, source(result, "N-hexane", "crude_pc03"));
        assertEquals(DerivedPairs.Source.NONPOLAR, source(result, "Propane", "crude_pc03"));
        int methane = ids.indexOf("Methane"), nitrogen = ids.indexOf("Nitrogen");
        assertTrue(excess.classical(methane, nitrogen) && excess.interactionPair(methane, nitrogen) < 0
                && excess.interactionSlope(methane, nitrogen) == 0.0 && excess.interaction(methane, nitrogen) != 0.0, "a constant gas pair");
        assertTrue(excess.interactions() == null, "no derived pair carries a temperature law");
        int hexane = ids.indexOf("N-hexane"), pc03 = ids.indexOf("crude_pc03");
        assertTrue(excess.classical(hexane, pc03) && excess.interaction(hexane, pc03) == 0.0);
        // An authored pair wins over E-PPR78, which both compounds' counts would give.
        assertEquals(DerivedPairs.Source.LIBRARY, source(result, "N-hexane", "Benzene"));
        assertEquals(0.008, excess.interaction(ids.indexOf("N-hexane"), ids.indexOf("Benzene")));
        assertEquals(DerivedPairs.Source.LIBRARY, source(result, "Nitrogen", "CarbonDioxide"));
        assertEquals(-0.0193, excess.interaction(ids.indexOf("Nitrogen"), ids.indexOf("CarbonDioxide")));
        assertTrue(result.identity().startsWith("derived-pairs=" + DerivedPairs.REVISION + ";records="), result.identity());
        assertTrue(result.identity().contains("icheme:dwsim_refinery_pr78_co2@"), result.identity());
        // The cut takes paraffinic counts: 131.6 g/mol is C9H20.
        var cut = catalog.requirePackage(PACKAGE).properties().get(catalog.requirePackage(PACKAGE).components().indexOf("crude_pc03"));
        assertEquals(Map.of("CH3", 2, "CH2", 7), DerivedPairs.paraffinic(cut));
    }

    /**
     * Section 13 of W5_COLUMN_PLAN.md (owner, 2026-11-12): an activity pair needs a solvent, water or a polar compound that names a
     * homomorph. Methyl mercaptan, polar by its SH group with no homomorph, takes E-PPR78 at 298.15 K with a hydrocarbon and a
     * cut; with water it takes E-PPR78 as before the rule, since the Dortmund table holds no H2O / CH3SH group pair. Water and
     * ethanol, which names one, keep their fits with the hydrocarbons.
     */
    @Test void anActivityPairNeedsASolvent() {
        var bundled = MaterialCatalog.bundled().withWater("icheme:refinery");
        var sour = DerivedPairs.derive(bundled, "icheme:refinery", List.of("Propane", "N-hexane", "MethylMercaptan", "crude_pc03", "Water"));
        assertEquals(DerivedPairs.Source.EPPR78, source(sour, "MethylMercaptan", "Propane"));
        assertEquals(DerivedPairs.Source.EPPR78, source(sour, "MethylMercaptan", "N-hexane"));
        assertEquals(DerivedPairs.Source.EPPR78, source(sour, "MethylMercaptan", "crude_pc03"));
        assertEquals(DerivedPairs.Source.EPPR78, source(sour, "MethylMercaptan", "Water"));
        assertTrue(sour.notices().stream().anyMatch(n -> n.startsWith("MethylMercaptan / Water: modified UNIFAC: no group pair")), () -> "notices " + sour.notices());
        assertEquals(DerivedPairs.Source.ACTIVITY, source(sour, "N-hexane", "Water"));
        assertTrue(sour.polar(sour.components().indexOf("MethylMercaptan")), "still polar by its groups");
        var pilot = DerivedPairs.derive(PilotAqueousTestCatalog.catalog(), PACKAGE);
        assertEquals(DerivedPairs.Source.ACTIVITY, source(pilot, "Ethanol", "N-hexane"));
    }

    @Test void theIdentityNamesTheRevisionsRead() {
        List<String> list = List.of("Water", "Ethanol");
        String base = DerivedPairs.derive(PilotAqueousTestCatalog.catalog(), PACKAGE, list).identity();
        var bumped = PilotAqueousTestCatalog.parseWith(ETHANOL_RECORD, o -> {
            var counts = o.getAsJsonObject("group_counts").getAsJsonObject(ModifiedUnifac.SCHEME);
            counts.addProperty("revision", counts.get("revision").getAsString() + "-edited");
        });
        String moved = DerivedPairs.derive(bumped, PACKAGE, list).identity();
        assertNotEquals(base, moved);
        assertTrue(moved.contains("properties/icheme:chemsep_ethanol/" + ModifiedUnifac.SCHEME + "@chemsep-8.3-modfac-r1-edited"), moved);
        var unread = PilotAqueousTestCatalog.parseWith(ETHANOL_RECORD, o -> {
            var freezing = o.getAsJsonObject("freezing");
            freezing.addProperty("revision", freezing.get("revision").getAsString() + "-edited");
        });
        assertEquals(base, DerivedPairs.derive(unread, PACKAGE, list).identity(), "a sub-record the derivation does not read");
    }

    @Test void aPairIsFittedOncePerCatalog() {
        var catalog = PilotAqueousTestCatalog.parseWith(ETHANOL_RECORD, o -> {});
        var first = DerivedPairs.derive(catalog, PACKAGE);
        assertTrue(first.fitsRun() > 0);
        var second = DerivedPairs.derive(catalog, PACKAGE);
        assertEquals(0, second.fitsRun(), "the same list again");
        assertEquals(0, DerivedPairs.derive(catalog, PACKAGE, List.of("Ethanol", "Water", "Nitrogen")).fitsRun(), "a list sharing the pairs");
        assertEquals(first.fitsRun(), catalog.derivedPairCache().fitsRun());
        assertEquals(first.identity(), second.identity());
        assertEquals(first.pairs(), second.pairs());
        // The order of a list does not change a fitted pair.
        var forward = DerivedPairs.derive(catalog, PACKAGE, List.of("Ethanol", "Water")).excess();
        var backward = DerivedPairs.derive(catalog, PACKAGE, List.of("Water", "Ethanol")).excess();
        assertEquals(forward.alpha(0, 1), backward.alpha(0, 1));
        assertEquals(forward.energyIntercept(0, 1), backward.energyIntercept(1, 0));
        assertEquals(forward.energySlope(1, 0), backward.energySlope(0, 1));
    }

    @Test void aHomomorphNamesAKnownCompoundWithADataset() {
        for (var edit : List.<java.util.function.Consumer<com.google.gson.JsonObject>>of(
                o -> o.getAsJsonObject("homomorph").addProperty("component", "Nope"),
                o -> o.getAsJsonObject("homomorph").addProperty("component", "Ethanol"),
                o -> o.getAsJsonObject("homomorph").addProperty("revision", "bad revision"),
                o -> o.getAsJsonObject("homomorph").remove("source"))) {
            var error = assertThrows(IllegalArgumentException.class, () -> PilotAqueousTestCatalog.parseWith(ETHANOL_RECORD, edit));
            assertTrue(error.getMessage().contains("chemsep_ethanol.json") && error.getMessage().contains("homomorph."), error.getMessage());
        }
        assertEquals("Propane", PilotAqueousTestCatalog.catalog().homomorph(PACKAGE, "Ethanol").orElseThrow().component());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The solvers of group-derived-pairs.mjs on the kernel
    // ---------------------------------------------------------------------------------------------------------------

    private static double[] logPhi(PengRobinsonKernel kernel, double t, double p, double[] x, PengRobinsonKernel.Root root) {
        var output = kernel.newEvaluation();
        try {
            kernel.evaluate(t, p, x, root, kernel.newWorkspace(), output);
        } catch (RuntimeException unavailable) {
            return null;
        }
        return output.logFugacityCoefficients();
    }

    private record Bubble(double t, double y) {}

    /** {@code bubble}: bisection on T in [230, 520] K; per T, y by successive substitution (phi-phi). */
    private static Bubble bubble(PengRobinsonKernel kernel, double x1, double pressure) {
        double[] x = {x1, 1.0 - x1};
        double low = 230.0, high = 520.0;
        for (int k = 0; k < 60; k++) {
            double middle = 0.5 * (low + high);
            double[] y = x.clone();
            if (residual(kernel, middle, pressure, x, y) > 0) high = middle; else low = middle;
        }
        double t = 0.5 * (low + high);
        double[] y = x.clone();
        residual(kernel, t, pressure, x, y);
        return new Bubble(t, y[0]);
    }

    /** sum K_i x_i - 1 at T, with y updated in place; NaN without roots. */
    private static double residual(PengRobinsonKernel kernel, double t, double pressure, double[] x, double[] y) {
        double sum = 1.0;
        for (int iteration = 0; iteration < 300; iteration++) {
            double[] liquid = logPhi(kernel, t, pressure, x, PengRobinsonKernel.Root.LIQUID);
            double[] vapour = logPhi(kernel, t, pressure, y, PengRobinsonKernel.Root.VAPOR);
            if (liquid == null || vapour == null) return Double.NaN;
            double k0 = x[0] * Math.exp(liquid[0] - vapour[0]), k1 = x[1] * Math.exp(liquid[1] - vapour[1]);
            sum = k0 + k1;
            double next = k0 / sum, change = Math.abs(next - y[0]);
            y[0] = next;
            y[1] = k1 / sum;
            if (change < 1e-12) break;
        }
        return sum - 1.0;
    }

    /** {@code azeotrope}: the scanned x (step 0.002) where |y - x| is least; NaN when that is not below 1.5e-3. */
    private static double azeotrope(PengRobinsonKernel kernel, double pressure, double from, double to) {
        double best = Double.NaN, gap = Double.POSITIVE_INFINITY;
        for (int k = 0; from + k * 0.002 <= to; k++) {
            double x = from + k * 0.002;
            double d = Math.abs(bubble(kernel, x, pressure).y - x);
            if (d < gap) { gap = d; best = x; }
        }
        return gap < 1.5e-3 && best < to - 0.003 ? best : Double.NaN;
    }

    /** {@code twoLiquids}: K_i = phi_i(aqueous)/phi_i(organic), Rachford-Rice on the organic share; null for one liquid. */
    private static double[][] twoLiquids(PengRobinsonKernel kernel, double[] z, double t, double p, double[] aqueous, double[] organic) {
        int n = z.length;
        double[] xa = aqueous.clone(), xo = organic.clone();
        for (int iteration = 0; iteration < 4000; iteration++) {
            double[] la = logPhi(kernel, t, p, xa, PengRobinsonKernel.Root.LIQUID), lo = logPhi(kernel, t, p, xo, PengRobinsonKernel.Root.LIQUID);
            double[] k = new double[n];
            for (int i = 0; i < n; i++) k[i] = Math.exp(la[i] - lo[i]);
            java.util.function.DoubleUnaryOperator f = v -> {
                double s = 0.0;
                for (int i = 0; i < n; i++) s += z[i] * (k[i] - 1.0) / (1.0 + v * (k[i] - 1.0));
                return s;
            };
            double a = 1e-12, b = 1.0 - 1e-12;
            if (f.applyAsDouble(a) < 0 || f.applyAsDouble(b) > 0) return null;
            for (int step = 0; step < 80; step++) {
                double m = 0.5 * (a + b);
                if (f.applyAsDouble(m) > 0) a = m; else b = m;
            }
            double beta = 0.5 * (a + b), sa = 0.0, so = 0.0;
            double[] na = new double[n], no = new double[n];
            for (int i = 0; i < n; i++) { na[i] = z[i] / (1.0 + beta * (k[i] - 1.0)); no[i] = k[i] * na[i]; sa += na[i]; so += no[i]; }
            double change = 0.0;
            for (int i = 0; i < n; i++) {
                change = Math.max(change, Math.max(Math.abs(na[i] / sa - xa[i]), Math.abs(no[i] / so - xo[i])));
                xa[i] = na[i] / sa;
                xo[i] = no[i] / so;
            }
            if (change < 1e-13) break;
        }
        return new double[][] {xa, xo};
    }

    /** {@code dissolved}: the gas's mole fraction in the water-rich liquid, gas first, at T and the total pressure. */
    private static double dissolved(PengRobinsonKernel kernel, double t, double pressure) {
        double[] x = {1e-4, 1 - 1e-4}, y = {0.97, 0.03};
        for (int iteration = 0; iteration < 2000; iteration++) {
            double[] liquid = logPhi(kernel, t, pressure, x, PengRobinsonKernel.Root.LIQUID);
            double[] vapour = logPhi(kernel, t, pressure, y, PengRobinsonKernel.Root.VAPOR);
            double waterVapour = x[1] * Math.exp(liquid[1] - vapour[1]);
            double gas = (1.0 - waterVapour) * Math.exp(vapour[0] - liquid[0]);
            double change = Math.abs(gas - x[0]);
            x = new double[] {gas, 1.0 - gas};
            y = new double[] {1.0 - waterVapour, waterVapour};
            if (change < 1e-14) break;
        }
        return x[0];
    }

    /**
     * W3b step 2c: the derived lines (NRTL energies, homomorph k) are held past their fitted ends, so the kernel answers
     * both roots from 90 to 800 K at 1 atm and at 10 MPa for the nine pairs measured in step 2b (the plain lines refused
     * water/n-hexane from 750 K, water/crude_pc03 at 800 K and water/N2 from 700 K), and the held line is the plain line
     * between the ends.
     */
    @Test void heldLinesAnswerFromNinetyToEightHundredKelvin() {
        String[][] pairs = {{"Water", "Ethanol"}, {"Water", "N-hexane"}, {"Water", "Nitrogen"}, {"Water", "Methane"},
                {"Water", "CarbonDioxide"}, {"Ethanol", "N-hexane"}, {"Ethanol", "Nitrogen"}, {"Water", "Benzene"},
                {"Water", "crude_pc03"}};
        int answered = 0;
        for (String[] pair : pairs) {
            var kernel = kernel(pair[0], pair[1]);
            var workspace = kernel.newWorkspace();
            var evaluation = kernel.newEvaluation();
            for (double t = 90.0; t <= 800.0; t += 5.0) {
                for (double pressure : new double[] {ATM, 1.0e7}) {
                    for (double x = 0.02; x < 0.99; x += 0.08) {
                        for (var root : PengRobinsonKernel.Root.values()) {
                            String at = String.format(Locale.ROOT, "%s/%s %.0f K %.0f Pa x %.2f %s", pair[0], pair[1], t, pressure, x, root);
                            double kelvin = t, share = x;
                            assertDoesNotThrow(() -> kernel.evaluate(kelvin, pressure, new double[] {share, 1.0 - share}, root, workspace, evaluation), at);
                            for (double value : evaluation.logFugacityCoefficients()) assertTrue(Double.isFinite(value), at);
                            answered++;
                        }
                    }
                }
            }
        }
        System.out.println("HELD_LINES 90..800 K, 1 atm and 10 MPa: " + answered + " evaluations answered");
        double[] out = new double[3];
        for (double t = 298.15; t <= 373.15; t += 2.5) {
            com.wormzjl.icheme.science.thermo.HuronVidalExcess.held(1234.5, -6.75, 298.15, 373.15, t, out);
            assertEquals(1234.5 + -6.75 * t, out[0], 0.0);
            assertEquals(-6.75, out[1], 0.0);
        }
        // Past an end the slope fades over 150 K: the value gains s W/2, then stays (W3b step 2d).
        com.wormzjl.icheme.science.thermo.HuronVidalExcess.held(0.0, 2.0, 298.15, 373.15, 373.15 + 150.0, out);
        assertEquals(2.0 * 373.15 + 150.0, out[0], 1e-12);
        assertEquals(0.0, out[1], 1e-15);
        assertEquals(0.0, out[2], 1e-15);
        com.wormzjl.icheme.science.thermo.HuronVidalExcess.held(0.0, 2.0, 298.15, 373.15, 900.0, out);
        assertEquals(2.0 * 373.15 + 150.0, out[0], 1e-12);
        assertEquals(0.0, out[1], 0.0);
        com.wormzjl.icheme.science.thermo.HuronVidalExcess.held(0.0, 2.0, 298.15, 373.15, 98.15, out);
        assertEquals(2.0 * 298.15 - 150.0, out[0], 1e-12);
        // Value, slope and second derivative continuous at both ends and at the end of the fade.
        for (double edge : new double[] {298.15, 373.15, 148.15, 523.15}) {
            double[] below = new double[3], above = new double[3];
            com.wormzjl.icheme.science.thermo.HuronVidalExcess.held(5.0, 2.0, 298.15, 373.15, edge - 1e-7, below);
            com.wormzjl.icheme.science.thermo.HuronVidalExcess.held(5.0, 2.0, 298.15, 373.15, edge + 1e-7, above);
            assertEquals(below[0], above[0], 1e-6, "value at " + edge);
            assertEquals(below[1], above[1], 1e-7, "slope at " + edge);
            assertEquals(below[2], above[2], 1e-7, "second derivative at " + edge);
        }
    }

    /**
     * W3b step 2d: water/ethanol at x = 0.5 on the liquid root at 1 atm. The residual enthalpy and its temperature
     * slope (the residual heat capacity) are continuous through the fitted ends 298.15 and 373.15 K and the ends of the
     * fade, 148.15 and 523.15 K (a linear fade over 50 K left a step of about 35 J/mol/K at 373.15 K). Printed: the
     * largest step at an end, and at 10 MPa (a liquid over the whole fade) the largest departure of the residual heat
     * capacity from its linear trend at the fitted end, an upper indicator of what the hold adds (it holds the mixture's
     * own curvature too).
     */
    @Test void heldEnthalpyAndHeatCapacityAreContinuousThroughTheFittedEnds() {
        var kernel = kernel("Water", "Ethanol");
        var workspace = kernel.newWorkspace();
        var derivatives = kernel.newDerivatives();
        double[] x = {0.5, 0.5};
        double delta = 1.0e-4, step = 0.0;
        for (double edge : new double[] {148.15, 298.15, 373.15, 523.15}) {
            kernel.evaluateDerivatives(edge - delta, ATM, x, PengRobinsonKernel.Root.LIQUID, workspace, derivatives);
            double hBelow = derivatives.evaluation().residualEnthalpyJoulesPerMol(), cpBelow = derivatives.dResidualEnthalpyDt();
            kernel.evaluateDerivatives(edge + delta, ATM, x, PengRobinsonKernel.Root.LIQUID, workspace, derivatives);
            double hAbove = derivatives.evaluation().residualEnthalpyJoulesPerMol(), cpAbove = derivatives.dResidualEnthalpyDt();
            String at = String.format(Locale.ROOT, "water/ethanol x 0.5 liquid at %.2f K", edge);
            // H: its change over 2 delta is the slope's (no step); the slope: no step (a linear fade left about 35 J/mol/K).
            assertEquals(0.5 * (cpBelow + cpAbove) * 2.0 * delta, hAbove - hBelow, 1.0e-5, "H " + at);
            assertEquals(cpBelow, cpAbove, 0.01, "dH/dT " + at);
            step = Math.max(step, Math.abs(cpAbove - cpBelow));
        }
        double pressure = 1.0e7;
        double largest = 0.0, where = 0.0;
        for (double[] end : new double[][] {{298.15, -1.0}, {373.15, 1.0}}) {
            kernel.evaluateDerivatives(end[0], pressure, x, PengRobinsonKernel.Root.LIQUID, workspace, derivatives);
            double atEnd = derivatives.dResidualEnthalpyDt();
            kernel.evaluateDerivatives(end[0] - end[1] * 1.0, pressure, x, PengRobinsonKernel.Root.LIQUID, workspace, derivatives);
            double slopeInside = atEnd - derivatives.dResidualEnthalpyDt();
            for (double u = 1.0; u <= 150.0; u += 1.0) {
                double t = end[0] + end[1] * u;
                kernel.evaluateDerivatives(t, pressure, x, PengRobinsonKernel.Root.LIQUID, workspace, derivatives);
                double change = derivatives.dResidualEnthalpyDt() - (atEnd + slopeInside * u);
                if (Math.abs(change) > Math.abs(largest)) { largest = change; where = t; }
            }
        }
        System.out.println(String.format(Locale.ROOT,
                "HELD_HEAT_CAPACITY water/ethanol x 0.5 liquid: largest step at an end %.2e J/mol/K (1 atm); largest departure from the end's linear trend %.2f J/mol/K at %.0f K (10 MPa)",
                step, largest, where));
    }

    private static DerivedPairs.Source source(DerivedPairs.Result result, String a, String b) {
        for (var pair : result.pairs()) {
            if (pair.first().equals(a) && pair.second().equals(b) || pair.first().equals(b) && pair.second().equals(a)) return pair.source();
        }
        throw new AssertionError("no pair " + a + " / " + b);
    }
}
