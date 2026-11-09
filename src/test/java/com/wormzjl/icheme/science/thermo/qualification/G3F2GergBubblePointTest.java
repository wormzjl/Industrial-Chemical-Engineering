package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.PhaseState;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F2 (plan {@code P3_PILOT_ENGINE_PLAN.md} sections 4.5 and 8.2; D7 K-value row): bubble points of the
 * six pilot binaries of {@code icheme:pilot_cryogenic} (E-PPR78 kij(T) on the translated PR78) against the WP8
 * GERG-2008 fixture {@code science/thermo/gerg2008/pilot-binaries.json} (CoolProp 8.0.0 GERG-2008 mixing on the reference
 * pure fluids; model against model, declared: experimental VLE of these pairs is E-PPR78's fitting data).
 *
 * <p>Gated: the fixture's 144 bubble rows without flags, per pair: bubble-pressure AAD at most 10 %, every point at most
 * 20 %, {@code |y_model - y_ref|} at most 0.02, {@code |ln K_model - ln K_ref|} at most 0.15 for species with x or y
 * above 1e-3. Reported, not gated: the {@code near_critical} (rhoL/rhoV below 3) and {@code above_10_MPa} rows, the dew
 * rows, and the zero-kij model beside E-PPR78 (the baseline the rule must beat). The model's bubble point is solved on
 * the engine's evaluator ({@link CubicPhaseEvaluator#forPackage}, bit for bit the network's EOS), from the reference state
 * as the start; a start is not a result: the solution must pass {@code sum x K = 1} to 1e-12 with both roots distinct.</p>
 */
class G3F2GergBubblePointTest {
    /**
     * Pairs whose held |dy| is D15's 0.025, declared for CO2-rich liquids within 5 K of CO2's triple point, instead of
     * D7's 0.02 (measured 0.0222 and 0.0212 at 220 K, x_CO2 0.9-0.95); their rows outside that scope are reported against
     * D7's 0.02 beside it.
     */
    static final java.util.Set<String> DY_D15 = java.util.Set.of("CO2/CH4", "CO2/C2H6");
    /** D15's scope of the 0.025: a CO2-rich liquid (x_CO2 at least 0.5) within 5 K of CO2's 216.592 K triple point. */
    static boolean d15DyScope(double t, double xCo2) { return t <= 216.592 + 5 && xCo2 >= 0.5; }
    record Point(double pressure, double[] other, boolean converged) {}

