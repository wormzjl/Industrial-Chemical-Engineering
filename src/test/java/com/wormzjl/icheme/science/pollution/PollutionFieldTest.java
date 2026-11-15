package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.Environment.Cell;
import com.wormzjl.icheme.science.pollution.PollutionField.Result;
import com.wormzjl.icheme.science.pollution.PollutionLedger.Total;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The pollution field's step (plan section 4): ledger, truncation, closed and unloaded cubes, sinks, decay, cap. */
class PollutionFieldTest {
    private static final PollutionSettings S = PollutionSettings.DEFAULT;
    private static final double N = PollutionField.CUBE_AIR_MOLES;
    private static final double PASS = 20.0;
    private static final Cell OPEN = new Cell(1, true, true);
    private static final Cell ENCLOSED = new Cell(1, false, true);
    private static final Cell CLOSED = new Cell(0, false, true);
    private static final int BOTTOM = -4;
    private static final int TOP = 19;

    private static Result start() {
        return new Result(PollutionField.EMPTY, PollutionLedger.EMPTY);
    }

    private static Result pass(Result r, List<Release> releases, Environment environment, double seconds) {
        return PollutionField.step(r.field(), r.ledger(), releases, environment, seconds, S);
    }

    private static Result passes(Result r, List<Release> releases, Environment environment, int count) {
        for (int p = 0; p < count; p++) {
            r = pass(r, releases, environment, PASS);
        }
        return r;
    }

    private static Environment isolated(Map<Long, Cell> cells) {
        return new MapEnvironment(cells, Cell.UNLOADED, false, BOTTOM, TOP);
    }

    private static void assertCloses(Result r, double relative) {
        for (Channel c : Channel.values()) {
            double released = r.ledger().get(c, Total.RELEASED);
            double closure = r.ledger().closure(c, r.field());
            assertTrue(Math.abs(closure) <= relative * Math.max(released, 1.0),
                    c + ": closure " + closure + " of released " + released);
        }
    }

    private static void assertNotNegative(PollutionField field) {
        double[] amounts = field.amounts();
        for (int i = 0; i < amounts.length; i++) {
            assertTrue(amounts[i] >= 0.0, "amount " + i + " is " + amounts[i]);
        }
    }

    @Test
    void theLedgerClosesOverAThousandPassesOfAConstantRelease() {
        Environment open = MapEnvironment.uniform(OPEN, false, BOTTOM, TOP);
        long source = CubeKey.of(0, 4, 0);
        long other = CubeKey.of(3, 0, 2);
        List<Release> releases = List.of(new Release(source, Channel.TOXIC, 2e5),
                new Release(source, Channel.COMBUSTIBLE, 5e4), new Release(source, Channel.CORROSIVE, 1e5),
                new Release(source, Channel.PARTICULATE, 3e4), new Release(other, Channel.TOXIC, 7e4));
        Result r = start();
        for (int p = 0; p < 1000; p++) {
            r = pass(r, releases, open, PASS);
            if (p % 100 == 0) {
                assertNotNegative(r.field());
            }
        }
        assertNotNegative(r.field());
        assertCloses(r, 1e-9);
        for (Channel c : Channel.values()) {
            assertTrue(r.ledger().get(c, Total.DECAYED) > 0.0, c + " decays");
        }
        // The source stands 8 cubes over the bottom: its particulate falls below the cutoff on the way, so none reaches
        // the ground here (the ground bookings have their own test).
        assertTrue(r.ledger().get(Channel.PARTICULATE, Total.DISSIPATED) > 0.0, "particulate thins out below the cutoff");
    }

    @Test
    void aPointReleaseNeverActivatesMoreThanItsIndexOverTheCutoff() {
        Environment enclosed = MapEnvironment.uniform(ENCLOSED, false, BOTTOM, TOP);
        double q = 0.5;
        Result r = pass(start(), List.of(new Release(CubeKey.of(0, 4, 0), Channel.TOXIC, q * N)), enclosed, PASS);
        int bound = (int) (q / S.cutoff()) + 1;
        int largest = r.field().size();
        for (int p = 0; p < 400; p++) {
            r = pass(r, List.of(), enclosed, PASS);
            largest = Math.max(largest, r.field().size());
            assertTrue(r.field().size() <= bound, "pass " + p + ": " + r.field().size() + " cubes above " + bound);
        }
        assertTrue(largest > 7, "the release spread beyond its first ring: " + largest);
        assertCloses(r, 1e-9);
    }

