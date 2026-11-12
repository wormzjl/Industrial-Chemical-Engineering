package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.runtime.fluid.Converters;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * A fluid converter's Forge fluid port (batch 2026-11-12-fluid-conversion, W3): its Forge tank as one passive NeoForge tank.
 * It never pushes or pulls; pipes and buckets of other mods fill and drain it in whole mB (1 mB = 1 L). A fluid's data
 * components are ignored: the tank holds a fluid by its registry id. A call moves whole mB in the converter's facts and
 * wakes its island at the engine's next decision point; it never solves or applies an event ({@link Converters.Tank}).
 */
final class ConverterPort implements IFluidHandler {
    private final Converters.Tank tank;

    ConverterPort(Converters.Tank tank) {
        this.tank = tank;
    }

    private static String id(FluidStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }

    private static int clamp(long mB) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, mB));
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int index) {
        if (index != 0 || tank.stored() <= 0) return FluidStack.EMPTY;
        var id = ResourceLocation.tryParse(tank.fluid());
        var fluid = id == null ? null : BuiltInRegistries.FLUID.getOptional(id).orElse(null);
        // A fluid whose mod left keeps its id in the facts; Forge sees nothing it could take.
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        return new FluidStack(fluid, clamp(tank.stored()));
    }

    @Override
    public int getTankCapacity(int index) {
        return index == 0 ? clamp(tank.capacity()) : 0;
    }

    @Override
    public boolean isFluidValid(int index, FluidStack stack) {
        return index == 0 && !stack.isEmpty() && tank.valid(id(stack));
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        return clamp(tank.fill(id(resource), resource.getAmount(), action.simulate()));
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !id(resource).equals(tank.fluid())) return FluidStack.EMPTY;
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        var held = getFluidInTank(0);
        if (held.isEmpty() || maxDrain <= 0) return FluidStack.EMPTY;
        long given = tank.drain(maxDrain, action.simulate());
        return given > 0 ? held.copyWithAmount(clamp(given)) : FluidStack.EMPTY;
    }
}
