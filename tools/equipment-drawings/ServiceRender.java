package com.wormzjl.icheme.science.equipment;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Textured interface proof using the established PumpRender rasterizer and current native owners. */
public final class ServiceRender {
    private ServiceRender() {}
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void main(String[] args)throws Exception{
        var out=Path.of(args[0]);Files.createDirectories(out);System.setProperty("ground","0");System.setProperty("cap","600");
        var load=PumpRender.class.getDeclaredMethod("sprites");load.setAccessible(true);Map sprites=(Map)load.invoke(null);
        Class<?> spriteType=Class.forName(PumpRender.class.getName()+"$Sprite");var ctor=spriteType.getDeclaredConstructor(int.class,int.class,int[].class);ctor.setAccessible(true);
        // This unanimated atlas image is rectangular; retain its full height rather than PumpRender's animated-strip shortcut.
        var finish=javax.imageio.ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/block/instrument_conduit.png").toFile());var pixels=finish.getRGB(0,0,finish.getWidth(),finish.getHeight(),null,0,finish.getWidth());sprites.put(Mesh.Material.INSTRUMENT_CONDUIT,ctor.newInstance(finish.getWidth(),finish.getHeight(),pixels));
        sprites.put(Mesh.Material.GUARD,ctor.newInstance(1,1,new int[]{0xff3299a9}));sprites.put(Mesh.Material.ACTUATOR,ctor.newInstance(1,1,new int[]{0xffc78235}));
        var add=PumpRender.class.getDeclaredMethod("add",List.class,Mesh.Polygon.class,int.class,int.class,int.class);add.setAccessible(true);
        var render=PumpRender.class.getDeclaredMethod("render",List.class,EquipmentShape.class,double[].class,Map.class,String.class);render.setAccessible(true);
        var heading=EquipmentShape.Heading.NORTH;
        var cases=new LinkedHashMap<String,EquipmentShape>();cases.put("motor",EquipmentShape.unit(EquipmentShape.Kind.MOTOR,250,1,heading,0));cases.put("pump",EquipmentShape.unit(EquipmentShape.Kind.PUMP,.1,1,heading,0));cases.put("drum",EquipmentShape.drum(4,8,EquipmentShape.Orientation.VERTICAL,heading,3));cases.put("exchanger",EquipmentShape.unit(EquipmentShape.Kind.EXCHANGER,1.5,8,heading,2));
        for(var item:cases.entrySet())for(var detail:RenderDetail.values()){
            var shape=item.getValue();var polygons=new ArrayList<>(ShapeRasterizer.renderPreview(shape,detail));var plan=EquipmentInstruments.of(shape);var ids=new HashSet<String>();for(var p:plan.probes())if(p.manual())ids.add(p.id());if(!ids.isEmpty())polygons.addAll(ShapeRasterizer.instrumentWiring(shape,ids,detail).polygons());
            var service=ShapeRasterizer.services(shape);var socket=service.data();var n=LineGeometry.step(socket.face());var q=new double[]{socket.x(),socket.y(),socket.z()};polygons.addAll(SocketGeometry.polygons(q,n,false,CableMedium.DATA,detail));
            if(service.power()!=null){var p=service.power();polygons.addAll(SocketGeometry.polygons(new double[]{p.x(),p.y(),p.z()},LineGeometry.step(p.face()),false,CableMedium.POWER,detail));}
            var triangles=new ArrayList();for(var polygon:polygons)add.invoke(null,triangles,polygon,0,0,0);
            System.setProperty("focus",q[0]+","+q[1]+","+q[2]+",.85");var camera=Mesh.unit(new double[]{-n[0],-.55,-n[2]+.35});
            for(var paint:List.of("light","dark")){System.setProperty("paint",paint.equals("light")?"AAB4B8":"445260");render.invoke(null,triangles,shape,camera,sprites,out.resolve(item.getKey()+"-"+detail.name().toLowerCase(Locale.ROOT)+"-"+paint+".png").toString());}
            if(shape.kind()==EquipmentShape.Kind.EXCHANGER){var p=plan.probes().getFirst();var c=p.centre();System.setProperty("focus",c[0]+","+c[1]+","+c[2]+",.65");render.invoke(null,triangles,shape,Mesh.unit(new double[]{-1,-.45,.35}),sprites,out.resolve("probe-"+detail.name().toLowerCase(Locale.ROOT)+".png").toString());}
        }
    }
}
