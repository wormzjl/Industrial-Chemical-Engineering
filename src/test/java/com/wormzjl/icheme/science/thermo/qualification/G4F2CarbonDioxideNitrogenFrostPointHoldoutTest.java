package com.wormzjl.icheme.science.thermo.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.phase.DepositionOnset;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * G4 family 2 (P4 stage 2b, batch 2026-09-24-coolprop-low-temperature): CO2 frost points in nitrogen, the plan's own G4
 * case (P4 bullet 1), from the owner-provided dissertations transcribed on 2026-09-25: Sonntag 1960 (Table VIII, 64 points,
 * 140 to 190 K, 5 to 100 atm) and Smith 1963 (Table XIII: his own 69 measurements, 80 to 200 atm at 140 and 150 K and 60 to
 * 200 atm at 190 K; the 48 rows he reprints from Sonntag and the 6 where both agree are Sonntag's, and the 2 values read
 * from a plot are excluded). Each point (T, P, y_CO2) on an isotherm is scored as {@code depositionTemperature(P, y)} on
 * the pilot package against the isotherm temperature, as G4F1 scores the methane-gas frost points. Pressures above the
 * pilot's 10 MPa envelope (about 98.7 atm) are answered on the continued models, graded {@code EXTRAPOLATED} (owner rule
 * of 2026-10-16; {@code OUT_OF_DOMAIN} before): reported apart, not scored. The 14 points P4 held typed
 * (the onset lies in dense supercritical N2 that the phase identification parameter labels liquid-like, 140 to 160 K and
 * 6 to 9.1 MPa) are answered since P5 (the dense one-root fluid carries the crystal as a vapour does) and scored as their
 * own group, the dense carrier: the original caps stay on the 51 vapour-carrier points and are not loosened, the dense
 * group has its own bound at what it measures, and the pooled MAD of all 65 is held to the P0 target.
 *
 * <p>P0 target: 1.5 K (pooled MAD, D17's rule of an average with per-dataset and per-point caps at what is measured).
 * For context, Maltby et al. 2025 report Peng-Robinson with a fitted kij at 0.36 % of T (about 0.5 to 0.7 K) on the same
 * Sonntag plus Smith set; the pilot's E-PPR78 kij(T) is predictive. Smith attributes a warm bias of about 1 K at 190 K to
 * Sonntag's apparatus and measured lower y where both overlap.</p>
 */
class G4F2CarbonDioxideNitrogenFrostPointHoldoutTest {
    static final String SONNTAG = "sonntag-1960-dissertation-table8-co2-n2-sve.tsv";
    static final String SMITH = "smith-1963-dissertation-table13-co2-n2-sve.tsv";
    static final double ATM = 101325.0;

    /** Pooled MAD over both datasets: the P0 target (measured 2026-09-25: 0.420 K over 51 points). */
    static final double POOLED_MAD = 1.5;
    /** Per dataset: MAD and per-point maximum at what the pilot family measures with a margin (0.403 K and 1.991 K Sonntag,
     * 0.836 K and 0.985 K Smith's two points inside 10 MPa; P4_NETWORK_COUPLING.md). */
    static final double DATASET_MAD = 1.0;
    static final double POINT_MAX = 2.5;
    /** Typed holds since P5: none (P4 held 14, the dense carrier below). */
    static final int HOLDS = 0;
    /**
     * The dense carrier (P5): the 14 points whose onset fluid is a one-root fluid labelled liquid-like. Measured 2026-09-25
     * (9019 J/mol): MAD 3.505 K, bias +3.505 K, max 5.900 K (Sonntag, 140 K, 7.09 MPa); some of them lie in the declared
     * critical band of N2 (Tr 1.05 to 1.2, Pr 2 to 3), where the fluid is research grade. Bounds at what is measured with a
     * margin. A dense carrier is supercritical N2 (above its 126.2 K critical temperature): no condensation is involved.
     */
    static final int DENSE_POINTS = 14;
    static final double DENSE_MAD = 4.0;
    static final double DENSE_MAX = 6.5;
    /** Points above the pilot's 10 MPa envelope: 5 of Sonntag's (100 atm) and 63 of Smith's (100 to 200 atm), fixed by the
     * data; extrapolated, reported apart. */
    static final int ABOVE_CEILING = 68;

    /** One frost point: dataset, isotherm T (K), P (Pa), y_CO2 and the transcription's origin or flag. */
    record Point(String dataset, double temperature, double pressure, double y, String origin) {
        double[] feed() { return new double[] {1 - y, 0, 0, y}; }
    }

    static List<Point> sonntag() {
        List<Point> points = new ArrayList<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows(SONNTAG)) {
            points.add(new Point("Sonntag 1960", Double.parseDouble(c[0]), Double.parseDouble(c[1]) * ATM, Double.parseDouble(c[3]),
                    c.length > 4 ? c[4] : ""));
        }
        return points;
    }

    /** Smith's own measurements in his Table XIII: origin {@code smith} or {@code smith_avg}. */
    static List<Point> smith() {
        List<Point> points = new ArrayList<>();
        for (String[] c : G4F1CarbonDioxideFrostPointHoldoutTest.rows(SMITH)) {
            String origin = c[4];
            if (!origin.equals("smith") && !origin.equals("smith_avg")) continue;
            points.add(new Point("Smith 1963", Double.parseDouble(c[0]), Double.parseDouble(c[1]) * ATM, Double.parseDouble(c[3]), origin));
        }
        return points;
    }

    /** Scores of one dataset: deviations inside the envelope, typed holds, points above the pilot's 10 MPa envelope. */
    static final class Tally {
        final G4F1CarbonDioxideFrostPointHoldoutTest.Score score = new G4F1CarbonDioxideFrostPointHoldoutTest.Score();
        final List<String> extrapolated = new ArrayList<>();
        final List<Double> extrapolatedDeviations = new ArrayList<>();
        final List<Double> dense = new ArrayList<>();
        final Map<Double, List<Double>> byIsotherm = new LinkedHashMap<>();
    }

    static Map<String, Tally> score(List<Point> points) {
        var service = G4F1CarbonDioxideFrostPointHoldoutTest.SERVICE;
        var ws = service.newWorkspace();
        Map<String, Tally> tallies = new LinkedHashMap<>();
        System.out.println("dataset | T K | P kPa | y_CO2 | origin | onset T K | onset - measured K | partial-pressure T - measured K");
        for (Point point : points) {
            Tally tally = tallies.computeIfAbsent(point.dataset(), k -> new Tally());
            DepositionOnset onset = service.depositionTemperature(null, point.pressure(), G4F1CarbonDioxideFrostPointHoldoutTest.BASIS, point.feed(), ws);
            String where = String.format(Locale.ROOT, "%s | %.0f | %.1f | %.5f | %s", point.dataset(), point.temperature(), point.pressure() / 1e3,
                    point.y(), point.origin());
            if (point.pressure() > 1e7) {
                // Above the pilot's 10 MPa envelope: answered on the continued models since the owner rule of 2026-10-16
                // (out of domain before), reported apart and not scored (the qualification covers the envelope).
                tally.extrapolated.add(where);
                if (onset.converged()) {
                    assertEquals(EquilibriumResult.CoverageGrade.EXTRAPOLATED, onset.coverage().grade(), where + ": " + onset.coverage().evidence());
                    double d = onset.temperature() - point.temperature();
                    tally.extrapolatedDeviations.add(d);
                    System.out.printf(Locale.ROOT, "%s | %.3f | %+.3f | extrapolated (above 10 MPa)%n", where, onset.temperature(), d);
                } else {
                    System.out.println(where + " | above 10 MPa, " + onset.status() + ": " + onset.detail());
                }
                continue;
            }
            switch (onset.status()) {
                case CONVERGED -> {
                    double d = onset.temperature() - point.temperature();
                    if (denseCarrier(onset)) {
                        tally.dense.add(d);
                        System.out.printf(Locale.ROOT, "%s | %.3f | %+.3f | %+.3f | dense carrier (%s, %s)%n", where, onset.temperature(), d,
                                onset.approximation() - point.temperature(), onset.fluid().classification(), onset.fluid().coverage().grade());
                        continue;
                    }
                    tally.score.deviations.add(d);
                    tally.byIsotherm.computeIfAbsent(point.temperature(), k -> new ArrayList<>()).add(d);
                    if (Double.isFinite(onset.approximation())) tally.score.approximations.add(onset.approximation() - point.temperature());
                    System.out.printf(Locale.ROOT, "%s | %.3f | %+.3f | %+.3f%n", where, onset.temperature(), d, onset.approximation() - point.temperature());
                }
                case UNSUPPORTED -> {
                    assertTrue(onset.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES), onset.detail());
                    tally.score.holds.add(where);
                    System.out.println(where + " | typed hold: " + onset.detail());
                }
                default -> {
                    tally.score.failures.add(where + ": " + onset.status() + " " + onset.detail());
                    System.out.println(where + " | " + onset.status() + ": " + onset.detail());
                }
            }
        }
        System.out.println("dataset | scored | holds | above 10 MPa | failures | MAD K | bias K | max K | P0 target K | partial-pressure MAD K | max K");
        for (var e : tallies.entrySet()) {
            var s = e.getValue().score;
            System.out.printf(Locale.ROOT, "%s | %d | %d | %d | %d | %.3f | %+.3f | %.3f | 1.5 | %.3f | %.3f%n", e.getKey(), s.deviations.size(), s.holds.size(),
                    e.getValue().extrapolated.size(), s.failures.size(), s.mean(), s.bias(), s.max(), s.approximationMean(), s.approximationMax());
            for (var iso : e.getValue().byIsotherm.entrySet()) {
                double mad = iso.getValue().stream().mapToDouble(Math::abs).average().orElse(Double.NaN);
                double bias = iso.getValue().stream().mapToDouble(d -> d).average().orElse(Double.NaN);
                System.out.printf(Locale.ROOT, "  %s isotherm %.0f K: %d points, MAD %.3f K, bias %+.3f K%n", e.getKey(), iso.getKey(), iso.getValue().size(), mad, bias);
            }
        }
        return tallies;
    }

    /** Whether the fluid at the onset is one dense one-root fluid labelled liquid-like (P4's held points, P5's dense carrier). */
    static boolean denseCarrier(DepositionOnset onset) {
        var fluid = onset.fluid();
        return fluid.phases().size() == 1 && fluid.phases().get(0).kind() == com.wormzjl.icheme.science.thermo.phase.PhaseKind.LIQUID;
    }

    /** Both datasets inside the pilot domain: the pooled MAD against the P0 target, per-dataset MAD and per-point caps. */
    @Test
    void carbonDioxideFrostPointsInNitrogen() {
        List<Point> points = new ArrayList<>(sonntag());
        points.addAll(smith());
        assertEquals(64 + 69, points.size(), "Sonntag's 64 and Smith's own 69");
        for (Point p : points) assertTrue(p.y() > 0 && p.y() < 0.1 && p.temperature() >= 140 && p.temperature() <= 190, p.toString());
        var tallies = score(points);
        var pooled = new G4F1CarbonDioxideFrostPointHoldoutTest.Score();
        int extrapolated = 0;
        List<Double> beyond = new ArrayList<>();
        List<Double> dense = new ArrayList<>();
        for (var tally : tallies.values()) {
            dense.addAll(tally.dense);
            pooled.deviations.addAll(tally.score.deviations);
            pooled.approximations.addAll(tally.score.approximations);
            pooled.holds.addAll(tally.score.holds);
            pooled.failures.addAll(tally.score.failures);
            extrapolated += tally.extrapolated.size();
            beyond.addAll(tally.extrapolatedDeviations);
        }
        System.out.printf(Locale.ROOT, "CO2 + N2 pooled: %d scored, %d holds, %d above 10 MPa, %d failures; MAD %.3f K, bias %+.3f K, max %.3f K (P0 target 1.5 K);"
                + " partial-pressure MAD %.3f K, max %.3f K%n", pooled.deviations.size(), pooled.holds.size(), extrapolated, pooled.failures.size(),
                pooled.mean(), pooled.bias(), pooled.max(), pooled.approximationMean(), pooled.approximationMax());
        double denseMad = dense.stream().mapToDouble(Math::abs).average().orElse(Double.NaN);
        double denseMax = dense.stream().mapToDouble(Math::abs).max().orElse(Double.NaN);
        double allMad = (pooled.deviations.stream().mapToDouble(Math::abs).sum() + dense.stream().mapToDouble(Math::abs).sum())
                / (pooled.deviations.size() + dense.size());
        System.out.printf(Locale.ROOT, "dense carrier (P5, P4's holds): %d points, MAD %.3f K, bias %+.3f K, max %.3f K; all %d scored points: MAD %.3f K%n",
                dense.size(), denseMad, dense.stream().mapToDouble(d -> d).average().orElse(Double.NaN), denseMax,
                pooled.deviations.size() + dense.size(), allMad);
        assertTrue(pooled.failures.isEmpty(), pooled.failures.toString());
        System.out.printf(Locale.ROOT, "above 10 MPa (extrapolated, not scored): %d points, %d onsets, MAD %.3f K, bias %+.3f K%n", extrapolated,
                beyond.size(), beyond.stream().mapToDouble(Math::abs).average().orElse(Double.NaN),
                beyond.stream().mapToDouble(d -> d).average().orElse(Double.NaN));
        assertEquals(points.size(), pooled.deviations.size() + dense.size() + pooled.holds.size() + extrapolated);
        assertEquals(DENSE_POINTS, dense.size());
        assertTrue(denseMad <= DENSE_MAD && denseMax <= DENSE_MAX, "dense carrier MAD " + denseMad + ", max " + denseMax);
        assertTrue(allMad <= POOLED_MAD, "all scored points MAD " + allMad);
        assertEquals(ABOVE_CEILING, extrapolated, "every point above 10 MPa, and only those, is beyond the pilot's envelope");
        assertEquals(points.stream().filter(p -> p.pressure() > 1e7).count(), extrapolated);
        assertTrue(pooled.holds.size() <= HOLDS, pooled.holds.toString());
        assertTrue(pooled.mean() <= POOLED_MAD, "pooled MAD " + pooled.mean());
        for (var e : tallies.entrySet()) {
            var s = e.getValue().score;
            assertTrue(s.mean() <= DATASET_MAD, e.getKey() + " MAD " + s.mean());
            assertTrue(s.max() <= POINT_MAX, e.getKey() + " max " + s.max());
        }
    }
}
