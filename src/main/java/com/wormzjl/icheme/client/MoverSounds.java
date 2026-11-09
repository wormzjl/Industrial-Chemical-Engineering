package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
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

/** Client-thread-only requested operating states and positional loops. No process state is inferred on client ticks. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class MoverSounds {
    private static final Set<BlockEntity> CELLS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Target> WATCHED=new HashSet<>();
    private static final Map<Target,FluidNetwork.MoverState> STATES=new HashMap<>();
    private static final Map<Target,Loop> LOOPS=new HashMap<>();
    /** A pump's crackle and churn loops (W10c), over its casing loop: index 0 the crackle, 1 the churn. */
    private static final Map<Target,Loop[]> LAYERS=new HashMap<>();
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
        STATES.put(state.target(),state);if(!state.running())stop(state.target());
    }
    private static void stop(Target target){
        var loop=LOOPS.remove(target);if(loop!=null)Minecraft.getInstance().getSoundManager().stop(loop);
        var layers=LAYERS.remove(target);if(layers!=null)for(var layer:layers)if(layer!=null)Minecraft.getInstance().getSoundManager().stop(layer);
    }
    private static void drop(Target target){STATES.remove(target);stop(target);}
    private static void forget(BlockPos pos) {
        var gone=WATCHED.stream().filter(t->t.position().equals(pos)).toList();WATCHED.removeAll(gone);gone.forEach(MoverSounds::drop);
        if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.request(FluidNetwork.Watch.MOVERS,List.of(),gone);
    }
    public static void clear(){for(var target:List.copyOf(LOOPS.keySet()))stop(target);STATES.clear();WATCHED.clear();CELLS.clear();world=null;age=0;}
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
            for(var target:List.copyOf(LOOPS.keySet()))stop(target);STATES.clear();WATCHED.clear();
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
        for(var target:List.copyOf(STATES.keySet())) {
            var state=STATES.get(target);
            boolean audible=state.running()&&valid(target)&&target.position().distToCenterSqr(point)<=24*24
                    &&mc.options.getSoundSourceVolume(SoundSource.BLOCKS)>0&&mc.options.getSoundSourceVolume(SoundSource.MASTER)>0;
            if(!audible){stop(target);continue;}
            // A casing's running loop; an in-line pump has none (it never had one), only its layers.
            var loop=LOOPS.get(target);
            if(casing(target)&&(loop==null||!mc.getSoundManager().isActive(loop))){loop=new Loop(state,mc.level,0);LOOPS.put(target,loop);mc.getSoundManager().play(loop);}
            // A pump's crackle and churn (W10c): each starts when its byte rises above 0 and stops itself once silent.
            var layers=LAYERS.computeIfAbsent(target,t->new Loop[2]);
            for(int i=0;i<2;i++) {
                if(layer(state,i+1)<=0||layers[i]!=null&&mc.getSoundManager().isActive(layers[i]))continue;
                layers[i]=new Loop(state,mc.level,i+1);mc.getSoundManager().play(layers[i]);
            }
        }
    }
    /** Whether {@code target} is a constructed mover's fluid cell (it has a casing loop), not an in-line pump. */
    private static boolean casing(Target target){var level=Minecraft.getInstance().level;return level!=null&&level.getBlockEntity(target.position()) instanceof EquipmentShellBlockEntity;}
    /** Layer {@code layer}'s share of full loudness, 0 to 1: the casing 1, a pump's crackle and churn their bytes over 255 (W10c). */
    private static double layer(FluidNetwork.MoverState state,int layer){return layer==0?1:(layer==1?state.crackle():state.churn())/255.0;}
    /** How far a layer's volume moves toward its goal each client tick: about a second to settle. */
    private static final float EASE=.05f;
    private static final class Loop extends AbstractTickableSoundInstance {
        private final Target target;
        private final ClientLevel level;
        /** 0: the casing; 1: a pump's cavitation crackle; 2: its gas churn (W10c). */
        private final int layer;
        private Loop(FluidNetwork.MoverState state,ClientLevel level,int layer) {
            super((layer==1?ModBlocks.PUMP_CAVITATION:layer==2?ModBlocks.PUMP_GAS:state.compressor()?ModBlocks.COMPRESSOR_RUNNING:ModBlocks.PUMP_RUNNING).get(),SoundSource.BLOCKS,RandomSource.create());
            this.target=state.target();this.level=level;this.layer=layer;looping=true;delay=0;volume=0;pitch=1;if(layer==0)refreshVolume();
            attenuation=net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR;
            x=target.position().getX()+.5;y=target.position().getY()+.5;z=target.position().getZ()+.5;
        }
        /** A layer starts silent and eases in (the sound engine would drop it otherwise). */
        @Override public boolean canStartSilent(){return layer>0;}
        @Override public void tick() {
            var mc=Minecraft.getInstance();var state=STATES.get(target);
            if(mc.level!=level||state==null||!state.running()||!WATCHED.contains(target)||!valid(target)||mc.player==null
                    ||target.position().distToCenterSqr(mc.player.position())>24*24)stop();
            else refreshVolume();
        }
        /**
         * The casing's gain and pitch, {@link UnitDesign#soundGain(EquipmentShape, double)} and {@link UnitDesign#soundPitch} of
         * its size and speed share (decision D26). A crackle or churn layer (W10c) takes the casing's gain times its byte over
         * 255, eased toward it, at its own pitch, and stops itself once it has faded out.
         */
        private void refreshVolume(){
            var state=STATES.get(target);
            // An in-line pump's layers take the smallest pump casing's gain (UnitDesign.leastGain): it has no casing shape.
            float gain=state==null?0:level.getBlockEntity(target.position()) instanceof EquipmentShellBlockEntity shell?shell.shape()==null?0:UnitDesign.soundGain(shell.shape(),speed(state))
                    :UnitDesign.leastGain(speed(state));
            float goal=state==null?0:(float)(gain*layer(state,layer));
            // The pitch follows the shaft's rpm over the loop's (owner 2026-11-04); a pump's layers take its pump's pitch.
            pitch=state==null?1:level.getBlockEntity(target.position()) instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null?UnitDesign.soundPitch(shell.shape(),speed(state))
                    :state.rpm()>0?UnitDesign.soundPitch(speed(state)*state.rpm(),UnitDesign.PUMP_LOOP_RPM):1;
            if(layer==0){volume=goal;return;}
            volume+=(goal-volume)*EASE;
            if(goal<=0&&volume<.003f)stop();
        }
    }
    /**
     * A mover's speed share, published with its state from its shaft train's driver (W3; 1 for a set-flow mover). Its
     * driver's own loop is {@link DriverSounds}.
     */
    private static double speed(FluidNetwork.MoverState state){return state.running()?Math.min(state.speed(),1.5):0;}
}