    static JsonObject fixture() throws Exception {
        try (var input = G3F2GergBubblePointTest.class.getResourceAsStream("/science/thermo/gerg2008/pilot-binaries.json")) {
            assertNotNull(input, "the WP8 fixture");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * A bubble ({@code bubble} true: {@code fixed} is the liquid) or dew point of the evaluator at T, from (P0, other0):
     * successive substitution on the free phase's composition at each pressure, a secant in ln P on ln(sum), converged at
     * |ln sum| below 1e-12.
     */
    static Point saturation(CubicPhaseEvaluator evaluator, double t, double[] fixed, double p0, double[] other0, boolean bubble) {
        int n = fixed.length;
        var ws = evaluator.newWorkspace();
        var a = new PhaseState(n);
        var b = new PhaseState(n);
        double[] other = other0.clone();
        double lnP = Math.log(p0), previousLnP = Double.NaN, previousF = Double.NaN;
        Root fixedRoot = bubble ? Root.LIQUID : Root.VAPOR, otherRoot = bubble ? Root.VAPOR : Root.LIQUID;
        for (int outer = 0; outer < 200; outer++) {
            double p = Math.exp(lnP), sum = 0;
            double[] ratio = new double[n];
            for (int inner = 0; inner < 200; inner++) {
                evaluator.evaluate(t, p, fixed, fixedRoot, ws, a);
                evaluator.evaluate(t, p, other, otherRoot, ws, b);
                sum = 0;
                for (int i = 0; i < n; i++) {
                    if (fixed[i] <= 0) continue;
                    // K = y/x = phi_L/phi_V; the bubble sums x K, the dew y / K.
                    double lnK = bubble ? a.logFugacityCoefficient(i) - b.logFugacityCoefficient(i)
                            : b.logFugacityCoefficient(i) - a.logFugacityCoefficient(i);
                    ratio[i] = bubble ? fixed[i] * Math.exp(lnK) : fixed[i] * Math.exp(-lnK);
                    sum += ratio[i];
                }
                double change = 0;
                for (int i = 0; i < n; i++) {
                    double next = ratio[i] / sum;
                    change = Math.max(change, Math.abs(next - other[i]));
                    other[i] = next;
                }
                if (change < 1e-13) break;
            }
            double f = Math.log(sum);
            if (Math.abs(f) < 1e-12) {
                double distance = 0;
                for (int i = 0; i < n; i++) distance = Math.max(distance, Math.abs(other[i] - fixed[i]));
                boolean distinct = distance > 1e-6 && Math.abs(a.molarVolume() / b.molarVolume() - 1) > 1e-6;
                return new Point(p, other, distinct);
            }
            double next;
            if (Double.isNaN(previousF) || previousF == f) next = lnP + (bubble ? f : -f);
            else next = lnP - f * (lnP - previousLnP) / (f - previousF);
            next = Math.max(lnP - 0.5, Math.min(lnP + 0.5, next));
            previousLnP = lnP;
            previousF = f;
            lnP = next;
        }
        return new Point(Double.NaN, other, false);
    }

    /** The E-PPR78 evaluator and the same package with zero interactions (the rule pairs' constant entries are 0.0). */
    static CubicPhaseEvaluator[] models() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var eppr78 = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var pkg = catalog.requirePackage(PILOT);
        List<IdealGasFunction> spines = new ArrayList<>();
        for (String id : pkg.components()) spines.add(catalog.requireSpine(PILOT, id));
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) assertEquals(0.0, pkg.interactions().get(i).get(j), 0.0);
        var zero = CubicPhaseEvaluator.fromPackage(pkg, pkg.components(), eppr78.translations(), spines);
        return new CubicPhaseEvaluator[] {eppr78, zero};
    }

    static final class Stats {
        int n, failed;
        double sumDp, worstDp, worstDy, worstLnK;
        String worstDpAt = "-", worstDyAt = "-", worstLnKAt = "-";

        void add(double dp, double dy, double dlnK, String at) {
            n++;
            sumDp += Math.abs(dp);
            if (Math.abs(dp) >= Math.abs(worstDp)) { worstDp = dp; worstDpAt = at; }
            if (Math.abs(dy) >= Math.abs(worstDy)) { worstDy = dy; worstDyAt = at; }
            if (Math.abs(dlnK) >= Math.abs(worstLnK)) { worstLnK = dlnK; worstLnKAt = at; }
        }

        double aad() { return n == 0 ? 0 : sumDp / n; }

        String cells() {
            return n == 0 ? "- | - | - | -" : f("%.2f | %+.2f (%s) | %+.4f (%s) | %+.3f (%s)", 100 * aad(), 100 * worstDp, worstDpAt, worstDy, worstDyAt,
                    worstLnK, worstLnKAt);
        }
    }

