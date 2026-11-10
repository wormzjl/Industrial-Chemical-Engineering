package com.wormzjl.icheme.science.flowsheet.sizing;

import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.above;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.given;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.least;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.most;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.within;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.Step.rest;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.Step.to;
import static com.wormzjl.icheme.science.flowsheet.sizing.Bound.Step.under;
import static com.wormzjl.icheme.science.flowsheet.sizing.Limit.Kind.MUST;
import static com.wormzjl.icheme.science.flowsheet.sizing.Limit.Kind.RULE;
import static com.wormzjl.icheme.science.flowsheet.sizing.Limit.Kind.SHOULD;
import static com.wormzjl.icheme.science.flowsheet.sizing.Limit.Rank.PAID;
import static com.wormzjl.icheme.science.flowsheet.sizing.Limit.Rank.SURPLUS;
import static com.wormzjl.icheme.science.flowsheet.sizing.Limit.Rank.QUIET;

import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.sizing.Bound.Axis;
import com.wormzjl.icheme.science.flowsheet.sizing.Bound.Sense;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.machine.Motor;

/**
 * The sizing stage's limits, one row a limit (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.3, 3.4 and
 * 3.10). A value changes here and nowhere else; the code around a row reads it. Each row's source is the plan's tag, an entry of
 * {@code SIZING_SOURCES.md}; a World row reads the world's own constant where one exists. The name is the row's stable id.
 *
 * <p>Numbers stand in the unit the plan writes them in, so a row reads as its line of the plan.
 */
public enum Limit {
    // ---- Every item, section 3.5 ----
    /** 1 if the run is steady, 0 if not. A sizing reads only a steady run's streams; on any other run every item holds this one reading. */
    RUN_STEADY(MUST, least(1), "", "World: `FlowsheetRun.Status`"),
    /**
     * Auto's spare over the design need (S4c): an exchanger's or kettle's surface, an air cooler's, a heater's duty, its type and its
     * count of cabins, and a compressor's displacement. Auto first tries the sizes that hold the need and this much more. The owner's
     * decisions of 2026-11-03: "let it propose the actual equipment to build instead", with "20 % spare", and "always have enough
     * excess". The lamps read the bare need.
     */
    AUTO_SPARE(RULE, least(20), "%", "This plan"),

    // ---- Every line, section 3.3 ----
    LINE_BORE(MUST, most(PipeSizes.MAX_BORE), "m", "World: `PipeSizes.MAX_BORE`", QUIET),
    /** The line's state gives this one: the lesser of 100 m/s and the isothermal acoustic bound. */
    LINE_SPEED(MUST, given(Sense.MOST), "m/s", "World: `velocityLimit`", QUIET),
    LINE_NOZZLE(MUST, given(Sense.MOST), "m", "World: `NozzleBore.largest`", QUIET),
    /** On a line with solids: the liquid's speed, over the speed that keeps them suspended. A refusal that speed does not decide takes no speed. */
    LINE_SOLIDS(MUST, given(Sense.ABOVE), "m/s", "World: `SolidMobility`"),
    /** Auto pays this one: it takes parallel runs, section 3.5, before it leaves a run outside a service's limits. */
    LINE_RUNS(SHOULD, most(1), "", "This plan", PAID),

    // ---- Every unit, section 3.1 ----
    /** 1 if the constructor builds this size, 0 if not. */
    UNIT_SHAPE(MUST, least(1), "", "World: `EquipmentShape`", QUIET),

    // ---- Which service, section 3.3: S1's decisions are in the batch README ----
    /** Above this mole share of a liquid, it is water. */
    SERVICE_WATER(RULE, most(95), "%", "This plan"),
    /** Above this mass share, a stream is vapour. */
    SERVICE_VAPOUR(RULE, most(95), "%", "This plan"),
    /** Above this mass share of vapour, a stream is two-phase. */
    SERVICE_TWO_PHASE(RULE, most(.05), "%", "This plan"),
    /** Above this mole share of the gas, a vapour is steam: the same share as the water row. */
    SERVICE_STEAM(RULE, most(95), "%", "This plan"),
    /** Steam stands this far over the saturation at its partial pressure to count as superheated: V3's own rule for a steam feed. */
    SERVICE_SUPERHEAT(RULE, least(ColumnSolve.SUPERHEAT), "K", "World: `ColumnSolve.SUPERHEAT`"),
    /** A vapour under this pressure is vacuum vapour, whatever its gas. */
    SERVICE_VACUUM(RULE, most(PhaseLayout.AMBIENT_PRESSURE / 1e5), "bara", "World: `PhaseLayout.AMBIENT_PRESSURE`"),
    /** A suction this close to its bubble pressure is boiling. */
    SERVICE_BOILING(RULE, most(.3), "bar", "This plan"),

