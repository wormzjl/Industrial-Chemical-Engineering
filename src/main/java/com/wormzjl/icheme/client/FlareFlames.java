package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.Flare;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread-only flames of the flare stacks (batch 2026-11-09-flare-stack, W5). A flare is tracked at its gas inlet
 * cell, where its identity binds; the client asks for the nearest once a second ({@link FluidNetwork.Watch#FLARES}) and the
 * server publishes each one's status, flame and plume on its presentation cadence ({@link #accept}). Until then a flare shows
 * its pilot only. Each client tick draws particles from the last published state: no process state is inferred here.
 * <ul>
 * <li>FLARING, OVER CAPACITY: a flame at the tip whose length follows the heat released ({@link Flare#flameLength}, its
 * share of the rated flame), flickering, leaning a little downwind, with smoke above it.</li>
 * <li>FLAME OUT: a pale plume of unburnt gas, no flame.</li>
 * <li>Always: the pilots' small flame at the tip.</li>
 * </ul>
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class FlareFlames {
    private static final Set<EquipmentShellBlockEntity> CELLS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Target> WATCHED=new HashSet<>();
    private static final Map<Target,FluidNetwork.FlareState> STATES=new HashMap<>();
    /** Flames are drawn within this distance of the player, blocks: a tall stack's flame shows from afar. */
    private static final double SIGHT=FluidNetwork.MOVER_RANGE-8;
    /** The most flame particles one flare adds in a client tick, at its full rated flame. */
    private static final int MOST=24;
    private static final int FLARING=1,FLAME_OUT=2,OVER_CAPACITY=3;
    private static ClientLevel world;
    private static int age;
    private FlareFlames(){}

    /** Lifecycle callers must be on the client thread. A flare is tracked only at its gas inlet cell. */
    public static void track(BlockEntity entity){if(entity instanceof EquipmentShellBlockEntity shell){if(target(shell)!=null)CELLS.add(shell);else CELLS.remove(shell);}}
    public static void forget(BlockEntity entity){
        if(!(entity instanceof EquipmentShellBlockEntity shell)||!CELLS.remove(shell))return;
        var gone=WATCHED.stream().filter(t->t.position().equals(shell.getBlockPos())).toList();
        WATCHED.removeAll(gone);gone.forEach(STATES::remove);
        if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.request(FluidNetwork.Watch.FLARES,List.of(),gone);
    }
    /** The server's flame state for a flare this client watches. */
    public static void accept(FluidNetwork.FlareState state){if(WATCHED.contains(state.target()))STATES.put(state.target(),state);}
    public static void clear(){STATES.clear();WATCHED.clear();CELLS.clear();world=null;age=0;}

    private static @Nullable Target target(EquipmentShellBlockEntity shell) {
        var shape=shell.shape();var origin=shell.origin();
        if(shell.isRemoved()||shape==null||origin==null||shape.kind()!=EquipmentShape.Kind.FLARE_STACK||!(shell instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()<=0)return null;
        var cell=ShapeRasterizer.fluidCell(shape);
        return cell!=null&&origin.offset(cell.x(),cell.y(),cell.z()).equals(shell.getBlockPos())?new Target(shell.getBlockPos(),receiver.fluidIdentity()):null;
    }

    /** The tip of {@code shell}'s stack: over the centre of its top cells, at its stack height. */
    private static Vec3 tip(EquipmentShellBlockEntity shell) {
        var shape=shell.shape();var origin=shell.origin();
        int top=Integer.MIN_VALUE;double x=0,z=0;int n=0;
        for(var c:ShapeRasterizer.layout(shape).shell()) {
            if(c.y()>top){top=c.y();x=0;z=0;n=0;}
            if(c.y()==top){x+=c.x();z+=c.z();n++;}
        }
        return new Vec3(origin.getX()+x/Math.max(n,1)+.5,origin.getY()+Flare.stackHeight(shape.diameter()),origin.getZ()+z/Math.max(n,1)+.5);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            if(mc.getConnection()!=null&&!WATCHED.isEmpty())FluidNetwork.request(FluidNetwork.Watch.FLARES,List.of(),List.copyOf(WATCHED));
            STATES.clear();WATCHED.clear();CELLS.removeIf(cell->cell.getLevel()!=mc.level);world=mc.level;age=0;
        }
        if(mc.level==null||mc.player==null||mc.getConnection()==null||mc.isPaused())return;
        var point=mc.player.position();
        if(age++%20==0) {
            var near=new ArrayList<Target>();
            for(var cell:List.copyOf(CELLS)){var t=target(cell);if(t!=null&&cell.getLevel()==mc.level&&t.position().distToCenterSqr(point)<=SIGHT*SIGHT)near.add(t);}
            near.sort(Comparator.comparingDouble((Target t)->t.position().distToCenterSqr(point)).thenComparingLong(Target::identity));
            var wanted=new LinkedHashSet<Target>();for(var t:near){if(wanted.size()>=FluidNetwork.MAXIMUM_WATCHED)break;wanted.add(t);}
            // Registration is queued: retry until a newly placed flare's first state arrives.
            var unwatch=WATCHED.stream().filter(t->!wanted.contains(t)).toList();var watch=wanted.stream().filter(t->!WATCHED.contains(t)||!STATES.containsKey(t)).toList();
            WATCHED.removeAll(unwatch);unwatch.forEach(STATES::remove);WATCHED.addAll(watch);
            FluidNetwork.request(FluidNetwork.Watch.FLARES,watch,unwatch);
        }
        var random=mc.level.random;
        for(var cell:List.copyOf(CELLS)) {
            var t=target(cell);if(t==null||cell.getLevel()!=mc.level||!WATCHED.contains(t))continue;
            var tip=tip(cell);if(tip.distanceToSqr(point)>SIGHT*SIGHT*1.5)continue;
            var state=STATES.get(t);int status=state==null?0:state.status();
            // The pilots: a small flame at the rim, every few ticks.
            if(random.nextInt(4)==0)mc.level.addParticle(ParticleTypes.SMALL_FLAME,true,tip.x+(random.nextDouble()-.5)*.6,tip.y+.2,tip.z+(random.nextDouble()-.5)*.6,0,.02,0);
            if(state==null)continue;
            if(status==FLARING||status==OVER_CAPACITY)flame(mc.level,tip,cell.shape().diameter(),state.flame());
            else if(status==FLAME_OUT)plume(mc.level,tip,state.plume());
        }
    }

    /** A flame of {@code share} of the rated heat: its length {@link Flare#flameLength} of that heat, flickering tick to tick. */
    private static void flame(ClientLevel level,Vec3 tip,double diameter,double share) {
        var random=level.random;
        double length=Math.max(1,Flare.flameLength(share*Flare.rated(diameter))),flicker=.75+.5*random.nextDouble();
        int count=Math.max(2,(int)Math.round(MOST*Math.sqrt(share)*flicker));
        // A light wind leans the flame: its tilt grows along its length.
        double lean=.25;
        for(int i=0;i<count;i++) {
            double along=Math.pow(random.nextDouble(),1.4)*length*flicker,width=Math.max(.3,diameter*2)*(1+along/length*1.5)*(.4+.6*random.nextDouble());
            double angle=random.nextDouble()*Math.PI*2;
            double x=tip.x+Math.cos(angle)*width*.5+along*lean,y=tip.y+.3+along,z=tip.z+Math.sin(angle)*width*.5;
            level.addParticle(along>length*.75&&random.nextInt(3)==0?ParticleTypes.LAVA:ParticleTypes.FLAME,true,x,y,z,(random.nextDouble()-.5)*.05+.03,.08+.1*random.nextDouble(),(random.nextDouble()-.5)*.05);
        }
        // Smoke above the flame's end: a rich gas smokes more at a high flow.
        if(random.nextDouble()<.3+.5*share)level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,true,tip.x+length*lean,tip.y+length*flicker,tip.z,.02,.06,0);
        if(random.nextInt(3)==0)level.addParticle(ParticleTypes.LARGE_SMOKE,true,tip.x+length*lean*.9,tip.y+length*.9,tip.z,.03,.05,0);
    }

    /** A pale plume of unburnt gas, {@code share} of the most the flare passes. */
    private static void plume(ClientLevel level,Vec3 tip,double share) {
        var random=level.random;
        int count=1+(int)Math.round(4*share*random.nextDouble());
        for(int i=0;i<count;i++)level.addParticle(ParticleTypes.CLOUD,true,tip.x+(random.nextDouble()-.5)*.6,tip.y+.4,tip.z+(random.nextDouble()-.5)*.6,.02+(random.nextDouble()-.5)*.03,.15+.2*share,(random.nextDouble()-.5)*.03);
    }
}
