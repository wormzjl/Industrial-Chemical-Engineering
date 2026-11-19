package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.control.ValveSignal;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.ExchangerRelay;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.at;
import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.full;
import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.remote;
import static com.wormzjl.icheme.fluid.gametest.FixtureChunks.ticket;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ExchangerChunkGameTests {
    private ExchangerChunkGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "exchanger-unloaded-edit")
    public static void aRemotePipeEditReleasesTheUnloadedExchangersValve(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = remote(helper);
        var plant = new PlantBuilder(level, base, new BlockPos(-10, -2, -10), new BlockPos(52, 16, 16));
        var dependency = buildBypass(plant, true);
        var remote = new ChunkPos(dependency);
        var valve = plant.world.at(plant.position(plant.bypassValve)).orElseThrow().device().id();
        long shell = plant.side(HeatSides.Side.SHELL);
        var vessel = plant.column;
        var nodes = ShapeRasterizer.fluidCells(vessel.shape()).stream().map(c -> at(vessel.origin(), c)).toList();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(plant.valvesInstalled(), "Waiting for the bypass valve"))
                .thenExecute(() -> setBypass(helper, plant, shell, valve))
                .thenExecute(() -> {
                    checkBypass(helper, plant, shell, valve);
                    helper.assertTrue(plant.entry().codes().containsKey(dependency.asLong()), "The report omitted the remote pipe");
                    helper.assertTrue(nodes.stream().noneMatch(n -> new ChunkPos(n).equals(remote)),
                            "The dependency shares a chunk with a heat node");
                    plant.release();
                })
                .thenWaitUntil(() -> helper.assertTrue(nodes.stream().noneMatch(n -> full(level, n)), "Waiting for both heat nodes to unload"))
                .thenExecute(() -> ticket(level, remote, true))
                .thenWaitUntil(() -> helper.assertTrue(full(level, dependency), "Waiting for just the dependency's chunk"))
                .thenExecute(() -> {
                    helper.assertTrue(nodes.stream().noneMatch(n -> full(level, n)), "Loading the dependency loaded a heat node");
                    helper.assertTrue(ExchangerRelay.Operation.bypass(valve).equals(plant.world.exchangerOperation(shell)),
                            "Unloading the heat nodes dropped the bypass operation");
                    level.removeBlock(dependency, false);
                    plant.devices.remove(dependency);
                    helper.assertTrue(plant.entry().status() == AssemblyStore.Status.NOT_VALIDATED, "Removing the pipe did not revert the report");
                    helper.assertTrue(plant.world.exchangerOperation(shell) == null, "The reverted report still owns the bypass valve");
                    helper.assertTrue(!plant.world.controlReserved(valve), "The valve is still reserved for the exchanger");
                    helper.assertTrue(nodes.stream().noneMatch(n -> full(level, n)), "Reverting the report loaded a heat node");
                    ticket(level, remote, false);
                    plant.hold();
                    plant.remove();
                    plant.release();
                })
                .thenSucceed();
    }

    /** Builds an instrumented bypass. The larger fixture extends the checked inlet branch into a distant chunk. */
    static BlockPos buildBypass(PlantBuilder plant, boolean extend) {
        plant.hold();
        var level = plant.level;
        plant.column = plant.place(PlantBuilder.unit(EquipmentShape.Kind.EXCHANGER), 0, 4, 0);
        var tank = ModBlocks.FLUID_RESERVOIR.get().defaultBlockState();
        var inlet = PlantBuilder.front(plant.column, Role.SHELL_INLET);
        var outlet = PlantBuilder.front(plant.column, Role.SHELL_OUTLET);
        // Shell nozzles point up and down. Run the bypass outside the casting with room above its valve.
        var top = inlet.above(3);
        var bottom = outlet.below(2);
        int east = Math.max(inlet.getX(), outlet.getX()) + 6;
        var path = new ArrayList<>(List.of(inlet));
        appendRun(path, top);
        appendRun(path, new BlockPos(east, top.getY(), top.getZ()));
        appendRun(path, new BlockPos(east, top.getY(), bottom.getZ()));
        appendRun(path, new BlockPos(east, bottom.getY(), bottom.getZ()));
        appendRun(path, bottom);
        appendRun(path, outlet);
        path.forEach(plant::pipe);
        plant.bypassValve = top.east(3);
        plant.valves.put(plant.bypassValve, Direction.EAST);
        var feed = plant.branch(inlet, Direction.WEST, false, tank);
        plant.branch(outlet, Direction.WEST, true, tank);
        var dependency = plant.bypassValve;
        if (extend) {
            var joins = Direction.stream().map(feed::relative).filter(plant.devices::contains).toList();
            level.removeBlock(feed, false);
            plant.devices.remove(feed);
            var terminal = plant.base.east(48).above(6);
            var run = plant.route(feed, terminal.west(), Set.copyOf(joins));
            level.setBlockAndUpdate(terminal, tank);
            plant.devices.add(terminal);
            dependency = run.getLast();
        }
        plant.valves.forEach(plant::install);
        return dependency;
    }

    private static void appendRun(List<BlockPos> path, BlockPos end) {
        var start = path.getLast();
        int length = start.distManhattan(end);
        if (length == 0) return;
        var delta = end.subtract(start);
        var direction = Direction.fromDelta(Integer.signum(delta.getX()), Integer.signum(delta.getY()), Integer.signum(delta.getZ()));
        for (int step = 1; step <= length; step++) path.add(start.relative(direction, step));
    }

    static void setBypass(GameTestHelper helper, PlantBuilder plant, long shell, long valve) {
        var world = plant.world;
        var side = world.registrations().get(shell);
        var heat = side.device().heat().withControl(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 318.15, 0, 0));
        world.edit(shell, side.revision(), side.device().withHeat(heat), side.spec());
        world.instruments().legacyInstalled(valve, Instrument.Type.PRESSURE_VALVE);
        var entity = (FluidDeviceBlockEntity) plant.level.getBlockEntity(plant.bypassValve);
        entity.refreshGeometry(entity.bore(), true);
        try {
            var sensor = LevelLoopGameTests.fit(plant, plant.bypassValve, Sensor.Kind.FLOW, ModItems.FLOWMETER.get(), "valve_sensor");
            var fitting = world.registrations().get(valve);
            var law = ValveSignal.select((FlowControl.ControlValve) fitting.device().control(), sensor, Sensor.Kind.FLOW,
                    FlowControl.ValveSource.MASS_FLOW, 101325, .5, 0);
            world.edit(valve, fitting.revision(), fitting.device().withControl(law), fitting.spec());
        } catch (RuntimeException failure) {
            helper.fail(failure.getMessage());
        }
    }

    static void checkBypass(GameTestHelper helper, PlantBuilder plant, long shell, long valve) {
        var entry = AssemblyService.validate(plant.level, plant.controller());
        helper.assertTrue(entry != null && entry.status() == AssemblyStore.Status.COMPLETE, "Bypass validation failed: " + plant.faults());
        helper.assertTrue(ExchangerRelay.Operation.bypass(valve).equals(plant.world.exchangerOperation(shell)),
                "The validated bypass does not drive its real valve");
    }
}
