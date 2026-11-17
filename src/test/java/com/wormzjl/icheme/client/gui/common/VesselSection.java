package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.util.ArrayList;
import java.util.List;

/**
 * A vessel's section on the HMI glass (batch 2026-11-17-dynamic-settling, decision D6 C: "bars and section"): its outline,
 * a standing vessel upright with 2:1 elliptical heads or a lying drum on its side, and inside it from the top the gas, the
 * clear oil, the turbid band (a hatch of the oil's and the water's colours) and the clear water; each reading beside its
 * layer for a standing vessel, under the drum for a lying one, so the drum takes the glass's width. Minecraft-free, as
 * {@link HeatFlowsheet}: the layout is a pure method ({@link #layout}), which the gate checks and {@link #draw} draws.
 * Everything lies inside the box it is given.
 */
public final class VesselSection {
    /** The gas space's tint over the glass, and the glass under it. */
    static final int GAS = 0x50B3C0CA, GAS_GLASS = 0xFF1A2228;
    /** A standing vessel's width over its height; a lying drum's height over its length. */
    static final double SLENDER = .42;
    /** The gap between the vessel and its readings, a reading's swatch and the gap after it, the gap between readings in a row. */
    static final int GAP = 8, SWATCH = 7, SWATCH_GAP = 3, APART = 8;
    /** The smallest vessel worth drawing with its readings, px: below it the readings are left out (the door's gauges hold them). */
    static final int SMALLEST = 12;
    /** The layers, from the top: {@link Label#layer()}. */
    public static final int GAS_LAYER = 0, OIL_LAYER = 1, TURBID_LAYER = 2, WATER_LAYER = 3;

    private VesselSection() {
    }

    /**
     * What the section shows.
     *
     * @param lying  a lying drum; else a standing vessel
     * @param level  the liquid's share of the vessel's height, 0 to 1
     * @param oil    the clear oil's share of the liquid's height
     * @param turbid the turbid band's share of the liquid's height
     * @param water  the clear water's share of the liquid's height (the three sum to 1; any may be 0)
     * @param mist   drops in the gas space (a vessel over its gas limit)
     * @param labels the readings, each beside its layer
     */
    public record Layers(boolean lying, double level, double oil, double turbid, double water, boolean mist, List<Label> labels) {
        public Layers {
            labels = List.copyOf(labels);
            if (!(level >= 0 && level <= 1) || !(oil >= 0 && oil <= 1) || !(turbid >= 0 && turbid <= 1) || !(water >= 0 && water <= 1))
                throw new IllegalArgumentException("A layer's share outside 0 to 1");
            if (level > 0 && Math.abs(oil + turbid + water - 1) > 1e-6) throw new IllegalArgumentException("The liquid's layers do not sum to 1");
        }

        /** The layers' shares of the vessel's height, from the top: gas, clear oil, turbid band, clear water. */
        double[] heights() {
            return new double[]{1 - level, level * oil, level * turbid, level * water};
        }
    }

    /** A reading beside layer {@code layer} ({@link #GAS_LAYER} to {@link #WATER_LAYER}). */
    public record Label(String text, int layer) {
        public Label {
            if (layer < GAS_LAYER || layer > WATER_LAYER) throw new IllegalArgumentException("No layer " + layer);
        }
    }

    /** A reading as placed: its lines, its text's top left, its swatch ({@code swatch}; null beside a standing vessel, where a leader line points to its layer). */
    public record Text(Label label, List<String> lines, int x, int y, int w, @org.jetbrains.annotations.Nullable Rect swatch) {
        public Text {
            lines = List.copyOf(lines);
        }

        /** The rect its lines take. */
        public Rect rect() {
            return new Rect(x, y, w, (lines.size() - 1) * Measure.LINE + 9);
        }
    }

    /**
     * The section as placed in {@code box}: the vessel's inner rect (its outline 1 px around it), the layers' bounds in
     * px from the vessel's top (five: 0, then after the gas, the oil, the band, the water), the readings.
     */
    public record Layout(Rect vessel, int[] bounds, List<Text> texts) {
        public Layout {
            bounds = bounds.clone();
            texts = List.copyOf(texts);
        }
    }

