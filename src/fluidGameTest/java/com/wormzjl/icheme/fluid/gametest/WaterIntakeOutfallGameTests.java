package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The water intake and the waste outfall in a server (batch 2026-11-18-water-intake-outfall). An intake in a built pool of
 * exactly 100 water blocks supplies; a pump behind it fills a tank; a stone set in the pool beside it cuts the pool to 99, and
 * the intake closes (NO WATER BODY) and gives no more. An outfall below a tank of water with 1 mol % hydrogen sulfide drains
 * it, and the pollution field books the gas at the outfall's cube: its toxic and combustible amounts in H2S's own ratio
 * (100 ppm against 4 %), nothing corrosive. Each test removes every block it placed.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class WaterIntakeOutfallGameTests {
    private WaterIntakeOutfallGameTests() {}

    private static PhysicalFluidTopology.Position position(ServerLevel level, BlockPos p) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), p.getX(), p.getY(), p.getZ());
    }

    private static double moles(FluidWorldAuthority world, long id, String component) {
        var view = world.view(id);
        if (view == null || view.state() == null) return 0;
        int c = world.components().indexOf(component);
        double n = 0;
        for (var phase : view.state().phaseMoles()) n += phase[c];
        return n;
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "water-intake")
    public static void anIntakeInAPoolOfAHundredBlocksFillsATankAndStopsAtNinetyNine(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var placed = new ArrayList<BlockPos>();
        // A stone basin: floor at y 1, water at y 2 over x 1..10, z 1..10 and one more cell at (11, 2, 5): 101 cells, the intake in one.
        for (int x = 0; x <= 12; x++) for (int z = 0; z <= 11; z++) {
            boolean pool = x >= 1 && x <= 10 && z >= 1 && z <= 10 || x == 11 && z == 5;
            var floor = helper.absolutePos(new BlockPos(x, 1, z));
            var at = helper.absolutePos(new BlockPos(x, 2, z));
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at, pool ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            placed.add(floor);
            placed.add(at);
        }
        var intake = helper.absolutePos(new BlockPos(5, 2, 5));
        var riser = intake.above();
        var inlet = riser.east();
        var pump = inlet.east();
        var outlet = pump.east();
        var tank = outlet.east();
        level.setBlock(intake, ModBlocks.WATER_INTAKE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(tank, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
        var charge = new TankCharge(helper, world, tank, Direction.UP, 102000);
        long intakeId = ((FluidDeviceBlockEntity) level.getBlockEntity(intake)).fluidIdentity();
        long tankId = ((FluidDeviceBlockEntity) level.getBlockEntity(tank)).fluidIdentity();
        var cut = intake.west();
        BlockPos[] power = {null};
        double[] filled = {0};
        List<BlockPos> line = List.of(riser, inlet, pump, outlet, tank, intake);
        Runnable cleanup = () -> {
            for (var p : line) level.removeBlock(p, false);
            for (var p : charge.blocks()) level.removeBlock(p, false);
            if (power[0] != null) level.removeBlock(power[0], false);
            for (var p : placed) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        };
        helper.startSequence()
                .thenWaitUntil(() -> {
                    var r = world.registrations().get(intakeId);
                    helper.assertTrue(r != null && FluidWorldAuthority.waterIntake(r), "The intake registers the water body");
                    helper.assertTrue(r.device().generatorEnabled(), "A pool of 100 blocks: the intake supplies");
                    helper.assertTrue(r.spec().temperature() == 298.15 && r.spec().pressure() == 101325 && r.device().geometry().minorLoss() == 3, "At 25 C and 1 atm, K = 3");
                    var stood = FluidDeviceBlock.stateFor(r);
                    helper.assertTrue(stood != null && stood.getBlock() == ModBlocks.WATER_INTAKE.get(), "The registry stands it as the water intake block");
                })
                .thenWaitUntil(() -> helper.assertTrue(charge.charged(), "Waiting for the tank's nitrogen charge"))
                .thenExecute(() -> {
                    level.setBlock(riser, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(inlet, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(pump, FluidPumpStartupGameTests.inlinePump(Direction.EAST), Block.UPDATE_ALL);
                    power[0] = FluidPumpStartupGameTests.powerInlineMover(level, pump);
                    level.setBlock(outlet, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
                    long id = ((FluidDeviceBlockEntity) level.getBlockEntity(pump)).fluidIdentity();
                    var old = world.registrations().get(id);
                    world.edit(id, old.revision(), old.device().withControl(new FlowControl.Pump(.0005, 300000)), old.spec());
                })
                .thenWaitUntil(() -> helper.assertTrue(moles(world, tankId, "Water") > 50, "Waiting for the pump to fill the tank from the pool: " + world.view(tankId).status()))
                .thenExecute(() -> level.setBlock(cut, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL))
                .thenWaitUntil(() -> {
                    var r = world.registrations().get(intakeId);
                    helper.assertTrue(r != null && !r.device().generatorEnabled(), "99 blocks: the intake closes");
                    helper.assertTrue(world.view(intakeId).status().contains(FluidWorldAuthority.NO_WATER_BODY), "Its status says why: " + world.view(intakeId).status());
                    filled[0] = moles(world, tankId, "Water");
                })
                .thenIdle(600)
                .thenExecute(() -> {
                    double now = moles(world, tankId, "Water");
                    // The line's holdup may still reach the tank: four 50 mm pipes hold under 8 L (440 mol).
                    helper.assertTrue(now - filled[0] < 450, "A closed intake gives no more water: " + filled[0] + " then " + now);
                    cleanup.run();
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, intake)).isEmpty() && world.registrations().get(tankId) == null, "Waiting for the registrations to leave"))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "waste-outfall")
    public static void anOutfallDrainsATankAndThePollutionFieldBooksTheGas(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        helper.assertTrue(service.enabled(), "Pollution runs in the GameTest server");
        var tank = helper.absolutePos(new BlockPos(2, 4, 2));
        var pipe = tank.below();
        var outfall = pipe.below();
        level.setBlock(tank, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
        var components = world.components();
        double[] n = new double[components.size()];
        n[components.indexOf("Water")] = .99;
        n[components.indexOf("HydrogenSulfide")] = .01;
        var charge = new TankCharge(helper, world, tank, Direction.UP, new FluidDeviceSpec(1, 298.15, 300000, n));
        long tankId = ((FluidDeviceBlockEntity) level.getBlockEntity(tank)).fluidIdentity();
        var dimension = level.dimension();
        long cube = PollutionService.cube(outfall);
        double[] start = new double[3];
        double[] held = {0};
        Runnable cleanup = () -> {
            for (var p : List.of(outfall, pipe, tank)) level.removeBlock(p, false);
            for (var p : charge.blocks()) level.removeBlock(p, false);
        };
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(charge.charged(), "Waiting for the tank's charge"))
                .thenExecute(() -> {
                    held[0] = moles(world, tankId, "HydrogenSulfide");
                    var ledger = service.ledger(dimension);
                    start[0] = ledger.get(Channel.TOXIC, PollutionLedger.Total.RELEASED);
                    start[1] = ledger.get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED);
                    start[2] = ledger.get(Channel.CORROSIVE, PollutionLedger.Total.RELEASED);
                    level.setBlock(outfall, ModBlocks.WASTE_OUTFALL.get().defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenWaitUntil(() -> {
                    var r = world.at(position(level, outfall)).orElse(null);
                    helper.assertTrue(r != null && FluidWorldAuthority.outfall(r), "The outfall registers a sink open to the air");
                    var stood = FluidDeviceBlock.stateFor(r);
                    helper.assertTrue(stood != null && stood.getBlock() == ModBlocks.WASTE_OUTFALL.get(), "The registry stands it as the outfall block");
                    helper.assertTrue(held[0] > 0, "The tank holds hydrogen sulfide");
                    helper.assertTrue(moles(world, tankId, "HydrogenSulfide") < .5 * held[0], "Waiting for the tank to drain: " + world.view(tankId).status());
                    var ledger = service.ledger(dimension);
                    double toxic = ledger.get(Channel.TOXIC, PollutionLedger.Total.RELEASED) - start[0];
                    double combustible = ledger.get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED) - start[1];
                    helper.assertTrue(toxic > 0 && service.index(dimension, cube, Channel.TOXIC) > 0, "Waiting for the field to book the gas at the outfall's cube");
                    helper.assertTrue(Math.abs(toxic / combustible - .04 / 100e-6) < 1e-6 * (.04 / 100e-6), "Toxic and combustible in H2S's ratio, 400: " + toxic / combustible);
                    helper.assertTrue(ledger.get(Channel.CORROSIVE, PollutionLedger.Total.RELEASED) - start[2] == 0, "Nothing corrosive: water and H2S have no corrosive reference");
                })
                .thenExecute(cleanup)
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, outfall)).isEmpty() && world.registrations().get(tankId) == null, "Waiting for the registrations to leave"))
                .thenSucceed();
    }
}
