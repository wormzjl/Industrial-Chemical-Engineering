package com.wormzjl.icheme.fluid.gametest;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.ValveSignal;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * A control valve regulates only on a sensor physically linked to it and selected on its screen (owner, 2026-11-17: "The
 * control valve needs to select sensor first in order to function, like other controlling systems"; batch
 * 2026-11-16-review-plant-sizing, VALVE_SENSOR_REVIEW.md), read through the fluid world: a flowmeter fitted on the valve's
 * own mount writes no law, the valve's screen lists it and reads NO SIGNAL; the player selects it through the screen's
 * edit, and the valve regulates on it; a law naming a sensor that is not linked has no signal. The test removes what it
 * placed before it passes or fails.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ValveSignalGameTests {
    private ValveSignalGameTests() {}

    @GameTest(template = "empty", batch = "valve-signal", timeoutTicks = 900)
    public static void aValveRegulatesOnlyOnTheSensorThePlayerSelects(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper);
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        var source = plant.base;
        var valve = source.east(2);
        var sink = source.east(4);
        long[] id = {0};
        UUID[] mounted = {null};
        ServerPlayer[] player = {null};
        FluidDeviceMenu[] menu = {null};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            level.setBlock(source, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(source.immutable());
            for (int k = 1; k <= 3; k++) plant.pipe(source.east(k));
            level.setBlock(sink, ModBlocks.FLUID_VOID.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(sink.immutable());
            plant.valves.put(valve, Direction.EAST);
            plant.install(valve, Direction.EAST);
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        sequence = sequence.thenWaitUntil(() -> helper.assertTrue(failure[0] != null || plant.valvesInstalled(), "Waiting for the valve"))
                .thenExecute(() -> {
                    if (failure[0] != null) return;
                    try {
                        id[0] = world.at(plant.position(valve)).orElseThrow().device().id();
                        // The control valve fitting's record, as the fitting item makes it, then a flowmeter on its own mount.
                        world.instruments().legacyInstalled(id[0], Instrument.Type.PRESSURE_VALVE);
                        var entity = (FluidDeviceBlockEntity) level.getBlockEntity(valve);
                        entity.refreshGeometry(entity.bore(), true);
                        mounted[0] = LevelLoopGameTests.fit(plant, valve, Sensor.Kind.FLOW, ModItems.FLOWMETER.get(), "valve_sensor");
                        var law = world.registrations().get(id[0]).device().control();
                        if (law instanceof FlowControl.ControlValve v && v.sensor() != null) failure[0] = "fitting the flowmeter wrote the valve's law: " + v;
                        else if (ValveSignal.choices(world, id[0]).stream().noneMatch(c -> c.sensor().instance().equals(mounted[0])))
                            failure[0] = "the valve's screen does not list its own flowmeter: " + ValveSignal.choices(world, id[0]);
                        else if (!Double.isNaN(FluidNetwork.opening(world, id[0]))) failure[0] = "a valve with no sensor selected reads an opening";
                    } catch (RuntimeException e) {
                        failure[0] = "fit: " + e;
                    }
                })
                .thenExecute(() -> {
                    if (failure[0] != null) return;
                    // The player selects the flowmeter on the valve's screen, to hold 0.5 kg/s, and presses Apply.
                    player[0] = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ValveSignalSelect".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "ValveSignalSelect"));
                    player[0].setGameMode(GameType.CREATIVE);
                    player[0].setPos(valve.getX() + .5, valve.getY() + 1, valve.getZ() + .5);
                    menu[0] = new FluidDeviceMenu(73, player[0].getInventory(), valve, id[0], false);
                    player[0].containerMenu = menu[0];
                    var record = world.registrations().get(id[0]);
                    var c = FluidNetwork.Controls.from(record);
                    var selected = new FluidNetwork.Controls(c.temperature(), c.pressure(), c.diameter(), c.roughness(), c.volumeFlow(), c.maximumAddedPressure(), c.composition(), c.solids(),
                            c.maximumPressureRatio(), c.power(), FlowControl.ValveSource.MASS_FLOW, .5).withSensor(mounted[0]);
                    FluidNetwork.edit(new FluidNetwork.EditPayload(73, valve, id[0], record.revision(), new Gson().toJson(selected)), player[0]);
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || world.registrations().get(id[0]).device().control() instanceof FlowControl.ControlValve v && mounted[0].equals(v.sensor()),
                        "Waiting for the selection to apply"))
                .thenExecute(() -> {
                    if (failure[0] != null) return;
                    var law = (FlowControl.ControlValve) world.registrations().get(id[0]).device().control();
                    if (law.source() != FlowControl.ValveSource.MASS_FLOW || law.massTarget() != .5) failure[0] = "the selection wrote the wrong law: " + law;
                    else if (!ValveSignal.linked(world, id[0], law)) failure[0] = "the selected flowmeter on the valve reads unlinked";
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || world.view(id[0]) != null && !Double.isNaN(FluidNetwork.opening(world, id[0])),
                        "Waiting for the valve's screen to read an opening"))
                .thenExecute(() -> {
                    if (failure[0] != null) return;
                    // A law naming a sensor that is not linked to the valve (a flowmeter elsewhere): no signal.
                    var r = world.registrations().get(id[0]);
                    var remote = new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 1, new UUID(0x7e57, 1), Math.addExact(((FlowControl.ControlValve) r.device().control()).revision(), 1));
                    if (ValveSignal.linked(world, id[0], remote)) failure[0] = "a law on a sensor not linked reads linked";
                    else world.edit(id[0], r.revision(), r.device().withControl(remote), r.spec());
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || world.registrations().get(id[0]).device().control() instanceof FlowControl.ControlValve v && new UUID(0x7e57, 1).equals(v.sensor()),
                        "Waiting for the law on the unlinked sensor to apply"))
                .thenExecute(() -> {
                    if (failure[0] == null && !Double.isNaN(FluidNetwork.opening(world, id[0]))) failure[0] = "a valve with no signal reads an opening";
                });
        sequence.thenExecute(() -> {
                    if (menu[0] != null) {
                        menu[0].removed(player[0]);
                        player[0].containerMenu = player[0].inventoryMenu;
                    }
                    plant.remove();
                }).thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the line to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }
}
