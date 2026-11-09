package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class Shafts {
  public static void main(String[] a) throws Exception {
    var list = new ArrayList<>(ProcessUnitTest.everySize());
    list.add(0, AssemblyProbe.pump());
    for (var shape : list) {
      var shafts = ShapeRasterizer.shafts(shape); var terminal = ShapeRasterizer.terminal(shape);
      if (shafts.isEmpty() && terminal==null) continue;
      var layout = ShapeRasterizer.layout(shape); var occ = new HashSet<ShapeRasterizer.Cell>(layout.shell()); occ.addAll(layout.interior());
      String kind = shape.kind()+" "+shape.diameter()+" L"+shape.tangentLength()+" s"+shape.support()+" box "+shape.sizeX()+","+shape.sizeY()+","+shape.sizeZ();
      if (terminal != null && !occ.contains(terminal)) System.out.println(kind+": terminal "+terminal+" not occupied");
      for (var s : shafts) {
        var b = s.block();
        if (s.dy()!=0) System.out.println(kind+": shaft not level");
        if (!occ.contains(s.cell())) System.out.println(kind+": coupling cell free "+s.cell());
        if (occ.contains(b)) System.out.println(kind+": shaft block occupied "+b);
        if (b.y()<0) System.out.println(kind+": under ground");
        for (var port : ShapeRasterizer.ports(shape)) { int dx=Math.abs(b.x()-port.cell().x()-port.dx()), dy=Math.abs(b.y()-port.cell().y()-port.dy()), dz=Math.abs(b.z()-port.cell().z()-port.dz()); if (Math.max(dx,Math.max(dy,dz))<2) System.out.println(kind+": shaft touches pipe of "+shape.nozzles().get(port.nozzle()).role()); }
      }
    }
    var p = AssemblyProbe.pump();
    System.out.println("PUMP box "+p.sizeX()+","+p.sizeY()+","+p.sizeZ()+" shafts "+ShapeRasterizer.shafts(p));
    for (var e : ShapeRasterizer.services(p).enclosures()) System.out.println("  case "+Arrays.toString(e.low())+" - "+Arrays.toString(e.high()));
  }
}
class AssemblyProbe { static EquipmentShape pump(){ var d = UnitDesign.of(EquipmentShape.Kind.PUMP); double size = d.minSize(); int length = d.defaultLength(size); return EquipmentShape.designed(EquipmentShape.Kind.PUMP, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);} }
