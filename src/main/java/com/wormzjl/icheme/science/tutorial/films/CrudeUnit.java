package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The film "the crude unit" (batch 2026-11-12-tutorial-system, P5; guide step {@code unit/whole}): the bundled "Small crude
 * unit" ({@code flowsheet_examples/small_crude_unit.json}) laid out in a row in its streams' order, each unit its kind's
 * reference shape scaled to sit beside the others, the tower the small tower itself. Five stops, as the crude goes:
 * <ol>
 * <li>the fluid converter fills the crude tank, and P-101 sends it on (stream CRUDE);</li>
 * <li>the preheat: the crude cools the tower's pumparound in its cooler (S-1 to the tower's {@code pa/1/a}, back on
 * {@code pa/1/b}), then takes the hot residue's heat in E-101's tubes (S-4; the residue S-9 on its shell);</li>
 * <li>H-101 heats it (S-5), and V-101 lets it into the tower (S-6, S-7);</li>
 * <li>in the tower it parts: gas and naphtha over the top through the condenser and the reflux drum, the middle product
 * by P-102 (S-8), the residue from the foot to E-101's shell (S-9);</li>
 * <li>each product to a tank of its own, the residue through E-102, the air cooler, first (S-10, RESIDUE).</li>
 * </ol>
 * The scale of a scaled unit is its own, never a size from the sheet; the film draws the order and the ways, not a design.
 */
public final class CrudeUnit {
    public static final String NAME = "crude_unit";
    public static final String TITLE = "tutorial.icheme.film." + NAME;
    static final double STOP_1 = 5, STOP_2 = 11, STOP_3 = 17, STOP_4 = 24, STOP_5 = 31;
    static final double YAW = .5, PITCH = .42, PIPE = .25, RACK = 1.2;
    static final String TAG = "tutorial.icheme.film.crude_unit.";
    /** The units the film names, by their tags on the sheet (and the tanks). */
    public static final String CONVERTER = TAG + "converter", CRUDE_TANK = TAG + "crude_tank", P101 = TAG + "p101", LOOP = TAG + "loop_cooler", E101 = TAG + "e101",
            H101 = TAG + "h101", V101 = TAG + "v101", T101 = TAG + "t101", DRUM = TAG + "drum", P102 = TAG + "p102", E102 = TAG + "e102", TANKS = TAG + "tanks";

    private CrudeUnit() {
    }

    /**
     * A unit on the film's ground: its shape, scaled by {@code scale} about its box's foot centre ({@code foot}, in the shape's
     * box), set with that point at ({@code x}, 0, {@code z}).
     */
    record Placed(String id, EquipmentShape shape, double scale, double x, double z, double[] foot, Actor actor) {
        static Placed of(String id, EquipmentShape shape, double scale, double x, double z) {
            var raw = Actors.equipment(id, shape);
            var b = EquipmentFilms.bounds(raw);
            double fx = (b[0][0] + b[1][0]) / 2, fz = (b[0][2] + b[1][2]) / 2, fy = b[0][1];
            var out = new ArrayList<Mesh.Polygon>(raw.polygons().size());
            for (var p : raw.polygons()) {
                double[] pos = p.positions().clone();
                for (int i = 0; i < p.size(); i++) {
                    pos[3 * i] = x + scale * (pos[3 * i] - fx);
                    pos[3 * i + 1] = scale * (pos[3 * i + 1] - fy);
                    pos[3 * i + 2] = z + scale * (pos[3 * i + 2] - fz);
                }
                out.add(new Mesh.Polygon(pos, p.uvs(), p.normals(), p.material()));
            }
            return new Placed(id, shape, scale, x, z, new double[]{fx, fy, fz}, new Actor(id, out));
        }

        /** The scene point of {@code role}'s {@code which}-th flange face, {@code beyond} metres out along its neck. */
        double[] face(Role role, int which, double beyond) {
            var raw = EquipmentFilms.face(shape, role, which, beyond / scale);
            var f = foot;
            return new double[]{x + scale * (raw[0] - f[0]), scale * (raw[1] - f[1]), z + scale * (raw[2] - f[2])};
        }

