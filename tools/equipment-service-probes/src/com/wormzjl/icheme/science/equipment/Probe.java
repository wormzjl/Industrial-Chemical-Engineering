package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class Probe {
  public static void main(String[] a) throws Exception {
    for (var shape : ProcessUnitTest.everySize()) {
      if (shape.kind()!=EquipmentShape.Kind.GENERATOR && shape.kind()!=EquipmentShape.Kind.MOTOR) continue;
      var t = ShapeRasterizer.terminal(shape); var layout = ShapeRasterizer.layout(shape);
      var occ = new HashSet<ShapeRasterizer.Cell>(layout.shell()); occ.addAll(layout.interior());
      var plan = ShapeRasterizer.services(shape);
      String encl = plan.enclosures().stream().map(e->Arrays.toString(e.low())+"-"+Arrays.toString(e.high())).reduce("",(x,y)->x+" "+y);
      System.out.println(shape.kind()+" "+shape.duty().power()+" "+shape.front()+" s"+shape.support()+" term="+t+" occ="+(t==null?"-":occ.contains(t))+" size="+shape.sizeX()+","+shape.sizeY()+","+shape.sizeZ()+" encl="+encl);
    }
  }
}
