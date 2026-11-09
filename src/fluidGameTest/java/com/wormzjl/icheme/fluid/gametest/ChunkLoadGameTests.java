package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * What a chunk load or unload may do (batch 2026-10-26-chunk-loading-review): a loading pipe reads no neighbour in a chunk
 * that is not loaded (D1), and a calculator that unloads or breaks while it calculates cancels its solve (D3).
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ChunkLoadGameTests {
    private ChunkLoadGameTests() {}

    /**
     * D1: on a server, reading a block in a chunk that is not loaded loads that chunk on the main thread, and a pipe on the
     * loaded area's edge read all six neighbours as it loaded, so each load loaded the next chunk along the run. The drawn
     * joints now come from the registry alone (batch 2026-11-09-drawn-connections), which knows a neighbour by its
     * registration, so no block is read. Here the neighbour is registered with no block, as one in a chunk that is not
     * loaded shows to the level: its joint is drawn, and a face with nothing registered beyond it draws none.
     */
    @GameTest(template="empty",batch="chunk-load-pipe-joint")
    public static void aLoadingPipeReadsNoNeighbourInAChunkThatIsNotLoaded(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var position=helper.absolutePos(new BlockPos(1,1,1));var east=position.east();
        var registered=new ArrayList<Long>();
        for(var at:List.of(position,east))registered.add(world.place(new PhysicalFluidTopology.Position(level.dimension().location().toString(),at.getX(),at.getY(),at.getZ()),
                TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.Direction.EAST).device().id());
        try {
            helper.assertTrue(level.getBlockState(east).isAir(),"The neighbour stands without a block");
            var drawn=FluidDeviceBlock.connectedState(ModBlocks.FLUID_PIPE.get().defaultBlockState(),level,position);
            helper.assertTrue(drawn.getValue(FluidDeviceBlock.connection(Direction.EAST)),"The registered neighbour was not drawn joined");
            for(var direction:new Direction[]{Direction.UP,Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST})
                helper.assertTrue(!drawn.getValue(FluidDeviceBlock.connection(direction)),"Joined to nothing towards "+direction);
        } finally {
            registered.forEach(world::remove);
        }
        helper.succeed();
    }
}
