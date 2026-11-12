package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.tutorial.scene.Track;

import java.util.List;
import java.util.function.Function;

/**
 * The card "three parts" (guide step {@code welcome/dots}): a pot seen from the side holding dots in the films' three
 * colours ({@link Track.Kind}), a legend naming them light, middle and heavy, and a heat slider. As the heat rises the
 * light dots leave through the top first, then the middle ones; the heavy ones stay. The dots' places are fixed by their
 * index; the phase only makes them sway, so a frame is a pure function of phase and heat.
 */
public final class ThreeParts implements Card {
    public static final String NAME = "three_parts";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** Dots of each kind. */
    public static final int COUNT = 9;
    /** The heat over which each kind's dots leave, one after another, and how long one dot takes from its place to the top. */
    static final double LIGHT_FROM = .1, LIGHT_TO = .42, MIDDLE_FROM = .52, MIDDLE_TO = .84, WAY = .08;
    private static final int WALL = 0xFFB3C0CA, LIQUID = 0xFF2F4B5E, FLAME = 0xFFF08A24, FLAME_CORE = 0xFFFFD27A;

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
        return "tutorial.icheme.card.heat";
    }

    @Override
    public List<String> textKeys() {
        return List.of(KEY + ".light", KEY + ".middle", KEY + ".heavy");
    }

    /** The heat at which dot {@code index} of {@code kind} starts to leave; above 1 for a heavy dot, which never leaves. */
    public static double leaves(Track.Kind kind, int index) {
        double from, to;
        switch (kind) {
            case LIGHT -> {
                from = LIGHT_FROM;
                to = LIGHT_TO;
            }
            case MIDDLE -> {
                from = MIDDLE_FROM;
                to = MIDDLE_TO;
            }
            default -> {
                return 2;
            }
        }
        return from + (to - from) * index / (COUNT - 1.0);
    }

    /** How many dots of {@code kind} are still in the pot at {@code heat}: those that have not started to leave. */
    public static int inPot(Track.Kind kind, double heat) {
        int n = 0;
        for (int i = 0; i < COUNT; i++) if (heat < leaves(kind, i)) n++;
        return n;
    }

    /** The pot's body in {@code area}: the drawing's middle third, its foot above the fire's room. */
    static Rect pot(Rect area) {
        int legend = 14, fire = Math.max(8, area.h() / 7);
        int h = Math.max(20, (area.h() - legend - fire - 14) * 3 / 4), w = Math.min(area.w() / 2, h * 3 / 2);
        return new Rect(area.x() + (area.w() - w) / 2, area.bottom() - fire - h - 2, w, h);
    }

    @Override
    public void draw(Canvas c, Rect area, double phase, double control, Skin skin, Function<String, String> tr) {
        double heat = Math.clamp(control, 0, 1);
        var pot = pot(area);
        int neckW = Math.max(6, pot.w() / 6), neckX = pot.x() + (pot.w() - neckW) / 2, top = area.y() + 16;
        int remaining = 0;
        for (var kind : Track.Kind.values()) remaining += inPot(kind, heat);
        // The liquid falls as dots leave: full with every dot, half with the heavy ones alone.
        int full = pot.h() - 8, level = (int) Math.round(full * (.5 + .5 * remaining / (3.0 * COUNT)));
        int liquidTop = pot.bottom() - 2 - level;
        c.batch(() -> {
            // The neck and the pipe up to the top of the drawing: the vapour's way out.
            c.fill(neckX - 2, top, neckX, pot.y(), WALL);
            c.fill(neckX + neckW, top, neckX + neckW + 2, pot.y(), WALL);
            // The pot: walls, floor and shoulders round the neck.
            c.fill(pot.x(), pot.y(), neckX, pot.y() + 2, WALL);
            c.fill(neckX + neckW, pot.y(), pot.right(), pot.y() + 2, WALL);
            c.fill(pot.x(), pot.y(), pot.x() + 2, pot.bottom(), WALL);
            c.fill(pot.right() - 2, pot.y(), pot.right(), pot.bottom(), WALL);
            c.fill(pot.x(), pot.bottom() - 2, pot.right(), pot.bottom(), WALL);
            c.fill(pot.x() + 2, liquidTop, pot.right() - 2, pot.bottom() - 2, LIQUID);
            // The fire under the pot: tongues as tall as the heat.
            int fireTop = pot.bottom() + 1, room = area.bottom() - fireTop;
            int tongues = Math.max(3, pot.w() / 12);
            for (int t = 0; t < tongues; t++) {
                int x = pot.x() + 4 + t * (pot.w() - 8) / tongues, wide = Math.max(4, (pot.w() - 8) / tongues - 2);
                double sway = .85 + .15 * Math.sin(phase * 7 + t * 1.9);
                int tall = (int) Math.round(room * heat * sway);
                for (int r = 0; r < tall; r++) {
                    int inset = wide * r / Math.max(1, 2 * tall);
                    c.fill(x + inset, fireTop + r, x + wide - inset, fireTop + r + 1, r < tall / 2 ? FLAME_CORE : FLAME);
                }
            }
            // The dots: light near the top of the liquid, heavy near the floor; a leaving one rises to the neck and out.
            var kinds = Track.Kind.values();
            for (int k = 0; k < kinds.length; k++)
                for (int i = 0; i < COUNT; i++) {
                    var kind = kinds[k];
                    int colour = 0xFF000000 | kind.rgb;
                    double start = leaves(kind, i), way = (heat - start) / WAY;
                    // A dot's place: its band of the liquid by its kind, its column by its index.
                    double u = ((i * 0.618034 + k * 0.29) % 1), v = ((i * 0.381966 + k * 0.53) % 1);
                    int span = Math.max(4, level - 6);
                    int px = pot.x() + 5 + (int) Math.round(u * (pot.w() - 13) + 1.5 * Math.sin(phase * 2.1 + i + 3 * k));
                    int band = span / 3, py = liquidTop + 2 + (int) Math.round(k * band + v * Math.max(1, band - 3) + Math.sin(phase * 1.7 + 2 * i + k));
                    if (way < 0) {
                        c.fill(px, py, px + 3, py + 3, colour);
                        continue;
                    }
                    if (way >= 1) continue;
                    // Leaving: from its place to the neck's foot in the first third, then up the neck to the top.
                    int nx = neckX + neckW / 2 - 1, x, y;
                    if (way < 1 / 3.0) {
                        double f = way * 3;
                        x = (int) Math.round(px + (nx - px) * f);
                        y = (int) Math.round(py + (pot.y() + 2 - py) * f);
                    } else {
                        double f = (way - 1 / 3.0) * 1.5;
                        x = nx;
                        y = (int) Math.round(pot.y() + 2 + (top - pot.y() - 2) * f);
                    }
                    c.fill(x, y, x + 3, y + 3, colour);
                }
        });
        // The legend at the top left: a dot and its word for each kind.
        int x = area.x() + 4;
        var kinds = Track.Kind.values();
        var words = textKeys();
        for (int k = 0; k < kinds.length; k++) {
            c.fill(x, area.y() + 4, x + 5, area.y() + 9, 0xFF000000 | kinds[k].rgb);
            String word = tr.apply(words.get(k));
            c.text(word, x + 8, area.y() + 3, Palette.TEXT);
            x += 8 + c.measure().width(word) + 10;
        }
    }
}
