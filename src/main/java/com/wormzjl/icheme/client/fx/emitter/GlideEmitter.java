package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxEmitter;
import com.wormzjl.icheme.client.fx.FxGlide;
import com.wormzjl.icheme.client.fx.FxKind;
import com.wormzjl.icheme.client.fx.FxSink;

import java.util.Arrays;

/**
 * One effect of a watched structure (batch 2026-11-10-custom-shader-system, plan section 4.1): a vector of values glided
 * between the snapshots ({@link FxGlide}) and a geometry that writes its quads eased from the glide's start to its target.
 * Its source ({@link FxSources}) aims it at each new snapshot; every aim at new values starts a glide and a new
 * {@link #revision()}, so the SHADER path rebuilds its buffer only then. Render thread only.
 */
final class GlideEmitter implements FxEmitter {
    /** Writes at most {@code quads} quads eased from {@code from} to {@code to}. */
    interface Geometry {
        void write(FxSink sink, double[] from, double[] to, int quads);
    }

    private final String type;
    private final FxKind kind;
    private final double x, y, z, period;
    private double growth;
    private final Geometry geometry;
    private final FxGlide glide;
    private boolean alive = true;
    private long revision;
    private int quads;
    private double radius, aimed;

    /**
     * An emitter at {@code x, y, z} (world, m), resting at {@code rest} (a flame of no length, a plume of no density) until
     * its first aim, from which it grows.
     */
    GlideEmitter(String type, FxKind kind, double x, double y, double z, double period, double[] rest, Geometry geometry) {
        this.type = type;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.period = period;
        this.geometry = geometry;
        this.glide = new FxGlide(rest);
    }

    /**
     * Aims at {@code target} at {@code now} (s of game time), with {@code quads} quads at full density and {@code radius} m
     * about the origin: the same values change nothing; new ones start a glide from where the effect stands.
     */
    void aim(double[] target, int quads, double radius, double now) {
        if (quads == this.quads && Arrays.equals(target, glide.to())) return;
        glide.arrive(target, now);
        this.quads = quads;
        // The old shape still shows while the glide runs: the radius covers both.
        this.radius = Math.max(radius, aimed);
        this.aimed = radius;
        revision++;
    }

    void kill() {
        alive = false;
    }

    double[] target() {
        return glide.to();
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public FxKind kind() {
        return kind;
    }

    @Override
    public boolean alive() {
        return alive;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public double radius() {
        return radius;
    }

    @Override
    public int quads() {
        return quads;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public double glideStart() {
        return glide.start();
    }

    @Override
    public double driftPeriod() {
        return period;
    }

    @Override
    public double puffGrowth() {
        return growth;
    }

    /** Sets {@link #puffGrowth()}, m per period: set with each aim of a plume, from its segment length. */
    void growth(double metres) {
        growth = Double.isFinite(metres) ? Math.max(0, metres) : 0;
    }

    @Override
    public void write(FxSink sink, int quads) {
        geometry.write(sink, glide.from(), glide.to(), quads);
    }
}
