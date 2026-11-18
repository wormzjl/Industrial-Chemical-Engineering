package com.wormzjl.icheme.world.item;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

/** The respirator filter component's saved form (batch 2026-11-18-respirator): a capacity 0 to 600 round trips exactly. */
class RespiratorFilterCodecTest {
    @Test
    void roundTrip() {
        for (double remaining : new double[] {0, 0.1, 123.456789, 599.9999999, 600}) {
            var json = RespiratorFilterCodec.CODEC.encodeStart(JsonOps.INSTANCE, remaining).getOrThrow();
            assertEquals(remaining, RespiratorFilterCodec.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        }
    }

    @Test
    void outOfRangeIsRefused() {
        assertTrue(RespiratorFilterCodec.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(-1)).isError());
        assertTrue(RespiratorFilterCodec.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(600.5)).isError());
    }
}
