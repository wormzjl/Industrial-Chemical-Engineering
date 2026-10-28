package com.wormzjl.icheme.science.equipment;
import java.util.List;
public class Stripped { public static void main(String[] a) {
  var plain = new ColumnTrays(6, 3, List.of(), List.of(7), List.of());
  for (Boolean kettle : new Boolean[]{null, true, false}) {
    var t = kettle == null ? plain : plain.withComponents(new ColumnComponents(ColumnComponents.Condenser.AIR_COOLER, ColumnComponents.Pump.STANDARD, List.of(), false, kettle));
    var s = EquipmentShape.column(2, t, EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(2));
    System.out.print("kettle " + kettle + ":");
    for (var n : ColumnDesign.sides(s)) System.out.print(" " + n.role() + "/" + n.side() + "@" + (n.nozzle() == null ? "-" : n.nozzle().base()[1]));
    System.out.println();
  } } }