        double[] face(Role role) {
            return face(role, 0, 0);
        }

        double[][] bounds() {
            return EquipmentFilms.bounds(actor);
        }

        double[] top() {
            var b = bounds();
            return new double[]{(b[0][0] + b[1][0]) / 2, b[1][1], (b[0][2] + b[1][2]) / 2};
        }
    }

    public static Scene build() {
        var preset = SmallTower.preset();
        var tower = SmallTower.shape(preset);
        var converter = Placed.of("converter", ref(Kind.FLUID_CONVERTER, false), .22, 0, 0);
        var crudeTank = Placed.of("crude_tank", ref(Kind.TANK, false), .22, 9, 0);
        var p101 = Placed.of("p101", ref(Kind.PUMP, false), 1, 15, 0);
        var loop = Placed.of("loop_cooler", ref(Kind.EXCHANGER, false), .45, 19, -3);
        var e101 = Placed.of("e101", ref(Kind.EXCHANGER, false), .45, 23, -3);
        var h101 = Placed.of("h101", ref(Kind.VERTICAL_HEATER, false), .3, 29, 0);
        var t101 = Placed.of("t101", tower, 1, 38, 0);
        var drum = Placed.of("drum", ref(Kind.DRUM, true), .3, 42.5, -2);
        var p102 = Placed.of("p102", ref(Kind.PUMP, false), 1, 42, 3);
        var e102 = Placed.of("e102", ref(Kind.AIR_COOLER, false), .6, 31, 7);
        var tanks = List.of(Placed.of("naphtha_tank", ref(Kind.TANK, false), .13, 50, -5), Placed.of("middle_tank", ref(Kind.TANK, false), .13, 50, 0),
                Placed.of("residue_tank", ref(Kind.TANK, false), .13, 50, 5));
        var all = new ArrayList<>(List.of(converter, crudeTank, p101, loop, e101, h101, t101, drum, p102, e102));
        all.addAll(tanks);
        var b = Scene.builder(TITLE);
        for (var p : all) b.actor(p.actor());
        b.actor(Actors.box("ground", new double[]{-6, -.1, -10}, new double[]{56, 0, 12}, Mesh.Material.SUPPORT));
        double y = RACK;
        // The crude's way: converter, crude tank, P-101, the loop's cooler, E-101's tubes, H-101, V-101, the tower's feed.
        double[] cOut = converter.face(Role.BOTTOM), tIn = crudeTank.face(Role.BOTTOM, 0, 0), tOut = crudeTank.face(Role.BOTTOM, 1, 0), pIn = p101.face(Role.SUCTION),
                pOut = p101.face(Role.DISCHARGE), lIn = loop.face(Role.TUBE_INLET), lOut = loop.face(Role.TUBE_OUTLET), eIn = e101.face(Role.TUBE_INLET),
                eOut = e101.face(Role.TUBE_OUTLET), hIn = h101.face(Role.CONVECTION_INLET), hOut = h101.face(Role.RADIANT_OUTLET), feed = t101.face(Role.FEED);
        double[] valve = {34, y, 0};
        var crude1 = ortho(List.of(cOut, at(cOut[0], y, cOut[2]), at(tIn[0], y, tIn[2]), tIn));
        var crude2 = ortho(List.of(tOut, at(tOut[0], y, tOut[2]), at(pIn[0], y, pIn[2]), pIn));
        var crude3 = ortho(List.of(pOut, at(pOut[0], y + 1, pOut[2]), at(lIn[0], y + 1, lIn[2]), lIn));
        var crude4 = ortho(List.of(lOut, at(lOut[0], lOut[1] + .4, lOut[2]), at(eIn[0], lOut[1] + .4, eIn[2]), eIn));
        var crude5 = ortho(List.of(eOut, at(eOut[0], y + 2, eOut[2]), at(hIn[0], y + 2, eOut[2]), at(hIn[0], y + 2, hIn[2] - 1), hIn));
        var crude6 = ortho(List.of(hOut, at(hOut[0], y, hOut[2] - 1), at(valve[0], y, hOut[2] - 1), valve, at(feed[0], y, valve[2]), at(feed[0], feed[1], feed[2]), feed));
        // The products: over the top to the drum, the drum's oil to the naphtha tank; the middle draw by P-102; the foot by E-101's shell and E-102.
        double[] over = t101.face(Role.VAPOUR_OUTLET), drumIn = drum.face(Role.SIDE), drumOil = drum.face(Role.LIQUID_OUTLET), draw = t101.face(Role.DRAW),
                p2In = p102.face(Role.SUCTION), p2Out = p102.face(Role.DISCHARGE), foot = t101.face(Role.BOTTOMS), sIn = e101.face(Role.SHELL_INLET), sOut = e101.face(Role.SHELL_OUTLET),
                aIn = e102.face(Role.PROCESS_INLET), aOut = e102.face(Role.PROCESS_OUTLET);
        double[] nIn = tanks.get(0).top(), mIn = tanks.get(1).top(), rIn = tanks.get(2).top();
        var top = ortho(List.of(over, at(over[0], over[1] + .6, over[2]), at(drumIn[0], over[1] + .6, drumIn[2]), drumIn));
        var naphtha = ortho(List.of(drumOil, at(drumOil[0], y, drumOil[2]), at(nIn[0], y, drumOil[2]), nIn));
        var middle = ortho(List.of(draw, at(p2In[0], draw[1], p2In[2]), p2In, p2Out, at(p2Out[0], y + .5, p2Out[2]), at(mIn[0], y + .5, mIn[2]), mIn));
        var residue = ortho(List.of(foot, at(foot[0], y - .4, foot[2]), at(sIn[0], y - .4, foot[2]), at(sIn[0], y - .4, sIn[2]), sIn));
        var cooled = ortho(List.of(sOut, at(sOut[0], y - .6, sOut[2]), at(aIn[0], y - .6, sOut[2]), at(aIn[0], y - .6, aIn[2]), aIn));
        var stored = ortho(List.of(aOut, at(aOut[0], y - .8, aOut[2]), at(rIn[0], y - .8, aOut[2]), at(rIn[0], y - .8, rIn[2]), rIn));
        int k = 0;
        for (var way : List.of(crude1, crude2, crude3, crude4, crude5, crude6, top, naphtha, middle, residue, cooled, stored))
            b.actor(Actors.pipe("pipe" + k++, way, PIPE, Mesh.Material.PIPE));
        b.actor(Actors.box("valve", new double[]{valve[0] - .35, y - .35, valve[2] - .35}, new double[]{valve[0] + .35, y + .35, valve[2] + .35}, Mesh.Material.ACTUATOR));
        b.track(new Track.Camera(List.of(
                new Track.Key(0, YAW, PITCH, 7, 25, 4, 0),
                new Track.Key(2, YAW, PITCH, 13, 8, 2, 0),
                new Track.Key(STOP_1, YAW, PITCH, 13, 8, 2, 0),
                new Track.Key(STOP_1 + 2, YAW, PITCH, 17, 21, 2, -2),
                new Track.Key(STOP_2, YAW, PITCH, 17, 21, 2, -2),
                new Track.Key(STOP_2 + 2, YAW, PITCH, 9, 32, 5, 0),
                new Track.Key(STOP_3, YAW, PITCH, 9, 32, 5, 0),
                new Track.Key(STOP_3 + 2, YAW, PITCH, 7.5, 40, 10, 0),
                new Track.Key(STOP_4, YAW, PITCH, 7.5, 40, 10, 0),
                new Track.Key(STOP_4 + 2, YAW, PITCH, 6.5, 30, 4, 1),
                new Track.Key(STOP_5, YAW, PITCH, 6.5, 30, 4, 1))))
                .track(new Track.Caption(TITLE + ".s1", 0, STOP_1))
                .track(new Track.Caption(TITLE + ".s2", STOP_1, STOP_2))
                .track(new Track.Caption(TITLE + ".s3", STOP_2, STOP_3))
                .track(new Track.Caption(TITLE + ".s4", STOP_3, STOP_4))
                .track(new Track.Caption(TITLE + ".s5", STOP_4, STOP_5))
                .track(new Track.Label(converter.top(), CONVERTER, 0, STOP_1))
                .track(new Track.Label(crudeTank.top(), CRUDE_TANK, 0, STOP_1))
                .track(new Track.Label(pOut, P101, 0, STOP_1))
                .track(new Track.Label(loop.top(), LOOP, STOP_1, STOP_2))
                .track(new Track.Label(e101.top(), E101, STOP_1, STOP_2))
                .track(new Track.Label(h101.top(), H101, STOP_2, STOP_3))
                .track(new Track.Label(valve, V101, STOP_2, STOP_3))
                .track(new Track.Label(t101.top(), T101, STOP_3, STOP_4))
                .track(new Track.Label(drum.top(), DRUM, STOP_3, STOP_4))
                .track(new Track.Label(p2Out, P102, STOP_3, STOP_4))
                .track(new Track.Label(e102.top(), E102, STOP_4, STOP_5))
                .track(new Track.Label(tanks.get(1).top(), TANKS, STOP_4, STOP_5))
                .track(new Track.Legend(STOP_3, STOP_5));
        // The crude's dots, each leg from its stop on; the products' from stop 4.
        crude(b, crude1, 1, STOP_5);
        crude(b, crude2, 2.5, STOP_5);
        crude(b, crude3, 3.5, STOP_5);
        crude(b, crude4, STOP_1 + .5, STOP_5);
        crude(b, crude5, STOP_1 + 2, STOP_5);
        crude(b, crude6, STOP_2 + .5, STOP_5);
        dots(b, Track.Kind.LIGHT, top, STOP_3 + .5, STOP_5, 10);
        dots(b, Track.Kind.LIGHT, naphtha, STOP_3 + 1.5, STOP_5, 10);
        dots(b, Track.Kind.MIDDLE, middle, STOP_3 + 1, STOP_5, 12);
        dots(b, Track.Kind.HEAVY, residue, STOP_3 + 1, STOP_5, 12);
        dots(b, Track.Kind.HEAVY, cooled, STOP_4 + .5, STOP_5, 10);
        dots(b, Track.Kind.HEAVY, stored, STOP_4 + 1.5, STOP_5, 10);
        return b.stop(STOP_1).stop(STOP_2).stop(STOP_3).stop(STOP_4).stop(STOP_5).build();
    }

