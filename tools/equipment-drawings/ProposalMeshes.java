package com.wormzjl.icheme.science.equipment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

/** Offline bridge only: actual game owners supply instrument, conduit and shaft meshes. */
public final class ProposalMeshes {
    private ProposalMeshes() {}
    public static void main(String[] args) throws Exception {
        var work=Path.of(args[0]);var gson=new Gson();
        var input=JsonParser.parseString(Files.readString(work.resolve("instrument-connectors.json"))).getAsJsonObject();
        var connectors=new LinkedHashMap<String,Object>();
        for(var entry:input.entrySet()) {
            var p=entry.getValue().getAsJsonObject();
            var mesh=InstrumentGeometry.connection(gson.fromJson(p.get("tap"),double[].class),p.get("top").getAsBoolean(),p.get("temperature").getAsBoolean(),p.get("depth").getAsDouble());
            connectors.put(entry.getKey(),mesh.stream().map(q->Map.of("v",q.vertices(),"colour",q.colour())).toList());
        }
        Files.writeString(work.resolve("geometry/instrument-connector-meshes.json"),gson.toJson(connectors));
        input=JsonParser.parseString(Files.readString(work.resolve("cable-routes.json"))).getAsJsonObject();
        var cables=new LinkedHashMap<String,Object>();
        for(var entry:input.entrySet()) {
            var routes=new ArrayList<Object>();
            for(var route:entry.getValue().getAsJsonArray()) {
                var p=route.getAsJsonObject();var points=new ArrayList<double[]>();
                for(var point:p.getAsJsonArray("points"))points.add(gson.fromJson(point,double[].class));
                routes.add(Map.of("medium",p.get("medium").getAsString(),"polygons",ConduitGeometry.path(points).stream().map(q->Map.of("v",q.positions(),"m",q.material().name())).toList()));
            }
            cables.put(entry.getKey(),routes);
        }
        Files.writeString(work.resolve("cable-meshes.json"),gson.toJson(cables));
        Files.writeString(work.resolve("shaft-block.json"),gson.toJson(ShaftGeometry.polygons(2,2).stream().map(q->Map.of("v",q.positions(),"m",q.material().name())).toList()));
        if(Files.exists(work.resolve("instrument-conduits.json"))) {
            input=JsonParser.parseString(Files.readString(work.resolve("instrument-conduits.json"))).getAsJsonObject();
            var fixed=new LinkedHashMap<String,Object>();
            for(var entry:input.entrySet()) {
                var request=entry.getValue().getAsJsonObject();var runs=new ArrayList<Object>();
                for(var item:request.getAsJsonArray("runs")) {
                    var r=item.getAsJsonObject();var points=new ArrayList<double[]>();
                    for(var point:r.getAsJsonArray("points"))points.add(gson.fromJson(point,double[].class));
                    var mesh=LineGeometry.conduit(points,r.get("outside").getAsDouble());
                    runs.add(Map.of("id",r.get("id").getAsString(),"points",points,"flex",r.get("flex").getAsBoolean(),"polygons",mesh.stream().map(q->Map.of("v",q.positions(),"m",q.material().name())).toList()));
                }
                fixed.put(entry.getKey(),Map.of("fingerprint",request.get("fingerprint").getAsString(),"runs",runs));
            }
            Files.writeString(work.resolve("instrument-conduits-meshes.json"),gson.toJson(fixed));
        }
        System.out.println("Native proposal meshes: "+connectors.size()+" instrument connectors; "+cables.size()+" conduit layouts");
    }
}
