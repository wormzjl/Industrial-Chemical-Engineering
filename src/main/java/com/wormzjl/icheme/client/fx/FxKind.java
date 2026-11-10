package com.wormzjl.icheme.client.fx;

/**
 * What an emitter's quads are, which picks the shader and the vanilla render type that draw them
 * ({@link FxRenderTypes}) and the meaning of each quad's parameter bytes ({@link FxSink}).
 */
public enum FxKind {
    /** Flame ribbons: {@code fx_flame} / {@code entityTranslucentEmissive} on the flame flipbook, full bright. */
    FLAME,
    /** Smoke puffs: {@code fx_plume} / {@code entityTranslucentCull} on the smoke texture. */
    SMOKE,
    /** Condensed-water plume puffs: {@code fx_plume} / {@code entityTranslucentCull} on the plume texture. */
    PLUME
}
