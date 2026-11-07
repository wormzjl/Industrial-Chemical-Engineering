from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java');s=p.read_text();s=s.replace('snapshot(menu.panel(),sensors,readings)','snapshot(menu,sensors,readings)')
a=s.index('    private static <T> List<T> page') if '    private static <T> List<T> page' in s else s.index('    private CompoundTag snapshot(')
s=s[:a]+'''    private static <T> List<T> page(List<T> values,int requested,int size){int page=Math.clamp(requested,0,Math.max(0,(values.size()-1)/size)),from=page*size;return new ArrayList<>(values.subList(from,Math.min(values.size(),from+size)));}
    private CompoundTag snapshot(ControlPanelMenu menu,Map<UUID,Sensor.Installed> sensors,Map<UUID,Sensor.Reading> readings){
        UUID owner=menu.panel();var root=new CompoundTag();var controllers=new ListTag();var station=facts.panel(owner);
        if(station==null)return root;
        var connected=bus().connected(owner);
        var visible=facts.panels().stream().filter(p->!p.dcs()&&(p.id().equals(owner)||station.dcs()&&connected.contains(p.id()))).sorted(Comparator.comparing(p->p.position().toShortString())).toList();
        root.putInt("controllerPages",Math.max(1,(visible.size()+31)/32));root.putInt("controllerPage",Math.clamp(menu.controllerPage(),0,Math.max(0,(visible.size()-1)/32)));
        for(var p:page(visible,menu.controllerPage(),32)){
            var row=p.save();row.putString("status",status.getOrDefault(p.id(),"WAITING"));row.putString("positionLabel",p.position().toShortString());
            var available=bus().connected(p.id());var hostIds=facts.ports().values().stream().filter(port->available.contains(port.id())).map(DataPort::host).filter(h->h>0).collect(java.util.stream.Collectors.toSet());
            var inputChoices=sensors.values().stream().filter(s->hostIds.contains(s.key().host())).sorted(Comparator.comparing(s->s.instance().toString())).toList();
            row.putInt("inputPages",Math.max(1,(inputChoices.size()+63)/64));row.putInt("inputPage",Math.clamp(menu.devicePage(),0,Math.max(0,(inputChoices.size()-1)/64)));
            var inputRows=page(inputChoices,menu.devicePage(),64);inputChoices.stream().filter(s->s.instance().equals(p.settings().sensor())&&!inputRows.contains(s)).findFirst().ifPresent(inputRows::add);
            var inputs=new ListTag();for(var s:inputRows){
                var in=new CompoundTag();in.putUUID("id",s.instance());in.putString("label",s.kind().name()+" / "+label(s.key().host()));in.putString("kind",s.kind().name());
                var r=readings.get(s.instance());if(r!=null){in.putDouble("value",r.value());in.putDouble("secondary",r.secondary());in.putString("quality",r.availability().name());in.putString("unit",r.unit().name());if(r.secondaryUnit().isPresent())in.putString("secondUnit",r.secondaryUnit().get().name());}inputs.add(in);
            }row.put("inputs",inputs);
            var outputChoices=world.registrations().values().stream().filter(r->hostIds.contains(r.device().id())&&(r.device().control() instanceof FlowControl.ControlValve||r.device().heat()!=null&&r.device().heat().controllable())).sorted(Comparator.comparingLong(r->r.device().id())).toList();
            row.putInt("outputPages",Math.max(1,(outputChoices.size()+63)/64));row.putInt("outputPage",Math.clamp(menu.devicePage(),0,Math.max(0,(outputChoices.size()-1)/64)));
            var outputRows=page(outputChoices,menu.devicePage(),64);outputChoices.stream().filter(r->r.device().id()==p.settings().output()&&!outputRows.contains(r)).findFirst().ifPresent(outputRows::add);
            var outputs=new ListTag();for(var r:outputRows){var out=new CompoundTag();out.putLong("id",r.device().id());out.putString("label",label(r.device().id()));outputs.add(out);}row.put("outputs",outputs);controllers.add(row);
        }
        root.put("controllers",controllers);return root;
    }
}
''';p.write_text(s)
p=Path('src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/ControlPanelScreen.java');s=p.read_text();s=s.replace('private final KitDoor door=', 'private int controllerPage,controllerPages=1;'+ '\n    private final KitDoor door=')
s=s.replace('if(page.equals("input"))return ControlColumns.choices(', 'if(page.equals("input"))return withNavigation(ControlColumns.choices(')
s=s.replace('.toList(),ControlPanelScreen::tr);\n        if(page.equals("output"))', '.toList(),ControlPanelScreen::tr),row.getInt("inputPage"),row.getInt("inputPages"),"devices");\n        if(page.equals("output"))',1)
s=s.replace('if(page.equals("output"))return ControlColumns.choices(', 'if(page.equals("output"))return withNavigation(ControlColumns.choices(')
s=s.replace('.toList(),ControlPanelScreen::tr);\n        var input=input();', '.toList(),ControlPanelScreen::tr),row.getInt("outputPage"),row.getInt("outputPages"),"devices");\n        var input=input();',1)
a=s.index('    private List<List<Column.Row>> rows()');b=s.index('    private void receive()',a)
s=s[:a]+'''    private static List<Column.Row> withNavigation(List<Column.Row> rows,int page,int pages,String key){var out=new ArrayList<>(rows);out.addAll(ControlColumns.navigation(page,pages,key,ControlPanelScreen::tr));return out;}
    private List<List<Column.Row>> rows(){var r=right();if(!menu.dcs())return List.of(r);return List.of(withNavigation(ControlColumns.controllers(controllers.stream().map(t->{var id=t.getUUID("id");String name=t.getCompound("settings").getString("name");return new ControlColumns.Choice("controller:"+id,name.isBlank()?t.getString("positionLabel"):name,id.equals(selected));}).toList(),ControlPanelScreen::tr),controllerPage,controllerPages,"controllers"),r);}
''' + s[b:]
s=s.replace('controllers=tags(view,"controllers");', 'controllers=tags(view,"controllers");controllerPage=view.getInt("controllerPage");controllerPages=Math.max(1,view.getInt("controllerPages"));')
s=s.replace('return page+"/"+selected+', 'return controllerPage+"/"+controllerPages+"/"+page+"/"+selected+')
s=s.replace('private void act(String key){var d=draft();', '''private void act(String key){var d=draft();
        if(key.startsWith("controllers:")||key.startsWith("devices:")){int direction=key.endsWith("next")?1:-1;if(key.startsWith("controllers:"))ControlNetwork.browse(menu.containerId,controllerPage+direction,0);else{var row=selected();if(row!=null)ControlNetwork.browse(menu.containerId,controllerPage,row.getInt(page.equals("input")?"inputPage":"outputPage")+direction);}return;}
''')
p.write_text(s)
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text().rstrip();s=s[:-1].rstrip()+',\n  "gui.icheme.control.previous": "Previous",\n  "gui.icheme.control.next": "Next"\n}\n';p.write_text(s)
