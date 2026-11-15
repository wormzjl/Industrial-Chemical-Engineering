package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The cube key packs as Minecraft 1.21.1's {@code SectionPos.asLong} and unpacks with sign extension. */
class CubeKeyTest {
    @Test
    void packingEqualsSectionPosLiterally() {
        assertEquals((1L << 42) | (3L << 20) | 2L, CubeKey.of(1, 2, 3));
        assertEquals(0L, CubeKey.of(0, 0, 0));
        assertEquals(-1L, CubeKey.of(-1, -1, -1), "all bits set for (-1, -1, -1)");
    }

    @Test
    void negativeAndPositiveCoordinatesRoundTrip() {
        int[] xs = {0, 1, -1, 1875000, -1875000, (1 << 21) - 1, -(1 << 21)};
        int[] ys = {0, 1, -1, -4, 19, -64, 63, (1 << 19) - 1, -(1 << 19)};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : xs) {
                    long key = CubeKey.of(x, y, z);
                    assertEquals(x, CubeKey.x(key), "x of " + x + "," + y + "," + z);
                    assertEquals(y, CubeKey.y(key), "y of " + x + "," + y + "," + z);
                    assertEquals(z, CubeKey.z(key), "z of " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void offsetMovesEachAxisAcrossZero() {
        long key = CubeKey.of(0, 0, 0);
        assertEquals(CubeKey.of(-1, 0, 0), CubeKey.offset(key, -1, 0, 0));
        assertEquals(CubeKey.of(0, -1, 0), CubeKey.offset(key, 0, -1, 0));
        assertEquals(CubeKey.of(0, 0, -1), CubeKey.offset(key, 0, 0, -1));
        assertEquals(CubeKey.of(5, -3, 7), CubeKey.offset(CubeKey.of(4, -2, 9), 1, -1, -2));
        long back = CubeKey.offset(CubeKey.offset(key, 3, -5, -7), -3, 5, 7);
        assertEquals(key, back);
    }
}
