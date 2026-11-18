package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.pollution.Ignition;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.List;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class UnloadedPollutionGameTests {
    private UnloadedPollutionGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "pollution_unloaded")
    public static void savedTerrainAndTheRegisteredShellFeedAnUnloadedBlast(GameTestHelper helper) {
        blast(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "pollution_unloaded_legacy")
    public static void anUnloadedBlastFindsTheOldShellWhenTheBuildFactIsAbsent(GameTestHelper helper) {
        blast(helper, true);
    }

    private static void blast(GameTestHelper helper, boolean legacy) {
        var level = helper.getLevel();
        var server = level.getServer();
        var dimension = level.dimension();
        var service = PollutionService.find(server).orElseThrow();
        var world = FluidWorldAuthority.find(server).orElseThrow();
        var near = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var remote = new ChunkPos(near.x + 64, near.z);
        var origin = new BlockPos(remote.getMinBlockX() + 8, 81, remote.getMinBlockZ() + 8);
        var shape = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var device = ShapeRasterizer.fluidCell(shape);
        var node = origin.offset(device.x(), device.y(), device.z());
        var source = origin.west(2).above().south();
        long cube = PollutionService.cube(source);
        service.clear(dimension);
        level.setChunkForced(remote.x, remote.z, true);
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        long id = world.at(Positions.of(level, node)).orElseThrow().device().id();
        level.getChunkSource().save(true);
        level.setChunkForced(remote.x, remote.z, false);
        double[] burntBefore = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(!level.hasChunkAt(origin), "Waiting for the test chunk to unload"))
                .thenExecute(() -> {
                    if (legacy) world.structure(Positions.of(level, origin), null);
                    service.add(dimension, cube, Channel.COMBUSTIBLE, 16);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(!level.hasChunkAt(origin), "Pollution loaded the test chunk");
                    helper.assertTrue(service.measuredIndex(dimension, source, Channel.COMBUSTIBLE) >= 1,
                            "Waiting for the saved terrain and flammable release");
                })
                .thenExecute(() -> {
                    helper.assertTrue(service.cell(dimension, cube).airShare() < 1, "The saved palette must include the drum's cells");
                    burntBefore[0] = service.ledger(dimension).get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT);
                    var centre = source.getCenter();
                    var ignition = new Explosion(level, null, centre.x, centre.y, centre.z, 0, false, Explosion.BlockInteraction.KEEP);
                    Ignition.detonated(new ExplosionEvent.Detonate(level, ignition, List.of()));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(!level.hasChunkAt(origin), "The blast loaded the test chunk");
                    helper.assertTrue(service.ledger(dimension).get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT) > burntBefore[0],
                            "The blast did not book the burned combustible");
                    helper.assertTrue(world.integrity().wrecked(id) && world.isBreached(id), "The saved shell's vessel was not breached");
                })
                .thenExecute(() -> {
                    service.clear(dimension);
                    level.getChunk(origin);
                    PlantBuilder.removeStructure(level, node);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(Positions.of(level, node)).isEmpty(), "Waiting for the fixture's removal"))
                .thenSucceed();
    }
}
