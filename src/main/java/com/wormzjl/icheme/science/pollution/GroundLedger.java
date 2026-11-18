package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Where every ground amount went (batch 2026-11-18-ground-pollution): per channel in air-mole equivalents and per compound
 * in moles, {@code deposited = stored + decayed + drawn + capped}, exact to rounding ({@link #closure}). The air ledger's
 * {@code GROUND} total keeps its own meaning (what left the air field or an outfall for the ground). Immutable.
 */
public final class GroundLedger {
    public enum Total {
        DEPOSITED,
        DECAYED,
        DRAWN,
        CAPPED
    }

    static final int TOTALS = Total.values().length;
    /** Length of {@link #channels()}: channel-major, {@code channel.ordinal() * 4 + total.ordinal()}. */
    public static final int LENGTH = Channel.values().length * TOTALS;
    public static final GroundLedger EMPTY = new GroundLedger(new double[LENGTH], new TreeMap<>());

    private final double[] channels;
    private final TreeMap<String, double[]> compounds;

    /** Takes the arrays without a copy or a check. */
    private GroundLedger(double[] channels, TreeMap<String, double[]> compounds) {
        this.channels = channels;
        this.compounds = compounds;
    }

    static int slot(int channel, Total total) {
        return channel * TOTALS + total.ordinal();
    }

    public double get(Channel channel, Total total) {
        return channels[slot(channel.ordinal(), total)];
    }

    /** A compound's total in moles; 0 for a compound never deposited. */
    public double get(String compound, Total total) {
        var row = compounds.get(compound);
        return row == null ? 0.0 : row[total.ordinal()];
    }

    /** The channel totals for a save: channel-major, length {@link #LENGTH}. */
    public double[] channels() {
        return channels.clone();
    }

    /** The compound totals for a save, by name, each four values in {@link Total} order (copies). */
    public SortedMap<String, double[]> compounds() {
        var out = new TreeMap<String, double[]>();
        for (var e : compounds.entrySet()) out.put(e.getKey(), e.getValue().clone());
        return Collections.unmodifiableSortedMap(out);
    }

    /** Reads saved totals; refuses a wrong length, a non-finite or a negative value. */
    public static GroundLedger of(double[] channels, Map<String, double[]> compounds) {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(compounds, "compounds");
        if (channels.length != LENGTH) {
            throw new IllegalArgumentException("ground ledger needs " + LENGTH + " values, got " + channels.length);
        }
        for (double v : channels) valid(v);
        var copy = new TreeMap<String, double[]>();
        for (var e : compounds.entrySet()) {
            if (e.getValue().length != TOTALS) {
                throw new IllegalArgumentException("compound " + e.getKey() + " needs " + TOTALS + " totals");
            }
            for (double v : e.getValue()) valid(v);
            copy.put(Objects.requireNonNull(e.getKey(), "compound"), e.getValue().clone());
        }
        return new GroundLedger(channels.clone(), copy);
    }

    private static void valid(double v) {
        if (!(v >= 0.0) || !Double.isFinite(v)) {
            throw new IllegalArgumentException("ground ledger value must be finite and not negative: " + v);
        }
    }

    /** {@code deposited − (stored + decayed + drawn + capped)} of a channel; zero to rounding. */
    public double closure(Channel channel, GroundField field) {
        int c = channel.ordinal();
        return channels[slot(c, Total.DEPOSITED)] - (field.stored(channel) + channels[slot(c, Total.DECAYED)]
                + channels[slot(c, Total.DRAWN)] + channels[slot(c, Total.CAPPED)]);
    }

    /** {@code deposited − (stored + decayed + drawn + capped)} of a compound, moles; zero to rounding. */
    public double closure(String compound, GroundField field) {
        return get(compound, Total.DEPOSITED) - (field.stored(compound) + get(compound, Total.DECAYED)
                + get(compound, Total.DRAWN) + get(compound, Total.CAPPED));
    }

    /** A mutable copy the step books into. */
    Book book() {
        var copy = new TreeMap<String, double[]>();
        for (var e : compounds.entrySet()) copy.put(e.getKey(), e.getValue().clone());
        return new Book(channels.clone(), copy);
    }

    /** The step's working ledger. */
    static final class Book {
        final double[] channels;
        final TreeMap<String, double[]> compounds;

        private Book(double[] channels, TreeMap<String, double[]> compounds) {
            this.channels = channels;
            this.compounds = compounds;
        }

        void channel(int channel, Total total, double amount) {
            channels[slot(channel, total)] += amount;
        }

        void compound(String name, Total total, double moles) {
            compounds.computeIfAbsent(name, k -> new double[TOTALS])[total.ordinal()] += moles;
        }

        GroundLedger ledger() {
            return new GroundLedger(channels, compounds);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GroundLedger ledger) || !Arrays.equals(channels, ledger.channels)
                || !compounds.keySet().equals(ledger.compounds.keySet())) {
            return false;
        }
        for (var e : compounds.entrySet()) {
            if (!Arrays.equals(e.getValue(), ledger.compounds.get(e.getKey()))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(channels);
        for (var e : compounds.entrySet()) h = 31 * h + e.getKey().hashCode() * 17 + Arrays.hashCode(e.getValue());
        return h;
    }

    @Override
    public String toString() {
        return "GroundLedger" + Arrays.toString(channels) + " compounds " + compounds.keySet();
    }
}
