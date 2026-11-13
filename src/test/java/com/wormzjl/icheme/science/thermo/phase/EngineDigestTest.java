package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.fail;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Status;
import com.wormzjl.icheme.science.thermo.phase.PhaseTestSupport.StubIdealGas;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The equilibrium engine's digest gate (batch 2026-11-13-codebase-review, D-E0; THERMO_CORE_REVIEW section 3, "digest
 * (to add)"): a fixed list of TP, PH and UV requests and crystal onsets on the engines the tests reach without a game,
 * each answer written as one text line of raw bits, compared line by line with the recorded reference
 * {@value #REFERENCE}. The engine's split (S01) and the moves after it keep every bit, so a line that moves names the
 * request whose answer changed.
 *
 * <p>The requests are chosen from the engine's stages, not from a uniform grid: the feed stability test and its longer
 * budget, the two-phase flash with its Newton finish, merging and the critical band, the three-slot system with water
 * in the cubic, crystals (one, two, complete deposition, beside a vapour and a liquid, the near-pure rule, the triple
 * point), carried crystals, the PH and UV searches with the steps they answer from a balance, the warm-started UV, the
 * onsets, and the refusals (typed unsupported, outside the numerical box, a species list off the basis). PH and UV
 * requests are made from the enthalpy, internal energy and volume of converged TP answers, so they are consistent by
 * construction; a sweep places PH targets between two TP answers, inside whatever steps lie between them.</p>
 *
 * <p>A line is {@code <engine> <request> | <answer>}: the specification and status, the typed reason (the unsupported
 * reason, the failure kind read from the detail's typed prefix), the classification and grade; the diagnostics' counts
 * (flash, extrapolation, Rachford-Rice, kernel, derivative, Newton and outer iterations, the band and balance flags) and
 * the raw bits of their doubles; per phase its label, crystal, temperature, pressure, the bits of every nonzero amount,
 * its root and root count, molar volume, enthalpy, entropy, Gibbs energy, water's share, and {@code ln phi} over the
 * request's species; the aggregates H, U, V, S; the deposition's numbers. A NaN is written {@code NaN} (its payload is
 * the platform's); every other double is {@link Double#doubleToRawLongBits} in hex. Each engine answers its requests
 * on one workspace, in list order. No request depends on a wall clock or a thread count (the engine reads neither); every
 * budget is a count of iterations.</p>
 *
 * <p>Record: with the system property {@value #RECORD_PROPERTY}{@code =true} or the environment variable
 * {@value #RECORD_ENVIRONMENT}{@code =true} the lines go to {@code build/engine-digest.txt} and the test passes; copy
 * that file to {@code src/test/resources}{@value #REFERENCE}. Without a reference the test fails ("no reference: record
 * it").</p>
 */
class EngineDigestTest {
    static final String REFERENCE = "/thermo/engine-digest.txt";
    static final String RECORD_PROPERTY = "icheme.engineDigest.record";
    static final String RECORD_ENVIRONMENT = "ICHEME_ENGINE_DIGEST_RECORD";
    private static final String SEPARATOR = " | ";
    private static final PhaseCompetition FLUID = PhaseCompetition.FLUID_ONLY;
    private static final PhaseCompetition CRYSTALS = PhaseCompetition.FLUID_AND_CRYSTALS;
    private static final PhaseCompetition CARRIED = PhaseCompetition.FLUID_CARRYING_CRYSTALS;
    private static final String CO2_I = "icheme:crystal_carbon_dioxide_i";
    private static final double ATM = 101325.0;

    @Test
    void everyDigestRequestIsAnsweredBitForBitAsRecorded() throws IOException {
        var digest = new Digest();
        testEngines(digest);
        pilotCryogenic(digest);
        pilotNetwork(digest);
        pilotAqueous(digest);
        bundledDry(digest);
        bundledWet(digest);
        refineryWet(digest);
        refineryDry(digest);
        List<String> lines = digest.lines;
        System.out.println("engine digest: " + lines.size() + " requests; " + digest.tally);
        if (recording()) {
            Path out = Path.of("build", "engine-digest.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            System.out.println("engine digest recorded: " + out.toAbsolutePath());
            return;
        }
        List<String> reference = reference();
        if (reference == null) {
            fail("no reference: record it (run with " + RECORD_ENVIRONMENT + "=true or -D" + RECORD_PROPERTY
                    + "=true, then copy build/engine-digest.txt to src/test/resources" + REFERENCE + ")");
        }
        int common = Math.min(reference.size(), lines.size());
        for (int k = 0; k < common; k++) {
            if (!reference.get(k).equals(lines.get(k))) {
                fail("request " + (k + 1) + " (" + id(lines.get(k)) + ") differs from the reference\n reference: "
                        + reference.get(k) + "\n now:       " + lines.get(k));
            }
        }
        if (reference.size() != lines.size()) {
            fail(lines.size() + " requests against " + reference.size() + " in the reference; the first "
                    + (lines.size() > common ? "new one: " + id(lines.get(common)) : "missing one: " + id(reference.get(common))));
        }
    }

    private static boolean recording() {
        return Boolean.parseBoolean(System.getProperty(RECORD_PROPERTY)) || "true".equalsIgnoreCase(System.getenv(RECORD_ENVIRONMENT));
    }

    /** The reference's lines in order (blank lines skipped), or null without the resource. */
    private static List<String> reference() throws IOException {
        try (InputStream in = EngineDigestTest.class.getResourceAsStream(REFERENCE)) {
            if (in == null) return null;
            List<String> lines = new ArrayList<>();
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (!line.isEmpty()) lines.add(line);
                }
            }
            return lines;
        }
    }

    private static String id(String line) {
        int end = line.indexOf(SEPARATOR);
        return end < 0 ? line : line.substring(0, end);
    }

    // The engines.

    /** One engine of the digest with the one workspace its requests run on, in list order. */
    private static final class Engine {
        final String name;
        final FluidTpEquilibrium engine;
        final FluidTpEquilibrium.Workspace workspace;

        Engine(String name, FluidTpEquilibrium engine) {
            this.name = name;
            this.engine = engine;
            this.workspace = engine.newWorkspace();
        }

        List<String> basis() { return engine.contract().components(); }
    }

    /** Amounts over {@code e}'s basis from (species, amount) pairs, every other species zero; a name off the basis fails. */
    private static double[] z(Engine e, Object... pairs) {
        var contract = e.engine.contract();
        double[] amounts = new double[contract.components().size()];
        for (int k = 0; k < pairs.length; k += 2) {
            int i = contract.index((String) pairs[k]);
            if (i < 0) throw new IllegalStateException(e.name + " has no species " + pairs[k]);
            amounts[i] = ((Number) pairs[k + 1]).doubleValue();
        }
        return amounts;
    }

    /** The Tia Juana light assay with 5 mol% nitrogen ({@link PhaseTestSupport#crudeWithNitrogen}), and {@code water} of water. */
    private static double[] crude(Engine e, double water) {
        double[] amounts = PhaseTestSupport.crudeWithNitrogen(e.engine.contract());
        if (water > 0.0) amounts[index(e, "Water")] = water;
        return amounts;
    }

    /** 10 mol% each of C1 to nC5, 30 % nitrogen and 1e-4 of each cut: a gas with a trace liquid. */
    private static double[] lightGas(Engine e) {
        double[] gas = z(e, "Methane", 0.1, "Ethane", 0.1, "Propane", 0.1, "Isobutane", 0.1, "N-butane", 0.1,
                "Isopentane", 0.1, "N-pentane", 0.1, "Nitrogen", 0.3);
        List<String> basis = e.basis();
        for (int i = 0; i < gas.length; i++) if (basis.get(i).startsWith("crude_pc")) gas[i] = 1.0e-4;
        return gas;
    }

    private static int index(Engine e, String species) {
        int i = e.engine.contract().index(species);
        if (i < 0) throw new IllegalStateException(e.name + " has no species " + species);
        return i;
    }

    private static FluidTpEquilibrium.Settings settings(int substitutionLimit, int newtonIterations, double mergeTieLine,
                                                        double mergeVolumeGap) {
        return new FluidTpEquilibrium.Settings(1.0e-10, 1000, 5, 1.0e-9, 1.0e-8, TangentPlaneStability.Settings.DEFAULT,
                substitutionLimit, newtonIterations, mergeTieLine, mergeVolumeGap, 100, 1.0e-11);
    }

    /** The P3 tests' methane/nitrogen binary on its open research contract, with {@code settings}. */
    private static FluidTpEquilibrium binary(FluidTpEquilibrium.Settings settings) {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        return new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator, settings);
    }

    /** The kernel's own equal-fugacity pressure of pure {@code species} at {@code t}, bisected between the two pressures. */
    private static double kernelSaturation(Engine e, String species, double t, double below, double above) {
        var kernel = e.engine.evaluator().kernel();
        int k = index(e, species);
        double[] pure = new double[kernel.componentCount()];
        pure[k] = 1.0;
        var workspace = kernel.newWorkspace();
        var liquid = kernel.newEvaluation();
        var vapour = kernel.newEvaluation();
        for (int iteration = 0; iteration < 200 && Math.nextUp(below) < above; iteration++) {
            double middle = 0.5 * (below + above);
            kernel.evaluate(t, middle, pure, Root.LIQUID, workspace, liquid);
            kernel.evaluate(t, middle, pure, Root.VAPOR, workspace, vapour);
            if (liquid.logFugacityCoefficient(k) - vapour.logFugacityCoefficient(k) > 0.0) below = middle; else above = middle;
        }
        return 0.5 * (below + above);
    }

    // The requests, engine by engine.

    /** Engines the P2/P3 tests build on test contracts: the near-critical binary, forced failures, merging, two liquids, water chemistry. */
    private static void testEngines(Digest d) {
        Engine bin = new Engine("test:methane_nitrogen", binary(FluidTpEquilibrium.Settings.DEFAULT));
        // P3: states P2 left typed-not-converged next to the critical locus; the Newton finish inside the band.
        double[][] band = {{140.0, 4.2e6, 0.82}, {130.0, 3.58e6, 0.95}, {145.0, 4.36e6, 0.75}, {150.0, 4.56e6, 0.67},
                {155.0, 4.7e6, 0.58}, {165.0, 4.98e6, 0.47}, {175.0, 4.92e6, 0.31}, {185.0, 4.76e6, 0.11}};
        for (int k = 0; k < band.length; k++) {
            double x = band[k][2];
            d.tp(bin, band[k][0], band[k][1], z(bin, "Methane", 1.0 - x, "Nitrogen", x), "x_N2 " + x, FLUID, k == 3 || k == 5);
        }
        // P2's grid: two-phase and single-phase states, round trips at the middle composition.
        for (double t : new double[] {110.0, 120.0}) {
            for (double p : new double[] {0.5e6, 3.0e6}) {
                for (double x : new double[] {0.1, 0.5, 0.9}) {
                    d.tp(bin, t, p, z(bin, "Methane", 1.0 - x, "Nitrogen", x), "x_N2 " + x, FLUID, x == 0.5 && (t == 110.0) == (p == 3.0e6));
                }
            }
        }
        // P3 WP7c labels: liquid, one-root vapour, supercritical, hot nitrogen, dense and hot mixtures.
        double[][] labels = {{77.0, 0.5e6, 0.0}, {300.0, 0.1e6, 0.0}, {130.0, 6.0e6, 0.0}, {700.0, 5.0e6, 0.0},
                {150.0, 10.0e6, 0.5}, {400.0, 3.0e6, 0.5}};
        for (double[] s : labels) d.tp(bin, s[0], s[1], z(bin, "Methane", s[2], "Nitrogen", 1.0 - s[2]), "x_CH4 " + s[2], FLUID, false);
        // H(T) through the dew and bubble points, and pure nitrogen's coexistence from the balance.
        d.sweep(bin, 1.0e6, z(bin, "Methane", 0.5, "Nitrogen", 0.5), "x_N2 0.5", FLUID, 100.0, 160.0, 4);
        d.sweep(bin, 0.5e6, z(bin, "Nitrogen", 1.0), "N2", FLUID, 85.0, 100.0, 4);

        // Forced failures (one substitution step, one Newton step): typed CRITICAL_BAND inside the band, untyped outside.
        Engine budget = new Engine("test:methane_nitrogen/budget-1", binary(settings(1, 1, 1.0e-8, 1.0e-2)));
        d.tp(budget, 150.0, 4.56e6, z(budget, "Methane", 0.33, "Nitrogen", 0.67), "x_N2 0.67", FLUID, false);
        d.tp(budget, 110.0, 0.5e6, z(budget, "Methane", 0.5, "Nitrogen", 0.5), "x_N2 0.5", FLUID, false);
        // A split within widened merge thresholds: one fluid.
        Engine merge = new Engine("test:methane_nitrogen/merge", binary(settings(50, 50, 1.0, 10.0)));
        d.tp(merge, 150.0, 4.56e6, z(merge, "Methane", 0.33, "Nitrogen", 0.67), "x_N2 0.67", FLUID, false);

        // P3 WP7d: two liquid-like phases of a synthetic binary with k_ij 0.3; one liquid at k_ij 0.
        for (double kij : new double[] {0.3, 0.0}) {
            var components = List.of(new ThermoComponent("LightLiquid", 190.564, 4.5992e6, 0.01142, 0.0160428),
                    new ThermoComponent("HeavyLiquid", 305.32, 4.8722e6, 0.0995, 0.0300690));
            var synthetic = new CubicPhaseEvaluator("test:synthetic-lle", components, new double[][] {{0.0, kij}, {kij, 0.0}},
                    new double[2], List.of(StubIdealGas.constant(35.7, 50.0, 1000.0), StubIdealGas.constant(52.5, 50.0, 1000.0)));
            Engine lle = new Engine("test:synthetic-lle/kij-" + kij,
                    new FluidTpEquilibrium(PhaseTestSupport.openContract(synthetic), synthetic, FluidTpEquilibrium.Settings.DEFAULT));
            d.tp(lle, 110.0, 3.0e6, z(lle, "LightLiquid", 0.5, "HeavyLiquid", 0.5), "0.5/0.5", FLUID, kij > 0.0);
        }

        // A contract that names a water-chemistry species and a qualified crystal it has no evaluator for.
        var pkg = PhaseTestSupport.network();
        var methane = pkg.properties().get(pkg.components().indexOf("Methane")).pr();
        List<ThermoComponent> components = List.of(
                new ThermoComponent("Methane", methane.criticalTemperature(), methane.criticalPressure(), methane.acentricFactor(), 0.0160428),
                new ThermoComponent("Ammonia", 405.4, 11.333e6, 0.256, 0.017031),
                new ThermoComponent("Water", 647.096, 22.064e6, 0.3443, 0.018015));
        var evaluator = new CubicPhaseEvaluator("test:ammonia", components, new double[3][3], new double[3],
                List.of(StubIdealGas.constant(35.7, 100, 1000), StubIdealGas.constant(35.1, 100, 1000), StubIdealGas.constant(33.6, 100, 1000)));
        List<String> basis = List.of("Methane", "Ammonia", "Water");
        var identity = new ThermoIdentity("test:ammonia", evaluator.family(), ThermoIdentity.NO_SPINE,
                ThermoIdentity.WATER_IN_EOS_FORMULATION + ":test");
        var contract = new PhaseContract("test:ammonia", identity, basis, WaterParticipation.IN_EQUATION_OF_STATE, "Water",
                Set.of(PhaseCompetition.FLUID_ONLY, PhaseCompetition.withCrystals("ammonia_solid")), Set.of("Ammonia"),
                PhaseDomain.open("test:ammonia", basis), new EquilibriumResult.Coverage(CoverageGrade.RESEARCH_ONLY, "test"));
        Engine ammonia = new Engine("test:ammonia", new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT));
        d.tp(ammonia, 300.0, 1.0e6, z(ammonia, "Methane", 1.0, "Ammonia", 0.2, "Water", 1.0), "wet", FLUID, false);
        d.tp(ammonia, 300.0, 1.0e6, z(ammonia, "Methane", 1.0, "Ammonia", 0.2), "dry", FLUID, false);
        d.tp(ammonia, 300.0, 1.0e6, z(ammonia, "Methane", 1.0, "Ammonia", 0.2), "dry", PhaseCompetition.withCrystals("ammonia_solid"), false);
        d.tp(ammonia, 300.0, 1.0e6, z(ammonia, "Methane", 1.0, "Ammonia", 0.2), "dry",
                PhaseCompetition.withCrystals("ammonia_solid", "methane_solid"), false);
    }

    /** The pilot package {@code icheme:pilot_cryogenic} with its CO2-I crystal (the G3, G4, G5, P4, P5 and D21 engine). */
    private static void pilotCryogenic(Digest d) {
        MaterialCatalog catalog = PilotCryogenicTestCatalog.withPilot();
        Engine cryo = new Engine("pilot_cryogenic", PackageBinding.equilibrium(catalog, "icheme:pilot_cryogenic"));
        // Fluid answers (P3 WP7d, WP11): splits on a reused workspace, dense gases beside liquids, an unstable product, type III
        // liquids, the near-critical split, the unresolved feed test repeated on the longer budget.
        double[][] states = {{150.0, 4.56e6, 0.67, 0.33, 0.0, 0.0}, {250.0, 8.0e6, 0.0, 0.55, 0.0, 0.45},
                {220.0, 6.0e6, 0.0, 0.85, 0.15, 0.0}, {100.0, 4.0e6, 0.85, 0.0, 0.15, 0.0}, {220.0, 6.0e6, 0.0, 0.55, 0.0, 0.45},
                {120.0, 2.0e6, 0.3, 0.7, 0.0, 0.0}, {165.0, 4.98e6, 0.47, 0.53, 0.0, 0.0}, {195.0, 10.0e6, 0.55, 0.0, 0.45, 0.0},
                {245.0, 8.0e6, 0.0, 0.45, 0.0, 0.55}, {125.0, 3.0e6, 0.93, 0.0, 0.07, 0.0}, {125.0, 3.0e6, 0.94, 0.0, 0.06, 0.0},
                {125.0, 3.0e6, 0.95, 0.0, 0.05, 0.0}, {95.0, 2.0e6, 0.3, 0.0, 0.7, 0.0}, {150.0, 4.64e6, 0.67, 0.33, 0.0, 0.0},
                {150.0, 4.69e6, 0.67, 0.33, 0.0, 0.0}};
        for (int k = 0; k < states.length; k++) {
            double[] s = states[k];
            double[] amounts = z(cryo, "Nitrogen", s[2], "Methane", s[3], "Ethane", s[4], "CarbonDioxide", s[5]);
            d.tp(cryo, s[0], s[1], amounts, "N2/CH4/C2H6/CO2 " + s[2] + "/" + s[3] + "/" + s[4] + "/" + s[5], FLUID,
                    k == 1 || k == 5 || k == 12);
        }
        // The pure-fluid critical band: G3 evidence states inside, and a state outside.
        var nitrogen = cryo.engine.evaluator().component(index(cryo, "Nitrogen"));
        double tc = nitrogen.criticalTemperatureKelvin(), pc = nitrogen.criticalPressurePascal();
        double[] pureNitrogen = z(cryo, "Nitrogen", 1.0);
        d.tp(cryo, 131.19, 5.10e6, pureNitrogen, "N2", FLUID, false);
        d.tp(cryo, 121.19, 2.70e6, pureNitrogen, "N2", FLUID, false);
        d.tp(cryo, 1.12 * tc, 1.75 * pc, pureNitrogen, "N2", FLUID, false);

        // Crystals (P4, P5): CO2 in nitrogen over the grid of GasSolidEquilibriumTest (b), round trips at 10 %.
        double[][] grid = {{130.0, 1.0e5}, {150.0, 1.0e6}, {170.0, 5.0e5}, {190.0, 5.0e5}, {210.0, 5.0e6}};
        for (double y : new double[] {0.001, 0.01, 0.1, 0.5}) {
            for (double[] s : grid) d.tp(cryo, s[0], s[1], inNitrogen(cryo, y), "y_CO2 " + y + " in N2", CRYSTALS, y == 0.1);
        }
        // Complete deposition of pure CO2, a gas beside no crystal, CO2 in liquid methane, cold fuzz seed 183 (a crystal beside a
        // vapour and a liquid).
        double[] pureCo2 = z(cryo, "CarbonDioxide", 2.5);
        for (double[] s : new double[][] {{150.0, 1.0e5}, {200.0, 1.0e6}, {120.0, 5.0e6}, {230.0, 1.0e6}}) {
            d.tp(cryo, s[0], s[1], pureCo2, "CO2 2.5 mol", CRYSTALS, false);
        }
        d.tp(cryo, 130.0, 1.0e6, z(cryo, "Methane", 0.95, "CarbonDioxide", 0.05), "CH4/CO2 0.95/0.05", CRYSTALS, true);
        d.tp(cryo, 140.90028704290555, 619923.0283660815, z(cryo, "Nitrogen", 0.17685241566161913, "Methane", 54.73778258807344,
                "Ethane", 2.531240191239479, "CarbonDioxide", 2.0505124775177914), "seed 183 LNG", CRYSTALS, true);
        // Reversibility: a vapour cooled at fixed P and at fixed V past its frost point.
        double[] tenPercent = inNitrogen(cryo, 0.1);
        var vapour = d.tp(cryo, 200.0, 1.0e6, tenPercent, "y_CO2 0.1 in N2", CRYSTALS, false);
        if (Digest.answered(vapour)) {
            d.ph(cryo, "ph cooled by 3000 J at 1 MPa y_CO2 0.1", 1.0e6, vapour.enthalpy() - 3000.0, tenPercent, CRYSTALS);
            d.uv(cryo, "uv cooled by 3000 J at fixed V y_CO2 0.1", vapour.internalEnergy() - 3000.0, vapour.volume(), tenPercent, CRYSTALS);
        } else {
            d.skip(cryo, "ph cooled by 3000 J at 1 MPa y_CO2 0.1");
            d.skip(cryo, "uv cooled by 3000 J at fixed V y_CO2 0.1");
        }
        // D21: near-pure CO2 vessels (row A at its sublimation line, the general solve above the bound), PH across the
        // sublimation line at 1 atm, and the triple point's triangle with its typed hold.
        for (double f : new double[] {0.0, 1.0e-9, 1.0e-6, 1.0e-5, 2.0e-5}) {
            d.uv(cryo, "uv row A f " + f, -9.972099514183599e7, 1.0, nearPure(cryo, 249.1435, f), CRYSTALS);
        }
        for (double f : new double[] {0.0, 1.0e-7, 2.0e-6}) {
            double[] amounts = nearPure(cryo, 1.0, f);
            var start = d.tp(cryo, 200.0, ATM, amounts, "near-pure CO2 f " + f, CRYSTALS, false);
            for (double step : new double[] {-150.0, -1000.0, -12000.0}) {
                String label = "ph near-pure CO2 f " + f + " h(200 K) " + step + " J";
                if (Digest.answered(start)) d.ph(cryo, label, ATM, start.enthalpy() + step, amounts, CRYSTALS); else d.skip(cryo, label);
            }
        }
        double[] onePure = nearPure(cryo, 1.0, 0.0);
        var corners = d.uv(cryo, "uv triple-point corners CO2", -4.1337e5, 3.0e-4, onePure, CRYSTALS);
        boolean triangle = Digest.answered(corners) && corners.classification() == EquilibriumResult.Classification.VAPOR_LIQUID_SOLID;
        double[] u = new double[3], v = new double[3];
        if (triangle) {
            for (PhaseAmounts phase : corners.phases()) {
                int k = phase.kind() == PhaseKind.VAPOR ? 0 : phase.kind() == PhaseKind.LIQUID ? 1 : 2;
                u[k] = phase.state().molarInternalEnergy();
                v[k] = phase.state().molarVolume();
            }
        }
        double[] n = {2e-3, 3e-3, 0.995};
        double uTarget = n[0] * u[0] + n[1] * u[1] + n[2] * u[2];
        double vTarget = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
        for (double f : new double[] {1.0e-7, 1.0e-6}) {
            String label = "uv inside the triple point's triangle f " + f;
            if (triangle) d.uv(cryo, label, uTarget, vTarget, nearPure(cryo, 1.0, f), CRYSTALS); else d.skip(cryo, label);
        }
        // Onsets (P4, P5): the fluid answer's frost point and liquidus, the pure limit, the frost point as one vapour, the
        // liquidus as one liquid, the onset pressure.
        d.onset(cryo, "deposition-T", null, 1.0e5, inNitrogen(cryo, 0.01), "y_CO2 0.01 in N2");
        d.onset(cryo, "deposition-T", null, 1.0e6, tenPercent, "y_CO2 0.1 in N2");
        d.onset(cryo, "deposition-T", null, 5.0e6, inNitrogen(cryo, 0.5), "y_CO2 0.5 in N2");
        d.onset(cryo, "deposition-T", null, 1.0e5, z(cryo, "Nitrogen", 1.0e-6, "CarbonDioxide", 1.0 - 1.0e-6), "CO2 with 1e-6 N2");
        d.onset(cryo, "deposition-T", null, 1.0e5, z(cryo, "CarbonDioxide", 1.0), "CO2");
        d.onset(cryo, "frost-T", CO2_I, 1.0e6, tenPercent, "y_CO2 0.1 in N2");
        d.onset(cryo, "freezing-T", CO2_I, 1.0e6, z(cryo, "Methane", 0.99, "CarbonDioxide", 0.01), "CH4/CO2 0.99/0.01");
        d.onset(cryo, "deposition-P", null, 170.0, tenPercent, "y_CO2 0.1 in N2");
        d.onset(cryo, "deposition-P", null, 150.0, inNitrogen(cryo, 0.05), "y_CO2 0.05 in N2");
        // A crystal the contract qualifies without its evaluator here: NOT_IMPLEMENTED.
        Engine bare = new Engine("pilot_cryogenic/no-crystal-evaluator",
                new FluidTpEquilibrium(cryo.engine.contract(), cryo.engine.evaluator(), FluidTpEquilibrium.Settings.DEFAULT));
        d.tp(bare, 150.0, 1.0e6, inNitrogen(bare, 0.01), "y_CO2 0.01 in N2", CRYSTALS, false);
        d.ph(bare, "ph 1 MPa -1e4 J y_CO2 0.01 in N2 crystals", 1.0e6, -1.0e4, inNitrogen(bare, 0.01), CRYSTALS);
    }

    private static double[] inNitrogen(Engine e, double y) { return z(e, "Nitrogen", 1.0 - y, "CarbonDioxide", y); }

    /** {@code co2} mol of CO2 with nitrogen at fraction {@code f} of the feed (NearPureCrystalStepTest's feed). */
    private static double[] nearPure(Engine e, double co2, double f) {
        return z(e, "CarbonDioxide", co2, "Nitrogen", f / (1.0 - f) * co2);
    }

    /** The pilot package on its derived contract, as the fluid network builds it (NetworkPhaseEngine): carried crystals. */
    private static void pilotNetwork(Digest d) {
        Engine net = new Engine("pilot_cryogenic.derived",
                PackageBinding.derivedEquilibrium(PilotCryogenicTestCatalog.withPilot(), "icheme:pilot_cryogenic"));
        double[] lng = z(net, "Nitrogen", 0.01, "Methane", 0.94, "Ethane", 0.01, "CarbonDioxide", 0.04);
        double[] lng3 = z(net, "Nitrogen", 0.01, "Methane", 0.95, "Ethane", 0.01, "CarbonDioxide", 0.03);
        double[] letDown = z(net, "Methane", 0.6, "CarbonDioxide", 0.4);
        double[] gas = z(net, "Nitrogen", 0.02, "Methane", 0.97, "Ethane", 0.0086, "CarbonDioxide", 0.0014);
        double[] lean = z(net, "Nitrogen", 0.1, "Methane", 0.6, "Ethane", 0.1, "CarbonDioxide", 0.1);
        // The three shapes (D23): a dense fluid stays one liquid, a vapour keeps the stand-in, a methane-rich liquid holds the
        // CO2; two liquids with no crystal-rich phase; a vapour feed is never filtered; above the triple point nothing is.
        d.tp(net, 114.0, 1.0e6, lng, "LNG 4 % CO2", FLUID, false);
        d.tp(net, 114.0, 1.0e6, lng, "LNG 4 % CO2", CARRIED, false);
        d.tp(net, 112.0, 1.0e6, letDown, "CH4/CO2 0.6/0.4", CARRIED, false);
        d.tp(net, 91.0, 1.0e6, z(net, "Methane", 0.99, "CarbonDioxide", 0.01), "CH4/CO2 0.99/0.01", CARRIED, false);
        d.tp(net, 150.0, 5.0e5, letDown, "CH4/CO2 0.6/0.4", CARRIED, true);
        d.tp(net, 112.0, ATM, lng3, "LNG 3 % CO2", CARRIED, false);
        d.tp(net, 112.0, ATM, lng3, "LNG 3 % CO2", CRYSTALS, false);
        d.tp(net, 95.0, 2.0e6, z(net, "Nitrogen", 0.5, "Ethane", 0.45, "CarbonDioxide", 0.05), "N2/C2H6/CO2 0.5/0.45/0.05", CARRIED, false);
        d.tp(net, 134.0, 541000.0, gas, "let-down gas", FLUID, false);
        d.tp(net, 134.0, 541000.0, gas, "let-down gas", CARRIED, false);
        d.tp(net, 225.0, 3.0e6, z(net, "Methane", 0.5, "CarbonDioxide", 0.5), "CH4/CO2 0.5/0.5", CARRIED, false);
        // The three-fluid window below the triple point: refused fluid-only, one liquid beside the vapour carried.
        for (double t : new double[] {113.2, 120.0, 129.0}) {
            d.tp(net, t, ATM, lng3, "LNG 3 % CO2", FLUID, false);
            d.tp(net, t, ATM, lng3, "LNG 3 % CO2", CARRIED, false);
        }
        d.tp(net, 139.5, 541000.0, z(net, "Nitrogen", 0.02, "Methane", 0.95, "Ethane", 0.002, "CarbonDioxide", 0.028), "gas 2.8 % CO2",
                CARRIED, false);
        d.tp(net, 125.0, 1.0e5, lean, "N2/CH4/C2H6/CO2 0.1/0.6/0.1/0.1", FLUID, false);
        d.tp(net, 125.0, 1.0e5, lean, "N2/CH4/C2H6/CO2 0.1/0.6/0.1/0.1", CARRIED, false);
        // A junction's let-down through the window, and a vessel's crystal refresh, cold and warm-started.
        d.sweep(net, ATM, lng3, "LNG 3 % CO2", CARRIED, 105.0, 135.0, 4);
        double[] vessel = z(net, "Nitrogen", 1.0, "Methane", 95.0, "Ethane", 1.0, "CarbonDioxide", 3.0);
        d.uv(net, "uv vessel -1.5e6 J 0.04 m3 crystals", -1.5e6, 0.04, vessel, CRYSTALS);
        d.uvWarm(net, "uv vessel -1.5e6 J 0.04 m3 crystals from 114 K 800 kPa", -1.5e6, 0.04, vessel, CRYSTALS, 114.0, 8.0e5);
    }

    /** The pilot list {@code icheme:pilot_aqueous}: water in the cubic, the three-slot system, ice beside two liquids. */
    private static void pilotAqueous(Digest d) {
        Engine aq = new Engine("pilot_aqueous",
                PackageBinding.derivedEquilibrium(PilotAqueousTestCatalog.catalog(), PilotAqueousTestCatalog.PACKAGE_ID));
        // W3b's evaluation table, each with its round trip.
        d.tp(aq, 298.15, ATM, z(aq, "Methane", 0.5, "N-hexane", 0.5), "CH4/C6 0.5/0.5", FLUID, true);
        d.tp(aq, 298.15, ATM, z(aq, "Water", 0.5, "N-hexane", 0.5), "H2O/C6 0.5/0.5", FLUID, true);
        d.tp(aq, 353.15, ATM, z(aq, "Ethanol", 0.5, "Water", 0.5), "EtOH/H2O 0.5/0.5", FLUID, true);
        double[] ternary = z(aq, "Ethanol", 0.2, "Water", 0.4, "N-hexane", 0.4);
        d.tp(aq, 331.15, ATM, ternary, "EtOH/H2O/C6 0.2/0.4/0.4", FLUID, true);
        d.tp(aq, 329.94, ATM, ternary, "EtOH/H2O/C6 0.2/0.4/0.4", FLUID, true);
        d.tp(aq, 390.0, ATM, z(aq, "Water", 0.5, "crude_pc03", 0.5), "H2O/pc03 0.5/0.5", FLUID, true);
        // Gases dissolved, ethanol and water as one liquid and at a Perry point.
        d.tp(aq, 298.15, ATM, z(aq, "Water", 0.9, "CarbonDioxide", 0.1), "H2O/CO2 0.9/0.1", FLUID, false);
        d.tp(aq, 298.15, ATM, z(aq, "Ethanol", 0.9, "CarbonDioxide", 0.1), "EtOH/CO2 0.9/0.1", FLUID, false);
        d.tp(aq, 298.15, ATM, z(aq, "Ethanol", 0.3, "Water", 0.7), "EtOH/H2O 0.3/0.7", FLUID, false);
        d.tp(aq, 358.45, ATM, z(aq, "Ethanol", 0.1238, "Water", 1.0 - 0.1238), "EtOH/H2O Perry 0.1238", FLUID, false);
        d.tp(aq, 400.0, 5.0e6, z(aq, "Hydrogen", 0.2, "N-hexane", 0.8), "H2/C6 0.2/0.8", FLUID, true);
        d.tp(aq, 300.0, 1.0e6, z(aq, "Propane", 0.5, "Water", 0.5), "C3/H2O 0.5/0.5", FLUID, true);
        // Ice: out of 10 % ethanol, pure water across its melting point, beside dry ice, beside two liquids and three fluid phases.
        double xe = (10.0 / 46.069) / (10.0 / 46.069 + 90.0 / 18.015);
        d.tp(aq, 265.0, ATM, z(aq, "Ethanol", xe, "Water", 1.0 - xe), "EtOH/H2O 10 % by mass", CRYSTALS, false);
        double[] water = z(aq, "Water", 1.0);
        d.tp(aq, 272.0, ATM, water, "H2O", CRYSTALS, false);
        d.tp(aq, 274.0, ATM, water, "H2O", CRYSTALS, false);
        d.tp(aq, 170.0, ATM, z(aq, "Nitrogen", 0.8, "CarbonDioxide", 0.15, "Water", 0.05), "N2/CO2/H2O 0.8/0.15/0.05", CRYSTALS, true);
        d.tp(aq, 180.0, ATM, z(aq, "CarbonDioxide", 0.5, "Water", 0.5), "CO2/H2O 0.5/0.5", CRYSTALS, false);
        d.tp(aq, 268.15, ATM, z(aq, "Water", 0.3, "N-hexane", 0.4, "Nitrogen", 0.3), "H2O/C6/N2 0.3/0.4/0.3", CRYSTALS, true);
        double ethanol = 0.1 * 0.3 * 18.015 / 46.069 / 0.9;
        d.tp(aq, 262.0, ATM, z(aq, "Ethanol", ethanol, "Water", 0.3, "N-hexane", 0.4, "Nitrogen", 0.3), "EtOH/H2O/C6/N2 wet", CRYSTALS, true);
        d.tp(aq, 262.0, 5.0 * ATM, z(aq, "Ethanol", ethanol, "Water", 0.3, "N-hexane", 0.4), "EtOH/H2O/C6 liquids", CRYSTALS, true);
        d.tp(aq, 258.15, ATM, z(aq, "Ethanol", 0.05, "Water", 0.45, "N-hexane", 0.45, "Nitrogen", 0.05), "EtOH/H2O/C6/N2 0.05/0.45/0.45/0.05",
                CRYSTALS, false);
        // One-root phases with water at 40 MPa through the label flip; a narrow-boiling hydrocarbon binary.
        double[] dense = z(aq, "Water", 0.9, "Methane", 0.1);
        for (double t = 500.0; t <= 1000.0; t += 100.0) d.tp(aq, t, 40.0e6, dense, "H2O/CH4 0.9/0.1", FLUID, false);
        double[] narrow = z(aq, "N-hexane", 0.5, "Benzene", 0.5);
        d.tp(aq, 343.5, ATM, narrow, "C6/benzene 0.5/0.5", FLUID, true);
        d.tp(aq, 344.0, ATM, narrow, "C6/benzene 0.5/0.5", FLUID, true);
        // TP to PH and UV over one, two and three fluid phases (W3b step 1).
        Object[][] cases = {{ATM, 250.0, 400.0, new Object[] {"Methane", 0.5, "N-hexane", 0.5}, "CH4/C6 0.5/0.5"},
                {5.0e5, 300.0, 500.0, new Object[] {"Water", 0.5, "crude_pc03", 0.4, "Methane", 0.1}, "H2O/pc03/CH4 0.5/0.4/0.1"},
                {ATM, 300.0, 450.0, new Object[] {"Water", 0.5, "crude_pc03", 0.5}, "H2O/pc03 0.5/0.5"}};
        for (Object[] c : cases) {
            double p = (Double) c[0], low = (Double) c[1], high = (Double) c[2];
            double[] amounts = z(aq, (Object[]) c[3]);
            d.tp(aq, low, p, amounts, (String) c[4], FLUID, true);
            d.tp(aq, high, p, amounts, (String) c[4], FLUID, true);
        }
        // Steps from the balance: pure water boiling, water and n-hexane's three phases, pure water melting beside ice.
        d.sweep(aq, ATM, water, "H2O", FLUID, 372.0, 374.5, 3);
        d.sweep(aq, ATM, z(aq, "Water", 0.5, "N-hexane", 0.5), "H2O/C6 0.5/0.5", FLUID, 333.5, 335.0, 3);
        d.sweep(aq, ATM, water, "H2O", CRYSTALS, 272.5, 273.8, 3);
    }

    /** The bundled crude package {@code icheme:tjl20_methane_nitrogen} on its own contract (constant pairs, no water). */
    private static void bundledDry(Digest d) {
        Engine dry = new Engine("tjl20_methane_nitrogen",
                PackageBinding.equilibrium(MaterialCatalog.bundled(), "icheme:tjl20_methane_nitrogen"));
        double[] crude = crude(dry, 0.0);
        d.tp(dry, 350.0, 0.5e6, crude, "crude+N2", FLUID, true);
        d.tp(dry, 600.0, 2.0e6, crude, "crude+N2", FLUID, false);
        d.tp(dry, 900.0, 0.1e6, crude, "crude+N2", FLUID, true);
        d.tp(dry, 300.0, 5.0e6, crude, "crude+N2", FLUID, false);
        double[] gas = lightGas(dry);
        for (double t : new double[] {300.0, 600.0, 900.0}) {
            for (double p : new double[] {0.5e6, 2.0e6}) d.tp(dry, t, p, gas, "light gas", FLUID, t == 300.0 && p == 2.0e6);
        }
        double[] nitrogen = z(dry, "Nitrogen", 1.0);
        double t = 77.355;
        double saturation = kernelSaturation(dry, "Nitrogen", t, 90_000.0, 120_000.0);
        d.tp(dry, t, 90_000.0, nitrogen, "N2", FLUID, false);
        d.tp(dry, t, 120_000.0, nitrogen, "N2", FLUID, false);
        d.tp(dry, t, saturation, nitrogen, "N2 at the kernel's saturation pressure", FLUID, false);
        d.tp(dry, t, saturation * (1.0 + 2.0e-9), nitrogen, "N2 above the kernel's saturation pressure", FLUID, false);
        d.tp(dry, 150.0, 6.0e6, nitrogen, "N2", FLUID, true);
        d.tp(dry, 150.0, 12.0e6, nitrogen, "N2", FLUID, false);
        double[] methane = z(dry, "Methane", 1.0);
        d.tp(dry, 200.0, 1.0e6, methane, "CH4", FLUID, false);
        d.tp(dry, 350.0, 3.0e6, methane, "CH4", FLUID, true);
        d.tp(dry, 350.0, 12.0e6, methane, "CH4", FLUID, false);
        d.tp(dry, 150.0, 4.56e6, z(dry, "Methane", 0.33, "Nitrogen", 0.67), "x_N2 0.67", FLUID, false);
        d.tp(dry, 110.0, 0.5e6, z(dry, "Methane", 0.5, "Nitrogen", 0.5), "x_N2 0.5", FLUID, false);
        d.sweep(dry, 0.5e6, crude, "crude+N2", FLUID, 300.0, 700.0, 4);
        d.sweep(dry, 0.5e6, nitrogen, "N2", FLUID, 90.0, 100.0, 4);
        // Refusals: competitions the package is not qualified for, a species list off the basis, states outside the box.
        PhaseCompetition named = PhaseCompetition.withCrystals(CO2_I);
        d.tp(dry, 300.0, 1.0e6, methane, "CH4", named, false);
        d.tp(dry, 300.0, 1.0e6, methane, "CH4", CRYSTALS, false);
        d.tp(dry, 300.0, 1.0e6, methane, "CH4", CARRIED, false);
        d.ph(dry, "ph 1 MPa -1e4 J CH4 named crystal", 1.0e6, -1.0e4, methane, named);
        d.uv(dry, "uv -1e4 J 1e-3 m3 CH4 named crystal", -1.0e4, 1.0e-3, methane, named);
        d.reversed(dry, 300.0, 1.0e6);
        d.tp(dry, 5.0, 1.0e5, methane, "CH4 below the box", FLUID, false);
        d.ph(dry, "ph 2e9 Pa above the box CH4", 2.0e9, -1.0e4, methane, FLUID);
        d.ph(dry, "ph 1e5 Pa 1e9 J CH4", 1.0e5, 1.0e9, methane, FLUID);
        d.uv(dry, "uv 1e9 J 1e-3 m3 CH4", 1.0e9, 1.0e-3, methane, FLUID);
    }

    /** The same package as the fluid network reads it, water a compound of its cubic and ice its crystal (W3b). */
    private static void bundledWet(Digest d) {
        String id = "icheme:tjl20_methane_nitrogen";
        Engine wet = new Engine("tjl20_methane_nitrogen+water", PackageBinding.equilibrium(MaterialCatalog.bundled().withWater(id), id));
        double[] crude = crude(wet, 0.3);
        d.tp(wet, 350.0, 0.5e6, crude, "crude+N2+0.3 H2O", FLUID, true);
        d.tp(wet, 420.0, 2.0e6, crude, "crude+N2+0.3 H2O", FLUID, false);
        d.tp(wet, 300.0, 1.0e5, crude, "crude+N2+0.3 H2O", FLUID, false);
        double[] gas = lightGas(wet);
        gas[index(wet, "Water")] = 0.05;
        d.tp(wet, 330.0, 1.0e6, gas, "light gas+0.05 H2O", FLUID, true);
        d.tp(wet, 400.0, 1.0e6, gas, "light gas+0.05 H2O", FLUID, false);
        double[] water = z(wet, "Water", 1.0);
        d.tp(wet, 300.0, 1.0e5, water, "H2O", FLUID, false);
        d.tp(wet, 500.0, 1.0e5, water, "H2O", FLUID, false);
        d.tp(wet, 268.15, ATM, z(wet, "Water", 0.5, "Nitrogen", 0.5), "H2O/N2 0.5/0.5", CRYSTALS, true);
        d.tp(wet, 260.0, 1.0e6, z(wet, "Water", 0.3, "Methane", 0.7), "H2O/CH4 0.3/0.7", CARRIED, false);
        d.sweep(wet, ATM, water, "H2O", FLUID, 360.0, 390.0, 4);
    }

    /** The world's package {@code icheme:refinery} as the fluid network reads it: water in the cubic, CO2-I and ice. */
    private static void refineryWet(Digest d) {
        String id = "icheme:refinery";
        Engine ref = new Engine("refinery+water", PackageBinding.equilibrium(MaterialCatalog.bundled().withWater(id), id));
        double[] crude = crude(ref, 0.3);
        d.tp(ref, 350.0, 0.5e6, crude, "crude+N2+0.3 H2O", FLUID, true);
        d.tp(ref, 520.0, 1.0e6, crude, "crude+N2+0.3 H2O", FLUID, false);
        d.tp(ref, 300.0, 2.0e6, crude, "crude+N2+0.3 H2O", FLUID, false);
        double[] refineryGas = z(ref, "Hydrogen", 0.3, "Methane", 0.3, "Ethane", 0.1, "Propane", 0.05, "HydrogenSulfide", 0.05,
                "CarbonDioxide", 0.1, "Nitrogen", 0.1);
        d.tp(ref, 300.0, 3.0e6, refineryGas, "refinery gas", FLUID, true);
        d.tp(ref, 250.0, 3.0e6, refineryGas, "refinery gas", FLUID, false);
        d.tp(ref, 200.0, 3.0e6, refineryGas, "refinery gas", CRYSTALS, false);
        d.tp(ref, 320.0, 1.0e5, refineryGas, "refinery gas", FLUID, false);
        double[] aromatics = z(ref, "Benzene", 0.2, "Toluene", 0.2, "P-xylene", 0.1, "Water", 0.5);
        d.tp(ref, 350.0, ATM, aromatics, "BTX/H2O", FLUID, true);
        d.tp(ref, 300.0, ATM, aromatics, "BTX/H2O", FLUID, false);
        double[] ethanolWater = z(ref, "Ethanol", 0.3, "Water", 0.7);
        d.tp(ref, 298.15, ATM, ethanolWater, "EtOH/H2O 0.3/0.7", FLUID, true);
        d.tp(ref, 360.0, ATM, ethanolWater, "EtOH/H2O 0.3/0.7", FLUID, false);
        d.tp(ref, 330.0, 1.0e5, z(ref, "Ammonia", 0.2, "Water", 0.8), "NH3/H2O 0.2/0.8", FLUID, true);
        double[] sweetening = z(ref, "CarbonDioxide", 0.3, "Methane", 0.7);
        d.tp(ref, 200.0, 2.0e6, sweetening, "CO2/CH4 0.3/0.7", FLUID, false);
        d.tp(ref, 200.0, 2.0e6, sweetening, "CO2/CH4 0.3/0.7", CRYSTALS, true);
        d.tp(ref, 190.0, 1.0e5, z(ref, "CarbonDioxide", 1.0), "CO2", CRYSTALS, false);
        d.tp(ref, 260.0, ATM, z(ref, "Nitrogen", 0.9, "Water", 0.1), "N2/H2O 0.9/0.1", CRYSTALS, false);
        d.tp(ref, 170.0, ATM, z(ref, "Nitrogen", 0.8, "CarbonDioxide", 0.15, "Water", 0.05), "N2/CO2/H2O 0.8/0.15/0.05", CRYSTALS, false);
        d.tp(ref, 112.0, ATM, z(ref, "CarbonDioxide", 0.03, "Methane", 0.97), "CO2/CH4 0.03/0.97", CARRIED, false);
        d.tp(ref, 150.0, 5.0e5, z(ref, "CarbonDioxide", 0.4, "Methane", 0.6), "CO2/CH4 0.4/0.6", CARRIED, false);
        d.tp(ref, 220.0, 4.0e6, z(ref, "HydrogenSulfide", 0.2, "Methane", 0.8), "H2S/CH4 0.2/0.8", FLUID, false);
        d.tp(ref, 300.0, 5.0e6, z(ref, "Methane", 0.98, "Ethane", 0.02, "MethylMercaptan", 1.0e-6), "CH4/C2H6 with 1e-6 MeSH", FLUID, false);
        d.tp(ref, 250.0, 1.0e6, z(ref, "Ethylene", 0.5, "Propylene", 0.5), "C2H4/C3H6 0.5/0.5", FLUID, true);
        d.sweep(ref, 3.0e6, refineryGas, "refinery gas", FLUID, 200.0, 350.0, 4);
        d.sweep(ref, 0.5e6, crude, "crude+N2+0.3 H2O", FLUID, 300.0, 600.0, 3);
        double[] sour = z(ref, "CarbonDioxide", 0.05, "Methane", 0.95);
        d.onset(ref, "deposition-T", CO2_I, 1.0e6, sour, "CO2/CH4 0.05/0.95");
        d.onset(ref, "frost-T", CO2_I, 1.0e6, sour, "CO2/CH4 0.05/0.95");
        d.onset(ref, "deposition-P", CO2_I, 170.0, z(ref, "CarbonDioxide", 0.1, "Nitrogen", 0.9), "CO2/N2 0.1/0.9");
    }

    /** {@code icheme:refinery} on its own contract: no water, constant pairs, CO2-I on the fit ideal gas (E27). */
    private static void refineryDry(Digest d) {
        Engine ref = new Engine("refinery", PackageBinding.equilibrium(MaterialCatalog.bundled(), "icheme:refinery"));
        d.tp(ref, 350.0, 0.5e6, crude(ref, 0.0), "crude+N2", FLUID, true);
        d.tp(ref, 200.0, 2.0e6, z(ref, "CarbonDioxide", 0.3, "Methane", 0.7), "CO2/CH4 0.3/0.7", CRYSTALS, true);
        double[] co2 = z(ref, "CarbonDioxide", 1.0);
        d.tp(ref, 194.0, ATM, co2, "CO2", CRYSTALS, false);
        d.tp(ref, 200.0, ATM, co2, "CO2", CRYSTALS, false);
        d.tp(ref, 340.0, 1.0e5, z(ref, "Ethanol", 0.5, "Benzene", 0.5), "EtOH/benzene 0.5/0.5", FLUID, true);
        d.onset(ref, "deposition-T", null, 1.0e5, z(ref, "CarbonDioxide", 0.01, "Nitrogen", 0.99), "CO2/N2 0.01/0.99");
    }

    // The lines.

    /** The digest's lines in request order, the ids unique, and a tally of the answers by kind. */
    private static final class Digest {
        final List<String> lines = new ArrayList<>();
        final Map<String, Integer> tally = new TreeMap<>();
        private final Set<String> ids = new HashSet<>();

        static boolean answered(EquilibriumResult r) { return r != null && r.converged() && !r.phases().isEmpty(); }

        EquilibriumResult tp(Engine e, double t, double p, double[] z, String feed, PhaseCompetition c, boolean roundTrip) {
            return tpLabelled(e, "tp " + t + " K " + p + " Pa " + feed + " " + tag(c), t, p, z, c, roundTrip);
        }

        private EquilibriumResult tpLabelled(Engine e, String label, double t, double p, double[] z, PhaseCompetition c, boolean roundTrip) {
            var r = solve(e, label, z, () -> e.engine.tp(EquilibriumRequest.tp(t, p, e.basis(), z, c), e.workspace));
            if (roundTrip) {
                if (answered(r)) {
                    ph(e, "ph of " + label, p, r.enthalpy(), z, c);
                    uv(e, "uv of " + label, r.internalEnergy(), r.volume(), z, c);
                } else {
                    skip(e, "ph of " + label);
                    skip(e, "uv of " + label);
                }
            }
            return r;
        }

        EquilibriumResult ph(Engine e, String label, double p, double h, double[] z, PhaseCompetition c) {
            return solve(e, label, z, () -> e.engine.ph(EquilibriumRequest.ph(p, h, e.basis(), z, c), e.workspace));
        }

        EquilibriumResult uv(Engine e, String label, double u, double v, double[] z, PhaseCompetition c) {
            return solve(e, label, z, () -> e.engine.uv(EquilibriumRequest.uv(u, v, e.basis(), z, c), e.workspace));
        }

        EquilibriumResult uvWarm(Engine e, String label, double u, double v, double[] z, PhaseCompetition c, double hintT, double hintP) {
            return solve(e, label, z, () -> e.engine.uv(EquilibriumRequest.uv(u, v, e.basis(), z, c), e.workspace, hintT, hintP));
        }

        /** PH at {@code k/parts} of the way from H(low) to H(high) at {@code p}, k = 1 .. parts - 1, and UV of each answer. */
        void sweep(Engine e, double p, double[] z, String feed, PhaseCompetition c, double low, double high, int parts) {
            String label = "sweep " + p + " Pa " + feed + " " + tag(c) + " " + low + ".." + high + " K";
            var a = tpLabelled(e, label + " from tp " + low + " K", low, p, z, c, false);
            var b = tpLabelled(e, label + " to tp " + high + " K", high, p, z, c, false);
            for (int k = 1; k < parts; k++) {
                String step = label + " " + k + "/" + parts;
                if (!answered(a) || !answered(b)) {
                    skip(e, "ph " + step);
                    skip(e, "uv " + step);
                    continue;
                }
                double h = a.enthalpy() + (b.enthalpy() - a.enthalpy()) * k / parts;
                var r = ph(e, "ph " + step, p, h, z, c);
                if (answered(r)) uv(e, "uv " + step, r.internalEnergy(), r.volume(), z, c); else skip(e, "uv " + step);
            }
        }

        void onset(Engine e, String kind, String crystal, double fixed, double[] z, String feed) {
            String id = claim(e, "onset " + kind + " " + (crystal == null ? "any" : crystal) + " at " + fixed + " " + feed);
            DepositionOnset onset;
            try {
                onset = switch (kind) {
                    case "deposition-T" -> e.engine.depositionTemperature(crystal, fixed, e.basis(), z, e.workspace);
                    case "frost-T" -> e.engine.frostTemperature(crystal, fixed, e.basis(), z, e.workspace);
                    case "freezing-T" -> e.engine.freezingTemperature(crystal, fixed, e.basis(), z, e.workspace);
                    case "deposition-P" -> e.engine.depositionPressure(crystal, fixed, e.basis(), z, e.workspace);
                    default -> throw new AssertionError("Unknown onset " + kind);
                };
            } catch (RuntimeException refused) {
                add(id, "throws " + refused.getClass().getSimpleName());
                count("throws");
                return;
            }
            StringBuilder s = new StringBuilder("onset ").append(onset.status());
            if (onset.reason() != null) s.append(' ').append(onset.reason());
            if (!onset.converged()) s.append(' ').append(failureKind(onset.detail()));
            s.append(" crystal=").append(onset.crystal()).append(" T=").append(hex(onset.temperature()))
                    .append(" P=").append(hex(onset.pressure())).append(" approximation=").append(hex(onset.approximation()))
                    .append(" residual=").append(hex(onset.residual())).append(" fluids=").append(onset.fluidEquilibria())
                    .append(" grade=").append(onset.coverage().grade());
            if (onset.fluid() != null) s.append(SEPARATOR).append("fluid ").append(describe(onset.fluid(), z));
            add(id, s.toString());
            count("onset " + onset.status());
        }

        /** A TP request whose species list is the basis reversed: refused by an exception. */
        void reversed(Engine e, double t, double p) {
            List<String> species = new ArrayList<>(e.basis());
            Collections.reverse(species);
            double[] z = new double[species.size()];
            z[0] = 1.0;
            solve(e, "tp " + t + " K " + p + " Pa the basis reversed", z,
                    () -> e.engine.tp(EquilibriumRequest.tp(t, p, species, z, FLUID), e.workspace));
        }

        void skip(Engine e, String label) {
            add(claim(e, label), "skipped: no answer to start from");
            count("skipped");
        }

        private EquilibriumResult solve(Engine e, String label, double[] z, Supplier<EquilibriumResult> call) {
            String id = claim(e, label);
            EquilibriumResult r;
            try {
                r = call.get();
            } catch (RuntimeException refused) {
                add(id, "throws " + refused.getClass().getSimpleName());
                count("throws");
                return null;
            }
            add(id, describe(r, z));
            count(r.status() + " " + (r.converged() ? r.classification().toString() : failureKind(r.detail())));
            var d = r.diagnostics();
            if (d.coexistenceFromBalance()) count("balance");
            if (d.criticalBand()) count("band");
            if (d.newtonIterations() > 0) count("newton");
            if (d.outerIterations() > 0) count("outer");
            if (r.deposition() != null) count("deposition");
            return r;
        }

        private String claim(Engine e, String label) {
            String id = e.name + " " + label;
            if (id.contains(SEPARATOR)) throw new IllegalStateException("A digest id holds the separator: " + id);
            if (!ids.add(id)) throw new IllegalStateException("Duplicate digest request: " + id);
            return id;
        }

        private void add(String id, String answer) { lines.add(id + SEPARATOR + answer); }

        private void count(String key) { tally.merge(key, 1, Integer::sum); }
    }

    private static String tag(PhaseCompetition c) {
        if (c.equals(FLUID)) return "fluid";
        if (c.equals(CRYSTALS)) return "crystals";
        if (c.equals(CARRIED)) return "carried";
        return "competition " + String.join("+", c.crystals());
    }

    /**
     * The typed kind of a detail without an answer: its typed prefix ({@code CRITICAL_BAND} with the product failure it
     * holds), a refused evaluation, a state outside the box, else {@code UNTYPED}.
     */
    static String failureKind(String detail) {
        if (detail.startsWith(EquilibriumResult.CRITICAL_BAND)) {
            for (String inner : List.of(EquilibriumResult.UNSTABLE_PRODUCT, EquilibriumResult.UNRESOLVED_PRODUCT)) {
                if (detail.contains("): " + inner)) return EquilibriumResult.CRITICAL_BAND + "+" + inner;
            }
            return EquilibriumResult.CRITICAL_BAND;
        }
        for (String prefix : List.of(EquilibriumResult.UNSTABLE_PRODUCT, EquilibriumResult.UNRESOLVED_PRODUCT,
                EquilibriumResult.CRYSTAL_PHASES, EquilibriumResult.CRYSTAL_STEP)) {
            if (detail.startsWith(prefix)) return prefix;
        }
        if (detail.startsWith("evaluation refused")) return "REFUSED";
        if (detail.contains("outside the numerical box")) return "OUTSIDE_BOX";
        return "UNTYPED";
    }

    /** One answer as the digest's text: see the class comment. {@code z} names the request's species for {@code ln phi}. */
    static String describe(EquilibriumResult r, double[] z) {
        StringBuilder s = new StringBuilder();
        s.append(r.specification()).append(' ').append(r.status());
        if (r.reason() != null) s.append(' ').append(r.reason());
        if (r.converged()) {
            s.append(' ').append(r.classification());
            if (r.detail().contains("merged near-critical split")) s.append(" merged");
            if (r.detail().contains("near-pure (D21)")) s.append(" near-pure");
        } else {
            String kind = failureKind(r.detail());
            if (r.status() == Status.NOT_CONVERGED || !kind.equals("UNTYPED")) s.append(' ').append(kind);
        }
        s.append(" grade=").append(r.coverage().grade());
        var d = r.diagnostics();
        s.append(SEPARATOR).append("verdict=").append(d.feedVerdict())
                .append(" flash=").append(d.flashIterations())
                .append(" extrapolations=").append(d.extrapolations())
                .append(" rr=").append(d.rachfordRiceIterations())
                .append(" kernel=").append(d.kernelEvaluations())
                .append(" derivatives=").append(d.derivativeEvaluations())
                .append(" newton=").append(d.newtonIterations())
                .append(" outer=").append(d.outerIterations())
                .append(" band=").append(d.criticalBand() ? 1 : 0)
                .append(" balance=").append(d.coexistenceFromBalance() ? 1 : 0)
                .append(" tm=").append(hex(d.feedTangentPlaneDistance()))
                .append(" dlnK=").append(hex(d.lnKChange()))
                .append(" fugacity=").append(hex(d.fugacityResidual()))
                .append(" tmProducts=").append(hex(d.productTangentPlaneDistance()))
                .append(" tie=").append(hex(d.tieLine()))
                .append(" specification=").append(hex(d.specificationResidual()));
        for (PhaseAmounts phase : r.phases()) {
            s.append(SEPARATOR).append(phase.kind());
            if (phase.crystal() != null) s.append('/').append(phase.crystal());
            s.append(" T=").append(hex(phase.temperature())).append(" P=").append(hex(phase.pressure())).append(" n=");
            double[] amounts = phase.amountsView();
            boolean first = true;
            for (int i = 0; i < amounts.length; i++) {
                if (Double.doubleToRawLongBits(amounts[i]) == 0L) continue;
                s.append(first ? "" : ",").append(i).append(':').append(hex(amounts[i]));
                first = false;
            }
            state(s, phase.state(), z);
        }
        for (PhaseState coexisting : r.coexisting()) {
            s.append(SEPARATOR).append("coexisting ").append(coexisting.kind()).append(" T=").append(hex(coexisting.temperature()))
                    .append(" P=").append(hex(coexisting.pressure()));
            state(s, coexisting, z);
        }
        if (r.converged() && !r.phases().isEmpty()) {
            s.append(SEPARATOR).append("H=").append(hex(r.enthalpy())).append(" U=").append(hex(r.internalEnergy()))
                    .append(" V=").append(hex(r.volume())).append(" S=").append(hex(r.entropy()));
        }
        var deposition = r.deposition();
        if (deposition != null) {
            s.append(SEPARATOR).append("deposition ").append(deposition.crystal()).append(' ').append(deposition.component())
                    .append(" D=").append(hex(deposition.feedDrivingForce())).append(" residual=").append(hex(deposition.residual()))
                    .append(" solid=").append(hex(deposition.solidAmount())).append(" complete=").append(deposition.complete() ? 1 : 0)
                    .append(" iterations=").append(deposition.iterations()).append(" fluids=").append(deposition.fluidEquilibria());
        }
        return s.toString();
    }

    /** A phase state's root, its molar volume, enthalpy, entropy, Gibbs energy and water share, and a fluid's ln phi over the request's species. */
    private static void state(StringBuilder s, PhaseState state, double[] z) {
        s.append(" root=").append(state.root()).append('/').append(state.physicalRootCount())
                .append(" v=").append(hex(state.molarVolume())).append(" h=").append(hex(state.molarEnthalpy()))
                .append(" s=").append(hex(state.molarEntropy())).append(" g=").append(hex(state.molarGibbsEnergy()))
                .append(" share=").append(hex(state.shareEnthalpy())).append(',').append(hex(state.shareEntropy()))
                .append(',').append(hex(state.shareVolume()));
        if (state.kind() == PhaseKind.SOLID) return;
        s.append(" lnphi=");
        boolean first = true;
        for (int i = 0; i < z.length && i < state.componentCount(); i++) {
            if (!(z[i] > 0.0)) continue;
            s.append(first ? "" : ",").append(i).append(':').append(hex(state.logFugacityCoefficient(i)));
            first = false;
        }
    }

    /** A double's raw bits in hex; a NaN as {@code NaN}, whose payload is the platform's. */
    static String hex(double value) {
        return Double.isNaN(value) ? "NaN" : Long.toHexString(Double.doubleToRawLongBits(value));
    }
}
