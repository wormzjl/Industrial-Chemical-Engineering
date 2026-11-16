package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * A gas intake block registers the atmosphere (batch 2026-11-15-fluid-interfaces, W2): a generator, switched on, whose spec is
 * ambient, with its entry loss; the registry stands it again as the intake block; it leaves with its block.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class GasIntakeGameTests {
    private GasIntakeGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 400, batch = "gas-intake")
    public static void anIntakeBlockRegistersTheAtmosphere(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var intake = helper.absolutePos(new BlockPos(1, 2, 1));
        var pipe = intake.below();
        var dimension = level.dimension().location().toString();
        var at = new PhysicalFluidTopology.Position(dimension, intake.getX(), intake.getY(), intake.getZ());
        var below = new PhysicalFluidTopology.Position(dimension, pipe.getX(), pipe.getY(), pipe.getZ());
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(intake, ModBlocks.GAS_INTAKE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.startSequence().thenWaitUntil(() -> {
            var r = world.at(at).orElseThrow();
            helper.assertTrue(r.device().kind() == TopologyCompiler.Kind.GENERATOR, "An intake is a generator");
            helper.assertTrue(r.spec().ambient(), "Its spec is the atmosphere");
            helper.assertTrue(r.device().generatorEnabled(), "It is placed on");
            helper.assertTrue(r.device().geometry().minorLoss() == 3, "Its entry loss is three velocity heads");
            helper.assertTrue(r.spec().temperature() == 298.15 && r.spec().pressure() == 101325, "At the ambient temperature and pressure");
            var stood = FluidDeviceBlock.stateFor(r);
            helper.assertTrue(stood != null && stood.getBlock() == ModBlocks.GAS_INTAKE.get(), "The registry stands it as the intake block");
            helper.assertTrue(world.at(below).isPresent(), "The pipe below is registered");
        }).thenExecute(() -> {
            for (var cell : List.of(intake, pipe)) level.removeBlock(cell, false);
        }).thenWaitUntil(() -> helper.assertTrue(world.at(at).isEmpty() && world.at(below).isEmpty(), "Waiting for the registrations to leave"))
                .thenSucceed();
    }
}
