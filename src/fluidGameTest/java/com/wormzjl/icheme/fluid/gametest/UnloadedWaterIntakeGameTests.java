package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.WaterIntakes;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class UnloadedWaterIntakeGameTests {
    private UnloadedWaterIntakeGameTests() {}
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** A sealed, 100-block water body. A change at its far end cannot notify the intake directly. */
    public static void buildPool(ServerLevel level, BlockPos intake) {
        for (int i=1; i<=100; i++) {
            var water=intake.east(i);
            for (var wall : new BlockPos[]{water.north(),water.south(),water.above(),water.below()})
                level.setBlock(wall,Blocks.GLASS.defaultBlockState(),FLAGS);
        }
        level.setBlock(intake.east(101),Blocks.GLASS.defaultBlockState(),FLAGS);
        for (int i=1; i<=100; i++) level.setBlock(intake.east(i),Blocks.WATER.defaultBlockState(),FLAGS);
        level.setBlock(intake,ModBlocks.WATER_INTAKE.get().defaultBlockState(),FLAGS);
    }

    @GameTest(template="empty",timeoutTicks=10000,batch="water-intake-unloaded")
    public static void anUnloadedIntakeTracksBothEndsOfItsPool(GameTestHelper helper) {
        var level=helper.getLevel();
        var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var near=new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var origin=new ChunkPos(near.x+96,near.z);
        var intake=new BlockPos(origin.getMinBlockX()+4,90,origin.getMinBlockZ()+8);
        var far=intake.east(100);
        var end=new ChunkPos(far);
        for (int cx=origin.x; cx<=end.x; cx++) level.setChunkForced(cx,origin.z,true);
        buildPool(level,intake);
        var position=Positions.of(level,intake);
        long id=world.at(position).orElseThrow().device().id();
        level.getChunkSource().save(true);
        for (int cx=origin.x+1; cx<=end.x; cx++) level.setChunkForced(cx,origin.z,false);
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(!level.hasChunkAt(far),"Waiting for the end of the pool to unload"))
                .thenExecute(()->{
                    helper.assertTrue(WaterIntakes.supplying(level,intake),"Saved water must complete the 100-block pool");
                    helper.assertTrue(!level.hasChunkAt(far),"Water sampling ticketed the far bank");
                    level.setChunkForced(end.x,end.z,true);
                    level.setChunkForced(origin.x,origin.z,false);
                })
                .thenWaitUntil(()->helper.assertTrue(!level.hasChunkAt(intake) && level.hasChunkAt(far),"Waiting for the intake to unload"))
                .thenExecute(()->level.setBlock(far,Blocks.STONE.defaultBlockState(),FLAGS))
                .thenWaitUntil(()->{
                    helper.assertTrue(!level.hasChunkAt(intake),"Recount loaded the intake");
                    helper.assertTrue(!world.registrations().get(id).device().generatorEnabled(),"99 water blocks must close the sleeping intake");
                })
                .thenExecute(()->level.setBlock(far,Blocks.WATER.defaultBlockState(),FLAGS))
                .thenWaitUntil(()->{
                    helper.assertTrue(!level.hasChunkAt(intake),"Reopening loaded the intake");
                    helper.assertTrue(world.registrations().get(id).device().generatorEnabled(),"100 water blocks must reopen the sleeping intake");
                })
                .thenExecute(()->{
                    level.setChunkForced(origin.x,origin.z,true);
                    level.getChunk(intake);
                    helper.assertTrue(world.at(position).orElseThrow().device().id()==id,"The intake identity changed on reload");
                    level.removeBlock(intake,false);
                    for (int cx=origin.x; cx<=end.x; cx++) level.setChunkForced(cx,origin.z,false);
                }).thenSucceed();
    }
}
