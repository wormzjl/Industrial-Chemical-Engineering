package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class AreaProbe {
  static double area(Mesh.Polygon p){ double[] q=p.positions(), n=new double[3]; int m=p.size(); for(int i=0;i<m;i++){int j=(i+1)%m; n[0]+=(q[3*i+1]-q[3*j+1])*(q[3*i+2]+q[3*j+2]); n[1]+=(q[3*i+2]-q[3*j+2])*(q[3*i]+q[3*j]); n[2]+=(q[3*i]-q[3*j])*(q[3*i+1]+q[3*j+1]);} return .5*Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]); }
  public static void main(String[] a) throws Exception {
    var all = new ArrayList<>(ProcessUnitTest.everySize()); all.addAll(ShapeRasterizerTest.shapes());
    EquipmentShape shape=null; for (var s: all) if (s.drum() && s.boot() && s.diameter()==1 && s.tangentLength()==6 && s.front()==EquipmentShape.Heading.SOUTH) shape=s;
    System.out.println(shape.sizeX()+","+shape.sizeY()+","+shape.sizeZ());
    var layout=ShapeRasterizer.layout(shape); var shell=new HashSet<>(layout.shell()); var inter=new HashSet<>(layout.interior());
    double shellA=0, other=0;
    for (int x=-1;x<=shape.sizeX();x++) for(int y=-1;y<=shape.sizeY();y++) for(int z=-1;z<=shape.sizeZ();z++){
      double s=0; for (var p: ShapeRasterizer.surface(ShapeRasterizer.key(shape,x,y,z))) s+=area(p);
      var c=new ShapeRasterizer.Cell(x,y,z); if (shell.contains(c)) shellA+=s; else if (s>0) {other+=s; System.out.println("non-shell cell "+c+" interior="+inter.contains(c)+" area "+s);} }
    System.out.println("facets "+ShapeRasterizer.facetArea(shape)+" shell "+shellA+" other "+other);
    var sv=ShapeRasterizer.services(shape); double sa=0; for (var p: sv.polygons()) sa+=area(p); System.out.println("services area "+sa);
    for (var e: sv.enclosures()) System.out.println("case "+Arrays.toString(e.low())+" "+Arrays.toString(e.high()));
    // services polygons: check vertices out of box
    for (var p: sv.polygons()){ double[] q=p.positions(); for(int i=0;i<q.length;i+=3){ if(q[i]<0||q[i+1]<0||q[i+2]<0||q[i]>shape.sizeX()||q[i+1]>shape.sizeY()||q[i+2]>shape.sizeZ()) {System.out.println("poly out of box "+p.material()+" "+q[i]+","+q[i+1]+","+q[i+2]+" area "+area(p)); break;} } }
  }
}
