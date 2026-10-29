package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * A world to try the column's Operation tab in by hand (batch 2026-10-28-column-operation): the default column template
 * (Tia Juana Light: 40 trays, three side draws, three pumparounds, sump steam) at a sixteenth of its feed on a quarter of
 * its column area, a 2 m column at the template's own tray loading, built with a water-cooled condenser (C6b), its
 * receiver, a reflux pump and each pumparound's pump and exchanger. The feed and the steam come from generators, the
 * condenser's water from a generator into a void; every product line ends at a void at the atmosphere. The build
 * validates the plant complete, starts the column and leaves it running.
 *
 * <p>Not a gate: its namespace runs only in {@code runReviewWorldServer}, which leaves the world in
 * {@code run/review-world/world} to be copied into a client's {@code saves}.
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ColumnOperationReviewWorld {
    private ColumnOperationReviewWorld() {}

    /** The share of the template's feed the column takes; its area takes the same share, so each tray carries the template's loading. */
    static final double SCALE = 1 / 16.0;
    /** The supply pressure of the feed, the steam and the cooling water, Pa; the atmosphere the products leave to. */
    static final double SUPPLY = 500_000, WATER = 400_000, ATMOSPHERE = 101_325;
    static final ColumnComponents.Choices CHOICES = new ColumnComponents.Choices(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD,
            List.of(ColumnComponents.Pump.STANDARD, ColumnComponents.Pump.STANDARD, ColumnComponents.Pump.STANDARD));
    /** The condenser's shell size, m: larger than the least exchanger, for the overhead's duty. */
    static final double CONDENSER_SIZE = 1.5;
    /** The feed's and the steam's line bores, m (their generators' too). */
    static final double FEED_BORE = .25, STEAM_BORE = .15;
    /**
     * The reports of the running column: their number and the online ticks between them. A GameTest server runs its ticks as
     * fast as it can, far ahead of the solves a cycle and its islands wait for (plan section 9), and an island behind the
     * clock plans from the column's past: from the start on, the test holds the server to 20 ticks a second, as a game runs.
     */
    static final int REPORTS = 4, REPORT_TICKS = 600;
    static final long PACE_MILLIS = 50;

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = "review-operation")
    public static void aCrudeColumnRuns(GameTestHelper helper) {
        var level = helper.getLevel();
        // North of the column review plant, clear of the test's own structure block, on the ground.
        var corner = helper.absolutePos(new BlockPos(48, 0, -64));
        level.getChunk(corner.getX() >> 4, corner.getZ() >> 4);
        var base = new BlockPos(corner.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE, corner.getX(), corner.getZ()), corner.getZ());
        var plant = new AssemblyGameTests.Plant(helper, base, new BlockPos(-34, 0, -32), new BlockPos(24, 44, 36));
        var rig = new Rig(plant);
        String[] failure = {null};
        try {
            rig.build();
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            IChemE.LOGGER.error("review_operation build failed", e);
        }
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || plant.valvesInstalled(), "Waiting for the valves"))
                .thenIdle(20)
                .thenExecute(() -> {
                    if (failure[0] == null) rig.start(failure);
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.paced() && rig.past(), "Waiting for the first solve"));
        for (int i = 1; i <= REPORTS; i++) {
            int n = i;
            int[] ticks = {0};
            sequence = sequence.thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.paced() && ++ticks[0] >= REPORT_TICKS, "Waiting for the column"))
                    .thenExecute(() -> {
                        if (failure[0] == null) rig.report(n);
                    });
        }
        sequence.thenExecute(() -> {
            plant.release();
            if (failure[0] != null) helper.fail(failure[0]);
        }).thenSucceed();
    }

    /** The default template at {@code scale} of its feed, its draws, steam and pumparound duties, its column area in step. */
    static V3ColumnInput scaled(V3ColumnInput in, double scale) {
        double[] feed = in.feedComponentMolarFlowsMolPerSecond().clone();
        for (int c = 0; c < feed.length; c++) feed[c] *= scale;
        var draws = in.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * scale)).toList();
        var steam = in.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * scale, s.temperatureKelvin())).toList();
        var pumparounds = in.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * scale, p.split())).toList();
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), feed, in.feedTemperatureKelvin(), in.stageCount(),
                in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(), draws, steam, pumparounds,
                in.columnDiameterMetres() * Math.sqrt(scale));
    }

    private static final class Rig {
        final AssemblyGameTests.Plant plant;
        final List<BlockPos> sinks = new ArrayList<>();
        BlockPos feed, steam, water;
        V3ColumnInput input;

        Rig(AssemblyGameTests.Plant plant) {
            this.plant = plant;
        }

        /**
         * The plant, west to east: the overhead train (north: condenser, receiver, reflux pump) and the three pumparound
         * loops (south: a pump, then an exchanger's shell) on the column's west, where its reflux and pumparound nozzles
         * face; the column; its side draws and its steam to the east. Boundary lines first, straight out of their nozzles;
         * then the lines between units, each the shortest that touches no other line.
         */
        void build() {
            plant.hold();
            MaterialCatalog catalog = MaterialRuntime.active();
            input = scaled(catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog), SCALE);
            var design = ColumnDesign.of(input, CHOICES);
            var shape = EquipmentShape.column(design.diameter(), design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(design.diameter()));
            ColumnDesign.misfit(shape).ifPresent(reason -> {
                throw new IllegalStateException(reason);
            });
            var column = plant.column = plant.place(shape, 0, 0, 0);
            var condenser = plant.place(exchanger(CONDENSER_SIZE), -14, 0, -20);
            var drum = plant.drum = plant.place(EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 5,
                    EquipmentShape.defaultConnections(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, 5, true), true), -24, 0, -6);
            var refluxPump = plant.place(AssemblyGameTests.Plant.unit(Kind.PUMP), -10, 0, -8);
            var pumps = new ArrayList<Vessel>();
            var coolers = new ArrayList<Vessel>();
            for (int j = 0; j < 3; j++) {
                pumps.add(plant.place(AssemblyGameTests.Plant.unit(Kind.PUMP), -7, 0, 9 + 8 * j));
                coolers.add(plant.place(AssemblyGameTests.Plant.unit(Kind.EXCHANGER), -16, 0, 9 + 8 * j));
            }

            // Boundary lines: the feed and steam valves point at the column, the products' away from it.
            var generator = ModBlocks.FLUID_GENERATOR.get().defaultBlockState();
            var sink = ModBlocks.FLUID_VOID.get().defaultBlockState();
            feed = line(column, Role.FEED, 0, false, generator);
            steam = line(column, Role.STEAM, 0, false, generator);
            for (int j = 0; j < 3; j++) sinks.add(line(column, Role.DRAW, j, true, sink));
            sinks.add(line(column, Role.BOTTOMS, 0, true, sink));
            sinks.add(line(drum, Role.VAPOUR_OUTLET, 0, true, sink));
            sinks.add(line(drum, Role.BOOT_DRAIN, 0, true, sink));
            // The condenser's water: a generator into its tubes, out into a void.
            var tubeIn = AssemblyGameTests.Plant.front(condenser, Role.TUBE_INLET);
            plant.pipe(tubeIn);
            water = plant.branch(tubeIn, AssemblyGameTests.Plant.out(condenser, Role.TUBE_INLET), false, generator);
            var tubeOut = AssemblyGameTests.Plant.front(condenser, Role.TUBE_OUTLET);
            plant.pipe(tubeOut);
            sinks.add(plant.branch(tubeOut, AssemblyGameTests.Plant.out(condenser, Role.TUBE_OUTLET), true, sink));
            // Branching pipes off the tube nozzles left valves on the water lines: the water runs free.
            for (var q : Direction.values()) {
                plant.valves.remove(tubeIn.relative(q, 2));
                plant.valves.remove(tubeOut.relative(q, 2));
            }

            // The overhead: column, condenser shell, receiver, reflux pump, the distillate's tee, the reflux back.
            plant.route(AssemblyGameTests.Plant.front(column, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(condenser, Role.SHELL_INLET));
            plant.route(AssemblyGameTests.Plant.front(condenser, Role.SHELL_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
            plant.route(AssemblyGameTests.Plant.front(drum, Role.LIQUID_OUTLET), AssemblyGameTests.Plant.front(refluxPump, Role.SUCTION));
            var before = new HashSet<>(plant.devices);
            plant.tee(plant.route(AssemblyGameTests.Plant.front(refluxPump, Role.DISCHARGE), front(column, Role.REFLUX, 0)));
            // The tee's branch ends at a tank: the distillate leaves to a void instead.
            for (var at : new ArrayList<>(plant.devices))
                if (!before.contains(at) && plant.level.getBlockState(at).is(ModBlocks.FLUID_RESERVOIR.get())) {
                    plant.level.setBlock(at, sink, Block.UPDATE_ALL);
                    sinks.add(at);
                }
            // Each pumparound: drawn through its pump and its exchanger's shell, back two trays up; the lowest first, the upper ones around it.
            for (int j = 2; j >= 0; j--) {
                plant.route(front(column, Role.PUMPAROUND_DRAW, j), AssemblyGameTests.Plant.front(pumps.get(j), Role.SUCTION));
                plant.route(AssemblyGameTests.Plant.front(pumps.get(j), Role.DISCHARGE), AssemblyGameTests.Plant.front(coolers.get(j), Role.SHELL_INLET));
                plant.route(AssemblyGameTests.Plant.front(coolers.get(j), Role.SHELL_OUTLET), front(column, Role.RETURN, j));
            }
            // The feed and the steam lines sized for their flows, on both sides of their valves: the least bore (0.05 m) passes
            // 2.6 kg/s of the 638 K crude at 5 bar (13 kg/m³) at the 100 m/s cap, a quarter of the feed, and the steam (2.1 kg/m³)
            // at 92 m/s. At 0.25 m the crude runs at 12 m/s, at 0.15 m the steam at 10 m/s.
            for (var line : List.of(new Object[]{Role.FEED, FEED_BORE}, new Object[]{Role.STEAM, STEAM_BORE})) {
                var role = (Role) line[0];
                for (int k : new int[]{0, 2})
                    plant.world.resizeLine(id(front(column, role, 0).relative(out(column, role, 0), k)), (double) line[1], d -> PipeSizes.MAX_BORE);
            }
            for (var valve : plant.valves.entrySet()) plant.install(valve.getKey(), valve.getValue());

            // The supplies and the sinks.
            supply(feed, FEED_BORE, new FluidDeviceSpec(1, input.feedTemperatureKelvin(), SUPPLY, crude(catalog)));
            var waterFluid = FluidDeviceSpec.water(catalog).composition();
            supply(steam, STEAM_BORE, new FluidDeviceSpec(1, input.steamFeeds().getFirst().temperatureKelvin(), SUPPLY, waterFluid));
            TankCharge.run(plant.world, id(water), new FluidDeviceSpec(1, 303.15, WATER, waterFluid));
            for (var at : sinks) {
                long id = id(at);
                var r = plant.world.registrations().get(id);
                plant.world.edit(id, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, ATMOSPHERE, waterFluid));
            }
        }

        /**
         * The generator at {@code at} at bore {@code bore}, m, set to {@code spec} and switched on, in one edit as its own
         * screen makes it: a generator's connection is the least bore unless set.
         */
        void supply(BlockPos at, double bore, FluidDeviceSpec spec) {
            long id = id(at);
            var r = plant.world.registrations().get(id);
            var g = r.device().geometry();
            var geometry = new PipeResistance.Geometry(g.length(), bore, g.roughness(), g.minorLoss(), g.channels());
            plant.world.edit(id, r.revision(), r.device().withGeometry(geometry).withGeneratorEnabled(true), spec);
        }

        /** The design's feed on the network's axis, as mole fractions. */
        double[] crude(MaterialCatalog catalog) {
            var ids = new ArrayList<>(catalog.requirePackage(FluidMaterialCatalog.networkPackage(catalog)).components());
            ids.add("Water");
            var axis = new MaterialAxis(ids);
            double[] z = new double[axis.size()];
            double[] flows = input.feedComponentMolarFlowsMolPerSecond();
            double total = 0;
            for (double f : flows) total += f;
            for (int c = 0; c < flows.length; c++) z[axis.requireIndex(input.componentBasis().componentId(c))] += flows[c] / total;
            return z;
        }

        /** Validates the plant complete and starts its column; the reviewer stands north of it, facing it, at noon. */
        void start(String[] failure) {
            var entry = AssemblyService.validate(plant.level, plant.controller());
            if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) {
                failure[0] = "not complete: " + plant.faults();
                return;
            }
            var refused = AssemblyService.start(plant.level, plant.column.origin());
            if (refused != null) {
                for (var s : entry.report().slots()) {
                    var at = BlockPos.of(s.at());
                    IChemE.LOGGER.info("review_operation slot={}#{} at={} block={} registered={}", s.slot().part(), s.slot().index(), at.toShortString(),
                            plant.level.getBlockState(at).getBlock().getDescriptionId(), plant.world.at(plant.position(at)).isPresent());
                }
                failure[0] = "start refused: " + refused;
                return;
            }
            var spawn = plant.base.offset(4, 0, -30);
            plant.level.setDefaultSpawnPos(spawn, 0);
            plant.level.setDayTime(6000);
            IChemE.LOGGER.info("review_operation status=STARTED slots={} column={} spawn={} devices={} design_kmol_h={}", entry.report().slots().size(),
                    plant.column.origin().toShortString(), spawn.toShortString(), plant.devices.size(),
                    String.format(Locale.ROOT, "%.1f", java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum() * 3.6));
        }

        long lastTick;

        /** Holds the server to a game's 20 ticks a second ({@link #PACE_MILLIS} a tick); always true. */
        boolean paced() {
            long wait = lastTick + PACE_MILLIS - System.currentTimeMillis();
            if (wait > 0) try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lastTick = System.currentTimeMillis();
            return true;
        }

        /** Whether the column is past its first solve: running, limited, upset or not ready. */
        boolean past() {
            var view = plant.world.columnView(plant.position(plant.column.origin()), id -> Double.NaN);
            return view != null && view.phase() != OperationView.Phase.STARTING;
        }

        /** The column's Operation view at report {@code n}: its phase, its streams and its duties. */
        void report(int n) {
            long ticks = plant.world.onlineTick();
            var view = plant.world.columnView(plant.position(plant.column.origin()), id -> Double.NaN);
            if (view == null) {
                IChemE.LOGGER.info("review_operation t={} view=none", ticks);
                return;
            }
            IChemE.LOGGER.info("review_operation t={} phase={} limit={} alpha={} ruleA={} failed={} engine=\"{}\"", ticks, view.phase(), view.limit(),
                    String.format(Locale.ROOT, "%.3f", view.alpha()), view.ruleA(), view.failed(), view.engine());
            for (var at : List.of(feed, steam, water)) {
                var d = plant.world.registrations().get(id(at)).device();
                IChemE.LOGGER.info("review_operation t={} generator={} bore={} on={} status=\"{}\"", ticks, at.toShortString(), d.geometry().diameter(), d.generatorEnabled(),
                        plant.world.view(d.id()).status());
            }
            var unit = plant.world.column(plant.position(plant.column.origin()));
            if (unit != null) for (var s : unit.stations())
                if (s.port().inlet()) IChemE.LOGGER.info("review_operation t={} port={}{} device={} valve={} port_status=\"{}\" valve_status=\"{}\" wakes={}", ticks, s.port(), s.index(),
                        s.device(), s.valve(), plant.world.view(s.device()).status(), plant.world.view(s.valve()).status(),
                        com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.couplingWakes.sum());
            IChemE.LOGGER.info("review_operation t={} solver={} feed_line=\"{}\" water_line=\"{}\"", ticks,
                    com.wormzjl.icheme.runtime.ProcessSolveServices.diagnostics(plant.level.getServer()), plant.world.view(id(feed)).status(),
                    plant.world.view(id(water)).status());
            for (var s : view.streams())
                IChemE.LOGGER.info("review_operation t={} stream={}{} kmol_h={} T={} P_kPa={} held={}", ticks, s.port(), s.index(),
                        String.format(Locale.ROOT, "%.2f", s.rate() * 3.6), String.format(Locale.ROOT, "%.1f", s.temperature()),
                        String.format(Locale.ROOT, "%.1f", s.pressure() / 1000), String.format(Locale.ROOT, "%.2f", s.held()));
            for (var d : view.duties())
                IChemE.LOGGER.info("review_operation t={} duty={}{} booked_kW={} solved_kW={}", ticks, d.kind(), d.index(),
                        String.format(Locale.ROOT, "%.1f", d.booked() / 1000), String.format(Locale.ROOT, "%.1f", d.solved() / 1000));
            if (view.solve() != null)
                IChemE.LOGGER.info("review_operation t={} solve trigger={} warm={} age_s={}", ticks, view.solve().trigger(), view.solve().warm(),
                        String.format(Locale.ROOT, "%.0f", view.solve().age()));
        }

        /**
         * A boundary line straight out of the {@code n}th {@code role} nozzle: three pipes, a valve on the second (its outlet
         * along the line, or back at the nozzle), then {@code end}. Returns the end's position.
         */
        BlockPos line(Vessel vessel, Role role, int n, boolean outward, BlockState end) {
            var from = front(vessel, role, n);
            var d = out(vessel, role, n);
            for (int k = 0; k <= 3; k++) {
                var at = from.relative(d, k);
                if (!plant.free(at, AssemblyGameTests.Plant.around(from, from.relative(d, k - 1), from.relative(d, k + 1))))
                    throw new IllegalStateException(role + " line blocked at " + at.toShortString());
            }
            for (int k = 0; k < 3; k++) plant.pipe(from.relative(d, k));
            plant.valves.put(from.relative(d), outward ? d : d.getOpposite());
            var at = from.relative(d, 3);
            plant.level.setBlock(at, end, Block.UPDATE_ALL);
            plant.devices.add(at);
            return at;
        }

        long id(BlockPos at) {
            return plant.world.at(plant.position(at)).orElseThrow(() -> new IllegalStateException("Nothing registered at " + at.toShortString())).device().id();
        }
    }

    /** An exchanger of shell size {@code size}, m, at its default length and support. */
    static EquipmentShape exchanger(double size) {
        var d = UnitDesign.of(Kind.EXCHANGER);
        int length = d.defaultLength(size);
        return EquipmentShape.designed(Kind.EXCHANGER, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    /** The {@code n}th of {@code vessel}'s {@code role} ports, in the shape's order (a column's from its top tray down). */
    static ShapeRasterizer.Port port(Vessel vessel, Role role, int n) {
        int seen = 0;
        for (var p : ShapeRasterizer.ports(vessel.shape()))
            if (vessel.shape().nozzles().get(p.nozzle()).role() == role && seen++ == n) return p;
        throw new IllegalStateException("No " + role + " " + n + " on the " + vessel.shape().kind());
    }

    static BlockPos front(Vessel vessel, Role role, int n) {
        var p = port(vessel, role, n);
        return vessel.origin().offset(p.cell().x() + p.dx(), p.cell().y() + p.dy(), p.cell().z() + p.dz());
    }

    static Direction out(Vessel vessel, Role role, int n) {
        var p = port(vessel, role, n);
        return Direction.fromDelta(p.dx(), p.dy(), p.dz());
    }
}
