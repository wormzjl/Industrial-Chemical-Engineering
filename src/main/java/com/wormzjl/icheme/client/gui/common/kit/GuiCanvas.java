package com.wormzjl.icheme.client.gui.common.kit;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

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

    /** Tells the batch about a fill at these pose coordinates, in screen pixels: what waits under it goes out first. */
    private void held(float x0, float y0, float x1, float y1) {
        if (!HELD.open()) return;
        var m = g.pose().last().pose();
        HELD.fill(m.m00() * x0 + m.m30(), m.m11() * y0 + m.m31(), m.m00() * x1 + m.m30(), m.m11() * y1 + m.m31());
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        held(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        g.fill(x0, y0, x1, y1, argb);
    }

    // A line and an outline are fills as GuiGraphics draws them; through fill, the batch sees each.

    @Override
    public void text(String text, int x, int y, int argb) {
        hold(text, x, y, 1, () -> g.drawString(font, text, x, y, argb, false));
    }

    @Override
    public void text(String text, float x, float y, float scale, int argb) {
        hold(text, x, y, scale, () -> {
            g.pose().translate(x, y, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, text, 0, 0, argb, false);
        });
    }

    /** A text waits in a batch, under the pose it was drawn in, until what follows would cover it or the batch ends. */
    private void hold(String text, float x, float y, float scale, Runnable draw) {
        var m = new Matrix4f(g.pose().last().pose());
        float x1 = x + font.width(text) * scale, y1 = y + font.lineHeight * scale;
        HELD.text(m.m00() * x + m.m30(), m.m11() * y + m.m31(), m.m00() * x1 + m.m30(), m.m11() * y1 + m.m31(), () -> {
            g.pose().pushPose();
            g.pose().last().pose().set(m);
            draw.run();
            g.pose().popPose();
        });
    }

    /**
     * What the open batch holds back, on the render thread, across every canvas on the frame's {@code GuiGraphics}:
     * {@code drawManaged} ends managed drawing when its body returns, so only the outermost batch opens one.
     */
    private static final HeldDraws HELD = new HeldDraws();

    /**
     * The body in {@code drawManaged}: its fills wait in the shared buffer and go out as one draw call, where unmanaged
     * each fill flushes, and its texts and sprites wait as {@link HeldDraws} says. Order stays: the buffer holds one render
     * type at a time, a scissor change flushes when managed, and the sprites draw after the buffer.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void batch(Runnable body) {
        if (!HELD.begin(g::flush)) {
            try {
                body.run();
            } finally {
                HELD.end();
            }
            return;
        }
        try {
            g.drawManaged(() -> {
                body.run();
                HELD.end();
            });
        } finally {
            if (HELD.open()) HELD.end();
        }
    }

    /** Sends out what an open batch holds, before something draws on {@code GuiGraphics} past this canvas (a vanilla widget's text). */
    public static void release() {
        HELD.release();
    }

    /**
     * One linear page, no mip levels: at scale 2 and zoom 1 a texture pixel lies near a screen pixel, and vanilla has no
     * chain for a plain texture. The blit draws at once, so the fills a batch holds go out first.
     */
    @Override
    public void sprite(Sprite sprite, float x0, float y0, float x1, float y1, int argb) {
        var m = new Matrix4f(g.pose().last().pose());
        HELD.sprite(m.m00() * x0 + m.m30(), m.m11() * y0 + m.m31(), m.m00() * x1 + m.m30(), m.m11() * y1 + m.m31(), () -> {
            g.pose().pushPose();
            g.pose().last().pose().set(m);
            blit(sprite, x0, y0, x1, y1, argb);
            g.pose().popPose();
        });
    }

    private void blit(Sprite sprite, float x0, float y0, float x1, float y1, int argb) {
        g.flush();
        var location = ResourceLocation.parse(sprite.texture());
        Minecraft.getInstance().getTextureManager().getTexture(location).setFilter(true, false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        g.pose().pushPose();
        g.pose().translate(x0, y0, 0);
        g.pose().scale((x1 - x0) / sprite.w(), (y1 - y0) / sprite.h(), 1);
        g.blit(location, 0, 0, sprite.w(), sprite.h(), sprite.u(), sprite.v(), sprite.w(), sprite.h(), sprite.atlasWidth(), sprite.atlasHeight());
        g.pose().popPose();
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    @Override
    public void clip(Rect area) {
        HELD.clip();
        g.enableScissor(originX + area.x(), originY + area.y(), originX + area.right(), originY + area.bottom());
    }

    @Override
    public void unclip() {
        HELD.clip();
        g.disableScissor();
    }
}