    // ---- Each service's limits, section 3.3 ----
    SUCTION_BOILING_SPEED(SHOULD, most(Axis.BORE, under(.25, 1), to(.5, 1.25), rest(1.5)), "m/s", "[KARM]"),
    SUCTION_BOILING_DROP(SHOULD, most(.10), "bar/100 m", "[LINEG]"),
    SUCTION_COLD_SPEED(SHOULD, most(Axis.BORE, under(.25, 1.5), rest(1.8)), "m/s", "[KARM]"),
    SUCTION_COLD_DROP(SHOULD, most(.10), "bar/100 m", "[LINEG]"),
    DRAW_SPEED(SHOULD, most(Axis.BORE, to(.05, .6), rest(.9)), "m/s", "[KARM]"),
    DRAW_DROP(SHOULD, most(.10), "bar/100 m", "[LINEG]"),
    LIQUID_SPEED(SHOULD, most(3), "m/s", "[KARM], [THAK]"),
    LIQUID_DROP(SHOULD, most(.45), "bar/100 m", "[ET]"),
    WATER_SPEED(SHOULD, most(3), "m/s", "[KARM], [LELE]"),
    WATER_DROP(SHOULD, most(.45), "bar/100 m", "[ET]"),
    GAS_SPEED(SHOULD, most(Axis.GAUGE, to(.1, 60), to(1, 40), to(5, 30), rest(20)), "m/s", "[KARM]"),
    GAS_DROP(SHOULD, most(.11), "bar/100 m", "[CHER]"),
    GAS_MOMENTUM(SHOULD, most(Axis.GAUGE, under(20, 6000), under(50, 7500), under(80, 10000), rest(15000)), "kg/m·s²", "[KARM]"),
    STEAM_SATURATED_SPEED(SHOULD, most(40), "m/s", "[SPX]"),
    STEAM_SATURATED_DROP(SHOULD, most(.2), "bar/100 m", "[SPX]"),
    STEAM_SATURATED_MOMENTUM(SHOULD, most(15000), "kg/m·s²", "[KARM]"),
    STEAM_SUPERHEATED_SPEED(SHOULD, most(60), "m/s", "[SPX]"),
    STEAM_SUPERHEATED_DROP(SHOULD, most(.2), "bar/100 m", "[SPX]"),
    STEAM_SUPERHEATED_MOMENTUM(SHOULD, most(15000), "kg/m·s²", "[KARM]"),
    VACUUM_SPEED(SHOULD, most(Axis.ABSOLUTE, under(.01, 90), under(.05, 75), rest(60)), "m/s", "[KARM]"),
    /** A share of the absolute pressure, over the whole line, so it reads the line's length. */
    VACUUM_DROP(SHOULD, most(5), "%", "[BRAN]"),
    /** A floor: a slower flow slugs. */
    TWO_PHASE_SPEED(SHOULD, least(3), "m/s", "[A14E]"),
    TWO_PHASE_MOMENTUM(SHOULD, most(10000), "kg/m·s²", "[KARM]"),

    // ---- Pumps, section 3.4 ----
    PUMP_FLOW(MUST, most(FlowControl.Mover.MAXIMUM_VOLUME_FLOW * 3600), "m³/h", "World: `FluidNetwork.Controls`", QUIET),
    /** A rise for the world's reference fluid, so a head in metres takes that fluid's density. */
    // Its bound is the world's added pressure, Pa; a pump reads it as head, m of its own liquid, against that pressure as head of the reference liquid.
    PUMP_HEAD(MUST, most(FlowControl.Pump.MAXIMUM_ADDED_PRESSURE), "m", "World: 2 MPa at water reference", QUIET),
    /** The world starves a pump whose suction falls to its liquid's vapour pressure plus {@code 0.85 NPSH3} at its flow
     * (batch 2026-11-03-rotating-machinery, owner decisions D20 Q11 A and D21): the rating gives that floor
     * ({@link PumpSizing#requiredNpsh}). */
    PUMP_NPSH_WORLD(MUST, given(Sense.ABOVE), "m", "World: `PUMP_SUCTION_LIMIT` at `0.85 NPSH3` (`Npsh`)", QUIET),
    /** The margin over the pump's NPSH3 (m): the rating reads NPSH available against NPSH3 plus this number. */
    PUMP_NPSH(SHOULD, least(1), "m", "[HI-961]", QUIET),
    PUMP_RATED(SHOULD, within(70, 120), "%", "[API610]"),

