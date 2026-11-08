package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
/** Drawing adapter only: the production conduit/pipe mesher owns all lead and bend geometry. */
public class CableDrawingExport {
 public static void main(String[] args)throws Exception{
  var gson=new Gson();var input=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();var output=new LinkedHashMap<String,Object>();
  for(var entry:input.entrySet()){
   var routes=new ArrayList<Object>();
   for(var r:entry.getValue().getAsJsonArray()){
    var obj=r.getAsJsonObject();var points=new ArrayList<double[]>();for(var point:obj.getAsJsonArray("points"))points.add(gson.fromJson(point,double[].class));
    var polygons=ConduitGeometry.path(points);routes.add(Map.of("medium",obj.get("medium").getAsString(),"polygons",polygons.stream().map(p->Map.of("v",p.positions(),"m",p.material().name())).toList()));
   }
   output.put(entry.getKey(),routes);
  }
  Files.writeString(Path.of(args[1]),gson.toJson(output));
  Files.writeString(Path.of(args[1]).resolveSibling("shaft-block.json"),gson.toJson(ShaftGeometry.polygons(2,2).stream().map(p->Map.of("v",p.positions(),"m",p.material().name())).toList()));
  System.out.println("Native conduit mesher exported "+output.size()+" drawing layouts");
 }
}
