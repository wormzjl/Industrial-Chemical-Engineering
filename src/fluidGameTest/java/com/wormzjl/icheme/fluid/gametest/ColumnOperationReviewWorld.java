package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.science.plant.column.OperationView;
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
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A world to try the column's Operation tab in by hand (batch 2026-10-28-column-operation): the default column template
 * (Tia Juana Light: 40 trays, three side draws, three pumparounds, sump steam) at a sixteenth of its feed on a quarter of
 * its column area, built with a water-cooled condenser (C6b), its receiver, a reflux pump and each pumparound's pump and
 * exchanger. The column's diameter, every unit and the boundary lines' bores are the process simulator's Size all on the same
 * column ({@link #sized}, batch 2026-11-16-review-plant-sizing); the column tests keep the fixed units of {@link Options#REVIEW}. The feed and the steam come from generators, the
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
    /** The review plant's least receiver saddles, m: the boot's drain line, three pipes and its void, clears the ground. */
    static final int RECEIVER_SUPPORT = 5;
    /**
     * The reports of the running column: their number and the online ticks between them. A GameTest server runs its ticks as
     * fast as it can, far ahead of the solves a cycle and its islands wait for (plan section 9), and an island behind the
     * clock plans from the column's past: from the start on, the test holds the server to 20 ticks a second, as a game runs.
     */
    static final int REPORTS = 4, REPORT_TICKS = 600;

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = "review-operation")
    public static void aCrudeColumnRuns(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "review-operation")) return;
        var rig = rig(helper, review(MaterialRuntime.active()));
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
     * and product line carries a shutoff valve beyond its boundary valve. Batch 2026-11-16-review-plant-sizing: the column's
     * diameter (null: the design's, at the scale's area), the reflux pump's shape (null: the least pump), each pumparound's
     * pump and cooler (a list shorter than the pumparounds: the least pump, {@code coolerUnit}), and a bore by product line
     * name ({@code offgas}, {@code water}, {@code distillate}, {@code drawN}, {@code bottoms}) over {@code productBore}.
     */
    record Options(double scale, @Nullable java.util.function.Function<MaterialCatalog, V3ColumnInput> design, ColumnComponents.Condenser condenser,
                   EquipmentShape condenserUnit, @Nullable EquipmentShape trimUnit, EquipmentShape coolerUnit, @Nullable EquipmentShape kettleUnit,
                   double feedBore, double steamBore, double waterBore, double productBore, boolean motors, boolean shutoffs, Layout layout,
                   @Nullable Double diameter, @Nullable EquipmentShape refluxPumpUnit, List<EquipmentShape> pumpUnits, List<EquipmentShape> coolerUnits,
                   Map<String, Double> productBores) {
        /** The fixed units of the column tests and the failure-modes plants. */
        static final Options REVIEW = new Options(SCALE, null, ColumnComponents.Condenser.EXCHANGER, exchanger(CONDENSER_SIZE), null,
                AssemblyGameTests.Plant.unit(Kind.EXCHANGER), null, FEED_BORE, STEAM_BORE, 0, 0, false, false, Layout.REVIEW);

        Options {
            pumpUnits = List.copyOf(pumpUnits);
            coolerUnits = List.copyOf(coolerUnits);
            productBores = Map.copyOf(productBores);
        }

        /** The design's diameter, the least pumps, {@code coolerUnit} on every pumparound, the product lines at {@code productBore}. */
        Options(double scale, @Nullable java.util.function.Function<MaterialCatalog, V3ColumnInput> design, ColumnComponents.Condenser condenser,
                EquipmentShape condenserUnit, @Nullable EquipmentShape trimUnit, EquipmentShape coolerUnit, @Nullable EquipmentShape kettleUnit,
                double feedBore, double steamBore, double waterBore, double productBore, boolean motors, boolean shutoffs, Layout layout) {
            this(scale, design, condenser, condenserUnit, trimUnit, coolerUnit, kettleUnit, feedBore, steamBore, waterBore, productBore, motors, shutoffs, layout,
                    null, null, List.of(), List.of(), Map.of());
        }

        EquipmentShape pumpUnit(int j) {
            return j < pumpUnits.size() ? pumpUnits.get(j) : AssemblyGameTests.Plant.unit(Kind.PUMP);
        }

        EquipmentShape coolerUnit(int j) {
            return j < coolerUnits.size() ? coolerUnits.get(j) : coolerUnit;
        }
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

    /** {@code in} at a column diameter of {@code diameter}, m. */
    static V3ColumnInput withDiameter(V3ColumnInput in, double diameter) {
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), in.feedComponentMolarFlowsMolPerSecond(), in.feedTemperatureKelvin(),
                in.stageCount(), in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(), in.sideDraws(), in.steamFeeds(), in.pumparounds(),
                diameter);
    }

    /**
     * The extent of {@code shape}'s cells and nozzle pipe blocks, {min x, max x, min y, max y, min z, max z}, from its origin;
     * a shape that rasterizes no cells spans its size from the origin.
     */
    static int[] extent(EquipmentShape shape) {
        int[] e = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        var layout = ShapeRasterizer.layout(shape);
        var cells = new ArrayList<int[]>();
        for (var c : layout.shell()) cells.add(new int[]{c.x(), c.y(), c.z()});
        for (var c : layout.interior()) cells.add(new int[]{c.x(), c.y(), c.z()});
        for (var p : ShapeRasterizer.ports(shape)) cells.add(new int[]{p.cell().x() + p.dx(), p.cell().y() + p.dy(), p.cell().z() + p.dz()});
        if (cells.isEmpty()) return new int[]{0, shape.sizeX() - 1, 0, shape.sizeY() - 1, 0, shape.sizeZ() - 1};
        for (var c : cells) {
            e[0] = Math.min(e[0], c[0]);
            e[1] = Math.max(e[1], c[0]);
            e[2] = Math.min(e[2], c[1]);
            e[3] = Math.max(e[3], c[1]);
            e[4] = Math.min(e[4], c[2]);
            e[5] = Math.max(e[5], c[2]);
        }
        return e;
    }

    /** The column's tag on the review plant's sheet. */
    private static final String TOWER = "T-1";

    /**
     * The review plant as the process simulator sizes it (batch 2026-11-16-review-plant-sizing): the column's diameter, the
     * condenser's shells and their count, the receiver, the reflux pump, each pumparound's cooler and pump, and each boundary
     * line's bore by its name ({@code feed}, {@code steam1}, the products').
     */
    record SizedPlant(V3ColumnInput input, double diameter, EquipmentShape condenser, int condensers, EquipmentShape receiver, EquipmentShape refluxPump,
                      List<EquipmentShape> coolers, List<EquipmentShape> pumps, Map<String, Double> bores) {
    }

    /**
     * The review plant sized by the process simulator's rules: a one-column sheet of the network's default template at
     * {@link #SCALE} (its feed at its own temperature and {@link #SUPPLY}, its steam rows as wired steam at {@link #SUPPLY}, a
     * product stream on every outlet, the condenser's and the pumparound coolers' water ports free, so each is rated on tower
     * water), run, then Size all ({@code Proposal.sizeAll}: the column's half-metre diameters by real solves, every member
     * Auto, exchangers on their design deposit with the 20 % spare, at {@link DesignBasis#DEFAULT}'s design flow). Logs one
     * {@code review_operation sizing} line per unit and line, with every rating row that is not green.
     *
     * @throws IllegalStateException where a run is not steady, or a size is one the rig does not build
     */
    static SizedPlant sized(MaterialCatalog catalog) {
        String network = FluidMaterialCatalog.networkPackage(catalog);
        return MaterialRuntime.with(catalog, network, () -> sizing(catalog, network));
    }

    private static SizedPlant sizing(MaterialCatalog catalog, String network) {
        var template = com.wormzjl.icheme.science.column.v3.V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog);
        var input = scaled(template, SCALE);
        var model = FluidThermodynamics.forNetwork(catalog, network);
        var params = ColumnDoor.from(input, ColumnComponents.Condenser.EXCHANGER);
        var door = ColumnDoor.of(params);
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        for (int c = 0; c < flows.length; c++) amounts.merge(input.componentBasis().componentId(c), flows[c], Double::sum);
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End(TOWER, ColumnUnit.FEED), new Flowsheet.Feed(input.feedTemperatureKelvin(), SUPPLY, amounts, List.of())));
        for (int s = 0; s < door.steam().size(); s++) {
            var row = door.steam().get(s);
            streams.add(new Flowsheet.Stream("steam" + (s + 1), "steam" + (s + 1), null, new Flowsheet.End(TOWER, ColumnUnit.steamPort(row)),
                    new Flowsheet.Feed(row.temperature(), SUPPLY, Map.of("Water", row.flow()), List.of())));
        }
        streams.add(product("offgas", ColumnUnit.OVERHEAD));
        streams.add(product("distillate", ColumnUnit.DISTILLATE));
        streams.add(product("water", ColumnUnit.WATER));
        for (int j = 0; j < door.draws().size(); j++) streams.add(product("draw" + (j + 1), ColumnUnit.drawPort(door.draws().get(j))));
        streams.add(product("bottoms", ColumnUnit.BOTTOMS));
        var sheet = new Flowsheet(new Flowsheet.Basis(network, catalog.requirePackage(network).revision(), model.components()),
                List.of(new Flowsheet.Unit(TOWER, "column", TOWER, 0, 0, params)), streams, FlowsheetSolver.Settings.DEFAULT);
        var run = steady(FlowsheetEngine.run(sheet, catalog, () -> {
        }, null, FlowsheetSolver.Progress.NONE), "run");
        var thermo = new FlowThermo(model, catalog, network, () -> {
        });
        var all = Proposal.sizeAll(sheet, run, thermo, FlowsheetSolver.Progress.NONE);
        steady(all.run(), "sized re-run");
        var sized = all.sized();

        // The evidence: every unit and line with its rows off green.
        for (var unit : sized.units().entrySet()) {
            var size = unit.getValue().size();
            var shape = size == null ? null : UnitSize.built(size);
            IChemE.LOGGER.info("review_operation sizing unit=\"{}\" count={} shape={} lamp={} off_green={}", unit.getKey(), size == null ? 0 : size.count(),
                    shape == null ? "none" : describe(shape), unit.getValue().rating().lamp(), offGreen(unit.getValue().rating()));
        }
        for (var line : sized.lines().entrySet()) {
            var size = line.getValue().size();
            IChemE.LOGGER.info("review_operation sizing line=\"{}\" bore={} runs={} lamp={} off_green={}", line.getKey(), size == null ? "none" : size.bore(),
                    size == null ? 0 : size.runs(), line.getValue().rating().lamp(), offGreen(line.getValue().rating()));
        }

        var columnSize = (ColumnSizing.Size) require(sized, TOWER).size();
        var flood = require(sized, TOWER).rating().reading(Limit.COLUMN_FLOOD);
        var condenser = (ExchangerSizing.Size) require(sized, SheetSizing.member(TOWER, "condenser")).size();
        var receiver = (DrumSizing.Size) require(sized, SheetSizing.member(TOWER, "receiver")).size();
        if (receiver.type() != UnitType.LYING_DRUM || !receiver.boot())
            throw new IllegalStateException("The sized receiver is " + receiver + ": the rig builds a lying drum with a boot");
        var refluxPump = pump(require(sized, SheetSizing.member(TOWER, "reflux pump")).size());
        var coolers = new ArrayList<EquipmentShape>();
        var pumps = new ArrayList<EquipmentShape>();
        for (var pumparound : door.pumparounds()) {
            var cooler = (ExchangerSizing.Size) require(sized, SheetSizing.member(TOWER, SheetSizing.PumparoundMember.name(pumparound.id(), SheetSizing.PumparoundMember.COOLER))).size();
            if (cooler.shells() != 1)
                throw new IllegalStateException("Pumparound " + pumparound.id() + "'s cooler is sized at " + cooler.shells() + " shells: the rig places one cooler shell a pumparound");
            coolers.add(cooler.shape());
            pumps.add(pump(require(sized, SheetSizing.member(TOWER, SheetSizing.PumparoundMember.name(pumparound.id(), "pump"))).size()));
        }
        var bores = new LinkedHashMap<String, Double>();
        for (var stream : streams) {
            var line = sized.lines().get(stream.id());
            if (line == null || line.size() == null) throw new IllegalStateException("The " + stream.id() + " line has no size");
            bores.put(stream.id(), bore(line.size()));
        }
        IChemE.LOGGER.info("review_operation sizing column diameter_m={} trays={} flood_pct={} condenser_shells={} condenser_MW={} bores={}", columnSize.diameter(),
                input.stageCount(), flood == null ? "none" : String.format(Locale.ROOT, "%.1f", flood.value()), condenser.shells(),
                String.format(Locale.ROOT, "%.3f", run.reports().get(TOWER).readings().getOrDefault("condenser", Double.NaN) / 1e6), bores);
        double diameter = ColumnDesign.builtDiameter(columnSize.diameter());
        return new SizedPlant(withDiameter(input, diameter), diameter, condenser.shape(), condenser.shells(), receiver.shape(), refluxPump, coolers, pumps, bores);
    }

    private static Flowsheet.Stream product(String id, String port) {
        return new Flowsheet.Stream(id, id, new Flowsheet.End(TOWER, port), null, null);
    }

    private static com.wormzjl.icheme.science.flowsheet.FlowsheetRun steady(com.wormzjl.icheme.science.flowsheet.FlowsheetRun run, String what) {
        if (run.status() != com.wormzjl.icheme.science.flowsheet.FlowsheetRun.Status.STEADY)
            throw new IllegalStateException("The review plant's sizing " + what + " is " + run.status() + ": " + (run.log().isEmpty() ? "no message" : run.log().getFirst()));
        return run;
    }

    private static SheetSizing.SizedUnit require(SheetSizing.Sized sized, String id) {
        var unit = sized.units().get(id);
        if (unit == null || unit.size() == null) throw new IllegalStateException("The sizing gave " + id + " no size: " + (unit == null ? "absent" : offGreen(unit.rating())));
        return unit;
    }

    /** A sized pump's shape: the rig drives a centrifugal casing. */
    private static EquipmentShape pump(UnitSize size) {
        if (!(size instanceof PumpSizing.Size pump)) throw new IllegalStateException("The sized pump is " + size + ": the rig builds a centrifugal pump");
        return pump.shape();
    }

    /** A sized line's bore, m: its runs as one line of the same area, up to the next 0.05 m, at most the largest bore. */
    static double bore(com.wormzjl.icheme.science.flowsheet.sizing.LineSizing.Size size) {
        return size.runs() == 1 ? size.bore() : Math.min(PipeSizes.MAX_BORE, Math.ceil(size.bore() * Math.sqrt(size.runs()) * 20 - 1e-9) / 20);
    }

    private static String describe(EquipmentShape shape) {
        return String.format(Locale.ROOT, "%s:%.2fx%.1f:support=%d", shape.kind(), shape.diameter(), shape.tangentLength(), shape.support());
    }

    private static List<String> offGreen(Rating rating) {
        var out = new ArrayList<String>();
        for (var r : rating.readings())
            if (r.lamp() != Lamp.GREEN && r.lamp() != Lamp.NONE)
                out.add(String.format(Locale.ROOT, "%s=%.4g[%.4g..%.4g]:%s", r.limit().name(), r.value(), r.low(), r.high(), r.lamp()));
        return out;
    }

    /**
     * The review plant's rig on the sizes of {@link #sized}: the column at its sized diameter; the condenser's shells in a row
     * north-west of it, the receiver west of them; the reflux pump west of the column; each pumparound's pump and cooler in a
     * row south-west; every unit placed by its extent with a {@code gap} between, the box from the extents.
     */
    static Options review(MaterialCatalog catalog) {
        var s = sized(catalog);
        var design = ColumnDesign.of(s.input(), new ColumnComponents.Choices(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD,
                java.util.Collections.nCopies(s.input().pumparounds().size(), ColumnComponents.Pump.STANDARD)));
        var column = extent(EquipmentShape.column(design.diameter(), design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(design.diameter())));
        int gap = 7;
        int north = column[4] - gap;
        var e = extent(s.condenser());
        var first = new BlockPos(column[0] - gap - e[1], 0, north - e[5]);
        var condensers = new ArrayList<BlockPos>();
        for (int i = 0; i < s.condensers(); i++) condensers.add(first.offset(0, 0, -i * (e[5] - e[4] + 1 + gap)));
        int depth = s.condensers() * (e[5] - e[4] + 1) + (s.condensers() - 1) * gap;
        // The receiver as the sizing gives it, its saddles high enough for the boot's drain line to clear the ground.
        var drumShape = s.receiver();
        double dd = drumShape.diameter(), dl = drumShape.tangentLength();
        int support = Math.max(RECEIVER_SUPPORT, drumShape.support());
        var receiver = EquipmentShape.drum(dd, dl, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, support,
                EquipmentShape.defaultConnections(dd, dl, EquipmentShape.Orientation.HORIZONTAL, support, true), true);
        var r = extent(receiver);
        var drumAt = new BlockPos(first.getX() + e[0] - gap - r[1], 0, north - r[5]);
        var p = extent(s.refluxPump());
        var refluxAt = new BlockPos(column[0] - gap - 3 - p[1], 0, column[4] - p[4]);
        var pumps = new ArrayList<BlockPos>();
        var coolers = new ArrayList<BlockPos>();
        int z = column[5] + gap, west = Math.min(drumAt.getX() + r[0], first.getX() + e[0]), south = column[5];
        for (int j = 0; j < s.pumps().size(); j++) {
            var pj = extent(s.pumps().get(j));
            var cj = extent(s.coolers().get(j));
            var pumpAt = new BlockPos(column[0] - gap - pj[1], 0, z - pj[4]);
            var coolerAt = new BlockPos(pumpAt.getX() + pj[0] - 2 * gap - cj[1], 0, z - cj[4]);
            pumps.add(pumpAt);
            coolers.add(coolerAt);
            west = Math.min(west, coolerAt.getX() + cj[0]);
            south = Math.max(south, z + Math.max(cj[5] - cj[4], pj[5] - pj[4]));
            z += Math.max(cj[5] - cj[4], pj[5] - pj[4] + 8) + gap;
        }
        var layout = new Layout(condensers, List.of(), drumAt, dd, dl, support, refluxAt, pumps, coolers, null, Layout.REVIEW.corner(),
                new BlockPos(west - 12, 0, north - Math.max(depth, r[5] - r[4]) - 16), new BlockPos(column[1] + 24, Math.max(column[3] + 8, 44), south + 16));
        var bores = new LinkedHashMap<>(s.bores());
        double feed = bores.remove("feed"), steam = bores.remove("steam1");
        bores.keySet().removeIf(k -> k.startsWith("steam"));
        IChemE.LOGGER.info("review_operation sizing layout condensers={} drum={} support={} reflux_pump={} pumps={} coolers={} below={} above={}", condensers, drumAt.toShortString(),
                support, refluxAt.toShortString(), pumps, coolers, layout.below().toShortString(), layout.above().toShortString());
        return new Options(SCALE, null, ColumnComponents.Condenser.EXCHANGER, s.condenser(), null, s.coolers().getFirst(), null, feed, steam, 0, 0, false, false, layout,
                s.diameter(), s.refluxPump(), s.pumps(), s.coolers(), bores);
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
        /** Each boundary line's end (its generator or void) by its name, the distillate's void with them. */
        final java.util.Map<String, BlockPos> ends = new java.util.LinkedHashMap<>();
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
            var base = options.design() == null ? com.wormzjl.icheme.science.column.v3.V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog) : options.design().apply(catalog);
            input = scaled(base, options.scale());
            if (options.diameter() != null) input = withDiameter(input, options.diameter());
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
            var refluxPump = plant.place(options.refluxPumpUnit() != null ? options.refluxPumpUnit() : AssemblyGameTests.Plant.unit(Kind.PUMP),
                    layout.refluxPump().getX(), layout.refluxPump().getY(), layout.refluxPump().getZ());
            pumps.add(refluxPump);
            var paPumps = new ArrayList<Vessel>();
            for (int j = 0; j < pumparounds; j++) {
                var p = layout.pumps().get(j);
                var c = layout.coolers().get(j);
                paPumps.add(plant.place(options.pumpUnit(j), p.getX(), p.getY(), p.getZ()));
                coolers.add(plant.place(options.coolerUnit(j), c.getX(), c.getY(), c.getZ()));
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
                var in = plant.route(AssemblyGameTests.Plant.front(column, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(condensers.getFirst(), Role.SHELL_INLET));
                var out = plant.route(AssemblyGameTests.Plant.front(condensers.getFirst(), Role.SHELL_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
                // Every further shell in parallel with the first, as an air cooler's banks.
                for (var shell : condensers.subList(1, condensers.size())) {
                    plant.join(in, AssemblyGameTests.Plant.front(shell, Role.SHELL_INLET));
                    plant.join(out, AssemblyGameTests.Plant.front(shell, Role.SHELL_OUTLET));
                }
            } else {
                var in = plant.route(AssemblyGameTests.Plant.front(column, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(condensers.getFirst(), Role.PROCESS_INLET));
                var out = trims.isEmpty() ? plant.route(AssemblyGameTests.Plant.front(condensers.getFirst(), Role.PROCESS_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE))
                        : plant.route(AssemblyGameTests.Plant.front(condensers.getFirst(), Role.PROCESS_OUTLET), AssemblyGameTests.Plant.front(trims.getFirst(), Role.SHELL_INLET));
                if (!trims.isEmpty()) plant.route(AssemblyGameTests.Plant.front(trims.getFirst(), Role.SHELL_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
                // Every further bank in parallel with the first: its inlet off the overhead line, its outlet onto the first's.
                for (var bank : condensers.subList(1, condensers.size())) {
                    plant.join(in, AssemblyGameTests.Plant.front(bank, Role.PROCESS_INLET));
                    plant.join(out, AssemblyGameTests.Plant.front(bank, Role.PROCESS_OUTLET));
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
                    ends.put("distillate", at);
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
            for (var line : options.productBores().entrySet()) {
                var at = ends.get(line.getKey());
                if (at == null) throw new IllegalStateException("No product line " + line.getKey() + " to size: " + ends.keySet());
                resize(at, line.getValue());
            }
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
            ends.put(name, at);
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
