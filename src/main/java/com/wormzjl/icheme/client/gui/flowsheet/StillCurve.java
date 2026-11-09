package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * A batch still's curve on its door's Batch tab (batch 2026-11-09-batch-distiller, W7): the run's time-series reading
 * ({@link Report.Series}) drawn on the glass. Above, the pot's and the head's temperatures over the batch's time; below, the
 * distillate's composition as stacked mole shares, one column a step, with its rate to the spout over them on its own scale. Each
 * cut's end is a dotted line across both, its number at the top. Minecraft-free: the screen and the render tool draw the same.
 */
public final class StillCurve {
    /** The pot's line, the head's, the rate's; the composition's bands in order, and the rest of the distillate. */
    static final int POT = Palette.YELLOW, HEAD = Palette.ACCENT, RATE = Palette.TEXT, REST = 0xFF3A4C57;
    static final int[] BANDS = {0xFF3377FF, 0xFFD0A85A, 0xFF9B7FD1, 0xFF5BBF6A, 0xFFE2826B};
    /** The margins: the axes' labels left and right, a legend line, the time's labels. */
    static final int LEFT = 32, RIGHT = 34, LINE = 11;

    private StillCurve() {
    }

    /**
     * What the curve needs beside its series: the player's units, a text by its key under {@code gui.icheme.flowsheet.}, and a
     * component's name by its id.
     */
    public record Labels(SheetUnits units, Function<String, String> text, Function<String, String> names) {
        static Labels of(SheetContext x) {
            return new Labels(x.units(), key -> x.text(key), x.fluids().names());
        }
    }

    /** The glass of a still's Batch tab: the curve of the results shown. */
    static Door.Glass glass(SheetContext x, Flowsheet.Unit unit) {
        return (c, skin, box) -> draw(c, box, series(x, unit.id()), Labels.of(x));
    }

    /** The still's curve in the results the sheet shows for it, or null before a run. */
    static Report.@Nullable Series series(SheetContext x, String unit) {
        var results = x.state().shown(unit);
        var report = results == null ? null : results.report(unit);
        return report == null ? null : report.series().get(BatchStillUnit.SERIES);
    }

    /** The panels' places in {@code box}: the temperatures', the composition's, and the lines between. */
    record Layout(Rect temperatures, Rect composition, int legend, int shares, int times) {
        static Layout of(Rect box) {
            int x0 = box.x() + LEFT, x1 = box.right() - RIGHT, top = box.y() + LINE + 3, bottom = box.bottom() - LINE - 1;
            int room = Math.max(20, bottom - top - LINE - 4), split = top + room * 11 / 20;
            return new Layout(new Rect(x0, top, Math.max(1, x1 - x0), Math.max(1, split - top)), new Rect(x0, split + LINE + 4, Math.max(1, x1 - x0), Math.max(1, bottom - split - LINE - 4)),
                    box.y() + 2, split + 3, bottom + 2);
        }
    }

