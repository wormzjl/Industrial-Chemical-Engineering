package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.*;

/** Closed instrument casing and independent process connectors; metres, dial normal +z. */
public final class InstrumentGeometry {
    private InstrumentGeometry(){}
    public static final double RADIUS=.17,FACE=.045,TICKS=.047,LABEL=.050,NEEDLE=.057,RIM=.068;
    public record Quad(double[] vertices,int colour) {
        public Quad{vertices=vertices.clone();}
        @Override public double[] vertices(){return vertices.clone();}
        public Quad front(){double z=(vertices[3]-vertices[0])*(vertices[7]-vertices[1])-(vertices[4]-vertices[1])*(vertices[6]-vertices[0]);return z>=0?this:new Quad(new double[]{vertices[9],vertices[10],vertices[11],vertices[6],vertices[7],vertices[8],vertices[3],vertices[4],vertices[5],vertices[0],vertices[1],vertices[2]},colour);}
    }
    /** Upright world pose shared by in-world instruments and native drawing tools. */
    public static Quad pose(Quad quad,double[] centre,double[] normal){var source=quad.vertices();var p=new double[source.length];var right=new double[]{normal[2],0,-normal[0]};for(int i=0;i<source.length;i+=3)for(int a=0;a<3;a++)p[i+a]=centre[a]+right[a]*source[i]+(a==1?source[i+1]:0)+normal[a]*source[i+2];return new Quad(p,quad.colour());}
    private static final List<Quad> HOUSING=build();
    public static List<Quad> housing(){return HOUSING;}
    private static List<Quad> build(){
        var q=new ArrayList<Quad>();double inner=.143,back=-.07;
        for(int i=0;i<48;i++){
            double a=i*2*Math.PI/48,b=(i+1)*2*Math.PI/48;
            double x=DeterministicMath.cos(a),y=DeterministicMath.sin(a),u=DeterministicMath.cos(b),v=DeterministicMath.sin(b);
            // Outer wall, front bezel, inner bezel wall, dial and rear cap form one closed envelope.
            q.add(quad(RADIUS*x,RADIUS*y,back,RADIUS*u,RADIUS*v,back,RADIUS*u,RADIUS*v,RIM,RADIUS*x,RADIUS*y,RIM,0xff8b949b));
            q.add(quad(RADIUS*x,RADIUS*y,RIM,RADIUS*u,RADIUS*v,RIM,inner*u,inner*v,RIM,inner*x,inner*y,RIM,0xffc5cbd0));
            q.add(quad(inner*x,inner*y,FACE,inner*x,inner*y,RIM,inner*u,inner*v,RIM,inner*u,inner*v,FACE,0xff778189));
            q.add(quad(0,0,FACE,inner*x,inner*y,FACE,inner*u,inner*v,FACE,0,0,FACE,0xfffaf7e8));
            q.add(quad(0,0,back,RADIUS*u,RADIUS*v,back,RADIUS*x,RADIUS*y,back,0,0,back,0xff59626a));
        }
        for(int i=0;i<=20;i++){
            double a=Math.PI*(1.25-i*1.5/20),x=DeterministicMath.cos(a),y=DeterministicMath.sin(a),r=i%5==0?.105:.119;
            double w=i%5==0?.003:.0022;
            q.add(quad(r*x-w*y,r*y+w*x,TICKS,.136*x-w*y,.136*y+w*x,TICKS,.136*x+w*y,.136*y-w*x,TICKS,r*x+w*y,r*y-w*x,TICKS,0xff101517).front());
        }
        return List.copyOf(q);
    }
    private static Quad quad(double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz,double dx,double dy,double dz,int c){return new Quad(new double[]{ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz},c);}
    public static Quad needle(double ratio){double a=Math.PI*(1.25-Math.clamp(ratio,0,1)*1.5),x=DeterministicMath.cos(a),y=DeterministicMath.sin(a);return quad(-.021*x-.006*y,-.021*y+.006*x,NEEDLE,.104*x-.002*y,.104*y+.002*x,NEEDLE,.104*x+.002*y,.104*y-.002*x,NEEDLE,-.021*x+.006*y,-.021*y-.006*x,NEEDLE,0xff101517).front();}
    public static List<Quad> connection(double[] tap,boolean bottomEntry,boolean thermometer){return connection(tap,bottomEntry,thermometer,.12);}
    public static List<Quad> connection(double[] tap,boolean bottomEntry,boolean thermometer,double probeDepth){
        if(!Double.isFinite(probeDepth)||probeDepth<=0)throw new IllegalArgumentException("Invalid probe depth");
        var q=new ArrayList<Quad>();double[] end=bottomEntry?new double[]{0,-.15,0}:new double[]{0,0,-.055};
        double[] elbow=bottomEntry?new double[]{0,tap[1],0}:tap;
        if(bottomEntry&&Math.abs(tap[2])>.001)cylinder(q,tap,elbow,.014,12,0xff8e999f);
        cylinder(q,elbow,end,.014,12,0xff8e999f);
        var direction=unit(sub(bottomEntry&&Math.abs(tap[2])>.001?elbow:end,tap));
        var bossEnd=add(tap,direction,.045);cylinder(q,add(tap,direction,-.01),bossEnd,thermometer?.036:.032,6,0xffb3bbbd);
        if(thermometer)cylinder(q,add(tap,direction,-probeDepth),bossEnd,.006,12,0xff89979d);
        return List.copyOf(q);
    }
    /** Bevelled metal transmitter with a recessed display and the shared yellow instrument tag. */
    private static final List<Quad> PROBE=buildTransmitter(false),LEVEL_TRANSMITTER=buildTransmitter(true);
    public static List<Quad> transmitter(boolean level){return level?LEVEL_TRANSMITTER:PROBE;}
    /** Static constructor/drawing silhouette; the placed renderer uses the same posed head with its face colours and ID. */
    public static List<Mesh.Polygon> previewHead(EquipmentInstruments.Probe probe){
        var out=new ArrayList<Mesh.Polygon>();
        for(var q:transmitter(probe.quantity()==EquipmentInstruments.Quantity.LEVEL)){
            var v=pose(q,probe.centre(),probe.normal()).vertices();
            var normal=Mesh.unit(Mesh.cross(Mesh.sub(java.util.Arrays.copyOfRange(v,3,6),java.util.Arrays.copyOfRange(v,0,3)),Mesh.sub(java.util.Arrays.copyOfRange(v,6,9),java.util.Arrays.copyOfRange(v,0,3))));
            var normals=new double[v.length];for(int i=0;i<v.length;i+=3)System.arraycopy(normal,0,normals,i,3);
            out.add(new Mesh.Polygon(v,new double[v.length/3*2],normals,(q.colour()&0xffffff)==0x102b36?Mesh.Material.OPENING:Mesh.Material.INSTRUMENT_STEEL));
        }
        return List.copyOf(out);
    }
    private static List<Quad> buildTransmitter(boolean level){
        var q=new ArrayList<Quad>();double x=level?.14:.115,y=.115,back=-.065,front=.07;
        double[][] outline={{-.7*x,-y},{.7*x,-y},{x,-.7*y},{x,.7*y},{.7*x,y},{-.7*x,y},{-x,.7*y},{-x,-.7*y}};
        for(int i=0;i<8;i++){
            var a=outline[i];var b=outline[(i+1)%8];
            q.add(quad(a[0],a[1],back,b[0],b[1],back,b[0],b[1],.045,a[0],a[1],.045,0xffd9dcdc));
            q.add(quad(a[0],a[1],.045,b[0],b[1],.045,b[0]*.88,b[1]*.88,front,a[0]*.88,a[1]*.88,front,0xffeceeea));
            q.add(quad(0,0,front,a[0]*.88,a[1]*.88,front,b[0]*.88,b[1]*.88,front,0,0,front,0xffafb8ba).front());
            q.add(quad(0,0,back,b[0],b[1],back,a[0],a[1],back,0,0,back,0xff9ea9ac));
        }
        q.add(quad(-x*.65,-.067,.071,x*.65,-.067,.071,x*.65,.067,.071,-x*.65,.067,.071,0xff354249).front());
        q.add(quad(-x*.55,-.052,.072,x*.55,-.052,.072,x*.55,.052,.072,-x*.55,.052,.072,0xff102b36).front());
        q.add(quad(-x*.8,-.092,.072,x*.8,-.092,.072,x*.8,-.070,.072,-x*.8,-.070,.072,0xffc3ad69).front());
        return List.copyOf(q);
    }
    private static void cylinder(List<Quad> out,double[] start,double[] end,double radius,int sides,int colour){
        var axis=unit(sub(end,start));var ref=unit(cross(axis,Math.abs(axis[1])>.9?new double[]{1,0,0}:new double[]{0,1,0}));var side=cross(axis,ref);
        for(int i=0;i<sides;i++){
            double a=2*Math.PI*i/sides,b=2*Math.PI*(i+1)/sides;var p=radial(start,ref,side,a,radius);var q=radial(start,ref,side,b,radius);var r=radial(end,ref,side,b,radius);var s=radial(end,ref,side,a,radius);
            out.add(points(p,q,r,s,colour));out.add(points(start,q,p,start,colour));out.add(points(end,s,r,end,colour));
        }
    }
    private static Quad points(double[] a,double[] b,double[] c,double[] d,int colour){return quad(a[0],a[1],a[2],b[0],b[1],b[2],c[0],c[1],c[2],d[0],d[1],d[2],colour);}
    private static double[] radial(double[] p,double[] ref,double[] side,double angle,double r){return add(add(p,ref,r*DeterministicMath.cos(angle)),side,r*DeterministicMath.sin(angle));}
    private static double[] sub(double[] a,double[] b){return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    private static double[] add(double[] p,double[] axis,double amount){return new double[]{p[0]+axis[0]*amount,p[1]+axis[1]*amount,p[2]+axis[2]*amount};}
    private static double[] unit(double[] a){double length=Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);if(length<1e-12)throw new IllegalArgumentException("Empty instrument connector");return new double[]{a[0]/length,a[1]/length,a[2]/length};}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}

    // ---- fixed front service taps, using each native vessel design's own axis and surface ----
    public record Socket(String id,double x,double y,double z,boolean pressure,boolean top,int face) {
        public Socket(String id,double x,double y,double z,boolean pressure,boolean top){this(id,x,y,z,pressure,top,-1);}
    }
    /** The shared physical catalog alone decides which equipment accepts manual instruments. */
    public static List<Socket> sockets(EquipmentShape shape,ShapeRasterizer.Cell node){return sockets(shape);}
    public static List<Socket> sockets(EquipmentShape shape){return EquipmentInstruments.of(shape).probes().stream().filter(p->p.manual()&&(p.quantity()==EquipmentInstruments.Quantity.PRESSURE||p.quantity()==EquipmentInstruments.Quantity.TEMPERATURE)).map(InstrumentGeometry::socket).toList();}
    public static List<Socket> levelSockets(EquipmentShape shape){return EquipmentInstruments.of(shape).probes().stream().filter(p->p.manual()&&p.quantity()==EquipmentInstruments.Quantity.LEVEL).map(InstrumentGeometry::socket).toList();}
    private static Socket socket(EquipmentInstruments.Probe p){var c=p.centre();return new Socket(p.id(),c[0],c[1],c[2],p.quantity()==EquipmentInstruments.Quantity.PRESSURE,p.top(),p.face());}
}
