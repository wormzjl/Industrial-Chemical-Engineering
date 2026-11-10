package com.wormzjl.icheme.client.fx;

import com.wormzjl.icheme.IChemE;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * The effects' always-on counters (batch 2026-11-10-custom-shader-system, FX_BENCH_PLAN.md): increments and stores of
 * numbers only, no timers that allocate and no per-frame allocation. Client and render thread only (the same thread in
 * Minecraft). Two scopes:
 * <ul>
 * <li><b>Frame</b> counters ({@link Scope#FRAME}) add up during one frame ({@link #add}); {@link #frame} at the frame's
 * start keeps them as the last frame's ({@link #last}) and starts the next from zero. Each also adds to its total since the
 * session started ({@link #total}), so a bench window reads totals at its two ends.</li>
 * <li><b>Gauges</b> ({@link Scope#GAUGE}) hold the value last stored ({@link #set}) or moved ({@link #add}): sizes, bytes,
 * loops, the last sound tick's time.</li>
 * </ul>
 * One F3 line on the right ({@link #debugLine}): the path, the last frame's quads and emitters, the loops playing. The
 * bench (tools/fx-bench/) reads the counters each frame; it adds its timers and GPU queries from outside {@code src/main}.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class FxStats {
    /** Whether a counter adds up per frame or holds a value. */
    public enum Scope { FRAME, GAUGE }

    /** What is counted; the unit is in the name (ns, bytes) or a count. */
    public enum Counter {
        /** Quads the batch drew (SHADER: from cached buffers; COMPATIBLE: written this frame). */
        QUADS(Scope.FRAME),
        /** Emitters the batch drew. */
        EMITTERS(Scope.FRAME),
        /** Cached emitter buffers built again. */
        REBUILDS(Scope.FRAME),
        /** Render-thread time writing vertices, ns: buffer rebuilds (SHADER) or the per-frame writes (COMPATIBLE). */
        BUILD_NS(Scope.FRAME),
        /** Render-thread time of the whole effects draw, ns, its build included. */
        DRAW_NS(Scope.FRAME),
        /** Render-thread time of the instrument renderer (dials, sensors, wiring), ns. */
        WIRING_NS(Scope.FRAME),
        /** Structures' wiring built again (its drawing, not its upload). */
        WIRING_BUILDS(Scope.FRAME),
        /** Emitters registered with the batch. */
        REGISTERED(Scope.GAUGE),
        /** The effects' sources: one per watched structure ({@code FxSources}). */
        SOURCES(Scope.GAUGE),
        /** Bytes in the batch's cached vertex buffers. */
        VERTEX_BYTES(Scope.GAUGE),
        /** Bytes uploaded to the wiring's cached buffers (eviction is not subtracted: an upper bound). */
        WIRING_BYTES(Scope.GAUGE),
        /** Bytes of the effects' own render targets (the heat haze's screen copy). */
        TEXTURE_BYTES(Scope.GAUGE),
        /** Silhouette boxes registered with Distant Horizons. */
        SILHOUETTE_BOXES(Scope.GAUGE),
        /** Silhouette records the client holds. */
        SILHOUETTES(Scope.GAUGE),
        /** Process states the client's watches hold (flares, stills, towers, heaters). */
        STATES(Scope.GAUGE),
        /** Noise sources the last noise answer brought. */
        NOISE(Scope.GAUGE),
        /** Loops playing. */
        LOOPS(Scope.GAUGE),
        /** Sound emitters gathered at the last choice. */
        SOUND_SOURCES(Scope.GAUGE),
        /** Groups those merged into at the last choice. */
        SOUND_GROUPS(Scope.GAUGE),
        /** The last client tick's sound work, ns. */
        SOUND_NS(Scope.GAUGE);

        public final Scope scope;

        Counter(Scope scope) {
            this.scope = scope;
        }
    }

    /** The counters, once: {@code values()} copies its array at every call. */
    private static final Counter[] COUNTERS = Counter.values();
    private static final int N = COUNTERS.length;
    private static final long[] NOW = new long[N], LAST = new long[N], TOTAL = new long[N];
    private static long frames;
    private static FxPath path = FxPath.COMPATIBLE;

    private FxStats() {
    }

    /** Adds {@code amount} to {@code counter}: this frame's and the total for a frame counter, the value for a gauge. */
    public static void add(Counter counter, long amount) {
        int i = counter.ordinal();
        NOW[i] += amount;
        if (counter.scope == Scope.FRAME) TOTAL[i] += amount;
    }

    /** Stores {@code value} in gauge {@code counter}. */
    public static void set(Counter counter, long value) {
        if (counter.scope != Scope.GAUGE) throw new IllegalArgumentException(counter + " is a frame counter");
        NOW[counter.ordinal()] = value;
    }

    /** A frame starts on {@code current}: the frame counters become the last frame's and start again from zero. */
    public static void frame(FxPath current) {
        path = current;
        frames++;
        for (var c : COUNTERS) {
            if (c.scope != Scope.FRAME) continue;
            LAST[c.ordinal()] = NOW[c.ordinal()];
            NOW[c.ordinal()] = 0;
        }
    }

    /** This frame so far (a frame counter) or the value (a gauge). */
    public static long now(Counter counter) {
        return NOW[counter.ordinal()];
    }

    /** The last whole frame's (a frame counter) or the value (a gauge). */
    public static long last(Counter counter) {
        return counter.scope == Scope.FRAME ? LAST[counter.ordinal()] : NOW[counter.ordinal()];
    }

    /** A frame counter's total since the session started (or {@link #reset}); a gauge's value. */
    public static long total(Counter counter) {
        return counter.scope == Scope.FRAME ? TOTAL[counter.ordinal()] : NOW[counter.ordinal()];
    }

    /** Frames started since the session started. */
    public static long frames() {
        return frames;
    }

    /** The path of the frame that runs. */
    public static FxPath path() {
        return path;
    }

    /** The F3 line: path, the last frame's quads and emitters, the loops playing. */
    public static String debugLine() {
        return "IChemE fx: " + path + ", " + last(Counter.QUADS) + " quads, " + last(Counter.EMITTERS) + " emitters, " + last(Counter.LOOPS) + " loops";
    }

    /** Every counter to zero (tests, and a client that left the world keeps its totals otherwise). */
    public static void reset() {
        java.util.Arrays.fill(NOW, 0);
        java.util.Arrays.fill(LAST, 0);
        java.util.Arrays.fill(TOTAL, 0);
        frames = 0;
        path = FxPath.COMPATIBLE;
    }

    @SubscribeEvent
    public static void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        event.getRight().add(debugLine());
    }
}
