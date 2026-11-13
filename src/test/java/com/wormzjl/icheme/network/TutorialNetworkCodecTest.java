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

    /**
     * Code review finding 9: the tutorial's server side loads on a dedicated server. Every class of {@code science.tutorial},
     * {@code runtime.tutorial} and {@code TutorialNetwork} loads and resolves its fields', methods' and constructors' types
     * (the science classes initialised too; the others reach the game's registries, which need its bootstrap) in a loader that refuses the client's classes ({@link ServerSide}); the client handler inside
     * {@code TutorialNetwork}'s lambda is never resolved there, as on a server.
     */
    @Test
    void theTutorialsServerSideLoadsWithNoClientClass() throws Exception {
        var root = Path.of(TutorialNetwork.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var names = new java.util.ArrayList<String>();
        for (var folder : java.util.List.of("com/wormzjl/icheme/science/tutorial", "com/wormzjl/icheme/runtime/tutorial"))
            try (var files = Files.walk(root.resolve(folder))) {
                files.filter(f -> f.toString().endsWith(".class")).forEach(f -> names.add(root.relativize(f).toString().replace('\\', '/').replace('/', '.').replaceAll("\\.class$", "")));
            }
        try (var files = Files.list(root.resolve("com/wormzjl/icheme/network"))) {
            files.map(f -> f.getFileName().toString()).filter(f -> f.equals("TutorialNetwork.class") || f.startsWith("TutorialNetwork$")).forEach(f -> names.add("com.wormzjl.icheme.network." + f.replaceAll("\\.class$", "")));
        }
        assertTrue(names.size() > 50, names.size() + " classes");
        try (var loader = new ServerSide()) {
            for (var name : names) {
                var type = Class.forName(name, name.startsWith("com.wormzjl.icheme.science."), loader);
                assertNotSame(type, Class.forName(name, false, TutorialNetworkCodecTest.class.getClassLoader()), name + " is the loader's own");
                type.getDeclaredFields();
                type.getDeclaredMethods();
                type.getDeclaredConstructors();
            }
        }
    }

    /**
     * A class loader over the test class path that refuses the client's classes ({@code com.wormzjl.icheme.client.},
     * {@code net.minecraft.client.}), but {@code SimulatorHost}: {@code SimulatorResults} names it on the server and it
     * holds no client type (the review's finding 8, left).
     */
    static final class ServerSide extends java.net.URLClassLoader {
        static final Set<String> ALLOWED = Set.of("com.wormzjl.icheme.client.gui.flowsheet.SimulatorHost");

        ServerSide() throws Exception {
            super(urls(), ClassLoader.getPlatformClassLoader());
        }

        private static java.net.URL[] urls() throws Exception {
            var urls = new java.util.ArrayList<java.net.URL>();
            for (var entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) urls.add(new java.io.File(entry).toURI().toURL());
            return urls.toArray(java.net.URL[]::new);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ((name.startsWith("com.wormzjl.icheme.client.") || name.startsWith("net.minecraft.client.")) && !ALLOWED.contains(name))
                throw new ClassNotFoundException("The tutorial's server side may not load " + name);
            return super.loadClass(name, resolve);
        }
    }
}
