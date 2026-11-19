package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.ElectricStores;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.runtime.power.PowerNetwork;
import com.wormzjl.icheme.runtime.power.PowerPorts;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

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
    private static final Map<ServerLevel, Map<BlockPos, EnergyStorage>> FOREIGN_ENERGY = new WeakHashMap<>();

    private ElectricGameTests() {
    }

    static void registerForeignEnergy(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, entity, side) ->
                level instanceof ServerLevel server ? FOREIGN_ENERGY.getOrDefault(server, Map.of()).get(pos) : null,
                Blocks.COAL_BLOCK);
    }

    private static void placeForeignEnergy(PlantBuilder plant, BlockPos pos, EnergyStorage battery) {
        FOREIGN_ENERGY.computeIfAbsent(plant.level, ignored -> new HashMap<>()).put(pos.immutable(), battery);
        plant.level.setBlock(pos, Blocks.COAL_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(pos);
    }

    private static PhysicalFluidTopology.Position position(net.minecraft.world.level.Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    /** The FE port of {@code vessel}'s terminal box, from its top face. */
    private static IEnergyStorage port(PlantBuilder plant, Vessel vessel) {
        var t = ShapeRasterizer.terminal(vessel.shape());
        if (t == null) throw new IllegalStateException("No terminal box on the " + vessel.shape().kind());
        var at = vessel.origin().offset(t.x(), t.y(), t.z());
        var port = plant.level.getCapability(Capabilities.EnergyStorage.BLOCK, at, Direction.UP);
        if (port == null) throw new IllegalStateException("No FE port at " + at.toShortString());
        return port;
    }

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "electric-motor")
    public static void aMotorDrivenPumpDrainsItsStoreStopsAndRestartsOnARefill(GameTestHelper helper) {
        var plant = AssemblyGameTests.plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] ids = new long[3];
        Vessel[] motor = {null};
        boolean[] feed = {false};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            var pump = plant.place(PlantBuilder.unit(EquipmentShape.Kind.PUMP), 0, 0, 0);
            var suction = PlantBuilder.front(pump, Role.SUCTION);
            var generator = suction.relative(PlantBuilder.out(pump, Role.SUCTION));
            var discharge = PlantBuilder.front(pump, Role.DISCHARGE);
            var tank = discharge.relative(PlantBuilder.out(pump, Role.DISCHARGE));
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
            motor[0] = plant.motor(pump, Motor.ratingFor(2 * shaft) / 1000, duty.poles() == 0 ? 2 : duty.poles());
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
                helper.assertTrue(status.get().equals("RUNNING") && world.view(ids[2]).drive() != null && world.view(ids[2]).drive().status().startsWith("RUNNING"), "Waiting for the restart: " + status.get());
            }).thenExecute(() -> feed[0] = false);
        }
        finish(helper, plant, sequence, failure);
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "electric-cooler")
    public static void anAirCoolerWithoutPowerHoldsNaturalDraughtUntilItsPortIsFilled(GameTestHelper helper) {
        var plant = AssemblyGameTests.plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] side = {0};
        Vessel[] cooler = {null};
        boolean[] feed = {false};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            cooler[0] = plant.place(PlantBuilder.unit(EquipmentShape.Kind.AIR_COOLER), 0, 0, 0);
            // Hot water through the bundle: a supply at 2 bar beyond the inlet's pipe block, a void at the atmosphere
            // beyond the outlet's, so the island has its boundaries and runs.
            var inlet = PlantBuilder.front(cooler[0], Role.PROCESS_INLET);
            var supply = inlet.relative(PlantBuilder.out(cooler[0], Role.PROCESS_INLET));
            var outlet = PlantBuilder.front(cooler[0], Role.PROCESS_OUTLET);
            var sink = outlet.relative(PlantBuilder.out(cooler[0], Role.PROCESS_OUTLET));
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
                helper.assertTrue(view.heat().fans() > .99 && !view.status().contains("NO POWER") && view.power() != null && view.power().draw() > 0,
                        "Waiting for the fans to start on power: " + view.status());
            }).thenExecute(() -> feed[0] = false);
        }
        finish(helper, plant, sequence, failure);
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "electric-generator")
    public static void aGeneratorOnACreativeMotorFillsItsStoreAndGivesFE(GameTestHelper helper) {
        var plant = AssemblyGameTests.plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        Vessel[] generator = {null};
        BlockPos[] shaft = {null};
        int[] given = {0};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            generator[0] = plant.place(PlantBuilder.unit(EquipmentShape.Kind.GENERATOR), 0, 0, 0);
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

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "electric-unloaded-creative")
    public static void aCreativeSourceKeepsAnUnloadedMotorSuppliedUntilItIsBroken(GameTestHelper helper) {
        var plant = remotePlant(helper);
        var level = plant.level;
        var world = plant.world;
        plant.hold();
        var motor = plant.place(EquipmentShape.unit(EquipmentShape.Kind.MOTOR, 1000, 1,
                EquipmentShape.Heading.NORTH, 0), 0, 0, 0);
        var terminal = terminal(motor);
        var source = freeNeighbour(plant, terminal);
        level.setBlock(source, ModBlocks.CREATIVE_POWER.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(source);
        var network = PowerNetwork.of(level.getServer());
        long[] id = {0};
        long[] tick = {0};
        int[] retained = {0};
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            var unit = world.shafts().unitAt(position(level, motor.origin()));
            helper.assertTrue(unit != null, "Waiting for the motor registration");
            int stored = world.electric().stored(unit.id());
            int capacity = world.electric().capacity(unit.id());
            var drive = world.shafts().view(unit.id());
            helper.assertTrue(stored > 0 && stored == capacity && drive.speed() > 0,
                    "Waiting for the adjacent source to start and fill the motor: " + drive.status()
                            + ", " + stored + "/" + capacity + " FE");
            id[0] = unit.id();
        }).thenExecute(plant::release).thenWaitUntil(() -> {
            helper.assertTrue(!level.hasChunkAt(terminal) && !level.hasChunkAt(source),
                    "Waiting for the source and motor chunks to unload");
            tick[0] = world.onlineTick();
        }).thenWaitUntil(() -> {
            helper.assertTrue(world.onlineTick() >= tick[0] + 2 * ElectricStores.CADENCE,
                    "Waiting past two unloaded motor decisions");
            helper.assertTrue(!level.hasChunkAt(terminal) && !level.hasChunkAt(source),
                    "Electrical transport loaded a plant chunk");
            helper.assertTrue(network.supplied(world.shafts().unit(id[0]))
                            && world.shafts().view(id[0]).speed() > 0,
                    "The unloaded motor lost its adjacent IChemE source");
        }).thenExecute(() -> {
            plant.hold();
            retained[0] = world.electric().stored(id[0]);
            level.destroyBlock(source, false);
            helper.assertTrue(!network.supplied(world.shafts().unit(id[0])),
                    "The broken source can still supply its neighbour");
        }).thenWaitUntil(() -> {
            helper.assertTrue(world.shafts().view(id[0]).speed() == 0,
                    "Waiting for the disconnected motor to stop");
            helper.assertTrue(world.electric().stored(id[0]) == retained[0],
                    "Disconnecting the motor discarded buffered FE");
        });
        finish(helper, plant, sequence, new String[1]);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "electric-unloaded-generator")
    public static void anUnloadedGeneratorTransfersItsStoredEnergyToAnUnloadedNeighbour(GameTestHelper helper) {
        var plant = remotePlant(helper);
        var level = plant.level;
        var world = plant.world;
        plant.hold();
        var generator = plant.place(PlantBuilder.unit(EquipmentShape.Kind.GENERATOR), 0, 0, 0);
        var terminal = terminal(generator);
        var sink = freeNeighbour(plant, terminal);
        level.setBlock(sink, FluidPumpStartupGameTests.inlinePump(Direction.EAST), Block.UPDATE_ALL);
        plant.devices.add(sink);
        long sinkId = ((FluidDeviceBlockEntity) level.getBlockEntity(sink)).fluidIdentity();
        long[] sourceId = {0};
        double[] initial = {0};
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            var unit = world.shafts().unitAt(position(level, generator.origin()));
            helper.assertTrue(unit != null && world.electric().port(unit.id()) != null
                            && world.electric().port(sinkId) != null,
                    "Waiting for the two stores");
            sourceId[0] = unit.id();
        }).thenExecute(plant::release).thenWaitUntil(() ->
                helper.assertTrue(!level.hasChunkAt(terminal) && !level.hasChunkAt(sink),
                        "Waiting for both FE terminals to unload")).thenExecute(() -> {
            // Book one finite packet of generated energy after unloading, with no powered shaft to add more.
            world.electric().give(sourceId[0], 1000, world.onlineTick());
            initial[0] = world.electric().view(sourceId[0]).joules();
            helper.assertTrue(initial[0] > 0 && world.electric().view(sinkId).joules() == 0,
                    "The unloaded source is charged and its neighbour starts empty");
        }).thenWaitUntil(() -> {
            double gained = world.electric().view(sinkId).joules();
            helper.assertTrue(gained > 0, "Waiting for FE from the unloaded generator");
            double remaining = world.electric().view(sourceId[0]).joules();
            helper.assertTrue(Math.abs(initial[0] - remaining - gained) < 1e-6,
                    "The unloaded transfer changed the amount of energy");
            helper.assertTrue(!level.hasChunkAt(terminal) && !level.hasChunkAt(sink),
                    "FE delivery loaded a source or sink chunk");
        }).thenExecute(plant::hold);
        finish(helper, plant, sequence, new String[1]);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "electric-unloaded-foreign-source")
    public static void anUnloadedForeignSourceStopsTheMeteredMotorAndLeavesItsCharge(GameTestHelper helper) {
        var plant = remotePlant(helper, 80);
        var level = plant.level;
        var world = plant.world;
        var network = PowerNetwork.of(level.getServer());
        plant.hold();
        var motor = plant.place(EquipmentShape.unit(EquipmentShape.Kind.MOTOR, 1000, 1,
                EquipmentShape.Heading.NORTH, 0), 0, 0, 0);
        var terminal = terminal(motor);
        var battery = new EnergyStorage(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        BlockPos[] source = {null};
        UUID[] sourcePort = {null};
        long[] motorId = {0};
        double[] retained = {0};
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            var holder = (EquipmentShellBlockEntity) level.getBlockEntity(terminal);
            var socket = holder.instruments().powerPort();
            var unit = world.shafts().unitAt(position(level, motor.origin()));
            helper.assertTrue(socket != null && unit != null, "Waiting for the motor and its socket");
            motorId[0] = unit.id();
        }).thenExecute(() -> {
            var holder = (EquipmentShellBlockEntity) level.getBlockEntity(terminal);
            var socket = holder.instruments().powerPort();
            var direction = socket.outward();
            source[0] = socket.cell().relative(direction, 3);
            helper.assertTrue(plant.inside(source[0]) && level.getBlockState(source[0]).isAir(),
                    "No room for the foreign FE source");
            placeForeignEnergy(plant, source[0], battery);
            var external = PowerPorts.external(level, source[0], direction.getOpposite());
            if (external == null) throw new IllegalStateException("The foreign source has no FE capability");
            sourcePort[0] = external.id();
            wireForeignSource(plant, socket, external);
        }).thenWaitUntil(() -> {
            long id = motorId[0];
            var view = world.electric().view(id);
            helper.assertTrue(view != null && view.metered() && world.electric().stored(id) > 0
                            && world.electric().stored(id) == world.electric().capacity(id)
                            && world.shafts().view(id).speed() > 0
                            && battery.getEnergyStored() < battery.getMaxEnergyStored(),
                    "Waiting for the live foreign source to fill and start the motor");
        }).thenExecute(plant::release).thenWaitUntil(() -> {
            helper.assertTrue(!level.hasChunkAt(source[0]), "Waiting for the supplying foreign source to unload");
            var unit = world.shafts().unit(motorId[0]);
            helper.assertTrue(unit != null && !network.supplied(unit),
                    "The unloaded foreign capability is still available to a registered motor");
            retained[0] = world.electric().view(unit.id()).joules();
            helper.assertTrue(retained[0] > 0, "The foreign source left the motor without its buffered energy");
        }).thenWaitUntil(() -> {
            long id = motorId[0];
            helper.assertTrue(!level.hasChunkAt(source[0]) && world.shafts().unit(id) != null,
                    "Source probing loaded its chunk or dropped the motor registration");
            helper.assertTrue(world.shafts().view(id).speed() == 0 && world.electric().view(id).unpowered(),
                    "Waiting for the metered motor's scheduled stop");
            helper.assertTrue(world.electric().view(id).joules() == retained[0],
                    "Losing the foreign source changed the motor's charge");
        }).thenExecute(() -> {
            plant.hold();
            FOREIGN_ENERGY.get(level).remove(source[0]);
            world.controls().facts().removePort(sourcePort[0]);
        });
        finish(helper, plant, sequence, new String[1]);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "electric-unloaded-foreign-receiver")
    public static void aForeignReceiverUnloadsWhileTheCreativeSourceRemainsRegistered(GameTestHelper helper) {
        var plant = remotePlant(helper, 96);
        var level = plant.level;
        plant.hold();
        var source = plant.base;
        var receiver = source.east();
        level.setBlock(source, ModBlocks.CREATIVE_POWER.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(source);
        var battery = new EnergyStorage(Integer.MAX_VALUE, 1, 0);
        placeForeignEnergy(plant, receiver, battery);
        long[] unloaded = {0};
        int[] charged = {0};
        var sequence = helper.startSequence().thenWaitUntil(() ->
                helper.assertTrue(battery.getEnergyStored() > 0, "Waiting for the ordinary FE receiver to charge"))
                .thenExecute(plant::release).thenWaitUntil(() -> {
                    helper.assertTrue(!level.hasChunkAt(receiver), "The FE probe is keeping the receiving chunk loaded");
                    unloaded[0] = plant.world.onlineTick();
                    charged[0] = battery.getEnergyStored();
                }).thenWaitUntil(() -> {
                    helper.assertTrue(plant.world.onlineTick() >= unloaded[0] + ElectricStores.CADENCE,
                            "Waiting past an unloaded power cadence");
                    helper.assertTrue(!level.hasChunkAt(receiver) && battery.getEnergyStored() == charged[0],
                            "The ordinary FE provider received energy after unload");
                    var site = new DataBus.Site(level.dimension().location().toString(), source, 0);
                    helper.assertTrue(plant.world.controls().facts().creativePower().contains(site),
                            "The creative source was removed by chunk unload");
                }).thenExecute(plant::hold).thenWaitUntil(() ->
                    helper.assertTrue(battery.getEnergyStored() > charged[0],
                            "The foreign FE receiver did not resume on reload"))
                .thenExecute(() -> FOREIGN_ENERGY.get(level).remove(receiver));
        finish(helper, plant, sequence, new String[1]);
    }

    private static void wireForeignSource(PlantBuilder plant, DataPort motor, DataPort source) {
        var facts = plant.world.controls().facts();
        facts.port(source);
        var direction = motor.outward();
        var incoming = direction.getOpposite();
        int joints = 1 << direction.ordinal() | 1 << incoming.ordinal();
        for (int step = 0; step < 3; step++) {
            var p = motor.cell().relative(direction, step);
            var geometry = new ConduitGeometry.Key(joints, 0, CableMedium.POWER);
            Map<Direction, UUID> ends = Map.of();
            if (p.equals(motor.cell())) {
                geometry = geometry.connect(motor.face().ordinal(), motor.u(), motor.v());
                ends = Map.of(motor.face(), motor.id());
            } else if (p.equals(source.cell())) {
                geometry = geometry.connect(source.face().ordinal(), source.u(), source.v());
                ends = Map.of(source.face(), source.id());
            }
            plant.level.setBlock(p, ModBlocks.POWER_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(p);
            ((ConduitBlockEntity) plant.level.getBlockEntity(p)).configure(
                    new DataBus.Cable(new DataBus.Site(motor.dimension(), p, 0), geometry, 0, ends));
        }
    }

    private static PlantBuilder remotePlant(GameTestHelper helper) {
        return remotePlant(helper, 64);
    }

    private static PlantBuilder remotePlant(GameTestHelper helper, int offset) {
        var test = helper.absolutePos(BlockPos.ZERO);
        var remote = new BlockPos(((test.getX() >> 4) + offset) * 16 + 8, 80, (test.getZ() >> 4) * 16 + 8);
        return AssemblyGameTests.plant(helper, remote, new BlockPos(-6, -2, -6), new BlockPos(9, 12, 9));
    }

    private static BlockPos terminal(Vessel vessel) {
        var cell = ShapeRasterizer.terminal(vessel.shape());
        if (cell == null) throw new IllegalStateException("The fixture has no power terminal");
        return vessel.origin().offset(cell.x(), cell.y(), cell.z());
    }

    private static BlockPos freeNeighbour(PlantBuilder plant, BlockPos terminal) {
        return Arrays.stream(Direction.values()).map(terminal::relative)
                .filter(pos -> plant.inside(pos) && plant.level.getBlockState(pos).isAir())
                .findFirst().orElseThrow();
    }

    /** Removes the plant and its shaft parts, then passes or fails. */
    private static void finish(GameTestHelper helper, PlantBuilder plant, net.minecraft.gametest.framework.GameTestSequence sequence, String[] failure) {
        sequence.thenExecute(plant::remove).thenWaitUntil(() -> helper.assertTrue(plant.removed() && plant.world.shafts().units().stream()
                        .noneMatch(u -> plant.inside(new BlockPos(u.position().x(), u.position().y(), u.position().z()))), "Waiting for the plant and its shaft parts to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }
}
