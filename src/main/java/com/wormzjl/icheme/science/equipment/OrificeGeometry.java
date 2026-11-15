package com.wormzjl.icheme.science.equipment;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * A restriction orifice plate on its pipe (batch 2026-11-15-fluid-interfaces, W6), in the block's unit cell: a pair of
 * weld-neck orifice flanges at the block's centre with the thin plate held between them, its paddle handle standing up
 * out of the joint (the cue a plant operator reads the plate by), and the stud nuts on both flanges' backs
 * ({@link Mesh#flangeBolts}: solid nuts, owner 2026-11-15). Few polygons: the handle's stamping and the flange faces are
 * texture. An accessory (the item) has an open bore and no arms; a pipe draws its own arms out to both ends of its run.
 */
public final class OrificeGeometry {
    private static final double[] CENTRE = {.5, .5, .5};

    private OrificeGeometry() {}

    public static List<Polygon> polygons(LineGeometry.Key key, boolean accessory) {
        return polygons(key, accessory, RenderDetail.NORMAL);
    }

    public static List<Polygon> polygons(LineGeometry.Key key, boolean accessory, RenderDetail detail) {
        var mesh = new Mesh(detail);
        double od = PipeSizes.outsideOf(key.outside());
        int axis = key.facing() & ~1, sides = Math.max(16, PipeSizes.sides(od));
        double[] run = LineGeometry.step(axis | 1), up = handleUp(axis);
        var f = new Frame(CENTRE, run, up);
        double rim = PipeSizes.flange(od) / 2, hub = PipeSizes.hubOutside(od) / 2, t = PipeSizes.flangeThickness(od);
        double plate = plateThickness(od), gap = plate / 2, neck = Math.max(t, PipeSizes.hub(od));
        double back = gap + t, end = back + neck;
        double bore = accessory ? Math.min(.46 * od, .9 * hub) : 0;
        // The plate's edge in the joint, a little inside the rims, and its paddle handle up out of the bolt circle.
        double edge = .5 * (hub + rim);
        // The two flanges: face to the plate, rim, back with its nuts, the neck tapering to the pipe.
        for (int sign : new int[]{-1, 1}) {
            mesh.annulus(f, edge, rim, sign * gap, sides, -sign, Material.FLANGE);
            mesh.prism(f, rim, sign < 0 ? -back : gap, sign < 0 ? -gap : back, sides, Material.FLANGE);
            mesh.annulus(f, hub, rim, sign * back, sides, sign, Material.FLANGE);
            mesh.flangeBolts(f, od, hub, rim, sign * back, sign, Material.BOLT);
            mesh.frustum(f, sign < 0 ? od / 2 : hub, sign < 0 ? hub : od / 2, sign < 0 ? -end : back, sign < 0 ? -back : end, sides, Material.PIPE);
            if (accessory) mesh.annulus(f, bore, od / 2, sign * end, sides, sign, Material.PIPE);
        }
        mesh.prism(f, edge, -gap, gap, sides, Material.STEELWORK);
        double width = Math.max(.05, Math.min(.22, .45 * od)), reach = rim + Math.max(.05, .35 * od);
        var side = f.side();
        double[] lo = new double[3], hi = new double[3];
        for (int i = 0; i < 3; i++) {
            double base = CENTRE[i] + up[i] * (edge - .01), top = CENTRE[i] + up[i] * reach;
            double a = base - side[i] * width / 2 - run[i] * gap, b = top + side[i] * width / 2 + run[i] * gap;
            lo[i] = Math.min(a, b);
            hi[i] = Math.max(a, b);
        }
        mesh.box(lo, hi, ALL_FACES, Material.STEELWORK);
        if (accessory) mesh.bore(f, bore, -end, end, sides, Material.OPENING);
        else for (int face : new int[]{axis, axis ^ 1})
            LineGeometry.arm(mesh, face, end, od, LineGeometry.fittingEnd(key, face, od), LineGeometry.ref(face), sides, true);
        return LineGeometry.overhanging(mesh);
    }

    /** The handle stands up off a horizontal run, and off a vertical one towards north (−Z). */
    static double[] handleUp(int axis) {
        return axis == 0 ? new double[]{0, 0, -1} : new double[]{0, 1, 0};
    }

    /** The plate's thickness: 3 mm on a small line to 12 mm on the largest. */
    static double plateThickness(double od) {
        return Math.max(.004, Math.min(.012, .015 * od + .003));
    }
}
