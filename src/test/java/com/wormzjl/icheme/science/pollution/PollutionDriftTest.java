package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.Environment.Cell;
import com.wormzjl.icheme.science.pollution.PollutionField.Result;
import com.wormzjl.icheme.science.pollution.PollutionLedger.Total;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The upward drift (batch 2026-11-18-pollution-propagation, P1): one upwind term per open, loaded cube toward the cube
 * above, {@code U = r min(φa, φb) A_a / φa}, on top of the unchanged six-face exchange; and the stored digests that hold
 * the step's bits.
 */
class PollutionDriftTest {
    private static final double N = PollutionField.CUBE_AIR_MOLES;
    private static final Cell OPEN = new Cell(1, true, true);
    private static final Cell CLOSED = new Cell(0, false, true);
    private static final int BOTTOM = -4;
    private static final int TOP = 19;

    static PollutionSettings settings(double riseShare, int maximumCubes) {
        PollutionSettings d = PollutionSettings.DEFAULT;
        return new PollutionSettings(d.faceShare(), d.nominalSeconds(), d.halfLifeOpenSeconds(),
                d.halfLifeEnclosedSeconds(), d.washout(), d.settlingShare(), d.cutoff(), maximumCubes,
                d.closedAirShare(), riseShare);
    }

    private static double toxic(PollutionField field, long key) {
        int i = field.find(key);
        return i < 0 ? 0.0 : field.amounts()[i * Channel.values().length + Channel.TOXIC.ordinal()];
    }

    private static Result one(Environment environment, long key, double amount, PollutionSettings settings) {
        return PollutionField.step(PollutionField.EMPTY, PollutionLedger.EMPTY,
                List.of(new Release(key, Channel.TOXIC, amount)), environment, 20.0, settings);
    }

    @Test
    void oneStepFromOneCubeGivesTheAboveTwiceTheFaceShare() {
        Environment open = MapEnvironment.uniform(OPEN, false, BOTTOM, TOP);
        long a = CubeKey.of(0, 4, 0);
        double amount = 100 * N;
        PollutionField field = one(open, a, amount, PollutionSettings.DEFAULT).field();
        // Every cube decays by the same factor in the step, so the shares stand as ratios to the source.
        double source = toxic(field, a);
        double total = 0.0;
        for (double v : field.amounts()) {
            total += v;
        }
        assertEquals(0.16, source / total, 1e-12, "source");
        assertEquals(0.24, toxic(field, CubeKey.offset(a, 0, 1, 0)) / total, 1e-12, "above");
        assertEquals(0.12, toxic(field, CubeKey.offset(a, 0, -1, 0)) / total, 1e-12, "below");
        assertEquals(0.12, toxic(field, CubeKey.offset(a, 1, 0, 0)) / total, 1e-12, "east");
        assertEquals(0.12, toxic(field, CubeKey.offset(a, 0, 0, -1)) / total, 1e-12, "north");
        assertEquals(7, field.size());
    }

    @Test
    void theDriftRaisesTheCentreOfMassAndLeavesTheHorizontalSpreadAlone() {
        Environment open = MapEnvironment.uniform(OPEN, false, BOTTOM, TOP);
        long a = CubeKey.of(0, 4, 0);
        List<Release> release = List.of(new Release(a, Channel.TOXIC, 50 * N));
        double[][] moments = new double[2][];
        double[] rises = {0.0, 0.12};
        for (int run = 0; run < 2; run++) {
            PollutionSettings settings = settings(rises[run], 8192);
            Result r = new Result(PollutionField.EMPTY, PollutionLedger.EMPTY);
            for (int p = 0; p < 10; p++) {
                r = PollutionField.step(r.field(), r.ledger(), p == 0 ? release : List.of(), open, 20.0, settings);
            }
            moments[run] = moments(r.field());
            assertEquals(0.0, moments[run][0], 1e-9, "x mean");
            assertEquals(0.0, moments[run][2], 1e-9, "z mean");
            assertEquals(moments[run][3], moments[run][5], 1e-9 * moments[run][3], "x and z spread");
        }
        assertEquals(4.0, moments[0][1], 0.05, "no drift: the centre stays near the source (the sinks are far)");
        assertTrue(moments[1][1] > moments[0][1] + 1.0, "drift: the centre rises " + moments[1][1]);
        // The horizontal spread is the face exchange's alone: the drift moves nothing sideways.
        assertEquals(moments[0][3], moments[1][3], 0.05 * moments[0][3], "horizontal spread with and without drift");
    }

