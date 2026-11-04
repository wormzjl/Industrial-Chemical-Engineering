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

    // ---- Design point (shape fields 8, batch 2026-11-03-rotating-machinery) ------------------------------------------

    /**
     * Whether the kind's shape carries a design point ({@link MachineDuty}): a pump, a centrifugal compressor, a steam
     * turbine, a motor, a generator, a gearbox. Its main and second sizes then follow from the design point.
     */
    public boolean hasDuty() {
        return false;
    }

    /**
     * The design point of a unit of these sizes where none was given: a shape saved before shape fields 8 (its
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

    /** A running-loop gain at full speed ({@link #soundGain(EquipmentShape, double)}). */
    public static float soundGain(EquipmentShape shape) {
        return soundGain(shape, 1);
    }

    /**
     * A running loop's gain, one law for a mover's casing and for a driver (decision D26, batch
     * 2026-11-03-rotating-machinery): 0.10 to 0.60 over the part's size range, times the speed share to the power
     * {@link #SPEED_EXPONENT}; 0 for any other unit. A casing's size is its kind's size range (its main size, and its
     * stage or throw count where it has one); a driver's is its rated power on a log scale over its kind's powers. The
     * gain stays at or below 1: in Minecraft a volume above 1 widens the range instead of raising the loudness. Sound
     * only: dimensions impose no hydraulic limits.
     */
    public static float soundGain(EquipmentShape shape, double speed) {
        java.util.Objects.requireNonNull(shape);
        double size;
        if(shape.kind().mover()){
            var design=of(shape.kind());size=(shape.diameter()-design.minSize())/(design.maxSize()-design.minSize());
            int least=design.minLength(shape.diameter()),most=design.maxLength(shape.diameter());
            if(most>least)size=(size+(shape.tangentLength()-least)/(most-least))/2;
        }else if(driver(shape.kind())){
            boolean turbine=shape.kind()==EquipmentShape.Kind.STEAM_TURBINE;
            double least=turbine?SteamTurbineDesign.MIN_POWER:MotorDesign.POWERS.getFirst(),most=turbine?SteamTurbineDesign.MAX_POWER:MotorDesign.POWERS.getLast();
            size=com.wormzjl.icheme.science.math.DeterministicMath.log(shape.duty().power()/least)/com.wormzjl.icheme.science.math.DeterministicMath.log(most/least);
        }else return 0;
        return gain(size,speed);
    }

    /** The law of {@link #soundGain(EquipmentShape, double)} at a size share {@code size} (0 to 1) and a speed share. */
    private static float gain(double size,double speed) {
        return (float)((.10+.50*Math.clamp(size,0,1))*com.wormzjl.icheme.science.math.DeterministicMath.pow(Math.clamp(speed,0,1),SPEED_EXPONENT));
    }

    /**
     * The smallest pump casing's gain at a speed share (size share 0: 0.10 at full speed), which an in-line pump's
     * cavitation crackle and gas churn take (W10c, owner 2026-11-04): an in-line pump has no casing shape for the law.
     */
    public static float leastGain(double speed) {
        return gain(0,speed);
    }

    /**
     * A rotating machine's sound power rises about as its speed to the fifth (the fan law, 50 log N dB), its sound
     * pressure, which a loop's gain scales, as the root of that: half speed is 0.18 of the full-speed gain.
     */
    public static final double SPEED_EXPONENT = 2.5;

    /**
     * The shaft speeds, rpm, the loops were authored at (tools/machine-sounds/README.md): {@code pump_running} at 2950 rpm
     * (its 300 Hz vane pass is 2950 rpm times 6 vanes), which a pump's crackle and churn layers share, and
     * {@code motor_running} (motor and generator) at 1500 rpm (its 250 Hz fan blade pass is 25 Hz times 10 blades). The
     * compressor and turbine loops state none: they sound at their machine's rated speed.
     */
    public static final double PUMP_LOOP_RPM = 2950, MOTOR_LOOP_RPM = 1500;

    /**
     * A running loop's pitch at a shaft speed {@code rpm} for a loop authored at {@code loopRpm} (decision D26; owner
     * 2026-11-04: "Rotation machine sound should also scale with their rpm"): a machine's tones (blade pass, vane pass,
     * fan, slots) are its shaft speed times a fixed count, so the pitch is {@code n / n_loop}, between Minecraft's 0.5 and
     * 2. A 1475 rpm pump sounds at 0.5 of the 2950 rpm loop, a 4425 rpm one at 1.5; overspeed rises to 2.
     */
    public static float soundPitch(double rpm, double loopRpm) {
        if (!(loopRpm > 0) || !(rpm >= 0)) return 1;
        return (float) Math.clamp(rpm / loopRpm, .5, 2);
    }

    /**
     * The rated shaft speed, rpm, of a sounding machine's shape from its design data: a pump casing's or a motor's or
     * generator's from its poles ({@link com.wormzjl.icheme.science.machine.Motor#ratedSpeed}; a pump without poles is
     * 2-pole); 0 where its loop states no speed (a compressor, a turbine).
     */
    public static double ratedRpm(EquipmentShape shape) {
        var kind = shape.kind();
        boolean pump = kind == Kind.PUMP, motor = kind == Kind.MOTOR || kind == Kind.GENERATOR;
        if (!pump && !motor) return 0;
        int poles = shape.duty() == null ? 0 : shape.duty().poles();
        if (poles == 0) poles = pump ? 2 : 4;
        return com.wormzjl.icheme.science.machine.Motor.ratedSpeed(poles);
    }

    /**
     * A machine's loop pitch at speed share {@code share} of its rated speed: {@link #soundPitch(double, double)} of its
     * rpm ({@code share} times {@link #ratedRpm}) over its loop's ({@link #PUMP_LOOP_RPM}, {@link #MOTOR_LOOP_RPM}); for a
     * loop that states no speed, the share itself, between 0.5 and 2. A pump's crackle and churn take its pump's pitch.
     */
    public static float soundPitch(EquipmentShape shape, double share) {
        double rated = ratedRpm(shape);
        if (!(rated > 0)) return soundPitch(share, 1);
        return soundPitch(share * rated, shape.kind() == Kind.PUMP ? PUMP_LOOP_RPM : MOTOR_LOOP_RPM);
    }

    /** Whether a kind sounds as a driver, its own loop at its own cells: a motor, its mirror the generator, or a steam turbine. */
    public static boolean driver(EquipmentShape.Kind kind) {
        return kind == EquipmentShape.Kind.MOTOR || kind == EquipmentShape.Kind.GENERATOR || kind == EquipmentShape.Kind.STEAM_TURBINE;
    }

    /** Whether a kind is a member of a shaft train (batch 2026-11-03-rotating-machinery, W3): a driver, a gearbox, or a
     * pump or compressor casing. */
    public static boolean shafted(EquipmentShape.Kind kind) {
        return driver(kind) || kind == EquipmentShape.Kind.GEARBOX || kind.mover();
    }

    /**
     * A shaft member's design speed, rpm (W3): what its shaft turns at its rated point. A pump at its poles' speed with slip
     * (2950 or 1475), a motor too; a generator at its synchronous speed; a centrifugal compressor at its impeller's tip speed;
     * a reciprocating frame at its stroke's; a turbine at its last stage's tip ({@code SteamTurbineTrain}). 0 for a gearbox,
     * whose speeds are its neighbours'.
     */
    public static double designSpeed(EquipmentShape shape) {
        var duty = shape.duty();
        return switch (shape.kind()) {
            case PUMP, MOTOR -> com.wormzjl.icheme.science.machine.Motor.ratedSpeed(duty.poles() == 0 ? 2 : duty.poles());
            case GENERATOR -> duty.poles() == 0 ? 3000 : duty.speed();
            case CENTRIFUGAL_COMPRESSOR -> CentrifugalCompressorDesign.speed(shape.diameter());
            case COMPRESSOR -> ProcessUnit.compressorSpeed(shape.diameter());
            case STEAM_TURBINE -> SteamTurbineTrain.speed(shape.diameter());
            default -> 0;
        };
    }

    /** A driver's, generator's or gearbox's rated shaft power, W (W3); 0 for a casing, whose power its law gives. */
    public static double ratedPower(EquipmentShape shape) {
        return switch (shape.kind()) {
            case MOTOR, GENERATOR, GEARBOX, STEAM_TURBINE -> 1000 * shape.duty().power();
            default -> 0;
        };
    }

    /** A pump casing's rated flow, m³/s, and head, m (W3): its duty, or without one its discharge's flow at {@code PUMP_HEAD}. */
    public static double[] pumpRating(EquipmentShape shape) {
        var duty = shape.duty();
        return new double[]{(duty.flow() > 0 ? duty.flow() : ProcessUnit.pumpFlow(shape.diameter())) / 3600, duty.head() > 0 ? duty.head() : ProcessUnit.PUMP_HEAD};
    }

    /** A reciprocating frame's displacement at its design speed, m³/s (W3). */
    public static double displacement(EquipmentShape shape) {
        return ProcessUnit.displacement(shape.diameter(), (int) shape.tangentLength()) / 3600;
    }

    /** A centrifugal compressor's rated polytropic head, J/kg, and inlet flow, m³/s, from its design point (W3). */
    public static double[] compressorRating(EquipmentShape shape) {
        var duty = shape.duty();
        double flow = duty.flow() > 0 ? duty.flow() : CentrifugalCompressorDesign.flow(shape.diameter());
        return new double[]{flow / 3600, 1000 * (duty.polytropicHead() > 0 ? duty.polytropicHead() : 150)};
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
