package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.Npsh;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * A constructed centrifugal pump's curve on its page's glass (batch 2026-11-03-rotating-machinery W10d, owner decisions D37
 * and D38; skill recipe 7). On the left axis the head H(Q) at the present speed ({@link CentrifugalPump#head}); on the right
 * axis NPSH3(Q) at that speed ({@link CentrifugalPump#npsh}, {@link Npsh#required}), the inception curve 4 x NPSH3(Q) dashed
 * (Gülich's σ = 4, {@code Wear.INCEPTION}) and the NPSH available a horizontal line: between NPSH3 and 4 x NPSH3 the pump
 * cavitates, above both it runs quiet, below NPSH3 it starves. The operating point is a dot at the present flow and head.
 * The axis of NPSH grows to fit the inception curve and the NPSH available.
 *
 * <p>Drawn from the casing's static design and what the page's view already holds (the speed share, the flow, the head, the
 * NPSH available): the client computes no process state. Every loop of fills draws inside {@link Canvas#batch}. No hit
 * tests, no Minecraft classes. Keys under {@code gui.icheme.fluid.pump_curve.}.
 */
public final class PumpCurve {
    /** The flow axis runs to this share of the rated flow. */
    public static final double SPAN = 1.4;
    /** The inception curve's multiple of NPSH3 (Gülich's σ at which cavitation starts, {@code Wear.INCEPTION}). */
    public static final double INCEPTION = 4;
    public static final int HEAD = Palette.ACCENT, NPSH3 = Palette.WARN, ONSET = 0xFFF08A6E, AVAILABLE = 0xFF91B5FF, GRID = 0xFF33454F;
    public static final String KEY = "pump_curve.";

    /**
     * What the curve shows: the pump's design; its present speed (share of rated, 0 or more); its operating suction flow
     * (m³/s) and head (m), and the NPSH available (m), each NaN where the page has none; the flow unit's seconds
     * ({@code 3600} for /h) and its symbol ({@code m³/h}).
     */
    public record Input(CentrifugalPump pump, double speed, double flow, double head, double available, double perTime, String flowUnit) {
        public Input {
            if (!(speed >= 0) || !Double.isFinite(speed) || !(perTime > 0)) throw new IllegalArgumentException("Invalid pump curve input: speed " + speed + ", " + perTime + " s");
        }
    }

    /**
     * Where the curve stands in its box: the plot's frame, the heading's height, the axes' last values (flow in the
     * player's unit, head and NPSH in m) and their ticks.
     */
    public record Layout(Rect plot, int heading, double flowTop, double headTop, double npshTop, double[] flowTicks, double[] headTicks, double[] npshTicks) {
        /** The plot's x of flow {@code q} (m³/s) at {@code perTime} seconds a unit. */
        public int x(double q, double perTime) {
            return plot.x() + (int) Math.round(q * perTime / flowTop * plot.w());
        }

        /** The plot's y of {@code v} on an axis that ends at {@code top}. */
        public int y(double v, double top) {
            return plot.bottom() - (int) Math.round(v / top * plot.h());
        }
    }

    private PumpCurve() {
    }

    /** The legend: the head, NPSH3 and the inception curve, each in its curve's colour. */
    static List<CalculatorColumn.Legend> legend(HeatControlColumn.Texts t) {
        return List.of(new CalculatorColumn.Legend(t.get(KEY + "head"), HEAD), new CalculatorColumn.Legend(t.get(KEY + "npsh3"), NPSH3),
                new CalculatorColumn.Legend(t.get(KEY + "inception"), ONSET));
    }

    static String title(Input in, HeatControlColumn.Texts t) {
        return t.get(KEY + "title", label(100 * in.speed()));
    }

    /** The head axis's last value, m: the curve's shutoff head at the present speed and the operating head, or the rated head for a pump at rest. */
    static double headRange(Input in) {
        double v = Math.max(in.pump().head(0, in.speed()), Double.isFinite(in.head()) ? in.head() : 0);
        return v > 0 ? v * 1.05 : in.pump().ratedHead();
    }

    /** The NPSH axis's last value, m: the inception curve at the axis's end of flow (it rises with flow) and the NPSH available. */
    static double npshRange(Input in) {
        double end = INCEPTION * in.pump().npsh(in.speed()).required(SPAN * in.pump().ratedFlow());
        double v = Math.max(end, Double.isFinite(in.available()) ? in.available() : 0);
        return v > 0 ? v * 1.05 : INCEPTION * Npsh.rated(in.pump().ratedFlow(), in.pump().ratedSpeed());
    }

    /** The curve's layout in glass box {@code box}. */
    public static Layout layout(Rect box, Input in, Measure m, HeatControlColumn.Texts t) {
        double[] flowTicks = ticks(SPAN * in.pump().ratedFlow() * in.perTime()), headTicks = ticks(headRange(in)), npshTicks = ticks(npshRange(in));
        double flowTop = flowTicks[flowTicks.length - 1], headTop = headTicks[headTicks.length - 1], npshTop = npshTicks[npshTicks.length - 1];
        int heading = CalculatorColumn.heading(null, box, title(in, t), legend(t), m);
        int l = widest(headTicks, m) + 7, r = widest(npshTicks, m) + 7;
        int top = box.y() + heading + CalculatorColumn.PITCH + 6;
        var plot = new Rect(box.x() + l, top, Math.max(0, box.w() - l - r), Math.max(0, box.bottom() - top - 2 * CalculatorColumn.PITCH - 4));
        return new Layout(plot, heading, flowTop, headTop, npshTop, flowTicks, headTicks, npshTicks);
    }

    /** Draws the curve in glass box {@code box} (the box inside {@code skin.well}). */
    public static void draw(Canvas c, Rect box, Input in, HeatControlColumn.Texts t) {
        var m = c.measure();
        var pump = in.pump();
        Npsh npsh = pump.npsh(in.speed());
        var lay = layout(box, in, m, t);
        var p = lay.plot();
        if (p.w() < 2 || p.h() < 2) return;
        int x0 = p.x(), y0 = p.y(), w = p.w(), h = p.h(), bottom = p.bottom();
        CalculatorColumn.heading(c, box, title(in, t), legend(t), m);
        // The axes' titles over their numbers.
        String headAxis = t.get(KEY + "head_axis"), npshAxis = t.get(KEY + "npsh_axis"), flowAxis = t.get(KEY + "flow_axis", in.flowUnit());
        c.text(headAxis, box.x() + 3, y0 - CalculatorColumn.PITCH - 4, HEAD);
        c.text(npshAxis, box.right() - 3 - m.width(npshAxis), y0 - CalculatorColumn.PITCH - 4, NPSH3);
        c.fill(x0, y0, x0 + w, bottom, Palette.PANEL);
        // The grid at the head's and the flow's ticks, then both axes' numbers.
        c.batch(() -> {
            for (double v : lay.headTicks()) if (v > 0 && v < lay.headTop()) c.hLine(x0, x0 + w, lay.y(v, lay.headTop()), GRID);
            for (double v : lay.flowTicks()) if (v > 0 && v < lay.flowTop()) c.vLine(x0 + (int) Math.round(v / lay.flowTop() * w), y0, bottom, GRID);
        });
        for (double v : lay.headTicks()) {
            String s = label(v);
            c.text(s, x0 - 4 - m.width(s), lay.y(v, lay.headTop()) - 4, HEAD);
        }
        for (double v : lay.npshTicks()) c.text(label(v), x0 + w + 5, lay.y(v, lay.npshTop()) - 4, NPSH3);
        for (double v : lay.flowTicks()) {
            String s = label(v);
            int x = x0 + (int) Math.round(v / lay.flowTop() * w);
            c.text(s, Math.clamp(x - m.width(s) / 2, x0 - 2, x0 + w + 2 - m.width(s)), bottom + 4, Palette.MUTED);
        }
        c.text(flowAxis, x0 + w / 2 - m.width(flowAxis) / 2, bottom + 4 + CalculatorColumn.PITCH, Palette.MUTED);
        c.outline(x0, y0, w + 1, h + 1, Palette.LINE);
        // The NPSH available: a line across, its word at the right end above it.
        if (Double.isFinite(in.available())) {
            int ya = lay.y(Math.clamp(in.available(), 0, lay.npshTop()), lay.npshTop());
            c.fill(x0 + 1, ya, x0 + w, ya + 1, AVAILABLE);
            String word = t.get(KEY + "available");
            c.text(word, x0 + w - 3 - m.width(word), Math.max(y0 + 2, ya - 10), AVAILABLE);
        }
        // The curves, one sample a pixel column; the head stops where it reaches zero.
        polyline(c, p, lay.flowTop(), lay.headTop(), q -> pump.head(q / in.perTime(), in.speed()), HEAD, false);
        polyline(c, p, lay.flowTop(), lay.npshTop(), q -> npsh.required(q / in.perTime()), NPSH3, false);
        polyline(c, p, lay.flowTop(), lay.npshTop(), q -> INCEPTION * npsh.required(q / in.perTime()), ONSET, true);
        // The operating point: a guide down to the flow axis, a white dot ringed in the head's colour.
        if (Double.isFinite(in.flow()) && Double.isFinite(in.head())) {
            int ox = Math.clamp(lay.x(Math.max(0, in.flow()), in.perTime()), x0, x0 + w), oy = Math.clamp(lay.y(Math.max(0, in.head()), lay.headTop()), y0, bottom);
            c.batch(() -> {
                for (int y = oy + 4; y < bottom; y += 3) c.fill(ox, y, ox + 1, y + 1, Palette.MUTED);
                c.fill(ox - 3, oy - 3, ox + 4, oy + 4, HEAD);
                c.fill(ox - 2, oy - 2, ox + 3, oy + 3, Palette.TEXT);
            });
        }
    }

    /**
     * A curve {@code f} (axis units) over the plot from flow 0 to {@code flowTop}, one point a pixel column, held inside the
     * plot; {@code dashed} draws four columns in every eight.
     */
    static void polyline(Canvas c, Rect p, double flowTop, double top, DoubleUnaryOperator f, int colour, boolean dashed) {
        c.batch(() -> {
            int px = -1, py = 0;
            for (int i = 0; i <= p.w(); i++) {
                double v = f.applyAsDouble(flowTop * i / p.w());
                if (!(v >= 0) || v > top) {
                    px = -1;
                    continue;
                }
                int x = p.x() + i, y = p.bottom() - (int) Math.round(v / top * p.h());
                if (!dashed || i % 8 < 4) {
                    if (px >= 0) for (int yy = Math.min(py, y); yy <= Math.max(py, y); yy++) c.fill(x, yy, x + 1, yy + 1, colour);
                    else c.fill(x, y, x + 1, y + 1, colour);
                }
                px = x;
                py = y;
            }
        });
    }

    /** The first 1, 2, 2.5 or 5 times a power of ten at or above {@code v} (positive). */
    static double nice(double v) {
        double e = Math.pow(10, Math.floor(Math.log10(v))), f = v / e;
        return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * e;
    }

    /** Ticks from zero by a nice step, about five of them, the last at or above {@code top} (positive). */
    static double[] ticks(double top) {
        double step = nice(top / 5);
        int n = Math.max(1, (int) Math.ceil(top / step - 1e-9));
        var out = new double[n + 1];
        for (int i = 0; i <= n; i++) out[i] = i * step;
        return out;
    }

    /** The widest of {@code ticks}' numbers. */
    static int widest(double[] ticks, Measure m) {
        int w = 0;
        for (double v : ticks) w = Math.max(w, m.width(label(v)));
        return w;
    }

    static String label(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, Math.abs(10 * v - Math.rint(10 * v)) < 1e-9 ? "%.1f" : "%.2f", v);
    }
}
