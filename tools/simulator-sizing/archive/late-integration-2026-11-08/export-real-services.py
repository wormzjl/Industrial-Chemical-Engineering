from pathlib import Path
p=Path('tools/equipment-drawings/EngineeringExport.java');s=p.read_text(encoding='utf-8').replace('  var socket=DataSocketGeometry.equipment(s);if(socket!=null)data.put("existingData",socket);','  var socket=DataSocketGeometry.equipment(s);if(socket!=null)data.put("existingData",socket);')
s=s.replace('  finish(id,data);\n }\n static void extra','''  var example=new ArrayList<Map<String,Object>>();
  for(boolean electrical:new boolean[]{false,true}){var gland=electrical?power:socket;if(gland==null)continue;var q=new double[]{gland.x(),gland.y(),gland.z()};var n=LineGeometry.step(gland.face());var pieces=electrical?PowerPortGeometry.polygons(q,n,false):DataPortGeometry.polygons(q,n,false);
   @SuppressWarnings("unchecked") var body=(List<Map<String,Object>>)data.get("polygons");for(var p:pieces)body.add(Map.of("v",p.positions(),"m",p.material()==Mesh.Material.GUARD?"DATA":p.material()==Mesh.Material.ACTUATOR?"FE":p.material().name()));
   var away=Mesh.plus(q,Mesh.scale(n,.55));var down=away.clone();down[1]=Math.max(.18,q[1]-.65);var end=Mesh.plus(down,Mesh.scale(n,.65));for(var p:ConduitGeometry.path(List.of(q,away,down,end)))example.add(Map.of("v",p.positions(),"m",electrical?"CABLE_FE":"CABLE_DATA"));
  }data.put("cableExamplePolygons",example);
  finish(id,data);
 }
 static void extra''',1)
# base uses mutable list, for physical sockets emitted by actual owners.
s=s.replace('polygons.stream().map(p->Map.of("v",p.positions(),"m",p.material().name())).toList()','new ArrayList<>(polygons.stream().map(p->Map.of("v",p.positions(),"m",p.material().name())).toList())')
p.write_text(s,encoding='utf-8')
p=Path('tools/equipment-drawings/rendering.py');s=p.read_text(encoding='utf-8').replace("elif material=='INSTRUMENT_LEAD':","elif material=='INSTRUMENT_CONDUIT':c=np.array([108,119,125])\n elif material=='SERVICE_COVER':c=np.array([170,180,185])\n elif material=='SERVICE_CASE':c=np.array([145,157,165])\n elif material=='INSTRUMENT_LEAD':");p.write_text(s,encoding='utf-8')
