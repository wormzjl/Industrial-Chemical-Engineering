package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Clip;
import com.wormzjl.icheme.science.tutorial.scene.DotMotion;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Projection;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The scene player's rules (batch 2026-11-12-tutorial-system, P0): {@link Scene#at} is pure, tracks act as they say. */
class SceneTest {
    /** A cube of side 2 round the origin, its faces counter-clockwise from outside. */
    static Actor cube(String id) {
        var faces = new ArrayList<Mesh.Polygon>();
        int[][] axes = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1}};
        for (var a : axes)
            for (int sign : new int[]{-1, 1}) {
                // The face at a[0] = sign, its corners round in the (a[1], a[2]) plane, wound outward.
                double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                double[] p = new double[12], n = new double[12], uv = new double[8];
                for (int k = 0; k < 4; k++) {
                    var c = corners[sign > 0 ? k : 3 - k];
                    p[3 * k + a[0]] = sign;
                    p[3 * k + a[1]] = c[0];
                    p[3 * k + a[2]] = c[1];
                    n[3 * k + a[0]] = sign;
                    uv[2 * k] = (c[0] + 1) / 2;
                    uv[2 * k + 1] = (c[1] + 1) / 2;
                }
                faces.add(new Mesh.Polygon(p, uv, n, Mesh.Material.SHELL));
            }
        return new Actor(id, faces);
    }

    static Scene scene() {
        var box = new Track.Box(new double[]{-.5, -.5, -.5}, new double[]{.5, .5, .5});
        var line = new Track.Line(List.of(new double[]{0, 0, 0}, new double[]{0, 3, 0}, new double[]{2, 3, 0}), .05, box, 4, .5, .1);
        return Scene.builder("t")
                .actor(cube("a")).actor(cube("b"))
                .track(new Track.Camera(List.of(new Track.Key(0, 0, 0, 20, 0, 0, 0), new Track.Key(2, 1, .5, 40, 1, 1, 1), new Track.Key(5, 1.2, .3, 30, 0, 1, 0))))
                .track(new Track.Visible("b", 1, 3, .5))
                .track(new Track.Cut("a", new double[]{0, 0, 0}, new double[]{1, 0, 1}, 1, 2))
                .track(new Track.Caption("k1", 0, 2))
                .track(new Track.Caption("k2", 2, 6))
                .track(new Track.Dots(Track.Kind.HEAVY, box, 0, 6, 5, .2))
                .track(new Track.Dots(Track.Kind.LIGHT, line, 2, 6, 6, 2))
                .stop(2).stop(4).stop(6)
                .build();
    }

    @Test
    void theSameTimeGivesTheSameFrame() {
        var s = scene();
        for (double t = 0; t <= 6; t += .37) assertEquals(s.at(t), s.at(t), "t = " + t);
        // A scene built again has new polygon arrays (records compare arrays by identity); all else is equal.
        var a = s.at(2.5);
        var b = scene().at(2.5);
        assertEquals(a.camera(), b.camera());
        assertEquals(a.dots(), b.dots(), "a scene built again places its dots to the bit");
        assertEquals(a.captions(), b.captions());
        assertEquals(a.stop(), b.stop());
    }

    @Test
    void stopsAscendInsideTheLength() {
        var s = scene();
        assertEquals(6, s.length());
        for (int i = 1; i < s.stops().size(); i++) assertTrue(s.stops().get(i) > s.stops().get(i - 1));
        assertEquals(s.length(), s.stops().getLast());
        assertThrows(IllegalArgumentException.class, () -> Scene.builder("t").track(new Track.Camera(List.of(new Track.Key(0, 0, 0, 1, 0, 0, 0)))).stop(2).stop(1).build());
        assertEquals(0, s.at(0).stop());
        assertEquals(0, s.at(2).stop(), "at a stop, the frame is that stop's");
        assertEquals(1, s.at(2.01).stop());
        assertEquals(2, s.at(6).stop());
        assertEquals(List.of("k1"), s.at(2).captions(), "a stop shows the caption that led up to it");
        assertEquals(List.of("k1"), s.at(0).captions());
        assertEquals(List.of("k2"), s.at(2.01).captions());
    }

    @Test
    void aVisibleTrackFadesInAndOutAtItsEdges() {
        var s = scene();
        assertNull(actor(s.at(1), "b"), "not drawn at its start");
        assertEquals(.5, actor(s.at(1.25), "b").alpha(), 1e-12);
        assertEquals(1, actor(s.at(1.5), "b").alpha(), 1e-12);
        assertEquals(1, actor(s.at(2.5), "b").alpha(), 1e-12);
        assertEquals(.5, actor(s.at(2.75), "b").alpha(), 1e-12);
        assertNull(actor(s.at(3), "b"), "gone at its end");
        assertNull(actor(s.at(.5), "b"));
        assertEquals(1, actor(s.at(.5), "a").alpha(), "an actor with no visibility track is always drawn");
    }

    @Test
    void theCameraIsContinuousAtItsKeyframes() {
        var camera = (Track.Camera) scene().tracks().getFirst();
        for (double key : new double[]{0, 2, 5}) {
            var before = camera.at(key - 1e-7);
            var at = camera.at(key);
            var after = camera.at(key + 1e-7);
            for (var c : List.of(before, after)) {
                assertEquals(at.yaw(), c.yaw(), 1e-6);
                assertEquals(at.pitch(), c.pitch(), 1e-6);
                assertEquals(at.scale(), c.scale(), 1e-5);
                assertEquals(at.y(), c.y(), 1e-6);
            }
        }
        assertEquals(new Frame.CameraState(1, .5, 40, 1, 1, 1), camera.at(2));
        assertEquals(camera.at(5), camera.at(9), "constant after the last keyframe");
    }

    @Test
    void aClippedPolygonIsConvexOnTheKeptSideAndKeepsItsArea() {
        double[] point = {.3, -.2, .1}, normal = unit(new double[]{1, .7, -.4}), minus = {-normal[0], -normal[1], -normal[2]};
        for (var face : cube("c").polygons()) {
            var kept = Clip.keep(face, point, normal);
            var removed = Clip.keep(face, point, minus);
            double whole = Clip.area(face), sum = (kept == null ? 0 : Clip.area(kept)) + (removed == null ? 0 : Clip.area(removed));
            assertEquals(whole, sum, 1e-12, "kept plus removed is the whole face");
            for (var part : new Mesh.Polygon[]{kept, removed}) {
                if (part == null) continue;
                assertEquals(part.size() * 3, part.positions().length);
                assertEquals(part.size() * 3, part.normals().length);
                assertEquals(part.size() * 2, part.uvs().length);
                assertConvex(part, face);
            }
            if (kept != null)
                for (int i = 0; i < kept.size(); i++) assertTrue(side(kept, i, point, normal) <= 1e-9, "on the kept side");
        }
    }

    @Test
    void theSameCutStepGivesTheSameListAndTheCutMovesIn() {
        var s = scene();
        assertSame(actor(s.at(.5), "a").polygons(), s.actors().getFirst().polygons(), "before the cut, the whole actor");
        assertFalse(actor(s.at(.5), "a").twoSided());
        var a = actor(s.at(1.1), "a");
        assertSame(a.polygons(), actor(s.at(1.2), "a").polygons(), "one step, one list instance");
        assertTrue(a.twoSided());
        var last = actor(s.at(3), "a").polygons();
        assertSame(last, actor(s.at(6), "a").polygons());
        assertNotSame(a.polygons(), last);
        double before = area(a.polygons()), after = area(last);
        assertTrue(after < before && after > 0, before + " then " + after);
        // At its place the plane through the centre with normal (1, 0, 1) keeps half the cube's surface.
        assertEquals(12, after, 1e-9);
        for (var polygon : last)
            for (int i = 0; i < polygon.size(); i++) assertTrue(side(polygon, i, new double[3], unit(new double[]{1, 0, 1})) <= 1e-9);
    }

    @Test
    void dotsAreDeterministicAndStayOnTheirPathOrInTheirBox() {
        var s = scene();
        var box = new Track.Box(new double[]{-.5, -.5, -.5}, new double[]{.5, .5, .5});
        var lineTrack = (Track.Dots) s.tracks().stream().filter(t -> t instanceof Track.Dots d && d.path() instanceof Track.Line).findFirst().orElseThrow();
        var line = (Track.Line) lineTrack.path();
        boolean moved = false;
        for (double t = 0; t <= 6; t += .05) {
            var dots = s.at(t).dots();
            assertEquals(dots, s.at(t).dots());
            for (var d : dots) {
                if (d.kind() == Track.Kind.HEAVY) {
                    assertTrue(box.contains(d.x(), d.y(), d.z(), 1e-12), "in its box at " + t);
                    continue;
                }
                if (box.contains(d.x(), d.y(), d.z(), 1e-12)) continue;
                moved = true;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < line.leaving(); i++) best = Math.min(best, distance(DotMotion.path(line, Track.Kind.LIGHT.ordinal(), i, lineTrack), d));
                assertTrue(best <= line.radius() + 1e-9, "on its line at " + t + ": " + best);
            }
        }
        assertTrue(moved, "some dots left the box");
        long heavy = s.at(6).dots().stream().filter(d -> d.kind() == Track.Kind.HEAVY).count();
        assertEquals(5, heavy);
        assertTrue(s.at(6).dots().stream().filter(d -> d.kind() == Track.Kind.LIGHT).count() < 6, "a dot that reached the end is gone");
    }

    @Test
    void theProjectionKeepsTheTargetAtTheCentreAndTheWindingAsSeen() {
        var p = new Projection(.72, .42, 30, new double[]{1, 2, 3}, 100, 50);
        var c = p.screen(1, 2, 3);
        assertEquals(100, c[0], 1e-9);
        assertEquals(50, c[1], 1e-9);
        assertEquals(0, c[2], 1e-9);
        var r = p.right();
        var u = p.up();
        var f = p.forward();
        double[] ru = {r[1] * u[2] - r[2] * u[1], r[2] * u[0] - r[0] * u[2], r[0] * u[1] - r[1] * u[0]};
        for (int a = 0; a < 3; a++) assertEquals(-f[a], ru[a], 1e-12, "r x u = -f");
        var m = p.affine();
        var q = p.screen(-.4, 1.3, 2.2);
        for (int row = 0; row < 3; row++) assertEquals(q[row], m[row][0] * -.4 + m[row][1] * 1.3 + m[row][2] * 2.2 + m[row][3], 1e-9);
        var front = new Projection(0, 0, 1, new double[3], 0, 0);
        assertEquals(1, front.forward()[2], 1e-12, "yaw 0 looks along +z");
        assertEquals(-1, front.right()[0], 1e-12, "seen from the front, +x is on the left");
    }

    private static Frame.ActorFrame actor(Frame frame, String id) {
        return frame.actors().stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    private static double area(List<Mesh.Polygon> polygons) {
        return polygons.stream().mapToDouble(Clip::area).sum();
    }

    private static double side(Mesh.Polygon polygon, int i, double[] point, double[] normal) {
        var p = polygon.positions();
        return (p[3 * i] - point[0]) * normal[0] + (p[3 * i + 1] - point[1]) * normal[1] + (p[3 * i + 2] - point[2]) * normal[2];
    }

    /** Every turn of {@code part} bends the same way as {@code whole}'s face normal. */
    private static void assertConvex(Mesh.Polygon part, Mesh.Polygon whole) {
        var n = whole.normals();
        var p = part.positions();
        int m = part.size();
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m, k = (i + 2) % m;
            double ax = p[3 * j] - p[3 * i], ay = p[3 * j + 1] - p[3 * i + 1], az = p[3 * j + 2] - p[3 * i + 2];
            double bx = p[3 * k] - p[3 * j], by = p[3 * k + 1] - p[3 * j + 1], bz = p[3 * k + 2] - p[3 * j + 2];
            double turn = (ay * bz - az * by) * n[0] + (az * bx - ax * bz) * n[1] + (ax * by - ay * bx) * n[2];
            assertTrue(turn >= -1e-12, "convex and counter-clockwise");
        }
    }

    private static double distance(List<double[]> path, Frame.DotFrame d) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < path.size(); i++) {
            var a = path.get(i);
            var b = path.get(i + 1);
            double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, ap = {d.x() - a[0], d.y() - a[1], d.z() - a[2]};
            double l = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2], f = l > 0 ? Math.max(0, Math.min(1, (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / l)) : 0;
            double x = ap[0] - f * ab[0], y = ap[1] - f * ab[1], z = ap[2] - f * ab[2];
            best = Math.min(best, Math.sqrt(x * x + y * y + z * z));
        }
        return best;
    }

    private static double[] unit(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    /** P2b's tracks on the cube scene: labels, legend, fire, liquid, readouts, pointer and thinned dots. */
    static Scene overlays() {
        var box = new Track.Box(new double[]{-.5, -.5, -.5}, new double[]{.5, .5, .5});
        var series = new com.wormzjl.icheme.science.tutorial.scene.Series(new double[]{0, 100, 200}, new double[]{300, 400, 450});
        var timeline = new com.wormzjl.icheme.science.tutorial.scene.Value.Timeline(new double[]{0, 6}, new double[]{0, 200});
        return Scene.builder("t")
                .actor(cube("a"))
                .track(new Track.Camera(List.of(new Track.Key(0, .5, .4, 40, 0, 0, 0))))
                .track(new Track.Cut("a", new double[]{0, 0, 0}, new double[]{0, 0, -1}, 1, 1))
                .track(new Track.Label(new double[]{1, 1, -1}, "a", 0, 2))
                .track(new Track.Legend(2, 6))
                .track(new Track.Flame(new double[]{0, -1, 0}, com.wormzjl.icheme.science.tutorial.scene.Value.of(.4), 3, 6))
                .track(new Track.Level(new Track.Box(new double[]{-.9, -.9, -.9}, new double[]{.9, .9, .9}), t -> 1 - t / 12, 0x3B2A1A, "a", 0, 6))
                .track(new Track.Readout("pot", timeline.replay(series), Track.Reading.TEMPERATURE, 0, 6))
                .track(new Track.Readout("share", t -> t / 6, Track.Reading.SHARE, 4, 6))
                .track(new Track.Pointer(Track.Sign.RIGHT_CLICK, "", new double[]{0, 1, 0}, 0, 1))
                .track(new Track.Dots(Track.Kind.LIGHT, box, 0, 6, 10, .2, t -> 1 - t / 6))
                .stop(2).stop(6)
                .build();
    }

    @Test
    void theNewTracksActOnTheirIntervalsAndAFrameStaysPure() {
        var s = overlays();
        for (double t = 0; t <= 6; t += .41) assertEquals(s.at(t), s.at(t), "t = " + t);
        var start = s.at(.5);
        assertEquals(1, start.labels().size());
        assertFalse(start.legend());
        assertTrue(start.flames().isEmpty());
        assertEquals(1, start.pointers().size());
        assertEquals(Track.Sign.RIGHT_CLICK, start.pointers().getFirst().sign());
        assertEquals(1, start.readouts().size(), "the share readout starts at 4");
        assertEquals(300 + 100 * (.5 * 200 / 6) / 100, start.readouts().getFirst().value(), 1e-9, "the series replayed on the film's time");
        var late = s.at(5);
        assertTrue(late.labels().isEmpty());
        assertTrue(late.legend());
        assertEquals(1, late.flames().size());
        assertEquals(.4, late.flames().getFirst().size());
        assertTrue(late.pointers().isEmpty());
        assertEquals(2, late.readouts().size());
        assertEquals(5 / 6.0, late.readouts().get(1).value(), 1e-12);
        assertEquals(Math.round(10 * (1 - 5 / 6.0)), late.dots().size(), "the share thins the dots");
        assertEquals(10, s.at(0).dots().size());
        var liquid = late.liquids().getFirst();
        assertEquals(-.9 + (1 - 5 / 12.0) * 1.8, liquid.top(), 1e-12, "the level's share of the box's height");
        assertEquals(.9, liquid.radius(), 1e-12);
    }

    @Test
    void moreThanTwoReadoutsAtOnceAreRefused() {
        var b = Scene.builder("t").actor(cube("a")).track(new Track.Camera(List.of(new Track.Key(0, 0, 0, 1, 0, 0, 0))));
        for (int i = 0; i < 3; i++) b.track(new Track.Readout("r" + i, t -> 0, Track.Reading.SHARE, i, 5));
        assertThrows(IllegalArgumentException.class, () -> b.stop(5).build());
        assertThrows(IllegalArgumentException.class, () -> Scene.builder("t").actor(cube("a")).track(new Track.Camera(List.of(new Track.Key(0, 0, 0, 1, 0, 0, 0))))
                .track(new Track.Level(new Track.Box(new double[3], new double[]{1, 1, 1}), t -> 1, 0, "none", 0, 1)).stop(1).build(), "a level cut by no actor");
    }

    @Test
    void theLiquidIsCutWithItsActorAndShowsItsSection() {
        var s = overlays();
        assertNull(s.at(.5).liquids().getFirst().cut(), "whole before the cut");
        var cut = s.at(3).liquids().getFirst();
        assertNotNull(cut.cut());
        var faces = com.wormzjl.icheme.science.tutorial.scene.Bodies.liquid(cut);
        var plane = cut.cut();
        boolean section = false;
        for (var face : faces) {
            var p = face.positions();
            boolean onPlane = true;
            for (int i = 0; i < face.size(); i++) {
                double d = (p[3 * i] - plane.px()) * plane.nx() + (p[3 * i + 1] - plane.py()) * plane.ny() + (p[3 * i + 2] - plane.pz()) * plane.nz();
                assertTrue(d <= 1e-9, "every face on the kept side");
                onPlane &= Math.abs(d) <= 1e-9;
            }
            section |= onPlane;
        }
        assertTrue(section, "the cut's face closes the liquid");
        int whole = com.wormzjl.icheme.science.tutorial.scene.Bodies.liquid(s.at(.5).liquids().getFirst()).size();
        assertEquals(com.wormzjl.icheme.science.tutorial.scene.Bodies.SIDES + 2, whole);
    }

    @Test
    void aFireFlickersByTimeAloneAndStaysWithinItsSize() {
        var p = new Projection(.5, .4, 40, new double[3], 0, 0);
        var f = new Frame.FlameFrame(0, 0, 0, .4, 3.3);
        var a = com.wormzjl.icheme.science.tutorial.scene.Bodies.flame(f, p.right(), p.up(), p.forward());
        var b = com.wormzjl.icheme.science.tutorial.scene.Bodies.flame(f, p.right(), p.up(), p.forward());
        assertEquals(3 * com.wormzjl.icheme.science.tutorial.scene.Bodies.TONGUES, a.size());
        for (int i = 0; i < a.size(); i++) assertArrayEquals(a.get(i).c(), b.get(i).c(), "the same time, the same fire");
        var later = com.wormzjl.icheme.science.tutorial.scene.Bodies.flame(new Frame.FlameFrame(0, 0, 0, .4, 3.9), p.right(), p.up(), p.forward());
        boolean moved = false;
        for (int i = 0; i < a.size(); i++) moved |= !java.util.Arrays.equals(a.get(i).c(), later.get(i).c());
        assertTrue(moved, "it flickers");
        var up = p.up();
        for (var t : a) {
            double tall = t.c()[0] * up[0] + t.c()[1] * up[1] + t.c()[2] * up[2];
            assertTrue(tall > 0 && tall <= .4 + 1e-9, "within its size: " + tall);
        }
    }
}
