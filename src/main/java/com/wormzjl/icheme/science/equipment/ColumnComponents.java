package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The component design of a distillation column (batch 2026-10-26-multi-machine-framework, plan section 4): what the
 * player chose on the column calculator's Components page, and the two components the design's numbers require. The
 * flags are resolved when the design is saved, so a later change of the rules never changes a built column's
 * requirements. The receiver (a lying drum with a boot), the reflux line (its tee and valve, also at a zero reflux ratio,
 * owner answer Q2) and the control valves are always required and carry no field.
 *
 * @param condenser       the condenser kind
 * @param refluxPump      the reflux pump kind
 * @param pumparoundPumps one pump kind per designed pumparound, in the design's order
 * @param ejector         a vacuum top: the top pressure is below ambient, and {@code vacuum} names what makes it
 * @param kettle          a kettle reboiler: the design has a reboiler duty
 * @param units           how many identical units stand in parallel on each heat-transfer member step (batch
 *                        2026-11-02-process-simulator, S3b); one cooler count per pumparound
 * @param vacuum          what holds a vacuum top (batch 2026-11-10-vacuum-pump, shape fields 12): a steam ejector before the
 *                        condenser, or a vacuum pump on the receiver's vapour outlet; a steam ejector where saved before it
 */
public record ColumnComponents(Condenser condenser, Pump refluxPump, List<Pump> pumparoundPumps, boolean ejector, boolean kettle, Units units, Exchangers exchangers, Vacuum vacuum) {
    public ColumnComponents(Condenser condenser,Pump refluxPump,List<Pump> pumparoundPumps,boolean ejector,boolean kettle,Units units) {
        this(condenser,refluxPump,pumparoundPumps,ejector,kettle,units,Exchangers.STANDARD);
    }
    public ColumnComponents(Condenser condenser,Pump refluxPump,List<Pump> pumparoundPumps,boolean ejector,boolean kettle,Units units,Exchangers exchangers) {
        this(condenser,refluxPump,pumparoundPumps,ejector,kettle,units,exchangers,Vacuum.EJECTOR);
    }
    public ColumnComponents withExchangers(Exchangers value){return new ColumnComponents(condenser,refluxPump,pumparoundPumps,ejector,kettle,units,value,vacuum);}
    public ColumnComponents withVacuum(Vacuum value){return new ColumnComponents(condenser,refluxPump,pumparoundPumps,ejector,kettle,units,exchangers,value);}

    /**
     * What holds a vacuum top (batch 2026-11-10-vacuum-pump, owner: the pump "should be placed on the drum vapor outlet"):
     * a steam ejector on the column's vapour outlet, before the condenser, whose steam the condenser takes; or a liquid ring
     * or a dry screw vacuum pump on the receiver's vapour outlet, after the condenser, which takes only the vent. Saved by name.
     */
    public enum Vacuum {
        EJECTOR, LIQUID_RING, DRY_SCREW;

        /** A vacuum pump on the receiver, not an ejector before the condenser. */
        public boolean pump() {
            return this != EJECTOR;
        }
    }

    /** {@code vacuum} named only where it is not the ejector, so a design saved before it prints as it did. */
    @Override public String toString() {
        return "ColumnComponents[condenser=" + condenser + ", refluxPump=" + refluxPump + ", pumparoundPumps=" + pumparoundPumps + ", ejector=" + ejector
                + ", kettle=" + kettle + ", units=" + units + ", exchangers=" + exchangers + (vacuum == Vacuum.EJECTOR ? "" : ", vacuum=" + vacuum) + "]";
    }

    /** Whether the top's vacuum comes from a vacuum pump on the receiver's vapour outlet. */
    public boolean vacuumPump() {
        return ejector && vacuum.pump();
    }

