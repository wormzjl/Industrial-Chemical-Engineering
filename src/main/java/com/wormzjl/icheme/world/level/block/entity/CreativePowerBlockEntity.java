package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Unlimited extract-only FE capability, also pushing to each loaded adjacent receiver. No saved
 * inventory.
 */
public final class CreativePowerBlockEntity extends BlockEntity
        implements IEnergyStorage, com.wormzjl.icheme.runtime.instrument.Instrument.Holder {
    private final com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments =
            new com.wormzjl.icheme.runtime.instrument.Instrument.Metadata();
    private com.wormzjl.icheme.runtime.control.DataPort socketPrimary;
    private java.util.List<com.wormzjl.icheme.runtime.control.DataPort> sockets =
            java.util.List.of();

    @Override
    public com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments() {
        return instruments;
    }

    @Override
    public java.util.List<com.wormzjl.icheme.runtime.control.DataPort> powerPorts() {
        var primary = instruments.powerPort();
        if (!java.util.Objects.equals(primary, socketPrimary)) {
            socketPrimary = primary;
            sockets =
                    primary == null
                            ? java.util.List.of()
                            : com.wormzjl.icheme.runtime.power.PowerPorts.omnidirectional(primary);
        }
        return sockets;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);
    }

    @Override
    public void setRemoved() {
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(
            net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        instruments.write(tag);
    }

    @Override
    protected void loadAdditional(
            net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        instruments.read(tag);
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    public CreativePowerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CREATIVE_POWER.get(), pos, state);
    }

    public static void tick(
            Level level, BlockPos pos, BlockState state, CreativePowerBlockEntity source) {
        for (var face : Direction.values()) {
            var neighbour = pos.relative(face);
            if (!level.hasChunkAt(neighbour)) continue;
            var receiver =
                    level.getCapability(
                            Capabilities.EnergyStorage.BLOCK, neighbour, face.getOpposite());
            if (receiver != null && receiver.canReceive())
                receiver.receiveEnergy(Integer.MAX_VALUE, false);
        }
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        return 0;
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        return Math.max(0, amount);
    }

    @Override
    public int getEnergyStored() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMaxEnergyStored() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return false;
    }
}
