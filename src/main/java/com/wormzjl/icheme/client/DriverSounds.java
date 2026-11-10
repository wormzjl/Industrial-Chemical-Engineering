package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread-only positional loops of the drivers ({@link UnitDesign#driver}: motor, generator, steam turbine), apart
 * from their machine's casing loop ({@link MoverSounds}; decision D26, batch 2026-11-03-rotating-machinery). A driver is
 * known by its structure's origin and tracked at its controller cell; its loop sits at the centre of its cells, its gain
 * {@link UnitDesign#soundGain(EquipmentShape, double)} of its rated power and speed share, its pitch {@link UnitDesign#soundPitch(EquipmentShape, double)} of its rpm over the loop's. The running state and the
 * speed share come from the server's shaft trains (W3) through {@link #accept}; W3 also sends the watch request
 * ({@link #request}). Until then no driver sounds. No process state is inferred on client ticks. The loops are the one
 * loop budget's ({@code FxSounds}, batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md S4 A): the watch stays
 * here, the sources go to the budget ({@link #emitters}).
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class DriverSounds {
    private static final Set<EquipmentShellBlockEntity> CELLS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<BlockPos> WATCHED=new HashSet<>();
    private static final Map<BlockPos,Double> SPEEDS=new HashMap<>();
    private static ClientLevel world;
    private static int age;
    private DriverSounds(){}
    /** Lifecycle callers must be on the client thread. A driver is tracked only at its controller cell. */
    public static void track(BlockEntity entity){if(entity instanceof EquipmentShellBlockEntity shell){if(origin(shell)!=null)CELLS.add(shell);else CELLS.remove(shell);}}
    public static void forget(BlockEntity entity){
        if(!(entity instanceof EquipmentShellBlockEntity shell)||!CELLS.remove(shell))return;
        var gone=WATCHED.stream().filter(o->shell(o)==null).toList();
        WATCHED.removeAll(gone);gone.forEach(DriverSounds::drop);if(!gone.isEmpty())request(List.of(),gone);
    }
    /**
     * The entry point for the server's shaft trains (W3): the driver whose structure has its origin at {@code origin}
     * runs, or not, at {@code speed}, its share of its rated speed (0 to 1). Ignored for a driver this client does not
     * watch. A stopped driver, or one at no speed, is silent.
     */
    public static void accept(BlockPos origin,boolean running,double speed) {
        var shell=WATCHED.contains(origin)?shell(origin):null;
        if(shell==null)return;
        double share=running&&speed>0?Math.min(speed,1):0;
        var before=SPEEDS.put(origin,share);
        if(before!=null&&(before>0)!=(share>0))crossing(shell,share>0);
    }
    /**
     * A prime mover's speed crossed zero (batch 2026-11-10-prime-movers): its start or stop one-shot at its cells' centre. A
     * medium-speed engine (750 rpm and under) starts on air, a high-speed one on its electric starter. Played only on a
     * change between two publications this client saw, never on the first, so a set the player walks up to stays as it is.
     */
    private static void crossing(EquipmentShellBlockEntity shell,boolean start) {
        var shape=shell.shape();var level=Minecraft.getInstance().level;
        if(shape==null||level==null)return;
        var sound=switch(shape.kind()){
            case COMBUSTION_ENGINE->start?(UnitDesign.mediumSpeedEngine(shape)?com.wormzjl.icheme.registry.ModBlocks.ENGINE_START_AIR:com.wormzjl.icheme.registry.ModBlocks.ENGINE_START_ELECTRIC):com.wormzjl.icheme.registry.ModBlocks.ENGINE_STOP;
            case GAS_TURBINE->start?com.wormzjl.icheme.registry.ModBlocks.GAS_TURBINE_START:com.wormzjl.icheme.registry.ModBlocks.GAS_TURBINE_STOP;
            default->null;
        };
        if(sound==null)return;
        var c=centre(shell);
        level.playLocalSound(c[0],c[1],c[2],sound.get(),net.minecraft.sounds.SoundSource.BLOCKS,(float)Math.max(.5,Math.min(2,UnitDesign.soundGain(shape,1))),1F,false);
    }
    /** The centre of a structure's cells, world coordinates. */
    private static double[] centre(EquipmentShellBlockEntity shell) {
        var origin=shell.origin();var cells=ShapeRasterizer.layout(shell.shape()).shell();double cx=0,cy=0,cz=0;
        for(var cell:cells){cx+=cell.x();cy+=cell.y();cz+=cell.z();}
        int n=Math.max(1,cells.size());
        return new double[]{origin.getX()+cx/n+.5,origin.getY()+cy/n+.5,origin.getZ()+cz/n+.5};
    }
    /** The driver watch request (W3): the drivers near the player in, the ones out of reach out. */
    private static void request(List<BlockPos> watch,List<BlockPos> unwatch){FluidNetwork.requestDrivers(watch,unwatch);}
    public static void clear(){SPEEDS.clear();WATCHED.clear();CELLS.clear();world=null;age=0;}
    /** A driver structure's origin, where this is its controller cell; otherwise null. */
    private static @Nullable BlockPos origin(EquipmentShellBlockEntity shell) {
        var shape=shell.shape();var origin=shell.origin();
        if(shell.isRemoved()||shape==null||origin==null||!UnitDesign.driver(shape.kind()))return null;
        var cell=ShapeRasterizer.layout(shape).controller();
        return origin.offset(cell.x(),cell.y(),cell.z()).equals(shell.getBlockPos())?origin:null;
    }
    private static @Nullable EquipmentShellBlockEntity shell(BlockPos origin) {
        var level=Minecraft.getInstance().level;
        for(var cell:CELLS)if(cell.getLevel()==level&&origin.equals(origin(cell)))return cell;
        return null;
    }
    private static void drop(BlockPos origin){SPEEDS.remove(origin);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            if(mc.getConnection()!=null&&!WATCHED.isEmpty())request(List.of(),List.copyOf(WATCHED));
            SPEEDS.clear();WATCHED.clear();
            CELLS.removeIf(cell->cell.getLevel()!=mc.level);world=mc.level;age=0;
        }
        if(mc.level==null||mc.player==null||mc.getConnection()==null||age++%20!=0)return;
        var point=mc.player.position();var near=new ArrayList<BlockPos>();
        for(var cell:List.copyOf(CELLS)) {
            var origin=origin(cell);
            if(origin!=null&&cell.getLevel()==mc.level&&cell.getBlockPos().distToCenterSqr(point)<=(FluidNetwork.MOVER_RANGE-8)*(FluidNetwork.MOVER_RANGE-8))near.add(origin);
        }
        near.sort(Comparator.comparingDouble((BlockPos o)->o.distToCenterSqr(point)).thenComparingLong(BlockPos::asLong));
        var wanted=new LinkedHashSet<BlockPos>();for(var origin:near){if(wanted.size()>=FluidNetwork.MAXIMUM_WATCHED)break;wanted.add(origin);}
        // Registration is queued: retry until a newly placed driver's first speed publication arrives.
        var unwatch=WATCHED.stream().filter(o->!wanted.contains(o)).toList();var watch=wanted.stream().filter(o->!WATCHED.contains(o)||!SPEEDS.containsKey(o)).toList();
        WATCHED.removeAll(unwatch);unwatch.forEach(DriverSounds::drop);WATCHED.addAll(watch);
        if(!watch.isEmpty()||!unwatch.isEmpty())request(watch,unwatch);
    }
    /**
     * The running drivers' sources for the budget (S4 A): each at the centre of its cells (a motor's body and coupling, a
     * turbine's casing), its gain {@link UnitDesign#soundGain(EquipmentShape, double)} as the level {@link com.wormzjl.icheme.client.fx.sound.SoundBudget#FULL} + 20 log10 gain
     * ({@code SoundBudget.level}), its pitch {@link UnitDesign#soundPitch(EquipmentShape, double)}.
     */
    public static void emitters(net.minecraft.world.phys.Vec3 point,List<com.wormzjl.icheme.client.fx.sound.SoundBudget.Emitter> into) {
        for(var e:SPEEDS.entrySet()) {
            var origin=e.getKey();double speed=e.getValue();var shell=speed>0&&WATCHED.contains(origin)?shell(origin):null;
            if(shell==null||shell.shape()==null)continue;
            var cells=ShapeRasterizer.layout(shell.shape()).shell();double cx=0,cy=0,cz=0;
            for(var cell:cells){cx+=cell.x();cy+=cell.y();cz+=cell.z();}
            int n=Math.max(1,cells.size());var kind=shell.shape().kind();
            String loop=switch(kind){
                case STEAM_TURBINE->"turbine_running";
                case GENERATOR->"generator_running";
                case COMBUSTION_ENGINE->UnitDesign.mediumSpeedEngine(shell.shape())?"engine_running_medium":"engine_running";
                case GAS_TURBINE->"gas_turbine_running";
                default->"motor_running";
            };
            into.add(new com.wormzjl.icheme.client.fx.sound.SoundBudget.Emitter(loop,
                    origin.asLong(),origin.getX()+cx/n+.5,origin.getY()+cy/n+.5,origin.getZ()+cz/n+.5,
                    com.wormzjl.icheme.client.fx.sound.SoundBudget.level(UnitDesign.soundGain(shell.shape(),speed)),UnitDesign.soundPitch(shell.shape(),speed)));
        }
    }
}
