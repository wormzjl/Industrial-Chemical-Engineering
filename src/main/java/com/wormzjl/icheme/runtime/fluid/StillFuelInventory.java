package com.wormzjl.icheme.runtime.fluid;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wormzjl.icheme.science.plant.still.BatchStill;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** The single item slot belonging to each solid-fired pot, in the same checkpoint as its burning energy. */
final class StillFuelInventory {
    private final FluidWorldAuthority world;
    private final Map<Long, PhysicalFluidTopology.Position> missingLegacySlots = new HashMap<>();

    StillFuelInventory(FluidWorldAuthority world) {
        this.world = world;
    }

    /** Validate saved stacks with the live registry and adopt pre-schema-32 slots before the first process slice. */
    void restore() {
        for (var saved : world.topology.stills().values()) {
            if (saved.heater() == BatchStill.Heater.SOLID) contents(unit(saved.id()));
        }
    }

    @Nullable IItemHandler slot(long pot) {
        return unit(pot) == null ? null : new Port(pot);
    }

    void chunkLoaded(String dimension, long chunk) {
        missingLegacySlots.values().removeIf(p -> p.dimension().equals(dimension)
                && net.minecraft.world.level.ChunkPos.asLong(p.x() >> 4,p.z() >> 4) == chunk);
    }

    void removed(long pot) {
        missingLegacySlots.remove(pot);
    }

    private @Nullable StillUnit unit(long pot) {
        var u = world.topology.still(pot);
        if (u == null || u.heater() != BatchStill.Heater.SOLID) return null;
        if (u.fuelStack() != null) return u;
        var record = world.registrations().get(pot);
        if (record == null) return null;
        var p = record.device().position();
        var level = world.serverLevel(p.dimension());
        if (level == null) return null;
        var at = new BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(at) && p.equals(missingLegacySlots.get(pot))) return null;
        var tag = world.savedBlockEntity(p.dimension(), at);
        if (tag == null || !"icheme:equipment_shell".equals(tag.getString("id")) || tag.getLong("FluidIdentity") != pot) {
            if (!level.hasChunkAt(at)) missingLegacySlots.put(pot, p);
            return null;
        }
        missingLegacySlots.remove(pot);
        var stack = ItemStack.EMPTY;
        if (tag.contains("StillFuel")) {
            if (!tag.contains("StillFuel", Tag.TAG_COMPOUND)) throw new IllegalStateException("Invalid still fuel inventory at " + p);
            var legacy = tag.getCompound("StillFuel");
            if (!legacy.contains("Size", Tag.TAG_INT) || legacy.getInt("Size") != 1 || !(legacy.get("Items") instanceof ListTag items))
                throw new IllegalStateException("Invalid still fuel slot at " + p);
            if (items.size() > 1 || (!items.isEmpty() && (items.getElementType() != Tag.TAG_COMPOUND
                    || !items.getCompound(0).contains("Slot", Tag.TAG_INT) || items.getCompound(0).getInt("Slot") != 0)))
                throw new IllegalStateException("Invalid still fuel items at " + p);
            if (!items.isEmpty()) stack = decode(items.getCompound(0));
        }
        u = u.withFuelStack(encode(stack));
        world.topology.still(u);
        return u;
    }

    private ItemStack decode(CompoundTag tag) {
        var ops = world.server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.parse(ops, tag).getOrThrow();
    }

    private ItemStack contents(StillUnit u) {
        if (u == null || u.fuelStack().isEmpty()) return ItemStack.EMPTY;
        try {
            return decode(TagParser.parseTag(u.fuelStack()));
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("Invalid fuel item for still " + u.id(), e);
        }
    }

    private String encode(ItemStack stack) {
        return stack.isEmpty() ? "" : stack.save(world.server.registryAccess()).toString();
    }

    double burn(long pot) {
        var u = unit(pot);
        var stack = contents(u);
        double energy = EquipmentShellBlockEntity.fuelEnergy(stack);
        if (!(energy > 0)) return 0;
        var next = stack.getCount() == 1 ? stack.getCraftingRemainingItem() : stack.copyWithCount(stack.getCount() - 1);
        world.topology.still(u.withFuelStack(encode(next)));
        return energy;
    }

    /** A retained hopper capability always looks up the current pot, including after it was dismantled. */
    private final class Port implements IItemHandler {
        private final long pot;
        Port(long pot) { this.pot = pot; }

        @Override public int getSlots() { return 1; }
        @Override public int getSlotLimit(int slot) { return net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE; }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && EquipmentShellBlockEntity.fuelEnergy(stack) > 0;
        }
        @Override public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? contents(unit(pot)) : ItemStack.EMPTY;
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            var u = unit(pot);
            if (u == null || !isItemValid(slot, stack)) return stack;
            var inventory = new ItemStackHandler(1);
            inventory.setStackInSlot(0, contents(u));
            var rest = inventory.insertItem(0, stack, simulate);
            if (!simulate && rest.getCount() < stack.getCount()) commit(u, inventory);
            return rest;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var u = unit(pot);
            if (slot != 0 || amount <= 0 || u == null) return ItemStack.EMPTY;
            var inventory = new ItemStackHandler(1);
            inventory.setStackInSlot(0, contents(u));
            var result = inventory.extractItem(0, amount, simulate);
            if (!simulate && !result.isEmpty()) commit(u, inventory);
            return result;
        }
        private void commit(StillUnit u, ItemStackHandler inventory) {
            world.topology.still(u.withFuelStack(encode(inventory.getStackInSlot(0))));
            world.stillFuelChanged(pot);
        }
    }
}
