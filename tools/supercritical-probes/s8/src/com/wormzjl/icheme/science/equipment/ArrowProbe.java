package com.wormzjl.icheme.science.equipment;
/** S8 scratch: each plunger nozzle's neck at its design size, and whether PortMarkings draws its arrows. */
public class ArrowProbe {
    public static void main(String[] a) {
        for (int m = 1; m <= 4; m++) {
            var s = EquipmentShape.unit(EquipmentShape.Kind.PLUNGER_PUMP, m, 1, EquipmentShape.Heading.NORTH, 0);
            for (int k = 0; k < s.nozzles().size(); k++) {
                var nz = s.nozzles().get(k);
                double od = nz.defaultOd();
                var mesh = new Mesh();
                PortMarkings.band(mesh, s, k, 0, PortMarkings.flow(s.kind(), nz.role()));
                var root = ShapeRasterizer.nozzleRoot(s, k);
                System.out.printf(java.util.Locale.ROOT, "PP model %d %s: od %.4f m, neck length %.4f m, flange %.4f m, root %s, facets %d%n", m, nz.role(), od, nz.length(),
                        PipeSizes.flangeThickness(od), root == null ? "none" : String.format(java.util.Locale.ROOT, "r-od/2 %.3e to %.4f", root.radius() - od / 2, root.end(nz.length(), od)), mesh.facets().size());
            }
        }
    }
}
