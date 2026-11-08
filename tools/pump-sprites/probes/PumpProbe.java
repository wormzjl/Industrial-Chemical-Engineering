package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;

/** Scratch check (not a gate): each pump size's box and pipe blocks, at 2 and 4 poles and 5, 100 and 300 m. */
public class PumpProbe {
    public static void main(String[] args) {
        for (double d : ProcessUnit.sizes(Kind.PUMP))
            for (int poles : new int[]{2, 4})
                for (double head : new double[]{5, 100, 300}) {
                    if (args.length == 0 && d < .35) continue;
                    var shape = EquipmentShape.unit(Kind.PUMP, d, 1, Heading.NORTH, 0).withDuty(MachineDuty.pump(ProcessUnit.pumpFlow(d), head, poles));
                    var b = new StringBuilder(String.format("d=%.2f %dp %3.0fm box=%dx%dx%d |", d, poles, head, shape.sizeX(), shape.sizeY(), shape.sizeZ()));
                    for (var port : ShapeRasterizer.ports(shape))
                        b.append(' ').append(shape.nozzles().get(port.nozzle()).role()).append('=').append(port.cell().x() + port.dx()).append(',').append(port.cell().y() + port.dy()).append(',').append(port.cell().z() + port.dz());
                    for (var shaft : ShapeRasterizer.shafts(shape)) b.append(" SHAFT=").append(shaft.block());
                    b.append(" clashes=").append(shape.clashes().size());
                    System.out.println(b);
                }
    }
}
