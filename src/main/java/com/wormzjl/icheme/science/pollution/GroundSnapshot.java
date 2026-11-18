package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;
import java.util.Objects;

/**
 * The ground as its readers see it (batch 2026-11-18-ground-pollution, plan G2): per column four index bytes
 * ({@link MapColumns#quantise}, the map's scale: 50 per index unit, 255 at 5.1), published once per installed pass. The
 * map, the meter and the world effects read this on the server thread and never touch the worker's arrays. Immutable.
 */
public final class GroundSnapshot {
    public static final GroundSnapshot EMPTY = new GroundSnapshot(new long[0], new int[0]);

    private final long[] columns;
    private final int[] packed;

    private GroundSnapshot(long[] columns, int[] packed) {
        this.columns = columns;
        this.packed = packed;
    }

    /** The snapshot of {@code field}: every column with any byte above 0. */
    public static GroundSnapshot of(GroundField field, GroundSettings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(settings, "settings");
        long[] columns = new long[field.size()];
        int[] packed = new int[field.size()];
        int n = 0;
        for (int i = 0; i < field.size(); i++) {
            long column = field.column(i);
            int p = 0;
            for (var channel : Channel.values()) {
                p |= MapColumns.quantise(field.index(column, channel, settings)) << 8 * channel.ordinal();
            }
            if (p == 0) continue;
            columns[n] = column;
            packed[n] = p;
            n++;
        }
        return new GroundSnapshot(Arrays.copyOf(columns, n), Arrays.copyOf(packed, n));
    }

    public int size() {
        return columns.length;
    }

    public long column(int i) {
        return columns[i];
    }

    public int packed(int i) {
        return packed[i];
    }

    /** A column's four bytes; 0 when absent. */
    public int packed(long column) {
        int i = Arrays.binarySearch(columns, column);
        return i < 0 ? 0 : packed[i];
    }

    /** A column's channel index, to the byte's resolution; 0 when absent. */
    public double index(long column, Channel channel) {
        return MapColumns.dequantise(MapColumns.value(packed(column), channel));
    }

    /** A column's worst index over the four channels. */
    public double worst(long column) {
        int p = packed(column);
        int max = 0;
        for (var channel : Channel.values()) max = Math.max(max, MapColumns.value(p, channel));
        return MapColumns.dequantise(max);
    }
}
