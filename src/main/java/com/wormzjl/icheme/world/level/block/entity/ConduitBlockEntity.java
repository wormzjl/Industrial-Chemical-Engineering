package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.world.level.block.ConduitBlock;
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
public final class ConduitBlockEntity extends BlockEntity {
    public static final ModelProperty<ConduitGeometry.Key> GEOMETRY=new ModelProperty<>();
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
        }else{requestModelDataUpdate();level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),0);}
    }
    @Override public void onLoad(){super.onLoad();if(level instanceof ServerLevel server)FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().loaded(this));}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);tag.putString("Medium",geometry.medium().name());tag.putInt("Joints",geometry.joints());tag.putLong("Offsets",geometry.offsets());}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);
        try{geometry=new ConduitGeometry.Key(tag.getInt("Joints"),tag.getLong("Offsets"),((ConduitBlock)getBlockState().getBlock()).medium());}catch(IllegalArgumentException invalid){geometry=new ConduitGeometry.Key(0,0,((ConduitBlock)getBlockState().getBlock()).medium());}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider provider){loadAdditional(tag,provider);requestModelDataUpdate();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),0);}
    @Override public ModelData getModelData(){return ModelData.of(GEOMETRY,geometry);}
}
