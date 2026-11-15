package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.List;

/**
 * The alarm beacon (batch 2026-11-15-fluid-interfaces, W9, decision A1), in sixteenths of a block for a beacon standing on
 * a floor (its mounting face down): a square steel base with the data socket's gland on its north side, a neck, a lamp
 * collar, the lens dome (its own texture; a lit beacon draws the animated lens full-bright) under a four-bar wire guard, and
 * a short stepped horn cone on its east side. The block model ({@code tools/pump-sprites/AlarmAssets.java}) and the
 * collision shape read these boxes; 17 boxes, 102 faces.
 */
public final class AlarmGeometry {
    private AlarmGeometry() {}

    /** A part's finish: the texture its faces take. */
    public enum Finish {
        BASE,
        SOCKET,
        LENS,
        GUARD,
        HORN,
        MOUTH
    }

    /** One box, {@code x..X, y..Y, z..Z} in sixteenths, for a beacon standing on a floor. */
    public record Part(String name, double x, double y, double z, double X, double Y, double Z, Finish finish) {
        public double[] box() {
            return new double[] {x, y, z, X, Y, Z};
        }
    }

    /** The data socket's centre on its gland's north face, in sixteenths (standing on a floor), and its outward axis −Z. */
    public static final double[] SOCKET = {8, 1.5, 1.5};

    public static List<Part> parts() {
        var out = new ArrayList<Part>();
        out.add(new Part("base plate", 3, 0, 3, 13, 3, 13, Finish.BASE));
        out.add(new Part("socket gland", 6, .5, 1.5, 10, 2.5, 3, Finish.SOCKET));
        out.add(new Part("neck", 6, 3, 6, 10, 5, 10, Finish.BASE));
        out.add(new Part("lamp collar", 5, 5, 5, 11, 6, 11, Finish.BASE));
        out.add(new Part("lens", 5.5, 6, 5.5, 10.5, 10.5, 10.5, Finish.LENS));
        out.add(new Part("lens crown", 6.5, 10.5, 6.5, 9.5, 11.5, 9.5, Finish.LENS));
        for (double[] c : new double[][] {{5, 5}, {10.4, 5}, {5, 10.4}, {10.4, 10.4}})
            out.add(new Part("guard post", c[0], 6, c[1], c[0] + .6, 11.5, c[1] + .6, Finish.GUARD));
        out.add(new Part("guard bow", 5, 11.5, 7.7, 11, 12.1, 8.3, Finish.GUARD));
        out.add(new Part("guard bow", 7.7, 11.5, 5, 8.3, 12.1, 11, Finish.GUARD));
        out.add(new Part("guard cap", 7.4, 12.1, 7.4, 8.6, 12.5, 8.6, Finish.GUARD));
        out.add(new Part("horn throat", 10, 3, 6.5, 11.5, 5.5, 9.5, Finish.HORN));
        out.add(new Part("horn body", 11.5, 2.5, 5.75, 13, 6, 10.25, Finish.HORN));
        out.add(new Part("horn bell", 13, 2, 5, 14.5, 6.5, 11, Finish.HORN));
        out.add(new Part("horn mouth", 14.5, 2.3, 5.3, 14.6, 6.2, 10.7, Finish.MOUTH));
        return List.copyOf(out);
    }

    /**
     * {@code box} (sixteenths, standing on a floor) turned to a beacon whose top faces {@code facing} (a direction's ordinal:
     * 0 down, 1 up, 2 north, 3 south, 4 west, 5 east), as the block state's model turns it ({@code x}, {@code y} as the
     * vanilla end rod): up as drawn, down turned 180° about X, a wall mount turned 90° about X, then about Y.
     */
    public static double[] turned(double[] box, int facing) {
        double[] a = point(box[0], box[1], box[2], facing), b = point(box[3], box[4], box[5], facing);
        return new double[] {
            Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
            Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])
        };
    }

    /** A point (sixteenths) of the standing beacon turned to {@code facing}, as {@link #turned}. */
    public static double[] point(double x, double y, double z, int facing) {
        switch (facing) {
            case 1:
                return new double[] {x, y, z};
            case 0:
                return new double[] {x, 16 - y, 16 - z};
            default: {
                // 90° about X (the blockstate's x = 90): up goes north (−Z), north goes down.
                double[] p = {x, z, 16 - y};
                int turns = facing == 2 ? 0 : facing == 5 ? 1 : facing == 3 ? 2 : 3;
                for (int i = 0; i < turns; i++) p = new double[] {16 - p[2], p[1], p[0]};
                return p;
            }
        }
    }

    /** The data socket's centre (sixteenths) for a beacon whose top faces {@code facing}. */
    public static double[] socket(int facing) {
        return point(SOCKET[0], SOCKET[1], SOCKET[2], facing);
    }

    /**
     * The way the data socket faces out of the beacon (a direction's ordinal) for a beacon whose top faces {@code facing}:
     * north standing on a floor, south hanging from a ceiling, down on a wall.
     */
    public static int socketOutward(int facing) {
        double[] a = point(8, 8, 8, facing), b = point(8, 8, 7, facing);
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        if (Math.abs(dy) > .5) return dy < 0 ? 0 : 1;
        if (Math.abs(dz) > .5) return dz < 0 ? 2 : 3;
        return dx < 0 ? 4 : 5;
    }

    /** The blockstate rotation {@code {x, y}} in degrees for {@code facing}, matching {@link #point}. */
    public static int[] rotation(int facing) {
        return switch (facing) {
            case 0 -> new int[] {180, 0};
            case 1 -> new int[] {0, 0};
            case 2 -> new int[] {90, 0};
            case 3 -> new int[] {90, 180};
            case 4 -> new int[] {90, 270};
            default -> new int[] {90, 90};
        };
    }
}
