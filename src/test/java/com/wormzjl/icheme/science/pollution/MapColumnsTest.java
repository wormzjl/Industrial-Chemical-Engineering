package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The pollution map's column summary (WP9, plan section 12): maxima, bytes, reach, cap and the look of a square. */
class MapColumnsTest {
    private static final PollutionSettings SETTINGS = PollutionSettings.DEFAULT;

    /** A field from cube indices at air share 1: each cube's four indices in channel order. */
    private static PollutionField field(Map<Long, double[]> indices) {
        var sorted = new TreeMap<>(indices);
        long[] keys = new long[sorted.size()];
        double[] amounts = new double[sorted.size() * 4];
        int i = 0;
        for (var entry : sorted.entrySet()) {
            keys[i] = entry.getKey();
            for (int c = 0; c < 4; c++) {
                amounts[i * 4 + c] = entry.getValue()[c] * PollutionField.CUBE_AIR_MOLES;
            }
            i++;
        }
        return PollutionField.of(keys, amounts);
    }

    private static int bytes(int toxic, int combustible, int corrosive, int particulate) {
        return toxic | combustible << 8 | corrosive << 16 | particulate << 24;
    }

    @Test
    void aColumnShowsItsLargestIndexOverItsCubesPerChannel() {
        var indices = Map.of(
                CubeKey.of(3, 0, -5), new double[] {0.2, 0, 0, 0},
                CubeKey.of(3, 1, -5), new double[] {0.6, 1.2, 0, 0},
                CubeKey.of(3, 2, -5), new double[] {0.4, 0, 0.3, 0},
                CubeKey.of(3, -4, -5), new double[] {0, 0, 0, 0.12},
                // A faint column (worst 0.05) is not drawn, so not kept.
                CubeKey.of(4, 0, -5), new double[] {0.05, 0, 0, 0},
                CubeKey.of(-2, 7, 7), new double[] {0, 0, 0, 2.0});
        var columns = MapColumns.columns(field(indices), cube -> 1.0, SETTINGS);
        assertEquals(2, columns.size());
        long a = MapColumns.column(3, -5);
        long b = MapColumns.column(-2, 7);
        assertArrayEquals(new long[] {Math.min(a, b), Math.max(a, b)}, columns.columns(), "sorted by column");
        int packedA = columns.values()[Arrays.binarySearch(columns.columns(), a)];
        assertEquals(30, MapColumns.value(packedA, Channel.TOXIC), "0.6 over 0.2 and 0.4");
        assertEquals(60, MapColumns.value(packedA, Channel.COMBUSTIBLE));
        assertEquals(15, MapColumns.value(packedA, Channel.CORROSIVE));
        assertEquals(6, MapColumns.value(packedA, Channel.PARTICULATE), "the cube far below counts too");
        int packedB = columns.values()[Arrays.binarySearch(columns.columns(), b)];
        assertEquals(bytes(0, 0, 0, 100), packedB);
        assertEquals(MapColumns.Columns.EMPTY.size(), MapColumns.columns(PollutionField.EMPTY, cube -> 1.0, SETTINGS).size());
    }

    @Test
    void theCubesAirShareRaisesItsIndex() {
        long half = CubeKey.of(0, 0, 0);
        long closed = CubeKey.of(1, 0, 0);
        var field = field(Map.of(half, new double[] {0.25, 0, 0, 0}, closed, new double[] {0.002, 0, 0, 0}));
        var columns = MapColumns.columns(field, cube -> cube == half ? 0.5 : 0.0, SETTINGS);
        assertEquals(25, MapColumns.value(columns.values()[Arrays.binarySearch(columns.columns(), MapColumns.column(0, 0))], Channel.TOXIC),
                "0.25 of an open cube's air in half the air is 0.5");
        // A closed cube's index uses closedAirShare (0.02): 0.002 / 0.02 = 0.1.
        assertEquals(5, MapColumns.value(columns.values()[Arrays.binarySearch(columns.columns(), MapColumns.column(1, 0))], Channel.TOXIC));
    }

    @Test
    void bytesRoundTripAndSaturate() {
        for (int b = 0; b <= 255; b++) {
            assertEquals(b, MapColumns.quantise(MapColumns.dequantise(b)), "byte " + b);
        }
        assertEquals(21, MapColumns.quantise(0.42));
        assertEquals(0.42, MapColumns.dequantise(21), 1e-12);
        assertEquals(0, MapColumns.quantise(0.009), "below half a step");
        assertEquals(1, MapColumns.quantise(0.011));
        assertEquals(255, MapColumns.quantise(5.1));
        assertEquals(255, MapColumns.quantise(100));
        assertEquals(255, MapColumns.quantise(Double.POSITIVE_INFINITY));
        assertEquals(0, MapColumns.quantise(Double.NaN));
        assertEquals(0, MapColumns.quantise(-1));
        assertEquals(5.1, MapColumns.dequantise(255), 1e-12);
        for (var channel : Channel.values()) {
            int packed = MapColumns.raise(0, channel, 200);
            assertEquals(200, MapColumns.value(packed, channel), channel.name());
            assertEquals(200, MapColumns.value(MapColumns.raise(packed, channel, 7), channel), "raise keeps the larger");
        }
    }

