package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.DataCableGeometry;
import com.wormzjl.icheme.world.level.block.DataCableBlock;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.*;
import java.util.*;

/** Chunk model copy of cable facts. The registered control store remains authoritative across chunk unloading. */
public final class DataCableBlockEntity extends BlockEntity {
    public static final ModelProperty<DataCableGeometry.Key> GEOMETRY=new ModelProperty<>();
    private DataCableGeometry.Key geometry=new DataCableGeometry.Key(0,0);
    private Map<Direction,UUID> endpoints=Map.of();
    public DataCableBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.DATA_CABLE.get(),pos,state);}
    public DataCableGeometry.Key geometry(){return geometry;}
    public DataBus.Cable cable(){
        if(level==null)throw new IllegalStateException("Cable has no level");
        return new DataBus.Cable(new DataBus.Site(level.dimension().location().toString(),worldPosition,0),geometry,0,endpoints);
    }
    public void configure(DataBus.Cable cable){
        if(!cable.site().cell().equals(worldPosition)||cable.site().lane()!=0||level==null
                ||!cable.site().dimension().equals(level.dimension().location().toString()))throw new IllegalArgumentException("Cable belongs to another cell");
        geometry=cable.geometry();endpoints=cable.endpoints();setChanged();
        if(level instanceof ServerLevel server){
            var state=getBlockState();for(var face:Direction.values())state=state.setValue(DataCableBlock.connection(face),(geometry.joints()&(1<<face.ordinal()))!=0);
            level.setBlock(worldPosition,state,Block.UPDATE_CLIENTS);
            FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().cable(cable));
            level.sendBlockUpdated(worldPosition,state,state,Block.UPDATE_CLIENTS);
        }else{requestModelDataUpdate();level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),0);}
    }
    @Override public void onLoad(){super.onLoad();if(level instanceof ServerLevel server)FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().loaded(this));}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);tag.putInt("Joints",geometry.joints());tag.putLong("Offsets",geometry.offsets());}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);
        try{geometry=new DataCableGeometry.Key(tag.getInt("Joints"),tag.getLong("Offsets"));}catch(IllegalArgumentException invalid){geometry=new DataCableGeometry.Key(0,0);}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider provider){loadAdditional(tag,provider);requestModelDataUpdate();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),0);}
    @Override public ModelData getModelData(){return ModelData.of(GEOMETRY,geometry);}
}
