package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.world.item.*;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Server-thread attachment authority. Static attachment transactions commit immediately; live readings and hydraulic edits retain the engine schedule. */
public final class InstrumentService {
    private final MinecraftServer server;
    private final FluidWorldAuthority world;
    private final Store store;
    private record SensorOperation(UUID player,Instrument.Key key,Sensor.Kind kind,ItemStack held,InteractionHand hand,boolean remove,long revision,String dimension,long position){}
    private final Map<Instrument.Key,SensorOperation> sensorOperations=new LinkedHashMap<>();
    private final Map<UUID,ItemStack> exactStacks=new HashMap<>();
    private final Map<UUID,Set<FluidNetwork.InstrumentTarget>> watchers=new HashMap<>();
    private final Map<UUID,Sensor.Reading> sensorSamples=new HashMap<>();
    private final Map<UUID,Instrument.Sample> samples=new HashMap<>();

    /** {@code store} is the overworld's, opened by {@link Store#open} before the fluid authority starts anything. */
    public InstrumentService(MinecraftServer server,FluidWorldAuthority world,Store store){this.server=server;this.world=world;this.store=store;}
    public boolean occupied(long host){return !store.sensors(host).isEmpty()||!store.host(host).isEmpty()||store.pending().stream().anyMatch(p->p.key().host()==host&&p.install());}
    public boolean observes(long host){return watchers.values().stream().anyMatch(set->set.stream().anyMatch(t->t.key().host()==host));}
    public void request(ServerPlayer player,InteractionHand hand,boolean remove){if(remove&&requestSensorRemoval(player,hand))return;if(player.isSpectator()||!player.mayBuild())return;var held=player.getItemInHand(hand);GaugeSettings.Kind kind=held.getItem() instanceof GaugeItem g?g.kind():null;if(!remove&&kind==null||remove&&!held.is(ModItems.ACCESSORY_WRENCH.get()))return;double reach=player.blockInteractionRange();var hosts=InstrumentHosts.nearby(player.level(),player.getEyePosition(),8);var selected=InstrumentHosts.select(hosts,player.getEyePosition(),player.getLookAngle(),reach,kind,remove);if(!remove){var pipeHit=InstrumentHosts.pipeSelection(player,kind);if(pipeHit!=null)selected=pipeHit;}
        InstrumentHosts.Host host;String mount;UUID instance;Instrument.Type type;GaugeSettings settings=null;
        if(selected!=null){host=selected.host();mount=selected.mount().id();if(remove){var installed=store.get(new Instrument.Key(host.identity(),mount));if(installed==null)return;instance=installed.instance();type=installed.settings().kind()==GaugeSettings.Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE;}else{if(host.occupied(selected.mount())||!InstrumentHosts.clear(player.level(),host,selected.mount())||host.pipe()&&occupied(host.identity()))return;settings=held.get(ModItems.GAUGE_SETTINGS.get());if(settings==null||settings.kind()!=kind)return;instance=UUID.randomUUID();type=kind==GaugeSettings.Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE;}}
        else if(remove){host=null;InstrumentHosts.PipeAccessory selectedLegacy=null;double nearest=reach;for(var candidate:hosts){if(!(candidate.entity() instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe))continue;var legacy=InstrumentHosts.PipeAccessory.of(pipe);if(legacy==null)continue;var hit=legacy.bounds().clip(player.getEyePosition(),player.getEyePosition().add(player.getLookAngle().scale(reach)));if(hit.isPresent()&&hit.get().distanceTo(player.getEyePosition())<=nearest&&InstrumentHosts.reachable(player,candidate,legacy)){host=candidate;selectedLegacy=legacy;nearest=hit.get().distanceTo(player.getEyePosition());}}if(host==null)return;type=InstrumentHosts.legacy(host.entity().getBlockState());mount=selectedLegacy.id();var fitting=store.legacy(host.identity());if(fitting==null)return;instance=fitting.instance();}
        else return;
        if(!permission(player,host,held))return;var p=new Store.Pending(UUID.randomUUID(),player.getUUID(),new Instrument.Key(host.identity(),mount),instance,store.revision(host.identity()),!remove,type,settings,player.level().dimension().location().toString(),host.entity().getBlockPos().asLong(),player.getInventory().selected,hand==InteractionHand.OFF_HAND);if(store.queue(p)){exactStacks.put(p.request(),held);commit(player,player.serverLevel(),p);store.finish(p.request());exactStacks.remove(p.request());}
    }
    private static boolean permission(ServerPlayer player,InstrumentHosts.Host host,ItemStack held){var pos=host.entity().getBlockPos();return player.mayBuild()&&!player.isSpectator()&&player.serverLevel().mayInteract(player,pos)&&player.mayUseItemAt(pos,Direction.UP,held);}
    public void tick(long tick){if(store.nextDue()==0)store.nextDue(tick+100);if(tick<store.nextDue())return;store.nextDue(tick+100);commitSensors();recoverInvalidSensors();for(var p:store.pending()){var player=server.getPlayerList().getPlayer(p.player());if(player==null)continue;var level=level(p.dimension());if(level==null||!level.hasChunkAt(BlockPos.of(p.position())))continue;commit(player,level,p);store.finish(p.request());exactStacks.remove(p.request());}for(var i:store.installed()){var h=world.instrumentHost(i.key().host());if(h==null)continue;var m=h.mount(i.key().mount());if(m==null||!m.kinds().contains(i.settings().kind())||h.pipe()&&InstrumentHosts.legacy(h.entity().getBlockState())!=null){var removed=store.remove(i.key(),i.instance());if(removed!=null){refund(null,h.entity().getLevel(),h.entity().getBlockPos(),item(removed));samples.remove(i.instance());sync(h);}}}prune();}
    private void commit(ServerPlayer player,ServerLevel level,Store.Pending p){var host=InstrumentHosts.at(level,BlockPos.of(p.position()));if(host==null||host.identity()!=p.key().host()||player.serverLevel()!=level||store.revision(host.identity())!=p.expectedRevision())return;var hand=p.offhand()?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;var held=player.getItemInHand(hand);if(!permission(player,host,held))return;if(!p.offhand()&&player.getInventory().selected!=p.slot()||exactStacks.containsKey(p.request())&&held!=exactStacks.get(p.request()))return;var mount=host.mount(p.key().mount());if(p.install()){if((!(held.getItem() instanceof GaugeItem gauge)||gauge.kind()!=p.settings().kind())||!p.settings().equals(held.get(ModItems.GAUGE_SETTINGS.get()))||mount==null||host.occupied(mount)||!InstrumentHosts.clear(level,host,mount)||!mount.kinds().contains(p.settings().kind())||host.pipe()&&!store.host(host.identity()).isEmpty()||!(host.pipe()?InstrumentHosts.pipeReachable(player,host):InstrumentHosts.reachable(player,host,mount)))return;if(store.install(p)==null)return;if(!player.isCreative())held.shrink(1);}
        else {if(!held.is(ModItems.ACCESSORY_WRENCH.get()))return;if(p.type()==Instrument.Type.PRESSURE||p.type()==Instrument.Type.TEMPERATURE){if(mount==null||!InstrumentHosts.reachable(player,host,mount))return;var removed=store.remove(p.key(),p.instance());if(removed==null)return;refund(player,level,host.entity().getBlockPos(),item(removed));samples.remove(removed.instance());}else{if(InstrumentHosts.legacy(host.entity().getBlockState())!=p.type()||!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe)||InstrumentHosts.PipeAccessory.of(pipe)==null||!InstrumentHosts.reachable(player,host,InstrumentHosts.PipeAccessory.of(pipe))||store.legacy(host.identity())==null||!store.legacy(host.identity()).instance().equals(p.instance())||!world.removePipeAccessory(host.identity()))return;var recovered=store.removeLegacy(host.identity(),p.instance());if(recovered==null)return;recoverSensors(host.identity(),player,level,host.entity().getBlockPos(),true);clearLegacy(host);refund(player,level,host.entity().getBlockPos(),item(recovered.type()));}}
        sync(host);world.markViewDirty(host.identity());player.inventoryMenu.broadcastChanges();
    }
    public void requestSensor(ServerPlayer player,InteractionHand hand,Sensor.Kind kind){
        if(player.isSpectator()||!player.mayBuild())return;
        var hosts=InstrumentHosts.nearby(player.level(),player.getEyePosition(),8);
        InstrumentHosts.Host chosen=null;Instrument.Mount mount=null;double nearest=player.blockInteractionRange();
        var eye=player.getEyePosition();var end=eye.add(player.getLookAngle().scale(nearest));
        for(var host:hosts)for(var m:Sensor.mounts(host,kind)){var hit=m.bounds().clip(eye,end);if(hit.isPresent()&&hit.get().distanceTo(eye)<=nearest){chosen=host;mount=m;nearest=hit.get().distanceTo(eye);}}
        if(chosen==null&&player.pick(player.blockInteractionRange(),0,false) instanceof net.minecraft.world.phys.BlockHitResult hit){
            var host=InstrumentHosts.at(player.level(),hit.getBlockPos());if(host!=null){var mounts=Sensor.mounts(host,kind);if(!mounts.isEmpty()){chosen=host;mount=mounts.stream().min(Comparator.comparingDouble(m->m.centre().distanceToSqr(eye))).orElseThrow();}}
        }
        if(chosen==null||mount==null||!permission(player,chosen,player.getItemInHand(hand)))return;
        queueSensor(player,hand,chosen,mount,kind,false);
    }
    private boolean requestSensorRemoval(ServerPlayer player,InteractionHand hand){
        if(!player.getItemInHand(hand).is(ModItems.ACCESSORY_WRENCH.get())||player.isSpectator()||!player.mayBuild())return false;
        double reach=player.blockInteractionRange();var eye=player.getEyePosition();var end=eye.add(player.getLookAngle().scale(reach));
        for(var host:InstrumentHosts.nearby(player.level(),eye,8))for(var sensor:store.sensors(host.identity()))for(var mount:Sensor.mounts(host,sensor.kind())){
            if(!mount.id().equals(sensor.key().mount())||mount.bounds().clip(eye,end).isEmpty())continue;
            queueSensor(player,hand,host,mount,sensor.kind(),true);return true;
        }
        return false;
    }
    private void queueSensor(ServerPlayer player,InteractionHand hand,InstrumentHosts.Host host,Instrument.Mount mount,Sensor.Kind kind,boolean remove){
        var key=new Instrument.Key(host.identity(),mount.id());
        if(sensorOperations.size()>=4096||sensorOperations.containsKey(key))return;
        sensorOperations.put(key,new SensorOperation(player.getUUID(),key,kind,player.getItemInHand(hand),hand,remove,store.revision(host.identity()),player.level().dimension().location().toString(),host.entity().getBlockPos().asLong()));
    }
    private void commitSensors(){
        var operations=List.copyOf(sensorOperations.values());sensorOperations.clear();
        for(var op:operations){
            var player=server.getPlayerList().getPlayer(op.player());var level=level(op.dimension());if(player==null||level==null||player.level()!=level||player.getItemInHand(op.hand())!=op.held())continue;
            var host=InstrumentHosts.at(level,BlockPos.of(op.position()));
            if(host==null||host.identity()!=op.key().host()||store.revision(host.identity())!=op.revision()||!permission(player,host,op.held()))continue;
            var mount=Sensor.mounts(host,op.kind()).stream().filter(m->m.id().equals(op.key().mount())).findFirst().orElse(null);
            if(mount==null||!InstrumentHosts.reachable(player,host,mount))continue;
            var old=store.sensor(op.key());Sensor.Installed value;
            if(op.remove()){
                if(!op.held().is(ModItems.ACCESSORY_WRENCH.get())||old==null)continue;
                value=store.removeSensor(op.key(),old.instance());if(value==null)continue;sensorSamples.remove(value.instance());refund(player,level,host.entity().getBlockPos(),sensorItem(value.kind()));
            }else{
                if(!(op.held().getItem() instanceof SensorItem item)||item.kind()!=op.kind()||!InstrumentHosts.clear(level,host,mount))continue;
                value=store.installSensor(op.key(),UUID.randomUUID(),op.kind(),op.revision());if(value==null)continue;
                if(!player.isCreative())op.held().shrink(1);
            }
            if(op.key().mount().equals("valve_sensor")){
                var record=world.registrations().get(host.identity());
                if(record!=null){var source=op.remove()?com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.NONE:op.kind()==Sensor.Kind.PRESSURE?com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.PRESSURE:com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW;
                    double target=source==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW?.01:200000;
                    if(record.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve previous&&(op.remove()||previous.source()==source))target=previous.target();
                    var next=record.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve previous?previous.withSource(op.remove()?previous.source():source,op.remove()?null:value.instance(),store.revision(host.identity())):new com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve(source,target,op.remove()?null:value.instance(),store.revision(host.identity()));
                    editSensorValve(record,next);
                }
            }
            if(op.kind()==Sensor.Kind.FLOW){world.flowmeter(host.identity(),!op.remove());metered(host);}
            sync(host);world.markViewDirty(host.identity());player.inventoryMenu.broadcastChanges();
        }
    }
    private void editSensorValve(WorldTopologyLedger.Registration record,com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve control){
        var d=record.device();world.edit(d.id(),record.revision(),d.withControl(control),record.spec());
    }
    private void recoverSensors(long host,ServerPlayer player,Level level,BlockPos position,boolean drop){for(var sensor:store.sensors(host))if(store.removeSensor(sensor.key(),sensor.instance())!=null){sensorSamples.remove(sensor.instance());if(sensor.kind()==Sensor.Kind.FLOW)world.forgetFlowmeter(host);if(drop)refund(player,level,position,sensorItem(sensor.kind()));}}
    private void recoverInvalidSensors(){for(var sensor:store.allSensors()){
        var host=world.instrumentHost(sensor.key().host());if(host==null)continue;
        if(Sensor.mounts(host,sensor.kind()).stream().anyMatch(m->m.id().equals(sensor.key().mount())))continue;
        if(store.removeSensor(sensor.key(),sensor.instance())!=null){sensorSamples.remove(sensor.instance());if(sensor.kind()==Sensor.Kind.FLOW){world.flowmeter(host.identity(),false);metered(host);}refund(null,host.entity().getLevel(),host.entity().getBlockPos(),sensorItem(sensor.kind()));sync(host);world.markViewDirty(host.identity());}
    }}
    public static ItemStack sensorItem(Sensor.Kind kind){return new ItemStack(switch(kind){case PRESSURE->ModItems.PRESSURE_PROBE.get();case TEMPERATURE->ModItems.TEMPERATURE_PROBE.get();case FLOW->ModItems.FLOWMETER.get();case LEVEL->ModItems.DIGITAL_LEVEL_METER.get();});}
    public List<Sensor.Reading> sensorReadings(long host,FluidView view){var pipe=world.instrumentPipeReading(host);return store.sensors(host).stream().map(s->{var reading=Sensor.read(s,view,pipe,sensorSamples.get(s.instance()));sensorSamples.put(s.instance(),reading);return reading;}).toList();}
    private ServerLevel level(String name){return server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(name)));}
    /** A flowmeter installed or removed (W8): an assembly whose walk read that pipe reverts, as for a fitting. */
    private static void metered(InstrumentHosts.Host host){if(host.entity().getLevel() instanceof net.minecraft.server.level.ServerLevel level)com.wormzjl.icheme.runtime.assembly.AssemblyService.metered(level,host.entity().getBlockPos());}
    private void sync(InstrumentHosts.Host host){host.metadata().sensors(store.sensors(host.identity()));host.metadata().set(store.host(host.identity()),store.revision(host.identity()));host.entity().setChanged();InstrumentHosts.loaded(host.entity());var l=host.entity().getLevel();var pos=host.entity().getBlockPos();l.sendBlockUpdated(pos,host.entity().getBlockState(),host.entity().getBlockState(),Block.UPDATE_CLIENTS);}
    /** Saved registrations include unloaded valves; reconciliation queues edits without touching their chunks. */
    public void reconcileLegacyValves(){for(var record:List.copyOf(world.registrations().values()))if(record.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.PressureValve legacy)editSensorValve(record,store.convertLegacyValve(record.device().id(),legacy));}
    public void bind(InstrumentHosts.Host host){if(host==null)return;
        var record=world.registrations().get(host.identity());
        if(host.pipe()&&record!=null&&record.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.PressureValve legacy){
            var control=store.convertLegacyValve(host.identity(),legacy);
            editSensorValve(record,control);
        }
        if(!host.metadata().sensors().equals(store.sensors(host.identity()))||!host.metadata().installed().equals(store.host(host.identity()))||host.metadata().revision()!=store.revision(host.identity()))sync(host);
    }
    public void recoverHost(long id){var host=world.instrumentHost(id);if(host!=null)recover(id,host.entity().getLevel(),host.entity().getBlockPos(),host.pipe()?InstrumentHosts.legacy(host.entity().getBlockState()):null,host.metadata().recoverOnBreak());}
    public void recover(long id,Level level,BlockPos position,Instrument.Type legacy,boolean drop){recoverSensors(id,null,level,position,drop);for(var i:store.host(id))if(store.remove(i.key(),i.instance())!=null){if(drop)refund(null,level,position,item(i));samples.remove(i.instance());}var fitting=store.legacy(id);if(fitting!=null&&store.removeLegacy(id,fitting.instance())!=null&&drop)refund(null,level,position,item(fitting.type()));forget(id);}
    public void legacyInstalled(long host,Instrument.Type type){store.installLegacy(host,type);if(type==Instrument.Type.PRESSURE_VALVE){store.modular.add(host);store.setDirty();}bind(world.instrumentHost(host));}
    public void recoverLegacy(long host,Level level,BlockPos position){var old=store.legacy(host);if(old!=null&&store.removeLegacy(host,old.instance())!=null){refund(null,level,position,item(old.type()));bind(world.instrumentHost(host));}}
    private static void clearLegacy(InstrumentHosts.Host host){var e=host.entity();var s=e.getBlockState().setValue(FluidDeviceBlock.SIGHT_GLASS,false).setValue(FluidDeviceBlock.SHUTOFF,FluidDeviceBlock.Shutoff.NONE).setValue(FluidDeviceBlock.PRESSURE_CONTROL,false).setValue(FluidDeviceBlock.INLINE_FILTER,false).setValue(FluidDeviceBlock.INLINE_MOVER,FluidDeviceBlock.InlineMover.NONE);s=FluidDeviceBlock.connectedState(s,e.getLevel(),e.getBlockPos());e.getLevel().setBlock(e.getBlockPos(),s,Block.UPDATE_ALL);if(e instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe)pipe.refreshGeometry(pipe.bore(),true);}
    public static ItemStack item(Instrument.Installed i){var stack=item(i.settings().kind()==GaugeSettings.Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE);stack.set(ModItems.GAUGE_SETTINGS.get(),i.settings());return stack;}
    public static ItemStack item(Instrument.Type type){return new ItemStack(switch(type){case PRESSURE->ModItems.PRESSURE_GAUGE.get();case TEMPERATURE->ModItems.TEMPERATURE_GAUGE.get();case SIGHT_GLASS->ModItems.PIPELINE_SIGHT_GLASS.get();case SHUTOFF->ModItems.PIPELINE_SHUTOFF_VALVE.get();case CHECK->ModItems.PIPELINE_CHECK_VALVE.get();case PRESSURE_VALVE->ModItems.PRESSURE_CONTROL_VALVE.get();case FILTER->ModItems.INLINE_FILTER.get();case PUMP->ModItems.FLUID_PUMP.get();case COMPRESSOR->ModItems.FLUID_COMPRESSOR.get();});}
    private static void refund(ServerPlayer player,Level level,BlockPos pos,ItemStack stack){if(player!=null){if(player.getInventory().add(stack))return;player.drop(stack,false);}else Block.popResource(level,pos,stack);}
    public void watch(ServerPlayer player,List<FluidNetwork.InstrumentTarget> add,List<FluidNetwork.InstrumentTarget> remove){var set=watchers.computeIfAbsent(player.getUUID(),k->new HashSet<>());for(var t:remove){set.remove(t);world.instrumentObservationChanged(t.key().host());}for(var t:add){if(set.size()>=64||!valid(player,t))continue;set.add(t);world.instrumentObservationChanged(t.key().host());var sample=samples.get(t.instance());if(sample!=null)FluidNetwork.sendInstrument(player,t,sample,world.onlineTick());}}
    private boolean valid(ServerPlayer p,FluidNetwork.InstrumentTarget t){var i=store.get(t.key());var host=world.instrumentHost(t.key().host());var mount=host==null?null:host.mount(t.key().mount());return i!=null&&i.instance().equals(t.instance())&&host!=null&&host.entity().getLevel()==p.serverLevel()&&host.entity().getBlockPos().equals(t.position())&&mount!=null&&mount.centre().distanceToSqr(p.position())<=128*128;}
    private void prune(){var hosts=new HashSet<Long>();for(var it=watchers.entrySet().iterator();it.hasNext();){var e=it.next();var p=server.getPlayerList().getPlayer(e.getKey());e.getValue().removeIf(t->{boolean remove=p==null||p.hasDisconnected()||!valid(p,t);if(remove)hosts.add(t.key().host());return remove;});if(e.getValue().isEmpty())it.remove();}hosts.forEach(world::instrumentObservationChanged);}
    public void forget(long id){var removed=store.host(id);removed.forEach(i->samples.remove(i.instance()));for(var set:watchers.values())set.removeIf(t->t.key().host()==id);world.instrumentObservationChanged(id);}
    public void publish(long id,FluidView view){var host=world.instrumentHost(id);if(host==null)return;bind(host);for(var i:store.host(id)){var mount=host.mount(i.key().mount());if(mount==null)continue;long tick=view.committedTick();Instrument.Sample sample;
        if(host.pipe()){var reading=world.instrumentPipeReading(id);if(i.settings().kind()==GaugeSettings.Kind.PRESSURE)sample=reading!=null&&Double.isFinite(reading.pressure())?new Instrument.Sample(reading.pressure(),tick,Instrument.Availability.AVAILABLE):Instrument.Sample.unavailable(tick);else sample=Instrument.Sample.temperature(reading!=null&&reading.flowing()&&world.onlineTick()-tick<=100?reading.temperature():null,samples.get(i.instance()),tick);}
        else if(i.settings().kind()==GaugeSettings.Kind.TEMPERATURE)sample=view.state()!=null?new Instrument.Sample(view.state().temperature(),tick,Instrument.Availability.AVAILABLE):Instrument.Sample.unavailable(tick);
        else {Double pressure=view.tank()==null?null:mount.top()?view.tank().topPressure():view.tank().bottomPressure();sample=pressure==null?Instrument.Sample.unavailable(tick):new Instrument.Sample(pressure,tick,Instrument.Availability.AVAILABLE);}
        samples.put(i.instance(),sample);var target=new FluidNetwork.InstrumentTarget(host.entity().getBlockPos(),i.key(),i.instance());for(var e:watchers.entrySet())if(e.getValue().contains(target)){var player=server.getPlayerList().getPlayer(e.getKey());if(player!=null&&valid(player,target))FluidNetwork.sendInstrument(player,target,sample,world.onlineTick());}
    }}

    /**
     * Overworld saved data {@value #DATA_NAME}: the installed instruments, the pipe fittings and the slot tombstones (facts),
     * and the accepted, uncommitted player operations with the retry deadline (state). {@link AccessoryFacts} gives the
     * records and their rules.
     */
    public static final class Store extends SavedData {
        public static final String DATA_NAME="icheme_accessories";
        public record Legacy(Instrument.Key key,UUID instance,long revision,Instrument.Type type) {
            public Legacy{Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(type);if(!key.mount().equals("pipe_accessory")||revision<=0||type==Instrument.Type.PRESSURE||type==Instrument.Type.TEMPERATURE)throw new IllegalArgumentException("Invalid pipe accessory");}
        }
        public record Pending(UUID request,UUID player,Instrument.Key key,UUID instance,long expectedRevision,
                              boolean install,Instrument.Type type,GaugeSettings settings,String dimension,long position,int slot,boolean offhand) {
            public Pending{Objects.requireNonNull(request);Objects.requireNonNull(player);Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(type);Objects.requireNonNull(dimension);if(expectedRevision<0||slot<0||slot>8||net.minecraft.resources.ResourceLocation.tryParse(dimension)==null||install&&settings==null)throw new IllegalArgumentException("Invalid accessory operation");if(install&&type!=Instrument.Type.PRESSURE&&type!=Instrument.Type.TEMPERATURE||settings!=null&&(type!=Instrument.Type.PRESSURE&&type!=Instrument.Type.TEMPERATURE||(type==Instrument.Type.PRESSURE)!=(settings.kind()==GaugeSettings.Kind.PRESSURE)))throw new IllegalArgumentException("Accessory settings mismatch");}
        }
        private long nextDue;
        public long nextDue(){return nextDue;}
        public void nextDue(long tick){if(tick<0)throw new IllegalArgumentException("Invalid accessory deadline");nextDue=tick;setDirty();}
        private final Map<Instrument.Key,Sensor.Installed> sensors=new LinkedHashMap<>();
        private final Set<Long> modular=new LinkedHashSet<>();
        Set<Long> modular(){return Set.copyOf(modular);}
        com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve convertLegacyValve(long host,com.wormzjl.icheme.science.fluid.network.FlowControl.PressureValve legacy){
            var key=new Instrument.Key(host,"valve_sensor");var sensor=sensor(key);
            if(!modular.contains(host)&&sensor==null)sensor=installSensor(key,UUID.randomUUID(),Sensor.Kind.PRESSURE,revision(host));
            modular.add(host);setDirty();
            var source=sensor!=null&&sensor.kind()==Sensor.Kind.FLOW?com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW:com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.PRESSURE;
            return new com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve(source,legacy.targetPressure(),1,.01,sensor==null?null:sensor.instance(),revision(host));
        }
        public Sensor.Installed sensor(Instrument.Key key){return sensors.get(key);}
        public List<Sensor.Installed> allSensors(){return List.copyOf(sensors.values());}
        public List<Sensor.Installed> sensors(long host){return sensors.values().stream().filter(s->s.key().host()==host).toList();}
        public Sensor.Installed installSensor(Instrument.Key key,UUID instance,Sensor.Kind kind,long expectedRevision){
            if(revision(key.host())!=expectedRevision||sensors.containsKey(key)||host(key.host()).stream().anyMatch(g->g.key().equals(key)))return null;
            var fitting=legacy(key.host());
            if(key.mount().equals("valve_sensor")?(fitting==null||fitting.type()!=Instrument.Type.PRESSURE_VALVE):
                    key.mount().startsWith("pipe_")&&(fitting!=null||!host(key.host()).isEmpty()||!sensors(key.host()).isEmpty()))return null;
            long revision=Math.addExact(revision(key.host()),1);var value=new Sensor.Installed(key,instance,revision,kind);advance(key.host());sensors.put(key,value);if(key.mount().equals("valve_sensor"))modular.add(key.host());setDirty();return value;
        }
        public Sensor.Installed removeSensor(Instrument.Key key,UUID instance){var old=sensors.get(key);if(old==null||!old.instance().equals(instance))return null;sensors.remove(key);advance(key.host());setDirty();return old;}
        private final Map<Instrument.Key,Instrument.Installed> installed=new LinkedHashMap<>();
        private final Map<Long,Legacy> legacy=new LinkedHashMap<>();
        public Legacy legacy(long host){return legacy.get(host);}
        public Legacy installLegacy(long host,Instrument.Type type){var old=legacy.get(host);if(old!=null){if(old.type()!=type)throw new IllegalStateException("Occupied accessory slot");return old;}if(!host(host).isEmpty()||!sensors(host).isEmpty())throw new IllegalStateException("Occupied pipe");var value=new Legacy(new Instrument.Key(host,"pipe_accessory"),UUID.randomUUID(),Math.addExact(revision(host),1),type);revisions.put(host,value.revision());legacy.put(host,value);setDirty();return value;}
        public Legacy removeLegacy(long host,UUID instance){var old=legacy.get(host);if(old==null||!old.instance().equals(instance))return null;advance(host);legacy.remove(host);setDirty();return old;}
        private final Map<Long,Long> revisions=new LinkedHashMap<>();
        private final Map<UUID,Pending> pending=new LinkedHashMap<>();
        public Collection<Instrument.Installed> installed(){return List.copyOf(installed.values());}
        public Instrument.Installed get(Instrument.Key key){return installed.get(key);}
        public List<Instrument.Installed> host(long host){return installed.values().stream().filter(i->i.key().host()==host).toList();}
        public long revision(long host){return revisions.getOrDefault(host,0L);}
        Map<Long,Long> revisions(){return Map.copyOf(revisions);}
        public List<Pending> pending(){return List.copyOf(pending.values());}
        public boolean queue(Pending p){if(pending.containsKey(p.request)||p.expectedRevision!=revision(p.key.host())||pending.size()>=4096||pending.values().stream().anyMatch(v->v.key.equals(p.key)))return false;pending.put(p.request,p);setDirty();return true;}
        public void finish(UUID request){if(pending.remove(request)!=null)setDirty();}
        public Instrument.Installed install(Pending p){if(!p.equals(pending.get(p.request))||!p.install||revision(p.key.host())!=p.expectedRevision||installed.containsKey(p.key)||sensors.containsKey(p.key)||!sensors(p.key.host()).isEmpty()&&p.key.mount().startsWith("pipe_")||legacy.containsKey(p.key.host())||p.key.mount().startsWith("pipe_")&&!host(p.key.host()).isEmpty())return null;long rev=Math.addExact(revision(p.key.host()),1);var i=new Instrument.Installed(p.key,p.instance,rev,p.settings);revisions.put(p.key.host(),rev);installed.put(p.key,i);setDirty();return i;}
        public Instrument.Installed remove(Instrument.Key key,UUID instance){var old=installed.get(key);if(old==null||!old.instance().equals(instance))return null;advance(key.host());installed.remove(key);setDirty();return old;}
        public void changed(long host){advance(host);setDirty();}
        private long advance(long host){long next=Math.addExact(revision(host),1);revisions.put(host,next);return next;}
        @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider provider){return save(t);}
        public CompoundTag save(CompoundTag t) {
            t.putInt("format",AccessoryFacts.SCHEMA);
            var list=new ListTag();installed.values().forEach(i->list.add(AccessoryFacts.installed(i)));t.put("installed",list);
            var fittings=new ListTag();legacy.values().forEach(f->fittings.add(AccessoryFacts.fitting(f)));t.put("fittings",fittings);
            var revs=new ListTag();revisions.forEach((id,rev)->revs.add(AccessoryFacts.revision(id,rev)));t.put("revisions",revs);
            var probes=new ListTag();sensors.values().forEach(s->probes.add(Sensor.save(s)));t.put("sensors",probes);
            var converted=new ListTag();modular.forEach(id->{var v=new CompoundTag();v.putLong("host",id);converted.add(v);});t.put("modular_valves",converted);
            t.putInt("state_format",AccessoryFacts.STATE);t.putLong("next_due",nextDue);
            var events=new ListTag();pending.values().forEach(p->events.add(AccessoryFacts.pending(p)));t.put("pending",events);
            return t;
        }
        public static Store load(CompoundTag t,HolderLookup.Provider ignored){return load(t);}
        /**
         * A saved store: facts this build cannot read (a later schema, a field from a newer build, a damaged or conflicting
         * record) throw; state it cannot use (another state format, a record that does not validate) is dropped, the
         * facts kept.
         */
        public static Store load(CompoundTag t) {
            var root=new AccessoryFacts.Fields(t,"accessory store");int schema=root.i32("format");
            if(schema>AccessoryFacts.SCHEMA)throw root.invalid("facts schema "+schema+" cannot be read: this build reads schemas 1 to "+AccessoryFacts.SCHEMA+", so a newer build wrote it");
            if(schema<1)throw root.invalid("facts schema "+schema);
            var s=new Store();
            for(var r:root.list("revisions")){var e=AccessoryFacts.revision(r);if(s.revisions.put(e.getKey(),e.getValue())!=null)throw root.invalid("two revisions of host "+e.getKey());}
            for(var v:root.list("installed")){var i=AccessoryFacts.installed(v);long host=i.key().host();
                if(i.revision()>s.revision(host)||i.key().mount().startsWith("pipe_")&&!s.host(host).isEmpty()||s.host(host).stream().anyMatch(existing->existing.key().mount().startsWith("pipe_"))||s.installed.put(i.key(),i)!=null)
                    throw root.invalid("the gauge at "+i.key()+" conflicts with its host's records");}
            for(var v:root.list("fittings")){var f=AccessoryFacts.fitting(v);long host=f.key().host();
                if(f.revision()>s.revision(host)||!s.host(host).isEmpty()||s.legacy.put(host,f)!=null)throw root.invalid("the fitting on host "+host+" conflicts with its host's records");}
            var instances=new HashSet<UUID>();s.installed.values().forEach(i->instances.add(i.instance()));s.legacy.values().forEach(i->instances.add(i.instance()));
            for(var v:root.list("sensors",List.of())){var sensor=Sensor.load(v);long host=sensor.key().host();
                var fitting=s.legacy(host);
                if(!instances.add(sensor.instance())||sensor.revision()>s.revision(host)||s.sensors.put(sensor.key(),sensor)!=null||s.host(host).stream().anyMatch(g->g.key().equals(sensor.key()))||
                        sensor.key().mount().equals("valve_sensor")&&(fitting==null||fitting.type()!=Instrument.Type.PRESSURE_VALVE)||
                        sensor.key().mount().startsWith("pipe_")&&(fitting!=null||!s.host(host).isEmpty()||s.sensors(host).size()>1))
                    throw root.invalid("sensor conflicts with host "+host);
            }
            for(var v:root.list("modular_valves",List.of())){var f=new AccessoryFacts.Fields(v,"modular valve");long id=f.i64("host");f.end();if(id<=0||!s.modular.add(id))throw root.invalid("invalid modular valve");}
            root.claim("state_format","next_due","pending");root.end();
            try{s.state(new AccessoryFacts.Fields(t,"accessory state"));}
            catch(IllegalArgumentException|NullPointerException dropped) {
                s.nextDue=0;s.pending.clear();com.wormzjl.icheme.IChemE.LOGGER.warn("accessory_store status=STATE_DROPPED detail={}",dropped.getMessage());
            }
            return s;
        }
        /** Reads the state beside the facts; a store saved before the state format carried state format 1. */
        private void state(AccessoryFacts.Fields f) {
            int format=f.i32("state_format",1);if(format!=AccessoryFacts.STATE)throw f.invalid("state format "+format+": this build reads state format "+AccessoryFacts.STATE);
            long due=f.i64("next_due",0);if(due<0)throw f.invalid("deadline "+due);nextDue=due;
            var events=f.list("pending",List.of());if(events.size()>4096)throw f.invalid("too many operations");
            for(var v:events){var p=AccessoryFacts.pending(v);
                if(p.expectedRevision()>revision(p.key().host())||pending.values().stream().anyMatch(existing->existing.key().equals(p.key()))||pending.put(p.request,p)!=null)throw f.invalid("the operation "+p.request()+" conflicts with the store");}
        }
        /**
         * The overworld's store. A world without one starts empty. A store this build cannot read refuses the world
         * (AGENTS.md, "Saves and data formats"), its file left as it is: the server does not start, and nothing replaces
         * the installed accessories.
         */
        public static Store open(MinecraftServer server) {
            var store=read(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(DATA_NAME+".dat"));
            server.overworld().getDataStorage().set(DATA_NAME,store);return store;
        }
        /** The store saved at {@code file}, an empty one when there is none; one this build cannot read throws, the file untouched. */
        static Store read(java.nio.file.Path file) {
            CompoundTag saved;
            try{saved=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());}
            catch(java.nio.file.NoSuchFileException absent){return new Store();}
            catch(java.io.IOException|RuntimeException failure){throw refused(file,failure);}
            try{return load(saved.getCompound("data"));}catch(RuntimeException failure){throw refused(file,failure);}
        }
        private static IllegalStateException refused(java.nio.file.Path file,Exception failure) {
            return new IllegalStateException("The accessory store cannot be read: "+failure.getMessage()+". Refusing to open the world and replace its accessories: "+file,failure);
        }
    }
}
