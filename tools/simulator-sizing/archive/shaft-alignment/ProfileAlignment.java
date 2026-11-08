package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
public class ProfileAlignment {
    public static void main(String[] args)throws Exception {
        var lines=new ArrayList<String>();lines.add("kind,heading,end,pixels,max_rim_error_m");
        double worst=0;int count=0;
        for(var kind:List.of(EquipmentShape.Kind.PUMP,EquipmentShape.Kind.COMPRESSOR,EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR,
                EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR,EquipmentShape.Kind.STEAM_TURBINE,EquipmentShape.Kind.GEARBOX)) {
            double size=ProcessUnit.snap(kind,(ProcessUnit.minDiameter(kind)+ProcessUnit.maxDiameter(kind))/2);
            int length=ProcessUnit.defaultLength(kind,size),support=ProcessUnit.defaultSupport(kind,size,length);
            for(var heading:EquipmentShape.Heading.values()) {
                var shape=EquipmentShape.unit(kind,size,length,heading,support);
                var mesh=ProcessUnit.of(shape).drawing().mesh();
                var transformed=ShapeRasterizer.shafts(shape);
                int index=0;
                for(var end:shape.shaftEnds()) {
                    var cap=mesh.facets().stream().filter(f->f.material()==Mesh.Material.SHAFT&&f.positions().size()==6
                            &&f.normal()[2]*end.sign()>.999&&f.positions().stream().allMatch(p->Math.abs(p[2]-end.face()[2])<1e-9)).findFirst().orElseThrow();
                    var centre=shape.transform().point(end.face());
                    var port=transformed.get(index);var block=port.block();int axis=port.dx()!=0?0:port.dy()!=0?1:2;
                    double radius=Math.hypot(cap.positions().getFirst()[0]-end.face()[0],cap.positions().getFirst()[1]-end.face()[1]);
                    int pixels=(int)Math.round(radius*32);
                    var rim=new ArrayList<double[]>();
                    for(var polygon:ShaftGeometry.polygons(axis,pixels)) {
                        var p=polygon.positions();
                        for(int k=0;k<p.length;k+=3) {
                            double[] v={p[k]+block.x(),p[k+1]+block.y(),p[k+2]+block.z()};
                            if(Math.abs(v[axis]-centre[axis])<1e-9&&Math.abs(distance(v,centre)-radius)<1e-9)rim.add(v);
                        }
                    }
                    double error=0;
                    for(var point:cap.positions()) {
                        var at=shape.transform().point(point);double nearest=Double.POSITIVE_INFINITY;
                        for(var v:rim)nearest=Math.min(nearest,distance(at,v));
                        error=Math.max(error,nearest);
                    }
                    lines.add(kind+","+heading+","+index+","+pixels+","+error);
                    worst=Math.max(worst,error);count++;index++;
                }
            }
        }
        Files.write(Path.of("tmp/shaft-alignment/profiles.csv"),lines);
        System.out.println("Recorded "+count+" coupling profiles across seven equipment families and four headings; max rim error="+worst+" m");
    }
    static double distance(double[] a,double[] b){double d=0;for(int i=0;i<3;i++)d+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(d);}
}
