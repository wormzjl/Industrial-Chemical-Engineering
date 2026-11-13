package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The equipment pages' generic films (batch 2026-11-12-tutorial-system, P6): {@code page_<kind>} for every kind whose page
 * has no film of its own ({@link EquipmentFilms}, the small tower) and a nozzle to name, drawn on its reference shape
 * ({@link ReferenceShapes}), in one stop: the outside, every nozzle named by its role ({@link #LABELS}), at its flange face
 * ({@link EquipmentFilms#face}). No stop shows an inside: a cut of a reference shape uncovers an empty shell or none of the
 * process, and its dots ran off the body (owner's rule for an inside stop, 2026-11-13, gated by {@code PageFilmsTest}; the
 * 14 kinds judged in P6_COVERAGE.md). Its caption is {@code tutorial.icheme.film.page_<kind>.s1}. A film is built only
 * when its page's Watch is pressed.
 */
public final class PageFilms {
    public static final String PREFIX = "page_";
    static final String PART = EquipmentFilms.PART;
    static final double STOP_1 = EquipmentFilms.STOP_1, PITCH = EquipmentFilms.PITCH;

    /** Each nozzle role's label: the unit films' words where they fit, else a word of its own. */
    public static final Map<Role, String> LABELS = labels();
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
        scene.track(new Track.Camera(List.of(new Track.Key(0, 0, .1, whole, c[0], c[1], c[2]), new Track.Key(STOP_1, yaw, PITCH, whole, c[0], c[1], c[2]))));
        scene.track(new Track.Caption(title + ".s1", 0, STOP_1));
        // Stop 1: every nozzle by its role, the n-th of a role at its own face.
        var seen = new EnumMap<Role, Integer>(Role.class);
        for (var n : shape.nozzles()) {
            int which = seen.merge(n.role(), 1, Integer::sum) - 1;
            scene.track(new Track.Label(EquipmentFilms.face(shape, n.role(), which, 0), label(n.role()), 0, STOP_1));
        }
        scene.stop(STOP_1);
        return scene.build();
    }

    /** {@code role}'s label key. */
    public static String label(Role role) {
        var key = LABELS.get(role);
        if (key == null) throw new IllegalStateException("No label for " + role);
        return key;
    }

    /** Every label key the films may name. */
    public static List<String> labelKeys() {
        return List.copyOf(new java.util.LinkedHashSet<>(LABELS.values()));
    }
}
