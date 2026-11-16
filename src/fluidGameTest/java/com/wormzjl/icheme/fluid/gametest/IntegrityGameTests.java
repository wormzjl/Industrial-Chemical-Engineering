package com.wormzjl.icheme.fluid.gametest;

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
}
