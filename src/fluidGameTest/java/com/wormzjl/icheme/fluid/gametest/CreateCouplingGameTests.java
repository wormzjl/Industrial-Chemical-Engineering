package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.world.level.block.ShaftBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Create coupling in the world (batch 2026-11-03-rotating-machinery W6b, form A, decision D40): Create's creative
 * motor at 256 rpm turns the coupling; the coupling drives a constructed gearbox on auto (2950 / 256 = 11.52) and the
 * gearbox a constructed pump casing between a water supply and a tank. The pump delivers at its geared speed and Create's
 * network carries the coupling's stress; then the network's supply is cut to a small one (the creative motor's capacity
 * set to 0.5 su per rpm, 128 su at 256 rpm, 0.19 kW): the train slows (POWER LIMITED) and Create's network is not
 * overstressed. Runs only with Create loaded (W6a's guard); without Create it passes at once. It removes what it placed.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class CreateCouplingGameTests {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("icheme_fluid_test");

    private CreateCouplingGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "create-coupling")
    public static void aCreateCouplingDrivesAPumpThroughAGearboxWithinItsNetworksSpare(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }
        CreateSide.run(helper);
    }

    /** A gearbox at its smallest size, headed {@code heading}. */
    private static EquipmentShape gearbox(EquipmentShape.Heading heading) {
        var d = UnitDesign.of(EquipmentShape.Kind.GEARBOX);
        double size = d.minSize();
        int length = d.defaultLength(size);
        return EquipmentShape.designed(EquipmentShape.Kind.GEARBOX, size, length, heading, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    private static BlockPos at(BlockPos origin, ShapeRasterizer.Cell cell) {
        return origin.offset(cell.x(), cell.y(), cell.z());
    }

    private static PhysicalFluidTopology.Position position(net.minecraft.world.level.Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    /** The only class here that names a Create type; it loads only when the test above calls it, with Create loaded. */
    private static final class CreateSide {
        static void run(GameTestHelper helper) {
            var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(12, 2, 12)), new BlockPos(-16, -2, -16), new BlockPos(28, 10, 28));
            var level = plant.level;
            var world = plant.world;
            String[] failure = {null};
            long[] ids = new long[3];
            BlockPos[] coupling = {null}, motor = {null}, gearOrigin = {null};
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
                // The pump's driven end, a shaft block beyond it.
                ShapeRasterizer.Shaft driven = null;
                for (var s : ShapeRasterizer.shafts(pump.shape())) if (s.role() == EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT) driven = s;
                if (driven == null) throw new IllegalStateException("no driven end on the pump");
                var d = Direction.fromDelta(driven.dx(), driven.dy(), driven.dz());
                var pumpShaft = at(pump.origin(), driven.block());
                shaft(plant, pumpShaft, d.getAxis());
                // The gearbox: headed so its fast end faces the pump across that shaft block.
                EquipmentShape gear = null;
                ShapeRasterizer.Shaft fast = null, slow = null;
                for (var heading : EquipmentShape.Heading.values()) {
                    var shape = gearbox(heading);
                    ShapeRasterizer.Shaft f = null, s = null;
                    for (var e : ShapeRasterizer.shafts(shape))
                        if (e.role() == EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT) f = e;
                        else s = e;
                    if (f != null && s != null && Direction.fromDelta(f.dx(), f.dy(), f.dz()) == d.getOpposite()) {
                        gear = shape;
                        fast = f;
                        slow = s;
                        break;
                    }
                }
                if (gear == null) throw new IllegalStateException("no gearbox heading faces the pump's driven end " + d);
                var origin = pumpShaft.subtract(new BlockPos(fast.block().x(), fast.block().y(), fast.block().z()));
                var gearbox = plant.place(gear, origin.getX() - plant.base.getX(), origin.getY() - plant.base.getY(), origin.getZ() - plant.base.getZ());
                gearOrigin[0] = gearbox.origin();
                // The slow end: a shaft block, the coupling facing away (Create's side out), Create's creative motor beyond it.
                var e = Direction.fromDelta(slow.dx(), slow.dy(), slow.dz());
                var slowShaft = at(gearbox.origin(), slow.block());
                shaft(plant, slowShaft, e.getAxis());
                coupling[0] = slowShaft.relative(e).immutable();
                motor[0] = coupling[0].relative(e).immutable();
                for (var p : new BlockPos[]{coupling[0], motor[0]})
                    if (!level.getBlockState(p).canBeReplaced()) throw new IllegalStateException("no room at " + p.toShortString());
                level.setBlock(coupling[0], com.wormzjl.icheme.compat.create.CreateCompat.COUPLING.get().defaultBlockState()
                        .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, e), Block.UPDATE_ALL);
                plant.devices.add(coupling[0]);
                level.setBlock(motor[0], com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                        .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, e.getOpposite()), Block.UPDATE_ALL);
                plant.devices.add(motor[0]);
                ((com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) level.getBlockEntity(motor[0])).generatedSpeed.setValue(256);
            } catch (RuntimeException ex) {
                failure[0] = "build: " + ex;
            }
            if (failure[0] == null) {
                // Auto is an explicit selection; new constructed gearboxes start at output/input = 4.
                sequence = sequence.thenWaitUntil(() -> helper.assertTrue(world.shafts().unitAt(position(level, gearOrigin[0])) != null,
                        "Waiting for the gearbox registration")).thenExecute(() -> {
                    var unit = world.shafts().unitAt(position(level, gearOrigin[0]));
                    helper.assertTrue(unit.settings().ratio() == 4, "A new gearbox starts with ratio 4");
                    var settings = unit.settings();
                    world.shafts().queue(new ShaftTrains.Configure(unit.id(), new ShaftUnit.Settings(settings.running(), settings.mode(), settings.setPoint(), settings.powerLimit(), 0)));
                });
                // The pump delivers at its geared speed, and Create's network carries the coupling's stress.
                sequence = sequence.thenWaitUntil(() -> {
                    var view = world.view(ids[1]);
                    var unit = world.shafts().unitAt(position(level, coupling[0]));
                    var c = unit == null ? null : world.shafts().coupling(unit.id());
                    helper.assertTrue(view.state() != null && view.state().mass() > .01 && Math.abs(world.shafts().share(ids[2]) - 1) < .02 && c != null && c.impact() > 0
                            && entity(level, coupling[0]).impact() > 0, "Waiting for the pump to deliver at 2950 rpm: " + world.view(ids[2]).status() + ", "
                            + (unit == null ? "no coupling unit" : world.shafts().view(unit.id())) + ", " + c);
                }).thenExecute(() -> {
                    var unit = world.shafts().unitAt(position(level, coupling[0]));
                    var view = world.shafts().view(unit.id());
                    var c = world.shafts().coupling(unit.id());
                    var be = entity(level, coupling[0]);
                    var network = be.getOrCreateNetwork();
                    if (!view.status().equals("RUNNING")) failure[0] = "the coupling is not running: " + view;
                    else if (Math.abs(c.rpm() - 256) > 1e-6) failure[0] = "the engine reads Create at " + c.rpm() + " rpm";
                    else if (Math.abs(be.impact() - c.impact()) > 1e-3 * c.impact()) failure[0] = "Create holds " + be.impact() + " su/rpm, the engine sent " + c.impact();
                    else if (!(network.getActualStressOf(be) > 0) || Math.abs(network.getActualStressOf(be) - be.impact() * 256) > 1e-3 * network.getActualStressOf(be))
                        failure[0] = "the network carries " + network.getActualStressOf(be) + " su of the coupling's " + be.impact() * 256;
                    else {
                        // Power check: stress x W per su is the train's demand.
                        double drawn = network.getActualStressOf(be) * world.shafts().wattsPerStress();
                        if (Math.abs(drawn - view.demand()) > .05 * view.demand()) failure[0] = "the coupling draws " + drawn + " W of the train's " + view.demand();
                    }
                    // The page a player opens now (D41): the replayed view, read from the engine's last step, as the page shows it.
                    if (failure[0] == null) failure[0] = page(helper, world, unit.id(), "RUNNING", network.getActualStressOf(be));
                    LOG.info("create_coupling_test phase=running view={} coupling={} share={} stress={} su", view, c, world.shafts().share(ids[2]), network.getActualStressOf(be));
                    // A small supply: the creative motor's capacity cut to 0.5 su per rpm (128 su at 256 rpm).
                    var source = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) level.getBlockEntity(motor[0]);
                    source.getOrCreateNetwork().updateCapacityFor(source, .5f);
                });
                sequence = sequence.thenWaitUntil(() -> {
                    if (failure[0] != null) return;
                    var unit = world.shafts().unitAt(position(level, coupling[0]));
                    var view = world.shafts().view(unit.id());
                    var be = entity(level, coupling[0]);
                    var network = be.getOrCreateNetwork();
                    helper.assertTrue(view.status().startsWith("POWER LIMITED") && !be.isOverStressed() && network.calculateStress() <= network.calculateCapacity()
                                    && world.shafts().share(ids[2]) > 0 && world.shafts().share(ids[2]) < .95,
                            "Waiting for POWER LIMITED within the small supply: " + view + ", " + world.shafts().coupling(unit.id()) + ", stress " + network.calculateStress() + " of "
                                    + network.calculateCapacity() + (be.isOverStressed() ? " OVERSTRESSED" : ""));
                }).thenIdle(200).thenExecute(() -> {
                    if (failure[0] != null) return;
                    // Held: still limited, Create still not overstressed.
                    var unit = world.shafts().unitAt(position(level, coupling[0]));
                    LOG.info("create_coupling_test phase=limited view={} coupling={} share={} stress={} of {} su", world.shafts().view(unit.id()), world.shafts().coupling(unit.id()),
                            world.shafts().share(ids[2]), entity(level, coupling[0]).getOrCreateNetwork().calculateStress(), entity(level, coupling[0]).getOrCreateNetwork().calculateCapacity());
                    var be = entity(level, coupling[0]);
                    var network = be.getOrCreateNetwork();
                    if (be.isOverStressed() || network.calculateStress() > network.calculateCapacity())
                        failure[0] = "Create's network overstressed: " + network.calculateStress() + " of " + network.calculateCapacity();
                    else failure[0] = page(helper, world, unit.id(), "POWER LIMITED", network.getActualStressOf(be));
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
         * What opening the coupling's page would get (W6b, D41): the view the presentation replays ({@code MachineNetwork.machineView},
         * engine state only), checked against the plant: the status word, Create at 256 rpm, the train's first machine at its geared
         * speed (2950 rpm running, slower limited), the stress drawn as Create's network carries it, within the stress it may take,
         * the shaft power the train's demand; and the page's rows from it. Limited, Create may hold the impact cut to its spare at the
         * moment it landed (within the 1 % spare deadband), so the drawn stress agrees within 2 %. Null when it holds, else the failure.
         */
        private static String page(GameTestHelper helper, com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority world, long unit, String status, double carried) {
            var v = com.wormzjl.icheme.network.MachineNetwork.machineView(world, helper.getLevel(), unit);
            if (v == null || v.coupling() == null || com.wormzjl.icheme.client.gui.common.MachineColumn.Kind.of(v.kind()) != com.wormzjl.icheme.client.gui.common.MachineColumn.Kind.CREATE_COUPLING)
                return "no coupling page view: " + v;
            var c = v.coupling();
            boolean running = status.equals("RUNNING");
            if (!v.status().startsWith(status)) return "the page says " + v.status() + ", not " + status;
            if (Math.abs(c.createSpeed() - 256) > 1e-6) return "the page reads Create at " + c.createSpeed() + " rpm";
            if (running ? Math.abs(c.trainSpeed() / 2950 - 1) > .02 : !(c.trainSpeed() > 0 && c.trainSpeed() < .95 * 2950)) return "the page reads the train at " + c.trainSpeed() + " rpm";
            if (!(c.drawn() > 0) || Math.abs(c.drawn() - carried) > (running ? 1e-3 : .02) * carried) return "the page reads " + c.drawn() + " su drawn, Create's network carries " + carried;
            if (c.drawn() > c.stress() || !(c.spare() >= 0)) return "the page draws " + c.drawn() + " su of " + c.stress() + " su it may take, spare " + c.spare();
            var demand = world.shafts().view(unit).demand();
            if (!(v.shaftPower() > 0) || Math.abs(v.shaftPower() - demand) > 1e-9 * demand) return "the page reads " + v.shaftPower() + " W, the train demands " + demand;
            if (Math.abs(c.wattsPerStress() - world.shafts().wattsPerStress()) > 1e-12) return "the page's stress rate " + c.wattsPerStress();
            var in = com.wormzjl.icheme.client.gui.common.MachineColumn.input(v, true, true, com.wormzjl.icheme.client.gui.common.MachineColumn.Mode.SPEED, (key, args) -> key);
            if (!in.shaft().getFirst().value().equals("256") || in.supply().size() != 2) return "the page's rows: " + in;
            LOG.info("create_coupling_test page status={} create={} train={} drawn={} spare={} power={}", v.status(), c.createSpeed(), c.trainSpeed(), c.drawn(), c.spare(), v.shaftPower());
            return null;
        }

        private static void shaft(AssemblyGameTests.Plant plant, BlockPos at, Direction.Axis axis) {
            if (!plant.level.getBlockState(at).canBeReplaced()) throw new IllegalStateException("no room for a shaft at " + at.toShortString());
            plant.level.setBlock(at, ModBlocks.SHAFT.get().defaultBlockState().setValue(ShaftBlock.AXIS, axis), Block.UPDATE_ALL);
            plant.devices.add(at.immutable());
        }

        private static com.wormzjl.icheme.compat.create.CreateCouplingBlockEntity entity(net.minecraft.world.level.Level level, BlockPos at) {
            return (com.wormzjl.icheme.compat.create.CreateCouplingBlockEntity) level.getBlockEntity(at);
        }
    }
}