    /** Configurations of the exchanger members, fitted by the same pumparound order as their counts. */
    public record Exchangers(ExchangerType condenser,ExchangerType trim,List<ExchangerType> coolers) {
        public static final Exchangers STANDARD=new Exchangers(ExchangerType.STANDARD,ExchangerType.STANDARD,List.of());
        public Exchangers {Objects.requireNonNull(condenser);Objects.requireNonNull(trim);coolers=List.copyOf(coolers);if(coolers.size()>ColumnTrays.MAX_PUMPAROUNDS)throw new IllegalArgumentException("Too many cooler configurations");}
        public ExchangerType cooler(int index){return index<coolers.size()?coolers.get(index):ExchangerType.STANDARD;}
        public Exchangers fit(int count){var next=new ArrayList<ExchangerType>();for(int i=0;i<count;i++)next.add(cooler(i));return new Exchangers(condenser,trim,next);}
        public Exchangers withCondenser(ExchangerType type){return new Exchangers(type,trim,coolers);}
        public Exchangers withTrim(ExchangerType type){return new Exchangers(condenser,type,coolers);}
        public Exchangers withCooler(int index,ExchangerType type){var next=new ArrayList<>(fit(Math.max(index+1,coolers.size())).coolers);next.set(index,type);return new Exchangers(condenser,trim,next);}
    }
    /**
     * An air cooler, or an exchanger taking the vapour on its shell side (decision D15: its condensate drains only there), or
     * an air cooler that condenses the overhead to the split ({@link HeatSides#airSplit}) and a water exchanger, the trim,
     * that cools it from there to the receiver (batch 2026-11-02-process-simulator, {@code COOLER_MODEL_PLAN.md} section 6).
     */
    public enum Condenser {
        AIR_COOLER, EXCHANGER, AIR_THEN_WATER;

        /** Whether water takes some of the duty, so the condenser has a water side: an exchanger's whole, a trim's share. */
        public boolean waterCooled() {
            return this != AIR_COOLER;
        }
    }

    /** A standard pump on its base, or an In-line Pump fitted on a pipe. */
    public enum Pump { STANDARD, IN_LINE }

    /**
     * The most identical units one member step takes in parallel: the sizing stage walks an exchanger's shells to 6
     * ({@code SIZING_PLAN.md} section 3.5), and an air-cooled condenser of the largest overhead the world's column
     * makes needs a few banks of 6 × 4 bays. Six branches also bound the manifolds an assembly reads.
     */
    public static final int MAX_UNITS = 6;

    /**
     * How many identical units stand in parallel, between a split and a merge, on each member step that passes heat (batch
     * 2026-11-02-process-simulator, S3b): the condenser (air-cooler banks or exchanger shells), the kettle, and each
     * pumparound's cooler. One is a single unit, the behaviour before the count existed. The pumps and the receiver take
     * no count: one pump of the world's grid holds any member flow, and parallel drums would each need their own level.
     *
     * @param condenser the condenser's units
     * @param kettle    the kettle's units, read only where the design has one
     * @param coolers   each pumparound cooler's units, in the design's order
     * @param trim      the water trim's shells, read only where the condenser is {@link Condenser#AIR_THEN_WATER} (the
     *                  condenser's count is then its air cooler's banks)
     */
    public record Units(int condenser, int kettle, List<Integer> coolers, int trim) {
        /** One unit on every step. */
        public static final Units ONE = new Units(1, 1, List.of());

        public Units {
            coolers = List.copyOf(coolers);
            check(condenser, "condenser units");
            check(kettle, "kettle units");
            check(trim, "trim units");
            if (coolers.size() > ColumnTrays.MAX_PUMPAROUNDS) throw new IllegalArgumentException("A column has at most " + ColumnTrays.MAX_PUMPAROUNDS + " pumparound coolers");
            for (int i = 0; i < coolers.size(); i++) check(coolers.get(i), "pumparound cooler " + (i + 1) + " units");
        }

        /** These counts with one trim shell: the counts before the trim existed. */
        public Units(int condenser, int kettle, List<Integer> coolers) {
            this(condenser, kettle, coolers, 1);
        }

        private static void check(int count, String field) {
            if (count < 1 || count > MAX_UNITS) throw new IllegalArgumentException(field + ": " + count + " is not 1 to " + MAX_UNITS);
        }

