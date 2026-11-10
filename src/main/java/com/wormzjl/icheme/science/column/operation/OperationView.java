package com.wormzjl.icheme.science.column.operation;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * What a column's Operation tab shows (batch 2026-10-28-column-operation, C7, plan section 13): a snapshot of cached engine
 * data, read on the presentation bucket, never a solve. Minecraft-free; quantities in SI.
 *
 * @param phase      the column's state, said once on the message display: the highest rank of {@code conditions}
 * @param conditions what the last cycle and the solves found (W1): {@link #binding} cuts the throughput where {@code phase}
 *                   is {@link Phase#LIMITED}; a STEAM_SHORT with text is rule A; a SOLVE_REFUSED or SETPOINT_REFUSED a
 *                   refused solve, its table kept
 * @param alpha      the share of the set feed the last cycle took, 0 to 1
 * @param resolving  the trigger of the solve in flight, or null
 * @param engine     the engine's own status line, for the hover
 * @param running   the run switch's position
 * @param editable  whether set points can be applied: every column that can run, before its first run too (W7)
 * @param setpoints the set points the player requested (the design's where the column never ran)
 * @param accepted  the set points the table in force was solved at, where they differ from {@code setpoints} (W2), or null
 * @param streams   the ports, in the table's order: inlets, then products
 * @param duties    the duties the last cycle booked against the last solve's
 * @param solve     the table in force, or null before the first
 */
public record OperationView(Phase phase, List<Condition.Active> conditions, double alpha, @Nullable DriftBands.Trigger resolving, String engine,
                            boolean running, boolean editable, Setpoints setpoints, @Nullable Setpoints accepted, List<Stream> streams, List<Duty> duties, @Nullable Solve solve,
                            Trays trays) {
    /** The statuses of plan section 13; a solve in flight is said beside them ({@link #resolving}). */
    public enum Phase {
        NOT_READY, STOPPED, STARTING, RUNNING, LIMITED, UPSET
    }

    /**
     * A port: its rate, mol/s; its temperature, K (NaN unknown); the pressure at the port, Pa; its buffer's fill, 0 to 1;
     * its boundary valve's opening, 0 to 1 (NaN unknown).
     */
    public record Stream(Port port, int index, double rate, double temperature, double pressure, double held, double opening) {
        public Stream {
            Objects.requireNonNull(port, "port");
            if (index < 0) throw new IllegalArgumentException("A port's index is 0 or more");
        }
    }

    public enum DutyKind {
        CONDENSER, REBOILER, PUMPAROUND,
        /** An air condenser's fans, W of electric power (U2): at the last cycle's feed, and at the set feed on the table in force. */
        FANS
    }

    /** A duty, W: what the last cycle booked at its throughput, what the last solve gave at its feed (negative: heat removed). */
    public record Duty(DutyKind kind, int index, double booked, double solved) {
        public Duty {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The table in force: the trigger that asked it (null where a reload lost it), whether it solved warm, its age, s. */
    public record Solve(@Nullable DriftBands.Trigger trigger, boolean warm, double age) {
    }

    /**
     * The trays (W7): the pressure drop over them, Pa, and the worst tray's fraction of its flood limit, at the feed the last
     * cycle took ({@code drop}, {@code flood}) and at the set feed on the table in force ({@code dropAtSet}, {@code floodAtSet}),
     * with the worst tray's number as the player counts (1 the top tray); NaN and 0 where the table has no hydraulics.
     */
    public record Trays(double drop, double flood, double dropAtSet, double floodAtSet, int tray) {
        public static final Trays NONE = new Trays(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

        public Trays {
            if (tray < 0) throw new IllegalArgumentException("A tray number is 0 (none) or more");
        }

        public boolean known() {
            return Double.isFinite(dropAtSet) || Double.isFinite(floodAtSet);
        }
    }

    /** A view without tray readings. */
    public OperationView(Phase phase, List<Condition.Active> conditions, double alpha, @Nullable DriftBands.Trigger resolving, String engine, boolean running, boolean editable,
                         Setpoints setpoints, @Nullable Setpoints accepted, List<Stream> streams, List<Duty> duties, @Nullable Solve solve) {
        this(phase, conditions, alpha, resolving, engine, running, editable, setpoints, accepted, streams, duties, solve, Trays.NONE);
    }

    public OperationView {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(trays, "trays");
        conditions = List.copyOf(conditions);
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(setpoints, "setpoints");
        streams = List.copyOf(streams);
        duties = List.copyOf(duties);
    }

    /** A column with no unit yet: stopped or not ready, on its design's set points, which a column that can run takes (W7). */
    public static OperationView idle(Phase phase, String engine, Setpoints setpoints, boolean editable) {
        return new OperationView(phase, List.of(), 0, null, engine, false, editable, setpoints, null, List.of(), List.of(), null);
    }

    /** {@link #idle(Phase, String, Setpoints, boolean)} that takes no set points. */
    public static OperationView idle(Phase phase, String engine, Setpoints setpoints) {
        return idle(phase, engine, setpoints, false);
    }

    /** The condition that binds: the TRIP, else the LIMIT with the lowest share, or null. */
    public @Nullable Condition.Active binding() {
        return Condition.binding(conditions);
    }

    /** Whether rule A is in force: a steam short cuts every input together. */
    public boolean ruleA() {
        var steam = Condition.find(conditions, Condition.STEAM_SHORT);
        return steam != null && !steam.text().isEmpty();
    }

    /** Whether the last solve was refused and its table kept (a drift state or the applied set points). */
    public boolean refused() {
        return Condition.find(conditions, Condition.SOLVE_REFUSED) != null || Condition.find(conditions, Condition.SETPOINT_REFUSED) != null;
    }
}
