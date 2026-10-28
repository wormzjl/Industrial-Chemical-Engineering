package com.wormzjl.icheme.client.gui.common.kit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The kit screens' own GUI scale (owner 2026-10-28: "This should be scaled to window size"; option A,
 * GUI_TOOLKIT_PLAN.md section 19): the largest whole scale at which the window is at least {@link #WIDTH} by
 * {@link #HEIGHT} GUI px, the size every screen is designed and rendered at, and never below 1. A whole scale keeps
 * the bitmap font sharp.
 *
 * <p>A screen with taller pages has its own design height (owner 2026-10-28: "on big screens these should all fit into
 * one page instead of requiring sliders", "It should scale"; option b, one scale for every device screen): it takes the
 * largest scale at which the window holds {@link #WIDTH} by that height, and where its door columns still do not fit
 * their view, the next lower whole scale that holds them, down to the {@link #minimum} a player reads the font at
 * ("Define a minimum scale user can see correctly, use slider below that"). Below that its view scrolls. A screen sets
 * its scale on the window when it builds its widgets ({@link #apply}), so the game's projection, mouse and scissor
 * follow it, and gives the window back the player's scale when it closes ({@link #restore}).
 */
public final class WindowScale {
    /** The design size in GUI px (the automatic scale makes a 1920 by 1080 window 480 by 270). */
    public static final int WIDTH = 464, HEIGHT = 254;

    /** Whether a screen's door columns fit their views, with no scroll bar, in a window {@code width} by {@code height} GUI px. */
    @FunctionalInterface
    public interface Fit {
        boolean fits(int width, int height);
    }

    private WindowScale() {
    }

    /**
     * The scale for a frame {@code width} by {@code height} screen px; with the forced Unicode font an even one, as the
     * game's own ({@code Window.calculateScale}), the next lower where the window has room for it.
     */
    public static int of(int width, int height, boolean unicode) {
        return holding(width, height, unicode, HEIGHT);
    }

    /**
     * The smallest scale a player reads the screens at: half the design size's ({@link #of(int, int, boolean)}), rounded
     * up, and never below 2 where that one is 2 or more; even with the Unicode font. A 1920 by 1080 window reads at 2 and
     * up, 2560 by 1440 at 3, 3840 by 2160 at 4, 1366 by 768 at 2, 800 by 600 at 1.
     */
    public static int minimum(int width, int height, boolean unicode) {
        int design = of(width, height, unicode), least = Math.max((design + 1) / 2, Math.min(design, 2));
        return unicode && least % 2 != 0 ? least + 1 : least;
    }

    /**
     * The scale for a screen designed {@link #WIDTH} by {@code designHeight} GUI px whose door columns fit as {@code fit}
     * says: the largest at which the window holds its design size, between {@link #minimum} and the design size's
     * ({@link #of(int, int, boolean)}); lowered a whole step (two with the Unicode font) while the columns do not fit,
     * down to the minimum, where they scroll. The GUI size at a scale is the game's: the frame over the scale, rounded up.
     */
    public static int of(int width, int height, boolean unicode, int designHeight, Fit fit) {
        int least = minimum(width, height, unicode), step = unicode ? 2 : 1;
        int scale = Math.clamp(holding(width, height, unicode, designHeight), least, of(width, height, unicode));
        while (scale > least && !fit.fits(-Math.floorDiv(-width, scale), -Math.floorDiv(-height, scale))) scale -= step;
        return scale;
    }

    /** The largest whole scale at which the frame holds {@link #WIDTH} by {@code designHeight}, at least 1 (with Unicode, even). */
    private static int holding(int width, int height, boolean unicode, int designHeight) {
        int scale = Math.max(1, Math.min(width / WIDTH, height / designHeight));
        return unicode && scale % 2 != 0 ? scale > 1 ? scale - 1 : 2 : scale;
    }

    /** Sets the screen's scale on the window, and the screen's size to the window's in GUI px at that scale. */
    public static void apply(Minecraft minecraft, Screen screen) {
        var window = minecraft.getWindow();
        set(minecraft, screen, of(window.getWidth(), window.getHeight(), minecraft.isEnforceUnicode()));
    }

    /** As {@link #apply(Minecraft, Screen)}, for a screen designed {@code designHeight} high whose door columns fit as {@code fit} says. */
    public static void apply(Minecraft minecraft, Screen screen, int designHeight, Fit fit) {
        var window = minecraft.getWindow();
        set(minecraft, screen, of(window.getWidth(), window.getHeight(), minecraft.isEnforceUnicode(), designHeight, fit));
    }

    private static void set(Minecraft minecraft, Screen screen, int scale) {
        var window = minecraft.getWindow();
        if (window.getGuiScale() != scale) window.setGuiScale(scale);
        screen.width = window.getGuiScaledWidth();
        screen.height = window.getGuiScaledHeight();
    }

    /**
     * Whether the screen fits better at a scale lower than the window's: its column grew past its view after it opened
     * (the first publication's readings). The screen then builds again at that scale, outside a frame (its tick). A
     * column that shrinks leaves the scale until the screen opens again, so the scale only steps down while it is open.
     */
    public static boolean lower(Minecraft minecraft, int designHeight, Fit fit) {
        var window = minecraft.getWindow();
        return of(window.getWidth(), window.getHeight(), minecraft.isEnforceUnicode(), designHeight, fit) < window.getGuiScale();
    }

    /** Gives the window back the player's scale, as the game sets it ({@code Minecraft.resizeDisplay}). */
    public static void restore(Minecraft minecraft) {
        var window = minecraft.getWindow();
        window.setGuiScale(window.calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode()));
    }
}