        /** These counts for {@code pumparounds} pumparounds: the coolers' kept in order, a further one 1. */
        public Units fit(int pumparounds) {
            var next = new ArrayList<Integer>();
            for (int i = 0; i < pumparounds; i++) next.add(i < coolers.size() ? coolers.get(i) : 1);
            return new Units(condenser, kettle, next, trim);
        }

        /** Pumparound {@code index}'s cooler count, 1 past the list. */
        public int cooler(int index) {
            return index < coolers.size() ? coolers.get(index) : 1;
        }

        public Units withCondenser(int next) {
            return new Units(next, kettle, coolers, trim);
        }

        public Units withKettle(int next) {
            return new Units(condenser, next, coolers, trim);
        }

        public Units withTrim(int next) {
            return new Units(condenser, kettle, coolers, next);
        }

        /** These counts with pumparound {@code index}'s cooler at {@code next} (the list fitted to reach it). */
        public Units withCooler(int index, int next) {
            var list = new ArrayList<>(fit(Math.max(coolers.size(), index + 1)).coolers());
            list.set(index, next);
            return new Units(condenser, kettle, list, trim);
        }
    }

    public ColumnComponents {
        Objects.requireNonNull(condenser, "condenser");
        Objects.requireNonNull(refluxPump, "refluxPump");
        pumparoundPumps = List.copyOf(pumparoundPumps);
        if (pumparoundPumps.size() > ColumnTrays.MAX_PUMPAROUNDS)
            throw new IllegalArgumentException("A column has at most " + ColumnTrays.MAX_PUMPAROUNDS + " pumparound pumps, not " + pumparoundPumps.size());
        Objects.requireNonNull(units, "units");
        if (units.coolers().size() > pumparoundPumps.size()) throw new IllegalArgumentException("More cooler counts than pumparounds");
        // A design saved before the counts existed reads one unit on every step.
        units = units.fit(pumparoundPumps.size());
        exchangers=Objects.requireNonNull(exchangers).fit(pumparoundPumps.size());
        Objects.requireNonNull(vacuum, "vacuum");
    }

    /** A design of one unit on every member step: the design before the counts existed. */
    public ColumnComponents(Condenser condenser, Pump refluxPump, List<Pump> pumparoundPumps, boolean ejector, boolean kettle) {
        this(condenser, refluxPump, pumparoundPumps, ejector, kettle, Units.ONE);
    }

    /**
     * The player's choices: the condenser, the reflux pump, one pump per designed pumparound, and how many units each
     * heat-transfer step takes.
     */
    public record Choices(Condenser condenser, Pump refluxPump, List<Pump> pumparoundPumps, Units units,Exchangers exchangers,Vacuum vacuum) {
        public Choices(Condenser condenser,Pump refluxPump,List<Pump> pumparoundPumps,Units units){this(condenser,refluxPump,pumparoundPumps,units,Exchangers.STANDARD);}
        public Choices(Condenser condenser,Pump refluxPump,List<Pump> pumparoundPumps,Units units,Exchangers exchangers){this(condenser,refluxPump,pumparoundPumps,units,exchangers,Vacuum.EJECTOR);}
        /** What a calculator without a saved design proposes: an air cooler and standard pumps, one unit each. */
        public static final Choices DEFAULT = new Choices(Condenser.AIR_COOLER, Pump.STANDARD, List.of());

        public Choices {
            Objects.requireNonNull(exchangers);
            Objects.requireNonNull(vacuum, "vacuum");
            Objects.requireNonNull(condenser, "condenser");
            Objects.requireNonNull(refluxPump, "refluxPump");
            pumparoundPumps = List.copyOf(pumparoundPumps);
            if (pumparoundPumps.size() > ColumnTrays.MAX_PUMPAROUNDS) throw new IllegalArgumentException("Too many pumparound pumps");
            Objects.requireNonNull(units, "units");
        }

        public Choices(Condenser condenser, Pump refluxPump, List<Pump> pumparoundPumps) {
            this(condenser, refluxPump, pumparoundPumps, Units.ONE);
        }

