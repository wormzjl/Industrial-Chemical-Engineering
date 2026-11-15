package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.science.plant.still.BatchStill;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.BlockPos;
import java.util.*;

/**
 * The fluid world authority's world-space displays (code base review R8, R9): the eight {@link Display}s (open tanks,
 * sight glasses, movers; flares, stills, towers, heaters, chimneys), the rotations' and the drivers' own watches keyed by
 * position, the watch requests' bodies and the publication passes. The engine-owned presentation rule holds as in
 * {@link Display}: a new watch is answered from what was last published (or a display's replay read), the next states go
 * only to watchers, and nothing here solves or advances a process. Every entry is called by {@link FluidWorldAuthority},
 * which checks the thread ({@code owned()}) and that it is open first.
 */
final class Displays {
    private final FluidWorldAuthority authority;
    /**
     * The requested world-space displays (owner decision FR4, request path R-B; code base review R9), each its watchers and
     * what it last published. The bucket displays publish from the presentation host's {@code present}: the open tanks'
     * latest glides (their roofs' motion to the latest display; a tank's request is by node cell, {@link #watchTanks}),
     * the sight glasses and the movers, whose watches keep their devices observed ({@link #refreshObserved}).
     */
    private final Display<TankDisplay.Glide> tankDisplay;
    /** A sight glass is checked by its block state before its block entity. */
    private final Display<PipePresentation.SightGlass> sightGlassDisplay;
    /** A constructed mover at its fluid cell, or (W10c, owner 2026-11-04) an in-line mover at its pipe block. */
    private final Display<com.wormzjl.icheme.network.FluidNetwork.MoverState> moverDisplay;
    /**
     * The cadence displays, at their device's slot of the 100-tick cadence (code base review R10): the flares' flames (batch
     * 2026-11-09-flare-stack, W5), the batch stills' fires (2026-11-09-batch-distiller, W6), the cooling towers' plumes
     * (2026-11-10-custom-shader-system, W4), the fired heaters' flues by their radiant coils (W5) and the chimneys' plumes
     * (2026-11-10-chimney). A new watch with nothing cached replays a read of what was last booked; the towers and heaters
     * read their islands as stored there ({@link FluidWorldAuthority#storedIsland}) and, at the cadence, as presented, once per island per pass.
     */
    private final Display<com.wormzjl.icheme.network.FluidNetwork.FlareState> flareDisplay;
    private final Display<com.wormzjl.icheme.network.FluidNetwork.StillState> stillDisplay;
    private final Display<com.wormzjl.icheme.network.FluidNetwork.TowerPlume> towerDisplay;
    private final Display<com.wormzjl.icheme.network.FluidNetwork.HeaterFlue> heaterDisplay;
    private final Display<com.wormzjl.icheme.network.FluidNetwork.ChimneyState> chimneyDisplay;
    /** Every display, in the order a player who left is forgotten. */
    private final List<Display<?>> displays;
    /** The cadence displays, in the order they publish at each tick. */
    private final List<Display<?>> cadenceDisplays;
    Displays(FluidWorldAuthority authority) {
        this.authority=authority;
        tankDisplay=Display.bucket(this::online,Displays::inRange,null,null,null,
                (player,pos,target,glide)->com.wormzjl.icheme.network.FluidNetwork.sendTank(player,pos,glide));
        sightGlassDisplay=Display.bucket(this::online,Displays::inRange,this::sightGlassWatchable,
                this::observeWatched,this::refreshObserved,(player,pos,target,sample)->com.wormzjl.icheme.network.FluidNetwork.sendSightGlass(player,target,sample));
        moverDisplay=Display.bucket(this::online,Displays::moverRange,
                (player,level,target)->{var record=watchable(player,level,target,Displays::moverRange);return record!=null&&record.device().control() instanceof FlowControl.Mover;},
                this::refreshObserved,this::refreshObserved,(player,pos,target,state)->com.wormzjl.icheme.network.FluidNetwork.sendMover(player,state));
        flareDisplay=Display.cadence(this::online,Displays::moverRange,
                (player,level,target)->{var record=watchable(player,level,target,Displays::moverRange);return record!=null&&record.device().flare();},
                id->authority.registrations().get(id),record->record.device().flare(),this::flareState,Display.each(this::flareState),
                (player,pos,target,state)->com.wormzjl.icheme.network.FluidNetwork.sendFlare(player,state));
        stillDisplay=Display.cadence(this::online,Displays::moverRange,
                (player,level,target)->{var record=watchable(player,level,target,Displays::moverRange);return record!=null&&record.device().stillPot();},
                id->authority.registrations().get(id),record->record.device().stillPot(),this::stillState,Display.each(this::stillState),
                (player,pos,target,state)->com.wormzjl.icheme.network.FluidNetwork.sendStill(player,state));
        towerDisplay=Display.cadence(this::online,Displays::inRange,
                (player,level,target)->{var record=watchable(player,level,target,Displays::inRange);return record!=null&&record.spec().tower()!=null;},
                id->authority.registrations().get(id),record->record.spec().tower()!=null,target->towerPlume(target,new HashMap<>(),authority::storedIsland),
                ()->{var islands=new HashMap<Long,IslandCoordinator.Snapshot>();return target->towerPlume(target,islands,authority::presentation);},
                (player,pos,target,state)->com.wormzjl.icheme.network.FluidNetwork.sendTower(player,state));
        heaterDisplay=Display.cadence(this::online,Displays::inRange,
                (player,level,target)->{var record=watchable(player,level,target,Displays::inRange);return record!=null&&flued(record);},
                id->authority.registrations().get(id),Displays::flued,target->heaterFlue(target,new HashMap<>(),authority::storedIsland),
                ()->{var islands=new HashMap<Long,IslandCoordinator.Snapshot>();return target->heaterFlue(target,islands,authority::presentation);},
                (player,pos,target,state)->com.wormzjl.icheme.network.FluidNetwork.sendHeater(player,state));
        chimneyDisplay=Display.cadence(this::online,Displays::inRange,
                (player,level,target)->{var record=watchable(player,level,target,Displays::inRange);return record!=null&&record.device().chimney();},
                id->authority.registrations().get(id),record->record.device().chimney(),this::chimneyState,Display.each(this::chimneyState),
                (player,pos,target,state)->com.wormzjl.icheme.network.FluidNetwork.sendChimney(player,state));
        displays=List.of(tankDisplay,sightGlassDisplay,moverDisplay,flareDisplay,stillDisplay,towerDisplay,heaterDisplay,chimneyDisplay);
        cadenceDisplays=List.of(flareDisplay,stillDisplay,towerDisplay,heaterDisplay,chimneyDisplay);
    }
    /**
     * A client's watch request for open tanks by their node cells (owner decision FR4, R-B): each new watch of a loaded
     * open tank within {@link com.wormzjl.icheme.network.FluidNetwork#WATCH_RANGE} is answered with the tank's last published display, if it has one (a
     * read of cached data: nothing solves, nothing is marked), and the tank's next buckets send it again. Cells that are
     * no open tank, out of range or over the player's maximum are ignored.
     */
    void watchTanks(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch) {
        var uuid=player.getUUID();
        if(!unwatch.isEmpty())for(long device:tankDisplay.watchers.devices(uuid)){var record=authority.registrations().get(device);if(record==null||unwatch.contains(FluidWorldAuthority.cell(record)))tankDisplay.unwatch(uuid,device);}
        var level=player.serverLevel();
        for(var pos:watch) {
            if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver))continue;
            var record=authority.registrations().get(receiver.fluidIdentity());
            if(record==null||!record.spec().open()||!FluidWorldAuthority.cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString())||!inRange(player,pos))continue;
            // By its node cell, with no target: the identity is the block entity's.
            tankDisplay.replay(player,receiver.fluidIdentity(),pos,null);
        }
    }
    /** Requested shaft/machine speeds, keyed by dimension as well as cell; published values are presentation state only. */
    final Map<UUID,Set<PhysicalFluidTopology.Position>> rotationWatchers=new HashMap<>();
    private final Map<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.RotationNetwork.State> publishedRotations=new HashMap<>();
    /** Validate a bounded subscription and replay its cached publication. Opening a watch never reads a train's speed. */
    void watchRotations(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch) {
        var level=player.serverLevel();String dimension=level.dimension().location().toString();
        var set=rotationWatchers.computeIfAbsent(player.getUUID(),k->new HashSet<>());
        var removed=new HashSet<>(unwatch);
        set.removeIf(p->!p.dimension().equals(dimension)||removed.contains(new BlockPos(p.x(),p.y(),p.z())));
        for(var position:watch) {
            if(set.size()>=com.wormzjl.icheme.network.RotationNetwork.MAXIMUM_WATCHED||!level.hasChunkAt(position)||!moverRange(player,position))continue;
            var key=new PhysicalFluidTopology.Position(dimension,position.getX(),position.getY(),position.getZ());
            if(!(level.getBlockState(position).getBlock() instanceof com.wormzjl.icheme.world.level.block.ShaftBlock)&&authority.shafts.unitAt(key)==null)continue;
            set.add(key);
            var published=publishedRotations.get(key);
            if(published!=null)com.wormzjl.icheme.network.RotationNetwork.send(player,published);
        }
        if(set.isEmpty())rotationWatchers.remove(player.getUUID());
    }
    /** Cadence-only reads of the trains' already decided rpm, sent exclusively to clients watching those parts. */
    void publishRotations() {
        var current=new HashMap<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.RotationNetwork.State>();
        var units=new HashMap<PhysicalFluidTopology.Position,ShaftUnit>();
        for(var unit:authority.shafts.units())units.put(unit.position(),unit);
        var speeds=new HashMap<Long,Double>();
        for(var it=rotationWatchers.entrySet().iterator();it.hasNext();) {
            var entry=it.next();var player=authority.server.getPlayerList().getPlayer(entry.getKey());
            if(player==null||player.hasDisconnected()){it.remove();continue;}
            String dimension=player.serverLevel().dimension().location().toString();
            for(var key:List.copyOf(entry.getValue())) {
                var position=new BlockPos(key.x(),key.y(),key.z());
                if(!key.dimension().equals(dimension)||!player.serverLevel().hasChunkAt(position)||!moverRange(player,position)){
                    entry.getValue().remove(key);continue;
                }
                var state=current.computeIfAbsent(key,p->{
                    var pair=rotationSpeeds(units.get(p),units,speeds);
                    float rpm=(float)pair[0],output=(float)pair[1];
                    return new com.wormzjl.icheme.network.RotationNetwork.State(position,Float.isFinite(rpm)?rpm:0,Float.isFinite(output)?output:0);
                });
                com.wormzjl.icheme.network.RotationNetwork.send(player,state);
            }
            if(entry.getValue().isEmpty())it.remove();
        }
        publishedRotations.clear();publishedRotations.putAll(current);
    }
    /** A gearbox's connected shaft fixes that end's speed; its compiled ratio also fixes an unconnected other end. */
    private double[] rotationSpeeds(ShaftUnit unit,Map<PhysicalFluidTopology.Position,ShaftUnit> units,Map<Long,Double> speeds) {
        if(unit==null)return new double[]{0,0};
        if(unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GEARBOX) {
            double ratio=authority.shafts.ratio(unit.id());
            if(!(ratio>0))return new double[]{0,0};
            for(var end:unit.ends()) {
                var p=end.cell();var adjacent=units.get(new PhysicalFluidTopology.Position(p.dimension(),p.x()+end.dx(),p.y()+end.dy(),p.z()+end.dz()));
                int axis=end.dx()!=0?0:end.dy()!=0?1:2;
                if(adjacent==null||adjacent.kind()!=com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT||adjacent.axis()!=axis)continue;
                double rpm=rotationSpeed(adjacent,speeds);
                return end.output()?new double[]{rpm/ratio,rpm}:new double[]{rpm,rpm*ratio};
            }
            return new double[]{0,0};
        }
        double rpm=rotationSpeed(unit,speeds);
        return new double[]{rpm,rpm};
    }
    private double rotationSpeed(ShaftUnit unit,Map<Long,Double> speeds) {
        return speeds.computeIfAbsent(unit.id(),id->{
            var view=authority.shafts.view(id);double rpm=view==null?0:Math.max(0,view.speed());
            if(unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT&&(view==null||view.status().equals("LOOSE")||view.status().equals("NO DRIVER"))) {
                var level=authority.server.getLevel(FluidWorldAuthority.dimension(unit.position().dimension()));var pos=new BlockPos(unit.position().x(),unit.position().y(),unit.position().z());
                if(level!=null&&level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.ShaftParts.Coupling nativeShaft)rpm=nativeShaft.nativeSpeed();
            }
            return rpm;
        });
    }
    /** The drivers each player watches (W3), by their structures' origins, within their dimension. */
    final Map<UUID,Set<BlockPos>> driverWatchers=new HashMap<>();
    private final Map<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.FluidNetwork.DriverState> publishedDrivers=new HashMap<>();
    /**
     * A driver sound subscription replays its cached publication; only the presentation cadence reads train state.
     */
    void watchDrivers(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch) {
        var set=driverWatchers.computeIfAbsent(player.getUUID(),k->new HashSet<>());
        unwatch.forEach(set::remove);
        for(var origin:watch) {
            if(set.size()>=com.wormzjl.icheme.network.FluidNetwork.MAXIMUM_WATCHED||!player.serverLevel().hasChunkAt(origin)||!moverRange(player,origin))continue;
            var key=new PhysicalFluidTopology.Position(player.serverLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ());
            var unit=authority.shafts.unitAt(key);
            if(unit==null||!(unit.kind().driver()||unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GENERATOR))continue;
            set.add(origin.immutable());
            var state=publishedDrivers.get(key);
            if(state!=null)com.wormzjl.icheme.network.FluidNetwork.sendDriver(player,state);
        }
        if(set.isEmpty())driverWatchers.remove(player.getUUID());
    }
    private com.wormzjl.icheme.network.FluidNetwork.DriverState driverState(net.minecraft.server.level.ServerLevel level,BlockPos origin) {
        var unit=authority.shafts.unitAt(new PhysicalFluidTopology.Position(level.dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()));
        if(unit==null||!(unit.kind().driver()||unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GENERATOR))return null;
        var view=authority.shafts.view(unit.id());if(view==null)return null;
        return new com.wormzjl.icheme.network.FluidNetwork.DriverState(origin,view.share()>0,(float)Math.max(0,view.share()));
    }
    /** The presentation cadence's driver states: one per watched driver, to its watchers only. */
    void publishDrivers() {
        var current=new HashMap<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.FluidNetwork.DriverState>();
        for(var it=driverWatchers.entrySet().iterator();it.hasNext();) {
            var e=it.next();var player=authority.server.getPlayerList().getPlayer(e.getKey());
            if(player==null||player.hasDisconnected()){it.remove();continue;}
            for(var origin:List.copyOf(e.getValue())) {
                var key=new PhysicalFluidTopology.Position(player.serverLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ());
                var state=player.serverLevel().hasChunkAt(origin)&&moverRange(player,origin)
                        ?current.computeIfAbsent(key,k->driverState(player.serverLevel(),origin)):null;
                if(state==null) {
                    e.getValue().remove(origin);
                    com.wormzjl.icheme.network.FluidNetwork.sendDriver(player,new com.wormzjl.icheme.network.FluidNetwork.DriverState(origin,false,0));
                } else com.wormzjl.icheme.network.FluidNetwork.sendDriver(player,state);
            }
            if(e.getValue().isEmpty())it.remove();
        }
        publishedDrivers.clear();publishedDrivers.putAll(current);
    }
    /** A sound/animation subscription replays published operating state only; it never reads or advances a process. */
    void watchMovers(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        moverDisplay.watch(player,watch,unwatch);
    }
    /**
     * A flare's flame subscription (W5): it replays the state last published, or the flare's last booked span (a read), and
     * the presentation cadence keeps it current. It never reads or advances a process.
     */
    void watchFlares(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        flareDisplay.watch(player,watch,unwatch);
    }
    /** Flare {@code target}'s flame from its last booked span (a read): its heat over its rating, its unburnt flow over its limit. */
    private com.wormzjl.icheme.network.FluidNetwork.FlareState flareState(com.wormzjl.icheme.network.FluidNetwork.Target target) {
        var view=authority.flares.view(target.identity());if(view==null)return null;
        boolean flame=view.status()==Flares.Status.FLARING||view.status()==Flares.Status.OVER_CAPACITY;
        float share=(float)Math.clamp(view.heat()/view.rated(),0,1),plume=view.status()==Flares.Status.FLAME_OUT&&view.limit()>0?(float)Math.clamp(view.massFlow()/view.limit(),.05,1):0;
        // W3 (batch 2026-11-10-custom-shader-system): the flame's soot index and the gas's exit velocity, as the span booked them.
        var f=authority.flares.flame(target.identity());if(f==null)f=Flares.Flame.NONE;
        float soot=flame?(float)Math.clamp(f.soot(),0,1):0,exit=(float)Math.clamp(f.exitVelocity(),0,com.wormzjl.icheme.network.FluidNetwork.MAXIMUM_SPEED);
        return new com.wormzjl.icheme.network.FluidNetwork.FlareState(target,view.status().ordinal(),flame?Math.max(share,.02f):0,plume,soot,Float.isFinite(exit)?exit:0);
    }
    /**
     * A batch still's fire subscription (W6), as a flare's: it replays the state last published, or the still's cached view
     * (a read), and the presentation cadence keeps it current. It never reads or advances a process. A still is watched at
     * its pot's cell, where its identity binds.
     */
    void watchStills(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        stillDisplay.watch(player,watch,unwatch);
    }
    /** Still {@code target}'s fire from its cached view (a read): its heat over its rating, whether it boils, whether its spout drips. */
    private com.wormzjl.icheme.network.FluidNetwork.StillState stillState(com.wormzjl.icheme.network.FluidNetwork.Target target) {
        var u=authority.topology.still(target.identity());var v=authority.stills.view(target.identity());if(u==null||v==null)return null;
        float fire=(float)Math.clamp(v.heat()/u.design().rating(),0,1);
        var s=v.status();
        boolean boiling=v.heat()>0&&(s==BatchStill.Status.DISTILLING||s==BatchStill.Status.BOILING||s==BatchStill.Status.FLOODING||s==BatchStill.Status.COOLING_LIMITED);
        boolean dripping=u.condenser()&&v.distillateFlow()>com.wormzjl.icheme.network.FluidNetwork.StillState.DRIP;
        return new com.wormzjl.icheme.network.FluidNetwork.StillState(target,u.heater().ordinal(),s.ordinal(),fire,boiling,dripping);
    }
    /**
     * A cooling tower's plume subscription (batch 2026-11-10-custom-shader-system, W4), as a flare's: watched at its basin's
     * fluid cell within {@link com.wormzjl.icheme.network.FluidNetwork#WATCH_RANGE}; it replays the state last published, or the
     * rates its island last accepted (a read), and the presentation cadence keeps it current. It never reads or advances a process.
     */
    void watchTowers(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        towerDisplay.watch(player,watch,unwatch);
    }
    /**
     * A fired heater's flue subscription (W5), as a flare's: watched at its fluid cell, where its radiant coil binds; it
     * replays the state last published, or the heater's last booked slices (a read), and the presentation cadence keeps it current.
     */
    void watchHeaters(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        heaterDisplay.watch(player,watch,unwatch);
    }
    /**
     * A chimney's plume subscription (batch 2026-11-10-chimney), as a heater's: watched at its tip's cell; it replays the state
     * last published, or the chimney's last booked span (a read), and the presentation cadence keeps it current.
     */
    void watchChimneys(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        chimneyDisplay.watch(player,watch,unwatch);
    }
    /** Chimney {@code target}'s release from its last booked span (a read), or null for no chimney. */
    private com.wormzjl.icheme.network.FluidNetwork.ChimneyState chimneyState(com.wormzjl.icheme.network.FluidNetwork.Target target) {
        var c=authority.chimneys.view(target.identity());if(c==null)return null;
        float flow=(float)c.massFlow(),t=(float)Math.clamp(c.temperature(),1,2999),w=(float)Math.clamp(c.waterShare(),0,9),s=(float)c.solids(),o=(float)Math.clamp(c.opacity(),0,1),u=(float)Math.clamp(c.exitVelocity(),0,1000);
        if(!Float.isFinite(flow+t+w+s+o+u))return new com.wormzjl.icheme.network.FluidNetwork.ChimneyState(target,0,298.15f,0,0,0,0);
        return new com.wormzjl.icheme.network.FluidNetwork.ChimneyState(target,flow,t,w,s,o,u);
    }
    /**
     * The registration {@code target} names at its cell, loaded, in {@code level} and within {@code range} of {@code player}
     * (checked before the block entity is read); null otherwise.
     */
    private WorldTopologyLedger.Registration watchable(net.minecraft.server.level.ServerPlayer player,net.minecraft.server.level.ServerLevel level,com.wormzjl.icheme.network.FluidNetwork.Target target,Display.Range range) {
        var pos=target.position();
        if(!level.hasChunkAt(pos)||!range.within(player,pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())return null;
        var record=authority.registrations().get(target.identity());
        if(record==null||!FluidWorldAuthority.cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()))return null;
        return record;
    }
    /** Whether {@code record}'s flue is watched: a fired heater's radiant coil, or a prime mover's fuel void (its stack). */
    private static boolean flued(WorldTopologyLedger.Registration record){return FluidWorldAuthority.radiantCoil(record)||record.device().engineFuel();}
    /**
     * Tower {@code target}'s plume from the rates its island last accepted (a read): its dry air, its exhaust
     * ({@code TowerTransfer.exhaust}) and its evaporation; no air without rates or below {@code TowerTransfer.NO_AIR}.
     */
    private com.wormzjl.icheme.network.FluidNetwork.TowerPlume towerPlume(com.wormzjl.icheme.network.FluidNetwork.Target target,Map<Long,IslandCoordinator.Snapshot> islands,java.util.function.Function<Long,IslandCoordinator.Snapshot> read) {
        var record=authority.registrations().get(target.identity());if(record==null||record.spec().tower()==null)return null;
        Long owner=authority.registry.owner(target.identity());
        var rates=owner==null?null:islands.computeIfAbsent(owner,read).lastResult().map(r->r.towers().get(target.identity())).orElse(null);
        var still=new com.wormzjl.icheme.network.FluidNetwork.TowerPlume(target,0,(float)com.wormzjl.icheme.science.fluid.network.CoolingTower.AIR_TEMPERATURE,0,0);
        if(rates==null||!(FluidWorldAuthority.plumeAir(rates.fill().dryAirFlow())>0))return still;
        var exhaust=com.wormzjl.icheme.science.fluid.network.TowerTransfer.exhaust(authority.runtime.coordinator().model(owner),rates.fill());
        float air=(float)FluidWorldAuthority.plumeAir(rates.fill().dryAirFlow()),t=(float)exhaust.temperature(),w=(float)exhaust.humidity(),e=(float)Math.max(0,rates.fill().waterLoss());
        if(!Float.isFinite(air)||!Float.isFinite(t)||!Float.isFinite(w)||!Float.isFinite(e))return still;
        return new com.wormzjl.icheme.network.FluidNetwork.TowerPlume(target,air,t,Math.clamp(w,0,1),e);
    }
    /**
     * Heater {@code target}'s flue (a read of what its islands last booked): its firing, the fuel fired over the design's;
     * its stack temperature, the bridgewall less its convection coil's heat over the flue's capacity rate
     * ({@code ConvectionCoil.stackTemperature}), the bridgewall without a convection coil, the combustion air unfired; its flue
     * flow and water ({@link FiredHeaters#flue}); its fuel's phase.
     */
    private com.wormzjl.icheme.network.FluidNetwork.HeaterFlue heaterFlue(com.wormzjl.icheme.network.FluidNetwork.Target target,Map<Long,IslandCoordinator.Snapshot> islands,java.util.function.Function<Long,IslandCoordinator.Snapshot> read) {
        long id=target.identity();var record=authority.registrations().get(id);if(record==null)return null;
        if(record.device().engineFuel()) {
            // A prime mover's stack (batch 2026-11-10-prime-movers): its set's exhaust, by its fuel void.
            var stack=authority.shafts.primeMovers()==null?null:authority.shafts.primeMovers().flue(id);
            if(stack==null)return new com.wormzjl.icheme.network.FluidNetwork.HeaterFlue(target,0,(float)com.wormzjl.icheme.science.fluid.network.Burners.AIR,0,0,0);
            return new com.wormzjl.icheme.network.FluidNetwork.HeaterFlue(target,(float)stack.firing(),(float)stack.stack(),(float)stack.flow(),(float)Math.clamp(stack.water(),0,1),stack.fuel().ordinal());
        }
        if(!FluidWorldAuthority.radiantCoil(record))return null;
        long b=authority.burners.burnersOf(id);var flue=b==0?null:authority.burners.flue(b);
        if(flue==null)return new com.wormzjl.icheme.network.FluidNetwork.HeaterFlue(target,0,(float)com.wormzjl.icheme.science.fluid.network.Burners.AIR,0,(float)com.wormzjl.icheme.science.fluid.network.Burners.METHANE_WATER,0);
        double design=FiredHeaters.designFired(authority.registrations().get(b)),firing=design>0?Math.clamp(flue.fired()/design,0,1):0;
        double radiant=sideHeat(id,islands,read);long convection=record.device().heat().partner();
        double stack=convection==0?(radiant>0?com.wormzjl.icheme.science.fluid.network.ConvectionCoil.BRIDGEWALL:com.wormzjl.icheme.science.fluid.network.Burners.AIR)
                :com.wormzjl.icheme.science.fluid.network.ConvectionCoil.stackTemperature(radiant,sideHeat(convection,islands,read),com.wormzjl.icheme.science.fluid.network.Burners.AIR);
        float flow=(float)flue.flueFlow(),water=(float)Math.clamp(flue.waterShare(),0,1);
        return new com.wormzjl.icheme.network.FluidNetwork.HeaterFlue(target,(float)firing,(float)stack,Float.isFinite(flow)?flow:0,Float.isFinite(water)?water:0,flue.fuel().ordinal());
    }
    /** The heat side {@code device}'s heat over its island's last interval, W, into its stream: its {@code HEAT} boundaries (a read). */
    private double sideHeat(long device,Map<Long,IslandCoordinator.Snapshot> islands,java.util.function.Function<Long,IslandCoordinator.Snapshot> read) {
        Long owner=authority.registry.owner(device);if(owner==null)return 0;
        var result=islands.computeIfAbsent(owner,read).lastResult().orElse(null);if(result==null||!(result.advancedSeconds()>0))return 0;
        long key=PhysicalFluidTopology.sideIdentity(device);double joule=0;
        for(var boundary:result.boundaries())if(boundary.kind()==com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind.HEAT&&boundary.nodeId()==key)joule+=boundary.totalEnergyJoule();
        double rate=joule/result.advancedSeconds();
        return Double.isFinite(rate)?Math.max(0,rate):0;
    }
    private static boolean moverRange(net.minecraft.server.level.ServerPlayer player,BlockPos pos){return pos.distToCenterSqr(player.position())<=com.wormzjl.icheme.network.FluidNetwork.MOVER_RANGE*com.wormzjl.icheme.network.FluidNetwork.MOVER_RANGE;}
    void refreshObserved(long id){if(moverDisplay.watchers.watchers(id).isEmpty()&&sightGlassDisplay.watchers.watchers(id).isEmpty()&&(authority.instruments==null||!authority.instruments.observes(id)))authority.presentation.unobserve(id);else authority.presentation.observe(id);}
    /** A new sight glass watch keeps its device observed (its on-watch hook; a mover's is {@link #refreshObserved}). */
    private void observeWatched(long id){authority.presentation.observe(id);}
    /** An online player by identity (the displays' watcher lookup), read when a state is sent. */
    private net.minecraft.server.level.ServerPlayer online(UUID player){return authority.server.getPlayerList().getPlayer(player);}
    /** The mover state last published for device {@code id} (a read), or null. */
    com.wormzjl.icheme.network.FluidNetwork.MoverState publishedMover(long id){return moverDisplay.published(id);}
    void publishMover(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        if(!(record.device().control() instanceof FlowControl.Mover mover))return;
        long id=record.device().id();var target=new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id);
        // A machine turns at its shaft train's share (W3); a set-flow mover at its whole speed while it runs.
        double share=authority.shafts.unitOf(id)?authority.shafts.share(id):record.device().kind()==TopologyCompiler.Kind.PIPE&&authority.electric.generator(id)!=null?(authority.electric.powered(id)?mover instanceof FlowControl.Machine m?m.speed():1:0):1;
        boolean running=record.device().suspendedBy()!=0||mover.enabled()&&share>0&&mover.requestedVolumeFlow()>0;
        var state=new com.wormzjl.icheme.network.FluidNetwork.MoverState(target,!mover.liquid(),
                // A column holds its pumps and fans only while it runs, and runs them (C7).
                running,(float)(running?Math.max(share,0):0),
                // A pump's crackle and churn (W10c) from its last committed suction, a dry screw's liquid noise in the crackle (owner V6 B); 0 for a compressor and a stopped mover.
                running?authority.health.crackle(id):0,running&&mover.liquid()?authority.health.churn(id):0,
                // An in-line pump's rated speed (its D21 duty speed) for its pitch; a casing's comes from its shape.
                mover instanceof FlowControl.Pump pump&&pump.requestedVolumeFlow()>0?(float)com.wormzjl.icheme.science.machine.Npsh.dutySpeed(pump.requestedVolumeFlow(),com.wormzjl.icheme.science.machine.Motor.ratedSpeed(2)):0);
        moverDisplay.publish(id,pos,target,state,level);
    }
    /** Explicit visual requests replay cached appearance only, then keep a future engine bucket scheduled. */
    void watchSightGlasses(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        sightGlassDisplay.watch(player,watch,unwatch);
    }
    /** A sight glass watch's target check: its block state is read before its block entity, which a pipe without a glass is not asked for. */
    private boolean sightGlassWatchable(net.minecraft.server.level.ServerPlayer player,net.minecraft.server.level.ServerLevel level,com.wormzjl.icheme.network.FluidNetwork.Target target) {
        var pos=target.position();
        if(!level.hasChunkAt(pos)||!inRange(player,pos)||!com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasSightGlass(level.getBlockState(pos)))return false;
        if(!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())return false;
        var record=authority.registrations().get(target.identity());
        return !(record==null||record.device().kind()!=TopologyCompiler.Kind.PIPE||!FluidWorldAuthority.cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()));
    }
    void publishSightGlass(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos,FluidView view) {
        long id=record.device().id();
        var sample=PipePresentation.sightGlass(view,authority.componentNames,authority.model.molecularWeights(),authority.catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(authority.catalog),authority.registry.pipeViews(id));
        sightGlassDisplay.publish(id,pos,new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id),sample,level);
    }
    private static boolean inRange(net.minecraft.server.level.ServerPlayer player,BlockPos pos){return player.position().distanceToSqr(pos.getCenter())<=com.wormzjl.icheme.network.FluidNetwork.WATCH_RANGE*com.wormzjl.icheme.network.FluidNetwork.WATCH_RANGE;}
    /**
     * An open tank's bucket: its display is read off the view just presented, its roof glides to it from where it stands
     * ({@link TankDisplay.Glide}, which the level's roofs collide by) and the glide is sent to each watcher still online,
     * in its dimension and within range; the others are dropped. Nothing is sent when nobody watches. A tank with no glide
     * since it loaded glides from the level its node cell saved, so this runs before the cell takes the new view.
     */
    void publishTank(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos,FluidView view) {
        long id=record.device().id();var display=TankDisplay.of(view,authority.componentNames,authority.model.molecularWeights(),authority.catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(authority.catalog));
        var roofs=com.wormzjl.icheme.world.level.block.FloatingRoofs.of(level);
        if(display==null){tankDisplay.unpublish(id);roofs.glide(pos,null);return;}
        long now=level.getGameTime();var glide=TankDisplay.Glide.next(tankDisplay.published(id),roofs.standing(pos,now),display,now);
        roofs.glide(pos,glide);tankDisplay.publish(id,pos,null,glide,level);
    }
    /** The displays' part of a player's leaving ({@link FluidWorldAuthority#forgetWatcher}): the drivers' and the rotations' watches, then each display's in the order of {@link #displays}. */
    void forget(UUID player){driverWatchers.remove(player);rotationWatchers.remove(player);for(var display:displays)display.forget(player);}
    /** A device's block entity unloaded or was removed ({@link FluidWorldAuthority#deviceUnloaded}): its sight glass's, mover's and tank's watches and states end. */
    void unloaded(long id){sightGlassDisplay.forget(id);moverDisplay.forget(id);tankDisplay.forget(id);}
    /** A device a commit removed: its tank's, sight glass's and mover's watches and states end. */
    void removed(long device){tankDisplay.forget(device);sightGlassDisplay.forget(device);moverDisplay.forget(device);}
    /** Devices a commit recompiled: their sight glasses' and movers' cached states predate the change. */
    void recompiled(Set<Long> recompiled){sightGlassDisplay.unpublish(recompiled);moverDisplay.unpublish(recompiled);}
    /** A pipe presented without a sight glass: its sight glass watches and state end. */
    void forgetSightGlass(long id){sightGlassDisplay.forget(id);}
    /** The cadence displays' passes at online tick {@code displayTick}, in the order of {@link #cadenceDisplays}. Reads only. */
    void publishDue(long displayTick){for(var display:cadenceDisplays)display.publishDue(displayTick);}
    /** At close: the sight glasses', movers', flares', stills', towers' and heaters' states (not the tanks' glides nor the chimneys' states; no watcher list). */
    void clearPublished(){sightGlassDisplay.clearPublished();moverDisplay.clearPublished();flareDisplay.clearPublished();stillDisplay.clearPublished();towerDisplay.clearPublished();heaterDisplay.clearPublished();}
    /** At close, after the noise levels: the drivers' and the rotations' watches and states. */
    void clearWatches(){driverWatchers.clear();publishedDrivers.clear();rotationWatchers.clear();publishedRotations.clear();}
}
