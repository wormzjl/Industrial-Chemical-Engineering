package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * The film "inside the still" (batch 2026-11-12-tutorial-system, phase P0): the 500 L solid-fired pot still with its
 * built-in condenser, in three stops. 1: the whole still, the camera turning from the front to a three-quarter view. 2:
 * the pot cut open, dots of three colours drifting in its liquid. 3: the neck and the cooler cut open; the light dots rise
 * through the neck into the cooler and run down to the outlet, a few middle ones follow late, the heavy ones stay.
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
        return Scene.builder(TITLE)
                .actor(still).actor(neck).actor(condenser)
                .track(new Track.Camera(List.of(
                        new Track.Key(0, 0, .12, 30, 2, 2, 1.5),
                        new Track.Key(STOP_1, YAW, PITCH, 30, 2, 2, 1.5),
                        new Track.Key(8, YAW, .62, 84, px, (pot[1] + pot[4]) / 2, pz),
                        new Track.Key(STOP_2, YAW, .62, 84, px, (pot[1] + pot[4]) / 2, pz),
                        new Track.Key(STOP_2 + 2.5, YAW, PITCH, 34, (px + cx) / 2, (pot[1] + neckY) / 2 + .2, (pz + (cooler[2] + cooler[5]) / 2) / 2),
                        new Track.Key(STOP_3, YAW, PITCH, 34, (px + cx) / 2, (pot[1] + neckY) / 2 + .2, (pz + (cooler[2] + cooler[5]) / 2) / 2))))
                .track(new Track.Cut("pot", new double[]{px, 0, pz}, toCamera, STOP_1 + .5, 2))
                .track(new Track.Cut("neck", new double[]{px, neckY, pz}, new double[]{0, 1, 0}, STOP_2, 1.5))
                .track(new Track.Cut("cooler", new double[]{cx, cy, 0}, acrossCooler, STOP_2, 1.5))
                .track(new Track.Caption("tutorial.icheme.film.still_inside.s1", 0, STOP_1))
                .track(new Track.Caption("tutorial.icheme.film.still_inside.s2", STOP_1, STOP_2))
                .track(new Track.Caption("tutorial.icheme.film.still_inside.s3", STOP_2, STOP_3))
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
