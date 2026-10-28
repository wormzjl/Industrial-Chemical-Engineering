package com.wormzjl.icheme.science.equipment;

import java.util.List;
import java.util.Locale;

/** The failing test designs: every side nozzle's role, side and height, placed or not; the platform levels. */
public class Crowded {
    public static void main(String[] args) {
        var crowded = EquipmentShape.column(4, new ColumnTrays(30, 15, List.of(8, 9, 20, 21), List.of(31),
                List.of(new ColumnTrays.Pumparound(11, 10), new ColumnTrays.Pumparound(23, 22))), EquipmentShape.Heading.NORTH, 4);
        var plain = EquipmentShape.column(2, new ColumnTrays(6, 3, List.of(), List.of(7), List.of()), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(2));
        for (var s : List.of(crowded, plain)) {
            System.out.printf(Locale.ROOT, "D %.1f trays %d: bottom tangent %.1f, sump %.2f, bottom tray at %.2f%n", s.diameter(), s.trays().trays(), s.bottomTangent(),
                    ColumnTrays.bottomSpace(s.diameter()), s.bottomTangent() + ColumnTrays.bottomSpace(s.diameter()));
            var plan = ColumnStructure.plan(s);
            var levels = new java.util.TreeSet<Integer>();
            for (var p : plan.platforms()) levels.add(p.level());
            System.out.println("  platform levels " + levels);
            for (var n : ColumnDesign.sides(s))
                System.out.printf(Locale.ROOT, "  %-16s %d home %d side %d %s%n", n.role(), n.index(), n.home(), n.side(), n.nozzle() == null ? "UNPLACED" : String.format(Locale.ROOT, "y %.1f", n.nozzle().base()[1]));
        }
    }
}
