package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.client.gui.common.kit.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a frame's labels stand (batch 2026-11-12-tutorial-system, P2b): each beside its point, inside the glass, never
 * over another label, another label's point, or what the view keeps for itself (the legend, the readouts, the pointers);
 * as little as it can over what the view would rather keep clear (the drawn equipment). Candidates stand at growing
 * distances in {@link #DIRECTIONS} directions round the point; the nearest with the least of the avoided area wins.
 * Labels are placed in their order, so the first ones stand nearest. A pure function; no Minecraft type.
 */
public final class Labels {
    /** Directions tried round a point; distances tried, GUI px from the point to the label's near side. */
    static final int DIRECTIONS = 16;
    static final int[] DISTANCES = {6, 10, 15, 21, 28, 36, 46, 58, 72, 90, 110, 135};
    /** Room kept between a label and the glass's edge, another label or a kept area, px. */
    public static final int GAP = 2;
    /** The weight of a pixel over an avoided area against a pixel of leader. */
    static final double AVOID = 4;

    private Labels() {
    }

    /** A label to place: its point on the screen (GUI px) and its box's width and height. */
    public record Anchor(double x, double y, int w, int h) {
    }

    /**
     * Each anchor's box (null where none fits), in order: inside {@code glass} by {@link #GAP}, clear of {@code keep} and of
     * the boxes placed before it by {@link #GAP}, holding no other anchor's point, and over {@code avoid} as little as it can.
     */
    public static List<Rect> place(List<Anchor> anchors, Rect glass, List<Rect> keep, List<Rect> avoid) {
        var placed = new ArrayList<Rect>();
        for (int k = 0; k < anchors.size(); k++) {
            var a = anchors.get(k);
            Rect best = null;
            double score = Double.MAX_VALUE;
            for (int d : DISTANCES)
                for (int i = 0; i < DIRECTIONS; i++) {
                    double angle = 2 * Math.PI * i / DIRECTIONS, c = DeterministicMath.cos(angle), s = DeterministicMath.sin(angle);
                    // The box's centre moves out so its near side stands about d from the point.
                    double reach = d + Math.min(Math.abs(c) > 1e-9 ? a.w() / 2.0 / Math.abs(c) : Double.MAX_VALUE, Math.abs(s) > 1e-9 ? a.h() / 2.0 / Math.abs(s) : Double.MAX_VALUE);
                    var box = new Rect((int) Math.round(a.x() + c * reach - a.w() / 2.0), (int) Math.round(a.y() + s * reach - a.h() / 2.0), a.w(), a.h());
                    if (!fits(box, k, anchors, glass, keep, placed)) continue;
                    double over = 0;
                    for (var r : avoid) over += overlap(box, r);
                    double value = d + AVOID * over / Math.max(1, a.h());
                    if (value < score - 1e-9) {
                        score = value;
                        best = box;
                    }
                }
            placed.add(best);
        }
        return placed;
    }

    private static boolean fits(Rect box, int self, List<Anchor> anchors, Rect glass, List<Rect> keep, List<Rect> placed) {
        if (!grown(box, GAP).inside(glass)) return false;
        var room = grown(box, GAP);
        for (var r : keep) if (room.overlaps(r)) return false;
        for (var r : placed) if (r != null && room.overlaps(r)) return false;
        for (int j = 0; j < anchors.size(); j++) if (j != self && room.contains(anchors.get(j).x(), anchors.get(j).y())) return false;
        return true;
    }

    public static Rect grown(Rect r, int by) {
        return new Rect(r.x() - by, r.y() - by, r.w() + 2 * by, r.h() + 2 * by);
    }

    /** The area two rectangles share, px². */
    public static int overlap(Rect a, Rect b) {
        int w = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x()), h = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
        return w > 0 && h > 0 ? w * h : 0;
    }

    /** The point of {@code box} nearest to (x, y): where a label's leader line meets it. */
    public static double[] nearest(Rect box, double x, double y) {
        return new double[]{Math.clamp(x, box.x(), box.right()), Math.clamp(y, box.y(), box.bottom())};
    }
}
