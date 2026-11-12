package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.guide.Glossary;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.Marked;
import com.wormzjl.icheme.science.tutorial.guide.Progress;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The field guide's page (batch 2026-11-12-tutorial-system, P1; TUTORIAL_SYSTEM_PLAN.md section 3.3), with no Minecraft
 * class, so the screen, the gates and the render tool place and draw the same page. The title strip with the help and
 * theme keys; the chapters on the door at the left under their plate, the open one latched, a finished one with a done
 * mark; the open chapter's steps on the HMI glass at the right, each with its mark (done, current, open), its sentence
 * wrapped and never cut (a marked term underlined) and its media key ("Watch", "Open", or the two unit choices), beside
 * the sentence where the glass has room, under it where not. A step's card opens in place of the steps, with its caption,
 * its slider and Back. Panel coordinates; the steps scroll in the glass (content coordinates, moved up by the offset).
 * Designed at 464 by 254, checked at 320 by 240.
 */
public final class GuidePage {
    public static final String KEY = Guide.KEY;
    public static final int DESIGN_WIDTH = 464, DESIGN_HEIGHT = 254;
    /** The door's and the glass's top; a mark's side; the room between a step's mark and its text; between two steps. */
    public static final int TOP = DialogColumns.TOP, MARK = 8, MARK_GAP = 6, STEP_GAP = 8, KEY_H = Column.WIDGET, KEY_GAP = Column.BUTTON_GAP;
    /** The narrowest a step's text may get beside its keys; narrower, the keys go under it. */
    public static final int BESIDE_MIN = 100;

    private GuidePage() {
    }

    /** What the page shows: the guide, the player's progress, the open chapter, and the step whose card is open (null: the steps). */
    public record Input(Guide guide, Progress progress, String open, @Nullable String card) {
    }

    public enum Mark {
        DONE, CURRENT, OPEN;