    /** The section of {@code layers} placed inside {@code box}, measured with {@code m}. */
    public static Layout layout(Rect box, Layers layers, Measure m) {
        int labelW = 0;
        for (var label : layers.labels()) labelW = Math.max(labelW, m.width(label.text()));
        int innerW = box.w() - 2, innerH = box.h() - 2;
        if (!layers.lying() && !layers.labels().isEmpty()) {
            // Beside: the vessel upright at the left, its readings at its right.
            int vh = innerH, vw = Math.min(innerW - labelW - GAP, (int) Math.round(vh * SLENDER));
            if (vw >= SMALLEST && vh >= SMALLEST && layers.labels().size() * Measure.LINE - 1 <= box.h()) {
                var vessel = new Rect(box.x() + 1, box.y() + 1, vw, vh);
                int[] bounds = bounds(vh, layers);
                return new Layout(vessel, bounds, beside(vessel, bounds, layers.labels(), box, m));
            }
        }
        // Under: the readings in rows under the vessel, which takes the rest.
        var under = under(layers.labels(), box, m);
        int below = under.isEmpty() ? 0 : under.getLast().rect().bottom() - under.getFirst().y() + 4;
        int vh = innerH - below, vw;
        if (layers.lying()) {
            vw = innerW;
            vh = Math.min(vh, (int) Math.round(vw * SLENDER));
        } else vw = Math.min(innerW, (int) Math.round(vh * SLENDER));
        if (vw < SMALLEST || vh < SMALLEST || under.size() < layers.labels().size()) {
            // No room for the readings: the vessel alone, its readings on the door's gauges.
            under = List.of();
            below = 0;
            vh = innerH;
            if (layers.lying()) vh = Math.min(vh, (int) Math.round(innerW * SLENDER));
            vw = layers.lying() ? innerW : Math.min(innerW, Math.max(1, (int) Math.round(vh * SLENDER)));
        }
        // The vessel and its readings in the middle of the box's height, the readings straight under the vessel.
        vw = Math.max(1, vw);
        vh = Math.max(1, vh);
        var vessel = new Rect(box.x() + 1, box.y() + 1 + Math.max(0, innerH - below - vh) / 2, vw, vh);
        int shift = vessel.bottom() + 1 + 4 - (under.isEmpty() ? 0 : under.getFirst().y());
        var texts = new ArrayList<Text>();
        for (var t : under) texts.add(new Text(t.label(), t.lines(), t.x(), t.y() + shift, t.w(), t.swatch().moved(0, shift)));
        return new Layout(vessel, bounds(vessel.h(), layers), texts);
    }

    /** The layers' bounds in px from the top of a vessel {@code vh} high. */
    private static int[] bounds(int vh, Layers layers) {
        int[] bound = new int[5];
        double sum = 0;
        double[] heights = layers.heights();
        for (int i = 0; i < 4; i++) {
            sum += heights[i];
            bound[i + 1] = i == 3 ? vh : (int) Math.round(vh * Math.min(1, sum));
        }
        return bound;
    }

