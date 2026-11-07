package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.*;
import com.wormzjl.icheme.runtime.power.PowerPorts;
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
public final class CableLaying {
    private CableLaying(){}
    public record End(UUID port,BlockPos clicked,Direction face,Vec3 local,int lane){
        public End{Objects.requireNonNull(clicked);Objects.requireNonNull(face);Objects.requireNonNull(local);clicked=clicked.immutable();if(lane<0||lane>128||!Double.isFinite(local.x+local.y+local.z)||local.x<0||local.x>1||local.y<0||local.y>1||local.z<0||local.z>1)throw new IllegalArgumentException("Invalid cable anchor");}
    }
    public record Anchor(PipeAnchor placement,DataPort port,DataBus.Site host,int face,int point){
        public PipeRoute.End end(){return placement.end();}
        public int heading(){return placement.leaving();}
    }
    private static Anchor socket(Level level,DataPort p){var resolved=PipeAnchor.socket(level,p.cell(),p.face(),p.owner(),DataPorts.packed(p));return resolved.anchor()==null?null:new Anchor(resolved.anchor(),p,null,-1,DataPorts.packed(p));}
    /** Facts alone are not a live socket: a replaced host or removed FE capability must not remain connectable. */
    private static DataPort port(Level level,End click,Map<UUID,DataPort> ports,CableMedium medium){
        if(click.port()==null||!level.hasChunkAt(click.clicked()))return null;
        var p=ports.get(click.port());var be=level.getBlockEntity(click.clicked());DataPort live=null;
        if(be instanceof ControlPanelBlockEntity panel)live=panel.dataPort();
        else if(be instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder holder){
            if(medium==CableMedium.POWER&&PowerPorts.eligible(be))live=holder.powerPorts().stream().filter(q->q.id().equals(click.port())).findFirst().orElse(null);
            else live=holder.instruments().dataPort();
        }else if(medium==CableMedium.POWER)live=PowerPorts.external(level,click.clicked(),click.face());
        if(live==null||!live.id().equals(click.port())||p!=null&&!p.equals(live))return null;
        p=live;
        return p.kind().medium()==medium&&p.dimension().equals(level.dimension().location().toString())&&p.owner().equals(click.clicked())&&level.hasChunkAt(p.cell())?p:null;
    }
    public static List<LinePlacement.Lead> leads(Anchor start,Anchor end){
        var out=new ArrayList<LinePlacement.Lead>();
        for(var a:List.of(start,end))if(a.port()!=null)out.add(lead(a.port()));return List.copyOf(out);
    }
    private static LinePlacement.Lead lead(DataPort port){
        var n=Vec3.atLowerCornerOf(port.face().getOpposite().getNormal());var delta=port.cablePoint().subtract(port.centre());
        var points=ConduitGeometry.leadPoints(new double[]{n.x,n.y,n.z},new double[]{delta.x,delta.y,delta.z},new double[]{n.x,n.y,n.z});
        return new LinePlacement.Lead(port.owner(),port.cell(),port.centre(),port.kind().medium().outside(),points.stream().map(p->port.centre().add(p[0],p[1],p[2])).toList());
    }
    public static LinePlacement.Plan plan(Anchor start,Anchor end,List<BlockPos> path,List<Integer> options,CableMedium medium){
        return new LinePlacement.Plan(start.placement(),end.placement(),path,options,medium.outside(),leads(start,end));
    }
    public record Attachment(DataPort port,DataBus.Cable cable,LinePlacement.Plan plan){}
    /** The preview and server share the zero-new-block connection adapter too. */
    public static Attachment attachment(Level level,End click,Map<UUID,DataPort> ports,CableMedium medium){
        var port=port(level,click,ports,medium);if(port==null)return null;
        if(!(level.getBlockEntity(port.cell()) instanceof ConduitBlockEntity wire)||wire.geometry().medium()!=medium)return null;
        var old=wire.cable();var bound=old.endpoints().get(port.face());if(bound!=null&&!bound.equals(port.id()))return null;
        if(PipeRoute.hostJoints(old.geometry().joints(),1<<port.face().ordinal(),false)<0)return null;
        var plan=new LinePlacement.Plan(null,null,List.of(port.cell()),List.of(),medium.outside(),List.of(lead(port)));
        return new Attachment(port,old.connect(port.face().ordinal(),port.u(),port.v(),port.id()),plan);
    }
    public static PipeRoute.Cell cell(BlockPos p){return new PipeRoute.Cell(p.getX(),p.getY(),p.getZ());}
    public static BlockPos block(PipeRoute.Cell p){return new BlockPos(p.x(),p.y(),p.z());}
    public static List<DataBus.Cable> cables(Level level,BlockPos pos){
        if(!level.hasChunkAt(pos))return List.of();
        var be=level.getBlockEntity(pos);return be instanceof ConduitBlockEntity wire?List.of(wire.cable()):be instanceof PipeRackBlockEntity rack?rack.cables():List.of();
    }
    public static Anchor resolve(Level level,End click,Map<UUID,DataPort> ports,CableMedium medium){
        if(!level.hasChunkAt(click.clicked()))return null;
        if(click.port()!=null){
            var p=port(level,click,ports,medium);if(p==null)return null;
            return socket(level,p);
        }
        var be=level.getBlockEntity(click.clicked());
        if(click.lane()>0&&!(be instanceof PipeRackBlockEntity))return null;
        DataBus.Cable cable=null;
        if(be instanceof ConduitBlockEntity wire)cable=wire.cable();
        else if(be instanceof PipeRackBlockEntity rack&&click.lane()>0)cable=rack.cables().stream().filter(c->c.site().lane()==click.lane()).findFirst().orElse(null);
        if(click.lane()>0&&cable==null)return null;
        if(cable!=null){
            if(cable.geometry().medium()!=medium)return null;
            int face=click.face().ordinal();
            var geometry=cable.geometry();
            if(be instanceof PipeRackBlockEntity rack){
                int open=CableConnections.openFaces(cable,p->cables(level,p),ports::get);if(open==0)return null;
                face=PipeAnchor.openArm(open,face);
                geometry=ConduitGeometry.laneKey(rack.rack(),rack.cableLane(cable),geometry);
            }
            if(PipeRoute.hostJoints(cable.geometry().joints(),1<<face,cable.site().lane()>0)<0)return null;
            int packed=(int)(geometry.offsets()>>10*face)&1023;
            var resolved=PipeAnchor.continuation(level,click.clicked(),Direction.values()[face],cable.site().lane(),cable.geometry().joints()|1<<face,packed);
            return resolved.anchor()==null?null:new Anchor(resolved.anchor(),null,cable.site(),face,packed);
        }
        if(be instanceof PipeRackBlockEntity rack){
            var resolved=PipeAnchor.rack(click.clicked(),rack,click.local(),medium.outside());
            var anchor=resolved.anchor();if(anchor==null)return null;
            return new Anchor(anchor,null,null,-1,-1);
        }
        if(medium==CableMedium.POWER){var port=PowerPorts.external(level,click.clicked(),click.face());if(port!=null)return socket(level,port);}
        // Clicking equipment must select its socket, never silently become an unconnected free end.
        if(be instanceof EquipmentShellBlockEntity||level.getBlockState(click.clicked()).getBlock() instanceof EquipmentInteriorBlock)return null;
        if(be instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder holder
                &&(medium==CableMedium.POWER?holder.instruments().powerPort():holder.instruments().dataPort())!=null)return null;
        var resolved=PipeAnchor.open(level,new PipeAnchor.Click(click.clicked(),click.face(),click.local()),medium.outside());
        return resolved.anchor()==null?null:new Anchor(resolved.anchor(),null,null,-1,-1);
    }
    /** Geometry is also consumed by the route ghost: endpoint offsets and rack transitions cannot differ. */
    public static List<DataBus.Cable> design(Level level,Anchor start,Anchor end,PipeRoute.Result route,CableMedium medium){
        var placement=PipeRoute.placement(route.cells(),start.end(),start.heading(),end.end());var result=new ArrayList<DataBus.Cable>();
        String dim=level.dimension().location().toString();
        for(int i=0;i<route.cells().size();i++){
            var at=block(route.cells().get(i));int option=route.options().get(i);long points=0;
            if(option>=0){var rack=(PipeRackBlockEntity)level.getBlockEntity(at);points=RackGeometry.points(rack.rack(),new RackGeometry.Lane(option,RackGeometry.width(medium.outside()),placement.arms()[i],placement.joints()[i],PipeSizes.code(medium.outside())));}
            result.add(new DataBus.Cable(new DataBus.Site(dim,at,option+1),new ConduitGeometry.Key(placement.joints()[i],points,medium),option>=0?placement.arms()[i]:0,Map.of()));
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
    private static ConduitGeometry.Key connect(ConduitGeometry.Key key,int face,int packed){int u=packed&31,v=packed>>5&31;return key.connect(face,u>=16?u-32:u,v>=16?v-32:v);}
    /** BlockItem placement registers the same explicit face geometry as tool placement, without editing neighbours. */
    public static void handPlaced(Level level,ConduitBlockEntity wire,Direction facing,ServerPlayer player){
        if(!(level instanceof net.minecraft.server.level.ServerLevel server))return;
        var world=FluidWorldAuthority.find(server.getServer()).orElse(null);if(world==null)return;
        var old=wire.cable();if(old.geometry().joints()!=0){wire.configure(old);return;}
        var key=old.geometry();var medium=key.medium();var endpoints=new EnumMap<Direction,UUID>(Direction.class);
        for(var port:world.controls().facts().ports().values().stream().filter(p->p.kind().medium()==medium&&p.dimension().equals(old.site().dimension())&&p.cell().equals(old.site().cell())).sorted(Comparator.comparing(p->p.id().toString())).toList()){
            if(player==null||endpoints.containsKey(port.face())||PipeRoute.hostJoints(key.joints(),1<<port.face().ordinal(),false)<0)continue;
            var click=new End(port.id(),port.owner(),port.face().getOpposite(),new Vec3(.5,.5,.5),0);
            var attachment=attachment(level,click,world.controls().facts().ports(),key.medium());
            if(attachment==null||LinePlacement.check(level,attachment.plan())!=null||LinePlacement.touched(attachment.plan()).stream().anyMatch(p->!LinePlacement.editable(player,p)))continue;
            key=key.connect(port.face().ordinal(),port.u(),port.v());endpoints.put(port.face(),port.id());
        }
        for(var face:Direction.values()){
            if((key.joints()&1<<face.ordinal())!=0||PipeRoute.hostJoints(key.joints(),1<<face.ordinal(),false)<0)continue;
            for(var neighbour:cables(level,wire.getBlockPos().relative(face)))if(neighbour.geometry().medium()==key.medium()&&(neighbour.geometry().joints()&1<<face.getOpposite().ordinal())!=0){
                key=connect(key,face.ordinal(),(int)(neighbour.geometry().offsets()>>10*face.getOpposite().ordinal())&1023);break;
            }
        }
        int count=Integer.bitCount(key.joints());
        if(count==0)key=key.connect(facing.ordinal(),0,0).connect(facing.getOpposite().ordinal(),0,0);
        else if(count==1){int face=Integer.numberOfTrailingZeros(key.joints())^1;key=key.connect(face,0,0);}
        wire.configure(new DataBus.Cable(old.site(),key,0,endpoints));
    }
    public record Hosts(List<DataBus.Cable> cables,Component refusal){public Hosts{cables=List.copyOf(cables);}}
    /** Preview and server merge both ends into each retained host before checking its joint limit. */
    public static Hosts hosts(Anchor start,Anchor end,java.util.function.Function<DataBus.Site,DataBus.Cable> source){
        var edits=new LinkedHashMap<DataBus.Site,DataBus.Cable>();
        for(var anchor:List.of(start,end))if(anchor.host()!=null){
            var old=edits.get(anchor.host());if(old==null)old=source.apply(anchor.host());
            if(old==null)return new Hosts(List.of(),message("anchor.fitting"));
            if(PipeRoute.hostJoints(old.geometry().joints(),1<<anchor.face(),old.site().lane()>0)<0)return new Hosts(List.of(),message("anchor.junction"));
            edits.put(old.site(),new DataBus.Cable(old.site(),connect(old.geometry(),anchor.face(),anchor.point()),old.arms(),old.endpoints()));
        }
        return new Hosts(List.copyOf(edits.values()),null);
    }
    private static void endpoint(List<DataBus.Cable> wires,int index,Anchor anchor){
        if(anchor.end().joint()<0)return;
        var c=wires.get(index);var key=connect(c.geometry(),anchor.end().joint(),anchor.point());var ends=new EnumMap<Direction,UUID>(Direction.class);ends.putAll(c.endpoints());
        if(anchor.port()!=null)ends.put(anchor.port().face(),anchor.port().id());wires.set(index,new DataBus.Cable(c.site(),key,c.arms(),ends));
    }
    public static Component lay(ServerPlayer player,InteractionHand hand,End first,End last,List<BlockPos> path,List<Integer> options,CableMedium medium){
        if(!allowed(player,hand,medium)||path.size()>128||options.size()!=path.size())return message("not_allowed");
        if(path.isEmpty())return first.equals(last)?attach(player,hand,last,medium):message("not_allowed");
        var level=player.serverLevel();var world=FluidWorldAuthority.find(player.server).orElse(null);if(world==null)return message("not_allowed");
        if(first.clicked().getCenter().distanceToSqr(player.position())>64*64||!reachable(player,last,world.controls().facts().ports(),medium))return message("out_of_reach");
        var a=resolve(level,first,world.controls().facts().ports(),medium);var b=resolve(level,last,world.controls().facts().ports(),medium);if(a==null||b==null)return message("anchor.fitting");
        var plan=plan(a,b,path,options,medium);
        var refused=LinePlacement.check(level,plan);if(refused!=null)return refused;
        var cells=path.stream().map(CableLaying::cell).toList();
        var hosts=hosts(a,b,world.controls().facts()::cable);if(hosts.refusal()!=null)return hosts.refusal();
        var wires=design(level,a,b,new PipeRoute.Result(cells,options,null),medium);
        return LinePlacement.commit(player,plan,item(medium),path.size(),Component.translatable("message.icheme.conduit.items",path.size(),item(medium).getDescription()),()->{
            for(var anchor:List.of(a,b))if(anchor.port()!=null&&anchor.port().kind().power())world.controls().facts().port(anchor.port());
            var edits=new ArrayList<LinePlacement.Edit>();
            for(var wire:wires)edits.add(edit(world,wire,wire.site().lane()==0));
            for(var wire:hosts.cables())edits.add(edit(world,wire,false));
            return new LinePlacement.Work(edits,()->{});
        });
    }
    private static LinePlacement.Edit edit(FluidWorldAuthority world,DataBus.Cable cable,boolean place){
        var state=place?(cable.geometry().medium()==CableMedium.POWER?ModBlocks.POWER_CABLE:ModBlocks.DATA_CABLE).get().defaultBlockState():null;
        return new LinePlacement.Edit(cable.site().cell(),state,Block.UPDATE_CLIENTS,entity->{
            if(entity instanceof ConduitBlockEntity wire)wire.configure(cable);
            else if(entity instanceof PipeRackBlockEntity rack){
                world.controls().cable(cable);var all=new ArrayList<>(rack.cables());all.removeIf(c->c.site().equals(cable.site()));all.add(cable);rack.cables(all);
            }else throw new IllegalStateException("Validated line host disappeared");
        });
    }
    /** Finish a previously laid open end at its socket; the pipe framework likewise reuses an existing end. */
    public static Component attach(ServerPlayer player,InteractionHand hand,End click,CableMedium medium){
        if(!allowed(player,hand,medium)||click.port()==null)return message("not_allowed");
        var world=FluidWorldAuthority.find(player.server).orElse(null);if(world==null)return message("not_allowed");
        var attachment=attachment(player.level(),click,world.controls().facts().ports(),medium);
        if(attachment==null)return message("anchor.fitting");
        var port=attachment.port();
        if(port.centre().distanceToSqr(player.getEyePosition())>Math.pow(player.blockInteractionRange()+1,2)||!editable(player,port.owner())||!editable(player,port.cell()))return message("not_allowed");
        return LinePlacement.commit(player,attachment.plan(),item(medium),0,message("not_allowed"),()->{
            if(port.kind().power())world.controls().facts().port(port);
            return new LinePlacement.Work(List.of(edit(world,attachment.cable(),false)),()->{});
        });
    }
    public static Component dismantle(ServerPlayer player,InteractionHand hand,End click,CableMedium medium){
        if(!allowed(player,hand,medium)||!editable(player,click.clicked())||!player.canInteractWithBlock(click.clicked(),1))return message("not_allowed");
        var level=player.serverLevel();var be=level.getBlockEntity(click.clicked());
        if(be instanceof ConduitBlockEntity wire&&wire.geometry().medium()==medium){return player.gameMode.destroyBlock(click.clicked())?null:message("not_allowed");}
        if(be instanceof PipeRackBlockEntity rack){var cable=rack.cables().stream().filter(c->c.site().lane()==click.lane()).findFirst().orElse(null);if(cable!=null&&cable.geometry().medium()==medium){FluidWorldAuthority.find(player.server).ifPresent(w->w.controls().removeCable(cable.site()));rack.cables(rack.cables().stream().filter(c->!c.site().equals(cable.site())).toList());if(!player.getAbilities().instabuild)player.getInventory().placeItemBackInInventory(new ItemStack(item(medium)));return null;}}
        return message("not_a_pipe");
    }
    private static boolean allowed(ServerPlayer p,InteractionHand hand,CableMedium medium){var s=p.getItemInHand(hand);return s.is(ModItems.PIPE_LAYING_TOOL.get())&&PipeLayingToolItem.conduit(s)&&PipeLayingToolItem.medium(s)==medium&&p.mayBuild()&&!p.isSpectator();}
    private static boolean editable(ServerPlayer p,BlockPos pos){return LinePlacement.editable(p,pos);}
    private static boolean reachable(ServerPlayer p,End end,Map<UUID,DataPort> ports,CableMedium medium){if(end.port()==null)return p.canInteractWithBlock(end.clicked(),1);var anchor=resolve(p.level(),end,ports,medium);var port=anchor==null?null:anchor.port();return port!=null&&port.centre().distanceToSqr(p.getEyePosition())<Math.pow(p.blockInteractionRange()+1,2);}
    private static net.minecraft.world.item.Item item(CableMedium medium){return (medium==CableMedium.POWER?ModItems.POWER_CABLE:ModItems.DATA_CABLE).get();}

    private static Component message(String key,Object...args){return PipeLaying.message(key,args);}
}
