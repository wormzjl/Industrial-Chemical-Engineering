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
    private static List<Quad> buildTransmitter(boolean level){
        var q=new ArrayList<Quad>();double x=level?.14:.115,y=.115,back=-.065,front=.07;
        double[][] outline={{-.7*x,-y},{.7*x,-y},{x,-.7*y},{x,.7*y},{.7*x,y},{-.7*x,y},{-x,.7*y},{-x,-.7*y}};
        for(int i=0;i<8;i++){
            var a=outline[i];var b=outline[(i+1)%8];
            q.add(quad(a[0],a[1],back,b[0],b[1],back,b[0],b[1],.045,a[0],a[1],.045,0xff8e969a));
            q.add(quad(a[0],a[1],.045,b[0],b[1],.045,b[0]*.88,b[1]*.88,front,a[0]*.88,a[1]*.88,front,0xffb0b7b9));
            q.add(quad(0,0,front,a[0]*.88,a[1]*.88,front,b[0]*.88,b[1]*.88,front,0,0,front,0xff737e83).front());
            q.add(quad(0,0,back,b[0],b[1],back,a[0],a[1],back,0,0,back,0xff727d82));
        }
        q.add(quad(-x*.65,-.067,.071,x*.65,-.067,.071,x*.65,.067,.071,-x*.65,.067,.071,0xff1f343b).front());
        q.add(quad(-x*.55,-.052,.072,x*.55,-.052,.072,x*.55,.052,.072,-x*.55,.052,.072,0xffa7bcb4).front());
        q.add(quad(-x*.8,-.092,.072,x*.8,-.092,.072,x*.8,-.070,.072,-x*.8,-.070,.072,0xffb59d55).front());
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
    /** Process equipment without vessel service taps mounts its transmitters beside its own fluid connection. */
    public static List<Socket> sockets(EquipmentShape shape,ShapeRasterizer.Cell node){
        var standard=sockets(shape);
        if(!standard.isEmpty()||node==null)return standard;
        var ports=ShapeRasterizer.ports(shape);
        var port=ports.stream().min(Comparator.comparingDouble(p->
                com.wormzjl.icheme.science.math.DeterministicMath.pow(p.cell().x()-node.x(),2)+com.wormzjl.icheme.science.math.DeterministicMath.pow(p.cell().y()-node.y(),2)+com.wormzjl.icheme.science.math.DeterministicMath.pow(p.cell().z()-node.z(),2))).orElse(null);
        if(port==null)return List.of();
        var definitions=HeatSides.of(shape);int side=ShapeRasterizer.fluidCells(shape).indexOf(node);
        var outlet=side>=0&&side<definitions.size()?ports.stream().filter(p->shape.nozzles().get(p.nozzle()).role()==definitions.get(side).outlet()).findFirst().orElse(port):port;
        var result=new ArrayList<Socket>();
        for(int k=0;k<2;k++){
            var tap=k==0?port:outlet;
            double nx=tap.dz()!=0?1:0,nz=tap.dz()!=0?0:tap.dx()!=0?1:-1;
            int face=nx>0?5:nz>0?3:2;
            double x=tap.cell().x()+.5-.35*tap.dx(),y=tap.cell().y()+.5-.35*tap.dy()+(tap==port?.4*k:0),
                    z=tap.cell().z()+.5-.35*tap.dz();
            // Leave the actual casting, then stand the housing clear of it; do not infer a rectangular body surface.
            double out=0;
            while(out<2&&ShapeRasterizer.inside(shape,x+nx*out,y,z+nz*out))out+=1/32.0;
            if(ShapeRasterizer.inside(shape,x+nx*out,y,z+nz*out))continue;
            x+=nx*(out+.16);z+=nz*(out+.16);
            result.add(new Socket(k==0?"pressure_bottom":"temperature_bottom",x,y,z,k==0,false,face));
        }
        return List.copyOf(result);
    }
    public static List<Socket> sockets(EquipmentShape s){boolean drum=s.kind()==EquipmentShape.Kind.DRUM&&s.orientation()==EquipmentShape.Orientation.VERTICAL,tank=s.kind()==EquipmentShape.Kind.TANK,sphere=s.kind()==EquipmentShape.Kind.SPHERE;if(s.kind()==EquipmentShape.Kind.DRUM&&s.orientation().horizontal()){
        int cells=(int)Math.ceil(s.diameter());double a=(cells%2==0?cells+1:cells)/2.0,r=s.radius(),station=s.headDepth()+s.tangentLength()/2;
        var result=new ArrayList<Socket>();
        for(int i=0;i<3;i++){double h=i==0?a+r*.65:i==1?a-r*.65:a,offset=h-a,z=a-Math.sqrt(Math.max(.0001,r*r-offset*offset))-.16;
            var p=s.transform().point(new double[]{h,station+(i==2?.4:0),z});result.add(new Socket(i==0?"pressure_top":i==1?"pressure_bottom":"temperature_bottom",p[0],p[1],p[2],i<2,i==0));}
        return List.copyOf(result);
    }if(!drum&&!tank&&!sphere)return List.of();double a=s.axis(),r=s.radius(),lower=s.bottomTangent()+Math.min(.5,s.tangentLength()*.25),upper=s.topTangent()-Math.min(.5,s.tangentLength()*.25);
        if(tank){var p=TankDesign.Plan.of(s.diameter(),(int)s.tangentLength());a=p.a();lower=.8;}
        if(sphere){var p=SphereDesign.Plan.of(s.diameter(),s.support());a=p.a();upper=p.yc()+p.r()*.65;}
        double offset=Math.min(.3,r*.75);var result=new ArrayList<Socket>();
        if(drum||sphere)socket(result,s,a,r,"pressure_top",upper,-offset,true,true);
        if(drum||tank)socket(result,s,a,r,"pressure_bottom",lower,-offset,true,false);
        socket(result,s,a,r,sphere?"temperature_top":"temperature_bottom",sphere?upper:lower,offset,false,sphere);
        return List.copyOf(result);
    }
    /** Body and boot transmitter pads; tanks use a grade-accessible front pad beside their service taps. */
    public static List<Socket> levelSockets(EquipmentShape s){
        if(!s.reservoir())return List.of();
        if(s.kind()==EquipmentShape.Kind.TANK){
            // Opposite the optional sight column (+x), clear of both the central nozzles and the P/T taps.
            var plan=TankDesign.Plan.of(s.diameter(),(int)s.tangentLength());double offset=-.85;
            var p=s.transform().point(new double[]{plan.a()+offset,1.35,plan.a()-Math.sqrt(s.radius()*s.radius()-offset*offset)-.16});
            var n=s.transform().direction(new double[]{0,0,-1});int face=n[0]<0?4:n[0]>0?5:n[2]<0?2:3;
            return List.of(new Socket("level_body",p[0],p[1],p[2],false,false,face));
        }
        if(s.kind().coolingTower()){
            var tower=s.coolingTower();boolean natural=tower.draft()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Draft.NATURAL;
            double x=natural?tower.width()/2+.5:tower.width()*tower.columns()/2;
            double z=natural?tower.width()+.84:tower.width()*tower.rows()+.34;
            var p=s.transform().point(new double[]{x,1,z});
            return List.of(new Socket("level_body",p[0],p[1],p[2],false,false));
        }
        double a=s.axis(),r=s.radius(),x=a,y=s.bottomTangent()+s.tangentLength()/2,z=a+r+.14;
        if(s.kind()==EquipmentShape.Kind.SPHERE){var plan=SphereDesign.Plan.of(s.diameter(),s.support());a=plan.a();x=a;y=plan.yc();z=a+r+.14;}
        else if(s.saddles()){int cells=(int)Math.ceil(s.diameter());a=(cells%2==0?cells+1:cells)/2.0;x=a;y=s.headDepth()+s.tangentLength()/2;z=a+r+.14;}
        var result=new ArrayList<Socket>();var p=s.transform().point(new double[]{x,y,z});result.add(new Socket("level_body",p[0],p[1],p[2],false,false));
        var b=s.bootPlace();if(b!=null){p=s.transform().point(new double[]{a-r-b.length()/2,b.station(),a+b.diameter()/2+.14});result.add(new Socket("level_boot",p[0],p[1],p[2],false,false));}
        return List.copyOf(result);
    }
    private static void socket(List<Socket> out,EquipmentShape s,double a,double r,String id,double y,double offset,boolean pressure,boolean top){double radialSquared=r*r;if(s.kind()==EquipmentShape.Kind.SPHERE){double dy=y-s.support()-r;radialSquared-=dy*dy;}double z=a-Math.sqrt(Math.max(.0001,radialSquared-offset*offset))-.16;double[] p=s.transform().point(new double[]{a+offset,y,z});out.add(new Socket(id,p[0],p[1],p[2],pressure,top));}
}