    /** Readings beside a standing vessel, each at its layer's middle, pushed apart where they would touch and kept in {@code box}. */
    private static List<Text> beside(Rect vessel, int[] bounds, List<Label> labels, Rect box, Measure m) {
        int x = vessel.right() + 1 + GAP, n = labels.size();
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            int layer = labels.get(i).layer();
            y[i] = vessel.y() + (bounds[layer] + bounds[layer + 1]) / 2 - 4;
        }
        // In layer order, apart by a line, inside the box.
        var order = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> y[a] != y[b] ? Integer.compare(y[a], y[b]) : Integer.compare(labels.get(a).layer(), labels.get(b).layer()));
        int at = box.y();
        for (int i : order) {
            y[i] = Math.max(y[i], at);
            at = y[i] + Measure.LINE;
        }
        int floor = box.bottom() - 9;
        for (int k = order.size() - 1; k >= 0; k--) {
            int i = order.get(k);
            y[i] = Math.min(y[i], floor);
            floor = y[i] - Measure.LINE;
        }
        var out = new ArrayList<Text>();
        for (int i = 0; i < n; i++) out.add(new Text(labels.get(i), List.of(labels.get(i).text()), x, Math.max(box.y(), y[i]), m.width(labels.get(i).text()), null));
        return out;
    }

    /**
     * Readings in rows from the box's top left (the caller moves them under the vessel): each a swatch of its layer's
     * colour and its text, side by side while they fit the width, else on the next row; a reading wider than the box
     * wraps, its words whole. Fewer readings than labels where one cannot stand whole in the box.
     */
    private static List<Text> under(List<Label> labels, Rect box, Measure m) {
        var out = new ArrayList<Text>();
        int x = box.x(), y = box.y(), lead = SWATCH + SWATCH_GAP, room = box.w() - lead;
        for (var label : labels) {
            var lines = m.wrap(label.text(), room);
            for (var line : lines) if (m.width(line) > room) return out;
            int w = 0;
            for (var line : lines) w = Math.max(w, m.width(line));
            if (x > box.x() && (lines.size() > 1 || x + lead + w > box.right())) {
                x = box.x();
                y = out.getLast().rect().bottom() + 1;
            }
            out.add(new Text(label, lines, x + lead, y, w, new Rect(x, y + 1, SWATCH, SWATCH)));
            if (lines.size() > 1) {
                x = box.x();
                y = out.getLast().rect().bottom() + 1;
            } else x += lead + w + APART;
        }
        return out;
    }

    /** Draws the section of {@code layers} inside {@code box} on the glass. */
    public static void draw(Canvas c, Rect box, Layers layers) {
        var m = c.measure();
        var layout = layout(box, layers, m);
        var v = layout.vessel();
        int vx = v.x(), vy = v.y(), vw = v.w(), vh = v.h();
        int[] inset = insets(layers.lying(), vw, vh), bound = layout.bounds();
        int oil = DeviceColumn.PHASE_COLOURS[0], water = DeviceColumn.PHASE_COLOURS[1];
        c.batch(() -> {
            // The outline: each row a span 1 px wider than its own and its neighbours', then the inside over it.
            for (int y = -1; y <= vh; y++) {
                int in = Integer.MAX_VALUE;
                for (int j = y - 1; j <= y + 1; j++) if (j >= 0 && j < vh) in = Math.min(in, inset[j]);
                c.fill(vx + in - 1, vy + y, vx + vw - in + 1, vy + y + 1, Palette.MUTED);
            }
            for (int y = 0; y < vh; y++) {
                int x0 = vx + inset[y], x1 = vx + vw - inset[y];
                if (x1 <= x0) continue;
                if (y < bound[1]) {
                    c.fill(x0, vy + y, x1, vy + y + 1, GAS_GLASS);
                    c.fill(x0, vy + y, x1, vy + y + 1, GAS);
                } else if (y < bound[2]) c.fill(x0, vy + y, x1, vy + y + 1, oil);
                else if (y < bound[3]) hatch(c, x0, x1, vy + y, vx, y, oil, water);
                else c.fill(x0, vy + y, x1, vy + y + 1, water);
            }
            if (layers.mist() && bound[1] >= 4) {
                // A few drops of the liquid on top in the gas space.
                int drop = layers.oil() > 0 || layers.turbid() > 0 ? oil : water;
                int[][] at = {{30, 18}, {48, 30}, {62, 12}, {74, 38}, {40, 44}, {86, 24}, {56, 52}};
                for (var p : at) {
                    int y = Math.min(bound[1] - 2, bound[1] * p[1] / 60), x = vx + vw * p[0] / 100;
                    int row = Math.max(0, Math.min(vh - 1, y));
                    if (x < vx + inset[row] || x + 2 > vx + vw - inset[row] || y < 0) continue;
                    c.fill(x, vy + y, x + 2, vy + y + 2, drop);
                }
            }
            for (var t : layout.texts()) {
                if (t.swatch() == null) {
                    // A leader from the vessel to the reading.
                    c.hLine(v.right() + 2, t.x() - 2, t.y() + 4, Palette.LINE);
                    continue;
                }
                var s = t.swatch();
                if (t.label().layer() == TURBID_LAYER) for (int y = 0; y < s.h(); y++) hatch(c, s.x(), s.right(), s.y() + y, s.x(), y, oil, water);
                else c.fill(s.x(), s.y(), s.right(), s.bottom(), colour(t.label().layer()));
            }
        });
        for (var t : layout.texts()) {
            for (int i = 0; i < t.lines().size(); i++) c.text(t.lines().get(i), t.x(), t.y() + i * Measure.LINE, Palette.TEXT);
        }
    }

    /** One row of the turbid band's hatch, from {@code x0} to {@code x1}: 2 px of oil and of water by turns, shifted each row. */
    private static void hatch(Canvas c, int x0, int x1, int y, int origin, int row, int oil, int water) {
        for (int x = x0; x < x1; x += 2) c.fill(x, y, Math.min(x + 2, x1), y + 1, ((x - origin + row) / 2 & 1) == 0 ? oil : water);
    }

    /** A reading's swatch colour: its layer's. */
    private static int colour(int layer) {
        return switch (layer) {
            case GAS_LAYER -> DeviceColumn.PHASE_COLOURS[2];
            case OIL_LAYER -> DeviceColumn.PHASE_COLOURS[0];
            default -> DeviceColumn.PHASE_COLOURS[1];
        };
    }

    /** Each row's inset from the vessel's sides: a standing vessel's 2:1 elliptical heads, a lying drum's round ends. */
    static int[] insets(boolean lying, int vw, int vh) {
        int[] inset = new int[vh];
        for (int y = 0; y < vh; y++) {
            double yc = y + .5;
            if (!lying) {
                double hh = vw / 4.0, t = yc < hh ? (hh - yc) / hh : yc > vh - hh ? (yc - (vh - hh)) / hh : 0;
                inset[y] = (int) Math.round(vw / 2.0 * (1 - Math.sqrt(Math.max(0, 1 - t * t))));
            } else {
                double hw = vh / 4.0, t = Math.abs(yc - vh / 2.0) / (vh / 2.0);
                inset[y] = (int) Math.round(hw * (1 - Math.sqrt(Math.max(0, 1 - t * t))));
            }
        }
        return inset;
    }
}
