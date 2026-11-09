package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * What a block draws joined is what the network joins (batch 2026-11-09-drawn-connections): the drawing reads the
 * registry through the network's own as-built rule, {@code PhysicalFluidTopology.builtFaces}.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class DrawnJointGameTests {
    private DrawnJointGameTests() {}

    /**
     * An In-line Pump installed as {@code PipelineFittingItem} installs it registers an in-line machine (since 2260953dd),
     * which the redraw read as no mover: the pump vanished from the block, and a pipe placed beside it drew a branch the
     * network does not have. The block now draws the engine's classification, and the branch draws no joint.
     */
    @GameTest(template="empty",batch="drawn-joints")
    public static void anInLinePumpInstalledAsTheItemDoesDrawsAsAPumpAndNoBranch(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west=helper.absolutePos(new BlockPos(1,1,1));var middle=west.east();var east=middle.east();var side=middle.south();
        var pipe=ModBlocks.FLUID_PIPE.get().defaultBlockState();
        for(var at:List.of(west,middle,east))level.setBlock(at,pipe,Block.UPDATE_ALL);
        try {
            var entity=(FluidDeviceBlockEntity)level.getBlockEntity(middle);
            // The item's steps: the state with its pump on the straight pipe, the registration's edit, then the redraw.
            var state=level.getBlockState(middle).setValue(FluidDeviceBlock.FACING,Direction.EAST).setValue(FluidDeviceBlock.INLINE_MOVER,FluidDeviceBlock.InlineMover.PUMP);
            level.setBlock(middle,FluidDeviceBlock.connectedState(state,level,middle),Block.UPDATE_ALL);
            helper.assertTrue(world.installPipeMover(entity.fluidIdentity(),PhysicalFluidTopology.Direction.EAST,TopologyCompiler.Kind.PUMP),"The straight pipe took no pump");
            entity.refreshGeometry(entity.bore(),true);
            helper.assertTrue(world.registrations().get(entity.fluidIdentity()).device().control() instanceof FlowControl.Machine,"The item installs an in-line machine");
            var drawn=level.getBlockState(middle);
            helper.assertTrue(FluidDeviceBlock.hasPump(drawn),"The installed pump is not drawn");
            helper.assertTrue(drawn.getValue(FluidDeviceBlock.connection(Direction.WEST))&&drawn.getValue(FluidDeviceBlock.connection(Direction.EAST)),"The pump is not drawn joined along its axis");
            // A pipe placed beside the pump: the network joins it nowhere, so neither block draws the joint.
            level.setBlock(side,pipe,Block.UPDATE_ALL);
            helper.assertTrue(!level.getBlockState(middle).getValue(FluidDeviceBlock.connection(Direction.SOUTH)),"The pump draws a branch the network does not have");
            helper.assertTrue(!FluidDeviceBlock.connectedState(level.getBlockState(side),level,side).getValue(FluidDeviceBlock.connection(Direction.NORTH)),"The pipe beside the pump draws a joint into it");
        } finally {
            for(var at:List.of(side,east,middle,west))level.removeBlock(at,false);
        }
        helper.succeed();
    }
}
