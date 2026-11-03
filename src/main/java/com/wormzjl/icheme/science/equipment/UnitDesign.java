package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One kind of standalone unit (owner request 2026-10-18: eleven new units, built in parallel; batch
 * 2026-10-15-custom-equipment-rendering): its sizes, the constructor's rows, the datasheet's lines and its parts. Each
 * kind's design is its own file; {@link ProcessUnit} keeps the shared drawing kit (parts, solids, nozzles, decks,
 * ladders, moving parts) and the static sizes the game calls, which ask the kind's design.
 *
 * <p>The sizes a player sets are the shape's {@code diameter} (the unit's main size, on {@link #sizeStep()}),
 * {@code tangentLength} (a second whole number: a length, a height or a count; 1 where the kind has none) and
 * {@code support} (a height under the unit, m). A design draws its unit in the frame described in {@link ProcessUnit}.
 */
public abstract class UnitDesign {
    /** A screen line: its translation key (under the screen's own prefix) and its formatted arguments. */
    public record Line(String key, Object... args) {
    }

    // ---- Sizes ------------------------------------------------------------------------------------------------------

    public abstract double minSize();

    public abstract double maxSize();

    public abstract double sizeStep();

    /** Every main size a unit of the kind may take, smallest first: the grid from {@link #minSize()} by {@link #sizeStep()}. */
    public List<Double> sizes() {
        var out = new java.util.ArrayList<Double>();
        long count = Math.round((maxSize() - minSize()) / sizeStep());
        for (long k = 0; k <= count; k++) out.add(Math.round((minSize() + k * sizeStep()) * 1000) / 1000.0);
        return out;
    }

    /** {@code size} on the kind's grid and inside its range, cleared of rounding (0.30000000000000004 becomes 0.3). */
    public double snap(double size) {
        double step = sizeStep(), min = minSize();
        double k = Math.max(0, Math.min(Math.round((maxSize() - min) / step), Math.round((size - min) / step)));
        return Math.round((min + k * step) * 1000) / 1000.0;
    }

    /** {@code size} moved {@code steps} places along the kind's sizes (the constructor's [−] and [+]). */
    public double step(double size, int steps) {
        return snap(size + steps * sizeStep());
    }

    // ---- Design point (shape fields 6, batch 2026-11-03-rotating-machinery) ------------------------------------------

    /**
     * Whether the kind's shape carries a design point ({@link MachineDuty}): a pump, a centrifugal compressor, a steam
     * turbine, a motor, a generator, a gearbox. Its main and second sizes then follow from the design point.
     */
    public boolean hasDuty() {
        return false;
    }

    /**
     * The design point of a unit of these sizes where none was given: a shape saved before shape fields 6 (its
     * behaviour then, decision A3), and the sizes the constructor and the tests step through. {@link MachineDuty#NONE}
     * for a kind without one.
     */
    public MachineDuty duty(double size, int length) {
        return MachineDuty.NONE;
    }

    /** The main size design point {@code duty} gives. */
    public double size(MachineDuty duty) {
        throw new IllegalArgumentException("This kind has no design point");
    }

    /** The second size design point {@code duty} gives. */
    public int length(MachineDuty duty) {
        return 1;
    }

    /** Checks {@code duty}'s ranges for the kind; throws {@link IllegalArgumentException} with the reason. */
    void checkDuty(MachineDuty duty) {
    }

    /**
     * Design point {@code duty} with its setting {@code setting} ({@code flow}, {@code head}, {@code power} or
     * {@code exhaust}) stepped {@code steps} places along the values the constructor offers, held at its ends;
     * {@code duty} itself for a setting the kind does not have. Every value stepped to is in the kind's ranges.
     */
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return duty;
    }

    /** Checks a shape's design point: in its ranges, and giving the shape's sizes; none for a kind without one. */
    final void check(double size, int length, MachineDuty duty) {
        if (!hasDuty()) {
            if (!duty.equals(MachineDuty.NONE)) throw new IllegalArgumentException("This kind has no design point");
            return;
        }
        checkDuty(duty);
        if (Math.abs(size(duty) - size) > 1e-9 || length(duty) != length)
            throw new IllegalArgumentException("A machine's sizes follow its design point: " + size(duty) + " and " + length(duty) + ", not " + size + " and " + length);
    }

    /** {@code value} rounded up onto a grid of {@code step} (a micrometre's rounding error kept on its step), within lo..hi. */
    static double upTo(double value, double step, double lo, double hi) {
        double k = Math.ceil(value / step - 1e-6);
        return Math.max(lo, Math.min(hi, Math.round(k * step * 1000) / 1000.0));
    }

    /** The least second size (length, height or count) at main size {@code size}; 1 where the kind has none. */
    public int minLength(double size) {
        return 1;
    }

    public int maxLength(double size) {
        return 1;
    }

    /** A new unit's second size at main size {@code size}: the least by default (a set of adsorbers starts at two beds). */
    public int defaultLength(double size) {
        return minLength(size);
    }

    /** The second size's step (a balanced-opposed compressor's throws come in pairs). */
    public int lengthStep() {
        return 1;
    }

    public abstract int minSupport(double size, int length);

    public int maxSupport() {
        return EquipmentShape.MAX_SUPPORT;
    }

    /** A new unit's support, m: the least by default. */
    public int defaultSupport(double size, int length) {
        return minSupport(size, length);
    }

    public abstract Orientation orientation();

    /** Whether the kind is built in banks of bays ({@link ProcessUnit.Bank}): only air coolers. */
    public boolean banks() {
        return false;
    }

    /** Checks beyond the ranges and the grid; throws {@link IllegalArgumentException} with the reason. */
    void check(double size, int length, int support, ProcessUnit.Bank bank) {
    }

    /**
     * The quarter turns from the front the unit's bottom outlet may take at these sizes, as bits (bit q: q turns, through
     * {@link EquipmentShape#SIDES}; the front, bit 0, always): only a unit whose bottom outlet leaves horizontally through
     * its skirt turns it (owner request 2026-10-25), to the sides its other parts leave free. The ranges hold.
     */
    public int outlets(double size, int length, int support) {
        return 1;
    }

    /** Whether units of the kind have moving parts: their datasheet carries the switch. */
    public boolean moves() {
        return false;
    }

    // ---- Constructor and datasheet ----------------------------------------------------------------------------------

    /** The constructor's row for the main size (its translation key under the constructor's prefix). */
    public abstract String sizeRow();

    /** The main size's unit on the constructor's row: metres, or a machine's kilowatts. */
    public String sizeUnit() {
        return "m";
    }

    /** The main size as the constructor's row shows it. */
    public String sizeText(double size) {
        return size(size);
    }

    /** The constructor's row for the second size, or {@code null} where the kind has none. */
    public String lengthRow() {
        return null;
    }

    /** Whether the second size is shown in metres (a length or a height) rather than as a count. */
    public boolean lengthInMetres() {
        return true;
    }

    /** The constructor's row for the support, or {@code null} where it is fixed ({@link #maxSupport()} 0). */
    public abstract String supportRow();

    /** The constructor's middle line: what the unit holds or does. */
    public abstract Line held(EquipmentShape shape);

    /** The vessel screen's kind line's key suffix after {@code kind.<kind>} (a pump above {@link ProcessUnit#OH2_MAX} is radially split between bearings). */
    public String variant(EquipmentShape shape) {
        return "";
    }

    /** The vessel screen's lines between the kind and the overall length. */
    public abstract List<Line> datasheet(EquipmentShape shape);

    // ---- Parts ------------------------------------------------------------------------------------------------------

    /** Draws the unit's parts, solids, nozzles, decks, ladders and moving parts into {@code unit}. */
    abstract void build(ProcessUnit unit);

    // ---- Kinds ------------------------------------------------------------------------------------------------------

    private static final Map<Kind, UnitDesign> DESIGNS = new EnumMap<>(Kind.class);

    static {
        DESIGNS.put(Kind.TANK, new TankDesign());
        DESIGNS.put(Kind.SPHERE, new SphereDesign());
        DESIGNS.put(Kind.EXCHANGER, new ExchangerDesign());
        DESIGNS.put(Kind.KETTLE, new KettleDesign());
        DESIGNS.put(Kind.AIR_COOLER, new AirCoolerDesign());
        DESIGNS.put(Kind.EJECTOR, new EjectorDesign());
        DESIGNS.put(Kind.PUMP, new PumpDesign());
        DESIGNS.put(Kind.COMPRESSOR, new CompressorDesign());
        DESIGNS.put(Kind.VERTICAL_HEATER, new VerticalHeaterDesign());
        DESIGNS.put(Kind.CABIN_HEATER, new CabinHeaterDesign());
        DESIGNS.put(Kind.STIRRED_REACTOR, new StirredReactorDesign());
        DESIGNS.put(Kind.ADSORBER, new AdsorberDesign());
        DESIGNS.put(Kind.TRICKLE_BED_REACTOR, new TrickleBedReactorDesign());
        DESIGNS.put(Kind.EBULLATED_BED_REACTOR, new EbullatedBedReactorDesign());
        DESIGNS.put(Kind.CENTRIFUGAL_COMPRESSOR, new CentrifugalCompressorDesign());
        DESIGNS.put(Kind.STEAM_TURBINE, new SteamTurbineDesign());
        DESIGNS.put(Kind.CYCLONE, new CycloneDesign());
        DESIGNS.put(Kind.FCC_CONVERTER, new FccConverterDesign());
        DESIGNS.put(Kind.DELAYED_COKER, new DelayedCokerDesign());
        DESIGNS.put(Kind.FLUIDIZED_BED_BOILER, new FluidizedBedBoilerDesign());
        DESIGNS.put(Kind.FLUIDIZED_BED_GASIFIER, new FluidizedBedGasifierDesign());
        DESIGNS.put(Kind.GRATE_BOILER, new GrateBoilerDesign());
        DESIGNS.put(Kind.FIXED_BED_GASIFIER, new FixedBedGasifierDesign());
        DESIGNS.put(Kind.INDUCED_DRAFT_COOLING_TOWER,new CoolingTowerDesign(false));
        DESIGNS.put(Kind.NATURAL_DRAFT_COOLING_TOWER,new CoolingTowerDesign(true));
        DESIGNS.put(Kind.MOTOR, new MotorDesign(false));
        DESIGNS.put(Kind.GENERATOR, new MotorDesign(true));
        DESIGNS.put(Kind.GEARBOX, new GearboxDesign());
    }

    /** The design of a designed kind ({@link Kind#designed()}): a storage vessel's or a unit's. */
    public static UnitDesign of(Kind kind) {
        var design = DESIGNS.get(kind);
        if (design == null) throw new IllegalArgumentException(kind + " is not drawn from a design");
        return design;
    }

    /**
     * A constructed mover's running-loop gain, 0.10 to 0.60 over its kind's size range (its main size, and its stage or
     * throw count where it has one); 0 for any other unit. Sound only: dimensions impose no hydraulic limits.
     */
    public static float soundGain(EquipmentShape shape) {
        java.util.Objects.requireNonNull(shape);
        if(!shape.kind().mover())return 0;
        var design=of(shape.kind());double size=(shape.diameter()-design.minSize())/(design.maxSize()-design.minSize());
        int least=design.minLength(shape.diameter()),most=design.maxLength(shape.diameter());
        if(most>least)size=(size+(shape.tangentLength()-least)/(most-least))/2;
        return (float)(.10+.50*Math.clamp(size,0,1));
    }

    // ---- Formats ----------------------------------------------------------------------------------------------------

    /** One decimal. */
    public static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** A large quantity in whole units, thousands separated (30,401). */
    public static String whole(double value) {
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }

    /** A size on a 0.25 m or 0.1 m grid: one decimal where it has one, two where it has two (0.75). */
    public static String size(double value) {
        double tenths = value * 10;
        return Math.abs(tenths - Math.rint(tenths)) < 1e-6 ? number(value) : String.format(Locale.ROOT, "%.2f", value);
    }
}
