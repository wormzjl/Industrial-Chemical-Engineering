package com.wormzjl.icheme.client;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ValveGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A hand valve's turn (owner 2026-11-10: "add turning sound and animation for hand valve"): {@link ValveTurns}. */
class ValveTurnsTest {
    private static final double QUARTER = Math.PI / 2, EPS = 1e-9;
    private static final long AT = 42L;

    @Test
    void theLeverEasesAQuarterTurnOverOneSecond() {
        var turns = new ValveTurns();
        assertTrue(turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 1, 10), "the valve leaves the mesh");
        assertTrue(turns.apart(AT));
        assertEquals(0, turns.angle(AT, 10), EPS);
        assertEquals(QUARTER / 2, turns.angle(AT, 10.5), EPS);
        assertEquals(QUARTER, turns.angle(AT, 10 + ValveTurns.SECONDS), EPS);
        assertEquals(QUARTER, turns.angle(AT, 30), EPS, "past its end the lever stays at the target");
        double last = -1;
        for (int i = 0; i <= 20; i++) {
            double a = turns.angle(AT, 10 + i * .05);
            assertTrue(a >= last, "the lever never turns back during the turn");
            last = a;
        }
        assertTrue(turns.angle(AT, 10.05) < .01 * QUARTER, "a hand's start: slow at first");
        assertFalse(turns.settle(AT, 1, 10.9), "turning, even where the block state already agrees");
        assertTrue(turns.apart(AT));
    }

    @Test
    void theTurnEndsWhenTheBlockStateShowsTheTarget() {
        var turns = new ValveTurns();
        turns.start(AT, ValveTurns.Kind.SHUTOFF, 1, 0, 0);
        assertEquals(QUARTER, turns.angle(AT, 0), EPS, "from closed");
        assertTrue(turns.settle(AT, 0, 1.0), "back to the mesh: its section rebuilds");
        assertFalse(turns.apart(AT));
        assertEquals(0, turns.angle(AT, 1.1), EPS, "drawn a little longer while the section rebuilds");
        assertEquals(-1, turns.target(AT));
        assertFalse(turns.settle(AT, 0, 1.0 + ValveTurns.LINGER));
        assertTrue(Double.isNaN(turns.angle(AT, 2)));
        assertTrue(turns.positions().isEmpty(), "at rest nothing is drawn per frame");
    }

    @Test
    void theLeverWaitsForTheCommitAndTurnsBackAfterItsPatience() {
        var turns = new ValveTurns();
        turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 1, 0);
        assertFalse(turns.settle(AT, 0, 1.5));
        assertTrue(turns.apart(AT));
        assertEquals(QUARTER, turns.angle(AT, 1.5), EPS, "waiting at the target");
        assertFalse(turns.settle(AT, 0, ValveTurns.PATIENCE - .05));
        assertEquals(1, turns.target(AT));
        // Refused: the block state never shows the target; the lever turns back to it.
        double back = ValveTurns.PATIENCE;
        assertFalse(turns.settle(AT, 0, back));
        assertEquals(0, turns.target(AT));
        assertEquals(QUARTER, turns.angle(AT, back), EPS);
        assertEquals(QUARTER / 2, turns.angle(AT, back + .5), EPS);
        assertEquals(0, turns.angle(AT, back + 1), EPS);
        assertTrue(turns.settle(AT, 0, back + 1), "turned back, the block state agrees");
        assertFalse(turns.apart(AT));
    }

    @Test
    void aCommitDuringTheWaitEndsIt() {
        var turns = new ValveTurns();
        turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 1, 0);
        assertFalse(turns.settle(AT, 0, 3));
        assertTrue(turns.settle(AT, 1, 5), "the commit arrived at 5 s");
        assertEquals(QUARTER, turns.angle(AT, 5), EPS);
    }

    @Test
    void startsAreIdempotentAndANewTargetReAims() {
        var turns = new ValveTurns();
        assertTrue(turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 1, 0));
        assertFalse(turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 1, .3), "the server's block event repeats the client's start");
        assertEquals(QUARTER / 2, turns.angle(AT, .5), EPS, "the repeat does not restart the turn");
        assertFalse(turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 0, .5), "re-aimed, already apart: no rebuild");
        assertEquals(QUARTER / 2, turns.angle(AT, .5), EPS, "from where the lever is");
        assertEquals(0, turns.angle(AT, 1.5), EPS);
        assertEquals(0, turns.target(AT));
        assertFalse(new ValveTurns().start(AT, ValveTurns.Kind.SHUTOFF, 1, 1, 0), "nothing to turn");
    }

    @Test
    void aDiverterTurnsToItsNextOutletAndEndsWithItsMotion() {
        assertEquals(0, ValveTurns.angle(ValveTurns.Kind.DIVERTER, 0), EPS);
        assertEquals(QUARTER, ValveTurns.angle(ValveTurns.Kind.DIVERTER, 1), EPS);
        assertEquals(-QUARTER, ValveTurns.angle(ValveTurns.Kind.DIVERTER, 2), EPS);
        var turns = new ValveTurns();
        turns.start(AT, ValveTurns.Kind.DIVERTER, 1, 2, 0);
        assertEquals(0, turns.angle(AT, .5), EPS, "half way from +90 to -90 degrees");
        assertFalse(turns.settle(AT, -1, .99));
        assertTrue(turns.settle(AT, -1, 1), "no block state to wait for: its square case looks the same at every outlet");
    }

    @Test
    void forgettingAValveDropsItsTurn() {
        var turns = new ValveTurns();
        turns.start(AT, ValveTurns.Kind.SHUTOFF, 0, 1, 0);
        assertTrue(turns.forget(AT));
        assertFalse(turns.forget(AT));
        assertTrue(turns.positions().isEmpty());
    }

    /** The lever drawn apart is the baked one: the casting plus the lever is the whole valve, and a quarter turn about the stem is the closed lever. */
    @Test
    void theLeverApartTurnsIntoTheClosedLever() {
        for (int facing : new int[]{5, 1, 2}) {
            var key = new LineGeometry.Key(TopologyCompiler.Kind.PIPE, facing, 3 << (facing & ~1), PipeSizes.code(PipeSizes.outside(.1)), 0, 0, false);
            var lever = ValveGeometry.shutoffLever(key, RenderDetail.NORMAL);
            var body = ValveGeometry.shutoff(key, true, false, RenderDetail.NORMAL, false);
            assertEquals(vertices(ValveGeometry.shutoff(key, true, false, RenderDetail.NORMAL)), vertices(body) + vertices(lever));
            var closed = ValveGeometry.shutoff(key, false, false, RenderDetail.NORMAL);
            var closedLever = difference(closed, ValveGeometry.shutoff(key, false, false, RenderDetail.NORMAL, false));
            double[] pivot = ValveGeometry.shutoffPivot(key), axis = ValveGeometry.shutoffStem(key);
            assertArrayEquals(bounds(closedLever), bounds(rotated(lever, pivot, axis, QUARTER)), 1e-6, "facing " + facing);
        }
    }

    private static int vertices(List<Mesh.Polygon> polygons) {
        return polygons.stream().mapToInt(Mesh.Polygon::size).sum();
    }

    /** The vertices of {@code all} that {@code part} lacks, as a multiset. */
    private static List<double[]> difference(List<Mesh.Polygon> all, List<Mesh.Polygon> part) {
        var counts = new java.util.HashMap<String, Integer>();
        for (var p : points(part)) counts.merge(name(p), 1, Integer::sum);
        var out = new java.util.ArrayList<double[]>();
        for (var p : points(all)) {
            int left = counts.getOrDefault(name(p), 0);
            if (left > 0) counts.put(name(p), left - 1);
            else out.add(p);
        }
        return out;
    }

    private static List<double[]> points(List<Mesh.Polygon> polygons) {
        var out = new java.util.ArrayList<double[]>();
        for (var polygon : polygons) for (int i = 0; i < polygon.positions().length; i += 3)
            out.add(new double[]{polygon.positions()[i], polygon.positions()[i + 1], polygon.positions()[i + 2]});
        return out;
    }

    private static String name(double[] p) {
        return Math.round(p[0] * 1e6) + "," + Math.round(p[1] * 1e6) + "," + Math.round(p[2] * 1e6);
    }

    /** Rodrigues' rotation of every vertex by {@code angle} about unit {@code axis} through {@code pivot}. */
    private static List<double[]> rotated(List<Mesh.Polygon> polygons, double[] pivot, double[] axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return points(polygons).stream().map(p -> {
            double[] v = {p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]};
            double dot = v[0] * axis[0] + v[1] * axis[1] + v[2] * axis[2];
            double[] k = {axis[1] * v[2] - axis[2] * v[1], axis[2] * v[0] - axis[0] * v[2], axis[0] * v[1] - axis[1] * v[0]};
            double[] r = new double[3];
            for (int i = 0; i < 3; i++) r[i] = pivot[i] + v[i] * c + k[i] * s + axis[i] * dot * (1 - c);
            return r;
        }).toList();
    }

    private static double[] bounds(List<double[]> points) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var p : points) for (int i = 0; i < 3; i++) {
            b[i] = Math.min(b[i], p[i]);
            b[i + 3] = Math.max(b[i + 3], p[i]);
        }
        return b;
    }
}
