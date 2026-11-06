package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.*;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** Physical local controller or DCS console. Settings live in the registered control facts, not in a ticking tile. */
public final class ControlPanelBlockEntity extends BlockEntity {
    private UUID identity=UUID.randomUUID();
    private DataPort port;
    public ControlPanelBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.CONTROL_PANEL.get(),pos,state);}
    public UUID identity(){return identity;}
    public boolean dcs(){return getBlockState().is(ModBlocks.DCS.get());}
    public DataPort dataPort(){return port;}
    public void bind(UUID id,DataPort port){
        identity=id;this.port=port;setChanged();
        if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    @Override public void onLoad(){
        super.onLoad();
        if(level instanceof ServerLevel server)FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().loaded(this));
        else if(level!=null)com.wormzjl.icheme.client.DataControlRenderer.loaded(this);
    }
    @Override public void setRemoved(){super.setRemoved();if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.DataControlRenderer.unloaded(this);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);tag.putUUID("ControlIdentity",identity);if(port!=null)tag.put("DataPort",port.save());}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);if(tag.hasUUID("ControlIdentity"))identity=tag.getUUID("ControlIdentity");
        try{port=tag.contains("DataPort",10)?DataPort.load(tag.getCompound("DataPort")):null;}catch(IllegalArgumentException invalid){port=null;}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider provider){loadAdditional(tag,provider);if(level!=null)com.wormzjl.icheme.client.DataControlRenderer.loaded(this);}
}
