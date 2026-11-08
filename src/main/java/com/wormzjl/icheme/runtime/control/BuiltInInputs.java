package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;
import java.util.*;

/** Stable equipment channels over accepted engine publications. Never samples or solves the process. */
final class BuiltInInputs {
    private BuiltInInputs() {}
    record Binding(long host,EquipmentShape shape,PhysicalFluidTopology.Position origin,List<Long> nodes) {
        Binding{nodes=List.copyOf(nodes);}
        long node(int index){return index<nodes.size()?nodes.get(index):0;}
    }
    static List<ControlInput> channels(Binding binding){
        var out=new ArrayList<ControlInput>();var shape=binding.shape;var kind=shape.kind();long host=binding.host;
        if(kind==EquipmentShape.Kind.COLUMN){
            for(String end:List.of("top","bottom"))stream(out,host,host,end,"COLUMN:"+end,false);
            var counts=new EnumMap<EquipmentShape.Role,Integer>(EquipmentShape.Role.class);for(var nozzle:shape.nozzles())counts.merge(nozzle.role(),1,Integer::sum);
            var seen=new EnumMap<EquipmentShape.Role,Integer>(EquipmentShape.Role.class);for(var nozzle:shape.nozzles()){int index=seen.merge(nozzle.role(),1,Integer::sum)-1;String name=nozzle.role().name().toLowerCase(Locale.ROOT)+(counts.get(nozzle.role())>1||Set.of(EquipmentShape.Role.DRAW,EquipmentShape.Role.STEAM,EquipmentShape.Role.PUMPAROUND_DRAW,EquipmentShape.Role.RETURN).contains(nozzle.role())?"_"+(index+1):"");stream(out,host,host,name,nozzle.role()==EquipmentShape.Role.REFLUX?"REFLUX":"COLUMN_PORT:"+nozzle.role().name()+":"+index,true);}
            for(var role:com.wormzjl.icheme.science.column.operation.Port.values()){
                int count=role==com.wormzjl.icheme.science.column.operation.Port.STEAM?counts.getOrDefault(EquipmentShape.Role.STEAM,0):role==com.wormzjl.icheme.science.column.operation.Port.DRAW?counts.getOrDefault(EquipmentShape.Role.DRAW,0):1;
                for(int i=0;i<count;i++)stream(out,host,host,"boundary_"+role.name().toLowerCase(Locale.ROOT)+(count>1?"_"+(i+1):""),"COLUMN_PORT:"+role.name()+":"+i,true);
            }
        }else if(HeatSides.unit(kind)!=null){
            var sides=HeatSides.of(shape);for(int i=0;i<sides.size();i++)for(var role:List.of(sides.get(i).inlet(),sides.get(i).outlet()))stream(out,host,binding.node(i),role.name().toLowerCase(Locale.ROOT),"HEAT:"+role.name(),kind==EquipmentShape.Kind.EXCHANGER||kind==EquipmentShape.Kind.KETTLE);
            if(kind==EquipmentShape.Kind.KETTLE)for(var role:List.of(EquipmentShape.Role.FEED,EquipmentShape.Role.VAPOUR_OUTLET,EquipmentShape.Role.LIQUID_OUTLET))stream(out,host,binding.node(sides.size()),role.name().toLowerCase(Locale.ROOT),"HEAT:"+role.name(),true);
        }else if(kind.coolingTower()){
            stream(out,host,binding.node(0),"hot_water_inlet","BASIN:FEED",false);stream(out,host,binding.node(0),"cold_water_outlet","BASIN:LIQUID_OUTLET",false);
            out.add(ControlInput.builtIn(host,binding.node(0),"basin_level",Sensor.Kind.LEVEL,"LEVEL"));
        }else if(kind.mover()||kind==EquipmentShape.Kind.STEAM_TURBINE){
            stream(out,host,binding.node(0),"inlet","MOVER:INLET",false);stream(out,host,binding.node(0),"outlet","MOVER:OUTLET",false);out.add(ControlInput.builtIn(host,binding.node(0),"flow_rate",Sensor.Kind.FLOW,"MOVER:OUTLET"));
        }
        if(kind.mover()||kind==EquipmentShape.Kind.STEAM_TURBINE||kind==EquipmentShape.Kind.MOTOR||kind==EquipmentShape.Kind.GENERATOR)out.add(ControlInput.builtIn(host,binding.node(0),"shaft_speed",Sensor.Kind.SPEED,"SPEED"));
        return List.copyOf(out);
    }
    static List<ControlInput> inline(long host){var out=new ArrayList<ControlInput>();stream(out,host,host,"inlet","MOVER:INLET",false);stream(out,host,host,"outlet","MOVER:OUTLET",false);out.add(ControlInput.builtIn(host,host,"flow_rate",Sensor.Kind.FLOW,"MOVER:OUTLET"));return out;}
    static List<ControlInput> member(Binding binding){
        var out=new ArrayList<>(channels(binding));var known=new HashSet<String>();for(var input:out){int suffix=input.channel().lastIndexOf('_');if(suffix>0)known.add(input.channel().substring(0,suffix));}
        var seen=new EnumMap<EquipmentShape.Role,Integer>(EquipmentShape.Role.class);
        for(var p:binding.shape().nozzles()){
            int index=seen.merge(p.role(),1,Integer::sum)-1;String name=p.role().name().toLowerCase(Locale.ROOT);
            if(out.stream().anyMatch(i->i.channel().equals(name+"_pressure")))continue;
            stream(out,binding.host(),binding.node(0),name+(index>0?"_"+(index+1):""),"PORT:"+p.role()+":"+index,true);
        }
        if(binding.shape().kind()==EquipmentShape.Kind.AIR_COOLER)for(String end:List.of("inlet","outlet"))stream(out,binding.host(),binding.node(0),"air_utility_"+end,"AIR:"+end,true);
        return List.copyOf(out);
    }
    static boolean volumeAvailable(ControlInput input){return !input.role().startsWith("COLUMN")&&!input.role().equals("REFLUX")&&!input.role().startsWith("AIR:")&&!input.role().startsWith("PORT:");}
    private static void stream(List<ControlInput> out,long host,long source,String name,String role,boolean flow){out.add(ControlInput.builtIn(host,source,name+"_pressure",Sensor.Kind.PRESSURE,role));out.add(ControlInput.builtIn(host,source,name+"_temperature",Sensor.Kind.TEMPERATURE,role));if(flow)out.add(ControlInput.builtIn(host,source,name+"_flow_rate",Sensor.Kind.FLOW,role));}
    static Sensor.Reading read(ControlInput input,FluidWorldAuthority world,Binding binding,Map<Long,FluidView> views){
        var view=input.source()>0?views.computeIfAbsent(input.source(),world::view):null;long tick=view==null?world.onlineTick():view.committedTick();double value=0,secondary=0,span=0;var quality=Instrument.Availability.UNAVAILABLE;
        if(input.role().equals("SPEED")){
            var unit=world.shafts().unit(input.host());var drive=unit==null?world.shafts().unitOfDevice(input.source()):unit;var state=drive==null?null:world.shafts().view(drive.id());
            if(state!=null){value=state.speed();tick=world.onlineTick();quality=Instrument.Availability.AVAILABLE;}
        }else if(input.role().equals("LEVEL")){
            if(view!=null&&view.tank()!=null&&tick>0){span=view.tank().levelHeight();value=view.tank().level(view.state());secondary=span>0?100*value/span:0;quality=Instrument.Availability.AVAILABLE;}
        }else if((input.role().startsWith("COLUMN")||input.role().equals("REFLUX"))&&binding!=null){
            var sample=world.columnInstrument(binding.origin(),input.role(),input.kind());if(sample!=null)return new Sensor.Reading(input.instance(),input.kind(),input.channel(),sample.value(),0,0,sample.tick(),sample.availability());
        }else if(input.role().startsWith("PORT:")&&binding!=null){
            var parts=input.role().split(":");var role=EquipmentShape.Role.valueOf(parts[1]);int ordinal=Integer.parseInt(parts[2]),seen=0;
            for(var p:ShapeRasterizer.ports(binding.shape()))if(binding.shape().nozzles().get(p.nozzle()).role()==role){if(seen++!=ordinal)continue;var o=binding.origin();var c=p.cell();var observed=world.instrumentAt(new PhysicalFluidTopology.Position(o.dimension(),o.x()+c.x()+p.dx(),o.y()+c.y()+p.dy(),o.z()+c.z()+p.dz()));if(observed!=null){switch(input.kind()){case PRESSURE->value=observed.pressure();case TEMPERATURE->value=observed.temperature();case FLOW->{value=observed.massFlow();secondary=observed.volumeFlow()==null?0:observed.volumeFlow();}default->{}}quality=input.kind()==Sensor.Kind.TEMPERATURE&&!observed.flowing()?Instrument.Availability.NO_FLOW:Instrument.Availability.AVAILABLE;}break;}
        }else {
            FluidView.Port port=null;
            if(input.role().startsWith("HEAT:")&&view!=null&&view.heat()!=null)port=view.heat().port(input.role().substring(5));
            else if(input.role().startsWith("MOVER:"))port=world.instrumentMoverPort(input.source(),input.role().endsWith("OUTLET"));
            else if(input.role().startsWith("BASIN:")){String role=input.role().substring(6);port=world.instrumentVesselPorts(input.source()).stream().filter(p->p.role().equals(role)).findFirst().orElse(null);}
            if(port!=null&&tick>0)switch(input.kind()){
                case PRESSURE->{if(port.pressure()!=null){value=port.pressure();quality=Instrument.Availability.AVAILABLE;}}
                case TEMPERATURE->{if(port.temperature()!=null){value=port.temperature();quality=Instrument.Availability.AVAILABLE;}}
                case FLOW->{value=port.massFlow();secondary=Arrays.stream(port.phaseVolumes()).sum()*Math.signum(value);quality=Instrument.Availability.AVAILABLE;}
                default->{}
            }
        }
        return new Sensor.Reading(input.instance(),input.kind(),input.channel(),value,secondary,span,tick,quality);
    }
}
