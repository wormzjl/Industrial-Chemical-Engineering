package com.wormzjl.icheme.client.gui.tutorial.cards;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The card "chain" (guide step {@code crude/what}): a straight-chain alkane of 1 to 40 carbons on a stepped slider, drawn
 * as a zig-zag of carbons with their hydrogens (scaled so 40 fit), a thermometer filled to its normal boiling point, and
 * its state at 20 °C as a word and a picture (gas, liquid, wax), with its everyday name.
 * <p>
 * Normal boiling and melting points, °C: C1 to C6 agree with the mod's property records ({@code tjl20_methane},
 * {@code tjl19_ethane}, {@code tjl19_propane}, {@code tjl19_n_butane}, {@code tjl19_n_pentane}, {@code chemsep_n_hexane};
 * {@code ChainCardTest} holds them within 1 K); C8 to C40 are literature values, CRC Handbook of Chemistry and Physics
 * (physical constants of organic compounds) and the NIST Chemistry WebBook, shown in whole degrees. The C40 boiling point
 * is an extrapolated handbook value: the compound cracks near it.
 */
public final class Chain implements Card {
    public static final String NAME = "chain";
    public static final String KEY = "tutorial.icheme.card." + NAME;
    /** The slider's positions: carbons in the chain. */
    public static final int[] CARBONS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 30, 40};
    /** Normal boiling point, °C, by position. */
    public static final double[] BOILS = {-161.5, -88.6, -42.1, -0.5, 36.1, 68.7, 125.7, 174.1, 216.3, 286.8, 343, 450, 522};
    /** Normal melting point, °C, by position. */
    public static final double[] MELTS = {-182, -183, -188, -138, -130, -95, -57, -30, -10, 18, 37, 66, 82};
    /** Room temperature, K: the state's reference. */
    public static final double ROOM = 293.15;
    /** The thermometer's scale, K: from -200 °C to 600 °C. */
    static final double SCALE_LOW = 73.15, SCALE_HIGH = 873.15;
    private static final int BOND = 0xFF8C9AA6, CARBON = 0xFFE3E8EC, HYDROGEN = 0xFF79B4DE, WALL = 0xFFB3C0CA, HOT = 0xFFE0604C, COLD = 0xFF79B4DE,
            LIQUID = 0xFF3E6E91, WAX = 0xFFE6D9A8, WAX_LINE = 0xFFC9B97E;

    public enum Phase {
        GAS, LIQUID, WAX
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
        return CARBONS.length;
    }

    @Override
    public List<String> textKeys() {
        var out = new ArrayList<>(List.of(KEY + ".atom", KEY + ".atoms", KEY + ".boils", KEY + ".known"));
        for (var p : Phase.values()) out.add(KEY + ".room." + p.name().toLowerCase(java.util.Locale.ROOT));
        for (var name : NAMES) if (name != null && !out.contains(nameKey(name))) out.add(nameKey(name));
        return out;
    }

    /** The everyday name by position (null: none). */
    static final String[] NAMES = {"natural_gas", null, "bottle_gas", "bottle_gas", "petrol", "petrol", "petrol", "petrol", "jet_diesel", "jet_diesel",
            "lubricating_oil", "wax_bitumen", "wax_bitumen"};

    private static String nameKey(String name) {
        return name == null ? null : KEY + ".name." + name;
    }

    /** The slider's position at {@code control}. */
    public static int position(double control) {
        return (int) Math.round(Math.clamp(control, 0, 1) * (CARBONS.length - 1));
    }

    /** The chain's state at 20 °C: gas if it boils below, wax if it melts above, else liquid. */
    public static Phase phase(int position) {
        double room = ROOM - 273.15;
        if (BOILS[position] < room) return Phase.GAS;
        if (MELTS[position] > room) return Phase.WAX;
        return Phase.LIQUID;
    }

    /** {@code pattern} with each {@code %s} replaced by the next argument. */
    static String fill(String pattern, String... args) {
        var out = new StringBuilder();
        int from = 0;
        for (var arg : args) {
            int at = pattern.indexOf("%s", from);
            if (at < 0) break;
            out.append(pattern, from, at).append(arg);
            from = at + 2;
        }
        return out.append(pattern.substring(from)).toString();
    }

    @Override
    public void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr) {
        int at = position(state.control()), n = CARBONS[at];
        var phase = phase(at);
        double boilK = BOILS[at] + 273.15;
        // The right column: the state's picture and the thermometer.
        int column = Math.clamp(area.w() / 4, 44, 64), colX = area.right() - column;
        int textW = colX - area.x() - 10;
        var m = c.measure();
        var lines = new ArrayList<String>();
        lines.addAll(m.wrap("C" + sub(n) + "H" + sub(2 * n + 2) + "   " + fill(tr.apply(KEY + (n == 1 ? ".atom" : ".atoms")), Integer.toString(n)), textW));
        if (NAMES[at] != null) lines.addAll(m.wrap(fill(tr.apply(KEY + ".known"), tr.apply(nameKey(NAMES[at]))), textW));
        lines.addAll(m.wrap(fill(tr.apply(KEY + ".boils"), state.temperature().apply(boilK)), textW));
        lines.addAll(m.wrap(fill(tr.apply(KEY + ".room." + phase.name().toLowerCase(java.util.Locale.ROOT)), state.temperature().apply(ROOM)), textW));
        for (int i = 0; i < lines.size(); i++) c.text(lines.get(i), area.x() + 6, area.y() + 4 + i * 10, Palette.TEXT);
        var chain = new Rect(area.x() + 6, area.y() + 8 + lines.size() * 10, Math.max(10, colX - area.x() - 14), 0);
        chain = new Rect(chain.x(), chain.y(), chain.w(), Math.max(10, area.bottom() - 4 - chain.y()));
        var box = chain;
        c.batch(() -> {
            drawChain(c, box, n);
            drawThermometer(c, new Rect(area.right() - 14, area.y() + 6, 8, area.h() - 12), boilK);
            int side = Math.min(column - 22, 30);
            drawState(c, new Rect(colX, area.y() + (area.h() - side) / 2, side, side), phase, state.phase());
        });
    }

    /** Digits as written in a formula (plain digits: the game font has no subscripts); 1 is left out. */
    static String sub(int count) {
        return count == 1 ? "" : Integer.toString(count);
    }

    /** The zig-zag chain of {@code n} carbons with their hydrogens, centred in {@code r}, its spacing fixed so 40 fit in the width. */
    static void drawChain(Canvas c, Rect r, int n) {
        // As wide as the chain's room allows (40 carbons always fit), no wider than 24 px a bond nor taller than the room.
        double dx = Math.min(Math.min(24, (r.h() - 4) / 1.95), (r.w() - 6) / (n - 1 + 1.6));
        double dy = Math.max(2, dx * .55), h = Math.max(2, dx * .7);
        int size = (int) Math.clamp(Math.round(dx * .45), 2, 5), hs = size >= 4 ? 2 : 1;
        double width = (n - 1) * dx, x0 = r.x() + (r.w() - width) / 2.0, cy = r.y() + r.h() / 2.0;
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x0 + i * dx;
            ys[i] = cy + (i % 2 == 0 ? -dy / 2 : dy / 2);
        }
        for (int i = 0; i + 1 < n; i++) line(c, xs[i], ys[i], xs[i + 1], ys[i + 1], BOND);
        for (int i = 0; i < n; i++) {
            // Two hydrogens away from the chain's bend; the ends carry a third along it.
            double away = i % 2 == 0 ? -1 : 1;
            var bonds = new ArrayList<double[]>();
            bonds.add(new double[]{-.5, away});
            bonds.add(new double[]{.5, away});
            if (n == 1) {
                bonds.clear();
                bonds.add(new double[]{-1, 0});
                bonds.add(new double[]{1, 0});
                bonds.add(new double[]{0, -1});
                bonds.add(new double[]{0, 1});
            } else {
                if (i == 0) bonds.add(new double[]{-1, 0});
                if (i == n - 1) bonds.add(new double[]{1, 0});
            }
            for (var b : bonds) {
                double hx = xs[i] + b[0] * h, hy = ys[i] + b[1] * h;
                line(c, xs[i], ys[i], hx, hy, BOND);
                int px = (int) Math.round(hx), py = (int) Math.round(hy);
                c.fill(px - hs / 2, py - hs / 2, px - hs / 2 + hs, py - hs / 2 + hs, HYDROGEN);
            }
        }
        for (int i = 0; i < n; i++) {
            int px = (int) Math.round(xs[i]) - size / 2, py = (int) Math.round(ys[i]) - size / 2;
            c.fill(px, py, px + size, py + size, CARBON);
        }
    }

    /** A line of single pixels from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}). */
    static void line(Canvas c, double x0, double y0, double x1, double y1, int argb) {
        int steps = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int s = 0; s <= steps; s++) {
            double f = steps == 0 ? 0 : s / (double) steps;
            int x = (int) Math.round(x0 + (x1 - x0) * f), y = (int) Math.round(y0 + (y1 - y0) * f);
            c.fill(x, y, x + 1, y + 1, argb);
        }
    }

    /** The scale's height fraction of {@code kelvin}. */
    static double scale(double kelvin) {
        return Math.clamp((kelvin - SCALE_LOW) / (SCALE_HIGH - SCALE_LOW), 0, 1);
    }

    /** The thermometer in {@code r} (its bulb at the foot), filled to {@code kelvin}; a cold tick at room temperature. */
    static void drawThermometer(Canvas c, Rect r, double kelvin) {
        int bulb = r.w(), tubeX = r.x() + r.w() / 2 - 2, top = r.y(), foot = r.bottom() - bulb;
        c.outline(tubeX - 1, top - 1, 6, foot - top + 2, WALL);
        c.fill(r.x(), foot, r.right(), r.bottom(), HOT);
        int level = foot - (int) Math.round((foot - top) * scale(kelvin));
        c.fill(tubeX, level, tubeX + 4, foot, HOT);
        int room = foot - (int) Math.round((foot - top) * scale(ROOM));
        c.fill(tubeX - 4, room, tubeX, room + 1, COLD);
    }

    /** The state's picture in {@code r}: a gas's loose dots, a liquid's level, a wax's block. */
    static void drawState(Canvas c, Rect r, Phase phase, double time) {
        c.outline(r.x(), r.y(), r.w(), r.h(), WALL);
        int x0 = r.x() + 2, y0 = r.y() + 2, w = r.w() - 4, h = r.h() - 4;
        switch (phase) {
            case GAS -> {
                for (int i = 0; i < 7; i++) {
                    double u = ((i + 1) * 0.7548776662466927 + time * .05 * (1 + i % 3)) % 1, v = ((i + 1) * 0.5698402909980532 + time * .07 * (1 + i % 2)) % 1;
                    int px = x0 + (int) (u * (w - 2)), py = y0 + (int) (v * (h - 2));
                    c.fill(px, py, px + 2, py + 2, HYDROGEN);
                }
            }
            case LIQUID -> {
                int top = y0 + h * 2 / 5;
                c.fill(x0, top, x0 + w, y0 + h, LIQUID);
                for (int x = 0; x < w; x++) {
                    int wave = (int) Math.round(DeterministicMath.sin(x * .6 + time * 3));
                    c.fill(x0 + x, top - 1 + wave, x0 + x + 1, top + wave, COLD);
                }
            }
            case WAX -> {
                c.fill(x0, y0 + h / 4, x0 + w, y0 + h, WAX);
                for (int y = y0 + h / 4 + 3; y < y0 + h; y += 4) c.fill(x0, y, x0 + w, y + 1, WAX_LINE);
            }
        }
    }
}
