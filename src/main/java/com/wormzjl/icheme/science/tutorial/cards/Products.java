package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "products" (guide step {@code crude/why}): the medium crude bar in its six bands only; the slider runs along it
 * and the band under it lifts, with its pictogram (flame, car, aeroplane, truck, the cracking arrow, road), its word and
 * share, and one sentence on what it is for. The shares are the recording's ({@link BarrelData}).
 */
public final class Products implements Card {
    public static final String NAME = "products";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    private static final int OUTLINE = 0xFFB3C0CA, MARK = 0xFFFFFFFF;
    private final BarrelData.Grade grade;

    public Products() {
        this(BarrelData.load().getFirst());
    }

    Products(BarrelData.Grade grade) {
        this.grade = grade;
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
    public List<String> textKeys() {
        var out = new ArrayList<String>();
        for (var band : BarrelData.BANDS) {
            out.add(Barrel.bandKey(band));
            out.add(KEY + "." + band);
        }
        return out;
    }

    /** The band under the slider at {@code control}: the one whose part of the bar holds it. */
    public int band(double control) {
        double at = 0, x = Math.clamp(control, 0, 1);
        var bands = grade.bands();
        for (int i = 0; i < bands.length; i++) {
            at += bands[i];
            if (x < at) return i;
        }
        return bands.length - 1;
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        int band = band(state.control());
        var m = c.measure();
        int x0 = area.x() + 6, w = area.w() - 12;
        int lift = Math.clamp(area.h() / 14, 5, 9), barH = Math.clamp(area.h() / 9, 8, 16);
        int barY = area.y() + 4 + lift, below = barY + barH + 6;
        int[] shares = BarrelData.percents(grade.bands());
        int unit = Math.clamp(Math.min((area.bottom() - 4 - below) / 16, w / 3 / 16), 1, 5), side = unit * 16;
        int textX = x0 + side + 10, textW = Math.max(20, x0 + w - textX);
        var sentence = m.wrap(tr.apply(KEY + "." + BarrelData.BANDS.get(band)), textW);
        int pointer = x0 + (int) Math.round(Math.clamp(state.control(), 0, 1) * w);
        c.batch(() -> {
            double at = 0;
            var bands = grade.bands();
            for (int i = 0; i < bands.length; i++) {
                int a = x0 + (int) Math.round(at * w);
                at += bands[i];
                int b = x0 + (int) Math.round(at * w);
                if (b <= a) continue;
                int top = i == band ? barY - lift : barY;
                c.fill(a, top, b, top + barH, Barrel.BAND_COLOURS[i]);
                if (i > 0) c.fill(a, barY, a + 1, barY + barH, 0xFF20262B);
            }
            c.outline(x0 - 1, barY - 1, w + 2, barH + 2, OUTLINE);
            c.fill(pointer, barY + barH + 1, pointer + 1, barY + barH + 4, MARK);
            pictogram(c, x0, below, unit, band);
        });
        c.text(tr.apply(Barrel.bandKey(BarrelData.BANDS.get(band))) + "  " + shares[band] + " %", textX, below, Barrel.BAND_COLOURS[band]);
        for (int i = 0; i < sentence.size(); i++) if (below + 12 + i * 10 + 8 <= area.bottom()) c.text(sentence.get(i), textX, below + 12 + i * 10, Palette.TEXT);
    }

    /** {x, y, w, h} on a 16 by 16 grid, with colours, for each band's pictogram. */
    private static final int FLAME = 0xFFF08A24, FLAME_CORE = 0xFFFFD27A, BODY = 0xFFD9573F, GLASS = 0xFFBFE3F5, WHEEL = 0xFF2B2F33, HUB = 0xFF9AA4AC,
            PLANE = 0xFFD5DEE5, CAB = 0xFF4F8FC0, BOX = 0xFFE3E8EC, CHAIN = 0xFFB8653A, ARROW = 0xFFFFFFFF, ROAD = 0xFF5A6168, LINE = 0xFFF2D46B;

    /** Rects of each pictogram: x, y, w, h, colour. */
    static final int[][][] PICTURES = {
            // Flame: fuel gas.
            {{6, 1, 4, 2, FLAME}, {5, 3, 6, 3, FLAME}, {4, 6, 8, 4, FLAME}, {3, 10, 10, 4, FLAME}, {4, 14, 8, 2, FLAME}, {7, 6, 2, 3, FLAME_CORE}, {6, 9, 4, 4, FLAME_CORE},
                    {6, 13, 4, 2, FLAME_CORE}},
            // Car: petrol.
            {{1, 8, 14, 4, BODY}, {4, 5, 8, 3, BODY}, {5, 6, 3, 2, GLASS}, {9, 6, 2, 2, GLASS}, {3, 12, 3, 3, WHEEL}, {10, 12, 3, 3, WHEEL}, {4, 13, 1, 1, HUB}, {11, 13, 1, 1, HUB}},
            // Aeroplane from above: jet fuel.
            {{1, 7, 14, 2, PLANE}, {15, 7, 1, 2, PLANE}, {7, 1, 3, 6, PLANE}, {7, 9, 3, 6, PLANE}, {1, 4, 2, 3, PLANE}, {1, 9, 2, 3, PLANE}, {12, 7, 2, 1, GLASS}},
            // Truck: diesel.
            {{1, 4, 9, 8, BOX}, {10, 7, 5, 5, CAB}, {11, 8, 3, 2, GLASS}, {2, 12, 3, 3, WHEEL}, {6, 12, 3, 3, WHEEL}, {11, 12, 3, 3, WHEEL}},
            // Cracking: a long chain broken into two shorter ones.
            {{0, 2, 16, 2, CHAIN}, {7, 5, 2, 5, ARROW}, {5, 10, 6, 1, ARROW}, {6, 11, 4, 1, ARROW}, {7, 12, 2, 1, ARROW}, {0, 14, 7, 2, CHAIN}, {9, 14, 7, 2, CHAIN}},
            // Road: bitumen.
            {{6, 0, 4, 4, ROAD}, {5, 4, 6, 4, ROAD}, {4, 8, 8, 4, ROAD}, {3, 12, 10, 4, ROAD}, {7, 1, 2, 2, LINE}, {7, 6, 2, 2, LINE}, {7, 11, 2, 3, LINE}},
    };

    /** Band {@code band}'s pictogram at ({@code x}, {@code y}), {@code unit} pixels a grid cell. */
    static void pictogram(Canvas c, int x, int y, int unit, int band) {
        for (var r : PICTURES[band]) c.fill(x + r[0] * unit, y + r[1] * unit, x + (r[0] + r[2]) * unit, y + (r[1] + r[3]) * unit, r[4]);
    }
}
