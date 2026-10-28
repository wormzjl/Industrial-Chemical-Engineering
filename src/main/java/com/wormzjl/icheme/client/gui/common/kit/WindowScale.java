package com.wormzjl.icheme.client.gui.common.kit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The kit screens' own GUI scale (owner 2026-10-28: "This should be scaled to window size"; option A,
 * GUI_TOOLKIT_PLAN.md section 19): the largest whole scale at which the window is at least {@link #WIDTH} by
 * {@link #HEIGHT} GUI px, the size every screen is designed and rendered at, and never below 1. A whole scale keeps
 * the bitmap font sharp. A screen sets it on the window when it builds its widgets ({@link #apply}), so the game's
 * projection, mouse and scissor follow it, and gives the window back the player's scale when it closes
 * ({@link #restore}).
 */
public final class WindowScale {
    /** The design size in GUI px (the automatic scale makes a 1920 by 1080 window 480 by 270). */
    public static final int WIDTH = 464, HEIGHT = 254;

    private WindowScale() {
    }

    /**
     * The scale for a frame {@code width} by {@code height} screen px; with the forced Unicode font an even one, as the
     * game's own ({@code Window.calculateScale}), the next lower where the window has room for it.
     */
    public static int of(int width, int height, boolean unicode) {
        int scale = Math.max(1, Math.min(width / WIDTH, height / HEIGHT));
        return unicode && scale % 2 != 0 ? scale > 1 ? scale - 1 : 2 : scale;
    }

    /** Sets the screen's scale on the window, and the screen's size to the window's in GUI px at that scale. */
    public static void apply(Minecraft minecraft, Screen screen) {
        var window = minecraft.getWindow();
        int scale = of(window.getWidth(), window.getHeight(), minecraft.isEnforceUnicode());
        if (window.getGuiScale() != scale) window.setGuiScale(scale);
        screen.width = window.getGuiScaledWidth();
        screen.height = window.getGuiScaledHeight();
    }

    /** Gives the window back the player's scale, as the game sets it ({@code Minecraft.resizeDisplay}). */
    public static void restore(Minecraft minecraft) {
        var window = minecraft.getWindow();
        window.setGuiScale(window.calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode()));
    }
}