    @Test
    void aClosedAndAnUnloadedNeighbourTakeNothing() {
        long a = CubeKey.of(0, 0, 0);
        long closed = CubeKey.of(1, 0, 0);
        Environment environment = isolated(Map.of(a, OPEN, closed, CLOSED));
        Result r = pass(start(), List.of(new Release(a, Channel.TOXIC, 1e3 * N)), environment, PASS);
        r = passes(r, List.of(), environment, 50);
        assertEquals(1, r.field().size(), "only the source cube is active");
        assertTrue(r.field().find(closed) < 0, "the closed cube took nothing");
        assertEquals(0.0, r.ledger().get(Channel.TOXIC, Total.DISSIPATED));
        assertCloses(r, 1e-12);
    }

    @Test
    void aReleaseIntoAnUnloadedCubeStaysWholeUntilItLoads() {
        long a = CubeKey.of(2, 3, -5);
        Environment unloaded = MapEnvironment.uniform(Cell.UNLOADED, false, BOTTOM, TOP);
        double amount = 1e3 * N;
        Result r = pass(start(), List.of(new Release(a, Channel.CORROSIVE, amount)), unloaded, PASS);
        r = passes(r, List.of(), unloaded, 10);
        assertEquals(1, r.field().size());
        assertEquals(Double.doubleToLongBits(amount), Double.doubleToLongBits(r.field().amount(a, Channel.CORROSIVE)));
        Environment open = MapEnvironment.uniform(OPEN, false, BOTTOM, TOP);
        r = pass(r, List.of(), open, PASS);
        assertTrue(r.field().amount(a, Channel.CORROSIVE) < amount);
        assertEquals(7, r.field().size(), "the cube and its six faces");
        assertCloses(r, 1e-12);
    }

    @Test
    void twoCubesOfDifferentAirShareRelaxTowardEqualIndexNotEqualAmount() {
        long a = CubeKey.of(0, 0, 0);
        long b = CubeKey.of(1, 0, 0);
        Cell thin = new Cell(0.1, false, true);
        Environment environment = isolated(Map.of(a, ENCLOSED, b, thin));
        Result r = pass(start(), List.of(new Release(a, Channel.TOXIC, 1e3 * N)), environment, PASS);
        r = passes(r, List.of(), environment, 300);
        double ia = r.field().index(a, Channel.TOXIC, 1.0, S);
        double ib = r.field().index(b, Channel.TOXIC, 0.1, S);
        assertEquals(ia, ib, 1e-6 * ia, "equal index");
        assertEquals(10.0, r.field().amount(a, Channel.TOXIC) / r.field().amount(b, Channel.TOXIC), 1e-5,
                "amounts in the ratio of the air shares");
        assertCloses(r, 1e-12);
    }

    @Test
    void theTopAndTheBottomAreSinks() {
        long top = CubeKey.of(0, TOP, 0);
        long bottom = CubeKey.of(5, BOTTOM, 5);
        Environment environment = isolated(Map.of(top, OPEN, bottom, OPEN));
        double amount = 1e3 * N;
        Result r = pass(start(), List.of(new Release(top, Channel.TOXIC, amount),
                new Release(bottom, Channel.COMBUSTIBLE, amount)), environment, PASS);
        assertEquals(0.12 * amount, r.ledger().get(Channel.TOXIC, Total.DISSIPATED), 1e-12 * amount);
        assertEquals(0.12 * amount, r.ledger().get(Channel.COMBUSTIBLE, Total.DISSIPATED), 1e-12 * amount);
        assertCloses(r, 1e-12);
    }