    // ---- Compressors ----
    COMPRESSOR_RATIO(MUST, most(FlowControl.Compressor.MAXIMUM_PRESSURE_RATIO), "", "World: `FluidNetwork.Controls`"),
    /** The world compresses a wet suction since request 11 of batch 2026-11-03-rotating-machinery (no refusal, so no MUST,
     * D14); a wet suction costs efficiency and, with W10b, wears the machine. The share is the refusal it replaced. */
    COMPRESSOR_LIQUID(SHOULD, most(1), "%", "This plan: a wet suction erodes (rotating machinery, plan section 3.6)", QUIET),
    COMPRESSOR_DISPLACEMENT(SHOULD, given(Sense.LEAST), "m³/h", "World: `displacement`"),
    COMPRESSOR_STAGE_PISTON(SHOULD, most(3.5), "", "[PEREZ]"),
    COMPRESSOR_STAGE_TURBO(SHOULD, most(3), "", "[PEREZ]"),
    COMPRESSOR_DISCHARGE(SHOULD, most(150), "°C", "[WEAK]"),
    COMPRESSOR_DISCHARGE_HYDROGEN(SHOULD, most(135), "°C", "[API618]"),
    /** Above this mole share of hydrogen, a gas takes the hydrogen row: the standard says hydrogen-rich and gives no share here. */
    COMPRESSOR_HYDROGEN(RULE, most(50), "%", "This plan"),

    // ---- Exchangers ----
    /** Above this vapour share by mass the world's film leaves liquid, so a side stops reading the liquid drop. */
    EXCHANGER_SIDE(RULE, most(100 * HeatCurve.LIQUID_UNTIL), "%", "World: `HeatCurve.LIQUID_UNTIL`"),
    EXCHANGER_SURFACE(MUST, given(Sense.LEAST), "m²", "World"),
    EXCHANGER_HOT_END(MUST, above(0), "K", "World: counterflow", QUIET),
    EXCHANGER_COLD_END(MUST, above(0), "K", "World: counterflow", QUIET),
    EXCHANGER_APPROACH(SHOULD, least(10), "K", "[BLOG]"),
    EXCHANGER_DROP_LIQUID(SHOULD, most(.7), "bar", "[BLOG]"),
    EXCHANGER_DROP_GAS(SHOULD, most(.2), "bar", "[SCR-HX], [BLOG]"),
    EXCHANGER_DROP_BOILING(SHOULD, most(.1), "bar", "[BLOG]"),
    EXCHANGER_OVER_NEED(SHOULD, most(100), "%", "This plan", SURPLUS),
    EXCHANGER_WATER_OUT(SHOULD, most(45), "°C", "[BLOG]", QUIET),
    KETTLE_FLUX(SHOULD, most(38), "kW/m²", "[ET]"),

    /**
     * An air-then-water condenser's member that takes no duty, MW (C2): a trim below a receiver at the air's split or above it, or
     * an air cooler above a top vapour at it or below it. The twin stays the player's choice; the other member alone does the work.
     * The owner, 2026-11-03: "I mean the user can decide, but it's pointless to use twin cooler config if you don't need to cool
     * below 40C".
     */
    TWIN_IDLE(SHOULD, above(0), "MW", "plan section 6"),

    // ---- Air coolers ----
    AIR_COOLER_SURFACE(MUST, given(Sense.LEAST), "m²", "World"),
    AIR_COOLER_OUTLET(SHOULD, least(15), "K", "[HALL-R]"),
    AIR_COOLER_DROP(SHOULD, most(.7), "bar", "[WEAK]"),

