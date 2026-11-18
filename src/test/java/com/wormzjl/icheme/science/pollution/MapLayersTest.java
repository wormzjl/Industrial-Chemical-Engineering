package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The map's two layers (batch 2026-11-18-ground-pollution, G3): the union of the air's drawn columns and the ground's,
 * each with the air's four bytes and the ground's one, picked and ordered around the player by the one distance rule.
 */
class MapLayersTest {
    private static int packed(Channel channel, int value) {
        return value << 8 * channel.ordinal();
    }

    @Test void theLayersAreTheUnionWithBothBytes() {
        long a = MapColumns.column(0, 0), b = MapColumns.column(1, 0), c = MapColumns.column(5, 5);
        var air = new MapColumns.Columns(new long[] {a, b}, new int[] {packed(Channel.TOXIC, 30), packed(Channel.CORROSIVE, 6)});
        var deposits = List.of(GroundField.Deposit.of(b, 0.5 * GroundField.GROUND_REFERENCE),
                GroundField.Deposit.of(c, 2 * GroundField.GROUND_REFERENCE),
                GroundField.Deposit.of(MapColumns.column(9, 9), 0.01 * GroundField.GROUND_REFERENCE));
        var ground = GroundSnapshot.of(GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, deposits, GroundEnvironment.open(false), 1e-9,
                GroundSettings.DEFAULT.withoutSpread()).field(), GroundSettings.DEFAULT);
        var layers = MapColumns.layers(air, ground);
        assertArrayEquals(new long[] {a, b, c}, layers.columns(), "a ground column below the draw floor (index 0.01) is left out");
        assertEquals(packed(Channel.TOXIC, 30), layers.air()[0]);
        assertEquals(0, layers.ground()[0]);
        assertEquals(25, layers.ground()[1], "index 0.5 is byte 25");
        assertEquals(0, layers.air()[2], "a ground-only column has no air bytes");
        assertEquals(100, layers.ground()[2]);
        var near = MapColumns.around(layers, 1, 0, 3, 10);
        assertArrayEquals(new long[] {b, a}, near.columns(), "nearest first; (5, 5) is out of reach");
        assertEquals(layers.ground()[1], near.ground()[0]);
        assertEquals(layers.air()[0], near.air()[1]);
        assertEquals(MapColumns.groundArgb(25), MapColumns.bothArgb(layers.air()[1], layers.ground()[1]), "ground 0.50 over the air's corrosive 0.12");
        assertEquals(MapColumns.argb(layers.air()[0]), MapColumns.bothArgb(layers.air()[0], layers.ground()[0]), "no ground: the air");
    }
}