    private static EquipmentShape ref(Kind kind, boolean boot) {
        return Objects.requireNonNull(ReferenceShapes.of(kind, boot), kind.name());
    }

    /** {@code way} with each leg that changes more than one axis split into straight runs: along x, then z, then y. A pipe leg is a box, so a slanted one would draw as a slab. */
    static List<double[]> ortho(List<double[]> way) {
        var out = new ArrayList<double[]>();
        out.add(way.getFirst());
        for (int i = 1; i < way.size(); i++) {
            double[] a = out.getLast(), b = way.get(i);
            double[] p = {b[0], a[1], a[2]}, q = {b[0], a[1], b[2]};
            for (var r : List.of(p, q, b))
                if (Math.abs(r[0] - out.getLast()[0]) + Math.abs(r[1] - out.getLast()[1]) + Math.abs(r[2] - out.getLast()[2]) > 1e-9) out.add(r);
        }
        return out;
    }

    private static double[] at(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    private static void crude(Scene.Builder b, List<double[]> way, double from, double to) {
        dots(b, Track.Kind.LIGHT, way, from, to, 5);
        dots(b, Track.Kind.MIDDLE, way, from + .3, to, 5);
        dots(b, Track.Kind.HEAVY, way, from + .6, to, 5);
    }

    private static void dots(Scene.Builder b, Track.Kind kind, List<double[]> way, double from, double to, int count) {
        var s = way.getFirst();
        var source = new Track.Box(new double[]{s[0] - .1, s[1] - .1, s[2] - .1}, new double[]{s[0] + .1, s[1] + .1, s[2] + .1});
        double length = 0;
        for (int i = 0; i + 1 < way.size(); i++) {
            var p = way.get(i);
            var q = way.get(i + 1);
            length += Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]));
        }
        b.track(new Track.Dots(kind, new Track.Line(way, .06, source, count, Math.max(.2, (to - from) / count * .6), 0), from, to, count, Math.max(1, length / 3)));
    }
}