    // ---- Fired heaters ----
    HEATER_DUTY(MUST, given(Sense.LEAST), "MW", "World: `heaterDuty`"),
    /** Also the type's threshold, section 3.10: a vertical while the largest holds the design duty with {@link #AUTO_SPARE}, a cabin above. */
    HEATER_VERTICAL(MUST, most(largestHeater(EquipmentShape.Kind.VERTICAL_HEATER)), "MW", "World: `heaterDuty`", QUIET),
    HEATER_CABIN(SHOULD, least(23), "MW", "[FH-TYPE]", QUIET),
    HEATER_SPARE(MUST, least(0), "bar", "World", QUIET),

    // ---- Columns ----
    /** 1 if the column solves at this diameter, 0 if not: a trial that does not solve is no fit. */
    COLUMN_SOLVES(MUST, least(1), "", "World: `V3ColumnCalculator`", QUIET),
    COLUMN_FLOOD_WORLD(MUST, most(100), "%", "World: `V3TrayHydraulics`", QUIET),
    COLUMN_FLOOD(SHOULD, most(80), "%", "[NPTEL], [KIST]"),
    COLUMN_DIAMETER(MUST, most(EquipmentShape.MAX_COLUMN_DIAMETER), "m", "World", QUIET),
    COLUMN_TRAYS(MUST, most(ColumnTrays.MAX_TRAYS), "", "World", QUIET),
    COLUMN_DOWNCOMER(SHOULD, most(.18), "m/s", "[KIST]"),
    COLUMN_TRAY_DROP(SHOULD, most(1), "kPa", "[TRAY-X]"),
    COLUMN_SUMP(SHOULD, least(5), "min", "[ET]"),
    COLUMN_SLENDERNESS(SHOULD, most(30), "", "[ROT]", QUIET),

    // ---- Drums ----
    DRUM_DIAMETER(MUST, most(EquipmentShape.MAX_DIAMETER), "m", "World", QUIET),
    DRUM_LENGTH(MUST, most(EquipmentShape.MAX_TANGENT_LENGTH), "m", "World", QUIET),
    /** 1 if {@code bootFor} has room, 0 if not. */
    DRUM_BOOT(MUST, least(1), "", "World: `bootFor`", QUIET),
    /** The drum's entry starts at this. */
    DRUM_HOLD(SHOULD, least(5), "min", "[BLOG], [COST]"),
    DRUM_SLENDERNESS_LYING(SHOULD, within(2.5, 5), "", "[BLOG]", QUIET),
    DRUM_SLENDERNESS_STANDING(SHOULD, least(2), "", "[TOW]", QUIET),
    /** K of the gas speed's law, √((ρL − ρV)/ρV) times this: half the padded value, as the world's drum has no pad. */
    DRUM_GAS_STANDING(SHOULD, most(.107 / 2), "m/s", "[GPSA]"),
    DRUM_GAS_LYING(SHOULD, most(1.25 * .107 / 2), "m/s", "[GPSA], [FLU]"),
    DRUM_BOOT_HOLD(SHOULD, least(5), "min", "[REDBAG]"),

    // ---- Tanks and spheres ----
    TANK_VOLUME(MUST, most(EquipmentShape.MAX_VOLUME), "m³", "World", QUIET),
    TANK_DIAMETER(MUST, size(EquipmentShape.Kind.TANK), "m", "World", QUIET),
    TANK_SHELL(MUST, within(UnitDesign.of(EquipmentShape.Kind.TANK).minLength(0), UnitDesign.of(EquipmentShape.Kind.TANK).maxLength(0)), "m", "World", QUIET),
    SPHERE_DIAMETER(MUST, size(EquipmentShape.Kind.SPHERE), "m", "World", QUIET),
    /** The tank's entry starts at this. */
    TANK_HOLD(SHOULD, least(24), "h", "This plan"),
    TANK_WORKING(SHOULD, most(85), "%", "[REDDY]"),
    ROOF_BUBBLE_WORLD(MUST, most(PhaseLayout.AMBIENT_PRESSURE / 1e5), "bar", "World: `OpenVessel`", QUIET),
    /** Also the type's threshold, section 3.10: a floating roof at most this, a sphere above. */
    ROOF_BUBBLE(SHOULD, most(.766), "bar", "[CFR], [KC]"),
    SPHERE_DESIGN(SHOULD, most(20), "bar", "[API-P]"),
    /** A sphere's design pressure over its bubble pressure. */
    SPHERE_DESIGN_FACTOR(RULE, least(1.1), "", "[API-P]"),

