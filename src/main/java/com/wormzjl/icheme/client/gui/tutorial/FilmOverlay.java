package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.tutorial.cards.ThreeParts;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Labels;
import com.wormzjl.icheme.science.tutorial.scene.Projection;
import com.wormzjl.icheme.science.tutorial.scene.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * A film's overlays on its glass (batch 2026-11-12-tutorial-system, P2b), with no Minecraft class, so the render tool
 * draws what the screen draws: the legend of the three dot colours at the top left, at most two readouts at the top
 * right (a name and a number with its unit: a temperature in the client's unit, a share in whole percent), the pointers
 * (a mouse or a key sign) at their scene points, and the labels, each in a dark box beside its point with a leader line,
 * placed by {@link Labels#place} clear of the rest and as far as it can off the drawn equipment. The scene's
 * {@link Projection} puts each scene point on the screen.
 */
public final class FilmOverlay {
    /** A label's and a readout's padding, px; a legend dot's side; a pointer sign's width and height. */
    static final int PAD = 3, DOT = 5, SIGN_W = 9, SIGN_H = 13, KEY = 11;
    /** The overlays' backing on the glass, the leader's colour, the pointer's lit part. */
    static final int BACK = 0xD8101418, LEADER = 0xFFEAF1F5, LIT = 0xFFEF9F27, EDGE = 0xFF536C7A;

    private FilmOverlay() {
    }

    /** Where each overlay of a frame stands, GUI px: the legend (null when hidden), the readouts', the pointers' and the labels' boxes (a label null where none fits). */
    public record Layout(Rect legend, List<Rect> readouts, List<String> readoutTexts, List<Rect> pointers, List<Rect> labels, List<double[]> anchors) {
    }

    /** The legend's words, light, middle, heavy: the three parts card's. */
    public static List<String> legendKeys() {
        return new ThreeParts().textKeys();
    }

    /** A readout's text: its name, then its value with its unit. */
    public static String readout(Frame.ReadoutFrame r, Function<String, String> tr, DoubleFunction<String> temperature) {
        String value;
        if (Double.isNaN(r.value())) value = "-";
        else value = r.reading() == Track.Reading.TEMPERATURE ? temperature.apply(r.value()) : String.format(Locale.ROOT, "%d %%", Math.round(100 * r.value()));
        return tr.apply(r.key()) + "  " + value;
    }

    /** The frame's overlays laid out on {@code glass}. */
    public static Layout layout(Frame frame, Projection p, Rect glass, Measure m, Function<String, String> tr, DoubleFunction<String> temperature) {
        int line = Measure.LINE;
        Rect legend = null;
        var keep = new ArrayList<Rect>();
        if (frame.legend()) {
            int w = PAD;
            for (var key : legendKeys()) w += DOT + 3 + m.width(tr.apply(key)) + 8;
            legend = new Rect(glass.x() + 3, glass.y() + 3, w - 8 + PAD, line + 2);
            keep.add(legend);
        }
        var readouts = new ArrayList<Rect>();
        var texts = new ArrayList<String>();
        int y = glass.y() + 3;
        for (var r : frame.readouts()) {
            var text = readout(r, tr, temperature);
            int w = m.width(text) + 2 * PAD;
            var box = new Rect(glass.right() - 3 - w, y, w, line + 2);
            readouts.add(box);
            texts.add(text);
            keep.add(box);
            y += line + 4;
        }
        var pointers = new ArrayList<Rect>();
        for (var pointer : frame.pointers()) {
            var s = p.screen(pointer.x(), pointer.y(), pointer.z());
            int w = pointer.sign() == Track.Sign.KEY ? KEY : SIGN_W, h = pointer.sign() == Track.Sign.KEY ? KEY : SIGN_H;
            // The sign stands just right of and below its point, as a cursor's tip.
            var box = new Rect((int) Math.round(s[0]), (int) Math.round(s[1]), w, h);
            pointers.add(box);
            keep.add(Labels.grown(box, 1));
        }
        var anchors = new ArrayList<Labels.Anchor>();
        var points = new ArrayList<double[]>();
        for (var label : frame.labels()) {
            var s = p.screen(label.x(), label.y(), label.z());
            anchors.add(new Labels.Anchor(s[0], s[1], m.width(tr.apply(label.key())) + 2 * PAD, line + 2));
            points.add(new double[]{s[0], s[1]});
        }
        var labels = anchors.isEmpty() ? List.<Rect>of() : Labels.place(anchors, glass, keep, avoid(frame, p, glass));
        return new Layout(legend, List.copyOf(readouts), List.copyOf(texts), List.copyOf(pointers), labels, List.copyOf(points));
    }

    /** Each drawn actor's box on the screen (every fourth vertex sampled), clipped to {@code glass}: where labels would rather not stand. */
    static List<Rect> avoid(Frame frame, Projection p, Rect glass) {
        var out = new ArrayList<Rect>();
        for (var actor : frame.actors()) {
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            int k = 0;
            for (var polygon : actor.polygons()) {
                var v = polygon.positions();
                if ((k++ & 3) != 0) continue;
                for (int i = 0; i < v.length; i += 3) {
                    var s = p.screen(v[i], v[i + 1], v[i + 2]);
                    x0 = Math.min(x0, s[0]);
                    y0 = Math.min(y0, s[1]);
                    x1 = Math.max(x1, s[0]);
                    y1 = Math.max(y1, s[1]);
                }
            }
            int l = (int) Math.max(glass.x(), Math.floor(x0)), t = (int) Math.max(glass.y(), Math.floor(y0)), r = (int) Math.min(glass.right(), Math.ceil(x1)),
                    b = (int) Math.min(glass.bottom(), Math.ceil(y1));
            if (r > l && b > t) out.add(new Rect(l, t, r - l, b - t));
        }
        return out;
    }

    /** Draws the frame's overlays as {@code layout} placed them. */
    public static void draw(Canvas c, Frame frame, Layout layout, Function<String, String> tr) {
        var m = c.measure();
        c.batch(() -> {
            // The labels' leaders first, so a box covers its own line's end.
            for (int i = 0; i < layout.labels().size(); i++) {
                var box = layout.labels().get(i);
                if (box == null) continue;
                var a = layout.anchors().get(i);
                var end = Labels.nearest(box, a[0], a[1]);
                line(c, a[0], a[1], end[0], end[1], LEADER);
                c.fill((int) Math.round(a[0]) - 1, (int) Math.round(a[1]) - 1, (int) Math.round(a[0]) + 2, (int) Math.round(a[1]) + 2, LEADER);
            }
            for (var box : layout.labels()) if (box != null) plate(c, box);
            for (var box : layout.readouts()) plate(c, box);
            if (layout.legend() != null) {
                plate(c, layout.legend());
                int x = layout.legend().x() + PAD;
                for (int k = 0; k < Track.Kind.values().length; k++) {
                    int yy = layout.legend().y() + (layout.legend().h() - DOT) / 2;
                    c.fill(x, yy, x + DOT, yy + DOT, 0xFF000000 | Track.Kind.values()[k].rgb);
                    x += DOT + 3 + m.width(tr.apply(legendKeys().get(k))) + 8;
                }
            }
            for (int i = 0; i < layout.pointers().size(); i++) sign(c, frame.pointers().get(i), layout.pointers().get(i));
        });
        for (int i = 0; i < layout.labels().size(); i++) {
            var box = layout.labels().get(i);
            if (box != null) c.text(tr.apply(frame.labels().get(i).key()), box.x() + PAD, box.y() + 2, Palette.TEXT);
        }
        for (int i = 0; i < layout.readouts().size(); i++) {
            var box = layout.readouts().get(i);
            c.text(layout.readoutTexts().get(i), box.x() + PAD, box.y() + 2, Palette.TEXT);
        }
        if (layout.legend() != null) {
            int x = layout.legend().x() + PAD;
            for (var key : legendKeys()) {
                var word = tr.apply(key);
                c.text(word, x + DOT + 3, layout.legend().y() + 2, Palette.TEXT);
                x += DOT + 3 + m.width(word) + 8;
            }
        }
        for (int i = 0; i < layout.pointers().size(); i++) {
            var pointer = frame.pointers().get(i);
            if (pointer.sign() != Track.Sign.KEY) continue;
            var box = layout.pointers().get(i);
            c.text(pointer.text(), box.x() + (box.w() - m.width(pointer.text())) / 2 + 1, box.y() + 2, Palette.TEXT);
        }
    }

    private static void plate(Canvas c, Rect box) {
        c.fill(box.x(), box.y(), box.right(), box.bottom(), BACK);
        c.outline(box.x(), box.y(), box.w(), box.h(), EDGE);
    }

    /** A mouse (its pressed button lit) or a key cap. */
    private static void sign(Canvas c, Frame.PointerFrame pointer, Rect box) {
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        if (pointer.sign() == Track.Sign.KEY) {
            c.fill(x, y, x + w, y + h, 0xFF26292C);
            c.outline(x, y, w, h, LEADER);
            return;
        }
        // The mouse: its body, the line between its buttons, the pressed one lit.
        c.fill(x, y, x + w, y + h, 0xFFDADDE0);
        c.outline(x, y, w, h, 0xFF26292C);
        int mid = x + w / 2, buttons = y + h * 2 / 5;
        boolean right = pointer.sign() == Track.Sign.RIGHT_CLICK;
        c.fill(right ? mid + 1 : x + 1, y + 1, right ? x + w - 1 : mid, buttons, LIT);
        c.fill(mid, y + 1, mid + 1, buttons, 0xFF26292C);
        c.fill(x + 1, buttons, x + w - 1, buttons + 1, 0xFF26292C);
    }

    /** A one-pixel line from (x0, y0) to (x1, y1), a fill a pixel step. */
    static void line(Canvas c, double x0, double y0, double x1, double y1, int argb) {
        int steps = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : i / (double) steps;
            int x = (int) Math.round(x0 + (x1 - x0) * f), y = (int) Math.round(y0 + (y1 - y0) * f);
            c.fill(x, y, x + 1, y + 1, argb);
        }
    }
}
