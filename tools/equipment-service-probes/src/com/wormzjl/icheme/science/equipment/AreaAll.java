package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class AreaAll {
  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    var f = ShapeRasterizerTest.class.getDeclaredField("SHAPES"); f.setAccessible(true);
    var shapes = new ArrayList<EquipmentShape>((List<EquipmentShape>) f.get(null));
    shapes.addAll(ProcessUnitTest.everySize());
    int bad=0, n=0;
    for (var shape : shapes) {
      double area = 0;
      for (var cell : ShapeRasterizer.layout(shape).shell()) for (var p : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) area += AreaProbe.area(p);
      double facets = ShapeRasterizer.facetArea(shape); n++;
      boolean inSet = ((List<EquipmentShape>) f.get(null)).contains(shape);
      if (Math.abs(facets-area) > 1e-9*facets + 20*Mesh.MIN_AREA) { bad++; System.out.println((inSet?"SHAPES ":"everySize ")+shape.kind()+" "+shape.diameter()+" L"+shape.tangentLength()+" "+shape.front()+String.format(" missing %.6f", facets-area)); }
    }
    System.out.println("checked "+n+" bad "+bad);
  }
}