    @Test
    void columnsPackChunkCoordinates() {
        for (int[] xz : new int[][] {{0, 0}, {-1, -1}, {1_874_999, -1_875_000}, {-30, 12}}) {
            long column = MapColumns.column(xz[0], xz[1]);
            assertEquals(xz[0], MapColumns.x(column));
            assertEquals(xz[1], MapColumns.z(column));
        }
        assertEquals(MapColumns.column(-7, 9), MapColumns.columnOf(CubeKey.of(-7, -3, 9)));
    }

    /** Columns at every chunk of a square around the origin, each with toxic 1, sorted by column. */
    private static MapColumns.Columns grid(int half) {
        var list = new ArrayList<Long>();
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                list.add(MapColumns.column(x, z));
            }
        }
        list.sort(null);
        long[] columns = list.stream().mapToLong(Long::longValue).toArray();
        int[] values = new int[columns.length];
        Arrays.fill(values, bytes(50, 0, 0, 0));
        return new MapColumns.Columns(columns, values);
    }

    @Test
    void theAnswerReachesThirtyTwoChunksNearestFirst() {
        var all = grid(40);
        var near = MapColumns.around(all, 0, 0, MapColumns.RADIUS_CHUNKS, MapColumns.MAXIMUM_ENTRIES);
        int disc = 0;
        for (long column : all.columns()) {
            long dx = MapColumns.x(column);
            long dz = MapColumns.z(column);
            if (dx * dx + dz * dz <= 32L * 32) {
                disc++;
            }
        }
        assertTrue(disc <= MapColumns.MAXIMUM_ENTRIES, "the cap never cuts the disc of 32 chunks: " + disc);
        assertEquals(disc, near.size());
        var set = new java.util.HashSet<Long>();
        for (long column : near.columns()) {
            set.add(column);
        }
        assertTrue(set.contains(MapColumns.column(32, 0)), "at the edge");
        assertTrue(set.contains(MapColumns.column(-32, 0)));
        assertTrue(!set.contains(MapColumns.column(0, 33)), "beyond it");
        assertTrue(!set.contains(MapColumns.column(23, 23)), "a circle, not a square (d² 1058)");
        // Nearest first: the distances never fall.
        long last = -1;
        for (long column : near.columns()) {
            long d = (long) MapColumns.x(column) * MapColumns.x(column) + (long) MapColumns.z(column) * MapColumns.z(column);
            assertTrue(d >= last, "nearest first");
            last = d;
        }
        // Around another centre.
        var shifted = MapColumns.around(new MapColumns.Columns(new long[] {MapColumns.column(132, -50), MapColumns.column(133, -50)},
                new int[] {1, 2}), 100, -50, MapColumns.RADIUS_CHUNKS, MapColumns.MAXIMUM_ENTRIES);
        assertArrayEquals(new long[] {MapColumns.column(132, -50)}, shifted.columns());
        assertArrayEquals(new int[] {1}, shifted.values());
    }

    @Test
    void theCapKeepsTheNearestAndTiesGoByColumn() {
        var near = MapColumns.around(grid(3), 0, 0, MapColumns.RADIUS_CHUNKS, 5);
        assertArrayEquals(new long[] {MapColumns.column(0, 0), MapColumns.column(-1, 0), MapColumns.column(0, 1), MapColumns.column(0, -1),
                MapColumns.column(1, 0)}, near.columns());
        assertEquals(0, MapColumns.around(grid(3), 0, 0, 32, 0).size());
        assertThrows(IllegalArgumentException.class, () -> MapColumns.around(grid(1), 0, 0, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new MapColumns.Columns(new long[1], new int[2]));
    }

    @Test
    void theSquaresOpacityFollowsTheWorstIndex() {
        assertEquals(0, MapColumns.alpha(0.09), "below 0.1: not drawn");
        assertEquals(0x30, MapColumns.alpha(0.1));
        assertEquals(0x68, MapColumns.alpha(0.55), "halfway");
        assertEquals(0xA0, MapColumns.alpha(1.0));
        assertEquals(0xA0, MapColumns.alpha(5.0));
        assertEquals(0, MapColumns.alpha(Double.NaN));
        assertEquals(0xA04CAF50, MapColumns.argb(bytes(50, 10, 0, 0)), "toxic at 1");
        assertEquals(0x30FF9800, MapColumns.argb(bytes(0, 5, 0, 0)), "combustible at 0.1");
        assertEquals(0, MapColumns.argb(bytes(4, 4, 4, 4)), "0.08 is not drawn");
        assertEquals(0, MapColumns.argb(0));
    }

    @Test
    void tiesGoToxicCombustibleCorrosiveParticulate() {
        assertEquals(Channel.TOXIC, MapColumns.worst(bytes(25, 25, 25, 25)));
        assertEquals(Channel.COMBUSTIBLE, MapColumns.worst(bytes(10, 40, 40, 40)));
        assertEquals(Channel.CORROSIVE, MapColumns.worst(bytes(10, 20, 40, 40)));
        assertEquals(Channel.PARTICULATE, MapColumns.worst(bytes(0, 0, 0, 9)));
        for (var channel : List.of(Channel.values())) {
            int argb = MapColumns.argb(MapColumns.raise(0, channel, 50));
            assertEquals(MapColumns.rgb(channel), argb & 0xFFFFFF, channel.name());
        }
        assertEquals(0x4CAF50, MapColumns.rgb(Channel.TOXIC));
        assertEquals(0xFF9800, MapColumns.rgb(Channel.COMBUSTIBLE));
        assertEquals(0xFFEB3B, MapColumns.rgb(Channel.CORROSIVE));
        assertEquals(0x9E9E9E, MapColumns.rgb(Channel.PARTICULATE));
    }
}
