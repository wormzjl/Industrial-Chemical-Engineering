package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import com.wormzjl.icheme.science.tutorial.scene.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * The film "inside the still" (batch 2026-11-12-tutorial-system, phase P0): the 500 L solid-fired pot still with its
 * built-in condenser, in three stops. 1: the whole still, the camera turning from the front to a three-quarter view. 2:
 * the pot cut open, dots of three colours drifting in its liquid. 3: the neck and the cooler cut open; the light dots rise
 * through the neck into the cooler and run down to the outlet, a few middle ones follow late, the heavy ones stay.
 * Since P2b: the pot, the fire and the cooler named at stop 1, the legend of the dot colours from stop 2, the liquid in
 * the pot (shown through its cut), the fire burning at stop 3, and stop 3's camera close enough that the pot, neck and
 * cooler fill the glass's height and a dot is at least {@link #LEAST_DOT} GUI px across.
 *
 * <p>The still is three actors, each with its own cut, so every part of the dots' way shows: the pot (its plane through
 * the pot's axis, facing the camera), the gooseneck over the cooler (its plane through the neck's centre line, facing
 * up), and the cooler with its condensate line (its plane through the cooler's axis, facing the camera's side). Every
 * place comes from the still's own plan ({@link ShapeRasterizer#stillParts}).
 */
public final class StillInside {
    public static final String NAME = "still_inside";
    public static final String TITLE = "tutorial.icheme.film.still_inside";
    /** The stops, seconds. */
    static final double STOP_1 = 5, STOP_2 = 10, STOP_3 = 24;
    /** The three-quarter view's yaw and pitch, radians. */
    static final double YAW = .72, PITCH = .42;
    /** The liquid's height in the pot: this share of its straight wall. */
    static final double FILL = .6;
    /** Stop 3's camera: yaw, pitch and scale (px/m at the design size). */
    static final double CLOSE_YAW = .35, CLOSE_PITCH = .08, CLOSE_SCALE = 43.5;
    /** Stop 3's opening view on the fire, px/m at the design size. */
    static final double FIRE_SCALE = 90;
    /** The least width of a dot at stop 3, GUI px. */
    public static final double LEAST_DOT = 3;
    /** The crude's colour in the pot (RGB); the fire's height, m. */
    public static final int CRUDE = 0x6B4423;
    public static final double FIRE = .3;
    /** The part names' language keys. */
    public static final String POT = "tutorial.icheme.film.part.pot", FIRE_KEY = "tutorial.icheme.film.part.fire", COOLER = "tutorial.icheme.film.part.cooler";

    private StillInside() {
    }

