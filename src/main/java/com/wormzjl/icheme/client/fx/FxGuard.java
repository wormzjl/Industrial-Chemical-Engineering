package com.wormzjl.icheme.client.fx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A breaker around calls that may fail because another mod changed, or because our own draw code has a defect
 * (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.8). A call that throws a {@link LinkageError} ({@code NoSuchMethodError},
 * {@code NoClassDefFoundError}, {@code AbstractMethodError}, {@code IncompatibleClassChangeError}) or a
 * {@link RuntimeException} opens the breaker: one warning names the integration and its mod version, and every later
 * call returns its fallback at once, without logging, until the game restarts. Nothing else is caught: an
 * {@code OutOfMemoryError} still propagates.
 * <p>One guard per optional integration ({@code new FxGuard("Iris", ...)}), and one per effect type for our own draw
 * code ({@link #effect}). Thread-safe; names no Minecraft class.
 */
public final class FxGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("icheme/fx");
    private static final Map<String, FxGuard> EFFECTS = new ConcurrentHashMap<>();

    private final String name;
    private final Supplier<String> version;
    private final BiConsumer<String, Throwable> log;
    private volatile boolean disabled;
    private volatile String failure = "";

    /** A guard for {@code name}, logging to the mod's effects logger; {@code version} names the other mod's version. */
    public FxGuard(String name, Supplier<String> version) {
        this(name, version, LOGGER::warn);
    }

    /** A guard that reports its one failure to {@code log} (tests count the calls). */
    public FxGuard(String name, Supplier<String> version, BiConsumer<String, Throwable> log) {
        this.name = name;
        this.version = version;
        this.log = log;
    }

    /** The breaker of one effect type of our own ({@code "flare_flame"}, {@code "tower_plume"}): one per name. */
    public static FxGuard effect(String type) {
        return EFFECTS.computeIfAbsent(type, t -> new FxGuard("effect " + t, () -> "IChemE"));
    }

    /** The result of {@code call}, or {@code fallback} once the guard is open or when the call fails. */
    public <T> T call(Supplier<T> call, T fallback) {
        if (disabled) return fallback;
        try {
            return call.get();
        } catch (LinkageError | RuntimeException e) {
            fail(e);
            return fallback;
        }
    }

    /** {@link #call} for a boolean, without boxing. */
    public boolean test(BooleanSupplier call, boolean fallback) {
        if (disabled) return fallback;
        try {
            return call.getAsBoolean();
        } catch (LinkageError | RuntimeException e) {
            fail(e);
            return fallback;
        }
    }

    /** Runs {@code call} unless the guard is open; whether it ran to the end. */
    public boolean run(Runnable call) {
        if (disabled) return false;
        try {
            call.run();
            return true;
        } catch (LinkageError | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Opens the guard without an exception (a version gate refused the integration); logs once. */
    public void disable(String reason) {
        report(reason, null);
    }

    /** Whether the guard is open: the integration or effect is off for the session. */
    public boolean disabled() {
        return disabled;
    }

    /** Why the guard opened, empty while it is closed. */
    public String failure() {
        return failure;
    }

    public String name() {
        return name;
    }

    private void fail(Throwable error) {
        report(String.valueOf(error), error);
    }

    private synchronized void report(String reason, Throwable error) {
        if (disabled) return;
        disabled = true;
        failure = reason;
        String named;
        try {
            named = String.valueOf(version.get());
        } catch (LinkageError | RuntimeException e) {
            named = "unknown version";
        }
        try {
            log.accept("IChemE effects: " + name + " (" + named + ") is off until the game restarts: " + reason, error);
        } catch (RuntimeException ignored) {
            // A broken logger must not take the frame with it.
        }
    }
}
