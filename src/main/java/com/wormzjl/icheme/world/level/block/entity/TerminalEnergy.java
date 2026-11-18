package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.power.PowerTerminals;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A structure's FE port at its terminal box (batch 2026-11-03-rotating-machinery W5, build plan section 2): the NeoForge
 * energy capability of the terminal cell on every face, over the same registered store as the power grid.
 * External FE calls move stored energy without solving.
 */
final class TerminalEnergy implements IEnergyStorage {
    private final ServerLevel level;
    private final EquipmentShellBlockEntity cell;

    TerminalEnergy(ServerLevel level, EquipmentShellBlockEntity cell) {
        this.level = level;
        this.cell = cell;
    }

    private IEnergyStorage port() {
        if (cell.isRemoved() || !level.getServer().isSameThread()) return null;
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        var shape = cell.shape();
        var origin = cell.origin();
        if (world == null || shape == null || origin == null) return null;
        long id = PowerTerminals.unit(world.powerTopology(), Positions.of(level, origin), shape);
        return id == 0 ? null : world.electric().port(id);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        var port = port();
        return port == null ? 0 : port.receiveEnergy(toReceive, simulate);
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        var port = port();
        return port == null ? 0 : port.extractEnergy(toExtract, simulate);
    }

    @Override
    public int getEnergyStored() {
        var port = port();
        return port == null ? 0 : port.getEnergyStored();
    }

    @Override
    public int getMaxEnergyStored() {
        var port = port();
        return port == null ? 0 : port.getMaxEnergyStored();
    }

    @Override
    public boolean canExtract() {
        var port = port();
        return port != null && port.canExtract();
    }

    @Override
    public boolean canReceive() {
        var port = port();
        return port != null && port.canReceive();
    }
}
