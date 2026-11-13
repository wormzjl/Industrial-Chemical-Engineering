package com.wormzjl.icheme.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire codecs' bounded reads (codebase review P3-23). A list on the wire is a VarInt count, then its elements. A
 * declared count is refused before anything is allocated: outside {@code 0..maximum}, or more elements than the
 * buffer still holds at their least size. Every refusal of malformed input is a {@link DecoderException}; valid input
 * reads the same bytes as the codecs' own loops did.
 */
public final class Wire {
    private Wire() {
    }

    /**
     * A declared list length: in {@code 0..maximum}, and the buffer still holds {@code minimumBytes} for each element
     * (0 where an element may take no byte).
     *
     * @param what the list's name in the refusal
     */
    public static int count(FriendlyByteBuf buffer, int maximum, int minimumBytes, String what) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Invalid " + what + " count " + count + " (0.." + maximum + ")");
        if (buffer.readableBytes() < (long) count * minimumBytes) throw new DecoderException("Truncated " + what + " list");
        return count;
    }

    /** A list of at most {@code maximum} doubles, 8 bytes each. */
    public static double[] doubles(FriendlyByteBuf buffer, int maximum, String what) {
        var values = new double[count(buffer, maximum, Double.BYTES, what)];
        for (int i = 0; i < values.length; i++) values[i] = buffer.readDouble();
        return values;
    }

    /** Writes what {@link #doubles(FriendlyByteBuf, int, String)} reads. */
    public static void doubles(FriendlyByteBuf buffer, double[] values) {
        buffer.writeVarInt(values.length);
        for (double value : values) buffer.writeDouble(value);
    }

    /** A list of at most {@code maximum} block positions, 8 bytes each (a packed long); a mutable list. */
    public static List<BlockPos> positions(FriendlyByteBuf buffer, int maximum, String what) {
        int count = count(buffer, maximum, Long.BYTES, what);
        var positions = new ArrayList<BlockPos>(count);
        for (int i = 0; i < count; i++) positions.add(buffer.readBlockPos());
        return positions;
    }

    /** Writes what {@link #positions(FriendlyByteBuf, int, String)} reads. */
    public static void positions(FriendlyByteBuf buffer, List<BlockPos> positions) {
        buffer.writeVarInt(positions.size());
        for (var position : positions) buffer.writeBlockPos(position);
    }

    /** {@code value}, refused unless finite. */
    public static double finite(double value, String what) {
        if (!Double.isFinite(value)) throw new DecoderException("Non-finite " + what);
        return value;
    }
}
