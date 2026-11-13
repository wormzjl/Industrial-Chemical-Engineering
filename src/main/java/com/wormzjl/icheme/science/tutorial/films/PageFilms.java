package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The equipment pages' generic films (batch 2026-11-12-tutorial-system, P6): {@code page_<kind>} for every kind whose page
 * has no film of its own ({@link EquipmentFilms}, the small tower) and a nozzle to name, drawn on its reference shape
 * ({@link ReferenceShapes}). Stop 1: the outside, every nozzle named by its role ({@link #LABELS}), at its flange face
 * ({@link EquipmentFilms#face}). Stop 2, only for a kind whose inside holds the process and that has an inlet and an outlet
 * ({@link #INSIDE}): the body cut open, dots from the inlet to the outlet. A machine's casing, a stack with no outlet
 * nozzle and a store with no inlet have no stop 2, nor have the batch still, whose cut takes its pot and fire away (its own
 * films show its inside), and the adsorber, whose inlet and outlet stand on headers beside its two vessels. Nothing new is modelled: the cut shows the shape's own body. One caption
 * a stop, {@code tutorial.icheme.film.page_<kind>.s<n>}. A film is built only when its page's Watch is pressed.
 */
public final class PageFilms {
    public static final String PREFIX = "page_";
    static final String PART = EquipmentFilms.PART;
    static final double STOP_1 = EquipmentFilms.STOP_1, STOP_2 = EquipmentFilms.STOP_2, PITCH = EquipmentFilms.PITCH;

    /** Each nozzle role's label: the unit films' words where they fit, else a word of its own. */
    public static final Map<Role, String> LABELS = labels();
    /** The kinds whose stop 2 cuts the body open, with the nozzles the dots run between: the inlet, then the outlet. */
    public static final Map<Kind, Role[]> INSIDE = inside();
    /** Every film's kind by its name, in the kinds' order. */
    public static final Map<String, Kind> KINDS = kinds();

    private PageFilms() {
    }

    private static Map<Role, String> labels() {
        var m = new EnumMap<Role, String>(Role.class);
        for (var r : List.of(Role.FEED, Role.SIDE, Role.PROCESS_INLET, Role.SUCTION, Role.CHARGE, Role.CONVECTION_INLET)) m.put(r, EquipmentFilms.INLET);
        for (var r : List.of(Role.PROCESS_OUTLET, Role.DISCHARGE, Role.PRODUCT, Role.EFFLUENT, Role.BOTTOM, Role.RADIANT_OUTLET)) m.put(r, EquipmentFilms.OUTLET);
        m.put(Role.VAPOUR_OUTLET, EquipmentFilms.GAS);
        m.put(Role.WATER, EquipmentFilms.WATER);
        m.put(Role.FUEL, EquipmentFilms.FUEL);
        m.put(Role.STEAM, PART + "steam");
        String[][] own = {{"LIQUID_OUTLET", "liquid"}, {"TUBE_INLET", "tubes_in"}, {"TUBE_OUTLET", "tubes_out"}, {"MOTIVE", "driving_steam"},
                {"CONVECTION_OUTLET", "top_box_out"}, {"RADIANT_INLET", "firebox_in"}, {"AIR", "air"}, {"OXIDANT", "air_or_oxygen"}, {"HYDROGEN", "hydrogen"},
                {"QUENCH", "cooling_flow"}, {"NITROGEN", "nitrogen"}, {"COOLANT_INLET", "coolant_in"}, {"COOLANT_OUTLET", "coolant_out"},
                {"REGENERATION_INLET", "cleaning_gas_in"}, {"REGENERATION_OUTLET", "cleaning_gas_out"}, {"CATALYST_INLET", "grains_in"},
                {"CATALYST_OUTLET", "grains_out"}, {"CUTTING_WATER", "cutting_water"}, {"SOLIDS_INLET", "solid_fuel"}, {"SOLIDS_OUTLET", "solids_out"},
                {"FEEDWATER", "water_in"}, {"BLOWDOWN", "water_bleed"}, {"DRAIN", "drain"}, {"VENT", "vent"}, {"EXHAUST", "exhaust"}, {"FLUE_GAS", "burnt_gas"},
                {"RELIEF", "relief"}, {"SYNGAS", "burnable_gas"}, {"HOT_WATER_INLET", "warm_water_in"}, {"COLD_WATER_OUTLET", "cold_water_out"},
                {"FLARE_GAS", "gas_in"}, {"STACK_GAS", "burnt_gas_in"}, {"WATER_INLET", "cooling_water_in"}, {"WATER_OUTLET", "cooling_water_out"},
                {"DISTILLATE", "boiled_off"}, {"OVERFLOW", "overflow"}};
        for (var o : own) m.put(Role.valueOf(o[0]), PART + o[1]);
        return Collections.unmodifiableMap(m);
    }

    private static Map<Kind, Role[]> inside() {
        var m = new EnumMap<Kind, Role[]>(Kind.class);
        m.put(Kind.KETTLE, new Role[]{Role.FEED, Role.VAPOUR_OUTLET});
        m.put(Kind.CABIN_HEATER, new Role[]{Role.CONVECTION_INLET, Role.RADIANT_OUTLET});
        m.put(Kind.STIRRED_REACTOR, new Role[]{Role.FEED, Role.LIQUID_OUTLET});
        m.put(Kind.TRICKLE_BED_REACTOR, new Role[]{Role.FEED, Role.EFFLUENT});
        m.put(Kind.EBULLATED_BED_REACTOR, new Role[]{Role.FEED, Role.EFFLUENT});
        m.put(Kind.CYCLONE, new Role[]{Role.FEED, Role.VAPOUR_OUTLET});
        m.put(Kind.FCC_CONVERTER, new Role[]{Role.FEED, Role.VAPOUR_OUTLET});
        m.put(Kind.DELAYED_COKER, new Role[]{Role.FEED, Role.VAPOUR_OUTLET});
        m.put(Kind.FLUIDIZED_BED_BOILER, new Role[]{Role.FEEDWATER, Role.STEAM});
        m.put(Kind.GRATE_BOILER, new Role[]{Role.FEEDWATER, Role.STEAM});
        m.put(Kind.FLUIDIZED_BED_GASIFIER, new Role[]{Role.SOLIDS_INLET, Role.SYNGAS});
        m.put(Kind.FIXED_BED_GASIFIER, new Role[]{Role.SOLIDS_INLET, Role.SYNGAS});
        m.put(Kind.INDUCED_DRAFT_COOLING_TOWER, new Role[]{Role.HOT_WATER_INLET, Role.COLD_WATER_OUTLET});
        m.put(Kind.NATURAL_DRAFT_COOLING_TOWER, new Role[]{Role.HOT_WATER_INLET, Role.COLD_WATER_OUTLET});
        return Collections.unmodifiableMap(m);
    }

    /** Every kind but those whose page shows a film of its own and those with no nozzle to name (a shaft machine: the picture says all). */
    private static Map<String, Kind> kinds() {
        var own = new java.util.HashSet<>(EquipmentFilms.KINDS.values());
        own.add(Kind.COLUMN);
        var out = new LinkedHashMap<String, Kind>();
        for (var kind : Kind.values()) {
            if (own.contains(kind)) continue;
            var shape = ReferenceShapes.of(kind);
            if (shape == null || shape.nozzles().isEmpty()) continue;
            out.put(name(kind), kind);
        }
        return Collections.unmodifiableMap(out);
    }

    /** The film name of {@code kind}'s page. */
    public static String name(Kind kind) {
        return PREFIX + kind.name().toLowerCase(Locale.ROOT);
    }

    /** The film's stops: two where the inside is shown, else one. */
    public static int stops(String name) {
        return INSIDE.containsKey(KINDS.get(name)) ? 2 : 1;
    }

    /** The film named {@code name}, built anew; null for none. */
    public static @Nullable Scene build(String name) {
        var kind = KINDS.get(name);
        if (kind == null) return null;
        var shape = ReferenceShapes.of(kind);
        if (shape == null) return null;
        var body = Actors.equipment("body", shape);
        var b = EquipmentFilms.bounds(body);
        double yaw = EquipmentFilms.yaw(b), whole = EquipmentFilms.fit(b, yaw);
        double[] c = {(b[0][0] + b[1][0]) / 2, (b[0][1] + b[1][1]) / 2, (b[0][2] + b[1][2]) / 2};
        String title = "tutorial.icheme.film." + name;
        var scene = Scene.builder(title).actor(body);
        var flow = INSIDE.get(kind);
        var keys = new ArrayList<>(List.of(new Track.Key(0, 0, .1, whole, c[0], c[1], c[2]), new Track.Key(STOP_1, yaw, PITCH, whole, c[0], c[1], c[2])));
        if (flow != null) keys.add(new Track.Key(STOP_2, yaw + .25, PITCH, whole, c[0], c[1], c[2]));
        scene.track(new Track.Camera(keys));
        scene.track(new Track.Caption(title + ".s1", 0, STOP_1));
        // Stop 1: every nozzle by its role, the n-th of a role at its own face.
        var seen = new EnumMap<Role, Integer>(Role.class);
        for (var n : shape.nozzles()) {
            int which = seen.merge(n.role(), 1, Integer::sum) - 1;
            scene.track(new Track.Label(EquipmentFilms.face(shape, n.role(), which, 0), label(n.role()), 0, STOP_1));
        }
        scene.stop(STOP_1);
        if (flow != null) {
            scene.track(new Track.Caption(title + ".s2", STOP_1, STOP_2));
            inside(scene, shape, body, b, c, yaw, whole, flow[0], flow[1]);
            scene.stop(STOP_2);
        }
        return scene.build();
    }

    /** {@code role}'s label key. */
    public static String label(Role role) {
        var key = LABELS.get(role);
        if (key == null) throw new IllegalStateException("No label for " + role);
        return key;
    }

    /** Stop 2: the body cut open toward the camera, the inlet and the outlet named, the dots from one to the other through the middle between them. */
    private static void inside(Scene.Builder scene, EquipmentShape shape, com.wormzjl.icheme.science.tutorial.scene.Actor body, double[][] b, double[] c, double yaw, double whole, Role in, Role out) {
        double dx = b[1][0] - b[0][0], dz = b[1][2] - b[0][2];
        double[] toCamera = {-DeterministicMath.sin(yaw), 0, -DeterministicMath.cos(yaw)};
        // A long body opens along its length, a round or standing one across the view (EquipmentFilms' cut).
        double[] n = dz > 1.4 * dx ? new double[]{Math.signum(toCamera[0]), 0, 0} : dx > 1.4 * dz ? new double[]{0, 0, Math.signum(toCamera[2])} : toCamera;
        double[] from = EquipmentFilms.face(shape, in, 0, 0), to = EquipmentFilms.face(shape, out, 0, 0);
        // The process's middle: midway between the inlet and the outlet across the ground, so a vessel standing off its
        // frame's centre (a cyclone in its steelwork) is cut through itself, and the dots run through it. Where that cut
        // would take most of the body away (nozzles on a frame's edge), the body's own centre.
        double mx = (from[0] + to[0]) / 2, mz = (from[2] + to[2]) / 2, past = .05 * Math.min(dx, dz);
        if (kept(body, new double[]{mx - past * n[0], c[1], mz - past * n[2]}, n) < KEPT) {
            mx = c[0];
            mz = c[2];
        }
        scene.track(new Track.Cut("body", new double[]{mx - past * n[0], c[1], mz - past * n[2]}, n, STOP_1 + .3, 2));
        double[] before = EquipmentFilms.face(shape, in, 0, 1), after = EquipmentFilms.face(shape, out, 0, 1);
        var way = List.of(before, from, new double[]{mx, from[1], mz}, new double[]{mx, to[1], mz}, to, after);
        double length = 0;
        for (int i = 0; i + 1 < way.size(); i++) length += dist(way.get(i), way.get(i + 1));
        double size = Math.max(dx, Math.max(b[1][1] - b[0][1], dz));
        int count = 12;
        double s = .02 * size, start = STOP_1 + .8;
        var source = new Track.Box(new double[]{before[0] - s, before[1] - s, before[2] - s}, new double[]{before[0] + s, before[1] + s, before[2] + s});
        var kind = out.port == EquipmentShape.PortClass.VAPOUR ? Track.Kind.LIGHT : out.port == EquipmentShape.PortClass.LIQUID ? Track.Kind.HEAVY : Track.Kind.MIDDLE;
        double gap = Math.max(.15, (STOP_2 - start) / count * .8);
        scene.track(new Track.Dots(kind, new Track.Line(way, .015 * size, source, count, gap, 0), start, STOP_2, count, Math.max(.6, length / 4))
                .sized(EquipmentFilms.DOT_PX / whole));
        scene.track(new Track.Label(from, label(in), STOP_1, STOP_2));
        scene.track(new Track.Label(to, label(out), STOP_1, STOP_2));
    }

    /** The least share of the body's polygons a cut through the process's middle keeps. */
    static final double KEPT = .35;

    /** The share of {@code body}'s polygons whose centre stands on the kept side of the plane through {@code at} with {@code n} (Track.Cut). */
    static double kept(com.wormzjl.icheme.science.tutorial.scene.Actor body, double[] at, double[] n) {
        int kept = 0, all = 0;
        for (var p : body.polygons()) {
            double x = 0, y = 0, z = 0;
            for (int i = 0; i < p.size(); i++) {
                x += p.positions()[3 * i];
                y += p.positions()[3 * i + 1];
                z += p.positions()[3 * i + 2];
            }
            all++;
            if ((x / p.size() - at[0]) * n[0] + (y / p.size() - at[1]) * n[1] + (z / p.size() - at[2]) * n[2] <= 0) kept++;
        }
        return all == 0 ? 0 : kept / (double) all;
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** Every label key the films may name. */
    public static List<String> labelKeys() {
        return List.copyOf(new java.util.LinkedHashSet<>(LABELS.values()));
    }
}
