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
    }

    /** Chooses this frame's path from the client config, Iris and the shader state. */
    static void refresh() {
        current = choose(ClientConfig.effectsMode(), FxCompat.iris(), FxShaders.failed());
    }
}
