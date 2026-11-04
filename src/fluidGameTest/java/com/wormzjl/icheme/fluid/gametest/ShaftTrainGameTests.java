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
 * (NO DRIVER, no flow) within a slice or two; the shaft back, a power limit below the pump's demand slows the train
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
                var status = world.view(ids[2]).status();
                if (!status.contains("SHAFT: RUNNING")) failure[0] = "the pump's shaft is not running: " + status;
                level.removeBlock(shaft[0], false);
            });
            // A broken shaft: the casing has no driver and stands.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                var view = world.view(ids[2]);
                helper.assertTrue(view.status().contains("SHAFT: NO DRIVER") && Math.abs(view.massFlow()) < 1e-9, "Waiting for the pump to stand: " + view.status());
            }).thenExecute(() -> {
                if (failure[0] != null) return;
                level.setBlock(shaft[0], ModBlocks.SHAFT.get().defaultBlockState().setValue(ShaftBlock.AXIS, axis(plant, shaft[0])), Block.UPDATE_ALL);
            });
            // The shaft back and a 1 W limit on the creative motor: the train slows, POWER LIMITED.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                helper.assertTrue(world.view(ids[2]).status().contains("SHAFT: RUNNING"), "Waiting for the shaft to turn again: " + world.view(ids[2]).status());
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
                helper.assertTrue(world.view(ids[2]).status().contains("SHAFT: POWER LIMITED"), "Waiting for the power limit: " + world.view(ids[2]).status());
            });
        }
        sequence.thenExecute(plant::remove).thenWaitUntil(() -> helper.assertTrue(plant.removed() && world.shafts().units().stream()
                                .noneMatch(u -> plant.inside(new BlockPos(u.position().x(), u.position().y(), u.position().z()))), "Waiting for the plant and its shaft parts to leave"))
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
