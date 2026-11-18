package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.pollution.GroundSoil;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.GroundField;
import com.wormzjl.icheme.science.pollution.GroundLedger;
import com.wormzjl.icheme.science.pollution.MapColumns;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The ground store in a server (batch 2026-11-18-ground-pollution): a deposit lands in its chunk column at the next pass and
 * shows in the published snapshot, the ground ledger closes, and a crop on ground at index 1 may not grow nor take
 * bonemeal (G4) while the same crop grows as usual once the ground is cleared. Every test of the batch clears the dimension's pollution at its start and end.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class GroundPollutionGameTests {
    private GroundPollutionGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "ground-pollution")
    public static void aCropOnPollutedGroundDoesNotGrow(GameTestHelper helper) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        helper.assertTrue(service.enabled(), "Pollution runs in the GameTest server");
        service.clear(dimension);
        var soil = helper.absolutePos(new BlockPos(1, 1, 1));
        var crop = soil.above();
        level.setBlock(soil, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_ALL);
        level.setBlock(crop, Blocks.WHEAT.defaultBlockState(), Block.UPDATE_ALL);
        long column = MapColumns.column(crop.getX() >> 4, crop.getZ() >> 4);
        var amounts = new double[Channel.values().length];
        amounts[Channel.TOXIC.ordinal()] = GroundField.GROUND_REFERENCE;
        service.deposit(dimension, crop, amounts, List.of(new GroundField.Compound("crude_pc08", Channel.TOXIC, 1100e-6 * GroundField.GROUND_REFERENCE)));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(service.groundSnapshot(dimension).index(column, Channel.TOXIC) > .9,
                        "Waiting for the pass to land the deposit: " + service.groundSnapshot(dimension).index(column, Channel.TOXIC)))
                .thenExecute(() -> {
                    var ground = service.ground(dimension);
                    var ledger = service.groundLedger(dimension);
                    for (var channel : Channel.values()) {
                        double closure = ledger.closure(channel, ground);
                        helper.assertTrue(Math.abs(closure) <= 1e-9 * Math.max(1, ledger.get(channel, GroundLedger.Total.DEPOSITED)), "The ground ledger closes: " + channel + " " + closure);
                    }
                    helper.assertTrue(ground.moles(column, "crude_pc08") > 0, "The column keeps the compound");
                    // The event a crop posts at each growth chance (light and moisture aside): the ground says no.
                    var pre = NeoForge.EVENT_BUS.post(new CropGrowEvent.Pre(level, crop, level.getBlockState(crop)));
                    helper.assertTrue(pre.getResult() == CropGrowEvent.Pre.Result.DO_NOT_GROW, "Polluted ground: the wheat may not grow: " + pre.getResult());
                    var meal = NeoForge.EVENT_BUS.post(new BonemealEvent(null, level, crop, level.getBlockState(crop), ItemStack.EMPTY));
                    helper.assertTrue(meal.isCanceled(), "Polluted ground: bonemeal does nothing");
                    service.clear(dimension);
                })
                .thenWaitUntil(() -> helper.assertTrue(service.groundSnapshot(dimension).size() == 0, "Waiting for the cleared ground"))
                .thenExecute(() -> {
                    var pre = NeoForge.EVENT_BUS.post(new CropGrowEvent.Pre(level, crop, level.getBlockState(crop)));
                    helper.assertTrue(pre.getResult() == CropGrowEvent.Pre.Result.DEFAULT, "Clean ground: the wheat grows as usual: " + pre.getResult());
                    level.setBlock(crop, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(soil, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    service.clear(dimension);
                })
                .thenSucceed();
    }
    /**
     * G4b: at index 1 a pick removes a flower and turns its grass into polluted soil, takes a wheat crop and turns its farmland
     * into polluted soil, and leaves a log and stone alone; once the ground is clean, the soil's random tick makes dirt.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "ground-pollution")
    public static void pollutedGroundSpoilsTheSurfaceAndRecovers(GameTestHelper helper) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        service.clear(dimension);
        var grass = helper.absolutePos(new BlockPos(1, 1, 1));
        var farm = helper.absolutePos(new BlockPos(1, 1, 3));
        var log = helper.absolutePos(new BlockPos(3, 1, 1));
        var stone = helper.absolutePos(new BlockPos(3, 1, 3));
        var placed = List.of(grass, grass.above(), farm, farm.above(), log, stone);
        level.setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(grass.above(), Blocks.POPPY.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(farm, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_ALL);
        level.setBlock(farm.above(), Blocks.WHEAT.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var amounts = new double[Channel.values().length];
        amounts[Channel.TOXIC.ordinal()] = GroundField.GROUND_REFERENCE;
        // The four columns of blocks may fall in up to four chunk columns: each gets the deposit once.
        var columns = new java.util.TreeSet<Long>();
        for (var pos : List.of(grass, farm, log, stone)) if (columns.add(MapColumns.column(pos.getX() >> 4, pos.getZ() >> 4))) service.deposit(dimension, pos, amounts, List.of());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(columns.stream().allMatch(c -> service.groundSnapshot(dimension).index(c, Channel.TOXIC) > .9),
                        "Waiting for the pass to land the deposit"))
                .thenExecute(() -> {
                    for (var pos : List.of(grass, farm, log, stone)) {
                        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
                        helper.assertTrue(top == pos.getY() + 1, "The placed block is the motion-blocking top at " + pos + ": " + top);
                        GroundSoil.pick(level, pos.getX(), pos.getZ());
                    }
                    helper.assertTrue(level.getBlockState(grass).is(ModBlocks.POLLUTED_SOIL.get()), "Grass becomes polluted soil: " + level.getBlockState(grass));
                    helper.assertTrue(level.getBlockState(grass.above()).isAir(), "The flower is removed: " + level.getBlockState(grass.above()));
                    helper.assertTrue(level.getBlockState(farm).is(ModBlocks.POLLUTED_SOIL.get()), "Farmland becomes polluted soil: " + level.getBlockState(farm));
                    helper.assertTrue(level.getBlockState(farm.above()).isAir(), "The crop is removed: " + level.getBlockState(farm.above()));
                    helper.assertTrue(level.getBlockState(log).is(Blocks.OAK_LOG), "A log is left alone");
                    helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "Stone is left alone");
                    var soil = level.getBlockState(grass);
                    soil.randomTick(level, grass, level.getRandom());
                    helper.assertTrue(level.getBlockState(grass).is(ModBlocks.POLLUTED_SOIL.get()), "Polluted ground: the soil stays");
                    service.clear(dimension);
                })
                .thenWaitUntil(() -> helper.assertTrue(service.groundSnapshot(dimension).size() == 0, "Waiting for the cleared ground"))
                .thenExecute(() -> {
                    level.getBlockState(grass).randomTick(level, grass, level.getRandom());
                    helper.assertTrue(level.getBlockState(grass).is(Blocks.DIRT), "Clean ground: the soil turns back to dirt: " + level.getBlockState(grass));
                    for (var pos : placed) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    service.clear(dimension);
                })
                .thenSucceed();
    }
}
