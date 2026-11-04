package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Electricity in the world (batch 2026-11-03-rotating-machinery W5, build plan section 7.5): FE goes in and out through
 * the terminal box's energy capability. A motor-driven pump runs on a filled port, drains its store, stands
 * ({@code TRIPPED: NO POWER}) and restarts by itself on a refill; an air cooler without power holds natural draught and
 * runs its fans once filled; a generator turned by a creative motor fills its store and gives FE at its port. Each test
 * removes everything it placed before it passes or fails.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ElectricGameTests {
    private ElectricGameTests() {
    }

    private static PhysicalFluidTopology.Position position(net.minecraft.world.level.Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    /** The FE port of {@code vessel}'s terminal box, from its top face. */
    private static IEnergyStorage port(AssemblyGameTests.Plant plant, Vessel vessel) {
        var t = ShapeRasterizer.terminal(vessel.shape());
        if (t == null) throw new IllegalStateException("No terminal box on the " + vessel.shape().kind());
        var at = vessel.origin().offset(t.x(), t.y(), t.z());
        var port = plant.level.getCapability(Capabilities.EnergyStorage.BLOCK, at, Direction.UP);
        if (port == null) throw new IllegalStateException("No FE port at " + at.toShortString());
        return port;
    }

    /**
     * A motor standing beyond a shaft block on {@code pump}'s driven coupling, turned so its driving end faces the shaft,
     * rated {@code kilowatts} at {@code poles}: the plant's, removed with it.
     */
    private static Vessel motor(AssemblyGameTests.Plant plant, Vessel pump, double kilowatts, int poles) {
        for (var s : ShapeRasterizer.shafts(pump.shape())) {
            if (s.role() != EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT) continue;
            var d = Direction.fromDelta(s.dx(), s.dy(), s.dz());
            var shaft = pump.origin().offset(s.block().x(), s.block().y(), s.block().z());
            var end = shaft.relative(d);
            var design = UnitDesign.of(EquipmentShape.Kind.MOTOR);
            double size = design.snap(kilowatts);
            int length = design.defaultLength(size);
            for (var heading : EquipmentShape.Heading.values()) {
                var shape = EquipmentShape.designed(EquipmentShape.Kind.MOTOR, size, length, heading, 0, com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE)
                        .withDuty(MachineDuty.motor(size, poles, MachineDuty.Drive.FIXED));
                for (var m : ShapeRasterizer.shafts(shape)) {
                    if (m.role() != EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT || m.dx() != -s.dx() || m.dy() != -s.dy() || m.dz() != -s.dz()) continue;
                    var origin = end.offset(-m.cell().x(), -m.cell().y(), -m.cell().z());
                    boolean clear = plant.level.getBlockState(shaft).canBeReplaced();
                    for (var cell : ShapeRasterizer.layout(shape).shell()) clear &= plant.level.getBlockState(origin.offset(cell.x(), cell.y(), cell.z())).canBeReplaced();
                    if (!clear) continue;
                    plant.level.setBlock(shaft, ModBlocks.SHAFT.get().defaultBlockState().setValue(com.wormzjl.icheme.world.level.block.ShaftBlock.AXIS, d.getAxis()), Block.UPDATE_ALL);
                    plant.devices.add(shaft.immutable());
                    return plant.place(shape, origin.getX() - plant.base.getX(), origin.getY() - plant.base.getY(), origin.getZ() - plant.base.getZ());
                }
            }
            throw new IllegalStateException("No room for a motor on the pump's coupling at " + shaft.toShortString());
        }
        throw new IllegalStateException("No driven coupling on the " + pump.shape().kind());
    }

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "electric-motor")
    public static void aMotorDrivenPumpDrainsItsStoreStopsAndRestartsOnARefill(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] ids = new long[3];
        Vessel[] motor = {null};
        boolean[] feed = {false};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            var pump = plant.place(AssemblyGameTests.Plant.unit(EquipmentShape.Kind.PUMP), 0, 0, 0);
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
            // The motor: twice the pump's design shaft power with the design spare, so the line's run-out never overloads it.
            var duty = pump.shape().duty();
            double shaft = 1000 * 9.81 * duty.flow() / 3600 * duty.head() / .6;
            motor[0] = motor(plant, pump, Motor.ratingFor(2 * shaft) / 1000, duty.poles() == 0 ? 2 : duty.poles());
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        helper.onEachTick(() -> {
            if (feed[0] && failure[0] == null && motor[0] != null) {
                try {
                    port(plant, motor[0]).receiveEnergy(Integer.MAX_VALUE, false);
                } catch (RuntimeException e) {
                    failure[0] = "feed: " + e;
                }
            }
        });
        java.util.function.Supplier<String> status = () -> {
            var u = world.shafts().unitAt(position(level, motor[0].origin()));
            return u == null ? "no motor unit" : world.shafts().view(u.id()).status();
        };
        if (failure[0] == null) {
            // Built from W5 on, its store is empty: the motor stands.
            sequence = sequence.thenWaitUntil(() -> helper.assertTrue(status.get().equals("TRIPPED: NO POWER"), "Waiting for the empty motor to stand: " + status.get()))
                    .thenExecute(() -> {
                        if (port(plant, motor[0]).canExtract() || !port(plant, motor[0]).canReceive()) failure[0] = "a motor's port is receive-only";
                        feed[0] = true;
                    });
            // Filled, it restarts on the cadence and the pump delivers.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                var view = world.view(ids[1]);
                helper.assertTrue(status.get().equals("RUNNING") && view.state() != null && view.state().mass() > .01, "Waiting for the pump to deliver: " + status.get() + " / " + world.view(ids[2]).status());
            }).thenExecute(() -> feed[0] = false);
            // Its feed stopped, it drains its store and stands; NO POWER is never its saved trip.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                helper.assertTrue(status.get().equals("TRIPPED: NO POWER"), "Waiting for the store to run out: " + status.get() + ", " + port(plant, motor[0]).getEnergyStored() + " FE");
            }).thenExecute(() -> {
                if (failure[0] != null) return;
                var u = world.shafts().unitAt(position(level, motor[0].origin()));
                if (u.trip() != ShaftTrain.Trip.NONE) failure[0] = "NO POWER was saved as the motor's trip";
                feed[0] = true;
            });
            // A refill restarts it by itself (D6), no reset.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                helper.assertTrue(status.get().equals("RUNNING") && world.view(ids[2]).status().contains("SHAFT: RUNNING"), "Waiting for the restart: " + status.get());
            }).thenExecute(() -> feed[0] = false);
        }
        finish(helper, plant, sequence, failure);
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "electric-cooler")
    public static void anAirCoolerWithoutPowerHoldsNaturalDraughtUntilItsPortIsFilled(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] side = {0};
        Vessel[] cooler = {null};
        boolean[] feed = {false};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            cooler[0] = plant.place(AssemblyGameTests.Plant.unit(EquipmentShape.Kind.AIR_COOLER), 0, 0, 0);
            // Hot water through the bundle: a supply at 2 bar beyond the inlet's pipe block, a void at the atmosphere
            // beyond the outlet's, so the island has its boundaries and runs.
            var inlet = AssemblyGameTests.Plant.front(cooler[0], Role.PROCESS_INLET);
            var supply = inlet.relative(AssemblyGameTests.Plant.out(cooler[0], Role.PROCESS_INLET));
            var outlet = AssemblyGameTests.Plant.front(cooler[0], Role.PROCESS_OUTLET);
            var sink = outlet.relative(AssemblyGameTests.Plant.out(cooler[0], Role.PROCESS_OUTLET));
            plant.pipe(inlet);
            plant.pipe(outlet);
            level.setBlock(supply, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(supply.immutable());
            level.setBlock(sink, ModBlocks.FLUID_VOID.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(sink.immutable());
            var water = FluidDeviceSpec.water(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
            TankCharge.run(world, ((FluidDeviceBlockEntity) level.getBlockEntity(supply)).fluidIdentity(), new FluidDeviceSpec(1, 358.15, 2e5, water));
            long voidId = ((FluidDeviceBlockEntity) level.getBlockEntity(sink)).fluidIdentity();
            var r = world.registrations().get(voidId);
            world.edit(voidId, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, 101_325, water));
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        helper.onEachTick(() -> {
            if (feed[0] && failure[0] == null) port(plant, cooler[0]).receiveEnergy(Integer.MAX_VALUE, false);
        });
        if (failure[0] == null) {
            sequence = sequence.thenWaitUntil(() -> {
                side[0] = EquipmentStructure.sideIdentities(plant.level, cooler[0])[0];
                var view = world.view(side[0]);
                helper.assertTrue(view.heat() != null && view.heat().fans() != null && view.heat().fans() == 0 && view.status().contains("NO POWER"),
                        "Waiting for the empty cooler to stand at natural draught: " + view.status());
            }).thenExecute(() -> {
                var unit = world.registrations().get(side[0]).device().heat();
                if (unit.control().share() != 1) failure[0] = "the fans' setting changed: " + unit.control();
                if (unit.fanCount() <= 0) failure[0] = "no fan count on the cooler's side";
                feed[0] = true;
            }).thenWaitUntil(() -> {
                if (failure[0] != null) return;
                var view = world.view(side[0]);
                helper.assertTrue(view.heat().fans() > .99 && !view.status().contains("NO POWER") && view.status().contains("POWER:"),
                        "Waiting for the fans to start on power: " + view.status());
            }).thenExecute(() -> feed[0] = false);
        }
        finish(helper, plant, sequence, failure);
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "electric-generator")
    public static void aGeneratorOnACreativeMotorFillsItsStoreAndGivesFE(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        Vessel[] generator = {null};
        BlockPos[] shaft = {null};
        int[] given = {0};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            generator[0] = plant.place(AssemblyGameTests.Plant.unit(EquipmentShape.Kind.GENERATOR), 0, 0, 0);
            shaft[0] = plant.drive(generator[0]);
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        if (failure[0] == null) {
            // The creative motor at the generator's rated speed: no gearbox between them.
            sequence = sequence.thenWaitUntil(() -> {
                var g = world.shafts().unitAt(position(level, generator[0].origin()));
                ShaftUnit creative = null;
                for (var d : Direction.values()) {
                    var u = world.shafts().unitAt(position(level, shaft[0].relative(d)));
                    if (u != null && u.kind() == com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CREATIVE_MOTOR) creative = u;
                }
                helper.assertTrue(g != null && creative != null, "Waiting for the generator's train");
                world.shafts().queue(new ShaftTrains.Configure(creative.id(), new ShaftUnit.Settings(true, ShaftTrain.Mode.SPEED, g.designSpeed(), 0, 0)));
            }).thenWaitUntil(() -> {
                var g = world.shafts().unitAt(position(level, generator[0].origin()));
                var port = port(plant, generator[0]);
                helper.assertTrue(port.canExtract() && !port.canReceive() && port.getEnergyStored() > 0,
                        "Waiting for the generator to fill its store: " + world.shafts().view(g.id()).status() + ", " + port.getEnergyStored() + " FE");
            }).thenExecute(() -> {
                var port = port(plant, generator[0]);
                int stored = port.getEnergyStored();
                given[0] = port.extractEnergy(Integer.MAX_VALUE, false);
                if (given[0] <= 0 || given[0] > stored) failure[0] = "its port gave " + given[0] + " of " + stored + " FE";
                if (port.receiveEnergy(1000, false) != 0) failure[0] = "a generator's port took FE";
            });
        }
        finish(helper, plant, sequence, failure);
    }

    /** Removes the plant and its shaft parts, then passes or fails. */
    private static void finish(GameTestHelper helper, AssemblyGameTests.Plant plant, net.minecraft.gametest.framework.GameTestSequence sequence, String[] failure) {
        sequence.thenExecute(plant::remove).thenWaitUntil(() -> helper.assertTrue(plant.removed() && plant.world.shafts().units().stream()
                        .noneMatch(u -> plant.inside(new BlockPos(u.position().x(), u.position().y(), u.position().z()))), "Waiting for the plant and its shaft parts to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }
}
