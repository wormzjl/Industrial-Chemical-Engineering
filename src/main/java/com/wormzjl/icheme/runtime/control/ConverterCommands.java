package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.ConverterUnit;
import com.wormzjl.icheme.runtime.fluid.Converters;
import java.util.List;
import java.util.Objects;

/**
 * A DCS's converter page (batch 2026-11-12-fluid-conversion, W4, plan 3.4): the commands a console sends to a fluid converter
 * whose data socket its bus reaches, as {@link ColumnCommands} for a column. A command is queued like a controller edit and
 * runs on the control cadence: the console must be a DCS, its player may build, its bus must reach the converter; then the
 * converter's next settings (the ones already queued, else its facts) change in the one field the command names and are
 * queued for the engine, which applies them at its next tick.
 */
public final class ConverterCommands {
    private ConverterCommands() {
    }

    public enum Action {
        SET_MODE, SET_RATE, SET_DEADBAND, SET_THRESHOLDS
    }

    /**
     * What a console asks of converter host {@code host}: SET_MODE its {@code mode}; SET_RATE one value (L per process
     * second); SET_DEADBAND one value (a fill fraction); SET_THRESHOLDS four (fill fractions in {@link ConverterUnit#ICHEME_HIGH}
     * order, a negative value for none).
     */
    public record Command(long host, Action action, ConverterUnit.Mode mode, List<Double> values) {
        public Command {
            Objects.requireNonNull(action, "action");
            values = List.copyOf(values);
            if (action == Action.SET_MODE) Objects.requireNonNull(mode, "mode");
            if (values.size() != count(action)) throw new IllegalArgumentException("A converter command carries " + count(action) + " values");
        }

        /** How many values {@code action} carries. */
        public static int count(Action action) {
            return switch (action) {
                case SET_MODE -> 0;
                case SET_RATE, SET_DEADBAND -> 1;
                case SET_THRESHOLDS -> ConverterUnit.THRESHOLDS;
            };
        }
    }

    /** The converter's settings door: its next settings, or null for no converter; the queue, null or why not. */
    public interface Door {
        Converters.Settings settings(long host);

        String apply(long host, Converters.Settings settings);
    }

    public static final String NO_CONVERTER = "NO CONVERTER";

    /**
     * Runs {@code c} for a console: {@code dcs} whether it is a DCS, {@code allowed} whether its menu is open and its player
     * may build, {@code wired} whether its bus reaches the converter's data socket. Returns the console's answer: APPLIED, or
     * the refusal.
     */
    public static String run(boolean dcs, boolean allowed, boolean wired, Command c, Door door) {
        if (!dcs) return ColumnCommands.NOT_A_DCS;
        if (!allowed) return ColumnCommands.CHANGED;
        if (!wired) return ColumnCommands.NO_DATA_LINK;
        var s = door.settings(c.host());
        if (s == null) return NO_CONVERTER;
        var v = c.values();
        var next = switch (c.action()) {
            case SET_MODE -> new Converters.Settings(c.mode(), s.rate(), s.deadband(), s.thresholds());
            case SET_RATE -> new Converters.Settings(s.mode(), v.getFirst(), s.deadband(), s.thresholds());
            case SET_DEADBAND -> new Converters.Settings(s.mode(), s.rate(), v.getFirst(), s.thresholds());
            case SET_THRESHOLDS -> {
                var t = new double[ConverterUnit.THRESHOLDS];
                for (int i = 0; i < t.length; i++) t[i] = v.get(i);
                yield new Converters.Settings(s.mode(), s.rate(), s.deadband(), t);
            }
        };
        String refusal = door.apply(c.host(), next);
        return refusal == null ? ColumnCommands.APPLIED : refusal;
    }
}
