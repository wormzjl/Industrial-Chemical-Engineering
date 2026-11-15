package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * A liquid hopper block (batch 2026-11-15-fluid-interfaces, W4): it registers a converter's fill port on its own with its
 * facts; a water bucket empties into its Forge tank, lava (no rule) is refused, nothing drains back out, no handler stands
 * at the face its pipe joins; the registry stands it as the hopper block; it and its facts leave with its block.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class LiquidHopperGameTests {
    private LiquidHopperGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 400, batch = "liquid-hopper")
    public static void aBucketEmptiesIntoAHopper(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var hopper = helper.absolutePos(new BlockPos(1, 2, 1));
        var pipe = hopper.below();
        var dimension = level.dimension().location().toString();
        var at = new PhysicalFluidTopology.Position(dimension, hopper.getX(), hopper.getY(), hopper.getZ());
        var below = new PhysicalFluidTopology.Position(dimension, pipe.getX(), pipe.getY(), pipe.getZ());
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hopper, ModBlocks.LIQUID_HOPPER.get().defaultBlockState(), Block.UPDATE_ALL);
        long[] id = {0};
        helper.startSequence().thenWaitUntil(() -> {
            var r = world.at(at).orElseThrow();
            id[0] = r.device().id();
            helper.assertTrue(r.device().converterFill(), "A hopper is a converter's fill port");
            var unit = world.converters().unit(id[0]);
            helper.assertTrue(unit != null && unit.hopper() && unit.capacity() == 4000, "Its facts: a hopper with a 4 000 mB tank: " + unit);
            var stood = FluidDeviceBlock.stateFor(r);
            helper.assertTrue(stood != null && stood.getBlock() == ModBlocks.LIQUID_HOPPER.get(), "The registry stands it as the hopper block");
            helper.assertTrue(world.at(below).isPresent(), "The pipe below is registered");
            helper.assertTrue(level.getBlockState(hopper).getValue(FluidDeviceBlock.connection(Direction.DOWN)), "The pipe below is drawn joined");
        }).thenExecute(() -> {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, hopper, Direction.UP);
            helper.assertTrue(handler != null, "Its funnel takes Forge fluid from above");
            helper.assertTrue(level.getCapability(Capabilities.FluidHandler.BLOCK, hopper, Direction.DOWN) == null, "No handler at its outlet's face");
            helper.assertTrue(handler.fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.SIMULATE) == 0, "No rule converts lava");
            var emptied = FluidUtil.tryEmptyContainer(new ItemStack(Items.WATER_BUCKET), handler, 1000, null, true);
            helper.assertTrue(emptied.isSuccess() && emptied.getResult().is(Items.BUCKET), "A water bucket empties into it");
            helper.assertTrue(world.converters().unit(id[0]).stored() == 1000, "1 000 mB of water in its tank: " + world.converters().unit(id[0]));
            helper.assertTrue(handler.drain(1000, IFluidHandler.FluidAction.SIMULATE).isEmpty(), "Nothing drains back out");
        }).thenExecute(() -> {
            for (var cell : List.of(hopper, pipe)) level.removeBlock(cell, false);
        }).thenWaitUntil(() -> helper.assertTrue(world.at(at).isEmpty() && world.at(below).isEmpty() && world.converters().unit(id[0]) == null,
                "Waiting for the registrations and the hopper's facts to leave")).thenSucceed();
    }
}
