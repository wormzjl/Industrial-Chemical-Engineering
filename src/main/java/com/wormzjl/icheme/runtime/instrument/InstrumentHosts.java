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
    private static final Map<Level,Map<BlockPos,Double>> LOADED=new WeakHashMap<>();
    private static final Map<Level,Map<BlockPos,Double>> INSTALLED=new WeakHashMap<>();
    public record Host(long identity,BlockEntity entity,List<Instrument.Mount> mounts,boolean pipe) {
        public Host{mounts=List.copyOf(mounts);}
        public Instrument.Metadata metadata(){return ((Instrument.Holder)entity).instruments();}
        public Instrument.Mount mount(String id){return mounts.stream().filter(m->m.id().equals(id)).findFirst().orElse(null);}
        public boolean occupied(Instrument.Mount mount){return pipe?legacy(entity.getBlockState())!=null||!metadata().installed().isEmpty()||!metadata().sensors().isEmpty():metadata().installed().stream().anyMatch(i->i.key().mount().equals(mount.id()))||metadata().sensors().stream().anyMatch(i->i.key().mount().equals(mount.id()));}
    }
    public record Selection(Host host,Instrument.Mount mount,double distance) {}
    private static final Object INDEX_LOCK=new Object();
    public static void loaded(BlockEntity be){
        if(be.getLevel()==null||!(be instanceof FluidDeviceBlockEntity pipe&&pipe.getBlockState().getBlock() instanceof FluidDeviceBlock block&&block.kind()==TopologyCompiler.Kind.PIPE||be instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&shell.fluidIdentity()>0))return;
        double extent=2;
        if(be instanceof EquipmentShellBlockEntity shell){var s=shell.shape();extent=Math.sqrt((double)s.sizeX()*s.sizeX()+(double)s.sizeY()*s.sizeY()+(double)s.sizeZ()*s.sizeZ())+2;}
        // The index and its equipped subset are published together; callers inspect hosts outside the lock.
        synchronized(INDEX_LOCK){
            LOADED.computeIfAbsent(be.getLevel(),l->new HashMap<>()).put(be.getBlockPos().immutable(),extent);
            var equipped=INSTALLED.computeIfAbsent(be.getLevel(),l->new HashMap<>());
            if(be instanceof Instrument.Holder h&&(!h.instruments().installed().isEmpty()||!h.instruments().sensors().isEmpty()))equipped.put(be.getBlockPos().immutable(),extent);else equipped.remove(be.getBlockPos());
        }
    }
    private static Map<BlockPos,Double> loaded(Level level,boolean installed){synchronized(INDEX_LOCK){return Map.copyOf((installed?INSTALLED:LOADED).getOrDefault(level,Map.of()));}}
    public static void unloaded(BlockEntity be){synchronized(INDEX_LOCK){var set=LOADED.get(be.getLevel());if(set!=null)set.remove(be.getBlockPos());var equipped=INSTALLED.get(be.getLevel());if(equipped!=null)equipped.remove(be.getBlockPos());}}
    public static List<Host> nearby(Level level,Vec3 centre,double radius){return nearby(level,centre,radius,false);}
    public static List<Host> nearby(Level level,Vec3 centre,double radius,boolean installedOnly){
        var result=new LinkedHashMap<Long,Host>();
        for(var entry:loaded(level,installedOnly).entrySet()){
            var pos=entry.getKey();double reach=radius+entry.getValue();
            if(pos.getCenter().distanceToSqr(centre)>reach*reach||!level.hasChunkAt(pos))continue;
            var h=at(level,pos);if(h==null)continue;
            var mounts=new ArrayList<>(h.mounts());mounts.addAll(Sensor.mounts(h,Sensor.Kind.LEVEL));mounts.addAll(Sensor.mounts(h,Sensor.Kind.FLOW));mounts.addAll(Sensor.mounts(h,Sensor.Kind.SPEED));
            if(mounts.stream().anyMatch(m->m.centre().distanceToSqr(centre)<=radius*radius))result.putIfAbsent(h.identity(),h);
        }
        return List.copyOf(result.values());
    }
    public static Host at(Level level,BlockPos position){if(!level.hasChunkAt(position))return null;var be=level.getBlockEntity(position);
        if(be instanceof FluidDeviceBlockEntity pipe&&pipe.getBlockState().getBlock() instanceof FluidDeviceBlock block&&block.kind()==TopologyCompiler.Kind.PIPE&&pipe.fluidIdentity()>0)return new Host(pipe.fluidIdentity(),pipe,pipe.transition()?List.of():pipeMounts(position,pipe.bore(),pipe.getBlockState()),true);
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
    public static List<Instrument.Mount> reservoirMounts(EquipmentShape shape,BlockPos origin){return com.wormzjl.icheme.science.equipment.InstrumentGeometry.sockets(shape).stream().map(t->new Instrument.Mount(t.id(),new Vec3(origin.getX()+t.x(),origin.getY()+t.y(),origin.getZ()+t.z()),reservoirFace(shape),Set.of(t.pressure()?GaugeSettings.Kind.PRESSURE:GaugeSettings.Kind.TEMPERATURE),t.top())).toList();}
    private static Direction reservoirFace(EquipmentShape shape){if(!shape.saddles())return Direction.valueOf(shape.front().name());var n=shape.transform().direction(new double[]{0,0,-1});return Direction.getNearest(n[0],n[1],n[2]);}
    public static Selection select(Collection<Host> hosts,Vec3 eye,Vec3 direction,double reach,GaugeSettings.Kind kind,boolean installedOnly){Selection best=null;Vec3 end=eye.add(direction.scale(reach));for(var h:hosts)for(var m:h.mounts){if(kind!=null&&!m.kinds().contains(kind))continue;if(installedOnly&&h.metadata().installed().stream().noneMatch(i->i.key().mount().equals(m.id())))continue;var hit=m.bounds().clip(eye,end);if(hit.isEmpty())continue;double d=hit.get().distanceTo(eye);if(best==null||d<best.distance)best=new Selection(h,m,d);}return best;}
    public static boolean reachable(net.minecraft.world.entity.player.Player player,Host host,Instrument.Selectable mount){var eye=player.getEyePosition();if(mount.bounds().distanceToSqr(eye)>player.blockInteractionRange()*player.blockInteractionRange())return false;var point=mount.bounds().clip(eye,mount.bounds().getCenter()).orElse(mount.bounds().getCenter());var hit=player.level().clip(new net.minecraft.world.level.ClipContext(eye,point,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));return hit.getType()==HitResult.Type.MISS||hit.getLocation().distanceTo(eye)+.025>=point.distanceTo(eye);}
    public static boolean clear(Level level,Host host,Instrument.Mount mount){for(var pos:BlockPos.betweenClosedStream(mount.bounds()).toList()){if(!level.hasChunkAt(pos))return false;if(pos.equals(host.entity().getBlockPos())||level.getBlockState(pos).canBeReplaced())continue;var owner=at(level,pos);if(owner==null||owner.identity()!=host.identity())return false;}return true;}
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
