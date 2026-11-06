package com.wormzjl.icheme.science.equipment;

import java.util.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** A signal cable uses the pipe layer's face-point coordinates, without fluid passages or flanges. */
public final class DataCableGeometry {
    public static final double OUTSIDE=1/16.0;
    public record Key(int joints,long offsets) {
        public Key {
            if((joints&~63)!=0||(offsets&~((1L<<60)-1))!=0)throw new IllegalArgumentException("Invalid data cable geometry");
        }
        public double[] point(int face){
            return LineGeometry.point(new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,
                    2,joints,4,0,0,false,offsets),face);
        }
        public Key connect(int face,int u,int v){
            if(face<0||face>5)throw new IllegalArgumentException("Invalid cable face");
            long at=LineGeometry.Key.offsets(face,u,v);
            return new Key(joints|1<<face,(offsets&~(1023L<<(10*face)))|at);
        }
    }
    private DataCableGeometry(){}
    public record Segment(double ax,double ay,double az,double bx,double by,double bz){}
    public static List<Segment> segments(Key key){
        var points=new ArrayList<double[]>();
        for(int face=0;face<6;face++)if((key.joints()&(1<<face))!=0)points.add(key.point(face));
        if(points.isEmpty())points.addAll(List.of(new double[]{.15,.5,.5},new double[]{.85,.5,.5}));
        else if(points.size()==1)points.add(new double[]{.5,.5,.5});
        var centre=new double[3];
        for(var p:points)for(int k=0;k<3;k++)centre[k]+=p[k]/points.size();
        var out=new ArrayList<Segment>();
        if(points.size()==2){var a=points.get(0);var b=points.get(1);out.add(new Segment(a[0],a[1],a[2],b[0],b[1],b[2]));}
        else for(var p:points)out.add(new Segment(centre[0],centre[1],centre[2],p[0],p[1],p[2]));
        return List.copyOf(out);
    }
    public static List<Polygon> polygons(Key key){
        var mesh=new Mesh();var paths=segments(key);
        for(var s:paths)segment(mesh,new double[]{s.ax,s.ay,s.az},new double[]{s.bx,s.by,s.bz});
        if(paths.size()>1){var c=paths.getFirst();mesh.sphere(new double[]{c.ax,c.ay,c.az},OUTSIDE*.65,8,Material.PIPE);}
        return LineGeometry.overhanging(mesh);
    }
    /** Shared by installed cable segments, equipment leads, rack lanes and route ghosts. */
    public static List<Polygon> path(List<double[]> points){
        var mesh=new Mesh();
        for(int k=1;k<points.size();k++)segment(mesh,points.get(k-1),points.get(k));
        return LineGeometry.overhanging(mesh);
    }
    private static void segment(Mesh mesh,double[] a,double[] b){
        double[] delta=sub(b,a);double length=Math.sqrt(dot(delta,delta));
        if(length<1e-8)return;
        var axis=scale(delta,1/length);
        var ref=unit(cross(axis,Math.abs(axis[1])<.8?new double[]{0,1,0}:new double[]{1,0,0}));
        mesh.cylinder(new Frame(a,axis,ref),OUTSIDE/2,0,length,8,Material.PIPE);
    }
}