    @Test
    void particulateOverAClosedCubeGoesToTheGround() {
        long a = CubeKey.of(0, 0, 0);
        long below = CubeKey.of(0, -1, 0);
        long floor = CubeKey.of(4, BOTTOM, 4);
        Environment environment = isolated(Map.of(a, OPEN, below, CLOSED, floor, OPEN));
        double amount = 1e3 * N;
        Result r = pass(start(), List.of(new Release(a, Channel.PARTICULATE, amount),
                new Release(floor, Channel.PARTICULATE, amount)), environment, PASS);
        // The cube over the closed cube settles 5 %; the cube at the bottom first loses 12 % to the bottom sink by
        // exchange (dissipated), then settles 5 % of the rest to the ground.
        assertEquals((0.05 + 0.05 * 0.88) * amount, r.ledger().get(Channel.PARTICULATE, Total.GROUND),
                1e-12 * amount);
        assertEquals(0.12 * amount, r.ledger().get(Channel.PARTICULATE, Total.DISSIPATED), 1e-12 * amount);
        assertTrue(r.field().find(below) < 0, "the closed cube holds nothing");
        assertCloses(r, 1e-12);
    }

    @Test
    void anIsolatedOpenCubeHalvesInTheOpenHalfLife() {
        long a = CubeKey.of(0, 0, 0);
        double amount = 1e3 * N;
        Environment open = isolated(Map.of(a, OPEN));
        Result r = pass(start(), List.of(new Release(a, Channel.TOXIC, amount)), open, PASS);
        r = passes(r, List.of(), open, (int) (S.halfLifeOpenSeconds() / PASS) - 1);
        assertEquals(0.5, r.field().amount(a, Channel.TOXIC) / amount, 1e-12);
        assertCloses(r, 1e-12);
    }

    @Test
    void anEnclosedCubeUsesTheLongHalfLife() {
        long a = CubeKey.of(0, 0, 0);
        double amount = 1e3 * N;
        Environment enclosed = isolated(Map.of(a, ENCLOSED));
        Result r = pass(start(), List.of(new Release(a, Channel.TOXIC, amount)), enclosed, PASS);
        r = passes(r, List.of(), enclosed, (int) (S.halfLifeEnclosedSeconds() / PASS) - 1);
        assertEquals(0.5, r.field().amount(a, Channel.TOXIC) / amount, 1e-12);
    }

    @Test
    void rainSpeedsOnlyCorrosiveAndParticulateInOpenCubes() {
        long a = CubeKey.of(0, 0, 0);
        long b = CubeKey.of(10, 0, 0);
        double amount = 1e3 * N;
        Environment rain = new MapEnvironment(Map.of(a, OPEN, b, ENCLOSED), Cell.UNLOADED, true, BOTTOM, TOP);
        List<Release> releases = new ArrayList<>();
        for (Channel c : Channel.values()) {
            releases.add(new Release(a, c, amount));
            releases.add(new Release(b, c, amount));
        }
        Result r = pass(start(), releases, rain, S.halfLifeOpenSeconds());
        // Below unloaded: nothing settles, so the particulate only decays.
        assertEquals(0.5, r.field().amount(a, Channel.TOXIC) / amount, 1e-12);
        assertEquals(0.5, r.field().amount(a, Channel.COMBUSTIBLE) / amount, 1e-12);
        assertEquals(1.0 / 16.0, r.field().amount(a, Channel.CORROSIVE) / amount, 1e-12);
        assertEquals(1.0 / 16.0, r.field().amount(a, Channel.PARTICULATE) / amount, 1e-12);
        double enclosed = Math.pow(0.5, S.halfLifeOpenSeconds() / S.halfLifeEnclosedSeconds());
        for (Channel c : Channel.values()) {
            assertEquals(enclosed, r.field().amount(b, c) / amount, 1e-12, c + " in the enclosed cube");
        }
        assertCloses(r, 1e-12);
    }

