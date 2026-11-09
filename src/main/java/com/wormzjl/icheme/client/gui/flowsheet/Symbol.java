package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Sprite;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The flowsheet's equipment symbols by their design boxes, and where each lies in the atlas (batch 2026-11-02-process-simulator,
 * W6_SCREEN_PLAN.md section 3; decision D12, framed). Each symbol bakes to masks, white with the mask in alpha: its body, its marks
 * and, where it has a fire, a hot layer. {@code tools/heat-gui-renders/SymbolAtlas.java} bakes them from the same shapes the owner
 * approved. The packing is a plain function of this enum's order, so the game and the tool cannot disagree and no layout file is
 * needed. A column is three slices, for it is {@code 30 + 16 N} high: its trays lie at {@code 11 + 8 i}, so the cuts at 15 and
 * {@code 15 + 16 N} fall between trays, and the tray slice repeats whole.
 */
public enum Symbol {
    FEED(40, 18), PRODUCT(40, 18), PUMP(30, 34), COMPRESSOR(34, 34), TURBO(34, 34), VALVE(24, 24), MIXER(22, 26), SPLITTER(22, 26), FILTER(30, 26), EXCHANGER(64, 28), KETTLE(70, 34), AIR_COOLER(60, 32),
    HEATER(58, 88), CABIN(74, 74), DRUM(28, 60), LYING(64, 28), BOOT_DRUM(64, 28), TANK(54, 42), SPHERE(44, 44), ADSORBER(26, 60), CYCLONE(26, 60),
    COLUMN_TOP(44, 15), COLUMN_TRAY(44, 16), COLUMN_FOOT(44, 15), EJECTOR(50, 20), TURBINE(34, 34), CONDENSING(34, 34),
    REACTOR(34, 52), TRICKLE(26, 60), EBULLATED(26, 60), FCC(48, 56), COKER(44, 56), CFB(50, 60), GRATE(50, 60), FLUID_GAS(30, 60), FIXED_GAS(30, 60), COOLING_TOWER(50, 60), FLARE(20, 60);

    /** A symbol's masks: the body takes the look's fill, the marks its stroke, the hot layer {@link #FLAME}, untinted by the look. */
    public enum Layer {
        BODY, MARKS, HOT
    }

    public static final String TEXTURE = "icheme:textures/gui/flowsheet/symbols.png";
    /** Atlas pixels per design unit; the design units of margin round a mask, for an outline stands half outside its box. */
    public static final int PIXELS = 3, MARGIN = 1;
    public static final int FLAME = 0xFFEF9F27;
    /** The atlas's width; its height is what the packing needs. */
    public static final int ATLAS_WIDTH = 1024;

    public final int w, h;

    Symbol(int w, int h) {
        this.w = w;
        this.h = h;
    }

    private static final class Packing {
        static final Map<Symbol, Map<Layer, int[]>> CELLS = new EnumMap<>(Symbol.class);
        static final int HEIGHT;

        static {
            int x = 0, y = 0, row = 0;
            for (var symbol : values()) {
                var cells = new EnumMap<Layer, int[]>(Layer.class);
                for (var layer : symbol.layers()) {
                    int w = symbol.cellWidth(), h = symbol.cellHeight();
                    if (x + w > ATLAS_WIDTH) {
                        x = 0;
                        y += row;
                        row = 0;
                    }
                    cells.put(layer, new int[]{x, y});
                    x += w;
                    row = Math.max(row, h);
                }
                CELLS.put(symbol, cells);
            }
            HEIGHT = y + row;
        }
    }

    /** The atlas's height, which the packing needs. */
    public static int atlasHeight() {
        return Packing.HEIGHT;
    }

    /** Whether it has a fire, in {@link #FLAME}. */
    public boolean hot() {
        return this == HEATER || this == CABIN || this == CFB || this == GRATE || this == FIXED_GAS || this == FLARE;
    }

    public List<Layer> layers() {
        var layers = new ArrayList<>(List.of(Layer.BODY, Layer.MARKS));
        if (hot()) layers.add(Layer.HOT);
        return layers;
    }

    private int cellWidth() {
        return (w + 2 * MARGIN) * PIXELS;
    }

    private int cellHeight() {
        return (h + 2 * MARGIN) * PIXELS;
    }

    /** Design units its sprite stands over its box at the top. A slice joins the one above it, so it has none there. */
    public int top() {
        return this == COLUMN_TRAY || this == COLUMN_FOOT ? 0 : MARGIN;
    }

    /** Design units its sprite stands under its box. */
    public int foot() {
        return this == COLUMN_TOP || this == COLUMN_TRAY ? 0 : MARGIN;
    }

    /** The whole cell of {@code layer} in the atlas, margin included: where the tool bakes it. */
    public int[] cell(Layer layer) {
        var cell = Packing.CELLS.get(this).get(layer);
        return new int[]{cell[0], cell[1], cellWidth(), cellHeight()};
    }

    /** What {@code layer} draws: its cell, less a slice's margin where it joins the next. The margin stays in the atlas, so the linear filter finds the wall go on. */
    public Sprite sprite(Layer layer) {
        var cell = cell(layer);
        return new Sprite(TEXTURE, cell[0], cell[1] + (MARGIN - top()) * PIXELS, cell[2], (h + top() + foot()) * PIXELS, ATLAS_WIDTH, atlasHeight());
    }
}
