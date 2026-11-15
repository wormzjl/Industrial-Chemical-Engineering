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
    void aFieldCappedAtTheWorldsHeightGivesOutsideCubesToTheirColumnsEnds() {
        long above = CubeKey.of(2, 40, -3), top = CubeKey.of(2, 19, -3), below = CubeKey.of(2, -9, -3), bottom = CubeKey.of(2, -4, -3), inside = CubeKey.of(5, 0, 5);
        assertEquals(top, CubeKey.capped(above, -4, 19));
        assertEquals(bottom, CubeKey.capped(below, -4, 19));
        assertEquals(inside, CubeKey.capped(inside, -4, 19));
        long[] keys = {above, top, below, inside};
        java.util.Arrays.sort(keys);
        double[] amounts = new double[16];
        for (int i = 0; i < 4; i++) amounts[i * 4] = keys[i] == above ? 3 : keys[i] == top ? 2 : keys[i] == below ? 5 : 7;
        PollutionField field = PollutionField.of(keys, amounts);
        PollutionField capped = field.capped(-4, 19);
        assertEquals(3, capped.size());
        assertEquals(5.0, capped.amount(top, Channel.TOXIC));
        assertEquals(5.0, capped.amount(bottom, Channel.TOXIC));
        assertEquals(7.0, capped.amount(inside, Channel.TOXIC));
        assertEquals(field.stored(Channel.TOXIC), capped.stored(Channel.TOXIC));
        assertEquals(true, capped.capped(-4, 19) == capped, "a field inside the height is returned as it is");
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
