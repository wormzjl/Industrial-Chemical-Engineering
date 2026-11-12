package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Water on the column's axis (batch 2026-11-11-aqueous-solvents, W5 D2, gates E1 to E3 of {@code W5_COLUMN_PLAN.md}):
 * an ethanol and water feed is an ordinary column of its list, on the derived kernel (water's pairs the Huron-Vidal ones),
 * with no steam rows, no wet tray and no condenser water regime.
 *
 * <p>The list is the pilot package {@code icheme:pilot_aqueous} restricted to water and ethanol (its ethanol names propane
 * as homomorph). Feed 100 mol/s of 0.10 ethanol, liquid at its bubble point at 1 atm; trays at 1 atm (no drop), the
 * condenser at 70 C, all liquid. The McCabe-Thiele construction runs on the same kernel's K values at 1 atm, on the
 * column's own internal flows (constant molar overflow from tray one's vapour: water's and ethanol's latent heats are
 * near).</p>
 */
class V3AqueousColumnTest {
    private static final String PILOT = PilotAqueousTestCatalog.PACKAGE_ID;
    private static final MaterialCatalog VIEW = PilotAqueousTestCatalog.catalog().restrict(PILOT, List.of("Water", "Ethanol"));
    private static final List<String> IDS = VIEW.requirePackage(PILOT).components();
    private static final int WATER = IDS.indexOf("Water"), ETHANOL = IDS.indexOf("Ethanol");
    private static final double ATM = 101_325.0;
    private static final PengRobinsonKernel KERNEL = DerivedPairs.derive(VIEW, PILOT).kernel(NumericalBox.MINIMUM_TEMPERATURE,
            NumericalBox.MAXIMUM_TEMPERATURE, NumericalBox.MINIMUM_PRESSURE, NumericalBox.MAXIMUM_PRESSURE);
    /** The model's azeotrope at 1 atm (W4, measured on the engine: 0.8965). */
    private static final double AZEOTROPE = azeotrope();

    private record Solved(V3ColumnOutcome.Success success, V3NeuralSeed profile) {
        V3ColumnStreamProperties stream(String id) {
            return success.result().streams().stream().filter(s -> s.streamId().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError(id + " missing from " + success.result().streams()));
        }

        double ethanol(String id) {
            return stream(id).moleFractions().stream().filter(f -> f.componentId().equals("Ethanol")).findFirst().orElseThrow().moleFraction();
        }
    }

    @Test
    void e1ARectifierFollowsMcCabeThieleOnTheKernelsOwnKValues() {
        double feedTemperature = bubble(0.10)[0];
        var solved = solve(input(10, 8, feedTemperature, 3, 1.77e6, List.of()));
        double xd = solved.ethanol("distillate_liquid"), d = solved.stream("distillate_liquid").molarFlowMolPerSecond();
        double xb = solved.ethanol("bottoms_liquid"), b = solved.stream("bottoms_liquid").molarFlowMolPerSecond();
        double vapour = sum(solved.profile().vapor()[1]);
        double mt = mcCabeThiele(10, 8, d, vapour);
        double top = solved.profile().temperatures()[1] - 273.15, bottom = solved.profile().temperatures()[11] - 273.15;
        System.out.printf(Locale.ROOT, "W5 E1: feed %.3f C; distillate %.4f ethanol at %.3f mol/s (McCabe-Thiele %.4f, tray-one vapour %.3f mol/s), "
                        + "top tray %.2f C; bottoms %.3f mol/s, %.5f ethanol, %.2f C; azeotrope %.4f; %d Newton iterations%n",
                feedTemperature - 273.15, xd, d, mt, vapour, top, b, xb, bottom, AZEOTROPE, solved.success().diagnostics().newtonIterations());
        assertTrue(solved.success().result().acceptanceAudit().accepted(), () -> solved.success().result().acceptanceAudit().checks().toString());
        assertEquals(mt, xd, 0.01, "the distillate against McCabe-Thiele on the same K values");
        assertEquals(10.7, d, 0.03 * 10.7, "the distillate rate: boil-up over the top vapour's 4.075 D");
        assertTrue(xd > 0.80 && xd < 0.86, "distillate " + xd);
        assertTrue(top > 78.0 && top < 78.8, "top tray " + top);
        assertTrue(xb < 0.02, "bottoms " + xb);
        assertAllLiquidCondenser(solved);
        assertCloses(solved);
    }

