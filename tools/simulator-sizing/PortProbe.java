package com.wormzjl.icheme.science.equipment;
import java.util.*;
public class PortProbe {
 public static void main(String[] args){
  for(var kind:List.of(EquipmentShape.Kind.PUMP,EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR))for(double d:kind==EquipmentShape.Kind.PUMP?new double[]{.05,.1}:new double[]{.3,1.2}){
   var shape=EquipmentShape.unit(kind,d,kind==EquipmentShape.Kind.PUMP?1:2,EquipmentShape.Heading.NORTH,0);
   System.out.println("SHAPE "+kind+" "+d);
   for(int k=0;k<shape.nozzles().size();k++){
    var n=shape.nozzles().get(k);var root=ShapeRasterizer.nozzleRoot(shape,k);
    var m=new Mesh();PortMarkings.band(m,shape,k,0,PortMarkings.flow(kind,n.role()));
    System.out.println(n.role()+" base="+Arrays.toString(n.base())+" length="+n.length()+" od="+n.defaultOd()+" root="+root+" mark="+m.facets().size());
    for(double od:new double[]{n.defaultOd(),PipeSizes.MAX_OUTSIDE}){
     double to=n.length()-PipeSizes.flangeThickness(od)-PipeSizes.hub(od)-.01;
     System.out.println(" od="+od+" rootEnd="+root.end(n.length(),od)+" end="+(n.length()-PipeSizes.flangeThickness(od)-.055)+" clearanceTo="+to);
     for(double s:new double[]{0,.02,.1,Math.max(0,to)}){
      int hidden=0;for(int j=0;j<24;j++){
       var p=n.base().clone();p[n.axis()]+=n.sign()*s;p[(n.axis()+1)%3]+=(od/2+.01)*Math.cos(j*Math.PI/12);p[(n.axis()+2)%3]+=(od/2+.01)*Math.sin(j*Math.PI/12);var q=shape.transform().point(p);if(ShapeRasterizer.inside(shape,q[0],q[1],q[2]))hidden++;
      }System.out.println("  ring="+s+" hidden="+hidden);
     }
    }
   }
  }
 }
}
