package com.wormzjl.icheme.science.equipment;

import java.util.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Fixed-diameter metal signal conduit, using the pipe mesher and its face-point coordinates. */
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
    public static List<Polygon> polygons(Key key){return LineGeometry.conduit(key.joints(),key.offsets(),OUTSIDE);}
    /** Rack conduit uses the rack pipe sweep, rather than chords between its corners. */
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane){return RackGeometry.lane(rack,lane);}
    public static List<Polygon> path(List<double[]> points){return LineGeometry.conduit(points,OUTSIDE);}
    /** Orthogonal equipment lead, with face-normal connector and cable-entry legs. */
    public static List<Polygon> lead(double[] outward,double[] end,double[] entering){
        var p=scale(outward,.18);var q=sub(end,scale(entering,.18));var points=new ArrayList<double[]>();
        points.add(new double[3]);points.add(p);
        int axis=Math.abs(outward[0])>.5?0:Math.abs(outward[1])>.5?1:2;
        for(int step=1;step<=3;step++){int k=(axis+step)%3;if(Math.abs(p[k]-q[k])>1e-8){p=p.clone();p[k]=q[k];points.add(p);}}
        points.add(end);return path(points);
    }
    /** Small boxes around the actual pipe polygons, shared by selection and removal previews. */
    public static List<double[]> boxes(Key key){
        var result=new ArrayList<double[]>();
        for(var p:polygons(key)){
            var bounds=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            var v=p.positions();for(int i=0;i<v.length;i++){int k=i%3;bounds[k]=Math.min(bounds[k],v[i]);bounds[k+3]=Math.max(bounds[k+3],v[i]);}
            for(int k=0;k<3;k++){bounds[k]-=.012;bounds[k+3]+=.012;}result.add(bounds);
        }
        return List.copyOf(result);
    }
}
