package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A help tour's stop on the screen (batch 2026-11-12-tutorial-system, P1; TUTORIAL_SYSTEM_PLAN.md section 3.2), with no
 * Minecraft class, so the screens, the gate and the render tool place and draw the same overlay: the lit rectangle round
 * the stop's control, the four dim rectangles that tile the screen less it, the lit rectangle's frame, and the caption
 * bubble with its sentence and Back, Next (Done at the last stop) and Close, and "Show me" where the last stop offers a
 * film. The bubble stands beside the lit rectangle, never over it, always inside the screen: under it, over it, right of
 * it or left of it, the first that fits; where none does, the side with most room, clamped into the screen.
 */
public record TourLayout(Rect screen, Rect lit, List<Rect> dim, Rect bubble, List<String> lines, int textX, int textY, List<Key> keys) {
    /** The room round the control the lit rectangle keeps; the gap between it and the bubble; the screen's margin; the bubble's widest and its inset. */
    public static final int PAD = 2, GAP = 4, MARGIN = 4, WIDTH = 220, INSET = 6, KEY_H = Column.WIDGET, KEY_GAP = Column.BUTTON_GAP;
    /** The dim over the screen less the lit rectangle. */
    public static final int DIM = 0xA0000000;

    /** A bubble key: its action ({@code back}, {@code next}, {@code close}, {@code show}), place and word. */
    public record Key(String key, Rect rect, String text) {
    }

    /** The words of the bubble's keys. */
    public record Words(String back, String next, String done, String close, String show) {
    }

    /**
     * The stop on a screen {@code width} by {@code height}: {@code control} the stop's control (screen coordinates),
     * {@code sentence} its words, {@code first} and {@code last} where the stop stands in the tour, {@code show} whether
     * the bubble offers the page's film.
     */
    public static TourLayout of(int width, int height, Rect control, String sentence, boolean first, boolean last, boolean show, Words words, Measure m) {
        var screen = new Rect(0, 0, width, height);
        var lit = clip(new Rect(control.x() - PAD, control.y() - PAD, control.w() + 2 * PAD, control.h() + 2 * PAD), screen);
        int bw = Math.min(WIDTH, width - 2 * MARGIN);
        var lines = m.wrap(sentence, bw - 2 * INSET);
        int keysRow = KEY_H, showRow = show ? KEY_H + KEY_GAP : 0;
        int bh = INSET + lines.size() * Measure.LINE + 4 + showRow + keysRow + INSET;
        var bubble = beside(screen, lit, bw, bh);
        var keys = new ArrayList<Key>();
        int ky = bubble.bottom() - INSET - KEY_H, inner = bw - 2 * INSET, kw = (inner - 2 * KEY_GAP) / 3;
        keys.add(new Key("back", new Rect(bubble.x() + INSET, ky, kw, KEY_H), words.back()));
        keys.add(new Key("next", new Rect(bubble.x() + INSET + kw + KEY_GAP, ky, kw, KEY_H), last ? words.done() : words.next()));
        keys.add(new Key("close", new Rect(bubble.right() - INSET - kw, ky, kw, KEY_H), words.close()));
        if (show) keys.add(new Key("show", new Rect(bubble.x() + INSET, ky - KEY_GAP - KEY_H, inner, KEY_H), words.show()));
        if (first) keys.removeIf(k -> k.key().equals("back"));
        var dim = List.of(
                new Rect(0, 0, width, lit.y()),
                new Rect(0, lit.bottom(), width, height - lit.bottom()),
                new Rect(0, lit.y(), lit.x(), lit.h()),
                new Rect(lit.right(), lit.y(), width - lit.right(), lit.h()));
        return new TourLayout(screen, lit, dim, bubble, lines, bubble.x() + INSET, bubble.y() + INSET, keys);
    }

    private static Rect clip(Rect r, Rect screen) {
        int x0 = Math.clamp(r.x(), 0, screen.right()), y0 = Math.clamp(r.y(), 0, screen.bottom());
        int x1 = Math.clamp(r.right(), x0, screen.right()), y1 = Math.clamp(r.bottom(), y0, screen.bottom());
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    /** The bubble {@code w} by {@code h} beside {@code lit}: under, over, right, left, the first that fits; else the roomiest side, clamped. */
    static Rect beside(Rect screen, Rect lit, int w, int h) {
        int minX = MARGIN, maxX = screen.w() - MARGIN - w, minY = MARGIN, maxY = screen.h() - MARGIN - h;
        int alignX = Math.clamp(lit.x(), minX, Math.max(minX, maxX)), alignY = Math.clamp(lit.y(), minY, Math.max(minY, maxY));
        var under = new Rect(alignX, lit.bottom() + GAP, w, h);
        var over = new Rect(alignX, lit.y() - GAP - h, w, h);
        var right = new Rect(lit.right() + GAP, alignY, w, h);
        var left = new Rect(lit.x() - GAP - w, alignY, w, h);
        var inner = new Rect(MARGIN, MARGIN, Math.max(0, screen.w() - 2 * MARGIN), Math.max(0, screen.h() - 2 * MARGIN));
        for (var r : List.of(under, over, right, left)) if (r.inside(inner) && !r.overlaps(lit)) return r;
        // No side fits whole: the side with most room, clamped into the screen.
        int below = screen.h() - lit.bottom(), above = lit.y(), toRight = screen.w() - lit.right(), toLeft = lit.x();
        int most = Math.max(Math.max(below, above), Math.max(toRight, toLeft));
        var pick = most == below ? under : most == above ? over : most == toRight ? right : left;
        return new Rect(Math.clamp(pick.x(), minX, Math.max(minX, maxX)), Math.clamp(pick.y(), minY, Math.max(minY, maxY)), w, h);
    }

    /** The bubble key at ({@code x}, {@code y}), or null. */
    public @Nullable String action(double x, double y) {
        for (var key : keys) if (key.rect().contains(x, y)) return key.key();
        return null;
    }

    /** Draws the dim, the frame and the bubble in {@code skin}; {@code hover} the key under the pointer. The fills go in one batch. */
    public void draw(Canvas c, Skin skin, @Nullable String hover) {
        c.batch(() -> {
            for (var r : dim) if (r.w() > 0 && r.h() > 0) c.fill(r.x(), r.y(), r.right(), r.bottom(), DIM);
            c.outline(lit.x(), lit.y(), lit.w(), lit.h(), Palette.WARN);
            c.outline(lit.x() + 1, lit.y() + 1, lit.w() - 2, lit.h() - 2, Palette.WARN);
            c.fill(bubble.x(), bubble.y(), bubble.right(), bubble.bottom(), Palette.WARN);
            c.fill(bubble.x() + 1, bubble.y() + 1, bubble.right() - 1, bubble.bottom() - 1, Palette.BG);
        });
        for (int i = 0; i < lines.size(); i++) c.text(lines.get(i), textX, textY + i * Measure.LINE, Palette.TEXT);
        for (var key : keys) skin.button(c, key.rect(), key.text(), key.key().equals("next") ? Skin.Press.PRIMARY : Skin.Press.REST, key.key().equals(hover));
    }
}
