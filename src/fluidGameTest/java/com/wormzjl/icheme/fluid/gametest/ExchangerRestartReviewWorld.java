package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.SavedEquipment;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.ExchangerRelay;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Two launches of the same disposable review world. Prepare saves the plant and Verify opens it without its chunks. */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ExchangerRestartReviewWorld {
    private static final String PREPARE = "exchanger-restart-prepare", VERIFY = "exchanger-restart-verify";
    private static final BlockPos BASE = new BlockPos(1084008, 81, 1048008);
    private static final BlockPos ORIGIN = BASE.above(4);

    private ExchangerRestartReviewWorld() {}

    @GameTest(template = "empty", timeoutTicks = 3000, batch = PREPARE)
    public static void prepareAnUnloadedExchangerWithAValidatedValve(GameTestHelper helper) {
        if (!selected(helper, PREPARE)) return;
        var level = helper.getLevel();
        helper.assertTrue(AssemblyService.entry(level, ORIGIN) == null, "Use a fresh disposable review world for Prepare");
        var plant = new PlantBuilder(level, BASE, new BlockPos(-10, -2, -10), new BlockPos(24, 16, 16));
        ExchangerChunkGameTests.buildBypass(plant, false);
        long shell = plant.side(HeatSides.Side.SHELL);
        long valve = plant.world.at(plant.position(plant.bypassValve)).orElseThrow().device().id();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(plant.valvesInstalled(), "Waiting for the bypass valve"))
                .thenExecute(() -> ExchangerChunkGameTests.setBypass(helper, plant, shell, valve))
                .thenExecute(() -> {
                    ExchangerChunkGameTests.checkBypass(helper, plant, shell, valve);
                    plant.release();
                })
                .thenWaitUntil(() -> helper.assertTrue(ShapeRasterizer.fluidCells(plant.column.shape()).stream().noneMatch(c ->
                        FixtureChunks.full(level, FixtureChunks.at(ORIGIN, c))), "Waiting for the exchanger to unload"))
                .thenExecute(() -> level.save(null, true, false))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = VERIFY)
    public static void aColdExchangerKeepsItsValveWithoutLoadingEitherSide(GameTestHelper helper) {
        if (!selected(helper, VERIFY)) return;
        requireRestored(helper.getLevel());
        helper.succeed();
    }

    /** The authority is live when this listener runs, before Minecraft advances the engine's first tick. */
    static void beforeFirstDecision(ServerStartingEvent event) {
        if (VERIFY.equals(System.getProperty(ReviewBatch.PROPERTY))) requireRestored(event.getServer().overworld());
    }

    private static void requireRestored(ServerLevel level) {
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var stored = AssemblyService.entry(level, ORIGIN);
        if (stored == null || stored.status() != AssemblyStore.Status.COMPLETE)
            throw new IllegalStateException("The cold fixture must open the complete report saved by Prepare");
        var shape = SavedEquipment.at(world, Positions.of(level, ORIGIN));
        if (shape == null) throw new IllegalStateException("The cold fixture lost its exchanger shape");
        var nodes = ShapeRasterizer.fluidCells(shape).stream().map(c -> FixtureChunks.at(ORIGIN, c)).toList();
        long shell = world.at(Positions.of(level, nodes.get(1))).orElseThrow().device().id();
        var slot = stored.report().slot(ExchangerBlueprint.bypassValve(HeatSides.Side.SHELL));
        var at = BlockPos.of(slot.member());
        long valve = world.at(Positions.of(level, at)).orElseThrow().device().id();
        if (nodes.stream().anyMatch(n -> FixtureChunks.full(level, n)) || FixtureChunks.full(level, at))
            throw new IllegalStateException("The cold exchanger or valve chunk was loaded");
        if (!ExchangerRelay.Operation.bypass(valve).equals(world.exchangerOperation(shell)))
            throw new IllegalStateException("The saved report did not assign the bypass valve before the first engine decision");
    }

    private static boolean selected(GameTestHelper helper, String batch) {
        if (!ReviewBatch.skipped(helper, batch)) {
            if (batch.equals(System.getProperty(ReviewBatch.PROPERTY))) return true;
            helper.succeed();
        }
        return false;
    }
}