    /** The still shown: 500 L, solid fuel, its built-in condenser, facing north. */
    public static EquipmentShape shape() {
        return EquipmentShape.unit(EquipmentShape.Kind.BATCH_STILL, 500, 1, EquipmentShape.Heading.NORTH, 0)
                .withStill(new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.BUILT_IN, 0));
    }

    /** The pot's liquid, where the dots drift: inside the pot's wall by a dot and more, from its bottom to {@link #FILL} of its wall. */
    public static Track.Box liquid(ShapeRasterizer.StillParts parts) {
        var pot = parts.pot();
        double x = (pot[0] + pot[3]) / 2, z = (pot[2] + pot[5]) / 2, r = (pot[3] - pot[0]) / 2, half = .62 * r - Track.Dots.SIZE;
        double bottom = pot[1] + .02 + Track.Dots.SIZE, top = pot[1] + FILL * (parts.tangent() - pot[1]);
        return new Track.Box(new double[]{x - half, bottom, z - half}, new double[]{x + half, top, z + half});
    }

    /** The pot's liquid as a body: inside its wall, from its floor to its tangent line ({@link Track.Level}'s box). */
    public static Track.Box body(ShapeRasterizer.StillParts parts) {
        var pot = parts.pot();
        double x = (pot[0] + pot[3]) / 2, z = (pot[2] + pot[5]) / 2, r = .95 * (pot[3] - pot[0]) / 2;
        return new Track.Box(new double[]{x - r, pot[1] + .02, z - r}, new double[]{x + r, parts.tangent(), z + r});
    }

    /** The fire bed's top centre, where the fire burns: the still's named part {@code fire_bed}. */
    public static double[] fireBed(EquipmentShape shape) {
        var b = ShapeRasterizer.marks(shape).get("fire_bed");
        return new double[]{(b[0] + b[3]) / 2, b[4], (b[2] + b[5]) / 2};
    }

    /** The firing door's front centre: the still's named part {@code door}. */
    public static double[] door(EquipmentShape shape) {
        var b = ShapeRasterizer.marks(shape).get("door");
        return new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, b[2]};
    }

    /** Stop 3's camera target: the middle of the pot, the neck and the cooler, from near the flame's tip to the gooseneck (the flame's lower part stands under the glass: the whole height does not fit at a dot of 3 px). */
    static double[] closeTarget(ShapeRasterizer.StillParts parts, double[] fire) {
        var pot = parts.pot();
        var cooler = parts.condenser();
        double top = parts.vapour().get(2)[1] + .02;
        return new double[]{(pot[0] + cooler[3]) / 2, (fire[1] + .9 * FIRE + top) / 2, (Math.min(pot[2], cooler[2]) + Math.max(pot[5], cooler[5])) / 2};
    }

    public static Scene build() {
        var shape = shape();
        var parts = ShapeRasterizer.stillParts(shape);
        var pot = parts.pot();
        var cooler = parts.condenser();
        double px = (pot[0] + pot[3]) / 2, pz = (pot[2] + pot[5]) / 2, cx = (cooler[0] + cooler[3]) / 2, cy = (cooler[1] + cooler[4]) / 2;
        double neckY = parts.vapour().get(2)[1], neckFloor = cooler[4] + .1, split = (px + cx) / 2;
        // The gooseneck over the cooler; the cooler and its line on the cooler's side; the pot with its setting and helmet.
        var neck = Actors.equipment("neck", shape, c -> c[1] > neckFloor);
        var condenser = Actors.equipment("cooler", shape, c -> c[1] <= neckFloor && (c[0] - split) * (cx - px) > 0);
        var still = Actors.equipment("pot", shape, c -> c[1] <= neckFloor && (c[0] - split) * (cx - px) <= 0);
        double[] toCamera = {-DeterministicMath.sin(YAW), 0, -DeterministicMath.cos(YAW)};
        // The cooler's plane holds its axis (along z): its normal is the camera's side across that axis.
        double[] acrossCooler = {Math.signum(toCamera[0]), 0, 0};
        var liquid = liquid(parts);

        var light = new ArrayList<double[]>();
        light.add(new double[]{px, liquid.hi()[1], pz});
        light.addAll(parts.vapour());
        light.addAll(parts.condensate());
        double radius = .025;
        var close = closeTarget(parts, fireBed(shape));
        // Stop 3 opens on the fire through its door, then follows the vapour up to the neck and the cooler.
        var door = door(shape);
        double[] atFire = {door[0], door[1] + .25, door[2]};
        double r = (pot[3] - pot[0]) / 2;
        return Scene.builder(TITLE)
                .actor(still).actor(neck).actor(condenser)
                .track(new Track.Camera(List.of(
                        new Track.Key(0, 0, .12, 30, 2, 2, 1.5),
                        new Track.Key(STOP_1, YAW, PITCH, 30, 2, 2, 1.5),
                        new Track.Key(8, YAW, .62, 84, px, (pot[1] + pot[4]) / 2, pz),
                        new Track.Key(STOP_2, YAW, .62, 84, px, (pot[1] + pot[4]) / 2, pz),
                        new Track.Key(STOP_2 + 1.5, CLOSE_YAW, CLOSE_PITCH, FIRE_SCALE, atFire[0], atFire[1], atFire[2]),
                        new Track.Key(STOP_2 + 4, CLOSE_YAW, CLOSE_PITCH, FIRE_SCALE, atFire[0], atFire[1], atFire[2]),
                        new Track.Key(STOP_2 + 7, CLOSE_YAW, CLOSE_PITCH, CLOSE_SCALE, close[0], close[1], close[2]),
                        new Track.Key(STOP_3, CLOSE_YAW, CLOSE_PITCH, CLOSE_SCALE, close[0], close[1], close[2]))))
                .track(new Track.Cut("pot", new double[]{px, 0, pz}, toCamera, STOP_1 + .5, 2))
                .track(new Track.Cut("neck", new double[]{px, neckY, pz}, new double[]{0, 1, 0}, STOP_2, 1.5))
                .track(new Track.Cut("cooler", new double[]{cx, cy, 0}, acrossCooler, STOP_2, 1.5))
                .track(new Track.Caption("tutorial.icheme.film.still_inside.s1", 0, STOP_1))
                .track(new Track.Caption("tutorial.icheme.film.still_inside.s2", STOP_1, STOP_2))
                .track(new Track.Caption("tutorial.icheme.film.still_inside.s3", STOP_2, STOP_3))
                // Stop 1: the three parts named; from stop 2 the legend, the liquid shown through the pot's cut; stop 3 the fire.
                .track(new Track.Label(new double[]{px + r * toCamera[0], (pot[1] + pot[4]) / 2, pz + r * toCamera[2]}, POT, 0, STOP_1))
                .track(new Track.Label(door(shape), FIRE_KEY, 0, STOP_1))
                .track(new Track.Label(new double[]{cx, cooler[4], (cooler[2] + cooler[5]) / 2}, COOLER, 0, STOP_1))
                .track(new Track.Legend(STOP_1, STOP_3))
                .track(new Track.Level(body(parts), Value.of(FILL), CRUDE, "pot", STOP_1, STOP_3))
                .track(new Track.Flame(fireBed(shape), Value.of(FIRE), STOP_2, STOP_3))
                // Stop 2: the three kinds drift in the liquid.
                .track(new Track.Dots(Track.Kind.LIGHT, liquid, 7.5, STOP_2, 14, .12))
                .track(new Track.Dots(Track.Kind.MIDDLE, liquid, 7.5, 15, 10, .09))
                .track(new Track.Dots(Track.Kind.HEAVY, liquid, 7.5, STOP_3, 10, .06))
                // Stop 3: the light ones boil off one after another, up the neck, through the cooler, down to the outlet; three
                // middle ones follow late; the rest stay.
                .track(new Track.Dots(Track.Kind.LIGHT, new Track.Line(light, radius, liquid, 14, .9, .12), STOP_2, STOP_3, 14, .8))
                .track(new Track.Dots(Track.Kind.MIDDLE, new Track.Line(light, radius, liquid, 3, 2, .09), 15, STOP_3, 10, .8))
                .stop(STOP_1).stop(STOP_2).stop(STOP_3)
                .build();
    }
}
