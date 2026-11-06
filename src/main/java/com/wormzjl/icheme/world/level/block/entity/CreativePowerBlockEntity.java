package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Unlimited extract-only FE capability, also pushing to each loaded adjacent receiver. No saved inventory. */
public final class CreativePowerBlockEntity extends BlockEntity implements IEnergyStorage {
    public CreativePowerBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.CREATIVE_POWER.get(), pos, state); }
    public static void tick(Level level, BlockPos pos, BlockState state, CreativePowerBlockEntity source) {
        for (var face : Direction.values()) {
            var neighbour = pos.relative(face);
            if (!level.hasChunkAt(neighbour)) continue;
            var receiver = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbour, face.getOpposite());
            if (receiver != null && receiver.canReceive()) receiver.receiveEnergy(Integer.MAX_VALUE, false);
        }
    }
    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }
    @Override public int extractEnergy(int amount, boolean simulate) { return Math.max(0, amount); }
    @Override public int getEnergyStored() { return Integer.MAX_VALUE; }
    @Override public int getMaxEnergyStored() { return Integer.MAX_VALUE; }
    @Override public boolean canExtract() { return true; }
    @Override public boolean canReceive() { return false; }
}