        /** The mark's hover text's key. */
        public String hintKey() {
            return KEY + "mark." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A push button the page draws: its action key, place, word and hover (or null), and whether it stands latched. */
    public record Key(String key, Rect rect, String text, @Nullable String hint, boolean latched) {
    }

    /** One line of a step's sentence: its text's top left, and its marked terms' underlines (x0, x1 and the term). */
    public record Line(String text, int x, int y, List<Underline> underlines) {
    }

    public record Underline(int x0, int x1, String term) {
    }

    /** A step as placed (content coordinates): its id across the guide, its mark, its band, the mark's box, its lines, its keys, its text's width. */
    public record Row(String step, Mark mark, Rect rect, Rect markRect, List<Line> lines, List<Key> keys, int textWidth) {
    }

    /**
     * An open card's place (panel coordinates): the option keys over the drawing (action {@code card:option:<i>}), the
     * drawing, the caption's lines and top left, the control's label and slider, Back.
     */
    public record CardPlace(String step, Card card, List<Key> options, Rect drawing, List<String> caption, int captionX, int captionY, String label, int labelX,
                            Rect slider, Key back) {
        public CardPlace {
            options = List.copyOf(options);
        }

        public static final String OPTION = "card:option:";

        /** The slider's knob at {@code control}. */
        public Rect knob(double control) {
            int x = slider.x() + (int) Math.round((slider.w() - KNOB) * Math.clamp(control, 0, 1));
            return new Rect(x, slider.y() - 5, KNOB, 18);
        }

        /** The control at a pointer's x on the slider. */
        public double control(double x) {
            return Math.clamp((x - slider.x() - KNOB / 2.0) / Math.max(1, slider.w() - KNOB), 0, 1);
        }
    }

    public static final int KNOB = 8;
    /** An option key's width beyond its word. */
    public static final int OPTION_PAD = 8;

    /** The page as placed. */
    public record Page(int width, int height, String title, TitleStrip.Strip strip, Rect help, Rect theme, String themeHint,
                       Column door, Map<String, Key> chapters, Map<String, Boolean> finished, Rect well, Rect view, int content, boolean bar,
                       List<Row> rows, @Nullable CardPlace card, Function<String, String> tr) {
        public Page {
            chapters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(chapters));
            finished = Map.copyOf(finished);
            rows = List.copyOf(rows);
        }

        /** The scroll bar's place, right of the steps inside the glass, where they scroll. */
        public Rect barTrack() {
            return new Rect(view.right() - ScrollView.BAR, view.y(), ScrollView.BAR, view.h());
        }

        /** The largest scroll offset. */
        public int maxOffset() {
            return Math.max(0, content - view.h());
        }

        /**
         * Every anchor a tour may name, by key, in content coordinates for the steps ({@link #scrolls}) and panel
         * coordinates for the rest: {@code title}, {@code help}, {@code theme}, {@code chapters}, {@code chapter:<id>},
         * {@code step:<id>}, {@code mark:<id>}, each key's own ({@code media:<id>}, {@code units:use}, {@code units:keep}),
         * {@code step} (the current step's row, or the open chapter's first) and {@code media} (the current step's key, or
         * the chapter's first).
         */
        public Map<String, Rect> anchors() {
            var out = new LinkedHashMap<String, Rect>();
            out.put("title", strip.plate());
            out.put("help", help);
            out.put("theme", theme);
            var group = door.rows().getFirst().rect();
            out.put("chapters", new Rect(group.x(), group.y(), group.w(), group.h() - Column.GROUP_GAP));
            for (var e : chapters.entrySet()) out.put(e.getKey(), e.getValue().rect());
            Row current = null;
            Key media = null;
            for (var row : rows) {
                out.put("step:" + row.step(), row.rect());
                out.put("mark:" + row.step(), row.markRect());
                for (var key : row.keys()) out.put(key.key(), key.rect());
                if (row.mark() == Mark.CURRENT) current = row;
            }
            if (current == null && !rows.isEmpty()) current = rows.getFirst();
            if (current != null) {
                out.put("step", current.rect());
                if (!current.keys().isEmpty()) media = current.keys().getFirst();
            }
            for (var row : rows) if (media == null && !row.keys().isEmpty()) media = row.keys().getFirst();
            if (media != null) out.put("media", media.rect());
            if (card != null) {
                for (var key : card.options()) out.put(key.key(), key.rect());
                out.put("card:back", card.back().rect());
            }
            return out;
        }

        /** Whether an anchor lies on the scrolling glass. */
        public boolean scrolls(String anchor) {
            return anchor.equals("step") || anchor.equals("media") || anchor.startsWith("step:") || anchor.startsWith("mark:") || anchor.startsWith("media:")
                    || anchor.startsWith("units:");
        }

        /** {@code content} on the panel at scroll {@code offset}. */
        public Rect onPanel(Rect content, int offset) {
            return content.moved(0, -offset);
        }

        /** The offset that shows {@code content} whole, the nearest to {@code offset}. */
        public int reveal(Rect content, int offset) {
            int top = content.y() - view.y(), bottom = content.bottom() - view.y();
            int next = offset;
            if (bottom - next > view.h()) next = bottom - view.h();
            if (top < next) next = top;
            return Math.clamp(next, 0, maxOffset());
        }

        /** The action at panel point ({@code x}, {@code y}) with the steps scrolled by {@code offset}, or null. */
        public @Nullable String action(double x, double y, int offset) {
            if (help.contains(x, y)) return "help";
            if (theme.contains(x, y)) return "theme";
            for (var key : chapters.values()) if (!key.latched() && key.rect().contains(x, y)) return key.key();
            if (card != null) {
                for (var key : card.options()) if (key.rect().contains(x, y)) return key.key();
                return card.back().rect().contains(x, y) ? card.back().key() : null;
            }
            if (!view.contains(x, y)) return null;
            for (var row : rows) for (var key : row.keys()) if (onPanel(key.rect(), offset).contains(x, y)) return key.key();
            return null;
        }

        /** The hover text at panel point ({@code x}, {@code y}), or null: a key's, a mark's, a marked term's definition. */
        public @Nullable String hover(double x, double y, int offset) {
            if (help.contains(x, y)) return tr.apply(KEY + "help");
            if (theme.contains(x, y)) return themeHint;
            for (var e : chapters.entrySet()) if (e.getValue().rect().contains(x, y)) return e.getValue().hint();
            if (card != null || !view.contains(x, y)) return null;
            for (var row : rows) {
                for (var key : row.keys()) if (onPanel(key.rect(), offset).contains(x, y)) return key.hint();
                if (onPanel(row.markRect(), offset).contains(x, y)) return tr.apply(row.mark().hintKey());
                for (var line : row.lines())
                    for (var u : line.underlines()) {
                        int ly = line.y() - offset;
                        if (x >= u.x0() && x < u.x1() && y >= ly - 1 && y < ly + Measure.LINE)
                            return tr.apply(Glossary.wordKey(u.term())) + ": " + tr.apply(Glossary.definitionKey(u.term()));
                    }
            }
            return null;
        }

        /**
         * Draws the page in {@code skin}: the door, the title strip and its keys, the glass with the steps scrolled by
         * {@code offset} (or the open card in {@code state}), {@code hover} the action under the pointer. Loops of fills go in
         * one batch each.
         */
        public void draw(Canvas c, Skin skin, int offset, @Nullable String hover, Card.State state) {
            skin.page(c, new Rect(0, 0, width, height));
            skin.title(c, TitleStrip.TITLE_X, TitleStrip.TITLE_Y, strip.kind());
            skin.iconKey(c, help, Skin.Icon.HELP, "help".equals(hover));
            skin.iconKey(c, theme, Skin.Icon.THEME, "theme".equals(hover));
            door.draw(c, 0, skin);
            for (var e : chapters.entrySet()) {
                var key = e.getValue();
                var press = key.latched() ? Skin.Press.CURRENT : Skin.Press.REST;
                var r = key.rect();
                if (!finished.getOrDefault(e.getKey(), false)) skin.button(c, r, key.text(), press, key.key().equals(hover));
                else {
                    // P4b: a finished chapter's title stands centred right of its done mark, not under it.
                    skin.button(c, r, "", press, key.key().equals(hover));
                    mark(c, new Rect(r.x() + 5, r.y() + 5, MARK, MARK), Mark.DONE);
                    Skin.centred(c, new Rect(r.x() + 5 + MARK, r.y(), r.w() - 5 - MARK, r.h()), key.text(), skin.legend(press));
                }
            }
            skin.well(c, well);
            if (card != null) {
                drawCard(c, skin, card, state, hover, tr);
                return;
            }
            c.clip(view);
            c.batch(() -> {
                for (var row : rows) {
                    var r = onPanel(row.rect(), offset);
                    if (row.mark() == Mark.CURRENT) c.fill(r.x(), r.y(), r.right(), r.bottom(), DialogColumns.BAND);
                    mark(c, onPanel(row.markRect(), offset), row.mark());
                    for (var line : row.lines())
                        for (var u : line.underlines()) c.fill(u.x0(), line.y() - offset + 9, u.x1(), line.y() - offset + 10, Palette.ACCENT);
                }
            });
            for (var row : rows) {
                int colour = row.mark() == Mark.DONE ? Palette.MUTED : Palette.TEXT;
                for (var line : row.lines()) c.text(line.text(), line.x(), line.y() - offset, colour);
                for (var key : row.keys()) skin.button(c, onPanel(key.rect(), offset), key.text(), Skin.Press.REST, key.key().equals(hover));
            }
            c.unclip();
            if (bar) {
                var track = barTrack();
                int thumb = Math.max(12, track.h() * view.h() / Math.max(1, content));
                int y = track.y() + (track.h() - thumb) * offset / Math.max(1, maxOffset());
                skin.scrollbar(c, track, new Rect(track.x(), y, track.w(), thumb), false);
            }
        }
    }

