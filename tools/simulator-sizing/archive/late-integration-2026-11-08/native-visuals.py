from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/ShapeRasterizer.java');s=p.read_text(encoding='utf-8').replace('drawGeometry(shape,detail)','geometry(shape,detail)');p.write_text(s,encoding='utf-8')
p=Path('tools/equipment-drawings/EngineeringExport.java');s=p.read_text(encoding='utf-8');s=s.replace('selected=args[3].equals("*")','selected=args[3].equals("ALL")||args[3].equals("*")')
s=s.replace('  var polygons=raw(s);var data=base(id,polygons);','''  var polygons=raw(s);var probePlan=EquipmentInstruments.of(s);var active=new HashSet<String>();for(var p:probePlan.probes())if(p.manual())active.add(p.id());var wiring=ShapeRasterizer.instrumentWiring(s,active,RenderDetail.NORMAL);polygons.addAll(wiring.polygons());
  for(var p:probePlan.probes())if(p.manual())polygons.addAll(InstrumentWiring.heads(p));
  System.out.println(id+" wiring "+wiring.runs().size()+" runs; unresolved="+wiring.unresolved());var data=base(id,polygons);data.put("instrumentPlan",probePlan);data.put("instrumentWiring",Map.of("runs",wiring.runs(),"unresolved",wiring.unresolved()));data.put("serviceEnclosures",ShapeRasterizer.services(s).enclosures());''')
s=s.replace('data.put("builtIn",Set.of(', 'data.put("builtIn",Set.of(') # policy below
start=s.index('  data.put("builtIn",Set.of(');end=s.index('\n',start);s=s[:start]+'  data.put("builtIn",EquipmentInstruments.automatic(s.kind()));'+s[end:]
p.write_text(s,encoding='utf-8')
p=Path('tools/equipment-drawings/update.py');s=p.read_text(encoding='utf-8').replace("'-PequipmentDrawingOnly='+selection","'-PequipmentDrawingOnly='+('ALL' if selection=='*' else selection)");p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/InstrumentWiring.java');s=p.read_text(encoding='utf-8');at=s.index('    private static void head(');s=s[:at]+'''    public static List<Polygon> heads(EquipmentInstruments.Probe p){var mesh=new Mesh();for(var q:InstrumentGeometry.transmitter(p.quantity()==EquipmentInstruments.Quantity.LEVEL))head(mesh,p,q);return mesh.facets().stream().map(Mesh::whole).toList();}
''' +s[at:];p.write_text(s,encoding='utf-8')
import json
old=Path('tmp/equipment-drawings/proposal-k/geometry');new=Path('tmp/equipment-drawings/implementation-geometry-a/geometry')
for p in new.glob('*.json'):
 q=old/p.name
 if not q.exists():continue
 a=json.loads(q.read_text());b=json.loads(p.read_text())
 for k in ['processPorts','shaftPorts']:
  if a.get(k)!=b.get(k):print(p.stem,k,'CHANGED',a.get(k),b.get(k))
