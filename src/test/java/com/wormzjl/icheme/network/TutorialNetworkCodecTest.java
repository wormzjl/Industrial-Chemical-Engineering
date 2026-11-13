package com.wormzjl.icheme.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The field guide's wire, protocol tutorial-1 (batch 2026-11-12-tutorial-system, P1): every payload round-trips. */
class TutorialNetworkCodecTest {
    private static <T> T roundTrip(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T payload) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        codec.encode(buffer, payload);
        var back = codec.decode(buffer);
        assertEquals(0, buffer.readableBytes(), "read whole");
        return back;
    }

    @Test
    void everyPayloadRoundTrips() {
        assertEquals(new TutorialNetwork.StepDone("welcome/units"), roundTrip(TutorialNetwork.StepDone.STREAM_CODEC, new TutorialNetwork.StepDone("welcome/units")));
        assertEquals(new TutorialNetwork.Hello(), roundTrip(TutorialNetwork.Hello.STREAM_CODEC, new TutorialNetwork.Hello()));
        var progress = new TutorialNetwork.Progress(Set.of("welcome/guide", "still/build"));
        assertEquals(progress, roundTrip(TutorialNetwork.Progress.STREAM_CODEC, progress));
        assertEquals(new TutorialNetwork.Progress(Set.of()), roundTrip(TutorialNetwork.Progress.STREAM_CODEC, new TutorialNetwork.Progress(Set.of())));
    }

    /** Code review finding 6: one Hello a player a second is answered; another player, or the next second, is. */
    @Test
    void aHelloIsAnsweredAtMostOnceASecond() {
        var heard = new java.util.HashMap<String, Integer>();
        assertTrue(TutorialNetwork.admit(heard, "a", 100), "the first");
        assertFalse(TutorialNetwork.admit(heard, "a", 100 + TutorialNetwork.HELLO_TICKS - 1), "within the second");
        assertTrue(TutorialNetwork.admit(heard, "b", 101), "another player");
        assertTrue(TutorialNetwork.admit(heard, "a", 100 + TutorialNetwork.HELLO_TICKS), "the next second");
    }

    @Test
    void theNetworkNamesNoClientClassButItsHandlersLambda() throws Exception {
        var source = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/network/TutorialNetwork.java"));
        assertFalse(source.contains("import com.wormzjl.icheme.client"), "no client import");
        assertFalse(source.contains("import net.minecraft.client"), "no client import");
        assertEquals(1, source.split("icheme\\.client\\.", -1).length - 1, "the client handler, inside its lambda, alone");
        var triggers = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/runtime/tutorial/TutorialTriggers.java"));
        assertFalse(triggers.contains("icheme.client") || triggers.contains("net.minecraft.client"), "the triggers load on a dedicated server");
    }
}