    @Test
    void e2ThirtyTraysAtRefluxTenStopShortOfTheAzeotrope() {
        double feedTemperature = bubble(0.10)[0];
        var solved = solve(input(30, 24, feedTemperature, 10, 1.77e6, List.of()));
        double xd = solved.ethanol("distillate_liquid");
        System.out.printf(Locale.ROOT, "W5 E2: distillate %.5f ethanol (azeotrope %.4f), top tray %.3f C, %d Newton iterations%n", xd, AZEOTROPE,
                solved.profile().temperatures()[1] - 273.15, solved.success().diagnostics().newtonIterations());
        assertTrue(solved.success().result().acceptanceAudit().accepted());
        assertTrue(xd >= 0.88 && xd <= AZEOTROPE + 1e-4, "distillate " + xd + ", azeotrope " + AZEOTROPE);
        assertAllLiquidCondenser(solved);
        assertCloses(solved);
    }

    @Test
    void e3AStripperTakesTheEthanolOutOfTheBottoms() {
        double feedTemperature = bubble(0.10)[0];
        var solved = solve(input(8, 1, feedTemperature, 0.1, 1.22e6, List.of()));
        double xb = solved.ethanol("bottoms_liquid");
        System.out.printf(Locale.ROOT, "W5 E3: bottoms %.3e ethanol at %.3f mol/s, distillate %.4f at %.3f mol/s, %d Newton iterations%n", xb,
                solved.stream("bottoms_liquid").molarFlowMolPerSecond(), solved.ethanol("distillate_liquid"),
                solved.stream("distillate_liquid").molarFlowMolPerSecond(), solved.success().diagnostics().newtonIterations());
        assertTrue(solved.success().result().acceptanceAudit().accepted());
        assertTrue(xb < 1e-3, "bottoms ethanol " + xb);
        assertCloses(solved);
    }

    @Test
    void aSteamRowIsRefusedWithWaterOnTheAxis() {
        var input = input(10, 8, bubble(0.10)[0], 3, 1.77e6, List.of(new V3SteamFeedSpec(11, 5.0, 420.0)));
        assertTrue(V3ColumnProblemResolver.waterOnAxis(input));
        var outcome = MaterialRuntime.with(VIEW, PILOT, () -> V3TestInputs.calculate(input));
        var failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome);
        assertEquals(V3SolverFailureCode.INVALID_INPUT, failure.code());
        assertEquals(V3ColumnProblemResolver.STEAM_WITH_WATER_ON_AXIS, failure.summary());
        var learned = MaterialRuntime.with(VIEW, PILOT, () -> V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0, 0,
                V3TestInputs.LEARNED, V3NeuralRegistry.bundled().bind(VIEW, input), V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        assertEquals(V3ColumnProblemResolver.STEAM_WITH_WATER_ON_AXIS, assertInstanceOf(V3ColumnOutcome.Failure.class, learned).summary(),
                "the learned route gives the same reason");
    }

