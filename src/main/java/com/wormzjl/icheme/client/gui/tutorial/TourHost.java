package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.TourAnchors;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.science.tutorial.guide.Tour;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A screen's help tour host (batch 2026-11-12-tutorial-system, P6): the page's tour by name, the {@code ?} key that opens
 * it, and the forwarding every screen needs while it is open. A screen keeps one host, gives its {@code ?} key a place
 * left of its theme key (or at the title strip's right where it has none), and while {@link #isOpen} lets the host take
 * keys, typed characters, presses and the wheel, and draws it last with the pointer hidden from the page. The page's name
 * and the resolver are the screen's: a page with no tour has no key, and a stop whose anchor this page lacks is skipped.
 * A tour sends nothing to the server and reads only what the screen already shows.
 */
public final class TourHost {
    private final HelpTour tour = new HelpTour();
    private final Supplier<@Nullable String> page;
    private final HelpTour.Resolver resolver;
    private final Supplier<@Nullable Runnable> finished;

    /** A host for the page {@code page} names (null: no tour), its anchors from {@code resolver}. */
    public TourHost(Supplier<@Nullable String> page, HelpTour.Resolver resolver) {
        this(page, resolver, () -> null);
    }

    /** As above; {@code finished} gives what runs when the player steps past the last stop (null: nothing). */
    public TourHost(Supplier<@Nullable String> page, HelpTour.Resolver resolver, Supplier<@Nullable Runnable> finished) {
        this.page = page;
        this.resolver = resolver;
        this.finished = finished;
    }

    /** The page's tour, or null where it has none. */
    public @Nullable Tour tour() {
        var name = page.get();
        return name == null ? null : HelpTour.of(name);
    }

    public boolean has() {
        return tour() != null;
    }

    public boolean isOpen() {
        return tour.isOpen();
    }

    /** Opens the page's tour at its first stop the page shows. */
    public void open() {
        var t = tour();
        if (t != null) tour.open(t, resolver, finished.get());
    }

    public void close() {
        tour.close();
    }

    /** The {@code ?} key at {@code at} (screen coordinates), a sign and not a word (panel rule 7); null where the page has no tour. */
    public @Nullable SkinButton key(Rect at, Supplier<Skin> skin) {
        if (!has()) return null;
        String hint = Component.translatable("tutorial.icheme.guide.help").getString();
        var key = new SkinButton(at.x(), at.y(), at.w(), at.h(), Component.literal(hint), b -> open(), skin).icon(Skin.Icon.HELP);
        key.setTooltip(Tooltip.create(Component.literal(hint)));
        return key;
    }

    /** A key: true where the open tour takes it (every key while open; Escape closes the tour before the screen). */
    public boolean keyPressed(int key) {
        return tour.isOpen() && tour.keyPressed(key);
    }

    /** A press: true where the open tour takes it (all but the lit control of a stop that waits). */
    public boolean mouseClicked(Font font, int width, int height, double x, double y) {
        return tour.isOpen() && tour.mouseClicked(font, width, height, x, y);
    }

    /** Where the page reads the pointer: nowhere while the tour is open, so it shows no hover under the tour. */
    public int pointer(int at) {
        return tour.isOpen() ? -1 : at;
    }

    /**
     * A door row's anchor ({@link TourAnchors#find}) on the screen, the door's origin at ({@code left}, {@code top}), its view
     * scrolled to show it first where {@code reveal}; null where the door has none. {@code line} reads a plate key's text.
     */
    public static @Nullable Rect door(KitDoor door, String anchor, boolean reveal, java.util.function.Function<String, String> line, int left, int top) {
        var r = TourAnchors.find(anchor, door.columns(), line);
        return r == null ? null : TourAnchors.reveal(door.view(), r, reveal).moved(left, top);
    }

    /** A frame place {@code at} (screen-own coordinates) on the screen, or the door's anchor where the frame has none. */
    public static @Nullable Rect resolve(@Nullable Rect at, KitDoor door, String anchor, boolean reveal, java.util.function.Function<String, String> line, int left, int top) {
        return at != null ? at.moved(left, top) : door(door, anchor, reveal, line, left, top);
    }

    /** Draws the open tour over the whole screen, after the page. */
    public void render(GuiGraphics g, Font font, int width, int height, int mouseX, int mouseY) {
        if (tour.isOpen()) tour.render(g, font, width, height, mouseX, mouseY);
    }
}
