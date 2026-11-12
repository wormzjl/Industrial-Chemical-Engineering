package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.tutorial.guide.Marked;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The field guide's one-line tracker (batch 2026-11-12-tutorial-system, P1): a GUI layer at the top left with the current
 * step's sentence on one line, cut with an ellipsis, drawn in one batch. Hidden while a screen is open, the GUI is hidden
 * or the debug screen shows, when every step is done, and when {@code guide_hud} is off ({@link ClientConfig#guideHud}).
 */
public final class GuideHud {
    /** The line's left and top, its widest, and the ellipsis a cut line ends with. */
    public static final int X = 4, Y = 4, MAX_WIDTH = 300;
    public static final String ELLIPSIS = "…";

    private GuideHud() {
    }

    /** {@code text} on one line no wider than {@code width}, cut at a word where it can and ended with an ellipsis. */
    public static String line(String text, int width, Measure m) {
        if (m.width(text) <= width) return text;
        String head = m.cut(text, width - m.width(ELLIPSIS));
        int space = head.lastIndexOf(' ');
        if (space > head.length() / 2) head = head.substring(0, space);
        return head.stripTrailing() + ELLIPSIS;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || mc.player == null || mc.getDebugOverlay().showDebugScreen() || !ClientConfig.guideHud()) return;
        var guide = GuideClient.guide();
        var current = GuideClient.progress().current(guide);
        if (current == null) return;
        int slash = current.indexOf('/');
        var chapter = guide.chapter(current.substring(0, slash));
        var step = guide.step(current);
        if (chapter == null || step == null) return;
        var m = GuiCanvas.measure(mc.font);
        String text = line(Marked.parse(chapter.text(step, k -> Component.translatable(k).getString())).text(), Math.min(MAX_WIDTH, g.guiWidth() - 2 * X - 4), m);
        var canvas = new GuiCanvas(g, mc.font, 0, 0);
        canvas.batch(() -> {
            canvas.fill(X - 2, Y - 2, X + m.width(text) + 2, Y + 10, 0x90000000);
            canvas.text(text, X, Y, Palette.TEXT);
        });
    }
}
