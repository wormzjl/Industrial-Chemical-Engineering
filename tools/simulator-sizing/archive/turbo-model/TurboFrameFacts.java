package com.wormzjl.icheme.science.equipment;
public class TurboFrameFacts {
    public static void main(String[] args) {
        for (double d : new double[]{.3, .9, 1.5}) for (var heading : EquipmentShape.Heading.values()) {
            var shape = EquipmentShape.unit(EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR, d, 2, heading, 0);
            var ports = ShapeRasterizer.ports(shape);
            System.out.println(d + " " + heading + " box=" + shape.sizeX() + "," + shape.sizeY() + "," + shape.sizeZ()
                    + " gas=" + ports.subList(0, 2) + " shafts=" + ShapeRasterizer.shafts(shape) + " fluid=" + ShapeRasterizer.fluidCells(shape));
        }
    }
}
