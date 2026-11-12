package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The bars the cards of chapters 3 and 4 draw (batch 2026-11-12-tutorial-system, P4b): a horizontal bar cut into
 * coloured shares, and a legend of named colours that wraps into as many columns as fit.
 */
final class Bars {
    static final int OUTLINE = 0xFFB3C0CA;

    private Bars() {
    }

    /** A bar from ({@code x}, {@code y}), {@code w} by {@code h}, of {@code shares} (summing to at most 1) in {@code colours}, outlined. Call inside a batch. */
    static void bar(Canvas c, int x, int y, int w, int h, double[] shares, int[] colours) {
        double at = 0;
        for (int i = 0; i < shares.length; i++) {
            int a = x + (int) Math.round(at * w);
            at += shares[i];
            int b = x + (int) Math.round(Math.min(1, at) * w);
            if (b > a) c.fill(a, y, b, y + h, colours[i]);
        }
        c.outline(x - 1, y - 1, w + 2, h + 2, OUTLINE);
    }

    /** The legend's height for {@code names} in width {@code w}: rows of 10 px. */
    static int legendHeight(Canvas c, List<String> names, int w) {
        int columns = columns(c, names, w);
        return (names.size() + columns - 1) / columns * 10;
    }

    private static int columns(Canvas c, List<String> names, int w) {
        int widest = 0;
        for (var n : names) widest = Math.max(widest, c.measure().width(n) + 12);
        return Math.clamp(w / Math.max(1, widest), 1, Math.max(1, names.size()));
    }

    /** The legend: each name with its colour's square, from ({@code x}, {@code y}) in width {@code w}. */
    static void legend(Canvas c, int x, int y, int w, List<String> names, List<Integer> colours) {
        int columns = columns(c, names, w), colW = w / columns;
        c.batch(() -> {
            for (int i = 0; i < names.size(); i++) {
                int ex = x + (i % columns) * colW, ey = y + (i / columns) * 10;
                c.fill(ex, ey + 1, ex + 6, ey + 7, colours.get(i));
            }
        });
        for (int i = 0; i < names.size(); i++) c.text(c.measure().cut(names.get(i), colW - 9), x + (i % columns) * colW + 9, y + (i / columns) * 10, Palette.TEXT);
    }

    /** The parts of crude with a share in any of {@code cuts}, in boiling order: their names (the glossary's words) and colours. */
    static List<Integer> partsIn(List<double[]> cuts) {
        var out = new ArrayList<Integer>();
        for (int b = 0; b < BarrelData.BANDS.size(); b++)
            for (var cut : cuts)
                if (cut[b] >= .005 && !out.contains(b)) out.add(b);
        return out;
    }

    static List<String> partNames(List<Integer> parts, Function<String, String> tr) {
        return parts.stream().map(b -> tr.apply(Barrel.bandKey(BarrelData.BANDS.get(b)))).toList();
    }

    static List<Integer> partColours(List<Integer> parts) {
        return parts.stream().map(b -> Barrel.BAND_COLOURS[b]).toList();
    }

    /** A whole percent. */
    static String percent(double share) {
        return Math.round(100 * share) + " %";
    }
}
