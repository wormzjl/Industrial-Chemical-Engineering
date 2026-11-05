package com.wormzjl.icheme.science.thermo.qualification;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.DepositionOnset;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared data and scoring of the G5 families (P5, batch 2026-09-24-coolprop-low-temperature,
 * {@code P5_SOLID_LIQUID_EQUILIBRIUM.md}): the CO2-I crystal of the pilot package beside a liquid. The holdouts are the
 * owner-provided and open transcriptions of {@code P4_P5_HOLDOUT_DATA_SURVEY.md}, copied to
 * {@code src/test/resources/science/thermo/solid-co2-holdouts/} with their citation headers. Every point is scored as a
 * temperature at its printed composition and pressure: a liquid's by {@link FluidTpEquilibrium#freezingTemperature} (its
 * liquidus), a vapour's by {@link FluidTpEquilibrium#depositionTemperature} (its frost point). D17's rule: a dataset's MAD
 * against the P0 target (2 K for the liquidus), per-dataset MAD and per-point caps at what is measured with a margin, holds
 * and out-of-domain points counted apart. Every scoring function takes the service, so the fusion-enthalpy comparison
 * (D16 revisit, the stage document's section 3) scores the same points on a service whose crystal record carries another
 * anchor value.
 */
final class G5Support {
    static final String PILOT = "icheme:pilot_cryogenic";
    static final FluidTpEquilibrium SERVICE = FluidTpEquilibrium.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
    /** The pilot basis: N2, CH4, C2H6, CO2, water. */
    static final List<String> BASIS = SERVICE.contract().components();
    static final int CO2 = 3;
    static final double R = 8.31446261815324;

    private G5Support() {}

    /** One measured point: a liquid (freezing) or a vapour (frost) of composition {@code z} over the basis at {@code p}, measured at {@code t}. */
    record Point(String dataset, String group, double t, double p, double[] z, boolean vapour, String note) {
        Point {
            double sum = 0;
            for (double x : z) {
                if (!(x >= 0.0)) throw new IllegalArgumentException(dataset + " " + t + " K: negative fraction");
                sum += x;
            }
            if (Math.abs(sum - 1.0) > 1e-9) throw new IllegalArgumentException(dataset + " " + t + " K: fractions sum to " + sum);
        }
        double co2() { return z[CO2]; }
    }

    static double[] z(double n2, double ch4, double c2h6, double co2) { return new double[] {n2, ch4, c2h6, co2, 0.0}; }

    static double number(String cell) { return cell.isBlank() || cell.equals("-") ? Double.NaN : Double.parseDouble(cell); }

    // Data.

    /**
     * Shen et al. 2012 (NIST TRC ThermoML): the liquid's CO2 mole fraction and pressure on the solid-liquid-vapour locus,
     * paired by temperature and solvent. Sets 1/2 CH4 + CO2; 3/4 with a CH4 + N2 solvent; 5/6 with a CH4 + C2H6 solvent
     * (the solvent's CH4 fraction on a CO2-free basis).
     */
    static List<Point> shen() {
        Map<String, Double> pressure = new LinkedHashMap<>();
        Map<String, Double> fraction = new LinkedHashMap<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows("shen-2012-jced-57-2296.tsv")) {
            int set = Integer.parseInt(c[0]);
            String key = (set + 1) / 2 + "|" + c[7] + "|" + (c.length > 8 ? c[8] : "");
            if (set % 2 == 1) pressure.put(key, number(c[5]) * 1e3); else fraction.put(key, number(c[5]));
        }
        List<Point> points = new ArrayList<>();
        for (var e : pressure.entrySet()) {
            String[] k = e.getKey().split("\\|", -1);
            int pair = Integer.parseInt(k[0]);
            double t = Double.parseDouble(k[1]);
            double x = fraction.get(e.getKey());
            double solvent = k[2].isBlank() ? 1.0 : Double.parseDouble(k[2]);
            double methane = (1 - x) * solvent, other = (1 - x) - methane;
            String group = pair == 1 ? "CH4" : pair == 2 ? "CH4 + N2 (" + k[2] + " CH4)" : "CH4 + C2H6 (" + k[2] + " CH4)";
            double[] zz = pair == 2 ? z(other, methane, 0, x) : pair == 3 ? z(0, methane, other, x) : z(0, 1 - x, 0, x);
            points.add(new Point("Shen 2012", group, t, e.getValue(), zz, false, "x_CO2 " + x));
        }
        return points;
    }

    /** Davis, Rodewald, Kurata 1962 Table 1: the CH4 + CO2 solid-liquid-vapour locus (T K, P Pa), 31 points. */
    static double[][] davisLine() {
        List<double[]> line = new ArrayList<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows("davis-rodewald-kurata-1962-aiche-8-537.tsv")) {
            if (c[0].equals("SLV_PT")) line.add(new double[] {number(c[2]), number(c[7]) * 1e6});
        }
        line.sort((a, b) -> Double.compare(a[0], b[0]));
        return line.toArray(double[][]::new);
    }

    /** Davis's locus pressure at {@code t}: log-linear between the neighbouring Table 1 points. */
    static double davisPressure(double t) {
        double[][] line = davisLine();
        for (int k = 1; k < line.length; k++) {
            if (t <= line[k][0] || k == line.length - 1) {
                double w = (t - line[k - 1][0]) / (line[k][0] - line[k - 1][0]);
                return Math.exp(Math.log(line[k - 1][1]) + w * (Math.log(line[k][1]) - Math.log(line[k - 1][1])));
            }
        }
        throw new IllegalStateException();
    }

    /** Davis Table 3 (the vapour's CO2 fraction on the locus, T K and y) or Table 4 (the crystal points of liquids, T K and x). */
    static double[][] davisCompositions(boolean vapour) {
        List<double[]> rows = new ArrayList<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows("davis-rodewald-kurata-1962-aiche-8-537.tsv")) {
            if (c[0].equals(vapour ? "SLV_Y" : "SLV_X")) rows.add(new double[] {number(c[2]), number(c[3]) / 100});
        }
        return rows.toArray(double[][]::new);
    }

    /** Davis Table 4 as liquidus points: a liquid of the printed composition at the locus pressure of its crystal point. */
    static List<Point> davisCrystalPoints() {
        List<Point> points = new ArrayList<>();
        for (double[] r : davisCompositions(false)) {
            points.add(new Point("Davis 1962", "CH4 (Table 4)", r[0], davisPressure(r[0]), z(0, 1 - r[1], 0, r[1]), false, "x_CO2 " + r[1]));
        }
        return points;
    }

    /** Souza et al. 2020 (ThermoML set 4): the CO2 + CH4 three-phase line near the CO2 triple point (T K, P Pa). */
    static double[][] souzaLine() { return G4F1CarbonDioxideFrostPointHoldoutTest.souzaLine(); }

    /**
     * Campestrini et al. 2022 at about 4 MPa, 0, 5 and 10 % N2 in the solvent: SLE (a liquid beside the crystal) and SVE
     * (a vapour) rows as printed; the SLVE rows of Table 12 (their liquid and their vapour, N2 by closure). The table and
     * the phase name the group.
     */
    static List<Point> campestrini() {
        List<Point> points = new ArrayList<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows("campestrini-2022-fpe-553-113292.tsv")) {
            double t = number(c[3]), p = number(c[4]) * 1e6, ch4 = number(c[8]), co2 = number(c[10]);
            double n2 = Double.isNaN(number(c[6])) ? Math.max(0.0, 1 - ch4 - co2) : number(c[6]);
            double sum = n2 + ch4 + co2;
            boolean vapour = c[5].equals("vapor");
            String solvent = c[0].equals("4") || c[0].equals("6") ? "0 % N2" : c[0].equals("7") || c[0].equals("8") ? "5 % N2"
                    : c[0].equals("9") || c[0].equals("10") ? "10 % N2" : "5 or 10 % N2";
            points.add(new Point("Campestrini 2022", c[1] + " " + solvent + (c[1].equals("SLVE") ? " " + c[5] : ""), t, p,
                    z(n2 / sum, ch4 / sum, 0, co2 / sum), vapour, "point " + c[2]));
        }
        return points;
    }

    /** Riva and Stringari 2018 Table 4, series 1 and 2 (N2 + CH4 + CO2; series 3 and 4 carry O2, absent from the pilot): liquid and vapour. */
    static List<Point> rivaStringari() {
        List<Point> points = new ArrayList<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows("stringari-riva-2018-iecr-57-4124-table4.tsv")) {
            if (!c[2 + 1].equals("-")) continue; // O2 present
            double t = number(c[1]), p = number(c[2]) * 1e6;
            double x3 = number(c[5]), x4 = number(c[7]) * 1e-6, y3 = number(c[11]), y4 = number(c[13]) * 1e-6;
            points.add(new Point("Riva-Stringari 2018", "N2 series liquid", t, p, z(1 - x3 - x4, x3, 0, x4), false, "N " + c[0]));
            points.add(new Point("Riva-Stringari 2018", "N2 series vapour", t, p, z(1 - y3 - y4, y3, 0, y4), true, "N " + c[0]));
        }
        return points;
    }

    // Scoring.

    /** Accumulated scores of one group. */
    static final class Score {
        final List<Double> deviations = new ArrayList<>();
        final List<String> holds = new ArrayList<>();
        /** Answers outside the package's data ranges, graded extrapolated (owner rule of 2026-10-16; out of domain before):
         * reported apart, not scored. */
        final List<String> extrapolated = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        double mad() { return deviations.stream().mapToDouble(Math::abs).average().orElse(Double.NaN); }
        double bias() { return deviations.stream().mapToDouble(d -> d).average().orElse(Double.NaN); }
        double max() { return deviations.stream().mapToDouble(Math::abs).max().orElse(Double.NaN); }
        void add(Score other) {
            deviations.addAll(other.deviations);
            holds.addAll(other.holds);
            extrapolated.addAll(other.extrapolated);
            failures.addAll(other.failures);
        }
    }

    static DepositionOnset onset(FluidTpEquilibrium service, Point point, FluidTpEquilibrium.Workspace ws) {
        return point.vapour() ? service.frostTemperature(null, point.p(), BASIS, point.z(), ws)
                : service.freezingTemperature(null, point.p(), BASIS, point.z(), ws);
    }

    /** Scores {@code points} on {@code service}, by group (the dataset and its group), printing a row per point when {@code print}. */
    static Map<String, Score> score(FluidTpEquilibrium service, List<Point> points, boolean print) {
        var ws = service.newWorkspace();
        Map<String, Score> scores = new LinkedHashMap<>();
        if (print) System.out.println("dataset | group | T K | P kPa | x_CO2 | kind | model T K | model - measured K | fluid at the onset");
        for (Point point : points) {
            Score s = scores.computeIfAbsent(point.dataset() + " | " + point.group(), k -> new Score());
            DepositionOnset onset = onset(service, point, ws);
            String where = String.format(Locale.ROOT, "%s | %s | %.2f | %.1f | %.6g | %s", point.dataset(), point.group(), point.t(), point.p() / 1e3,
                    point.co2(), point.vapour() ? "frost" : "liquidus");
            switch (onset.status()) {
                case CONVERGED -> {
                    double d = onset.temperature() - point.t();
                    if (onset.coverage().grade() == EquilibriumResult.CoverageGrade.EXTRAPOLATED) {
                        s.extrapolated.add(where + ": " + String.format(Locale.ROOT, "%+.3f K", d));
                        if (print) System.out.printf(Locale.ROOT, "%s | %.3f | %+.3f | %s, extrapolated%n", where, onset.temperature(), d,
                                onset.fluid().classification());
                    } else {
                        s.deviations.add(d);
                        if (print) System.out.printf(Locale.ROOT, "%s | %.3f | %+.3f | %s%n", where, onset.temperature(), d, onset.fluid().classification());
                    }
                }
                case UNSUPPORTED -> {
                    s.holds.add(where + ": " + onset.detail());
                    if (print) System.out.println(where + " | typed hold: " + onset.detail());
                }
                default -> {
                    s.failures.add(where + ": " + onset.status() + " " + onset.detail());
                    if (print) System.out.println(where + " | " + onset.status() + ": " + onset.detail());
                }
            }
        }
        return scores;
    }

    /** One summary row per group and the dataset totals; returns the per-dataset totals. */
    static Map<String, Score> summary(String title, Map<String, Score> scores, double target) {
        System.out.println(title + ": dataset | group | scored | holds | extrapolated | failures | MAD K | bias K | max K | target K");
        Map<String, Score> datasets = new LinkedHashMap<>();
        for (var e : scores.entrySet()) {
            Score s = e.getValue();
            System.out.printf(Locale.ROOT, "%s | %d | %d | %d | %d | %.3f | %+.3f | %.3f | %s%n", e.getKey(), s.deviations.size(), s.holds.size(),
                    s.extrapolated.size(), s.failures.size(), s.mad(), s.bias(), s.max(), Double.isNaN(target) ? "report" : String.valueOf(target));
            datasets.computeIfAbsent(e.getKey().substring(0, e.getKey().indexOf(" | ")), k -> new Score()).add(s);
        }
        for (var e : datasets.entrySet()) {
            Score s = e.getValue();
            System.out.printf(Locale.ROOT, "%s (dataset) | all | %d | %d | %d | %d | %.3f | %+.3f | %.3f | %s%n", e.getKey(), s.deviations.size(),
                    s.holds.size(), s.extrapolated.size(), s.failures.size(), s.mad(), s.bias(), s.max(), Double.isNaN(target) ? "report" : String.valueOf(target));
        }
        return datasets;
    }

    /**
     * A binary's three-phase point at {@code t}: the pressure where the TP answer of a CO2-rich feed (95 % CO2 in the
     * partner) changes from a crystal beside a vapour (below) to a crystal beside a liquid (above), by bisection in
     * {@code ln P} on the engine's own TP answers (an independent route from the engine's three-phase Newton); the liquid's
     * CO2 fraction from the answer just above, the vapour's from the answer just below. {@code partner} is the basis index
     * of CH4 (1) or N2 (0). Null when no change lies between 1 kPa and 10 MPa.
     */
    static double[] threePhase(FluidTpEquilibrium service, double t, int partner, FluidTpEquilibrium.Workspace ws) {
        double[] feed = new double[5];
        feed[CO2] = 0.95;
        feed[partner] = 0.05;
        double lo = Math.log(1e3), hi = Math.log(0.9999e7);
        EquilibriumResult below = null, above = null;
        if (hasLiquid(tp(service, t, Math.exp(lo), feed, ws)) || !hasLiquid(tp(service, t, Math.exp(hi), feed, ws))) return null;
        for (int k = 0; k < 48; k++) {
            double mid = 0.5 * (lo + hi);
            var r = tp(service, t, Math.exp(mid), feed, ws);
            if (!r.converged()) return null;
            if (hasLiquid(r)) {
                hi = mid;
                above = r;
            } else {
                lo = mid;
                below = r;
            }
        }
        if (above == null || below == null) return null;
        double x = Double.NaN, y = Double.NaN;
        for (PhaseAmounts phase : above.phases()) if (phase.kind() == PhaseKind.LIQUID) x = phase.state().moleFraction(CO2);
        for (PhaseAmounts phase : below.phases()) if (phase.kind() != PhaseKind.SOLID) y = phase.state().moleFraction(CO2);
        return new double[] {Math.exp(0.5 * (lo + hi)), x, y};
    }

    static EquilibriumResult tp(FluidTpEquilibrium service, double t, double p, double[] feed, FluidTpEquilibrium.Workspace ws) {
        return service.tp(EquilibriumRequest.tp(t, p, BASIS, feed, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
    }

    static boolean hasLiquid(EquilibriumResult r) {
        for (PhaseAmounts phase : r.phases()) if (phase.kind() == PhaseKind.LIQUID) return true;
        return false;
    }
}
