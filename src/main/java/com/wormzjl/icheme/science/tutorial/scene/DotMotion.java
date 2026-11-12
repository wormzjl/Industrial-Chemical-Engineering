package com.wormzjl.icheme.science.tutorial.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each dot of a {@link Track.Dots} track is: a pure function of its kind, its index and the time, by a fixed hash
 * and plain arithmetic (no {@code Random}, no transcendental function), so every runtime places it to the bit. A dot's
 * drift depends on its kind and index, not on its track: a dot drifting in a box at a track's end drifts on in the next
 * track's source box.
 */
public final class DotMotion {
    /** A leaving dot's offset from its line ramps in over this distance after the line's first point, m. */
    static final double RAMP = .2;

    private DotMotion() {
    }

    /** Adds the drawn dots of {@code track} at {@code t} to {@code out}. */
    static void place(Track.Dots track, double t, List<Frame.DotFrame> out) {
        int k = track.kind().ordinal();
        for (int i = 0; i < track.count(); i++) {
            var at = switch (track.path()) {
                case Track.Box box -> drift(box, k, i, t, track.speed());
                case Track.Line line -> along(line, k, i, t, track);
            };
            if (at != null) out.add(new Frame.DotFrame(at[0], at[1], at[2], track.kind(), Track.Dots.SIZE));
        }
    }

    /** A dot drifting in {@code box}, bouncing off its walls; each axis at its own speed, 0.4 to 1 of {@code speed}. */
    public static double[] drift(Track.Box box, int kind, int index, double t, double speed) {
        double[] p = new double[3];
        for (int a = 0; a < 3; a++) {
            double span = box.hi()[a] - box.lo()[a];
            if (span <= 0) {
                p[a] = box.lo()[a];
                continue;
            }
            double v = speed * (.4 + .6 * unit(kind, index, 3 + a));
            double u = unit(kind, index, a) + t * v / (2 * span);
            double f = u - Math.floor(u);
            p[a] = box.lo()[a] + span * (1 - Math.abs(2 * f - 1));
        }
        return p;
    }

    /** A dot on a line ({@link Track.Line}), null when it is not drawn. */
    static double[] along(Track.Line line, int kind, int index, double t, Track.Dots track) {
        double start = track.from() + index * line.gap();
        if (index >= line.leaving() || t <= start) return line.source() == null ? null : drift(line.source(), kind, index, t, line.drift());
        var points = path(line, kind, index, track);
        double s = (t - start) * track.speed();
        double first = distance(points.get(0), points.get(1)), walked = 0;
        for (int j = 0; j + 1 < points.size(); j++) {
            var a = points.get(j);
            var b = points.get(j + 1);
            double l = distance(a, b);
            if (s <= walked + l) {
                double f = l > 0 ? (s - walked) / l : 0;
                double w = j == 0 ? 0 : Math.min(1, (s - first) / RAMP);
                var o = offset(line, kind, index);
                return new double[]{a[0] + (b[0] - a[0]) * f + w * o[0], a[1] + (b[1] - a[1]) * f + w * o[1], a[2] + (b[2] - a[2]) * f + w * o[2]};
            }
            walked += l;
        }
        return null;
    }

    /** A leaving dot's whole path: from its place in the source box at its start (where it has one), then the line. */
    public static List<double[]> path(Track.Line line, int kind, int index, Track.Dots track) {
        var points = new ArrayList<double[]>(line.points().size() + 1);
        if (line.source() != null) points.add(drift(line.source(), kind, index, track.from() + index * line.gap(), line.drift()));
        points.addAll(line.points());
        return points;
    }

    /** A dot's fixed offset from its line, each axis within radius / sqrt 3, so the offset is within the radius. */
    static double[] offset(Track.Line line, int kind, int index) {
        double r = line.radius() / Math.sqrt(3);
        return new double[]{r * (2 * unit(kind, index, 7) - 1), r * (2 * unit(kind, index, 8) - 1), r * (2 * unit(kind, index, 9) - 1)};
    }

    static double distance(double[] a, double[] b) {
        double x = b[0] - a[0], y = b[1] - a[1], z = b[2] - a[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** A fixed number in [0, 1) for a dot's kind, index and use (SplitMix64's finaliser). */
    static double unit(int kind, int index, int salt) {
        long h = 0x632BE59BD9B4E019L ^ (kind * 0x9E3779B97F4A7C15L) ^ (index * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
