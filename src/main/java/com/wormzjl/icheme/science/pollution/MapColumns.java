package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;
import java.util.Objects;

/**
 * The pollution map's column summary (batch 2026-11-15-pollution-system, WP9; plan section 12). A map is flat, so a chunk
 * column shows the largest index over its cubes, per channel. Each index is quantised to one byte in steps of
 * {@code 1 / }{@link #SCALE} (0.02), saturating at 255 (5.1). A column is kept when its worst byte reaches the drawn floor
 * ({@link #DRAW_FLOOR}, 0.1): the map draws nothing below it. Pure: no Minecraft class.
 *
 * <p>A column is a long, {@code x << 32 | z} in chunk coordinates ({@link #column}); its four bytes are an int, the channel
 * of ordinal {@code i} in bits {@code 8i..8i+7} ({@link #value}).</p>
 */
public final class MapColumns {
    /** Index steps per unit: one byte step is 0.02. */
    public static final int SCALE = 50;
    /** The largest byte: an index of 5.1 or more. */
    public static final int SATURATED = 255;
    /** The answer's reach around the player, in chunks: 512 blocks. */
    public static final int RADIUS_CHUNKS = 32;
    /** The most columns one answer carries, the nearest first. */
    public static final int MAXIMUM_ENTRIES = 4096;
    /** Below this index a column is not drawn (the meter's SAFE). */
    public static final double DRAW_FLOOR = 0.1;
    /** At and above this index a square is at its strongest. */
    public static final double DRAW_FULL = 1.0;
    /** The square's alpha at {@link #DRAW_FLOOR} and at {@link #DRAW_FULL}; linear between. */
    public static final int ALPHA_FAINT = 0x30;
    public static final int ALPHA_STRONG = 0xA0;
    /** The squares' colours by the worst channel, RGB. */
    public static final int TOXIC_RGB = 0x4CAF50;
    public static final int COMBUSTIBLE_RGB = 0xFF9800;
    public static final int CORROSIVE_RGB = 0xFFEB3B;
    public static final int PARTICULATE_RGB = 0x9E9E9E;

    private static final Channel[] CHANNELS = Channel.values();
    /** The byte of {@link #DRAW_FLOOR}: a column whose worst byte is below it is not kept. */
    static final int MINIMUM_BYTE = quantise(DRAW_FLOOR);

    private MapColumns() {
    }

    /** A cube's air share, as the field's index reads it (1 when unknown). */
    @FunctionalInterface
    public interface AirShare {
        double of(long cube);
    }

    /**
     * Columns and their four bytes, in the same order. {@link #columns(PollutionField, AirShare, PollutionSettings)} sorts
     * them by column; {@link #around} by distance, the nearest first.
     */
    public record Columns(long[] columns, int[] values) {
        public static final Columns EMPTY = new Columns(new long[0], new int[0]);

        public Columns {
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(values, "values");
            if (columns.length != values.length) {
                throw new IllegalArgumentException(columns.length + " columns but " + values.length + " values");
            }
        }

