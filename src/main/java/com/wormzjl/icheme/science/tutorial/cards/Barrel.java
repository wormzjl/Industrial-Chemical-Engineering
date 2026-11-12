package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "barrel" (guide step {@code crude/barrel}): 100 kg of a crude grade (an option: medium, light, heavy, oil
 * sand) as a bar of its components in boiling order, coloured as their records, grouped under the six bands with their
 * whole-percent shares in a legend. The slider moves a temperature mark from 0 to 600 °C; the part that boils off below
 * it lifts off the bar, and the readout gives its share. One line names the unwanted guests: sulfur (the grade's
 * specification), salt and water. Its numbers are the recording's ({@link BarrelData}).
 */
public final class Barrel implements Card {
    public static final String NAME = "barrel";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** The options' words, in {@link BarrelData#GRADES}' order. */
    static final List<String> GRADE_WORDS = List.of("medium", "light", "heavy", "oil_sand");
    /** The slider's range, °C. */
    public static final double FROM_C = 0, TO_C = 600;
    /** The bands' colours, gas to residue: the brackets, the legend and the products card. */
    static final int[] BAND_COLOURS = {0xFF9FD3F0, 0xFFF2D46B, 0xFFF0A53C, 0xFFD9772E, 0xFFB8653A, 0xFF9C8070};
    private static final int OUTLINE = 0xFFB3C0CA, MARK = 0xFFFFFFFF;
    private final List<BarrelData.Grade> grades;

    public Barrel() {
        this(BarrelData.load());
    }

    Barrel(List<BarrelData.Grade> grades) {
        this.grades = List.copyOf(grades);
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
    public List<String> options() {
        return GRADE_WORDS.stream().map(w -> KEY + ".grade." + w).toList();
    }

    @Override
    public List<String> textKeys() {
        var out = new ArrayList<>(List.of(KEY + ".title", KEY + ".boiled", KEY + ".guests"));
        for (var band : BarrelData.BANDS) out.add(bandKey(band));
        return out;
    }

    /** A band's word: the glossary's term. */
    static String bandKey(String band) {
        return "tutorial.icheme.term." + band;
    }

    /** The mark's temperature at {@code control}, K. */
    public static double kelvin(double control) {
        return 273.15 + FROM_C + (TO_C - FROM_C) * Math.clamp(control, 0, 1);
    }

    public BarrelData.Grade grade(int option) {
        return grades.get(Math.clamp(option, 0, grades.size() - 1));
    }

    /** A percent as text: whole where it is, else its digits. */
    static String percent(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + " %";
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var grade = grade(state.option());
        double kelvin = kelvin(state.control()), boiled = grade.boiled(kelvin);
        var m = c.measure();
        int x0 = area.x() + 6, w = area.w() - 12, y = area.y() + 3;
        // The title, and the readout beside it where it fits, else under it.
        String title = tr.apply(KEY + ".title");
        String readout = Chain.fill(tr.apply(KEY + ".boiled"), state.temperature().apply(kelvin), Math.round(boiled * 100) + " %");
        c.text(title, x0, y, Palette.TEXT);
        if (m.width(title) + 12 + m.width(readout) <= w) c.text(readout, x0 + w - m.width(readout), y, Palette.WARN);
        else c.text(m.cut(readout, w), x0, y += 10, Palette.WARN);
        int lift = Math.clamp(area.h() / 14, 5, 9), barH = Math.clamp(area.h() / 9, 8, 16);
        int liftY = y + 12, barY = liftY + lift + 2;
        // The legend's rows and the guests' line, from the foot up.
        var guests = m.wrap(Chain.fill(tr.apply(KEY + ".guests"), percent(grade.sulfur())), w);
        int[] shares = BarrelData.percents(grade.bands());
        var entries = new ArrayList<String>();
        int widest = 0;
        for (int b = 0; b < shares.length; b++) {
            entries.add(tr.apply(bandKey(BarrelData.BANDS.get(b))) + " " + shares[b] + " %");
            widest = Math.max(widest, m.width(entries.getLast()) + 10);
        }
        int columns = Math.max(1, Math.min(3, w / Math.max(1, widest + 6)));
        int rows = (entries.size() + columns - 1) / columns, colW = w / columns;
        int legendY = barY + barH + 7;
        int guestsY = Math.max(legendY + rows * 10 + 2, area.bottom() - 2 - guests.size() * 10);
        c.batch(() -> {
            double cum = 0;
            int boundary = x0 + (int) Math.round(boiled * w);
            for (var s : grade.segments()) {
                int a = x0 + (int) Math.round(cum * w);
                cum += s.share();
                int b = x0 + (int) Math.round(cum * w);
                if (b <= a) continue;
                // The part left of the mark's boundary has boiled off: it stands lifted above the bar.
                int split = Math.clamp(boundary, a, b);
                if (split > a) c.fill(a, liftY, split, liftY + lift, s.colour());
                if (b > split) c.fill(split, barY, b, barY + barH, s.colour());
            }
            c.outline(x0 - 1, barY - 1, w + 2, barH + 2, OUTLINE);
            c.fill(boundary, liftY - 2, boundary + 1, barY + barH + 2, MARK);
            // The bands' brackets under the bar.
            double at = 0;
            var bands = grade.bands();
            for (int i = 0; i < bands.length; i++) {
                int a = x0 + (int) Math.round(at * w);
                at += bands[i];
                int b = x0 + (int) Math.round(at * w);
                if (b - a > 1) c.fill(a, barY + barH + 3, b - 1, barY + barH + 5, BAND_COLOURS[i]);
            }
            for (int i = 0; i < entries.size(); i++) {
                int ex = x0 + (i % columns) * colW, ey = legendY + (i / columns) * 10;
                c.fill(ex, ey + 1, ex + 6, ey + 7, BAND_COLOURS[i]);
            }
        });
        for (int i = 0; i < entries.size(); i++) c.text(entries.get(i), x0 + (i % columns) * colW + 9, legendY + (i / columns) * 10, Palette.TEXT);
        for (int i = 0; i < guests.size(); i++) if (guestsY + i * 10 + 8 <= area.bottom()) c.text(guests.get(i), x0, guestsY + i * 10, Palette.MUTED);
    }
}
