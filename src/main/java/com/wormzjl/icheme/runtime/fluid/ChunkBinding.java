package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentShellBlock;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.*;

/**
 * The fluid world authority's chunk binding (code base review R8): what a chunk load binds and queues (its block
 * entities bound to their registrations or registered again, the equipment waiting for its other chunks, its
 * registrations and shaft parts queued once for their re-alignment, the structures recorded over it), the index of the
 * chunks each recorded structure spans, and the hold of every registration whose dimension has no level. It never loads
 * a chunk or runs a solve. Every entry is called by {@link FluidWorldAuthority}, which checks the thread
 * ({@code owned()}) and, where the entry did, that it is open first.
 */
final class ChunkBinding {
    private final FluidWorldAuthority authority;
    ChunkBinding(FluidWorldAuthority authority){this.authority=authority;}
    /** Equipment whose remaining chunks must load before its complete structure can be registered. */
    private final Map<PhysicalFluidTopology.Position,EquipmentStructure.Vessel> unboundStructures=new LinkedHashMap<>();
    /** Registrations whose chunk loaded, by dimension, each queued once until the re-alignment checks it (REGISTRY_FIRST_PLAN.md 4.2). */
    private final Map<String,ArrayDeque<Long>> realignment=new HashMap<>();
    private final Set<Long> realigning=new HashSet<>();
    /** The shaft parts whose chunk loaded, by dimension, each queued once until the re-alignment checks it (W5c C1). */
    private final Map<String,ArrayDeque<Long>> shaftRealignment=new HashMap<>();
    private final Set<Long> shaftRealigning=new HashSet<>();
    /** The origins of the structures the registry records, by dimension and by chunk of their box: a chunk load queues them for their re-alignment (plan 4.4). */
    final Map<String,Map<Long,Set<PhysicalFluidTopology.Position>>> structureChunks=new HashMap<>();
    /** Adds or removes the structure at {@code origin} in the index of the chunks its box spans. */
    void indexStructure(PhysicalFluidTopology.Position origin,WorldTopologyLedger.Structure structure,boolean add) {
        var shape=structure.shape();var chunks=structureChunks.computeIfAbsent(origin.dimension(),k->new HashMap<>());
        for(int x=origin.x()>>4;x<=(origin.x()+shape.sizeX()-1)>>4;x++)for(int z=origin.z()>>4;z<=(origin.z()+shape.sizeZ()-1)>>4;z++) {
            long chunk=net.minecraft.world.level.ChunkPos.asLong(x,z);
            if(add)chunks.computeIfAbsent(chunk,k->new HashSet<>()).add(origin);
            else{var at=chunks.get(chunk);if(at!=null&&at.remove(origin)&&at.isEmpty())chunks.remove(chunk);}
        }
    }
    /**
     * Rebind placed devices, queue missing registrations, then mark their presentation buckets. This also rebuilds an
     * empty authority after a format reset, including equipment spanning chunks. The chunk's registrations, and the
     * structures recorded over it, are queued for their re-alignment (REGISTRY_FIRST_PLAN.md 4.2, 4.4). Never loads a chunk
     * or runs a solve.
     */
    void loadedChunk(ResourceKey<Level> dimension,long chunk) {
        var level=authority.server.getLevel(dimension);if(level==null)return;
        var at=new net.minecraft.world.level.ChunkPos(chunk);
        var loaded=level.getChunkSource().getChunkNow(at.x,at.z);if(loaded==null)return;
        String name=dimension.location().toString();
        for(var cell:List.copyOf(loaded.getBlockEntitiesPos())) {
            var block=loaded.getBlockState(cell).getBlock();
            if(!(block instanceof FluidDeviceBlock)&&!(block instanceof EquipmentShellBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.PipeRackBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.ControlPanelBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.ConduitBlock))continue;
            var entity=loaded.getBlockEntity(cell); // Includes saved cells not yet promoted from pending NBT.
            if(entity instanceof com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity box)authority.controls().loaded(box);
            else if(entity instanceof com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity cable)authority.controls().loaded(cable);
            else if(entity instanceof com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity rack){rack.bind(authority);authority.controls().loaded(rack);}
            else if(entity instanceof FluidDeviceBlockEntity pipe) {
                var position=new PhysicalFluidTopology.Position(name,cell.getX(),cell.getY(),cell.getZ());
                var record=authority.registry.at(position).orElseGet(()->authority.place(position,pipe.kind(),PhysicalFluidTopology.Direction.valueOf(pipe.getBlockState().getValue(FluidDeviceBlock.FACING).name()),FluidDeviceBlock.cutoff(pipe.getBlockState()),FluidDeviceBlock.hasPressureValve(pipe.getBlockState()),FluidDeviceBlock.hasFilter(pipe.getBlockState()),FluidDeviceBlock.hasMover(pipe.getBlockState())?pipe.getBlockState().getValue(FluidDeviceBlock.INLINE_MOVER).kind():TopologyCompiler.Kind.PIPE,pipe.kind()==TopologyCompiler.Kind.PIPE?pipe.joints():PhysicalFluidTopology.ALL_FACES));
                pipe.bind(authority,record.device());
            } else if(entity instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&(shell.shape().fluidDevice()||com.wormzjl.icheme.science.equipment.UnitDesign.shafted(shell.shape().kind()))) {
                var origin=shell.origin();
                unboundStructures.put(new PhysicalFluidTopology.Position(name,origin.getX(),origin.getY(),origin.getZ()),new EquipmentStructure.Vessel(origin,shell.shape()));
            }
        }
        for(var it=unboundStructures.entrySet().iterator();it.hasNext();) {
            var entry=it.next();if(!entry.getKey().dimension().equals(name))continue;
            var vessel=entry.getValue();var origin=vessel.origin();
            if(at.x<(origin.getX()>>4)||at.x>((origin.getX()+vessel.shape().sizeX()-1)>>4)
                    ||at.z<(origin.getZ()>>4)||at.z>((origin.getZ()+vessel.shape().sizeZ()-1)>>4))continue;
            if(EquipmentStructure.restoreFluidBinding(level,vessel))it.remove();
        }
        var devices=authority.registry.chunk(name,chunk);
        if(!devices.isEmpty())FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignChunks);
        for(long id:devices){authority.presentation.markDirty(id);if(realigning.add(id))realignment.computeIfAbsent(name,k->new ArrayDeque<>()).add(id);}
        for(long id:authority.topology.shaftsIn(name,chunk))if(shaftRealigning.add(id))shaftRealignment.computeIfAbsent(name,k->new ArrayDeque<>()).add(id);
        authority.controls().loadedChunk(name,chunk);
        var structures=structureChunks.getOrDefault(name,Map.of()).get(chunk);
        if(structures!=null)for(var origin:structures)EquipmentStructure.realignLater(level,new net.minecraft.core.BlockPos(origin.x(),origin.y(),origin.z()));
    }
    /**
     * The next registration of {@code dimension} the re-alignment checks, taken off its queue, or 0 when none is queued
     * (registry first, REGISTRY_FIRST_PLAN.md 4.2). Each registration of a loaded chunk is queued once, at the load.
     */
    long nextRealignment(String dimension) {
        var queue=authority.closed?null:realignment.get(dimension);if(queue==null)return 0;
        Long id=queue.poll();if(queue.isEmpty())realignment.remove(dimension);
        if(id==null)return 0;realigning.remove(id);return id;
    }
    /** The next shaft part of {@code dimension} the re-alignment checks, taken off its queue, or 0 when none is queued (W5c C1). */
    long nextShaftRealignment(String dimension) {
        var queue=authority.closed?null:shaftRealignment.get(dimension);if(queue==null)return 0;
        Long id=queue.poll();if(queue.isEmpty())shaftRealignment.remove(dimension);
        if(id==null)return 0;shaftRealigning.remove(id);return id;
    }
    /**
     * Holds every registration whose dimension has no level (REGISTRY_FIRST_PLAN.md 4.2): no block can stand for it, so it
     * keeps what it holds and joins nothing until a level of that dimension loads its chunk again. One queued event.
     */
    void holdWithoutLevel() {
        var levels=new HashSet<String>();for(var level:authority.server.getAllLevels())levels.add(level.dimension().location().toString());
        var edits=new ArrayList<WorldTopologyLedger.Edit>();
        for(var r:authority.registrations().values()) {
            var d=r.device();
            if(!d.port()&&!d.held()&&!levels.contains(d.position().dimension()))
                edits.add(new WorldTopologyLedger.Edit(d.id(),new WorldTopologyLedger.Registration(d.withHeld(true),r.spec(),Math.addExact(r.revision(),1))));
        }
        if(edits.isEmpty())return;
        edits.sort(Comparator.comparingLong(WorldTopologyLedger.Edit::id));
        authority.submit(edits,authority.topology.nextIdentity());
        IChemE.LOGGER.warn("fluid_realign held={} reason=NO_LEVEL: their dimensions have no level; /icheme devices held lists them",edits.size());
    }
    /** At close: the equipment waiting for its chunks, both re-alignment queues and the structures' chunk index. */
    void clear(){unboundStructures.clear();realignment.clear();realigning.clear();shaftRealignment.clear();shaftRealigning.clear();structureChunks.clear();}
}