        public int size() {
            return columns.length;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Packing.

    /** The column of chunk {@code x, z}. */
    public static long column(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    public static int x(long column) {
        return (int) (column >> 32);
    }

    public static int z(long column) {
        return (int) column;
    }

    /** The column that holds a cube. */
    public static long columnOf(long cube) {
        return column(CubeKey.x(cube), CubeKey.z(cube));
    }

    /** The byte of {@code channel} in a column's four bytes. */
    public static int value(int packed, Channel channel) {
        return packed >>> (8 * channel.ordinal()) & 0xFF;
    }

    /** {@code packed} with the byte of {@code channel} raised to {@code value} if it is larger. */
    static int raise(int packed, Channel channel, int value) {
        int shift = 8 * channel.ordinal();
        int old = packed >>> shift & 0xFF;
        return value > old ? packed & ~(0xFF << shift) | (value & 0xFF) << shift : packed;
    }

    /** An index to a byte: {@code min(255, round(index x 50))}; 0 for NaN or a value not above 0. */
    public static int quantise(double index) {
        if (!(index > 0)) {
            return 0;
        }
        return (int) Math.min(SATURATED, Math.round(index * SCALE));
    }

    /** A byte back to its index. */
    public static double dequantise(int value) {
        return (value & 0xFF) / (double) SCALE;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The summary.

    /**
     * Every column of a field whose worst byte reaches {@link #DRAW_FLOOR}, with its largest byte per channel over its
     * cubes, sorted by column. Each cube's index uses {@code air} ({@link PollutionField#index(double, double,
     * PollutionSettings)}). One walk over the field.
     */
    public static Columns columns(PollutionField field, AirShare air, PollutionSettings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(air, "air");
        Objects.requireNonNull(settings, "settings");
        int n = field.size();
        long[] columns = new long[n];
        int[] values = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            long cube = field.key(i);
            double share = air.of(cube);
            int packed = 0;
            for (var channel : CHANNELS) {
                packed = raise(packed, channel, quantise(PollutionField.index(field.amount(i, channel), share, settings)));
            }
            long column = columnOf(cube);
            // The field's keys ascend by x, then z, then y, so a column's cubes are neighbours: merge into the last entry.
            if (count > 0 && columns[count - 1] == column) {
                values[count - 1] = merge(values[count - 1], packed);
            } else {
                columns[count] = column;
                values[count] = packed;
                count++;
            }
        }
        // Sorted by column. The field's key order (x signed, then z as an unsigned 22-bit field) is the column order (x
        // signed, then z as an unsigned 32-bit field), so the runs come sorted; any other order is sorted here, and the
        // merge below joins equal columns, so the result holds for keys in any order.
        Integer[] order = new Integer[count];
        boolean sorted = true;
        for (int i = 0; i < count; i++) {
            order[i] = i;
            sorted &= i == 0 || columns[i - 1] < columns[i];
        }
        if (!sorted) {
            final long[] unsorted = columns;
            Arrays.sort(order, (a, b) -> Long.compare(unsorted[a], unsorted[b]));
        }
        long[] outColumns = new long[count];
        int[] outValues = new int[count];
        int kept = 0;
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (kept > 0 && outColumns[kept - 1] == columns[i]) {
                outValues[kept - 1] = merge(outValues[kept - 1], values[i]);
            } else {
                outColumns[kept] = columns[i];
                outValues[kept] = values[i];
                kept++;
            }
        }
        int drawn = 0;
        for (int k = 0; k < kept; k++) {
            if (worstByte(outValues[k]) >= MINIMUM_BYTE) {
                outColumns[drawn] = outColumns[k];
                outValues[drawn] = outValues[k];
                drawn++;
            }
        }
        return new Columns(Arrays.copyOf(outColumns, drawn), Arrays.copyOf(outValues, drawn));
    }

    /**
     * The columns of {@code all} within {@code radius} chunks of chunk {@code centreX, centreZ} (a circle: dx² + dz² at most
     * radius²), the nearest first (ties by column), at most {@code maximum}.
     */
    public static Columns around(Columns all, int centreX, int centreZ, int radius, int maximum) {
        Objects.requireNonNull(all, "all");
        if (radius < 0 || maximum < 0) {
            throw new IllegalArgumentException("radius " + radius + ", maximum " + maximum);
        }
        long reach = (long) radius * radius;
        int n = all.size();
        // A sort key per kept column: its squared distance (at most 2 radius², below 2^31 for any int radius we use) above
        // its position in all, which is in column order, so ties go by column.
        long[] keys = new long[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            long dx = (long) x(all.columns()[i]) - centreX;
            long dz = (long) z(all.columns()[i]) - centreZ;
            long d2 = dx * dx + dz * dz;
            if (d2 <= reach) {
                keys[count++] = d2 << 32 | i;
            }
        }
        Arrays.sort(keys, 0, count);
        int size = Math.min(count, maximum);
        long[] columns = new long[size];
        int[] values = new int[size];
        for (int k = 0; k < size; k++) {
            int i = (int) (keys[k] & 0xFFFFFFFFL);
            columns[k] = all.columns()[i];
            values[k] = all.values()[i];
        }
        return new Columns(columns, values);
    }

    /**
     * Columns with two layers of four bytes each (batch 2026-11-18-ground-pollution, G3): the air's (worst cube) and the
     * ground's, in the same order; a layer with nothing at a column is 0 there.
     */
    public record Layers(long[] columns, int[] air, int[] ground) {
        public static final Layers EMPTY = new Layers(new long[0], new int[0], new int[0]);

        public Layers {
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(air, "air");
            Objects.requireNonNull(ground, "ground");
            if (columns.length != air.length || columns.length != ground.length) {
                throw new IllegalArgumentException(columns.length + " columns, " + air.length + " air, " + ground.length + " ground");
            }
        }

        public int size() {
            return columns.length;
        }
    }

    /**
     * The union of the air's drawn columns and the ground's columns whose worst byte reaches {@link #DRAW_FLOOR}, sorted by
     * column, each with both layers' bytes.
     */
    public static Layers layers(Columns air, GroundSnapshot ground) {
        Objects.requireNonNull(air, "air");
        Objects.requireNonNull(ground, "ground");
        var merged = new java.util.TreeMap<Long, int[]>();
        for (int i = 0; i < air.size(); i++) merged.computeIfAbsent(air.columns()[i], k -> new int[2])[0] = air.values()[i];
        for (int i = 0; i < ground.size(); i++) {
            int packed = ground.packed(i);
            if (worstByte(packed) >= MINIMUM_BYTE) merged.computeIfAbsent(ground.column(i), k -> new int[2])[1] = packed;
        }
        long[] columns = new long[merged.size()];
        int[] airValues = new int[merged.size()], groundValues = new int[merged.size()];
        int k = 0;
        for (var e : merged.entrySet()) {
            columns[k] = e.getKey();
            airValues[k] = e.getValue()[0];
            groundValues[k] = e.getValue()[1];
            k++;
        }
        return new Layers(columns, airValues, groundValues);
    }

    /** {@link #around(Columns, int, int, int, int)} for both layers: the same columns, the nearest first. */
    public static Layers around(Layers all, int centreX, int centreZ, int radius, int maximum) {
        Objects.requireNonNull(all, "all");
        // The positions ride in the values of a Columns, so the one distance rule picks and orders both layers.
        int[] positions = new int[all.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = i;
        var picked = around(new Columns(all.columns(), positions), centreX, centreZ, radius, maximum);
        int n = picked.size();
        int[] air = new int[n], ground = new int[n];
        for (int k = 0; k < n; k++) {
            air[k] = all.air()[picked.values()[k]];
            ground[k] = all.ground()[picked.values()[k]];
        }
        return new Layers(picked.columns(), air, ground);
    }

    /** Per channel the larger of two columns' bytes: what a square shows for both layers at once. */
    public static int worse(int a, int b) {
        return merge(a, b);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The look.

    /** The channel with the largest byte; on a tie the first in {@link Channel} order (toxic, combustible, corrosive, particulate). */
    public static Channel worst(int packed) {
        var out = CHANNELS[0];
        int best = value(packed, out);
        for (int c = 1; c < CHANNELS.length; c++) {
            int v = value(packed, CHANNELS[c]);
            if (v > best) {
                best = v;
                out = CHANNELS[c];
            }
        }
        return out;
    }

    /** The square's colour of a channel, RGB. */
    public static int rgb(Channel channel) {
        return switch (channel) {
            case TOXIC -> TOXIC_RGB;
            case COMBUSTIBLE -> COMBUSTIBLE_RGB;
            case CORROSIVE -> CORROSIVE_RGB;
            case PARTICULATE -> PARTICULATE_RGB;
        };
    }

    /**
     * The square's alpha at an index: 0 (not drawn) below {@link #DRAW_FLOOR} or for NaN, {@link #ALPHA_FAINT} at it,
     * {@link #ALPHA_STRONG} at {@link #DRAW_FULL} and above, linear between.
     */
    public static int alpha(double index) {
        if (!(index >= DRAW_FLOOR)) {
            return 0;
        }
        if (index >= DRAW_FULL) {
            return ALPHA_STRONG;
        }
        return (int) Math.round(ALPHA_FAINT + (index - DRAW_FLOOR) / (DRAW_FULL - DRAW_FLOOR) * (ALPHA_STRONG - ALPHA_FAINT));
    }

    /** A column's square as ARGB: the worst channel's colour at that channel's alpha; 0 when it is not drawn. */
    public static int argb(int packed) {
        var channel = worst(packed);
        int alpha = alpha(dequantise(value(packed, channel)));
        return alpha == 0 ? 0 : alpha << 24 | rgb(channel);
    }

    private static int worstByte(int packed) {
        return value(packed, worst(packed));
    }

    private static int merge(int a, int b) {
        int out = a;
        for (var channel : CHANNELS) {
            out = raise(out, channel, value(b, channel));
        }
        return out;
    }
}
