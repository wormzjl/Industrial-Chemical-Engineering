package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class Probe3 {
  public static void main(String[] a) throws Exception {
    var shape = EquipmentShape.drum(.5, 1, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
    var all=new ArrayList<>(ProcessUnitTest.everySize()); all.addAll(ShapeRasterizerTest.shapes()); for (var s : all) if (s.drum() && s.diameter()==.5 && s.tangentLength()==1 && s.support()==0 && !s.connections().isEmpty() && s.orientation()==EquipmentShape.Orientation.VERTICAL) shape = s;
    System.out.println(shape + " size "+shape.sizeX()+","+shape.sizeY()+","+shape.sizeZ()+" bottomT "+shape.bottomTangent()+" topT "+shape.topTangent());
    for (var n : shape.nozzles()) System.out.println(n.role()+" base "+Arrays.toString(n.base())+" axis "+n.axis()+" sign "+n.sign()+" len "+n.length());
    for (var z : EquipmentServices.necks(ServiceSurface.north(shape))) System.out.println("zone "+Arrays.toString(z));
  }
}
