package com.wormzjl.icheme.client;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.PipeAnchor;
import com.wormzjl.icheme.world.level.block.LinePortTarget;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** Shared pipe/data tool selection, grid-ray rack targeting, slot footprints and placement previews. */
public final class LayingToolAim {
    private LayingToolAim(){}
    public static boolean holding(LocalPlayer player){return player.getMainHandItem().is(ModItems.PIPE_LAYING_TOOL.get())||player.getOffhandItem().is(ModItems.PIPE_LAYING_TOOL.get());}
    public static ItemStack held(LocalPlayer player){return player.getMainHandItem().is(ModItems.PIPE_LAYING_TOOL.get())?player.getMainHandItem():player.getOffhandItem();}
    public static List<AABB> freeSlots(PipeRackBlockEntity rack){
        var frame=rack.rack();int taken=RackGeometry.taken(frame,rack.layingLanes());var boxes=new ArrayList<AABB>();
        for(int position=0;position<RackGeometry.POSITIONS;position++){if((taken>>position&1)!=0)continue;var c=frame.world(RackGeometry.centre(position,1),(RackGeometry.BEAM_TOP+2)*LineGeometry.OFFSET,.5);double h=.04;boxes.add(new AABB(c[0]-h,c[1]-h,c[2]-h,c[0]+h,c[1]+h,c[2]+h).move(rack.getBlockPos()));}return List.copyOf(boxes);
    }
    public record Slot(PipeRackBlockEntity rack,RackGeometry.Lane lane,boolean fits){}
    public static DataPort port(Minecraft mc,Collection<DataPort> ports){
        var player=mc.player;if(player==null||mc.level==null)return null;
        return LinePortTarget.aim(mc.level,ports,player.getEyePosition(),player.getLookAngle(),player.blockInteractionRange(),mc.hitResult instanceof BlockHitResult hit?hit:null);
    }
    public static Slot slot(net.minecraft.world.level.Level level,PipeAnchor.Click click,double outside){
        if(click==null||!(level.getBlockEntity(click.clicked()) instanceof PipeRackBlockEntity rack))return null;
        var aim=PipeAnchor.aimed(rack,click.local(),outside);if(aim==null)return null;int code=PipeSizes.code(outside),width=RackGeometry.width(PipeSizes.outsideOf(code));
        return new Slot(rack,new RackGeometry.Lane(aim.option(),width,1<<aim.through()|1<<aim.open(),1<<aim.through(),code),aim.fits());
    }
    public static @Nullable PipeAnchor.Click aimed(Minecraft mc,java.util.function.BiPredicate<PipeRackBlockEntity,PipeAnchor.Click> onLane) {
        var hit = mc.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK ? block : null;
        var vanilla = hit == null ? null : new PipeAnchor.Click(hit.getBlockPos(), hit.getDirection(), hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos())));
        var player = mc.player;
        if (player == null || mc.level == null) return vanilla;
        var eye = player.getEyePosition();
        var look = player.getViewVector(1);
        double reach = hit != null ? hit.getLocation().distanceTo(eye) : player.blockInteractionRange();
        double[] from = {eye.x, eye.y, eye.z}, along = {look.x, look.y, look.z}, next = new double[3], delta = new double[3];
        int[] at = {(int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z)}, step = new int[3];
        for (int a = 0; a < 3; a++) {
            step[a] = along[a] > 0 ? 1 : -1;
            delta[a] = along[a] == 0 ? Double.MAX_VALUE : 1 / Math.abs(along[a]);
            next[a] = along[a] == 0 ? Double.MAX_VALUE : (along[a] > 0 ? at[a] + 1 - from[a] : from[a] - at[a]) * delta[a];
        }
        var entered = Direction.UP;
        for (double t = 0; t <= reach + 1e-6; ) {
            var cell = new BlockPos(at[0], at[1], at[2]);
            boolean last = hit != null && cell.equals(hit.getBlockPos());
            if (mc.level.getBlockEntity(cell) instanceof PipeRackBlockEntity rack) {
                if (last && vanilla!=null&&onLane.test(rack,vanilla)) return vanilla;
                double tier = rack.frame().beam() * LineGeometry.OFFSET, crossing = along[1] == 0 ? -1 : (cell.getY() + tier - eye.y) / along[1];
                if (crossing >= 0) {
                    double x = eye.x + crossing * look.x - cell.getX(), z = eye.z + crossing * look.z - cell.getZ();
                    if (x >= 0 && x <= 1 && z >= 0 && z <= 1) return new PipeAnchor.Click(cell, entered, new Vec3(x, tier - LineGeometry.OFFSET, z));
                }
            }
            if (last) break;
            int a = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
            t = next[a];
            next[a] += delta[a];
            at[a] += step[a];
            entered = Direction.values()[2 * ((a + 2) % 3) + (step[a] > 0 ? 0 : 1)];
        }
        return vanilla;
    }

}
