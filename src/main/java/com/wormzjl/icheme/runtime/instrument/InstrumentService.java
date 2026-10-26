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
    private final Map<UUID,ItemStack> exactStacks=new HashMap<>();
    private final Map<UUID,Set<FluidNetwork.InstrumentTarget>> watchers=new HashMap<>();
    private final Map<UUID,Instrument.Sample> samples=new HashMap<>();

    /** {@code store} is the overworld's, opened by {@link Store#open} before the fluid authority starts anything. */
    public InstrumentService(MinecraftServer server,FluidWorldAuthority world,Store store){this.server=server;this.world=world;this.store=store;}
    public boolean occupied(long host){return !store.host(host).isEmpty()||store.pending().stream().anyMatch(p->p.key().host()==host&&p.install());}
    public boolean observes(long host){return watchers.values().stream().anyMatch(set->set.stream().anyMatch(t->t.key().host()==host));}
    public void request(ServerPlayer player,InteractionHand hand,boolean remove){
        if(player.isSpectator()||!player.mayBuild())return;
        var held=player.getItemInHand(hand);
        GaugeSettings.Kind kind=held.getItem() instanceof GaugeItem g?g.kind():null;
        boolean level=held.is(ModItems.LIQUID_LEVEL_GAUGE.get());
        if(!remove&&kind==null&&!level||remove&&!held.is(ModItems.ACCESSORY_WRENCH.get()))return;
        double reach=player.blockInteractionRange();
        var hosts=InstrumentHosts.nearby(player.level(),player.getEyePosition(),8);
        var selected=level?null:InstrumentHosts.select(hosts,player.getEyePosition(),player.getLookAngle(),reach,kind,remove);
        if(!remove&&kind!=null){var pipeHit=InstrumentHosts.pipeSelection(player,kind);if(pipeHit!=null)selected=pipeHit;}
        var fitting=level||remove?InstrumentHosts.selectFitting(hosts,player.getEyePosition(),player.getLookAngle(),reach,remove):null;
        if(selected!=null&&fitting!=null&&fitting.distance()<selected.distance())selected=null;
        InstrumentHosts.Host host;String mount;UUID instance;Instrument.Type type;GaugeSettings settings=null;
        if(selected!=null){
            host=selected.host();mount=selected.mount().id();
            if(remove){var installed=store.get(new Instrument.Key(host.identity(),mount));if(installed==null)return;instance=installed.instance();type=installed.settings().kind()==GaugeSettings.Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE;}
            else{if(host.occupied(selected.mount())||!InstrumentHosts.clear(player.level(),host,selected.mount())||host.pipe()&&occupied(host.identity()))return;settings=held.get(ModItems.GAUGE_SETTINGS.get());if(settings==null||settings.kind()!=kind)return;instance=UUID.randomUUID();type=kind==GaugeSettings.Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE;}
        }else if(fitting!=null){
            host=fitting.host();mount=fitting.mount().id();
            if(!InstrumentHosts.reachable(player,host,fitting.mount()))return;
            if(remove){var old=store.legacy(host.identity());if(old==null)return;instance=old.instance();type=old.type();}
            else{if(store.legacy(host.identity())!=null||!InstrumentHosts.clear(player.level(),host,fitting.mount()))return;instance=UUID.randomUUID();type=Instrument.Type.LEVEL_GAUGE;}
        }else return;
        if(!permission(player,host,held))return;
        var pending=new Store.Pending(UUID.randomUUID(),player.getUUID(),new Instrument.Key(host.identity(),mount),instance,store.revision(host.identity()),!remove,type,settings,player.level().dimension().location().toString(),host.entity().getBlockPos().asLong(),player.getInventory().selected,hand==InteractionHand.OFF_HAND);
        if(store.queue(pending)){exactStacks.put(pending.request(),held);commit(player,player.serverLevel(),pending);store.finish(pending.request());exactStacks.remove(pending.request());}
    }
    private static boolean permission(ServerPlayer player,InstrumentHosts.Host host,ItemStack held){var pos=host.entity().getBlockPos();return player.mayBuild()&&!player.isSpectator()&&player.serverLevel().mayInteract(player,pos)&&player.mayUseItemAt(pos,Direction.UP,held);}
    public void tick(long tick){if(store.nextDue()==0)store.nextDue(tick+100);if(tick<store.nextDue())return;store.nextDue(tick+100);for(var p:store.pending()){var player=server.getPlayerList().getPlayer(p.player());if(player==null)continue;var level=level(p.dimension());if(level==null||!level.hasChunkAt(BlockPos.of(p.position())))continue;commit(player,level,p);store.finish(p.request());exactStacks.remove(p.request());}for(var i:store.installed()){var h=world.instrumentHost(i.key().host());if(h==null)continue;var m=h.mount(i.key().mount());if(m==null||!m.kinds().contains(i.settings().kind())||h.pipe()&&InstrumentHosts.legacy(h.entity().getBlockState())!=null){var removed=store.remove(i.key(),i.instance());if(removed!=null){refund(null,h.entity().getLevel(),h.entity().getBlockPos(),item(removed));samples.remove(i.instance());sync(h);}}}for(var fitting:store.fittings())if(fitting.type()==Instrument.Type.LEVEL_GAUGE){var host=world.instrumentHost(fitting.key().host());if(host!=null&&InstrumentHosts.LevelGauge.of(host)==null&&store.removeLegacy(host.identity(),fitting.instance())!=null){refund(null,host.entity().getLevel(),host.entity().getBlockPos(),item(fitting.type()));sync(host);}}prune();}
    private void commit(ServerPlayer player,ServerLevel level,Store.Pending p){
        var host=InstrumentHosts.at(level,BlockPos.of(p.position()));
        if(host==null||host.identity()!=p.key().host()||player.serverLevel()!=level||store.revision(host.identity())!=p.expectedRevision())return;
        var hand=p.offhand()?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        var held=player.getItemInHand(hand);
        if(!permission(player,host,held)||!p.offhand()&&player.getInventory().selected!=p.slot()||exactStacks.containsKey(p.request())&&held!=exactStacks.get(p.request()))return;
        var mount=host.mount(p.key().mount());
        if(p.type()==Instrument.Type.LEVEL_GAUGE){
            var gauge=InstrumentHosts.LevelGauge.of(host);
            if(gauge==null||!InstrumentHosts.reachable(player,host,gauge))return;
            if(p.install()){
                if(!held.is(ModItems.LIQUID_LEVEL_GAUGE.get())||!InstrumentHosts.clear(level,host,gauge)||store.installFitting(p)==null)return;
                if(!player.isCreative())held.shrink(1);
            }else{
                if(!held.is(ModItems.ACCESSORY_WRENCH.get()))return;
                var old=store.legacy(host.identity());if(old==null||old.type()!=p.type())return;
                var removed=store.removeLegacy(host.identity(),p.instance());if(removed==null)return;
                refund(player,level,host.entity().getBlockPos(),item(removed.type()));
            }
        }else if(p.install()){
            if(!(held.getItem() instanceof GaugeItem gauge)||gauge.kind()!=p.settings().kind()||!p.settings().equals(held.get(ModItems.GAUGE_SETTINGS.get()))||mount==null||host.occupied(mount)||!InstrumentHosts.clear(level,host,mount)||!mount.kinds().contains(p.settings().kind())||host.pipe()&&!store.host(host.identity()).isEmpty()||!(host.pipe()?InstrumentHosts.pipeReachable(player,host):InstrumentHosts.reachable(player,host,mount)))return;
            if(store.install(p)==null)return;if(!player.isCreative())held.shrink(1);
        }else{
            if(!held.is(ModItems.ACCESSORY_WRENCH.get()))return;
            if(p.type()==Instrument.Type.PRESSURE||p.type()==Instrument.Type.TEMPERATURE){
                if(mount==null||!InstrumentHosts.reachable(player,host,mount))return;var removed=store.remove(p.key(),p.instance());if(removed==null)return;refund(player,level,host.entity().getBlockPos(),item(removed));samples.remove(removed.instance());
            }else{
                if(InstrumentHosts.legacy(host.entity().getBlockState())!=p.type()||!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe)||InstrumentHosts.PipeAccessory.of(pipe)==null||!InstrumentHosts.reachable(player,host,InstrumentHosts.PipeAccessory.of(pipe))||store.legacy(host.identity())==null||!store.legacy(host.identity()).instance().equals(p.instance())||!world.removePipeAccessory(host.identity()))return;
                var recovered=store.removeLegacy(host.identity(),p.instance());if(recovered==null)return;clearLegacy(host);refund(player,level,host.entity().getBlockPos(),item(recovered.type()));
            }
        }
        sync(host);world.markViewDirty(host.identity());player.inventoryMenu.broadcastChanges();
    }
    private ServerLevel level(String name){return server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(name)));}
    private void sync(InstrumentHosts.Host host){host.metadata().set(store.host(host.identity()),store.revision(host.identity()),store.legacy(host.identity()));host.entity().setChanged();InstrumentHosts.loaded(host.entity());var l=host.entity().getLevel();var pos=host.entity().getBlockPos();l.sendBlockUpdated(pos,host.entity().getBlockState(),host.entity().getBlockState(),Block.UPDATE_CLIENTS);}
    public void bind(InstrumentHosts.Host host){if(host!=null&&(!host.metadata().installed().equals(store.host(host.identity()))||host.metadata().revision()!=store.revision(host.identity())||!Objects.equals(host.metadata().fitting(),store.legacy(host.identity()))))sync(host);}
    public void recoverHost(long id){var host=world.instrumentHost(id);if(host!=null)recover(id,host.entity().getLevel(),host.entity().getBlockPos(),host.pipe()?InstrumentHosts.legacy(host.entity().getBlockState()):null,host.metadata().recoverOnBreak());}
    public void recover(long id,Level level,BlockPos position,Instrument.Type legacy,boolean drop){for(var i:store.host(id))if(store.remove(i.key(),i.instance())!=null){if(drop)refund(null,level,position,item(i));samples.remove(i.instance());}var fitting=store.legacy(id);if(fitting!=null&&store.removeLegacy(id,fitting.instance())!=null&&drop)refund(null,level,position,item(fitting.type()));forget(id);}
    public void legacyInstalled(long host,Instrument.Type type){store.installLegacy(host,type);bind(world.instrumentHost(host));}
    public void recoverLegacy(long host,Level level,BlockPos position){var old=store.legacy(host);if(old!=null&&store.removeLegacy(host,old.instance())!=null){refund(null,level,position,item(old.type()));bind(world.instrumentHost(host));}}
    private static void clearLegacy(InstrumentHosts.Host host){var e=host.entity();var s=e.getBlockState().setValue(FluidDeviceBlock.SIGHT_GLASS,false).setValue(FluidDeviceBlock.SHUTOFF,FluidDeviceBlock.Shutoff.NONE).setValue(FluidDeviceBlock.PRESSURE_CONTROL,false).setValue(FluidDeviceBlock.INLINE_FILTER,false).setValue(FluidDeviceBlock.INLINE_MOVER,FluidDeviceBlock.InlineMover.NONE);s=FluidDeviceBlock.connectedState(s,e.getLevel(),e.getBlockPos());e.getLevel().setBlock(e.getBlockPos(),s,Block.UPDATE_ALL);if(e instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe)pipe.refreshGeometry(pipe.bore(),true);}
    public static ItemStack item(Instrument.Installed i){var stack=item(i.settings().kind()==GaugeSettings.Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE);stack.set(ModItems.GAUGE_SETTINGS.get(),i.settings());return stack;}
    public static ItemStack item(Instrument.Type type){return new ItemStack(switch(type){case LEVEL_GAUGE->ModItems.LIQUID_LEVEL_GAUGE.get();case PRESSURE->ModItems.PRESSURE_GAUGE.get();case TEMPERATURE->ModItems.TEMPERATURE_GAUGE.get();case SIGHT_GLASS->ModItems.PIPELINE_SIGHT_GLASS.get();case SHUTOFF->ModItems.PIPELINE_SHUTOFF_VALVE.get();case CHECK->ModItems.PIPELINE_CHECK_VALVE.get();case PRESSURE_VALVE->ModItems.PRESSURE_CONTROL_VALVE.get();case FILTER->ModItems.INLINE_FILTER.get();case PUMP->ModItems.FLUID_PUMP.get();case COMPRESSOR->ModItems.FLUID_COMPRESSOR.get();});}
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
        public record Pending(UUID request,UUID player,Instrument.Key key,UUID instance,long expectedRevision,
                              boolean install,Instrument.Type type,GaugeSettings settings,String dimension,long position,int slot,boolean offhand) {
            public Pending{
                Objects.requireNonNull(request);Objects.requireNonNull(player);Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(type);Objects.requireNonNull(dimension);
                boolean dial=type==Instrument.Type.PRESSURE||type==Instrument.Type.TEMPERATURE;
                if(expectedRevision<0||slot<0||slot>8||net.minecraft.resources.ResourceLocation.tryParse(dimension)==null||install&&dial&&settings==null)throw new IllegalArgumentException("Invalid accessory operation");
                if(install&&!dial&&type!=Instrument.Type.LEVEL_GAUGE||settings!=null&&(!dial||(type==Instrument.Type.PRESSURE)!=(settings.kind()==GaugeSettings.Kind.PRESSURE)))throw new IllegalArgumentException("Accessory settings mismatch");
                if(dial){
                    if(key.mount().equals("tank_level")||key.mount().equals("pipe_accessory")||key.mount().startsWith("pressure_")&&type!=Instrument.Type.PRESSURE||key.mount().startsWith("temperature_")&&type!=Instrument.Type.TEMPERATURE)throw new IllegalArgumentException("Invalid dial mount");
                }else if(!key.mount().equals(Instrument.Fitting.mount(type)))throw new IllegalArgumentException("Invalid fitting mount");
            }
        }
        private long nextDue;
        public long nextDue(){return nextDue;}
        public void nextDue(long tick){if(tick<0)throw new IllegalArgumentException("Invalid accessory deadline");nextDue=tick;setDirty();}
        private final Map<Instrument.Key,Instrument.Installed> installed=new LinkedHashMap<>();
        private final Map<Long,Instrument.Fitting> legacy=new LinkedHashMap<>();
        public Instrument.Fitting legacy(long host){return legacy.get(host);}
        public Instrument.Fitting installLegacy(long host,Instrument.Type type){var old=legacy.get(host);if(old!=null){if(old.type()!=type)throw new IllegalStateException("Occupied accessory slot");return old;}if(!canFit(host,type))throw new IllegalStateException("Occupied accessory host");var value=new Instrument.Fitting(new Instrument.Key(host,Instrument.Fitting.mount(type)),UUID.randomUUID(),Math.addExact(revision(host),1),type);revisions.put(host,value.revision());legacy.put(host,value);setDirty();return value;}
        private boolean canFit(long host,Instrument.Type type){return type==Instrument.Type.LEVEL_GAUGE?host(host).stream().noneMatch(i->i.key().mount().startsWith("pipe_")):host(host).isEmpty();}
        public Instrument.Fitting installFitting(Pending p){
            if(!p.equals(pending.get(p.request()))||!p.install()||p.type()!=Instrument.Type.LEVEL_GAUGE||p.expectedRevision()!=revision(p.key().host())||legacy.containsKey(p.key().host())||!canFit(p.key().host(),p.type()))return null;
            var value=new Instrument.Fitting(p.key(),p.instance(),advance(p.key().host()),p.type());legacy.put(p.key().host(),value);setDirty();return value;
        }
        public Instrument.Fitting removeLegacy(long host,UUID instance){var old=legacy.get(host);if(old==null||!old.instance().equals(instance))return null;advance(host);legacy.remove(host);setDirty();return old;}
        private final Map<Long,Long> revisions=new LinkedHashMap<>();
        private final Map<UUID,Pending> pending=new LinkedHashMap<>();
        public Collection<Instrument.Fitting> fittings(){return List.copyOf(legacy.values());}
        public Collection<Instrument.Installed> installed(){return List.copyOf(installed.values());}
        public Instrument.Installed get(Instrument.Key key){return installed.get(key);}
        public List<Instrument.Installed> host(long host){return installed.values().stream().filter(i->i.key().host()==host).toList();}
        public long revision(long host){return revisions.getOrDefault(host,0L);}
        Map<Long,Long> revisions(){return Map.copyOf(revisions);}
        public List<Pending> pending(){return List.copyOf(pending.values());}
        public boolean queue(Pending p){if(pending.containsKey(p.request)||p.expectedRevision!=revision(p.key.host())||pending.size()>=4096||pending.values().stream().anyMatch(v->v.key.equals(p.key)))return false;pending.put(p.request,p);setDirty();return true;}
        public void finish(UUID request){if(pending.remove(request)!=null)setDirty();}
        public Instrument.Installed install(Pending p){if(p.type()==Instrument.Type.LEVEL_GAUGE||!p.equals(pending.get(p.request))||!p.install||revision(p.key.host())!=p.expectedRevision||installed.containsKey(p.key)||legacy.containsKey(p.key.host())&&(legacy.get(p.key.host()).type()!=Instrument.Type.LEVEL_GAUGE||p.key.mount().startsWith("pipe_"))||p.key.mount().startsWith("pipe_")&&!host(p.key.host()).isEmpty())return null;long rev=Math.addExact(revision(p.key.host()),1);var i=new Instrument.Installed(p.key,p.instance,rev,p.settings);revisions.put(p.key.host(),rev);installed.put(p.key,i);setDirty();return i;}
        public Instrument.Installed remove(Instrument.Key key,UUID instance){var old=installed.get(key);if(old==null||!old.instance().equals(instance))return null;advance(key.host());installed.remove(key);setDirty();return old;}
        public void changed(long host){advance(host);setDirty();}
        private long advance(long host){long next=Math.addExact(revision(host),1);revisions.put(host,next);return next;}
        @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider provider){return save(t);}
        public CompoundTag save(CompoundTag t) {
            t.putInt("format",AccessoryFacts.SCHEMA);
            var list=new ListTag();installed.values().forEach(i->list.add(AccessoryFacts.installed(i)));t.put("installed",list);
            var fittings=new ListTag();legacy.values().forEach(f->fittings.add(AccessoryFacts.fitting(f)));t.put("fittings",fittings);
            var revs=new ListTag();revisions.forEach((id,rev)->revs.add(AccessoryFacts.revision(id,rev)));t.put("revisions",revs);
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
                if(f.revision()>s.revision(host)||!s.canFit(host,f.type())||s.legacy.put(host,f)!=null)throw root.invalid("the fitting on host "+host+" conflicts with its host's records");}
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
