package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.List;

/**
 * The alarm beacon (batch 2026-11-15-fluid-interfaces, W9, decision A1; owner's form 2026-11-16, "Stacked unit"), in
 * sixteenths of a block for a beacon standing on a floor (its mounting face down): a combined sounder and beacon as one
 * stacked unit. A square mounting plate with four fixing bolts; on it the round sounder body (two crossed boxes, a stepped
 * round section) with grille slots all round, the data socket's cable gland on its back (north) near the plate; a collar;
 * the dome lens (its own texture; a lit beacon draws the animated lens full-bright) and its crown under two crossed wire
 * hoops, whose posts stand on the collar against the lens and whose bows rest on the crown. The block model
 * ({@code tools/pump-sprites/AlarmAssets.java}) and the collision shape read these boxes; 15 boxes, 90 faces.
 */
public final class AlarmGeometry {
    private AlarmGeometry() {}

    /** A part's finish: the texture its faces take. */
    public enum Finish {
        BASE,
        SOCKET,
        GRILLE,
        LENS,
        GUARD
    }

    /** One box, {@code x..X, y..Y, z..Z} in sixteenths, for a beacon standing on a floor. */
    public record Part(String name, double x, double y, double z, double X, double Y, double Z, Finish finish) {
        public double[] box() {
            return new double[] {x, y, z, X, Y, Z};
        }
    }

    /** The data socket's centre on its gland's north face, in sixteenths (standing on a floor), and its outward axis −Z. */
    public static final double[] SOCKET = {8, 3, 3};

    public static List<Part> parts() {
        var out = new ArrayList<Part>();
        out.add(new Part("mounting plate", 3, 0, 3, 13, 1, 13, Finish.BASE));
        // The sounder: a round body drawn as two crossed boxes, its grille slots all round.
        out.add(new Part("sounder body", 4.5, 1, 5.5, 11.5, 7.5, 10.5, Finish.GRILLE));
        out.add(new Part("sounder body", 5.5, 1, 4.5, 10.5, 7.5, 11.5, Finish.GRILLE));
        out.add(new Part("socket gland", 6.5, 1.75, 3, 9.5, 4.25, 4.5, Finish.SOCKET));
        out.add(new Part("collar", 4, 7.5, 5, 12, 8.5, 11, Finish.BASE));
        out.add(new Part("collar", 5, 7.5, 4, 11, 8.5, 12, Finish.BASE));
        out.add(new Part("lens", 5.5, 8.5, 6.25, 10.5, 12.5, 9.75, Finish.LENS));
        out.add(new Part("lens", 6.25, 8.5, 5.5, 9.75, 12.5, 10.5, Finish.LENS));
        out.add(new Part("lens crown", 6.75, 12.5, 6.75, 9.25, 13.25, 9.25, Finish.LENS));
        // Two crossed wire hoops: posts on the collar against the lens, bows resting on the crown.
        out.add(new Part("guard post", 5, 8.5, 7.7, 5.5, 13.25, 8.3, Finish.GUARD));
        out.add(new Part("guard post", 10.5, 8.5, 7.7, 11, 13.25, 8.3, Finish.GUARD));
        out.add(new Part("guard post", 7.7, 8.5, 5, 8.3, 13.25, 5.5, Finish.GUARD));
        out.add(new Part("guard post", 7.7, 8.5, 10.5, 8.3, 13.25, 11, Finish.GUARD));
        out.add(new Part("guard bow", 5, 13.25, 7.7, 11, 13.85, 8.3, Finish.GUARD));
        out.add(new Part("guard bow", 7.7, 13.25, 5, 8.3, 13.85, 11, Finish.GUARD));
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
