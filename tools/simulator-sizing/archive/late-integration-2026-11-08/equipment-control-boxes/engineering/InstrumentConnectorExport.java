package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
/** Drawing adapter: the product instrument connector owns tap, entry and thermowell geometry. */
public class InstrumentConnectorExport {
 public static void main(String[] args)throws Exception{
  var gson=new Gson();var input=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();var output=new LinkedHashMap<String,Object>();
  for(var entry:input.entrySet()){
   var p=entry.getValue().getAsJsonObject();var mesh=InstrumentGeometry.connection(gson.fromJson(p.get("tap"),double[].class),p.get("top").getAsBoolean(),p.get("temperature").getAsBoolean(),p.get("depth").getAsDouble());
   output.put(entry.getKey(),mesh.stream().map(q->Map.of("v",q.vertices(),"colour",q.colour())).toList());
  }
  Files.writeString(Path.of(args[1]),gson.toJson(output));System.out.println("Native instrument connectors exported: "+output.size());
 }
}
