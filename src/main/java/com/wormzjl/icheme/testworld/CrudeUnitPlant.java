package com.wormzjl.icheme.testworld;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The crude unit test plant (used by the column test world and the review GameTests): the default column template at its
 * full feed, sized by the process simulator's Size all, with its condenser, receiver, pumps, pumparound coolers on the
 * crude, charge pump on a flow-regulating drive, residue exchanger and fired heaters ({@link #review}); its rig ({@link Rig})
 * builds it with a {@link PlantBuilder} and starts it. Moved from the review GameTest {@code ColumnOperationReviewWorld}
 * (owner, 2026-11-17: the column test world is a world type of the mod); the layouts and options of the column tests'
 * fixed plants ({@link Options#REVIEW}) moved with it.
 */
public final class CrudeUnitPlant {
    private CrudeUnitPlant() {}

    /** The share of the template's feed the column takes; its area takes the same share, so each tray carries the template's loading. */
    public static final double SCALE = 1 / 16.0;
    /** The sized review plant's share of the template's feed ({@link #sized}, {@link #review}): the template's full flow. */
    public static final double SIZED_SCALE = 1;
    /** The supply pressure of the feed, the steam and the cooling water, Pa; the atmosphere the products leave to. */
    public static final double SUPPLY = 500_000, WATER = 400_000, ATMOSPHERE = 101_325;
    /** The cooling water's supply temperature, K. */
    public static final double WATER_TEMPERATURE = 298.15;
    /** Liquid water's viscosity at {@link #WATER_TEMPERATURE}, Pa s; the world's velocity limit for water, m/s (its 100 m/s cap). */
    public static final double WATER_VISCOSITY = 8.9e-4, WATER_SPEED_LIMIT = 100;
    /** The condenser's shell size, m: larger than the least exchanger, for the overhead's duty. */
    public static final double CONDENSER_SIZE = 1.5;
    /** The feed's and the steam's line bores, m (their generators' too). */
    public static final double FEED_BORE = .25, STEAM_BORE = .15;
    /** The review plant's least receiver saddles, m: the boot's drain line, three pipes and its void, clears the ground. */
    public static final int RECEIVER_SUPPORT = 5;
    /** The sized review plant's cost of a bend, in pipes: its lines run straight where a few pipes more allow it (owner, 2026-11-17). */
    public static final int BEND_COST = 8;

    /** The charge pump's set flow, a share of the design feed's volume flow at suction (owner, 2026-11-17; PUMP_DRIVE_FLOW_PLAN.md). */
    public static final double CHARGE_SHARE = 1.05;

    /**
     * The rig built as {@code options} say with its column's origin at {@code base} (on the ground: the first free block) in
     * {@code level}, its pipes in the layout's box round it: the one entry both the GameTest (the review GameTests' {@code ColumnOperationReviewWorld.rig})
     * and the column test world ({@link ColumnTestWorld}) build through. The ground in the box must be clear.
     */
    public static Rig rig(net.minecraft.server.level.ServerLevel level, BlockPos base, Options options) {
        return new Rig(new PlantBuilder(level, base, options.layout().below(), options.layout().above()), options);
    }

    /**
     * Where a rig's units stand, offsets from the column's origin (batch 2026-11-10-column-failure-modes, stage 5): the
     * condenser's units (an exchanger's shells or an air cooler's banks), the trim's shells (air then water), the receiver
     * and its size, the reflux pump, each pumparound's pump and cooler, the kettle (where the design has one); the box the
     * pipes stay in and the corner, from the test's cell, the search for clear ground starts at.
     */
    public record Layout(List<BlockPos> condenser, List<BlockPos> trim, BlockPos drum, double drumDiameter, double drumLength, int drumSupport,
                  BlockPos refluxPump, List<BlockPos> pumps, List<BlockPos> coolers, @Nullable BlockPos kettle, BlockPos corner, BlockPos below, BlockPos above) {
        /** The review plant's: the 2 m column of {@link #SCALE}. */
        public static final Layout REVIEW = new Layout(List.of(new BlockPos(-14, 0, -20)), List.of(), new BlockPos(-24, 0, -6), 1.5, 4, 5, new BlockPos(-10, 0, -8),
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
     * name ({@code offgas}, {@code water}, {@code distillate}, {@code drawN}, {@code bottoms}) over {@code productBore}, and a
     * bore by internal line name ({@link Rig#routes}: {@code overhead}, {@code receiver out}, {@code reflux}, {@code paN draw},
     * {@code paN return}, {@code condenser water}, {@code paN water}; a line not named stays as laid), and the cost of a bend
     * in pipes for the lines between units ({@code PlantBuilder#bendCost}: 0, the shortest line of any shape).
     */
    public record Options(double scale, @Nullable java.util.function.Function<MaterialCatalog, V3ColumnInput> design, ColumnComponents.Condenser condenser,
                   EquipmentShape condenserUnit, @Nullable EquipmentShape trimUnit, EquipmentShape coolerUnit, @Nullable EquipmentShape kettleUnit,
                   double feedBore, double steamBore, double waterBore, double productBore, boolean motors, boolean shutoffs, Layout layout,
                   @Nullable Double diameter, @Nullable EquipmentShape refluxPumpUnit, List<EquipmentShape> pumpUnits, List<EquipmentShape> coolerUnits,
                   Map<String, Double> productBores, Map<String, Double> lineBores, int bendCost, @Nullable Preheat preheat) {
        /** The fixed units of the column tests and the failure-modes plants. */
        public static final Options REVIEW = new Options(SCALE, null, ColumnComponents.Condenser.EXCHANGER, exchanger(CONDENSER_SIZE), null,
                PlantBuilder.unit(Kind.EXCHANGER), null, FEED_BORE, STEAM_BORE, 0, 0, false, false, Layout.REVIEW);

        public Options {
            pumpUnits = List.copyOf(pumpUnits);
            coolerUnits = List.copyOf(coolerUnits);
            productBores = Map.copyOf(productBores);
            lineBores = Map.copyOf(lineBores);
        }

        /** The design's diameter, the least pumps, {@code coolerUnit} on every pumparound, the product lines at {@code productBore}. */
        public Options(double scale, @Nullable java.util.function.Function<MaterialCatalog, V3ColumnInput> design, ColumnComponents.Condenser condenser,
                EquipmentShape condenserUnit, @Nullable EquipmentShape trimUnit, EquipmentShape coolerUnit, @Nullable EquipmentShape kettleUnit,
                double feedBore, double steamBore, double waterBore, double productBore, boolean motors, boolean shutoffs, Layout layout) {
            this(scale, design, condenser, condenserUnit, trimUnit, coolerUnit, kettleUnit, feedBore, steamBore, waterBore, productBore, motors, shutoffs, layout,
                    null, null, List.of(), List.of(), Map.of(), Map.of(), 0, null);
        }

        public EquipmentShape pumpUnit(int j) {
            return j < pumpUnits.size() ? pumpUnits.get(j) : PlantBuilder.unit(Kind.PUMP);
        }

        public EquipmentShape coolerUnit(int j) {
            return j < coolerUnits.size() ? coolerUnits.get(j) : coolerUnit;
        }
    }

    /** Whether every column from {@code low} to {@code high} has its surface at {@code ground}: nothing stands on it. */
    public static boolean clear(net.minecraft.server.level.ServerLevel level, BlockPos low, BlockPos high, int ground) {
        for (int x = low.getX(); x <= high.getX(); x++)
            for (int z = low.getZ(); z <= high.getZ(); z++) {
                level.getChunk(x >> 4, z >> 4);
                if (level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) != ground) return false;
            }
        return true;
    }

    /** The design at {@code scale} of its feed, its draws, steam, pumparound duties and reboiler duty, its column area in step. */
    public static V3ColumnInput scaled(V3ColumnInput in, double scale) {
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
    public static V3ColumnInput withDiameter(V3ColumnInput in, double diameter) {
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), in.feedComponentMolarFlowsMolPerSecond(), in.feedTemperatureKelvin(),
                in.stageCount(), in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(), in.sideDraws(), in.steamFeeds(), in.pumparounds(),
                diameter);
    }

    /**
     * The extent of {@code shape}'s cells and nozzle pipe blocks, {min x, max x, min y, max y, min z, max z}, from its origin;
     * a shape that rasterizes no cells spans its size from the origin.
     */
    public static int[] extent(EquipmentShape shape) {
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

    /** The rig's receiver: a lying drum heading north on saddles {@code support} high, with its boot. */
    public static EquipmentShape receiver(double diameter, double length, int support) {
        return EquipmentShape.drum(diameter, length, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, support,
                EquipmentShape.defaultConnections(diameter, length, EquipmentShape.Orientation.HORIZONTAL, support, true), true);
    }

    /** The height above the ground of the foot of the boundary line out of {@code shape}'s {@code role} nozzle ({@link Rig#line}: three pipes, then its end). */
    public static int foot(EquipmentShape shape, Role role) {
        for (var p : ShapeRasterizer.ports(shape))
            if (shape.nozzles().get(p.nozzle()).role() == role) return p.cell().y() + p.dy() + 3 * p.dy();
        throw new IllegalStateException("The " + shape.kind() + " has no " + role);
    }

    /** {@code exchanger} on the least support from its own up that its {@code role} boundary line stands on. */
    public static EquipmentShape standing(EquipmentShape exchanger, Role role) {
        var shape = exchanger;
        for (int support = exchanger.support(); foot(shape, role) < 0; )
            shape = EquipmentShape.designed(exchanger.kind(), exchanger.diameter(), (int) Math.round(exchanger.tangentLength()), EquipmentShape.Heading.NORTH, ++support, exchanger.bank())
                    .withExchangerType(exchanger.exchangerType());
        return shape;
    }

    /** The column's tag on the review plant's sheet. */
    private static final String TOWER = "T-1";

    /**
     * The review plant as the process simulator sizes it (batch 2026-11-16-review-plant-sizing): the column's diameter, the
     * condenser's shells and their count, the receiver, the reflux pump, each pumparound's cooler and pump, and each boundary
     * line's bore by its name ({@code feed}, {@code steam1}, the products'), and each internal line's by the rig's name
     * ({@link Rig#routes}).
     */
    public record SizedPlant(V3ColumnInput input, double diameter, EquipmentShape condenser, int condensers, EquipmentShape receiver, EquipmentShape refluxPump,
                      List<EquipmentShape> coolers, List<EquipmentShape> pumps, Map<String, Double> bores, Map<String, Double> lines,
                      EquipmentShape chargePump, EquipmentShape exchanger, EquipmentShape heater, int heaters, double fuelBore, double chargeSetFlow) {
    }

    /**
     * The crude unit ahead of the tower (owner, 2026-11-17): the charge pump, the residue exchanger and the fired heaters (in
     * parallel), each shape and where each stands (offsets from the column's origin); the crude's supply bore, m, and each
     * heater's fuel line's; the charge pump's set flow at suction, m3/s. The pumparound coolers' cold sides carry the crude (no
     * cooling water), the bottoms runs through the exchanger's shell to the residue's void, and the heaters' outlets are the tower's feed line.
     */
    public record Preheat(EquipmentShape chargePump, BlockPos chargePumpAt, EquipmentShape exchanger, BlockPos exchangerAt, EquipmentShape heater, List<BlockPos> heatersAt,
                   double crudeBore, double fuelBore, double chargeSetFlow) {
        public Preheat {
            heatersAt = List.copyOf(heatersAt);
        }
    }

    /**
     * The review plant sized by the process simulator's rules: a one-column sheet of the network's default template at
     * {@link #SIZED_SCALE} (its feed at its own temperature and {@link #SUPPLY}, its steam rows as wired steam at {@link #SUPPLY}, a
     * product stream on every outlet, the condenser's and the pumparound coolers' water ports free, so each is rated on tower
     * water), run, then Size all ({@code Proposal.sizeAll}: the column's half-metre diameters by real solves, every member
     * Auto, exchangers on their design deposit with the 20 % spare, at {@link DesignBasis#DEFAULT}'s design flow). Logs one
     * {@code review_operation sizing} line per unit and line, with every rating row that is not green.
     *
     * @throws IllegalStateException where a run is not steady, or a size is one the rig does not build
     */
    public static SizedPlant sized(MaterialCatalog catalog) {
        String network = FluidMaterialCatalog.networkPackage(catalog);
        return MaterialRuntime.with(catalog, network, () -> sizing(catalog, network));
    }

    private static SizedPlant sizing(MaterialCatalog catalog, String network) {
        var template = com.wormzjl.icheme.science.column.v3.V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog);
        var input = scaled(template, SIZED_SCALE);
        var model = FluidThermodynamics.forNetwork(catalog, network);
        var params = ColumnDoor.from(input, ColumnComponents.Condenser.EXCHANGER);
        var door = ColumnDoor.of(params);
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        for (int c = 0; c < flows.length; c++) amounts.merge(input.componentBasis().componentId(c), flows[c], Double::sum);
        var streams = new ArrayList<Flowsheet.Stream>();
        // The crude unit (owner, 2026-11-17): the crude from storage through the charge pump, the pumparound coolers' cold sides
        // from the coolest (the top) down, the residue exchanger's tubes and the fired heater into the tower's feed; the bottoms
        // through the exchanger's shell to the residue's rundown.
        streams.add(new Flowsheet.Stream(CRUDE, CRUDE, null, new Flowsheet.End(CHARGE_PUMP, "in"), new Flowsheet.Feed(CRUDE_TEMPERATURE, CRUDE_SUPPLY, amounts, List.of())));
        var from = new Flowsheet.End(CHARGE_PUMP, "out");
        for (int j = 0; j < door.pumparounds().size(); j++) {
            var pa = door.pumparounds().get(j);
            // A pumparound cooler's cold side by its passage's ends (ColumnUnit.passages): a in, b out.
            streams.add(new Flowsheet.Stream(preheat(j), preheat(j), from, new Flowsheet.End(TOWER, ColumnUnit.PUMPAROUND + pa.id() + "/a"), null));
            from = new Flowsheet.End(TOWER, ColumnUnit.PUMPAROUND + pa.id() + "/b");
        }
        int n = door.pumparounds().size();
        streams.add(new Flowsheet.Stream(preheat(n), preheat(n), from, new Flowsheet.End(RESIDUE_EXCHANGER, "tube_a"), null));
        streams.add(new Flowsheet.Stream(preheat(n + 1), preheat(n + 1), new Flowsheet.End(RESIDUE_EXCHANGER, "tube_b"), new Flowsheet.End(HEATER, "a"), null));
        streams.add(new Flowsheet.Stream(CHARGE, CHARGE, new Flowsheet.End(HEATER, "b"), new Flowsheet.End(TOWER, ColumnUnit.FEED), null));
        streams.add(new Flowsheet.Stream(BOTTOMS, BOTTOMS, new Flowsheet.End(TOWER, ColumnUnit.BOTTOMS), new Flowsheet.End(RESIDUE_EXCHANGER, "shell_a"), null));
        streams.add(new Flowsheet.Stream(RESIDUE, RESIDUE, new Flowsheet.End(RESIDUE_EXCHANGER, "shell_b"), null, null));
        var units = List.of(new Flowsheet.Unit(TOWER, "column", TOWER, 0, 0, params),
                new Flowsheet.Unit(CHARGE_PUMP, "pump", CHARGE_PUMP, 0, 0, json("{\"target\":\"outlet\",\"pressure\":" + CHARGE_PRESSURE + "}")),
                new Flowsheet.Unit(RESIDUE_EXCHANGER, "exchanger", RESIDUE_EXCHANGER, 0, 0, json("{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":" + RESIDUE_TEMPERATURE
                        + ",\"tube_drop\":0.5e5,\"shell_drop\":0.3e5}")),
                new Flowsheet.Unit(HEATER, "fired_heater", HEATER, 0, 0, json("{\"coils\":\"series\",\"target\":\"outlet\",\"temperature\":" + input.feedTemperatureKelvin()
                        + ",\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}")));
        for (int s = 0; s < door.steam().size(); s++) {
            var row = door.steam().get(s);
            streams.add(new Flowsheet.Stream("steam" + (s + 1), "steam" + (s + 1), null, new Flowsheet.End(TOWER, ColumnUnit.steamPort(row)),
                    new Flowsheet.Feed(row.temperature(), SUPPLY, Map.of("Water", row.flow()), List.of())));
        }
        streams.add(product("offgas", ColumnUnit.OVERHEAD));
        streams.add(product("distillate", ColumnUnit.DISTILLATE));
        streams.add(product("water", ColumnUnit.WATER));
        for (int j = 0; j < door.draws().size(); j++) streams.add(product("draw" + (j + 1), ColumnUnit.drawPort(door.draws().get(j))));
        var sheet = new Flowsheet(new Flowsheet.Basis(network, catalog.requirePackage(network).revision(), model.components()), units, streams, FlowsheetSolver.Settings.DEFAULT);
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
        var refluxPump = pump(require(sized, SheetSizing.member(TOWER, "reflux pump")));
        var coolers = new ArrayList<EquipmentShape>();
        var pumps = new ArrayList<EquipmentShape>();
        for (var pumparound : door.pumparounds()) {
            var cooler = (ExchangerSizing.Size) require(sized, SheetSizing.member(TOWER, SheetSizing.PumparoundMember.name(pumparound.id(), SheetSizing.PumparoundMember.COOLER))).size();
            if (cooler.shells() != 1)
                throw new IllegalStateException("Pumparound " + pumparound.id() + "'s cooler is sized at " + cooler.shells() + " shells: the rig places one cooler shell a pumparound");
            coolers.add(cooler.shape());
            pumps.add(pump(require(sized, SheetSizing.member(TOWER, SheetSizing.PumparoundMember.name(pumparound.id(), "pump")))));
        }
        // The column's own lines by the rig's names. The condenser's outlet to the receiver has no line of its own on the sheet:
        // the rig lays it at the overhead's bore. The reflux pump's discharge and the reflux are one laid line: the larger.
        var lines = new LinkedHashMap<String, Double>();
        lines.put("overhead", internal(sized, "overhead"));
        lines.put("receiver out", internal(sized, "receiver out"));
        lines.put("reflux", Math.max(internal(sized, "pump out"), internal(sized, "reflux")));
        for (int j = 0; j < door.pumparounds().size(); j++) {
            var id = door.pumparounds().get(j).id();
            lines.put("pa" + (j + 1) + " draw", internal(sized, SheetSizing.PumparoundMember.name(id, "draw")));
            lines.put("pa" + (j + 1) + " return", internal(sized, SheetSizing.PumparoundMember.name(id, "return")));
        }
        // The condenser's tower water (its free water side), at the flow its rating gives, on the sizer's water line rule; the
        // pumparound coolers' cold sides are the crude's.
        lines.put("condenser water", water("condenser", require(sized, SheetSizing.member(TOWER, "condenser")).rating(), condenser.shells()));
        var bores = new LinkedHashMap<String, Double>();
        for (var stream : streams) {
            var line = sized.lines().get(stream.id());
            if (line == null || line.size() == null) throw new IllegalStateException("The " + stream.id() + " line has no size");
            bores.put(stream.id(), bore(line.size()));
        }
        // The crude unit's lines between units are laid by the rig under their stream's name.
        for (int k = 0; k < door.pumparounds().size() + 2; k++) lines.put(preheat(k), bores.remove(preheat(k)));
        lines.put(CHARGE, bores.remove(CHARGE));
        lines.put(BOTTOMS, bores.remove(BOTTOMS));
        for (var unit : List.of(CHARGE_PUMP, RESIDUE_EXCHANGER, HEATER))
            IChemE.LOGGER.info("review_operation sizing readings unit=\"{}\" {} rating={}", unit, all.run().reports().containsKey(unit) ? all.run().reports().get(unit).readings() : all.run().reports().keySet(),
                    require(sized, unit).rating().readings().stream().map(r -> r.limit().name() + "=" + String.format(Locale.ROOT, "%.4g[%.4g,%.4g]", r.value(), r.low(), r.high())).toList());
        var chargePump = pump(require(sized, CHARGE_PUMP));
        var exchanger = (ExchangerSizing.Size) require(sized, RESIDUE_EXCHANGER).size();
        if (exchanger.shells() != 1) throw new IllegalStateException("The residue exchanger is sized at " + exchanger.shells() + " shells: the rig places one");
        if (!(require(sized, HEATER).size() instanceof com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing.Size running))
            throw new IllegalStateException("The heater's size is not a fired heater's");
        // The heater for the cold start (owner, 2026-11-17: "Bigger furnace"): the crude from the charge pump to the tower's feed
        // with no pumparound or residue heat yet, the charge's enthalpy over the pumped crude's, at the design flow, on Auto.
        var streamsRun = all.run().streams();
        double cold = (streamsRun.get(CHARGE).enthalpy() - streamsRun.get(preheat(0)).enthalpy()) * DesignBasis.DEFAULT.designFlow() / 1e6;
        var heater = com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing.auto(new com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing.Load(cold, null), null).size();
        if (heater == null) throw new IllegalStateException("No heater holds the cold start's " + cold + " MW");
        IChemE.LOGGER.info("review_operation sizing heater running={} cold_start_MW={} cold_start={}", running, String.format(Locale.ROOT, "%.1f", cold), heater);
        // The charge pump's set flow (owner, 2026-11-17; PUMP_DRIVE_FLOW_PLAN.md): 105 % of the design feed's volume flow at the
        // pump's suction (the crude stream's state), its drive regulating the flow. The column's feed valve asks up to twice its
        // rate while its account is short of its aim (ColumnOperation.inletPlan), so the pump holds the flow and that valve stands
        // open; at 100 % the account would never fill. The built heaters must hold the cold start at that flow with 5 % spare.
        double chargeMass = CHARGE_SHARE * streamsRun.get(preheat(0)).mass();
        var suction = java.util.Objects.requireNonNull(streamsRun.get(CRUDE).state(), "the crude's suction state");
        double suctionDensity = suction.mass() / suction.volume(), chargeSetFlow = chargeMass / suctionDensity;
        double coldAtCharge = cold / DesignBasis.DEFAULT.designFlow() * CHARGE_SHARE, held = heater.held();
        IChemE.LOGGER.info("review_operation sizing charge_pump set_m3_s={} kg_s={} suction_kg_m3={} cold_start_at_charge_MW={} heaters_held_MW={} spare_pct={}",
                String.format(Locale.ROOT, "%.5f", chargeSetFlow), String.format(Locale.ROOT, "%.2f", chargeMass), String.format(Locale.ROOT, "%.1f", suctionDensity),
                String.format(Locale.ROOT, "%.1f", coldAtCharge), String.format(Locale.ROOT, "%.1f", held), String.format(Locale.ROOT, "%.1f", 100 * (held / coldAtCharge - 1)));
        if (held < 1.05 * coldAtCharge) throw new IllegalStateException("The heaters hold " + held + " MW, under 105 % of the cold start at the charge pump's flow, " + coldAtCharge + " MW");
        // Each of Auto's heaters in parallel fires its share; its fuel line is sized for that share of the cold start.
        double heaterDuty = cold * 1e6 / DesignBasis.DEFAULT.designFlow() / heater.count();
        IChemE.LOGGER.info("review_operation sizing column diameter_m={} trays={} flood_pct={} condenser_shells={} condenser_MW={} bores={}", columnSize.diameter(),
                input.stageCount(), flood == null ? "none" : String.format(Locale.ROOT, "%.1f", flood.value()), condenser.shells(),
                String.format(Locale.ROOT, "%.3f", run.reports().get(TOWER).readings().getOrDefault("condenser", Double.NaN) / 1e6), bores);
        double diameter = ColumnDesign.builtDiameter(columnSize.diameter());
        return new SizedPlant(withDiameter(input, diameter), diameter, condenser.shape(), condenser.shells(), receiver.shape(), refluxPump, coolers, pumps, bores, lines,
                chargePump, exchanger.shape(), heater.shape(), heater.count(), fuel(heaterDuty), chargeSetFlow);
    }

    /**
     * The bore of the heater's fuel gas line, m: methane at {@link #FUEL_SUPPLY} and 25 °C, fired at {@code duty} W over the
     * burners' share ({@code Burners}: the heater takes 80 % of the fuel's heat) at its lower heating value, 50.0 MJ/kg, at the
     * design flow, on Auto's line rule for gas.
     */
    private static double fuel(double duty) {
        double mass = duty / .80 / 50.0e6 * DesignBasis.DEFAULT.designFlow(), density = FUEL_SUPPLY * .01604 / (8.314 * 298.15);
        var line = new com.wormzjl.icheme.science.flowsheet.sizing.LineSizing.Line(com.wormzjl.icheme.science.flowsheet.sizing.Service.GAS, mass, mass / density, density,
                1.1e-5, FUEL_SUPPLY, DesignBasis.DEFAULT.length(null), PipeSizes.MAX_BORE, WATER_SPEED_LIMIT, null);
        var size = com.wormzjl.icheme.science.flowsheet.sizing.LineSizing.auto(line).size();
        if (size == null) throw new IllegalStateException("No line size takes the heater's fuel, " + mass + " kg/s");
        IChemE.LOGGER.info("review_operation sizing line=\"fuel\" heater_MW={} kg_s={} bore={} runs={}", String.format(Locale.ROOT, "%.1f", duty / 1e6),
                String.format(Locale.ROOT, "%.2f", mass), size.bore(), size.runs());
        return bore(size);
    }

    /** The crude unit's sheet names (owner, 2026-11-17): its feed, units and the streams between them. */
    public static final String CRUDE = "crude", CHARGE_PUMP = "P-101", RESIDUE_EXCHANGER = "E-101", HEATER = "H-101", CHARGE = "charge", BOTTOMS = "bottoms", RESIDUE = "residue";
    /**
     * The crude from storage, K and Pa: 25 °C, at a storage tank's 1.5 bar, so the charge pump makes the line's head and its drive holds the flow (PUMP_DRIVE_FLOW_REVIEW.md); the charge pump's outlet, Pa
     * (16 bar: the coolers' and the exchanger's tube drops, the heater's coils, 7.6 bar, and the feed valve above the tower's
     * 2.5 bar); the residue's outlet from the residue exchanger, K (200 °C, as the sheet example's E-101).
     */
    public static final double CRUDE_TEMPERATURE = 298.15, CRUDE_SUPPLY = 1.5e5, CHARGE_PRESSURE = 16e5, RESIDUE_TEMPERATURE = 473.15;
    /** The heater's fuel gas supply, Pa: methane at 3 bar. */
    public static final double FUEL_SUPPLY = 3e5;

    /** The {@code k}th preheat stream: the charge pump to the first cooler is 0, the residue exchanger to the heater the last. */
    public static String preheat(int k) {
        return "crude " + (k + 1);
    }

    private static com.wormzjl.icheme.science.flowsheet.Params json(String text) {
        return new com.wormzjl.icheme.science.flowsheet.Params(com.google.gson.JsonParser.parseString(text).getAsJsonObject());
    }

    /** The sized bore of the column's own line {@code name}, m. */
    private static double internal(SheetSizing.Sized sized, String name) {
        var line = sized.lines().get(SheetSizing.member(TOWER, name));
        if (line == null || line.size() == null) throw new IllegalStateException("The " + name + " line has no size: " + sized.lines().keySet());
        return bore(line.size());
    }

    /**
     * The bore of {@code unit}'s tower water line, m: the free water side's flow at design flow from its {@code rating}
     * ({@link Rating#WATER}, {@link Rating#WATER_VOLUME}) sized by Auto's line rule for water ({@code LineSizing.auto},
     * {@code Service.WATER}: at most 3 m/s and 0.45 bar per 100 m), liquid water at {@link #WATER_TEMPERATURE} and {@link #WATER};
     * one shell's share where the unit has {@code shells} in parallel.
     */
    private static double water(String unit, Rating rating, int shells) {
        Double total = rating.values().get(Rating.WATER), hourly = rating.values().get(Rating.WATER_VOLUME);
        if (total == null || hourly == null) throw new IllegalStateException("The " + unit + "'s rating gives no tower water: " + rating.values());
        double mass = total / shells, volume = hourly / 3600 / shells;
        var line = new com.wormzjl.icheme.science.flowsheet.sizing.LineSizing.Line(com.wormzjl.icheme.science.flowsheet.sizing.Service.WATER, mass, volume, mass / volume,
                WATER_VISCOSITY, WATER, DesignBasis.DEFAULT.length(null), PipeSizes.MAX_BORE, WATER_SPEED_LIMIT, null);
        var size = com.wormzjl.icheme.science.flowsheet.sizing.LineSizing.auto(line).size();
        if (size == null) throw new IllegalStateException("No line size takes the " + unit + "'s tower water, " + mass + " kg/s");
        IChemE.LOGGER.info("review_operation sizing line=\"{} water\" kg_s={} bore={} runs={}", unit, String.format(Locale.ROOT, "%.1f", mass), size.bore(), size.runs());
        return bore(size);
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

    /** A sized pump's shape: the rig drives a centrifugal casing, built at its sized duty ({@link PumpSizing#built(SheetSizing.SizedUnit)}). */
    private static EquipmentShape pump(SheetSizing.SizedUnit unit) {
        if (!(unit.size() instanceof PumpSizing.Size pump)) throw new IllegalStateException("The sized pump is " + unit.size() + ": the rig builds a centrifugal pump");
        var shape = PumpSizing.built(unit);
        if (shape == null) throw new IllegalStateException("The constructor refuses the sized pump " + pump + " at " + PumpSizing.Duty.of(unit.rating()));
        IChemE.LOGGER.info("review_operation sizing pump_duty discharge_m={} rated_m3_h={} head_m={} poles={} speed_rpm={}", shape.diameter(),
                String.format(Locale.ROOT, "%.1f", shape.duty().flow()), String.format(Locale.ROOT, "%.2f", shape.duty().head()), shape.duty().poles(),
                String.format(Locale.ROOT, "%.0f", UnitDesign.designSpeed(shape)));
        return shape;
    }

    /** A sized line's bore, m: its runs as one line of the same area, up to the next 0.05 m, at most the largest bore. */
    public static double bore(com.wormzjl.icheme.science.flowsheet.sizing.LineSizing.Size size) {
        return size.runs() == 1 ? size.bore() : Math.min(PipeSizes.MAX_BORE, Math.ceil(size.bore() * Math.sqrt(size.runs()) * 20 - 1e-9) / 20);
    }

    private static String describe(EquipmentShape shape) {
        return String.format(Locale.ROOT, "%s:%.2fx%.1f:support=%d%s", shape.kind(), shape.diameter(), shape.tangentLength(), shape.support(),
                shape.kind() == Kind.EXCHANGER ? ":" + shape.exchangerType() : "");
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
    public static Options review(MaterialCatalog catalog) {
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
        var receiver = receiver(dd, dl, support);
        // The boot's drain line (three pipes, then its void) runs down from the boot's nozzle: the least support it stands on.
        while (foot(receiver, Role.BOOT_DRAIN) < 0) receiver = receiver(dd, dl, ++support);
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
        // The crude unit: the charge pump west of the first cooler, the residue exchanger at the end of the coolers' row (the
        // crude runs down the row), the heater south-east of the column, where the tower's feed line can reach it.
        var c0 = extent(s.coolers().getFirst());
        var cp = extent(s.chargePump());
        var chargeAt = new BlockPos(coolers.getFirst().getX() + c0[0] - gap - cp[1], 0, coolers.getFirst().getZ() + c0[4] - cp[4]);
        west = Math.min(west, chargeAt.getX() + cp[0]);
        var cl = extent(s.coolers().getLast());
        // Its shell's outlet is the residue's boundary line, straight out: its saddles high enough for that line to clear the ground.
        var exchanger = standing(s.exchanger(), Role.SHELL_OUTLET);
        var ex = extent(exchanger);
        var exchangerAt = new BlockPos(coolers.getLast().getX() + cl[1] - ex[1], 0, z - ex[4]);
        west = Math.min(west, exchangerAt.getX() + ex[0]);
        south = Math.max(south, z + ex[5] - ex[4]);
        var hx = extent(s.heater());
        var heatersAt = new ArrayList<BlockPos>();
        for (int k = 0; k < s.heaters(); k++) heatersAt.add(new BlockPos(column[1] + 2 * gap - hx[0] + k * (hx[1] - hx[0] + 1 + 2 * gap), 0, column[5] + gap - hx[4]));
        var heaterAt = heatersAt.getLast();
        south = Math.max(south, heaterAt.getZ() + hx[5]);
        int east = Math.max(column[1] + 24, heaterAt.getX() + hx[1] + 12), top = Math.max(Math.max(column[3] + 8, 44), hx[3] + 8);
        var layout = new Layout(condensers, List.of(), drumAt, dd, dl, support, refluxAt, pumps, coolers, null, Layout.REVIEW.corner(),
                new BlockPos(west - 12, 0, north - Math.max(depth, r[5] - r[4]) - 16), new BlockPos(east, top, south + 16));
        var bores = new LinkedHashMap<>(s.bores());
        double crude = bores.remove(CRUDE), steam = bores.remove("steam1");
        bores.keySet().removeIf(k -> k.startsWith("steam"));
        var preheat = new Preheat(s.chargePump(), chargeAt, exchanger, exchangerAt, s.heater(), heatersAt, crude, s.fuelBore(), s.chargeSetFlow());
        IChemE.LOGGER.info("review_operation sizing layout condensers={} drum={} support={} reflux_pump={} pumps={} coolers={} charge_pump={} exchanger={} heater={} below={} above={}",
                condensers, drumAt.toShortString(), support, refluxAt.toShortString(), pumps, coolers, chargeAt.toShortString(), exchangerAt.toShortString(), heatersAt,
                layout.below().toShortString(), layout.above().toShortString());
        // The tower's feed line is the charge line out of the heater.
        return new Options(SIZED_SCALE, null, ColumnComponents.Condenser.EXCHANGER, s.condenser(), null, s.coolers().getFirst(), null, s.lines().get(CHARGE), steam, 0, 0, false, false, layout,
                s.diameter(), s.refluxPump(), s.pumps(), s.coolers(), bores, s.lines(), BEND_COST, preheat);
    }

    public static final class Rig {
        public final PlantBuilder plant;
        public final Options options;
        public final List<BlockPos> sinks = new ArrayList<>();
        public BlockPos feed, steam, water;
        /** Each pumparound cooler's cooling-water generator (W4). */
        public final List<BlockPos> coolingWater = new ArrayList<>();
        /** Every water-cooled condenser unit's generator (the exchanger's shells, or the trim's), {@link #water} the first. */
        public final List<BlockPos> condenserWater = new ArrayList<>();
        /** The kettle's hot utility's generator, or null. */
        public BlockPos heating;
        /** The crude unit's fired heaters in parallel and their fuel gas generators ({@link Options#preheat}), {@code heater} the first or null. */
        public final List<Vessel> heaters = new ArrayList<>();
        public final List<BlockPos> fuels = new ArrayList<>();
        public Vessel heater;
        /** The charge line's last cell before the tower's feed line ({@link #layPreheat}), where the start reads the crude's temperature. */
        public BlockPos chargeEnd;
        /** The charge pump ({@link Options#preheat}), or null; its drive regulates its flow ({@link #holdCharge}). */
        public Vessel chargePump;
        /** The charge pump's flow check ({@link #chargeCheck}), or null while it holds. */
        public String chargeFault;
        /** The online tick of the first report at which the charge pump's mass flow is above zero, or -1 before it. */
        public long chargeFlowing = -1;
        /** Whether {@link #chargeCheck} has made its check. */
        public boolean chargeChecked;
        /** The online ticks after the charge pump's first flow before its check: at least 6 of its drive's decisions (about 100 ticks each). */
        public static final long CHARGE_TICKS = 800;
        /** The shutoff valves beyond the boundary valves, each with its axis ({@link Options#shutoffs}), and which line each is on. */
        public final java.util.Map<BlockPos, Direction> shutoffs = new java.util.LinkedHashMap<>();
        public final java.util.Map<String, BlockPos> lines = new java.util.LinkedHashMap<>();
        /** Each boundary line's end (its generator or void) by its name, the distillate's void with them. */
        public final java.util.Map<String, BlockPos> ends = new java.util.LinkedHashMap<>();
        /**
         * The laid paths of each internal line by its name: {@code overhead} (the column to the condenser and on to the receiver,
         * every further shell's branches), {@code receiver out} (to the reflux pump), {@code reflux} (the pump's discharge to the
         * column), {@code paN draw} (to the pump), {@code paN return} (the pump through the cooler back to the column).
         */
        public final java.util.Map<String, List<List<BlockPos>>> routes = new java.util.LinkedHashMap<>();
        /** Each utility line's generator and void by its internal line's name ({@link #water(Vessel, BlockState, BlockState, String, String)}). */
        public final java.util.Map<BlockPos, String> utilities = new java.util.LinkedHashMap<>();
        /** The bore each internal line was laid at by {@link #finish} ({@link Options#lineBores}, within its nozzles'), m. */
        public final java.util.Map<String, Double> laidBores = new java.util.LinkedHashMap<>();
        /** The units as placed: the condenser's, the trim's, the receiver, the pumps (the reflux pump first), the coolers, the kettle. */
        public final List<Vessel> condensers = new ArrayList<>(), trims = new ArrayList<>(), pumps = new ArrayList<>(), coolers = new ArrayList<>(), motors = new ArrayList<>();
        public Vessel kettle;
        /** The feed line's last pipe where the feed comes from upstream units the caller builds ({@link #layout(boolean)}). */
        public BlockPos feedLine;
        public V3ColumnInput input;
        public ColumnDesign design;

        public Rig(PlantBuilder plant) {
            this(plant, Options.REVIEW);
        }

        public Rig(PlantBuilder plant, Options options) {
            this.plant = plant;
            this.options = options;
        }

        /** The plant, its valves and its supplies: {@link #layout(boolean)}, then {@link #finish()}. */
        public void build() {
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
        public void layout(boolean openFeed) {
            plant.hold();
            plant.bendCost = options.bendCost();
            MaterialCatalog catalog = MaterialRuntime.active();
            var base = options.design() == null ? com.wormzjl.icheme.science.column.v3.V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog) : options.design().apply(catalog);
            input = scaled(base, options.scale());
            if (options.diameter() != null) input = withDiameter(input, options.diameter());
            var layout = options.layout();
            int pumparounds = input.pumparounds().size();
            // The blueprint asks for the configuration of the exchangers the rig places (a sized shell may be crossflow).
            var coolerTypes = new ArrayList<com.wormzjl.icheme.science.equipment.ExchangerType>();
            for (int j = 0; j < pumparounds; j++) coolerTypes.add(type(options.coolerUnit(j)));
            var choices = new ColumnComponents.Choices(options.condenser(), ColumnComponents.Pump.STANDARD, java.util.Collections.nCopies(pumparounds, ColumnComponents.Pump.STANDARD),
                    new ColumnComponents.Units(layout.condenser().size(), 1, java.util.Collections.nCopies(pumparounds, 1), Math.max(1, layout.trim().size())),
                    new ColumnComponents.Exchangers(type(options.condenserUnit()), options.trimUnit() == null ? com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD : type(options.trimUnit()), coolerTypes));
            design = ColumnDesign.of(input, choices);
            var shape = EquipmentShape.column(design.diameter(), design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(design.diameter()));
            ColumnDesign.misfit(shape).ifPresent(reason -> {
                throw new IllegalStateException(reason);
            });
            var column = plant.column = plant.place(shape, 0, 0, 0);
            for (var at : layout.condenser()) condensers.add(plant.place(options.condenserUnit(), at.getX(), at.getY(), at.getZ()));
            for (var at : layout.trim()) trims.add(plant.place(java.util.Objects.requireNonNull(options.trimUnit()), at.getX(), at.getY(), at.getZ()));
            double dd = layout.drumDiameter(), dl = layout.drumLength();
            var drum = plant.drum = plant.place(receiver(dd, dl, layout.drumSupport()), layout.drum().getX(), layout.drum().getY(), layout.drum().getZ());
            var refluxPump = plant.place(options.refluxPumpUnit() != null ? options.refluxPumpUnit() : PlantBuilder.unit(Kind.PUMP),
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
            var preheat = options.preheat();
            Vessel chargePump = null, exchanger = null;
            if (preheat != null) {
                chargePump = plant.place(preheat.chargePump(), preheat.chargePumpAt().getX(), 0, preheat.chargePumpAt().getZ());
                this.chargePump = chargePump;
                exchanger = plant.place(preheat.exchanger(), preheat.exchangerAt().getX(), 0, preheat.exchangerAt().getZ());
                for (var at : preheat.heatersAt()) heaters.add(plant.place(preheat.heater(), at.getX(), 0, at.getZ()));
                heater = heaters.getFirst();
                pumps.add(chargePump);
            }
            if (design.components().kettle()) {
                var k = java.util.Objects.requireNonNull(layout.kettle(), "the design has a kettle: the layout places it");
                kettle = plant.place(java.util.Objects.requireNonNull(options.kettleUnit()), k.getX(), k.getY(), k.getZ());
            }
            // A motor on FE beyond each pump's coupling (stage 5), before the lines, which go round it.
            if (options.motors()) for (var pump : pumps) motors.add(plant.motor(pump));

            // Boundary lines: the feed and steam valves point at the column, the products' away from it.
            var generator = ModBlocks.FLUID_GENERATOR.get().defaultBlockState();
            var sink = ModBlocks.FLUID_VOID.get().defaultBlockState();
            if (openFeed || preheat != null) feedLine = line(column, Role.FEED, 0, false, null, "feed");
            else feed = line(column, Role.FEED, 0, false, generator, "feed");
            BlockPos bottomsLine = null;
            if (preheat != null) {
                // The crude's supply into the charge pump and the heater's fuel gas: no valve of the plant's on either (the
                // pump draws what the tower's feed valve passes; the burners' own valve meters the fuel).
                feed = supplyLine(chargePump, Role.SUCTION, generator, CRUDE);
                for (int k = 0; k < heaters.size(); k++) fuels.add(supplyLine(heaters.get(k), Role.FUEL, generator, "fuel" + (k + 1)));
                // The bottoms to the residue exchanger's shell, the residue out of it to its void.
                bottomsLine = line(column, Role.BOTTOMS, 0, true, null, BOTTOMS);
                sinks.add(line(exchanger, Role.SHELL_OUTLET, 0, true, sink, RESIDUE));
                for (var h : heaters) power(h);
            }
            for (int s = 0; s < input.steamFeeds().size(); s++) {
                var at = line(column, Role.STEAM, s, false, generator, "steam" + (s + 1));
                if (s == 0) steam = at;
            }
            for (int j = 0; j < input.sideDraws().size(); j++) sinks.add(line(column, Role.DRAW, j, true, sink, "draw" + (j + 1)));
            if (preheat == null) sinks.add(kettle == null ? line(column, Role.BOTTOMS, 0, true, sink, "bottoms") : line(kettle, Role.LIQUID_OUTLET, 0, true, sink, "bottoms"));
            sinks.add(line(drum, Role.VAPOUR_OUTLET, 0, true, sink, "offgas"));
            sinks.add(line(drum, Role.BOOT_DRAIN, 0, true, sink, "water"));
            // The condenser's water: a generator into its tubes, out into a void (the trim's, with air then water).
            var cooled = options.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER ? trims : options.condenser() == ColumnComponents.Condenser.EXCHANGER ? condensers : List.<Vessel>of();
            for (int i = 0; i < cooled.size(); i++) condenserWater.add(water(cooled.get(i), generator, sink, i == 0 ? "trim" : "trim" + (i + 1), "condenser water"));
            water = condenserWater.isEmpty() ? null : condenserWater.getFirst();
            // Each pumparound cooler's water the same way (W4: the column owes it the pumparound's duty; without it the column
            // reads LIMITED: pumparound).
            if (preheat == null) for (int j = 0; j < coolers.size(); j++) coolingWater.add(water(coolers.get(j), generator, sink, "pa" + (j + 1), "pa" + (j + 1) + " water"));
            if (kettle != null) heating = water(kettle, generator, sink, "heating", "heating");

            // The overhead: column, condenser (an air cooler's process side, then the trim's shell, or an exchanger's shell),
            // receiver, reflux pump, the distillate's tee, the reflux back.
            if (options.condenser() == ColumnComponents.Condenser.EXCHANGER) {
                var in = laid("overhead", plant.route(PlantBuilder.front(column, Role.VAPOUR_OUTLET), PlantBuilder.front(condensers.getFirst(), Role.SHELL_INLET)));
                var out = laid("overhead", plant.route(PlantBuilder.front(condensers.getFirst(), Role.SHELL_OUTLET), PlantBuilder.front(drum, Role.SIDE)));
                // Every further shell in parallel with the first, as an air cooler's banks.
                for (var shell : condensers.subList(1, condensers.size())) {
                    laid("overhead", plant.join(in, PlantBuilder.front(shell, Role.SHELL_INLET)));
                    laid("overhead", plant.join(out, PlantBuilder.front(shell, Role.SHELL_OUTLET)));
                }
            } else {
                var in = plant.route(PlantBuilder.front(column, Role.VAPOUR_OUTLET), PlantBuilder.front(condensers.getFirst(), Role.PROCESS_INLET));
                var out = trims.isEmpty() ? plant.route(PlantBuilder.front(condensers.getFirst(), Role.PROCESS_OUTLET), PlantBuilder.front(drum, Role.SIDE))
                        : plant.route(PlantBuilder.front(condensers.getFirst(), Role.PROCESS_OUTLET), PlantBuilder.front(trims.getFirst(), Role.SHELL_INLET));
                if (!trims.isEmpty()) plant.route(PlantBuilder.front(trims.getFirst(), Role.SHELL_OUTLET), PlantBuilder.front(drum, Role.SIDE));
                // Every further bank in parallel with the first: its inlet off the overhead line, its outlet onto the first's.
                for (var bank : condensers.subList(1, condensers.size())) {
                    plant.join(in, PlantBuilder.front(bank, Role.PROCESS_INLET));
                    plant.join(out, PlantBuilder.front(bank, Role.PROCESS_OUTLET));
                }
            }
            laid("receiver out", plant.route(PlantBuilder.front(drum, Role.LIQUID_OUTLET), PlantBuilder.front(refluxPump, Role.SUCTION)));
            var reflux = laid("reflux", plant.route(PlantBuilder.front(refluxPump, Role.DISCHARGE), front(column, Role.REFLUX, 0)));
            var before = new HashSet<>(plant.devices);
            plant.tee(reflux);
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
                laid("pa" + (j + 1) + " draw", plant.route(front(column, Role.PUMPAROUND_DRAW, j), PlantBuilder.front(paPumps.get(j), Role.SUCTION)));
                laid("pa" + (j + 1) + " return", plant.route(PlantBuilder.front(paPumps.get(j), Role.DISCHARGE), PlantBuilder.front(coolers.get(j), Role.SHELL_INLET)));
                laid("pa" + (j + 1) + " return", plant.route(PlantBuilder.front(coolers.get(j), Role.SHELL_OUTLET), front(column, Role.RETURN, j)));
            }
            // The reboiler: the bottoms into the kettle, its vapour back under the bottom tray.
            if (kettle != null) {
                plant.route(PlantBuilder.front(column, Role.BOTTOMS), PlantBuilder.front(kettle, Role.FEED));
                plant.route(PlantBuilder.front(kettle, Role.VAPOUR_OUTLET), PlantBuilder.front(column, Role.VAPOUR_RETURN));
            }
            if (preheat != null) layPreheat(chargePump, exchanger, bottomsLine);
        }

        /**
         * The crude unit's lines (owner, 2026-11-17), each a laid path under its sheet stream's name: the charge pump into the
         * first pumparound cooler's tubes, cooler to cooler down the row, the last into the residue exchanger's tubes, out to the
         * heater's convection coil; the convection coil's outlet into both radiant passes, both passes' outlets onto the tower's
         * feed line ({@code charge}); the bottoms line into the exchanger's shell.
         */
        public void layPreheat(Vessel chargePump, Vessel exchanger, BlockPos bottomsLine) {
            var from = PlantBuilder.front(chargePump, Role.DISCHARGE);
            for (int j = 0; j < coolers.size(); j++) {
                laid(preheat(j), plant.route(from, PlantBuilder.front(coolers.get(j), Role.TUBE_INLET)));
                from = PlantBuilder.front(coolers.get(j), Role.TUBE_OUTLET);
            }
            int n = coolers.size();
            laid(preheat(n), plant.route(from, PlantBuilder.front(exchanger, Role.TUBE_INLET)));
            // The first heater on the line's own path, each further one's convection inlet off it; every radiant outlet onto the charge line.
            var heated = laid(preheat(n + 1), plant.route(PlantBuilder.front(exchanger, Role.TUBE_OUTLET), PlantBuilder.front(heater, Role.CONVECTION_INLET)));
            var out = laid(CHARGE, toOpen(front(heater, Role.RADIANT_OUTLET, 0), feedLine, out(plant.column, Role.FEED, 0)));
            chargeEnd = out.getLast();
            for (var h : heaters) {
                if (h != heater) laid(preheat(n + 1), plant.join(heated, PlantBuilder.front(h, Role.CONVECTION_INLET)));
                var radiant = laid(CHARGE, plant.route(PlantBuilder.front(h, Role.CONVECTION_OUTLET), front(h, Role.RADIANT_INLET, 0)));
                for (int k = 1; k < count(h, Role.RADIANT_INLET); k++) laid(CHARGE, plant.join(radiant, front(h, Role.RADIANT_INLET, k)));
                for (int k = h == heater ? 1 : 0; k < count(h, Role.RADIANT_OUTLET); k++) laid(CHARGE, plant.join(out, front(h, Role.RADIANT_OUTLET, k)));
            }
            laid(BOTTOMS, toOpen(PlantBuilder.front(exchanger, Role.SHELL_INLET), bottomsLine, out(plant.column, Role.BOTTOMS, 0)));
        }

        /**
         * A route from {@code from} to the open end {@code open} of a boundary line laid along {@code d} ({@link #line} with no
         * end: the cell beyond its last pipe), allowed beside that line's pipes.
         */
        public List<BlockPos> toOpen(BlockPos from, BlockPos open, Direction d) {
            return plant.route(from, open, java.util.Set.of(open.relative(d.getOpposite()), open.relative(d), open.relative(d, 2)));
        }

        /**
         * A supply line straight out of {@code vessel}'s {@code role} nozzle to {@code end} (a generator), as {@link #line} lays
         * it but without its valve. Returns the end's position.
         */
        public BlockPos supplyLine(Vessel vessel, Role role, BlockState end, String name) {
            var at = line(vessel, role, 0, false, end, name);
            plant.valves.remove(front(vessel, role, 0).relative(out(vessel, role, 0)));
            return at;
        }

        /** A creative power source beside {@code vessel}'s terminal box, its burners' and fans' FE (as a player wires one). */
        public void power(Vessel vessel) {
            var cell = ShapeRasterizer.terminal(vessel.shape());
            if (cell == null) throw new IllegalStateException("The " + vessel.shape().kind() + " has no terminal box");
            var box = vessel.origin().offset(cell.x(), cell.y(), cell.z());
            for (var d : Direction.values()) {
                var at = box.relative(d);
                if (!plant.level.getBlockState(at).isAir() || plant.devices.contains(at) || plant.reserved.contains(at)) continue;
                plant.level.setBlock(at, ModBlocks.CREATIVE_POWER.get().defaultBlockState(), Block.UPDATE_ALL);
                plant.devices.add(at);
                return;
            }
            throw new IllegalStateException("No room for power beside the " + vessel.shape().kind() + "'s terminal box at " + box.toShortString());
        }

        /** Every heater's coils fire to the tower's feed temperature (FIRING), once their registrations have applied. */
        public void fire() {
            for (var h : heaters) fire(h);
        }

        private void fire(Vessel heater) {
            var ids = com.wormzjl.icheme.world.level.block.EquipmentStructure.sideIdentities(plant.level, heater);
            var sides = com.wormzjl.icheme.science.equipment.HeatSides.of(heater.shape());
            for (int i = 0; i < Math.min(ids.length, sides.size()); i++) {
                var r = plant.world.registrations().get(ids[i]);
                if (r == null || r.device().heat() == null) throw new IllegalStateException("No registered " + sides.get(i).side() + " side on the heater");
                var control = new com.wormzjl.icheme.runtime.fluid.HeatUnit.Control(com.wormzjl.icheme.runtime.fluid.HeatUnit.Mode.FIRING, input.feedTemperatureKelvin(), 0, 1);
                plant.world.edit(ids[i], r.revision(), r.device().withHeat(r.device().heat().withControl(control)), r.spec());
            }
        }

        /**
         * The valves, the pumps' drivers and the supplies: the feed and the steam lines sized for their flows on both sides of
         * their valves (the network model's flash): at the review plant's scale the crude is 71 % vapour at 638 K and 5 bar
         * (30.2 kg/m³), 79 % at 632 K and 2.5 bar past the valve (13.5 kg/m³); its start target, 19.9 kg/s, runs at 336 and
         * 750 m/s in the least bore (0.05 m), 13 and 30 m/s at 0.25 m. The steam, 0.75 kg/s at the start (2.1 and 1.0 kg/m³),
         * runs at 185 and 370 m/s at 0.05 m, 21 and 41 m/s at 0.15 m. The velocity goes with the flow over the bore squared.
         */
        public void finish() {
            MaterialCatalog catalog = MaterialRuntime.active();
            var column = plant.column;
            for (var line : List.of(new Object[]{Role.FEED, options.feedBore(), 1}, new Object[]{Role.STEAM, options.steamBore(), input.steamFeeds().size()})) {
                var role = (Role) line[0];
                for (int n = 0; n < (int) line[2]; n++)
                    for (int k : options.shutoffs() ? new int[]{0, 2, 4} : new int[]{0, 2})
                        resizeLine(front(column, role, n).relative(out(column, role, n), k), (double) line[1]);
            }
            if (options.productBore() > 0) for (var at : sinks) resize(at, options.productBore());
            for (var line : options.productBores().entrySet()) {
                var at = ends.get(line.getKey());
                if (at == null) throw new IllegalStateException("No product line " + line.getKey() + " to size: " + ends.keySet());
                resize(at, line.getValue());
            }
            for (var line : options.lineBores().entrySet()) {
                var paths = routes.get(line.getKey());
                if (paths == null) throw new IllegalStateException("No internal line " + line.getKey() + " to size: " + routes.keySet());
                for (var path : paths) {
                    // One bore along a path: the sized one, or the largest its nozzles take.
                    double bore = line.getValue();
                    for (var at : path) bore = Math.min(bore, largest(at));
                    if (bore < line.getValue())
                        IChemE.LOGGER.info("review_operation sizing line=\"{}\" sized_bore={} laid_bore={} (the nozzle's largest)", line.getKey(), line.getValue(), bore);
                    for (var at : path) if (plant.world.at(plant.position(at)).isPresent()) resizeLine(at, bore);
                    laidBores.merge(line.getKey(), bore, Math::min);
                }
            }
            // A sized utility line's void connects at its bore (as its generator, below: a connection is the least bore unless set).
            for (var end : utilities.entrySet()) {
                var bore = laidBores.get(end.getValue());
                if (bore != null && sinks.contains(end.getKey())) connect(end.getKey(), bore);
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
            var preheat = options.preheat();
            if (preheat != null) {
                // The crude from storage into the charge pump; the fuel gas into the heater's burners.
                // Shut until the heaters are lit ({@link #openCrude}): the crude fills the train through lit burners.
                resize(feed, preheat.crudeBore());
                supply(feed, preheat.crudeBore(), new FluidDeviceSpec(1, CRUDE_TEMPERATURE, CRUDE_SUPPLY, crude(catalog)), false);
                for (var fuel : fuels) {
                    resize(fuel, preheat.fuelBore());
                    supply(fuel, preheat.fuelBore(), new FluidDeviceSpec(1, 298.15, FUEL_SUPPLY, only(catalog, "Methane")));
                }
            } else if (feed != null) supply(feed, options.feedBore(), new FluidDeviceSpec(1, input.feedTemperatureKelvin(), SUPPLY, crude(catalog)));
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

        /**
         * A utility's generator at {@code at} set to {@code spec}: switched on at {@link Options#waterBore}, or at its line's sized
         * bore ({@link #laidBores}), else charged at the laid bore.
         */
        public void water(BlockPos at, FluidDeviceSpec spec) {
            var sized = laidBores.get(utilities.get(at));
            if (options.waterBore() > 0) supply(at, options.waterBore(), spec);
            else if (sized != null) supply(at, sized, spec);
            else PlantBuilder.charge(plant.world, id(at), spec);
        }

        /** The void at {@code at} connected at bore {@code bore}, m, its fluid as it is. */
        public void connect(BlockPos at, double bore) {
            long id = id(at);
            var r = plant.world.registrations().get(id);
            var g = r.device().geometry();
            plant.world.edit(id, r.revision(), r.device().withGeometry(new PipeResistance.Geometry(g.length(), bore, g.roughness(), g.minorLoss(), g.channels())), r.spec());
        }

        /** The pipes of the straight line from {@code from} up to the end block {@code end}, not the end. */
        public static List<BlockPos> straight(BlockPos from, BlockPos end) {
            var d = Direction.fromDelta(Integer.signum(end.getX() - from.getX()), Integer.signum(end.getY() - from.getY()), Integer.signum(end.getZ() - from.getZ()));
            var path = new ArrayList<BlockPos>();
            for (var at = from; !at.equals(end); at = at.relative(d)) path.add(at);
            return path;
        }

        /** Every pipe of the straight line that ends at {@code end} (a generator or a void) at bore {@code bore}, m, up to the unit it leaves. */
        public void resize(BlockPos end, double bore) {
            for (var d : Direction.values()) {
                var at = end.relative(d);
                if (!plant.devices.contains(at)) continue;
                for (int k = 0; k < 8 && plant.devices.contains(at); k++, at = at.relative(d))
                    if (plant.world.at(plant.position(at)).isPresent()) resizeLine(at, bore);
            }
        }

        /** {@code path} kept as a path of the internal line {@code name} ({@link #routes}). */
        public List<BlockPos> laid(String name, List<BlockPos> path) {
            routes.computeIfAbsent(name, k -> new ArrayList<>()).add(path);
            return path;
        }

        /** The largest bore a pipe at {@code at} takes: the least of the nozzles beside it ({@code NozzleBore}), as the pipeline tool's. */
        public double largest(BlockPos at) {
            return com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity.largestBoreBeside(plant.level, at, 0x3F);
        }

        /**
         * The line through the pipe at {@code at} at bore {@code bore}, m, within what the nozzles beside its pipes take.
         *
         * @throws IllegalStateException where the line refuses the bore
         */
        public void resizeLine(BlockPos at, double bore) {
            int pipes = plant.world.resizeLine(id(at), bore, d -> largest(new BlockPos(d.position().x(), d.position().y(), d.position().z())));
            if (pipes < 0) throw new IllegalStateException("The line at " + at.toShortString() + " does not take a bore of " + bore + " m: " + pipes);
        }

        /** A shutoff valve on the pipe at {@code at}, its handle on {@code axis}, open, as the fitting item installs one. */
        public void shutoff(BlockPos at, Direction axis) {
            if (!plant.world.installPipeValve(id(at), com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.valueOf(axis.name()),
                    com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Cutoff.OPEN))
                throw new IllegalStateException("The shutoff valve at " + at.toShortString() + " did not install");
        }

        /**
         * Cooling water through {@code exchanger}'s tubes, straight out of its nozzles: a generator into the inlet, out into a
         * void (a sink), the water running free (branching pipes off the tube nozzles left valves on the water lines: they go;
         * with {@link Options#shutoffs} the inlet's becomes a shutoff named by {@code line}); returns the generator. Both
         * branches are laid paths of the internal line {@code route} ({@link #routes}), the generator and the void its ends
         * ({@link #utilities}).
         */
        public BlockPos water(Vessel exchanger, net.minecraft.world.level.block.state.BlockState generator, net.minecraft.world.level.block.state.BlockState sink, String line, String route) {
            var in = PlantBuilder.front(exchanger, Role.TUBE_INLET);
            plant.pipe(in);
            var source = plant.branch(in, PlantBuilder.out(exchanger, Role.TUBE_INLET), false, generator);
            var out = PlantBuilder.front(exchanger, Role.TUBE_OUTLET);
            plant.pipe(out);
            var drain = plant.branch(out, PlantBuilder.out(exchanger, Role.TUBE_OUTLET), true, sink);
            laid(route, straight(in, source));
            laid(route, straight(out, drain));
            utilities.put(source, route);
            utilities.put(drain, route);
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
        public void supply(BlockPos at, double bore, FluidDeviceSpec spec) {
            supply(at, bore, spec, true);
        }

        public void supply(BlockPos at, double bore, FluidDeviceSpec spec, boolean on) {
            long id = id(at);
            var r = plant.world.registrations().get(id);
            var g = r.device().geometry();
            var geometry = new PipeResistance.Geometry(g.length(), bore, g.roughness(), g.minorLoss(), g.channels());
            plant.world.edit(id, r.revision(), r.device().withGeometry(geometry).withGeneratorEnabled(on), spec);
        }

        /** The charge pump's fluid identity. */
        public long chargeId() {
            var cell = ShapeRasterizer.fluidCell(chargePump.shape());
            var at = chargePump.origin().offset(cell.x(), cell.y(), cell.z());
            if (!(plant.level.getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell))
                throw new IllegalStateException("No charge pump cell at " + at.toShortString());
            return shell.fluidIdentity();
        }

        /** Whether the charge pump's train has compiled with its driver and the driver's data socket is bound (always true without a
         * charge pump). */
        public boolean chargeDriven() {
            if (chargePump == null) return true;
            var driver = plant.world.shafts().driverOf(chargeId());
            return driver != null && drivePort(driver.id()) != null;
        }

        /** The data socket of drive {@code unit} (a motor's or a creative motor's, its shaft unit the host), or null before its bind. */
        private @Nullable com.wormzjl.icheme.runtime.control.DataPort drivePort(long unit) {
            for (var p : plant.world.controls().facts().ports().values())
                if (p.host() == unit && p.kind().medium() == com.wormzjl.icheme.science.equipment.CableMedium.DATA) return p;
            return null;
        }

        /**
         * The charge pump's flow held by its drive (PUMP_DRIVE_FLOW_PLAN.md): its set flow, {@link Preheat#chargeSetFlow} at
         * suction, through the edit a player's Apply on its page queues; its drive stopped in REGULATE_FLOW until {@link
         * #openCrude}. Its drive, a motor on FE or a creative motor, reads the pump's duty over a data cable from its port to the
         * pump's (else NO DATA LINK).
         */
        public void holdCharge(String[] failure) {
            if (chargePump == null) return;
            long pump = chargeId();
            var old = plant.world.registrations().get(pump);
            var d = old.device();
            if (!(d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Machine m)) {
                failure[0] = "the charge pump's control is " + d.control();
                return;
            }
            double set = options.preheat().chargeSetFlow();
            plant.world.edit(pump, old.revision(), d.withControl(new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(m.law(), m.speed(), m.enabled(), set, m.setPressure())), old.spec());
            var driver = plant.world.shafts().driverOf(pump);
            // The drive regulates the pump over a data cable, a motor's or a creative motor's (part C of PUMP_SPEED_SHUT_VALVE_REVIEW.md).
            var from = drivePort(driver.id());
            var to = PlantBuilder.port(plant, chargePump.origin(), com.wormzjl.icheme.science.equipment.CableMedium.DATA);
            if (from == null || to == null) {
                failure[0] = "no data port on the charge pump's " + (from == null ? "drive" : "casing");
                return;
            }
            PlantBuilder.lay(plant, List.of(from, to), com.wormzjl.icheme.science.equipment.CableMedium.DATA);
            var s = driver.settings();
            plant.world.shafts().queue(new com.wormzjl.icheme.runtime.fluid.ShaftTrains.Configure(driver.id(),
                    new com.wormzjl.icheme.runtime.fluid.ShaftUnit.Settings(false, com.wormzjl.icheme.science.machine.ShaftTrain.Mode.REGULATE_FLOW, s.setPoint(), s.powerLimit(), s.ratio())));
            IChemE.LOGGER.info("review_operation charge_pump hold pump={} driver={} kind={} set_m3_s={} rated_m3_s={} rated_head_m={} shutoff_head_m={} design_speed_rpm={} wired={}", pump, driver.id(), driver.kind(),
                    String.format(Locale.ROOT, "%.5f", set), String.format(Locale.ROOT, "%.5f", m.law().ratedFlow()), String.format(Locale.ROOT, "%.2f", m.law().ratedHead()),
                    m.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalPump c ? String.format(Locale.ROOT, "%.2f", c.head(0, 1)) : "-",
                    plant.world.shafts().unitOfDevice(pump) == null ? "-" : plant.world.shafts().unitOfDevice(pump).designSpeed(), options.motors());
        }

        /**
         * Starts the charge pump's drive (REGULATE_FLOW, {@link #holdCharge}) and opens the crude's supply (shut by {@link
         * #finish} while the heaters light: the supply's 10 bar would push the crude through a standing pump).
         */
        public void openCrude() {
            if (chargePump != null) {
                var driver = plant.world.shafts().driverOf(chargeId());
                var s = driver.settings();
                plant.world.shafts().queue(new com.wormzjl.icheme.runtime.fluid.ShaftTrains.Configure(driver.id(),
                        new com.wormzjl.icheme.runtime.fluid.ShaftUnit.Settings(true, s.mode(), s.setPoint(), s.powerLimit(), s.ratio())));
                IChemE.LOGGER.info("review_operation charge_pump start driver={} mode={}", driver.id(), s.mode());
            }
            long id = id(feed);
            var r = plant.world.registrations().get(id);
            if (!r.device().generatorEnabled()) plant.world.edit(id, r.revision(), r.device().withGeneratorEnabled(true), r.spec());
        }

        /** The crude's temperature at the charge line's end, K (NaN before it holds any). */
        public double chargeTemperature() {
            var state = plant.world.view(id(chargeEnd)).state();
            return state == null ? Double.NaN : state.temperature();
        }

        /** Whether the charge line is full to the tower's feed line and at the feed temperature, within {@link #HOT_BAND} K. */
        public boolean hot() {
            return plant.world.view(id(chargeEnd)).status().startsWith("FULL") && chargeTemperature() >= input.feedTemperatureKelvin() - HOT_BAND;
        }

        /** The start waits for the charge line within this many K of the feed temperature, at most this many ticks. */
        public static final double HOT_BAND = 10;
        public static final int HOT_TICKS = 200;
        private int warming;

        /** Whether the column may start: no heaters, the charge line hot, or {@link #HOT_TICKS} gone; logs every 600 ticks. */
        public boolean warmed() {
            if (chargeEnd == null) return true;
            boolean hot = hot();
            if (hot || ++warming % 600 == 0 || warming <= 200 && warming % 20 == 0 || warming >= HOT_TICKS) {
                var outlets = new ArrayList<String>();
                for (var h : heaters) {
                    var ids = com.wormzjl.icheme.world.level.block.EquipmentStructure.sideIdentities(plant.level, h);
                    for (long id : ids) {
                        var heat = plant.world.view(id).heat();
                        outlets.add(heat == null ? "-" : String.format(Locale.ROOT, "%.1f/%.0fkW", heat.outletTemperature(), heat.heatWatt() / 1000));
                        long burners = plant.world.burners().burnersOf(id);
                        if (burners != 0) {
                            var b = plant.world.burners().view(burners);
                            outlets.add(b == null ? "burners -" : String.format(Locale.ROOT, "burners demand=%.0fkW fired=%.0fkW fuel=%.3fkg/s %s", b.demand() / 1000, b.fired() / 1000, b.fuelFlow(), b.alarms()));
                        }
                    }
                }
                IChemE.LOGGER.info("review_operation warming ticks={} hot={} charge_K={} charge=\"{}\" heaters={} crude=\"{}\"", warming, hot,
                        String.format(Locale.ROOT, "%.1f", chargeTemperature()), plant.world.view(id(chargeEnd)).status(), outlets, plant.world.view(id(feed)).status());
            }
            return hot || warming >= HOT_TICKS;
        }

        /** The pure component {@code id} on the network's axis, as mole fractions. */
        public double[] only(MaterialCatalog catalog, String id) {
            var ids = new ArrayList<>(catalog.requirePackage(FluidMaterialCatalog.networkPackage(catalog)).components());
            ids.add("Water");
            var axis = new MaterialAxis(ids);
            double[] z = new double[axis.size()];
            z[axis.requireIndex(id)] = 1;
            return z;
        }

        /** The design's feed on the network's axis, as mole fractions. */
        public double[] crude(MaterialCatalog catalog) {
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

        /** Lights the heaters (FIRING), when the plant has any. */
        public void light(String[] failure) {
            if (heater != null) try {
                fire();
            } catch (RuntimeException e) {
                failure[0] = "heater: " + e.getMessage();
            }
        }

        /**
         * Whether every heater's fuel line holds its gas: a cold start's crude reaches the tower only through lit heaters
         * (without fuel the tower takes 25 °C crude and trips on CLOSURE). Always true without heaters.
         */
        public boolean fueled() {
            for (var f : fuels) {
                var status = plant.world.view(id(f)).status();
                if (!status.startsWith("FULL") && !status.startsWith("STEADY")) return false;
            }
            // And every heater's burners powered (builds 5b read NO POWER for 10 to 30 s after the crude opened). Not their firing:
            // an empty coil's island has nothing to solve, so its burners get no demand until the crude arrives (build 5c waited
            // on it for ten minutes).
            for (var h : heaters)
                for (long id : com.wormzjl.icheme.world.level.block.EquipmentStructure.sideIdentities(plant.level, h)) {
                    long burners = plant.world.burners().burnersOf(id);
                    if (burners == 0) continue;
                    var b = plant.world.burners().view(burners);
                    if (b == null || b.alarms().contains(com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.NO_POWER)) return false;
                }
            return true;
        }

        /** Validates the plant complete and starts its column. */
        public void start(String[] failure) {
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
        public void spawn() {
            plant.level.setDefaultSpawnPos(plant.base.offset(4, 0, -30), 0);
            plant.level.setDayTime(6000);
        }

        /**
         * Holds the tick until no solve runs on a worker: a GameTest server ticks flat out and would run far ahead of the solves
         * a cycle waits for. A ready job waits for this thread to dispatch it, and a finished one to drain it, so neither counts.
         * Always true.
         */
        public boolean paced() {
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
        public boolean past() {
            var view = plant.world.columnView(plant.position(plant.column.origin()), id -> Double.NaN);
            return view != null && view.phase() != OperationView.Phase.STARTING;
        }

        /**
         * The column's Operation view at report {@code n}: its phase, its streams and its duties. Returns whether the column
         * runs on a solved table: RUNNING, its last solve not refused, neither passing its feed nor off its table.
         */
        public boolean report(int n) {
            long ticks = plant.world.onlineTick();
            var view = plant.world.columnView(plant.position(plant.column.origin()), id -> Double.NaN);
            if (view == null) {
                IChemE.LOGGER.info("review_operation t={} view=none", ticks);
                return false;
            }
            var named = view.conditions().stream().map(c -> c.condition().name()).toList();
            boolean solved = view.phase() == OperationView.Phase.RUNNING && !view.refused() && !named.contains("PASSING") && !named.contains("OFF_TABLE");
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
            // The crude unit's units: each heat side's status, outlet and duty, the charge pump's status.
            if (heater != null) {
                var units = new ArrayList<>(coolers);
                units.addAll(heaters);
                for (var v : plant.structures)
                    if (v.shape().kind() == Kind.EXCHANGER && !coolers.contains(v) && !condensers.contains(v)) units.add(v);
                for (var v : units) {
                    var ids = com.wormzjl.icheme.world.level.block.EquipmentStructure.sideIdentities(plant.level, v);
                    var sides = com.wormzjl.icheme.science.equipment.HeatSides.of(v.shape());
                    for (int i = 0; i < Math.min(ids.length, sides.size()); i++) {
                        var side = plant.world.view(ids[i]);
                        var heat = side.heat();
                        IChemE.LOGGER.info("review_operation t={} unit={}@{} side={} status=\"{}\" outlet_K={} duty_kW={}", ticks, v.shape().kind(), v.origin().toShortString(), sides.get(i).side(),
                                side.status(), heat == null ? "-" : String.format(Locale.ROOT, "%.1f", heat.outletTemperature()), heat == null ? "-" : String.format(Locale.ROOT, "%.0f", heat.heatWatt() / 1000));
                    }
                }
                for (int j = 1; j < pumps.size() - 1; j++)
                    IChemE.LOGGER.info("review_operation t={} pa{}_loop status=\"{}\" draw=\"{}\"", ticks, j, plant.world.view(id(PlantBuilder.front(pumps.get(j), Role.DISCHARGE))).status(),
                            plant.world.view(id(front(plant.column, Role.PUMPAROUND_DRAW, j - 1))).status());
                var pump = pumps.getLast();
                IChemE.LOGGER.info("review_operation t={} charge_pump status=\"{}\" fuel=\"{}\"", ticks, plant.world.view(id(PlantBuilder.front(pump, Role.DISCHARGE))).status(),
                        fuels.stream().map(f -> plant.world.view(id(f)).status()).toList());
                if (chargePump != null) chargeCheck(ticks, n);
            }
            if (view.solve() != null)
                IChemE.LOGGER.info("review_operation t={} solve trigger={} warm={} age_s={}", ticks, view.solve().trigger(), view.solve().warm(),
                        String.format(Locale.ROOT, "%.0f", view.solve().age()));
            IChemE.LOGGER.info("review_operation t={} report={} solved_table={}", ticks, n, solved);
            return solved;
        }

        /**
         * A boundary line straight out of the {@code n}th {@code role} nozzle: three pipes, a valve on the second (its outlet
         * along the line, or back at the nozzle), then {@code end}. With {@link Options#shutoffs} two pipes more, a shutoff
         * valve on the fourth, named {@code name} in {@link #lines}. A null {@code end}: the last pipe is left free, with the
         * cells round it held, for the caller to route to. Returns
         * the end's position (the last pipe's).
         */
        public BlockPos line(Vessel vessel, Role role, int n, boolean outward, @Nullable BlockState end, String name) {
            var from = front(vessel, role, n);
            var d = out(vessel, role, n);
            int pipes = options.shutoffs() ? 5 : 3;
            for (int k = 0; k <= pipes; k++) {
                var at = from.relative(d, k);
                if (k == pipes && end == null) break;
                if (!plant.free(at, PlantBuilder.around(from, from.relative(d, k - 1), from.relative(d, k + 1))))
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

        /**
         * The charge pump's flow against its set flow, both at suction (its live suction density), its drive's speed against its
         * top and its status, logged at every report. The pump stands dead-headed until the column's start opens its feed valve,
         * and its drive regulates only on a flow: the check waits for the first report at least {@link #CHARGE_TICKS} online
         * ticks (at least 6 of the drive's decisions) after the first report that saw the pump's flow ({@link #chargeFlowing}).
         * There, once, the flow lies within 2 % of the set flow and the drive below its top speed (a creative motor's set speed, with
         * none its pump's design speed; a
         * motor's {@link com.wormzjl.icheme.science.machine.Motor#MAXIMUM_RPM}); else {@link #chargeFault}.
         */
        public void chargeCheck(long ticks, int n) {
            long pump = chargeId();
            var view = plant.world.view(pump);
            var state = view.state();
            double density = state != null && state.volume() > 0 ? state.mass() / state.volume() : Double.NaN;
            double flow = view.massFlow() / density, set = options.preheat().chargeSetFlow();
            var driver = plant.world.shafts().driverOf(pump);
            var train = driver == null ? null : plant.world.shafts().view(driver.id());
            double speed = train == null ? Double.NaN : train.driverSpeed();
            double top = driver == null ? Double.NaN : driver.kind() == com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR ? com.wormzjl.icheme.science.machine.Motor.MAXIMUM_RPM
                    : plant.world.shafts().setSpeed(driver.id());
            var discharge = plant.world.view(id(PlantBuilder.front(chargePump, Role.DISCHARGE)));
            IChemE.LOGGER.info("review_operation t={} charge_pump suction_kPa={} discharge_kPa={} rise_kPa={}", ticks, state == null ? "-" : String.format(Locale.ROOT, "%.1f", state.pressure() / 1000),
                    discharge.state() == null ? "-" : String.format(Locale.ROOT, "%.1f", discharge.state().pressure() / 1000),
                    view.devicePressureChange() == null ? "-" : String.format(Locale.ROOT, "%.1f", view.devicePressureChange() / 1000));
            IChemE.LOGGER.info("review_operation t={} charge_pump set_m3_s={} flow_m3_s={} kg_s={} suction_kg_m3={} speed_rpm={} top_rpm={} speed_share={} mode={} status=\"{}\"", ticks,
                    String.format(Locale.ROOT, "%.5f", set), String.format(Locale.ROOT, "%.5f", flow), String.format(Locale.ROOT, "%.2f", view.massFlow()),
                    String.format(Locale.ROOT, "%.1f", density), String.format(Locale.ROOT, "%.0f", speed), String.format(Locale.ROOT, "%.0f", top),
                    String.format(Locale.ROOT, "%.3f", plant.world.shafts().share(pump)), driver == null ? "-" : driver.settings().mode(), train == null ? "-" : train.status());
            if (chargeFlowing < 0 && view.massFlow() > 0) chargeFlowing = ticks;
            if (chargeChecked || chargeFlowing < 0 || ticks - chargeFlowing < CHARGE_TICKS) return;
            chargeChecked = true;
            IChemE.LOGGER.info("review_operation t={} charge_pump check report={} ticks_since_first_flow={} first_flow_t={}", ticks, n, ticks - chargeFlowing, chargeFlowing);
            if (!(Math.abs(flow - set) <= .02 * set && speed < top * (1 - 1e-3)))
                chargeFault = String.format(Locale.ROOT, "the charge pump's drive does not hold its flow: %.5f m3/s against %.5f, %.0f of %.0f rpm, %s", flow, set, speed, top,
                        train == null ? "no train" : train.status());
        }

        public long id(BlockPos at) {
            return plant.world.at(plant.position(at)).orElseThrow(() -> new IllegalStateException("Nothing registered at " + at.toShortString())).device().id();
        }
    }

    /** {@code shape}'s exchanger configuration; a unit that is not an exchanger (an air cooler's bank) reads standard. */
    public static com.wormzjl.icheme.science.equipment.ExchangerType type(EquipmentShape shape) {
        return shape.kind() == Kind.EXCHANGER ? shape.exchangerType() : com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD;
    }

    /** An exchanger of shell size {@code size}, m, at its default length and support. */
    public static EquipmentShape exchanger(double size) {
        var d = UnitDesign.of(Kind.EXCHANGER);
        int length = d.defaultLength(size);
        return EquipmentShape.designed(Kind.EXCHANGER, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    /** The {@code n}th of {@code vessel}'s {@code role} ports, in the shape's order (a column's from its top tray down). */
    public static ShapeRasterizer.Port port(Vessel vessel, Role role, int n) {
        int seen = 0;
        for (var p : ShapeRasterizer.ports(vessel.shape()))
            if (vessel.shape().nozzles().get(p.nozzle()).role() == role && seen++ == n) return p;
        throw new IllegalStateException("No " + role + " " + n + " on the " + vessel.shape().kind());
    }

    /** How many {@code role} ports {@code vessel} has. */
    public static int count(Vessel vessel, Role role) {
        int seen = 0;
        for (var p : ShapeRasterizer.ports(vessel.shape())) if (vessel.shape().nozzles().get(p.nozzle()).role() == role) seen++;
        return seen;
    }

    public static BlockPos front(Vessel vessel, Role role, int n) {
        var p = port(vessel, role, n);
        return vessel.origin().offset(p.cell().x() + p.dx(), p.cell().y() + p.dy(), p.cell().z() + p.dz());
    }

    public static Direction out(Vessel vessel, Role role, int n) {
        var p = port(vessel, role, n);
        return Direction.fromDelta(p.dx(), p.dy(), p.dz());
    }
}