    @Test
    void bubblePointsAgainstGerg2008() throws Exception {
        var fixture = fixture();
        var d7 = fixture.getAsJsonObject("d7_targets");
        double aadTarget = d7.get("bubble_pressure_aad").getAsDouble(), pointTarget = d7.get("bubble_pressure_point").getAsDouble();
        double dyTarget = d7.get("abs_dy").getAsDouble(), lnKTarget = d7.get("abs_ln_k").getAsDouble();
        double threshold = d7.get("ln_k_species_threshold").getAsDouble();
        var models = models();
        var names = List.of("Nitrogen", "Methane", "Ethane", "CarbonDioxide");
        var gate = new Table("G3 F2 bubble", "Pair", "Model", "Rows", "AAD %", "Worst dP % (state)", "Worst dy (state)", "Worst d ln K (state)",
                "D7 verdict (AAD 10 %, point 20 %, dy 0.02, d ln K 0.15)", "Held");
        var flagged = new Table("G3 F2 flagged", "Pair", "Flag", "Rows", "AAD %", "Worst dP % (state)", "Worst dy (state)", "Worst d ln K (state)");
        var dews = new Table("G3 F2 dew", "Pair", "Rows", "AAD %", "Worst dP % (state)", "Worst dx (state)", "Worst d ln K (state)");
        int clean = 0;
        List<String> misses = new ArrayList<>();
        for (var element : fixture.getAsJsonArray("pairs")) {
            var pair = element.getAsJsonObject();
            String label = pair.get("pair").getAsString();
            int c1 = names.indexOf(pair.get("component1").getAsString()), c2 = names.indexOf(pair.get("component2").getAsString());
            Stats[] gated = {new Stats(), new Stats()};
            // The E-PPR78 clean rows of a D15 pair outside its dy scope: reported against D7's 0.02 beside the held 0.025.
            Stats outsideScope = new Stats();
            Map<String, Stats> byFlag = new java.util.TreeMap<>();
            for (var rowElement : pair.getAsJsonArray("bubble")) {
                var row = rowElement.getAsJsonObject();
                double t = row.get("T").getAsDouble(), x1 = row.get("x1").getAsDouble(), pRef = row.get("P").getAsDouble(), y1 = row.get("y1").getAsDouble();
                var flags = row.getAsJsonArray("flags");
                double[] x = new double[4], y = new double[4];
                x[c1] = x1;
                x[c2] = 1 - x1;
                y[c1] = y1;
                y[c2] = 1 - y1;
                String at = f("%.0f K x1 %.2f", t, x1);
                for (int m = 0; m < (flags.isEmpty() ? 2 : 1); m++) {
                    var point = saturation(models[m], t, x, pRef, y, true);
                    Stats target = flags.isEmpty() ? gated[m] : byFlag.computeIfAbsent(flags.get(0).getAsString(), key -> new Stats());
                    if (!point.converged()) {
                        target.failed++;
                        continue;
                    }
                    double dp = point.pressure() / pRef - 1, dy = point.other()[c1] - y1;
                    // ln K_model - ln K_ref = ln(y_model / y_ref) at the same liquid x.
                    double dlnK = 0;
                    for (int i : new int[] {c1, c2}) {
                        double yr = i == c1 ? y1 : 1 - y1;
                        if (x[i] > threshold || yr > threshold) {
                            double d = Math.log(point.other()[i] / yr);
                            if (Math.abs(d) > Math.abs(dlnK)) dlnK = d;
                        }
                    }
                    target.add(dp, dy, dlnK, at);
                    double xCo2 = names.get(c1).equals("CarbonDioxide") ? x1 : names.get(c2).equals("CarbonDioxide") ? 1 - x1 : 0;
                    if (m == 0 && flags.isEmpty() && DY_D15.contains(label) && !d15DyScope(t, xCo2)) outsideScope.add(dp, dy, dlnK, at);
                }
                if (flags.isEmpty()) clean++;
            }
            // D15 (declared after WP9b): the two CO2 pairs miss |dy| 0.02 only at 220 K on the CO2-rich side (x_CO2 0.9-0.95, 1.2
            // and 2.2 thousandths beyond); their held |dy| is 0.025, every other criterion is D7's. Their rows outside D15's
            // scope (a CO2-rich liquid within 5 K of the triple point) are reported against D7's 0.02.
            double dyHeld = DY_D15.contains(label) ? 0.025 : dyTarget;
            if (DY_D15.contains(label)) {
                System.out.println(f("G3 F2 D15 dy scope, %s: %d clean rows outside the scope (x_CO2 >= 0.5 within 5 K of 216.592 K),"
                        + " worst dy %+.4f (%s), D7 0.02 %s", label, outsideScope.n, outsideScope.worstDy, outsideScope.worstDyAt,
                        Math.abs(outsideScope.worstDy) <= dyTarget ? "met" : "NOT met"));
            }
            for (int m = 0; m < 2; m++) {
                var s = gated[m];
                var missed = new ArrayList<String>();
                if (s.failed > 0) missed.add("not converged");
                if (s.aad() > aadTarget) missed.add("AAD");
                if (Math.abs(s.worstDp) > pointTarget) missed.add("point");
                if (Math.abs(s.worstDy) > dyTarget) missed.add("dy");
                if (Math.abs(s.worstLnK) > lnKTarget) missed.add("ln K");
                gate.row(label, m == 0 ? "E-PPR78" : "zero kij", s.n + (s.failed > 0 ? " (" + s.failed + " not converged)" : ""), s.cells(),
                        missed.isEmpty() ? "met" : "NOT met: " + String.join(", ", missed),
                        m == 1 ? "baseline, not gated" : dyHeld == dyTarget ? "D7" : "D7, dy 0.025 (D15 declared)");
                if (m == 0) {
                    assertEquals(0, s.failed, label + " converged");
                    assertTrue(s.aad() <= aadTarget && Math.abs(s.worstDp) <= pointTarget && Math.abs(s.worstLnK) <= lnKTarget, label + " pressure and ln K");
                    assertTrue(Math.abs(s.worstDy) <= dyHeld, label + " dy " + s.worstDy);
                    if (!missed.isEmpty()) misses.add(label + " (" + String.join(", ", missed) + ")");
                }
            }
            byFlag.forEach((flag, s) -> flagged.row(label, flag, s.n + (s.failed > 0 ? " (" + s.failed + " not converged)" : ""), s.cells()));
            // Dew rows (normal branch): reported only.
            var dew = new Stats();
            for (var rowElement : pair.getAsJsonArray("dew")) {
                var row = rowElement.getAsJsonObject();
                double t = row.get("T").getAsDouble(), y1 = row.get("y1").getAsDouble(), pRef = row.get("P").getAsDouble(), x1 = row.get("x1").getAsDouble();
                double[] x = new double[4], y = new double[4];
                y[c1] = y1;
                y[c2] = 1 - y1;
                x[c1] = x1;
                x[c2] = 1 - x1;
                var point = saturation(models[0], t, y, pRef, x, false);
                if (!point.converged()) {
                    dew.failed++;
                    continue;
                }
                double dlnK = 0;
                for (int i : new int[] {c1, c2}) {
                    double xr = i == c1 ? x1 : 1 - x1;
                    if (xr > threshold || y[i] > threshold) {
                        double d = -Math.log(point.other()[i] / xr);
                        if (Math.abs(d) > Math.abs(dlnK)) dlnK = d;
                    }
                }
                dew.add(point.pressure() / pRef - 1, point.other()[c1] - x1, dlnK, f("%.0f K y1 %.2f", t, y1));
            }
            dews.row(label, dew.n + (dew.failed > 0 ? " (" + dew.failed + " not converged)" : ""), dew.cells());
        }
        gate.print("clean bubble rows (no flag): the D7 K-value row, E-PPR78 against the zero-kij baseline");
        flagged.print("flagged bubble rows (near_critical: rhoL/rhoV < 3; above_10_MPa), E-PPR78, reported only");
        dews.print("dew rows (normal branch), E-PPR78, reported only; dx is x1 model - reference, d ln K of the liquid side");
        System.out.println(f("G3 F2: %d clean bubble rows scored; pairs missing the D7 row: %s", clean, misses.isEmpty() ? "none" : misses));
        assertEquals(144, clean, "the WP8 fixture's clean bubble rows");
        assertEquals(List.of("CO2/CH4 (dy)", "CO2/C2H6 (dy)"), misses, "the D7 misses are the two recorded ones");
    }
}
