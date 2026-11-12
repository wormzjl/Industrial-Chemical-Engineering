package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.flowsheet.SheetLayout;
import com.wormzjl.icheme.client.gui.flowsheet.SheetPainter;
import com.wormzjl.icheme.client.gui.flowsheet.SheetState;
import com.wormzjl.icheme.client.gui.flowsheet.SheetView;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The crude unit's map (batch 2026-11-12-tutorial-system, P5; guide step {@code unit/map}): the bundled "Small crude unit"
 * ({@code flowsheet_examples/small_crude_unit.json}) drawn as the process simulator draws it ({@link SheetPainter}: its own
 * symbols, lines and names), fitted to the card. Each unit's symbol is a place to press, and opens that unit's equipment
 * page ({@link #link}): the pumps the pump's, E-101 the exchanger's, H-101 the vertical heater's (the type Size all proposes
 * for its duty), V-101 the control valve's, T-101 the column's, E-102 the air cooler's (its outlet stays above the air's
 * split). Its step is done once one page was opened from it. No run: the map shows the sheet, never its results.
 */
public final class UnitMap implements Card {
    public static final String NAME = "unit_map";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    public static final String SHEET = "/assets/icheme/flowsheet_examples/small_crude_unit.json";
    /** The page each of the sheet's unit types opens. */
    public static final Map<String, String> PAGES = Map.of("pump", EquipmentInfo.itemId(EquipmentShape.Kind.PUMP), "exchanger",
            EquipmentInfo.itemId(EquipmentShape.Kind.EXCHANGER), "fired_heater", EquipmentInfo.itemId(EquipmentShape.Kind.VERTICAL_HEATER), "valve", "pressure_control_valve",
            "column", EquipmentInfo.itemId(EquipmentShape.Kind.COLUMN), "cooler", EquipmentInfo.itemId(EquipmentShape.Kind.AIR_COOLER));
    /** The layout's font: a fixed one, so the places to press and the drawing agree wherever they are asked for. */
    static final Measure M = Measure.fixed(6);
    private final Flowsheet sheet;
    private final SheetLayout layout;
    private final SheetState state;

    public UnitMap() {
        this.sheet = sheet();
        this.layout = SheetLayout.of(sheet, M);
        this.state = new SheetState(sheet, null, null, 0, null);
    }