    /** x, y, z means, then x, y, z variances, of the toxic channel. */
    private static double[] moments(PollutionField field) {
        long[] keys = field.keys();
        double[] amounts = field.amounts();
        double m = 0, x = 0, y = 0, z = 0, xx = 0, yy = 0, zz = 0;
        for (int i = 0; i < keys.length; i++) {
            double v = amounts[i * Channel.values().length + Channel.TOXIC.ordinal()];
            int cx = CubeKey.x(keys[i]), cy = CubeKey.y(keys[i]), cz = CubeKey.z(keys[i]);
            m += v;
            x += v * cx;
            y += v * cy;
            z += v * cz;
            xx += v * cx * cx;
            yy += v * cy * cy;
            zz += v * cz * cz;
        }
        x /= m;
        y /= m;
        z /= m;
        return new double[] {x, y, z, xx / m - x * x, yy / m - y * y, zz / m - z * z};
    }

    @Test
    void aClosedCubeAboveTakesNoDriftAndTheTopBooksItDissipated() {
        long a = CubeKey.of(0, 4, 0);
        long lid = CubeKey.offset(a, 0, 1, 0);
        Map<Long, Cell> cells = Map.of(a, OPEN, lid, CLOSED);
        Environment covered = new MapEnvironment(cells, Cell.UNLOADED, false, BOTTOM, TOP);
        double amount = 100 * N;
        assertEquals(toxic(one(covered, a, amount, settings(0.0, 8192)).field(), a),
                toxic(one(covered, a, amount, PollutionSettings.DEFAULT).field(), a), 0.0, "nothing drifts into a closed cube");

        long top = CubeKey.of(0, TOP, 0);
        Environment alone = new MapEnvironment(Map.of(top, OPEN), Cell.UNLOADED, false, BOTTOM, TOP);
        Result r = one(alone, top, amount, PollutionSettings.DEFAULT);
        assertEquals((0.12 + 0.12) * amount, r.ledger().get(Channel.TOXIC, Total.DISSIPATED), 1e-9 * amount,
                "the top face takes its face share and the drift");
        assertEquals(0.0, r.ledger().closure(Channel.TOXIC, r.field()), 1e-9 * amount);
    }

    @Test
    void theDriftIsCappedAtASeventhPerStep() {
        Environment open = MapEnvironment.uniform(OPEN, false, BOTTOM, TOP);
        long a = CubeKey.of(0, 4, 0);
        double amount = 100 * N;
        // 0.5 per 20 s over 60 s is 1.5, capped at 1/7; the face share is capped at 1/7 too.
        PollutionField field = PollutionField.step(PollutionField.EMPTY, PollutionLedger.EMPTY,
                List.of(new Release(a, Channel.TOXIC, amount)), open, 60.0, settings(0.5, 8192)).field();
        double total = 0.0;
        for (double v : field.amounts()) {
            total += v;
        }
        assertEquals(0.0, toxic(field, a) / total, 1e-12, "the source empties: 6/7 to the faces, 1/7 drift");
        assertEquals(2.0 / 7.0, toxic(field, CubeKey.offset(a, 0, 1, 0)) / total, 1e-12);
        for (double v : field.amounts()) {
            assertTrue(v >= 0.0);
        }
    }

