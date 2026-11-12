package com.wormzjl.icheme.science.tutorial.films;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import com.wormzjl.icheme.science.tutorial.scene.Value;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The film "the small tower" (batch 2026-11-12-tutorial-system, P4b; guide step {@code tower/many}): the bundled "Small crude
 * tower (Medium crude)" ({@code columnpreset/small_crude.json}: its trays, feed, middle draw, pumparound and bottom steam)
 * built as the constructor builds it ({@link EquipmentShape#column}), cut open, in four stops. 1: the whole tower, the crude
 * going in near its foot. 2: close on the trays from the feed up to the middle draw, the shell's near half cut away: round
 * plates with liquid on them and a downcomer at alternate sides; light dots climb through the plates, heavy ones run down
 * from plate to plate by the downcomers, middle ones leave at the draw's tray. 3: the products leave at their heights: the
 * top, the middle draw, the bottom. 4: the cooling loop and the steam. Every place is the shape's own: the nozzles by role,
 * the trays at {@link ColumnTrays#trayHeight} over the bottom tangent line (the skirt's top).
 */
public final class SmallTower {
    public static final String NAME = "small_tower";
    public static final String TITLE = "tutorial.icheme.film." + NAME;
    public static final String PRESET = "/assets/icheme/columnpreset/small_crude.json";
    static final double STOP_1 = 5, STOP_2 = 12, STOP_3 = 20, STOP_4 = 27;
    static final double YAW = .62, PITCH = .25, SCALE = 9.5, CLOSE_PITCH = .32, CLOSE_SCALE = 44;
    /** A plate's radius over the shell's, the downcomer's chord from the axis over the plate's, a plate's thickness and its liquid's depth, m. */
    static final double PLATE = .96, CHORD = .62, DECK = .03, LIQUID = .06;
    public static final int TRAY_LIQUID = 0xB07A3A;
    /** The skirt the film stands the tower on, whole blocks. */
    public static final int SKIRT = 2;
    public static final String FEED = "tutorial.icheme.film.part.crude_in", TOP = "tutorial.icheme.film.part.top_product", MIDDLE = "tutorial.icheme.film.part.middle_product",
            BOTTOM = "tutorial.icheme.film.part.bottom_product", LOOP = "tutorial.icheme.film.part.cooling_loop", STEAM = "tutorial.icheme.film.part.steam";

    private SmallTower() {
    }

    /** The preset's diameter and trays: tray count, feed, draws, steam and pumparounds as the column calculator numbers them. */
    public record Preset(double diameter, ColumnTrays trays) {
    }

    public static Preset preset() {
        try (var in = SmallTower.class.getResourceAsStream(PRESET)) {
            var o = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in, PRESET), StandardCharsets.UTF_8)).getAsJsonObject();
            var draws = new ArrayList<Integer>();
            for (var d : o.getAsJsonArray("draws")) draws.add(d.getAsJsonObject().get("tray").getAsInt());
            var steam = new ArrayList<Integer>();
            for (var s : o.getAsJsonArray("steam")) steam.add(s.getAsJsonObject().get("tray").getAsInt());
            var loops = new ArrayList<ColumnTrays.Pumparound>();
            for (var p : o.getAsJsonArray("pumparounds")) {
                JsonObject q = p.getAsJsonObject();
                loops.add(new ColumnTrays.Pumparound(q.get("draw_tray").getAsInt(), q.get("return_tray").getAsInt()));
            }
            return new Preset(o.get("diameter_m").getAsDouble(), new ColumnTrays(o.get("tray_count").getAsInt(), o.get("feed_tray").getAsInt(), draws, steam, loops));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static EquipmentShape shape(Preset p) {
        return EquipmentShape.column(p.diameter(), p.trays(), EquipmentShape.Heading.NORTH, SKIRT);
    }

    /** The first nozzle of {@code role}'s outer end. */
    static double[] nozzle(EquipmentShape shape, EquipmentShape.Role role) {
        for (var n : shape.nozzles())
            if (n.role() == role) {
                var d = n.direction();
                var b = n.base();
                return new double[]{b[0] + d[0] * n.length(), b[1] + d[1] * n.length(), b[2] + d[2] * n.length()};
            }
        throw new IllegalStateException("The small tower has no " + role + " nozzle: " + shape.nozzles().stream().map(x -> x.role().name()).toList());
    }

    /** The shell's axis (x, z): the top nozzle's base stands on it. */
    public static double[] axis(EquipmentShape shape) {
        for (var n : shape.nozzles()) if (n.role() == EquipmentShape.Role.VAPOUR_OUTLET) return new double[]{n.base()[0], n.base()[2]};
        throw new IllegalStateException("The small tower has no top nozzle");
    }

    /** Tray {@code k}'s deck height (1 at the top), m: over the bottom tangent line, the skirt's top. */
    public static double tray(Preset p, int k) {
        return SKIRT + p.trays().trayHeight(p.diameter(), k);
    }

    public static Scene build() {
        var p = preset();
        var shape = shape(p);
        var tower = Actors.equipment("tower", shape);
        var axis = axis(shape);
        double x = axis[0], z = axis[1], r = p.diameter() / 2;
        int n = p.trays().trays();
        double[] toCamera = {-DeterministicMath.sin(YAW), 0, -DeterministicMath.cos(YAW)};
        // Across the view: the downcomers stand at alternate ends of this direction, so the cut shows each in profile.
        double[] across = {DeterministicMath.cos(YAW), 0, -DeterministicMath.sin(YAW)};
        double plate = PLATE * r, chord = CHORD * plate, half = Math.sqrt(plate * plate - chord * chord);
        var decks = new ArrayList<Mesh.Polygon>();
        for (int k = 1; k <= n; k++) {
            double y = tray(p, k), side = side(k);
            decks.addAll(plate(x, z, plate, side * chord, across, toCamera, y - DECK / 2, y + DECK / 2));
            // The downcomer: a wall at the open chord from the plate down most of the way to the next.
            double[] a = at(x, z, across, toCamera, side * chord, -half), b = at(x, z, across, toCamera, side * chord, half);
            decks.addAll(prism(List.of(a, b, at(x, z, across, toCamera, side * (chord + .03), half), at(x, z, across, toCamera, side * (chord + .03), -half)),
                    y - .75 * ColumnTrays.SPACING, y + DECK / 2));
        }
        var trays = new Actor("trays", decks);
        double top = tray(p, 1), bottom = tray(p, n), feed = tray(p, p.trays().feed()), draw = tray(p, p.trays().draws().getFirst());
        double middle = (tray(p, 1) + 2.5) / 2;
        double[] feedIn = nozzle(shape, EquipmentShape.Role.FEED), over = nozzle(shape, EquipmentShape.Role.VAPOUR_OUTLET), side = nozzle(shape, EquipmentShape.Role.DRAW),
                foot = nozzle(shape, EquipmentShape.Role.BOTTOMS), loop = nozzle(shape, EquipmentShape.Role.PUMPAROUND_DRAW), steam = nozzle(shape, EquipmentShape.Role.VAPOUR_RETURN);
        // The dots' ways from the feed: the light up to the top and out, the middle to the draw, the heavy down to the foot.
        var source = new Track.Box(new double[]{x - .4 * r, feed - .3, z - .4 * r}, new double[]{x + .4 * r, feed + .3, z + .4 * r});
        var up = List.of(new double[]{x, feed, z}, new double[]{x, top + .6, z}, over);
        var toDraw = List.of(new double[]{x, feed, z}, new double[]{x, draw, z}, side);
        // The liquid's way down from the top plate (the liquid sent back): over each plate to its downcomer, down it to the
        // plate below, and on to the foot.
        var down = new ArrayList<double[]>();
        down.add(new double[]{x, top + LIQUID, z});
        for (int k = 1; k <= n; k++) {
            var edge = at(x, z, across, toCamera, side(k) * (chord + .1), 0);
            down.add(new double[]{edge[0], tray(p, k) + LIQUID, edge[1]});
            down.add(new double[]{edge[0], (k < n ? tray(p, k + 1) : bottom - .5) + LIQUID, edge[1]});
        }
        down.add(foot);
        double spread = .55 * r;
        var b = Scene.builder(TITLE)
                .actor(tower).actor(trays)
                .track(new Track.Camera(List.of(
                        new Track.Key(0, 0, .1, SCALE, x, middle, z),
                        new Track.Key(STOP_1, YAW, PITCH, SCALE, x, middle, z),
                        // Stop 2 closes in on the trays from the feed up to the draw, where the dots part; stops 3 and 4 show the whole tower again.
                        new Track.Key(STOP_1 + 3, YAW, CLOSE_PITCH, CLOSE_SCALE, x, (feed + draw) / 2, z),
                        new Track.Key(STOP_2, YAW, CLOSE_PITCH, CLOSE_SCALE, x, (feed + draw) / 2, z),
                        new Track.Key(STOP_2 + 3, YAW, PITCH, SCALE, x, middle, z),
                        new Track.Key(STOP_4, YAW, PITCH, SCALE, x, middle, z))))
                .track(new Track.Visible("trays", STOP_1, STOP_4, .5))
                .track(new Track.Cut("tower", new double[]{x, 0, z}, toCamera, STOP_1 + .5, 2))
                .track(new Track.Caption(TITLE + ".s1", 0, STOP_1))
                .track(new Track.Caption(TITLE + ".s2", STOP_1, STOP_2))
                .track(new Track.Caption(TITLE + ".s3", STOP_2, STOP_3))
                .track(new Track.Caption(TITLE + ".s4", STOP_3, STOP_4))
                .track(new Track.Label(feedIn, FEED, 0, STOP_1))
                // Stop 2: where the crude comes in and where the middle draw leaves, each at its own tray on the far wall.
                .track(new Track.Label(wall(x, z, across, toCamera, -plate, feed), FEED, STOP_1, STOP_2))
                .track(new Track.Label(wall(x, z, across, toCamera, plate, draw), MIDDLE, STOP_1, STOP_2))
                .track(new Track.Label(over, TOP, STOP_2, STOP_3))
                .track(new Track.Label(side, MIDDLE, STOP_2, STOP_3))
                .track(new Track.Label(foot, BOTTOM, STOP_2, STOP_3))
                .track(new Track.Label(loop, LOOP, STOP_3, STOP_4))
                .track(new Track.Label(steam, STEAM, STOP_3, STOP_4))
                .track(new Track.Legend(STOP_1, STOP_4));
        // Each plate's liquid, inside its downcomer's chord, shown through the cut.
        double pool = .42 * r;
        for (int k = 1; k <= n; k++) {
            double y = tray(p, k) + DECK / 2;
            b.track(new Track.Level(new Track.Box(new double[]{x - pool, y, z - pool}, new double[]{x + pool, y + LIQUID, z + pool}), Value.of(1), TRAY_LIQUID, "tower", STOP_1, STOP_4));
        }
        return b
                .track(new Track.Dots(Track.Kind.LIGHT, new Track.Line(up, spread, source, 14, 1.0, .1), STOP_1 + 2, STOP_4, 14, 1.4))
                .track(new Track.Dots(Track.Kind.MIDDLE, new Track.Line(toDraw, spread, source, 10, 1.4, .1), STOP_1 + 2.5, STOP_4, 10, .9))
                .track(new Track.Dots(Track.Kind.HEAVY, new Track.Line(down, .12 * r, new Track.Box(new double[]{x - .3 * r, top, z - .3 * r}, new double[]{x + .3 * r, top + .1, z + .3 * r}), 40, .3, .05), STOP_1, STOP_4, 40, 2.0))
                .stop(STOP_1).stop(STOP_2).stop(STOP_3).stop(STOP_4)
                .build();
    }

    /** Tray {@code k}'s downcomer end: +1 or -1 along the view's across direction, alternating from the top. */
    static int side(int k) {
        return k % 2 == 0 ? 1 : -1;
    }

    /** The point (x, z) at {@code u} along {@code across} and {@code v} along {@code toCamera} from the axis. */
    static double[] at(double x, double z, double[] across, double[] toCamera, double u, double v) {
        return new double[]{x + u * across[0] + v * toCamera[0], z + u * across[2] + v * toCamera[2]};
    }

    /** A label's point on the far wall at height {@code y}, {@code u} along the view's across direction. */
    static double[] wall(double x, double z, double[] across, double[] toCamera, double u, double y) {
        var p = at(x, z, across, toCamera, .8 * u, -.4 * Math.abs(u));
        return new double[]{p[0], y, p[1]};
    }

    /** A round plate of radius {@code r} from {@code y0} to {@code y1}, open beyond the chord {@code chord} (signed, along {@code across}). */
    static List<Mesh.Polygon> plate(double x, double z, double r, double chord, double[] across, double[] toCamera, double y0, double y1) {
        double sign = Math.signum(chord), a0 = Math.acos(Math.abs(chord) / r);
        var ring = new ArrayList<double[]>();
        int steps = 24;
        // From one chord end round the far side of the circle to the other (the open segment lies beyond the chord).
        for (int i = 0; i <= steps; i++) {
            double t = a0 + (2 * Math.PI - 2 * a0) * i / steps;
            ring.add(at(x, z, across, toCamera, sign * r * DeterministicMath.cos(t), r * DeterministicMath.sin(t)));
        }
        return prism(ring, y0, y1);
    }

    /** A prism of {@link Mesh.Material#STEELWORK} over the convex outline {@code ring} ((x, z) points), from {@code y0} to {@code y1}. */
    static List<Mesh.Polygon> prism(List<double[]> ring, double y0, double y1) {
        int n = ring.size();
        double cx = 0, cz = 0;
        for (var q : ring) {
            cx += q[0] / n;
            cz += q[1] / n;
        }
        // Counter-clockwise seen from above, so the top's normal points up.
        double area = 0;
        for (int i = 0; i < n; i++) area += ring.get(i)[0] * ring.get((i + 1) % n)[1] - ring.get((i + 1) % n)[0] * ring.get(i)[1];
        var ccw = new ArrayList<>(ring);
        if (area > 0) java.util.Collections.reverse(ccw);
        var out = new ArrayList<Mesh.Polygon>();
        out.add(cap(ccw, y1, 1));
        var under = new ArrayList<>(ccw);
        java.util.Collections.reverse(under);
        out.add(cap(under, y0, -1));
        for (int i = 0; i < n; i++) {
            var a = ccw.get(i);
            var b = ccw.get((i + 1) % n);
            double nx = b[1] - a[1], nz = -(b[0] - a[0]), l = Math.hypot(nx, nz);
            if (!(l > 0)) continue;
            nx /= l;
            nz /= l;
            if (nx * ((a[0] + b[0]) / 2 - cx) + nz * ((a[1] + b[1]) / 2 - cz) < 0) {
                nx = -nx;
                nz = -nz;
            }
            double[] pos = {a[0], y0, a[1], b[0], y0, b[1], b[0], y1, b[1], a[0], y1, a[1]};
            double[] nor = {nx, 0, nz, nx, 0, nz, nx, 0, nz, nx, 0, nz};
            out.add(new Mesh.Polygon(pos, new double[8], nor, Mesh.Material.STEELWORK));
        }
        return out;
    }

    private static Mesh.Polygon cap(List<double[]> ring, double y, double ny) {
        int n = ring.size();
        double[] pos = new double[3 * n], nor = new double[3 * n], uv = new double[2 * n];
        for (int i = 0; i < n; i++) {
            pos[3 * i] = ring.get(i)[0];
            pos[3 * i + 1] = y;
            pos[3 * i + 2] = ring.get(i)[1];
            nor[3 * i + 1] = ny;
            uv[2 * i] = ring.get(i)[0];
            uv[2 * i + 1] = ring.get(i)[1];
        }
        return new Mesh.Polygon(pos, uv, nor, Mesh.Material.STEELWORK);
    }
}