    /** A step's mark on the glass: done a teal box with a tick, current an amber ring round an amber dot, open a grey ring. */
    public static void mark(Canvas c, Rect r, Mark mark) {
        c.batch(() -> {
            switch (mark) {
                case DONE -> {
                    c.fill(r.x(), r.y(), r.right(), r.bottom(), Palette.TEAL);
                    int[][] tick = {{1, 4}, {2, 5}, {3, 6}, {4, 5}, {5, 4}, {6, 3}, {6, 2}};
                    for (var p : tick) c.fill(r.x() + p[0], r.y() + p[1], r.x() + p[0] + 1, r.y() + p[1] + 1, Palette.TEXT);
                }
                case CURRENT -> {
                    c.outline(r.x(), r.y(), r.w(), r.h(), Palette.WARN);
                    c.fill(r.x() + 2, r.y() + 2, r.right() - 2, r.bottom() - 2, Palette.WARN);
                }
                case OPEN -> c.outline(r.x(), r.y(), r.w(), r.h(), Palette.LINE);
            }
        });
    }

    private static void drawCard(Canvas c, Skin skin, CardPlace p, Card.State state, @Nullable String hover, Function<String, String> tr) {
        double control = state.control();
        for (int i = 0; i < p.options().size(); i++) {
            var key = p.options().get(i);
            skin.button(c, key.rect(), key.text(), i == state.option() ? Skin.Press.CURRENT : Skin.Press.REST, key.key().equals(hover));
        }
        c.clip(p.drawing());
        p.card().draw(c, p.drawing(), state, skin, tr);
        c.unclip();
        for (int i = 0; i < p.caption().size(); i++) c.text(p.caption().get(i), p.captionX(), p.captionY() + i * Measure.LINE, Palette.TEXT);
        if (p.card().control() != Card.Control.NONE) {
            c.text(p.label(), p.labelX(), p.slider().y(), Palette.MUTED);
            var s = p.slider();
            c.fill(s.x(), s.y() + 3, s.right(), s.y() + 5, Palette.LINE);
            c.fill(s.x(), s.y() + 3, p.knob(control).x() + KNOB / 2, s.y() + 5, Palette.ACCENT);
            skin.button(c, p.knob(control), "", Skin.Press.REST, "card:slider".equals(hover));
        }
        skin.button(c, p.back().rect(), p.back().text(), Skin.Press.REST, p.back().key().equals(hover));
    }
    /** The door column's width at a panel {@code width}. */
    public static int doorWidth(int width) {
        return Math.clamp(width * 26 / 100, 96, 130);
    }

