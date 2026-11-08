package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import com.google.gson.Gson;
public class EngineeringExport {
 static Gson gson=new Gson();static Path out;static List<Map<String,Object>> entries=new ArrayList<>(),excluded=new ArrayList<>();
 public static void main(String[] args)throws Exception{
  out=Path.of(args[0]);Files.createDirectories(out);
  for(var form:VesselPlacement.Form.values()){
   if(form==VesselPlacement.Form.CROSSWISE)continue;
   var kind=form.kind();
   if(kind!=EquipmentShape.Kind.COLUMN&&!kind.reservoir()&&!kind.mover()&&kind!=EquipmentShape.Kind.STEAM_TURBINE&&HeatSides.unit(kind)==null&&kind!=EquipmentShape.Kind.MOTOR&&kind!=EquipmentShape.Kind.GENERATOR){excluded.add(Map.of("type",form.name(),"reason","No data/power interface in the present equipment model"));continue;}
   var size=ConstructorDefaults.sizes(form);EquipmentShape s;
   if(kind==EquipmentShape.Kind.DRUM){double d=size.diameter();int sup=EquipmentShape.defaultSupport(d,form.orientation());s=EquipmentShape.drum(d,size.length(),form.orientation(),EquipmentShape.Heading.NORTH,sup);}
   else if(kind==EquipmentShape.Kind.COLUMN){double d=size.diameter();s=EquipmentShape.column(d,new ColumnTrays(32,18,List.of(10,24),List.of(33),List.of(new ColumnTrays.Pumparound(26,20))),EquipmentShape.Heading.NORTH,EquipmentShape.minimumSkirt(d));}
   else if(kind.storage())s=new EquipmentShape(kind,size.diameter(),size.length(),UnitDesign.of(kind).orientation(),EquipmentShape.Heading.NORTH,UnitDesign.of(kind).minSupport(size.diameter(),size.length()),null,List.of(),ProcessUnit.Bank.ONE,false,0,TowerBank.ONE,MachineDuty.NONE);
   else {int support=UnitDesign.of(kind).minSupport(size.diameter(),size.length());s=EquipmentShape.unit(kind,size.diameter(),size.length(),EquipmentShape.Heading.NORTH,support);}
   if(kind==EquipmentShape.Kind.MOTOR||kind==EquipmentShape.Kind.GENERATOR){emit(kind.name()+"_SMALL",EquipmentShape.unit(kind,250,1,EquipmentShape.Heading.NORTH,0));emit(kind.name()+"_LARGE",EquipmentShape.unit(kind,2500,1,EquipmentShape.Heading.NORTH,0));}
   else if(kind==EquipmentShape.Kind.PUMP){emit("PUMP_OH2",EquipmentShape.unit(kind,.1,1,EquipmentShape.Heading.NORTH,0));emit("PUMP_BB2",EquipmentShape.unit(kind,.4,1,EquipmentShape.Heading.NORTH,0));}
   else if(kind==EquipmentShape.Kind.EXCHANGER){emit("EXCHANGER_STANDARD",s);emit("EXCHANGER_CROSSFLOW",s.withExchangerType(ExchangerType.CROSSFLOW));}
   else {emit(form.name(),s);if(kind==EquipmentShape.Kind.TANK){emit("TANK_COMPACT",new EquipmentShape(kind,4,4,s.orientation(),s.front(),0,null,List.of(),ProcessUnit.Bank.ONE,false,0,TowerBank.ONE,MachineDuty.NONE));emit("TANK_SLENDER",new EquipmentShape(kind,4,8,s.orientation(),s.front(),0,null,List.of(),ProcessUnit.Bank.ONE,false,0,TowerBank.ONE,MachineDuty.NONE));}if(kind==EquipmentShape.Kind.SPHERE)emit("SPHERE_SMALL",new EquipmentShape(kind,6,1,s.orientation(),s.front(),2,null,List.of(),ProcessUnit.Bank.ONE,false,0,TowerBank.ONE,MachineDuty.NONE));if(kind==EquipmentShape.Kind.CABIN_HEATER)emit("CABIN_HEATER_SINGLE",EquipmentShape.unit(kind,8,16,EquipmentShape.Heading.NORTH,3));}
  }
  var horizontal=ConstructorDefaults.sizes(VesselPlacement.Form.LENGTHWISE);double bd=horizontal.diameter(),bl=horizontal.length();int bs=EquipmentShape.minimumBootSupport(bd,bl);
  emit("LENGTHWISE_BOOT",EquipmentShape.drum(bd,bl,EquipmentShape.Orientation.HORIZONTAL,EquipmentShape.Heading.NORTH,bs,EquipmentShape.defaultConnections(bd,bl,EquipmentShape.Orientation.HORIZONTAL,bs,true),true));entries.removeLast();
  var line=new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,5,48,PipeSizes.code(PipeSizes.outside(.15)),0,0,false);
  extra("INLINE_PUMP",InlineMoverGeometry.polygons(line,false,false),true,false);
  extra("INLINE_COMPRESSOR",InlineMoverGeometry.polygons(line,true,false),true,false);
  extra("CONTROL_VALVE",ValveGeometry.pressure(line,false),false,true);
  for(boolean level:new boolean[]{false,true}){var sensor=new ArrayList<Mesh.Polygon>();for(var q:InstrumentGeometry.transmitter(level)){var v=q.vertices();double[] uv=new double[v.length/3*2],n=new double[v.length];sensor.add(new Mesh.Polygon(v,uv,n,Mesh.Material.MACHINE));}extra(level?"LEVEL_SENSOR_HOUSING":"SENSOR_HOUSING",sensor,false,false);}
  var instrumentAssets=new LinkedHashMap<String,Object>();
  for(boolean thermometer:new boolean[]{false,true})instrumentAssets.put(thermometer?"temperature_connection":"pressure_connection",InstrumentGeometry.connection(new double[]{0,-.4,0},true,thermometer).stream().map(q->Map.of("v",q.vertices(),"colour",q.colour())).toList());
  for(boolean thermometer:new boolean[]{false,true})instrumentAssets.put(thermometer?"temperature_back":"pressure_back",InstrumentGeometry.connection(new double[]{0,0,-.17},false,thermometer).stream().map(q->Map.of("v",q.vertices(),"colour",q.colour())).toList());
  for(boolean level:new boolean[]{false,true})instrumentAssets.put(level?"digital_level":"digital",InstrumentGeometry.transmitter(level).stream().map(q->Map.of("v",q.vertices(),"colour",q.colour())).toList());
  instrumentAssets.put("dial",InstrumentGeometry.housing().stream().map(q->Map.of("v",q.vertices(),"colour",q.colour())).toList());
  Files.writeString(out.resolve("instrument-assets.json"),gson.toJson(instrumentAssets));
  Files.writeString(out.resolve("manifest.json"),gson.toJson(Map.of("snapshot","c15dfcecc","units","m; one Minecraft block = one metre","entries",entries,"excluded",excluded)));
 }
 static List<Mesh.Polygon> raw(EquipmentShape s)throws Exception{
  var method=ShapeRasterizer.class.getDeclaredMethod("geometry",EquipmentShape.class);method.setAccessible(true);var geometry=method.invoke(null,s);var cls=geometry.getClass();var mf=cls.getDeclaredField("mesh");mf.setAccessible(true);var mesh=(Mesh)mf.get(geometry);
  var out=new ArrayList<Mesh.Polygon>();for(var f:mesh.facets())out.add(Mesh.whole(f));
  var nf=cls.getDeclaredField("nozzles");nf.setAccessible(true);var ns=(List<?>)nf.get(geometry);var nozzle=cls.getDeclaredMethod("nozzle",int.class,int.class);nozzle.setAccessible(true);
  for(int i=0;i<ns.size();i++)for(var f:((Mesh)nozzle.invoke(geometry,i,0)).facets())out.add(Mesh.whole(f));
  if(s.kind()==EquipmentShape.Kind.TANK){var floating=ShapeRasterizer.floating(s);double level=(s.tangentLength()-TankDesign.FREEBOARD)*.5,lift=floating.underside(level)-floating.landed();for(var p:floating.roof()){var v=p.positions().clone();for(int i=1;i<v.length;i+=3)v[i]+=lift;out.add(new Mesh.Polygon(v,p.uvs(),p.normals(),p.material()));}for(var p:floating.ladder()){var v=p.positions().clone();var ax=floating.axis();var along=floating.along();double down=ax[2]*along[0]-ax[0]*along[2]>0?-1:1,angle=down*floating.pitch(level),co=Math.cos(angle),si=Math.sin(angle);for(int i=0;i<v.length;i+=3){var q=new double[]{v[i],v[i+1],v[i+2]};var cross=Mesh.cross(ax,q);double dot=Mesh.dot(ax,q);for(int k=0;k<3;k++)v[i+k]=floating.pivot()[k]+q[k]*co+cross[k]*si+ax[k]*dot*(1-co);}out.add(new Mesh.Polygon(v,p.uvs(),p.normals(),p.material()));}}

  if(s.kind()==EquipmentShape.Kind.TANK)out.addAll(ShapeRasterizer.tankGauge(s));
  return out;
 }
 static void emit(String id,EquipmentShape s)throws Exception{
  var polygons=raw(s);var data=base(id,polygons);data.put("kind",s.kind().name());data.put("parameters",Map.of("size",s.diameter(),"length",s.tangentLength(),"support",s.support(),"orientation",s.orientation().name(),"heading",s.front().name(),"power_kw",s.duty().power()));
  data.put("axis",s.axis());if(s.kind()==EquipmentShape.Kind.TANK)data.put("planAxis",TankDesign.Plan.of(s.diameter(),(int)s.tangentLength()).a());if(s.kind()==EquipmentShape.Kind.SPHERE)data.put("planAxis",SphereDesign.Plan.of(s.diameter(),s.support()).a());
  if(s.kind()==EquipmentShape.Kind.SPHERE){var plan=SphereDesign.Plan.of(s.diameter(),s.support());var legs=new ArrayList<double[]>();for(int i=0;i<plan.n();i++)legs.add(s.transform().point(plan.at(plan.legs(),plan.leg(i),0)));data.put("supportLegs",legs);data.put("legRadius",plan.wl()/2);}
  data.put("shaftPorts",ShapeRasterizer.shafts(s));
  data.put("processPorts",ShapeRasterizer.ports(s).stream().map(p->{var n=s.nozzles().get(p.nozzle());return Map.of("role",n.role().name(),"cell",p.cell(),"normal",new int[]{p.dx(),p.dy(),p.dz()},"face",s.transform().point(n.face()),"base",s.transform().point(n.base()),"outsideDiameter",n.defaultOd());}).toList());
  if(s.bootPlace()!=null){var b=s.bootPlace();double a=s.axis(),r=s.radius();data.put("boot",Map.of("axis",s.transform().point(new double[]{a,b.station(),a}),"top",s.transform().point(new double[]{a-r,b.station(),a}),"bottom",s.transform().point(new double[]{a-r-b.length()-b.diameter()/4,b.station(),a}),"diameter",b.diameter(),"length",b.length()));}
  if(s.kind()==EquipmentShape.Kind.SPHERE){var plan=SphereDesign.Plan.of(s.diameter(),s.support());data.put("topPlatform",Map.of("centre",s.transform().point(new double[]{plan.a(),plan.level()+Access.DECK,plan.a()}),"radius",plan.rp()));}
  if(s.kind()==EquipmentShape.Kind.TANK)data.put("mechanicalLevelBounds",TankGaugeGeometry.bounds(s));
  var access=new ArrayList<Object>();for(var climb:ShapeRasterizer.climbs(s)){double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};for(var corner:climb.footprint())for(double y:new double[]{climb.base()-.1,climb.top()+1.8}){var point=s.transform().point(new double[]{corner[0],y,corner[1]});for(int a=0;a<3;a++){double room=a==1?0:.3+1.0/(2*ShapeRasterizer.SOLID_RESOLUTION);min[a]=Math.min(min[a],point[a]-room);max[a]=Math.max(max[a],point[a]+room);}}access.add(Map.of("min",min,"max",max,"basis","native climb footprint plus standing player body and half collision voxel"));}data.put("accessClearance",access);
  if((s.kind()==EquipmentShape.Kind.MOTOR||s.kind()==EquipmentShape.Kind.GENERATOR)&&s.duty().power()<=MotorDesign.LOW_VOLTAGE){double r=ProcessUnit.motorDiameter(s.duty().power())/2;var c=s.transform().point(ProcessUnit.of(s).terminalBox);double y=ShapeRasterizer.shafts(s).getFirst().cell().y()+.5;data.put("nativeTerminalBounds",Map.of("min",new double[]{c[0]-.35*r,y+(s.kind()==EquipmentShape.Kind.GENERATOR?.76*r:r-.02),c[2]-.275*r},"max",new double[]{c[0]+.35*r,y+1.45*r,c[2]+.275*r}));}
  data.put("power",Set.of(EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR,EquipmentShape.Kind.AIR_COOLER,EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER).contains(s.kind()));
  data.put("builtIn",Set.of(EquipmentShape.Kind.COLUMN,EquipmentShape.Kind.EXCHANGER,EquipmentShape.Kind.KETTLE,EquipmentShape.Kind.AIR_COOLER,EquipmentShape.Kind.VERTICAL_HEATER,EquipmentShape.Kind.CABIN_HEATER,EquipmentShape.Kind.STEAM_TURBINE,EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR).contains(s.kind()));
  var socket=DataSocketGeometry.equipment(s);if(socket!=null)data.put("existingData",socket);
  var power=DataSocketGeometry.power(s);if(power!=null)data.put("existingPower",power);
  var taps=new ArrayList<InstrumentGeometry.Socket>();for(var node:ShapeRasterizer.fluidCells(s))taps.addAll(InstrumentGeometry.sockets(s,node));taps.addAll(InstrumentGeometry.levelSockets(s));data.put("sensorMounts",taps.stream().distinct().toList());
  if(s.designed()&&s.kind().unit()){var u=ProcessUnit.of(s);if(u.terminalBox!=null)data.put("terminal",s.transform().point(u.terminalBox));}
  finish(id,data);
 }
 static void extra(String id,List<Mesh.Polygon> polygons,boolean power,boolean builtin)throws Exception{var data=base(id,polygons);data.put("kind",id);data.put("parameters",Map.of("line_bore",.15));data.put("power",power);data.put("builtIn",builtin);finish(id,data);}
 static Map<String,Object> base(String id,List<Mesh.Polygon> polygons){var d=new LinkedHashMap<String,Object>();d.put("id",id);d.put("polygons",polygons.stream().map(p->Map.of("v",p.positions(),"m",p.material().name())).toList());return d;}
 static void finish(String id,Map<String,Object> data)throws Exception{Files.writeString(out.resolve(id+".json"),gson.toJson(data));entries.add(Map.of("id",id,"file",id+".json","kind",data.get("kind"),"power",data.get("power"),"builtIn",data.get("builtIn")));System.out.println(id+" exported "+((List<?>)data.get("polygons")).size()+" polygons");}
}