    // ---- Pressure paths, section 3.6 ----
    /** What a path to a pressure the sheet sets has left at design flow, past its sized drops: a valve at its end takes it. */
    PATH_SPARE(MUST, least(0), "bar", "World", QUIET),
    /**
     * A set path with no valve at its end: its spare over what it loses at design flow (S4c). Its losses go as the flow squared, so
     * 20 % holds √1.2 = 1.095 × the design flow, about 120 % of the steady flow at the default basis. A path that ends at a valve
     * reads the valve's rows instead.
     * The orchestrator's reading of the owner's "always have enough excess", 2026-11-03.
     */
    PATH_EXCESS(SHOULD, least(20), "%", "This plan", QUIET),

    // ---- Valves and ejectors ----
    VALVE_SHARE(SHOULD, least(20), "%", "[HALL-V]"),
    VALVE_DROP(SHOULD, least(.7), "bar", "[HALL-V]"),
    /**
     * A wired Motive port's steam against what the ejector's law needs (W10, section 3.12): with less, the ejector cannot hold its
     * suction. A free port is a utility that gives the need, and reads neither motive row.
     */
    EJECTOR_MOTIVE(MUST, given(Sense.LEAST), "kg/h", "World: `EjectorLaw`", QUIET),
    /**
     * A wired Motive port's steam over the need, as {@link #AUTO_SPARE}'s 20 %: yellow between the need and 1.20 times it. The W10
     * thread's reading of the owner's "always have enough excess" (2026-11-03), reversible by the owner.
     */
    EJECTOR_MOTIVE_SPARE(SHOULD, least(20), "%", "This plan", QUIET),

    // ---- Flare stacks (batch 2026-11-09-flare-stack, W4) ----
    /** The gas's stoichiometric flame temperature: below the owner's 1700 K (X1) the flame does not hold, at any tip. */
    FLARE_FLAME(MUST, least(com.wormzjl.icheme.science.fluid.network.Flare.FLAME_LIMIT), "K", "World: `Flare.FLAME_LIMIT`"),
    /** The gas at design flow against the most the tip passes of it: its rated heat or its stable exit velocity (X4). */
    FLARE_CAPACITY(MUST, given(Sense.MOST), "kg/h", "World: `Flare.limit`", QUIET),
    /** What the tip passes over the load, as {@link #AUTO_SPARE}'s 20 %: the owner's "always have enough excess". */
    FLARE_SPARE(SHOULD, least(20), "%", "This plan", QUIET),

    // ---- Batch stills (batch 2026-11-09-batch-distiller, W7) ----
    /** The charge's volume over the pot's: the pot holds no more. */
    STILL_VOLUME(MUST, most(100), "%", "World: the pot's volume"),
    /** The usual charge, {@code BatchStill.FILL}: a fuller pot boils over into its neck (plan section 6, item 6). */
    STILL_FILL(SHOULD, most(100 * com.wormzjl.icheme.science.fluid.network.BatchStill.FILL), "%", "This plan: `BatchStill.FILL`"),
    /** The batch's time at the design flow against the cycle: a still that takes longer cannot keep up with its inlet. */
    STILL_BATCH(MUST, given(Sense.MOST), "h", "World: `BatchStill.step`"),
    /** The cycle over the batch's time, as {@link #AUTO_SPARE}'s 20 %: the owner's "always have enough excess". */
    STILL_BATCH_SPARE(SHOULD, least(20), "%", "This plan"),
    /** The least share of the head's vapour the condenser takes: short of it, the rest vents (COOLING LIMITED). The same at every pot. */
    STILL_COOLING(SHOULD, least(100), "%", "World: `BatchStill.condenserCapacity`", QUIET),
    /** The share of the batch the column runs under its flood, F {@code BatchStill.FLOOD_F_FACTOR}; past it the firing is held. The same at every pot. */
    STILL_FLOOD(MUST, least(100), "%", "World: `BatchStill.FLOOD_F_FACTOR`", QUIET),
    /** The column's highest F-factor against its design, {@code BatchStill.DESIGN_F_FACTOR} at the full boil-up. The same at every pot. */
    STILL_F_FACTOR(SHOULD, most(com.wormzjl.icheme.science.fluid.network.BatchStill.DESIGN_F_FACTOR), "Pa^0.5", "This plan: `BatchStill.DESIGN_F_FACTOR`", QUIET),

