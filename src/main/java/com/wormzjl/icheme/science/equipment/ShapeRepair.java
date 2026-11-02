package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Connection;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import java.util.*;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * A saved equipment shape read onto the current rules (AGENTS.md, "Saves and data formats"; batch
 * 2026-10-26-backward-compat, plan section 5.1). Names are read through aliases; a field the shape was saved without
 * reads its default. A shape the current rules refuse is repaired a field at a time, in a fixed order: sizes clamped and
 * snapped to the kind's grid, the support into its range, side connections outside the shell dropped, the bottom outlet
 * to the front, the boot and the bank removed, and last the kind's default size. Each change is named, so the caller logs
 * it; the repaired shape has another layout stamp, so the vessel is then rebuilt by the normal refresh. A kind this build
 * no longer has is {@link Read#retired}: its structure is removed whole (owner decision D6).
 */
public final class ShapeRepair {
    /** Renamed or removed constants, by their saved name: the current name. None yet. */
    static final Map<String, String> KIND_ALIASES = Map.of(), HEADING_ALIASES = Map.of(), ORIENTATION_ALIASES = Map.of(), FORM_ALIASES = Map.of(),
            ROLE_ALIASES = Map.of();
    /** A column a repair falls back to: twenty trays, fed in the middle (the constructor's new column). */
    private static final ColumnTrays DEFAULT_TRAYS = ColumnTrays.simple(20, 10);

    private ShapeRepair() {
    }

    /**
     * A shape as saved, before any rule reads it: names as written, numbers, and the arrays a shape was saved with (null
     * when absent); a column's component design (null when absent: a column built without one); a column's turned side
     * nozzles (empty when absent: a column whose nozzles all take their sides by themselves).
     */
    public record Saved(String kind, String front, @Nullable String orientation, double diameter, double length, int support, int @Nullable [] trays,
                        int @Nullable [] connections, boolean boot, int outlet, int @Nullable [] bank, @Nullable SavedComponents components,
                        List<SavedTurn> turns, @Nullable SavedOperating operating) {
        public Saved {
            turns = List.copyOf(turns);
        }

        /** A shape saved before its column could carry an operating point. */
        public Saved(String kind, String front, @Nullable String orientation, double diameter, double length, int support, int @Nullable [] trays,
                     int @Nullable [] connections, boolean boot, int outlet, int @Nullable [] bank, @Nullable SavedComponents components,
                     List<SavedTurn> turns) {
            this(kind, front, orientation, diameter, length, support, trays, connections, boot, outlet, bank, components, turns, null);
        }

        /** A shape saved before its column could carry a component design. */
        public Saved(String kind, String front, @Nullable String orientation, double diameter, double length, int support, int @Nullable [] trays,
                     int @Nullable [] connections, boolean boot, int outlet, int @Nullable [] bank) {
            this(kind, front, orientation, diameter, length, support, trays, connections, boot, outlet, bank, null, List.of(), null);
        }

        /** A shape saved before its column could carry turned nozzles. */
        public Saved(String kind, String front, @Nullable String orientation, double diameter, double length, int support, int @Nullable [] trays,
                     int @Nullable [] connections, boolean boot, int outlet, int @Nullable [] bank, @Nullable SavedComponents components) {
            this(kind, front, orientation, diameter, length, support, trays, connections, boot, outlet, bank, components, List.of(), null);
        }
    }

    /**
     * A column's operating point as saved (batch 2026-10-28-column-operation, shape fields 4): its numbers, its ids and
     * the pumparound splits by name, as written. A missing number reads as NaN, which the point refuses.
     */
    public record SavedOperating(String packageId, String assayId, List<String> components, double[] feed, double feedTemperature, double topPressure,
                                 double stageDrop, double condenserTemperature, double refluxRatio, double reboilerDuty, double[] steamFlows,
                                 double[] steamTemperatures, double[] draws, double[] pumparoundDuties, List<String> pumparoundSplits) {
        public SavedOperating {
            components = List.copyOf(components);
            pumparoundSplits = List.copyOf(pumparoundSplits);
        }

        /** @throws IllegalArgumentException for a split this build does not have, mismatched lists or an invalid value */
        public ColumnOperatingPoint resolve() {
            if (feed.length != components.size() || steamFlows.length != steamTemperatures.length || pumparoundDuties.length != pumparoundSplits.size())
                throw new IllegalArgumentException("mismatched lists");
            var steam = new ArrayList<ColumnOperatingPoint.Steam>();
            for (int i = 0; i < steamFlows.length; i++) steam.add(new ColumnOperatingPoint.Steam(steamFlows[i], steamTemperatures[i]));
            var pas = new ArrayList<ColumnOperatingPoint.Pumparound>();
            for (int i = 0; i < pumparoundDuties.length; i++) {
                String name = pumparoundSplits.get(i);
                var split = constant(com.wormzjl.icheme.science.column.v3.V3PumparoundSpec.Split.class, name, SPLIT_ALIASES)
                        .orElseThrow(() -> new IllegalArgumentException("no pumparound split " + name));
                pas.add(new ColumnOperatingPoint.Pumparound(pumparoundDuties[i], split));
            }
            return new ColumnOperatingPoint(packageId, assayId, components, Arrays.stream(feed).boxed().toList(), feedTemperature, topPressure, stageDrop,
                    condenserTemperature, refluxRatio, reboilerDuty, steam, Arrays.stream(draws).boxed().toList(), pas);
        }
    }

    /** Renamed or removed pumparound splits, by their saved name: the current name. None yet. */
    static final Map<String, String> SPLIT_ALIASES = Map.of();

    /** A turned side nozzle as saved (finding F3): its role by name, its number and the side it faces. */
    public record SavedTurn(String role, int index, int side) {
        /** @throws IllegalArgumentException naming a role this build does not have or does not turn, a number or a side out of range */
        public ColumnTrays.Turn resolve() {
            var named = constant(EquipmentShape.Role.class, role, ROLE_ALIASES).orElseThrow(() -> new IllegalArgumentException("no role " + role));
            return new ColumnTrays.Turn(named, index, side);
        }
    }

    /** A column's component design as saved: its kinds by name and its two flags (batch 2026-10-26-multi-machine-framework). */
    public record SavedComponents(String condenser, String refluxPump, List<String> pumparoundPumps, boolean ejector, boolean kettle) {
        public SavedComponents {
            pumparoundPumps = List.copyOf(pumparoundPumps);
        }

        /** @throws IllegalArgumentException naming a kind this build does not have, or too many pumps */
        public ColumnComponents resolve() {
            return ColumnComponents.named(condenser, refluxPump, pumparoundPumps, ejector, kettle);
        }
    }

    /** The shape read, or the name of a {@link #retired} kind; and each change a repair made, as {@code field from -> to}. */
    public record Read(@Nullable EquipmentShape shape, @Nullable String retired, List<String> repairs) {
        public Read {
            repairs = List.copyOf(repairs);
            if ((shape == null) == (retired == null)) throw new IllegalArgumentException("A read gives a shape or a retired kind");
        }
    }

    /** The kind a saved name names, through its aliases, or empty for one this build no longer has. */
    public static Optional<Kind> kind(String saved) {
        return constant(Kind.class, saved, KIND_ALIASES);
    }

    /** The form a constructor's saved name names, through its aliases, or empty. */
    public static Optional<VesselPlacement.Form> form(String saved) {
        return constant(VesselPlacement.Form.class, saved, FORM_ALIASES);
    }

    private static <E extends Enum<E>> Optional<E> constant(Class<E> type, @Nullable String saved, Map<String, String> aliases) {
        if (saved == null) return Optional.empty();
        String name = saved.toUpperCase(Locale.ROOT);
        name = aliases.getOrDefault(name, name);
        try {
            return Optional.of(Enum.valueOf(type, name));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /**
     * The turns of {@code saved} this build reads: a turn naming a role it does not have or a number or side out of range
     * is dropped, as is a second turn of one nozzle and any turn past {@link ColumnTrays#MAX_TURNS}; that nozzle then
     * takes its side by itself. Each drop goes to {@code repairs}.
     */
    public static List<ColumnTrays.Turn> turns(List<SavedTurn> saved, java.util.function.Consumer<String> repairs) {
        var turns = new ArrayList<ColumnTrays.Turn>();
        var seen = new HashSet<String>();
        for (var turn : saved) {
            try {
                var read = turn.resolve();
                if (!seen.add(read.role() + "#" + read.index())) throw new IllegalArgumentException("turned twice");
                if (turns.size() == ColumnTrays.MAX_TURNS) throw new IllegalArgumentException("too many turns");
                turns.add(read);
            } catch (IllegalArgumentException invalid) {
                repairs.accept("turn " + turn.role() + " " + turn.index() + " (" + invalid.getMessage() + ") -> automatic");
            }
        }
        return turns;
    }

    /** The fields one repair step works on. */
    private record Fields(Kind kind, Heading front, Orientation orientation, double diameter, double length, int support, @Nullable ColumnTrays trays,
                          List<Connection> connections, boolean boot, int outlet, ProcessUnit.Bank bank) {
        EquipmentShape build() {
            if (kind == Kind.COLUMN) return EquipmentShape.column(diameter, trays, front, support);
            if (kind.designed()) return EquipmentShape.designed(kind, diameter, length, front, support, bank, outlet);
            return EquipmentShape.drum(diameter, length, orientation, front, support, connections, boot, outlet);
        }
    }

    /** Reads {@code saved} (see the class comment); never throws for any saved value. */
    public static Read read(Saved saved) {
        var kind = kind(saved.kind()).orElse(null);
        if (kind == null) return new Read(null, String.valueOf(saved.kind()), List.of());
        var repairs = new ArrayList<String>();
        var front = constant(Heading.class, saved.front(), HEADING_ALIASES).orElse(null);
        if (front == null) {
            front = Heading.NORTH;
            repairs.add("front " + saved.front() + " -> north");
        }
        Orientation orientation;
        if (kind == Kind.DRUM) {
            orientation = constant(Orientation.class, saved.orientation(), ORIENTATION_ALIASES).orElse(null);
            if (orientation == null) {
                orientation = Orientation.VERTICAL;
                repairs.add("orientation " + saved.orientation() + " -> vertical");
            }
        } else orientation = kind == Kind.COLUMN ? Orientation.VERTICAL : ProcessUnit.orientation(kind);
        ColumnTrays trays = null;
        if (kind == Kind.COLUMN) {
            try {
                trays = ColumnTrays.decode(Objects.requireNonNull(saved.trays(), "no trays"));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                trays = DEFAULT_TRAYS;
                repairs.add("trays (" + invalid.getMessage() + ") -> " + DEFAULT_TRAYS.trays() + " trays");
            }
            if (saved.components() != null)
                try {
                    trays = trays.withComponents(saved.components().resolve());
                } catch (IllegalArgumentException invalid) {
                    repairs.add("components (" + invalid.getMessage() + ") -> none");
                }
            if (!saved.turns().isEmpty()) trays = trays.withTurns(turns(saved.turns(), repairs::add));
            if (saved.operating() != null)
                try {
                    trays = trays.withOperating(saved.operating().resolve());
                } catch (IllegalArgumentException invalid) {
                    repairs.add("operating point (" + invalid.getMessage() + ") -> none");
                }
        }
        List<Connection> connections = List.of();
        if (kind == Kind.DRUM) {
            try {
                // A drum saved before it listed its side connections had the standard inlet a new drum has.
                connections = saved.connections() != null ? Connection.decode(saved.connections())
                        : EquipmentShape.defaultConnections(saved.diameter(), saved.length(), orientation, saved.support(), saved.boot());
            } catch (IllegalArgumentException invalid) {
                repairs.add("connections (" + invalid.getMessage() + ") -> none");
            }
        }
        var bank = ProcessUnit.Bank.ONE;
        if (kind == Kind.AIR_COOLER && saved.bank() != null) {
            try {
                bank = ProcessUnit.Bank.decode(saved.bank());
            } catch (IllegalArgumentException invalid) {
                repairs.add("bank (" + invalid.getMessage() + ") -> one bay");
            }
        }
        var fields = new Fields(kind, front, orientation, saved.diameter(), saved.length(), saved.support(), trays, connections, saved.boot(), saved.outlet(), bank);
        for (var step : LADDER) {
            try {
                return new Read(fields.build(), null, repairs);
            } catch (IllegalArgumentException | NullPointerException invalid) {
                var next = step.apply(fields);
                describe(fields, next, repairs);
                fields = next;
            }
        }
        // The kind's default shape is valid by construction; a refusal here is a defect of the rules, not of the save.
        return new Read(fields.build(), null, repairs);
    }

    /** The repairs, in order; the last gives the kind's default size. */
    private static final List<UnaryOperator<Fields>> LADDER = List.of(ShapeRepair::sizes, ShapeRepair::support, ShapeRepair::connections,
            f -> new Fields(f.kind, f.front, f.orientation, f.diameter, f.length, f.support, f.trays, f.connections, f.boot, 0, f.bank),
            f -> new Fields(f.kind, f.front, f.orientation, f.diameter, f.length, f.support, f.trays, f.connections, false, f.outlet, f.bank),
            f -> new Fields(f.kind, f.front, f.orientation, f.diameter, f.length, f.support, f.trays, f.connections, f.boot, f.outlet, ProcessUnit.Bank.ONE),
            ShapeRepair::defaults);

    /** The diameter on the kind's grid and inside its range, the length a whole number inside its range. */
    private static Fields sizes(Fields f) {
        double diameter, length;
        if (f.kind.designed()) {
            diameter = Double.isFinite(f.diameter) ? ProcessUnit.snap(f.kind, f.diameter) : ProcessUnit.minDiameter(f.kind);
            length = Double.isFinite(f.length) ? clamp(Math.rint(f.length), ProcessUnit.minLength(f.kind, diameter), ProcessUnit.maxLength(f.kind, diameter))
                    : ProcessUnit.defaultLength(f.kind, diameter);
        } else {
            double maximum = f.kind == Kind.COLUMN ? EquipmentShape.MAX_COLUMN_DIAMETER : EquipmentShape.MAX_DIAMETER;
            diameter = Double.isFinite(f.diameter) ? clamp(Math.rint(f.diameter * 2) / 2, EquipmentShape.MIN_DIAMETER, maximum) : 2;
            length = Double.isFinite(f.length) ? clamp(Math.rint(f.length), 1, EquipmentShape.MAX_TANGENT_LENGTH) : 4;
        }
        return new Fields(f.kind, f.front, f.orientation, diameter, length, f.support, f.trays, f.connections, f.boot, f.outlet, f.bank);
    }

    /** The support inside the range the kind, its orientation and its sizes allow. */
    private static Fields support(Fields f) {
        int support;
        if (f.kind.designed())
            support = (int) clamp(f.support, ProcessUnit.minSupport(f.kind, f.diameter, f.length), ProcessUnit.maxSupport(f.kind));
        else if (f.kind == Kind.COLUMN || !f.orientation.horizontal() && f.support != 0)
            support = (int) clamp(f.support, EquipmentShape.minimumSkirt(f.diameter), EquipmentShape.MAX_SUPPORT);
        else support = f.orientation.horizontal() ? (int) clamp(f.support, 1, EquipmentShape.MAX_SUPPORT) : 0;
        if (f.boot && f.kind == Kind.DRUM && EquipmentShape.bootFor(f.diameter, f.length) != null)
            support = Math.max(support, Math.min(EquipmentShape.MAX_SUPPORT, EquipmentShape.minimumBootSupport(f.diameter, f.length)));
        return new Fields(f.kind, f.front, f.orientation, f.diameter, f.length, support, f.trays, f.connections, f.boot, f.outlet, f.bank);
    }

    /** The side connections that still lie on the shell, at most {@link EquipmentShape#MAX_CONNECTIONS}. */
    private static Fields connections(Fields f) {
        if (f.kind != Kind.DRUM) return f;
        int[] range = EquipmentShape.stations(f.diameter, f.length, f.orientation, f.support);
        int across = EquipmentShape.maximumOffset(f.diameter);
        var kept = f.connections.stream().filter(c -> c.station() >= range[0] && c.station() <= range[1] && Math.abs(c.offset()) <= across)
                .limit(EquipmentShape.MAX_CONNECTIONS).toList();
        return new Fields(f.kind, f.front, f.orientation, f.diameter, f.length, f.support, f.trays, kept, f.boot, f.outlet, f.bank);
    }

    /** The kind's default size, as a new constructor proposes it, with the saved front. */
    private static Fields defaults(Fields f) {
        if (f.kind.designed()) {
            double diameter = ProcessUnit.minDiameter(f.kind);int length = ProcessUnit.defaultLength(f.kind, diameter);
            return new Fields(f.kind, f.front, f.orientation, diameter, length, ProcessUnit.defaultSupport(f.kind, diameter, length), null, List.of(), false, 0, ProcessUnit.Bank.ONE);
        }
        if (f.kind == Kind.COLUMN) return new Fields(f.kind, f.front, f.orientation, 2, 1, EquipmentShape.minimumSkirt(2), DEFAULT_TRAYS, List.of(), false, 0, ProcessUnit.Bank.ONE);
        int support = EquipmentShape.defaultSupport(2, f.orientation);
        return new Fields(f.kind, f.front, f.orientation, 2, 4, support, null, EquipmentShape.defaultConnections(2, 4, f.orientation, support, false), false, 0, ProcessUnit.Bank.ONE);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    /** Names each field a step changed. */
    private static void describe(Fields from, Fields to, List<String> repairs) {
        if (from.diameter != to.diameter) repairs.add("diameter " + from.diameter + " -> " + to.diameter);
        if (from.length != to.length) repairs.add("length " + from.length + " -> " + to.length);
        if (from.support != to.support) repairs.add("support " + from.support + " -> " + to.support);
        if (!Objects.equals(from.trays, to.trays)) repairs.add("trays -> " + (to.trays == null ? "none" : to.trays.trays() + " trays"));
        if (!from.connections.equals(to.connections)) repairs.add("connections " + from.connections.size() + " -> " + to.connections.size());
        if (from.outlet != to.outlet) repairs.add("outlet " + from.outlet + " -> " + to.outlet);
        if (from.boot != to.boot) repairs.add("boot -> none");
        if (!from.bank.equals(to.bank)) repairs.add("bank -> one bay");
    }
}
