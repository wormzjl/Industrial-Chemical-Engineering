package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Derived socket-lead occupancy. World/index writes stay on the level thread; readers see immutable cell buckets. */
public final class LinePieces {
    public record Piece(DataPort port,DataBus.Site parent){
        public Piece{Objects.requireNonNull(port);Objects.requireNonNull(parent);if(!port.dimension().equals(parent.dimension())||!port.cell().equals(parent.cell()))throw new IllegalArgumentException("Lead belongs to another entry");}
        public LinePlacement.Lead lead(){return ConduitLineBackend.socket(port).lead();}
        public Map<BlockPos,VoxelShape> cells(){return slices(lead());}
        public CompoundTag save(){var t=new CompoundTag();t.put("Port",port.save());t.putInt("Lane",parent.lane());return t;}
        public static Piece load(CompoundTag t){var p=DataPort.load(t.getCompound("Port"));return new Piece(p,new DataBus.Site(p.dimension(),p.cell(),t.getInt("Lane")));}
    }
    public interface Copy {
        List<Piece> linePieces();
        void linePieces(List<Piece> pieces);
    }
    private record Bucket(List<Piece> pieces,VoxelShape shape){}
    private static final class Index {
        // Main-thread ownership for sources/refcounts. Buckets alone are read by collision/model workers.
        final Map<Object,List<Piece>> sources=new HashMap<>();
        final Map<Piece,Integer> refs=new HashMap<>();
        final Map<Piece,Map<BlockPos,VoxelShape>> geometry=new HashMap<>();
        final ConcurrentHashMap<BlockPos,Bucket> cells=new ConcurrentHashMap<>();
        List<Piece> visible=List.of();boolean visibleDirty;
        void set(Object source,List<Piece> next){
            var old=sources.getOrDefault(source,List.of());if(old.equals(next))return;
            var touched=new HashMap<BlockPos,Set<Piece>>();
            for(var p:old){int n=refs.get(p)-1;if(n>0){refs.put(p,n);continue;}refs.remove(p);
                for(var cell:geometry.get(p).keySet())touched.computeIfAbsent(cell,k->new HashSet<>(cells.get(k).pieces())).remove(p);
            }
            if(next.isEmpty())sources.remove(source);else sources.put(source,List.copyOf(next));
            for(var p:next){int n=refs.merge(p,1,Integer::sum);if(n>1)continue;geometry.computeIfAbsent(p,k->p.cells());
                for(var cell:geometry.get(p).keySet())touched.computeIfAbsent(cell,k->new HashSet<>(cells.containsKey(k)?cells.get(k).pieces():List.of())).add(p);
            }
            for(var entry:touched.entrySet()){
                var cell=entry.getKey();var all=entry.getValue();if(all.isEmpty()){cells.remove(cell);continue;}
                var shape=Shapes.empty();for(var p:all)shape=Shapes.or(shape,geometry.get(p).get(cell));
                cells.put(cell,new Bucket(List.copyOf(all),shape.optimize()));
            }
            for(var p:old)if(!refs.containsKey(p))geometry.remove(p);visibleDirty=true;
        }
    }

