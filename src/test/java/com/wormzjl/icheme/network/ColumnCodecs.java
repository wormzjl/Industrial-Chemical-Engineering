package com.wormzjl.icheme.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.function.Executable;

/**
 * The one reflection helper of the column codec tests.
 *
 * <p>The wire codecs are package statics. A decoder failure surfaces as an {@link InvocationTargetException};
 * {@link #failure} returns its cause.</p>
 */
public final class ColumnCodecs {
    private ColumnCodecs() {}

    public static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    /** The cause of the reflective failure {@code call} must raise. */
    public static Throwable failure(Executable call) {
        return assertThrows(InvocationTargetException.class, call).getCause();
    }

    public static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    public static void writeInput(RegistryFriendlyByteBuf buffer, V3ColumnInput input) throws Exception {
        invoke(ColumnWire.class, "writeInput",
                new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, input);
    }

    public static V3ColumnInput readInput(RegistryFriendlyByteBuf buffer) throws Exception {
        return (V3ColumnInput) invoke(ColumnWire.class, "readInput",
                new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
    }

    public static void writeResult(RegistryFriendlyByteBuf buffer, V3ColumnDisplayResult result) throws Exception {
        invoke(ColumnWire.class, "writeDisplayResult",
                new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
    }

    public static V3ColumnDisplayResult readResult(RegistryFriendlyByteBuf buffer) throws Exception {
        return (V3ColumnDisplayResult) invoke(ColumnWire.class, "readDisplayResult",
                new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
    }

    /** Wire round trip that also requires the decoder to consume every byte. */
    public static V3ColumnInput wireRoundTrip(V3ColumnInput input) throws Exception {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeInput(buffer, input);
            V3ColumnInput decoded = readInput(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    /** Wire round trip that also requires the decoder to consume every byte. */
    public static V3ColumnDisplayResult wireRoundTrip(V3ColumnDisplayResult result) throws Exception {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeResult(buffer, result);
            V3ColumnDisplayResult decoded = readResult(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
