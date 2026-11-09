package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.runtime.fluid.ElectricStores;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A structure's FE port at its terminal box (batch 2026-11-03-rotating-machinery W5, build plan section 2): the NeoForge
 * energy capability of the terminal cell, on every face (reading A56), over its unit's electric store
 * ({@link ElectricStores}). A consumer's (motor, air cooler, induced tower) takes FE, a generator's gives it (A57). Other
 * mods' pushes and pulls move stored FE only: that is their protocol, and nothing wakes or solves. The unit is found at
 * each call: a motor's or generator's shaft unit at the structure's origin, else the structure's fluid registration.
 */
final class TerminalEnergy implements IEnergyStorage {
    private final ServerLevel level;
    private final EquipmentShellBlockEntity cell;

    TerminalEnergy(ServerLevel level, EquipmentShellBlockEntity cell) {
        this.level = level;
        this.cell = cell;
    }

    /** The unit behind this port and the world's stores, or null while either is missing. */
    private record Unit(ElectricStores stores, long id, boolean generator) {
    }

    private Unit unit() {
        if (cell.isRemoved() || !level.getServer().isSameThread()) return null;
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        var shape = cell.shape();
        var origin = cell.origin();
        if (world == null || shape == null || origin == null) return null;
        long id = 0;
        if (shape.kind() == EquipmentShape.Kind.MOTOR || shape.kind() == EquipmentShape.Kind.GENERATOR) {
            var u = world.shafts().unitAt(new PhysicalFluidTopology.Position(level.dimension().location().toString(), origin.getX(), origin.getY(), origin.getZ()));
            if (u != null) id = u.id();
        } else {
            // A fired heater's FE is its burners' (their combustion-air fans), registered at its last node.
            var fluid = com.wormzjl.icheme.science.equipment.HeatSides.burners(shape.kind()) ? ShapeRasterizer.fluidCells(shape).getLast() : ShapeRasterizer.fluidCell(shape);
            if (fluid != null && level.getBlockEntity(new BlockPos(origin.getX() + fluid.x(), origin.getY() + fluid.y(), origin.getZ() + fluid.z())) instanceof EquipmentShellBlockEntity node)
                id = node.fluidIdentity();
        }
        if (id <= 0) return null;
        var stores = world.electric();
        var generator = stores.generator(id);
        return generator == null ? null : new Unit(stores, id, generator);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        var u = unit();
        return u == null || u.generator() ? 0 : u.stores().receive(u.id(), toReceive, simulate);
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        var u = unit();
        return u == null || !u.generator() ? 0 : u.stores().extract(u.id(), toExtract, simulate);
    }

    @Override
    public int getEnergyStored() {
        var u = unit();
        return u == null ? 0 : u.stores().stored(u.id());
    }

    @Override
    public int getMaxEnergyStored() {
        var u = unit();
        return u == null ? 0 : u.stores().capacity(u.id());
    }

    @Override
    public boolean canExtract() {
        var u = unit();
        return u != null && u.generator();
    }

    @Override
    public boolean canReceive() {
        var u = unit();
        return u != null && !u.generator();
    }
}
