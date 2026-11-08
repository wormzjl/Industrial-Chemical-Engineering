package com.wormzjl.icheme.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chunk-baked geometry has one update lifecycle for equipment, pipes, rack lanes and conduits. Both
 * an initial chunk tag and a live packet load the new geometry before invalidating NeoForge's
 * model-data cache and the rendered section.
 */
public abstract class ModelledBlockEntity extends BlockEntity {
    protected ModelledBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }

    @Override
    public final ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public final void onDataPacket(
            Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        refreshModelData();
    }

    @Override
    public final void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        refreshModelData();
    }

    /** Also used for geometry assigned directly on the client; it never updates process state. */
    protected final void refreshModelData() {
        clientGeometryChanged();
        requestModelDataUpdate();
        if (level != null && level.isClientSide)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 0);
    }

    /**
     * Refresh client-only indices after loading geometry, without replacing the shared cache
     * invalidation.
     */
    protected void clientGeometryChanged() {}
}
