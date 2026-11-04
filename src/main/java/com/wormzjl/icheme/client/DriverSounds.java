package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
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
 * ({@link #request}). Until then no driver sounds. No process state is inferred on client ticks.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class DriverSounds {
    private static final Set<EquipmentShellBlockEntity> CELLS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<BlockPos> WATCHED=new HashSet<>();
    private static final Map<BlockPos,Double> SPEEDS=new HashMap<>();
    private static final Map<BlockPos,Loop> LOOPS=new HashMap<>();
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
        if(!WATCHED.contains(origin)||shell(origin)==null)return;
        double share=running&&speed>0?Math.min(speed,1):0;
        SPEEDS.put(origin,share);if(share==0)stop(origin);
    }
    /** The driver watch request (W3): the drivers near the player in, the ones out of reach out. */
    private static void request(List<BlockPos> watch,List<BlockPos> unwatch){FluidNetwork.requestDrivers(watch,unwatch);}
    public static void clear(){for(var origin:List.copyOf(LOOPS.keySet()))stop(origin);SPEEDS.clear();WATCHED.clear();CELLS.clear();world=null;age=0;}
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
    private static void stop(BlockPos origin){var loop=LOOPS.remove(origin);if(loop!=null)Minecraft.getInstance().getSoundManager().stop(loop);}
    private static void drop(BlockPos origin){SPEEDS.remove(origin);stop(origin);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            if(mc.getConnection()!=null&&!WATCHED.isEmpty())request(List.of(),List.copyOf(WATCHED));
            for(var origin:List.copyOf(LOOPS.keySet()))stop(origin);SPEEDS.clear();WATCHED.clear();
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
        var unwatch=WATCHED.stream().filter(o->!wanted.contains(o)).toList();var watch=wanted.stream().filter(o->!WATCHED.contains(o)).toList();
        WATCHED.removeAll(unwatch);unwatch.forEach(DriverSounds::drop);WATCHED.addAll(watch);
        if(!watch.isEmpty()||!unwatch.isEmpty())request(watch,unwatch);
        for(var origin:List.copyOf(SPEEDS.keySet())) {
            var shell=shell(origin);
            boolean audible=SPEEDS.get(origin)>0&&shell!=null&&shell.getBlockPos().distToCenterSqr(point)<=24*24
                    &&mc.options.getSoundSourceVolume(SoundSource.BLOCKS)>0&&mc.options.getSoundSourceVolume(SoundSource.MASTER)>0;
            if(!audible){stop(origin);continue;}
            var loop=LOOPS.get(origin);
            if(loop==null||!mc.getSoundManager().isActive(loop)){loop=new Loop(origin,shell,mc.level);LOOPS.put(origin,loop);mc.getSoundManager().play(loop);}
        }
    }
    private static final class Loop extends AbstractTickableSoundInstance {
        private final BlockPos origin;
        private final EquipmentShellBlockEntity shell;
        private final ClientLevel level;
        private Loop(BlockPos origin,EquipmentShellBlockEntity shell,ClientLevel level) {
            super(sound(shell.shape().kind()),SoundSource.BLOCKS,RandomSource.create());
            this.origin=origin;this.shell=shell;this.level=level;looping=true;delay=0;volume=0;pitch=1;refreshVolume();
            attenuation=net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR;
            // The centre of the driver's cells: a motor's body and coupling, a turbine's casing.
            var cells=ShapeRasterizer.layout(shell.shape()).shell();double cx=0,cy=0,cz=0;
            for(var cell:cells){cx+=cell.x();cy+=cell.y();cz+=cell.z();}
            x=origin.getX()+cx/cells.size()+.5;y=origin.getY()+cy/cells.size()+.5;z=origin.getZ()+cz/cells.size()+.5;
        }
        private static net.minecraft.sounds.SoundEvent sound(EquipmentShape.Kind kind) {
            return (kind==EquipmentShape.Kind.STEAM_TURBINE?ModBlocks.TURBINE_RUNNING:kind==EquipmentShape.Kind.GENERATOR?ModBlocks.GENERATOR_RUNNING:ModBlocks.MOTOR_RUNNING).get();
        }
        @Override public void tick() {
            var mc=Minecraft.getInstance();var speed=SPEEDS.get(origin);
            if(mc.level!=level||speed==null||speed<=0||!WATCHED.contains(origin)||!origin.equals(origin(shell))||mc.player==null
                    ||shell.getBlockPos().distToCenterSqr(mc.player.position())>24*24)stop();
            else refreshVolume();
        }
        private void refreshVolume(){var speed=SPEEDS.get(origin);volume=shell.shape()==null||speed==null?0:UnitDesign.soundGain(shell.shape(),speed);pitch=shell.shape()==null?1:UnitDesign.soundPitch(shell.shape(),speed==null?1:speed);}
    }
}
