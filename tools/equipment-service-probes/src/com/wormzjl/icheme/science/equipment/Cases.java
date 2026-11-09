package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class Cases {
  public static void main(String[] a) throws Exception {
    var shapes = new ArrayList<>(ProcessUnitTest.everySize()); shapes.addAll(ShapeRasterizerTest.shapes()); shapes.addAll(EquipmentLayoutGoldenTest.shapes());
    for (var s : shapes) {
      var sb = new StringBuilder(s.toString()+" box "+s.sizeX()+","+s.sizeY()+","+s.sizeZ()+" term "+ShapeRasterizer.terminal(s)+" |");
      for (var e : ShapeRasterizer.services(s).enclosures()) { for (double v: e.low()) sb.append(String.format(" %.3f",v)); sb.append(" /"); for (double v: e.high()) sb.append(String.format(" %.3f",v)); sb.append(";"); }
      System.out.println(sb);
    }
  }
}
