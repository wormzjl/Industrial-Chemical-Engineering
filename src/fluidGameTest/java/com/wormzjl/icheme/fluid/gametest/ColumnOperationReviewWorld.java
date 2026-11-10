package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * A world to try the column's Operation tab in by hand (batch 2026-10-28-column-operation): the default column template
 * (Tia Juana Light: 40 trays, three side draws, three pumparounds, sump steam) at a sixteenth of its feed on a quarter of
 * its column area, a 2 m column at the template's own tray loading, built with a water-cooled condenser (C6b), its
 * receiver, a reflux pump and each pumparound's pump and exchanger. The feed and the steam come from generators, the
 * condenser's and each pumparound cooler's water from a generator into a void (W4: the column owes them their duties);
 * every product line ends at a void at the atmosphere. The build validates the plant complete, starts the column and
 * leaves it running.
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
    /** The cooling water's supply temperature, K. */
    static final double WATER_TEMPERATURE = 298.15;
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

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = "review-operation")
    public static void aCrudeColumnRuns(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "review-operation")) return;
        var rig = rig(helper);
        var plant = rig.plant;
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
                    if (failure[0] == null) rig.spawn();
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

    /**
     * The plant's rig north of the test's own structure block, on clear ground. The test grid grows east and south from its
     * first test, and the structures of the tests that ran before stay standing: where the test's own row is not the first,
     * the plant's box reaches back over earlier rows. The rig moves north a plant's depth at a time until no block stands
     * above the ground anywhere in its box.
     */
    static Rig rig(GameTestHelper helper) {
        return rig(helper, Options.REVIEW);
    }

    /** {@link #rig(GameTestHelper)} built as {@code options} say, its box and its first place from them. */
    static Rig rig(GameTestHelper helper, Options options) {
        var level = helper.getLevel();
        var layout = options.layout();
        var corner = helper.absolutePos(layout.corner());
        var below = layout.below();
        var above = layout.above();
        for (int k = 0; k <= 16; k++) {
            var at = corner.north(k * (above.getZ() - below.getZ() + 1));
            level.getChunk(at.getX() >> 4, at.getZ() >> 4);
            int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
            if (clear(level, at.offset(below), at.offset(above), ground))
                return new Rig(new AssemblyGameTests.Plant(helper, new BlockPos(at.getX(), ground, at.getZ()), below, above), options);
        }
        throw new IllegalStateException("No clear ground for the column plant north of " + corner.toShortString());
    }

    /**
     * Where a rig's units stand, offsets from the column's origin (batch 2026-11-10-column-failure-modes, stage 5): the
     * condenser's units (an exchanger's shells or an air cooler's banks), the trim's shells (air then water), the receiver
     * and its size, the reflux pump, each pumparound's pump and cooler, the kettle (where the design has one); the box the
     * pipes stay in and the corner, from the test's cell, the search for clear ground starts at.
     */
    record Layout(List<BlockPos> condenser, List<BlockPos> trim, BlockPos drum, double drumDiameter, double drumLength, int drumSupport,
                  BlockPos refluxPump, List<BlockPos> pumps, List<BlockPos> coolers, @Nullable BlockPos kettle, BlockPos corner, BlockPos below, BlockPos above) {
        /** The review plant's: the 2 m column of {@link #SCALE}. */
        static final Layout REVIEW = new Layout(List.of(new BlockPos(-14, 0, -20)), List.of(), new BlockPos(-24, 0, -6), 1.5, 4, 5, new BlockPos(-10, 0, -8),
                List.of(new BlockPos(-7, 0, 9), new BlockPos(-7, 0, 17), new BlockPos(-7, 0, 25)),
                List.of(new BlockPos(-16, 0, 9), new BlockPos(-16, 0, 17), new BlockPos(-16, 0, 25)), null,
                new BlockPos(48, 0, -64), new BlockPos(-34, 0, -32), new BlockPos(24, 44, 36));
    }

    /**
     * How a rig is built (stage 5 of batch 2026-11-10-column-failure-modes; the review plant's are {@link #REVIEW}): the
     * share of the design's feed, the design (null: the network's default column template), the condenser, the units'
     * shapes (the condenser's, the trim's, a pumparound cooler's, the kettle's), the feed's, steam's and utility lines'
     * bores (0: as laid), whether each pump turns on a motor fed with FE (else a creative motor), and whether every supply
     * and product line carries a shutoff valve beyond its boundary valve.
     */
    record Options(double scale, @Nullable java.util.function.Function<MaterialCatalog, V3ColumnInput> design, ColumnComponents.Condenser condenser,
                   EquipmentShape condenserUnit, @Nullable EquipmentShape trimUnit, EquipmentShape coolerUnit, @Nullable EquipmentShape kettleUnit,
                   double feedBore, double steamBore, double waterBore, double productBore, boolean motors, boolean shutoffs, Layout layout) {
        static final Options REVIEW = new Options(SCALE, null, ColumnComponents.Condenser.EXCHANGER, exchanger(CONDENSER_SIZE), null,
                AssemblyGameTests.Plant.unit(Kind.EXCHANGER), null, FEED_BORE, STEAM_BORE, 0, 0, false, false, Layout.REVIEW);
    }

    /** Whether every column from {@code low} to {@code high} has its surface at {@code ground}: nothing stands on it. */
    private static boolean clear(net.minecraft.server.level.ServerLevel level, BlockPos low, BlockPos high, int ground) {
        for (int x = low.getX(); x <= high.getX(); x++)
            for (int z = low.getZ(); z <= high.getZ(); z++) {
                level.getChunk(x >> 4, z >> 4);
                if (level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) != ground) return false;
            }
        return true;
    }

    /** The design at {@code scale} of its feed, its draws, steam, pumparound duties and reboiler duty, its column area in step. */
    static V3ColumnInput scaled(V3ColumnInput in, double scale) {
        double[] feed = in.feedComponentMolarFlowsMolPerSecond().clone();
        for (int c = 0; c < feed.length; c++) feed[c] *= scale;
        var draws = in.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * scale)).toList();
        var steam = in.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * scale, s.temperatureKelvin())).toList();
        var specifications = in.specifications().stream().map(sp -> sp instanceof com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ReboilerDuty r
                ? (com.wormzjl.icheme.science.column.v3.V3ColumnSpecification) new com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ReboilerDuty(r.watts() * scale) : sp).toList();
        var pumparounds = in.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * scale, p.split())).toList();
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), feed, in.feedTemperatureKelvin(), in.stageCount(),
                in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), specifications, draws, steam, pumparounds,
                in.columnDiameterMetres() * Math.sqrt(scale));
    }

    static final class Rig {
        final AssemblyGameTests.Plant plant;
        final Options options;
        final List<BlockPos> sinks = new ArrayList<>();
        BlockPos feed, steam, water;
        /** Each pumparound cooler's cooling-water generator (W4). */
        final List<BlockPos> coolingWater = new ArrayList<>();
        /** Every water-cooled condenser unit's generator (the exchanger's shells, or the trim's), {@link #water} the first. */
        final List<BlockPos> condenserWater = new ArrayList<>();
        /** The kettle's hot utility's generator, or null. */
        BlockPos heating;
        /** The shutoff valves beyond the boundary valves, each with its axis ({@link Options#shutoffs}), and which line each is on. */
        final java.util.Map<BlockPos, Direction> shutoffs = new java.util.LinkedHashMap<>();
        final java.util.Map<String, BlockPos> lines = new java.util.LinkedHashMap<>();
        /** The units as placed: the condenser's, the trim's, the receiver, the pumps (the reflux pump first), the coolers, the kettle. */
        final List<Vessel> condensers = new ArrayList<>(), trims = new ArrayList<>(), pumps = new ArrayList<>(), coolers = new ArrayList<>(), motors = new ArrayList<>();
        Vessel kettle;
        /** The feed line's last pipe where the feed comes from upstream units the caller builds ({@link #layout(boolean)}). */
        BlockPos feedLine;
        V3ColumnInput input;
        ColumnDesign design;

        Rig(AssemblyGameTests.Plant plant) {
            this(plant, Options.REVIEW);
        }

        Rig(AssemblyGameTests.Plant plant, Options options) {
            this.plant = plant;
            this.options = options;
        }

        /** The plant, its valves and its supplies: {@link #layout(boolean)}, then {@link #finish()}. */
        void build() {
            layout(false);
            finish();
        }

        /**
         * The plant, west to east: the overhead train (north: condenser, [trim,] receiver, reflux pump) and the pumparound
         * loops (south: a pump, then an exchanger's shell) on the column's west, where its reflux and pumparound nozzles
         * face; the column; its side draws and its steam to the east; a kettle where the design has one. Boundary lines
         * first, straight out of their nozzles; then the lines between units, each the shortest that touches no other line.
         * {@code openFeed}: the feed line ends at a pipe ({@link #feedLine}) for the caller to feed, else at a generator.
         */
        void layout(boolean openFeed) {
            plant.hold();
            MaterialCatalog catalog = MaterialRuntime.active();
            var base = options.design() == null ? catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog) : options.design().apply(catalog);
            input = scaled(base, options.scale());
            var layout = options.layout();
            int pumparounds = input.pumparounds().size();
            var choices = new ColumnComponents.Choices(options.condenser(), ColumnComponents.Pump.STANDARD, java.util.Collections.nCopies(pumparounds, ColumnComponents.Pump.STANDARD),
                    new ColumnComponents.Units(layout.condenser().size(), 1, java.util.Collections.nCopies(pumparounds, 1), Math.max(1, layout.trim().size())));
            design = ColumnDesign.of(input, choices);
            var shape = EquipmentShape.column(design.diameter(), design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(design.diameter()));
            ColumnDesign.misfit(shape).ifPresent(reason -> {
                throw new IllegalStateException(reason);
            });
            var column = plant.column = plant.place(shape, 0, 0, 0);
            for (var at : layout.condenser()) condensers.add(plant.place(options.condenserUnit(), at.getX(), at.getY(), at.getZ()));
            for (var at : layout.trim()) trims.add(plant.place(java.util.Objects.requireNonNull(options.trimUnit()), at.getX(), at.getY(), at.getZ()));
            double dd = layout.drumDiameter(), dl = layout.drumLength();
            var drum = plant.drum = plant.place(EquipmentShape.drum(dd, dl, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, layout.drumSupport(),
                    EquipmentShape.defaultConnections(dd, dl, EquipmentShape.Orientation.HORIZONTAL, layout.drumSupport(), true), true), layout.drum().getX(), layout.drum().getY(), layout.drum().getZ());
            var refluxPump = plant.place(AssemblyGameTests.Plant.unit(Kind.PUMP), layout.refluxPump().getX(), layout.refluxPump().getY(), layout.refluxPump().getZ());
            pumps.add(refluxPump);
            var paPumps = new ArrayList<Vessel>();
            for (int j = 0; j < pumparounds; j++) {
                var p = layout.pumps().get(j);
                var c = layout.coolers().get(j);
                paPumps.add(plant.place(AssemblyGameTests.Plant.unit(Kind.PUMP), p.getX(), p.getY(), p.getZ()));
                coolers.add(plant.place(options.coolerUnit(), c.getX(), c.getY(), c.getZ()));
            }
            pumps.addAll(paPumps);
            if (design.components().kettle()) {
                var k = java.util.Objects.requireNonNull(layout.kettle(), "the design has a kettle: the layout places it");
                kettle = plant.place(java.util.Objects.requireNonNull(options.kettleUnit()), k.getX(), k.getY(), k.getZ());
            }
            // A motor on FE beyond each pump's coupling (stage 5), before the lines, which go round it.
            if (options.motors()) for (var pump : pumps) motors.add(plant.motor(pump));

            // Boundary lines: the feed and steam valves point at the column, the products' away from it.
            var generator = ModBlocks.FLUID_GENERATOR.get().defaultBlockState();
            var sink = ModBlocks.FLUID_VOID.get().defaultBlockState();
            if (openFeed) feedLine = line(column, Role.FEED, 0, false, null, "feed");
            else feed = line(column, Role.FEED, 0, false, generator, "feed");
            for (int s = 0; s < input.steamFeeds().size(); s++) {
                var at = line(column, Role.STEAM, s, false, generator, "steam" + (s + 1));
                if (s == 0) steam = at;
            }
            for (int j = 0; j < input.sideDraws().size(); j++) sinks.add(line(column, Role.DRAW, j, true, sink, "draw" + (j + 1)));
            sinks.add(kettle == null ? line(column, Role.BOTTOMS, 0, true, sink, "bottoms") : line(kettle, Role.LIQUID_OUTLET, 0, true, sink, "bottoms"));
            sinks.add(line(drum, Role.VAPOUR_OUTLET, 0, true, sink, "offgas"));
            sinks.add(line(drum, Role.BOOT_DRAIN, 0, true, sink, "water"));
            // The condenser's water: a generator into its tubes, out into a void (the trim's, with air then water).
            var cooled = options.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER ? trims : options.condenser() == ColumnComponents.Condenser.EXCHANGER ? condensers : List.<Vessel>of();
            for (int i = 0; i < cooled.size(); i++) condenserWater.add(water(cooled.get(i), generator, sink, i == 0 ? "trim" : "trim" + (i + 1)));
            water = condenserWater.isEmpty() ? null : condenserWater.getFirst();
            // Each pumparound cooler's water the same way (W4: the column owes it the pumparound's duty; without it the column
            // reads LIMITED: pumparound).
            for (int j = 0; j < coolers.size(); j++) coolingWater.add(water(coolers.get(j), generator, sink, "pa" + (j + 1)));
            if (kettle != null) heating = water(kettle, generator, sink, "heating");

            // The overhead: column, condenser (an air cooler's process side, then the trim's shell, or an exchanger's shell),
            // receiver, reflux pump, the distillate's tee, the reflux back.
            if (options.condenser() == ColumnComponents.Condenser.EXCHANGER) {
                plant.route(AssemblyGameTests.Plant.front(column, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(condensers.getFirst(), Role.SHELL_INLET));
                plant.route(AssemblyGameTests.Plant.front(condensers.getFirst(), Role.SHELL_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
            } else {
                plant.route(AssemblyGameTests.Plant.front(column, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(condensers.getFirst(), Role.PROCESS_INLET));
                if (trims.isEmpty()) plant.route(AssemblyGameTests.Plant.front(condensers.getFirst(), Role.PROCESS_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
                else {
                    plant.route(AssemblyGameTests.Plant.front(condensers.getFirst(), Role.PROCESS_OUTLET), AssemblyGameTests.Plant.front(trims.getFirst(), Role.SHELL_INLET));
                    plant.route(AssemblyGameTests.Plant.front(trims.getFirst(), Role.SHELL_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
                }
            }
            plant.route(AssemblyGameTests.Plant.front(drum, Role.LIQUID_OUTLET), AssemblyGameTests.Plant.front(refluxPump, Role.SUCTION));
            var before = new HashSet<>(plant.devices);
            plant.tee(plant.route(AssemblyGameTests.Plant.front(refluxPump, Role.DISCHARGE), front(column, Role.REFLUX, 0)));
            // The tee's branch ends at a tank: the distillate leaves to a void instead.
            for (var at : new ArrayList<>(plant.devices))
                if (!before.contains(at) && plant.level.getBlockState(at).is(ModBlocks.FLUID_RESERVOIR.get())) {
                    plant.level.setBlock(at, sink, Block.UPDATE_ALL);
                    sinks.add(at);
                    lines.put("distillate", at);
                }
            // Each pumparound: drawn through its pump and its exchanger's shell, back two trays up; the lowest first, the upper ones around it.
            for (int j = pumparounds - 1; j >= 0; j--) {
                plant.route(front(column, Role.PUMPAROUND_DRAW, j), AssemblyGameTests.Plant.front(paPumps.get(j), Role.SUCTION));
                plant.route(AssemblyGameTests.Plant.front(paPumps.get(j), Role.DISCHARGE), AssemblyGameTests.Plant.front(coolers.get(j), Role.SHELL_INLET));
                plant.route(AssemblyGameTests.Plant.front(coolers.get(j), Role.SHELL_OUTLET), front(column, Role.RETURN, j));
            }
            // The reboiler: the bottoms into the kettle, its vapour back under the bottom tray.
            if (kettle != null) {
                plant.route(AssemblyGameTests.Plant.front(column, Role.BOTTOMS), AssemblyGameTests.Plant.front(kettle, Role.FEED));
                plant.route(AssemblyGameTests.Plant.front(kettle, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(column, Role.VAPOUR_RETURN));
            }
        }

        /**
         * The valves, the pumps' drivers and the supplies: the feed and the steam lines sized for their flows on both sides of
         * their valves (the network model's flash): at the review plant's scale the crude is 71 % vapour at 638 K and 5 bar
         * (30.2 kg/m³), 79 % at 632 K and 2.5 bar past the valve (13.5 kg/m³); its start target, 19.9 kg/s, runs at 336 and
         * 750 m/s in the least bore (0.05 m), 13 and 30 m/s at 0.25 m. The steam, 0.75 kg/s at the start (2.1 and 1.0 kg/m³),
         * runs at 185 and 370 m/s at 0.05 m, 21 and 41 m/s at 0.15 m. The velocity goes with the flow over the bore squared.
         */
        void finish() {
            MaterialCatalog catalog = MaterialRuntime.active();
            var column = plant.column;
            for (var line : List.of(new Object[]{Role.FEED, options.feedBore(), 1}, new Object[]{Role.STEAM, options.steamBore(), input.steamFeeds().size()})) {
                var role = (Role) line[0];
                for (int n = 0; n < (int) line[2]; n++)
                    for (int k : options.shutoffs() ? new int[]{0, 2, 4} : new int[]{0, 2})
                        plant.world.resizeLine(id(front(column, role, n).relative(out(column, role, n), k)), (double) line[1], d -> PipeSizes.MAX_BORE);
            }
            if (options.productBore() > 0) for (var at : sinks) resize(at, options.productBore());
            if (options.waterBore() > 0) {
                var utilities = new ArrayList<BlockPos>(condenserWater);
                utilities.addAll(coolingWater);
                if (heating != null) utilities.add(heating);
                for (var at : utilities) resize(at, options.waterBore());
            }
            for (var valve : plant.valves.entrySet()) plant.install(valve.getKey(), valve.getValue());
            for (var valve : shutoffs.entrySet()) shutoff(valve.getKey(), valve.getValue());
            // The casings turn only with their shaft trains (W3): a creative motor drives each pump, or a motor on FE (stage 5).
            if (!options.motors()) for (var pump : pumps) plant.drive(pump);

            // The supplies and the sinks.
            if (feed != null) supply(feed, options.feedBore(), new FluidDeviceSpec(1, input.feedTemperatureKelvin(), SUPPLY, crude(catalog)));
            var waterFluid = FluidDeviceSpec.water(catalog).composition();
            if (steam != null) supply(steam, options.steamBore(), new FluidDeviceSpec(1, input.steamFeeds().getFirst().temperatureKelvin(), SUPPLY, waterFluid));
            var spec = new FluidDeviceSpec(1, WATER_TEMPERATURE, WATER, waterFluid);
            for (var at : condenserWater) water(at, spec);
            for (var at : coolingWater) water(at, spec);
            for (var at : sinks) {
                long id = id(at);
                var r = plant.world.registrations().get(id);
                plant.world.edit(id, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, ATMOSPHERE, waterFluid));
            }
        }

        /** A utility's generator at {@code at} set to {@code spec}: charged at the laid bore, or switched on at {@link Options#waterBore}. */
        void water(BlockPos at, FluidDeviceSpec spec) {
            if (options.waterBore() > 0) supply(at, options.waterBore(), spec);
            else TankCharge.run(plant.world, id(at), spec);
        }

        /** Every pipe of the straight line that ends at {@code end} (a generator or a void) at bore {@code bore}, m, up to the unit it leaves. */
        void resize(BlockPos end, double bore) {
            for (var d : Direction.values()) {
                var at = end.relative(d);
                if (!plant.devices.contains(at)) continue;
                for (int k = 0; k < 8 && plant.devices.contains(at); k++, at = at.relative(d))
                    if (plant.world.at(plant.position(at)).isPresent()) plant.world.resizeLine(id(at), bore, x -> PipeSizes.MAX_BORE);
            }
        }

        /** A shutoff valve on the pipe at {@code at}, its handle on {@code axis}, open, as the fitting item installs one. */
        void shutoff(BlockPos at, Direction axis) {
            if (!plant.world.installPipeValve(id(at), com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.valueOf(axis.name()),
                    com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Cutoff.OPEN))
                throw new IllegalStateException("The shutoff valve at " + at.toShortString() + " did not install");
        }

        /**
         * Cooling water through {@code exchanger}'s tubes, straight out of its nozzles: a generator into the inlet, out into a
         * void (a sink), the water running free (branching pipes off the tube nozzles left valves on the water lines: they go;
         * with {@link Options#shutoffs} the inlet's becomes a shutoff named by {@code line}); returns the generator.
         */
        BlockPos water(Vessel exchanger, net.minecraft.world.level.block.state.BlockState generator, net.minecraft.world.level.block.state.BlockState sink, String line) {
            var in = AssemblyGameTests.Plant.front(exchanger, Role.TUBE_INLET);
            plant.pipe(in);
            var source = plant.branch(in, AssemblyGameTests.Plant.out(exchanger, Role.TUBE_INLET), false, generator);
            var out = AssemblyGameTests.Plant.front(exchanger, Role.TUBE_OUTLET);
            plant.pipe(out);
            var drain = plant.branch(out, AssemblyGameTests.Plant.out(exchanger, Role.TUBE_OUTLET), true, sink);
            sinks.add(drain);
            if (options.shutoffs()) lines.put(line + " out", drain);
            for (var q : Direction.values()) {
                var valve = plant.valves.remove(in.relative(q, 2));
                if (valve != null && options.shutoffs()) {
                    shutoffs.put(in.relative(q, 2), valve);
                    lines.put(line, in.relative(q, 2));
                }
                plant.valves.remove(out.relative(q, 2));
            }
            return source;
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

        /** Validates the plant complete and starts its column. */
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
            IChemE.LOGGER.info("review_operation status=STARTED slots={} column={} devices={} design_kmol_h={}", entry.report().slots().size(),
                    plant.column.origin().toShortString(), plant.devices.size(),
                    String.format(Locale.ROOT, "%.1f", java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum() * 3.6));
        }

        /** The reviewer stands north of the column, facing it, at noon. */
        void spawn() {
            plant.level.setDefaultSpawnPos(plant.base.offset(4, 0, -30), 0);
            plant.level.setDayTime(6000);
        }

        /**
         * Holds the tick until no solve runs on a worker: a GameTest server ticks flat out and would run far ahead of the solves
         * a cycle waits for. A ready job waits for this thread to dispatch it, and a finished one to drain it, so neither counts.
         * Always true.
         */
        boolean paced() {
            var server = plant.level.getServer();
            while (running(ProcessSolveServices.diagnostics(server)) > 0) try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            return true;
        }

        private static int running(ProcessSolveServices.Diagnostics d) {
            return d.outstandingJobs() - d.readyJobs() - d.pendingCompletions();
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
            IChemE.LOGGER.info("review_operation t={} phase={} conditions={} alpha={} ruleA={} refused={} engine=\"{}\"", ticks, view.phase(), view.conditions().stream().map(c -> c.condition().name()).toList(),
                    String.format(Locale.ROOT, "%.3f", view.alpha()), view.ruleA(), view.refused(), view.engine());
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
         * along the line, or back at the nozzle), then {@code end}. With {@link Options#shutoffs} two pipes more, a shutoff
         * valve on the fourth, named {@code name} in {@link #lines}. A null {@code end}: the last pipe is left free, with the
         * cells round it held, for the caller to route to. Returns
         * the end's position (the last pipe's).
         */
        BlockPos line(Vessel vessel, Role role, int n, boolean outward, @Nullable BlockState end, String name) {
            var from = front(vessel, role, n);
            var d = out(vessel, role, n);
            int pipes = options.shutoffs() ? 5 : 3;
            for (int k = 0; k <= pipes; k++) {
                var at = from.relative(d, k);
                if (k == pipes && end == null) break;
                if (!plant.free(at, AssemblyGameTests.Plant.around(from, from.relative(d, k - 1), from.relative(d, k + 1))))
                    throw new IllegalStateException(role + " line blocked at " + at.toShortString());
            }
            for (int k = 0; k < (end == null ? pipes - 1 : pipes); k++) plant.pipe(from.relative(d, k));
            plant.valves.put(from.relative(d), outward ? d : d.getOpposite());
            if (options.shutoffs()) {
                shutoffs.put(from.relative(d, 3), d);
                lines.put(name, from.relative(d, 3));
            }
            if (end == null) {
                // Held free for the caller's route: the lines laid before it go round.
                for (int k = pipes - 1; k <= pipes + 1; k++) plant.reserved.add(from.relative(d, k));
                return from.relative(d, pipes - 1);
            }
            var at = from.relative(d, pipes);
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