    private static V3ColumnInput input(int trays, int feedTray, double feedTemperature, double reflux, double duty, List<V3SteamFeedSpec> steam) {
        double[] feed = new double[IDS.size()];
        feed[WATER] = 90;
        feed[ETHANOL] = 10;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, PILOT, "icheme:none", new V3ComponentBasis(IDS), feed, feedTemperature,
                trays, feedTray, ATM, 0.0, List.of(new V3ColumnSpecification.CondenserOutletTemperature(343.15),
                new V3ColumnSpecification.OrganicRefluxRatio(reflux), new V3ColumnSpecification.ReboilerDuty(duty)),
                List.of(), steam, List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private static Solved solve(V3ColumnInput input) {
        var profile = new AtomicReference<V3NeuralSeed>();
        var outcome = MaterialRuntime.with(VIEW, PILOT,
                () -> V3ColumnCalculator.calculateWithAcceptedProfile(input, V3SolveControl.UNBOUNDED, profile::set));
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        return new Solved(success, profile.get());
    }

    /** The condenser at 70 C reads all liquid: no vapour product, no free water (no water regime). */
    private static void assertAllLiquidCondenser(Solved solved) {
        for (var stream : solved.success().result().streams()) {
            assertTrue(!stream.streamId().equals("overhead_vapor") && !stream.streamId().equals("free_water"), stream.streamId());
        }
    }

    /** Each component and the energy close over the column. */
    private static void assertCloses(Solved solved) {
        var input = solved.success().result().problem().input();
        double[] out = new double[IDS.size()];
        for (var stream : solved.success().result().streams()) {
            for (var f : stream.moleFractions()) out[IDS.indexOf(f.componentId())] += f.moleFraction() * stream.molarFlowMolPerSecond();
        }
        for (int i = 0; i < out.length; i++) assertEquals(input.feedComponentMolarFlowsMolPerSecond()[i], out[i], 1e-7, IDS.get(i));
        for (String family : List.of("LOCAL_COMPONENT_BALANCE", "ENERGY_BALANCE", "GLOBAL_ENERGY_BALANCE", "CONDENSER_ENERGY_BALANCE")) {
            var check = solved.success().result().acceptanceAudit().checks().stream().filter(c -> c.family().equals(family)).findFirst();
            check.ifPresent(c -> assertTrue(c.passed(), family + ": " + c.detail()));
        }
    }

    /**
     * Distillate ethanol of a column of {@code trays} trays and a reboiler stage, feed liquid at its bubble point on tray
     * {@code feedTray}, a total condenser, at the column's distillate {@code d} and tray-one vapour {@code vapour}: the
     * distillate whose stepping down both operating lines lands on the bottoms the balance gives.
     */
    private static double mcCabeThiele(int trays, int feedTray, double d, double vapour) {
        double f = 100, z = 0.10, b = f - d, rectifying = vapour - d, stripping = rectifying + f;
        double low = z, high = AZEOTROPE - 1e-6;
        for (int k = 0; k < 60; k++) {
            double xd = (low + high) / 2, xb = (f * z - d * xd) / b, y = xd, x = 0;
            for (int stage = 1; stage <= trays + 1; stage++) {
                x = liquidOf(y);
                y = stage < feedTray ? (rectifying * x + d * xd) / vapour : (stripping * x - b * xb) / vapour;
            }
            // Too rich a distillate for these stages pinches near the top: the stepped bottoms stay richer than the balance's.
            if (x > xb) high = xd;
            else low = xd;
        }
        return (low + high) / 2;
    }

    /** The liquid whose bubble vapour is {@code y} at 1 atm, below the azeotrope. */
    private static double liquidOf(double y) {
        double low = 0, high = AZEOTROPE;
        if (y <= 0) return 0;
        for (int k = 0; k < 60; k++) {
            double mid = (low + high) / 2;
            if (bubble(mid)[1] < y) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    private static double azeotrope() {
        double low = 0.7, high = 0.995;
        for (int k = 0; k < 50; k++) {
            double mid = (low + high) / 2;
            if (bubble(mid)[1] > mid) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    /** The bubble point at 1 atm of a liquid of ethanol fraction {@code xe}: {temperature, vapour ethanol fraction}. */
    static double[] bubble(double xe) {
        double[] x = new double[IDS.size()];
        x[ETHANOL] = xe;
        x[WATER] = 1 - xe;
        var workspace = KERNEL.newWorkspace();
        var liquid = KERNEL.newEvaluation();
        var gas = KERNEL.newEvaluation();
        double low = 330, high = 375;
        double[] y = x.clone();
        for (int k = 0; k < 60; k++) {
            double t = (low + high) / 2, sum = 0;
            KERNEL.evaluate(t, ATM, x, PengRobinsonKernel.Root.LIQUID, workspace, liquid);
            for (int pass = 0; pass < 30; pass++) {
                KERNEL.evaluate(t, ATM, y, PengRobinsonKernel.Root.VAPOR, workspace, gas);
                sum = 0;
                double[] next = new double[x.length];
                for (int i = 0; i < x.length; i++) {
                    next[i] = x[i] * Math.exp(liquid.logFugacityCoefficient(i) - gas.logFugacityCoefficient(i));
                    sum += next[i];
                }
                for (int i = 0; i < x.length; i++) y[i] = next[i] / sum;
            }
            if (sum > 1) high = t;
            else low = t;
        }
        return new double[] {(low + high) / 2, y[ETHANOL]};
    }

    private static double sum(double[] values) {
        double s = 0;
        for (double v : values) s += v;
        return s;
    }
}
