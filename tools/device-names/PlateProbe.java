package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * N1 probe (batch 2026-11-02-process-simulator): where {@link Nameplate} puts the plate on every shape of
 * EquipmentLayoutGoldenTest's list (each kind at its least, middle and largest size, facing north). One line each.
 */
public class PlateProbe {
    public static void main(String[] args) {
        int none = 0, front = 0;
        for (var shape : shapes()) {
            long t = System.nanoTime();
            var plate = Nameplate.of(shape);
            double ms = (System.nanoTime() - t) / 1e6;
            if (plate.isEmpty()) none++;
            else if (plate.get().facing() == shape.front()) front++;
            System.out.printf(Locale.ROOT, "%-28s D %5.2f L %5.1f support %2d box %dx%dx%d  %s  (%.0f ms)%n", shape.kind(), shape.diameter(), shape.tangentLength(), shape.support(),
                    shape.sizeX(), shape.sizeY(), shape.sizeZ(), plate.map(p -> String.format(Locale.ROOT, "%s at (%.2f, %.2f, %.2f) %.2f x %.2f", p.facing(), p.x(), p.y(), p.z(), p.width(), p.height())).orElse("NO PLATE"), ms);
        }
        System.out.println("front " + front + ", none " + none);
    }

    static List<EquipmentShape> shapes() {
        var shapes = new ArrayList<EquipmentShape>();
        for (var orientation : List.of(Orientation.VERTICAL, Orientation.HORIZONTAL))
            for (double diameter : sizes(EquipmentShape.MIN_DIAMETER, EquipmentShape.MAX_DIAMETER)) {
                double length = Math.min(EquipmentShape.MAX_TANGENT_LENGTH, Math.rint(2 * diameter));
                shapes.add(EquipmentShape.drum(diameter, length, orientation, EquipmentShape.Heading.NORTH, EquipmentShape.defaultSupport(diameter, orientation)));
            }
        for (double diameter : sizes(EquipmentShape.MIN_DIAMETER, EquipmentShape.MAX_COLUMN_DIAMETER))
            shapes.add(EquipmentShape.column(diameter, ColumnTrays.simple(20, 10), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(diameter)));
        for (var kind : EquipmentShape.Kind.values()) {
            if (!kind.designed()) continue;
            double low = ProcessUnit.minDiameter(kind), high = ProcessUnit.maxDiameter(kind);
            for (double diameter : new double[]{low, ProcessUnit.snap(kind, (low + high) / 2), high}) {
                int length = ProcessUnit.defaultLength(kind, diameter);
                shapes.add(EquipmentShape.designed(kind, diameter, length, EquipmentShape.Heading.NORTH, ProcessUnit.defaultSupport(kind, diameter, length), ProcessUnit.Bank.ONE, 0));
            }
        }
        return shapes;
    }

    private static double[] sizes(double low, double high) {
        return new double[]{low, Math.rint((low + high)) / 2, high};
    }
}
