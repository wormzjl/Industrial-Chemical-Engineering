package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxSink;

import java.util.ArrayList;
import java.util.List;

/** An {@link FxSink} that records what an emitter writes, for the geometry tests. */
final class RecordingSink implements FxSink {
    record Ribbon(Point a, double va, Point b, double vb, int params, int seed) {
    }

    record Puff(Point start, double dx, double dy, double dz, int params, int seed) {
    }

    final List<Point> sprites = new ArrayList<>();
    final List<Ribbon> ribbons = new ArrayList<>();
    final List<Puff> puffs = new ArrayList<>();
    private int left;

    RecordingSink(int quads) {
        left = quads;
    }

    RecordingSink() {
        this(10_000);
    }

    int quads() {
        return sprites.size() + ribbons.size() + puffs.size();
    }

    @Override
    public void sprite(Point centre, int params, int seed) {
        if (left-- > 0) sprites.add(centre);
    }

    @Override
    public void puff(Point start, double dx, double dy, double dz, int params, int seed) {
        if (left-- > 0) puffs.add(new Puff(start, dx, dy, dz, params, seed));
    }

    @Override
    public void ribbon(Point a, double va, Point b, double vb, int params, int seed) {
        if (left-- > 0) ribbons.add(new Ribbon(a, va, b, vb, params, seed));
    }

    @Override
    public int remaining() {
        return Math.max(0, left);
    }
}
