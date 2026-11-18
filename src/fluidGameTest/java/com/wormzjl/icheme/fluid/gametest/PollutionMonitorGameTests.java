package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.pollution.MonitorSignals;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Exposure;
import com.wormzjl.icheme.world.level.block.PollutionMonitorBlock;
import com.wormzjl.icheme.world.level.block.entity.PollutionMonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The fixed pollution monitor (batch 2026-11-15-pollution-system, WP10): placed, it takes a data socket and a host in the
 * control facts; in a cube at toxic HIGH (index at or above 1) its lamp turns red within 60 ticks of the field's pass and
 * its toxic reading is the cube's installed index; after a clear it turns green again; removed, its socket leaves the facts.
 *
 * <p>The dimension's field is shared and the effects tests clear it too, so this test runs in a batch of its own (batches
 * run one after another), clears the field at its start and its end, and removes the block it placed.</p>
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PollutionMonitorGameTests {
    /**
     * The toxic index the test releases into the monitor's cube: 1.375 after one pass (it keeps 1 - 6 x 0.12 - 0.12 of the
     * upward drift, then decays).
     */
    private static final double RELEASE = 9.0;

    private PollutionMonitorGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 800, batch = "pollution_monitor")
    public static void aMonitorTurnsRedInAToxicCubeAndGreenAfterAClear(GameTestHelper helper) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        var controls = FluidWorldAuthority.find(level.getServer()).orElseThrow().controls();
        helper.assertTrue(service.enabled(), "Pollution runs in the GameTest server");
        service.clear(dimension);
        // Free cells start at x and z 2 (vanilla rings the one-cell structure with barriers).
        var at = new BlockPos(2, 2, 2);
        var absolute = helper.absolutePos(at);
        long cube = PollutionService.cube(absolute);
        helper.setBlock(at, ModBlocks.POLLUTION_MONITOR.get().defaultBlockState());
        long[] mark = new long[1];
        UUID[] socket = new UUID[1];
        helper.startSequence()
                // Its load binds it: a sensor socket of its host, owned by its block; the first pass lights it green.
                .thenWaitUntil(() -> {
                    PollutionMonitorBlockEntity monitor = helper.getBlockEntity(at);
                    helper.assertTrue(monitor != null && monitor.host() > 0 && monitor.dataPort() != null, "Waiting for the monitor's socket");
                    helper.assertBlockProperty(at, PollutionMonitorBlock.LAMP, MonitorSignals.Lamp.GREEN);
                })
                .thenExecute(() -> {
                    PollutionMonitorBlockEntity monitor = helper.getBlockEntity(at);
                    var port = controls.facts().port(monitor.dataPort().id());
                    helper.assertTrue(port != null && port.kind() == DataPort.Kind.SENSOR && port.host() == monitor.host()
                            && port.owner().equals(absolute), "The socket is a control fact of the monitor's host and block");
                    socket[0] = port.id();
                    // A release lands, then the same pass shares it with the six neighbours (0.12 a face) and decays it:
                    // 1.2 would install under 1, so the test releases enough to stay at or above 1 after one pass.
                    service.add(dimension, cube, Channel.TOXIC, RELEASE);
                })
                // One field pass (100 ticks) and its job install the index.
                .thenWaitUntil(() -> helper.assertTrue(service.index(dimension, cube, Channel.TOXIC) >= Exposure.HIGH,
                        "Waiting for the pass to install the toxic cube; index " + service.index(dimension, cube, Channel.TOXIC)))
                .thenExecute(() -> mark[0] = helper.getTick())
                .thenWaitUntil(() -> helper.assertBlockProperty(at, PollutionMonitorBlock.LAMP, MonitorSignals.Lamp.RED))
                .thenExecute(() -> {
                    long waited = helper.getTick() - mark[0];
                    double toxic = MonitorSignals.indices(service, dimension, absolute)[Channel.TOXIC.ordinal()];
                    helper.assertTrue(waited <= 60, "The lamp turns red within 60 ticks of the pass; it took " + waited);
                    helper.assertTrue(toxic >= Exposure.HIGH && toxic <= RELEASE + 1e-9 && toxic == service.index(dimension, cube, Channel.TOXIC),
                            "The toxic channel reads the cube's installed index; " + toxic);
                    service.clear(dimension);
                })
                .thenWaitUntil(() -> helper.assertBlockProperty(at, PollutionMonitorBlock.LAMP, MonitorSignals.Lamp.GREEN))
                .thenExecute(() -> {
                    // Remove what the test placed, then judge: a removed monitor leaves no socket in the facts.
                    helper.setBlock(at, Blocks.AIR.defaultBlockState());
                    service.clear(dimension);
                    helper.assertTrue(controls.facts().port(socket[0]) == null, "A removed monitor's socket leaves the control facts");
                })
                .thenSucceed();
    }
}
