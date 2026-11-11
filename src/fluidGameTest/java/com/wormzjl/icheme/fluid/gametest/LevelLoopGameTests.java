package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.ControlService;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/**
 * An inventory level loop on a drum on the control cadence (batch 2026-11-11-minecraft-time-delta, owner T5): water from a
 * generator through a flow controller (FIC) into a lying 1.5 m by 4 m drum vented to a 1 atm nitrogen void, its liquid out
 * through a level controller's valve (LIC, {@code InventoryControl} once per {@link ControlService#CADENCE}) down to a void.
 * The drum holds {@link #CYCLES} control cycles of the feed: the feed's volume flow is the drum's volume over that many
 * cadences of process time, at the server's time factor. The drum fills at a tenth of the feed to 25 % and holds 25 % on
 * the design feed; then (the start, away from the setpoint) the 50 % setpoint, and it runs 30 cadences, the feed stepped +50 % after 15. Passes when nothing holds, the drum neither
 * empties nor overflows (1 % to 99 % of its height), the level is inside its band (50 ± 10 %) at the end of each half,
 * and the island's mass and energy close against its boundaries. Every published interval of the island is booked by the
 * world's diagnostic observer. The test removes everything it placed. At another factor the same body runs as a tool
 * ({@code tools/minecraft-time-delta/level-loop/}).
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class LevelLoopGameTests {
    private LevelLoopGameTests() {}

    /** Control cycles of the feed the drum holds (owner T5: two); a tool run may set {@code icheme.levelLoop.cycles}. */
    static final int CYCLES = Integer.getInteger("icheme.levelLoop.cycles", 2);
    static final int HALF = 15;
    /** The setpoint and its band, %; the fill's level, % (the start), and its feed, a share of the design feed; empty (1 %) and overflowing (99 %). */
    static final double SETPOINT = 50, BAND = 10, FILLED = 25, FILL = .1, LOW = 1, HIGH = 99;
    static final double DENSITY = 996.5, BORE = .25;
    static final int LAG = 10;
    /** The ramp from the fill's feed to the design feed, shares of the design feed. */
    static final double[] RAMP = {.4, .7, 1};

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-loop")
    public static void aDrumOfTwoCyclesHoldsItsLevelOnTheCadence(GameTestHelper helper) {
        run(helper);
    }

    /**
     * The same loop on a drum with a gas blanketing valve (batch 2026-11-11-gas-blanketing): no vent line; the valve on the
     * drum's top flange, its inlet on a 3 bar nitrogen line, pads and vents the drum's gas. The same fill, ramp and loop.
     * (From empty on the design feed, {@code icheme.levelLoop.direct}, the drum overfills before its level controller reads
     * it, and draining it wide open holds: open, GAS_BLANKET_REVIEW.md.)
     */
    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-loop-blanket")
    public static void aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence(GameTestHelper helper) {
        run(helper, true, false);
    }

    /** One line of the run's record, for the log and the batch's review. */
    static void log(String format, Object... args) {
        IChemE.LOGGER.info("level_loop " + format, args);
    }

    /** The loop as the tool's switches set it: {@code icheme.levelLoop.blanket} (a blanketed drum), {@code icheme.levelLoop.direct} (from empty on the design feed). */
    static void run(GameTestHelper helper) {
        run(helper, Boolean.getBoolean("icheme.levelLoop.blanket"), Boolean.getBoolean("icheme.levelLoop.direct"));
    }

    static void run(GameTestHelper helper, boolean blanket, boolean direct) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 4, 8)), new BlockPos(-8, -3, -8), new BlockPos(16, 24, 16));
        var state = new State();
        state.blanket = blanket;
        state.direct = direct;
        try {
            build(helper, plant, state);
        } catch (RuntimeException e) {
            IChemE.LOGGER.error("level_loop setup failed", e);
            plant.world.observe(null);
            plant.remove();
            plant.release();
            throw new net.minecraft.gametest.framework.GameTestAssertException("level loop setup: " + e.getMessage());
        }
    }

    /** A wait of at most {@code ticks} online ticks: passes once {@code done} holds, or records {@code what} as the run's failure. */
    static Runnable until(GameTestHelper helper, FluidWorldAuthority world, State state, long ticks, String what, java.util.function.BooleanSupplier done) {
        long[] deadline = {-1};
        return () -> {
            if (state.failure != null) return;
            if (deadline[0] < 0) deadline[0] = world.onlineTick() + ticks;
            if (state.held != null) {state.failure = "the drum's island held: " + state.held;return;}
            boolean ok;
            try {
                ok = done.getAsBoolean();
            } catch (RuntimeException e) {
                IChemE.LOGGER.error("level_loop step failed", e);
                state.failure = what + ": " + e;
                return;
            }
            if (ok) return;
            if (world.onlineTick() > deadline[0]) {state.failure = "timed out " + what;return;}
            helper.assertTrue(false, what);
        };
    }

    /** {@code body} unless the run already failed; its exception the run's failure. */
    static Runnable step(State state, String what, Runnable body) {
        return () -> {
            if (state.failure != null) return;
            try {
                body.run();
            } catch (RuntimeException e) {
                IChemE.LOGGER.error("level_loop step failed", e);
                state.failure = what + ": " + e;
            }
        };
    }

    private static void build(GameTestHelper helper, AssemblyGameTests.Plant plant, State state) {
        var level = plant.level;
        var world = plant.world;
        var time = world.time();
        plant.hold();
        var drum = plant.place(EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 2), 0, 6, 0);
        var water = water();
        // The feed: a generator out along the drum's side inlet, its pipes placed from it toward the drum (the meter's run
        // reads its flow forward), the flow valve two cells out, the flowmeter four.
        var feed = line(plant, drum, Role.SIDE, 6, 0);
        var generator = feed.getLast();
        level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(generator);
        for (int k = feed.size() - 2; k >= 0; k--) plant.pipe(feed.get(k));
        var feedValve = feed.get(2);
        var meter = feed.get(4);
        plant.valves.put(feedValve, AssemblyGameTests.Plant.out(drum, Role.SIDE).getOpposite());
        // The liquid out through the level valve, then 6 m down to a void at 1 atm; the vapour to a void at 1 atm.
        var outlet = line(plant, drum, Role.LIQUID_OUTLET, 2, 6);
        for (int k = 0; k < outlet.size() - 1; k++) plant.pipe(outlet.get(k));
        level.setBlock(outlet.getLast(), ModBlocks.FLUID_VOID.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(outlet.getLast());
        var levelValve = outlet.get(1);
        plant.valves.put(levelValve, AssemblyGameTests.Plant.out(drum, Role.LIQUID_OUTLET));
        // The gas side: a vent line to a void at 1 atm, or (blanketed) a nitrogen line of two pipes from a generator to the
        // inlet of the gas blanketing valve on the drum's top flange; the valve is fitted with the sensors.
        var gas = new ArrayList<BlockPos>();
        var valve = state.blanket ? InstrumentHosts.BlanketValve.of(level, drum.origin(), drum.shape()) : null;
        if (state.blanket) {
            if (valve == null) throw new IllegalStateException("The drum has no flange for a gas blanketing valve");
            var cell = valve.port().cell();
            var out = Direction.valueOf(valve.port().face().name());
            for (int k = 1; k <= 3; k++) gas.add(new BlockPos(cell.x(), cell.y(), cell.z()).relative(out, k));
            for (var at : gas) if (!plant.free(at, new HashSet<>(gas))) throw new IllegalStateException("supply line blocked at " + at.toShortString());
            level.setBlock(gas.getLast(), ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(gas.getLast());
            for (int k = gas.size() - 2; k >= 0; k--) plant.pipe(gas.get(k));
        } else {
            gas.addAll(line(plant, drum, Role.VAPOUR_OUTLET, 3, 0));
            for (int k = 0; k < gas.size() - 1; k++) plant.pipe(gas.get(k));
            level.setBlock(gas.getLast(), ModBlocks.FLUID_VOID.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(gas.getLast());
        }
        var vent = gas;
        // Lines of 0.25 m: two cycles of an 8 m3 drum at factor 4 is about 200 kg/s of water.
        for (var line : List.of(feed, outlet, vent)) world.resizeLine(id(plant, line.get(state.blanket && line == vent ? 0 : 1)), BORE, x -> com.wormzjl.icheme.science.equipment.PipeSizes.MAX_BORE);
        for (var v : plant.valves.entrySet()) plant.install(v.getKey(), v.getValue());
        long generatorId = id(plant, generator), outletId = id(plant, outlet.getLast()), ventId = id(plant, vent.getLast());
        // The generator stands 6 m above the drum's top inlet: at 1 atm less its head until the feed valve has its law, so
        // nothing flows through the open valves before.
        TankCharge.run(world, generatorId, new FluidDeviceSpec(1, 300, 101_325 - 6 * 9.81 * DENSITY, water));
        TankCharge.run(world, outletId, new FluidDeviceSpec(1, 300, 101_325, water));
        TankCharge.run(world, ventId, new FluidDeviceSpec(1, 300, state.blanket ? 300_000 : 101_325, FluidDeviceSpec.nitrogen(MaterialRuntime.active()).composition()));
        long feedValveId = id(plant, feedValve), levelValveId = id(plant, levelValve);
        world.observe(state::published);
        long cadence = ControlService.CADENCE;
        helper.startSequence()
                .thenWaitUntil(until(helper, world, state, 600, "waiting for the committed control valves and the drum", () -> plant.valvesInstalled()
                        && world.committedDevice(feedValveId).map(d -> d.control() instanceof FlowControl.ControlValve).orElse(false)
                        && world.committedDevice(levelValveId).map(d -> d.control() instanceof FlowControl.ControlValve).orElse(false)
                        && InstrumentHosts.at(level, drum.controller()) != null && world.view(InstrumentHosts.at(level, drum.controller()).identity()).tank() != null))
                .thenExecute(step(state, "fitting", () -> {
                    state.drum = InstrumentHosts.at(level, drum.controller()).identity();
                    if (state.blanket && !world.blanketValve(state.drum, valve.port())) throw new IllegalStateException("The gas blanketing valve was not fitted");
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
                    var tank = world.view(state.drum).tank();
                    state.capacity = tank.capacity();
                    state.span = tank.levelHeight();
                    state.cycle = time.seconds(cadence);
                    state.feed = state.capacity / (CYCLES * state.cycle) * DENSITY;
                    state.operator = ControlLoopGameTests.operator(level, state.lic);
                    // The feed valve's own law holds the fill's flow, and the generator goes to 3 bar, in one edit before the
                    // loops: no topology edit follows (an edit recompiles the island, and the slice after one, 6 min of process
                    // at 72, is where a raised feed overfilled the drum).
                    var old = world.registrations().get(feedValveId);
                    var d = old.device();
                    // Direct: the design feed and the 50 % setpoint from the empty drum.
                    double first = state.direct ? 1 : FILL, level0 = state.direct ? SETPOINT : FILLED;
                    var law = new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, first * state.feed, state.flowSensor, 1);
                    world.edit(feedValveId, old.revision(), new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Device(d.id(), d.position(), d.kind(), d.facing(), d.geometry(), law, d.ports(), true, d.cutoff(), d.inlineFilter()), old.spec());
                    TankCharge.run(world, generatorId, new FluidDeviceSpec(1, 300, 300_000, water));
                    // The fill: a tenth of the feed to a 25 % level (a placed vessel is empty, and the full feed into a near-empty
                    // drum is the start's own transient, not the loop's).
                    edit(helper, world, state, state.fic, state.ficBox, new ControlSettings("FIC-1", true, state.flowSensor, feedValveId, Sensor.Unit.KILOGRAM_PER_SECOND, first * state.feed, ControlSettings.Action.OUTLET));
                    edit(helper, world, state, state.lic, state.licBox, new ControlSettings("LIC-1", true, state.levelSensor, levelValveId, Sensor.Unit.HEIGHT_PERCENT, level0, ControlSettings.Action.OUTLET));
                    // Two more operators keep LIC-1's and FIC-1's menus open: their views carry each loop's status and reading.
                    state.watcher = ControlLoopGameTests.operator(level, state.lic);
                    ControlPanelMenu.open(state.watcher.player, state.lic);
                    state.ficWatcher = ControlLoopGameTests.operator(level, state.fic);
                    ControlPanelMenu.open(state.ficWatcher.player, state.fic);
                    state.applied = world.onlineTick();
                    log("start factor={} capacity={} m3 height={} m cycle={} s cycles={} feed={} kg/s blanket={} direct={}", time.factor(), fmt(state.capacity), fmt(state.span), fmt(state.cycle), CYCLES, fmt(state.feed), state.blanket, state.direct);
                }))
                // Both loops CONTROLLING on their menus and a cadence more.
                .thenWaitUntil(until(helper, world, state, 2400, "waiting for both loops to control", () -> {
                    if (!state.shown(state.watcher, state.licBox, state.levelSensor).startsWith("CONTROLLING")
                            || !state.shown(state.ficWatcher, state.ficBox, state.flowSensor).startsWith("CONTROLLING")) return false;
                    if (state.controlling < 0) state.controlling = committed(world, state);
                    return committed(world, state) >= state.controlling + cadence;
                }))
                .thenWaitUntil(until(helper, world, state, 6000, "waiting for the drum to stand at " + FILLED + " %", () -> {
                    if (state.direct) return true;
                    pace(world, state);
                    double l = state.level(world);
                    long c = committed(world, state) / cadence;
                    if (c > state.logged) {
                        state.logged = c;
                        var out = world.instrumentPipeReading(levelValveId);
                        var view = world.view(state.drum);
                        log("fill level={} % feed={} kg/s outletReading={} m3/s lic={} island={} residual={} mol {} J phaseVolumes={} solids={} desired={} m3 capacity={} m3", fmt(l), fmt(state.flow(world, meter, plant)),
                                out == null || out.volumeFlow() == null ? "none" : fmt(out.volumeFlow()), state.lic(), view.status(), fmt(state.massResidual), fmt(state.energyResidual),
                                view.state() == null ? "none" : Arrays.toString(view.state().phaseVolumes()), view.state() == null ? "none" : fmt(view.state().solids().volume()),
                                view.tank() == null ? "none" : fmt(view.tank().geometry().volumeAt(FILLED / 100 * view.tank().levelHeight())), view.tank() == null ? "none" : fmt(view.tank().capacity()));
                    }
                    if (!(Math.abs(l - FILLED) < 2)) {state.settled = -1;return false;}
                    if (state.settled < 0) state.settled = committed(world, state);
                    return committed(world, state) >= state.settled + cadence;
                }))
                // The design feed in three steps a cadence apart (a step of the whole feed into the drum at once is a solver
                // hold of its own, not the loop's), the level held at 25 %; then the start: the 50 % setpoint.
                .thenWaitUntil(until(helper, world, state, 2400, "ramping the feed", () -> {
                    if (state.direct) return true;
                    if (committed(world, state) < state.rampAt) return false;
                    if (state.ramp == RAMP.length) return true;
                    edit(helper, world, state, state.fic, state.ficBox, new ControlSettings("FIC-1", true, state.flowSensor, feedValveId, Sensor.Unit.KILOGRAM_PER_SECOND, RAMP[state.ramp++] * state.feed, ControlSettings.Action.OUTLET));
                    state.rampAt = committed(world, state) + cadence;
                    state.settled = -1;
                    return false;
                }))
                .thenWaitUntil(until(helper, world, state, 6000, "waiting for the drum to hold its level on the design feed", () -> {
                    pace(world, state);
                    double l = state.level(world), target = state.direct ? SETPOINT : FILLED, near = state.direct ? BAND / 2 : 2;
                    long c = committed(world, state) / cadence;
                    if (c > state.logged) {
                        state.logged = c;
                        var out = world.instrumentPipeReading(levelValveId);
                        var s = world.view(state.drum).state();
                        log("hold level={} % feed={} kg/s outletReading={} m3/s lic={} island={} residual={} mol {} J pressure={} Pa", fmt(l), fmt(state.flow(world, meter, plant)),
                                out == null || out.volumeFlow() == null ? "none" : fmt(out.volumeFlow()), state.lic(), world.view(state.drum).status(), fmt(state.massResidual), fmt(state.energyResidual), s == null ? "none" : fmt(s.pressure()));
                    }
                    if (!(Math.abs(l - target) < near) || state.flow(world, meter, plant) < .98 * state.feed) {state.settled = -1;return false;}
                    if (state.settled < 0) state.settled = committed(world, state);
                    return committed(world, state) >= state.settled + 2 * cadence;
                }))
                .thenExecute(step(state, "start", () -> {
                    edit(helper, world, state, state.lic, state.licBox, new ControlSettings("LIC-1", true, state.levelSensor, levelValveId, Sensor.Unit.HEIGHT_PERCENT, SETPOINT, ControlSettings.Action.OUTLET));
                    state.start = committed(world, state);
                    state.logged = -1;
                    // The run's book starts here, after the last topology change: the setup's recompiles are not the loop's.
                    state.setupMoles = state.massResidual;
                    state.setupEnergy = state.energyResidual;
                    state.massOrigin = Double.NaN;
                    state.massIn = state.energyIn = state.massThrough = state.energyThrough = state.massResidual = state.energyResidual = 0;
                    state.intervals = 0;
                    log("start level={} % at tick {}", fmt(state.level(world)), state.start);
                }))
                .thenWaitUntil(until(helper, world, state, 2 * HALF * cadence * 4, "running the loop", () -> {
                    pace(world, state);
                    long tick = committed(world, state), cycles = (tick - state.start) / cadence;
                    double l = state.level(world);
                    if (Double.isNaN(l)) {state.unread++;return false;}
                    state.low = Math.min(state.low, l);
                    state.high = Math.max(state.high, l);
                    var gasState = world.view(state.drum).state();
                    if (gasState != null) {state.lowPressure = Math.min(state.lowPressure, gasState.pressure());state.highPressure = Math.max(state.highPressure, gasState.pressure());}
                    if (cycles > state.logged) {
                        state.logged = cycles;
                        var out = world.instrumentPipeReading(levelValveId);
                        log("cycle={} level={} % flow={} kg/s outletReading={} m3/s lag={} lic={} island={} residual={} mol {} J", cycles, fmt(l), fmt(state.flow(world, meter, plant)),
                                out == null || out.volumeFlow() == null ? "none" : fmt(out.volumeFlow()), world.onlineTick() - tick, state.lic(), world.view(state.drum).status(), fmt(state.massResidual), fmt(state.energyResidual));
                    }
                    if (cycles >= HALF && !state.stepped) {
                        state.firstHalf = l;
                        state.stepped = true;
                        edit(helper, world, state, state.fic, state.ficBox, new ControlSettings("FIC-1", true, state.flowSensor, feedValveId, Sensor.Unit.KILOGRAM_PER_SECOND, 1.5 * state.feed, ControlSettings.Action.OUTLET));
                        log("step feed={} kg/s at cycle {}", fmt(1.5 * state.feed), cycles);
                    }
                    state.secondHalf = l;
                    return cycles >= 2 * HALF;
                }))
                .thenExecute(() -> {
                    world.observe(null);
                    if (state.operator != null) state.operator.player.closeContainer();
                    if (state.watcher != null) state.watcher.player.closeContainer();
                    if (state.ficWatcher != null) state.ficWatcher.player.closeContainer();
                    log("result factor={} cycles={} blanket={} direct={} pressure={} to {} Pa low={} % high={} % levelAtStep={} % levelAtEnd={} % held={} failure={} molesResidual={} mol of {} mol energyResidual={} J of {} J intervals={} ticks={} unread={} setupResidual={} mol {} J",
                            time.factor(), CYCLES, state.blanket, state.direct, fmt(state.lowPressure), fmt(state.highPressure), fmt(state.low), fmt(state.high), fmt(state.firstHalf), fmt(state.secondHalf), state.held, state.failure,
                            fmt(state.massResidual), fmt(state.massThrough), fmt(state.energyResidual), fmt(state.energyThrough), state.intervals, world.onlineTick() - state.applied, state.unread, fmt(state.setupMoles), fmt(state.setupEnergy));
                    plant.remove();
                })
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(() -> {
                    plant.release();
                    helper.assertTrue(state.failure == null, "The level loop failed: " + state.failure);
                    helper.assertTrue(state.held == null, "The drum's island held: " + state.held);
                    helper.assertTrue(state.low > LOW && state.high < HIGH, "The drum emptied or overflowed (1 % to 99 %): " + fmt(state.low) + " % to " + fmt(state.high) + " %");
                    helper.assertTrue(Math.abs(state.firstHalf - SETPOINT) <= BAND && Math.abs(state.secondHalf - SETPOINT) <= BAND,
                            "The level was outside its band at the step or the end: " + fmt(state.firstHalf) + " %, " + fmt(state.secondHalf) + " %");
                    helper.assertTrue(state.intervals > 0, "No interval of the drum's island was booked");
                    helper.assertTrue(Math.abs(state.massResidual) <= 1e-6 * state.massThrough, "The moles do not close: " + state.massResidual + " mol of " + state.massThrough);
                    helper.assertTrue(Math.abs(state.energyResidual) <= 1e-6 * state.energyThrough, "The energy does not close: " + state.energyResidual + " J of " + state.energyThrough);
                })
                .thenSucceed();
    }

    /** The drum's committed tick: the process time its island has reached. */
    static long committed(FluidWorldAuthority world, State state) {
        return world.view(state.drum).committedTick();
    }

    /**
     * The GameTest server ticks without waiting, far ahead of the island's workers; the loop then reads samples many
     * cadences old. While the drum's island runs more than one slice and {@link #LAG} ticks behind, each tick waits 20 ms, as a
     * server at 20 TPS with quick workers would (an island publishes at the end of its slice).
     */
    static void pace(FluidWorldAuthority world, State state) {
        if (world.onlineTick() - committed(world, state) <= ControlService.CADENCE + LAG) return;
        // One short wait per tick: the server drains the workers' completions on its next tick.
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The run's state, and its book of the drum island's published intervals. */
    static final class State {
        long drum, start, applied, logged = -1, settled = -1;
        String failure;
        /** Ticks the drum's view had no state (between publications): not samples. */
        int unread;
        UUID levelSensor, flowSensor;
        BlockPos lic, fic;
        ControlPanelBlockEntity licBox, ficBox;
        ControlLoopGameTests.ViewCapture operator, watcher, ficWatcher;
        long controlling = -1, rampAt;
        int ramp;
        /** The book's residual over the setup (valves, lines, specs recompiling the island), mol and J: reported, not checked. */
        double setupMoles, setupEnergy;
        double capacity, span, cycle, feed, low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, firstHalf = Double.NaN, secondHalf = Double.NaN;
        boolean stepped;
        /** The drum has a gas blanketing valve on a nitrogen line, no vent line; the run starts empty on the design feed. */
        boolean blanket, direct;
        /** The drum's pressure over the loop, Pa. */
        double lowPressure = Double.POSITIVE_INFINITY, highPressure = Double.NEGATIVE_INFINITY;
        String held;
        /** The island's inventory (moles, J) less its boundaries' net inflow since the first booked interval, and that at the first. */
        double massResidual, energyResidual, massThrough, energyThrough, massOrigin = Double.NaN, energyOrigin, massIn, energyIn;
        int intervals;
        final Map<Long, Long> seen = new HashMap<>();

        /** The drum's level, % of its height, from its condensed volume. */
        double level(FluidWorldAuthority world) {
            var view = world.view(drum);
            return view.tank() == null || view.state() == null ? Double.NaN : 100 * view.tank().level(view.state()) / view.tank().levelHeight();
        }

        /** LIC-1's status and level reading as its last view showed them. */
        String lic() {
            return shown(watcher, licBox, levelSensor);
        }

        /** The status and the {@code sensor} reading of {@code box} as the last view {@code capture} received showed them. */
        String shown(ControlLoopGameTests.ViewCapture capture, ControlPanelBlockEntity box, UUID sensor) {
            for (int i = capture.views.size() - 1; i >= 0; i--) {
                var view = capture.views.get(i);
                if (!view.panel().equals(box.identity())) continue;
                var rows = view.data().getList("controllers", net.minecraft.nbt.Tag.TAG_COMPOUND);
                if (rows.isEmpty()) return "no row";
                var row = rows.getCompound(0);
                var inputs = row.getList("inputs", net.minecraft.nbt.Tag.TAG_COMPOUND);
                double value = Double.NaN;
                for (int j = 0; j < inputs.size(); j++) if (inputs.getCompound(j).hasUUID("id") && inputs.getCompound(j).getUUID("id").equals(sensor)) value = inputs.getCompound(j).getDouble("value");
                return row.getString("status") + " " + fmt(value);
            }
            return "no view";
        }

        double flow(FluidWorldAuthority world, BlockPos meter, AssemblyGameTests.Plant plant) {
            var r = world.instrumentPipeReading(id(plant, meter));
            return r == null ? Double.NaN : r.massFlow();
        }

        void published(List<IslandCoordinator.Snapshot> changed, Map<Long, IslandCoordinator.Metrics> timings) {
            if (drum == 0) return;
            for (var island : changed) {
                if (island.graph().reservoirs().stream().noneMatch(r -> r.id() == drum)) continue;
                if (island.status().startsWith("HELD") && held == null) held = island.status();
                var timing = timings.get(island.id());
                if (timing == null || seen.getOrDefault(island.id(), -1L) >= timing.sequence()) continue;
                seen.put(island.id(), timing.sequence());
                if (!timing.accepted() || island.lastResult().isEmpty()) continue;
                var result = island.lastResult().get();
                double mass = 0, energy = 0;
                for (var node : island.graph().reservoirs()) {
                    var inventory = node.inventory();
                    if (inventory == null) continue;
                    for (double n : inventory.moles()) mass += n;
                    energy += inventory.internalEnergy();
                }
                if (Double.isNaN(massOrigin)) {massOrigin = mass;energyOrigin = energy;continue;}
                for (var b : result.boundaries()) {
                    double n = 0;
                    for (double x : b.moles()) n += x;
                    massIn += n;
                    energyIn += b.totalEnergyJoule();
                    massThrough += Math.abs(n);
                    energyThrough += Math.abs(b.totalEnergyJoule());
                }
                energyIn += result.pumpWorkJoule();
                massResidual = mass - massOrigin - massIn;
                energyResidual = energy - energyOrigin - energyIn;
                intervals++;
            }
        }
    }

    /** {@code cells} cells straight out of {@code role}'s nozzle of {@code drum}, the nozzle's pipe block first, then {@code drop} cells down. */
    static List<BlockPos> line(AssemblyGameTests.Plant plant, Vessel drum, Role role, int cells, int drop) {
        var from = AssemblyGameTests.Plant.front(drum, role);
        var d = AssemblyGameTests.Plant.out(drum, role);
        var out = new ArrayList<BlockPos>();
        for (int k = 0; k <= cells; k++) out.add(from.relative(d, k));
        var corner = out.getLast();
        for (int k = 1; k <= drop; k++) out.add(corner.below(k));
        for (var at : out) if (!plant.free(at, new HashSet<>(out))) throw new IllegalStateException(role + " line blocked at " + at.toShortString());
        return out;
    }

    static long id(AssemblyGameTests.Plant plant, BlockPos at) {
        return plant.world.at(plant.position(at)).orElseThrow(() -> new IllegalStateException("Nothing registered at " + at.toShortString())).device().id();
    }

    /** One mole of water on the network's list (water last). */
    static double[] water() {
        var n = new double[FluidDeviceSpec.nitrogen(MaterialRuntime.active()).composition().length];
        n[n.length - 1] = 1;
        return n;
    }

    /** A sensor of {@code kind} fitted from {@code item} on {@code host}, as a player fits one; its instance. */
    static UUID fit(AssemblyGameTests.Plant plant, BlockPos host, Sensor.Kind kind, net.minecraft.world.item.Item item) {
        var level = plant.level;
        var h = InstrumentHosts.at(level, host);
        if (h == null) throw new IllegalStateException("No instrument host at " + host.toShortString());
        var fitter = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "LevelLoopFitter"));
        fitter.setGameMode(GameType.CREATIVE);
        fitter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        var why = new ArrayList<String>();
        for (var m : Sensor.mounts(h, kind)) {
            why.add(m.id() + " at " + m.centre() + (InstrumentHosts.clear(level, h, m) ? "" : " blocked by "
                    + BlockPos.betweenClosedStream(m.bounds()).filter(p -> !level.getBlockState(p).canBeReplaced() && !p.equals(host)).map(p -> p.toShortString() + "=" + level.getBlockState(p).getBlock()).toList()));
            var eye = m.centre().add(m.normal().scale(1.6));
            fitter.moveTo(eye.x, eye.y - fitter.getEyeHeight(), eye.z);
            fitter.lookAt(EntityAnchorArgument.Anchor.EYES, m.centre());
            plant.world.instruments().requestSensor(fitter, InteractionHand.MAIN_HAND, kind);
            for (var s : plant.world.instruments().allSensors())
                if (s.key().host() == h.identity() && s.kind() == kind) return s.instance();
        }
        throw new IllegalStateException("The " + kind + " sensor did not fit on " + host.toShortString() + ": mounts " + why);
    }

    static void edit(GameTestHelper helper, FluidWorldAuthority world, State state, BlockPos at, ControlPanelBlockEntity box, ControlSettings settings) {
        var by = state.operator;
        by.player.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + .5);
        var id = box.identity();
        if (!(by.player.containerMenu instanceof ControlPanelMenu m && m.panel().equals(id))) ControlPanelMenu.open(by.player, at);
        world.controls().edit(ControlLoopGameTests.menu(helper, by), id, world.controls().facts().panel(id).revision(), settings);
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4g", v);
    }
}