    /** {@code series} on the glass inside {@code box}; with no series, a line that says to run the sheet. */
    public static void draw(Canvas c, Rect box, Report.@Nullable Series series, Labels labels) {
        var m = c.measure();
        if (series == null || series.size() < 2) {
            String none = labels.text().apply("curve.none");
            c.text(none, box.x() + (box.w() - m.width(none)) / 2, box.y() + box.h() / 2 - 4, Palette.MUTED);
            return;
        }
        var u = labels.units();
        var layout = Layout.of(box);
        double[] time = series.time(), pot = orNaN(series.channel("pot"), time.length), head = orNaN(series.channel("head"), time.length);
        double[] rate = orNaN(series.channel("rate"), time.length), cut = orNaN(series.channel("cut"), time.length);
        var shares = new ArrayList<double[]>();
        var names = new ArrayList<String>();
        for (var key : series.channels().keySet()) {
            if (!key.startsWith("x:")) continue;
            shares.add(series.channel(key));
            names.add(labels.names().apply(key.substring(2)));
        }
        double end = Math.max(time[time.length - 1], 1e-9);
        // Temperatures on whole tens of kelvin around both lines.
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (double[] line : new double[][]{pot, head}) for (double v : line) if (Double.isFinite(v)) {
            low = Math.min(low, v);
            high = Math.max(high, v);
        }
        low = 10 * Math.floor((low - 2) / 10);
        high = Math.max(low + 10, 10 * Math.ceil((high + 2) / 10));
        double most = 0;
        for (double v : rate) if (Double.isFinite(v)) most = Math.max(most, v);
        if (!(most > 0)) most = 1;
        var t = layout.temperatures();
        var s = layout.composition();
        double tLow = low, tHigh = high, rateMost = most;
        c.batch(() -> {
            frame(c, t);
            frame(c, s);
            // The composition: each step's column of stacked shares, the rest of the distillate over them.
            for (int k = 1; k < time.length; k++) {
                int xa = x(s, time[k - 1], end), xb = Math.max(xa + 1, x(s, time[k], end));
                double base = 0;
                boolean any = false;
                for (int b = 0; b < shares.size(); b++) {
                    double share = shares.get(b)[k];
                    if (!Double.isFinite(share)) continue;
                    any = true;
                    int y0 = y(s, base + share, 0, 1), y1 = y(s, base, 0, 1);
                    if (y1 > y0) c.fill(xa, y0, xb, y1, BANDS[b % BANDS.length]);
                    base += share;
                }
                if (any && base < 1) c.fill(xa, y(s, 1, 0, 1), xb, y(s, base, 0, 1), REST);
            }
            var p = new FlowsheetPainter(c, 0, 0, 1, 1);
            polyline(p, t, time, pot, end, tLow, tHigh, POT);
            polyline(p, t, time, head, end, tLow, tHigh, HEAD);
            polyline(p, s, time, rate, end, 0, rateMost, RATE);
            // Each cut's end, across both panels.
            for (int k = 1; k < time.length; k++) {
                if (!Double.isFinite(cut[k - 1]) || !Double.isFinite(cut[k]) || cut[k] == cut[k - 1]) continue;
                int xc = x(t, time[k - 1], end);
                for (int y = t.y(); y < s.bottom(); y += 3) c.fill(xc, y, xc + 1, y + 1, Palette.MUTED);
            }
        });
        // The cuts' numbers, at the middle of each cut's span.
        int k0 = -1;
        for (int k = 0; k <= time.length; k++) {
            boolean change = k == time.length || !Double.isFinite(cut[k]) || k0 >= 0 && cut[k] != cut[k0];
            if (k0 >= 0 && change) {
                String label = String.valueOf((int) cut[k0]);
                int xm = (x(t, time[Math.max(0, k0 - 1)], end) + x(t, time[k - 1], end)) / 2 - m.width(label) / 2;
                c.text(label, xm, t.y() + 2, Palette.MUTED);
                k0 = -1;
            }
            if (k < time.length && k0 < 0 && Double.isFinite(cut[k])) k0 = k;
        }
        // The axes' labels: temperatures left of the upper panel, shares left and the rate right of the lower one, the time under it.
        var text = labels.text();
        axis(c, t, u.t(tHigh), u.t((tLow + tHigh) / 2), u.t(tLow), true);
        axis(c, s, "100", "50", "0", true);
        axis(c, s, SheetUnits.number(0, rateMost * 3600), "", "0", false);
        String hours = SheetUnits.number(2, end / 3600);
        c.text("0", s.x(), layout.times(), Palette.MUTED);
        c.text(hours, s.right() - m.width(hours), layout.times(), Palette.MUTED);
        String axisTime = text.apply("curve.time");
        c.text(axisTime, s.x() + (s.w() - m.width(axisTime)) / 2, layout.times(), Palette.MUTED);
        // The legends: the lines above, the components between the panels.
        int lx = box.x() + 4;
        lx = legend(c, lx, layout.legend(), POT, text.apply("curve.pot") + " " + u.temperature().symbol());
        lx = legend(c, lx, layout.legend(), HEAD, text.apply("curve.head") + " " + u.temperature().symbol());
        legend(c, lx, layout.legend(), RATE, text.apply("curve.rate"));
        int sx = box.x() + 4;
        String mole = text.apply("curve.composition");
        c.text(mole, sx, layout.shares(), Palette.MUTED);
        sx += m.width(mole) + 6;
        for (int b = 0; b < names.size() && sx < box.right() - 20; b++) sx = legend(c, sx, layout.shares(), BANDS[b % BANDS.length], m.cut(names.get(b), 60));
    }

    private static double[] orNaN(double @Nullable [] values, int count) {
        if (values != null) return values;
        double[] none = new double[count];
        java.util.Arrays.fill(none, Double.NaN);
        return none;
    }

    private static void frame(Canvas c, Rect r) {
        c.outline(r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2, Palette.LINE);
        c.hLine(r.x(), r.right() - 1, r.y() + r.h() / 2, 0xFF2E3D46);
    }

    static int x(Rect r, double time, double end) {
        return r.x() + (int) Math.round(Math.clamp(time / end, 0, 1) * (r.w() - 1));
    }

    static int y(Rect r, double value, double low, double high) {
        return r.bottom() - 1 - (int) Math.round(Math.clamp((value - low) / (high - low), 0, 1) * (r.h() - 1));
    }

    /** A line through the finite values; a gap where a value is none. */
    private static void polyline(FlowsheetPainter p, Rect r, double[] time, double[] values, double end, double low, double high, int colour) {
        for (int k = 1; k < time.length; k++) {
            if (!Double.isFinite(values[k - 1]) || !Double.isFinite(values[k])) continue;
            p.line(x(r, time[k - 1], end), y(r, values[k - 1], low, high), x(r, time[k], end), y(r, values[k], low, high), colour);
        }
    }

    /** Three labels down a panel's side: its top, middle and bottom. */
    private static void axis(Canvas c, Rect r, String top, String middle, String bottom, boolean left) {
        var m = c.measure();
        int[] ys = {r.y() - 1, r.y() + r.h() / 2 - 4, r.bottom() - 8};
        String[] texts = {top, middle, bottom};
        for (int i = 0; i < 3; i++) {
            if (texts[i].isEmpty()) continue;
            int xt = left ? r.x() - 4 - m.width(texts[i]) : r.right() + 4;
            c.text(texts[i], xt, ys[i], Palette.MUTED);
        }
    }

    private static int legend(Canvas c, int x, int y, int colour, String label) {
        c.fill(x, y + 2, x + 6, y + 8, colour);
        c.text(label, x + 9, y + 1, Palette.TEXT);
        return x + 9 + c.measure().width(label) + 8;
    }

    /** The curve's keys, for the gates. */
    static List<String> keys() {
        return List.of("curve.none", "curve.time", "curve.pot", "curve.head", "curve.rate", "curve.composition");
    }
}
