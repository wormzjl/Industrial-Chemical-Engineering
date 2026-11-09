package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;

/**
 * New equipment joins the simulator (batch 2026-11-02-process-simulator, plan section 3.10): each kind the game can build and each node the network compiles names its palette tile, and whether that
 * tile runs. The switches have no default, so a new constant fails the compile here. {@code FlowsheetPaletteCoverageTest} holds each to a tile, a port rule and a lang line. One tile may hold several
 * kinds, for a tile is a function: the run needs no drum's or heater's kind.
 */
public final class FlowsheetCatalog {
    public enum Coverage {
        /** A lit tile with a process model. */
        MODELLED,
        /** An unlit tile: drawn so the list stays whole, with no process model yet. */
        DRAWING_ONLY
    }

    /** Where a kind stands: its tile's type and whether it runs. */
    public record Entry(String tile, Coverage coverage) {
    }

    private static final Entry COLUMN = modelled("column"), DRUM = modelled("drum"), TANK = modelled("storage_tank"), EXCHANGER = modelled("exchanger"), KETTLE = modelled("kettle"),
            AIR_COOLER = modelled("cooler"), HEATER = modelled("fired_heater"), PUMP = modelled("pump"), COMPRESSOR = modelled("compressor"), COOLING_TOWER = drawn("cooling_tower"),
            EJECTOR = modelled("ejector"), TURBINE = modelled("steam_turbine"), DRIVER = drawn("steam_turbine"), ADSORBER = drawn("adsorber"), CYCLONE = drawn("cyclone"), STIRRED = drawn("stirred_reactor"),
            TRICKLE = drawn("trickle_bed"), EBULLATED = drawn("ebullated_bed"), FCC = drawn("fcc_converter"), COKER = drawn("delayed_coker"), CFB = drawn("cfb_boiler"),
            FLUID_BED = drawn("fluid_bed_gasifier"), GRATE = drawn("grate_boiler"), FIXED_BED = drawn("fixed_bed_gasifier"), FLARE = modelled("flare");

    private FlowsheetCatalog() {
    }

    private static Entry modelled(String tile) {
        return new Entry(tile, Coverage.MODELLED);
    }

    private static Entry drawn(String tile) {
        return new Entry(tile, Coverage.DRAWING_ONLY);
    }

    public static Entry of(EquipmentShape.Kind kind) {
        return switch (kind) {
            case DRUM -> DRUM;
            case COLUMN -> COLUMN;
            case TANK, SPHERE -> TANK;
            case EXCHANGER -> EXCHANGER;
            case KETTLE -> KETTLE;
            case AIR_COOLER -> AIR_COOLER;
            case EJECTOR -> EJECTOR;
            case PUMP -> PUMP;
            case COMPRESSOR, CENTRIFUGAL_COMPRESSOR -> COMPRESSOR;
            case VERTICAL_HEATER, CABIN_HEATER -> HEATER;
            case STIRRED_REACTOR -> STIRRED;
            case ADSORBER -> ADSORBER;
            case TRICKLE_BED_REACTOR -> TRICKLE;
            case EBULLATED_BED_REACTOR -> EBULLATED;
            case STEAM_TURBINE -> TURBINE;
            // The drivers and the gearbox (batch 2026-11-03-rotating-machinery) pass shaft power, no stream: until the simulator has a tile
            // of their own, they stand on the steam turbine's tile, drawn only: they are not run (W8, D43).
            case MOTOR, GENERATOR, GEARBOX -> DRIVER;
            case CYCLONE -> CYCLONE;
            case FCC_CONVERTER -> FCC;
            case DELAYED_COKER -> COKER;
            case FLUIDIZED_BED_BOILER -> CFB;
            case FLUIDIZED_BED_GASIFIER -> FLUID_BED;
            case GRATE_BOILER -> GRATE;
            case FIXED_BED_GASIFIER -> FIXED_BED;
            case INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER -> COOLING_TOWER;
            case FLARE_STACK -> FLARE;
        };
    }

    public static Entry of(ConstructorColumn.Equipment equipment) {
        return switch (equipment) {
            case VERTICAL_DRUM, HORIZONTAL_DRUM -> DRUM;
            case COLUMN -> COLUMN;
            case FLOATING_ROOF_TANK, SPHERE -> TANK;
            case ADSORBER -> ADSORBER;
            case CYCLONE -> CYCLONE;
            case EXCHANGER -> EXCHANGER;
            case KETTLE -> KETTLE;
            case AIR_COOLER -> AIR_COOLER;
            case VERTICAL_HEATER, CABIN_HEATER -> HEATER;
            case INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER -> COOLING_TOWER;
            case FLARE_STACK -> FLARE;
            case PUMP -> PUMP;
            case COMPRESSOR, CENTRIFUGAL_COMPRESSOR -> COMPRESSOR;
            case EJECTOR -> EJECTOR;
            case STEAM_TURBINE -> TURBINE;
            // The drivers and the gearbox (batch 2026-11-03-rotating-machinery) pass shaft power, no stream: until the simulator has a tile
            // of their own, they stand on the steam turbine's tile, drawn only: they are not run (W8, D43).
            case MOTOR, GENERATOR, GEARBOX -> DRIVER;
            case STIRRED_REACTOR -> STIRRED;
            case TRICKLE_BED_REACTOR -> TRICKLE;
            case EBULLATED_BED_REACTOR -> EBULLATED;
            case FCC_CONVERTER -> FCC;
            case DELAYED_COKER -> COKER;
            case FLUIDIZED_BED_BOILER -> CFB;
            case FLUIDIZED_BED_GASIFIER -> FLUID_BED;
            case GRATE_BOILER -> GRATE;
            case FIXED_BED_GASIFIER -> FIXED_BED;
        };
    }

    /** A pipe, a generator and a void are a stream's, which the feed and product tiles draw. */
    public static Entry of(TopologyCompiler.Kind kind) {
        return switch (kind) {
            case RESERVOIR -> TANK;
            case PIPE, GENERATOR -> modelled(Tiles.FEED);
            case VOID -> modelled(Tiles.PRODUCT);
            case PUMP -> PUMP;
            case VALVE -> modelled("valve");
            case FILTER -> modelled("filter");
            case COMPRESSOR -> COMPRESSOR;
            case HEAT_SIDE -> EXCHANGER;
            // A steam turbine registers as a fluid device (W3, reading A37).
            case TURBINE -> TURBINE;
        };
    }
}