    // Values contain no Level/Server references, so closed worlds are not retained.
    private static final Map<Level,Index> INDEX=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer,Set<DataBus.Site>> DUE=Collections.synchronizedMap(new WeakHashMap<>());
    private static Index index(Level level){synchronized(INDEX){return INDEX.computeIfAbsent(level,k->new Index());}}
    private LinePieces(){}
    /** Same conservative swept envelope is used for preflight, occupied cells and collision/selection. */
    public static Map<BlockPos,VoxelShape> slices(LinePlacement.Lead lead){
        var parts=new HashMap<BlockPos,VoxelShape>();
        for(var box:LinePlacement.bounds(lead))for(var at:BlockPos.betweenClosedStream(box.deflate(1e-7)).toList()){
            var cut=box.intersect(new AABB(at));if(cut.getXsize()<=1e-7||cut.getYsize()<=1e-7||cut.getZsize()<=1e-7)continue;
            parts.merge(at.immutable(),Shapes.create(cut.move(-at.getX(),-at.getY(),-at.getZ())),Shapes::or);
        }return Map.copyOf(parts);
    }
    public static VoxelShape shape(BlockGetter getter,BlockPos cell,VoxelShape base){
        if(!(getter instanceof Level level))return base;var bucket=index(level).cells.get(cell);return bucket==null?base:Shapes.or(base,bucket.shape());
    }
    public static List<Piece> at(Level level,BlockPos cell){var bucket=index(level).cells.get(cell);return bucket==null?List.of():bucket.pieces();}
    public static List<Piece> visible(Level level){var idx=index(level);if(idx.visibleDirty){idx.visible=List.copyOf(idx.refs.keySet());idx.visibleDirty=false;}return idx.visible;}
    public static void clientCopy(net.minecraft.world.level.block.entity.BlockEntity entity,List<Piece> pieces){
        if(entity.getLevel()!=null&&entity.getLevel().isClientSide())index(entity.getLevel()).set(entity.getBlockPos(),pieces);
    }
    public static void unloaded(net.minecraft.world.level.block.entity.BlockEntity entity){clientCopy(entity,List.of());}
    public static void queue(MinecraftServer server,DataBus.Site site){synchronized(DUE){DUE.computeIfAbsent(server,k->new LinkedHashSet<>()).add(site);}}
    public static void watch(MinecraftServer server,ControlFacts facts){
        facts.lineChanges(site->queue(server,site));
    }
    /** Structural due work only, no solver, process updates, network scans or chunk loads. */
    public static void flush(MinecraftServer server){
        Set<DataBus.Site> due;synchronized(DUE){due=DUE.remove(server);}if(due==null)return;
        var world=FluidWorldAuthority.find(server).orElse(null);if(world==null)return;var facts=world.controls().facts();
        for(var site:due){var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(site.dimension())));if(level==null)continue;
            var idx=index(level);var previous=idx.sources.getOrDefault(site,List.of());var cable=facts.cable(site);var next=new ArrayList<Piece>();
            if(cable!=null)for(var id:cable.endpoints().values()){var port=facts.port(id);if(port!=null&&CableConnections.binds(cable,port))next.add(new Piece(port,site));}
            idx.set(site,List.copyOf(next));var touched=new HashSet<BlockPos>();previous.forEach(p->touched.addAll(p.cells().keySet()));next.forEach(p->touched.addAll(p.cells().keySet()));
            // Native entry copies and empty-cell proxies carry the same rebuildable descriptors to clients.
            if(level.hasChunkAt(site.cell())&&level.getBlockEntity(site.cell()) instanceof Copy copy){
                var all=new ArrayList<Piece>();for(int lane=0;lane<=com.wormzjl.icheme.science.equipment.RackGeometry.OPTIONS;lane++)all.addAll(idx.sources.getOrDefault(new DataBus.Site(site.dimension(),site.cell(),lane),List.of()));copy.linePieces(List.copyOf(all));
            }
            for(var cell:touched)if(level.hasChunkAt(cell)){
                var state=level.getBlockState(cell);var bucket=idx.cells.get(cell);
                if(bucket==null){if(state.getBlock() instanceof LinePieceBlock)level.removeBlock(cell,false);continue;}
                if(state.isAir()||state.canBeReplaced())level.setBlock(cell,ModBlocks.LINE_PIECE.get().defaultBlockState(),3);
                if(level.getBlockEntity(cell) instanceof LinePieceBlockEntity copy)copy.linePieces(bucket.pieces());
                // Owned equipment/entry blocks keep their identity and union this bucket through shape().
            }
        }
    }
    public static ListTag save(List<Piece> pieces){var list=new ListTag();pieces.forEach(p->list.add(p.save()));return list;}
    public static List<Piece> load(CompoundTag tag){
        try{var list=tag.getList("LinePieces",10);if(list.size()>16)throw new IllegalArgumentException("Too many lead copies");var result=new ArrayList<Piece>();for(var t:list)result.add(Piece.load((CompoundTag)t));return List.copyOf(result);}catch(IllegalArgumentException damaged){return List.of();}
    }
    public static LineTarget target(Level level,LineTarget target,LineProfile profile){
        if(!(level.getBlockState(target.clicked()).getBlock() instanceof LinePieceBlock))return target;
        var match=at(level,target.clicked()).stream().filter(p->LineProfile.of(p.port().kind().medium())==profile).min(Comparator.comparingDouble(p->p.port().centre().distanceToSqr(target.clicked().getCenter()))).orElse(null);
        if(match==null)return target;return new LineTarget(null,match.parent().cell(),target.face(),new Vec3(.5,.5,.5),match.parent().lane());
    }
}
