package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import java.util.HashMap;
import java.util.TreeMap;

/** What a natural tower costs: cells, block entities, chunk polygons by material, sections touched, time to mesh. */
public class NaturalCount {
    public static void main(String[] args) {
        for (String arg : args) {
            int d = Integer.parseInt(arg);
            var shape = EquipmentShape.unit(Kind.NATURAL_DRAFT_COOLING_TOWER, d, (int) (1.3 * d), Heading.NORTH, 0);
            long t0 = System.nanoTime();
            var layout = ShapeRasterizer.layout(shape);
            long t1 = System.nanoTime();
            var byMaterial = new TreeMap<String, Integer>();
            var perSection = new HashMap<Long, Integer>();
            int empty = 0;
            for (var cell : layout.shell()) {
                var polygons = ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()));
                if (polygons.isEmpty()) empty++;
                for (var polygon : polygons) byMaterial.merge(polygon.material().name(), 1, Integer::sum);
                long section = ((long) (cell.x() >> 4) & 0xFFFFF) << 40 | ((long) (cell.y() >> 4) & 0xFFFFF) << 20 | (cell.z() >> 4) & 0xFFFFF;
                perSection.merge(section, polygons.size(), Integer::sum);
            }
            long t2 = System.nanoTime();
            var whole = new TreeMap<String, Integer>();
            for (var polygon : ShapeRasterizer.towerShell(shape)) whole.merge(polygon.material().name(), 1, Integer::sum);
            int total = byMaterial.values().stream().mapToInt(Integer::intValue).sum();
            int max = perSection.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("%d m: box %d %d %d, shell cells %d (no polygons %d), interior %d, chunk polygons %d, sections %d, most in a section %d%n",
                    d, shape.sizeX(), shape.sizeY(), shape.sizeZ(), layout.shell().size(), empty, layout.interior().size(), total, perSection.size(), max);
            System.out.printf("   layout %.0f ms, mesh all cells %.0f ms%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6);
            System.out.println("   chunk " + byMaterial);
            System.out.println("   whole " + whole);
        }
    }
}
