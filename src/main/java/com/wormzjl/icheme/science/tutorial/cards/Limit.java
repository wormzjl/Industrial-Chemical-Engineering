package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.column.operation.ColumnStatus;
import com.wormzjl.icheme.science.column.operation.Condition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "limit" (batch 2026-11-12-tutorial-system, P5 stage B; guide step {@code start/limit}): a running tower takes
 * the share of its set feed that its shortest input or output allows, and its status line names that one. Three real cases,
 * an option each: the feed short (the crude account holds half the set rate), the steam short (rule B: the tower takes the
 * whole feed and asks a new solve at the steam it has), a product full (its tank has no room). For each, the status line
 * exactly as the Operation tab shows it ({@link ColumnStatus#line} on the case's {@link Condition}), three meters (the feed,
 * the steam, the products' room, as shares of what the set feed needs; the short one in the warning colour) and one
 * sentence on what to do.
 */
public final class Limit implements Card {
    public static final String NAME = "limit";
    public static final String KEY = "tutorial.icheme.card." + NAME;

    /** A case: its condition, the share of the set feed it allows, and the meters' shares (feed, steam, room). */
    public record Case(String word, Condition condition, double alpha, double feed, double steam, double room) {
        /** The status line, as the Operation tab writes it. */
        public String line() {
            return ColumnStatus.line(true, List.of(new Condition.Active(condition, alpha)), alpha, Double.NaN, 0);
        }
    }

    /** The three cases, in the options' order. */
    public static final List<Case> CASES = List.of(
            new Case("feed", Condition.FEED_SHORT, .5, .5, 1, 1),
            new Case("steam", Condition.STEAM_SHORT, 1, 1, .4, 1),
            new Case("product", Condition.PRODUCT_FULL, 0, 1, 1, 0));
    private static final String[] METERS = {"feed", "steam", "room"};
    private static final int TRACK = 0xFF2B3740, OK = 0xFF56B4E9;

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
    public List<String> options() {
        return CASES.stream().map(c -> KEY + ".case." + c.word()).toList();
    }

    @Override
    public List<String> textKeys() {
        var out = new ArrayList<String>();
        out.add(KEY + ".status");
        for (var m : METERS) out.add(KEY + ".meter." + m);
        for (var c : CASES) out.add(KEY + ".do." + c.word());
        return out;
    }

    /** The case the option chooses. */
    public static Case of(int option) {
        return CASES.get(Math.clamp(option, 0, CASES.size() - 1));
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        var k = of(state.option());
        var m = c.measure();
        int x0 = area.x() + 4, w = area.w() - 8, y = area.y() + 3;
        // The status line, as the tab writes it, under its label.
        String label = tr.apply(KEY + ".status");
        var line = m.wrap(k.line(), w);
        var advice = m.wrap(tr.apply(KEY + ".do." + k.word()), w);
        double[] shares = {k.feed(), k.steam(), k.room()};
        int labelW = 0;
        for (var meter : METERS) labelW = Math.max(labelW, m.width(tr.apply(KEY + ".meter." + meter)));
        int barX = x0 + labelW + 6, barW = Math.max(10, x0 + w - barX);
        int lineY = y + 11, metersY = lineY + line.size() * 10 + 6, rowH = Math.clamp((area.bottom() - metersY - advice.size() * 10 - 6) / 3, 10, 16);
        int adviceY = Math.max(metersY + 3 * rowH + 4, area.bottom() - 2 - advice.size() * 10);
        int warnIndex = switch (k.condition()) {
            case FEED_SHORT -> 0;
            case STEAM_SHORT -> 1;
            default -> 2;
        };
        c.batch(() -> {
            c.text(label, x0, y, Palette.MUTED);
            c.fill(x0 - 2, lineY - 2, x0 + w + 2, lineY + line.size() * 10, 0xFF151C22);
            for (int i = 0; i < line.size(); i++) c.text(line.get(i), x0, lineY + i * 10, Palette.WARN);
            for (int i = 0; i < 3; i++) {
                int ry = metersY + i * rowH, bh = Math.max(4, rowH - 6);
                c.text(tr.apply(KEY + ".meter." + METERS[i]), x0, ry + (bh - 8) / 2, Palette.TEXT);
                c.fill(barX, ry, barX + barW, ry + bh, TRACK);
                int filled = (int) Math.round(Math.clamp(shares[i], 0, 1) * barW);
                if (filled > 0) c.fill(barX, ry, barX + filled, ry + bh, i == warnIndex ? Palette.WARN : OK);
                if (i == warnIndex) c.outline(barX - 1, ry - 1, barW + 2, bh + 2, Palette.WARN);
            }
            for (int i = 0; i < advice.size(); i++) c.text(advice.get(i), x0, adviceY + i * 10, Palette.TEXT);
        });
    }
}
