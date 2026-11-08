package com.wormzjl.icheme.science.equipment;

import java.util.List;

/** The six-sided steel profile shared by shaft blocks and the machines' exposed shafts. */
public final class ShaftGeometry {
    public static final int SIDES = 6;
    private static final List<List<List<Mesh.Polygon>>> POLYGONS = build();

    private ShaftGeometry() {}

    /** A block-centred shaft on axis 0, 1 or 2, with its outside diameter in pixels (2 to 8). */
    public static List<Mesh.Polygon> polygons(int axis, int pixels) {
        if (axis < 0 || axis > 2 || pixels < 2 || pixels > 8)
            throw new IllegalArgumentException("Invalid shaft profile");
        return POLYGONS.get(axis).get(pixels - 2);
    }

    private static List<List<List<Mesh.Polygon>>> build() {
        var axes = new java.util.ArrayList<List<List<Mesh.Polygon>>>();
        for (int axis = 0; axis < 3; axis++) {
            var sizes = new java.util.ArrayList<List<Mesh.Polygon>>();
            for (int pixels = 2; pixels <= 8; pixels++) {
                var mesh = new Mesh();
                // Horizontal machine shafts have flats on top after any heading turn. Frame.of's X
                // reference points
                // up instead, which rotates a hexagon 30 degrees relative to those equipment
                // couplings.
                var frame =
                        axis == 0
                                ? new Mesh.Frame(
                                        new double[] {.5, .5, .5},
                                        new double[] {1, 0, 0},
                                        new double[] {0, 0, 1})
                                : Mesh.Frame.of(new double[] {.5, .5, .5}, axis, 1);
                double radius = pixels / 32.0;
                for (int side = 0; side < SIDES; side++) {
                    double a = 2 * Math.PI * side / SIDES, b = 2 * Math.PI * (side + 1) / SIDES;
                    double[] normal = frame.at((a + b) / 2, 1, 0);
                    for (int i = 0; i < 3; i++) normal[i] -= .5;
                    mesh.flat(
                            List.of(
                                    frame.at(a, radius, -.5),
                                    frame.at(b, radius, -.5),
                                    frame.at(b, radius, .5),
                                    frame.at(a, radius, .5)),
                            normal,
                            Mesh.Material.SHAFT);
                }
                mesh.disc(frame, radius, -.5, SIDES, -1, Mesh.Material.SHAFT);
                mesh.disc(frame, radius, .5, SIDES, 1, Mesh.Material.SHAFT);
                sizes.add(List.copyOf(mesh.cell(0, 0, 0)));
            }
            axes.add(List.copyOf(sizes));
        }
        return List.copyOf(axes);
    }
}
