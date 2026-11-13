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
 * The card "reflux" (batch 2026-11-12-tutorial-system, P4b; guide step {@code tower/reflux}): chapter 3's recorded runs
 * ({@link StillTraysData}) on a stepped slider, from the pot still to the column at its highest recorded reflux ratio. For
 * the chosen form: what its first cut's liquid holds, as a bar coloured by the crude's parts, and how long the cut takes,
 * as a bar against the slowest form; the readout gives the naphtha's share and the minutes of play. Purer and slower to
 * the right. Its numbers are the recording's.
 */
public final class RefluxCut implements Card {
    public static final String NAME = "reflux";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    private static final int TIME = 0xFF7A8794;
    private final List<StillTraysData.Form> forms;

    public RefluxCut() {
        this(StillTraysData.load());
    }

    RefluxCut(List<StillTraysData.Form> forms) {
        this.forms = List.copyOf(forms);
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
    public int steps() {
        return forms.size();
    }

    @Override
    public List<String> textKeys() {
        var out = new ArrayList<>(List.of(KEY + ".pot", KEY + ".ratio", KEY + ".reading", KEY + ".liquid", KEY + ".time"));
        for (var band : BarrelData.BANDS) out.add(Barrel.bandKey(band));
        return out;
    }

    /** The form at the slider's {@code control}. */
    public StillTraysData.Form form(double control) {
        return forms.get((int) Math.round(stepped(control) * (forms.size() - 1)));
    }

    /** The form's name on the card: the pot still, or the column's parts sent back for each part kept. */
    static String formName(StillTraysData.Form form, Function<String, String> tr, String key) {
        return form.stages() == 0 ? tr.apply(key + ".pot") : Chain.fill(tr.apply(key + ".ratio"), Integer.toString(form.stages()), Long.toString(Math.round(form.reflux())));
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var m = c.measure();
        var form = form(state.control());
        var cut = form.first();
        int x0 = area.x() + 4, w = area.w() - 8, y = area.y() + 3;
        String title = formName(form, tr, KEY);
        String readout = Chain.fill(tr.apply(KEY + ".reading"), Bars.percent(cut.part(1)), Long.toString(Math.round(cut.playMinutes())));
        c.text(m.cut(title, w), x0, y, Palette.TEXT);
        if (m.width(title) + 12 + m.width(readout) <= w) c.text(readout, x0 + w - m.width(readout), y, Palette.WARN);
        else c.text(m.cut(readout, w), x0, y += 10, Palette.WARN);
        var cuts = new ArrayList<double[]>();
        for (var f : forms) cuts.add(f.first().parts());
        var parts = Bars.partsIn(cuts);
        var names = Bars.partNames(parts, tr);
        int legend = Bars.legendHeight(c, names, w);
        int free = area.bottom() - 2 - legend - (y + 12);
        int barH = Math.clamp((free - 24) / 2, 6, 16), gap = Math.max(2, (free - 2 * barH - 20) / 2);
        int liquidY = y + 12 + 10, timeY = liquidY + barH + gap + 10;
        double slowest = 0;
        for (var f : forms) slowest = Math.max(slowest, f.first().playMinutes());
        double share = cut.playMinutes() / slowest;
        String minutes = Long.toString(Math.round(cut.playMinutes())) + " min";
        int timeW = Math.max(1, (int) Math.round(share * (w - m.width(minutes) - 6)));
        c.text(m.cut(tr.apply(KEY + ".liquid"), w), x0, liquidY - 10, Palette.MUTED);
        c.text(m.cut(tr.apply(KEY + ".time"), w), x0, timeY - 10, Palette.MUTED);
        c.batch(() -> {
            Bars.bar(c, x0, liquidY, w, barH, cut.parts(), Barrel.BAND_COLOURS);
            c.fill(x0, timeY, x0 + timeW, timeY + barH, TIME);
        });
        c.text(minutes, x0 + timeW + 4, timeY + Math.max(0, (barH - 8) / 2), Palette.TEXT);
        Bars.legend(c, x0, area.bottom() - 2 - legend, w, names, Bars.partColours(parts));
    }
}
