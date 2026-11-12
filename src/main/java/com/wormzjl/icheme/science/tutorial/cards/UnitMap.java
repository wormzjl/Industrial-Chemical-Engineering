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
     * Where each hit's tag stands: under its symbol, else over it, else right or left of it, then those places moved half a
     * tag aside, then a row further out; the first inside {@code area} that keeps {@link #TAG_GAP} from every tag placed
     * before and covers at most an eighth of itself with other symbols, else the first that keeps from the tags, else under it.
     */
    public List<Rect> tags(List<Hit> hits, Rect area, Measure m) {
        var placed = new ArrayList<Rect>();
        // Inside by one pixel: the tag's backing reaches a pixel past its words.
        var inner = new Rect(area.x() + 1, area.y() + 1, area.w() - 2, area.h() - 2);
        for (var h : hits) {
            var r = h.rect();
            int w = m.width(sheet.units().get(h.index()).name()), th = 9, cx = r.x() + (r.w() - w) / 2, cy = r.y() + (r.h() - th) / 2;
            var tries = new ArrayList<Rect>();
            for (int out = 0; out <= 1; out++)
                for (int aside : new int[]{0, -w / 2, w / 2}) {
                    int d = out * (th + 1);
                    tries.add(new Rect(cx + aside, r.bottom() + 2 + d, w, th));
                    tries.add(new Rect(cx + aside, r.y() - 2 - th - d, w, th));
                    tries.add(new Rect(r.right() + 3 + d, cy + aside / 3, w, th));
                    tries.add(new Rect(r.x() - 3 - w - d, cy + aside / 3, w, th));
                }
            Rect chosen = null, apart = null;
            for (var t : tries) {
                if (!t.inside(inner)) continue;
                boolean free = true;
                for (var p : placed) free &= !grown(t, TAG_GAP).overlaps(p);
                if (!free) continue;
                if (apart == null) apart = t;
                int covered = 0;
                for (var other : hits) if (other != h) covered += overlap(t, other.rect());
                if (covered * 8 <= w * th) {
                    chosen = t;
                    break;
                }
            }
            placed.add(chosen != null ? chosen : apart != null ? apart : tries.getFirst());
        }
        return placed;
    }

    /** Room kept between two tags, px. */
    static final int TAG_GAP = 2;

    private static Rect grown(Rect r, int by) {
        return new Rect(r.x() - by, r.y() - by, r.w() + 2 * by, r.h() + 2 * by);
    }

    private static int overlap(Rect a, Rect b) {
        int w = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x()), h = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
        return w > 0 && h > 0 ? w * h : 0;
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
