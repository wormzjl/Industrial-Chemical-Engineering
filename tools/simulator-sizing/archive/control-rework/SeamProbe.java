package com.wormzjl.icheme.science.equipment;
import java.util.*;
public final class SeamProbe {
 static String vertex(double[] p,int v){return Math.round(p[v]*1e8)+","+Math.round(p[v+1]*1e8)+","+Math.round(p[v+2]*1e8);}
 static int open(List<Mesh.Polygon> polygons){var edges=new HashMap<String,Integer>();var ends=new HashMap<String,double[]>();
  for(var p:polygons)if(p.material()==Mesh.Material.PIPE)for(int i=0;i<p.size();i++){
   int a=i*3,b=((i+1)%p.size())*3;String x=vertex(p.positions(),a),y=vertex(p.positions(),b),key=x.compareTo(y)<0?x+"|"+y:y+"|"+x;
   edges.merge(key,1,Integer::sum);ends.put(key,new double[]{p.positions()[a],p.positions()[a+1],p.positions()[a+2],p.positions()[b],p.positions()[b+1],p.positions()[b+2]});}
  int open=0;for(var e:edges.entrySet())if(e.getValue()==1){var p=ends.get(e.getKey());boolean face=false;for(int k=0;k<3;k++)if(Math.abs(p[k]-p[k+3])<1e-8&&(Math.abs(p[k])<1e-8||Math.abs(p[k]-1)<1e-8))face=true;if(!face)open++;}return open;
 }
 public static void main(String[] args){int broken=0;for(int a=0;a<6;a++)for(int b=a+1;b<6;b++)for(int u:new int[]{-12,-5,0,5,12})for(int v:new int[]{-12,0,12}){
 var key=new ConduitGeometry.Key(0,0).connect(a,u,v).connect(b,0,0);int n=open(LineGeometry.mesh(new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,5,key.joints(),PipeSizes.code(.125),0,0,false,key.offsets())).facets().stream().map(Mesh::whole).toList());if(n>0){broken++;if(broken<10)System.out.println(a+" "+b+" "+u+" "+v+" internal_open_edges="+n);}}
 System.out.println("broken="+broken);}
}

