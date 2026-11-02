package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/**
 * What a chunk load or unload may do (batch 2026-10-26-chunk-loading-review): a loading pipe reads no neighbour in a chunk
 * that is not loaded (D1), and a calculator that unloads or breaks while it calculates cancels its solve (D3).
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ChunkLoadGameTests {
    private ChunkLoadGameTests() {}

    /**
     * D1: on a server, reading a block in a chunk that is not loaded loads that chunk on the main thread. A pipe on the
     * loaded area's edge read all six neighbours as it loaded, so each load loaded the next chunk along the run. The
     * joint towards such a neighbour keeps its saved state; loaded neighbours are read as before.
     */
    @GameTest(template="empty",batch="chunk-load-pipe-joint")
    public static void aLoadingPipeReadsNoNeighbourInAChunkThatIsNotLoaded(GameTestHelper helper) {
        var pipe=ModBlocks.FLUID_PIPE.get().defaultBlockState();
        // A pipe on the east edge of chunk 0, saved joined to a pipe beyond it in chunk 1, which is not loaded.
        var position=new BlockPos(15,64,8);var west=position.west();
        var unloadedReads=new ArrayList<BlockPos>();
        var level=stubLevel(at->(at.getX()>>4)!=1,at->{
            if((at.getX()>>4)==1){unloadedReads.add(at);return pipe;}
            return at.equals(west)?pipe:Blocks.AIR.defaultBlockState();
        });
        var saved=pipe.setValue(FluidDeviceBlock.connection(Direction.EAST),true);
        var result=FluidDeviceBlock.connectedState(saved,level,position);
        helper.assertTrue(unloadedReads.isEmpty(),"The pipe read "+unloadedReads+" in a chunk that is not loaded");
        helper.assertTrue(result.getValue(FluidDeviceBlock.connection(Direction.EAST)),"The saved joint towards the unloaded chunk was dropped");
        helper.assertTrue(result.getValue(FluidDeviceBlock.connection(Direction.WEST)),"The loaded pipe to the west was not joined");
        for(var direction:new Direction[]{Direction.UP,Direction.DOWN,Direction.NORTH,Direction.SOUTH})
            helper.assertTrue(!result.getValue(FluidDeviceBlock.connection(direction)),"Joined to air towards "+direction);
        // A joint saved towards a loaded neighbour that is gone is dropped, as before.
        var stale=FluidDeviceBlock.connectedState(pipe.setValue(FluidDeviceBlock.connection(Direction.UP),true),level,position);
        helper.assertTrue(!stale.getValue(FluidDeviceBlock.connection(Direction.UP)),"A joint to loaded air was kept");
        helper.succeed();
    }

    /**
     * A level that answers only what a pipe's joint rule asks: which chunks are loaded, and the block at a position. Its
     * default methods run as the interface defines them, so {@code hasChunkAt} asks {@code hasChunk}.
     */
    private static LevelReader stubLevel(java.util.function.Predicate<BlockPos> loaded,java.util.function.Function<BlockPos,BlockState> blocks) {
        InvocationHandler handler=(proxy,method,args)->switch(method.getName()) {
            case "hasChunk"->loaded.test(new BlockPos(((Integer)args[0])<<4,0,((Integer)args[1])<<4));
            case "getBlockState"->blocks.apply((BlockPos)args[0]);
            case "getBlockEntity"->null;
            case "toString"->"stub level";
            case "hashCode"->System.identityHashCode(proxy);
            case "equals"->proxy==args[0];
            default->{
                if(method.isDefault())yield InvocationHandler.invokeDefault(proxy,method,args);
                throw new UnsupportedOperationException("stub level: "+method.getName());
            }
        };
        return (LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),new Class<?>[]{LevelReader.class},handler);
    }
}
