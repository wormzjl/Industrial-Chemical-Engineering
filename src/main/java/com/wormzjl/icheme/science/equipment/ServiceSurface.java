package com.wormzjl.icheme.science.equipment;

import java.util.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Queries the actual native facets; mounting never falls back to an equipment bounding box. */
final class ServiceSurface {
    final List<Polygon> polygons;
    ServiceSurface(List<Polygon> polygons) { this.polygons=List.copyOf(polygons); }

    double[] ray(int axis,double[] wanted,int sign,Set<Material> materials) {return ray(axis,wanted,sign,materials,null);}
    double[] rayNear(int axis,double[] wanted,int sign,Set<Material> materials,double datum){return ray(axis,wanted,sign,materials,datum);}
    private double[] ray(int axis,double[] wanted,int sign,Set<Material> materials,Double datum) {
        int u=(axis+1)%3,v=(axis+2)%3; double best=sign>0?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY,bestDistance=Double.POSITIVE_INFINITY;
        for(var polygon:polygons) {
            if(!materials.contains(polygon.material()))continue;
            var p=polygon.positions();
            for(int i=1;i+1<polygon.size();i++) {
                int a=3*i,b=3*(i+1);double x=p[a+u]-p[u],y=p[a+v]-p[v],X=p[b+u]-p[u],Y=p[b+v]-p[v],det=x*Y-y*X;
                if(Math.abs(det)<1e-11)continue;
                double dx=wanted[u]-p[u],dy=wanted[v]-p[v],s=(dx*Y-dy*X)/det,t=(x*dy-y*dx)/det;
                if(s< -1e-8||t< -1e-8||s+t>1+1e-8)continue;
                double at=p[axis]+s*(p[a+axis]-p[axis])+t*(p[b+axis]-p[axis]);if(datum==null)best=sign>0?Math.max(best,at):Math.min(best,at);else if(Math.abs(at-datum)<bestDistance){bestDistance=Math.abs(at-datum);best=at;}
            }
        }
        if(!Double.isFinite(best))throw new IllegalArgumentException("No native service mounting surface at "+Arrays.toString(wanted));
        var point=wanted.clone();point[axis]=best;return point;
    }
    double[] bounds(Set<Material> materials) {
        double[] out={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var polygon:polygons)if(materials.contains(polygon.material())) {
            var p=polygon.positions();for(int i=0;i<p.length;i++) {int a=i%3;out[a]=Math.min(out[a],p[i]);out[a+3]=Math.max(out[a+3],p[i]);}
        }
        if(!Double.isFinite(out[0]))throw new IllegalArgumentException("Missing service carrier "+materials);
        return out;
    }
    boolean overlaps(double[] lo,double[] hi,Set<Material> excluded) {
        for(var polygon:polygons) {
            if(excluded.contains(polygon.material()))continue;var p=polygon.positions();boolean hit=true;
            for(int a=0;a<3;a++) {double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;for(int i=a;i<p.length;i+=3){low=Math.min(low,p[i]);high=Math.max(high,p[i]);}if(high<lo[a]||low>hi[a]){hit=false;break;}}
            if(hit)return true;
        }return false;
    }
    /** Outward support face with the full rear mounting footprint, optionally the first saddle. */
    double[] support(double y,boolean first,List<double[]> clearances) {
        var choices=new ArrayList<double[]>();var allowed=Set.of(Material.SUPPORT,Material.STEELWORK,Material.CONCRETE);
        for(var polygon:polygons) {
            if(!allowed.contains(polygon.material()))continue;var p=polygon.positions();double[] lo={p[0],p[1],p[2]},hi=lo.clone();
            for(int i=3;i<p.length;i++){lo[i%3]=Math.min(lo[i%3],p[i]);hi[i%3]=Math.max(hi[i%3],p[i]);}
            if(lo[1]>y-.2||hi[1]<y+.2||hi[2]-lo[2]<=.07)continue;
            var n=cross(sub(Arrays.copyOfRange(p,3,6),Arrays.copyOfRange(p,0,3)),sub(Arrays.copyOfRange(p,6,9),Arrays.copyOfRange(p,0,3)));
            if(Math.abs(n[0])<1e-9)continue;
            double z=(lo[2]+hi[2])/2;double[] point;try{point=ray(0,new double[]{0,y,z},1,allowed);}catch(IllegalArgumentException outside){continue;}boolean footprint=true;
            if(first)for(double dy:new double[]{-.14,.14})for(double dz:new double[]{-.14,.14}) {
                try{footprint&=Math.abs(ray(0,new double[]{0,y+dy,z+dz},1,allowed)[0]-point[0])<1e-7;}catch(IllegalArgumentException missing){footprint=false;}
            }
            if(footprint&&clear(point,clearances))choices.add(point);
        }
        choices.sort((a,b)->{int x=Double.compare(b[0],a[0]);return x!=0?x:Double.compare(first?a[2]:b[2],first?b[2]:a[2]);});
        if(choices.isEmpty())throw new IllegalArgumentException("No clear native service support at height "+y);
        return choices.getFirst();
    }
    static boolean clear(double[] point,List<double[]> zones) {
        for(var z:zones)if(point[0]+.4>=z[0]&&point[0]-.4<=z[3]&&point[1]+.3>=z[1]&&point[1]-.3<=z[4]&&point[2]+.4>=z[2]&&point[2]-.4<=z[5])return false;
        return true;
    }
    static Transform between(Transform from,Transform to) {
        var origin=to.point(from.local(new double[3]));var matrix=new int[9];
        for(int i=0;i<3;i++){var basis=new double[3];basis[i]=1;var point=to.point(from.local(basis));for(int a=0;a<3;a++)matrix[3*a+i]=(int)Math.round(point[a]-origin[a]);}
        return new Transform(matrix,origin);
    }
    static EquipmentShape north(EquipmentShape s) {return new EquipmentShape(s.kind(),s.diameter(),s.tangentLength(),s.orientation(),EquipmentShape.Heading.NORTH,s.support(),s.trays(),s.connections(),s.bank(),s.boot(),s.outlet(),s.towerBank(),s.duty(),s.exchangerType());}
    static List<Polygon> turn(List<Polygon> polygons,Transform transform) {
        var result=new ArrayList<Polygon>();
        for(var polygon:polygons){var p=polygon.positions().clone();var n=polygon.normals().clone();for(int i=0;i<p.length;i+=3){System.arraycopy(transform.point(Arrays.copyOfRange(p,i,i+3)),0,p,i,3);System.arraycopy(transform.direction(Arrays.copyOfRange(n,i,i+3)),0,n,i,3);}result.add(new Polygon(p,polygon.uvs(),n,polygon.material()));}
        return List.copyOf(result);
    }
}
