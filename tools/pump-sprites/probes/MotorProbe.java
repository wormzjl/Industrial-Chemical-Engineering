package com.wormzjl.icheme.science.equipment;

/** Every motor and generator size's box, shaft blocks and terminal cell at each speed, grade and on a 3 m table: a before/after check. */
public class MotorProbe {
    public static void main(String[] args) {
        for (var kind : new EquipmentShape.Kind[]{EquipmentShape.Kind.MOTOR, EquipmentShape.Kind.GENERATOR})
            for (double kw : ProcessUnit.sizes(kind))
                for (int support : new int[]{0, 3}) {
                    var shape = EquipmentShape.unit(kind, kw, 1, EquipmentShape.Heading.NORTH, support);
                    var b = new StringBuilder(kind + " " + kw + " h" + support + " box " + shape.sizeX() + "x" + shape.sizeY() + "x" + shape.sizeZ());
                    for (var t : ShapeRasterizer.shafts(shape)) b.append(" shaft=").append(t.block());
                    b.append(" terminal=").append(ShapeRasterizer.terminal(shape)).append(" clashes=").append(shape.clashes().size());
                    System.out.println(b);
                }
    }
}
