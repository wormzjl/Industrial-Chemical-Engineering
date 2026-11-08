package com.wormzjl.icheme.science.thermo.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.DepositionOnset;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * G4 family 1 (P4 stage 2a, batch 2026-09-24-coolprop-low-temperature): CO2 frost points in methane-rich gas, the
 * open vapour-solid holdouts of {@code P4_P5_HOLDOUT_DATA_SURVEY.md} (no open CO2 + N2 frost points exist; the same
 * vapour-solid physics), against {@link FluidTpEquilibrium#depositionTemperature} on the pilot package: Le and Trebble
 * 2007 (CH4 + CO2, and the C2H6 and N2 ternaries), Zhang et al. 2011 (CH4 + CO2), Xiong et al. 2015 (CH4 + CO2 and the
 * N2 and C2H6 ternaries, frost pressures at given T, scored as the frost temperature at the measured pressure). Rows from
 * {@code src/test/resources/science/thermo/solid-co2-holdouts/} (NIST TRC ThermoML transcriptions with their citation
 * headers). Every point is scored; a point whose onset on this family lies where the fluid holds a liquid (the gas
 * condenses before the crystal forms: P4's typed hold, answered beside the liquid since P5) is reported apart with its
 * deviation, not a failure: the measured state is a frost point in the gas, so the pilot's dew point, not its frost-point
 * function, is what disagrees there. The CO2 + N2 three-phase points of Fandino et al. 2015 are reported as the engine
 * answers them.
 *
 * <p>P0 target: frost point within 1.5 K (Le and Trebble's own uncertainty, TRC U95, is 1.2 to 1.6 K). The bounds
 * below are what this pilot family measures with a margin; the stage document quotes the tables.</p>
 */
class G4F1CarbonDioxideFrostPointHoldoutTest {
    static final String PILOT = "icheme:pilot_cryogenic";
    static final String RESOURCES = "science/thermo/solid-co2-holdouts/";
    static final FluidTpEquilibrium SERVICE = FluidTpEquilibrium.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
    static final List<String> BASIS = SERVICE.contract().components();

    /** One frost point: the measured temperature and pressure, the gas composition [N2, CH4, C2H6, CO2], U95 of T in K (NaN if the data give U95 in pressure). */
    record Point(String dataset, int set, double temperature, double pressure, double[] z, double u95) {
        double co2() { return z[3]; }
    }

    static List<String[]> rows(String file) {
        List<String[]> rows = new ArrayList<>();
        var stream = G4F1CarbonDioxideFrostPointHoldoutTest.class.getClassLoader().getResourceAsStream(RESOURCES + file);
        assertNotNull(stream, file);
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            boolean header = true;
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#") || line.isBlank()) continue;
                if (header) {
                    header = false;
                    continue;
                }
                String[] cells = line.split("\t", -1);
                rows.add(cells);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    static double number(String cell) { return cell.isBlank() ? Double.NaN : Double.parseDouble(cell); }

    /**
     * Le and Trebble 2007: set 1 CH4 + CO2 (x_CO2, P kPa, T); set 2 with C2H6, whose file column labelled C2H6 holds the
     * CH4 fraction (the survey's TRC label defect: C2H6 = 1 - x_CO2 - that value); set 3 with N2, x_CO2 = 0.0194 as the
     * constraint and the column labelled N2 holding the CH4 fraction (N2 = 1 - 0.0194 - that value).
     */
    static List<Point> leTrebble() {
        List<Point> points = new ArrayList<>();
        for (String[] c : rows("le-trebble-2007-jced-52-683.tsv")) {
            int set = Integer.parseInt(c[0]);
            double t = number(c[5]), u = number(c[6]), p = number(c[8]) * 1e3;
            double[] z;
            if (set == 1) {
                double x = number(c[7]);
                z = new double[] {0, 1 - x, 0, x};
            } else if (set == 2) {
                double x = number(c[7]), methane = number(c[9]);
                z = new double[] {0, methane, 1 - x - methane, x};
            } else {
                double x = number(c[10]), methane = number(c[11]);
                z = new double[] {1 - x - methane, methane, 0, x};
            }
            points.add(new Point("Le-Trebble 2007", set, t, p, z, u));
        }
        return points;
    }

    /** Zhang et al. 2011: CH4 + CO2 (T, y_CO2, P kPa). */
    static List<Point> zhang() {
        List<Point> points = new ArrayList<>();
        for (String[] c : rows("zhang-2011-jced-56-2971.tsv")) {
            double y = number(c[7]);
            points.add(new Point("Zhang 2011", 1, number(c[5]), number(c[8]) * 1e3, new double[] {0, 1 - y, 0, y}, number(c[6])));
        }
        return points;
    }

    /** Xiong et al. 2015: frost pressure (kPa) at T with y_CO2, y_N2 (set 1) or y_C2H6 (set 2); set 3 the binary. */
    static List<Point> xiong() {
        List<Point> points = new ArrayList<>();
        for (String[] c : rows("xiong-2015-jced-60-3077.tsv")) {
            int set = Integer.parseInt(c[0]);
            double p = number(c[5]) * 1e3, t = number(c[7]), y = number(c[8]);
            double n2 = c.length > 9 && !c[9].isBlank() ? number(c[9]) : 0.0;
            double ethane = c.length > 10 && !c[10].isBlank() ? number(c[10]) : 0.0;
            points.add(new Point("Xiong 2015", set, t, p, new double[] {n2, 1 - y - n2 - ethane, ethane, y}, Double.NaN));
        }
        return points;
    }

    /** Souza et al. 2020: the CO2 + CH4 three-phase (S + L + V) line, (T, P). */
    static double[][] souzaLine() {
        List<double[]> line = new ArrayList<>();
        for (String[] c : rows("souza-2020-fpe-522-112762-svle.tsv")) line.add(new double[] {number(c[9]), number(c[5]) * 1e3});
        line.sort((a, b) -> Double.compare(a[0], b[0]));
        return line.toArray(double[][]::new);
    }

    /** Accumulated scores of one group of points. */
    static final class Score {
        final List<Double> deviations = new ArrayList<>();
        final List<Double> approximations = new ArrayList<>();
        /** Onsets where this family's fluid holds a liquid (condensed first): P4's holds, P5 answers, reported apart. */
        final List<String> holds = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        double mean() { return deviations.stream().mapToDouble(Math::abs).average().orElse(Double.NaN); }
        double bias() { return deviations.stream().mapToDouble(d -> d).average().orElse(Double.NaN); }
        double max() { return deviations.stream().mapToDouble(Math::abs).max().orElse(Double.NaN); }
        double approximationMean() { return approximations.stream().mapToDouble(Math::abs).average().orElse(Double.NaN); }
        double approximationMax() { return approximations.stream().mapToDouble(Math::abs).max().orElse(Double.NaN); }
    }

    static double[] feed(double[] z) { return new double[] {z[0], z[1], z[2], z[3], 0.0}; }

    static Map<String, Score> score(List<Point> points, FluidTpEquilibrium.Workspace ws, boolean print) {
        Map<String, Score> scores = new LinkedHashMap<>();
        for (Point point : points) {
            String group = point.dataset() + " set " + point.set();
            Score s = scores.computeIfAbsent(group, k -> new Score());
            DepositionOnset onset = SERVICE.depositionTemperature(null, point.pressure(), BASIS, feed(point.z()), ws);
            String where = String.format(Locale.ROOT, "%.2f K %.1f kPa y_CO2 %.4f", point.temperature(), point.pressure() / 1e3, point.co2());
            switch (onset.status()) {
                case CONVERGED -> {
                    double d = onset.temperature() - point.temperature();
                    if (condensed(onset)) {
                        s.holds.add(where + String.format(Locale.ROOT, " (onset %.3f K, %+.3f K, fluid %s)", onset.temperature(), d,
                                onset.fluid().classification()));
                        if (print) System.out.println(group + " | " + where + " | condensed first on this family (P5 answer beside a liquid): "
                                + onset.detail());
                        continue;
                    }
                    s.deviations.add(d);
                    if (Double.isFinite(onset.approximation())) s.approximations.add(onset.approximation() - point.temperature());
                    if (print) {
                        System.out.printf(Locale.ROOT, "%s | %s | %.3f | %+.3f | %+.3f | %s%n", group, where, onset.temperature(), d,
                                onset.approximation() - point.temperature(), onset.fluid().coverage().grade());
                    }
                }
                case UNSUPPORTED -> {
                    s.failures.add(where + ": typed hold " + onset.detail());
                    if (print) System.out.println(group + " | " + where + " | typed hold: " + onset.detail());
                }
                default -> {
                    s.failures.add(where + ": " + onset.detail());
                    if (print) System.out.println(group + " | " + where + " | " + onset.status() + ": " + onset.detail());
                }
            }
        }
        return scores;
    }

    /**
     * Whether the fluid at an onset holds a phase labelled liquid (a methane-rich liquid below methane's critical
     * temperature, or a vapour-liquid split): on this family the gas condensed before the crystal formed.
     */
    static boolean condensed(DepositionOnset onset) {
        for (var phase : onset.fluid().phases()) {
            if (phase.kind() == com.wormzjl.icheme.science.thermo.phase.PhaseKind.LIQUID) return true;
        }
        return false;
    }

    static void summary(Map<String, Score> scores) {
        System.out.println("dataset | scored | condensed first (apart) | failures | MAD K | bias K | max K | P0 target K | partial-pressure MAD K | max K");
        for (var e : scores.entrySet()) {
            Score s = e.getValue();
            System.out.printf(Locale.ROOT, "%s | %d | %d | %d | %.3f | %+.3f | %.3f | 1.5 | %.3f | %.3f%n", e.getKey(), s.deviations.size(), s.holds.size(),
                    s.failures.size(), s.mean(), s.bias(), s.max(), s.approximationMean(), s.approximationMax());
        }
    }

    /** The binary CH4 + CO2 frost points of all three datasets. */
    @Test
    void binaryMethaneCarbonDioxideFrostPoints() {
        var ws = SERVICE.newWorkspace();
        List<Point> points = new ArrayList<>();
        for (Point p : leTrebble()) if (p.set() == 1) points.add(p);
        points.addAll(zhang());
        for (Point p : xiong()) if (p.set() == 3) points.add(p);
        assertEquals(55 + 17 + 64, points.size());
        System.out.println("dataset | point | onset T K | onset - measured K | partial-pressure T - measured K | grade");
        var scores = score(points, ws, true);
        summary(scores);
        Score all = new Score();
        scores.values().forEach(s -> {
            all.deviations.addAll(s.deviations);
            all.approximations.addAll(s.approximations);
            all.holds.addAll(s.holds);
            all.failures.addAll(s.failures);
        });
        System.out.printf(Locale.ROOT, "binary CH4 + CO2: %d scored, %d condensed first (apart), %d failures; MAD %.3f K, bias %+.3f K, max %.3f K (P0 target 1.5 K);"
                + " partial-pressure MAD %.3f K, max %.3f K%n", all.deviations.size(), all.holds.size(), all.failures.size(), all.mean(),
                all.bias(), all.max(), all.approximationMean(), all.approximationMax());
        // The three-phase line of CO2 + CH4 (Souza 2020): where the measured points lie against it.
        double[][] line = souzaLine();
        System.out.println("Souza 2020 SVLE line (T K, P kPa): " + java.util.Arrays.deepToString(line));
        assertTrue(all.failures.isEmpty(), all.failures.toString());
        assertTrue(all.holds.size() <= BINARY_HOLDS, all.holds.toString());
        assertEquals(136 - all.holds.size(), all.deviations.size());
        assertTrue(all.mean() <= BINARY_POOLED_MAD, "pooled MAD " + all.mean());
        for (var e : scores.entrySet()) {
            Score s = e.getValue();
            assertTrue(s.mean() <= BINARY_MAD, e.getKey() + " MAD " + s.mean());
            assertTrue(s.max() <= BINARY_MAX, e.getKey() + " max " + s.max());
        }
    }

    /** The N2 and C2H6 ternaries (Le and Trebble sets 2 and 3, Xiong sets 1 and 2) inside the pilot domain. */
    @Test
    void ternaryFrostPointsWithNitrogenAndEthane() {
        var ws = SERVICE.newWorkspace();
        List<Point> points = new ArrayList<>();
        for (Point p : leTrebble()) if (p.set() != 1) points.add(p);
        for (Point p : xiong()) if (p.set() != 3) points.add(p);
        assertEquals(24 + 24 + 77 + 53, points.size());
        for (Point p : points) {
            double sum = 0;
            for (double x : p.z()) {
                assertTrue(x >= 0.0, p.toString());
                sum += x;
            }
            assertEquals(1.0, sum, 1e-12, p.toString());
        }
        System.out.println("dataset | point | onset T K | onset - measured K | partial-pressure T - measured K | grade");
        var scores = score(points, ws, true);
        summary(scores);
        int holds = 0;
        for (Score s : scores.values()) holds += s.holds.size();
        assertTrue(holds <= TERNARY_HOLDS, "holds " + holds);
        for (var e : scores.entrySet()) {
            Score s = e.getValue();
            assertTrue(s.failures.isEmpty(), e.getKey() + " " + s.failures);
            assertTrue(s.mean() <= TERNARY_MAD, e.getKey() + " MAD " + s.mean());
            assertTrue(s.max() <= TERNARY_MAX, e.getKey() + " max " + s.max());
        }
    }

    /** Xiong's frost pressures at their temperatures: depositionPressure against the measured pressure. */
    @Test
    void xiongFrostPressuresAtTheirTemperatures() {
        var ws = SERVICE.newWorkspace();
        Map<Integer, List<Double>> deviations = new LinkedHashMap<>();
        Map<Integer, List<Double>> approximations = new LinkedHashMap<>();
        int holds = 0, beyond = 0;
        for (Point p : xiong()) {
            DepositionOnset onset = SERVICE.depositionPressure(null, p.temperature(), BASIS, feed(p.z()), ws);
            if (onset.status() == EquilibriumResult.Status.UNSUPPORTED) {
                assertTrue(onset.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES), onset.detail());
                holds++;
                continue;
            }
            if (!onset.converged() || onset.pressure() > 1e7) {
                // No onset below the 10 MPa envelope at this temperature: the gas condenses first on this family, and its liquid
                // does not freeze below the envelope (P4 held these at the window's end; P5 continues the search through the liquid).
                // Past the envelope the search goes on in the numerical box on the continued models (owner rule of 2026-10-16):
                // an extrapolated onset of the compressed liquid, or none below 1 GPa.
                assertTrue(onset.converged() ? onset.coverage().grade() == EquilibriumResult.CoverageGrade.EXTRAPOLATED
                        : onset.detail().contains("lies above the pressure window"), onset.toString());
                beyond++;
                System.out.printf(Locale.ROOT, "set %d, %.2f K, %.1f kPa, y_CO2 %.4f: %s%n", p.set(), p.temperature(), p.pressure() / 1e3, p.co2(), onset.detail());
                continue;
            }
            assertTrue(onset.converged(), onset.toString());
            deviations.computeIfAbsent(p.set(), k -> new ArrayList<>()).add(100 * (onset.pressure() / p.pressure() - 1));
            if (Double.isFinite(onset.approximation())) {
                approximations.computeIfAbsent(p.set(), k -> new ArrayList<>()).add(100 * (onset.approximation() / p.pressure() - 1));
            }
        }
        System.out.println("Xiong set | points | mean |dP/P| % | bias % | max % | partial-pressure mean % | max %");
        for (var e : deviations.entrySet()) {
            var d = e.getValue();
            var a = approximations.getOrDefault(e.getKey(), List.of());
            System.out.printf(Locale.ROOT, "%d | %d | %.2f | %+.2f | %.2f | %.2f | %.2f%n", e.getKey(), d.size(),
                    d.stream().mapToDouble(Math::abs).average().orElse(Double.NaN), d.stream().mapToDouble(x -> x).average().orElse(Double.NaN),
                    d.stream().mapToDouble(Math::abs).max().orElse(Double.NaN), a.stream().mapToDouble(Math::abs).average().orElse(Double.NaN),
                    a.stream().mapToDouble(Math::abs).max().orElse(Double.NaN));
        }
        System.out.println("holds " + holds + ", no onset below the envelope " + beyond);
        // Measured: 14 points whose gas condenses on this family before the crystal forms; P4 held them typed, P5 finds their
        // liquid does not freeze below the 10 MPa envelope (out of domain until the owner rule of 2026-10-16, an extrapolated
        // onset or none in the box since). No typed hold remains.
        assertTrue(holds == 0 && beyond <= 14, "holds " + holds + ", beyond " + beyond);
    }

    /** Fandino 2015: the CO2 + N2 three-phase points near the CO2 triple point, as the engine answers them. */
    @Test
    void fandinoThreePhasePointsAreNotAnsweredAsVapourSolid() {
        var ws = SERVICE.newWorkspace();
        int answered = 0;
        System.out.println("T K | P MPa | x_CO2 | TP answer | onset at P");
        for (String[] c : rows("fandino-2015-ijggc-36-78-table8.tsv")) {
            double t = number(c[1]), p = number(c[2]) * 1e6;
            for (double x : new double[] {0.99, 0.9, 0.5}) {
                double[] z = {1 - x, 0, 0, x, 0};
                var r = SERVICE.tp(EquilibriumRequest.tp(t, p, BASIS, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
                var onset = SERVICE.depositionTemperature(null, Math.min(p, 1.0e7), BASIS, z, ws);
                System.out.printf(Locale.ROOT, "%.2f | %.3f | %.2f | %s %s %s | %s %s%n", t, p / 1e6, x, r.status(), r.classification(),
                        r.converged() ? "" : r.detail(), onset.status(), onset.converged() ? String.format(Locale.ROOT, "%.3f K", onset.temperature()) : onset.detail());
                if (r.converged() && r.classification() == EquilibriumResult.Classification.VAPOR_SOLID) answered++;
            }
        }
        System.out.println("vapour-solid answers at the three-phase points: " + answered);
    }

    // Bounds: what the pilot family measures (P4_GAS_SOLID_EQUILIBRIUM.md section 6) with a margin.
    /** Measured 2026-09-25: per dataset 2.312 (Le-Trebble), 0.667 (Zhang), 0.703 K (Xiong); pooled 1.354 K. */
    static final double BINARY_MAD = 2.5;
    /** The pooled binary MAD is held to the P0 target itself. */
    static final double BINARY_POOLED_MAD = 1.5;
    /** Measured 5.193 K (Le-Trebble, x_CO2 0.01 at 2.19 MPa). */
    static final double BINARY_MAX = 5.5;
    /** Measured 1.730 and 1.662 K (Le-Trebble sets 2 and 3), 0.955 and 0.935 K (Xiong sets 1 and 2). */
    static final double TERNARY_MAD = 2.0;
    /** Measured 5.444 K (Xiong set 1). */
    static final double TERNARY_MAX = 6.0;
    /**
     * Measured: one binary point (Xiong, 153.15 K, 0.1 % CO2) and four ternary points (Xiong set 2) where the gas condenses
     * first on this family (P4's typed holds; since P5 onsets beside the liquid, reported apart with their deviations).
     */
    static final int BINARY_HOLDS = 1;
    static final int TERNARY_HOLDS = 4;
}