    /** The page of a panel {@code width} by {@code height}, its texts from {@code tr} (language key to line). */
    public static Page place(Input in, int width, int height, Measure m, Function<String, String> tr, String themeHint) {
        String title = tr.apply(KEY + "title");
        var strip = TitleStrip.placeKeys(width, 2, title, m);
        var keys = TitleStrip.keys(width, 2);
        var current = in.progress().current(in.guide());
        // The door: the chapters under their plate, one push button each, the open one latched.
        int doorW = doorWidth(width);
        var buttons = new ArrayList<Column.Row>();
        var finished = new LinkedHashMap<String, Boolean>();
        for (var chapter : in.guide().chapters()) {
            boolean open = chapter.id().equals(in.open()), done = in.progress().finished(chapter);
            finished.put("chapter:" + chapter.id(), done);
            buttons.add(new Column.Buttons(List.of(new Column.Button("chapter:" + chapter.id(), tr.apply(chapter.titleKey()), !open,
                    done ? tr.apply(KEY + "chapter_done") : null, open))));
        }
        var door = Column.place(List.of(new Column.Group(tr.apply(KEY + "chapters"), null, buttons)), 10, TOP, doorW, m, Column.Metrics.COMPACT);
        var chapters = new LinkedHashMap<String, Key>();
        for (var p : door.rows())
            if (p.row() instanceof Column.Buttons b)
                for (var button : b.buttons()) chapters.put(button.key(), new Key(button.key(), p.slots().get(button.key()), button.text(), button.hint(), button.latched()));
        // The glass right of the door, down to the foot.
        int glassX = 10 + doorW + 8;
        var well = new Rect(glassX, TOP, width - 10 - glassX, height - 8 - TOP);
        var view = FilmLayout.glass(PanelSkin.LIGHT, well);
        var chapter = in.guide().chapter(in.open());
        if (chapter == null) chapter = in.guide().chapters().getFirst();
        if (in.card() != null) {
            var step = in.guide().step(in.card());
            var card = step != null && step.media() instanceof Guide.Card named ? Cards.build(named.name()) : null;
            if (card != null)
                return new Page(width, height, title, strip, keys.get(1), keys.get(0), themeHint, door, chapters, finished, well, view, 0, false, List.of(),
                        card(in.card(), card, view, m, tr), tr);
        }
        var rows = rows(in, chapter, current, view.x() + 2, view.w() - 4, view.y() + 2, m, tr);
        int content = height(rows, view);
        boolean bar = content > view.h();
        if (bar) {
            rows = rows(in, chapter, current, view.x() + 2, view.w() - 6 - ScrollView.BAR, view.y() + 2, m, tr);
            content = height(rows, view);
        }
        return new Page(width, height, title, strip, keys.get(1), keys.get(0), themeHint, door, chapters, finished, well, view, content, bar, rows, null, tr);
    }

