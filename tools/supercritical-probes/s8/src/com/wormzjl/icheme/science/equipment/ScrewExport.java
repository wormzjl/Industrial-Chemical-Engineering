package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Scratch (S8 W4): the built PLUNGER_PUMP design's polygons for the four models, written as tmp/plunger-forms' PlungerForms.write writes them. */
public final class ScrewExport {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        int[] caps = {100, 300, 650, 2500};
        for (int i = 0; i < 4; i++) {
            var shape = EquipmentShape.unit(EquipmentShape.Kind.DRY_SCREW_PUMP, i + 1, 1, EquipmentShape.Heading.NORTH, 0);
            write(out.resolve("DS_" + caps[i] + ".json"), ProcessUnit.of(shape));
        }
    }

    static void write(Path file, ProcessUnit u) throws Exception {
        var sb = new StringBuilder("{\"polygons\":[");
        boolean first = true;
        var extra = new Mesh();
        for (var nz : u.nozzles()) {
            var ref = new double[3];
            ref[(nz.axis() + 1) % 3] = 1;
            var f = new Frame(nz.base(), nz.direction(), ref);
            double od = nz.defaultOd(), r = od / 2, t = PipeSizes.flangeThickness(od);
            int n = PipeSizes.sides(od);
            extra.prism(f, r, 0, nz.length(), n, Material.PIPE);
            extra.ring(f, r, PipeSizes.flange(od) / 2, nz.length() - t, nz.length(), n, 3, Material.FLANGE);
        }
        for (var mesh : new Mesh[]{u.mesh(), extra})
            for (var facet : mesh.facets()) {
                var p = Mesh.whole(facet);
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"m\":\"").append(p.material().name()).append("\",\"v\":[");
                var v = p.positions();
                for (int i = 0; i < v.length; i++) sb.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.4f", v[i]));
                sb.append("]}");
            }
        sb.append("]}");
        Files.writeString(file, sb);
        System.out.println(file.getFileName() + " " + u.nozzles().size() + " nozzles, " + u.motions.size() + " motions, " + u.solids.size() + " solids");
    }
}
