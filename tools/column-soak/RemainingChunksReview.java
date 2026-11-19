package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Run the chunk-dependency regression fixtures on an isolated review server. */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class RemainingChunksReview {
    private RemainingChunksReview() {}

    private static boolean selected(GameTestHelper helper,String name) {
        var batch=System.getProperty(ReviewBatch.PROPERTY,"");
        if(batch.equals("chunk-dependencies")||java.util.Arrays.asList(batch.split(",")).contains(name)) return true;
        helper.succeed();
        return false;
    }

    @GameTest(template="empty",timeoutTicks=10000,batch="chunk-water")
    public static void anUnloadedIntakeTracksBothEndsOfItsPool(GameTestHelper helper) {
        if(selected(helper,"chunk-water")) UnloadedWaterIntakeGameTests.anUnloadedIntakeTracksBothEndsOfItsPool(helper);
    }

    @GameTest(template="empty",timeoutTicks=3000,batch="chunk-exchanger")
    public static void aRemotePipeEditReleasesTheUnloadedExchangersValve(GameTestHelper helper) {
        if(selected(helper,"chunk-exchanger")) ExchangerChunkGameTests.aRemotePipeEditReleasesTheUnloadedExchangersValve(helper);
    }

    @GameTest(template="empty",timeoutTicks=3000,batch="chunk-binding")
    public static void bindingJustTheShellSideDoesNotLoadThePrimarySocket(GameTestHelper helper) {
        if(selected(helper,"chunk-binding")) SecondaryNodeBindingGameTests.bindingJustTheShellSideDoesNotLoadThePrimarySocket(helper);
    }

    @GameTest(template="empty",timeoutTicks=15000,batch="chunk-dcs")
    public static void aRestoredDcsRunsAnUnloadedColumnAndItsMembers(GameTestHelper helper) {
        if(selected(helper,"chunk-dcs")) ColumnOperationGameTests.aRestoredDcsRunsAnUnloadedColumnAndItsMembers(helper);
    }

    @GameTest(template="empty",timeoutTicks=4000,batch="chunk-blast-loaded")
    public static void aLoadedFootprintRupturesBothPipesAndBooksTankStockOnce(GameTestHelper helper) {
        if(selected(helper,"chunk-blast-loaded")) UnloadedPollutionGameTests.aLoadedFootprintRupturesBothPipesAndBooksTankStockOnce(helper);
    }

    @GameTest(template="empty",timeoutTicks=4000,batch="chunk-blast-mixed")
    public static void aMixedFootprintRupturesBothPipesAndBooksTankStockOnce(GameTestHelper helper) {
        if(selected(helper,"chunk-blast-mixed")) UnloadedPollutionGameTests.aMixedFootprintRupturesBothPipesAndBooksTankStockOnce(helper);
    }
}