    private static int height(List<Row> rows, Rect view) {
        return rows.isEmpty() ? 0 : rows.getLast().rect().bottom() + 2 - view.y();
    }

    private static List<Row> rows(Input in, Guide.Chapter chapter, @Nullable String current, int x0, int w, int y0, Measure m, Function<String, String> tr) {
        var rows = new ArrayList<Row>();
        int y = y0;
        for (var step : chapter.steps()) {
            String id = chapter.id(step);
            var mark = in.progress().done(id) ? Mark.DONE : id.equals(current) ? Mark.CURRENT : Mark.OPEN;
            var words = keyWords(step, id, tr);
            int bw = 0;
            for (var word : words) bw = Math.max(bw, m.width(word[1]) + 12);
            bw = words.isEmpty() ? 0 : Math.max(48, bw);
            int textX = x0 + MARK + MARK_GAP, full = w - MARK - MARK_GAP;
            boolean beside = !words.isEmpty() && full - bw - 6 >= BESIDE_MIN;
            int tw = beside ? full - bw - 6 : full;
            var marked = Marked.parse(chapter.text(step, tr));
            var wrapped = m.wrap(marked.text(), tw);
            int textY = y + (beside ? (KEY_H - 8) / 2 : 1);
            var lines = new ArrayList<Line>();
            int from = 0;
            for (int i = 0; i < wrapped.size(); i++) {
                String text = wrapped.get(i);
                int start = Math.max(0, marked.text().indexOf(text, from));
                from = start + text.length();
                var underlines = new ArrayList<Underline>();
                for (var span : marked.spans()) {
                    int a = Math.max(span.start(), start) - start, b = Math.min(span.end(), from) - start;
                    if (a < b) underlines.add(new Underline(textX + m.width(text.substring(0, a)), textX + m.width(text.substring(0, b)), span.term()));
                }
                lines.add(new Line(text, textX, textY + i * Measure.LINE, underlines));
            }
            int textH = wrapped.size() * Measure.LINE;
            var keys = new ArrayList<Key>();
            int keysBottom = y;
            if (beside) {
                int kx = x0 + w - bw;
                for (int i = 0; i < words.size(); i++) keys.add(new Key(words.get(i)[0], new Rect(kx, y + i * (KEY_H + KEY_GAP), bw, KEY_H), words.get(i)[1], null, false));
                keysBottom = y + words.size() * (KEY_H + KEY_GAP) - KEY_GAP;
            } else if (!words.isEmpty()) {
                int ky = textY + textH + 3;
                boolean row = words.size() * bw + (words.size() - 1) * KEY_GAP <= full;
                for (int i = 0; i < words.size(); i++) {
                    var r = row ? new Rect(textX + i * (bw + KEY_GAP), ky, bw, KEY_H) : new Rect(textX, ky + i * (KEY_H + KEY_GAP), bw, KEY_H);
                    keys.add(new Key(words.get(i)[0], r, words.get(i)[1], null, false));
                    keysBottom = Math.max(keysBottom, r.bottom());
                }
            }
            int bottom = Math.max(textY + textH, keysBottom);
            var rect = new Rect(x0 - 2, y - 2, w + 4, bottom - y + 4);
            rows.add(new Row(id, mark, rect, new Rect(x0, textY, MARK, MARK), lines, keys, tw));
            y = bottom + 2 + STEP_GAP;
        }
        return rows;
    }

