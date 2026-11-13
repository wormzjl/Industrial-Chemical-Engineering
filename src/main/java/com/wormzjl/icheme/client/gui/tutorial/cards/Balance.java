package com.wormzjl.icheme.client.gui.tutorial.cards;

import com.wormzjl.icheme.science.tutorial.cards.SmallUnitData;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "balance" (batch 2026-11-12-tutorial-system, P4b; guide step {@code simulator/balance}): what goes in comes out.
 * The bundled "Small crude unit"'s recorded run ({@link SmallUnitData}): the crude's bar, its mass flow beside it, and under
 * it, as long, the three products' bar cut at their shares (top, middle, bottom), each share named under its part.
 */
public final class Balance implements Card {
    public static final String NAME = "balance";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** The products' colours: the top's naphtha, the middle draw's diesel, the bottom's residue (the barrel's bands). */
    static final int[] COLOURS = {Barrel.BAND_COLOURS[1], Barrel.BAND_COLOURS[3], Barrel.BAND_COLOURS[5]};
    private static final int CRUDE = 0xFF6B4423;
    private final SmallUnitData.Run run;

    public Balance() {
        this(SmallUnitData.bundled());
    }

    Balance(SmallUnitData.Run run) {
        this.run = run;
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
        var out = new ArrayList<>(List.of(KEY + ".in", KEY + ".out"));
        for (var p : SmallUnitData.PRODUCTS) out.add(KEY + "." + p);
        return out;
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var m = c.measure();
        int x0 = area.x() + 4, w = area.w() - 8, y = area.y() + 3;
        double[] shares = new double[SmallUnitData.PRODUCTS.size()];
        for (int i = 0; i < shares.length; i++) shares[i] = run.share(SmallUnitData.PRODUCTS.get(i));
        String flow = state.massFlow().apply(run.crudeKgH() / 3600);
        int free = area.bottom() - y - 2;
        int barH = Math.clamp((free - 44) / 2, 8, 20);
        int inY = y + 10, outLabel = inY + barH + Math.max(6, (free - 2 * barH - 44) / 2), outY = outLabel + 10, words = outY + barH + 3;
        c.text(m.cut(tr.apply(KEY + ".in"), w - m.width(flow) - 8), x0, y, Palette.MUTED);
        c.text(flow, x0 + w - m.width(flow), y, Palette.WARN);
        c.text(m.cut(tr.apply(KEY + ".out"), w), x0, outLabel, Palette.MUTED);
        c.batch(() -> {
            Bars.bar(c, x0, inY, w, barH, new double[]{1}, new int[]{CRUDE});
            Bars.bar(c, x0, outY, w, barH, shares, COLOURS);
        });
        // Each product's word and share in the legend under the bar, in as many columns as fit (P4b render: the small window).
        var names = new java.util.ArrayList<String>();
        var colours = new java.util.ArrayList<Integer>();
        for (int i = 0; i < shares.length; i++) {
            names.add(Chain.fill(tr.apply(KEY + "." + SmallUnitData.PRODUCTS.get(i)), Bars.percent(shares[i])));
            colours.add(COLOURS[i]);
        }
        Bars.legend(c, x0, words, w, names, colours);
    }
}
