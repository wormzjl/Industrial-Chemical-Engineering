package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.control.ColumnCommands;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.EquipmentAlarm;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.operation.Condition;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.ControlPanelBlock;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.CreativePowerBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The test world of batch 2026-11-10-column-failure-modes (stage 5, W8): a full-size crude column plant (plant A: the
 * network's default column template at its design feed, about 8 m across, 40 trays, three draws, three pumparounds, sump
 * steam; an air-then-water condenser) and a small reboiled column (plant B, a kettle on a hot utility), every supply behind
 * a named shutoff valve, the pumps on motors fed with FE, a DCS console and a level loop on the feed's surge drum. A script
 * then runs the failure cases of the execution plan's section 7 one by one: each acts, waits for the expected condition,
 * logs one {@code review_failure} line, restores and waits for the columns to run again. A case that misses its expectation
 * fails the run with its line; the plants stay standing in {@code run/review-world/world} for the owner's manual test.
 *
 * <p>Not a gate: its namespace runs only in {@code runReviewWorldServer}. A development run may build plant A at a share of
 * its feed with the environment variable {@code ICHEME_REVIEW_DIVISOR} (16: the review plant's sixteenth).
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ColumnFailureModesReviewWorld {
    private ColumnFailureModesReviewWorld() {}

    /** Plant A's share of the template's feed: 1, the full-size column. */
    static final double SCALE = 1.0 / divisor();
    /** Plant A's receiver, K: 35 °C, under the air's 40 °C split, so the water trim takes a share of the condenser. */
    static final double RECEIVER = 308.15;
    /** Plant B: the registered 30-stage reboiled crude pilot of {@code V3ColumnCalculatorTest} on the network's crude, at a sixteenth. */
    static final double B_SCALE = 1 / 16.0;
    static final int B_STAGES = 30, B_FEED_STAGE = 24;
    static final double B_RECEIVER = 400, B_REFLUX = 2, B_REBOILER = 8e6;
    /** Plant B's distillate rundown, Pa: a tank at 2 bar, under the receiver's 2.5 bar. */
    static final double B_RUNDOWN = 200_000;
    /**
     * Plant B's hot utility: superheated steam into the kettle's tubes, K and Pa, out to a void at {@link #HOT_OUT}. Its sensible
     * heat only (about 370 kJ/kg down to the bottoms): 20 bar into 5 bar passes some 2 kg/s, the 0.5 MW reboiler with 60 % spare.
     */
    static final double HOT_TEMPERATURE = 800, HOT_SUPPLY = 2_000_000, HOT_OUT = 500_000;
    /** Cases' time limits, ticks: an action's expectation, a restore's return to RUNNING, the first start. */
    static final int CASE_TICKS = 6000, RESTORE_TICKS = 12000, START_TICKS = 36000;
    /** Ticks a held condition must stand before its line is logged: two of the column's 5 s cycles. */
    static final int HOLD_TICKS = 200, END_TICKS = 600;
    /** The receivers' saddles, m: the boot's drain line, with its shutoff six blocks long, clears the ground. */
    static final int RECEIVER_SUPPORT = 9;

    /** A development run's switch ({@code ICHEME_REVIEW_UNPACED=1}): the server ticks as fast as it can instead of 20 TPS. */
    static final boolean UNPACED = "1".equals(System.getenv("ICHEME_REVIEW_UNPACED"));

    /** A development run's case filter ({@code ICHEME_REVIEW_CASES=3,9}): only those cases run; unset, all thirteen. */
    static boolean selected(int n) {
        var v = System.getenv("ICHEME_REVIEW_CASES");
        return v == null || v.isBlank() || Arrays.stream(v.split(",")).map(String::trim).anyMatch(c -> c.equals(Integer.toString(n)));
    }

    static int divisor() {
        try {
            var v = System.getenv("ICHEME_REVIEW_DIVISOR");
            return v == null || v.isBlank() ? 1 : Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- design

    /** The network's default column template with its receiver at {@link #RECEIVER}. */
    static V3ColumnInput crude(MaterialCatalog catalog) {
        var t = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        var specs = t.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                ? (V3ColumnSpecification) new V3ColumnSpecification.CondenserOutletTemperature(RECEIVER) : s).toList();
        return new V3ColumnInput(t.schemaVersion(), t.packageId(), t.assayId(), t.componentBasis(), t.feedComponentMolarFlowsMolPerSecond(), t.feedTemperatureKelvin(),
                t.stageCount(), t.feedStageNumber(), t.topPressurePascal(), t.stagePressureDropPascal(), specs, t.sideDraws(), t.steamFeeds(), t.pumparounds(),
                t.columnDiameterMetres());
    }

    /**
     * Plant B's design at full feed (the rig scales it): the template's crude, {@link #B_STAGES} stages fed on
     * {@link #B_FEED_STAGE}, its top pressure and tray drop, a {@link #B_RECEIVER} K receiver, reflux {@link #B_REFLUX} and
     * an {@link #B_REBOILER} W reboiler; no draws, no steam, no pumparounds.
     */
    static V3ColumnInput reboiled(MaterialCatalog catalog) {
        var t = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        var specs = List.<V3ColumnSpecification>of(new V3ColumnSpecification.CondenserOutletTemperature(B_RECEIVER),
                new V3ColumnSpecification.OrganicRefluxRatio(B_REFLUX), new V3ColumnSpecification.ReboilerDuty(B_REBOILER));
        return new V3ColumnInput(t.schemaVersion(), t.packageId(), t.assayId(), t.componentBasis(), t.feedComponentMolarFlowsMolPerSecond(), t.feedTemperatureKelvin(),
                B_STAGES, B_FEED_STAGE, t.topPressurePascal(), t.stagePressureDropPascal(), specs, List.of(), List.of(), List.of(), t.columnDiameterMetres());
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

    /**
     * Plant A's air cooler: a START solve of the design with an air-then-water condenser at its set feed, then Auto's rule of
     * the process simulator (the fewest banks, then the least face, that hold the air's need at full fans with the 20 %
     * spare): {@code count} identical banks in parallel, each holding its share; a need four banks cannot hold refuses the
     * plant. Logs the air's, the trim's and the pumparounds' duties.
     */
    record Air(EquipmentShape bank, int count) {}

    static Air airBank(V3ColumnInput design, MaterialCatalog catalog) {
        var model = FluidThermodynamics.forNetwork(catalog, design.packageId());
        var axis = model.components();
        double[] feed = new double[axis.size()];
        for (int c = 0; c < design.componentBasis().componentCount(); c++) {
            int i = axis.indexOf(design.componentBasis().componentId(c));
            if (i >= 0) feed[i] += design.feedComponentMolarFlowsMolPerSecond()[c];
        }
        double rate = Arrays.stream(feed).sum();
        var steam = design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList();
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN, steam), null,
                ColumnComponents.Condenser.AIR_THEN_WATER), model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        if (!(outcome instanceof ColumnSolve.Solved solved)) throw new IllegalStateException("the sizing solve failed: " + outcome);
        SplitTable table = solved.table();
        double[] area = table.airArea();
        double need = area[SplitTable.FAN_STEPS] * rate, held = need * 1.2;
        double[] best = null;
        int count = 0;
        for (int k = 1; k <= 4 && best == null; k++) {
            count = k;
            for (var bay : ProcessUnit.bays())
                for (int parallel = 1; parallel <= ProcessUnit.Bank.MAX_PARALLEL; parallel++)
                    for (int series = 1; series <= ProcessUnit.Bank.MAX_SERIES; series++) {
                        var bank = new ProcessUnit.Bank(parallel, series);
                        double surface = HeatSides.airCoolerSurface(bay[0], (int) bay[1], bank), face = bank.bays() * ProcessUnit.bundleFace(bay[0], (int) bay[1]);
                        if (surface >= held / k && (best == null || face < best[1] || face == best[1] && bank.bays() < best[2]))
                            best = new double[]{1, face, bank.bays(), surface, bay[0], bay[1], parallel, series};
                    }
        }
        log("sizing rate_mol_s={} air_MW={} trim_MW={} pumparounds_MW={} need_m2={} natural_m2={} flood={}", rate,
                -(table.condenserPerFeed() - table.condenserWaterPerFeed()) * rate / 1e6, -table.condenserWaterPerFeed() * rate / 1e6,
                Arrays.toString(Arrays.stream(table.pumparoundPerFeed()).map(q -> q * rate / 1e6).toArray()), need, area[0] * rate,
                table.flood().known() ? table.flood().fraction(rate) : Double.NaN);
        if (best == null) throw new IllegalStateException("four air cooler banks do not hold the air's " + need + " m²");
        log("sizing banks={} bank={}x{} bay={}x{} surface_m2={}", count, (int) best[6], (int) best[7], best[4], best[5], best[3]);
        var d = UnitDesign.of(Kind.AIR_COOLER);
        int length = (int) best[5];
        return new Air(EquipmentShape.designed(Kind.AIR_COOLER, best[4], length, EquipmentShape.Heading.NORTH, d.defaultSupport(best[4], length),
                new ProcessUnit.Bank((int) best[6], (int) best[7])), count);
    }

    /** An exchanger of shell size {@code size}, m, at its default length and support. */
    static EquipmentShape exchanger(double size) {
        return ColumnOperationReviewWorld.exchanger(size);
    }

    /**
     * Plant A's options: the template at {@link #SCALE} with its receiver at {@link #RECEIVER}, the air bank of
     * {@link #airBank}, a water trim and pumparound coolers of the largest shell, the lines at the bores that keep every
     * stream under 100 m/s at the start's feed (twice the design's), motors on FE and a shutoff beyond every boundary valve;
     * the units placed round the column by their extents.
     */
    static ColumnOperationReviewWorld.Options plantA(MaterialCatalog catalog) {
        var design = ColumnOperationReviewWorld.scaled(crude(catalog), SCALE);
        var air = airBank(design, catalog);
        var bank = air.bank();
        double shell = Math.min(2.5, Math.max(.5, Math.round(2.5 * Math.sqrt(SCALE) * 2) / 2.0));
        var trim = exchanger(shell);
        var cooler = exchanger(shell);
        var drumDiameter = Math.max(1.5, Math.round(3 * Math.sqrt(SCALE) * 2) / 2.0);
        double drumLength = Math.max(4, Math.round(8 * Math.sqrt(SCALE)));
        var pump = AssemblyGameTests.Plant.unit(Kind.PUMP);
        // The column's extent at its built diameter: trays from the design, as the rig builds it.
        var columnDesign = com.wormzjl.icheme.science.equipment.ColumnDesign.of(design, new ColumnComponents.Choices(ColumnComponents.Condenser.AIR_THEN_WATER,
                ColumnComponents.Pump.STANDARD, java.util.Collections.nCopies(design.pumparounds().size(), ColumnComponents.Pump.STANDARD)));
        var column = extent(EquipmentShape.column(columnDesign.diameter(), columnDesign.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(columnDesign.diameter())));
        int gap = 7;
        // North-west, the overhead train west from the column: the air bank, the trim, the receiver.
        var b = extent(bank);
        int north = column[4] - gap;
        var bankAt = new BlockPos(column[0] - gap - b[1], 0, north - b[5]);
        var t = extent(trim);
        // Further banks in a row north of the first, in parallel.
        var banks = new ArrayList<BlockPos>();
        for (int i = 0; i < air.count(); i++) banks.add(bankAt.offset(0, 0, -i * (b[5] - b[4] + 1 + gap)));
        int depth = air.count() * (b[5] - b[4] + 1) + (air.count() - 1) * gap;
        var trimAt = new BlockPos(bankAt.getX() + b[0] - gap - t[1], 0, north - t[5]);
        var receiver = EquipmentShape.drum(drumDiameter, drumLength, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, RECEIVER_SUPPORT,
                EquipmentShape.defaultConnections(drumDiameter, drumLength, EquipmentShape.Orientation.HORIZONTAL, RECEIVER_SUPPORT, true), true);
        var r = extent(receiver);
        var drumAt = new BlockPos(trimAt.getX() + t[0] - gap - r[1], 0, north - r[5]);
        // West of the column, the reflux pump; south-west, the pumparound loops in rows.
        var p = extent(pump);
        var c = extent(cooler);
        var refluxAt = new BlockPos(column[0] - gap - 3 - p[1], 0, column[4] - p[4]);
        int row = Math.max(c[5] - c[4], p[5] - p[4] + 8) + gap;
        var pumps = new ArrayList<BlockPos>();
        var coolers = new ArrayList<BlockPos>();
        for (int j = 0; j < design.pumparounds().size(); j++) {
            int z = column[5] + gap + j * row;
            pumps.add(new BlockPos(column[0] - gap - p[1], 0, z - p[4]));
            coolers.add(new BlockPos(column[0] - gap - p[1] + p[0] - 2 * gap - c[1], 0, z - c[4]));
        }
        int west = Math.min(drumAt.getX() + r[0], coolers.isEmpty() ? 0 : coolers.getLast().getX() + c[0]) - 12;
        int south = (pumps.isEmpty() ? column[5] : pumps.getLast().getZ() + Math.max(c[5], p[5])) + 16;
        int top = Math.max(column[3], 30) + 8;
        var layout = new ColumnOperationReviewWorld.Layout(banks, List.of(trimAt), drumAt, drumDiameter, drumLength, RECEIVER_SUPPORT, refluxAt, pumps, coolers, null,
                new BlockPos(240, 0, -96), new BlockPos(west, 0, north - Math.max(depth, r[5] - r[4]) - 16), new BlockPos(column[1] + 56, top, south));
        // The bores at the start's feed, twice the design's: the review plant's 0.25 m feed line at 13 and 30 m/s and 0.15 m
        // steam line at 21 and 41 m/s grow with the feed over the bore squared.
        double feedBore = Math.min(PipeSizes.MAX_BORE, Math.max(.25, .25 * Math.sqrt(16 * SCALE) * 1.25));
        double steamBore = Math.min(PipeSizes.MAX_BORE, Math.max(.15, .15 * Math.sqrt(16 * SCALE) * 1.25));
        return new ColumnOperationReviewWorld.Options(SCALE, ColumnFailureModesReviewWorld::crude, ColumnComponents.Condenser.AIR_THEN_WATER, bank, trim, cooler, null,
                feedBore, steamBore, Math.min(PipeSizes.MAX_BORE, Math.max(.1, .5 * Math.sqrt(SCALE))), Math.min(PipeSizes.MAX_BORE, Math.max(.0625, .3 * Math.sqrt(SCALE))),
                true, true, layout);
    }

    /** Plant B's options: {@link #reboiled} at {@link #B_SCALE} on the review plant's units, a kettle east of the column, motors and shutoffs. */
    static ColumnOperationReviewWorld.Options plantB() {
        var r = ColumnOperationReviewWorld.Layout.REVIEW;
        var layout = new ColumnOperationReviewWorld.Layout(r.condenser(), List.of(), r.drum(), r.drumDiameter(), r.drumLength(), RECEIVER_SUPPORT, r.refluxPump(), List.of(),
                List.of(), new BlockPos(12, 0, 0), new BlockPos(600, 0, -96), new BlockPos(-34, 0, -32), new BlockPos(32, 44, 36));
        return new ColumnOperationReviewWorld.Options(B_SCALE, ColumnFailureModesReviewWorld::reboiled, ColumnComponents.Condenser.EXCHANGER,
                exchanger(ColumnOperationReviewWorld.CONDENSER_SIZE), null, AssemblyGameTests.Plant.unit(Kind.EXCHANGER), AssemblyGameTests.Plant.unit(Kind.KETTLE),
                ColumnOperationReviewWorld.FEED_BORE, ColumnOperationReviewWorld.STEAM_BORE, .25, 0, true, true, layout);
    }

    // ---------------------------------------------------------------------------------------------------------------- plant

    /** One plant's rig with its named parts. */
    static final class Built {
        final String label;
        final ColumnOperationReviewWorld.Rig rig;
        /** Named parts and their positions, logged for the manual test. */
        final Map<String, BlockPos> named = new LinkedHashMap<>();
        /** Laid cables by name: each cell's cable fact, to break and restore. */
        final Map<String, List<DataBus.Cable>> cables = new LinkedHashMap<>();
        BlockPos surge, supplyValve, supplyMeter, dcs, dcsB, lic, fic, pumpsSource, fansSource;
        Vessel surgeDrum;
        long columnHost;
        UUID level, flow;

        Built(String label, ColumnOperationReviewWorld.Rig rig) {
            this.label = label;
            this.rig = rig;
        }

        AssemblyGameTests.Plant plant() {
            return rig.plant;
        }

        OperationView view() {
            return AssemblyService.operation(plant().level, plant().column.origin(), null, id -> com.wormzjl.icheme.network.FluidNetwork.opening(plant().world, id));
        }

        Set<EquipmentAlarm> alarms() {
            return plant().world.columnAlarms(plant().position(plant().column.origin()));
        }
    }

    /** Names the one-block device at {@code at} and records it. */
    static void name(Built b, String name, BlockPos at) {
        if (b.plant().level.getBlockEntity(at) instanceof FluidDeviceBlockEntity e) e.rename(name);
        else if (b.plant().level.getBlockEntity(at) instanceof EquipmentShellBlockEntity s) s.rename(name);
        b.named.put(name, at.immutable());
    }

    /** Names a structure by a cell of it. */
    static void name(Built b, String name, Vessel vessel) {
        if (b.plant().level.getBlockEntity(vessel.controller()) instanceof EquipmentShellBlockEntity s) s.rename(name);
        b.named.put(name, vessel.origin().immutable());
    }

    /**
     * The surge drum's supply as a liquid (case 12): the crude at the enthalpy it has at the feed's temperature and the rig's
     * supply pressure, raised to 1.2 times the least listed pressure at which that enthalpy has no vapour (the network
     * model's flash). The drum then holds liquid, its one outlet carries liquid, and the feed flashes across the column's
     * feed valve. Returns {temperature K, pressure Pa}.
     */
    static double[] liquidSupply(MaterialCatalog catalog, double[] z, double temperature, double pressure) {
        var model = FluidThermodynamics.forNetwork(catalog, com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog));
        double h = model.flashTP(temperature, pressure, z.clone(), () -> {}).enthalpy();
        double[] found = null;
        for (double bar : new double[]{10, 15, 20, 30, 40, 60, 80, 120, 160, 240}) {
            double p = bar * 1e5, t = matched(model, z, h, temperature, p);
            var s = model.flashTP(t, p, z.clone(), () -> {});
            double vapour = 0;
            for (double v : s.vaporAmountsView()) vapour += v;
            IChemE.LOGGER.info("review_failure supply_search bar={} T={} vapour={}", bar, String.format("%.1f", t), String.format("%.4f", vapour));
            if (!(vapour > 1e-9)) {
                found = new double[]{matched(model, z, h, temperature, 1.2 * p), 1.2 * p};
                break;
            }
        }
        if (found == null) throw new IllegalStateException("The crude keeps vapour up to 240 bar at its feed enthalpy");
        IChemE.LOGGER.info("review_failure supply_liquid T={} P={}", String.format("%.1f", found[0]), String.format("%.0f", found[1]));
        return found;
    }

    /** The temperature at which {@code z} at {@code p} has the enthalpy {@code h} (bisection, the enthalpy rises with T). */
    private static double matched(FluidThermodynamics model, double[] z, double h, double near, double p) {
        double lo = near - 200, hi = near + 300;
        for (int k = 0; k < 50; k++) {
            double mid = (lo + hi) / 2;
            if (model.flashTP(mid, p, z.clone(), () -> {}).enthalpy() < h) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /**
     * Plant A's feed upstream of its feed line: a lying surge drum, the line's last pipe fed from its liquid outlet with its
     * vapour outlet joined to that line (a flash drum whose phases leave together), and its inlet fed through a flowmeter and
     * a control valve from the crude's generator.
     */
    static void surge(Built b) {
        var rig = b.rig;
        var plant = rig.plant;
        var line = rig.feedLine;
        var d = ColumnOperationReviewWorld.out(plant.column, Role.FEED, 0);
        double dd = Math.max(1.5, Math.round(3 * Math.sqrt(SCALE) * 2) / 2.0), dl = Math.max(4, Math.round(10 * Math.sqrt(SCALE)));
        var shape = EquipmentShape.drum(dd, dl, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 2,
                EquipmentShape.defaultConnections(dd, dl, EquipmentShape.Orientation.HORIZONTAL, 2, false), false);
        var e = extent(shape);
        var side = d.getClockWise();
        BlockPos origin = null;
        for (int k = 10; k <= 48 && origin == null; k += 2)
            for (int lateral : new int[]{0, 6, -6, 12, -12, 18, -18}) {
                var centre = line.relative(d, k + (e[1] - e[0]) / 2).relative(side, lateral);
                var at = new BlockPos(centre.getX() - (e[0] + e[1]) / 2, plant.base.getY(), centre.getZ() - (e[4] + e[5]) / 2);
                if (clear(plant, shape, at)) {
                    origin = at;
                    break;
                }
            }
        if (origin == null) throw new IllegalStateException("No clear ground for the surge drum beyond " + line.toShortString());
        var drum = plant.place(shape, origin.getX() - plant.base.getX(), 0, origin.getZ() - plant.base.getZ());
        b.surge = drum.origin();
        b.surgeDrum = drum;
        // The supply first, so the outlet routes go round it: out of the drum's inlet, the flowmeter's pipe and the control
        // valve on a run whose flow toward the drum is along a positive axis (a flowmeter reads the pipe's signed flow, and a
        // loop on a reverse reading refuses), then the generator. An inlet facing a positive axis (the drum's is on top) turns
        // once beside it onto the first free negative axis: down (the flow then rises), north or west.
        var in = AssemblyGameTests.Plant.front(drum, Role.SIDE);
        var sq = AssemblyGameTests.Plant.out(drum, Role.SIDE);
        List<BlockPos> run = null;
        Direction away = sq;
        BlockPos turn = in;
        var tries = sq.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? List.of(sq)
                : java.util.stream.Stream.of(Direction.DOWN, Direction.NORTH, Direction.WEST).filter(dir -> dir.getAxis() != sq.getAxis()).toList();
        search:
        for (int rise = 1; rise <= 2; rise++)
            for (int length = 5; length <= 8; length++)
                for (var dir : tries) {
                    var cells = new ArrayList<BlockPos>();
                    cells.add(in);
                    if (dir != sq) for (int k = 1; k <= rise; k++) cells.add(in.relative(sq, k));
                    var corner = cells.getLast();
                    for (int k = 1; k <= length + 1; k++) cells.add(corner.relative(dir, k));
                    boolean clear = true;
                    for (int k = 0; k < cells.size() && clear; k++)
                        clear = plant.free(cells.get(k), AssemblyGameTests.Plant.around(cells.get(k), k > 0 ? cells.get(k - 1) : in.relative(sq.getOpposite()), k + 1 < cells.size() ? cells.get(k + 1) : in));
                    if (clear) {
                        run = cells;
                        away = dir;
                        turn = corner;
                        break search;
                    }
                    if (dir == sq) break;
                }
        if (run == null) throw new IllegalStateException("The surge drum's supply line is blocked beyond " + in.toShortString());
        // The generator first, then the pipes from it toward the drum: the run between it and the valve then reads its flow
        // forward (a pipe run's sense is the order its pipes came in).
        var generator = run.getLast();
        plant.level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
        plant.devices.add(generator);
        for (int k = run.size() - 2; k >= 0; k--) plant.pipe(run.get(k));
        b.supplyValve = turn.relative(away, 2);
        b.supplyMeter = turn.relative(away, 4);
        plant.valves.put(b.supplyValve, away.getOpposite());
        rig.feed = generator;
        var path = plant.route(AssemblyGameTests.Plant.front(drum, Role.LIQUID_OUTLET), line, Set.of(line.relative(d.getOpposite()), line.relative(d), line.relative(d, 2)));
        // The vapour outlet onto a straight pipe of that line, from beside it.
        plant.join(path, AssemblyGameTests.Plant.front(drum, Role.VAPOUR_OUTLET));
        name(b, "FEED SURGE DRUM", drum);
        b.named.put("FEED SUPPLY", generator);
    }

    /** Whether {@code shape} at {@code origin} stands on air inside the plant's box, a block clear of every pipe and nozzle placed before. */
    static boolean clear(AssemblyGameTests.Plant plant, EquipmentShape shape, BlockPos origin) {
        var e = extent(shape);
        for (int x = e[0] - 1; x <= e[1] + 1; x++)
            for (int y = e[2]; y <= e[3] + 1; y++)
                for (int z = e[4] - 1; z <= e[5] + 1; z++) {
                    var at = origin.offset(x, y, z);
                    if (!plant.inside(at) || !plant.level.getBlockState(at).isAir() || plant.devices.contains(at) || plant.reserved.contains(at)) return false;
                }
        return true;
    }

    /** Resizes every pipe from the surge drum's supply to its generator, and its outlet lines, to the feed's bore. */
    static void surgeBores(Built b) {
        var plant = b.plant();
        double bore = b.rig.options.feedBore();
        for (var at : new ArrayList<>(plant.devices)) {
            if (!(plant.level.getBlockEntity(at) instanceof FluidDeviceBlockEntity e) || e.kind() != com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE) continue;
            if (at.distManhattan(b.surge) > 40) continue;
            var r = plant.world.at(plant.position(at));
            if (r.isPresent() && r.get().device().geometry().diameter() < bore - 1e-9) plant.world.resizeLine(r.get().device().id(), bore, x -> PipeSizes.MAX_BORE);
        }
    }

    /** The data or power port of the structure whose origin is {@code origin}, or null. */
    static @Nullable DataPort port(AssemblyGameTests.Plant plant, BlockPos origin, CableMedium medium) {
        var level = plant.level;
        for (var p : plant.world.controls().facts().ports().values())
            if (p.kind().medium() == medium && level.getBlockEntity(p.owner()) instanceof EquipmentShellBlockEntity s && origin.equals(s.origin())) return p;
        return null;
    }

    static @Nullable DataPort pipePort(AssemblyGameTests.Plant plant, BlockPos at) {
        return plant.level.getBlockEntity(at) instanceof FluidDeviceBlockEntity e ? e.instruments().dataPort() : null;
    }

    /**
     * A cable of {@code medium} through {@code ports} in order: each leg the shortest run of free cells inside the plant's box,
     * a cable block in each cell with joints to its neighbours on the run and its socket's joint where a port's cell is, as
     * the laying tool leaves a line. Returns each cell's cable fact, in order.
     */
    static List<DataBus.Cable> lay(AssemblyGameTests.Plant plant, List<DataPort> ports, CableMedium medium) {
        var level = plant.level;
        var path = new ArrayList<BlockPos>();
        // No leg leaves a socket's cell through the socket's own face: that joint is the socket's, at its offset.
        var beyond = new HashSet<BlockPos>();
        for (var p : ports) beyond.add(p.cell().relative(p.face()));
        for (int i = 0; i + 1 < ports.size(); i++) {
            var taken = new HashSet<>(path);
            taken.addAll(beyond);
            // Nor through another port's cell, which its own legs reach.
            for (var p : ports) taken.add(p.cell());
            taken.remove(ports.get(i + 1).cell());
            var leg = cableRoute(plant, ports.get(i).cell(), ports.get(i + 1).cell(), taken);
            if (!path.isEmpty()) leg.removeFirst();
            path.addAll(leg);
        }
        var joints = new LinkedHashMap<BlockPos, ConduitGeometry.Key>();
        for (int i = 0; i < path.size(); i++) {
            var cell = path.get(i);
            var key = joints.getOrDefault(cell, new ConduitGeometry.Key(0, 0, medium));
            for (int j : new int[]{i - 1, i + 1})
                if (j >= 0 && j < path.size() && !path.get(j).equals(cell)) {
                    var step = path.get(j).subtract(cell);
                    key = key.connect(Direction.fromDelta(step.getX(), step.getY(), step.getZ()).ordinal(), 0, 0);
                }
            joints.put(cell, key);
        }
        var ends = new HashMap<BlockPos, Map<Direction, UUID>>();
        for (var p : ports) {
            joints.put(p.cell(), joints.get(p.cell()).connect(p.face().ordinal(), p.u(), p.v()));
            ends.computeIfAbsent(p.cell(), c -> new EnumMap<>(Direction.class)).put(p.face(), p.id());
        }
        var block = medium == CableMedium.POWER ? ModBlocks.POWER_CABLE.get() : ModBlocks.DATA_CABLE.get();
        var dimension = level.dimension().location().toString();
        var out = new ArrayList<DataBus.Cable>();
        for (var entry : joints.entrySet()) {
            var cable = new DataBus.Cable(new DataBus.Site(dimension, entry.getKey(), 0), entry.getValue(), 0, ends.getOrDefault(entry.getKey(), Map.of()));
            place(plant, cable, block);
            out.add(cable);
        }
        return out;
    }

    /** A cable block holding {@code cable}. */
    static void place(AssemblyGameTests.Plant plant, DataBus.Cable cable, Block block) {
        var level = plant.level;
        var at = cable.site().cell();
        level.setBlock(at, block.defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(at) instanceof ConduitBlockEntity conduit)) throw new IllegalStateException("No cable at " + at.toShortString());
        conduit.configure(cable);
        plant.devices.add(at.immutable());
    }

    /** The shortest run of air cells from {@code from} to {@code to} inside the plant's box, past no pipe or structure and none of {@code taken}. */
    static List<BlockPos> cableRoute(AssemblyGameTests.Plant plant, BlockPos from, BlockPos to, Set<BlockPos> taken) {
        var previous = new HashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        previous.put(from, from);
        queue.add(from);
        while (!queue.isEmpty()) {
            var cell = queue.removeFirst();
            if (cell.equals(to)) {
                var path = new ArrayList<BlockPos>();
                for (var c = to; !c.equals(from); c = previous.get(c)) path.addFirst(c);
                path.addFirst(from);
                return path;
            }
            for (var d : Direction.values()) {
                var next = cell.relative(d);
                if (previous.containsKey(next) || !plant.inside(next) || next.getY() < plant.base.getY()) continue;
                if (!next.equals(to) && (!plant.level.getBlockState(next).isAir() || taken.contains(next) || plant.reserved.contains(next))) continue;
                previous.put(next, cell);
                queue.addLast(next);
            }
        }
        throw new IllegalStateException("No free cable path from " + from.toShortString() + " to " + to.toShortString());
    }

    /** A control panel (a local controller, or a DCS console) at {@code at} facing {@code facing}. */
    static ControlPanelBlockEntity panel(AssemblyGameTests.Plant plant, BlockPos at, Direction facing, boolean dcs) {
        var level = plant.level;
        var block = dcs ? ModBlocks.DCS.get() : ModBlocks.LOCAL_CONTROLLER.get();
        level.setBlock(at, block.defaultBlockState().setValue(ControlPanelBlock.FACING, facing), Block.UPDATE_ALL);
        if (dcs) ModBlocks.DCS.get().complete(level, at);
        var box = (ControlPanelBlockEntity) level.getBlockEntity(at);
        plant.world.controls().loaded(box);
        plant.devices.add(at.immutable());
        if (dcs) plant.devices.add(at.above().immutable());
        return box;
    }

    /** A sensor of {@code kind} fitted from {@code item} on {@code host}'s mount nearest {@code toward}, as a player fits one; its instance. */
    static UUID fit(Built b, BlockPos host, Sensor.Kind kind, net.minecraft.world.item.Item item) {
        var level = b.plant().level;
        var world = b.plant().world;
        var h = InstrumentHosts.at(level, host);
        if (h == null) throw new IllegalStateException("No instrument host at " + host.toShortString());
        var mounts = Sensor.mounts(h, kind);
        if (mounts.isEmpty()) throw new IllegalStateException("No " + kind + " mount at " + host.toShortString());
        var fitter = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ReviewFitter"));
        fitter.setGameMode(GameType.CREATIVE);
        fitter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        for (var m : mounts) {
            var eye = m.centre().add(m.normal().scale(1.6));
            fitter.moveTo(eye.x, eye.y - fitter.getEyeHeight(), eye.z);
            fitter.lookAt(EntityAnchorArgument.Anchor.EYES, m.centre());
            world.instruments().requestSensor(fitter, InteractionHand.MAIN_HAND, kind);
            for (var s : world.instruments().allSensors())
                if (s.key().host() == h.identity() && s.kind() == kind) return s.instance();
        }
        throw new IllegalStateException("The " + kind + " sensor did not fit on " + host.toShortString());
    }

    // ---------------------------------------------------------------------------------------------------------------- the test

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = "review-failure-modes")
    public static void theFailureModesPlantRunsItsCases(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "review-failure-modes")) return;
        var script = new Script(helper);
        try {
            script.build();
        } catch (RuntimeException e) {
            script.failure = "build: " + e;
            IChemE.LOGGER.error("review_failure build failed", e);
        }
        script.run();
    }

    /** One case's expectation: whether it holds now, and its words for the log. */
    record Expect(String word, BooleanSupplier holds) {}

    static final class Script {
        final GameTestHelper helper;
        Built a, b;
        String failure;
        final List<String> missed = new ArrayList<>();
        ControlLoopGameTests.ViewCapture operator, operatorB, operatorF;
        UUID licPanel, ficPanel;
        double designFeed;
        long caseStart;

        Script(GameTestHelper helper) {
            this.helper = helper;
        }

        void build() {
            var catalog = MaterialRuntime.active();
            var ra = ColumnOperationReviewWorld.rig(helper, plantA(catalog));
            a = new Built("A", ra);
            ra.layout(true);
            surge(a);
            ra.finish();
            var liquid = liquidSupply(catalog, ra.crude(catalog), ra.input.feedTemperatureKelvin(), ColumnOperationReviewWorld.SUPPLY);
            ra.supply(ra.feed, ra.options.feedBore(), new FluidDeviceSpec(1, liquid[0], liquid[1], ra.crude(catalog)));
            surgeBores(a);
            var rb = ColumnOperationReviewWorld.rig(helper, plantB());
            b = new Built("B", rb);
            rb.build();
            // Plant B's hot utility: superheated steam through the kettle's tubes into 5 bar.
            var waterFluid = FluidDeviceSpec.water(catalog).composition();
            rb.supply(rb.heating, .25, new FluidDeviceSpec(1, HOT_TEMPERATURE, HOT_SUPPLY, waterFluid));
            var drain = rb.lines.get("heating out");
            if (drain != null) {
                long id = rb.id(drain);
                var r = rb.plant.world.registrations().get(id);
                rb.plant.world.edit(id, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, HOT_OUT, waterFluid));
            }
            // Plant B's distillate runs down at pressure: its 400 K liquid leaves the receiver saturated at 2.5 bar, and a
            // real plant does not let it down to the atmosphere (README open items: the island held on that flash).
            var rundown = rb.lines.get("distillate");
            if (rundown != null) {
                long id = rb.id(rundown);
                var r = rb.plant.world.registrations().get(id);
                rb.plant.world.edit(id, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, B_RUNDOWN, waterFluid));
            }
            designFeed = Arrays.stream(ra.input.feedComponentMolarFlowsMolPerSecond()).sum();
            log("plantA scale={} design_mol_s={} diameter_m={} trays={} column={} box={}..{}", SCALE, designFeed, ra.design.diameter(), ra.design.trays().trays(),
                    ra.plant.column.origin().toShortString(), ra.plant.low.toShortString(), ra.plant.high.toShortString());
            log("plantB scale={} design_mol_s={} diameter_m={} trays={} column={}", B_SCALE, Arrays.stream(rb.input.feedComponentMolarFlowsMolPerSecond()).sum(),
                    rb.design.diameter(), rb.design.trays().trays(), rb.plant.column.origin().toShortString());
        }

        /** {@code body} as a wait's check: a failed assertion waits on; any other exception is the run's failure, logged. */
        Runnable guard(Runnable body) {
            return () -> {
                if (failed()) return;
                checked(body);
            };
        }

        /** {@code body} run: a failed assertion waits on; any other exception is the run's failure, logged. */
        void checked(Runnable body) {
            try {
                body.run();
            } catch (net.minecraft.gametest.framework.GameTestAssertException wait) {
                throw wait;
            } catch (RuntimeException e) {
                if (failure == null) failure = e.toString();
                IChemE.LOGGER.error("review_failure check failed", e);
            }
        }

        boolean failed() {
            return failure != null;
        }

        void stage(Runnable step) {
            if (failed()) return;
            try {
                step.run();
            } catch (RuntimeException e) {
                failure = e.toString();
                IChemE.LOGGER.error("review_failure stage failed", e);
            }
        }

        void run() {
            var s = helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(failed() || a.plant().valvesInstalled() && b.plant().valvesInstalled() && shutoffs(a) && shutoffs(b), "Waiting for the valves"))
                    .thenIdle(20)
                    .thenExecute(() -> stage(this::names))
                    .thenWaitUntil(() -> helper.assertTrue(failed() || sockets(), "Waiting for the sockets"))
                    .thenExecute(() -> stage(this::sources))
                    .thenWaitUntil(() -> helper.assertTrue(failed() || sourced(), "Waiting for the power sources' sockets"))
                    .thenExecute(() -> stage(this::power))
                    .thenIdle(100)
                    .thenExecute(() -> stage(this::sensors))
                    // The cable goes in once the supply's control valve stands committed, so no socket moves after it is wired.
                    .thenWaitUntil(guard(() -> helper.assertTrue(failed() || a.plant().world.committedDevice(a.rig.id(a.supplyValve))
                            .map(d -> d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve).orElse(false), "Waiting for the committed control valve")))
                    .thenIdle(20)
                    .thenExecute(() -> stage(this::control))
                    .thenIdle(40)
                    .thenExecute(() -> stage(this::start))
                    .thenExecute(() -> {
                        caseStart = now();
                        paceNanos = System.nanoTime();
                        paceTick = now();
                    });
            s = waitFor(s, "start", () -> running(a) && running(b) && atDesign(a), START_TICKS);
            s = s.thenExecute(() -> stage(() -> {
                report(a, "start");
                report(b, "start");
            }));
            s = cases(s);
            s = end(s);
            s.thenExecute(() -> {
                if (a != null) {
                    a.plant().release();
                    a.plant().level.setDefaultSpawnPos(a.named.getOrDefault("DCS-1", a.plant().base).offset(-3, 0, 0), 0);
                    a.plant().level.setDayTime(6000);
                }
                if (b != null) b.plant().release();
                log("summary missed={} failure={}", missed, failure);
                if (failure != null) helper.fail(failure);
                else if (!missed.isEmpty()) helper.fail("missed: " + missed);
            }).thenSucceed();
        }

        /**
         * The script's end: both plants RUNNING at their set feed, with no held island on a boundary line, for
         * {@link #END_TICKS} on end; one {@code review_failure end} line. Anything else within {@link #RESTORE_TICKS} fails.
         */
        GameTestSequence end(GameTestSequence s) {
            long[] since = {-1}, t0 = {-1};
            boolean[] done = {false};
            return s.thenWaitUntil(() -> checked(() -> {
                if (done[0]) return;
                if (t0[0] < 0) t0[0] = now();
                paced();
                int held = held("end");
                boolean ok = running(a) && running(b) && atDesign(a) && atDesign(b) && held == 0;
                if (!ok) since[0] = -1;
                else if (since[0] < 0) since[0] = now();
                boolean passed = ok && now() - since[0] >= END_TICKS;
                if (passed || now() - t0[0] > RESTORE_TICKS) {
                    IChemE.LOGGER.info("review_failure end plantA={} plantB={} held={}", words(a).replace(' ', '_'), words(b).replace(' ', '_'), held);
                    if (!passed && failure == null) failure = "end not reached in " + RESTORE_TICKS + " ticks: A " + words(a) + ", B " + words(b) + ", held " + held;
                    done[0] = true;
                    return;
                }
                trace(a, "end");
                trace(b, "end");
                helper.assertTrue(false, "Waiting for the end");
            }));
        }

        long now() {
            return a.plant().world.onlineTick();
        }

        boolean shutoffs(Built x) {
            for (var at : x.rig.shutoffs.keySet())
                if (!x.plant().world.at(x.plant().position(at)).map(r -> r.device().cutoff() != PhysicalFluidTopology.Cutoff.NONE).orElse(false)) return false;
            return true;
        }

        /** Every named valve and unit, logged with its place. */
        void names() {
            for (var x : List.of(a, b)) {
                var rig = x.rig;
                for (var e : rig.lines.entrySet()) {
                    String n = switch (e.getKey()) {
                        case "feed" -> "FEED SHUTOFF";
                        case "steam1" -> "STEAM SHUTOFF";
                        case "draw1", "draw2", "draw3" -> "DRAW " + e.getKey().substring(4) + " SHUTOFF";
                        case "bottoms" -> "BOTTOMS SHUTOFF";
                        case "offgas" -> "OFFGAS SHUTOFF";
                        case "water" -> "SOUR WATER SHUTOFF";
                        case "trim" -> x == b ? "CW CONDENSER" : "CW TRIM";
                        case "heating out" -> "KETTLE STEAM OUT";
                        case "trim out" -> x == b ? "CW CONDENSER RETURN" : "CW TRIM RETURN";
                        case "pa1 out", "pa2 out", "pa3 out" -> "PA" + e.getKey().charAt(2) + " CW RETURN";
                        case "pa1", "pa2", "pa3" -> "PA" + e.getKey().substring(2) + " CW";
                        case "heating" -> "KETTLE STEAM";
                        case "distillate" -> "DISTILLATE";
                        default -> e.getKey().toUpperCase(Locale.ROOT);
                    };
                    name(x, n, e.getValue());
                }
                name(x, "COLUMN", rig.plant.column);
                for (int i = 0; i < rig.pumps.size(); i++) name(x, i == 0 ? "REFLUX PUMP" : "PA" + i + " PUMP", rig.pumps.get(i));
                for (int i = 0; i < rig.motors.size(); i++) name(x, i == 0 ? "REFLUX MOTOR" : "PA" + i + " MOTOR", rig.motors.get(i));
                for (int i = 0; i < rig.coolers.size(); i++) name(x, "PA" + (i + 1) + " COOLER", rig.coolers.get(i));
                for (int i = 0; i < rig.condensers.size(); i++)
                    name(x, (rig.options.condenser() == ColumnComponents.Condenser.EXCHANGER ? "CONDENSER" : "AIR CONDENSER") + (rig.condensers.size() > 1 ? " " + (i + 1) : ""), rig.condensers.get(i));
                for (int i = 0; i < rig.trims.size(); i++) name(x, "TRIM CONDENSER", rig.trims.get(i));
                name(x, "RECEIVER", rig.plant.drum);
                if (rig.kettle != null) name(x, "KETTLE", rig.kettle);
            }
            name(a, "SUPPLY FCV", a.supplyValve);
        }

        boolean sockets() {
            for (var x : List.of(a, b)) {
                for (var m : x.rig.motors) if (port(x.plant(), m.origin(), CableMedium.POWER) == null) return false;
                if (port(x.plant(), x.plant().column.origin(), CableMedium.DATA) == null) return false;
            }
            for (var bank : a.rig.condensers) if (port(a.plant(), bank.origin(), CableMedium.POWER) == null) return false;
            return port(a.plant(), a.surge, CableMedium.DATA) != null;
        }

        /**
         * The FE: a creative source beside each plant's pumps, one cable to each motor (the reflux motor's its own), and for
         * plant A a second source with one cable to the air condenser's terminal ({@code FANS POWER}). This places the sources.
         */
        void sources() {
            for (var x : List.of(a, b)) {
                var level = x.plant().level;
                var reflux = x.rig.motors.getFirst();
                var at = free(x, reflux.origin(), 4);
                level.setBlock(at, ModBlocks.CREATIVE_POWER.get().defaultBlockState(), Block.UPDATE_ALL);
                x.plant().devices.add(at);
                x.pumpsSource = at;
                x.named.put("PUMPS POWER SOURCE", at);
            }
            var terminal = java.util.Objects.requireNonNull(port(a.plant(), a.rig.condensers.getFirst().origin(), CableMedium.POWER));
            var at = free(a, terminal.cell(), 3);
            a.plant().level.setBlock(at, ModBlocks.CREATIVE_POWER.get().defaultBlockState(), Block.UPDATE_ALL);
            a.plant().devices.add(at);
            a.fansSource = at;
            a.named.put("FANS POWER SOURCE", at);
        }

        /** Whether every source shows its six sockets. */
        boolean sourced() {
            for (var at : List.of(a.pumpsSource, b.pumpsSource, a.fansSource))
                if (!(a.plant().level.getBlockEntity(at) instanceof CreativePowerBlockEntity source) || source.powerPorts().size() < 6) return false;
            return true;
        }

        /** A source's sockets whose cell is free for a cable. */
        List<DataPort> faces(Built x, BlockPos at) {
            var source = (CreativePowerBlockEntity) x.plant().level.getBlockEntity(at);
            var out = new ArrayList<DataPort>();
            for (var p : source.powerPorts()) if (x.plant().level.getBlockState(p.cell()).isAir() && p.cell().getY() >= x.plant().base.getY()) out.add(p);
            return out;
        }

        /** One cable from a free socket of the pumps' source to each motor, and one from the fans' source to the air condenser. */
        void power() {
            for (var x : List.of(a, b)) {
                var faces = faces(x, x.pumpsSource);
                for (int i = 0; i < x.rig.motors.size(); i++) {
                    var motor = x.rig.motors.get(i);
                    var target = java.util.Objects.requireNonNull(port(x.plant(), motor.origin(), CableMedium.POWER));
                    DataPort face = nearest(faces, target.cell());
                    faces.remove(face);
                    var name = i == 0 ? "REFLUX MOTOR POWER" : "PA" + i + " MOTOR POWER";
                    var laid = lay(x.plant(), List.of(face, target), CableMedium.POWER);
                    x.cables.put(name, laid);
                    x.named.put(name, laid.get(laid.size() / 2).site().cell());
                }
            }
            // One cable from the fans' source through every bank's terminal.
            var terminals = new ArrayList<DataPort>();
            for (var bank : a.rig.condensers) terminals.add(java.util.Objects.requireNonNull(port(a.plant(), bank.origin(), CableMedium.POWER)));
            terminals.addFirst(nearest(faces(a, a.fansSource), terminals.getFirst().cell()));
            var laid = lay(a.plant(), terminals, CableMedium.POWER);
            a.cables.put("FANS POWER", laid);
            a.named.put("FANS POWER", laid.get(laid.size() / 2).site().cell());
        }

        static DataPort nearest(List<DataPort> ports, BlockPos to) {
            return ports.stream().min(java.util.Comparator.comparingDouble(p -> p.cell().distSqr(to))).orElseThrow();
        }

        /** A free cell on the ground at {@code distance} or more from {@code near}, with its six neighbours free. */
        BlockPos free(Built x, BlockPos near, int distance) {
            var plant = x.plant();
            for (int r = distance; r < distance + 24; r++)
                for (var d : new Direction[]{Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.EAST})
                    for (int up = 0; up <= 4; up++) {
                        var at = new BlockPos(near.getX(), plant.base.getY() + up, near.getZ()).relative(d, r);
                        if (!plant.inside(at) || !plant.level.getBlockState(at).isAir() || plant.reserved.contains(at)) continue;
                        boolean clear = true;
                        for (var q : Direction.values()) clear &= plant.level.getBlockState(at.relative(q)).isAir() || q == Direction.DOWN && up == 0;
                        if (clear) return at.immutable();
                    }
            throw new IllegalStateException("No free cell near " + near.toShortString());
        }

        /**
         * The control: east of plant A's surge drum a DCS console (DCS-1), a level controller (LIC-1) and a flow controller
         * (FIC-1) on one data cable with the column, the drum and the supply's valve and flowmeter; a second console (DCS-2)
         * on no cable. LIC-1 reads the drum's level with the column as its output, switched off; FIC-1 reads the supply's flow
         * with its valve as its output, switched off. Plant B gets a console on its column too.
         */
        void sensors() {
            var plant = a.plant();
            var world = plant.world;
            var level = plant.level;
            a.level = fit(a, a.surgeDrum.controller(), Sensor.Kind.LEVEL,
                    ModItems.DIGITAL_LEVEL_METER.get());
            var valve = a.supplyValve;
            long valveId = a.rig.id(valve);
            world.instruments().legacyInstalled(valveId, Instrument.Type.PRESSURE_VALVE);
            ((FluidDeviceBlockEntity) level.getBlockEntity(valve)).refreshGeometry(((FluidDeviceBlockEntity) level.getBlockEntity(valve)).bore(), true);
            a.flow = fit(a, a.supplyMeter, Sensor.Kind.FLOW, ModItems.FLOWMETER.get());
            name(a, "SUPPLY FT", a.supplyMeter);
        }

        /** The panels and their cables, laid once the supply's control valve stands committed and no socket moves. */
        void control() {
            var plant = a.plant();
            var valve = a.supplyValve;
            var level = plant.level;
            long valveId = a.rig.id(valve);
            // The panels in a row on the ground, east of the drum, facing west toward the plant.
            int x0 = a.surge.getX() + extent(a.surgeDrum.shape())[1] + 6;
            var base = new BlockPos(x0, plant.base.getY(), a.surge.getZ());
            a.dcs = base;
            a.lic = base.south(3);
            a.fic = base.south(6);
            a.dcsB = base.north(4);
            var dcs = panel(a.plant(), a.dcs, Direction.WEST, true);
            var lic = panel(a.plant(), a.lic, Direction.WEST, false);
            var fic = panel(a.plant(), a.fic, Direction.WEST, false);
            panel(a.plant(), a.dcsB, Direction.WEST, true);
            a.named.put("DCS-1", a.dcs);
            a.named.put("LIC-1", a.lic);
            a.named.put("FIC-1", a.fic);
            a.named.put("DCS-2 (no cable)", a.dcsB);
            var column = java.util.Objects.requireNonNull(port(a.plant(), plant.column.origin(), CableMedium.DATA));
            a.columnHost = column.host();
            var drum = java.util.Objects.requireNonNull(port(a.plant(), a.surge, CableMedium.DATA));
            var bus = lay(a.plant(), List.of(fic.dataPort(), java.util.Objects.requireNonNull(pipePort(a.plant(), a.supplyMeter)), java.util.Objects.requireNonNull(pipePort(a.plant(), valve)),
                    lic.dataPort(), dcs.dataPort(), drum, column), CableMedium.DATA);
            a.cables.put("DATA BUS", bus);
            a.named.put("DATA BUS (column end)", bus.getLast().site().cell());
            // Plant B: a console on its column.
            var pb = b.plant();
            var bColumn = java.util.Objects.requireNonNull(port(b.plant(), pb.column.origin(), CableMedium.DATA));
            b.columnHost = bColumn.host();
            b.dcs = free(b, bColumn.cell(), 4);
            var dcsB = panel(b.plant(), b.dcs, Direction.WEST, true);
            b.named.put("DCS-B", b.dcs);
            b.cables.put("DATA BUS", lay(b.plant(), List.of(dcsB.dataPort(), bColumn), CableMedium.DATA));
            // The operators and the loops' settings, switched off.
            operator = ControlLoopGameTests.operator(level, a.lic);
            licPanel = lic.identity();
            ficPanel = fic.identity();
            edit(a.lic, licPanel, new ControlSettings("LIC-1", false, a.level, a.columnHost, Sensor.Unit.HEIGHT_PERCENT, 50, ControlSettings.Action.OUTLET));
            edit(a.fic, ficPanel, new ControlSettings("FIC-1", false, a.flow, valveId, Sensor.Unit.KILOGRAM_PER_SECOND, 1, ControlSettings.Action.OUTLET));
            for (var x : List.of(a, b))
                for (var e : x.named.entrySet()) log("place plant={} name=\"{}\" at={}", x.label, e.getKey(), e.getValue().toShortString());
        }

        void edit(BlockPos panel, UUID id, ControlSettings settings) {
            edit(operator, panel, id, settings);
        }

        void edit(ControlLoopGameTests.ViewCapture by, BlockPos panel, UUID id, ControlSettings settings) {
            var world = a.plant().world;
            by.player.moveTo(panel.getX() + 1.5, panel.getY(), panel.getZ() + .5);
            if (!(by.player.containerMenu instanceof ControlPanelMenu m && m.panel().equals(id))) ControlPanelMenu.open(by.player, panel);
            var menu = ControlLoopGameTests.menu(helper, by);
            world.controls().edit(menu, id, world.controls().facts().panel(id).revision(), settings);
        }

        void start() {
            for (var x : List.of(a, b)) {
                String[] f = {null};
                x.rig.start(f);
                if (f[0] != null) throw new IllegalStateException("plant " + x.label + " " + f[0]);
            }
            a.plant().level.setDefaultSpawnPos(a.dcs.offset(-3, 0, 0), 0);
        }

        // -------------------------------------------------------------------------------------------------------- reading

        static boolean running(Built x) {
            var v = x.view();
            return v != null && v.phase() == OperationView.Phase.RUNNING && x.alarms().isEmpty();
        }

        boolean atDesign(Built x) {
            var v = x.view();
            return v != null && feedRate(v) >= .97 * Arrays.stream(x.rig.input.feedComponentMolarFlowsMolPerSecond()).sum();
        }

        static double feedRate(OperationView v) {
            return v.streams().stream().filter(s -> s.port() == Port.FEED).mapToDouble(OperationView.Stream::rate).sum();
        }

        /** The column's words: its phase, its binding condition with its index, its alarms. */
        static String words(Built x) {
            var v = x.view();
            if (v == null) return "none";
            var binding = v.binding();
            var alarms = x.alarms().stream().map(al -> al.text).toList();
            return v.phase() + (binding == null ? "" : ":" + binding.condition() + (binding.index() >= 0 ? "#" + binding.index() : "")) + (alarms.isEmpty() ? "" : ":" + String.join("+", alarms));
        }

        void report(Built x, String when) {
            var v = x.view();
            if (v == null) return;
            log("state plant={} when={} t={} words={} alpha={} feed_mol_s={} engine=\"{}\" conditions={}", x.label, when, now(), words(x), v.alpha(), feedRate(v), v.engine(),
                    v.conditions().stream().map(c -> c.condition() + "(" + String.format(Locale.ROOT, "%.3g", c.value()) + "," + c.index() + ")").toList());
            log("streams plant={} when={} {}", x.label, when, v.streams().stream().map(st -> st.port() + "" + st.index() + "=" + String.format(Locale.ROOT, "%.4g mol/s held %.3f open %.3f", st.rate(), st.held(), st.opening())).toList());
            log("duties plant={} when={} {}", x.label, when, v.duties().stream().map(d -> d.kind() + "" + d.index() + "=" + String.format(Locale.ROOT, "%.4g/%.4g", d.booked(), d.solved())).toList());
            var lines = new StringBuilder();
            for (var e : x.rig.lines.entrySet()) {
                try {
                    var fv = x.plant().world.view(x.rig.id(e.getValue()));
                    lines.append(String.format(Locale.ROOT, " [%s m=%.4g T=%.1f P=%.4g]", e.getKey(), fv.massFlow(), fv.state().temperature(), fv.state().pressure()));
                } catch (RuntimeException ex) {
                    lines.append(" [").append(e.getKey()).append(" ?]");
                }
            }
            log("lines plant={} when={}{}", x.label, when, lines);
        }

        // -------------------------------------------------------------------------------------------------------- cases

        GameTestSequence waitFor(GameTestSequence s, String what, BooleanSupplier until, int limit) {
            long[] since = {-1};
            return s.thenWaitUntil(guard(() -> {
                if (failed()) return;
                if (since[0] < 0) since[0] = now();
                boolean done = paced() && until.getAsBoolean();
                trace(a, what);
                trace(b, what);
                if (!done && (now() - since[0]) % 3000 == 2999) {
                    report(a, what + "-wait");
                    report(b, what + "-wait");
                }
                if (!done && now() - since[0] > limit) {
                    failure = what + " not reached in " + limit + " ticks: A " + words(a) + ", B " + words(b);
                    report(a, what);
                    report(b, what);
                    return;
                }
                helper.assertTrue(done, "Waiting for " + what);
            }));
        }

        /** The wall clock and online tick the columns started at: from there the run is held to 20 TPS (unless {@link #UNPACED}). */
        long paceNanos = -1, paceTick;

        boolean paced() {
            a.rig.paced();
            if (paceNanos < 0 || UNPACED) return true;
            long due = paceNanos + (now() - paceTick) * 50_000_000L;
            for (long wait = due - System.nanoTime(); wait > 0; wait = due - System.nanoTime())
                try {
                    Thread.sleep(Math.min(50, wait / 1_000_000L + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            return true;
        }

        /**
         * A case: {@code act}, then the expectation held for {@link #HOLD_TICKS} (a miss after {@link #CASE_TICKS}), its line,
         * {@code restore}, and both columns RUNNING again at their design feed.
         */
        GameTestSequence one(GameTestSequence s, int n, Built x, Runnable act, Expect expect, Runnable restore) {
            if (!selected(n)) return s;
            long[] t0 = {0}, held = {-1};
            boolean[] done = {false};
            s = s.thenExecute(() -> stage(() -> {
                t0[0] = now();
                held[0] = -1;
                done[0] = false;
                act.run();
            })).thenWaitUntil(guard(() -> {
                if (failed() || done[0]) return;
                paced();
                boolean ok = expect.holds().getAsBoolean();
                long t = now() - t0[0];
                trace(x, "case" + n);
                if (ok && held[0] < 0) held[0] = now();
                if (!ok) held[0] = -1;
                if (ok && now() - held[0] >= HOLD_TICKS || t > CASE_TICKS) {
                    var v = x.view();
                    log("case={} expected=\"{}\" got=\"{}\" share={} ticks={}", n, expect.word(), words(x), v == null ? Double.NaN : v.alpha(), ok ? held[0] - t0[0] : t);
                    IChemE.LOGGER.info("review_failure case={} expected={} got={} share={} ticks={}", n, expect.word().replace(' ', '_'), words(x).replace(' ', '_'),
                            v == null ? "NaN" : String.format(Locale.ROOT, "%.3f", v.alpha()), ok ? held[0] - t0[0] : t);
                    report(x, "case" + n);
                    if (!ok) missed.add("case " + n + ": expected " + expect.word() + ", got " + words(x));
                    done[0] = true;
                    return;
                }
                helper.assertTrue(false, "Waiting for case " + n);
            })).thenExecute(() -> stage(restore));
            return waitFor(s, "case " + n + " restored", () -> running(a) && running(b) && atDesign(a) && atDesign(b), RESTORE_TICKS);
        }

        /** The last words and engine text logged per plant: a change is logged with its tick. */
        final Map<String, String> traced = new HashMap<>();

        /** Logs plant {@code x}'s words, engine text and conditions when they change. */
        /** The islands seen held, by id. */
        final Map<Long, String> heldIslands = new HashMap<>();
        long lastHeldScan = -1000;

        /**
         * Every 100 ticks, the fluid islands now held: each new one is logged once with the plants' named parts and boundary
         * lines among its members and its nodes' state. Returns how many are held: the world holds the two plants alone, so each is
         * one of their lines.
         */
        int held(String when) {
            if (now() - lastHeldScan < 100) return heldIslands.size();
            lastHeldScan = now();
            var world = a.plant().world;
            var names = new HashMap<Long, String>();
            for (var x : List.of(a, b)) {
                for (var e : x.named.entrySet()) try { names.put(x.rig.id(e.getValue()), x.label + ":" + e.getKey()); } catch (RuntimeException ignored) {}
                for (var e : x.rig.lines.entrySet()) try { names.put(x.rig.id(e.getValue()), x.label + ":line " + e.getKey()); } catch (RuntimeException ignored) {}
            }
            var now = new HashMap<Long, String>();
            for (var s : world.diagnosticSnapshots()) {
                if (!s.status().startsWith("HELD")) continue;
                var members = new java.util.TreeSet<String>();
                var nodes = new StringBuilder();
                for (var r : s.graph().reservoirs()) {
                    var n = names.get(r.id());
                    if (n != null) members.add(n);
                    if (nodes.length() < 1500) nodes.append(String.format(Locale.ROOT, " [%d %s %s T=%.1f P=%.4g m=%.4g V=%.3g vap=%.3g]", r.id(), n == null ? "" : n, r.kind(),
                            r.state().temperature(), r.state().pressure(), r.state().mass(), r.state().volume(), r.state().vaporVolume()));
                }
                for (var p : s.graph().pipes()) {
                    var n = names.get(p.id());
                    if (n != null) members.add(n);
                    var g = p.sections().isEmpty() ? null : p.sections().getFirst();
                    if (nodes.length() < 3000) nodes.append(String.format(Locale.ROOT, " {pipe %d %d->%d sections=%d bore=%s len=%s blocked=%d forbidden=%d control=%s first=%s second=%s}", p.id(), p.first(), p.second(),
                            p.sections().size(), g == null ? "" : g.diameter(), g == null ? "" : g.length(), p.blockedDirections(), p.forbiddenDirections(), p.control(), p.firstPort(), p.secondPort()));
                }
                now.put(s.id(), members.toString());
                if (!heldIslands.containsKey(s.id()))
                    log("held island={} when={} committed_tick={} reservoirs={} pipes={} members={} status=\"{}\" nodes={}", s.id(), when, s.clock().committedTick(),
                            s.graph().reservoirs().size(), s.graph().pipes().size(), members, s.status(), nodes);
            }
            for (var id : heldIslands.keySet()) if (!now.containsKey(id)) log("held island={} when={} released", id, when);
            heldIslands.clear();
            heldIslands.putAll(now);
            return now.size();
        }

        void trace(Built x, String when) {
            if (x == a) held(when);
            var v = x.view();
            if (v == null) return;
            String engine = v.engine();
            int cut = engine.indexOf("; RECONSTRUCTED");
            var trays = v.trays();
            String now = words(x) + " | " + (cut < 0 ? engine : engine.substring(0, cut)) + " | flood " + String.format(Locale.ROOT, "%.2f/%.2f@%d", trays.flood(), trays.floodAtSet(), trays.tray()) + " | "
                    + v.conditions().stream().filter(c -> c.condition() != Condition.ADVISORY).map(c -> c.condition() + "(" + String.format(Locale.ROOT, "%.3g", c.value()) + "," + c.index() + ")").toList();
            if (now.equals(traced.put(x.label, now))) return;
            log("trace plant={} when={} t={} alpha={} {}", x.label, when, now(), String.format(Locale.ROOT, "%.3f", v.alpha()), now);
        }

        boolean bound(Built x, Condition condition, int index) {
            var v = x.view();
            var c = v == null ? null : v.binding();
            return c != null && c.condition() == condition && (index < 0 || c.index() == index);
        }

        boolean alarm(Built x, EquipmentAlarm alarm) {
            return x.alarms().contains(alarm);
        }

        void toggle(Built x, String name) {
            var at = java.util.Objects.requireNonNull(x.named.get(name), name);
            x.plant().world.togglePipeCutoff(x.rig.id(at));
            log("action plant={} toggle=\"{}\" at={}", x.label, name, at.toShortString());
        }

        void cut(Built x, String cable) {
            for (var c : x.cables.get(cable)) x.plant().level.destroyBlock(c.site().cell(), false);
            log("action plant={} break=\"{}\"", x.label, cable);
        }

        void mend(Built x, String cable) {
            var block = cable.contains("POWER") ? ModBlocks.POWER_CABLE.get() : ModBlocks.DATA_CABLE.get();
            for (var c : x.cables.get(cable)) place(x.plant(), c, block);
            log("action plant={} mend=\"{}\"", x.label, cable);
        }

        /** The design's set points of plant {@code x} in the Operation tab's order, with {@code edit} applied. */
        List<Double> setpoints(Built x, java.util.function.UnaryOperator<double[]> edit) {
            var s = Setpoints.of(java.util.Objects.requireNonNull(x.rig.design.trays().operating()));
            var v = new ArrayList<Double>();
            v.add(s.feedRate());
            v.add(s.condenserTemperature());
            v.add(s.refluxRatio());
            v.add(s.reboilerPerFeed());
            for (double d : s.steamPerFeed()) v.add(d);
            for (double d : s.drawsPerFeed()) v.add(d);
            for (double d : s.pumparoundsPerFeed()) v.add(d);
            double[] out = edit.apply(v.stream().mapToDouble(Double::doubleValue).toArray());
            return Arrays.stream(out).boxed().toList();
        }

        void apply(Built x, List<Double> values) {
            var refused = AssemblyService.setpoints(x.plant().level, x.plant().column.origin(), values);
            log("action plant={} setpoints={} answer={}", x.label, values, refused);
        }

        /** A console's last answer: how many its menu gave, and the text. */
        record Answer(long ack, String reply) {}

        /** The last answer {@code capture}'s open menu was sent. */
        static Answer answer(ControlLoopGameTests.ViewCapture capture) {
            for (int i = capture.views.size() - 1; i >= 0; i--) {
                var d = capture.views.get(i).data();
                if (d.contains("ack")) return new Answer(d.getLong("ack"), d.getString("reply"));
            }
            return new Answer(0, "");
        }

        ControlPanelMenu console(ControlLoopGameTests.ViewCapture capture, BlockPos at) {
            capture.player.moveTo(at.getX() - 1.5, at.getY(), at.getZ() + .5);
            if (!(capture.player.containerMenu instanceof ControlPanelMenu m && m.panel().equals(((ControlPanelBlockEntity) a.plant().level.getBlockEntity(at)).identity())))
                ControlPanelMenu.open(capture.player, at);
            return ControlLoopGameTests.menu(helper, capture);
        }

        void command(ControlLoopGameTests.ViewCapture capture, BlockPos at, long host, ColumnCommands.Action action, List<Double> values) {
            var menu = console(capture, at);
            a.plant().world.controls().column(menu, new ColumnCommands.Command(host, action, values));
            log("action dcs={} command={} host={}", at.toShortString(), action, host);
        }

        GameTestSequence cases(GameTestSequence s) {
            // 1. Nothing: RUNNING and no alarm, held over two cycles.
            s = one(s, 1, a, () -> {}, new Expect("RUNNING", () -> running(a)), () -> {});
            // 2. The feed's shutoff: the inlet's buffer runs dry, LOW FEED.
            s = one(s, 2, a, () -> toggle(a, "FEED SHUTOFF"), new Expect("LOW FEED", () -> bound(a, Condition.FEED_SHORT, -1) && alarm(a, EquipmentAlarm.LOW_FEED)),
                    () -> toggle(a, "FEED SHUTOFF"));
            // 3. The steam's shutoff: LOW STEAM (rule B's STEAM_RATIO solve; rule A where it is refused).
            s = one(s, 3, a, () -> toggle(a, "STEAM SHUTOFF"), new Expect("LOW STEAM", () -> alarm(a, EquipmentAlarm.LOW_STEAM) && !alarm(a, EquipmentAlarm.UPSET)), () -> toggle(a, "STEAM SHUTOFF"));
            // 4. The third draw's shutoff: its buffer fills, BLOCKED.
            s = one(s, 4, a, () -> toggle(a, "DRAW 3 SHUTOFF"), new Expect("BLOCKED", () -> bound(a, Condition.PRODUCT_FULL, -1) && alarm(a, EquipmentAlarm.BLOCKED)),
                    () -> toggle(a, "DRAW 3 SHUTOFF"));
            // 5. The trim's water: COOLING LIMITED.
            s = one(s, 5, a, () -> toggle(a, "CW TRIM"), new Expect("COOLING LIMITED", () -> bound(a, Condition.COOLING_SHORT, -1) && alarm(a, EquipmentAlarm.COOLING_LIMITED)),
                    () -> toggle(a, "CW TRIM"));
            // 6. The fans' cable: NO POWER on the condenser, the feed at what natural draught holds.
            s = one(s, 6, a, () -> cut(a, "FANS POWER"), new Expect("NO POWER", () -> bound(a, Condition.NO_POWER, 0) && alarm(a, EquipmentAlarm.NO_POWER) && a.view().alpha() > 0),
                    () -> mend(a, "FANS POWER"));
            // 7. The reflux motor's cable: NO POWER, share 0.
            s = one(s, 7, a, () -> cut(a, "REFLUX MOTOR POWER"), new Expect("NO POWER", () -> bound(a, Condition.NO_POWER, 1) && alarm(a, EquipmentAlarm.NO_POWER) && a.view().alpha() == 0),
                    () -> mend(a, "REFLUX MOTOR POWER"));
            // 8. Pumparound 1's water: COOLING LIMITED on pumparound 1.
            s = one(s, 8, a, () -> toggle(a, "PA1 CW"), new Expect("COOLING LIMITED", () -> bound(a, Condition.PUMPAROUND_SHORT, 0) && alarm(a, EquipmentAlarm.COOLING_LIMITED)),
                    () -> toggle(a, "PA1 CW"));
            // 9. Not covered: no set point floods these plants (orchestrator's decision, option C). Measured at a sixteenth:
            // plant A's worst tray 0.69 of flood at design reflux, 0.81 at 2.5 times and 0.82 at 6 times (the heat in is
            // fixed, so more reflux cuts the distillate); its pumparounds at a twentieth 0.69 with COOLING_SHORT binding;
            // plant B's reboiler at 3 times 0.60 with HEATING_SHORT binding. The flood limit is gated by ColumnCycleTest and
            // ColumnRunTest.
            s = s.thenExecute(() -> {
                if (selected(9)) IChemE.LOGGER.info("review_failure case=9 expected=NOT_COVERED got=NOT_RUN share=NaN ticks=0");
            });
            // 10. A set point V3 refuses: SETPOINT REFUSED, one solve asked.
            long[] solves = {0, 0};
            s = one(s, 10, a, () -> {
                solves[0] = 0;
                solves[1] = -1;
                apply(a, setpoints(a, v -> {
                    v[4 + a.rig.input.steamFeeds().size() + 2] *= REFUSED_DRAW;
                    return v;
                }));
            }, new Expect("SETPOINT REFUSED, one solve", () -> refusedOnce(a, solves)), () -> apply(a, setpoints(a, v -> v)));
            // 11. The DCS: STOP, START, APPLY the design's set points; a console without the cable is refused.
            s = dcs(s);
            // 12. The level loop on, the drum's supply halved: the feed follows, the level holds.
            s = level(s);
            // 13. Plant B's hot utility: HEATING LIMITED.
            s = one(s, 13, b, () -> toggle(b, "KETTLE STEAM"), new Expect("HEATING LIMITED", () -> bound(b, Condition.HEATING_SHORT, -1) && alarm(b, EquipmentAlarm.HEATING_LIMITED)),
                    () -> toggle(b, "KETTLE STEAM"));
            return s;
        }

        /**
         * Case 10's check: SETPOINT REFUSED stands, no solve runs, and one setpoint solve was asked since the action
         * ({@code solves[0]} counts each {@code RE-SOLVING: setpoint} seen to begin, {@code solves[1]} is 1 while one shows).
         */
        boolean refusedOnce(Built x, long[] solves) {
            var v = x.view();
            if (v == null) return false;
            boolean solving = v.engine().startsWith("RE-SOLVING: setpoint");
            if (solving && solves[1] != 1) solves[0]++;
            solves[1] = solving ? 1 : 0;
            boolean refused = v.conditions().stream().anyMatch(c -> c.condition() == Condition.SETPOINT_REFUSED);
            return refused && !solving && solves[0] == 1;
        }

        /**
         * Case 11: from DCS-1, Stop (the column stops), Start (it runs at its design feed again), Apply the design's set
         * points (answered APPLIED); then DCS-2, on no cable, sends Stop and is answered NO DATA LINK while the column runs on.
         */
        GameTestSequence dcs(GameTestSequence s) {
            if (!selected(11)) return s;
            long[] t0 = {0};
            int[] step = {0};
            String[] answer = {""};
            s = s.thenExecute(() -> stage(() -> {
                t0[0] = now();
                operator.views.clear();
                operatorB = ControlLoopGameTests.operator(a.plant().level, a.dcsB);
                command(operator, a.dcs, a.columnHost, ColumnCommands.Action.STOP, List.of());
            })).thenWaitUntil(guard(() -> {
                if (failed() || step[0] == 4) return;
                paced();
                var v = a.view();
                var r = answer(operator);
                switch (step[0]) {
                    case 0 -> {
                        if (v != null && v.phase() == OperationView.Phase.STOPPED && r.ack() >= 1) {
                            answer[0] += "STOP:" + r.reply() + ";";
                            command(operator, a.dcs, a.columnHost, ColumnCommands.Action.START, List.of());
                            step[0] = 1;
                        }
                    }
                    case 1 -> {
                        if (r.ack() >= 2 && running(a) && atDesign(a)) {
                            answer[0] += "START:" + r.reply() + ";";
                            command(operator, a.dcs, a.columnHost, ColumnCommands.Action.APPLY, setpoints(a, x -> x));
                            step[0] = 2;
                        }
                    }
                    case 2 -> {
                        if (r.ack() >= 3 && running(a)) {
                            answer[0] += "APPLY:" + r.reply() + ";";
                            command(operatorB, a.dcsB, a.columnHost, ColumnCommands.Action.STOP, List.of());
                            step[0] = 3;
                        }
                    }
                    case 3 -> {
                        var rb = answer(operatorB);
                        if (rb.ack() >= 1 && running(a)) {
                            answer[0] += "DCS-2:" + rb.reply();
                            step[0] = 4;
                            long t = now() - t0[0];
                            boolean ok = answer[0].equals("STOP:APPLIED;START:APPLIED;APPLY:APPLIED;DCS-2:" + ColumnCommands.NO_DATA_LINK);
                            IChemE.LOGGER.info("review_failure case=11 expected=STOP:APPLIED;START:APPLIED;APPLY:APPLIED;DCS-2:NO_DATA_LINK got={} share={} ticks={}",
                                    answer[0].replace(' ', '_'), String.format(Locale.ROOT, "%.3f", a.view().alpha()), t);
                            report(a, "case11");
                            if (!ok) missed.add("case 11: " + answer[0]);
                            return;
                        }
                    }
                    default -> {
                    }
                }
                if (now() - t0[0] > START_TICKS) {
                    IChemE.LOGGER.info("review_failure case=11 expected=STOP:APPLIED;START:APPLIED;APPLY:APPLIED;DCS-2:NO_DATA_LINK got={} share=NaN ticks={}",
                            (answer[0] + "step" + step[0] + ":" + r.reply()).replace(' ', '_'), now() - t0[0]);
                    missed.add("case 11: " + answer[0] + " at step " + step[0] + ", " + words(a));
                    step[0] = 4;
                    return;
                }
                helper.assertTrue(false, "Waiting for the DCS step " + step[0]);
            }));
            return waitFor(s, "case 11 restored", () -> running(a) && running(b) && atDesign(a), RESTORE_TICKS);
        }

        /** The status of the controller row in {@code capture}'s latest view, or empty. */
        static String status(@Nullable ControlLoopGameTests.ViewCapture capture) {
            if (capture == null || capture.views.isEmpty()) return "";
            var rows = capture.views.getLast().data().getList("controllers", net.minecraft.nbt.Tag.TAG_COMPOUND);
            return rows.isEmpty() ? "" : rows.getCompound(0).getString("status");
        }


        /** The column's feed valve's pipe reading, mass and volume flow, for the log. */
        String feedValve() {
            var w = a.plant().world;
            long unit = w.column(a.plant().position(a.plant().column.origin())).id();
            var r = w.instrumentPipeReading(w.columnFeedValve(unit));
            return r == null ? "none" : String.format(Locale.ROOT, "%.4g kg/s %s m3/s", r.massFlow(), r.volumeFlow());
        }

        /** The surge drum's level, %, as the last publication to the open LIC-1 menu showed it; NaN unknown. */
        double fill() {
            for (int i = operator.views.size() - 1; i >= 0; i--) {
                var rows = operator.views.get(i).data().getList("controllers", net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int k = 0; k < rows.size(); k++) {
                    var inputs = rows.getCompound(k).getList("inputs", net.minecraft.nbt.Tag.TAG_COMPOUND);
                    for (int j = 0; j < inputs.size(); j++) {
                        var in = inputs.getCompound(j);
                        if (in.hasUUID("id") && in.getUUID("id").equals(a.level) && in.contains("secondary")) return in.getDouble("secondary");
                    }
                }
            }
            return Double.NaN;
        }

        /**
         * Case 12, the feed loop in its flow form: FIC-1's menu open until the supply's meter reads forward, then FIC-1 on
         * with the column as its output at half the column's design feed in kg/s (the feed station's mean molar mass). The
         * column's feed follows that flow target below its set rate: within {@link #CASE_TICKS} the feed stands at half its
         * design within 0.1 and the column runs; then FIC-1 back off on the supply's valve. The level form (LIC-1 on the
         * column) is not covered: with its feed closed the column's feed valve reads no flow, so LIC-1 has no density and
         * the feed returns to its set rate (MANUAL_TEST.md).
         */
        GameTestSequence level(GameTestSequence s) {
            if (!selected(12)) return s;
            long[] t0 = {0};
            int[] step = {0};
            double[] half = {Double.NaN};
            s = s.thenExecute(() -> stage(() -> {
                t0[0] = now();
                // FIC-1 on its own operator, whose menu stays open on it (an edit is checked against the open menu when the
                // engine takes it), so its status is published too.
                operatorF = ControlLoopGameTests.operator(a.plant().level, a.fic);
                operatorF.player.moveTo(a.fic.getX() + 1.5, a.fic.getY(), a.fic.getZ() + .5);
                ControlPanelMenu.open(operatorF.player, a.fic);
            })).thenWaitUntil(guard(() -> {
                if (failed() || step[0] == 2) return;
                paced();
                long t = now() - t0[0];
                var v = a.view();
                if (step[0] == 0) {
                    var reading = a.plant().world.instrumentPipeReading(a.rig.id(a.supplyMeter));
                    double flow = reading == null ? 0 : reading.massFlow();
                    if (!(flow > 0)) {
                        if (t > CASE_TICKS) {
                            missed.add("case 12: the supply's meter reads no forward flow: " + flow);
                            step[0] = 2;
                            return;
                        }
                        helper.assertTrue(false, "Waiting for the supply's flow");
                    }
                    var feedStation = a.plant().world.column(a.plant().position(a.plant().column.origin())).stations().stream()
                            .filter(st -> st.port() == Port.FEED).findFirst().orElseThrow();
                    double[] n = feedStation.account().moles(), w = a.plant().world.model().molecularWeights();
                    double moles = 0, mass = 0;
                    for (int i = 0; i < n.length; i++) {
                        moles += n[i];
                        mass += n[i] * w[i];
                    }
                    half[0] = designFeed * mass / moles / 2;
                    edit(operatorF, a.fic, ficPanel, new ControlSettings("FIC-1", true, a.flow, a.columnHost, Sensor.Unit.KILOGRAM_PER_SECOND, half[0], ControlSettings.Action.OUTLET));
                    log("action FIC-1 on the column target_kg_s={} (half the design feed; the supply read {})", half[0], flow);
                    t0[0] = now();
                    step[0] = 1;
                    helper.assertTrue(false, "Waiting for case 12");
                }
                double share = v == null ? Double.NaN : feedRate(v) / designFeed;
                boolean ok = t >= 1200 && running(a) && Math.abs(share - .5) <= .1;
                if (ok || t > CASE_TICKS) {
                    log("case=12 expected=\"feed follows a flow target\" feed_share={} fic=\"{}\" ticks={}", share, status(operatorF), t);
                    IChemE.LOGGER.info("review_failure case=12 expected=FEED_FOLLOWS_FLOW_TARGET got={}:feed={}:fic={} share={} ticks={}", words(a).replace(' ', '_'),
                            String.format(Locale.ROOT, "%.3f", share), status(operatorF).replace(' ', '_'),
                            v == null ? "NaN" : String.format(Locale.ROOT, "%.3f", v.alpha()), t);
                    if (!ok) missed.add("case 12: feed share " + share + ", FIC-1 " + status(operatorF) + ", " + words(a));
                    step[0] = 2;
                    return;
                }
                if (t % 600 == 0) log("case=12 t={} feed_share={} words={} fic=\"{}\" feed_valve={}", t, share, words(a), status(operatorF) + " " + answer(operatorF), feedValve());
                helper.assertTrue(false, "Waiting for case 12");
            })).thenExecute(() -> stage(() -> {
                var supply = a.plant().world.instrumentPipeReading(a.rig.id(a.supplyMeter));
                edit(operatorF, a.fic, ficPanel, new ControlSettings("FIC-1", false, a.flow, a.rig.id(a.supplyValve), Sensor.Unit.KILOGRAM_PER_SECOND,
                        Math.max(1, supply == null ? 1 : supply.massFlow()), ControlSettings.Action.OUTLET));
            }));
            return waitFor(s, "case 12 restored", () -> running(a) && running(b) && atDesign(a), RESTORE_TICKS);
        }
    }

    /** Case 10's third draw over the design's: past the draw wall V3 cannot solve. */
    static final double REFUSED_DRAW = 6;

    static void log(String format, Object... values) {
        IChemE.LOGGER.info("review_failure_log " + format, Arrays.stream(values).map(v -> v instanceof Double d ? (Object) String.format(Locale.ROOT, "%.6g", d) : v).toArray());
    }
}
