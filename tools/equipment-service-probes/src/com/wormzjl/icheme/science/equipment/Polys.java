package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class Polys {
  public static void main(String[] a) throws Exception {
    var shapes = new ArrayList<>(ProcessUnitTest.everySize()); shapes.addAll(ShapeRasterizerTest.shapes()); shapes.addAll(EquipmentLayoutGoldenTest.shapes());
    for (var s : shapes) {
      double area=0; double[] lo={1e9,1e9,1e9}, hi={-1e9,-1e9,-1e9}; int out=0;
      for (var p : ShapeRasterizer.services(s).polygons()) { area+=AreaProbe.area(p); var q=p.positions(); boolean o=false; for(int i=0;i<q.length;i++){lo[i%3]=Math.min(lo[i%3],q[i]);hi[i%3]=Math.max(hi[i%3],q[i]);} for(int i=0;i<q.length;i+=3) if(q[i]<-1e-6||q[i+1]<-1e-6||q[i+2]<-1e-6||q[i]>s.sizeX()+1e-6||q[i+1]>s.sizeY()+1e-6||q[i+2]>s.sizeZ()+1e-6) o=true; if(o) out++; }
      System.out.println(s.kind()+" "+s.diameter()+" L"+s.tangentLength()+" "+s.orientation()+" "+s.front()+" s"+s.support()+" boot"+s.boot()+" c"+s.connections().size()+String.format(" area %.6f out %d bounds %.3f %.3f %.3f %.3f %.3f %.3f",area,out,lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]));
    }
  }
}
