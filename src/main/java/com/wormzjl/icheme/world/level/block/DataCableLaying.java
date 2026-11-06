package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.*;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Pipe-route search and rack slots, with a separate signal graph and inventory transaction. */
public final class DataCableLaying {
    private DataCableLaying(){}
    public record End(UUID port,BlockPos clicked,Direction face,Vec3 local,int lane){
        public End{Objects.requireNonNull(clicked);Objects.requireNonNull(face);Objects.requireNonNull(local);clicked=clicked.immutable();if(lane<0||lane>128||!Double.isFinite(local.x+local.y+local.z)||local.x<0||local.x>1||local.y<0||local.y>1||local.z<0||local.z>1)throw new IllegalArgumentException("Invalid cable anchor");}
    }
    public record Anchor(PipeRoute.End end,int heading,DataPort port,DataBus.Site host,int face,int point){}
    public static PipeRoute.Cell cell(BlockPos p){return new PipeRoute.Cell(p.getX(),p.getY(),p.getZ());}
    public static BlockPos block(PipeRoute.Cell p){return new BlockPos(p.x(),p.y(),p.z());}
    public static Anchor resolve(Level level,End click,Map<UUID,DataPort> ports){
        if(!level.hasChunkAt(click.clicked()))return null;
        if(click.port()!=null){
            var p=ports.get(click.port());if(p==null||!p.dimension().equals(level.dimension().location().toString())||!p.owner().equals(click.clicked())||!level.hasChunkAt(p.cell()))return null;
            return new Anchor(new PipeRoute.End(cell(p.cell()),p.face().ordinal(),DataPorts.packed(p)),p.face().getOpposite().ordinal(),p,null,-1,DataPorts.packed(p));
        }
        var be=level.getBlockEntity(click.clicked());
        DataBus.Cable cable=null;
        if(be instanceof DataCableBlockEntity wire)cable=wire.cable();
        else if(be instanceof PipeRackBlockEntity rack&&click.lane()>0)cable=rack.cables().stream().filter(c->c.site().lane()==click.lane()).findFirst().orElse(null);
        if(cable!=null){
            int face=click.face().ordinal();
            if(cable.site().lane()>0&&(cable.arms()&(1<<face))==0)return null;
            if(PipeRoute.hostJoints(cable.geometry().joints(),1<<face,cable.site().lane()>0)<0)return null;
            int packed=(int)(cable.geometry().offsets()>>10*face)&1023;
            var next=click.clicked().relative(click.face());
            return new Anchor(new PipeRoute.End(cell(next),face^1,packed),face,null,cable.site(),face,packed);
        }
        if(be instanceof PipeRackBlockEntity rack){
            var resolved=PipeAnchor.rack(click.clicked(),rack,click.local(),DataCableGeometry.OUTSIDE);
            var anchor=resolved.anchor();if(anchor==null)return null;
            return new Anchor(anchor.end(),anchor.leaving(),null,null,-1,-1);
        }
        var at=level.getBlockState(click.clicked()).canBeReplaced()?click.clicked():click.clicked().relative(click.face());
        return new Anchor(new PipeRoute.End(cell(at),-1),click.face().ordinal(),null,null,-1,-1);
    }
    /** Geometry is also consumed by the route ghost: endpoint offsets and rack transitions cannot differ. */
    public static List<DataBus.Cable> design(Level level,Anchor start,Anchor end,PipeRoute.Result route){
        var placement=PipeRoute.placement(route.cells(),start.end(),start.heading(),end.end());var result=new ArrayList<DataBus.Cable>();
        String dim=level.dimension().location().toString();
        for(int i=0;i<route.cells().size();i++){
            var at=block(route.cells().get(i));int option=route.options().get(i);long points=0;
            if(option>=0){var rack=(PipeRackBlockEntity)level.getBlockEntity(at);points=RackGeometry.points(rack.rack(),new RackGeometry.Lane(option,1,placement.arms()[i],placement.joints()[i],PipeSizes.code(DataCableGeometry.OUTSIDE)));}
            result.add(new DataBus.Cable(new DataBus.Site(dim,at,option+1),new DataCableGeometry.Key(placement.joints()[i],points),option>=0?placement.arms()[i]:0,Map.of()));
        }
        for(int i=0;i<result.size();i++){
            var c=result.get(i);var key=c.geometry();
            for(int face=0;face<6;face++)if((key.joints()&(1<<face))!=0){
                var adjacent=c.site().cell().relative(Direction.values()[face]);
                var other=result.stream().filter(n->n.site().cell().equals(adjacent)&&n.site().lane()>0).findFirst().orElse(null);
                if(c.site().lane()==0&&other!=null){int p=(int)(other.geometry().offsets()>>10*(face^1))&1023;key=connect(key,face,p);}
            }
            result.set(i,new DataBus.Cable(c.site(),key,c.arms(),c.endpoints()));
        }
        endpoint(result,0,start);endpoint(result,result.size()-1,end);return List.copyOf(result);
    }
    private static DataCableGeometry.Key connect(DataCableGeometry.Key key,int face,int packed){int u=packed&31,v=packed>>5&31;return key.connect(face,u>=16?u-32:u,v>=16?v-32:v);}
    private static void endpoint(List<DataBus.Cable> wires,int index,Anchor anchor){
        if(anchor.end().joint()<0)return;
        var c=wires.get(index);var key=connect(c.geometry(),anchor.end().joint(),anchor.point());var ends=new EnumMap<Direction,UUID>(Direction.class);ends.putAll(c.endpoints());
        if(anchor.port()!=null)ends.put(anchor.port().face(),anchor.port().id());wires.set(index,new DataBus.Cable(c.site(),key,c.arms(),ends));
    }
    public static Component lay(ServerPlayer player,InteractionHand hand,End first,End last,List<BlockPos> path,List<Integer> options){
        if(!allowed(player,hand)||path.isEmpty()||path.size()>128||options.size()!=path.size())return message("not_allowed");
        var level=player.serverLevel();var world=FluidWorldAuthority.find(player.server).orElse(null);if(world==null)return message("not_allowed");
        if(first.clicked().getCenter().distanceToSqr(player.position())>64*64||!reachable(player,last,world.controls().facts().ports()))return message("out_of_reach");
        var a=resolve(level,first,world.controls().facts().ports());var b=resolve(level,last,world.controls().facts().ports());if(a==null||b==null)return message("anchor.fitting");
        var cells=path.stream().map(DataCableLaying::cell).toList();var refused=PipeRoute.check(cells,options,a.end(),b.end(),128);if(refused!=null)return PipeLaying.refusal(refused);
        int blocked=PipeRoute.blocked(cells,options,a.end(),a.heading(),b.end(),DataCableGeometry.OUTSIDE,PipeAnchor.space(level));if(blocked>=0)return message("occupied",path.get(blocked).toShortString());
        var hosts=new HashMap<DataBus.Site,DataBus.Cable>();
        for(var anchor:List.of(a,b))if(anchor.host()!=null){
            var old=hosts.getOrDefault(anchor.host(),world.controls().facts().cable(anchor.host()));if(old==null)return message("anchor.fitting");
            int joints=PipeRoute.hostJoints(old.geometry().joints(),1<<anchor.face(),old.site().lane()>0);if(joints<0)return message("anchor.junction");
            var key=connect(old.geometry(),anchor.face(),anchor.point());hosts.put(old.site(),new DataBus.Cable(old.site(),key,old.arms(),old.endpoints()));
        }
        for(var pos:path)if(!editable(player,pos))return message("not_allowed");for(var site:hosts.keySet())if(!editable(player,site.cell()))return message("not_allowed");
        if(!editable(player,first.clicked())||!editable(player,last.clicked()))return message("not_allowed");
        var wires=design(level,a,b,new PipeRoute.Result(cells,options,null));
        if(!player.getAbilities().instabuild&&!take(player,path.size()))return message("items",path.size());
        for(var wire:wires){var at=wire.site().cell();if(wire.site().lane()==0){level.setBlock(at,ModBlocks.DATA_CABLE.get().defaultBlockState(),Block.UPDATE_CLIENTS);((DataCableBlockEntity)level.getBlockEntity(at)).configure(wire);}else{world.controls().cable(wire);var rack=(PipeRackBlockEntity)level.getBlockEntity(at);var all=new ArrayList<>(rack.cables());all.add(wire);rack.cables(all);}}
        for(var wire:hosts.values())if(wire.site().lane()==0)((DataCableBlockEntity)level.getBlockEntity(wire.site().cell())).configure(wire);else{world.controls().cable(wire);var rack=(PipeRackBlockEntity)level.getBlockEntity(wire.site().cell());var all=new ArrayList<>(rack.cables());all.removeIf(c->c.site().equals(wire.site()));all.add(wire);rack.cables(all);}
        return null;
    }
    public static Component dismantle(ServerPlayer player,InteractionHand hand,End click){
        if(!allowed(player,hand)||!editable(player,click.clicked())||!player.canInteractWithBlock(click.clicked(),1))return message("not_allowed");
        var level=player.serverLevel();var be=level.getBlockEntity(click.clicked());
        if(be instanceof DataCableBlockEntity){return player.gameMode.destroyBlock(click.clicked())?null:message("not_allowed");}
        if(be instanceof PipeRackBlockEntity rack){var cable=rack.cables().stream().filter(c->c.site().lane()==click.lane()).findFirst().orElse(null);if(cable!=null){FluidWorldAuthority.find(player.server).ifPresent(w->w.controls().removeCable(cable.site()));rack.cables(rack.cables().stream().filter(c->!c.site().equals(cable.site())).toList());if(!player.getAbilities().instabuild)player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.DATA_CABLE.get()));return null;}}
        return message("not_a_pipe");
    }
    private static boolean allowed(ServerPlayer p,InteractionHand hand){var s=p.getItemInHand(hand);return s.is(ModItems.PIPE_LAYING_TOOL.get())&&PipeLayingToolItem.data(s)&&p.mayBuild()&&!p.isSpectator();}
    private static boolean editable(ServerPlayer p,BlockPos pos){var l=p.serverLevel();return l.hasChunkAt(pos)&&l.getWorldBorder().isWithinBounds(pos)&&l.mayInteract(p,pos)&&!p.blockActionRestricted(l,pos,p.gameMode.getGameModeForPlayer());}
    private static boolean reachable(ServerPlayer p,End end,Map<UUID,DataPort> ports){if(end.port()==null)return p.canInteractWithBlock(end.clicked(),1);var port=ports.get(end.port());return port!=null&&port.centre().distanceToSqr(p.getEyePosition())<Math.pow(p.blockInteractionRange()+1,2);}
    private static boolean take(ServerPlayer p,int count){var inv=p.getInventory();int held=0;for(int i=0;i<inv.getContainerSize();i++)if(inv.getItem(i).is(ModItems.DATA_CABLE.get()))held+=inv.getItem(i).getCount();if(held<count)return false;for(int i=0;i<inv.getContainerSize()&&count>0;i++){var item=inv.getItem(i);if(!item.is(ModItems.DATA_CABLE.get()))continue;int n=Math.min(count,item.getCount());item.shrink(n);count-=n;}return true;}
    private static Component message(String key,Object...args){return PipeLaying.message(key,args);}
}
