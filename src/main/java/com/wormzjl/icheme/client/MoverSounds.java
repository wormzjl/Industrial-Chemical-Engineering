package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.fx.sound.SoundBudget;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client-thread-only requested operating states of the movers, and their sources for the one loop budget
 * ({@code FxSounds}, batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md S4 A): the watch stays here, the
 * loops are the budget's ({@link #emitters}). No process state is inferred on client ticks.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class MoverSounds {
    private static final Set<BlockEntity> CELLS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Target> WATCHED=new HashSet<>();
    private static final Map<Target,FluidNetwork.MoverState> STATES=new HashMap<>();
    private static ClientLevel world;
    private static int age;
    private MoverSounds(){}
    /** Lifecycle callers must be on the client thread. A constructed mover is tracked only at its fluid binding cell, an in-line pump at its pipe block. */
    public static void track(BlockEntity entity){if(target(entity)!=null)CELLS.add(entity);else CELLS.remove(entity);}
    public static void forget(BlockEntity entity){CELLS.remove(entity);forget(entity.getBlockPos());}
    private static Target target(BlockEntity entity) {
        if(entity==null||entity.isRemoved()||!(entity instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()<=0)return null;
        if(entity instanceof EquipmentShellBlockEntity shell) {
            var shape=shell.shape();if(shape==null||!shape.kind().mover())return null;
            var cell=ShapeRasterizer.fluidCell(shape);var origin=shell.origin();
            if(cell==null||origin==null||!origin.offset(cell.x(),cell.y(),cell.z()).equals(shell.getBlockPos()))return null;
        }else if(!(entity instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity)
                ||!com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasPump(entity.getBlockState()))return null;
        // An in-line pump at its pipe block (W10c, owner 2026-11-04): only its crackle and churn play; an in-line compressor is silent.
        return new Target(entity.getBlockPos(),receiver.fluidIdentity());
    }
    private static boolean valid(Target target) {
        var level=Minecraft.getInstance().level;
        return level!=null&&level.hasChunkAt(target.position())&&target.equals(target(level.getBlockEntity(target.position())));
    }
    public static void accept(FluidNetwork.MoverState state) {
        if(!WATCHED.contains(state.target())||!valid(state.target()))return;
        STATES.put(state.target(),state);
    }
    private static void drop(Target target){STATES.remove(target);}
    private static void forget(BlockPos pos) {
        var gone=WATCHED.stream().filter(t->t.position().equals(pos)).toList();WATCHED.removeAll(gone);gone.forEach(MoverSounds::drop);
        if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.request(FluidNetwork.Watch.MOVERS,List.of(),gone);
    }
    public static void clear(){STATES.clear();WATCHED.clear();CELLS.clear();world=null;age=0;}
    /** Unknown or stopped movers are drawn at rest. Non-mover units keep their existing cosmetic animation. */
    public static boolean running(EquipmentShellBlockEntity shell) {
        if(shell.shape()==null||!shell.shape().kind().mover())return true;
        var cell=ShapeRasterizer.fluidCell(shell.shape());var level=shell.getLevel();var origin=shell.origin();if(level==null||cell==null||origin==null)return false;
        var entity=level.getBlockEntity(origin.offset(cell.x(),cell.y(),cell.z()));var target=target(entity);
        var state=target==null?null:STATES.get(target);return state!=null&&state.running();
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            if(mc.getConnection()!=null)FluidNetwork.request(FluidNetwork.Watch.MOVERS,List.of(),List.copyOf(WATCHED));
            STATES.clear();WATCHED.clear();
            CELLS.removeIf(cell->cell.getLevel()!=mc.level);world=mc.level;age=0;
        }
        if(mc.level==null||mc.player==null||mc.getConnection()==null||age++%20!=0)return;
        var point=mc.player.position();var near=new ArrayList<Target>();
        for(var cell:List.copyOf(CELLS)) {
            var target=target(cell);
            if(target!=null&&cell.getLevel()==mc.level&&target.position().distToCenterSqr(point)<=(FluidNetwork.MOVER_RANGE-8)*(FluidNetwork.MOVER_RANGE-8))near.add(target);
        }
        near.sort(Comparator.comparingDouble((Target t)->t.position().distToCenterSqr(point)).thenComparingLong(Target::identity));
        var wanted=new LinkedHashSet<Target>();for(var target:near){if(wanted.size()>=FluidNetwork.MAXIMUM_WATCHED)break;wanted.add(target);}
        var unwatch=WATCHED.stream().filter(t->!wanted.contains(t)).toList();var watch=wanted.stream().filter(t->!WATCHED.contains(t)).toList();
        WATCHED.removeAll(unwatch);unwatch.forEach(MoverSounds::drop);WATCHED.addAll(watch);FluidNetwork.request(FluidNetwork.Watch.MOVERS,watch,unwatch);
    }
    /**
     * The running movers' sources for the budget (S4 A), read from the states last published: a casing's running loop at
     * its gain ({@link UnitDesign#soundGain(EquipmentShape, double)}, decision D26), and a pump's cavitation crackle and gas
     * churn (W10c) at that gain times their bytes over 255; an in-line pump's layers take the smallest casing's gain
     * ({@link UnitDesign#leastGain}). Each gain g is the level 100 + 20 log10 g ({@link SoundBudget#level}), so a lone
     * mover plays at the volume it had before the budget. The pitch follows the shaft's rpm over the loop's (owner 2026-11-04).
     */
    public static void emitters(Vec3 point,List<SoundBudget.Emitter> into) {
        var level=Minecraft.getInstance().level;if(level==null)return;
        for(var e:STATES.entrySet()) {
            var target=e.getKey();var state=e.getValue();
            if(!state.running()||!WATCHED.contains(target)||!valid(target))continue;
            var shell=level.getBlockEntity(target.position()) instanceof EquipmentShellBlockEntity s&&s.shape()!=null?s:null;
            double speed=speed(state);
            float gain=shell!=null?UnitDesign.soundGain(shell.shape(),speed):UnitDesign.leastGain(speed);
            float pitch=shell!=null?UnitDesign.soundPitch(shell.shape(),speed):state.rpm()>0?UnitDesign.soundPitch(speed*state.rpm(),UnitDesign.PUMP_LOOP_RPM):1;
            var p=target.position();double x=p.getX()+.5,y=p.getY()+.5,z=p.getZ()+.5;
            // A casing's running loop; an in-line pump has none (it never had one), only its layers.
            if(shell!=null)into.add(new SoundBudget.Emitter(state.compressor()?"compressor_running":"pump_running",p.asLong(),x,y,z,SoundBudget.level(gain),pitch));
            if(state.crackle()>0)into.add(new SoundBudget.Emitter("pump_cavitation",p.asLong(),x,y,z,SoundBudget.level(gain*state.crackle()/255.0),pitch));
            if(state.churn()>0)into.add(new SoundBudget.Emitter("pump_gas",p.asLong(),x,y,z,SoundBudget.level(gain*state.churn()/255.0),pitch));
        }
    }
    /**
     * A mover's speed share, published with its state from its shaft train's driver (W3; 1 for a set-flow mover). Its
     * driver's own loop is {@link DriverSounds}.
     */
    private static double speed(FluidNetwork.MoverState state){return state.running()?Math.min(state.speed(),1.5):0;}
}
