package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.item.GaugeSettings;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded host index and shared mount selection. Does not load chunks or inspect process contents. */
public final class InstrumentHosts {
    private InstrumentHosts(){}
    private static final Map<Level,Set<BlockPos>> LOADED=new WeakHashMap<>();
    private static final Map<Level,Set<BlockPos>> INSTALLED=new WeakHashMap<>();
    public record Host(long identity,BlockEntity entity,List<Instrument.Mount> mounts,boolean pipe) {
        public Host{mounts=List.copyOf(mounts);}
        public Instrument.Metadata metadata(){return ((Instrument.Holder)entity).instruments();}
        public Instrument.Mount mount(String id){return mounts.stream().filter(m->m.id().equals(id)).findFirst().orElse(null);}
        public boolean occupied(Instrument.Mount mount){return pipe?legacy(entity.getBlockState())!=null||!metadata().installed().isEmpty():metadata().installed().stream().anyMatch(i->i.key().mount().equals(mount.id()));}
    }
    public record Selection(Host host,Instrument.Mount mount,double distance) {}
    private static final Object INDEX_LOCK=new Object();
    public static void loaded(BlockEntity be){if(be.getLevel()==null||!(be instanceof FluidDeviceBlockEntity pipe&&pipe.getBlockState().getBlock() instanceof FluidDeviceBlock block&&block.kind()==TopologyCompiler.Kind.PIPE||be instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&shell.fluidIdentity()>0))return;synchronized(INDEX_LOCK){LOADED.computeIfAbsent(be.getLevel(),l->new HashSet<>()).add(be.getBlockPos().immutable());var set=INSTALLED.computeIfAbsent(be.getLevel(),l->new HashSet<>());if(be instanceof Instrument.Holder h&&(!h.instruments().installed().isEmpty()||h.instruments().fitting()!=null))set.add(be.getBlockPos().immutable());else set.remove(be.getBlockPos());}}
    private static List<BlockPos> loaded(Level level,boolean installed){synchronized(INDEX_LOCK){return List.copyOf((installed?INSTALLED:LOADED).getOrDefault(level,Set.of()));}}
    public static void unloaded(BlockEntity be){synchronized(INDEX_LOCK){var set=LOADED.get(be.getLevel());if(set!=null)set.remove(be.getBlockPos());var equipped=INSTALLED.get(be.getLevel());if(equipped!=null)equipped.remove(be.getBlockPos());}}
    public static List<Host> nearby(Level level,Vec3 centre,double radius){return nearby(level,centre,radius,false);}
    public static List<Host> nearby(Level level,Vec3 centre,double radius,boolean installedOnly){var result=new ArrayList<Host>();for(var pos:loaded(level,installedOnly)){if(pos.getCenter().distanceToSqr(centre)>(radius+20)*(radius+20)||!level.hasChunkAt(pos))continue;var h=at(level,pos);if(h!=null&&(h.mounts.stream().anyMatch(m->m.centre().distanceToSqr(centre)<=radius*radius)||LevelGauge.of(h)!=null&&LevelGauge.of(h).bounds().distanceToSqr(centre)<=radius*radius))result.add(h);}return result.stream().collect(java.util.stream.Collectors.toMap(Host::identity,h->h,(a,b)->a,LinkedHashMap::new)).values().stream().toList();}
    public static Host at(Level level,BlockPos position){if(!level.hasChunkAt(position))return null;var be=level.getBlockEntity(position);
        if(be instanceof FluidDeviceBlockEntity pipe&&pipe.getBlockState().getBlock() instanceof FluidDeviceBlock block&&block.kind()==TopologyCompiler.Kind.PIPE&&pipe.fluidIdentity()>0)return new Host(pipe.fluidIdentity(),pipe,pipeMounts(position,pipe.bore(),pipe.getBlockState()),true);
        if(be instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){var shape=shell.shape();var c=ShapeRasterizer.fluidCell(shape);if(c==null)return null;var root=shell.origin().offset(c.x(),c.y(),c.z());if(!root.equals(position))return at(level,root);if(shell.fluidIdentity()>0)return new Host(shell.fluidIdentity(),shell,reservoirMounts(shape,shell.origin()),false);}
        return null;
    }
    public static List<Instrument.Mount> pipeMounts(BlockPos pos,double bore,net.minecraft.world.level.block.state.BlockState state){return pipeMounts(pos,bore,FluidDeviceBlock.connectionMask(state),state.getValue(FluidDeviceBlock.FACING));}
    public static List<Instrument.Mount> pipeMounts(BlockPos pos,double bore,int mask,Direction facing){
        if(!com.wormzjl.icheme.science.equipment.SightGlassGeometry.supports(mask))return List.of();
        Direction.Axis axis=facing.getAxis();for(var d:Direction.values())if((mask&(1<<d.ordinal()))!=0){axis=d.getAxis();break;}
        var mounts=new ArrayList<Instrument.Mount>();double radius=PipeSizes.outside(bore)/2;
        for(var face:Direction.Plane.HORIZONTAL){
            var normal=Vec3.atLowerCornerOf(face.getNormal());boolean vertical=axis==Direction.Axis.Y;
            var tap=vertical?pos.getCenter().add(normal.scale(radius+.005)):pos.getCenter().add(0,radius+.005,0);
            var centre=vertical?pos.getCenter().add(normal.scale(radius+.24)).add(0,.29,0):pos.getCenter().add(0,radius+.32,0);
            mounts.add(new Instrument.Mount("pipe_"+face.getName(),centre,face,Set.of(GaugeSettings.Kind.PRESSURE,GaugeSettings.Kind.TEMPERATURE),false,tap,true,Math.min(.12,.005+(PipeSizes.outside(bore)-bore)/2+2*bore/3)));
        }
        return List.copyOf(mounts);
    }
    /** A vanilla pipe hit installs on its process tap, not on an invisible preview hit box. */
    public static Selection pipeSelection(net.minecraft.world.entity.player.Player player,GaugeSettings.Kind kind){
        var hit=player.pick(player.blockInteractionRange(),0,false);if(!(hit instanceof BlockHitResult block)||hit.getType()!=HitResult.Type.BLOCK)return null;
        var host=at(player.level(),block.getBlockPos());if(host==null||!host.pipe()||host.mounts().isEmpty())return null;
        var toward=player.getEyePosition().subtract(block.getBlockPos().getCenter());
        var mount=host.mounts().stream().filter(m->m.kinds().contains(kind)).max(Comparator.comparingDouble(m->m.normal().dot(toward))).orElse(null);
        return mount==null?null:new Selection(host,mount,hit.getLocation().distanceTo(player.getEyePosition()));
    }
    public static boolean pipeReachable(net.minecraft.world.entity.player.Player player,Host host){var hit=player.pick(player.blockInteractionRange(),0,false);return hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK&&b.getBlockPos().equals(host.entity().getBlockPos());}
    public static List<Instrument.Mount> reservoirMounts(EquipmentShape shape,BlockPos origin){return com.wormzjl.icheme.science.equipment.InstrumentGeometry.sockets(shape).stream().map(t->new Instrument.Mount(t.id(),new Vec3(origin.getX()+t.x(),origin.getY()+t.y(),origin.getZ()+t.z()),Direction.valueOf(shape.front().name()),Set.of(t.pressure()?GaugeSettings.Kind.PRESSURE:GaugeSettings.Kind.TEMPERATURE),t.top())).toList();}
    public static Selection select(Collection<Host> hosts,Vec3 eye,Vec3 direction,double reach,GaugeSettings.Kind kind,boolean installedOnly){Selection best=null;Vec3 end=eye.add(direction.scale(reach));for(var h:hosts)for(var m:h.mounts){if(kind!=null&&!m.kinds().contains(kind))continue;if(installedOnly&&h.metadata().installed().stream().noneMatch(i->i.key().mount().equals(m.id())))continue;var hit=m.bounds().clip(eye,end);if(hit.isEmpty())continue;double d=hit.get().distanceTo(eye);if(best==null||d<best.distance)best=new Selection(h,m,d);}return best;}
    public static boolean reachable(net.minecraft.world.entity.player.Player player,Host host,Instrument.Selectable mount){var eye=player.getEyePosition();if(mount.bounds().distanceToSqr(eye)>player.blockInteractionRange()*player.blockInteractionRange())return false;var point=mount.bounds().clip(eye,mount.bounds().getCenter()).orElse(mount.bounds().getCenter());var hit=player.level().clip(new net.minecraft.world.level.ClipContext(eye,point,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));return hit.getType()==HitResult.Type.MISS||hit.getLocation().distanceTo(eye)+.025>=point.distanceTo(eye);}
    public static boolean clear(Level level,Host host,Instrument.Selectable mount){for(var pos:BlockPos.betweenClosedStream(mount.bounds()).toList()){if(!level.hasChunkAt(pos))return false;if(pos.equals(host.entity().getBlockPos())||level.getBlockState(pos).canBeReplaced())continue;var owner=at(level,pos);if(owner==null||owner.identity()!=host.identity())return false;}return true;}
    public record FittingSelection(Host host,Instrument.Selectable mount,double distance){}
    public static FittingSelection selectFitting(Collection<Host> hosts,Vec3 eye,Vec3 direction,double reach,boolean installedOnly){
        FittingSelection best=null;var end=eye.add(direction.scale(reach));
        for(var host:hosts){
            Instrument.Selectable mount=host.pipe()?host.entity() instanceof FluidDeviceBlockEntity pipe?PipeAccessory.of(pipe):null:LevelGauge.of(host);
            if(mount==null||installedOnly&&(host.pipe()?legacy(host.entity().getBlockState())==null:!host.metadata().levelGauge()))continue;
            // Installation only supports the reservoir gauge; pipe fittings keep their existing item path.
            if(!installedOnly&&host.pipe())continue;
            var hit=mount.bounds().clip(eye,end);if(hit.isEmpty())continue;double distance=hit.get().distanceTo(eye);
            if(best==null||distance<best.distance())best=new FittingSelection(host,mount,distance);
        }
        return best;
    }
    /** Full accessory bounds shared by install ghosts, clearance, hover and wrench validation. */
    public record LevelGauge(AABB bounds) implements Instrument.Selectable {
        private static final Map<EquipmentShape,AABB> BOUNDS=new ConcurrentHashMap<>();
        @Override public String id(){return "tank_level";}
        public static LevelGauge of(Host host){
            if(!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||shell.shape().kind()!=EquipmentShape.Kind.TANK)return null;
            if(BOUNDS.size()>64)BOUNDS.clear();
            var box=BOUNDS.computeIfAbsent(shell.shape(),LevelGauge::selectionBounds);
            return new LevelGauge(box.move(shell.origin()));
        }
        static AABB selectionBounds(EquipmentShape shape){
            var bounds=TankGaugeGeometry.bounds(shape);
            // The drain ends at grade. Reach tolerance must not make the foundation below it block installation.
            return new AABB(bounds[0]-.008,bounds[1],bounds[2]-.008,bounds[3]+.008,bounds[4]+.008,bounds[5]+.008);
        }

    }
    public static Instrument.Type legacy(net.minecraft.world.level.block.state.BlockState state){if(FluidDeviceBlock.hasPump(state))return Instrument.Type.PUMP;if(FluidDeviceBlock.hasCompressor(state))return Instrument.Type.COMPRESSOR;if(FluidDeviceBlock.hasFilter(state))return Instrument.Type.FILTER;if(FluidDeviceBlock.hasSightGlass(state))return Instrument.Type.SIGHT_GLASS;if(FluidDeviceBlock.hasPressureValve(state))return Instrument.Type.PRESSURE_VALVE;if(!state.hasProperty(FluidDeviceBlock.SHUTOFF))return null;return switch(FluidDeviceBlock.cutoff(state)){case NONE->null;case CHECK->Instrument.Type.CHECK;default->Instrument.Type.SHUTOFF;};}
    /** Wrench hit boxes come from the existing fittings' actual model extents, including tall actuators/handles. */
    public record PipeAccessory(AABB bounds) implements Instrument.Selectable {
        @Override public String id(){return "pipe_accessory";}
        private record Look(LineGeometry.Key key,Instrument.Type type,boolean open,SightGlassGeometry.Axis window){}
        private static final Map<Look,AABB> CACHE=new ConcurrentHashMap<>();
        public static PipeAccessory of(FluidDeviceBlockEntity pipe){var s=pipe.getBlockState();var type=InstrumentHosts.legacy(s);if(type==null)return null;var drawn=pipe.getModelData().get(FluidDeviceBlockEntity.DRAWN);var key=new LineGeometry.Key(pipe.kind(),s.getValue(FluidDeviceBlock.FACING).ordinal(),FluidDeviceBlock.connectionMask(s),PipeSizes.code(PipeSizes.outside(pipe.bore())),drawn==null?0:drawn.faces(),drawn==null?0:drawn.flanged(),drawn!=null&&drawn.spool());var look=new Look(key,type,FluidDeviceBlock.cutoff(s)==com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Cutoff.OPEN,SightGlassGeometry.Axis.valueOf(s.getValue(FluidDeviceBlock.SIGHT_GLASS_AXIS).name()));return new PipeAccessory(CACHE.computeIfAbsent(look,PipeAccessory::build).move(pipe.getBlockPos()));}
        private static AABB build(Look look){List<Mesh.Polygon> polygons=switch(look.type){case PUMP,COMPRESSOR->InlineMoverGeometry.polygons(look.key,look.type==Instrument.Type.COMPRESSOR,false);case FILTER->LineGeometry.filterPolygons(look.key);case PRESSURE_VALVE->ValveGeometry.pressure(look.key,false);case SHUTOFF->ValveGeometry.shutoff(look.key,look.open,false);case CHECK->ValveGeometry.check(look.key,false);case SIGHT_GLASS->{var g=SightGlassGeometry.build(new SightGlassGeometry.Key(look.key,look.window),false);var all=new ArrayList<>(g.metal());all.addAll(g.glass());yield all;}default->throw new IllegalArgumentException("Not a pipe fitting");};double x=Double.POSITIVE_INFINITY,y=x,z=x,X=Double.NEGATIVE_INFINITY,Y=X,Z=X;for(var p:polygons){var v=p.positions();for(int i=0;i<v.length;i+=3){x=Math.min(x,v[i]);y=Math.min(y,v[i+1]);z=Math.min(z,v[i+2]);X=Math.max(X,v[i]);Y=Math.max(Y,v[i+1]);Z=Math.max(Z,v[i+2]);}}return new AABB(x,y,z,X,Y,Z).inflate(.015);}
    }
}
