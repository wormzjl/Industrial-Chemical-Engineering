package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.control.DataPort;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A creative motor's data socket (part C of PUMP_SPEED_SHUT_VALVE_REVIEW.md): the copy of its socket fact that the laying tool
 * and the client read, as an alarm beacon's ({@link DataPort.Owner}). The fact is the control facts' (keyed by the motor's
 * shaft unit, {@code ControlHosts.bindCreativeMotor}, which sets this copy at its bind); the unit and its settings are the
 * engine's. A motor placed before this had none: its block entity is made on first access and bound at the next pass. It
 * ticks nothing.
 */
public final class CreativeMotorBlockEntity extends BlockEntity implements DataPort.Owner {
    private DataPort port;

    public CreativeMotorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CREATIVE_MOTOR.get(), pos, state);
    }

    @Override
    public DataPort dataPort() {
        return port;
    }

    /** Sets the socket's copy to {@code port}, the control fact, and sends it to the clients. */
    public void bind(DataPort port) {
        if (port.equals(this.port)) return;
        this.port = port;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) com.wormzjl.icheme.client.DataControlRenderer.loaded(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) com.wormzjl.icheme.client.DataControlRenderer.unloaded(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (port != null) tag.put("DataPort", port.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        try {
            port = tag.contains("DataPort", 10) ? DataPort.load(tag.getCompound("DataPort")) : null;
        } catch (IllegalArgumentException invalid) {
            port = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider provider) {
        loadAdditional(tag, provider);
        if (level != null) com.wormzjl.icheme.client.DataControlRenderer.loaded(this);
    }
}
