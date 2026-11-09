package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class Probe2 {
  public static void main(String[] a) throws Exception {
    var shapes = new ArrayList<>(ProcessUnitTest.everySize()); shapes.addAll(ShapeRasterizerTest.shapes());
    for (var shape : shapes) for (int k = 0; k < shape.nozzles().size(); k++) {
      var nozzle = shape.nozzles().get(k);
      if (ProcessUnitTest.NozzleRoom.clear(shape, k, PipeSizes.outside(NozzleBore.largest(shape, k)))) continue;
      System.out.println("NOTCLEAR "+shape.kind()+" "+shape.diameter()+" "+nozzle.role()); if (shape.kind()!=EquipmentShape.Kind.DRUM) continue;
      System.out.println(shape.diameter()+" L"+shape.tangentLength()+" "+shape.orientation()+" s"+shape.support()+" boot"+shape.boot()+" conn"+shape.connections()+" "+nozzle.role()+" base="+Arrays.toString(nozzle.base())+" axis"+nozzle.axis()+" sign"+nozzle.sign()+" len"+nozzle.length()+" od="+PipeSizes.outside(NozzleBore.largest(shape,k)));
      var plan = ShapeRasterizer.services(shape);
      var t = shape.transform();
      for (var e : plan.enclosures()) System.out.println("   case(box frame) "+Arrays.toString(e.low())+" - "+Arrays.toString(e.high())+" face "+e.face());
      // sample the neck to find a hit in a case
      double od = PipeSizes.outside(NozzleBore.largest(shape,k));
      for (double s=0; s<=nozzle.length(); s+=.05) for (int j=0;j<24;j++){
        double ang=2*Math.PI*j/24; var p=nozzle.base().clone(); int ax=nozzle.axis(),u=(ax+1)%3,v=(ax+2)%3; p[ax]+=nozzle.sign()*s; p[u]+=(od/2+.01)*Math.cos(ang); p[v]+=(od/2+.01)*Math.sin(ang);
        var b=t.point(p); if (plan.inside(b)) { System.out.println("   neck hits case at s="+String.format("%.2f",s)+" "+Arrays.toString(b)); s=99; break; }
      }
    }
  }
}
