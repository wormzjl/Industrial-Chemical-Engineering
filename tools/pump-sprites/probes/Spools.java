package com.wormzjl.icheme.science.equipment;
/** Each pump size's nozzle runs at the default design point (casting or flange pair to face), m, and the run with the pipe block one block inward. */
public class Spools { public static void main(String[] a) {
  for (double d : ProcessUnit.sizes(EquipmentShape.Kind.PUMP)) {
    var shape = EquipmentShape.unit(EquipmentShape.Kind.PUMP, d, 1, EquipmentShape.Heading.NORTH, 0);
    var b = new StringBuilder(String.format("d=%.2f", d));
    for (var n : shape.nozzles()) {
      double od = n.defaultOd(), least = PipeSizes.hub(od) + PipeSizes.flangeThickness(od) + .03, in = n.length() - 1;
      b.append(String.format("  %s run %.2f, one block in %s", n.role(), n.length(), in >= least ? String.format("%.2f", in) : "no room (least " + String.format("%.2f", least) + ")"));
    }
    System.out.println(b);
  } } }
