package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
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
    private final Map<Long,BuiltInInputs.Binding> bindings=new HashMap<>();
    private final Map<Long,java.lang.ref.WeakReference<EquipmentShellBlockEntity>> columnCells=new HashMap<>();
    private final Map<Long,List<ControlInput>> memberInputs=new HashMap<>();
    private final Map<Long,Set<Long>> columnMembers=new HashMap<>();
    private final Map<Long,Long> aliases=new HashMap<>();
    private DataBus bus=new DataBus(List.of(),Map.of());
    private long busRevision=-1,driveRevision=-1;
    private final Set<ControlPanelMenu> watchers=Collections.newSetFromMap(new IdentityHashMap<>());
    private record Published(CompoundTag view,long topology){}
    private final Map<UUID,Published> published=new HashMap<>();
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
    public void loaded(ConduitBlockEntity cable){var existing=facts.cable(cable.cable().site());if(existing!=null)cable.configure(existing);else facts.cable(cable.cable());if(cable.getLevel() instanceof net.minecraft.server.level.ServerLevel server)com.wormzjl.icheme.world.level.block.LinePieces.queue(server.getServer(),cable.cable().site());}
    public void loaded(PipeRackBlockEntity rack){
        var wires=facts.cables().stream().filter(c->c.site().lane()>0&&c.site().dimension().equals(rack.getLevel().dimension().location().toString())&&c.site().cell().equals(rack.getBlockPos())).toList();
        rack.cables(wires);if(rack.getLevel() instanceof net.minecraft.server.level.ServerLevel server)for(var cable:wires)com.wormzjl.icheme.world.level.block.LinePieces.queue(server.getServer(),cable.site());
    }
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
        if(host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var nodes=ShapeRasterizer.fluidCells(shell.shape());var ids=new ArrayList<Long>();
            for(var node:nodes){var at=shell.origin().offset(node.x(),node.y(),node.z());var pos=new PhysicalFluidTopology.Position(shell.getLevel().dimension().location().toString(),at.getX(),at.getY(),at.getZ());ids.add(world.at(pos).map(r->r.device().id()).orElse(0L));}
            if(!ids.isEmpty()&&ids.getFirst()>0){long primary=ids.getFirst();bindings.put(primary,new BuiltInInputs.Binding(primary,shell.shape(),new PhysicalFluidTopology.Position(shell.getLevel().dimension().location().toString(),shell.origin().getX(),shell.origin().getY(),shell.origin().getZ()),ids));for(long id:ids)if(id>0)aliases.put(id,primary);
                if(host.identity()!=primary){if(shell.instruments().dataPort()!=null&&!facts.wired(shell.instruments().dataPort().id())){removeHost(host.identity());shell.instruments().dataPort(null);syncPort(host);}var cell=nodes.getFirst();var pos=shell.origin().offset(cell.x(),cell.y(),cell.z());if(shell.getLevel().getBlockEntity(pos) instanceof EquipmentShellBlockEntity first)bind(new InstrumentHosts.Host(primary,first,List.of(),false));return;}
            }
        }
        var r=world.registrations().get(host.identity());
        boolean actuator=r!=null&&(r.device().control() instanceof FlowControl.ControlValve||r.device().control() instanceof FlowControl.Mover||r.device().heat()!=null);
        var ids=new HashMap<UUID,String>();for(var sensor:host.metadata().sensors())ids.put(sensor.instance(),facts.identify(sensor).label());boolean namesChanged=!ids.equals(host.metadata().sensorIds());host.metadata().sensorIds(ids);
        // A pipe sensor carries its connector. Removing the last sensor removes that connector too;
        // the cable's built geometry and endpoint ID remain as a disconnected terminal.
        if(host.pipe()&&!actuator&&host.metadata().sensors().isEmpty()){
            removeHost(host.identity());
            if(host.metadata().dataPort()!=null||namesChanged){host.metadata().dataPort(null);syncPort(host);}
            return;
        }
        if(!actuator&&host.metadata().sensors().isEmpty()&&host.metadata().dataPort()==null&&!(host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&ShapeRasterizer.services(shell.shape()).data()!=null))return;
        var port=equipmentPorts.get(host.identity());
        if(port==null){port=DataPorts.equipment(host,actuator);facts.port(port);equipmentPorts.put(host.identity(),port);}
        else{var refreshed=DataPorts.refresh(port,host,facts::wired);if(!refreshed.equals(port)){port=refreshed;facts.port(port);equipmentPorts.put(host.identity(),port);}}
        if(namesChanged||!port.equals(host.metadata().dataPort())){
            host.metadata().dataPort(port);syncPort(host);
        }
    }
    private static void syncPort(InstrumentHosts.Host host){
        host.entity().setChanged();InstrumentHosts.loaded(host.entity());
        host.entity().getLevel().sendBlockUpdated(host.entity().getBlockPos(),host.entity().getBlockState(),host.entity().getBlockState(),2);
    }
    public void bindColumn(EquipmentShellBlockEntity cell){
        if(cell.shape()==null||cell.shape().kind()!=EquipmentShape.Kind.COLUMN)return;
        var existing=cell.instruments().dataPort();if(existing==null){var p=ShapeRasterizer.services(cell.shape()).data();var origin=cell.origin();var centre=new net.minecraft.world.phys.Vec3(origin.getX()+p.x(),origin.getY()+p.y(),origin.getZ()+p.z());existing=facts.ports().values().stream().filter(port->port.host()>0&&!port.kind().power()&&!world.registrations().containsKey(port.host())&&world.shafts().unit(port.host())==null&&port.dimension().equals(cell.getLevel().dimension().location().toString())&&port.centre().distanceToSqr(centre)<1).findFirst().orElse(null);}long id=existing==null?world.reserveControlIdentity():existing.host();
        var origin=cell.origin();bindings.put(id,new BuiltInInputs.Binding(id,cell.shape(),new PhysicalFluidTopology.Position(cell.getLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()),List.of()));
        columnCells.put(id,new java.lang.ref.WeakReference<>(cell));bind(new InstrumentHosts.Host(id,cell,List.of(),false));
    }
    public void bindMotor(EquipmentShellBlockEntity cell,ShaftUnit unit) {
        var origin=cell.origin();bindings.put(unit.id(),new BuiltInInputs.Binding(unit.id(),cell.shape(),new PhysicalFluidTopology.Position(cell.getLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()),List.of()));
        var port=equipmentPorts.get(unit.id());
        if(port==null){port=DataPorts.motor(cell,unit);facts.port(port);equipmentPorts.put(unit.id(),port);}
        else{var host=new InstrumentHosts.Host(unit.id(),cell,List.of(),false);var refreshed=DataPorts.refresh(port,host,facts::wired);if(!refreshed.equals(port)){port=refreshed;facts.port(port);equipmentPorts.put(unit.id(),port);}}
        if(!port.equals(cell.instruments().dataPort())) {
            cell.instruments().dataPort(port);cell.setChanged();InstrumentHosts.loaded(cell);
            cell.getLevel().sendBlockUpdated(cell.getBlockPos(),cell.getBlockState(),cell.getBlockState(),2);
        }
    }
    /** Uses the cached assembly report and registration positions; never traverses pipes or loads a chunk. */
    private void refreshColumnMembers(){
        for(var entry:columnCells.entrySet()){
            var cell=entry.getValue().get();if(cell==null||cell.isRemoved()||!(cell.getLevel() instanceof net.minecraft.server.level.ServerLevel level))continue;
            var stored=com.wormzjl.icheme.runtime.assembly.AssemblyService.entry(level,cell.origin());if(stored==null||stored.report()==null){memberInputs.remove(entry.getKey());columnMembers.remove(entry.getKey());continue;}
            var inputs=new ArrayList<ControlInput>();var members=new HashSet<Long>();
            for(var slot:stored.report().slots())for(long placed:slot.units()){
                var pos=BlockPos.of(placed);if(!level.hasChunkAt(pos))continue;var vessel=EquipmentStructure.find(level,pos);if(vessel==null)continue;var ids=new ArrayList<Long>();
                for(var node:ShapeRasterizer.fluidCells(vessel.shape())){var at=vessel.origin().offset(node.x(),node.y(),node.z());var where=new PhysicalFluidTopology.Position(level.dimension().location().toString(),at.getX(),at.getY(),at.getZ());ids.add(world.at(where).map(r->r.device().id()).orElse(0L));}
                long primary=ids.stream().filter(i->i>0).findFirst().orElse(0L);if(primary==0)continue;members.addAll(ids.stream().filter(i->i>0).toList());
                var binding=new BuiltInInputs.Binding(primary,vessel.shape(),new PhysicalFluidTopology.Position(level.dimension().location().toString(),vessel.origin().getX(),vessel.origin().getY(),vessel.origin().getZ()),ids);
                bindings.put(primary,binding);for(long node:ids)if(node>0)aliases.put(node,primary);String prefix=slot.slot().part()+"_"+(slot.slot().index()+1)+"_EQ"+primary+"_";
                for(var input:BuiltInInputs.member(binding))inputs.add(ControlInput.builtIn(entry.getKey(),input.source(),prefix+input.channel(),input.kind(),input.role()));
            }
            memberInputs.put(entry.getKey(),List.copyOf(inputs));columnMembers.put(entry.getKey(),Set.copyOf(members));
        }
    }
    /** Connected physical host IDs, independent of chunk loading and menu subscriptions. */
    public Set<Long> connectedHosts(long host) {
        var port=equipmentPorts.get(aliases.getOrDefault(host,host));if(port==null)return Set.of();
        var result=new HashSet<Long>();for(var id:bus().connected(port.id())){var p=facts.port(id);if(p!=null&&p.host()>0&&p.host()!=host)result.add(p.host());}for(var a:aliases.entrySet())if(result.contains(a.getValue()))result.add(a.getKey());return Set.copyOf(result);
    }
    public void removeHost(long host){bindings.remove(host);columnCells.remove(host);memberInputs.remove(host);columnMembers.remove(host);aliases.entrySet().removeIf(a->a.getKey()==host||a.getValue()==host);var port=equipmentPorts.remove(host);if(port!=null)facts.removePort(port.id());}
    private DataBus bus(){if(busRevision!=facts.topologyRevision()){bus=new DataBus(facts.cables(),facts.ports());busRevision=facts.topologyRevision();}return bus;}
    public void watch(ControlPanelMenu menu){watchers.add(menu);var cached=published.get(menu.panel());if(cached!=null&&cached.topology()==facts.topologyRevision())menu.publish(cached.view());}
    public void unwatch(ControlPanelMenu menu){watchers.remove(menu);}
    public void edit(ControlPanelMenu menu,UUID id,long revision,ControlSettings settings){if(edits.size()<4096)edits.addLast(new Edit(menu,id,revision,settings));else menu.answer(id,"BUSY: APPLY AGAIN");}
    private boolean accessible(ControlPanelMenu menu,UUID id){var owner=facts.panel(menu.panel());return owner!=null&&(owner.id().equals(id)||owner.dcs()&&bus().connected(owner.id(),id));}
    public void tick(long tick){
        if(tick%CADENCE!=0)return;
        if(facts.panels().isEmpty()&&watchers.isEmpty()&&edits.isEmpty()&&valvePlans.isEmpty()&&heatPlans.isEmpty())return;
        bus();
        if(driveRevision!=busRevision){driveRevision=busRevision;world.shafts().dataChanged();}
        refreshColumnMembers();
        var sensors=new HashMap<UUID,ControlInput>();world.instruments().allSensors().forEach(s->{facts.identify(s);sensors.put(s.instance(),ControlInput.installed(s));});
        for(var binding:bindings.values())for(var input:BuiltInInputs.channels(binding))sensors.put(input.instance(),input);
        for(var entry:equipmentPorts.entrySet())if(!bindings.containsKey(entry.getKey())){var r=world.registrations().get(entry.getKey());if(r!=null&&r.device().control() instanceof FlowControl.Mover)for(var input:BuiltInInputs.inline(entry.getKey()))sensors.put(input.instance(),input);}
        for(var channels:memberInputs.values())for(var input:channels)sensors.put(input.instance(),input);
        while(!edits.isEmpty()){
            var edit=edits.removeFirst();var panel=facts.panel(edit.id());
            if(!edit.menu().stillValid(edit.menu().player())||!edit.menu().player().mayBuild()||!accessible(edit.menu(),edit.id())||panel==null||panel.dcs()||panel.revision()!=edit.revision()){edit.menu().answer(edit.id(),"SETTINGS CHANGED: APPLY AGAIN");continue;}
            var before=panel.settings();var after=edit.settings();
            var sensor=sensors.get(after.sensor());
            if(after.enabled()&&(sensor==null||!wired(panel.id(),sensor.key().host())||!wired(panel.id(),after.output()))){edit.menu().answer(edit.id(),"NO DATA LINK");continue;}
            if(!Objects.equals(before.sensor(),after.sensor())||before.output()!=after.output()||before.action()!=after.action()||before.enabled()!=after.enabled())levels.remove(panel.id());
            facts.panel(panel.edit(after));
            edit.menu().answer(edit.id(),"APPLIED");
        }
        var readings=new HashMap<UUID,Sensor.Reading>();
        var needed=new HashSet<Long>();
        for(var panel:facts.panels())if(!panel.dcs()&&panel.settings().enabled()){var s=sensors.get(panel.settings().sensor());if(s!=null&&wired(panel.id(),s.key().host()))needed.add(s.key().host());}
        for(var menu:watchers)for(var id:bus().connected(menu.panel())){var p=facts.port(id);if(p!=null&&p.host()>0)needed.add(p.host());}
        // A pure read of accepted engine presentation; no replay invokes a solve or advances time.
        var views=new HashMap<Long,FluidView>();
        for(long host:needed)for(var r:world.instruments().sensorReadings(host,views.computeIfAbsent(host,world::view)))readings.put(r.instance(),r);
        for(var input:sensors.values())if(input.physical()==null&&needed.contains(input.host()))readings.put(input.instance(),BuiltInInputs.read(input,world,bindings.getOrDefault(aliases.getOrDefault(input.source(),input.source()),bindings.get(input.host())),views));
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
        for(var menu:watchers){var view=snapshot(menu,sensors,readings);published.put(menu.panel(),new Published(view,facts.topologyRevision()));menu.publish(view);}
    }
    private boolean wired(UUID controller,long host){for(var entry:columnMembers.entrySet())if(entry.getValue().contains(host)){var primary=equipmentPorts.get(entry.getKey());if(primary!=null&&bus().connected(controller,primary.id()))return true;}var port=equipmentPorts.get(aliases.getOrDefault(host,host));return port!=null&&bus().connected(controller,port.id());}
    private String control(ControlFacts.Panel panel,ControlInput sensor,Sensor.Reading reading,long tick){
        var s=panel.settings();var record=world.registrations().get(s.output());
        if(sensor==null||record==null)return "MISSING DEVICE";
        if(world.controlReserved(s.output()))return "OUTPUT RESERVED";
        if(!wired(panel.id(),sensor.key().host())||!wired(panel.id(),s.output()))return "NO DATA LINK";
        if(!world.controlCoupled(sensor.source(),s.output()))return "NOT SAME PROCESS";
        if(reading==null||reading.availability()!=Instrument.Availability.AVAILABLE)return "NO SIGNAL";
        if(sensor.physical()==null&&s.unit()==Sensor.Unit.CUBIC_METRE_PER_SECOND&&!BuiltInInputs.volumeAvailable(sensor))return "UNIT UNAVAILABLE";
        if(s.unit()!=reading.unit()&&reading.secondaryUnit().filter(u->u==s.unit()).isEmpty())return "UNIT MISMATCH";
        if(sensor.kind()==Sensor.Kind.TEMPERATURE){
            // Native outlet control already solves bypass/fans/firing against the finite heat curves.
            if(record.device().heat()==null||!record.device().heat().controllable()||sensor.source()!=s.output()||sensor.physical()==null&&!sensor.role().endsWith("OUTLET"))return "SELECT HEAT OUTLET";
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
    /** Structural location only; a cached menu publication never loads a chunk or samples the process. */
    private void sensorLocation(CompoundTag tag,ControlInput sensor){
        if(sensor.physical()==null){var binding=bindings.get(aliases.getOrDefault(sensor.source(),sensor.source()));if(binding==null)binding=bindings.get(sensor.host());if(binding!=null){tag.putLong("equipment",binding.host());var o=binding.origin();double[] point=null;if(sensor.role().startsWith("HEAT:")){String probe="temperature_"+sensor.role().substring(5).toLowerCase(Locale.ROOT);point=EquipmentInstruments.of(binding.shape()).probes().stream().filter(p->p.id().equals(probe)).map(EquipmentInstruments.Probe::centre).findFirst().orElse(null);}var position=point==null?new BlockPos(o.x(),o.y(),o.z()):BlockPos.containing(o.x()+point[0],o.y()+point[1],o.z()+point[2]);tag.putLong("position",position.asLong());tag.putBoolean("mountLocated",point!=null);return;}}
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
    private CompoundTag snapshot(ControlPanelMenu menu,Map<UUID,ControlInput> sensors,Map<UUID,Sensor.Reading> readings){
        UUID owner=menu.panel();var root=new CompoundTag();var controllers=new ListTag();var station=facts.panel(owner);
        if(station==null)return root;
        var connected=bus().connected(owner);
        var visible=facts.panels().stream().filter(p->!p.dcs()&&(p.id().equals(owner)||station.dcs()&&connected.contains(p.id()))).sorted(Comparator.comparing(p->p.position().toShortString())).toList();
        root.putInt("controllerPages",Math.max(1,(visible.size()+31)/32));root.putInt("controllerPage",Math.clamp(menu.controllerPage(),0,Math.max(0,(visible.size()-1)/32)));
        for(var p:page(visible,menu.controllerPage(),32)){
            var row=p.save();row.putString("status",status.getOrDefault(p.id(),"WAITING"));row.putString("controllerId","LC-"+p.id().toString().substring(0,8).toUpperCase(Locale.ROOT));
            var hostIds=new HashSet<>(bus().hosts(p.id(),facts::port));for(var a:aliases.entrySet())if(hostIds.contains(a.getValue()))hostIds.add(a.getKey());for(var entry:columnMembers.entrySet())if(hostIds.contains(entry.getKey()))hostIds.addAll(entry.getValue());
            var inputChoices=sensors.values().stream().filter(s->hostIds.contains(s.key().host())).sorted(Comparator.comparing(s->s.label(facts))).toList();
            row.putInt("inputPages",Math.max(1,(inputChoices.size()+63)/64));row.putInt("inputPage",Math.clamp(menu.devicePage(),0,Math.max(0,(inputChoices.size()-1)/64)));
            var inputRows=page(inputChoices,menu.devicePage(),64);inputChoices.stream().filter(s->s.instance().equals(p.settings().sensor())&&!inputRows.contains(s)).findFirst().ifPresent(inputRows::add);
            var inputs=new ListTag();for(var s:inputRows){
                var in=new CompoundTag();in.putUUID("id",s.instance());in.putString("label",s.label(facts));sensorLocation(in,s);in.putString("kind",s.kind().name());in.putString("unit",Sensor.primaryUnit(s.kind()).name());Sensor.secondaryUnit(s.kind()).ifPresent(u->in.putString("secondUnit",u.name()));
                var r=readings.get(s.instance());if(r!=null){in.putDouble("value",r.value());in.putDouble("secondary",r.secondary());in.putDouble("span",r.span());in.putString("quality",r.availability().name());in.putString("unit",r.unit().name());if(r.secondaryUnit().isPresent())in.putString("secondUnit",r.secondaryUnit().get().name());}if(s.physical()==null&&!BuiltInInputs.volumeAvailable(s))in.remove("secondUnit");inputs.add(in);
            }row.put("inputs",inputs);
            var outputChoices=world.registrations().values().stream().filter(r->hostIds.contains(r.device().id())&&(r.device().control() instanceof FlowControl.ControlValve||r.device().heat()!=null&&r.device().heat().controllable())).sorted(Comparator.comparingLong(r->r.device().id())).toList();
            row.putInt("outputPages",Math.max(1,(outputChoices.size()+63)/64));row.putInt("outputPage",Math.clamp(menu.devicePage(),0,Math.max(0,(outputChoices.size()-1)/64)));
            var outputRows=page(outputChoices,menu.devicePage(),64);outputChoices.stream().filter(r->r.device().id()==p.settings().output()&&!outputRows.contains(r)).findFirst().ifPresent(outputRows::add);
            var outputs=new ListTag();for(var r:outputRows){var out=new CompoundTag();out.putLong("id",r.device().id());out.putString("label",label(r.device().id()));outputs.add(out);}row.put("outputs",outputs);controllers.add(row);
        }
        root.put("controllers",controllers);return root;
    }
}
