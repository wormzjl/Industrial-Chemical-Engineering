package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;
import java.util.Objects;

/**
 * Where every amount went, per channel, in air-mole equivalents: {@code released = stored + decayed + dissipated +
 * burnt + ground}, exact to rounding ({@link #closure}). Immutable.
 */
public final class PollutionLedger {
    public enum Total {
        RELEASED,
        DECAYED,
        DISSIPATED,
        BURNT,
        GROUND
    }

    static final int TOTALS = Total.values().length;
    /** Length of {@link #values()}: channel-major, {@code channel.ordinal() * 5 + total.ordinal()}. */
    public static final int LENGTH = Channel.values().length * TOTALS;
    public static final PollutionLedger EMPTY = new PollutionLedger(new double[LENGTH]);

    private final double[] values;

    /** Takes the array without a copy or a check; callers own the validation. */
    PollutionLedger(double[] values) {
        this.values = values;
    }

    static int slot(int channel, Total total) {
        return channel * TOTALS + total.ordinal();
    }

    public double get(Channel channel, Total total) {
        return values[slot(channel.ordinal(), total)];
    }

    /** The totals for a save: channel-major, length {@link #LENGTH}. */
    public double[] values() {
        return values.clone();
    }

    /** Reads saved totals; refuses a wrong length, a non-finite or a negative value. */
    public static PollutionLedger of(double[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length != LENGTH) {
            throw new IllegalArgumentException("ledger needs " + LENGTH + " values, got " + values.length);
        }
        for (int i = 0; i < LENGTH; i++) {
            if (!(values[i] >= 0.0) || !Double.isFinite(values[i])) {
                throw new IllegalArgumentException("ledger value " + i + " must be finite and not negative: "
                        + values[i]);
            }
        }
        return new PollutionLedger(values.clone());
    }

    /** {@code released − (stored + decayed + dissipated + burnt + ground)}; zero to rounding. */
    public double closure(Channel channel, PollutionField field) {
        int c = channel.ordinal();
        return values[slot(c, Total.RELEASED)] - (field.stored(channel) + values[slot(c, Total.DECAYED)]
                + values[slot(c, Total.DISSIPATED)] + values[slot(c, Total.BURNT)] + values[slot(c, Total.GROUND)]);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PollutionLedger ledger && Arrays.equals(values, ledger.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "PollutionLedger" + Arrays.toString(values);
    }
}
