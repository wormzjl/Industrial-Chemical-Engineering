package com.wormzjl.icheme.client.fx;

/**
 * One effect source the batch draws: a flare's flame, its smoke, a tower's plume (CUSTOM_SHADER_SYSTEM_PLAN.md
 * section 4.1). Its owner keeps the state from the server's presentation snapshots, glides it ({@link FxGlide}) and
 * registers the emitter with {@link FxBatch#add}. Render thread only. Every method may be called each frame, so keep
 * them cheap; only {@link #write} builds geometry.
 * <p>A failure inside an emitter turns off its {@link #type()} for the session ({@link FxGuard#effect}), not the
 * whole system.
 */
public interface FxEmitter {
    /** Packed light for full bright (block and sky 15), as {@code LightTexture.FULL_BRIGHT}. */
    int FULL_BRIGHT = 0xF000F0;

    /** The effect type, one breaker per name: {@code "flare_flame"}, {@code "tower_plume"}. */
    String type();

    FxKind kind();

    /** False once the source is gone: the batch drops the emitter and frees its buffer. */
    boolean alive();

    /** The world position the emitter's quads are relative to, m. */
    double x();

    double y();

    double z();

    /** A radius about the origin that holds every quad, m: for frustum culling and the screen-size ranking. */
    double radius();

    /** Quads at full density; the batch may allot fewer. */
    int quads();

    /**
     * Changes whenever the geometry {@link #write} would produce changes: a new snapshot, a new glide. The SHADER path
     * rebuilds the emitter's cached buffer only then (or when its allotment moves by an eighth).
     */
    long revision();

    /** When the current glide started, s of game time ({@link FxGlide#seconds}); the shader eases from it. */
    double glideStart();

    /**
     * The period of this emitter's travelling puffs ({@link FxSink#puff}), s: fixed per emitter, so a new snapshot never
     * jumps their phase. The SHADER path passes it as the per-draw uniform {@code DriftPeriod}.
     */
    default double driftPeriod() {
        return 2;
    }

    /**
     * What one {@link #driftPeriod()} adds to each travelling puff's half-size, m: a plume's spread over the length a puff
     * travels, so a puff reaches the next segment's size as it gets there and a trail widens smoothly (visual review
     * 2026-11-10, item 5). The SHADER path passes it as the per-draw uniform {@code PuffGrowth}. Drips and sprites: none.
     */
    default double puffGrowth() {
        return 0;
    }

    /** Packed light for the COMPATIBLE path; flames are full bright. */
    default int light() {
        return FULL_BRIGHT;
    }

    /** Writes at most {@code quads} quads, most important first. */
    void write(FxSink sink, int quads);
}
