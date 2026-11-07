package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.PipeRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Common spatial plan, preview checks and server transaction. Fluid/control registration stays with its adapter. */
public final class LinePlacement {
    private LinePlacement(){}
    public record Lead(BlockPos owner,BlockPos entry,Vec3 socket,double outside,List<Vec3> points){
        public Lead{owner=owner.immutable();entry=entry.immutable();Objects.requireNonNull(socket);points=List.copyOf(points);}
    }
    public record Plan(PipeAnchor start,PipeAnchor end,List<BlockPos> path,List<Integer> options,double outside,List<Lead> leads){
        public Plan{path=path.stream().map(BlockPos::immutable).toList();options=List.copyOf(options);leads=List.copyOf(leads);}
    }
    /** One physical edit. Null state means updating a retained host or rack rather than replacing its block. */
    public record Edit(BlockPos at,BlockState state,int flags,Consumer<BlockEntity> bind){
        public Edit{at=at.immutable();Objects.requireNonNull(bind);}
    }
    public record Work(List<Edit> edits,Runnable finish){public Work{edits=List.copyOf(edits);Objects.requireNonNull(finish);}}
    public static Plan plan(PipeAnchor start,PipeAnchor end,List<BlockPos> path,List<Integer> options,double outside){
        return new Plan(start,end,path,options,outside,List.of());
    }
    /** Both previews search through this entry; the server checks the resulting complete plan again before writing. */
    public static PipeRoute.Result search(Level level,PipeAnchor start,PipeAnchor end,double outside,List<Lead> leads){
        if(PipeAnchor.refuses(start,end,outside)!=null||checkLeads(level,leads)!=null)return new PipeRoute.Result(List.of(),List.of(),PipeRoute.Refusal.BLOCKED);
        return PipeRoute.search(start.end(),start.leaving(),end.end(),outside,space(level,leads),PipeRoute.Limits.DEFAULT);
    }
    public static Component check(Level level,PipeAnchor start,PipeAnchor end,List<BlockPos> path,List<Integer> options,double outside){
        return check(level,plan(start,end,path,options,outside));
    }
    public static Component check(Level level,Plan plan){
        if(plan.start()!=null){var refused=route(level,plan.start(),plan.end(),plan.path(),plan.options(),plan.outside(),plan.leads());if(refused!=null)return refused;}
        return checkLeads(level,plan.leads(),plan.start()==null?Set.copyOf(plan.path()):Set.of());
    }
    private static Component route(Level level,PipeAnchor start,PipeAnchor end,List<BlockPos> path,List<Integer> options,double outside,List<Lead> leads){
        var fit=PipeAnchor.refuses(start,end,outside);if(fit!=null)return PipeLaying.refusal(fit);
        var cells=path.stream().map(PipeAnchor::cell).toList();
        var refusal=PipeRoute.check(cells,options,start.end(),end.end(),PipeRoute.Limits.DEFAULT.cells());if(refusal!=null)return PipeLaying.refusal(refusal);
        int blocked=PipeRoute.blocked(cells,options,start.end(),start.leaving(),end.end(),outside,space(level,leads));
        return blocked<0?null:PipeLaying.message("occupied",path.get(blocked).toShortString());
    }
    private static PipeRoute.Space space(Level level,List<Lead> leads){return reserve(PipeAnchor.space(level),leads);}
    /** Leads occupy cells before any new block is placed; only their own entries may carry the route. */
    public static PipeRoute.Space reserve(PipeRoute.Space world,List<Lead> leads){
        var occupied=new HashSet<BlockPos>();for(var lead:leads)occupied.addAll(LinePieces.slices(lead).keySet());for(var lead:leads)occupied.remove(lead.entry());
        return new PipeRoute.Space(){
            public PipeRoute.Room room(PipeRoute.Cell cell){return occupied.contains(PipeAnchor.block(cell))?PipeRoute.Room.BLOCKED:world.room(cell);}
            public Collection<PipeRoute.Cell> racks(PipeRoute.Cell low,PipeRoute.Cell high){return world.racks(low,high);}
        };
    }
    /** Swept lead envelopes include the fluid/equipment cells between a socket and its first placed cell. */
    public static List<AABB> bounds(Lead lead){
        var out=new ArrayList<AABB>();var points=lead.points();double radius=Math.max(3.0/32,lead.outside()/2);
        for(int i=1;i<points.size();i++)if(points.get(i-1).distanceToSqr(points.get(i))>1e-12)
            out.add(new AABB(points.get(i-1),points.get(i)).inflate(radius));
        return out;
    }
    public static Component checkLeads(Level level,List<Lead> leads){
        return checkLeads(level,leads,Set.of());
    }
    private static Component checkLeads(Level level,List<Lead> leads,Set<BlockPos> retained){
        for(int i=0;i<leads.size();i++)for(int j=0;j<i;j++)if(!leads.get(i).equals(leads.get(j)))for(var a:bounds(leads.get(i)))for(var b:bounds(leads.get(j)))if(a.deflate(1e-6).intersects(b.deflate(1e-6)))return PipeLaying.message("occupied",BlockPos.containing(a.getCenter()).toShortString());
        for(var lead:leads)for(var box:bounds(lead))for(var pos:BlockPos.betweenClosedStream(box).toList()){
            if(!level.hasChunkAt(pos)||level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos))return PipeLaying.message("anchor.outside");
            var state=level.getBlockState(pos);
            if(state.canBeReplaced())continue;
            if(state.getBlock() instanceof LinePieceBlock&&LinePieces.at(level,pos).stream().allMatch(p->p.lead().equals(lead)))continue;
            // An attachment retains this cable block. Its connector geometry is the object being edited.
            if(pos.equals(lead.entry())&&retained.contains(pos))continue;
            // Only the owner's actual gland may overlap the first few centimetres of its own casting.
            boolean own=pos.equals(lead.owner());
            if(!own&&level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell
                    &&level.getBlockEntity(lead.owner()) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity owner)
                own=shell.origin().equals(owner.origin())&&Objects.equals(shell.shape(),owner.shape());
            // Other occupied cells are reserved exactly as in PipeAnchor.space, including hollow equipment and rack
            // slots. An implicit lead cannot bypass a rack lane registration or cross another structure's empty skirt.
            var occupied=(own?state.getCollisionShape(level,pos):Shapes.block()).move(pos.getX(),pos.getY(),pos.getZ());
            if(own&&LinePieces.at(level,pos).stream().anyMatch(p->p.lead().equals(lead))){var part=LinePieces.slices(lead).get(pos);if(part!=null)occupied=Shapes.join(occupied,part.move(pos.getX(),pos.getY(),pos.getZ()),BooleanOp.ONLY_FIRST);}
            if(own)occupied=Shapes.join(occupied,Shapes.create(new AABB(lead.socket(),lead.socket()).inflate(Math.max(.09,lead.outside()/2+.04))),BooleanOp.ONLY_FIRST);
            if(Shapes.joinIsNotEmpty(occupied,Shapes.create(box.deflate(1e-6)),BooleanOp.AND))return PipeLaying.message("occupied",pos.toShortString());
        }
        return null;
    }
    /** All touched cells, including retained anchors and connecting leads, are permission checked before inventory. */
    public static Set<BlockPos> touched(Plan plan){
        var cells=new HashSet<BlockPos>(plan.path());
        for(var anchor:new PipeAnchor[]{plan.start(),plan.end()})if(anchor!=null&&anchor.host()!=null)cells.add(anchor.host());
        for(var lead:plan.leads()){
            cells.add(lead.owner());cells.add(lead.entry());
            for(var box:bounds(lead))BlockPos.betweenClosedStream(box).forEach(p->cells.add(p.immutable()));
        }
        return Set.copyOf(cells);
    }
    /** Registration is prepared only after the entire spatial/permission/inventory check succeeds. */
    public static Component commit(ServerPlayer player,Plan plan,Item material,int count,Component missing,Supplier<Work> prepare){
        var refused=check(player.level(),plan);if(refused!=null)return refused;
        for(var pos:touched(plan))if(!editable(player,pos))return PipeLaying.message("not_allowed");
        if(!player.getAbilities().instabuild&&!take(player,material,count))return missing;
        apply(player.level(),prepare.get());LinePieces.flush(player.server);return null;
    }
    public static void apply(Level level,Work work){
        boolean laying=FluidDeviceBlock.laying;FluidDeviceBlock.laying=true;
        try{for(var edit:work.edits()){
            if(edit.state()!=null)level.setBlock(edit.at(),edit.state(),edit.flags());edit.bind().accept(level.getBlockEntity(edit.at()));
        }}finally{FluidDeviceBlock.laying=laying;}
        work.finish().run();
    }
    public static boolean editable(ServerPlayer player,BlockPos pos){
        var level=player.serverLevel();return level.hasChunkAt(pos)&&level.getWorldBorder().isWithinBounds(pos)&&level.mayInteract(player,pos)
                &&!player.blockActionRestricted(level,pos,player.gameMode.getGameModeForPlayer());
    }
    public static boolean take(ServerPlayer player,Item material,int count){
        var inventory=player.getInventory();int held=0;
        for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).is(material))held+=inventory.getItem(i).getCount();
        if(held<count)return false;
        for(int i=0;i<inventory.getContainerSize()&&count>0;i++){
            var stack=inventory.getItem(i);if(!stack.is(material))continue;int taken=Math.min(count,stack.getCount());stack.shrink(taken);count-=taken;
        }inventory.setChanged();return true;
    }
}
