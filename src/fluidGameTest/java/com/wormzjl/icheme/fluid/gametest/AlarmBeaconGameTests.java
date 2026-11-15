package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.AlarmSettings;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.AlarmBeaconBlock;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.AlarmBeaconBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/**
 * The alarm beacon on the control cadence (batch 2026-11-15-fluid-interfaces, W9, plan section 6): nitrogen from a
 * generator through a flowmeter to a void, the meter's socket cabled to a standing beacon set ABOVE half the line's free
 * flow. The beacon's block state is ACTIVE (lit, block light 12) at a cadence once wired, ACKNOWLEDGED after a hand's
 * acknowledgement, and CLEAR once its threshold is set above the flow (the signal falls below the threshold less its
 * band). The test removes every block, cable and fact it made.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class AlarmBeaconGameTests {
    private AlarmBeaconGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 4000, batch = "alarm-beacon")
    public static void aBeaconSoundsOnItsInputAndClearsWithItsBand(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        // Clear of the barrier ring around the one-cell template, as the control loop's line.
        var source = helper.absolutePos(new BlockPos(2, 1, 2));
        var meter = source.east();
        var sink = meter.east(2);
        var beaconAt = meter.south(2);
        var line = List.of(source, meter, meter.east(), sink);
        level.setBlock(source, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        for (var pipe : List.of(meter, meter.east()))
            level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.EAST), 3);
        level.setBlock(sink, ModBlocks.FLUID_VOID.get().defaultBlockState(), 3);
        long[] ids = line.stream().mapToLong(p -> ControlLoopGameTests.identity(level, p)).toArray();
        long meterId = ids[1];
        var nitrogen = FluidDeviceSpec.nitrogen(MaterialRuntime.active()).composition();
        TankCharge.run(world, ids[0], new FluidDeviceSpec(1, 298.15, 150_000, nitrogen));
        var fitter = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "AlarmBeaconFit"));
        fitter.setGameMode(GameType.CREATIVE);
        fitter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.FLOWMETER.get()));
        fitter.moveTo(meter.getX() + .5, meter.getY() + 1, meter.getZ() + .5, 0, 90);
        level.setBlock(beaconAt, ModBlocks.ALARM_BEACON.get().defaultBlockState().setValue(AlarmBeaconBlock.FACING, Direction.UP), 3);
        var beacon = (AlarmBeaconBlockEntity) level.getBlockEntity(beaconAt);
        world.controls().loaded(beacon);
        UUID alarm = beacon.identity();
        var cables = new ArrayList<DataBus.Site>();
        double[] free = {Double.NaN};
        int[] phase = {0};
        Sensor.Installed[] flowmeter = {null};
        helper.succeedWhen(() -> {
            var standing = level.getBlockState(beaconAt);
            if (phase[0] < 4 && !standing.hasProperty(AlarmBeaconBlock.STATE))
                throw new IllegalStateException("phase " + phase[0] + ": the beacon's cell holds " + standing + ", facts " + world.controls().facts().alarm(alarm));
            var shown = phase[0] < 4 ? standing.getValue(AlarmBeaconBlock.STATE) : null;
            if (phase[0] == 0) {
                if (flowmeter[0] == null) {
                    world.instruments().requestSensor(fitter, InteractionHand.MAIN_HAND, Sensor.Kind.FLOW);
                    flowmeter[0] = world.instruments().allSensors().stream()
                            .filter(s -> s.key().host() == meterId && s.kind() == Sensor.Kind.FLOW)
                            .findFirst()
                            .orElse(null);
                    if (flowmeter[0] == null)
                        throw new IllegalStateException("The flowmeter was not fitted: " + ControlLoopGameTests.fitting(level, fitter, meter));
                }
                helper.assertTrue(beacon.dataPort() != null && ControlLoopGameTests.port(level, meter) != null, "Waiting for the two data sockets");
                double flow = ControlLoopGameTests.flow(helper, world, meterId);
                helper.assertTrue(flow > 0, "Waiting for flow through the flowmeter: " + flow);
                free[0] = flow;
                cables.addAll(ControlLoopGameTests.wire(level, world, List.of(beacon.dataPort(), ControlLoopGameTests.port(level, meter))));
                var facts = world.controls().facts();
                // The settings as a fact (a screen's Apply queues the same edit): ABOVE half the free flow.
                facts.alarm(facts.alarm(alarm).edit(new AlarmSettings("FAL-1", true, AlarmSettings.Source.INPUT, flowmeter[0].instance(),
                        Sensor.Unit.KILOGRAM_PER_SECOND, AlarmSettings.Comparison.ABOVE, free[0] / 2)));
                phase[0] = 1;
                helper.assertTrue(false, "Waiting for the cadence to decide the beacon");
            }
            if (phase[0] == 1) {
                helper.assertTrue(shown == AlarmBeaconBlock.Shown.ACTIVE, "Waiting for ACTIVE: " + shown);
                helper.assertTrue(level.getBlockState(beaconAt).getLightEmission() == AlarmBeaconBlock.LIGHT, "An active beacon lights its cell");
                world.controls().acknowledge(alarm);
                phase[0] = 2;
                helper.assertTrue(false, "Waiting for the cadence to take the acknowledgement");
            }
            if (phase[0] == 2) {
                helper.assertTrue(shown == AlarmBeaconBlock.Shown.ACKNOWLEDGED, "Waiting for ACKNOWLEDGED: " + shown);
                helper.assertTrue(level.getBlockState(beaconAt).getLightEmission() == AlarmBeaconBlock.LIGHT, "An acknowledged beacon still flashes");
                // The threshold above the flow: the signal now lies below the threshold less its band.
                var facts = world.controls().facts();
                var a = facts.alarm(alarm);
                var s = a.settings();
                facts.alarm(a.edit(new AlarmSettings(s.name(), true, s.source(), s.sensor(), s.unit(), s.comparison(), free[0] * 2)));
                phase[0] = 3;
                helper.assertTrue(false, "Waiting for the cadence to clear the beacon");
            }
            if (phase[0] == 3) {
                helper.assertTrue(shown == AlarmBeaconBlock.Shown.CLEAR, "Waiting for CLEAR: " + shown);
                helper.assertTrue(level.getBlockState(beaconAt).getLightEmission() == 0, "A clear beacon is dark");
                for (var site : cables) world.controls().removeCable(site);
                level.removeBlock(beaconAt, false);
                for (var p : line) level.removeBlock(p, false);
                phase[0] = 4;
                helper.assertTrue(false, "Waiting for cleanup");
            }
            for (long id : ids) helper.assertTrue(!world.registrations().containsKey(id), "Waiting for cleanup of " + id);
            helper.assertTrue(world.controls().facts().alarm(alarm) == null && world.controls().facts().port(alarm) == null,
                    "The removed beacon kept its facts");
            helper.assertTrue(world.instruments().allSensors().stream().noneMatch(s -> s.key().host() == meterId), "The removed pipe kept its flowmeter");
        });
    }
}
