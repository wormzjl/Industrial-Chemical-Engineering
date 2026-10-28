package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A world to review the heat exchanger batch by hand (batch 2026-10-27-heat-exchanger-design): each heat unit built on
 * water lines from generators to voids and left running, west to east along one row.
 *
 * <ol>
 * <li>An exchanger, hot water (90 °C, 2 bar) in its tubes against cold water (20 °C, 1.5 bar) in its shell, the shell side
 * held at 30 °C by its internal bypass (W3, W4).</li>
 * <li>The same exchanger with a real bypass around its shell side (W8): inlet tee, control valve, outlet tee, the shell held at
 * 30 °C, the bypass line 0.1 m. Its lines are not checked, and its valve carries no flowmeter, so it stands open: cold water
 * runs around the exchanger, whose internal bypass shuts. The reviewer checks the lines, fits the flowmeter and checks again,
 * after which the real valve holds the mixed outlet.</li>
 * <li>A large air cooler on 85 °C water at 2 bar, held at 80 °C by its fans (W5, W4).</li>
 * <li>A vertical fired heater raising 50 °C water at 10 bar to 200 °C steam through its convection then radiant coil, fed at
 * 0.6 kg/s by a pump between a 10 bar generator and a 10 bar void (W6).</li>
 * <li>A kettle of a 1 m bundle: 150 °C water at 6 bar through its tubes into 5 bar at a 0.5 MW duty; its shell fed with 99 °C
 * water from a generator at the atmosphere standing 1 m over the shell's floor, so the level settles there, as on a feed
 * drum's head; its vapour out to the atmosphere through a 0.15 m line (W7).</li>
 * </ol>
 *
 * <p>Not a gate: its namespace runs only in {@code runReviewWorldServer}, which leaves the world in
 * {@code run/review-world/world} to be copied into a client's {@code saves}. After a minute of running the build logs
 * each unit's status line, outlet and duty ({@code review_heat} lines).
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class HeatExchangerReviewWorld {
    private HeatExchangerReviewWorld() {}

    /** The row's units, each {@code SPACING} blocks east of the last. */
    private static final int SPACING = 18;

    /**
     * Owner test probe (OWNER_TEST_REVIEW.md, scratch until the batch's tooling cleanup): {@code -PreviewProbe=true} builds the
     * plant and applies the owner's changes; {@code -PreviewProbe=owner} builds nothing and watches the world already in
     * {@code run/review-probe/world} (a copy of the owner's save).
     */
    static final String PROBE_MODE = System.getProperty("icheme.review.probe", "false");
    private static final boolean PROBE = PROBE_MODE.equals("true");
    static final boolean OWNER = PROBE_MODE.equals("owner");
    /** The probe's watch after the owner's changes: this many reports, 5 s apart. */
    private static final int PROBE_REPORTS = 80;

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "review-heat")
    public static void theHeatUnitsStandRunning(GameTestHelper helper) {
        if (OWNER) {
            watchOwnerWorld(helper);
            return;
        }
        var level = helper.getLevel();
        // West of the column review plant, clear of the test's own structure block, on the ground.
        var corner = helper.absolutePos(new BlockPos(-110, 0, 48));
        level.getChunk(corner.getX() >> 4, corner.getZ() >> 4);
        var base = new BlockPos(corner.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE, corner.getX(), corner.getZ()), corner.getZ());
        var plant = new AssemblyGameTests.Plant(helper, base, new BlockPos(-8, 0, -12), new BlockPos(5 * SPACING, 26, 14));
        var rig = new Rig(plant);
        String[] failure = {null};
        try {
            rig.build();
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            IChemE.LOGGER.error("review_heat build failed", e);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || plant.valvesInstalled(), "Waiting for the valves"))
                .thenIdle(20)
                .thenExecute(() -> {
                    if (failure[0] == null) try {
                        rig.controls();
                    } catch (RuntimeException e) {
                        failure[0] = "controls: " + e.getMessage();
                    }
                })
                .thenIdle(1200)
                .thenExecute(() -> {
                    if (failure[0] == null) rig.report();
                    if (failure[0] == null && PROBE) rig.probe();
                    if (!PROBE) plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenWaitUntil(() -> helper.assertTrue(!PROBE || failure[0] != null || rig.probeDone(), "Probing"))
                .thenExecute(() -> {
                    if (PROBE) plant.release();
                }).thenSucceed();
    }

    /**
     * The owner's world as saved: every heat side, generator, void and pump with its settings once, then their status every
     * 5 s for 150 s. Holds show in the engine's own warnings.
     */
    private static void watchOwnerWorld(GameTestHelper helper) {
        var world = FluidWorldAuthority.find(helper.getLevel().getServer()).orElseThrow();
        FluidRuntimeDiagnostics.ENABLED = true;
        var ids = new java.util.ArrayList<Long>();
        for (var e : world.registrations().entrySet()) {
            var d = e.getValue().device();
            boolean pump = d.control() instanceof FlowControl.Pump;
            if (d.kind() != com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.HEAT_SIDE && d.kind() != com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR
                    && d.kind() != com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.VOID && !pump) continue;
            ids.add(e.getKey());
            var s = e.getValue().spec();
            IChemE.LOGGER.info("review_owner device={} kind={} at={} control={} heat={} spec={}", e.getKey(), d.kind(), d.position(), d.control(), d.heat(),
                    s == null ? "-" : String.format(Locale.ROOT, "%.2f K %.0f Pa", s.temperature(), s.pressure()));
        }
        long[] next = {world.onlineTick() + 20};
        int[] left = {30};
        helper.onEachTick(() -> {
            if (left[0] <= 0 || world.onlineTick() < next[0]) return;
            next[0] += 100;
            left[0]--;
            for (long id : ids) {
                var view = world.view(id);
                var heat = view.heat();
                IChemE.LOGGER.info("review_owner t={} device={} flow={} status={} outlet={} duty_kW={} state={}", world.onlineTick(), id,
                        String.format(Locale.ROOT, "%.4f", view.massFlow()), view.status(),
                        heat == null ? "-" : String.format(Locale.ROOT, "%.2f K", heat.outletTemperature()),
                        heat == null ? "-" : String.format(Locale.ROOT, "%.1f", heat.heatWatt() / 1000),
                        view.state() == null ? "-" : String.format(Locale.ROOT, "%.2f K %.0f Pa %.1f kg", view.state().temperature(), view.state().pressure(), view.state().mass()));
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(left[0] <= 0, "Watching"));
    }

    private static final class Rig {
        final AssemblyGameTests.Plant plant;
        final FluidWorldAuthority world;
        final double[] water = FluidDeviceSpec.water(MaterialRuntime.active()).composition();
        /** The registrations to report, by label. */
        final Map<String, Long> watched = new LinkedHashMap<>();
        Vessel internal, lined, cooler, heater, kettle;
        BlockPos heaterFeed, coolerFeed, heaterVoid;
        Direction pumpFacing;

        Rig(AssemblyGameTests.Plant plant) {
            this.plant = plant;
            world = plant.world;
        }

        static EquipmentShape unit(Kind kind) {
            return AssemblyGameTests.Plant.unit(kind);
        }

        /** A unit of {@code kind} at {@code size} (its main size, m), its length, support and a single bank as designed. */
        static EquipmentShape unit(Kind kind, double size) {
            var d = UnitDesign.of(kind);
            int length = d.defaultLength(size);
            return EquipmentShape.designed(kind, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
        }

        void build() {
            plant.hold();
            internal = plant.place(unit(Kind.EXCHANGER), 0, 3, 0);
            lined = plant.column = plant.place(unit(Kind.EXCHANGER), SPACING, 3, 0);
            cooler = plant.place(unit(Kind.AIR_COOLER, UnitDesign.of(Kind.AIR_COOLER).maxSize()), 2 * SPACING, 1, 0);
            heater = plant.place(unit(Kind.VERTICAL_HEATER), 3 * SPACING, 1, 0);
            kettle = plant.place(unit(Kind.KETTLE, 1), 4 * SPACING + 2, 3, 0);

            // 1. The exchanger on its internal bypass.
            source(internal, Role.TUBE_INLET, 363.15, 200_000);
            sink(internal, Role.TUBE_OUTLET, 101_325);
            source(internal, Role.SHELL_INLET, 293.15, 150_000);
            sink(internal, Role.SHELL_OUTLET, 101_325);
            // 2. The exchanger with a real bypass around its shell side: the feed and product branch off its tees.
            var ends = plant.buildBypass(lined, Role.SHELL_INLET, Role.SHELL_OUTLET, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), ModBlocks.FLUID_VOID.get().defaultBlockState());
            generator(ends[0], 293.15, 150_000);
            sink(ends[1], 101_325);
            // The bypass line at 0.1 m, as in the solver gate (ExchangerCouplingTest): its valve, once metered, throttles
            // a line that can carry the side's whole flow.
            world.resizeLine(id(plant.bypassPipe), .1, d -> PipeSizes.MAX_BORE);
            source(lined, Role.TUBE_INLET, 363.15, 200_000);
            sink(lined, Role.TUBE_OUTLET, 101_325);
            // 3. The air cooler.
            coolerFeed = source(cooler, Role.PROCESS_INLET, 358.15, 200_000);
            sink(cooler, Role.PROCESS_OUTLET, 101_325);
            // 4. The fired heater: a feed pump, the convection coil, then the radiant coil.
            var feed = heaterFeed = line(heater, Role.CONVECTION_INLET, ModBlocks.FLUID_GENERATOR.get().defaultBlockState());
            generator(feed, 323.15, 1_000_000);
            pump(feed, AssemblyGameTests.Plant.front(heater, Role.CONVECTION_INLET), .0006);
            plant.route(AssemblyGameTests.Plant.front(heater, Role.CONVECTION_OUTLET), AssemblyGameTests.Plant.front(heater, Role.RADIANT_INLET));
            heaterVoid = sink(heater, Role.RADIANT_OUTLET, 1_000_000);
            // 5. The kettle: its tubes, its shell's hydrostatic feed and its vapour.
            source(kettle, Role.TUBE_INLET, 423.15, 600_000);
            sink(kettle, Role.TUBE_OUTLET, 500_000);
            generator(headFeed(kettle, 1), 372.15, 101_325);
            sink(kettle, Role.VAPOUR_OUTLET, 101_325);
            // 0.22 kg/s of steam at the atmosphere is 0.36 m³/s: a 0.15 m line carries it at 20 m/s, under 1 kPa.
            world.resizeLine(id(AssemblyGameTests.Plant.front(kettle, Role.VAPOUR_OUTLET)), .15, d -> PipeSizes.MAX_BORE);
            for (var valve : plant.valves.entrySet()) plant.install(valve.getKey(), valve.getValue());
        }

        /** The units' controls, once their registrations have applied. */
        void controls() {
            control(internal, HeatSides.Side.SHELL, new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 303.15, 0, 0));
            control(lined, HeatSides.Side.SHELL, new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 303.15, 0, 0));
            control(cooler, HeatSides.Side.PROCESS, new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 353.15, 0, 1));
            control(heater, HeatSides.Side.CONVECTION, new HeatUnit.Control(HeatUnit.Mode.FIRING, 473.15, 0, 1));
            control(kettle, HeatSides.Side.TUBE, new HeatUnit.Control(HeatUnit.Mode.DUTY, 500_000, 0, 0));
            for (var v : List.of(internal, lined, cooler, heater, kettle)) {
                var ids = EquipmentStructure.sideIdentities(plant.level, v);
                var sides = HeatSides.of(v.shape());
                for (int i = 0; i < ids.length; i++)
                    watched.put(v.shape().kind().name().toLowerCase(Locale.ROOT) + "@" + v.origin().getX() + "." + (i < sides.size() ? sides.get(i).side().name() : "POOL"), ids[i]);
            }
            // The reviewer stands west of the row, facing east along it, at noon.
            var spawn = plant.base.offset(-6, 0, 8);
            plant.level.setDefaultSpawnPos(spawn, -90);
            plant.level.setDayTime(6000);
        }

        void control(Vessel v, HeatSides.Side side, HeatUnit.Control control) {
            var ids = EquipmentStructure.sideIdentities(plant.level, v);
            var sides = HeatSides.of(v.shape());
            for (int i = 0; i < sides.size(); i++) {
                if (sides.get(i).side() != side) continue;
                var r = world.registrations().get(ids[i]);
                if (r == null || r.device().heat() == null) throw new IllegalStateException("No registered " + side + " side on the " + v.shape().kind());
                world.edit(ids[i], r.revision(), r.device().withHeat(r.device().heat().withControl(control)), r.spec());
                return;
            }
            throw new IllegalStateException("No " + side + " side on the " + v.shape().kind());
        }

        /** Each watched registration's status, outlet and duty, and the kettle shell's contents. */
        void report() {
            for (var e : watched.entrySet()) {
                var view = world.view(e.getValue());
                var heat = view.heat();
                IChemE.LOGGER.info("review_heat unit={} status={} outlet={} duty_kW={} state={}", e.getKey(), view.status(),
                        heat == null ? "-" : String.format(Locale.ROOT, "%.2f K", heat.outletTemperature()),
                        heat == null ? "-" : String.format(Locale.ROOT, "%.1f", heat.heatWatt() / 1000),
                        view.state() == null ? "-" : String.format(Locale.ROOT, "%.2f K %.0f Pa %.1f kg", view.state().temperature(), view.state().pressure(), view.state().mass()));
            }
            var valve = world.at(plant.position(plant.bypassValve)).orElseThrow().device();
            IChemE.LOGGER.info("review_heat bypass_valve={} bore={} control={}", plant.bypassValve.toShortString(), valve.geometry().diameter(), valve.control());
            IChemE.LOGGER.info("review_heat status=BUILT spawn={} units={} devices={}", plant.base.offset(-6, 0, 8).toShortString(), watched.size(), plant.devices.size());
        }

        /**
         * A one-block pump on the line from {@code end} (its generator) to {@code front} (the nozzle's pipe block), in place of
         * the line's pipe beside the nozzle's (a device joins only a pipe), its outlet towards the nozzle, at
         * {@code volumeFlow} m³/s and up to 5 bar.
         */
        void pump(BlockPos end, BlockPos front, double volumeFlow) {
            Direction towards = null;
            for (var q : Direction.values()) if (end.relative(q).distManhattan(front) < end.distManhattan(front) && plant.devices.contains(end.relative(q))) towards = q;
            if (towards == null) throw new IllegalStateException("No pipe beside the generator at " + end.toShortString());
            pumpFacing = towards;
            var at = end.relative(towards, 2);
            if (!plant.devices.contains(at) || at.equals(front)) throw new IllegalStateException("No pipe for the pump at " + at.toShortString());
            var state = ModBlocks.FLUID_PUMP.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, towards);
            plant.level.setBlock(at, FluidDeviceBlock.connectedState(state, plant.level, at), Block.UPDATE_ALL);
            long id = id(at);
            var r = world.registrations().get(id);
            world.edit(id, r.revision(), r.device().withControl(new FlowControl.Pump(volumeFlow, 500_000, true)), r.spec());
        }

        /**
         * A kettle's feed from a generator standing {@code level} m over its shell's floor (its pool's node), off to the
         * kettle's west: at the atmosphere, the water runs in until the shell's level stands at the generator's, as from a
         * feed drum's head. Returns the generator's position.
         */
        BlockPos headFeed(Vessel v, int level) {
            var pool = ShapeRasterizer.fluidCells(v.shape()).getLast();
            int y = v.origin().getY() + pool.y() + level;
            var from = AssemblyGameTests.Plant.front(v, Role.FEED);
            int west = v.origin().getX() - 4;
            var at = new BlockPos(west, y, from.getZ());
            var hub = at.relative(Direction.EAST);
            plant.route(from, hub);
            if (!plant.free(at, AssemblyGameTests.Plant.around(hub))) throw new IllegalStateException("No room for the kettle's feed at " + at.toShortString());
            plant.level.setBlock(at, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(at);
            return at;
        }

        /** A line from {@code role}'s nozzle to a generator delivering water at {@code temperature} K and {@code pressure} Pa. */
        BlockPos source(Vessel v, Role role, double temperature, double pressure) {
            var at = line(v, role, ModBlocks.FLUID_GENERATOR.get().defaultBlockState());
            generator(at, temperature, pressure);
            return at;
        }

        /**
         * The owner's settings of 2026-10-28 as found in the owner's save, then a watch: the heater's feed generator at 25 °C
         * and 100 kPa, its void at 100 kPa, its pump at 0.01 m³/s; the air cooler fed 125 °C at 120 kPa (a little steam);
         * every unit, the heater's pump and the generators reported every 5 s.
         */
        void probe() {
            FluidRuntimeDiagnostics.ENABLED = true;
            long pump = id(heaterFeed.relative(pumpFacing, 2));
            watched.put("heater_pump", pump);
            watched.put("heater_feed", id(heaterFeed));
            watched.put("heater_void", id(heaterVoid));
            watched.put("cooler_feed", id(coolerFeed));
            heaterPump = pump;
            // The owner's likely order: the feed first (the void still at 10 bar), then the void and the pump.
            respec(heaterFeed, 298.15, 100_000);
            IChemE.LOGGER.info("review_probe changes=stage 1: heater feed 25 C 100 kPa, void still 10 bar");
            probeLeft = PROBE_REPORTS;
            probeNext = world.onlineTick() + 100;
        }

        int probeLeft;
        long heaterPump;
        long probeNext;

        boolean probeDone() {
            if (probeLeft > 0 && world.onlineTick() >= probeNext) {
                probeNext += 100;
                probeLeft--;
                if (probeLeft == PROBE_REPORTS / 2) {
                    respec(heaterVoid, 298.15, 100_000);
                    var r = world.registrations().get(heaterPump);
                    world.edit(heaterPump, r.revision(), r.device().withControl(new FlowControl.Pump(.01, 500_000, true)), r.spec());
                    IChemE.LOGGER.info("review_probe changes=stage 2: heater void 100 kPa, pump 0.01 m3/s");
                }
                for (var e : watched.entrySet()) {
                    var view = world.view(e.getValue());
                    var heat = view.heat();
                    IChemE.LOGGER.info("review_probe t={} unit={} flow={} status={} outlet={} duty_kW={} fans={} bypass={} burners={} state={}",
                            world.onlineTick(), e.getKey(), String.format(Locale.ROOT, "%.4f", view.massFlow()), view.status(),
                            heat == null ? "-" : String.format(Locale.ROOT, "%.2f K", heat.outletTemperature()),
                            heat == null ? "-" : String.format(Locale.ROOT, "%.1f", heat.heatWatt() / 1000),
                            heat == null || heat.fans() == null ? "-" : String.format(Locale.ROOT, "%.4f", heat.fans()),
                            heat == null || heat.bypass() == null ? "-" : String.format(Locale.ROOT, "%.4f", heat.bypass()),
                            heat == null || heat.burners() == null ? "-" : String.format(Locale.ROOT, "%.4f", heat.burners()),
                            view.state() == null ? "-" : String.format(Locale.ROOT, "%.2f K %.0f Pa %.1f kg", view.state().temperature(), view.state().pressure(), view.state().mass()));
                }
                var c = FluidRuntimeDiagnostics.sample();
                IChemE.LOGGER.info("review_probe t={} couplingWakes={} certificatesIssued={} certificateWakes={}", world.onlineTick(), c.get("couplingWakes"), c.get("certificatesIssued"), c.get("certificateWakes"));
            }
            return probeLeft == 0;
        }

        void respec(BlockPos at, double temperature, double pressure) {
            long id = id(at);
            var r = world.registrations().get(id);
            world.edit(id, r.revision(), r.device(), new FluidDeviceSpec(1, temperature, pressure, water));
        }

        /** A line from {@code role}'s nozzle to a void at {@code pressure} Pa; returns the void's position. */
        BlockPos sink(Vessel v, Role role, double pressure) {
            var at = line(v, role, ModBlocks.FLUID_VOID.get().defaultBlockState());
            sink(at, pressure);
            return at;
        }

        void generator(BlockPos at, double temperature, double pressure) {
            TankCharge.run(world, id(at), new FluidDeviceSpec(1, temperature, pressure, water));
        }

        void sink(BlockPos at, double pressure) {
            long id = id(at);
            var r = world.registrations().get(id);
            world.edit(id, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, pressure, water));
        }

        long id(BlockPos at) {
            return world.at(plant.position(at)).orElseThrow(() -> new IllegalStateException("Nothing registered at " + at.toShortString())).device().id();
        }

        /**
         * A line out of {@code role}'s nozzle: its pipe block, two pipes along the nozzle (square to it where that is
         * taken), then {@code end}. Returns the end's position.
         */
        BlockPos line(Vessel v, Role role, BlockState end) {
            var at = AssemblyGameTests.Plant.front(v, role);
            var d = AssemblyGameTests.Plant.out(v, role);
            var tries = new ArrayList<Direction>(List.of(d));
            for (var q : Direction.values()) if (q.getAxis() != d.getAxis()) tries.add(q);
            for (var q : tries) {
                if (!plant.free(at, AssemblyGameTests.Plant.around(at, at.relative(q)))) break;
                boolean clear = true;
                for (int k = 1; k <= 3 && clear; k++) clear = plant.free(at.relative(q, k), AssemblyGameTests.Plant.around(at.relative(q, k - 1), at.relative(q, k + 1)));
                if (!clear) continue;
                plant.pipe(at);
                for (int k = 1; k <= 2; k++) plant.pipe(at.relative(q, k));
                plant.level.setBlock(at.relative(q, 3), end, Block.UPDATE_ALL);
                plant.devices.add(at.relative(q, 3));
                return at.relative(q, 3);
            }
            throw new IllegalStateException("No room for the " + role + " line of the " + v.shape().kind());
        }
    }
}
