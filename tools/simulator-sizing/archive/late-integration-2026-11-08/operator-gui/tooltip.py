from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'runtime/control/ControlService.java';s=p.read_text(encoding='utf-8');a=s.index('    private void sensorLocation(');b=s.index('    private CompoundTag snapshot(',a);s=s[:a]+'''    private void sensorLocation(CompoundTag tag,Sensor.Installed sensor){
        long identity=sensor.key().host();var host=world.instrumentHost(identity);var registration=world.registrations().get(identity);
        if(host!=null&&!host.pipe()||registration!=null&&registration.device().kind()!=com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE)tag.putLong("equipment",identity);
        if(host!=null){
            var mount=Sensor.mounts(host,sensor.kind()).stream().filter(m->m.id().equals(sensor.key().mount())).findFirst().orElse(null);
            var cell=mount==null?host.entity().getBlockPos():BlockPos.containing(mount.centre());
            tag.putLong("position",cell.asLong());tag.putBoolean("mountLocated",mount!=null);return;
        }
        var port=equipmentPorts.get(identity);
        if(port!=null){tag.putLong("position",port.owner().asLong());return;}
        if(registration!=null){var p=registration.device().position();tag.putLong("position",new BlockPos(p.x(),p.y(),p.z()).asLong());}
    }
'''+s[b:];p.write_text(s,encoding='utf-8')
p=r/'client/gui/screens/inventory/ControlPanelScreen.java';s=p.read_text(encoding='utf-8');a=s.index('    private static String sensorHint(');b=s.index('    private static List<Column.Row> withNavigation',a);s=s[:a]+'''    private static String sensorHint(CompoundTag input){
        if(input==null)return tr("control.location_unknown");
        String location=tr("control.location_unknown");
        if(input.contains("position",4)){var p=net.minecraft.core.BlockPos.of(input.getLong("position"));location=tr(input.getBoolean("mountLocated")?"control.sensor_location":"control.sensor_host_location",p.getX(),p.getY(),p.getZ());}
        long equipment=input.getLong("equipment");return equipment>0?location+"\\n"+tr("control.sensor_equipment",String.format(Locale.ROOT,"#%04d",equipment)):location;
    }
'''+s[b:];p.write_text(s,encoding='utf-8')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8').replace('Sensor block: %s, %s, %s\\nDimension: %s','Sensor block: %s, %s, %s').replace('Equipment block: %s, %s, %s\\nDimension: %s','Equipment block: %s, %s, %s');s=s.replace('  "gui.icheme.control.location_unknown":','  "gui.icheme.control.sensor_equipment": "Equipment: %s",\n  "gui.icheme.control.location_unknown":');p.write_text(s,encoding='utf-8')
p=Path('tmp/operator-gui/REQUEST.md');s=p.read_text(encoding='utf-8');p.write_text(s+'\nOwner steering: tooltip must show attached equipment ID as well as coordinates, without dimension. Pipes retain sensor ID selection; constructed equipment IDs use #0000 formatting.\n',encoding='utf-8')
