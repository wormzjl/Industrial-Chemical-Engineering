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
 * going in near its foot. 2: cut open, its many trays; light dots climb, heavy ones run down. 3: the products leave at their
 * heights: the top, the middle draw, the bottom. 4: the cooling loop and the steam. Every place is the shape's own: the
 * nozzles by role, the trays at {@link ColumnTrays#trayHeight} over the bottom tangent line (the skirt's top).
 */
public final class SmallTower {
    public static final String NAME = "small_tower";
    public static final String TITLE = "tutorial.icheme.film." + NAME;
    public static final String PRESET = "/assets/icheme/columnpreset/small_crude.json";
    static final double STOP_1 = 5, STOP_2 = 12, STOP_3 = 20, STOP_4 = 27;
    static final double YAW = .62, PITCH = .25, SCALE = 9.5, CLOSE_PITCH = .12, CLOSE_SCALE = 34;
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
    static double[] axis(EquipmentShape shape) {
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
        var decks = new ArrayList<Mesh.Polygon>();
        for (int k = 1; k <= n; k++) {
            double y = tray(p, k), w = .9 * r;
            decks.addAll(Actors.box("deck", new double[]{x - w, y - .02, z - w}, new double[]{x + w, y + .02, z + w}, Mesh.Material.STEELWORK).polygons());
        }
        var trays = new Actor("trays", decks);
        double[] toCamera = {-DeterministicMath.sin(YAW), 0, -DeterministicMath.cos(YAW)};
        double top = tray(p, 1), bottom = tray(p, n), feed = tray(p, p.trays().feed()), draw = tray(p, p.trays().draws().getFirst());
        double middle = (tray(p, 1) + 2.5) / 2;
        double[] feedIn = nozzle(shape, EquipmentShape.Role.FEED), over = nozzle(shape, EquipmentShape.Role.VAPOUR_OUTLET), side = nozzle(shape, EquipmentShape.Role.DRAW),
                foot = nozzle(shape, EquipmentShape.Role.BOTTOMS), loop = nozzle(shape, EquipmentShape.Role.PUMPAROUND_DRAW), steam = nozzle(shape, EquipmentShape.Role.VAPOUR_RETURN);
        // The dots' ways from the feed: the light up to the top and out, the middle to the draw, the heavy down to the foot.
        var source = new Track.Box(new double[]{x - .4 * r, feed - .3, z - .4 * r}, new double[]{x + .4 * r, feed + .3, z + .4 * r});
        var up = List.of(new double[]{x, feed, z}, new double[]{x, top + .6, z}, over);
        var across = List.of(new double[]{x, feed, z}, new double[]{x, draw, z}, side);
        var down = List.of(new double[]{x, feed, z}, new double[]{x, bottom - .5, z}, foot);
        double spread = .55 * r;
        return Scene.builder(TITLE)
                .actor(tower).actor(trays)
                .track(new Track.Camera(List.of(
                        new Track.Key(0, 0, .1, SCALE, x, middle, z),
                        new Track.Key(STOP_1, YAW, PITCH, SCALE, x, middle, z),
                        // Stop 2 closes in on the trays round the feed, where the dots part; stops 3 and 4 show the whole tower again.
                        new Track.Key(STOP_1 + 3, YAW, CLOSE_PITCH, CLOSE_SCALE, x, feed + 1, z),
                        new Track.Key(STOP_2, YAW, CLOSE_PITCH, CLOSE_SCALE, x, feed + 1, z),
                        new Track.Key(STOP_2 + 3, YAW, PITCH, SCALE, x, middle, z),
                        new Track.Key(STOP_4, YAW, PITCH, SCALE, x, middle, z))))
                .track(new Track.Visible("trays", STOP_1, STOP_4, .5))
                .track(new Track.Cut("tower", new double[]{x, 0, z}, toCamera, STOP_1 + .5, 2))
                .track(new Track.Caption(TITLE + ".s1", 0, STOP_1))
                .track(new Track.Caption(TITLE + ".s2", STOP_1, STOP_2))
                .track(new Track.Caption(TITLE + ".s3", STOP_2, STOP_3))
                .track(new Track.Caption(TITLE + ".s4", STOP_3, STOP_4))
                .track(new Track.Label(feedIn, FEED, 0, STOP_2))
                .track(new Track.Label(over, TOP, STOP_2, STOP_3))
                .track(new Track.Label(side, MIDDLE, STOP_2, STOP_3))
                .track(new Track.Label(foot, BOTTOM, STOP_2, STOP_3))
                .track(new Track.Label(loop, LOOP, STOP_3, STOP_4))
                .track(new Track.Label(steam, STEAM, STOP_3, STOP_4))
                .track(new Track.Legend(STOP_1, STOP_4))
                .track(new Track.Dots(Track.Kind.LIGHT, new Track.Line(up, spread, source, 14, 1.0, .1), STOP_1 + 2, STOP_4, 14, 1.4))
                .track(new Track.Dots(Track.Kind.MIDDLE, new Track.Line(across, spread, source, 10, 1.4, .1), STOP_1 + 2.5, STOP_4, 10, .9))
                .track(new Track.Dots(Track.Kind.HEAVY, new Track.Line(down, spread, source, 10, 1.4, .1), STOP_1 + 2.5, STOP_4, 10, 1.1))
                .stop(STOP_1).stop(STOP_2).stop(STOP_3).stop(STOP_4)
                .build();
    }
}
