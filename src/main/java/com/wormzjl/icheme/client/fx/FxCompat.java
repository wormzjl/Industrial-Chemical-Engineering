package com.wormzjl.icheme.client.fx;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The effects system's view of the optional mods it talks to (CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.2 and 4.8).
 * The core sees only the small interfaces here, never a type of the other mod: those live in
 * {@code client/fx/compat/<mod>/} and are loaded by name ({@link #load}) only when the mod is present, inside a
 * try/catch, and only kept when their runtime version gate ({@link Integration#compatible()}) passes. Otherwise the
 * fallback, a no-op, stands for the session. {@code neoforge.mods.toml} sets no version range, so no version of either mod
 * stops the game from starting.
 */
public final class FxCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("icheme/fx");
    public static final String IRIS = "iris";
    static final String IRIS_BRIDGE = "com.wormzjl.icheme.client.fx.compat.iris.IrisBridge";

    private static volatile Iris iris;

    private FxCompat() {
    }

    /** What every integration answers before the core keeps it. */
    public interface Integration {
        /** The runtime version gate: false keeps the fallback. Must not throw (guard the call). */
        boolean compatible();

        /** One line for the log and the debug screen: the mod's version, the API revision, whether it is off. */
        String status();
    }

    /** The shader loader. Read once per frame through {@link FxPath}. */
    public interface Iris extends Integration {
        /** No Iris: no shader pack, no shadow pass, and the answer is certain. */
        Iris NONE = new Fixed(true, "Iris absent");
        /** Iris present but its bridge did not load or failed its gate: the answer is unknown. */
        Iris UNKNOWN = new Fixed(false, "Iris present, integration off");

        /** A shader pack is in use: our own shaders would draw wrong or vanish. */
        boolean packInUse();

        /** Iris is drawing its shadow map: no effect work. */
        boolean shadowPass();

        /** The answers above are trustworthy: false once the bridge failed, so the COMPATIBLE path is used. */
        boolean known();

        @Override
        default boolean compatible() {
            return true;
        }

        record Fixed(boolean known, String status) implements Iris {
            @Override
            public boolean packInUse() {
                return false;
            }

            @Override
            public boolean shadowPass() {
                return false;
            }
        }
    }

    /** The Iris integration: loaded on the first call (client frames only), {@link Iris#NONE} without Iris. */
    public static Iris iris() {
        var current = iris;
        if (current != null) return current;
        synchronized (FxCompat.class) {
            if (iris == null) iris = loaded(IRIS) ? load(IRIS, IRIS_BRIDGE, Iris.class, Iris.UNKNOWN) : Iris.NONE;
            return iris;
        }
    }

    /**
     * The implementation {@code implClassName} of {@code iface} when {@code modId} is loaded and the implementation passes its
     * version gate; {@code fallback} otherwise. Never throws: a missing class, a failing constructor or a broken gate
     * all give the fallback, with one log line.
     */
    public static <T extends Integration> T load(String modId, String implClassName, Class<T> iface, T fallback) {
        return load(FxCompat::loaded, modId, implClassName, iface, fallback, LOGGER::warn);
    }

    /** {@link #load} with the mod list and the log replaced (tests). */
    static <T extends Integration> T load(Predicate<String> loaded, String modId, String implClassName, Class<T> iface, T fallback,
                                          BiConsumer<String, Throwable> log) {
        try {
            if (!loaded.test(modId)) return fallback;
        } catch (LinkageError | RuntimeException e) {
            return fallback;
        }
        try {
            Class<?> type = Class.forName(implClassName, true, FxCompat.class.getClassLoader());
            if (!iface.isAssignableFrom(type)) {
                log.accept("IChemE effects: " + implClassName + " is not a " + iface.getSimpleName() + "; the " + modId + " integration is off", null);
                return fallback;
            }
            T integration = iface.cast(type.getDeclaredConstructor().newInstance());
            boolean compatible;
            String status;
            try {
                compatible = integration.compatible();
                status = integration.status();
            } catch (LinkageError | RuntimeException e) {
                log.accept("IChemE effects: the " + modId + " (" + version(modId) + ") version check failed; the integration is off", e);
                return fallback;
            }
            if (!compatible) {
                log.accept("IChemE effects: " + status + " fails the version gate (older than the API this build compiles against, or broken); the " + modId + " integration is off", null);
                return fallback;
            }
            return integration;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log.accept("IChemE effects: the " + modId + " (" + version(modId) + ") integration did not load; it is off", e);
            return fallback;
        }
    }

    /** Whether {@code modId} is loaded; false outside a running game. */
    public static boolean loaded(String modId) {
        try {
            var mods = ModList.get();
            return mods != null && mods.isLoaded(modId);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** The loaded version of {@code modId}, for log lines; {@code "?"} when unknown. */
    public static String version(String modId) {
        try {
            var mods = ModList.get();
            if (mods == null) return "?";
            return mods.getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        } catch (LinkageError | RuntimeException e) {
            return "?";
        }
    }
}