    /** The bundled sheet, read once. */
    public static Flowsheet sheet() {
        try (var in = UnitMap.class.getResourceAsStream(SHEET)) {
            return FlowsheetFile.read(Objects.requireNonNull(in, SHEET).readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Flowsheet flowsheet() {
        return sheet;
    }

    public SheetLayout layout() {
        return layout;
    }

    /** The view fitted to the card's {@code area}. */
    SheetView view(Rect area) {
        var view = new SheetView();
        view.layout(area);
        view.fit(layout.bounds());
        return view;
    }

    @Override
    public void draw(Canvas c, Rect area, State s, Skin skin, Function<String, String> tr) {
        c.batch(() -> drawMap(c, area, s, tr));
    }

    /** The sheet, the tags and the ring, in one batch. */
    private void drawMap(Canvas c, Rect area, State s, Function<String, String> tr) {
        var view = view(area);
        SheetPainter.draw(c, layout, state, view, null, null, (key, args) -> {
            String line = tr.apply("gui.icheme." + key);
            try {
                return args.length == 0 ? line : String.format(java.util.Locale.ROOT, line, args);
            } catch (java.util.IllegalFormatException bad) {
                return line;
            }
        });
        // Each unit's tag under its symbol (the painter hides its own names at a card's zoom), the symbol last pressed ringed.
        var hits = hits(area, s.option());
        var tags = tags(hits, area, c.measure());
        for (int i = 0; i < hits.size(); i++) {
            var t = tags.get(i);
            c.fill(t.x() - 1, t.y() - 1, t.right() + 1, t.bottom(), 0xC0101418);
            c.text(sheet.units().get(hits.get(i).index()).name(), t.x(), t.y(),
                    hits.get(i).index() == s.hit() ? com.wormzjl.icheme.client.gui.common.kit.Palette.ACCENT : com.wormzjl.icheme.client.gui.common.kit.Palette.MUTED);
        }
        for (var h : hits)
            if (h.index() == s.hit()) c.outline(h.rect().x() - 1, h.rect().y() - 1, h.rect().w() + 2, h.rect().h() + 2, com.wormzjl.icheme.client.gui.common.kit.Palette.ACCENT);
    }

    /**
     * Where each hit's tag stands, in {@code hits}' order: the place round its symbol, inside {@code area}, that meets no
     * symbol and keeps {@link #TAG_GAP} from every tag; of those the one that crosses the fewest stream lines and stands
     * nearest its symbol. The most hemmed-in unit takes its place first. Where none is free, the place that meets no
     * symbol and the fewest tags.
     */
    public List<Rect> tags(List<Hit> hits, Rect area, Measure m) {
        var view = view(area);
        // Inside by one pixel: the tag's backing reaches a pixel past its words.
        var inner = new Rect(area.x() + 1, area.y() + 1, area.w() - 2, area.h() - 2);
        var lines = new ArrayList<int[]>();
        for (var line : layout.lines()) {
            int[] pts = line.points();
            for (int i = 0; i + 3 < pts.length; i += 2)
                lines.add(new int[]{(int) Math.round(view.canvasX(pts[i])), (int) Math.round(view.canvasY(pts[i + 1])), (int) Math.round(view.canvasX(pts[i + 2])),
                        (int) Math.round(view.canvasY(pts[i + 3]))});
        }
        var tries = new ArrayList<List<Rect>>();
        for (var h : hits) tries.add(candidates(h, m.width(sheet.units().get(h.index()).name()), inner, hits));
        // The units with the fewest free places go first.
        var order = new ArrayList<Integer>();
        for (int i = 0; i < hits.size(); i++) order.add(i);
        order.sort(java.util.Comparator.comparingInt(i -> tries.get(i).size()));
        var placed = new Rect[hits.size()];
        for (int i : order) {
            var r = hits.get(i).rect();
            Rect best = null;
            double bestCost = Double.MAX_VALUE;
            for (var t : tries.get(i)) {
                int met = 0;
                for (var q : placed) if (q != null && grown(t, TAG_GAP).overlaps(q)) met++;
                double cost = met * 1e6 + 2 * crossed(t, lines) + distance(t, r);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = t;
                }
            }
            int w = m.width(sheet.units().get(hits.get(i).index()).name());
            placed[i] = best != null ? best : new Rect(r.x() + (r.w() - w) / 2, r.bottom() + 2, w, TAG_H);
        }
        return List.of(placed);
    }

    /** A tag's height, px, and the room kept between two tags and between a tag and a symbol. */
    static final int TAG_H = 9, TAG_GAP = 3, SYMBOL_GAP = 1;

    /** The places a tag {@code w} wide may take round {@code h}'s symbol: inside {@code inner}, clear of every symbol. */
    private static List<Rect> candidates(Hit h, int w, Rect inner, List<Hit> hits) {
        var r = h.rect();
        var out = new ArrayList<Rect>();
        int cx = r.x() + (r.w() - w) / 2, cy = r.y() + (r.h() - TAG_H) / 2;
        for (int out2 = 0; out2 <= 2; out2++) {
            int d = out2 * (TAG_H + 2);
            // Centred, half a tag aside, a whole tag aside, and flush with the symbol's left or right edge.
            for (int shift : new int[]{0, -w / 2, w / 2, -w, w, r.x() - cx, r.right() - w - cx}) {
                out.add(new Rect(cx + shift, r.bottom() + 2 + d, w, TAG_H));
                out.add(new Rect(cx + shift, r.y() - 2 - TAG_H - d, w, TAG_H));
            }
            for (int shift : new int[]{0, -TAG_H, TAG_H}) {
                out.add(new Rect(r.right() + 3 + d, cy + shift, w, TAG_H));
                out.add(new Rect(r.x() - 3 - w - d, cy + shift, w, TAG_H));
            }
        }
        out.removeIf(t -> {
            if (!t.inside(inner)) return true;
            for (var other : hits) if (grown(t, SYMBOL_GAP).overlaps(other.rect())) return true;
            return false;
        });
        return out;
    }

    /** How many pixels of stream line run through {@code t} (with its backing's pixel round it). */
    private static int crossed(Rect t, List<int[]> lines) {
        var g = grown(t, 1);
        int n = 0;
        for (var l : lines) {
            int x0 = Math.min(l[0], l[2]), x1 = Math.max(l[0], l[2]), y0 = Math.min(l[1], l[3]), y1 = Math.max(l[1], l[3]);
            int w = Math.min(x1 + 1, g.right()) - Math.max(x0, g.x()), hgt = Math.min(y1 + 1, g.bottom()) - Math.max(y0, g.y());
            if (w > 0 && hgt > 0) n += Math.max(w, hgt);
        }
        return n;
    }

    /** The gap between a tag and its symbol, px. */
    private static double distance(Rect t, Rect r) {
        int dx = Math.max(0, Math.max(r.x() - t.right(), t.x() - r.right())), dy = Math.max(0, Math.max(r.y() - t.bottom(), t.y() - r.bottom()));
        // Under or over the symbol reads best: a side place costs a little more.
        return dx + dy + (dx > 0 ? 3 : 0);
    }

    private static Rect grown(Rect r, int by) {
        return new Rect(r.x() - by, r.y() - by, r.w() + 2 * by, r.h() + 2 * by);
    }

    /** Each unit's box on the card, in the sheet's unit order. */
    @Override
    public List<Hit> hits(Rect area, int option) {
        var view = view(area);
        var out = new ArrayList<Hit>();
        var units = sheet.units();
        for (int i = 0; i < units.size(); i++) {
            var box = layout.box(units.get(i).id());
            if (box == null) continue;
            var r = box.rect();
            int x0 = (int) Math.floor(view.canvasX(r.x())), y0 = (int) Math.floor(view.canvasY(r.y())), x1 = (int) Math.ceil(view.canvasX(r.right())),
                    y1 = (int) Math.ceil(view.canvasY(r.bottom()));
            out.add(new Hit(i, new Rect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0))));
        }
        return out;
    }

    /** The page unit {@code hit} opens: its type's. */
    @Override
    public @Nullable String link(int hit) {
        var units = sheet.units();
        return hit < 0 || hit >= units.size() ? null : PAGES.get(units.get(hit).type());
    }

    /** Done once a page was opened from the map. */
    @Override
    public boolean done(double control, Set<Integer> visited) {
        return !visited.isEmpty();
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
        return List.of();
    }
}
