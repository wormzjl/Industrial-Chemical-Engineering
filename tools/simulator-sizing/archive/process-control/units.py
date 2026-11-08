from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java');s=p.read_text(encoding='utf-8');s=s.replace('in.putString("kind",s.kind().name());','in.putString("kind",s.kind().name());in.putString("unit",Sensor.primaryUnit(s.kind()).name());Sensor.secondaryUnit(s.kind()).ifPresent(u->in.putString("secondUnit",u.name()));');s=s.replace('in.putDouble("secondary",r.secondary());','in.putDouble("secondary",r.secondary());in.putDouble("span",r.span());');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/ControlPanelScreen.java');s=p.read_text(encoding='utf-8');s=s.replace('t.getLong("revision")+":"+tags(t,"inputs")','t.getLong("revision")+":"+t.getInt("inputPages")+":"+t.getInt("outputPages")+":"+tags(t,"inputs")')
s=s.replace('else if(key.equals("primary")||key.equals("secondary")){var in=input();if(in!=null)d.unit=Sensor.Unit.valueOf(in.getString(key.equals("primary")?"unit":"secondUnit"));}', 'else if(key.equals("primary")||key.equals("secondary")){var in=input();if(in!=null)convert(d,in,Sensor.Unit.valueOf(in.getString(key.equals("primary")?"unit":"secondUnit")));}')
s=s.replace('d.sensor=UUID.fromString(key.substring(7));var input=input();if(input!=null)d.unit=Sensor.Unit.valueOf(input.getString("unit"));', 'var prior=input();d.sensor=UUID.fromString(key.substring(7));var input=input();if(input!=null){d.unit=Sensor.Unit.valueOf(input.getString("unit"));if(prior==null||!prior.getString("kind").equals(input.getString("kind")))d.target=Double.toString(input.getDouble("value"));}')
a=s.index('    private void apply()')
s=s[:a]+'''    private void convert(Draft d,CompoundTag input,Sensor.Unit unit){
        if(d.unit==unit)return;
        try{double value=Double.parseDouble(d.target);boolean toSecondary=unit.name().equals(input.getString("secondUnit"));double factor=input.getString("kind").equals("LEVEL")?100/Math.max(1e-12,input.getDouble("span")):input.getDouble("value")==0?Double.NaN:input.getDouble("secondary")/input.getDouble("value");
            if(value!=0&&(!(factor>0)||!Double.isFinite(factor))){reply=tr("control.no_conversion");return;}d.target=Double.toString(value==0?0:toSecondary?value*factor:value/factor);d.unit=unit;
        }catch(NumberFormatException incomplete){reply=tr("control.invalid");}
    }
''' + s[a:];p.write_text(s,encoding='utf-8')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8').rstrip();s=s[:-1].rstrip()+',\n  "gui.icheme.control.no_conversion": "Wait for a valid reading before changing units."\n}\n';p.write_text(s,encoding='utf-8')
