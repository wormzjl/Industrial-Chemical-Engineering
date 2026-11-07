package com.wormzjl.icheme.science.equipment;
import java.util.*;
public final class NormalProbe {
 public static void main(String[] args){var key=new ConduitGeometry.Key(0,0).connect(0,0,0).connect(3,-12,12);var normals=new HashMap<String,List<double[]>>();
  for(var p:ConduitGeometry.polygons(key,false))if(p.material()==Mesh.Material.PIPE)for(int v=0;v<p.size();v++)normals.computeIfAbsent(SeamProbe.vertex(p.positions(),3*v),k->new ArrayList<>()).add(Arrays.copyOfRange(p.normals(),3*v,3*v+3));
  double worst=0;String at="";for(var e:normals.entrySet())for(var a:e.getValue())for(var b:e.getValue()){double dot=Mesh.dot(a,b);if(1-dot>worst){worst=1-dot;at=e.getKey()+" "+Arrays.toString(a)+" vs "+Arrays.toString(b);}}
  System.out.println("max_normal_difference="+worst+" at "+at);
 }
}