    @Test
    void shuffledReleasesGiveTheSameBits() {
        Random random = new Random(42);
        Map<Long, Cell> cells = new HashMap<>();
        List<Release> releases = new ArrayList<>();
        for (int x = -3; x < 3; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = -3; z < 3; z++) {
                    long key = CubeKey.of(x, y, z);
                    double phi = random.nextInt(4) == 0 ? 0.01 : 0.05 + 0.95 * random.nextDouble();
                    cells.put(key, new Cell(phi, random.nextBoolean(), random.nextInt(8) != 0));
                    if (random.nextInt(3) == 0) {
                        releases.add(new Release(key, Channel.values()[random.nextInt(4)], random.nextDouble() * 1e5));
                    }
                }
            }
        }
        releases.add(new Release(CubeKey.of(0, 1, 0), Channel.TOXIC, 3e4));
        releases.add(new Release(CubeKey.of(0, 1, 0), Channel.TOXIC, 2e4));
        Environment environment = new MapEnvironment(cells, OPEN, true, BOTTOM, TOP);
        Result first = start();
        Result second = start();
        Random order = new Random(7);
        for (int p = 0; p < 30; p++) {
            List<Release> shuffled = new ArrayList<>(releases);
            Collections.shuffle(shuffled, order);
            first = pass(first, releases, environment, PASS);
            second = pass(second, shuffled, environment, PASS);
        }
        assertArrayEquals(first.field().keys(), second.field().keys());
        double[] a = first.field().amounts();
        double[] b = second.field().amounts();
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(Double.doubleToLongBits(a[i]), Double.doubleToLongBits(b[i]), "amount " + i);
        }
        double[] la = first.ledger().values();
        double[] lb = second.ledger().values();
        for (int i = 0; i < la.length; i++) {
            assertEquals(Double.doubleToLongBits(la[i]), Double.doubleToLongBits(lb[i]), "ledger " + i);
        }
        assertCloses(first, 1e-9);
    }

    @Test
    void theCapRemovesTheLowestLoadedCubesFirst() {
        PollutionSettings capped = new PollutionSettings(0.12, 20, 300, 3600, 4, 0.05, 1e-3, 3, 0.02);
        Map<Long, Cell> cells = new HashMap<>();
        List<Release> releases = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long key = CubeKey.of(10 * i, 0, 0);
            cells.put(key, OPEN);
            releases.add(new Release(key, Channel.TOXIC, i * N));
        }
        long unloaded = CubeKey.of(100, 0, 0);
        releases.add(new Release(unloaded, Channel.TOXIC, 0.5 * N));
        Environment environment = isolated(cells);
        Result r = PollutionField.step(PollutionField.EMPTY, PollutionLedger.EMPTY, releases, environment, PASS,
                capped);
        assertEquals(3, r.field().size());
        assertTrue(r.field().find(CubeKey.of(40, 0, 0)) >= 0);
        assertTrue(r.field().find(CubeKey.of(50, 0, 0)) >= 0);
        assertTrue(r.field().find(unloaded) >= 0, "an unloaded cube is not capped");
        assertEquals(0.5 * N, r.field().amount(unloaded, Channel.TOXIC));
        assertTrue(r.ledger().get(Channel.TOXIC, Total.DISSIPATED) > 5.0 * N, "cubes 1 to 3 dissipated");
        assertCloses(r, 1e-12);
    }

    @Test
    void theSteadyH2sPlumeMatchesThePlanNumbers() {
        // 1 mol/s of H2S, 20 mol per pass, reference 100 ppm: 2e5 air-mole equivalents per pass, ΔI = 1.19.
        Environment open = MapEnvironment.uniform(OPEN, false, BOTTOM, TOP);
        long source = CubeKey.of(0, 4, 0);
        double perPass = 2e5;
        Result r = passes(start(), List.of(new Release(source, Channel.TOXIC, perPass)), open, 600);
        double atSource = r.field().index(source, Channel.TOXIC, 1.0, S);
        double neighbour = r.field().index(CubeKey.of(1, 4, 0), Channel.TOXIC, 1.0, S);
        double second = r.field().index(CubeKey.of(2, 4, 0), Channel.TOXIC, 1.0, S);
        double peak = atSource + perPass / N;
        // After a pass (release, exchange, decay) the source stands near 0.9 and rises to near 2.1 as the next pass's
        // release lands; a dense-grid estimate gives 0.895, 0.489 and 0.141 (tmp/wp2/steady.js).
        assertTrue(peak >= 1.0 && peak <= 3.0, "source with its fresh release " + peak);
        assertTrue(atSource >= 0.8 && atSource <= 1.0, "source after the pass " + atSource);
        assertTrue(neighbour >= 0.4 && neighbour <= 0.6, "face neighbour " + neighbour);
        assertTrue(second >= 0.1 && second <= 0.2, "second ring " + second);
        assertCloses(r, 1e-9);
    }

    @Test
    void ofRefusesUnsortedKeysNanAndNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> PollutionField.of(new long[] {2, 1}, new double[8]));
        assertThrows(IllegalArgumentException.class, () -> PollutionField.of(new long[] {1, 1}, new double[8]));
        assertThrows(IllegalArgumentException.class, () -> PollutionField.of(new long[] {1}, new double[3]));
        assertThrows(IllegalArgumentException.class,
                () -> PollutionField.of(new long[] {1}, new double[] {0, Double.NaN, 0, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> PollutionField.of(new long[] {1}, new double[] {0, 0, -1e-9, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> PollutionField.of(new long[] {1}, new double[] {0, 0, 0, Double.POSITIVE_INFINITY}));
        PollutionField field = PollutionField.of(new long[] {-5, 1}, new double[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertEquals(7.0, field.amount(1L, Channel.CORROSIVE));
        assertEquals(0.0, field.amount(3L, Channel.CORROSIVE));
        assertEquals(6.0, field.stored(Channel.TOXIC));
        assertThrows(IllegalArgumentException.class, () -> PollutionLedger.of(new double[19]));
        double[] bad = new double[PollutionLedger.LENGTH];
        bad[3] = -1;
        assertThrows(IllegalArgumentException.class, () -> PollutionLedger.of(bad));
        assertThrows(IllegalArgumentException.class, () -> PollutionField.step(PollutionField.EMPTY,
                PollutionLedger.EMPTY, List.of(), MapEnvironment.uniform(OPEN, false, 0, 1), 0.0, S));
    }

    @Test
    void burnBooksBurntAndSootAndBothLedgersClose() {
        long a = CubeKey.of(1, 1, 1);
        Environment unloaded = MapEnvironment.uniform(Cell.UNLOADED, false, BOTTOM, TOP);
        Result r = pass(start(), List.of(new Release(a, Channel.COMBUSTIBLE, 10 * N)), unloaded, PASS);
        r = PollutionField.burn(r.field(), r.ledger(), a, 4 * N, 0.02);
        assertEquals(6 * N, r.field().amount(a, Channel.COMBUSTIBLE), 1e-9 * N);
        assertEquals(0.08 * N, r.field().amount(a, Channel.PARTICULATE), 1e-12 * N);
        assertEquals(4 * N, r.ledger().get(Channel.COMBUSTIBLE, Total.BURNT));
        assertEquals(0.08 * N, r.ledger().get(Channel.PARTICULATE, Total.RELEASED), 1e-12 * N);
        assertCloses(r, 1e-12);
        r = PollutionField.burn(r.field(), r.ledger(), a, 100 * N, 0.02);
        assertEquals(0.0, r.field().amount(a, Channel.COMBUSTIBLE));
        assertEquals(10 * N, r.ledger().get(Channel.COMBUSTIBLE, Total.BURNT), 1e-9 * N);
        assertEquals(0.2 * N, r.field().amount(a, Channel.PARTICULATE), 1e-12 * N);
        assertCloses(r, 1e-12);
        r = PollutionField.burn(r.field(), r.ledger(), a, 1.0, 0.0);
        assertEquals(1, r.field().size(), "nothing left to burn changes nothing");
        Result none = PollutionField.burn(r.field(), r.ledger(), CubeKey.of(9, 9, 9), N, 0.02);
        assertSame(r.field(), none.field());
    }
}
