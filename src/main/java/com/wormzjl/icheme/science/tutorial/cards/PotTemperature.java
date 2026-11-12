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
 * second drum (the end of the naphtha) and the fire out (the end of the kerosene). Past the fire out the curve runs on,
 * dimmed, to the run's end: what the pot does with the fire left on, until the law cuts its fire.
 */
public final class PotTemperature implements Card {
    public static final String NAME = "pot_temperature";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** The curve's samples along the share distilled. */
    static final int SAMPLES = 200;
    /** The temperature axis, K: 0 to 400 °C (the run ends where the law cuts the fire, at 350 °C). */
    public static final double FLOOR = 273.15, CEILING = 673.15;
    private static final int OUTLINE = 0xFFB3C0CA, MARK = 0xFFFFFFFF, CURVE = 0xFFE8EEF2, LEFT_ON = 0xFF7A8794, CUT = 0xFFF08C24, NONE = 0xFF59636B;
    /** The share distilled at the run's end, at its first cut and at the fire out, and the pot's temperature and the part coming over at each sample. */
    private final double end, cut, fireOut;
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
        fireOut = distilled.at(run.line("kerosene", "time"));
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
        var out = new ArrayList<>(List.of(KEY + ".title", KEY + ".reading", KEY + ".share", KEY + ".cut_first", KEY + ".cut_end", KEY + ".after"));
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

    /** The shares distilled at the two cuts: the switch to the second drum (the end of the naphtha), and the fire out (the end of the kerosene). */
    public double[] cuts() {
        return new double[]{cut, fireOut};
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

    /** {@code argb} at half its brightness: past the fire out, what the fire left on does. */
    static int dimmed(int argb) {
        return 0xFF000000 | (argb >> 1 & 0x7F7F7F);
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
        // The cuts' words at the plot's top, each just left of its line: the first on the top row, the second on the row under
        // it; past the fire out, where it fits over the curve, the fire left on. Each is placed before the drawing, so the
        // mark's line breaks where it would cross one.
        String first = tr.apply(KEY + ".cut_first"), last = tr.apply(KEY + ".cut_end"), after = tr.apply(KEY + ".after");
        int cx1 = px + (int) Math.round(cuts[0] / end * (pw - 1)), cx2 = Math.min(plot.right() - 1, px + (int) Math.round(cuts[1] / end * (pw - 1)));
        int lastX = cx2 - 2 - m.width(last), firstX = cx1 - 2 - m.width(first);
        var words = new ArrayList<Word>();
        if (firstX >= px + 2) words.add(new Word(first, firstX, plot.y() + 1, m.width(first), Palette.WARN));
        // The second only where the curve stays under its row (the curve rises to the right, so it is highest at the fire out).
        if (lastX >= px + 2 && yOf(kelvin(cuts[1]), plot) >= plot.y() + 21) words.add(new Word(last, lastX, plot.y() + 11, m.width(last), Palette.WARN));
        if (cx2 + 3 + m.width(after) <= plot.right() - 1 && yOf(kelvin(end), plot) >= plot.y() + 11) words.add(new Word(after, cx2 + 3, plot.y() + 1, m.width(after), Palette.MUTED));
        c.batch(() -> {
            int lastY = -1;
            for (int x = 0; x < pw; x++) {
                double s = end * x / Math.max(1, pw - 1);
                int cy = yOf(kelvin(s), plot);
                boolean leftOn = s > fireOut;
                // Under the mark: the area down to the axis, coloured by the part coming over; dimmed past the fire out.
                if (px + x <= markX) c.fill(px + x, cy, px + x + 1, plot.bottom(), leftOn ? dimmed(colour(part(s))) : colour(part(s)));
                int a = lastY < 0 ? cy : Math.min(lastY, cy), b = lastY < 0 ? cy : Math.max(lastY, cy);
                c.fill(px + x, a, px + x + 1, b + 1, leftOn ? LEFT_ON : CURVE);
                lastY = cy;
            }
            for (double cutShare : cuts) {
                int cx = Math.min(plot.right() - 1, px + (int) Math.round(cutShare / end * (pw - 1)));
                for (int yy = plot.y(); yy < plot.bottom(); yy += 4) c.fill(cx, yy, cx + 1, Math.min(plot.bottom(), yy + 2), CUT);
            }
            c.fill(px, plot.y(), px + 1, plot.bottom(), OUTLINE);
            c.fill(px, plot.bottom() - 1, plot.right(), plot.bottom(), OUTLINE);
            // The mark, broken a pixel round every word it would cross.
            int from = plot.y();
            for (int yy = plot.y(); yy <= plot.bottom(); yy++) {
                boolean covered = false;
                for (var word : words) covered |= word.covers(markX, yy);
                if (covered || yy == plot.bottom()) {
                    if (yy > from) c.fill(markX, from, markX + 1, yy, MARK);
                    from = yy + 1;
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                int ex = x0 + (i % columns) * colW, ey = legendY + (i / columns) * 10;
                c.fill(ex, ey + 1, ex + 6, ey + 7, colour(shown.get(i)));
            }
        });
        c.text(top, x0, plot.y(), Palette.MUTED);
        c.text(bottom, x0, plot.bottom() - 9, Palette.MUTED);
        for (var word : words) c.text(word.text(), word.x(), word.y(), word.colour());
        String axis = tr.apply(KEY + ".share");
        c.text(m.cut(axis, pw), px + Math.max(0, (pw - m.width(axis)) / 2), plot.bottom() + 2, Palette.MUTED);
        for (int i = 0; i < entries.size(); i++) c.text(m.cut(entries.get(i), colW - 9), x0 + (i % columns) * colW + 9, legendY + (i / columns) * 10, Palette.TEXT);
    }

    /** A word on the plot: its text, its top left corner, its width (px) and colour; one text row (9 px) tall. */
    record Word(String text, int x, int y, int width, int colour) {
        /** Whether the pixel ({@code px}, {@code py}) is on the word or a pixel round it. */
        boolean covers(int px, int py) {
            return px >= x - 1 && px <= x + width && py >= y - 1 && py <= y + 9;
        }
    }

    /** The screen row of temperature {@code k} on {@code plot}. */
    private static int yOf(double k, Rect plot) {
        double f = Math.clamp((k - FLOOR) / (CEILING - FLOOR), 0, 1);
        return plot.bottom() - 1 - (int) Math.round(f * (plot.h() - 1));
    }
}
