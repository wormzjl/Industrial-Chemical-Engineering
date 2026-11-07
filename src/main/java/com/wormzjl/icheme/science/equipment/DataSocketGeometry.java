package com.wormzjl.icheme.science.equipment;

import java.util.*;

/** Junction-box mounts on real casing faces. Pipe necks, flanges, guards and support steel are excluded. */
public final class DataSocketGeometry {
    private DataSocketGeometry(){}
    public record Socket(double x,double y,double z,int face,boolean junctionBox){
        public Socket(double x,double y,double z,int face){this(x,y,z,face,true);}
    }
    private static final Map<EquipmentShape,Optional<Socket>> CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<EquipmentShape,Optional<Socket>> POWER=new java.util.concurrent.ConcurrentHashMap<>();
    /** Nearest block-face centre in the available mounting span; retain the offset only when none fits. */
    static double centered(double preferred,double min,double max){
        double first=Math.ceil(min-.5-1e-9)+.5,last=Math.floor(max-.5+1e-9)+.5;
        return first<=last?Math.clamp(Math.floor(preferred)+.5,first,last):preferred;
    }
    public static Socket equipment(EquipmentShape shape){
        if(CACHE.size()>128)CACHE.clear();
        return CACHE.computeIfAbsent(shape,s->{
            if(s.kind()==EquipmentShape.Kind.DRUM)return Optional.of(drum(s,-1));
            if(!s.designed()||s.reservoir())return Optional.empty();
            var unit=ProcessUnit.of(s);
            var socket=unit.dataSocket!=null?unit.dataSocket:surface(unit.mesh().facets().stream().filter(f->f.part()<0).map(Mesh::whole).toList());
            if(socket==null)return Optional.empty();
            var p=s.transform().point(new double[]{socket.x(),socket.y(),socket.z()});var n=s.transform().direction(LineGeometry.step(socket.face()));
            int face=n[0]>.5?5:n[0]<-.5?4:n[2]>.5?3:2;
            return Optional.ofNullable(clear(s,new Socket(p[0],p[1],p[2],face,socket.junctionBox())));
        }).orElse(null);
    }
    /** A separate shell-mounted terminal pad, away from the front process and instrument connections. */
    public static Socket drum(EquipmentShape s,int requiredFace){
        if(s.kind()!=EquipmentShape.Kind.DRUM)return null;
        double a=s.axis(),r=s.radius();
        // Standing: a side pad avoids front P/T taps and rear manway/level pads. Lying: use the side, not its crown.
        var directions=s.saddles()?new double[][]{{0,0,1},{0,0,-1}}:new double[][]{{1,0,0},{-1,0,0},{0,0,-1},{0,0,1}};
        double[] local=null,n=null;int face=-1;
        for(var candidate:directions){
            var world=s.transform().direction(candidate);int f=world[0]>.5?5:world[0]<-.5?4:world[2]>.5?3:2;
            if(requiredFace<0||f==requiredFace){local=candidate;n=world;face=f;break;}
        }
        if(local==null)return null;
        double low=s.saddles()?s.headDepth():s.bottomTangent(),length=s.tangentLength();
        double preferred=low+(s.saddles()?length*.25:Math.min(1.5,length*.5));
        if(!s.saddles()&&local[2]>.5)preferred=low+length*.75; // Existing rear wiring clears the manway below it.
        double y=centered(preferred,low+.15,low+length-.15);
        var p=s.transform().point(new double[]{a+local[0]*r,y,a+local[2]*r});
        // The box's back is 0.18 m behind its gland; put that back against the actual shell.
        return new Socket(p[0]+n[0]*.18,p[1]+n[1]*.18,p[2]+n[2]*.18,face);
    }
    /** The terminal box's opposite side from the +X data connector on the casing. */
    public static Socket power(EquipmentShape shape){
        if(POWER.size()>128)POWER.clear();
        return POWER.computeIfAbsent(shape,s->Optional.ofNullable(clear(s,powerSocket(s)))).orElse(null);
    }
    /** Mesh facets can sit within the coarse collision casting; both buses must leave that casting as well. */
    private static Socket clear(EquipmentShape shape,Socket socket){
        if(socket==null)return null;
        var n=LineGeometry.step(socket.face());
        for(int step=0;step<=16;step++){
            double distance=step*LineGeometry.OFFSET;
            if(!ShapeRasterizer.inside(shape,socket.x()+n[0]*(distance+.06),socket.y()+n[1]*(distance+.06),socket.z()+n[2]*(distance+.06)))
                return new Socket(socket.x()+n[0]*distance,socket.y()+n[1]*distance,socket.z()+n[2]*distance,socket.face(),socket.junctionBox());
        }
        return null;
    }
    private static Socket powerSocket(EquipmentShape shape){
        if(!shape.designed())return null;
        var unit=ProcessUnit.of(shape);
        if(unit.powerSocket!=null){
            var socket=unit.powerSocket;var p=shape.transform().point(new double[]{socket.x(),socket.y(),socket.z()});
            var n=shape.transform().direction(LineGeometry.step(socket.face()));int face=n[0]>.5?5:n[0]<-.5?4:n[2]>.5?3:2;
            return new Socket(p[0],p[1],p[2],face,socket.junctionBox());
        }
        var centre=unit.terminal();if(centre==null)return null;
        double x=Double.NEGATIVE_INFINITY;
        for(var facet:unit.mesh().facets()){
            var v=Mesh.whole(facet).positions();boolean positive=false,negative=false;
            for(int k=0;k<v.length;k+=3){int next=(k+3)%v.length;double side=(v[next+1]-v[k+1])*(centre[2]-v[k+2])-(v[next+2]-v[k+2])*(centre[1]-v[k+1]);positive|=side>1e-9;negative|=side< -1e-9;}
            if(positive&&negative)continue;
            var a=new double[]{v[3]-v[0],v[4]-v[1],v[5]-v[2]};var b=new double[]{v[6]-v[0],v[7]-v[1],v[8]-v[2]};var n=Mesh.cross(a,b);if(Math.abs(n[0])<1e-9)continue;
            double at=v[0]-(n[1]*(centre[1]-v[1])+n[2]*(centre[2]-v[2]))/n[0];if(at<=centre[0]+.001)x=Math.max(x,at);
        }
        if(!Double.isFinite(x))x=centre[0]-.15;
        var p=shape.transform().point(new double[]{x-.16,centre[1],centre[2]});var n=shape.transform().direction(new double[]{-1,0,0});int face=n[0]>.5?5:n[0]<-.5?4:n[2]>.5?3:2;
        return new Socket(p[0],p[1],p[2],face);
    }
    /** The same casing selector viewed from another horizontal side; media do not invent separate mount geometry. */
    public static Socket surface(List<Mesh.Polygon> polygons,int face){
        if(face==5)return surface(polygons);
        if(face<2||face>5)throw new IllegalArgumentException("Service connector must face horizontally");
        var n=LineGeometry.step(face);var side=Mesh.cross(n,new double[]{0,1,0});var turned=new ArrayList<Mesh.Polygon>();
        for(var polygon:polygons){
            var p=polygon.positions().clone();var normals=polygon.normals().clone();
            for(int k=0;k<p.length;k+=3){
                double x=p[k],z=p[k+2],nx=normals[k],nz=normals[k+2];
                p[k]=x*n[0]+z*n[2];p[k+2]=x*side[0]+z*side[2];
                normals[k]=nx*n[0]+nz*n[2];normals[k+2]=nx*side[0]+nz*side[2];
            }
            turned.add(new Mesh.Polygon(p,polygon.uvs(),normals,polygon.material()));
        }
        var socket=surface(turned);return socket==null?null:new Socket(socket.x()*n[0]+socket.z()*side[0],socket.y(),socket.x()*n[2]+socket.z()*side[2],face);
    }
    public static Socket surface(List<Mesh.Polygon> polygons){
        record Face(double[] v,double nx,double ny,double nz,double[] centre,double score){}
        var all=new ArrayList<Face>();var candidates=new ArrayList<Face>();
        for(var p:polygons){
            var m=p.material();var v=p.positions();double area=0;double[] normal=null;
            for(int k=1;k+1<p.size();k++){
                double[] a={v[3*k]-v[0],v[3*k+1]-v[1],v[3*k+2]-v[2]},b={v[3*k+3]-v[0],v[3*k+4]-v[1],v[3*k+5]-v[2]};
                var c=Mesh.cross(a,b);double magnitude=Math.sqrt(Mesh.dot(c,c));area+=magnitude/2;if(normal==null&&magnitude>1e-12)normal=Mesh.scale(c,1/magnitude);
            }
            if(normal==null)continue;
            double[] centre=new double[3];for(int k=0;k<v.length;k++)centre[k%3]+=v[k]/p.size();
            var face=new Face(v,normal[0],normal[1],normal[2],centre,area*normal[0]);all.add(face);
            if((m.channel()==Paint.BODY||m==Mesh.Material.ACTUATOR||m==Mesh.Material.MOTOR)&&m!=Mesh.Material.PIPE&&m!=Mesh.Material.FLANGE&&normal[0]>.65&&Math.abs(normal[1])<.35)candidates.add(face);
        }
        candidates.sort(Comparator.comparingDouble(Face::score).reversed());
        Socket fallback=null;int bestCentered=-1;
        for(var face:candidates){
            var original=face.centre();double y0=Double.POSITIVE_INFINITY,y1=Double.NEGATIVE_INFINITY,z0=y0,z1=y1;
            for(int k=0;k<face.v().length;k+=3){y0=Math.min(y0,face.v()[k+1]);y1=Math.max(y1,face.v()[k+1]);z0=Math.min(z0,face.v()[k+2]);z1=Math.max(z1,face.v()[k+2]);}
            double cy=centered(original[1],y0+.14,y1-.14),cz=centered(original[2],z0+.14,z1-.14);
            for(var yz:new double[][]{{cy,cz},{cy,original[2]},{original[1],cz},{original[1],original[2]}}){
                int centred=(Math.abs(yz[0]-Math.floor(yz[0])-.5)<1e-9?1:0)+(Math.abs(yz[1]-Math.floor(yz[1])-.5)<1e-9?1:0);
                if(centred<=bestCentered)continue;
                if((yz[0]!=original[1]||yz[1]!=original[2])&&!footprint(face.v(),yz[0],yz[1],.14))continue;
                var p=new double[]{original[0]-(face.ny()*(yz[0]-original[1])+face.nz()*(yz[1]-original[2]))/face.nx(),yz[0],yz[1]};boolean visible=true;
                for(var other:all){
                    if(Math.abs(other.nx())<1e-9)continue;var v=other.v();
                    double x=v[0]-(other.ny()*(p[1]-v[1])+other.nz()*(p[2]-v[2]))/other.nx();
                    if(x<=p[0]+.002)continue;
                    boolean positive=false,negative=false;
                    for(int k=0;k<v.length;k+=3){int next=(k+3)%v.length;double side=(v[next+1]-v[k+1])*(p[2]-v[k+2])-(v[next+2]-v[k+2])*(p[1]-v[k+1]);positive|=side>1e-9;negative|=side< -1e-9;}
                    if(!(positive&&negative)){visible=false;break;}
                }
                if(visible){fallback=new Socket(p[0]+.12,p[1],p[2],5);bestCentered=centred;if(centred==2)return fallback;}
            }
        }
        return fallback;
    }
    private static boolean footprint(double[] polygon,double y,double z,double half){
        for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}){
            boolean positive=false,negative=false;
            for(int k=0;k<polygon.length;k+=3){int next=(k+3)%polygon.length;double side=(polygon[next+1]-polygon[k+1])*(z+b*half-polygon[k+2])-(polygon[next+2]-polygon[k+2])*(y+a*half-polygon[k+1]);positive|=side>1e-9;negative|=side< -1e-9;}
            if(positive&&negative)return false;
        }return true;
    }
}
