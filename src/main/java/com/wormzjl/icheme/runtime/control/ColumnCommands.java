package com.wormzjl.icheme.runtime.control;

import java.util.List;
import java.util.Objects;

/**
 * A DCS's column page (batch 2026-11-10-column-failure-modes, W6.2): the commands a console sends to a column whose data
 * socket its bus reaches. A command is queued like a controller edit and runs on the control cadence: the console must be
 * a DCS, its player may build, its bus must reach the column; then the column's own Operation actions run at its origin
 * (Start, Stop, the set points), as the column's tab sends them.
 */
public final class ColumnCommands {
    private ColumnCommands() {
    }

    public enum Action {
        START, STOP, APPLY
    }

    /** What a console asks of column host {@code host}; {@code values} in {@code AssemblyNetwork.OperationPayload}'s order, APPLY only. */
    public record Command(long host, Action action, List<Double> values) {
        /** A command's most values: the set point list's bound. */
        public static final int MAX_VALUES = 64;

        public Command {
            Objects.requireNonNull(action, "action");
            values = List.copyOf(values);
            if (values.size() > MAX_VALUES) throw new IllegalArgumentException("Too many set point values");
        }
    }

    /** The column's Operation actions at its origin: null, or why not. */
    public interface Door {
        String start(long host);

        void stop(long host);

        String setpoints(long host, List<Double> values);
    }

    public static final String APPLIED = "APPLIED", NO_DATA_LINK = "NO DATA LINK", NOT_A_DCS = "NOT A DCS", CHANGED = "SETTINGS CHANGED: APPLY AGAIN";

    /**
     * Runs {@code c} for a console: {@code dcs} whether it is a DCS, {@code allowed} whether its menu is open and its player
     * may build, {@code wired} whether its bus reaches the column's data socket. Returns the console's answer: APPLIED, or
     * the refusal.
     */
    public static String run(boolean dcs, boolean allowed, boolean wired, Command c, Door door) {
        if (!dcs) return NOT_A_DCS;
        if (!allowed) return CHANGED;
        if (!wired) return NO_DATA_LINK;
        String refusal = switch (c.action()) {
            case START -> door.start(c.host());
            case STOP -> {
                door.stop(c.host());
                yield null;
            }
            case APPLY -> door.setpoints(c.host(), c.values());
        };
        return refusal == null ? APPLIED : refusal;
    }
}
