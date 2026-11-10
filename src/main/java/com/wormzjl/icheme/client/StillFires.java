package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread-only animation of the batch stills (batch 2026-11-09-batch-distiller, W6), as the flares' ({@link
 * FlareFlames}). A still is tracked at its pot's cell, where its identity binds; the client asks for the nearest once a
 * second ({@link FluidNetwork.Watch#STILLS}) and the server publishes each one's heater, status, fire, boiling and drip on
 * its presentation cadence ({@link #accept}). Each client tick draws from the last published state, at the parts the
 * design names ({@link ShapeRasterizer#marks}): no process state is inferred here.
 * <ul>
 * <li>A fired still while its fire burns: the firebox's glow through the door and flames licking at it (a solid fire),
 * the burner's flame at the door (a liquid fire), smoke from the flue, all as strong as the fire.</li>
 * <li>An electric still: its terminal box's lamp lit while it heats.</li>
 * <li>While the pot boils: a boiling sound at the pot.</li>
 * <li>While distillate runs: a drip at the spout.</li>
 * </ul>
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class StillFires {
    private static final Set<EquipmentShellBlockEntity> CELLS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Target> WATCHED=new HashSet<>();
    private static final Map<Target,FluidNetwork.StillState> STATES=new HashMap<>();
    /** Fires are drawn within this distance of the player, blocks. */
    private static final double SIGHT=48;
    private static final DustParticleOptions LAMP=new DustParticleOptions(new org.joml.Vector3f(1f,.55f,.15f),.6f);
    private static ClientLevel world;
    private static int age;
    private StillFires(){}

    /** Lifecycle callers must be on the client thread. A still is tracked only at its pot's cell. */
    public static void track(BlockEntity entity){if(entity instanceof EquipmentShellBlockEntity shell){if(target(shell)!=null)CELLS.add(shell);else CELLS.remove(shell);}}
    public static void forget(BlockEntity entity){
        if(!(entity instanceof EquipmentShellBlockEntity shell)||!CELLS.remove(shell))return;
        var gone=WATCHED.stream().filter(t->t.position().equals(shell.getBlockPos())).toList();
        WATCHED.removeAll(gone);gone.forEach(STATES::remove);
        if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.request(FluidNetwork.Watch.STILLS,List.of(),gone);
    }
    /** The server's state for a still this client watches. */
    public static void accept(FluidNetwork.StillState state){if(WATCHED.contains(state.target()))STATES.put(state.target(),state);}
    public static void clear(){STATES.clear();WATCHED.clear();CELLS.clear();world=null;age=0;}

    private static @Nullable Target target(EquipmentShellBlockEntity shell) {
        var shape=shell.shape();var origin=shell.origin();
        if(shell.isRemoved()||shape==null||origin==null||shape.kind()!=EquipmentShape.Kind.BATCH_STILL||!(shell instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()<=0)return null;
        var cell=ShapeRasterizer.fluidCell(shape);
        return cell!=null&&origin.offset(cell.x(),cell.y(),cell.z()).equals(shell.getBlockPos())?new Target(shell.getBlockPos(),receiver.fluidIdentity()):null;
    }

    /** Part {@code name}'s box in world coordinates (low corner, then high), or null where the design names none. */
    private static double @Nullable [] part(EquipmentShellBlockEntity shell,String name) {
        var box=ShapeRasterizer.marks(shell.shape()).get(name);if(box==null)return null;var o=shell.origin();
        return new double[]{o.getX()+box[0],o.getY()+box[1],o.getZ()+box[2],o.getX()+box[3],o.getY()+box[4],o.getZ()+box[5]};
    }
    private static Vec3 centre(double[] b){return new Vec3((b[0]+b[3])/2,(b[1]+b[4])/2,(b[2]+b[5])/2);}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            if(mc.getConnection()!=null&&!WATCHED.isEmpty())FluidNetwork.request(FluidNetwork.Watch.STILLS,List.of(),List.copyOf(WATCHED));
            STATES.clear();WATCHED.clear();CELLS.removeIf(cell->cell.getLevel()!=mc.level);world=mc.level;age=0;
        }
        if(mc.level==null||mc.player==null||mc.getConnection()==null||mc.isPaused())return;
        var point=mc.player.position();
        if(age++%20==0) {
            var near=new ArrayList<Target>();
            for(var cell:List.copyOf(CELLS)){var t=target(cell);if(t!=null&&cell.getLevel()==mc.level&&t.position().distToCenterSqr(point)<=SIGHT*SIGHT)near.add(t);}
            near.sort(Comparator.comparingDouble((Target t)->t.position().distToCenterSqr(point)).thenComparingLong(Target::identity));
            var wanted=new LinkedHashSet<Target>();for(var t:near){if(wanted.size()>=FluidNetwork.MAXIMUM_WATCHED)break;wanted.add(t);}
            // Registration is queued: retry until a newly placed still's first state arrives.
            var unwatch=WATCHED.stream().filter(t->!wanted.contains(t)).toList();var watch=wanted.stream().filter(t->!WATCHED.contains(t)||!STATES.containsKey(t)).toList();
            WATCHED.removeAll(unwatch);unwatch.forEach(STATES::remove);WATCHED.addAll(watch);
            FluidNetwork.request(FluidNetwork.Watch.STILLS,watch,unwatch);
        }
        for(var cell:List.copyOf(CELLS)) {
            var t=target(cell);if(t==null||cell.getLevel()!=mc.level||!WATCHED.contains(t))continue;
            if(t.position().distToCenterSqr(point)>SIGHT*SIGHT*1.5)continue;
            var state=STATES.get(t);if(state==null)continue;
            draw(mc.level,cell,state);
        }
    }

    private static void draw(ClientLevel level,EquipmentShellBlockEntity cell,FluidNetwork.StillState s) {
        var random=level.random;double fire=s.fire();
        var heater=BatchStill.Heater.values()[Math.min(s.heater(),BatchStill.Heater.values().length-1)];
        if(fire>0&&heater!=BatchStill.Heater.ELECTRIC) {
            var door=part(cell,"door");var bed=part(cell,"fire_bed");
            if(door!=null&&bed!=null) {
                var d=centre(door);var b=centre(bed);
                // Out of the door: the horizontal way from the fire bed to the door.
                var out=new Vec3(d.x-b.x,0,d.z-b.z);out=out.lengthSqr()<1e-6?new Vec3(0,0,-1):out.normalize();
                double w=Math.max(door[3]-door[0],door[5]-door[2]),h=door[4]-door[1];
                var across=new Vec3(-out.z,0,out.x);
                if(heater==BatchStill.Heater.SOLID) {
                    // The glow through the door: embers low in the opening, flames licking up at its lip.
                    if(random.nextDouble()<.25+.6*fire){var at=d.add(across.scale((random.nextDouble()-.5)*w*.8)).add(0,h*(.1+.3*random.nextDouble()),0).add(out.scale(.02));
                        level.addParticle(random.nextInt(3)==0?ParticleTypes.LAVA:ParticleTypes.FLAME,true,at.x,at.y,at.z,out.x*.01,.01,out.z*.01);}
                    if(random.nextDouble()<.15*fire){var at=d.add(across.scale((random.nextDouble()-.5)*w*.6)).add(0,h*.85,0).add(out.scale(.04));
                        level.addParticle(ParticleTypes.SMALL_FLAME,true,at.x,at.y,at.z,out.x*.02,.03,out.z*.02);}
                } else {
                    // The burner's flame: a short jet from the burner toward the fire bed, its length with the firing.
                    var gun=part(cell,"burner");var at=gun==null?d:centre(gun);
                    var jet=new Vec3(b.x-at.x,0,b.z-at.z);jet=jet.lengthSqr()<1e-6?out.scale(-1):jet.normalize();
                    var side=new Vec3(-jet.z,0,jet.x);
                    int n=1+(int)Math.round(3*fire);
                    for(int i=0;i<n;i++){double along=random.nextDouble()*(.15+.25*fire);var p=at.add(jet.scale(along)).add(side.scale((random.nextDouble()-.5)*.08));
                        level.addParticle(ParticleTypes.FLAME,true,p.x,p.y+(random.nextDouble()-.5)*.06,p.z,jet.x*.02,.005,jet.z*.02);}
                }
            }
            // Smoke from the flue, thicker at a high fire.
            var flue=part(cell,"flue_top");
            if(flue!=null&&random.nextDouble()<.1+.4*fire){var c=centre(flue);
                level.addParticle(heater==BatchStill.Heater.SOLID?ParticleTypes.CAMPFIRE_COSY_SMOKE:ParticleTypes.LARGE_SMOKE,true,c.x+(random.nextDouble()-.5)*.15,c.y+.1,c.z+(random.nextDouble()-.5)*.15,0,.04+.03*fire,0);}
            if(heater==BatchStill.Heater.SOLID&&random.nextInt(80)==0&&door!=null){var c=centre(door);level.playLocalSound(c.x,c.y,c.z,SoundEvents.FURNACE_FIRE_CRACKLE,SoundSource.BLOCKS,.6f,1f,false);}
        }
        if(heater==BatchStill.Heater.ELECTRIC&&fire>0) {
            // The terminal box's lamp, lit while the heater draws power.
            var lamp=part(cell,"lamp");
            if(lamp!=null&&random.nextInt(4)==0){var c=centre(lamp);level.addParticle(LAMP,true,c.x,c.y,c.z,0,0,0);}
        }
        if(s.boiling()&&random.nextInt(40)==0) {
            // The pot boils: a rumble of bubbles at the pot's cell.
            var p=cell.getBlockPos().getCenter();
            level.playLocalSound(p.x,p.y,p.z,SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT,SoundSource.BLOCKS,.35f+.3f*(float)fire,.8f+.2f*random.nextFloat(),false);
        }
        if(s.dripping()) {
            var spout=part(cell,"spout");
            if(spout!=null&&random.nextInt(6)==0){var c=centre(spout);level.addParticle(ParticleTypes.FALLING_WATER,true,c.x,spout[1]-.02,c.z,0,0,0);}
        }
    }
}
