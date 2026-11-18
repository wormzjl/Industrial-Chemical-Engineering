package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.Integrity;
import com.wormzjl.icheme.runtime.fluid.MachineHealth;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.integrity.Metallurgy;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Comparator;
import java.util.List;

/**
 * The walls of constructed equipment in the world (batch 2026-11-16-fouling-corrosion, WP2). A 3 m drum holding 2 bar
 * nitrogen, its wall set through by the integrity command's call ({@link Integrity#set}), stands breached at the walls' next
 * cadence and vents to the air; the Equipment Constructor's repair ({@link FluidWorldAuthority#queueStructureRepair}) is
 * refused while it holds pressure and accepted once it is drained: its wall new, its breach closed. A control valve whose trim
 * failed stands open as an unconfigured valve and refuses a kit while flow passes. Each test removes what it placed.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class IntegrityGameTests {
    private IntegrityGameTests() {}

    private static PhysicalFluidTopology.Position position(ServerLevel level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    private static BlockPos cell(BlockPos origin, ShapeRasterizer.Cell c) {
        return origin.offset(c.x(), c.y(), c.z());
    }

    /** Vessel {@code id}'s graph node; null while no island holds it. */
    private static PassiveNetwork.Reservoir node(FluidWorldAuthority world, long id) {
        for (var s : world.diagnosticSnapshots())
            for (var n : s.graph().reservoirs())
                if (n.id() == id && n.kind() == PassiveNetwork.NodeKind.RESERVOIR) return n;
        return null;
    }

    private static double pressure(FluidWorldAuthority world, long id) {
        var n = node(world, id);
        return n == null || n.empty() ? Double.NaN : n.state().pressure();
    }

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "integrity_drum")
    public static void aCorrodedDrumBreachesAndTheConstructorRepairsItOnceDrained(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var origin = helper.absolutePos(new BlockPos(4, 1, 4));
        var node = cell(origin, ShapeRasterizer.fluidCell(shape));
        var nozzle = ShapeRasterizer.ports(shape).stream().max(Comparator.comparingInt(ShapeRasterizer.Port::dy)).orElseThrow();
        var face = Direction.fromDelta(nozzle.dx(), nozzle.dy(), nozzle.dz());
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        long id = world.at(position(level, node)).orElseThrow().device().id();
        // 2 bar nitrogen through the top nozzle from a generator one pipe above it.
        var pipe = cell(origin, nozzle.cell()).relative(face);
        var generator = pipe.relative(face);
        level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        var nitrogen = FluidDeviceSpec.nitrogen(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
        TankCharge.run(world, ((FluidDeviceBlockEntity) level.getBlockEntity(generator)).fluidIdentity(), new FluidDeviceSpec(1, 298.15, 200000, nitrogen));
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        String[] failure = {null};
        Integrity.Outcome[] outcome = {null, null};
        double through = Metallurgy.CARBON_STEEL.allowance();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(pressure(world, id) > 1.6e5, "Waiting for nitrogen in the drum: " + pressure(world, id)))
                .thenExecute(() -> {
                    level.removeBlock(generator, false);
                    level.removeBlock(pipe, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.capture().world().events().isEmpty(), "Waiting for the drum to stand alone"))
                .thenExecute(() -> {
                    // A worn wall, not through: the repair waits for the drum to be drained.
                    if (!world.integrity().set(id, .5 * through, 0, false)) failure[0] = "the wall was not set";
                    if (failure[0] == null && world.integrity().kindOf(id) != Integrity.Kind.VESSEL) failure[0] = "the drum has no vessel wall: " + world.integrity().kindOf(id);
                    world.queueStructureRepair(List.of(id), o -> outcome[0] = o);
                })
                .thenWaitUntil(() -> helper.assertTrue(outcome[0] != null, "Waiting for the first repair's answer"))
                .thenExecute(() -> {
                    if (failure[0] == null && outcome[0] != Integrity.Outcome.NOT_EMPTY) failure[0] = "a pressurised drum was repaired: " + outcome[0];
                    if (failure[0] == null && world.isBreached(id)) failure[0] = "a worn wall breached the drum";
                    // Through: the walls' next cadence breaches it.
                    world.integrity().set(id, through, 0, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.isBreached(id), "Waiting for the wall through to breach the drum"))
                .thenExecute(() -> {
                    var status = world.view(id).status();
                    if (failure[0] == null && !status.contains("LEAKING")) failure[0] = "the drum's line does not say LEAKING: " + status;
                })
                .thenWaitUntil(() -> {
                    var n = node(world, id);
                    helper.assertTrue(n != null && n.inventory().open() && Math.abs(pressure(world, id) - 101325) < 1e4,
                            "Waiting for the breached drum to vent: " + pressure(world, id));
                })
                .thenExecute(() -> world.queueStructureRepair(List.of(id), o -> outcome[1] = o))
                .thenWaitUntil(() -> helper.assertTrue(outcome[1] != null, "Waiting for the second repair's answer"))
                .thenExecute(() -> {
                    if (failure[0] == null && outcome[1] != Integrity.Outcome.REPAIRED) failure[0] = "the drained drum was not repaired: " + outcome[1];
                    var wall = world.integrity().wall(id);
                    if (failure[0] == null && (wall == null || wall.loss() != 0)) failure[0] = "the wall is not new: " + wall;
                })
                .thenWaitUntil(() -> {
                    var n = node(world, id);
                    helper.assertTrue(!world.isBreached(id) && n != null && !n.inventory().open(), "Waiting for the repaired drum to close");
                })
                .thenExecute(() -> EquipmentTestCleanup.remove(level, node))
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, node)).isEmpty() && world.structure(position(level, origin)) == null
                        && world.integrity().wall(id) == null, "Waiting for the drum and its record to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /** A structure's material of construction is its devices': a stainless drum's vessel names it and takes its allowance. */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "integrity_material")
    public static void aStainlessDrumsVesselIsStainless(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0).withMaterial(Metallurgy.STAINLESS_STEEL);
        var origin = helper.absolutePos(new BlockPos(4, 1, 4));
        var node = cell(origin, ShapeRasterizer.fluidCell(shape));
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        long id = world.at(position(level, node)).orElseThrow().device().id();
        String[] failure = {null};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.capture().world().events().isEmpty() && world.integrity().wall(id) != null, "Waiting for the drum and its wall"))
                .thenExecute(() -> {
                    var material = world.registrations().get(id).spec().metallurgy();
                    if (material != Metallurgy.STAINLESS_STEEL) failure[0] = "the drum's vessel is of " + material;
                    var wall = world.integrity().wall(id);
                    if (failure[0] == null && wall.allowance() != Metallurgy.STAINLESS_STEEL.allowance()) failure[0] = "the wall's allowance is " + wall.allowance();
                    EquipmentTestCleanup.remove(level, node);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, node)).isEmpty() && world.structure(position(level, origin)) == null,
                        "Waiting for the drum to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /**
     * The constructor's Repair (WP4) through the server's own path: the structure's devices at its nodes
     * ({@code EquipmentRepair.repair}), one queued event, its answer at the tick drain. A weeping wall marks the drum's shell
     * cell for the clients at the walls' cadence, its state reads the wall left and WEEPING, and the repair clears both.
     */
    @GameTest(template = "empty", timeoutTicks = 1600, batch = "integrity_repair_key")
    public static void theConstructorsRepairPathMendsAWeepingDrum(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape = EquipmentShape.drum(3, 3, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 0);
        var origin = helper.absolutePos(new BlockPos(4, 1, 4));
        var node = cell(origin, ShapeRasterizer.fluidCell(shape));
        var vessel = new EquipmentStructure.Vessel(origin, shape);
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        long id = world.at(position(level, node)).orElseThrow().device().id();
        String[] failure = {null};
        Integrity.Outcome[] outcome = {null};
        boolean[] answeredInline = {false};
        double allowance = Metallurgy.CARBON_STEEL.allowance();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.capture().world().events().isEmpty() && world.integrity().wall(id) != null, "Waiting for the drum and its wall"))
                .thenExecute(() -> world.integrity().set(id, .95 * allowance, 0, false))
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockEntity(node) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell && shell.weeping(),
                        "Waiting for the walls' cadence to mark the weeping drum"))
                .thenExecute(() -> {
                    var state = com.wormzjl.icheme.world.level.block.EquipmentRepair.state(world, level, vessel);
                    if (Math.abs(state.used() - .95f) > 1e-3 || !state.words().equals("WEEPING")) failure[0] = "the state reads " + state;
                    com.wormzjl.icheme.world.level.block.EquipmentRepair.repair(world, level, vessel, o -> outcome[0] = o);
                    answeredInline[0] = outcome[0] != null;
                })
                .thenWaitUntil(() -> helper.assertTrue(outcome[0] != null, "Waiting for the repair's answer"))
                .thenExecute(() -> {
                    if (failure[0] == null && answeredInline[0]) failure[0] = "the repair answered before the engine's drain";
                    if (failure[0] == null && outcome[0] != Integrity.Outcome.REPAIRED) failure[0] = "the drum was not repaired: " + outcome[0];
                    var wall = world.integrity().wall(id);
                    if (failure[0] == null && (wall == null || wall.loss() != 0)) failure[0] = "the wall is not new: " + wall;
                    if (failure[0] == null && level.getBlockEntity(node) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell && shell.weeping())
                        failure[0] = "the repaired drum still weeps";
                    var state = com.wormzjl.icheme.world.level.block.EquipmentRepair.state(world, level, vessel);
                    if (failure[0] == null && (state.used() != 0 || !state.words().isEmpty())) failure[0] = "the repaired state reads " + state;
                    EquipmentTestCleanup.remove(level, node);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, node)).isEmpty() && world.structure(position(level, origin)) == null
                        && world.integrity().wall(id) == null, "Waiting for the drum and its record to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /** The valve's connection in its island's graph, or null. */
    private static PassiveNetwork.Pipe valvePipe(FluidWorldAuthority world, long valve) {
        for (var s : world.diagnosticSnapshots()) {
            var g = s.graph();
            for (var p : g.pipes()) if (g.reservoirs().get(p.first()).id() == valve && p.control() instanceof FlowControl.ControlValve) return p;
        }
        return null;
    }

    /** The valve's connection's mass flow over its island's last committed slice, kg/s; 0 for none. */
    private static double flow(FluidWorldAuthority world, long valve) {
        for (var s : world.diagnosticSnapshots()) {
            var r = s.lastResult().orElse(null);
            if (r == null) continue;
            var g = r.graph();
            for (int e = 0; e < g.pipes().size(); e++) {
                var p = g.pipes().get(e);
                if (g.reservoirs().get(p.first()).id() == valve && p.control() instanceof FlowControl.ControlValve) return Math.abs(r.averageMassFlows()[e]);
            }
        }
        return 0;
    }

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "integrity_trim")
    public static void aFailedTrimStandsOpenAndRefusesAKitWhileFlowPasses(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var source = helper.absolutePos(new BlockPos(0, 1, 0));
        var inlet = source.east();
        var valve = inlet.east();
        var outlet = valve.east();
        var sink = outlet.east();
        var placed = List.of(source, inlet, valve, outlet, sink);
        level.setBlock(source, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        for (var at : List.of(inlet, valve, outlet)) level.setBlock(at, ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.EAST), 3);
        level.setBlock(sink, ModBlocks.FLUID_VOID.get().defaultBlockState(), 3);
        long sourceId = ((FluidDeviceBlockEntity) level.getBlockEntity(source)).fluidIdentity();
        long valveId = ((FluidDeviceBlockEntity) level.getBlockEntity(valve)).fluidIdentity();
        var nitrogen = FluidDeviceSpec.nitrogen(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
        TankCharge.run(world, sourceId, new FluidDeviceSpec(1, 298.15, 200_000, nitrogen));
        helper.assertTrue(world.installPipePressureValve(valveId, PhysicalFluidTopology.Direction.EAST), "The control valve was not queued");
        var own = new FlowControl.ControlValve(FlowControl.ValveSource.PRESSURE, 150_000, null, 7);
        String[] failure = {null};
        MachineHealth.Outcome[] kit = {null};
        boolean[] configured = {false};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.committedDevice(valveId).map(d -> d.control() instanceof FlowControl.ControlValve).orElse(false),
                        "Waiting for the committed control valve"))
                .thenExecute(() -> {
                    var r = world.registrations().get(valveId);
                    configured[0] = world.edit(valveId, r.revision(), r.device().withControl(own), r.spec()) != null;
                    if (!configured[0]) failure[0] = "the valve's settings were not queued";
                })
                .thenWaitUntil(() -> {
                    var p = valvePipe(world, valveId);
                    helper.assertTrue(p != null && own.equals(p.control()) && MachineHealth.trim(world.registrations().get(valveId)), "Waiting for the configured valve: " + (p == null ? null : p.control()));
                })
                .thenExecute(() -> world.health().breach(valveId))
                .thenWaitUntil(() -> {
                    var p = valvePipe(world, valveId);
                    helper.assertTrue(world.health().failed(valveId) && p != null && FlowControl.ControlValve.unconfigured().equals(p.control()),
                            "Waiting for the failed trim to stand open: " + (p == null ? null : p.control()));
                })
                .thenExecute(() -> {
                    var status = world.view(valveId).status();
                    if (failure[0] == null && !status.contains("TRIM FAILED")) failure[0] = "the valve's line does not say TRIM FAILED: " + status;
                    if (failure[0] == null && !own.equals(world.registrations().get(valveId).device().control())) failure[0] = "the failure changed the valve's settings";
                })
                .thenWaitUntil(() -> helper.assertTrue(flow(world, valveId) > 1e-6, "Waiting for flow through the open valve: " + flow(world, valveId)))
                .thenExecute(() -> world.health().queue(new MachineHealth.Repair(valveId, () -> true, o -> kit[0] = o)))
                .thenWaitUntil(() -> helper.assertTrue(kit[0] != null, "Waiting for the kit's answer"))
                .thenExecute(() -> {
                    if (failure[0] == null && kit[0] != MachineHealth.Outcome.NOT_IDLE) failure[0] = "a kit was accepted while flow passed: " + kit[0];
                    for (var at : placed) level.removeBlock(at, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(java.util.stream.LongStream.of(sourceId, valveId).noneMatch(world.registrations()::containsKey), "Waiting for the line to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    /** Heat side {@code side}'s compiled passage, or null while no island holds it. */
    private static com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Pipe passage(FluidWorldAuthority world, long side) {
        long id = PhysicalFluidTopology.sideIdentity(side);
        for (var s : world.diagnosticSnapshots())
            for (var p : s.graph().pipes()) if (p.id() == id) return p;
        return null;
    }

    /**
     * A heat side's fouling deposit takes effect (batch 2026-11-16-fouling-corrosion, WP5): hot water through an air cooler at
     * natural draught (no power). The deposit set at its cap (0.002 m²K/W) recompiles the side's passage 4 mm narrower with
     * its process films behind the deposit, its status line says FOULED and it moves less heat. Its page's Clean, refused while
     * water flows, is accepted once the supply and the drain are gone; refed, the clean passage moves its old heat again. Its
     * page reads the bank's UA (decision D16): all of its clean UA while clean, less behind the deposit.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "integrity_fouling")
    public static void aFouledCoolerMovesLessHeatUntilItIsCleaned(GameTestHelper helper) {
        var plant = AssemblyGameTests.plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(16, 10, 16));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] side = {0};
        double[] duty = {0, 0, 0}, bore = {0};
        Integrity.Outcome[] outcome = {null, null};
        BlockPos[] ends = new BlockPos[2];
        var water = FluidDeviceSpec.water(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
        Runnable feed = () -> {
            level.setBlock(ends[0], ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            level.setBlock(ends[1], ModBlocks.FLUID_VOID.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            TankCharge.run(world, ((FluidDeviceBlockEntity) level.getBlockEntity(ends[0])).fluidIdentity(), new FluidDeviceSpec(1, 358.15, 2e5, water));
            long sink = ((FluidDeviceBlockEntity) level.getBlockEntity(ends[1])).fluidIdentity();
            var r = world.registrations().get(sink);
            world.edit(sink, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, 101_325, water));
        };
        var sequence = helper.startSequence();
        try {
            plant.hold();
            var cooler = plant.place(PlantBuilder.unit(EquipmentShape.Kind.AIR_COOLER), 0, 0, 0);
            var inlet = PlantBuilder.front(cooler, EquipmentShape.Role.PROCESS_INLET);
            var outlet = PlantBuilder.front(cooler, EquipmentShape.Role.PROCESS_OUTLET);
            ends[0] = inlet.relative(PlantBuilder.out(cooler, EquipmentShape.Role.PROCESS_INLET)).immutable();
            ends[1] = outlet.relative(PlantBuilder.out(cooler, EquipmentShape.Role.PROCESS_OUTLET)).immutable();
            plant.pipe(inlet);
            plant.pipe(outlet);
            plant.devices.add(ends[0]);
            plant.devices.add(ends[1]);
            feed.run();
            side[0] = EquipmentStructure.sideIdentities(level, cooler)[0];
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        if (failure[0] == null) sequence = sequence
                .thenWaitUntil(() -> {
                    var view = world.view(side[0]);
                    helper.assertTrue(view.heat() != null && Math.abs(view.heat().heatWatt()) > 0 && passage(world, side[0]) != null, "Waiting for the cooler to run");
                })
                .thenIdle(600)
                .thenExecute(() -> {
                    duty[0] = Math.abs(world.view(side[0]).heat().heatWatt());
                    bore[0] = passage(world, side[0]).sections().getFirst().diameter();
                    var view = world.view(side[0]);
                    // Hot water lays down its deposit from the start, far below one step of the graph's (5e-5 m²K/W since D14) in these few minutes.
                    if (view.heat().fouling() == null || !(view.heat().fouling() < com.wormzjl.icheme.science.integrity.FoulingLaw.STEP)) failure[0] = "a clean side's deposit reads " + view.heat().fouling();
                    if (failure[0] == null && !(view.heat().ua() != null && view.heat().uaClean() != null && view.heat().ua() > 0 && view.heat().ua().doubleValue() == view.heat().uaClean().doubleValue()))
                        failure[0] = "a clean bank reads UA " + view.heat().ua() + " against its clean " + view.heat().uaClean();
                    if (!world.integrity().set(side[0], 0, com.wormzjl.icheme.science.integrity.FoulingLaw.CAP, false)) failure[0] = "the deposit was not set";
                })
                .thenWaitUntil(() -> {
                    var p = passage(world, side[0]);
                    // 4 mm at the cap; the deposit eases towards water's asymptote, so a step or two less may have compiled since.
                    helper.assertTrue(p != null && p.sections().getFirst().diameter() < bore[0] - .0035, "Waiting for the fouled passage to compile");
                })
                .thenIdle(600)
                .thenExecute(() -> {
                    var view = world.view(side[0]);
                    duty[1] = Math.abs(view.heat().heatWatt());
                    if (failure[0] == null && !(duty[1] < .97 * duty[0])) failure[0] = "the fouled cooler moved " + duty[1] + " W against " + duty[0] + " W clean";
                    if (failure[0] == null && !view.status().contains("FOULED")) failure[0] = "the status line does not say FOULED: " + view.status();
                    if (failure[0] == null && !(view.heat().fouling() >= com.wormzjl.icheme.science.integrity.FoulingLaw.FOULED && view.heat().ua() != null && view.heat().uaClean() != null
                            && view.heat().ua() / view.heat().uaClean() < 1))
                        failure[0] = "deposit " + view.heat().fouling() + ", UA " + view.heat().ua() + " of clean " + view.heat().uaClean();
                    var air = ((com.wormzjl.icheme.science.fluid.network.AirCooled) passage(world, side[0]).heat()).air();
                    // 2000 W/m²K behind the cap's 0.002 is 400; behind 0.001 (FOULED's threshold before D14, now 0.0005), 667.
                    if (failure[0] == null && !(air.process().liquid() >= 400 - 1e-9 && air.process().liquid() < 2000 / 3.0)) failure[0] = "the fouled liquid film is " + air.process().liquid();
                    world.queueUnitClean(side[0], o -> outcome[0] = o);
                })
                .thenWaitUntil(() -> helper.assertTrue(outcome[0] != null, "Waiting for the first cleaning's answer"))
                .thenExecute(() -> {
                    if (failure[0] == null && outcome[0] != Integrity.Outcome.NOT_IDLE) failure[0] = "a cooler cleaned while water flows: " + outcome[0];
                    level.removeBlock(ends[0], false);
                    level.removeBlock(ends[1], false);
                })
                .thenWaitUntil(() -> {
                    // Asked again at each wait until the passage stands idle; each ask is one queued event.
                    if (outcome[1] == null) world.queueUnitClean(side[0], o -> outcome[1] = o);
                    else if (outcome[1] == Integrity.Outcome.NOT_IDLE) outcome[1] = null;
                    helper.assertTrue(outcome[1] == Integrity.Outcome.CLEANED || outcome[1] != null && outcome[1] != Integrity.Outcome.NOT_IDLE,
                            "Waiting for the idle cooler to be cleaned: " + outcome[1]);
                })
                .thenExecute(() -> {
                    if (failure[0] == null && outcome[1] != Integrity.Outcome.CLEANED) failure[0] = "the idle cooler was not cleaned: " + outcome[1];
                    if (failure[0] == null && !(world.integrity().fouling(side[0]) < com.wormzjl.icheme.science.integrity.FoulingLaw.STEP)) failure[0] = "a deposit is left: " + world.integrity().fouling(side[0]);
                    feed.run();
                })
                .thenWaitUntil(() -> {
                    var p = passage(world, side[0]);
                    helper.assertTrue(p != null && p.sections().getFirst().diameter() == bore[0] && world.view(side[0]).heat() != null
                            && Math.abs(world.view(side[0]).heat().heatWatt()) > 0, "Waiting for the clean passage to run again");
                })
                .thenIdle(600)
                .thenExecute(() -> {
                    duty[2] = Math.abs(world.view(side[0]).heat().heatWatt());
                    if (failure[0] == null && Math.abs(duty[2] - duty[0]) > .03 * duty[0]) failure[0] = "the cleaned cooler moved " + duty[2] + " W against " + duty[0] + " W before";
                    if (failure[0] == null && world.view(side[0]).status().contains("FOULED")) failure[0] = "the cleaned cooler still says FOULED";
                });
        sequence.thenExecute(plant::remove)
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }
}
