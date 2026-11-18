package com.wormzjl.icheme.client.fx;

import com.wormzjl.icheme.client.ClientConfig;

/**
 * The render path of this frame (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.1). Chosen once per frame by
 * {@link FxBatch} at the frame's start and read by every effect and the instrument wiring through {@link #current()}.
 * Our own shaders run only when Iris is confirmed absent or confirmed to have no shader pack (section 4.8, rule 4).
 */
public enum FxPath {
    /** The mod's own core shaders: GPU-animated cached buffers. */
    SHADER,
    /** Vanilla entity render types, vertices written every frame: correct with or without a shader pack. */
    COMPATIBLE,
    /** Nothing drawn. */
    OFF;

    private static volatile FxPath current = COMPATIBLE;
    /** This frame's path per effect kind (render thread): {@link #current} where it is not SHADER, else its program's ({@link FxShaders#path}). */
    private static final FxPath[] KINDS = new FxPath[FxKind.values().length];

    static {
        java.util.Arrays.fill(KINDS, COMPATIBLE);
    }

    /**
     * This frame's path for {@code kind}: the frame's path, or COMPATIBLE where its shader program's switch is off or it
     * failed to load (flames and smoke can differ; a haze off is not drawn).
     */
    public static FxPath of(FxKind kind) {
        return KINDS[kind.ordinal()];
    }

    /** This frame's path; COMPATIBLE before the first frame. */
    public static FxPath current() {
        return current;
    }

    /**
     * The path for a mode, the Iris answer and whether our shaders failed to load: OFF and COMPATIBLE as set; AUTO takes
     * COMPATIBLE when our shaders failed, when the Iris answer is unknown (bridge failed) or a pack is in use, else SHADER.
     */
    public static FxPath choose(FxMode mode, FxCompat.Iris iris, boolean shadersFailed) {
        if (mode == FxMode.OFF) return OFF;
        if (mode == FxMode.COMPATIBLE || shadersFailed) return COMPATIBLE;
        if (iris == null || !iris.known() || iris.packInUse()) return COMPATIBLE;
        return SHADER;
    }

    /** The choice itself failed: the path that draws correctly with or without a pack. */
    static void fallback() {
        current = COMPATIBLE;
        java.util.Arrays.fill(KINDS, COMPATIBLE);
    }

    /**
     * Chooses this frame's path from the client config and Iris, and each kind's from its shader program
     * ({@link FxShaderProgram}): a program that failed to load or is switched off puts only its own kind on COMPATIBLE.
     */
    static void refresh() {
        var all = choose(ClientConfig.effectsMode(), FxCompat.iris(), false);
        for (var kind : FxKind.VALUES) KINDS[kind.ordinal()] = all == SHADER ? FxShaders.path(FxShaderProgram.of(kind)) : all;
        current = all;
    }
}