    @Test
    void settingsRefuseADriftOutsideZeroToHalf() {
        assertThrows(IllegalArgumentException.class, () -> settings(-0.01, 8192));
        assertThrows(IllegalArgumentException.class, () -> settings(0.51, 8192));
        assertThrows(IllegalArgumentException.class, () -> settings(Double.NaN, 8192));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Stored digests. The rise-0 digest was taken from the field before the drift existed (b57774830's
    // PollutionField), so riseShare 0 reproduces it bit for bit; the default digest holds the drift's bits for the
    // step's optimisation (P2).

    static final long DIGEST_NO_RISE = 0x6450680631e3021fL;
    static final long DIGEST_DEFAULT = 0x5ae9aaa2c9acc5c5L;

    @Test
    void theHintedSearchAnswersAsBinarySearch() {
        Random random = new Random(3);
        for (int round = 0; round < 200; round++) {
            int n = random.nextInt(40);
            long[] keys = random.longs(n, -50, 50).distinct().sorted().toArray();
            for (int q = 0; q < 50; q++) {
                long key = random.nextInt(120) - 60;
                int from = random.nextInt(keys.length + 4) - 2;
                assertEquals(java.util.Arrays.binarySearch(keys, key), PollutionField.search(keys, key, from),
                        "key " + key + " from " + from + " in " + java.util.Arrays.toString(keys));
            }
        }
    }

    @Test
    void riseShareZeroReproducesTheFieldBeforeTheDrift() {
        assertEquals(Long.toHexString(DIGEST_NO_RISE), Long.toHexString(digest(settings(0.0, 600))));
    }

    @Test
    void theDefaultDriftKeepsItsStoredBits() {
        assertEquals(Long.toHexString(DIGEST_DEFAULT), Long.toHexString(digest(settings(0.12, 600))));
    }

    /**
     * 120 passes of five releases (80 passes on) over a 12 × 8 × 12 block of open, enclosed, thin, closed and unloaded
     * cubes, in rain, with the top a sink at y 6 and the cap at 600 cubes (at the cap in 5 of the 6 sampled passes);
     * the field and ledger bits every 20 passes.
     * The copy in {@code tools/pollution-bench/} computes the same digest.
     */
    static long digest(PollutionSettings s) {
        Random random = new Random(7);
        Map<Long, Cell> cells = new HashMap<>();
        Cell thin = new Cell(0.3, false, true);
        Cell enclosed = new Cell(1, false, true);
        for (int x = -6; x < 6; x++) {
            for (int y = -4; y <= 3; y++) {
                for (int z = -6; z < 6; z++) {
                    double u = random.nextDouble();
                    Cell c = u < 0.7 ? OPEN : u < 0.85 ? enclosed : u < 0.9 ? thin : u < 0.95 ? CLOSED : Cell.UNLOADED;
                    cells.put(CubeKey.of(x, y, z), c);
                }
            }
        }
        Environment environment = new MapEnvironment(cells, OPEN, true, -4, 6);
        List<Release> releases = List.of(new Release(CubeKey.of(0, 0, 0), Channel.TOXIC, 3e5),
                new Release(CubeKey.of(0, 0, 0), Channel.PARTICULATE, 8e4),
                new Release(CubeKey.of(3, -2, -4), Channel.CORROSIVE, 2e5),
                new Release(CubeKey.of(-5, 2, 1), Channel.COMBUSTIBLE, 1.5e5),
                new Release(CubeKey.of(2, 5, 2), Channel.TOXIC, 5e4));
        PollutionField field = PollutionField.EMPTY;
        PollutionLedger ledger = PollutionLedger.EMPTY;
        long h = 0xcbf29ce484222325L;
        for (int p = 0; p < 120; p++) {
            Result r = PollutionField.step(field, ledger, p < 80 ? releases : List.of(), environment, 20.0, s);
            field = r.field();
            ledger = r.ledger();
            if (p % 20 == 19) {
                for (long k : field.keys()) {
                    h = mix(h, k);
                }
                for (double a : field.amounts()) {
                    h = mix(h, Double.doubleToRawLongBits(a));
                }
                for (double v : ledger.values()) {
                    h = mix(h, Double.doubleToRawLongBits(v));
                }
            }
        }
        return h;
    }

    private static long mix(long h, long v) {
        for (int b = 0; b < 8; b++) {
            h ^= (v >>> (8 * b)) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
