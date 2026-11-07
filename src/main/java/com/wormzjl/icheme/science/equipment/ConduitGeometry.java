package com.wormzjl.icheme.science.equipment;

import java.util.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Fixed-diameter metallic data and power enclosures using the pipe mesher and its face-point coordinates. */
public final class ConduitGeometry {
    public static final double OUTSIDE=1/8.0;
    public record Key(int joints,long offsets,CableMedium medium) {
        public Key(int joints,long offsets){this(joints,offsets,CableMedium.DATA);}
        public Key {
            Objects.requireNonNull(medium);
            if((joints&~63)!=0||(offsets&~((1L<<60)-1))!=0)throw new IllegalArgumentException("Invalid data cable geometry");
        }
        public double[] point(int face){
            return LineGeometry.point(new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,
                    2,joints,4,0,0,false,offsets),face);
        }
        public Key connect(int face,int u,int v){
            if(face<0||face>5)throw new IllegalArgumentException("Invalid cable face");
            long at=LineGeometry.Key.offsets(face,u,v);
            return new Key(joints|1<<face,(offsets&~(1023L<<(10*face)))|at,medium);
        }
    }
    private ConduitGeometry(){}
    /** One identification band every four cells, including at negative world coordinates. */
    public static boolean markedAt(int x,int y,int z){return Math.floorMod(x+y+z,4)==0;}
    public static List<Polygon> polygons(Key key){return polygons(key,true);}
    public static List<Polygon> polygons(Key key,boolean marked){
        var body=LineGeometry.conduit(key.joints(),key.offsets(),key.medium().outside());
        var out=new ArrayList<>(finish(body));
        int drawn=key.joints();if(drawn==0)drawn=48;else if(Integer.bitCount(drawn)==1)drawn|=1<<(Integer.numberOfTrailingZeros(drawn)^1);
        if(marked)for(int face=0;face<6;face++)if((drawn&1<<face)!=0&&mark(out,body,key.point(face),LineGeometry.step(face),LineGeometry.ref(face),key.medium()))break;
        return List.copyOf(out);
    }
    /** Rack conduit uses the rack pipe sweep, rather than chords between its corners. */
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane){return lane(rack,lane,CableMedium.DATA);}
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane,CableMedium medium){return lane(rack,lane,medium,true);}
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane,CableMedium medium,boolean marked){
        var body=RackGeometry.lane(rack,lane);var out=new ArrayList<>(finish(body));
        var caps=new Mesh();
        for(int face=0;face<6;face++)if((lane.arms()&1<<face)!=0){
            var point=RackGeometry.facePoint(rack,lane,face);
            if(point!=null){
                if(marked&&mark(out,body,point,LineGeometry.step(face),LineGeometry.ref(face),medium))marked=false;
                if((lane.joined()&1<<face)!=0)caps.disc(new Frame(point,LineGeometry.step(face),LineGeometry.ref(face)),medium.outside()/2,-.002,8,1,Material.OPENING);
            }
        }
        out.addAll(LineGeometry.overhanging(caps));
        return List.copyOf(out);
    }
    /** Saved end coordinates remain authoritative when an enclosure's drawing diameter changes. */
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane,Key geometry){return lane(rack,lane,geometry,true);}
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane,Key geometry,boolean marked){
        var actual=laneKey(rack,lane,geometry);
        return displaced(rack,lane,geometry)?polygons(actual,marked):lane(rack,lane,geometry.medium(),marked);
    }
    public static Key laneKey(RackGeometry.Rack rack,RackGeometry.Lane lane,Key geometry){
        var actual=geometry;
        for(int face=0;face<6;face++)if((lane.arms()&1<<face)!=0){
            int point=RackGeometry.point(rack,lane.option(),lane.width(),lane.diameter(),face);
            if((geometry.joints()&1<<face)==0)actual=actual.connect(face,signed(point&31),signed(point>>5&31));
        }
        return actual;
    }
    private static boolean displaced(RackGeometry.Rack rack,RackGeometry.Lane lane,Key geometry){
        for(int face=0;face<6;face++)if((lane.arms()&geometry.joints()&1<<face)!=0){
            var a=geometry.point(face);var b=RackGeometry.facePoint(rack,lane,face);
            if(b!=null&&dot(sub(a,b),sub(a,b))>EPS)return true;
        }
        return false;
    }
    /** Collision and picking follow saved face points, just as the drawn lane does. */
    public static List<double[]> laneBoxes(RackGeometry.Rack rack,RackGeometry.Lane lane,Key geometry){
        return displaced(rack,lane,geometry)?boxes(laneKey(rack,lane,geometry)):RackGeometry.boxes(rack,lane);
    }
    private static int signed(int value){return value>=16?value-32:value;}
    public static List<Polygon> path(List<double[]> points){return finish(LineGeometry.conduit(points,OUTSIDE));}
    /** Orthogonal equipment lead, with face-normal connector and cable-entry legs. */
    public static List<Polygon> lead(double[] outward,double[] end,double[] entering){return lead(outward,end,entering,CableMedium.DATA);}
    public static List<Polygon> lead(double[] outward,double[] end,double[] entering,CableMedium medium){
        var points=leadPoints(outward,end,entering);
        return points.size()<2?List.of():finish(LineGeometry.conduit(points,medium.outside()));
    }
    /** The same lead spine drives drawing and placement clearance; neither may invent a different connection. */
    public static List<double[]> leadPoints(double[] outward,double[] end,double[] entering){
        double along=dot(end,outward);var lateral=sub(end,scale(outward,along));
        boolean parallel=dot(outward,entering)>.99;
        if(parallel&&along<=.08&&dot(lateral,lateral)<.025*.025)return List.of(); // The placed line already enters the gland.
        if(parallel&&along>0&&dot(lateral,lateral)<1e-12)return List.of(new double[3],end.clone());
        double tail=parallel?Math.min(.18,Math.max(0,.4*along)):.18;
        var p=scale(outward,tail);var q=sub(end,scale(entering,tail));var points=new ArrayList<double[]>();
        points.add(new double[3]);points.add(p);
        int axis=Math.abs(outward[0])>.5?0:Math.abs(outward[1])>.5?1:2;
        for(int step=1;step<=3;step++){int k=(axis+step)%3;if(Math.abs(p[k]-q[k])>1e-8){p=p.clone();p[k]=q[k];points.add(p);}}
        points.add(end.clone());return List.copyOf(points);
    }

    /** Keep the metal finish continuous around bends; identification belongs on deliberate straight marker bands. */
    private static List<Polygon> finish(List<Polygon> polygons){
        return polygons.stream().map(p->{
            if(p.material()!=Material.PIPE)return p;
            var uv=p.uvs().clone();for(int v=1;v<uv.length;v+=2)uv[v]=.35+.25*uv[v];
            return new Polygon(p.positions(),uv,p.normals(),p.material());
        }).toList();
    }

    private static boolean mark(List<Polygon> out,List<Polygon> body,double[] end,double[] axis,double[] ref,CableMedium medium){
        double radius=medium.outside()/2;
        // A short transition can bend immediately behind its gland. Mark only an actual straight length of enclosure.
        boolean straight=false;
        for(var p:body)if(p.material()==Material.PIPE){
            double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;boolean cylinder=true;
            for(int v=0;v<p.size();v++){
                double[] d={p.positions()[3*v]-end[0],p.positions()[3*v+1]-end[1],p.positions()[3*v+2]-end[2]};
                double along=dot(d,axis);lo=Math.min(lo,along);hi=Math.max(hi,along);
                if(Math.abs(dot(d,d)-along*along-radius*radius)>1e-8||Math.abs(p.normals()[3*v]*axis[0]+p.normals()[3*v+1]*axis[1]+p.normals()[3*v+2]*axis[2])>1e-6)cylinder=false;
            }
            if(cylinder&&lo<=-.22+EPS&&hi>=-.04-EPS){straight=true;break;}
        }
        if(!straight)return false;
        var m=new Mesh();var frame=new Frame(end,axis,ref);double r=radius+.0007;
        m.prism(frame,r,-.22,-.04,8,Material.GUARD);
        out.addAll(LineGeometry.overhanging(m));
        // The symbols are vector decals on alternate octagonal flats, readable from every side of a straight run.
        var ink=new Mesh();double width=radius*.48,length=.13;
        double[][] bolt={{-.2,-.5},{.48,-.5},{.05,-.08},{.4,-.08},{-.38,.5},{-.05,.08},{-.4,.08}};
        double[][] link={{-.42,-.38},{.42,-.38},{.42,-.2},{-.42,-.2}};
        for(int k=0;k<8;k+=2){
            double theta=2*Math.PI*(k+.5)/8;
            double[] normal=unit(sub(frame.at(theta,1,0),end));
            var tangent=cross(axis,normal);var centre=plus(frame.along(-.13),scale(normal,r*.9238795325112867+.0002));
            if(medium==CableMedium.POWER)glyph(ink,centre,tangent,axis,normal,bolt,width,length);
            else {
                glyph(ink,centre,tangent,axis,normal,link,width,length);
                glyph(ink,plus(centre,scale(axis,.058)),tangent,axis,normal,link,width,length);
                glyph(ink,centre,tangent,axis,normal,new double[][]{{-.1,-.3},{.1,-.3},{.1,.3},{-.1,.3}},width,length);
            }
        }
        out.addAll(LineGeometry.overhanging(ink));
        return true;
    }

    private static void glyph(Mesh mesh,double[] centre,double[] across,double[] along,double[] normal,double[][] points,double width,double length){
        // Triangulate the lightning bolt's concave outline explicitly; all Mesh facets remain convex.
        if(points.length==7){
            for(int[] tri:new int[][]{{0,1,2},{0,2,6},{2,3,4},{2,4,5},{2,5,6}}){
                var polygon=new ArrayList<double[]>();for(int i:tri)polygon.add(plus(centre,plus(scale(across,points[i][0]*width),scale(along,points[i][1]*length))));
                mesh.flat(polygon,normal,Material.OPENING);
            }
        }else{
            var polygon=new ArrayList<double[]>();for(var p:points)polygon.add(plus(centre,plus(scale(across,p[0]*width),scale(along,p[1]*length))));
            mesh.flat(polygon,normal,Material.OPENING);
        }
    }
    /** The shared pipe selection envelope, also used by removal previews. */
    public static List<double[]> boxes(Key key){
        return LineSelection.boxes(key.joints(),5,PipeSizes.code(key.medium().outside()),key.offsets());
    }
}
