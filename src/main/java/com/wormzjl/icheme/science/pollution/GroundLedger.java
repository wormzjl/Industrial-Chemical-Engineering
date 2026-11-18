package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Where every ground amount went (batch 2026-11-18-ground-pollution): the one amount in air-mole equivalents and each
 * compound in moles, {@code deposited = stored + decayed + drawn + capped}, exact to rounding ({@link #closure}). The air
 * ledger's {@code GROUND} total keeps its own per-channel meaning (what each channel sent to the ground, settled
 * particulate included). Immutable.
 */
public final class GroundLedger {
    public enum Total {
        DEPOSITED,
        DECAYED,
        DRAWN,
        CAPPED
    }

    /** Length of {@link #totals()}: one value per {@link Total}. */
    public static final int LENGTH = Total.values().length;
    public static final GroundLedger EMPTY = new GroundLedger(new double[LENGTH], new TreeMap<>());

    private final double[] totals;
    private final TreeMap<String, double[]> compounds;

    /** Takes the arrays without a copy or a check. */
    private GroundLedger(double[] totals, TreeMap<String, double[]> compounds) {
        this.totals = totals;
        this.compounds = compounds;
    }

    /** The amount's total, in air-mole equivalents. */
    public double get(Total total) {
        return totals[total.ordinal()];
    }

    /** A compound's total in moles; 0 for a compound never deposited. */
    public double get(String compound, Total total) {
        var row = compounds.get(compound);
        return row == null ? 0.0 : row[total.ordinal()];
    }

    /** The amount's totals for a save, in {@link Total} order (a copy). */
    public double[] totals() {
        return totals.clone();
    }

    /** The compound totals for a save, by name, each four values in {@link Total} order (copies). */
    public SortedMap<String, double[]> compounds() {
        var out = new TreeMap<String, double[]>();
        for (var e : compounds.entrySet()) out.put(e.getKey(), e.getValue().clone());
        return Collections.unmodifiableSortedMap(out);
    }

    /** Reads saved totals; refuses a wrong length, a non-finite or a negative value. */
    public static GroundLedger of(double[] totals, Map<String, double[]> compounds) {
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(compounds, "compounds");
        if (totals.length != LENGTH) throw new IllegalArgumentException("ground ledger needs " + LENGTH + " values, got " + totals.length);
        for (double v : totals) valid(v);
        var copy = new TreeMap<String, double[]>();
        for (var e : compounds.entrySet()) {
            if (e.getValue().length != LENGTH) throw new IllegalArgumentException("compound " + e.getKey() + " needs " + LENGTH + " totals");
            for (double v : e.getValue()) valid(v);
            copy.put(Objects.requireNonNull(e.getKey(), "compound"), e.getValue().clone());
        }
        return new GroundLedger(totals.clone(), copy);
    }

    private static void valid(double v) {
        if (!(v >= 0.0) || !Double.isFinite(v)) throw new IllegalArgumentException("ground ledger value must be finite and not negative: " + v);
    }

    /** {@code deposited − (stored + decayed + drawn + capped)} of the amount; zero to rounding. */
    public double closure(GroundField field) {
        return get(Total.DEPOSITED) - (field.stored() + get(Total.DECAYED) + get(Total.DRAWN) + get(Total.CAPPED));
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
        return new Book(totals.clone(), copy);
    }

    /** The step's working ledger. */
    static final class Book {
        final double[] totals;
        final TreeMap<String, double[]> compounds;

        private Book(double[] totals, TreeMap<String, double[]> compounds) {
            this.totals = totals;
            this.compounds = compounds;
        }

        void amount(Total total, double amount) {
            totals[total.ordinal()] += amount;
        }

        /** The rows by name, found without the tree's string comparisons; the same arrays as {@link #compounds}' values. */
        private final java.util.HashMap<String, double[]> rows = new java.util.HashMap<>();

        void compound(String name, Total total, double moles) {
            double[] row = rows.get(name);
            if (row == null) {
                row = compounds.computeIfAbsent(name, k -> new double[LENGTH]);
                rows.put(name, row);
            }
            row[total.ordinal()] += moles;
        }

        GroundLedger ledger() {
            return new GroundLedger(totals, compounds);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GroundLedger ledger) || !Arrays.equals(totals, ledger.totals) || !compounds.keySet().equals(ledger.compounds.keySet())) {
            return false;
        }
        for (var e : compounds.entrySet()) if (!Arrays.equals(e.getValue(), ledger.compounds.get(e.getKey()))) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(totals);
        for (var e : compounds.entrySet()) h = 31 * h + e.getKey().hashCode() * 17 + Arrays.hashCode(e.getValue());
        return h;
    }

    @Override
    public String toString() {
        return "GroundLedger" + Arrays.toString(totals) + " compounds " + compounds.keySet();
    }
}
