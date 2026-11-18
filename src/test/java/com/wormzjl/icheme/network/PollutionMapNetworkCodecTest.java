package com.wormzjl.icheme.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.pollution.MapColumns;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** The pollution map's wire (WP9, protocol icheme-2): the request and the answer round-trip; a malformed answer is refused. */
class PollutionMapNetworkCodecTest {
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void theRequestRoundTrips() {
        for (boolean watching : new boolean[] {true, false}) {
            var buffer = buffer();
            PollutionMapNetwork.Request.STREAM_CODEC.encode(buffer, new PollutionMapNetwork.Request(watching));
            assertEquals(new PollutionMapNetwork.Request(watching), PollutionMapNetwork.Request.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes(), "read whole");
        }
    }

    @Test
    void theAnswerRoundTrips() {
        long[] columns = {MapColumns.column(0, 0), MapColumns.column(-3, 17), MapColumns.column(31, -32)};
        int[] values = {0x0000_0032, 0xFF00_0A05, 0x0119_0000};
        int[] ground = {0x0000_0000, 0x0005_0000, 0xFF00_00FF};
        var answer = new PollutionMapNetwork.Answer("minecraft:the_nether", false, columns, values, ground);
        var buffer = buffer();
        PollutionMapNetwork.Answer.STREAM_CODEC.encode(buffer, answer);
        var back = PollutionMapNetwork.Answer.STREAM_CODEC.decode(buffer);
        assertEquals(0, buffer.readableBytes(), "read whole");
        assertEquals("minecraft:the_nether", back.dimension());
        assertFalse(back.denied());
        assertArrayEquals(columns, back.columns(), "the order is kept: nearest first");
        assertArrayEquals(values, back.values());
        assertArrayEquals(ground, back.ground(), "the ground layer rides beside the air (batch 2026-11-18-ground-pollution)");

        var refused = PollutionMapNetwork.Answer.refused("minecraft:overworld");
        var again = buffer();
        PollutionMapNetwork.Answer.STREAM_CODEC.encode(again, refused);
        var read = PollutionMapNetwork.Answer.STREAM_CODEC.decode(again);
        assertTrue(read.denied());
        assertEquals(0, read.columns().length);
        assertEquals("minecraft:overworld", read.dimension());
    }

    @Test
    void aMalformedAnswerIsRefused() {
        // A count above the cap, before anything is allocated.
        var tooMany = buffer();
        tooMany.writeUtf("minecraft:overworld", PollutionMapNetwork.DIMENSION_MAX);
        tooMany.writeBoolean(false);
        tooMany.writeVarInt(MapColumns.MAXIMUM_ENTRIES + 1);
        assertThrows(DecoderException.class, () -> PollutionMapNetwork.Answer.STREAM_CODEC.decode(tooMany));
        // A count the buffer cannot hold.
        var truncated = buffer();
        truncated.writeUtf("minecraft:overworld", PollutionMapNetwork.DIMENSION_MAX);
        truncated.writeBoolean(false);
        truncated.writeVarInt(2);
        truncated.writeInt(1);
        assertThrows(DecoderException.class, () -> PollutionMapNetwork.Answer.STREAM_CODEC.decode(truncated));
        // A denied answer with columns.
        var denied = buffer();
        denied.writeUtf("minecraft:overworld", PollutionMapNetwork.DIMENSION_MAX);
        denied.writeBoolean(true);
        denied.writeVarInt(1);
        denied.writeInt(0);
        denied.writeInt(0);
        denied.writeInt(5);
        assertThrows(DecoderException.class, () -> PollutionMapNetwork.Answer.STREAM_CODEC.decode(denied));
        // And the record refuses what it cannot be.
        assertThrows(IllegalArgumentException.class, () -> new PollutionMapNetwork.Answer("d", false, new long[2], new int[1]));
        assertThrows(IllegalArgumentException.class, () -> new PollutionMapNetwork.Answer("d", true, new long[1], new int[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new PollutionMapNetwork.Answer("d", false, new long[MapColumns.MAXIMUM_ENTRIES + 1], new int[MapColumns.MAXIMUM_ENTRIES + 1]));
    }
}
