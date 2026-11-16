package com.wormzjl.icheme.science.equipment;
/** S8 scratch: along each plunger neck at design size, just outside its radius, where a solid hides it. */
public class ArrowProbe2 {
    public static void main(String[] a) {
        for (int m = 1; m <= 4; m++) {
            var s = EquipmentShape.unit(EquipmentShape.Kind.PLUNGER_PUMP, m, 1, EquipmentShape.Heading.NORTH, 0);
            for (int k = 0; k < s.nozzles().size(); k++) {
                var nz = s.nozzles().get(k); double od = nz.defaultOd(); int axis = nz.axis(); double[] base = nz.base();
                double end = nz.length() - PipeSizes.flangeThickness(od) - .055;
                var sb = new StringBuilder();
                for (double t = 0; t <= end + 1e-9; t += .01) {
                    int hidden = 0;
                    for (int side = 0; side < 8; side++) {
                        double ang = 2 * Math.PI * side / 8; double[] q = base.clone(); q[axis] = base[axis] + t * nz.sign();
                        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3; q[a1] += (od / 2 + .02) * Math.cos(ang); q[a2] += (od / 2 + .02) * Math.sin(ang);
                        if (ShapeRasterizer.hides(s, k, 0, q)) hidden++;
                    }
                    sb.append(hidden);
                }
                System.out.printf(java.util.Locale.ROOT, "PP model %d %s: base %.3f,%.3f,%.3f axis %d, bare to %.3f m; hidden sides of 8 per cm from the base: %s%n", m, nz.role(), base[0], base[1], base[2], axis, end, sb);
            }
        }
    }
}
