package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import com.wormzjl.icheme.science.tutorial.scene.Value;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crude unit's equipment films (batch 2026-11-12-tutorial-system, P5), one for each kind of the unit with a body of its
 * own, each opened from its equipment page: the kind's reference shape ({@link ReferenceShapes}) in two or three stops. 1:
 * outside, its connections named. 2: inside, cut open where the inside is the idea (the exchanger's tubes and shell, the
 * heater's flames, the drum's oil and boot, the tank's floating roof), dots running from the inlet to the outlet. 3: where
 * it stands in the unit, what comes in and where it goes. Every place is the shape's own: a nozzle's flange face by role,
 * moved by the shape's frame-to-box transform. The column's page shows "the small tower" ({@link SmallTower}) instead.
 * A film is built only when its page's Watch is pressed.
 */
public final class EquipmentFilms {
    public static final String CONVERTER = "unit_converter", TANK = "unit_tank", PUMP = "unit_pump", EXCHANGER = "unit_exchanger", HEATER = "unit_heater",
            DRUM = "unit_drum", AIR_COOLER = "unit_air_cooler";
    /** Each film's kind, in the unit's order. */
    public static final Map<String, Kind> KINDS = kinds();
    static final String PART = "tutorial.icheme.film.part.";
    public static final String INLET = PART + "inlet", OUTLET = PART + "outlet", CRUDE_OUT = PART + "crude_out", TO_TANK = PART + "to_tank", TO_PUMP = PART + "to_pump",
            FROM_TANK = PART + "from_tank", TO_EXCHANGERS = PART + "to_exchangers", HOT_IN = PART + "hot_in", TO_HEATER = PART + "to_heater", TO_COOLER = PART + "to_cooler",
            FUEL = PART + "fuel", TO_TOWER = PART + "to_tower", GAS = PART + "gas", OIL = PART + "oil", WATER = PART + "water", BACK = PART + "oil_back";
    static final double STOP_1 = 4, STOP_2 = 11, STOP_3 = 17, YAW = .62, PITCH = .22;
    /** A dot's radius on the glass at the inside stop, px at the design size: large enough to read its colour. */
    static final double DOT_PX = 2.4;
    /** The share of the glass's width and height a framed box may take. */
    static final double FILL_W = .92, FILL_H = .88;
    public static final int WATER_RGB = 0x3A78B0;

    private EquipmentFilms() {
    }

    private static Map<String, Kind> kinds() {
        var out = new LinkedHashMap<String, Kind>();
        out.put(CONVERTER, Kind.FLUID_CONVERTER);
        out.put(TANK, Kind.TANK);
        out.put(PUMP, Kind.PUMP);
        out.put(EXCHANGER, Kind.EXCHANGER);
        out.put(HEATER, Kind.VERTICAL_HEATER);
        out.put(DRUM, Kind.DRUM);
        out.put(AIR_COOLER, Kind.AIR_COOLER);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The shape film {@code name} stands on: its kind's reference shape, the drum with a column's reflux drum's boot. */
    public static EquipmentShape shape(String name) {
        var shape = ReferenceShapes.of(KINDS.get(name), name.equals(DRUM));
        if (shape == null) throw new IllegalStateException(name + ": no reference shape");
        return shape;
    }

    /** The film's stops: two for the converter (outside, then where it stands), three for the rest. */
    public static int stops(String name) {
        return name.equals(CONVERTER) ? 2 : 3;
    }

    /** The {@code which}-th nozzle of {@code role}'s flange face centre on the scene, {@code beyond} metres out along its neck. */
    public static double[] face(EquipmentShape shape, Role role, int which, double beyond) {
        int seen = 0;
        var t = shape.transform();
        for (var n : shape.nozzles())
            if (n.role() == role && seen++ == which) {
                double[] f = t.point(n.face()), d = t.direction(n.direction());
                return new double[]{f[0] + d[0] * beyond, f[1] + d[1] * beyond, f[2] + d[2] * beyond};
            }
        throw new IllegalStateException(shape.kind() + " has no nozzle " + role + " " + which);
    }

    /** The scene box {lo, hi} of {@code actor}'s polygons. */
    public static double[][] bounds(Actor actor) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var p : actor.polygons())
            for (int i = 0; i < p.size(); i++)
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], p.positions()[3 * i + a]);
                    hi[a] = Math.max(hi[a], p.positions()[3 * i + a]);
                }
        return new double[][]{lo, hi};
    }

    /** The film glass at the guide's design size, px: the client's {@code FilmLayout.DESIGN_GLASS} (held equal by {@code UnitFilmsTest}). */
    public static final int GLASS_W = 432, GLASS_H = 148;

    /** The camera's scale (px/m at the design size) that keeps the box {@code b} on the design glass from the films' view. */
    static double fit(double[][] b) {
        return fit(b, yaw(b));
    }

    /** The same from {@code yaw}. */
    static double fit(double[][] b, double yaw) {
        double dx = b[1][0] - b[0][0], dy = b[1][1] - b[0][1], dz = b[1][2] - b[0][2];
        double across = Math.abs(dx * DeterministicMath.cos(yaw)) + Math.abs(dz * DeterministicMath.sin(yaw));
        double deep = Math.abs(dx * DeterministicMath.sin(yaw)) + Math.abs(dz * DeterministicMath.cos(yaw));
        double tall = dy * DeterministicMath.cos(PITCH) + deep * DeterministicMath.sin(PITCH);
        return Math.min(FILL_W * GLASS_W / across, FILL_H * GLASS_H / tall);
    }

    /** The view's yaw for a body of box {@code b}: a long body seen from its side, its length across the glass; a round one at {@link #YAW}. */
    static double yaw(double[][] b) {
        double dx = b[1][0] - b[0][0], dz = b[1][2] - b[0][2];
        return dz > 1.4 * dx ? 1.1 : dx > 1.4 * dz ? .45 : YAW;
    }

    private static double[] centre(double[][] b) {
        return new double[]{(b[0][0] + b[1][0]) / 2, (b[0][1] + b[1][1]) / 2, (b[0][2] + b[1][2]) / 2};
    }

    /**
     * The film named {@code name}, built anew; null for none. Stop 1 and the last stop frame the whole body; the inside stop
     * frames the part its caption names ({@link #focus}), close. Its dots are sized to read at that stop.
     */
    public static @Nullable Scene build(String name) {
        if (!KINDS.containsKey(name)) return null;
        var shape = shape(name);
        var body = Actors.equipment("body", shape);
        var b = bounds(body);
        // The heater's stack is no part of the idea: the films look at the firebox and the top box.
        if (name.equals(HEATER)) b = new double[][]{b[0], {b[1][0], face(shape, Role.CONVECTION_INLET, 0, 0)[1] + 2, b[1][2]}};
        double yaw = yaw(b), whole = fit(b, yaw);
        var focus = focus(name, shape, b);
        double near = fit(focus, yaw);
        double[] c = centre(b), fc = centre(focus);
        String title = "tutorial.icheme.film." + name;
        var film = new Film(Scene.builder(title).actor(body), shape, b, c, yaw, DOT_PX / near);
        int stops = stops(name);
        var keys = new ArrayList<Track.Key>(List.of(new Track.Key(0, 0, .1, whole, c[0], c[1], c[2]), new Track.Key(STOP_1, yaw, PITCH, whole, c[0], c[1], c[2])));
        if (stops == 2) keys.add(new Track.Key(STOP_2, yaw + .3, PITCH, whole, c[0], c[1], c[2]));
        else {
            keys.add(new Track.Key(STOP_1 + 1.5, yaw, PITCH, near, fc[0], fc[1], fc[2]));
            keys.add(new Track.Key(STOP_2, yaw + .12, PITCH, near, fc[0], fc[1], fc[2]));
            keys.add(new Track.Key(STOP_2 + 1.5, yaw + .2, PITCH, whole, c[0], c[1], c[2]));
            keys.add(new Track.Key(STOP_3, yaw + .3, PITCH, whole, c[0], c[1], c[2]));
        }
        film.scene.track(new Track.Camera(keys));
        for (int i = 1; i <= stops; i++) film.scene.track(new Track.Caption(title + ".s" + i, i == 1 ? 0 : i == 2 ? STOP_1 : STOP_2, i == 1 ? STOP_1 : i == 2 ? STOP_2 : STOP_3));
        switch (name) {
            case CONVERTER -> converter(film);
            case TANK -> tank(film);
            case PUMP -> pump(film);
            case EXCHANGER -> exchanger(film);
            case HEATER -> heater(film);
            case DRUM -> drum(film);
            case AIR_COOLER -> airCooler(film);
            default -> throw new IllegalStateException(name);
        }
        film.scene.stop(STOP_1).stop(STOP_2);
        if (stops == 3) film.scene.stop(STOP_3);
        return film.scene.build();
    }

    /**
     * The box the inside stop frames: a lying shell between its heads without its saddles (the exchanger; the drum with its
     * boot); the heater's firebox and top box; the whole body for the rest.
     */
    static double[][] focus(String name, EquipmentShape shape, double[][] b) {
        double r = shape.diameter() / 2;
        return switch (name) {
            case EXCHANGER -> {
                double axis = axis(shape, Role.TUBE_INLET, Role.TUBE_OUTLET);
                yield new double[][]{{b[0][0], axis - r - .2, b[0][2]}, {b[1][0], axis + r + .2, b[1][2]}};
            }
            case DRUM -> {
                double axis = axis(shape, Role.LIQUID_OUTLET, Role.VAPOUR_OUTLET);
                yield new double[][]{{b[0][0], face(shape, Role.BOOT_DRAIN, 0, 0)[1] + .5, b[0][2]}, {b[1][0], axis + r + .3, b[1][2]}};
            }
            case HEATER -> new double[][]{{b[0][0] + 1, shape.support() - .5, b[0][2] + 1}, {b[1][0] - 1, b[1][1] - 1.5, b[1][2] - 1}};
            default -> b;
        };
    }

    /** A lying shell's axis height: midway between a nozzle below it ({@code down}) and one on top ({@code up}), whose necks are alike. */
    static double axis(EquipmentShape shape, Role down, Role up) {
        return (face(shape, down, 0, 0)[1] + face(shape, up, 0, 0)[1]) / 2;
    }

    /** A film under construction: its builder, its shape, its body's bounds and centre. */
    private record Film(Scene.Builder scene, EquipmentShape shape, double[][] b, double[] c, double yaw, double dot) {
        double[] face(Role role, int which, double beyond) {
            return EquipmentFilms.face(shape, role, which, beyond);
        }

        double[] face(Role role) {
            return face(role, 0, 0);
        }

        /** The view's toward-the-camera direction at the stops' yaw: a cut along it keeps the far half. */
        double[] toCamera() {
            return new double[]{-DeterministicMath.sin(yaw), 0, -DeterministicMath.cos(yaw)};
        }

        /**
         * Cuts the body open from {@code from}: a long lying body along its length (the plane holds its long axis, its normal
         * the other level axis toward the camera), a round or standing one across the view.
         */
        void cut(double from) {
            cut(from, c, .5);
        }

        /** The same through the point {@code on}, {@code past} metres beyond it (a slim shell keeps its far half: a small step). */
        void cut(double from, double[] on, double past) {
            var n = opening();
            // Past the axis: the cells on the axis go too, so a body opens rather than keeping its middle row.
            double[] at = {on[0] - past * n[0], on[1], on[2] - past * n[2]};
            scene.track(new Track.Cut("body", at, n, from, 2));
        }

        /** Tubes inside the body, shown from {@code from} and cut with it: straight runs of pipe, each between two points. */
        void tubes(List<double[][]> runs, double side, double from) {
            tubes(runs, side, from, c);
        }

        /** The same, cut through {@code on}. */
        void tubes(List<double[][]> runs, double side, double from, double[] on) {
            var polygons = new ArrayList<com.wormzjl.icheme.science.equipment.Mesh.Polygon>();
            // Bright steel, so the tubes stand out against the shell's inside.
            for (var run : runs) polygons.addAll(Actors.pipe("tube", List.of(run[0], run[1]), side, com.wormzjl.icheme.science.equipment.Mesh.Material.SHAFT).polygons());
            scene.actor(new Actor("tubes", polygons));
            scene.track(new Track.Visible("tubes", from, STOP_3, .5));
            // Cut on the axis itself: the near tubes go, the middle and far ones stay in view.
            scene.track(new Track.Cut("tubes", on, opening(), from, 0));
        }

        double[] opening() {
            double dx = b[1][0] - b[0][0], dz = b[1][2] - b[0][2];
            var to = toCamera();
            if (dz > 1.4 * dx) return new double[]{Math.signum(to[0]), 0, 0};
            if (dx > 1.4 * dz) return new double[]{0, 0, Math.signum(to[2])};
            return to;
        }

        void label(double[] point, String key, double from, double to) {
            scene.track(new Track.Label(point, key, from, to));
        }

        /** The crude's three parts on one way, light, middle and heavy, {@code each} dots apiece. */
        void crude(List<double[]> way, double from, double to, int each) {
            dots(Track.Kind.LIGHT, way, from, to, each);
            dots(Track.Kind.MIDDLE, way, from + .4, to, each);
            dots(Track.Kind.HEAVY, way, from + .8, to, each);
        }

        void dots(Track.Kind kind, List<double[]> way, double from, double to, int count) {
            double length = 0;
            for (int i = 0; i + 1 < way.size(); i++) length += dist(way.get(i), way.get(i + 1));
            double size = Math.max(b[1][0] - b[0][0], Math.max(b[1][1] - b[0][1], b[1][2] - b[0][2]));
            double speed = Math.max(.6, length / 4), gap = Math.max(.15, (to - from) / Math.max(1, count) * .8);
            var start = way.getFirst();
            double s = .02 * size;
            var source = new Track.Box(new double[]{start[0] - s, start[1] - s, start[2] - s}, new double[]{start[0] + s, start[1] + s, start[2] + s});
            scene.track(new Track.Dots(kind, new Track.Line(way, .015 * size, source, count, gap, 0), from, to, count, speed).sized(dot));
        }
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double[] at(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    // ---- The films ----

    /** The fluid converter: outside with its process outlet; then the crude leaving to the crude tank. */
    private static void converter(Film f) {
        double[] out = f.face(Role.BOTTOM), beyond = f.face(Role.BOTTOM, 0, 2.5);
        f.label(out, CRUDE_OUT, 0, STOP_1);
        double[] inside = {out[0], out[1] + 1, f.c()[2]};
        f.crude(List.of(inside, out, beyond), STOP_1 + .5, STOP_2, 8);
        f.label(beyond, TO_TANK, STOP_1, STOP_2);
    }

    /** The floating-roof tank: outside; cut open, the liquid and the roof floating on it; then the crude out to the pump. */
    private static void tank(Film f) {
        var b = f.b();
        double cx = f.c()[0], cz = f.c()[2], r = .5 * Math.min(b[1][0] - b[0][0], b[1][2] - b[0][2]) * .92;
        var out = f.face(Role.BOTTOM);
        f.label(out, OUTLET, 0, STOP_1);
        double floor = b[0][1] + .5, level = floor + .55 * (b[1][1] - floor);
        f.cut(STOP_1 + .3);
        f.scene().track(new Track.Level(new Track.Box(new double[]{cx - .97 * r, floor, cz - .97 * r}, new double[]{cx + .97 * r, level, cz + .97 * r}), Value.of(1),
                SmallTower.TRAY_LIQUID, "body", STOP_1, STOP_3));
        var ring = new ArrayList<double[]>();
        for (int i = 0; i < 24; i++) {
            double t = 2 * Math.PI * i / 24;
            ring.add(new double[]{cx + .93 * r * DeterministicMath.cos(t), cz + .93 * r * DeterministicMath.sin(t)});
        }
        f.scene().actor(new Actor("roof", SmallTower.prism(ring, level, level + .4)));
        f.scene().track(new Track.Visible("roof", STOP_1 + 1, STOP_3, .5));
        f.scene().track(new Track.Cut("roof", f.c(), f.toCamera(), STOP_1 + 1, 0));
        f.label(at(cx, level + .4, cz), PART + "roof", STOP_1, STOP_2);
        var beyond = f.face(Role.BOTTOM, 0, 3);
        f.crude(List.of(at(cx, floor + .3, cz), at(out[0], floor + .3, out[2]), out, beyond), STOP_2 + .5, STOP_3, 6);
        f.label(beyond, TO_PUMP, STOP_2, STOP_3);
    }

    /** The pump: outside, its inlet and outlet; the liquid through its casing; then where it stands. */
    private static void pump(Film f) {
        double[] in = f.face(Role.SUCTION), out = f.face(Role.DISCHARGE), before = f.face(Role.SUCTION, 0, 1.2), after = f.face(Role.DISCHARGE, 0, 1);
        f.label(in, INLET, 0, STOP_1);
        f.label(out, OUTLET, 0, STOP_1);
        double[] eye = {out[0], in[1], out[2]};
        f.crude(List.of(before, in, eye, out, after), STOP_1 + .5, STOP_3, 8);
        f.label(before, FROM_TANK, STOP_2, STOP_3);
        f.label(after, TO_EXCHANGERS, STOP_2, STOP_3);
    }

    /**
     * The exchanger: outside; cut open on its axis, the bundle in the far half of the shell, the crude's dots inside the
     * middle row of tubes (cut in half, so they show), the residue's dots in the shell round them; then where it stands.
     */
    private static void exchanger(Film f) {
        double[] tubeIn = f.face(Role.TUBE_INLET), tubeOut = f.face(Role.TUBE_OUTLET), shellIn = f.face(Role.SHELL_INLET), shellOut = f.face(Role.SHELL_OUTLET);
        f.label(tubeIn, PART + "crude_in", 0, STOP_1);
        f.label(shellIn, HOT_IN, 0, STOP_1);
        var shape = f.shape();
        double r = shape.diameter() / 2, cy = axis(shape, Role.TUBE_INLET, Role.TUBE_OUTLET), x = tubeIn[0];
        double[] axis = {x, cy, f.c()[2]};
        f.cut(STOP_1 + .3, axis, .05 * r);
        var b = f.b();
        // The tube side's two passes: in at the channel, along the lower tubes to the far end, back by the upper ones.
        double near = tubeIn[2], far = shellIn[2] > near ? b[1][2] - 1 : b[0][2] + 1, low = cy - .45 * r, high = cy + .45 * r, side = .14 * r;
        var bundle = new ArrayList<double[][]>();
        for (double dy : new double[]{-.45, -.15, .15, .45})
            for (double dx : new double[]{0, .3}) bundle.add(new double[][]{at(x + dx * r, cy + dy * r, near), at(x + dx * r, cy + dy * r, far)});
        f.tubes(bundle, side, STOP_1 + .5, axis);
        f.dots(Track.Kind.MIDDLE, List.of(tubeIn, at(x, low, near), at(x, low, far), at(x, high, far), at(x, high, near), tubeOut), STOP_1 + 1, STOP_3, 16);
        // The residue zigzags round the baffles, between the rows of tubes and the shell.
        double mid = (shellIn[2] + shellOut[2]) / 2, top = cy + .75 * r, under = cy - .75 * r, gap = cy;
        f.dots(Track.Kind.HEAVY, List.of(shellIn, at(x, top, shellIn[2]), at(x, under, (shellIn[2] + mid) / 2), at(x, gap, mid), at(x, top, (mid + shellOut[2]) / 2),
                at(x, under, shellOut[2]), shellOut), STOP_1 + 1.5, STOP_3, 14);
        f.label(f.face(Role.TUBE_OUTLET, 0, .5), TO_HEATER, STOP_2, STOP_3);
        f.label(f.face(Role.SHELL_OUTLET, 0, .5), TO_COOLER, STOP_2, STOP_3);
    }

    /**
     * The fired heater: outside; cut open, the flames on the firebox's floor, the radiant tubes up its wall and the
     * convection tubes across the top box, the crude through the top box first, then down the wall tubes; then where it stands.
     */
    private static void heater(Film f) {
        double[] fuel = f.face(Role.FUEL), convIn = f.face(Role.CONVECTION_INLET), convOut = f.face(Role.CONVECTION_OUTLET), radIn = f.face(Role.RADIANT_INLET),
                radOut = f.face(Role.RADIANT_OUTLET);
        f.label(fuel, FUEL, 0, STOP_1);
        f.label(convIn, PART + "crude_in", 0, STOP_1);
        f.cut(STOP_1 + .3);
        var shape = f.shape();
        // The firebox's axis and its wall's radius; the tubes stand just inside the wall.
        double cx = f.c()[0], cz = f.c()[2], wall = .8 * shape.diameter() / 2, floor = shape.support() + .3;
        f.scene().track(new Track.Flame(at(cx, floor, cz), Value.of(5), STOP_1 + .5, STOP_3));
        var tubes = new ArrayList<double[][]>();
        int count = 14;
        for (int i = 0; i < count; i++) {
            double t = 2 * Math.PI * i / count;
            double tx = cx + wall * DeterministicMath.cos(t), tz = cz + wall * DeterministicMath.sin(t);
            tubes.add(new double[][]{at(tx, radOut[1], tz), at(tx, radIn[1], tz)});
        }
        // The top box's rows across it, between its inlet and outlet.
        double box = .35 * shape.diameter();
        var rows = new double[]{convOut[1] + .4, (convIn[1] + convOut[1]) / 2, convIn[1] - .4};
        for (double y : rows)
            for (double dz : new double[]{-.6, -.2, .2, .6}) tubes.add(new double[][]{at(cx - box, y, cz + dz * box), at(cx + box, y, cz + dz * box)});
        f.tubes(tubes, .45, STOP_1 + .5);
        // The crude: across the top box row by row, down to the wall tubes, down them in turns round the flames.
        var way = new ArrayList<double[]>(List.of(convIn, at(cx - box, rows[2], cz), at(cx + box, rows[2], cz), at(cx + box, rows[1], cz), at(cx - box, rows[1], cz),
                at(cx - box, rows[0], cz), at(cx + box, rows[0], cz), convOut, radIn));
        int turns = 4;
        for (int i = 0; i <= turns * count; i++) {
            double t = 2 * Math.PI * i / count + Math.PI, y = radIn[1] + (radOut[1] - radIn[1]) * i / (double) (turns * count);
            way.add(at(cx + wall * DeterministicMath.cos(t), y, cz + wall * DeterministicMath.sin(t)));
        }
        way.add(radOut);
        f.crude(way, STOP_1 + 1, STOP_3, 12);
        f.label(f.face(Role.RADIANT_OUTLET, 0, 1), TO_TOWER, STOP_2, STOP_3);
    }

    /**
     * The reflux drum with its boot: outside, its three outlets; cut open along its length, the oil lying in the shell's
     * lower part, the gas over it, the water in the boot; then where it stands.
     */
    private static void drum(Film f) {
        double[] gas = f.face(Role.VAPOUR_OUTLET), oil = f.face(Role.LIQUID_OUTLET), water = f.face(Role.BOOT_DRAIN), in = f.face(Role.SIDE);
        f.label(gas, GAS, 0, STOP_1);
        f.label(oil, OIL, 0, STOP_1);
        f.label(water, WATER, 0, STOP_1);
        var shape = f.shape();
        double radius = shape.diameter() / 2, axis = axis(shape, Role.LIQUID_OUTLET, Role.VAPOUR_OUTLET);
        f.cut(STOP_1 + .3, at(f.c()[0], axis, f.c()[2]), .1 * radius);
        var b = f.b();
        double cx = f.c()[0], cz = f.c()[2];
        boolean alongZ = b[1][2] - b[0][2] > b[1][0] - b[0][0];
        // The oil: upright pools side by side along the shell (a liquid stands in a round pool), their tops at the level,
        // their floors where the shell's curve allows at their radius.
        double pool = .62 * radius, level = axis - .15 * radius, bottom = axis - Math.sqrt(radius * radius - pool * pool) + .1;
        double length = (alongZ ? b[1][2] - b[0][2] : b[1][0] - b[0][0]) - 2 * radius, start = (alongZ ? b[0][2] : b[0][0]) + radius;
        int pools = Math.max(2, (int) Math.ceil(length / (1.6 * pool)));
        for (int i = 0; i < pools; i++) {
            double along = start + pool + (length - 2 * pool) * i / Math.max(1, pools - 1);
            double px = alongZ ? cx : along, pz = alongZ ? along : cz;
            f.scene().track(new Track.Level(new Track.Box(new double[]{px - pool, bottom, pz - pool}, new double[]{px + pool, level, pz + pool}), Value.of(1),
                    SmallTower.TRAY_LIQUID, "body", STOP_1, STOP_3));
        }
        double[] boot = {alongZ ? cx : water[0], water[1], alongZ ? water[2] : cz};
        f.scene().track(new Track.Level(new Track.Box(new double[]{boot[0] - .35, water[1] + .5, boot[2] - .35}, new double[]{boot[0] + .35, axis - radius + .2, boot[2] + .35}),
                Value.of(1), WATER_RGB, "body", STOP_1, STOP_3));
        double[] mid = {(in[0] + gas[0]) / 2, axis + .5 * radius, (in[2] + gas[2]) / 2};
        f.dots(Track.Kind.LIGHT, List.of(in, mid, gas, f.face(Role.VAPOUR_OUTLET, 0, 1)), STOP_1 + 1, STOP_3, 10);
        f.dots(Track.Kind.MIDDLE, List.of(in, at(mid[0], level - .3 * radius, mid[2]), at(oil[0], level - .3 * radius, oil[2]), oil, f.face(Role.LIQUID_OUTLET, 0, 1)),
                STOP_1 + 1.4, STOP_3, 10);
        f.label(f.face(Role.LIQUID_OUTLET, 0, 1), BACK, STOP_2, STOP_3);
        f.label(f.face(Role.VAPOUR_OUTLET, 0, 1), GAS, STOP_2, STOP_3);
    }

    /** The air cooler: outside, its inlet and outlet; the residue to and fro along the bundle on its top, over the fans; then where it stands. */
    private static void airCooler(Film f) {
        double[] in = f.face(Role.PROCESS_INLET), out = f.face(Role.PROCESS_OUTLET);
        f.label(in, INLET, 0, STOP_1);
        f.label(out, OUTLET, 0, STOP_1);
        // The bundle's top: its fins' (the heads' boxes at its ends), the passes across its width.
        double top = f.b()[0][1] + .875 * (f.b()[1][1] - f.b()[0][1]) + .1, x = f.c()[0], w = .3 * (f.b()[1][0] - f.b()[0][0]);
        double near = in[2] + .5, far = out[2] - 1;
        var way = List.of(f.face(Role.PROCESS_INLET, 0, 1), in, at(x - w, top, near), at(x - w, top, far), at(x, top, far), at(x, top, near), at(x + w, top, near),
                at(x + w, top, far), at(out[0], top - .3, out[2] - .4), out, f.face(Role.PROCESS_OUTLET, 0, 1));
        f.dots(Track.Kind.HEAVY, way, STOP_1 + .5, STOP_3, 16);
        f.label(f.face(Role.PROCESS_OUTLET, 0, 1), TO_TANK, STOP_2, STOP_3);
    }

    /** Every part label key the films name. */
    public static List<String> partKeys() {
        return List.of(INLET, OUTLET, CRUDE_OUT, TO_TANK, TO_PUMP, FROM_TANK, TO_EXCHANGERS, HOT_IN, TO_HEATER, TO_COOLER, FUEL, TO_TOWER, GAS, OIL, WATER, BACK, PART + "roof");
    }
}