    // ---- Steam turbines (batch 2026-11-03-rotating-machinery, W8, D43) ----
    /** The rated power over the shaft power at design flow, at least a motor's {@link Motor#SPARE}: the owner's "always have enough excess". */
    TURBINE_SPARE(SHOULD, least(Motor.SPARE), "", "This plan: `Motor.SPARE`"),
    /** The inlet's pressure under its steam class's header: a casing is rated for its class. The world reads the real inlet and refuses none. */
    TURBINE_INLET(SHOULD, given(Sense.MOST), "kPa", "This plan: `MachineDuty.Steam`", QUIET),

    // ---- Types, section 3.10: what section 3.4 has no row for ----
    /** Above the largest cabin, a count of cabins. */
    TYPE_CABIN_LARGEST(RULE, most(largestHeater(EquipmentShape.Kind.CABIN_HEATER)), "MW", "[FH-TYPE], World"),
    /** A drum whose hold would need more than this lies down. */
    TYPE_DRUM_STANDING(RULE, most(5), "", "[SCR-VES], [TOW]"),
    TYPE_PISTON(SHOULD, most(15000), "m³/h", "[COMP-X], [GALL]"),
    /** Under the smallest frame no turbo exists. */
    TYPE_TURBO(MUST, least(ProcessUnit.turboFlow(UnitDesign.of(EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR).minSize())), "m³/h", "World");

    public enum Kind {
        /** Missed, the row is red: the world refuses it, its own model says it fails, or the constructor cannot build it. */
        MUST,
        /** Missed, the row is yellow: outside good practice, and it still runs. */
        SHOULD,
        /** A threshold that picks a service or a type: it lights no lamp. */
        RULE
    }

    /** How a reading of this row weighs when nothing has failed. */
    public enum Rank {
        /** It can be what is closest. */
        CLOSEST,
        /** It is what is closest only when it fails: a size of this item hardly moves it. */
        QUIET,
        /** As QUIET, and {@link Selection} pays it to meet every other limit: the lamp shows the price. */
        PAID,
        /** Unused capacity: visible as an advisory, never a reason to build additional equipment. */
        SURPLUS
    }

    private final Kind kind;
    private final Bound bound;
    private final String unit, source;
    private final Rank rank;

    Limit(Kind kind, Bound bound, String unit, String source) {
        this(kind, bound, unit, source, Rank.CLOSEST);
    }

    Limit(Kind kind, Bound bound, String unit, String source, Rank rank) {
        this.kind = kind;
        this.bound = bound;
        this.unit = unit;
        this.source = source;
        this.rank = rank;
    }

    private static double largestHeater(EquipmentShape.Kind kind) {
        var design = UnitDesign.of(kind);
        return ProcessUnit.heaterDuty(kind, design.maxSize(), design.maxLength(design.maxSize()));
    }

    private static Bound size(EquipmentShape.Kind kind) {
        var design = UnitDesign.of(kind);
        return within(design.minSize(), design.maxSize());
    }

    public Kind kind() {
        return kind;
    }

    public Bound bound() {
        return bound;
    }

    public String unit() {
        return unit;
    }

    /** The plan's tag. */
    public String source() {
        return source;
    }

    public Rank rank() {
        return rank;
    }

    /** The row's number, on a row with one. */
    public double value() {
        return value(0);
    }

    /** The row's number at {@code key} on its {@linkplain Bound#axis axis}. */
    public double value(double key) {
        return bound.at(key);
    }

    /** A reading of {@code value} against the row's number. */
    public Reading read(double value) {
        return read(value, 0);
    }

    /** A reading of {@code value} against the row's number at {@code key} on its axis. */
    public Reading read(double value, double key) {
        return against(value, value(key));
    }

    /** A reading of {@code value} against a limit the rating gives, or works out from the row's number. */
    public Reading against(double value, double limit) {
        return Reading.of(this, value, limit);
    }
}
