package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.tutorial.films.StillRunData;
import com.wormzjl.icheme.science.tutorial.scene.Series;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "pot temperature" (batch 2026-11-12-tutorial-system, P2b; guide step {@code still/cut}): the recorded still
 * run ({@link StillRunData}) as a curve of the pot's temperature against the share of the charge distilled. The slider
 * runs along the run; the area under the curve up to the mark is coloured by the part coming over (the barrel card's
 * colours), the readout gives the pot's temperature and the share, and two lines mark the run's cuts: the switch to the
 * second drum (the end of the naphtha) and the fire out (the run's end).
 */
public final class PotTemperature implements Card {
    public static final String NAME = "pot_temperature";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** The curve's samples along the share distilled. */
    static final int SAMPLES = 200;
    /** The temperature axis, K: 0 to 300 °C. */
    public static final double FLOOR = 273.15, CEILING = 573.15;
    private static final int OUTLINE = 0xFFB3C0CA, MARK = 0xFFFFFFFF, CURVE = 0xFFE8EEF2, CUT = 0xFFF08C24, NONE = 0xFF59636B;
    /** The share distilled at the run's end, at its first cut, and the pot's temperature and the part coming over at each sample. */
    private final double end, cut;
    private final double[] kelvin;
    private final int[] part;

    public PotTemperature() {
        this(StillRunData.load());
    }

    PotTemperature(Series.Recording run) {
        var distilled = run.series("distilled");
        var pot = run.series("pot");
        var parts = run.series("part");
        end = distilled.value(distilled.size() - 1);
        cut = distilled.at(run.line("naphtha", "time"));
        kelvin = new double[SAMPLES + 1];
        part = new int[SAMPLES + 1];
        for (int i = 0; i <= SAMPLES; i++) {
            double t = time(distilled, end * i / SAMPLES);
            kelvin[i] = pot.at(t);
            part[i] = (int) Math.round(parts.value(parts.index(t)));
        }
    }

    /** When the share distilled first reaches {@code share}: the run's end past it. */
    private static double time(Series distilled, double share) {
        double t = distilled.reaches(share);
        return Double.isNaN(t) ? distilled.end() : t;
    }

    @Override
    public String captionKey() {
        return KEY;
    }

    @Override
    public Control control() {
        return Control.SLIDER;
    }

    @Override
    public String controlKey() {
        return KEY + ".control";
    }

    @Override
    public List<String> textKeys() {
        var out = new ArrayList<>(List.of(KEY + ".title", KEY + ".reading", KEY + ".share", KEY + ".cut_first", KEY + ".cut_end"));
        for (var band : StillRunData.PARTS) out.add(Barrel.bandKey(band));
        return out;
    }

    /** The share distilled at the slider's {@code control}. */
    public double share(double control) {
        return end * Math.clamp(control, 0, 1);
    }

    /** The pot's temperature at {@code share} distilled, K (linear between samples). */
    public double kelvin(double share) {
        double at = Math.clamp(share / end, 0, 1) * SAMPLES;
        int i = Math.min(SAMPLES - 1, (int) at);
        return kelvin[i] + (kelvin[i + 1] - kelvin[i]) * (at - i);
    }

    /** The part coming over at {@code share} distilled (0 gas to 5 residue), -1 for none. */
    public int part(double share) {
        return part[(int) Math.round(Math.clamp(share / end, 0, 1) * SAMPLES)];
    }

    /** The shares distilled at the two cuts: the switch to the second drum, and the fire out. */
    public double[] cuts() {
        return new double[]{cut, end};
    }

    /** The parts that come over in the run, in boiling order. */
    List<Integer> partsShown() {
        var out = new ArrayList<Integer>();
        for (int p : part) if (p >= 0 && !out.contains(p)) out.add(p);
        out.sort(Integer::compare);
        return out;
    }

    static int colour(int part) {
        return part >= 0 && part < Barrel.BAND_COLOURS.length ? Barrel.BAND_COLOURS[part] : NONE;
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var m = c.measure();
        double share = share(state.control()), now = kelvin(share);
        int x0 = area.x() + 4, w = area.w() - 8, y = area.y() + 3;
        String title = tr.apply(KEY + ".title");
        String readout = Chain.fill(tr.apply(KEY + ".reading"), state.temperature().apply(now), Math.round(share * 100) + " %");
        c.text(m.cut(title, w), x0, y, Palette.TEXT);
        if (m.width(title) + 12 + m.width(readout) <= w) c.text(readout, x0 + w - m.width(readout), y, Palette.WARN);
        else c.text(m.cut(readout, w), x0, y += 10, Palette.WARN);
        // The legend of the parts that come over, at the foot; the share's axis words above it.
        var shown = partsShown();
        var entries = new ArrayList<String>();
        int widest = 0;
        for (int p : shown) {
            entries.add(tr.apply(Barrel.bandKey(StillRunData.PARTS.get(p))));
            widest = Math.max(widest, m.width(entries.getLast()) + 12);
        }
        int columns = Math.clamp(w / Math.max(1, widest), 1, Math.max(1, entries.size()));
        int rows = (entries.size() + columns - 1) / columns, colW = w / columns;
        int legendY = area.bottom() - 2 - rows * 10;
        // The plot: the temperature scale's words at its left, the share's under it.
        String top = state.temperature().apply(CEILING), bottom = state.temperature().apply(FLOOR);
        int scaleW = Math.max(m.width(top), m.width(bottom)) + 3;
        int px = x0 + scaleW, pw = Math.max(10, w - scaleW), pyTop = y + 14, pyBottom = legendY - 13;
        int ph = Math.max(10, pyBottom - pyTop);
        var plot = new Rect(px, pyTop, pw, ph);
        int markX = px + (int) Math.round(share / end * (pw - 1));
        double[] cuts = cuts();
        c.batch(() -> {
            int lastY = -1;
            for (int x = 0; x < pw; x++) {
                double s = end * x / Math.max(1, pw - 1);
                int cy = yOf(kelvin(s), plot);
                // Under the mark: the area down to the axis, coloured by the part coming over.
                if (px + x <= markX) c.fill(px + x, cy, px + x + 1, plot.bottom(), colour(part(s)));
                int a = lastY < 0 ? cy : Math.min(lastY, cy), b = lastY < 0 ? cy : Math.max(lastY, cy);
                c.fill(px + x, a, px + x + 1, b + 1, CURVE);
                lastY = cy;
            }
            for (double cutShare : cuts) {
                int cx = Math.min(plot.right() - 1, px + (int) Math.round(cutShare / end * (pw - 1)));
                for (int yy = plot.y(); yy < plot.bottom(); yy += 4) c.fill(cx, yy, cx + 1, Math.min(plot.bottom(), yy + 2), CUT);
            }
            c.fill(px, plot.y(), px + 1, plot.bottom(), OUTLINE);
            c.fill(px, plot.bottom() - 1, plot.right(), plot.bottom(), OUTLINE);
            c.fill(markX, plot.y(), markX + 1, plot.bottom(), MARK);
            for (int i = 0; i < entries.size(); i++) {
                int ex = x0 + (i % columns) * colW, ey = legendY + (i / columns) * 10;
                c.fill(ex, ey + 1, ex + 6, ey + 7, colour(shown.get(i)));
            }
        });
        c.text(top, x0, plot.y(), Palette.MUTED);
        c.text(bottom, x0, plot.bottom() - 9, Palette.MUTED);
        // The cuts' words at the plot's top, each just left of its line: the first on the top row, the second on the row under it.
        String first = tr.apply(KEY + ".cut_first"), last = tr.apply(KEY + ".cut_end");
        int cx1 = px + (int) Math.round(cuts[0] / end * (pw - 1)), cx2 = plot.right() - 1;
        int lastX = cx2 - 2 - m.width(last), firstX = cx1 - 2 - m.width(first);
        if (firstX >= px + 2) c.text(first, firstX, plot.y() + 1, Palette.WARN);
        // The second only where the curve stays under its row (the curve rises to its right end, so its end is its top).
        if (lastX >= px + 2 && yOf(kelvin(end), plot) >= plot.y() + 21) c.text(last, lastX, plot.y() + 11, Palette.WARN);
        String axis = tr.apply(KEY + ".share");
        c.text(m.cut(axis, pw), px + Math.max(0, (pw - m.width(axis)) / 2), plot.bottom() + 2, Palette.MUTED);
        for (int i = 0; i < entries.size(); i++) c.text(m.cut(entries.get(i), colW - 9), x0 + (i % columns) * colW + 9, legendY + (i / columns) * 10, Palette.TEXT);
    }

    /** The screen row of temperature {@code k} on {@code plot}. */
    private static int yOf(double k, Rect plot) {
        double f = Math.clamp((k - FLOOR) / (CEILING - FLOOR), 0, 1);
        return plot.bottom() - 1 - (int) Math.round(f * (plot.h() - 1));
    }
}
