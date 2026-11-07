package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Server-thread owner. Cable topology is structural; control and publication run only on engine deadlines. */
public final class ControlService {
    public static final int CADENCE=100;
    private final FluidWorldAuthority world;
    private final ControlFacts facts;
    private final Map<Long,DataPort> equipmentPorts=new HashMap<>();
    private DataBus bus=new DataBus(List.of(),Map.of());
    private long busRevision=-1,driveRevision=-1;
    private final Set<ControlPanelMenu> watchers=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID,CompoundTag> published=new HashMap<>();
    private final ArrayDeque<Edit> edits=new ArrayDeque<>();
    private final Map<UUID,String> status=new HashMap<>();
    private final Map<UUID,LevelSample> levels=new HashMap<>();
    private Map<Long,FlowControl.ControlValve> valvePlans=Map.of();
    private Map<Long,Double> heatPlans=Map.of();
    private Map<Long,FlowControl.ControlValve> nextValves;
    private Map<Long,Double> nextHeat;
    private record LevelSample(UUID sensor,long output,long tick,double volume){}
    private record Edit(ControlPanelMenu menu,UUID id,long revision,ControlSettings settings){}
    public ControlService(FluidWorldAuthority world,ControlFacts facts){this.world=world;this.facts=facts;world.instruments().allSensors().stream().sorted(Comparator.comparing(s->s.instance().toString())).forEach(facts::identify);facts.ports().values().forEach(p->{if(p.host()>0&&!p.kind().power())equipmentPorts.put(p.host(),p);});}
    public ControlFacts facts(){return facts;}
    /** Immutable slice inputs. The original device settings remain facts and resume when the controller is off. */
    public PassiveNetwork apply(PassiveNetwork graph){
        List<PassiveNetwork.Pipe> changed=null;
        var registered=world.registrations();
        for(int e=0;e<graph.pipes().size();e++){
            var pipe=graph.pipes().get(e);long host=graph.reservoirs().get(pipe.first()).id();
            var record=registered.get(host);var next=pipe;
            if(record!=null&&record.device().control() instanceof FlowControl.ControlValve base&&pipe.control() instanceof FlowControl.ControlValve){
                var law=valvePlans.getOrDefault(host,base);if(!pipe.control().equals(law))next=next.withControl(law);
            }
            if(pipe.heat()!=null){
                long physical=pipe.id()-Long.MIN_VALUE/2;var heatRecord=registered.get(physical);
                if(heatRecord!=null&&heatRecord.device().heat()!=null){
                    var base=heatRecord.device().heat();var set=heatPlans.get(physical);
                    HeatControl control=set==null?base.controller():new HeatControl(HeatControl.Target.OUTLET_TEMPERATURE,set,base.control().lowLimit(),base.fans()?base.control().share():1-base.control().share());
                    var law=pipe.heat();
                    if(law instanceof HeatSide side&&!Objects.equals(side.control(),control))next=next.withHeat(new HeatSide(side.area(),side.films(),side.wall(),control==null&&side.control()!=null?base.control().share():side.bypass(),side.giveBack(),side.partner(),side.active(),control,side.limit(),side.routing()));
                    else if(law instanceof AirCooled air&&!Objects.equals(air.control(),control))next=next.withHeat(new AirCooled(air.area(),air.air(),control==null&&air.control()!=null?base.control().share():air.draught(),control,air.limit(),air.fanLimit()));
                }
            }
            if(next!=pipe){if(changed==null)changed=new ArrayList<>(graph.pipes());changed.set(e,next);}
        }
        return changed==null?graph:new PassiveNetwork(graph.reservoirs(),changed,graph.scheduledTransfers());
    }
    public void cable(DataBus.Cable cable){facts.cable(cable);}
    public void removeCable(DataBus.Site site){facts.removeCable(site);}
    public void loaded(ConduitBlockEntity cable){var existing=facts.cable(cable.cable().site());if(existing!=null)cable.configure(existing);}
    public void loaded(PipeRackBlockEntity rack){rack.cables(facts.cables().stream().filter(c->c.site().lane()>0&&c.site().dimension().equals(rack.getLevel().dimension().location().toString())&&c.site().cell().equals(rack.getBlockPos())).toList());}
    public void loaded(ControlPanelBlockEntity box){
        var dim=box.getLevel().dimension().location().toString();
        var panel=facts.panels().stream().filter(p->p.dimension().equals(dim)&&p.position().equals(box.getBlockPos())).findFirst().orElse(null);
        if(panel==null){
            // A copied block must never take over another controller's persistent identity.
            var id=facts.panel(box.identity())==null?box.identity():UUID.randomUUID();
            panel=new ControlFacts.Panel(id,dim,box.getBlockPos(),box.dcs(),0,ControlSettings.EMPTY);facts.panel(panel);
        }
        if(panel.dcs()!=box.dcs())throw new IllegalStateException("Control panel kind differs from registered facts");
        var port=facts.port(panel.id());var mount=DataPorts.panel(box,panel.id());
        if(port==null){port=mount;facts.port(port);}
        else{var refreshed=DataPorts.remount(port,mount,facts.wired(port.id()));if(!refreshed.equals(port)){port=refreshed;facts.port(port);}}
        if(!panel.id().equals(box.identity())||!port.equals(box.dataPort()))box.bind(panel.id(),port);
    }
    public void removePanel(UUID id){facts.removePanel(id);levels.remove(id);status.remove(id);published.remove(id);}
    public void bind(InstrumentHosts.Host host){
        var r=world.registrations().get(host.identity());
        boolean actuator=r!=null&&(r.device().control() instanceof FlowControl.ControlValve||r.device().control() instanceof FlowControl.Mover||r.device().heat()!=null);
        if(!actuator&&host.metadata().sensors().isEmpty()&&host.metadata().dataPort()==null)return;
        var ids=new HashMap<UUID,String>();for(var sensor:host.metadata().sensors())ids.put(sensor.instance(),facts.identify(sensor).label());boolean namesChanged=!ids.equals(host.metadata().sensorIds());host.metadata().sensorIds(ids);
        var port=equipmentPorts.get(host.identity());
        if(port==null){port=DataPorts.equipment(host,actuator);facts.port(port);equipmentPorts.put(host.identity(),port);}
        else{var refreshed=DataPorts.refresh(port,host,facts::wired);if(!refreshed.equals(port)){port=refreshed;facts.port(port);equipmentPorts.put(host.identity(),port);}}
        if(namesChanged||!port.equals(host.metadata().dataPort())){
            host.metadata().dataPort(port);host.entity().setChanged();InstrumentHosts.loaded(host.entity());
            host.entity().getLevel().sendBlockUpdated(host.entity().getBlockPos(),host.entity().getBlockState(),host.entity().getBlockState(),2);
        }
    }
    public void bindMotor(EquipmentShellBlockEntity cell,ShaftUnit unit) {
        var port=equipmentPorts.get(unit.id());
        if(port==null){port=DataPorts.motor(cell,unit);facts.port(port);equipmentPorts.put(unit.id(),port);}
        else{var host=new InstrumentHosts.Host(unit.id(),cell,List.of(),false);var refreshed=DataPorts.refresh(port,host,facts::wired);if(!refreshed.equals(port)){port=refreshed;facts.port(port);equipmentPorts.put(unit.id(),port);}}
        if(!port.equals(cell.instruments().dataPort())) {
            cell.instruments().dataPort(port);cell.setChanged();InstrumentHosts.loaded(cell);
            cell.getLevel().sendBlockUpdated(cell.getBlockPos(),cell.getBlockState(),cell.getBlockState(),2);
        }
    }
    /** Connected physical host IDs, independent of chunk loading and menu subscriptions. */
    public Set<Long> connectedHosts(long host) {
        var port=equipmentPorts.get(host);if(port==null)return Set.of();
        var result=new HashSet<Long>();for(var id:bus().connected(port.id())){var p=facts.port(id);if(p!=null&&p.host()>0&&p.host()!=host)result.add(p.host());}return Set.copyOf(result);
    }
    public void removeHost(long host){var port=equipmentPorts.remove(host);if(port!=null)facts.removePort(port.id());}
    private DataBus bus(){if(busRevision!=facts.topologyRevision()){bus=new DataBus(facts.cables(),facts.ports());busRevision=facts.topologyRevision();}return bus;}
    public void watch(ControlPanelMenu menu){watchers.add(menu);var cached=published.get(menu.panel());if(cached!=null)menu.publish(cached);}
    public void unwatch(ControlPanelMenu menu){watchers.remove(menu);}
    public void edit(ControlPanelMenu menu,UUID id,long revision,ControlSettings settings){if(edits.size()<4096)edits.addLast(new Edit(menu,id,revision,settings));else menu.answer(id,"BUSY: APPLY AGAIN");}
    private boolean accessible(ControlPanelMenu menu,UUID id){var owner=facts.panel(menu.panel());return owner!=null&&(owner.id().equals(id)||owner.dcs()&&bus().connected(owner.id(),id));}
    public void tick(long tick){
        if(tick%CADENCE!=0)return;
        if(facts.panels().isEmpty()&&watchers.isEmpty()&&edits.isEmpty()&&valvePlans.isEmpty()&&heatPlans.isEmpty())return;
        bus();
        if(driveRevision!=busRevision){driveRevision=busRevision;world.shafts().dataChanged();}
        while(!edits.isEmpty()){
            var edit=edits.removeFirst();var panel=facts.panel(edit.id());
            if(!edit.menu().stillValid(edit.menu().player())||!edit.menu().player().mayBuild()||!accessible(edit.menu(),edit.id())||panel==null||panel.dcs()||panel.revision()!=edit.revision()){edit.menu().answer(edit.id(),"SETTINGS CHANGED: APPLY AGAIN");continue;}
            var before=panel.settings();var after=edit.settings();
            if(!Objects.equals(before.sensor(),after.sensor())||before.output()!=after.output()||before.action()!=after.action()||before.enabled()!=after.enabled())levels.remove(panel.id());
            facts.panel(panel.edit(after));
            edit.menu().answer(edit.id(),"APPLIED");
        }
        var sensors=new HashMap<UUID,Sensor.Installed>();world.instruments().allSensors().forEach(s->{facts.identify(s);sensors.put(s.instance(),s);});
        var readings=new HashMap<UUID,Sensor.Reading>();
        var needed=new HashSet<Long>();
        for(var panel:facts.panels())if(!panel.dcs()&&panel.settings().enabled()){var s=sensors.get(panel.settings().sensor());if(s!=null&&wired(panel.id(),s.key().host()))needed.add(s.key().host());}
        for(var menu:watchers)for(var id:bus().connected(menu.panel())){var p=facts.port(id);if(p!=null&&p.host()>0)needed.add(p.host());}
        // A pure read of accepted engine presentation; no replay invokes a solve or advances time.
        for(long host:needed)for(var r:world.instruments().sensorReadings(host,world.view(host)))readings.put(r.instance(),r);
        nextValves=new HashMap<>();nextHeat=new HashMap<>();
        var claims=new HashMap<Long,Integer>();for(var panel:facts.panels())if(!panel.dcs()&&panel.settings().enabled())claims.merge(panel.settings().output(),1,Integer::sum);
        for(var panel:facts.panels().stream().sorted(Comparator.comparing(p->p.id().toString())).toList())if(!panel.dcs()){
            var setting=panel.settings();String state="OFF";
            if(setting.enabled()){
                if(claims.getOrDefault(setting.output(),0)>1)state="OUTPUT CONFLICT";
                else try{state=control(panel,sensors.get(setting.sensor()),readings.get(setting.sensor()),tick);}catch(IllegalArgumentException invalid){state="INVALID SETTINGS";}
                if(!state.equals("OUTPUT RESERVED")&&!nextValves.containsKey(setting.output())&&!nextHeat.containsKey(setting.output())){
                    var held=valvePlans.get(setting.output());var thermal=heatPlans.get(setting.output());
                    if(held!=null){nextValves.put(setting.output(),held);state+=" · LAST SETPOINT";}
                    else if(thermal!=null){nextHeat.put(setting.output(),thermal);state+=" · LAST SETPOINT";}
                }
            }
            status.put(panel.id(),state);
        }
        var changed=new HashSet<Long>(valvePlans.keySet());changed.addAll(nextValves.keySet());changed.removeIf(id->Objects.equals(valvePlans.get(id),nextValves.get(id)));
        var thermalChanged=new HashSet<Long>(heatPlans.keySet());thermalChanged.addAll(nextHeat.keySet());thermalChanged.removeIf(id->Objects.equals(heatPlans.get(id),nextHeat.get(id)));changed.addAll(thermalChanged);
        valvePlans=Map.copyOf(nextValves);heatPlans=Map.copyOf(nextHeat);world.controlInputsChanged(changed);
        // Publication is only built for active requests. Opening a menu only replays this cache.
        watchers.removeIf(m->!m.stillValid(m.player())||m.player().containerMenu!=m);
        for(var menu:watchers){var view=snapshot(menu,sensors,readings);published.put(menu.panel(),view);menu.publish(view);}
    }
    private boolean wired(UUID controller,long host){var port=equipmentPorts.get(host);return port!=null&&bus().connected(controller,port.id());}
    private String control(ControlFacts.Panel panel,Sensor.Installed sensor,Sensor.Reading reading,long tick){
        var s=panel.settings();var record=world.registrations().get(s.output());
        if(sensor==null||record==null)return "MISSING DEVICE";
        if(world.controlReserved(s.output()))return "OUTPUT RESERVED";
        if(!wired(panel.id(),sensor.key().host())||!wired(panel.id(),s.output()))return "NO DATA LINK";
        if(!world.controlCoupled(sensor.key().host(),s.output()))return "NOT SAME PROCESS";
        if(reading==null||reading.availability()!=Instrument.Availability.AVAILABLE)return "NO SIGNAL";
        if(s.unit()!=reading.unit()&&reading.secondaryUnit().filter(u->u==s.unit()).isEmpty())return "UNIT MISMATCH";
        if(sensor.kind()==Sensor.Kind.TEMPERATURE){
            // Native outlet control already solves bypass/fans/firing against the finite heat curves.
            if(record.device().heat()==null||!record.device().heat().controllable()||sensor.key().host()!=s.output())return "SELECT HEAT OUTLET";
            var heat=record.device().heat();var old=heat.control();
            nextHeat.put(s.output(),s.target());
            return "CONTROLLING";
        }
        if(!(record.device().control() instanceof FlowControl.ControlValve law))return "SELECT CONTROL VALVE";
        if(sensor.kind()==Sensor.Kind.LEVEL||sensor.kind()==Sensor.Kind.PRESSURE){var action=world.controlAction(sensor.key().host(),s.output());if(action!=null&&action!=s.action())return "CHECK FLOW DIRECTION";}
        var source=FlowControl.ValveSource.MASS_FLOW;double target=s.target();
        switch(sensor.kind()){
            case FLOW->{
                source=s.unit()==Sensor.Unit.CUBIC_METRE_PER_SECOND?FlowControl.ValveSource.VOLUME_FLOW:FlowControl.ValveSource.MASS_FLOW;
                var output=world.instrumentPipeReading(s.output());
                if(reading.value()< -1e-10)return "REVERSE FLOW";
                if(output!=null&&reading.value()>1e-10&&output.massFlow()>1e-10){
                    double measured=s.unit()==Sensor.Unit.CUBIC_METRE_PER_SECOND?reading.secondary():reading.value();
                    if(measured>1e-12){source=FlowControl.ValveSource.MASS_FLOW;target=s.target()*output.massFlow()/measured;}
                }
            }
            case PRESSURE->{
                if(s.action()!=ControlSettings.Action.OUTLET)return "PRESSURE: OUTLET ACTION";
                var local=world.instrumentPipeReading(s.output());if(local==null)return "NO SIGNAL";
                source=FlowControl.ValveSource.PRESSURE;
                // The native valve holds its upstream node. Correct the intervening line's measured pressure loss.
                target=Math.max(1,local.pressure()+s.target()-reading.value());
            }
            case LEVEL->{
                var view=world.view(sensor.key().host());var state=view.state();var tank=view.tank();
                var output=world.instrumentPipeReading(s.output());
                if(state==null||tank==null||output==null||output.volumeFlow()==null||sensor.key().mount().equals("level_boot"))return "NO LEVEL BALANCE";
                double height=s.unit()==Sensor.Unit.HEIGHT_PERCENT?s.target()*reading.span()/100:s.target();
                if(height>reading.span())return "TARGET OUT OF RANGE";
                double volume=state.phaseVolumes()[0]+state.phaseVolumes()[1]+state.solids().volume();
                double desired=tank.geometry().volumeAt(height),flow=Math.max(0,output.volumeFlow());
                var previous=levels.get(panel.id());double elapsed=0,prior=volume;
                if(previous!=null&&previous.sensor().equals(sensor.instance())&&previous.output()==s.output()&&reading.tick()>previous.tick()){
                    elapsed=(reading.tick()-previous.tick())/20.0;prior=previous.volume();
                }
                // One scheduled interval to recover the inventory error, rather than a tuned integral loop.
                double requested=InventoryControl.target(volume,desired,flow,prior,elapsed,CADENCE/20.0,s.action()==ControlSettings.Action.OUTLET);
                source=FlowControl.ValveSource.VOLUME_FLOW;target=Math.max(0,requested);
                if(previous==null||reading.tick()>previous.tick())levels.put(panel.id(),new LevelSample(sensor.instance(),s.output(),reading.tick(),volume));
            }
            default->{return "READ ONLY SENSOR";}
        }
        double p=source==FlowControl.ValveSource.PRESSURE?target:law.targetPressure();
        double m=source==FlowControl.ValveSource.MASS_FLOW?target:law.massTarget(),v=source==FlowControl.ValveSource.VOLUME_FLOW?target:law.volumeTarget();
        nextValves.put(s.output(),new FlowControl.ControlValve(source,p,m,v,sensor.instance(),panel.revision()));
        return "CONTROLLING";
    }
    private String label(long id){var record=world.registrations().get(id);if(record==null)return "#"+id;if(record.device().control() instanceof FlowControl.ControlValve)return String.format(Locale.ROOT,"CV-%04d",id);var heat=record.device().heat();if(heat!=null)return String.format(Locale.ROOT,"%s-%04d / %s",switch(heat.unit()){case EXCHANGER->"HX";case KETTLE->"RB";case AIR_COOLER->"AC";case FIRED_HEATER->"FH";},id,heat.side());return record.device().kind().name()+" #"+id;}
    private static <T> List<T> page(List<T> values,int requested,int size){int page=Math.clamp(requested,0,Math.max(0,(values.size()-1)/size)),from=page*size;return new ArrayList<>(values.subList(from,Math.min(values.size(),from+size)));}
    private CompoundTag snapshot(ControlPanelMenu menu,Map<UUID,Sensor.Installed> sensors,Map<UUID,Sensor.Reading> readings){
        UUID owner=menu.panel();var root=new CompoundTag();var controllers=new ListTag();var station=facts.panel(owner);
        if(station==null)return root;
        var connected=bus().connected(owner);
        var visible=facts.panels().stream().filter(p->!p.dcs()&&(p.id().equals(owner)||station.dcs()&&connected.contains(p.id()))).sorted(Comparator.comparing(p->p.position().toShortString())).toList();
        root.putInt("controllerPages",Math.max(1,(visible.size()+31)/32));root.putInt("controllerPage",Math.clamp(menu.controllerPage(),0,Math.max(0,(visible.size()-1)/32)));
        for(var p:page(visible,menu.controllerPage(),32)){
            var row=p.save();row.putString("status",status.getOrDefault(p.id(),"WAITING"));row.putString("controllerId","LC-"+p.id().toString().substring(0,8).toUpperCase(Locale.ROOT));var selectedId=facts.sensorId(p.settings().sensor());if(selectedId!=null)row.putString("sensorId",selectedId.label());
            var available=bus().connected(p.id());var hostIds=available.stream().map(facts::port).filter(Objects::nonNull).map(DataPort::host).filter(h->h>0).collect(java.util.stream.Collectors.toSet());
            var inputChoices=sensors.values().stream().filter(s->hostIds.contains(s.key().host())).sorted(Comparator.comparingLong(s->facts.sensorId(s.instance()).number())).toList();
            row.putInt("inputPages",Math.max(1,(inputChoices.size()+63)/64));row.putInt("inputPage",Math.clamp(menu.devicePage(),0,Math.max(0,(inputChoices.size()-1)/64)));
            var inputRows=page(inputChoices,menu.devicePage(),64);inputChoices.stream().filter(s->s.instance().equals(p.settings().sensor())&&!inputRows.contains(s)).findFirst().ifPresent(inputRows::add);
            var inputs=new ListTag();for(var s:inputRows){
                var in=new CompoundTag();in.putUUID("id",s.instance());in.putString("label",facts.sensorId(s.instance()).label()+" / "+s.kind().name());in.putString("kind",s.kind().name());in.putString("unit",Sensor.primaryUnit(s.kind()).name());Sensor.secondaryUnit(s.kind()).ifPresent(u->in.putString("secondUnit",u.name()));
                var r=readings.get(s.instance());if(r!=null){in.putDouble("value",r.value());in.putDouble("secondary",r.secondary());in.putDouble("span",r.span());in.putString("quality",r.availability().name());in.putString("unit",r.unit().name());if(r.secondaryUnit().isPresent())in.putString("secondUnit",r.secondaryUnit().get().name());}inputs.add(in);
            }row.put("inputs",inputs);
            var outputChoices=world.registrations().values().stream().filter(r->hostIds.contains(r.device().id())&&(r.device().control() instanceof FlowControl.ControlValve||r.device().control() instanceof FlowControl.Mover||r.device().heat()!=null&&r.device().heat().controllable())).sorted(Comparator.comparingLong(r->r.device().id())).toList();
            row.putInt("outputPages",Math.max(1,(outputChoices.size()+63)/64));row.putInt("outputPage",Math.clamp(menu.devicePage(),0,Math.max(0,(outputChoices.size()-1)/64)));
            var outputRows=page(outputChoices,menu.devicePage(),64);outputChoices.stream().filter(r->r.device().id()==p.settings().output()&&!outputRows.contains(r)).findFirst().ifPresent(outputRows::add);
            var outputs=new ListTag();for(var r:outputRows){var out=new CompoundTag();out.putLong("id",r.device().id());out.putString("label",label(r.device().id()));outputs.add(out);}row.put("outputs",outputs);controllers.add(row);
        }
        root.put("controllers",controllers);return root;
    }
}
