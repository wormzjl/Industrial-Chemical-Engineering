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
        public boolean occupied(Instrument.Mount mount){return pipe?legacy(entity.getBlockState())!=null||metadata().safetyValve()||!metadata().installed().isEmpty()||!metadata().sensors().isEmpty():metadata().installed().stream().anyMatch(i->i.key().mount().equals(mount.id()))||metadata().sensors().stream().anyMatch(i->i.key().mount().equals(mount.id()));}
    }
    public record Selection(Host host,Instrument.Mount mount,double distance) {}
    private static final Object INDEX_LOCK=new Object();
    public static void loaded(BlockEntity be){
        com.wormzjl.icheme.runtime.power.PowerNetwork.loaded(be);
        if(be.getLevel()==null||!(be instanceof FluidDeviceBlockEntity pipe&&pipe.getBlockState().getBlock() instanceof FluidDeviceBlock block&&(block.kind()==TopologyCompiler.Kind.PIPE||block.kind()==TopologyCompiler.Kind.DIVERTER_VALVE)||be instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&(shell.fluidIdentity()>0||shell.instruments().dataPort()!=null||shell.instruments().powerPort()!=null)||be instanceof CreativePowerBlockEntity))return;
        double extent=2;
        if(be instanceof EquipmentShellBlockEntity shell){var s=shell.shape();extent=Math.sqrt((double)s.sizeX()*s.sizeX()+(double)s.sizeY()*s.sizeY()+(double)s.sizeZ()*s.sizeZ())+2;}
        // The index and its equipped subset are published together; callers inspect hosts outside the lock.
        synchronized(INDEX_LOCK){
            LOADED.computeIfAbsent(be.getLevel(),l->new HashMap<>()).put(be.getBlockPos().immutable(),extent);
            var equipped=INSTALLED.computeIfAbsent(be.getLevel(),l->new HashMap<>());
            if(be instanceof Instrument.Holder h&&(!h.instruments().installed().isEmpty()||!h.instruments().sensors().isEmpty()||h.instruments().dataPort()!=null||h.instruments().powerPort()!=null||h.instruments().fitting()!=null||h.instruments().safetyValve()))equipped.put(be.getBlockPos().immutable(),extent);else equipped.remove(be.getBlockPos());
        }
    }
    private static Map<BlockPos,Double> loaded(Level level,boolean installed){synchronized(INDEX_LOCK){return Map.copyOf((installed?INSTALLED:LOADED).getOrDefault(level,Map.of()));}}
    public static void unloaded(BlockEntity be){synchronized(INDEX_LOCK){var set=LOADED.get(be.getLevel());if(set!=null)set.remove(be.getBlockPos());var equipped=INSTALLED.get(be.getLevel());if(equipped!=null)equipped.remove(be.getBlockPos());}}
    public static List<com.wormzjl.icheme.runtime.control.DataPort> dataPorts(Level level,Vec3 centre,double radius){
        var result=new ArrayList<com.wormzjl.icheme.runtime.control.DataPort>();
        for(var entry:loaded(level,true).entrySet()){
            var pos=entry.getKey();double reach=radius+entry.getValue();if(pos.getCenter().distanceToSqr(centre)>reach*reach||!level.hasChunkAt(pos))continue;
            if(level.getBlockEntity(pos) instanceof Instrument.Holder holder){var port=holder.instruments().dataPort();if(port!=null&&port.centre().distanceToSqr(centre)<=radius*radius)result.add(port);}
        }return List.copyOf(result);
    }
    public static List<com.wormzjl.icheme.runtime.control.DataPort> powerPorts(Level level,Vec3 centre,double radius){
        var result=new ArrayList<com.wormzjl.icheme.runtime.control.DataPort>();
        for(var entry:loaded(level,true).entrySet()){
            var pos=entry.getKey();double reach=radius+entry.getValue();if(pos.getCenter().distanceToSqr(centre)>reach*reach||!level.hasChunkAt(pos))continue;
            if(level.getBlockEntity(pos) instanceof Instrument.Holder holder)for(var port:holder.powerPorts())if(port.centre().distanceToSqr(centre)<=radius*radius)result.add(port);
        }return List.copyOf(result);
    }
    public static List<Host> nearby(Level level,Vec3 centre,double radius){return nearby(level,centre,radius,false);}
    public static List<Host> nearby(Level level,Vec3 centre,double radius,boolean installedOnly){
        var result=new LinkedHashMap<Long,Host>();
        for(var entry:loaded(level,installedOnly).entrySet()){
            var pos=entry.getKey();double reach=radius+entry.getValue();
            if(pos.getCenter().distanceToSqr(centre)>reach*reach||!level.hasChunkAt(pos))continue;
            var h=at(level,pos);if(h==null)continue;
            var mounts=new ArrayList<>(h.mounts());mounts.addAll(Sensor.mounts(h,Sensor.Kind.LEVEL));mounts.addAll(Sensor.mounts(h,Sensor.Kind.FLOW));mounts.addAll(Sensor.mounts(h,Sensor.Kind.SPEED));
            var gauge=vesselFitting(h);if(gauge==null)gauge=ReliefValve.of(h);
            if(h.entity() instanceof EquipmentShellBlockEntity shell&&shell.cell().equals(ShapeRasterizer.fluidCell(shell.shape()))&&EquipmentInstruments.of(shell.shape()).probes().stream().anyMatch(p->{var q=p.centre();return centre.distanceToSqr(q[0]+shell.origin().getX(),q[1]+shell.origin().getY(),q[2]+shell.origin().getZ())<=radius*radius;})||mounts.stream().anyMatch(m->m.centre().distanceToSqr(centre)<=radius*radius)||gauge!=null&&gauge.bounds().distanceToSqr(centre)<=radius*radius)result.putIfAbsent(h.identity(),h);
        }
        return List.copyOf(result.values());
    }
    public static Host at(Level level,BlockPos position){if(!level.hasChunkAt(position))return null;var be=level.getBlockEntity(position);
        if(be instanceof FluidDeviceBlockEntity pipe&&pipe.getBlockState().getBlock() instanceof FluidDeviceBlock block&&block.kind()==TopologyCompiler.Kind.PIPE&&pipe.fluidIdentity()>0)return new Host(pipe.fluidIdentity(),pipe,pipe.transition()?List.of():pipeMounts(position,pipe.bore(),pipe.getBlockState()),true);
        // A diverter valve (batch 2026-11-09-batch-distiller, owner decision O2): a host for its data port, no mounts.
        if(be instanceof FluidDeviceBlockEntity valve&&valve.getBlockState().getBlock() instanceof FluidDeviceBlock block&&block.kind()==TopologyCompiler.Kind.DIVERTER_VALVE&&valve.fluidIdentity()>0)return new Host(valve.fluidIdentity(),valve,List.of(),false);
        if(be instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var shape=shell.shape();
            if(shell.fluidIdentity()>0)return new Host(shell.fluidIdentity(),shell,reservoirMounts(shape,shell.origin(),shell.cell()),false);
            var cells=ShapeRasterizer.fluidCells(shape);
            var c=cells.stream().min(Comparator.comparingDouble(cell->
                    com.wormzjl.icheme.science.math.DeterministicMath.pow(cell.x()-shell.cell().x(),2)+com.wormzjl.icheme.science.math.DeterministicMath.pow(cell.y()-shell.cell().y(),2)+com.wormzjl.icheme.science.math.DeterministicMath.pow(cell.z()-shell.cell().z(),2))).orElse(null);
            if(c==null)return null;
            var root=shell.origin().offset(c.x(),c.y(),c.z());
            if(!root.equals(position))return at(level,root);
        }
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
    public static List<Instrument.Mount> reservoirMounts(EquipmentShape shape,BlockPos origin){return reservoirMounts(shape,origin,ShapeRasterizer.fluidCell(shape));}
    private static List<Instrument.Mount> reservoirMounts(EquipmentShape shape,BlockPos origin,ShapeRasterizer.Cell node){return EquipmentInstruments.of(shape).probes().stream().filter(p->p.manual()&&(p.quantity()==EquipmentInstruments.Quantity.PRESSURE||p.quantity()==EquipmentInstruments.Quantity.TEMPERATURE)).map(p->equipmentMount(p,origin)).toList();}
    public static Instrument.Mount equipmentMount(EquipmentInstruments.Probe probe,BlockPos origin){var p=probe.centre();var tap=probe.tap();var n=probe.normal();return new Instrument.Mount(probe.id(),new Vec3(origin.getX()+p[0],origin.getY()+p[1],origin.getZ()+p[2]),Direction.values()[probe.face()],Set.of(probe.quantity()==EquipmentInstruments.Quantity.TEMPERATURE?GaugeSettings.Kind.TEMPERATURE:GaugeSettings.Kind.PRESSURE),probe.id().equals("pressure_top"),new Vec3(origin.getX()+tap[0],origin.getY()+tap[1],origin.getZ()+tap[2]),probe.top(),probe.depth(),new Vec3(n[0],n[1],n[2]));}

    public static Selection select(Collection<Host> hosts,Vec3 eye,Vec3 direction,double reach,GaugeSettings.Kind kind,boolean installedOnly){Selection best=null;Vec3 end=eye.add(direction.scale(reach));for(var h:hosts)for(var m:h.mounts){if(kind!=null&&!m.kinds().contains(kind))continue;if(installedOnly&&h.metadata().installed().stream().noneMatch(i->i.key().mount().equals(m.id())))continue;var hit=m.bounds().clip(eye,end);if(hit.isEmpty())continue;double d=hit.get().distanceTo(eye);if(best==null||d<best.distance)best=new Selection(h,m,d);}return best;}
    public static boolean reachable(net.minecraft.world.entity.player.Player player,Host host,Instrument.Selectable mount){var eye=player.getEyePosition();if(mount.bounds().distanceToSqr(eye)>player.blockInteractionRange()*player.blockInteractionRange())return false;var point=mount.bounds().clip(eye,mount.bounds().getCenter()).orElse(mount.bounds().getCenter());var hit=player.level().clip(new net.minecraft.world.level.ClipContext(eye,point,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));return hit.getType()==HitResult.Type.MISS||hit.getLocation().distanceTo(eye)+.025>=point.distanceTo(eye);}
    public static boolean clear(Level level,Host host,Instrument.Selectable mount){for(var pos:BlockPos.betweenClosedStream(mount.bounds()).map(BlockPos::immutable).toList()){if(!level.hasChunkAt(pos))return false;if(pos.equals(host.entity().getBlockPos())||level.getBlockState(pos).canBeReplaced())continue;var owner=at(level,pos);if(owner==null||owner.identity()!=host.identity())return false;}return true;}
    public record FittingSelection(Host host,Instrument.Selectable mount,double distance){}
    public static FittingSelection selectFitting(Collection<Host> hosts,Vec3 eye,Vec3 direction,double reach,boolean installedOnly){return selectFitting(hosts,eye,direction,reach,installedOnly,null);}
    /**
     * The fitting place the player looks at: to remove ({@code installedOnly}), a fitted one; to fit one of {@code type}, a free
     * place for it. A pipe's fittings other than the safety valve keep their own item path.
     */
    public static FittingSelection selectFitting(Collection<Host> hosts,Vec3 eye,Vec3 direction,double reach,boolean installedOnly,Instrument.Type type){
        FittingSelection best=null;var end=eye.add(direction.scale(reach));
        for(var host:hosts){
            var mounts=new ArrayList<Instrument.Selectable>();
            if(installedOnly){
                if(host.pipe()){if(host.entity() instanceof FluidDeviceBlockEntity pipe&&legacy(host.entity().getBlockState())!=null)mounts.add(PipeAccessory.of(pipe));}
                else if(host.metadata().fitting()!=null)mounts.add(vesselFitting(host));
                if(host.metadata().safetyValve())mounts.add(ReliefValve.of(host));
            }else if(type!=null&&(!host.pipe()||type==Instrument.Type.SAFETY_VALVE))mounts.add(type==Instrument.Type.SAFETY_VALVE?ReliefValve.of(host,toward(direction)):vesselFitting(host,type));
            for(var mount:mounts){
                if(mount==null)continue;
                var hit=mount.bounds().clip(eye,end);if(hit.isEmpty())continue;double distance=hit.get().distanceTo(eye);
                if(best==null||distance<best.distance())best=new FittingSelection(host,mount,distance);
            }
        }
        return best;
    }
    /** The place on {@code host} of its own fitting of {@code type}: a tank's level gauge, a drum's gas blanketing valve, a drum's or a pipe's safety valve; null where it takes none. */
    public static Instrument.Selectable vesselFitting(Host host,Instrument.Type type){
        return switch(type){case LEVEL_GAUGE->LevelGauge.of(host);case BLANKET_VALVE->BlanketValve.of(host);case SAFETY_VALVE->ReliefValve.of(host);default->null;};
    }
    /** The horizontal face toward a player who looks along {@code look}: a safety valve's outlet faces the player who fits it. */
    public static Direction toward(Vec3 look){return Direction.getNearest(-look.x,0,-look.z);}
    /** The fitting type a place of {@link #vesselFitting} takes. */
    public static Instrument.Type typeOf(Instrument.Selectable mount){return mount instanceof LevelGauge?Instrument.Type.LEVEL_GAUGE:mount instanceof BlanketValve?Instrument.Type.BLANKET_VALVE:mount instanceof ReliefValve?Instrument.Type.SAFETY_VALVE:null;}
    /**
     * A pressure safety valve (batch 2026-11-11-gas-blanketing): its bounds, and its outlet: on a drum the relief port it gives
     * the drum ({@code port}), on a pipe the face it leaves by ({@code face}).
     */
    public record ReliefValve(AABB bounds,com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Port port,com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction face) implements Instrument.Selectable {
        @Override public String id(){return "safety_valve";}
        /** A fitted valve, its outlet out of its registration's face; on a host without one, the valve as fitted with the default face. */
        public static ReliefValve of(Host host){return of(host,null);}
        /** The valve on {@code host}: a fitted one out of its own face, a valve still to fit with its outlet {@code toward} the player (null: the default); null where that face is taken. */
        public static ReliefValve of(Host host,Direction toward){
            var face=host.metadata().safetyValve()&&host.metadata().reliefFace()!=null?host.metadata().reliefFace():toward;
            if(host.entity() instanceof FluidDeviceBlockEntity pipe)return host.pipe()?onPipe(pipe,face):null;
            if(!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||shell.getLevel()==null)return null;
            return of(shell.getLevel(),shell.origin(),shell.shape(),face);
        }
        public static ReliefValve of(Level level,BlockPos origin,EquipmentShape shape){return of(level,origin,shape,null);}
        /** The valve on a drum, its outlet out of {@code face} (null: the default direction); null where that face is taken or not horizontal. */
        public static ReliefValve of(Level level,BlockPos origin,EquipmentShape shape,Direction asked){
            if(asked!=null&&asked.getAxis()==Direction.Axis.Y)return null;
            var place=asked==null?ReliefValveGeometry.of(shape):ReliefValveGeometry.of(shape,asked.getAxis().ordinal(),asked.getAxisDirection().getStep());if(place==null)return null;
            var b=place.bounds();var cell=place.cell();var face=place.face();
            var position=new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position(level.dimension().location().toString(),origin.getX()+cell[0],origin.getY()+cell[1],origin.getZ()+cell[2]);
            var direction=com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(face[0],face[1],face[2]).name());
            return new ReliefValve(new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(origin).inflate(.008),
                    new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Port(position,direction,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.VAPOR,com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.ConnectionRole.RELIEF),direction);
        }
        /** The valve on {@code pipe}, its outlet out of {@code face} (null: {@link #outletFace}); null where that face is not a free side across the pipe. */
        static ReliefValve onPipe(FluidDeviceBlockEntity pipe,Direction face){
            var state=pipe.getBlockState();int mask=FluidDeviceBlock.connectionMask(state);
            var axis=pipeAxis(mask,state.getValue(FluidDeviceBlock.FACING));
            if(face==null)face=outletFace(mask,axis);if(face==null||face.getAxis()==axis||face.getAxis()==Direction.Axis.Y||(mask&1<<face.ordinal())!=0)return null;
            var place=ReliefValveGeometry.onPipe(face.getAxis().ordinal(),face.getAxisDirection().getStep(),axis.ordinal(),PipeSizes.outside(pipe.bore()));
            var b=place.bounds();
            return new ReliefValve(new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(pipe.getBlockPos()).inflate(.008),null,com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.valueOf(face.name()));
        }
        /** A pipe's axis: that of its first connection, or its facing's on a pipe without one. */
        public static Direction.Axis pipeAxis(int mask,Direction facing){for(var d:Direction.values())if((mask&1<<d.ordinal())!=0)return d.getAxis();return facing.getAxis();}
        /** The default face a safety valve on a pipe leaves by: the first free side across the pipe; null where none is. */
        public static Direction outletFace(int mask,Direction.Axis axis){
            for(var d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST})if(d.getAxis()!=axis&&(mask&1<<d.ordinal())==0)return d;
            return null;
        }
    }
    /** The place of {@code host}'s own primary fitting: a tank's level gauge, a drum's gas blanketing valve; null for every other host. */
    public static Instrument.Selectable vesselFitting(Host host){var gauge=LevelGauge.of(host);return gauge!=null?gauge:BlanketValve.of(host);}
    /** The type of the fitting {@link #vesselFitting} places, or null. */
    public static Instrument.Type vesselType(Host host){return LevelGauge.of(host)!=null?Instrument.Type.LEVEL_GAUGE:BlanketValve.of(host)!=null?Instrument.Type.BLANKET_VALVE:null;}
    /** A drum's gas blanketing valve (batch 2026-11-11-gas-blanketing): its bounds, and the port its gas inlet gives the drum. */
    public record BlanketValve(AABB bounds,com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Port port) implements Instrument.Selectable {
        @Override public String id(){return "blanket_valve";}
        public static BlanketValve of(Host host){
            if(!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||shell.getLevel()==null)return null;
            return of(shell.getLevel(),shell.origin(),shell.shape());
        }
        public static BlanketValve of(Level level,BlockPos origin,EquipmentShape shape){
            var place=com.wormzjl.icheme.science.equipment.BlanketValveGeometry.of(shape);if(place==null)return null;
            var b=place.bounds();var cell=place.cell();var face=place.face();
            var position=new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position(level.dimension().location().toString(),origin.getX()+cell[0],origin.getY()+cell[1],origin.getZ()+cell[2]);
            var direction=com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(face[0],face[1],face[2]).name());
            return new BlanketValve(new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(origin).inflate(.008),
                    new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Port(position,direction,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.VAPOR,com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.ConnectionRole.BLANKET));
        }
    }
    /** Full accessory bounds shared by install ghosts, clearance, hover and wrench validation. */
    public record LevelGauge(AABB bounds) implements Instrument.Selectable {
        private static final Map<EquipmentShape,AABB> BOUNDS=new ConcurrentHashMap<>();
        @Override public String id(){return "tank_level";}
        public static LevelGauge of(Host host){
            // A floating-roof tank's, or a liquid converter's (batch 2026-11-12-fluid-conversion, plan D11): every shape with a gauge place.
            if(!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||com.wormzjl.icheme.science.equipment.GaugePlace.of(shell.shape())==null)return null;
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
