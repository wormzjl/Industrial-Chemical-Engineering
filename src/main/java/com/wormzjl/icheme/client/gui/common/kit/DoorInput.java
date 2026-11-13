package com.wormzjl.icheme.client.gui.common.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * The input routing a kit screen shares with the others (finding C6, batch 2026-11-13-codebase-review): what goes over
 * the page (the help tour) first, then the door. A screen calls these where its own order is the same, and adds its own
 * handlers after them, before the game's ({@code || super...}); a screen with another order keeps its own routing for
 * that event. Static and holding nothing, so no event allocates.
 */
public final class DoorInput {
    private DoorInput() {
    }

    /** What takes a screen's input while it is open, and draws over the page last: the screen's help tour ({@code TourHost}). */
    public interface Overlay {
        boolean isOpen();

        /** A press at screen point ({@code x}, {@code y}): true where the open overlay takes it. */
        boolean mouseClicked(Font font, int width, int height, double x, double y);

        /** Draws the open overlay over the whole screen. */
        void render(GuiGraphics g, Font font, int width, int height, int mouseX, int mouseY);
    }

    /**
     * A press at screen point ({@code x}, {@code y}): the overlay's where it takes it; else the focused entry's edit ends
     * where the press misses it ({@link SkinField#clickOutside}), and the door's view takes it (its bar) at door point
     * ({@code x - left}, {@code y - top}). False where neither took it: the screen's own handlers and the game's come next.
     */
    public static boolean press(Screen screen, Font font, Overlay tour, KitDoor door, double x, double y, int left, int top, int button) {
        if (tour.mouseClicked(font, screen.width, screen.height, x, y)) return true;
        SkinField.clickOutside(screen, x, y);
        return door.press(x - left, y - top, button);
    }

    /** The wheel at screen point ({@code x}, {@code y}): taken while the overlay is open; else the door's view scrolls where it is under the pointer. */
    public static boolean wheel(Overlay tour, KitDoor door, double x, double y, int left, int top, double delta) {
        if (tour.isOpen()) return true;
        return door.wheel(x - left, y - top, delta);
    }

    /** After the page: the open overlay over it, else the hover's {@code hint} at the pointer ({@link KitDoor#hint}). */
    public static void overlay(Screen screen, GuiGraphics g, Font font, Overlay tour, @Nullable String hint, int x, int y) {
        if (tour.isOpen()) tour.render(g, font, screen.width, screen.height, x, y);
        else KitDoor.hint(g, font, hint, x, y);
    }
}
