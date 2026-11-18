package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The sorted key sets give what the boxed sets they replaced gave: the same keys, in the same order. */
class KeySetsTest {
    private static long[] array(TreeSet<Long> set) {
        long[] out = new long[set.size()];
        int i = 0;
        for (long v : set) out[i++] = v;
        return out;
    }

    @Test
    void cubeFacesAreTheCubesAndTheirSixNeighboursInKeyOrder() {
        Random random = new Random(1);
        for (int round = 0; round < 300; round++) {
            TreeSet<Long> cubes = new TreeSet<>();
            int n = random.nextInt(60);
            int spread = 1 + random.nextInt(6);
            for (int i = 0; i < n; i++) {
                cubes.add(CubeKey.of(random.nextInt(2 * spread + 1) - spread, random.nextInt(2 * spread + 1) - spread,
                        random.nextInt(2 * spread + 1) - spread));
            }
            TreeSet<Long> expected = new TreeSet<>(cubes);
            for (long c : cubes) {
                for (int[] d : new int[][] {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}}) {
                    expected.add(CubeKey.offset(c, d[0], d[1], d[2]));
                }
            }
            assertArrayEquals(array(expected), KeySets.cubeFaces(array(cubes)), "round " + round);
        }
    }

    @Test
    void columnFacesAreTheColumnsAndTheirFourNeighboursInKeyOrder() {
        Random random = new Random(2);
        for (int round = 0; round < 300; round++) {
            TreeSet<Long> columns = new TreeSet<>();
            int n = random.nextInt(60);
            int spread = 1 + random.nextInt(8);
            for (int i = 0; i < n; i++) {
                columns.add(MapColumns.column(random.nextInt(2 * spread + 1) - spread, random.nextInt(2 * spread + 1) - spread));
            }
            TreeSet<Long> expected = new TreeSet<>(columns);
            for (long c : columns) {
                int x = MapColumns.x(c), z = MapColumns.z(c);
                expected.add(MapColumns.column(x - 1, z));
                expected.add(MapColumns.column(x + 1, z));
                expected.add(MapColumns.column(x, z - 1));
                expected.add(MapColumns.column(x, z + 1));
            }
            assertArrayEquals(array(expected), KeySets.columnFaces(array(columns)), "round " + round);
        }
    }

    @Test
    void unionAndOfGiveSortedSets() {
        Random random = new Random(3);
        for (int round = 0; round < 200; round++) {
            TreeSet<Long> a = new TreeSet<>(), b = new TreeSet<>();
            for (int i = random.nextInt(30); i > 0; i--) a.add(random.nextLong() % 50);
            for (int i = random.nextInt(30); i > 0; i--) b.add(random.nextLong() % 50);
            TreeSet<Long> both = new TreeSet<>(a);
            both.addAll(b);
            assertArrayEquals(array(both), KeySets.union(array(a), array(b)));
            long[] raw = new long[a.size() + b.size()];
            int i = 0;
            for (long v : b) raw[i++] = v;
            for (long v : a) raw[i++] = v;
            assertArrayEquals(array(both), KeySets.of(raw));
        }
    }
}
