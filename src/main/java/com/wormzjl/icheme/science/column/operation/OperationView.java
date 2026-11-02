package com.wormzjl.icheme.science.column.operation;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * What a column's Operation tab shows (batch 2026-10-28-column-operation, C7, plan section 13): a snapshot of cached engine
 * data, read on the presentation bucket, never a solve. Minecraft-free; quantities in SI.
 *
 * @param phase     the column's state, said once on the message display
 * @param limit     what cuts the throughput where {@code phase} is {@link Phase#LIMITED}
 * @param alpha     the share of the set feed the last cycle took, 0 to 1
 * @param ruleA     every input cut together (rule A: a solve of the short inputs failed)
 * @param resolving the trigger of the solve in flight, or null
 * @param failed    the last solve failed and its table was kept
 * @param engine    the engine's own status line, for the hover
 * @param running   the run switch's position
 * @param editable  whether set points can be applied: the column has its unit (it ran once)
 * @param setpoints the set points in force (the design's where the column never ran)
 * @param streams   the ports, in the table's order: inlets, then products
 * @param duties    the duties the last cycle booked against the last solve's
 * @param solve     the table in force, or null before the first
 */
public record OperationView(Phase phase, ColumnCycle.Limit limit, double alpha, boolean ruleA, @Nullable DriftBands.Trigger resolving, boolean failed, String engine,
                            boolean running, boolean editable, Setpoints setpoints, List<Stream> streams, List<Duty> duties, @Nullable Solve solve) {
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
        CONDENSER, REBOILER, PUMPAROUND
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

    public OperationView {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(setpoints, "setpoints");
        streams = List.copyOf(streams);
        duties = List.copyOf(duties);
    }

    /** A column with no unit yet: stopped or not ready, on its design's set points. */
    public static OperationView idle(Phase phase, String engine, Setpoints setpoints) {
        return new OperationView(phase, ColumnCycle.Limit.NONE, 0, false, null, false, engine, false, false, setpoints, List.of(), List.of(), null);
    }
}
