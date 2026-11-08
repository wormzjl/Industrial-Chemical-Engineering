from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
def read(p):return p.read_text(encoding='utf-8')
def write(p,s):p.write_text(s,encoding='utf-8')
p=r/'runtime/control/ControlService.java';s=read(p);s=s.replace('in.putString("kind",s.kind().name());in.putString("unit"','sensorLocation(in,s);in.putString("kind",s.kind().name());in.putString("unit"');i=s.index('    private CompoundTag snapshot(');s=s[:i]+'''    /** Structural location only; a cached menu publication never loads a chunk or samples the process. */
    private void sensorLocation(CompoundTag tag,Sensor.Installed sensor){
        var host=world.instrumentHost(sensor.key().host());
        if(host!=null){
            var mount=Sensor.mounts(host,sensor.kind()).stream().filter(m->m.id().equals(sensor.key().mount())).findFirst().orElse(null);
            var cell=mount==null?host.entity().getBlockPos():BlockPos.containing(mount.centre());
            tag.putLong("position",cell.asLong());tag.putString("dimension",host.entity().getLevel().dimension().location().toString());tag.putBoolean("mountLocated",mount!=null);return;
        }
        var port=equipmentPorts.get(sensor.key().host());
        if(port!=null){tag.putLong("position",port.owner().asLong());tag.putString("dimension",port.dimension());return;}
        var registration=world.registrations().get(sensor.key().host());
        if(registration!=null){var p=registration.device().position();tag.putLong("position",new BlockPos(p.x(),p.y(),p.z()).asLong());tag.putString("dimension",p.dimension());}
    }
'''+s[i:];write(p,s)
p=r/'client/gui/screens/inventory/ControlPanelScreen.java';s=read(p);s=s.replace('Objects.equals(d.sensor,t.getUUID("id")))).toList()', 'Objects.equals(d.sensor,t.getUUID("id")),sensorHint(t))).toList()');s=s.replace('d.action==ControlSettings.Action.OUTLET,variable),ControlPanelScreen::tr)','d.action==ControlSettings.Action.OUTLET,variable),sensorHint(input),ControlPanelScreen::tr)');i=s.index('    private static List<Column.Row> withNavigation');s=s[:i]+'''    private static String sensorHint(CompoundTag input){
        if(input==null||!input.contains("position",4)||input.getString("dimension").isBlank())return tr("control.location_unknown");
        var p=net.minecraft.core.BlockPos.of(input.getLong("position"));
        return tr(input.getBoolean("mountLocated")?"control.sensor_location":"control.sensor_host_location",p.getX(),p.getY(),p.getZ(),input.getString("dimension"));
    }
'''+s[i:];write(p,s)
p=r/'client/gui/screens/EquipmentConstructorScreen.java';s=read(p).replace('CHOOSE, DESIGN, CONNECTIONS, NOZZLES, PAINT, TEMPLATES, IMPORTS','CHOOSE, DESIGN, PAINT, TEMPLATES, IMPORTS');a=s.index('            case DESIGN ->');b=s.index('            case PAINT ->',a);s=s[:a]+'''            case DESIGN -> ConstructorColumn.design(new ConstructorColumn.Design(form, diameter, tangentLength, support, forward, right, up, bank,
                    form.column() ? new ConstructorColumn.Imported(column != null ? column.diameter() : diameter, shownTrays().trays() + 2, shownTrays().feed() + 1) : null,
                    new ConstructorColumn.Outlet(outlets(), outlet(), words()), EquipmentNetwork.IMPORT_REACH,towerBank,duty,exchangerType),
                    !form.designed()&&!form.column()?connectionInput():null,form.column()?nozzleInput():null,EquipmentConstructorScreen::tr);
'''+s[b:];i=s.index('    /** The templates as their page lists');s=s[:i]+'''    private ConstructorColumn.Connections connectionInput(){
        var c=connections.isEmpty()?null:connection();int[] stations=stations();
        return new ConstructorColumn.Connections(connections.size(),selected,c!=null?c.side():0,words(),c!=null?number(c.station()+.5):"",form.orientation().horizontal(),c!=null?c.offset():0,
                c!=null&&c.offset()!=0?toward(c.across(),Integer.signum(c.offset())):null,connections.size()<EquipmentShape.MAX_CONNECTIONS&&stations[0]<=stations[1],EquipmentShape.bootFor(diameter,tangentLength)!=null,hasBoot(),new ConstructorColumn.Outlet(outlets(),outlet(),words()));
    }
    private ConstructorColumn.Nozzles nozzleInput(){
        var sides=sideNozzles();nozzle=clamp(nozzle,0,Math.max(0,sides.size()-1));ConstructorColumn.Nozzle shown=null;
        if(!sides.isEmpty()){var n=sides.get(nozzle);shown=new ConstructorColumn.Nozzle(n.role().name().toLowerCase(Locale.ROOT),n.index(),n.placed(),n.placed()?number(n.nozzle().base()[1]):"-",place(n).where(),n.turned(),n.side(),n.home());}
        return new ConstructorColumn.Nozzles(sides.size(),nozzle,shown,words(),(int)sides.stream().filter(ColumnDesign.SideNozzle::turned).count());
    }

'''+s[i:];s=s.replace('            case "nozzles" -> show(form.column() ? Page.NOZZLES : Page.CONNECTIONS);\n','').replace('            case "poles" -> machine(duty.withPoles(Integer.parseInt(at)));\n','');s=s.replace('page == Page.CONNECTIONS ? "clash.hint" : page == Page.NOZZLES ? "nozzle.status.hint" : "status.hint"','form.column() ? "nozzle.status.hint" : !form.designed() ? "clash.hint" : "status.hint"');s=s.replace('(page == Page.DESIGN || page == Page.NOZZLES) && form.column()','page == Page.DESIGN && form.column()').replace('page == Page.NOZZLES && !sides.isEmpty()','!sides.isEmpty()');write(p,s)
p=r/'science/equipment/PumpDesign.java';s=read(p);s=s.replace('duty.head(), duty.poles());','duty.head(), duty.poles()).withBaseSpeed(duty.baseSpeed());').replace('duty.poles());\n            default -> duty;','duty.poles()).withBaseSpeed(duty.baseSpeed());\n            case "base_speed" -> duty.withBaseSpeed(Math.clamp(UnitDesign.designSpeed(EquipmentShape.Kind.PUMP,duty)+steps*100,300,com.wormzjl.icheme.science.machine.Motor.MAXIMUM_RPM));\n            default -> duty;');write(p,s)
p=r/'science/equipment/MotorDesign.java';s=read(p).replace('setting.equals("base_speed") && !generator','setting.equals("base_speed")').replace('duty.speed()+steps*100','UnitDesign.designSpeed(generator?EquipmentShape.Kind.GENERATOR:EquipmentShape.Kind.MOTOR,duty)+steps*100').replace('return generator ? MachineDuty.generator(power, duty.poles()) : new MachineDuty(0,0,0,power,duty.poles(),duty.drive(),null,0,duty.baseSpeed());','return new MachineDuty(0,0,0,power,duty.poles(),duty.drive(),null,0,duty.baseSpeed());');write(p,s)
p=r/'client/gui/common/MachineColumn.java';s=read(p);s='\n'.join(line for line in s.splitlines() if 'if (v.poles() > 0 &&' not in line)+'\n';write(p,s)
p=r/'client/gui/screens/inventory/FluidDeviceScreen.java';s=read(p).replace('d.ratedPower()<=0?kind:d.poles()>0?tr("drive_line_poles",kind,rating,d.poles()):tr("drive_line",kind,rating)','d.ratedPower()<=0?kind:tr("drive_line",kind,rating)');write(p,s)
