package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;
import java.util.Objects;

/**
 * The ground as its readers see it (batch 2026-11-18-ground-pollution, plan G2): per column one index byte
 * ({@link MapColumns#quantise}, the map's scale: 50 per index unit, 255 at 5.1), published once per installed pass. The
 * map, the meter and the world effects read this on the server thread and never touch the worker's arrays. Immutable.
 */
public final class GroundSnapshot {
    public static final GroundSnapshot EMPTY = new GroundSnapshot(new long[0], new int[0]);

    private final long[] columns;
    private final int[] values;

    private GroundSnapshot(long[] columns, int[] values) {
        this.columns = columns;
        this.values = values;
    }

    /** The snapshot of {@code field}: every column whose byte is above 0. */
    public static GroundSnapshot of(GroundField field, GroundSettings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(settings, "settings");
        long[] columns = new long[field.size()];
        int[] values = new int[field.size()];
        int n = 0;
        for (int i = 0; i < field.size(); i++) {
            long column = field.column(i);
            int v = MapColumns.quantise(field.index(column, settings));
            if (v == 0) continue;
            columns[n] = column;
            values[n] = v;
            n++;
        }
        return new GroundSnapshot(Arrays.copyOf(columns, n), Arrays.copyOf(values, n));
    }

    public int size() {
        return columns.length;
    }

    public long column(int i) {
        return columns[i];
    }

    /** The {@code i}-th column's byte (0 to 255). */
    public int value(int i) {
        return values[i];
    }

    /** A column's byte; 0 when absent. */
    public int value(long column) {
        int i = Arrays.binarySearch(columns, column);
        return i < 0 ? 0 : values[i];
    }

    /** A column's index, to the byte's resolution; 0 when absent. */
    public double index(long column) {
        return MapColumns.dequantise(value(column));
    }
}
