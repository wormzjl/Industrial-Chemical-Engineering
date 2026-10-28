package com.wormzjl.icheme.client.gui.common.kit;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/**
 * The kit's canvas on the game's {@code GuiGraphics}, with the game font. Drawing coordinates are those of the pose it is
 * given (a container screen's labels: relative to its corner); the scissor works in screen coordinates, so a clip adds
 * {@code originX} and {@code originY}.
 */
public final class GuiCanvas implements Canvas {
    private final GuiGraphics g;
    private final Font font;
    private final Measure measure;
    private final int originX, originY;

    public GuiCanvas(GuiGraphics g, Font font, int originX, int originY) {
        this.g = g;
        this.font = font;
        this.measure = measure(font);
        this.originX = originX;
        this.originY = originY;
    }

    /** The game font as a measure. */
    public static Measure measure(Font font) {
        return new Measure() {
            @Override
            public int width(String text) {
                return font.width(text);
            }

            @Override
            public int height() {
                return font.lineHeight;
            }

            @Override
            public String cut(String text, int width) {
                return font.plainSubstrByWidth(text, Math.max(0, width));
            }

            @Override
            public List<String> wrap(String text, int width) {
                var lines = font.getSplitter().splitLines(text, Math.max(1, width), Style.EMPTY).stream().map(FormattedText::getString).toList();
                return lines.isEmpty() ? List.of("") : lines;
            }
        };
    }

    @Override
    public Measure measure() {
        return measure;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        g.fill(x0, y0, x1, y1, argb);
    }

    @Override
    public void hLine(int x0, int x1, int y, int argb) {
        g.hLine(x0, x1, y, argb);
    }

    @Override
    public void vLine(int x, int y0, int y1, int argb) {
        g.vLine(x, y0, y1, argb);
    }

    @Override
    public void outline(int x, int y, int w, int h, int argb) {
        g.renderOutline(x, y, w, h, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        g.drawString(font, text, x, y, argb, false);
    }

    @Override
    public void text(String text, float x, float y, float scale, int argb) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, argb, false);
        g.pose().popPose();
    }

    @Override
    public void clip(Rect area) {
        g.enableScissor(originX + area.x(), originY + area.y(), originX + area.right(), originY + area.bottom());
    }

    @Override
    public void unclip() {
        g.disableScissor();
    }
}
