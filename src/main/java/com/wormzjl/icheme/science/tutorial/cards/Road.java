package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The card "road" (guide step {@code crude/road}): crude oil's road from the well to the daily products, nine boxes left
 * to right in rows with arrows between. Pressing a box shows under the boxes its sentence, the mod's equipment for it
 * (the constructor's short names) and a mark: built in this guide; in the mod and running in the process simulator; in
 * the mod as a shape only; not in the mod yet. The step is done when every box was pressed. The kinds are the
 * constructor's equipment ({@code ConstructorColumn.Equipment}); which of them the process simulator models is the
 * simulator's catalog's ({@code FlowsheetCatalog}, test code), held equal by {@code RoadCardTest}.
 */
public final class Road implements Card {
    public static final String NAME = "road";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** A box's size and the room between two boxes (the arrow's). */
    public static final int BOX_W = 52, BOX_H = 16, GAP = 10, ROW_GAP = 8;

    public enum Mark {
        GUIDE, RUNS, SHAPE, NOT_YET;

        public String key() {
            return KEY + ".mark." + name().toLowerCase(Locale.ROOT);
        }

        int colour() {
            return switch (this) {
                case GUIDE -> Palette.TEAL;
                case RUNS -> 0xFF79B4DE;
                case SHAPE -> Palette.WARN;
                case NOT_YET -> Palette.MUTED;
            };
        }
    }

    /** A box: its id (its lines' keys) and the constructor equipment that does its work in the mod. */
    public record Box(String id, List<String> kinds) {
        public String wordKey() {
            return KEY + "." + id;
        }

        public String sentenceKey() {
            return KEY + "." + id + ".text";
        }
    }

    public static final List<Box> BOXES = List.of(
            new Box("well", List.of()),
            new Box("clean", List.of()),
            new Box("boil", List.of("COLUMN", "BATCH_STILL")),
            new Box("vacuum", List.of("COLUMN", "EJECTOR", "LIQUID_RING_PUMP")),
            new Box("break", List.of("FCC_CONVERTER", "DELAYED_COKER")),
            new Box("reshape", List.of()),
            new Box("sulfur", List.of("TRICKLE_BED_REACTOR", "EBULLATED_BED_REACTOR")),
            new Box("blend", List.of("FLOATING_ROOF_TANK")),
            new Box("products", List.of("FLOATING_ROOF_TANK", "SPHERE")));
    /** The box this guide builds. */
    public static final String GUIDE_BOX = "boil";
    /** The kinds the process simulator models (its catalog's MODELLED), of those the boxes name. */
    public static final Set<String> MODELLED = Set.of("COLUMN", "BATCH_STILL", "EJECTOR", "LIQUID_RING_PUMP", "FLOATING_ROOF_TANK", "SPHERE");

    /** A box's mark. */
    public static Mark mark(Box box) {
        if (box.id().equals(GUIDE_BOX)) return Mark.GUIDE;
        if (box.kinds().isEmpty()) return Mark.NOT_YET;
        return box.kinds().stream().anyMatch(MODELLED::contains) ? Mark.RUNS : Mark.SHAPE;
    }

    /** A kind's short name's key in the constructor. */
    public static String shortKey(String kind) {
        return "gui.icheme.equipment_constructor.equipment." + kind.toLowerCase(Locale.ROOT) + ".short";
    }

    @Override
    public String captionKey() {
        return KEY;
    }

    @Override
    public Control control() {
        return Control.NONE;
    }

    @Override
    public String controlKey() {
        return null;
    }

    @Override
    public List<String> textKeys() {
        var out = new ArrayList<String>();
        out.add(KEY + ".press");
        out.add(KEY + ".kinds");
        for (var box : BOXES) {
            out.add(box.wordKey());
            out.add(box.sentenceKey());
            for (var kind : box.kinds()) if (!out.contains(shortKey(kind))) out.add(shortKey(kind));
        }
        for (var mark : Mark.values()) out.add(mark.key());
        return out;
    }

    /** Boxes in a row of {@code area}. */
    static int columns(Rect area) {
        return Math.clamp((area.w() - 8 + GAP) / (BOX_W + GAP), 1, BOXES.size());
    }

    @Override
    public List<Hit> hits(Rect area, int option) {
        int cols = columns(area), rowW = cols * BOX_W + (cols - 1) * GAP, x0 = area.x() + (area.w() - rowW) / 2;
        var out = new ArrayList<Hit>();
        for (int i = 0; i < BOXES.size(); i++)
            out.add(new Hit(i, new Rect(x0 + (i % cols) * (BOX_W + GAP), area.y() + 4 + (i / cols) * (BOX_H + ROW_GAP), BOX_W, BOX_H)));
        return out;
    }

    @Override
    public boolean done(double control, Set<Integer> visited) {
        return visited.size() >= BOXES.size();
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var hits = hits(area, state.option());
        var m = c.measure();
        c.batch(() -> {
            for (int i = 0; i < hits.size(); i++) {
                var r = hits.get(i).rect();
                boolean chosen = i == state.hit(), seen = state.visited().contains(i);
                c.fill(r.x(), r.y(), r.right(), r.bottom(), chosen ? 0xFF33475A : 0xFF1C252D);
                c.outline(r.x(), r.y(), r.w(), r.h(), chosen ? Palette.ACCENT : seen ? Palette.LINE : 0xFF4A5560);
                // A pressed box shows its mark's colour in its corner.
                if (seen) c.fill(r.right() - 5, r.y() + 2, r.right() - 2, r.y() + 5, mark(BOXES.get(i)).colour());
                if (i + 1 < hits.size()) arrow(c, r, hits.get(i + 1).rect());
            }
        });
        for (int i = 0; i < hits.size(); i++) {
            var r = hits.get(i).rect();
            String word = m.cut(tr.apply(BOXES.get(i).wordKey()), r.w() - 4);
            c.text(word, r.x() + (r.w() - m.width(word)) / 2, r.y() + (r.h() - 8) / 2, Palette.TEXT);
        }
        // Under the boxes: the pressed box's sentence, its equipment and its mark.
        int y = hits.getLast().rect().bottom() + 8, x = area.x() + 6, w = area.w() - 12;
        if (state.hit() < 0 || state.hit() >= BOXES.size()) {
            for (var line : m.wrap(tr.apply(KEY + ".press"), w)) if (y + 8 <= area.bottom()) {
                c.text(line, x, y, Palette.MUTED);
                y += 10;
            }
            return;
        }
        var box = BOXES.get(state.hit());
        var lines = new ArrayList<>(m.wrap(tr.apply(box.sentenceKey()), w));
        if (!box.kinds().isEmpty()) {
            var names = new ArrayList<String>();
            for (var kind : box.kinds()) names.add(tr.apply(shortKey(kind)));
            lines.addAll(m.wrap(Chain.fill(tr.apply(KEY + ".kinds"), String.join(", ", names)), w));
        }
        for (var line : lines) if (y + 8 <= area.bottom()) {
            c.text(line, x, y, Palette.TEXT);
            y += 10;
        }
        var mark = mark(box);
        if (y + 8 <= area.bottom()) {
            int my = y;
            c.batch(() -> c.fill(x, my + 1, x + 6, my + 7, mark.colour()));
            c.text(m.cut(tr.apply(mark.key()), w - 9), x + 9, y, mark.colour());
        }
    }

    /** The arrow from box {@code a} to box {@code b}: straight on in a row; down, back and down where the row wraps. */
    static void arrow(Canvas c, Rect a, Rect b) {
        int colour = Palette.MUTED;
        if (b.y() == a.y()) {
            int y = a.y() + a.h() / 2;
            c.fill(a.right() + 1, y, b.x() - 2, y + 1, colour);
            c.fill(b.x() - 4, y - 2, b.x() - 3, y + 3, colour);
            c.fill(b.x() - 3, y - 1, b.x() - 2, y + 2, colour);
            return;
        }
        int ax = a.x() + a.w() / 2, bx = b.x() + b.w() / 2, mid = a.bottom() + (b.y() - a.bottom()) / 2;
        c.fill(ax, a.bottom() + 1, ax + 1, mid, colour);
        c.fill(Math.min(ax, bx), mid, Math.max(ax, bx) + 1, mid + 1, colour);
        c.fill(bx, mid, bx + 1, b.y() - 1, colour);
        c.fill(bx - 2, b.y() - 3, bx + 3, b.y() - 2, colour);
    }
}
