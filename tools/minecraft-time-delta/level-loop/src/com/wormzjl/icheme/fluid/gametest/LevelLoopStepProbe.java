package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.ControlService;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

import static com.wormzjl.icheme.fluid.gametest.LevelLoopGameTests.*;

/**
 * W6 probe (batch 2026-11-11-minecraft-time-delta, owner "Yes, check them"): {@link LevelLoopGameTests}'s plant without its
 * ramp, at the review server's factor, to tell whether the time factor causes the feed-step hold. EMPTY: the feed valve's
 * law and FIC-1 at the full feed from an empty drum. STEP: filled at a tenth of the feed to 25 %, then FIC-1 to the full
 * feed in one step. Each with the feed by the sizing rule (two cycles of the drum at this factor) and with the factor-4
 * feed (198.1 kg/s). Measures only: a run logs {@code level_probe} with the island's first hold or "clean" over six
 * cycles after the step, and the book's residual at the loops' start (the setup's recompiles), then removes its plant.
 * Not a gate: attached by level-loop.init.gradle, run by run.sh with {@code -PreviewBatch=level-loop}.
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class LevelLoopStepProbe {
    private LevelLoopStepProbe() {}

    static final double FEED_AT_4 = 198.1;

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-probe-empty-rule")
    public static void emptyRule(GameTestHelper helper) {probe(helper, "empty", false);}

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-probe-step-rule")
    public static void stepRule(GameTestHelper helper) {probe(helper, "step", false);}

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-probe-empty-four")
    public static void emptyFour(GameTestHelper helper) {probe(helper, "empty", true);}

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-probe-step-four")
    public static void stepFour(GameTestHelper helper) {probe(helper, "step", true);}

    static void probe(GameTestHelper helper, String mode, boolean four) {
        if (ReviewBatch.skipped(helper, "level-loop")) return;
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 4, 8)), new BlockPos(-8, -3, -8), new BlockPos(16, 24, 16));
        var state = new State();
        var level = plant.level;
        var world = plant.world;
        var time = world.time();
        String label = mode + (four ? " factor-4 feed" : " rule feed");
        plant.hold();
        var drum = plant.place(EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 2), 0, 6, 0);
        var water = water();
        var feed = line(plant, drum, Role.SIDE, 6, 0);
        var generator = feed.getLast();
        level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(generator);
        for (int k = feed.size() - 2; k >= 0; k--) plant.pipe(feed.get(k));
        var feedValve = feed.get(2);
        var meter = feed.get(4);
        plant.valves.put(feedValve, AssemblyGameTests.Plant.out(drum, Role.SIDE).getOpposite());
        var outlet = line(plant, drum, Role.LIQUID_OUTLET, 2, 6);
        for (int k = 0; k < outlet.size() - 1; k++) plant.pipe(outlet.get(k));
        level.setBlock(outlet.getLast(), ModBlocks.FLUID_VOID.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(outlet.getLast());
        var levelValve = outlet.get(1);
        plant.valves.put(levelValve, AssemblyGameTests.Plant.out(drum, Role.LIQUID_OUTLET));
        var vent = line(plant, drum, Role.VAPOUR_OUTLET, 3, 0);
        for (int k = 0; k < vent.size() - 1; k++) plant.pipe(vent.get(k));
        // -Dicheme.levelLoop.generatorVent=true: the vent is a 1 atm nitrogen generator, which can give gas back to a
        // liquid-full drum (a void only takes gas). Off: the suite's void.
        level.setBlock(vent.getLast(), (Boolean.getBoolean("icheme.levelLoop.generatorVent") ? ModBlocks.FLUID_GENERATOR : ModBlocks.FLUID_VOID).get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(vent.getLast());
        for (var line : List.of(feed, outlet, vent)) world.resizeLine(id(plant, line.get(1)), BORE, x -> com.wormzjl.icheme.science.equipment.PipeSizes.MAX_BORE);
        for (var v : plant.valves.entrySet()) plant.install(v.getKey(), v.getValue());
        long generatorId = id(plant, generator), outletId = id(plant, outlet.getLast()), ventId = id(plant, vent.getLast());
        TankCharge.run(world, generatorId, new FluidDeviceSpec(1, 300, 101_325 - 6 * 9.81 * DENSITY, water));
        // -Dicheme.levelLoop.gasOutlet=true (batch 2026-11-11-drum-operation-holds, the void-column run): the outlet void is
        // charged as 1 atm nitrogen instead of water. Off: the suite's water.
        TankCharge.run(world, outletId, new FluidDeviceSpec(1, 300, 101_325, Boolean.getBoolean("icheme.levelLoop.gasOutlet") ? FluidDeviceSpec.nitrogen(MaterialRuntime.active()).composition() : water));
        TankCharge.run(world, ventId, new FluidDeviceSpec(1, 300, 101_325, FluidDeviceSpec.nitrogen(MaterialRuntime.active()).composition()));
        long feedValveId = id(plant, feedValve), levelValveId = id(plant, levelValve);
        long cadence = ControlService.CADENCE;
        // Option 2's timing (owner "Options 1 and 2"): the level valve's target in each accepted slice of the drum's island, with
        // the slice's start, end and dispatch ticks, logged when it changes; and at each control cadence the online tick and the
        // drum view's committed tick, the reading the level law decides on.
        double[] lastTarget = {Double.NaN};long[] lastDecision = {-1};int[] lines = {0}, nodeLines = {0};
        world.observe((changed, timings) -> {
            state.published(changed, timings);
            if (state.drum == 0 || lines[0] > 120) return;
            for (var island : changed) {
                if (nodeLines[0] >= 12 || island.graph().reservoirs().stream().noneMatch(r -> r.id() == state.drum)) continue;
                var m = timings.get(island.id());if (m == null || !m.accepted()) continue;nodeLines[0]++;
                var b = new StringBuilder();for (var r : island.graph().reservoirs()) b.append(' ').append(r.kind()).append('@').append(fmt(r.elevation())).append('=').append(fmt(r.state().pressure())).append("Pa/").append(fmt(r.state().mass())).append("kg");
                log("probe nodes {} tick={} slice end={}{}", label, world.onlineTick(), m.endTick(), b);
            }
            for (var island : changed) {
                if (island.graph().reservoirs().stream().noneMatch(r -> r.id() == state.drum)) continue;
                var m = timings.get(island.id());
                if (m == null || !m.accepted()) continue;
                var g = island.graph();
                for (var pipe : g.pipes())
                    if (pipe.control() instanceof FlowControl.ControlValve law && g.reservoirs().get(pipe.first()).id() == levelValveId && law.volumeTarget() != lastTarget[0]) {
                        lastTarget[0] = law.volumeTarget();lines[0]++;
                        log("probe timing {} slice start={} end={} dispatched={} levelTarget={} m3/s", label, m.startTick(), m.endTick(), m.dispatchedAtTick(), fmt(law.volumeTarget()));
                    }
            }
        });
        Runnable decisions = () -> {
            long t = world.onlineTick();
            if (state.drum == 0 || t % cadence != 0 || t == lastDecision[0] || lines[0] > 120) return;
            lastDecision[0] = t;lines[0]++;
            var out = world.instrumentPipeReading(levelValveId);var view = world.view(state.drum);
            log("probe timing {} decide tick={} readingTick={} level={} % liquid={} m3 outletReading={} m3/s {} kg/s", label, t, committed(world, state), fmt(state.level(world)),
                    view.state() == null ? "none" : fmt(view.state().phaseVolumes()[0] + view.state().phaseVolumes()[1]), out == null || out.volumeFlow() == null ? "none" : fmt(out.volumeFlow()), out == null ? "none" : fmt(out.massFlow()));
        };
        double[] residual = {Double.NaN, Double.NaN};
        long[] stepAt = {-1};
        boolean empty = mode.equals("empty");
        log("probe setup {} placed, sized, valves queued, charged tick={}", label, world.onlineTick());
        helper.startSequence()
                .thenWaitUntil(until(helper, world, state, 600, "waiting for the valves and the drum", () -> plant.valvesInstalled()
                        && world.committedDevice(feedValveId).map(d -> d.control() instanceof FlowControl.ControlValve).orElse(false)
                        && world.committedDevice(levelValveId).map(d -> d.control() instanceof FlowControl.ControlValve).orElse(false)
                        && InstrumentHosts.at(level, drum.controller()) != null && world.view(InstrumentHosts.at(level, drum.controller()).identity()).tank() != null))
                .thenExecute(step(state, "fitting", () -> {
                    state.drum = InstrumentHosts.at(level, drum.controller()).identity();
                    log("probe setup {} fitting (valve instruments, sensors, panels) tick={}", label, world.onlineTick());
                    for (var v : List.of(feedValve, levelValve)) {
                        world.instruments().legacyInstalled(id(plant, v), Instrument.Type.PRESSURE_VALVE);
                        var e = (FluidDeviceBlockEntity) level.getBlockEntity(v);
                        e.refreshGeometry(e.bore(), true);
                    }
                    state.levelSensor = fit(plant, drum.controller(), Sensor.Kind.LEVEL, ModItems.DIGITAL_LEVEL_METER.get());
                    state.flowSensor = fit(plant, meter, Sensor.Kind.FLOW, ModItems.FLOWMETER.get());
                    state.lic = drum.origin().relative(Direction.EAST, 6);
                    state.fic = state.lic.south(3);
                    state.licBox = ColumnFailureModesReviewWorld.panel(plant, state.lic, Direction.WEST, false);
                    state.ficBox = ColumnFailureModesReviewWorld.panel(plant, state.fic, Direction.WEST, false);
                }))
                .thenWaitUntil(until(helper, world, state, 600, "waiting for the data sockets", () -> ColumnFailureModesReviewWorld.port(plant, drum.origin(), CableMedium.DATA) != null
                        && ColumnFailureModesReviewWorld.pipePort(plant, feedValve) != null && ColumnFailureModesReviewWorld.pipePort(plant, levelValve) != null
                        && ColumnFailureModesReviewWorld.pipePort(plant, meter) != null && state.licBox.dataPort() != null && state.ficBox.dataPort() != null))
                .thenExecute(step(state, "wiring", () -> {
                    List<DataPort> ports = List.of(state.licBox.dataPort(), ColumnFailureModesReviewWorld.port(plant, drum.origin(), CableMedium.DATA),
                            ColumnFailureModesReviewWorld.pipePort(plant, levelValve), ColumnFailureModesReviewWorld.pipePort(plant, feedValve),
                            ColumnFailureModesReviewWorld.pipePort(plant, meter), state.ficBox.dataPort());
                    ColumnFailureModesReviewWorld.lay(plant, ports, CableMedium.DATA);
                    log("probe setup {} cables laid tick={}", label, world.onlineTick());
                    var tank = world.view(state.drum).tank();
                    state.capacity = tank.capacity();
                    state.cycle = time.seconds(cadence);
                    state.feed = four ? FEED_AT_4 : state.capacity / (Integer.getInteger("icheme.levelLoop.cycles", CYCLES) * state.cycle) * DENSITY;
                    state.operator = ControlLoopGameTests.operator(level, state.lic);
                    double first = empty ? state.feed : FILL * state.feed;
                    var old = world.registrations().get(feedValveId);
                    var d = old.device();
                    var law = new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, first, state.flowSensor, 1);
                    world.edit(feedValveId, old.revision(), new PhysicalFluidTopology.Device(d.id(), d.position(), d.kind(), d.facing(), d.geometry(), law, d.ports(), true, d.cutoff(), d.inlineFilter()), old.spec());
                    TankCharge.run(world, generatorId, new FluidDeviceSpec(1, 300, 300_000, water));
                    log("probe setup {} feed law and 3 bar charge tick={}", label, world.onlineTick());
                    edit(helper, world, state, state.fic, state.ficBox, new ControlSettings("FIC-1", true, state.flowSensor, feedValveId, Sensor.Unit.KILOGRAM_PER_SECOND, first, ControlSettings.Action.OUTLET));
                    edit(helper, world, state, state.lic, state.licBox, new ControlSettings("LIC-1", true, state.levelSensor, levelValveId, Sensor.Unit.HEIGHT_PERCENT, FILLED, ControlSettings.Action.OUTLET));
                    if (empty) stepAt[0] = committed(world, state);
                    log("probe {} factor={} capacity={} m3 cycle={} s feed={} kg/s first={} kg/s", label, time.factor(), fmt(state.capacity), fmt(state.cycle), fmt(state.feed), fmt(first));
                }))
                // The book's residual once the setup's edits have compiled (two cadences of the island).
                .thenWaitUntil(until(helper, world, state, 2400, "waiting two cadences", () -> {
                    decisions.run();
                    if (state.settled < 0) state.settled = committed(world, state);
                    if (committed(world, state) < state.settled + 2 * cadence) return false;
                    residual[0] = state.massResidual;
                    residual[1] = state.energyResidual;
                    return true;
                }))
                // STEP: filled to 25 % at a tenth of the feed, then the full feed in one step.
                .thenWaitUntil(until(helper, world, state, 6000, "waiting for 25 %", () -> {
                    decisions.run();
                    if (empty) return true;
                    pace(world, state);
                    if (!(Math.abs(state.level(world) - FILLED) < 2)) return false;
                    edit(helper, world, state, state.fic, state.ficBox, new ControlSettings("FIC-1", true, state.flowSensor, feedValveId, Sensor.Unit.KILOGRAM_PER_SECOND, state.feed, ControlSettings.Action.OUTLET));
                    stepAt[0] = committed(world, state);
                    return true;
                }))
                .thenWaitUntil(until(helper, world, state, 6000, "six cycles after the step", () -> {
                    decisions.run();
                    pace(world, state);
                    double l = state.level(world);
                    if (!Double.isNaN(l)) {state.low = Math.min(state.low, l);state.high = Math.max(state.high, l);}
                    return committed(world, state) >= stepAt[0] + 6 * cadence;
                }))
                .thenExecute(() -> {
                    world.observe(null);
                    if (state.operator != null) state.operator.player.closeContainer();
                    String held = state.held != null ? state.held : state.failure != null && state.failure.contains("held") ? state.failure : null;
                    log("probe result case=\"{}\" factor={} feed={} kg/s held={} failure={} level={} to {} % setupResidual={} mol {} J endResidual={} mol {} J of {} mol through endLevel={} %",
                            label, time.factor(), fmt(state.feed), held == null ? "clean" : held.lines().findFirst().orElse(held), state.failure,
                            fmt(state.low), fmt(state.high), fmt(residual[0]), fmt(residual[1]), fmt(state.massResidual), fmt(state.energyResidual), fmt(state.massThrough), fmt(state.level(world)));
                    plant.remove();
                })
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(plant::release)
                .thenSucceed();
    }
}
