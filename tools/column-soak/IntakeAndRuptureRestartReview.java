package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Companion fixtures for the two exchanger review launches, using the same disposable game directory. */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class IntakeAndRuptureRestartReview {
    private static final BlockPos INTAKE=new BlockPos(928004,90,912008);
    private static final BlockPos TANK=INTAKE.north(3);
    private static final String PREPARE="exchanger-restart-prepare", VERIFY="exchanger-restart-verify";
    private IntakeAndRuptureRestartReview() {}

    @GameTest(template="empty",timeoutTicks=3500,batch="prepare-intake-and-rupture")
    public static void prepareColdIntakeAndPendingRupture(GameTestHelper helper) {
        if(!PREPARE.equals(System.getProperty(ReviewBatch.PROPERTY))){helper.succeed();return;}
        var level=helper.getLevel();
        var w=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var p=Positions.of(level,INTAKE);var target=Positions.of(level,TANK);
        helper.assertTrue(w.at(p).isEmpty(),"Prepare requires a fresh disposable world");
        UnloadedWaterIntakeGameTests.buildPool(level,INTAKE);
        level.setBlock(TANK,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),Block.UPDATE_ALL);
        long id=w.at(target).orElseThrow().device().id();
        long intake=w.at(p).orElseThrow().device().id();
        level.getChunkSource().save(true);
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(!level.hasChunkAt(INTAKE),"Waiting for saved pool and tank to unload"))
                .thenExecute(()->helper.assertTrue(w.removeRupturedDevice(target,id),"The registered tank refused rupture"))
                .thenWaitUntil(()->{
                    helper.assertTrue(w.committedDevice(id).isEmpty() && w.hasRuptureAt(target),"Waiting for committed removal");
                    helper.assertTrue(w.waterBody(intake,false),"Preparing the saved intake with a stale closed switch");
                }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=3500,batch="verify-intake-and-rupture")
    public static void recoverTerrainAndDestroyedCellAfterRestart(GameTestHelper helper) {
        if(!VERIFY.equals(System.getProperty(ReviewBatch.PROPERTY))){helper.succeed();return;}
        var level=helper.getLevel();
        var w=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var p=Positions.of(level,INTAKE);var target=Positions.of(level,TANK);
        var chunk=new ChunkPos(TANK);
        long[] replacement={0};
        helper.startSequence()
                .thenWaitUntil(()->{
                    helper.assertTrue(!level.hasChunkAt(INTAKE),"Cold restore loaded the strainer");
                    helper.assertTrue(w.at(p).orElseThrow().device().generatorEnabled(),"Cold recount did not find the saved pool");
                    helper.assertTrue(w.at(target).isEmpty() && w.hasRuptureAt(target),"Old tank identity or missing saved rupture");
                })
                .thenExecute(()->level.setChunkForced(chunk.x,chunk.z,true))
                .thenWaitUntil(()->helper.assertTrue(level.hasChunkAt(TANK) && level.getBlockState(TANK).isAir() && w.at(target).isEmpty(),
                        "Cold reconciliation did not remove the old tank"))
                .thenExecute(()->{
                    level.setBlock(TANK,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),Block.UPDATE_ALL);
                    replacement[0]=w.at(target).orElseThrow().device().id();
                    level.getChunkSource().save(true);
                    level.setChunkForced(chunk.x,chunk.z,false);
                })
                .thenWaitUntil(()->helper.assertTrue(!level.hasChunkAt(TANK),"Waiting for replacement to unload"))
                .thenExecute(()->level.setChunkForced(chunk.x,chunk.z,true))
                .thenWaitUntil(()->helper.assertTrue(level.hasChunkAt(TANK) && !level.getBlockState(TANK).isAir()
                        && w.at(target).orElseThrow().device().id()==replacement[0],"An old rupture removed the replacement"))
                .thenExecute(()->level.setChunkForced(chunk.x,chunk.z,false))
                .thenSucceed();
    }
}
