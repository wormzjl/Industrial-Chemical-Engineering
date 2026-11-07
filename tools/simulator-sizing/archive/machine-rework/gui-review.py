from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:65]);p.write_text(s.replace(old,new),encoding='utf-8')
p=b/'client/gui/common/MachineColumn.java';s=p.read_text(encoding='utf-8');old='''            for (var m : Mode.values())
                keys.add(position("regulate:" + m.name().toLowerCase(Locale.ROOT), t.get(KEY + "regulate." + m.name().toLowerCase(Locale.ROOT)),
                        t.get(KEY + "regulate." + m.name().toLowerCase(Locale.ROOT) + ".hint"), in.mode() == m, true));
            regulate.add(new Column.Buttons(keys));''';assert old in s;s=s.replace(old,'''            boolean motor=in.kind()==Kind.MOTOR;
            if(motor)regulate.add(new Column.Buttons(List.of(position("mode:manual",t.get(KEY+"mode.manual"),t.get(KEY+"regulate.speed.hint"),in.mode()==Mode.SPEED,true),position("mode:auto",t.get(KEY+"mode.auto"),t.get(KEY+"auto.hint"),in.mode()!=Mode.SPEED,true))));
            if(!motor||in.mode()!=Mode.SPEED){
                for(var m:Mode.values())if(!motor||m!=Mode.SPEED)
                    keys.add(position("regulate:"+m.name().toLowerCase(Locale.ROOT),t.get(KEY+"regulate."+m.name().toLowerCase(Locale.ROOT)),t.get(KEY+"regulate."+m.name().toLowerCase(Locale.ROOT)+".hint"),in.mode()==m,true));
                regulate.add(new Column.Buttons(keys));
            }''');p.write_text(s,encoding='utf-8')
edit('client/gui/screens/inventory/MachineScreen.java','        else if (key.startsWith("regulate:"))','        else if(key.equals("mode:manual"))mode=MachineColumn.Mode.SPEED;\n        else if(key.equals("mode:auto")&&mode==MachineColumn.Mode.SPEED)mode=MachineColumn.Mode.FLOW;\n        else if (key.startsWith("regulate:"))')
edit('network/MachineNetwork.java','if (unit == null || !hasPage(unit.kind()))','if (unit == null || !hasPage(world.shafts().kindOf(unit.id())))')
edit('network/MachineNetwork.java','                    boolean variable = true;\n                    if (!variable) yield new ShaftUnit.Settings(asked.running(), now.mode(), now.setPoint(), now.powerLimit(), now.ratio());\n','')
edit('network/MachineNetwork.java','100 * Motor.LOWEST_SPEED, 100 * MAXIMUM_SHARE','100 * Motor.LOWEST_SPEED, 100 * (u.kind()==ShaftNetwork.Kind.MOTOR?Motor.HIGHEST_SPEED:MAXIMUM_SHARE)')
edit('runtime/fluid/ShaftTrains.java','d.kind() == ShaftNetwork.Kind.CREATE_COUPLING) rpm','(d.kind() == ShaftNetwork.Kind.CREATE_COUPLING||createDrivers.contains(d.id()))) rpm')
edit('network/FluidNetwork.java','PROTOCOL="fluid-18"','PROTOCOL="fluid-19"')
import json
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8');d=json.loads(s)
changes={
'gui.icheme.machine.regulate.speed':'Speed','gui.icheme.machine.regulate.flow':'Flow','gui.icheme.machine.regulate.pressure':'Pressure',
'gui.icheme.machine.regulate.hint':'Choose a manual speed or automatic demand control.',
'gui.icheme.machine.regulate.flow.hint':"A motor follows the set flow of the single casing connected over its data cable. Set that target on the casing, then press Apply.",
'gui.icheme.machine.regulate.pressure.hint':"A motor follows the discharge target of the single casing connected over its data cable. Set that target on the casing, then press Apply.",
'gui.icheme.machine.mode.manual':'Manual','gui.icheme.machine.mode.auto':'Auto','gui.icheme.machine.auto.hint':'Link exactly one driven pump or compressor by data cable. It must also share the shaft train. Then press Apply.'}
for k,v in changes.items():
 if k in d:s=s.replace(json.dumps(k)+': '+json.dumps(d[k],ensure_ascii=False),json.dumps(k)+': '+json.dumps(v,ensure_ascii=False))
 else:s=s.rstrip()[:-1].rstrip()+',\n  '+json.dumps(k)+': '+json.dumps(v)+'\n}\n'
p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/wormzjl/icheme/client/gui/common/MachineColumnTest.java');s=p.read_text(encoding='utf-8').replace('Map.of(MachineColumn.Kind.MOTOR, List.of("speed"),','Map.of(MachineColumn.Kind.MOTOR, List.of("speed", "powerLimit"),')
s=s.replace('assertEquals(80, MachineColumn.speedEntry(motor)','assertEquals(2360, MachineColumn.speedEntry(motor)').replace('Kind.MOTOR, 80, 2950','Kind.MOTOR, 2360, 2950')
s=s.replace('assertEquals(List.of(), keys(place(MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, false, MachineColumn.Mode.SPEED','assertEquals(List.of("speed", "powerLimit"), keys(place(MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, false, MachineColumn.Mode.SPEED')
s=s.replace('assertEquals(List.of(), keys(place(MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.FLOW','assertEquals(List.of("powerLimit"), keys(place(MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.FLOW')
s=s.replace('// A fixed motor neither regulates nor sets a speed; a regulating one names the casing\'s target; Reset shows only while tripped (A94).','// Every motor is VFD-controlled; automatic control reads the wired casing. Reset shows only while tripped.')
p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/wormzjl/icheme/client/gui/common/ConstructorColumnTest.java');s=p.read_text(encoding='utf-8');start=s.index('        assertEquals(List.of("power"',s.index('        var motor ='));end=s.index('        var turbine',start)
s=s[:start]+'''        assertEquals(List.of("power","power-","power+","support","support-","support+","base_speed","base_speed-","base_speed+"),slots(motor).subList(0,9));
        assertFalse(slots(motor).stream().anyMatch(k->k.startsWith("poles:")||k.startsWith("drive:")));
'''+s[end:];p.write_text(s,encoding='utf-8')
