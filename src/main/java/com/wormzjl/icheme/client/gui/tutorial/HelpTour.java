package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.guide.Tour;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/**
 * A screen's help tour (batch 2026-11-12-tutorial-system, P1): the {@code ?} key opens it. The screen dims, one control is
 * lit, one sentence says what it is for ({@link TourLayout}). While open it takes all input: Left, Right and Enter step,
 * Escape closes; on a stop that waits, a click on the lit control goes through to the screen and the tour steps once it
 * has. The host's resolver maps an anchor to the control's rectangle on the screen, scrolling a door to show it first,
 * or to null where this page has none: such a stop is skipped. A tour sends nothing to the server and changes no widget,
 * so the host's rebuilds leave it at its stop.
 */
public final class HelpTour {
    /** Where the host shows an anchor: its control's rectangle on the screen, or null where this page has none; with {@code reveal}, a door scrolls to show it first. */
    public interface Resolver {
        @Nullable Rect at(String anchor, boolean reveal);
    }

    private static Map<String, Tour> tours;
    private @Nullable Tour tour;
    private Resolver resolver = (a, reveal) -> null;
    private int stop;
    private @Nullable Runnable onFinished;

    /** The tour of {@code page}, or null where it has none. */
    public static @Nullable Tour of(String page) {
        if (tours == null) tours = TourFile.load();
        return tours.get(page);
    }

    public boolean isOpen() {
        return tour != null;
    }

    /** Opens {@code tour} at its first stop that this page shows; {@code onFinished} runs when the player steps past its last (null: nothing). */
    public void open(Tour tour, Resolver resolver, @Nullable Runnable onFinished) {
        this.tour = tour;
        this.resolver = resolver;
        this.onFinished = onFinished;
        stop = -1;
        next();
    }

    public void close() {
        tour = null;
    }

    private @Nullable Rect at(int index) {
        return at(index, false);
    }

    private @Nullable Rect at(int index, boolean reveal) {
        return tour == null || index < 0 || index >= tour.stops().size() ? null : resolver.at(tour.stops().get(index).anchor(), reveal);
    }

    private boolean last() {
        for (int i = stop + 1; tour != null && i < tour.stops().size(); i++) if (at(i) != null) return false;
        return true;
    }

    private boolean first() {
        for (int i = stop - 1; i >= 0; i--) if (at(i) != null) return false;
        return true;
    }

    private void next() {
        if (tour == null) return;
        int i = stop + 1;
        while (i < tour.stops().size() && at(i) == null) i++;
        if (i >= tour.stops().size()) {
            var done = onFinished;
            close();
            if (done != null && stop >= 0) done.run();
            return;
        }
        stop = i;
    }

    private void back() {
        int i = stop - 1;
        while (i >= 0 && at(i) == null) i--;
        if (i >= 0) stop = i;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** The stop as laid out on a screen {@code width} by {@code height}, or null where its control is gone. */
    private @Nullable TourLayout layout(Font font, int width, int height) {
        var control = at(stop, true);
        if (tour == null || control == null) return null;
        boolean last = last();
        var words = new TourLayout.Words(tr(Tour.KEY + "back"), tr(Tour.KEY + "next"), tr(Tour.KEY + "done"), tr(Tour.KEY + "close"), tr(Tour.KEY + "show_me"));
        return TourLayout.of(width, height, control, tr(tour.key(stop)), first(), last, last && tour.film() != null, words, GuiCanvas.measure(font));
    }

    /** Draws the open stop over the whole screen. */
    public void render(GuiGraphics g, Font font, int width, int height, int mouseX, int mouseY) {
        var layout = layout(font, width, height);
        if (layout == null) {
            // The control left this page (a rebuild, another page): the next stop that has one, else the tour ends unfinished.
            if (tour != null) {
                if (last()) close();
                else next();
            }
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        layout.draw(new GuiCanvas(g, font, 0, 0), ClientConfig.skin(), layout.action(mouseX, mouseY));
        g.pose().popPose();
    }

    /**
     * A press while open: true where the tour takes it; false where it goes through to the screen (the lit control of a
     * stop that waits, which the tour then steps past).
     */
    public boolean mouseClicked(Font font, int width, int height, double x, double y) {
        var layout = layout(font, width, height);
        if (layout == null) return false;
        var action = layout.action(x, y);
        if (action != null) {
            switch (action) {
                case "back" -> back();
                case "next" -> next();
                case "close" -> close();
                case "show" -> show();
                default -> {
                }
            }
            return true;
        }
        if (tour != null && tour.stops().get(stop).waits() && layout.lit().contains(x, y)) {
            next();
            return false;
        }
        return true;
    }

    /** The last stop's "Show me": the page's film, which returns to this screen when it closes. */
    private void show() {
        var film = tour == null ? null : tour.film();
        var mc = Minecraft.getInstance();
        Screen here = mc.screen;
        var scene = film == null ? null : Films.build(film);
        close();
        if (scene != null) mc.setScreen(new FilmScreen(scene, here, null));
    }

    /** A key while open: Left back, Right or Enter on, Escape closes; every other key is taken too. */
    public boolean keyPressed(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> back();
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> next();
            case GLFW.GLFW_KEY_ESCAPE -> close();
            default -> {
            }
        }
        return true;
    }
}
