package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.world.level.block.LinePieces;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.world.level.block.ConduitBlock;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.*;
import java.util.*;

/** Chunk model copy of cable facts. The registered control store remains authoritative across chunk unloading. */
public final class ConduitBlockEntity extends ModelledBlockEntity implements LinePieces.Copy {
    private List<LinePieces.Piece> pieces=List.of();
    public List<LinePieces.Piece> linePieces(){return pieces;}
    public void linePieces(List<LinePieces.Piece> value){if(pieces.equals(value))return;pieces=List.copyOf(value);setChanged();LinePieces.clientCopy(this,pieces);if(level!=null&&!level.isClientSide())level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);}
    /** Client-thread loaded geometry index; never scans or loads world chunks for tool marks. */
    private static final Set<ConduitBlockEntity> CLIENT_CABLES=Collections.newSetFromMap(new WeakHashMap<>());
    public static List<ConduitBlockEntity> clientCables(){return List.copyOf(CLIENT_CABLES);}
    public static final ModelProperty<ConduitGeometry.Key> GEOMETRY=new ModelProperty<>();
    public static final ModelProperty<Boolean> MARKED=new ModelProperty<>();
    private ConduitGeometry.Key geometry;
    private Map<Direction,UUID> endpoints=Map.of();
    public ConduitBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.DATA_CABLE.get(),pos,state);geometry=new ConduitGeometry.Key(0,0,((ConduitBlock)state.getBlock()).medium());}
    public net.neoforged.neoforge.energy.IEnergyStorage energy(){return geometry.medium()==com.wormzjl.icheme.science.equipment.CableMedium.POWER&&level instanceof ServerLevel server?com.wormzjl.icheme.runtime.power.PowerNetwork.of(server.getServer()).capability(cable().site()):null;}
    public ConduitGeometry.Key geometry(){return geometry;}
    public DataBus.Cable cable(){
        if(level==null)throw new IllegalStateException("Cable has no level");
        return new DataBus.Cable(new DataBus.Site(level.dimension().location().toString(),worldPosition,0),geometry,0,endpoints);
    }
    public void configure(DataBus.Cable cable){
        if(!cable.site().cell().equals(worldPosition)||cable.site().lane()!=0||level==null
                ||!cable.site().dimension().equals(level.dimension().location().toString()))throw new IllegalArgumentException("Cable belongs to another cell");
        if(cable.geometry().medium()!=((ConduitBlock)getBlockState().getBlock()).medium())throw new IllegalArgumentException("Cable medium differs from its block");
        geometry=cable.geometry();endpoints=cable.endpoints();setChanged();
        if(level instanceof ServerLevel server){
            var state=getBlockState();for(var face:Direction.values())state=state.setValue(ConduitBlock.connection(face),(geometry.joints()&(1<<face.ordinal()))!=0);
            level.setBlock(worldPosition,state,Block.UPDATE_CLIENTS);
            FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().cable(cable));
            level.sendBlockUpdated(worldPosition,state,state,Block.UPDATE_CLIENTS);
        }else refreshModelData();
    }
    @Override public void onLoad(){super.onLoad();LinePieces.clientCopy(this,pieces);if(level!=null&&level.isClientSide())CLIENT_CABLES.add(this);if(level instanceof ServerLevel server)FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().loaded(this));}
    @Override protected void clientGeometryChanged(){CLIENT_CABLES.add(this);}
    @Override public void setRemoved(){LinePieces.unloaded(this);super.setRemoved();if(level!=null&&level.isClientSide())CLIENT_CABLES.remove(this);}
    @Override public void onChunkUnloaded(){LinePieces.unloaded(this);super.onChunkUnloaded();if(level!=null&&level.isClientSide())CLIENT_CABLES.remove(this);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);tag.put("LinePieces",LinePieces.save(pieces));tag.putString("Medium",geometry.medium().name());tag.putInt("Joints",geometry.joints());tag.putLong("Offsets",geometry.offsets());tag.put("Endpoints",DataBus.saveEndpoints(endpoints));}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);pieces=LinePieces.load(tag);LinePieces.clientCopy(this,pieces);
        try{geometry=new ConduitGeometry.Key(tag.getInt("Joints"),tag.getLong("Offsets"),((ConduitBlock)getBlockState().getBlock()).medium());}catch(IllegalArgumentException invalid){geometry=new ConduitGeometry.Key(0,0,((ConduitBlock)getBlockState().getBlock()).medium());}
        try{endpoints=DataBus.loadEndpoints(tag.getList("Endpoints",10));}catch(IllegalArgumentException invalid){endpoints=Map.of();}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public ModelData getModelData(){return ModelData.builder().with(GEOMETRY,geometry).with(MARKED,ConduitGeometry.markedAt(worldPosition.getX(),worldPosition.getY(),worldPosition.getZ())).build();}
}