    /** A step's keys: action key and word. */
    private static List<String[]> keyWords(Guide.Step step, String id, Function<String, String> tr) {
        return switch (step.media()) {
            case Guide.Film ignored -> List.<String[]>of(new String[]{"media:" + id, tr.apply(KEY + "watch")});
            case Guide.Card ignored -> List.<String[]>of(new String[]{"media:" + id, tr.apply(KEY + "open")});
            case Guide.Units ignored -> List.of(new String[]{"units:use", tr.apply(KEY + "units.use")}, new String[]{"units:keep", tr.apply(KEY + "units.keep")});
            case Guide.Read ignored -> List.<String[]>of(new String[]{"read:" + id, tr.apply(KEY + "read")});
            case Guide.None ignored -> List.of();
        };
    }

    /** An open card in the glass: the drawing above, its caption under it, the slider and Back at the foot. */
    private static CardPlace card(String step, Card card, Rect view, Measure m, Function<String, String> tr) {
        String backText = tr.apply(KEY + "back");
        int bw = Math.max(48, m.width(backText) + 12);
        var back = new Key("card:back", new Rect(view.right() - bw - 2, view.bottom() - KEY_H - 2, bw, KEY_H), backText, null, false);
        String label = card.controlKey() == null ? "" : tr.apply(card.controlKey());
        int labelX = view.x() + 4, sliderX = labelX + m.width(label) + 8;
        var slider = new Rect(sliderX, back.rect().y() + (KEY_H - 8) / 2, Math.max(20, back.rect().x() - 10 - sliderX), 8);
        var caption = m.wrap(tr.apply(card.captionKey()), view.w() - 8);
        int captionY = back.rect().y() - 4 - caption.size() * Measure.LINE;
        // The options: soft keys across the top, each as wide as its word, flowing into a second row only where the glass is too narrow.
        var options = new ArrayList<Key>();
        var words = card.options();
        int top = view.y();
        if (!words.isEmpty()) {
            int x = view.x() + 2, y = view.y() + 2;
            for (int i = 0; i < words.size(); i++) {
                String word = tr.apply(words.get(i));
                int ow = Math.min(view.w() - 4, Math.max(24, m.width(word) + OPTION_PAD));
                if (x > view.x() + 2 && x + ow > view.right() - 2) {
                    x = view.x() + 2;
                    y += KEY_H + KEY_GAP;
                }
                options.add(new Key(CardPlace.OPTION + i, new Rect(x, y, ow, KEY_H), word, null, false));
                x += ow + KEY_GAP;
            }
            top = options.getLast().rect().bottom() + 2;
        }
        var drawing = new Rect(view.x(), top, view.w(), Math.max(10, captionY - 4 - top));
        return new CardPlace(step, card, options, drawing, caption, view.x() + 4, captionY, label, labelX, slider, back);
    }
}
