package com.wormzjl.icheme.science.equipment;

public class TurbinePorts {
    public static void main(String[] args) {
        for (int tenth = 3; tenth <= 30; tenth++) {
            for (int support = 0; support <= 7; support++) {
                for (var heading : EquipmentShape.Heading.values()) {
                    var shape = EquipmentShape.unit(EquipmentShape.Kind.STEAM_TURBINE, tenth / 10.0, 1, heading, support);
                    System.out.println(tenth + ":" + support + ":" + heading + ":" + shape.sizeX() + ":" + shape.sizeY() + ":" + shape.sizeZ());
                    for (var nozzle : shape.nozzles()) {
                        System.out.println(nozzle.role() + ":" + java.util.Arrays.toString(nozzle.face()) + ":" + java.util.Arrays.toString(nozzle.pipeBlock()));
                    }
                    for (var end : ProcessUnit.of(shape).shaftEnds()) System.out.println(end);
                }
            }
        }
    }
}
