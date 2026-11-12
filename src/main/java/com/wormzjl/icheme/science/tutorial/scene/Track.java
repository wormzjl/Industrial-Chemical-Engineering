package com.wormzjl.icheme.science.tutorial.scene;

import java.util.List;

/**
 * What changes over a film's time (batch 2026-11-12-tutorial-system, TUTORIAL_SYSTEM_PLAN.md section 3.1). A track whose
 * interval is {@code from} to {@code to} acts on (from, to]: at a stop the frame shows what led up to it; a track that
 * starts at 0 acts at 0 as well ({@link Scene#active}).
 */
public sealed interface Track permits Track.Camera, Track.Visible, Track.Cut, Track.Caption, Track.Dots {

    /**
     * The camera's keyframes, in ascending time: between two keyframes each value follows a smoothstep, before the first and
     * after the last it stands. Yaw 0 looks from the front (from -z toward +z), a positive yaw turns the camera toward -x
     * (the front's right), a positive pitch looks down; scale is pixels a metre at the design size; the target is the scene
     * point at the view's centre.
     */
    record Camera(List<Key> keys) implements Track {
        public Camera {
            keys = List.copyOf(keys);
            if (keys.isEmpty()) throw new IllegalArgumentException("A camera needs a keyframe");
            for (int i = 1; i < keys.size(); i++)
                if (keys.get(i).time() < keys.get(i - 1).time()) throw new IllegalArgumentException("Keyframes out of order");
        }

        public Frame.CameraState at(double t) {
            var first = keys.getFirst();
            if (t <= first.time()) return first.state();
            for (int i = 0; i + 1 < keys.size(); i++) {
                var a = keys.get(i);
                var b = keys.get(i + 1);
                if (t > b.time()) continue;
                if (b.time() <= a.time()) return b.state();
                double u = (t - a.time()) / (b.time() - a.time()), s = u * u * (3 - 2 * u);
                return new Frame.CameraState(lerp(a.yaw(), b.yaw(), s), lerp(a.pitch(), b.pitch(), s), lerp(a.scale(), b.scale(), s),
                        lerp(a.x(), b.x(), s), lerp(a.y(), b.y(), s), lerp(a.z(), b.z(), s));
            }
            return keys.getLast().state();
        }

        private static double lerp(double a, double b, double s) {
            return a + (b - a) * s;
        }
    }

    /** One keyframe: the time, yaw and pitch (radians), scale (px/m at the design size), the target point (m). */
    record Key(double time, double yaw, double pitch, double scale, double x, double y, double z) {
        public Frame.CameraState state() {
            return new Frame.CameraState(yaw, pitch, scale, x, y, z);
        }
    }

    /** An actor shown over (from, to], fading in over {@code fade} seconds after {@code from} and out before {@code to}, unless {@code to} is the film's end. */
    record Visible(String actor, double from, double to, double fade) implements Track {
    }

    /**
     * A cutaway of {@code actor}: the plane through {@code point} with {@code normal} (pointing at the part removed, so a
     * normal toward the camera keeps the far half) moves from outside the actor's bounds to its place over {@code sweep}
     * seconds after {@code from}, in {@link #STEPS} steps; the cut stays to the film's end.
     */
    record Cut(String actor, double[] point, double[] normal, double from, double sweep) implements Track {
        /** The plane's steps from outside the actor to its place. */
        public static final int STEPS = 8;

        public Cut {
            point = point.clone();
            double l = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            if (!(l > 0)) throw new IllegalArgumentException("A cut needs a normal");
            normal = new double[]{normal[0] / l, normal[1] / l, normal[2] / l};
        }

        /** The step at {@code t}: 0 before the cut starts (the whole actor), then 1 to {@link #STEPS} (the plane in place). */
        public int step(double t) {
            if (t <= from) return 0;
            if (sweep <= 0) return STEPS;
            return (int) Math.min(STEPS, 1 + Math.floor((t - from) / sweep * STEPS));
        }
    }

    /** A caption's language key, shown over (from, to]. */
    record Caption(String key, double from, double to) implements Track {
    }

    /** Dots of one {@code kind} over (from, to]: {@code count} of them, moving at {@code speed} m/s on their {@code path}. */
    record Dots(Kind kind, Path path, double from, double to, int count, double speed) implements Track {
        /** A dot's radius, m. */
        public static final double SIZE = .035;

        public Dots {
            if (count < 0) throw new IllegalArgumentException("Negative count");
        }
    }

    /** The three kinds of molecule the films draw, each its fixed colour (RGB) in the game and the tool. */
    enum Kind {
        LIGHT(0x56B4E9), MIDDLE(0xF0E442), HEAVY(0xCC79A7);

        public final int rgb;

        Kind(int rgb) {
            this.rgb = rgb;
        }
    }

    /** Where dots move: along a line, or drifting in a box. */
    sealed interface Path permits Line, Box {
    }

    /** A box the dots drift in, bouncing off its walls; low corner and high corner. */
    record Box(double[] lo, double[] hi) implements Path {
        public Box {
            lo = lo.clone();
            hi = hi.clone();
            for (int a = 0; a < 3; a++) if (hi[a] < lo[a]) throw new IllegalArgumentException("Box corners out of order");
        }

        public boolean contains(double x, double y, double z, double tolerance) {
            return x >= lo[0] - tolerance && x <= hi[0] + tolerance && y >= lo[1] - tolerance && y <= hi[1] + tolerance && z >= lo[2] - tolerance && z <= hi[2] + tolerance;
        }
    }

    /**
     * A line the first {@code leaving} dots travel once, each {@code gap} seconds after the one before, each within
     * {@code radius} of the line; a dot that reaches its end is gone. Before its start, and for the dots that do not leave,
     * a dot drifts in {@code source} at {@code drift} m/s (none: it is not drawn); a leaving dot first runs from its place there to the line's
     * first point.
     */
    record Line(List<double[]> points, double radius, Box source, int leaving, double gap, double drift) implements Path {
        public Line {
            points = points.stream().map(double[]::clone).toList();
            if (points.size() < 2) throw new IllegalArgumentException("A line needs two points");
        }
    }
}
