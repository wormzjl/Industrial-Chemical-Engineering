package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
public class DriveEnds {
    public static void main(String[] args)throws Exception {
        var root=Path.of(args[0]);var records=new ArrayList<String>();var counts=new ArrayList<String>();
        for(var kind:List.of(EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR))
            for(double power:UnitDesign.of(kind).sizes())for(var heading:EquipmentShape.Heading.values())for(int support:new int[]{0,8}){
                var s=EquipmentShape.unit(kind,power,1,heading,support);var l=ShapeRasterizer.layout(s);
                records.add(kind+" "+power+" "+heading+" "+support+" | "+s.sizeX()+" "+s.sizeY()+" "+s.sizeZ()+" | "+l.shell().size()+" "+l.interior().size()+" "+Long.toHexString(ShapeRasterizer.stamp(s))+" | "+ShapeRasterizer.shafts(s)+" | "+ShapeRasterizer.terminal(s));
                if(heading==EquipmentShape.Heading.NORTH&&support==0){
                    var faces=ProcessUnit.of(s).mesh().facets().stream().filter(f->f.part()<0&&Math.abs(f.normal()[2])>.999999&&f.material().name().startsWith("MOTOR")||f.part()<0&&Math.abs(f.normal()[2])>.999999&&f.material()==Mesh.Material.GENERATOR_PANEL).toList();
                    int n=0;double area=0;
                    for(int a=0;a<faces.size();a++)for(int b=a+1;b<faces.size();b++){
                        var p=faces.get(a);var q=faces.get(b);
                        if(p.normal()[2]*q.normal()[2]<0||Math.abs(p.positions().getFirst()[2]-q.positions().getFirst()[2])>1e-8)continue;
                        double overlap=intersection(p.positions(),q.positions());if(overlap>1e-9){n++;area+=overlap;}
                    }
                    counts.add(kind+" "+power+" | overlapping end-face pairs "+n+" | area "+area);
                }
            }
        Files.write(root.resolve("layouts.txt"),records);Files.write(root.resolve("overlaps.txt"),counts);
        System.out.println("Inspected "+records.size()+" placed layouts and "+counts.size()+" end-face meshes");
        if(args.length>1)for(var kind:List.of("MOTOR","GENERATOR"))for(int power:new int[]{22,1000})
            UnitRender.main(new String[]{kind,""+power,"1","0",root.resolve(kind.toLowerCase()+"-"+power).toString(),"front,back,iso,iso3"});
    }
    static double signed(List<double[]> p){double s=0;for(int i=0;i<p.size();i++){var a=p.get(i);var b=p.get((i+1)%p.size());s+=a[0]*b[1]-a[1]*b[0];}return s/2;}
    static double side(double[] a,double[] b,double[] p){return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]);}
    static double intersection(List<double[]> p,List<double[]> q){
        var poly=new ArrayList<>(p);double sign=Math.signum(signed(q));
        for(int k=0;k<q.size()&&!poly.isEmpty();k++){
            var a=q.get(k);var b=q.get((k+1)%q.size());var out=new ArrayList<double[]>();
            for(int i=0;i<poly.size();i++){
                var v=poly.get(i);var w=poly.get((i+1)%poly.size());double u=sign*side(a,b,v),z=sign*side(a,b,w);
                if(u>=-1e-12)out.add(v);
                if((u>=0)!=(z>=0)){double t=u/(u-z);out.add(new double[]{v[0]+t*(w[0]-v[0]),v[1]+t*(w[1]-v[1])});}
            }
            poly=out;
        }
        return Math.abs(signed(poly));
    }
}
