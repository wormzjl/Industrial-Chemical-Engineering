package com.wormzjl.icheme.science.equipment;
import java.lang.reflect.Method;
/** S8 scratch: PortMarkings.band's own per-side shown lengths on each plunger neck at design size (reflection on its privates). */
public class ArrowProbe3 {
    public static void main(String[] a) throws Exception {
        Method shown = PortMarkings.class.getDeclaredMethod("shown", EquipmentShape.class, int.class, int.class, Mesh.Frame.class, double[].class, double.class, double.class, double.class);
        Method normal = PortMarkings.class.getDeclaredMethod("normal", Mesh.Frame.class, double[].class, double.class, int.class);
        shown.setAccessible(true); normal.setAccessible(true);
        for (int m = 1; m <= 4; m++) {
            var s = EquipmentShape.unit(EquipmentShape.Kind.PLUNGER_PUMP, m, 1, EquipmentShape.Heading.NORTH, 0);
            for (int k = 0; k < s.nozzles().size(); k++) {
                var nz = s.nozzles().get(k); double od = nz.defaultOd(); int sides = PipeSizes.sides(od);
                double end = nz.length() - PipeSizes.flangeThickness(od) - .055, longest = Math.min(.65, Math.max(.28, 1.6 * od));
                var frame = Mesh.Frame.of(nz.base(), nz.axis(), nz.sign()); var tangent = frame.side();
                var sb = new StringBuilder();
                for (int side = 0; side < 4; side++) {
                    double length = Math.min(longest + .02, end);
                    for (double off = -.5; off <= .5; off += .25)
                        length = Math.min(length, (double) shown.invoke(null, s, k, 0, frame, (double[]) normal.invoke(null, frame, tangent, side * sides / 4 + .5 + off, sides), od / 2 + .01, end, length));
                    sb.append(String.format(java.util.Locale.ROOT, " side %d %.3f", side, length - .02));
                }
                System.out.printf(java.util.Locale.ROOT, "PP model %d %s: end %.4f, sides %d;%s (needs >= 0.100)%n", m, nz.role(), end, sides, sb);
            }
        }
    }
}
