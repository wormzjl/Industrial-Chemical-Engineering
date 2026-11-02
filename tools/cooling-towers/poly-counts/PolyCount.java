package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import java.util.TreeMap;

/** Counts the drawn polygons of a fan tower, by material: PolyCount width fill columns rows. */
public class PolyCount {
    public static void main(String[] args) {
        double width = Double.parseDouble(args[0]);
        int fill = Integer.parseInt(args[1]);
        var shape = EquipmentShape.unit(Kind.INDUCED_DRAFT_COOLING_TOWER, width, fill, Heading.NORTH, 0)
                .withTowerBank(new TowerBank(Integer.parseInt(args[2]), Integer.parseInt(args[3])));
        var fixed = new TreeMap<String, Integer>();
        var moving = new TreeMap<String, Integer>();
        var layout = ShapeRasterizer.layout(shape);
        int blocks = 0;
        for (var cell : layout.shell()) {
            blocks++;
            for (var polygon : ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                fixed.merge(polygon.material().name(), 1, Integer::sum);
        }
        var whole = new TreeMap<String, Integer>();
        for (var polygon : ShapeRasterizer.towerShell(shape)) whole.merge(polygon.material().name(), 1, Integer::sum);
        for (var part : ShapeRasterizer.moving(shape))
            for (var polygon : part.polygons()) moving.merge(polygon.material().name(), 1, Integer::sum);
        int a = fixed.values().stream().mapToInt(Integer::intValue).sum(), b = moving.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println(width + " m, fill " + fill + ", " + args[2] + " x " + args[3] + ": " + blocks + " shell blocks, fixed " + a + ", moving " + b);
        System.out.println("  chunk  " + fixed);
        System.out.println("  shell  " + whole + " = " + whole.values().stream().mapToInt(Integer::intValue).sum());
        System.out.println("  moving " + moving);
    }
}
