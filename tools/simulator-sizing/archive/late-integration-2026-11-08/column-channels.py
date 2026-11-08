from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/BuiltInInputs.java');s=p.read_text(encoding='utf-8').replace(';stream(out,host,host,"reflux","REFLUX",true);',';')
s=s.replace('stream(out,host,host,name,"COLUMN_PORT:"+nozzle.role().name()+":"+index,true);','stream(out,host,host,name,nozzle.role()==EquipmentShape.Role.REFLUX?"REFLUX":"COLUMN_PORT:"+nozzle.role().name()+":"+index,true);')
s=s.replace('        }else if(HeatSides.unit(kind)!=null){','''            for(var role:com.wormzjl.icheme.science.column.operation.Port.values()){
                int count=role==com.wormzjl.icheme.science.column.operation.Port.STEAM?counts.getOrDefault(EquipmentShape.Role.STEAM,0):role==com.wormzjl.icheme.science.column.operation.Port.DRAW?counts.getOrDefault(EquipmentShape.Role.DRAW,0):1;
                for(int i=0;i<count;i++)stream(out,host,host,"boundary_"+role.name().toLowerCase(Locale.ROOT)+(count>1?"_"+(i+1):""),"COLUMN_PORT:"+role.name()+":"+i,true);
            }
        }else if(HeatSides.unit(kind)!=null){''',1)
s=s.replace('    private static void stream(','''    static List<ControlInput> member(Binding binding){
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
    private static void stream(''',1)
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java');s=p.read_text(encoding='utf-8').replace('BuiltInInputs.channels(binding))inputs.add','BuiltInInputs.member(binding))inputs.add');s=s.replace('        if(s.unit()!=reading.unit()', '        if(sensor.physical()==null&&s.unit()==Sensor.Unit.CUBIC_METRE_PER_SECOND&&!BuiltInInputs.volumeAvailable(sensor))return "UNIT UNAVAILABLE";\n        if(s.unit()!=reading.unit()',1)
s=s.replace('if(r.secondaryUnit().isPresent())in.putString("secondUnit",r.secondaryUnit().get().name());}inputs.add(in);','if(r.secondaryUnit().isPresent())in.putString("secondUnit",r.secondaryUnit().get().name());}if(s.physical()==null&&!BuiltInInputs.volumeAvailable(s))in.remove("secondUnit");inputs.add(in);')
p.write_text(s,encoding='utf-8')
