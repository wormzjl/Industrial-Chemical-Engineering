package com.wormzjl.icheme.client.gui.tutorial.cards;

import com.wormzjl.icheme.science.tutorial.cards.StillTraysData;
import com.wormzjl.icheme.science.tutorial.cards.BarrelData;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "compare" (batch 2026-11-12-tutorial-system, P4b; guide step {@code tower/compare}): two bars, the pot still's
 * first cut and the column's at its highest recorded reflux ratio ({@link StillTraysData}), each coloured by the crude's
 * parts, with its naphtha share beside its name. Nothing to move: the step is the comparison.
 */
public final class CompareCut implements Card {
    public static final String NAME = "compare";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    private final StillTraysData.Form pot, column;

    public CompareCut() {
        this(StillTraysData.pot(), StillTraysData.columns().getLast());
    }

    CompareCut(StillTraysData.Form pot, StillTraysData.Form column) {
        this.pot = pot;
        this.column = column;
    }

    /** The two forms compared: the pot still, then the column. */
    public List<StillTraysData.Form> forms() {
        return List.of(pot, column);
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
        var out = new ArrayList<>(List.of(KEY + ".title", KEY + ".naphtha", RefluxCut.KEY + ".pot", RefluxCut.KEY + ".ratio"));
        for (var band : BarrelData.BANDS) out.add(Barrel.bandKey(band));
        return out;
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var m = c.measure();
        int x0 = area.x() + 4, w = area.w() - 8, y = area.y() + 3;
        c.text(m.cut(tr.apply(KEY + ".title"), w), x0, y, Palette.TEXT);
        var parts = Bars.partsIn(List.of(pot.first().parts(), column.first().parts()));
        var names = Bars.partNames(parts, tr);
        int legend = Bars.legendHeight(c, names, w);
        int top = y + 14, free = area.bottom() - 2 - legend - 4 - top;
        int barH = Math.clamp((free - 22) / 2, 6, 18), step = barH + 12 + Math.max(0, (free - 2 * (barH + 12)) / 2);
        var forms = forms();
        for (int i = 0; i < forms.size(); i++) {
            var form = forms.get(i);
            int ly = top + i * step;
            String name = RefluxCut.formName(form, tr, RefluxCut.KEY);
            String naphtha = Chain.fill(tr.apply(KEY + ".naphtha"), Bars.percent(form.first().part(1)));
            c.text(m.cut(name, w - m.width(naphtha) - 8), x0, ly, Palette.MUTED);
            c.text(naphtha, x0 + w - m.width(naphtha), ly, Palette.WARN);
        }
        c.batch(() -> {
            for (int i = 0; i < forms.size(); i++) Bars.bar(c, x0, top + i * step + 10, w, barH, forms.get(i).first().parts(), Barrel.BAND_COLOURS);
        });
        Bars.legend(c, x0, area.bottom() - 2 - legend, w, names, Bars.partColours(parts));
    }
}
