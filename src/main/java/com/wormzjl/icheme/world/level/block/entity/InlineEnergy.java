package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.runtime.fluid.*;

import net.neoforged.neoforge.energy.IEnergyStorage;

/** The integral drive's receive-only electrical port. All calls only transfer stored energy. */
final class InlineEnergy implements IEnergyStorage {
    private final ElectricStores stores;
    private final long id;

    InlineEnergy(ElectricStores stores, long id) {
        this.stores = stores;
        this.id = id;
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        return stores.receive(id, amount, simulate);
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return stores.stored(id);
    }

    @Override
    public int getMaxEnergyStored() {
        return stores.capacity(id);
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