        /** These choices for {@code pumparounds} pumparounds: the chosen pumps and counts kept in order, a further one standard and single. */
        public Choices fit(int pumparounds) {
            var pumps = new ArrayList<Pump>();
            for (int i = 0; i < pumparounds; i++) pumps.add(i < pumparoundPumps.size() ? pumparoundPumps.get(i) : Pump.STANDARD);
            return new Choices(condenser, refluxPump, pumps, units.fit(pumparounds),exchangers.fit(pumparounds),vacuum);
        }

        public Choices withCondenser(Condenser next) {
            return new Choices(next, refluxPump, pumparoundPumps, units,exchangers,vacuum);
        }

        public Choices withRefluxPump(Pump next) {
            return new Choices(condenser, next, pumparoundPumps, units,exchangers,vacuum);
        }

        public Choices withUnits(Units next) {
            return new Choices(condenser, refluxPump, pumparoundPumps, next,exchangers,vacuum);
        }
        public Choices withExchangers(Exchangers next) {
            return new Choices(condenser,refluxPump,pumparoundPumps,units,next,vacuum);
        }
        public Choices withVacuum(Vacuum next) {
            return new Choices(condenser,refluxPump,pumparoundPumps,units,exchangers,next);
        }

        /** These choices with pumparound {@code index}'s pump replaced (the list fitted to reach it). */
        public Choices withPumparoundPump(int index, Pump next) {
            var pumps = new ArrayList<>(fit(Math.max(pumparoundPumps.size(), index + 1)).pumparoundPumps());
            pumps.set(index, next);
            return new Choices(condenser, refluxPump, pumps, units,exchangers,vacuum);
        }
    }

    /** The choices this design was saved with: what the Components page shows again. */
    public Choices choices() {
        return new Choices(condenser, refluxPump, pumparoundPumps, units,exchangers,vacuum);
    }

    /**
     * A top pressure below ambient ({@link PhaseLayout#AMBIENT_PRESSURE}) needs a steam ejector before the condenser: it
     * compresses the overhead to about the drum's pressure, where the stripping steam can condense (decision D12).
     */
    public static boolean ejectorRequired(double topPressurePascal) {
        return topPressurePascal < PhaseLayout.AMBIENT_PRESSURE;
    }

    /** A reboiler duty above zero needs a kettle reboiler; without one, sump steam strips the bottom. */
    public static boolean kettleRequired(double reboilerDutyWatts) {
        return reboilerDutyWatts > 0;
    }

    /** The design's components: the choices fitted to {@code pumparounds} pumparounds, the flags from the design's numbers. */
    public static ColumnComponents resolve(Choices choices, double topPressurePascal, double reboilerDutyWatts, int pumparounds) {
        var fitted = choices.fit(pumparounds);
        return new ColumnComponents(fitted.condenser(), fitted.refluxPump(), fitted.pumparoundPumps(),
                ejectorRequired(topPressurePascal), kettleRequired(reboilerDutyWatts), fitted.units(),fitted.exchangers(),fitted.vacuum());
    }

    /**
     * A design read back from saved names (enums are saved by name, AGENTS.md): each name must be one of its enum's.
     *
     * @throws IllegalArgumentException naming the field whose name is unknown
     */
    public static ColumnComponents named(String condenser, String refluxPump, List<String> pumparoundPumps, boolean ejector, boolean kettle) {
        return named(condenser, refluxPump, pumparoundPumps, ejector, kettle, Units.ONE);
    }

    /**
     * A design read back from saved names and counts.
     *
     * @throws IllegalArgumentException naming the field whose name or count is invalid
     */
    public static ColumnComponents named(String condenser, String refluxPump, List<String> pumparoundPumps, boolean ejector, boolean kettle, Units units) {
        var pumps = new ArrayList<Pump>();
        for (int i = 0; i < pumparoundPumps.size(); i++) pumps.add(constant(Pump.class, pumparoundPumps.get(i), "pumparound pump " + (i + 1)));
        return new ColumnComponents(constant(Condenser.class, condenser, "condenser"), constant(Pump.class, refluxPump, "reflux pump"), pumps, ejector, kettle, units);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String field) {
        try {
            return Enum.valueOf(type, Objects.requireNonNull(name, field).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(field + ": unknown kind " + name);
        }
    }
}
