package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import com.wormzjl.icheme.world.level.block.ShaftBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Shaft trains in the world (batch 2026-11-03-rotating-machinery W3, build plan section 6): a creative motor turns a
 * constructed pump through a shaft block, from a water supply into a tank. The pump delivers; a broken shaft stands it
 * (its Drive group says NO DRIVER, W7; no flow) within a slice or two; the shaft back, a power limit below the pump's demand slows the train
 * (POWER LIMITED). The test removes everything it placed before it passes or fails.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ShaftTrainGameTests {
    private ShaftTrainGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "shaft-trains")
    public static void aCreativeMotorDrivesAPumpUntilItsShaftBreaks(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(12, 10, 12));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] ids = new long[3];
        BlockPos[] shaft = {null};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            var pump = plant.place(AssemblyGameTests.Plant.unit(EquipmentShape.Kind.PUMP), 0, 0, 0);
            // The supply: a water generator one cell beyond the suction's pipe block; the tank one beyond the discharge's.
            var suction = AssemblyGameTests.Plant.front(pump, Role.SUCTION);
            var generator = suction.relative(AssemblyGameTests.Plant.out(pump, Role.SUCTION));
            var discharge = AssemblyGameTests.Plant.front(pump, Role.DISCHARGE);
            var tank = discharge.relative(AssemblyGameTests.Plant.out(pump, Role.DISCHARGE));
            plant.pipe(suction);
            plant.pipe(discharge);
            level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(generator.immutable());
            level.setBlock(tank, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(tank.immutable());
            ids[0] = ((FluidDeviceBlockEntity) level.getBlockEntity(generator)).fluidIdentity();
            ids[1] = ((FluidDeviceBlockEntity) level.getBlockEntity(tank)).fluidIdentity();
            var cell = ShapeRasterizer.fluidCell(pump.shape());
            ids[2] = ((EquipmentShellBlockEntity) level.getBlockEntity(pump.origin().offset(cell.x(), cell.y(), cell.z()))).fluidIdentity();
            TankCharge.run(world, ids[0], new FluidDeviceSpec(1, 298.15, 2e5, FluidDeviceSpec.water(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition()));
            shaft[0] = plant.drive(pump);
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        if (failure[0] == null) {
            // The pump delivers: the tank fills, the pump's status names its running shaft.
            sequence = sequence.thenWaitUntil(() -> {
                var view = world.view(ids[1]);
                helper.assertTrue(view.state() != null && view.state().mass() > .01, "Waiting for the pump to deliver: " + world.view(ids[2]).status());
            }).thenExecute(() -> {
                var drive = world.view(ids[2]).drive();
                if (drive == null || !drive.status().startsWith("RUNNING")) failure[0] = "the pump's shaft is not running: " + drive;
                level.removeBlock(shaft[0], false);
            });
            // A broken shaft: the casing has no driver and stands.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                var view = world.view(ids[2]);
                helper.assertTrue(view.drive() != null && view.drive().driver().isEmpty() && Math.abs(view.massFlow()) < 1e-9, "Waiting for the pump to stand: " + view.drive());
            }).thenExecute(() -> {
                if (failure[0] != null) return;
                level.setBlock(shaft[0], ModBlocks.SHAFT.get().defaultBlockState().setValue(ShaftBlock.AXIS, axis(plant, shaft[0])), Block.UPDATE_ALL);
            });
            // The shaft back and a 1 W limit on the creative motor: the train slows, POWER LIMITED.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                helper.assertTrue(world.view(ids[2]).drive() != null && world.view(ids[2]).drive().status().startsWith("RUNNING"), "Waiting for the shaft to turn again: " + world.view(ids[2]).drive());
            }).thenExecute(() -> {
                if (failure[0] != null) return;
                var motor = world.shafts().unitAt(position(level, motor(plant, shaft[0])));
                if (motor == null) {
                    failure[0] = "no creative motor unit";
                    return;
                }
                world.shafts().queue(new ShaftTrains.Configure(motor.id(), new ShaftUnit.Settings(true, ShaftTrain.Mode.SPEED, 0, 1, 0)));
            }).thenWaitUntil(() -> {
                if (failure[0] != null) return;
                helper.assertTrue(world.view(ids[2]).drive() != null && world.view(ids[2]).drive().status().contains("POWER LIMITED"), "Waiting for the power limit: " + world.view(ids[2]).drive());
            });
        }
        sequence.thenExecute(plant::remove).thenWaitUntil(() -> helper.assertTrue(plant.removed() && world.shafts().units().stream()
                                .noneMatch(u -> plant.inside(new BlockPos(u.position().x(), u.position().y(), u.position().z()))), "Waiting for the plant and its shaft parts to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }

    /**
     * A machine's page (W7, readings A85, A87): a creative motor's page opens a session that replays its view; a speed edit
     * sent through the page's route is checked at receipt and queued, not applied by the handler; the engine applies it at
     * its tick drain and the creative motor turns at the new speed. The test closes its session and removes its block.
     */
    @GameTest(template = "empty", timeoutTicks = 4000, batch = "machine-pages")
    public static void aMachinePageQueuesASpeedEditForTheEngine(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(4, 2, 4)), new BlockPos(-2, -2, -2), new BlockPos(2, 2, 2));
        var level = plant.level;
        var world = plant.world;
        var at = plant.base.immutable();
        String[] failure = {null};
        com.wormzjl.icheme.world.inventory.MachineMenu[] menu = {null};
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "MachinePageTest"));
        player.setPos(at.getX() + 2, at.getY(), at.getZ());
        int[] before = {0};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            level.setBlock(at, ModBlocks.CREATIVE_MOTOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(at);
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        if (failure[0] == null)
            sequence = sequence.thenWaitUntil(() -> helper.assertTrue(world.shafts().unitAt(position(level, at)) != null, "Waiting for the creative motor's part"))
                    .thenExecute(() -> {
                        var unit = world.shafts().unitAt(position(level, at));
                        before[0] = com.wormzjl.icheme.network.MachineNetwork.sessions(level.getServer());
                        menu[0] = new com.wormzjl.icheme.world.inventory.MachineMenu(1, player.getInventory(), at, com.wormzjl.icheme.world.inventory.MachineMenu.REACH, unit.id(),
                                unit.kind(), false);
                        player.containerMenu = menu[0];
                        com.wormzjl.icheme.network.MachineNetwork.subscribe(menu[0]);
                        if (com.wormzjl.icheme.network.MachineNetwork.sessions(level.getServer()) != before[0] + 1) failure[0] = "the page opened no session";
                        com.wormzjl.icheme.network.MachineNetwork.receive(player, new com.wormzjl.icheme.network.MachineNetwork.EditPayload(menu[0].containerId, 1,
                                com.wormzjl.icheme.network.MachineNetwork.Action.APPLY, new ShaftUnit.Settings(true, ShaftTrain.Mode.SPEED, 1500, 0, 0)));
                        // The handler only queues: the settings move at the engine's tick drain.
                        if (failure[0] == null && world.shafts().unit(unit.id()).settings().setPoint() == 1500) failure[0] = "the handler applied the edit itself";
                    }).thenWaitUntil(() -> {
                        if (failure[0] != null) return;
                        var unit = world.shafts().unitAt(position(level, at));
                        var view = unit == null ? null : world.shafts().view(unit.id());
                        helper.assertTrue(unit != null && unit.settings().setPoint() == 1500 && view != null && Math.abs(view.speed() - 1500) < 1e-6,
                                "Waiting for the creative motor to turn at 1500 rpm: " + view);
                    });
        sequence.thenExecute(() -> {
            if (menu[0] != null) {
                player.containerMenu = player.inventoryMenu;
                menu[0].removed(player);
                if (com.wormzjl.icheme.network.MachineNetwork.sessions(level.getServer()) != before[0] && failure[0] == null) failure[0] = "the page's session outlived it";
            }
            plant.remove();
        }).thenWaitUntil(() -> helper.assertTrue(plant.removed() && world.shafts().units().stream()
                        .noneMatch(u -> plant.inside(new BlockPos(u.position().x(), u.position().y(), u.position().z()))), "Waiting for the motor's part to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }

    /** The shaft block's axis: the line from it to the creative motor beyond it. */
    private static net.minecraft.core.Direction.Axis axis(AssemblyGameTests.Plant plant, BlockPos shaft) {
        var motor = motor(plant, shaft);
        return net.minecraft.core.Direction.fromDelta(motor.getX() - shaft.getX(), motor.getY() - shaft.getY(), motor.getZ() - shaft.getZ()).getAxis();
    }

    /** The creative motor the plant placed beside {@code shaft}. */
    private static BlockPos motor(AssemblyGameTests.Plant plant, BlockPos shaft) {
        for (var d : net.minecraft.core.Direction.values()) {
            var at = shaft.relative(d);
            if (plant.devices.contains(at) && plant.level.getBlockState(at).is(ModBlocks.CREATIVE_MOTOR.get())) return at;
        }
        throw new IllegalStateException("No creative motor beside " + shaft.toShortString());
    }

    private static PhysicalFluidTopology.Position position(net.minecraft.world.level.Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }
}
