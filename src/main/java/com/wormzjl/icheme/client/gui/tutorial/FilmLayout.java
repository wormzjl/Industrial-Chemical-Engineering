package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a film's screen puts its parts (batch 2026-11-12-tutorial-system, P0), with no Minecraft class, so the render
 * tool draws the scene at the size the screen does: the title row with one pip a stop at its right, the scene on the HMI
 * glass under it, the caption under the scene (room for its longest caption's lines, so nothing moves between stops), and
 * the footer's Back, Replay and Next. Panel coordinates; designed at 464 by 254, checked at 320 by 240.
 */
public record FilmLayout(int width, int height, Rect scene, Rect caption, List<Rect> buttons, List<Rect> pips) {
    public static final int DESIGN_WIDTH = 464, DESIGN_HEIGHT = 254;
    /** The caption's room at least, lines; the scene's top; a pip's side and the gap between pips. */
    public static final int CAPTION_LINES = 3, SCENE_TOP = 26, PIP = 5, PIP_GAP = 4;

    /** The layout of a panel {@code width} by {@code height} for {@code stops} stops and captions of {@code captionLines} lines at most. */
    public static FilmLayout of(int width, int height, int stops, int captionLines) {
        var buttons = DialogColumns.footer(width, height, 3);
        int lines = Math.max(CAPTION_LINES, captionLines), footer = buttons.getFirst().y();
        var caption = new Rect(10, footer - 6 - lines * Measure.LINE, width - 20, lines * Measure.LINE);
        var scene = new Rect(10, SCENE_TOP, width - 20, Math.max(0, caption.y() - 4 - SCENE_TOP));
        var pips = new ArrayList<Rect>();
        int x = width - 10 - stops * PIP - Math.max(0, stops - 1) * PIP_GAP;
        for (int i = 0; i < stops; i++) pips.add(new Rect(x + i * (PIP + PIP_GAP), 12, PIP, PIP));
        return new FilmLayout(width, height, scene, caption, List.copyOf(buttons), List.copyOf(pips));
    }

    /** The lines the longest of {@code captions} takes at the caption's width {@code width - 20}. */
    public static int captionLines(List<String> captions, int width, Measure m) {
        int lines = 1;
        for (var text : captions) lines = Math.max(lines, m.wrap(text, width - 20).size());
        return lines;
    }

    /** The glass the scene draws on inside {@link #scene}, as {@code skin}'s well gives it. */
    public static Rect glass(Skin skin, Rect well) {
        return skin.well(NOTHING, well);
    }

    /** The view's pixels a design pixel on {@code glass}: the scene keeps its whole picture on a smaller window. */
    public static double fit(Rect glass) {
        return Math.min(glass.w() / (double) DESIGN_GLASS.w(), glass.h() / (double) DESIGN_GLASS.h());
    }

    /** A canvas that draws nothing: for a skin's geometry alone. */
    private static final Canvas NOTHING = new Canvas() {
        @Override
        public Measure measure() {
            return Measure.fixed(6);
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
        }

        @Override
        public void text(String text, int x, int y, int argb) {
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
        }

        @Override
        public void clip(Rect area) {
        }

        @Override
        public void unclip() {
        }
    };

    /** The glass at the design size, where a film's camera scale is pixels a metre. */
    public static final Rect DESIGN_GLASS = glass(PanelSkin.LIGHT, of(DESIGN_WIDTH, DESIGN_HEIGHT, 1, CAPTION_LINES).scene());
}
