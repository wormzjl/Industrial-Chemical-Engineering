package com.wormzjl.icheme.science.equipment;

import java.util.*;

/** Junction-box mounts on real casing faces. Pipe necks, flanges, guards and support steel are excluded. */
public final class DataSocketGeometry {
    private DataSocketGeometry(){}
    public record Socket(double x,double y,double z,int face){}
    private static final Map<EquipmentShape,Optional<Socket>> CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<EquipmentShape,Optional<Socket>> POWER=new java.util.concurrent.ConcurrentHashMap<>();
    public static Socket equipment(EquipmentShape shape){
        if(CACHE.size()>128)CACHE.clear();
        return CACHE.computeIfAbsent(shape,s->{
            if(!s.designed()||s.reservoir())return Optional.empty();
            var unit=ProcessUnit.of(s);
            var socket=unit.dataSocket!=null?unit.dataSocket:surface(unit.mesh().facets().stream().filter(f->f.part()<0).map(Mesh::whole).toList());
            if(socket==null)return Optional.empty();
            var p=s.transform().point(new double[]{socket.x(),socket.y(),socket.z()});var n=s.transform().direction(new double[]{1,0,0});
            int face=n[0]>.5?5:n[0]<-.5?4:n[2]>.5?3:2;
            return Optional.of(new Socket(p[0],p[1],p[2],face));
        }).orElse(null);
    }
    /** The terminal box's opposite side from the +X data connector on the casing. */
    public static Socket power(EquipmentShape shape){
        if(POWER.size()>128)POWER.clear();
        return POWER.computeIfAbsent(shape,s->Optional.ofNullable(powerSocket(s))).orElse(null);
    }
    private static Socket powerSocket(EquipmentShape shape){
        if(!shape.designed())return null;
        var unit=ProcessUnit.of(shape);var centre=unit.terminal();if(centre==null)return null;
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
        for(var face:candidates){
            var p=face.centre();boolean visible=true;
            for(var other:all){
                if(Math.abs(other.nx())<1e-9)continue;var v=other.v();
                double x=v[0]-(other.ny()*(p[1]-v[1])+other.nz()*(p[2]-v[2]))/other.nx();
                if(x<=p[0]+.002)continue;
                boolean positive=false,negative=false;
                for(int k=0;k<v.length;k+=3){int next=(k+3)%v.length;double side=(v[next+1]-v[k+1])*(p[2]-v[k+2])-(v[next+2]-v[k+2])*(p[1]-v[k+1]);positive|=side>1e-9;negative|=side< -1e-9;}
                if(!(positive&&negative)){visible=false;break;}
            }
            if(visible)return new Socket(p[0]+.12,p[1],p[2],5);
        }
        return null;
    }
}
