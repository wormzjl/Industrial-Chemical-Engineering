package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.pollution.Blasts;
import com.wormzjl.icheme.runtime.pollution.Ignition;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

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


    @GameTest(template = "empty", timeoutTicks = 3000, batch = "pollution_rupture_loaded")
    public static void aLoadedFootprintRupturesBothPipesAndBooksTankStockOnce(GameTestHelper helper) {
        rupture(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "pollution_rupture_mixed")
    public static void aMixedFootprintRupturesBothPipesAndBooksTankStockOnce(GameTestHelper helper) {
        rupture(helper, true);
    }

    private static void rupture(GameTestHelper helper, boolean mixed) {
        var level = helper.getLevel();
        var chunks = level.getChunkSource();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        var dimension = level.dimension();
        var template = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var near = new ChunkPos(template.x + 64, template.z);
        var far = new ChunkPos(near.x + 1, near.z);
        var source = new BlockPos(near.getMaxBlockX(), 199, near.getMinBlockZ() + 8);
        var closePipe = source.south(2);
        var remotePipe = closePipe.east();
        var lineSource = closePipe.west();
        var lineSink = remotePipe.east();
        var tank = source.east(2);
        var protectedValve = source.north(2);
        var shieldedPipe = source.east(3).south(5);
        var hits = List.of(closePipe, remotePipe, tank);
        var survivors = List.of(protectedValve, shieldedPipe);
        var shield = BlockPos.betweenClosedStream(shieldedPipe.offset(-1, -1, -1), shieldedPipe.offset(1, 1, 1))
                .filter(at -> !at.equals(shieldedPipe)).map(BlockPos::immutable).toList();

        level.setChunkForced(near.x, near.z, true);
        level.setChunkForced(far.x, far.z, true);
        for (var at : shield) level.setBlock(at, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        for (var at : List.of(closePipe, remotePipe, shieldedPipe)) {
            level.setBlock(at, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(protectedValve, ModBlocks.FLUID_PIPE.get().defaultBlockState()
                .setValue(FluidDeviceBlock.SHUTOFF, FluidDeviceBlock.Shutoff.OPEN), Block.UPDATE_ALL);
        level.setBlock(tank, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lineSource, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lineSink, ModBlocks.FLUID_VOID.get().defaultBlockState(), Block.UPDATE_ALL);

        var catalog = MaterialRuntime.active();
        String material = FluidMaterialCatalog.networkPackage(catalog);
        int methane = world.model().components().indexOf("Methane");
        int hazard = catalog.conservedComponents(material).indexOf("Methane");
        double lfl = catalog.lowerFlammabilityLimits(material)[hazard];
        double[] feed = new double[world.model().componentCount()];
        feed[methane] = 1;
        var methaneFeed = new FluidDeviceSpec(1, 298.15, 2e5, feed);
        var charge = new TankCharge(helper, world, tank, Direction.EAST, methaneFeed);
        TankCharge.run(world, world.at(Positions.of(level, lineSource)).orElseThrow().device().id(), methaneFeed);
        var hitIds = hits.stream().map(at -> world.at(Positions.of(level, at)).orElseThrow().device().id()).toList();
        long tankId = hitIds.getLast();
        var lineIds = hitIds.subList(0, 2);
        double[] stock = {0};
        double[] spill = {0};
        double[] removedBefore = {0};
        double[] releasedBefore = {0};
        var centre = source.getCenter();
        float power = 6;

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(charge.charged(), "Waiting for a sealed methane charge");
                    for (long pipe : lineIds) {
                        helper.assertTrue(methaneInPipe(world, pipe, methane) > 0, "Waiting for methane in pipe " + pipe);
                    }
                })
                .thenExecute(() -> {
                    service.clear(dimension);
                    for (var phase : world.view(tankId).state().phaseMoles()) stock[0] += phase[methane];
                    removedBefore[0] = world.capture().world().destroyed().moles()[methane];
                    releasedBefore[0] = service.ledger(dimension).get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED);
                    helper.assertTrue(stock[0] > 0, "The test tank has no stock");
                    if (mixed) {
                        // Distance zero leaves just the near FULL chunk. The blast must never request the east one.
                        chunks.addRegionTicket(TicketType.FORCED, near, 0, near);
                        chunks.save(true);
                        level.setChunkForced(near.x, near.z, false);
                        level.setChunkForced(far.x, far.z, false);
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(chunks.getChunkNow(near.x, near.z) != null, "The loaded pipe's chunk is missing");
                    helper.assertTrue((chunks.getChunkNow(far.x, far.z) == null) == mixed, "Waiting for the blast's chunk footprint");
                })
                .thenExecute(() -> {
                    Blasts.applyDamage(level, centre, power, false);
                    Blasts.keepBlocks(true);
                    try {
                        Blasts.applyDamage(level, centre, power, true);
                    } finally {
                        Blasts.keepBlocks(false);
                    }
                    Consumer<ExplosionEvent.Start> cancel = event -> {
                        if (event.getLevel() == level && event.getExplosion().center().equals(centre)) event.setCanceled(true);
                    };
                    NeoForge.EVENT_BUS.addListener(cancel);
                    try {
                        Blasts.applyDamage(level, centre, power, true);
                    } finally {
                        NeoForge.EVENT_BUS.unregister(cancel);
                    }
                    for (var at : hits) {
                        helper.assertTrue(world.at(Positions.of(level, at)).isPresent(), "Disabled or canceled damage removed " + at);
                    }
                    spill[0] = stock[0];
                    for (long pipe : lineIds) {
                        double holdup = methaneInPipe(world, pipe, methane);
                        helper.assertTrue(holdup > 0, "The exposed line lost its methane before the blast");
                        spill[0] += holdup;
                    }
                    Consumer<ExplosionEvent.Detonate> protect = event -> {
                        if (event.getLevel() != level || !event.getExplosion().center().equals(centre)) return;
                        helper.assertTrue(event.getAffectedBlocks().remove(protectedValve), "The exposed valve was not a blast target");
                        helper.assertTrue(!event.getAffectedBlocks().contains(shieldedPipe), "The blast ignored the obsidian shield");
                    };
                    NeoForge.EVENT_BUS.addListener(protect);
                    try {
                        Blasts.applyDamage(level, centre, power, true);
                    } finally {
                        NeoForge.EVENT_BUS.unregister(protect);
                    }
                    helper.assertTrue(level.getBlockState(closePipe).isAir(), "The blast did not remove the loaded pipe");
                })
                .thenWaitUntil(() -> {
                    if (mixed) helper.assertTrue(chunks.getChunkNow(far.x, far.z) == null, "The blast loaded the absent chunk");
                    for (var at : hits) {
                        helper.assertTrue(world.at(Positions.of(level, at)).isEmpty(), "Waiting for rupture of " + at);
                    }
                    for (var at : survivors) {
                        helper.assertTrue(world.at(Positions.of(level, at)).isPresent(), "The blast removed protected device " + at);
                    }
                    double removed = world.capture().world().destroyed().moles()[methane] - removedBefore[0];
                    helper.assertTrue(Math.abs(removed - stock[0]) < 1e-8, "Removed methane " + removed + " mol, expected " + stock[0]);
                    double released = service.ledger(dimension).get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED) - releasedBefore[0];
                    helper.assertTrue(Math.abs(released - spill[0] / lfl) < 1e-8, "Released methane index-moles " + released + ", expected " + spill[0] / lfl);
                })
                .thenExecute(() -> {
                    if (!mixed) return;
                    var pending = world.capture().world().builds().ruptures();
                    for (int i = 1; i < hits.size(); i++) {
                        var position = Positions.of(level, hits.get(i));
                        helper.assertTrue(pending.getOrDefault(position, java.util.Set.of()).contains(hitIds.get(i)), "Missing saved rupture " + position
                                + ", expected identity " + hitIds.get(i) + ", found " + pending.get(position));
                    }
                    level.setChunkForced(far.x, far.z, true);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "Waiting for return of the far chunk");
                    for (int i = 0; i < hits.size(); i++) {
                        var at = hits.get(i);
                        var position = Positions.of(level, at);
                        var state = level.getBlockState(at);
                        var registration = world.at(position);
                        if (state.isAir() && registration.isEmpty()) continue;
                        var saved = world.savedBlockEntity(position.dimension(), at);
                        var marker = world.capture().world().builds().ruptures().get(position);
                        helper.assertTrue(false, "Waiting for rupture reconciliation at " + position + ". Original " + hitIds.get(i)
                                + ", block " + state + ", block entity " + (saved == null ? "-" : saved.getLong("FluidIdentity"))
                                + ", registration " + registration.map(r -> r.device().id()).orElse(0L) + ", marker " + marker);
                    }
                    for (var at : survivors) {
                        helper.assertTrue(!level.getBlockState(at).isAir(), "The protected block vanished at " + at);
                    }
                })
                .thenExecuteAfter(120, () -> {
                    double released = service.ledger(dimension).get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED) - releasedBefore[0];
                    helper.assertTrue(Math.abs(released - spill[0] / lfl) < 1e-8, "Reconciliation released methane twice");
                    service.clear(dimension);
                    level.removeBlock(lineSource, false);
                    level.removeBlock(lineSink, false);
                    for (var at : survivors) level.removeBlock(at, false);
                    for (var at : shield) level.removeBlock(at, false);
                    if (mixed) chunks.removeRegionTicket(TicketType.FORCED, near, 0, near);
                    level.setChunkForced(near.x, near.z, false);
                    level.setChunkForced(far.x, far.z, false);
                })
                .thenSucceed();
    }

    /** A straight line has no inventory node. The fitted volume takes the methane concentration of its interval. */
    private static double methaneInPipe(FluidWorldAuthority world, long pipe, int methane) {
        var history = world.view(pipe).pipeHistory();
        if (history.isEmpty()) return 0;
        var transfer = history.getFirst();
        double moles = 0, volume = 0;
        for (var stream : List.of(transfer.forward(), transfer.reverse())) {
            for (var phase : stream.phaseMoles()) moles += phase[methane];
            volume += Arrays.stream(stream.phaseVolumes()).sum();
        }
        var geometry = world.registrations().get(pipe).device().geometry();
        double pipeVolume = geometry.length() * Math.max(1, geometry.channels())
                * Math.PI * geometry.diameter() * geometry.diameter() / 4;
        return volume > 0 ? moles * pipeVolume / volume : 0;
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
