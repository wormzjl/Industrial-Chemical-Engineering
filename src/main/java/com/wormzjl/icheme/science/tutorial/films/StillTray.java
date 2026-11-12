package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.cards.StillTraysData;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import com.wormzjl.icheme.science.tutorial.scene.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * The film "one tray" (batch 2026-11-12-tutorial-system, P4b; guide step {@code tower/tray}): chapter 2's still with the
 * column of chapter 3's recording ({@link StillTraysData#trays()} trays), in three stops. 1: the whole still, its column
 * between the pot and the cooler. 2: the column cut open, a thin layer of liquid on each tray; light dots rise from the pot
 * through every tray to the cooler. 3: middle and heavy dots fall back from the top trays to the pot while the light ones
 * rise on. The column's places come from the still's plan ({@link ShapeRasterizer#stillParts}): its top is the vapour
 * path's second point, its trays stand {@link #TRAY_SPACING} apart from {@link #FIRST_TRAY} over its base, its radius
 * {@link #COLUMN_SHARE} of the pot's diameter ({@code ProcessUnit.StillPlan}; {@code StillTrayFilmTest} holds them to the
 * plan's drawn sight glasses).
 */
public final class StillTray {
    public static final String NAME = "still_tray";
    public static final String TITLE = "tutorial.icheme.film." + NAME;
    static final double STOP_1 = 5, STOP_2 = 13, STOP_3 = 22;
    static final double YAW = .72, PITCH = .42, CLOSE_YAW = .15, CLOSE_PITCH = .22;
    /** The still plan's column: radius over the pot's diameter; the first tray over the column's base and the trays' spacing, m; the top's room over the last tray. */
    public static final double COLUMN_SHARE = .124, FIRST_TRAY = .25, TRAY_SPACING = .3, TOP_ROOM = .2;
    /** A tray's deck and its liquid's depth, m; the liquid's colour. */
    static final double DECK = .02, LIQUID = .05;
    public static final int TRAY_LIQUID = 0xB07A3A;
    public static final String COLUMN = "tutorial.icheme.film.part.column", TRAY = "tutorial.icheme.film.part.tray";

    private StillTray() {
    }

    /** The still shown: chapter 2's (500 L, solid fuel, its built-in condenser) with the recording's column. */
    public static EquipmentShape shape() {
        return EquipmentShape.unit(EquipmentShape.Kind.BATCH_STILL, 500, 1, EquipmentShape.Heading.NORTH, 0)
                .withStill(new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.BUILT_IN, StillTraysData.trays()));
    }

    /** The column's axis (x, z), its base and top heights and its radius, m, from the still's parts. */
    public record Column(double x, double z, double base, double top, double radius, int trays) {
        /** Tray {@code k}'s deck height, 0 the lowest. */
        public double tray(int k) {
            return base + FIRST_TRAY + TRAY_SPACING * k;
        }
    }

    public static Column column(ShapeRasterizer.StillParts parts, int trays) {
        var pot = parts.pot();
        var top = parts.vapour().get(1);
        double d = pot[3] - pot[0];
        return new Column(top[0], top[2], top[1] - TRAY_SPACING * trays - TOP_ROOM, top[1], COLUMN_SHARE * d, trays);
    }

    /** The liquid on tray {@code k}: a thin body on its deck. */
    static Track.Box liquid(Column c, int k) {
        double r = .85 * c.radius(), y = c.tray(k) + DECK / 2;
        return new Track.Box(new double[]{c.x() - r, y, c.z() - r}, new double[]{c.x() + r, y + LIQUID, c.z() + r});
    }

    public static Scene build() {
        var shape = shape();
        var parts = ShapeRasterizer.stillParts(shape);
        var c = column(parts, StillTraysData.trays());
        var pot = parts.pot();
        double px = (pot[0] + pot[3]) / 2, pz = (pot[2] + pot[5]) / 2, rc = c.radius();
        // The column with its head as its own actor, cut through its axis toward the camera; the rest of the still whole.
        java.util.function.Predicate<double[]> inColumn = p -> Math.abs(p[0] - c.x()) <= rc + .06 && Math.abs(p[2] - c.z()) <= rc + .06 && p[1] >= c.base() - .02 && p[1] <= c.top() + rc;
        var column = Actors.equipment("column", shape, inColumn);
        var still = Actors.equipment("still", shape, inColumn.negate());
        var decks = new ArrayList<Mesh.Polygon>();
        for (int k = 0; k < c.trays(); k++) {
            double r = .9 * rc, y = c.tray(k);
            decks.addAll(Actors.box("deck", new double[]{c.x() - r, y - DECK / 2, c.z() - r}, new double[]{c.x() + r, y + DECK / 2, c.z() + r}, Mesh.Material.STEELWORK).polygons());
        }
        var trays = new Actor("trays", decks);
        double[] toCamera = {-DeterministicMath.sin(CLOSE_YAW), 0, -DeterministicMath.cos(CLOSE_YAW)};
        var potLiquid = StillInside.liquid(parts);
        // Up: from the pot's liquid through the column's axis to its top, on through the gooseneck into the cooler.
        var up = new ArrayList<double[]>();
        up.add(new double[]{px, potLiquid.hi()[1], pz});
        up.add(new double[]{c.x(), c.base(), c.z()});
        up.add(new double[]{c.x(), c.top(), c.z()});
        up.addAll(parts.vapour().subList(2, parts.vapour().size()));
        // Down: from the top tray's liquid down the column to the pot.
        var top = liquid(c, c.trays() - 1);
        var down = List.of(new double[]{c.x(), c.tray(c.trays() - 1), c.z()}, new double[]{c.x(), c.base() + .02, c.z()}, new double[]{px, potLiquid.hi()[1], pz});
        double radius = .55 * rc;
        double mid = (c.base() + c.top()) / 2;
        var b = Scene.builder(TITLE)
                .actor(still).actor(column).actor(trays)
                .track(new Track.Camera(List.of(
                        new Track.Key(0, 0, .12, 30, 2, 2, 1.5),
                        new Track.Key(STOP_1, YAW, PITCH, 30, 2, 2, 1.5),
                        new Track.Key(STOP_1 + 3, CLOSE_YAW, CLOSE_PITCH, 72, c.x(), mid + .2, c.z()),
                        new Track.Key(STOP_3, CLOSE_YAW, CLOSE_PITCH, 72, c.x(), mid + .2, c.z()))))
                .track(new Track.Visible("trays", STOP_1, STOP_3, .5))
                .track(new Track.Cut("column", new double[]{c.x(), 0, c.z()}, toCamera, STOP_1 + .5, 2))
                .track(new Track.Caption(TITLE + ".s1", 0, STOP_1))
                .track(new Track.Caption(TITLE + ".s2", STOP_1, STOP_2))
                .track(new Track.Caption(TITLE + ".s3", STOP_2, STOP_3))
                .track(new Track.Label(new double[]{px + (pot[3] - pot[0]) / 2 * toCamera[0], (pot[1] + pot[4]) / 2, pz + (pot[3] - pot[0]) / 2 * toCamera[2]}, StillInside.POT, 0, STOP_1))
                .track(new Track.Label(new double[]{c.x() + rc * toCamera[0], mid, c.z() + rc * toCamera[2]}, COLUMN, 0, STOP_1))
                .track(new Track.Label(new double[]{c.x() + .9 * rc * toCamera[0], c.tray(1), c.z() + .9 * rc * toCamera[2]}, TRAY, STOP_1, STOP_3))
                .track(new Track.Legend(STOP_1, STOP_3));
        for (int k = 0; k < c.trays(); k++) b.track(new Track.Level(liquid(c, k), Value.of(1), TRAY_LIQUID, "column", STOP_1, STOP_3));
        return b
                // Stop 2: the light ones rise through every tray's liquid to the cooler.
                .track(new Track.Dots(Track.Kind.LIGHT, new Track.Line(up, radius, potLiquid, 16, 1.0, .1), STOP_1 + 2, STOP_3, 16, .5))
                // Stop 3: the heavier ones turn back on the top trays and run down to the pot; the light ones rise on.
                .track(new Track.Dots(Track.Kind.MIDDLE, new Track.Line(down, radius, top, 6, 1.2, .05), STOP_2, STOP_3, 6, .3))
                .track(new Track.Dots(Track.Kind.HEAVY, new Track.Line(down, radius, top, 5, 1.4, .05), STOP_2 + .6, STOP_3, 5, .3))
                .stop(STOP_1).stop(STOP_2).stop(STOP_3)
                .build();
    }
}
