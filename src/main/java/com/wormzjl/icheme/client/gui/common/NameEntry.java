package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.science.equipment.DeviceName;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The name's entry in a screen's title strip (N1, {@link TitleStrip}; finding C7, batch 2026-11-13-codebase-review): the
 * name as typed, sent with Apply and saved by Enter, and the name it last followed. The draft follows the device's name
 * (another player's rename, this one's answered) while the player has not changed it and the entry is not focused. A
 * reader sees the name as text in its place. The screen keeps what is its own: where the name is read from, who may rename,
 * and where the rename is sent. It holds the game's text field, so it stands beside {@link TitleStrip}, which stays
 * Minecraft-free for the gates and the render tools. One per screen; a box at each rebuild, as the screen's own widgets.
 */
public final class NameEntry {
    private static final String NAME = "gui.icheme.fluid.name";
    private final Supplier<String> current;
    private final BooleanSupplier renamer;
    private @Nullable String draft, shown;
    private @Nullable SkinField box;

    /** An entry for the name {@code current} reads (the client's cells or block), Apply sending it where it changed. */
    public NameEntry(Supplier<String> current) {
        this(current, () -> true);
    }

    /** As above; Apply sends a changed name only where {@code renamer} holds (the device takes a name, the player may build). */
    public NameEntry(Supplier<String> current, BooleanSupplier renamer) {
        this.current = current;
        this.renamer = renamer;
    }

    /** Takes the device's name as the draft and as the name followed, the first time: before the entry is first shown. */
    public void seed() {
        if (draft == null) draft = shown = current.get();
    }

    /** The name as typed, or null before {@link #seed}. */
    public @Nullable String draft() {
        return draft;
    }

    /** Whether Apply has a name to send: the typed name, cleaned, differs from the device's. */
    public boolean renames() {
        return draft != null && renamer.getAsBoolean() && !DeviceName.clean(draft).equals(current.get());
    }

    /** Each tick: the draft follows the device's name while the player has not changed it and is not typing in it. */
    public void follow() {
        String now = current.get();
        if (shown == null || now.equals(shown)) return;
        if (draft != null && draft.equals(shown) && (box == null || !box.isFocused())) {
            draft = now;
            if (box != null) box.setValue(now);
        }
        shown = now;
    }

    /** The entry's box from the last rebuild, or null where this rebuild shows none (a reader, no place on the strip). */
    public @Nullable SkinField box() {
        return box;
    }

    /** At a rebuild, before the widgets are made again: no box until {@link #box(Font, Rect, Supplier, Runnable)} makes one. */
    public void forget() {
        box = null;
    }

    /**
     * The entry at {@code at} (screen coordinates): the draft, at most {@link DeviceName#MAX} characters, its hint while
     * empty; {@code save} runs on Enter. The screen adds it as a widget.
     */
    public SkinField box(Font font, Rect at, Supplier<Skin> skin, Runnable save) {
        String name = Component.translatable(NAME).getString();
        var made = new SkinField(font, at.x(), at.y(), at.w(), at.h(), Component.literal(name), skin);
        made.setMaxLength(DeviceName.MAX);
        made.setValue(draft == null ? "" : draft);
        made.setResponder(text -> draft = text);
        made.setHint(Component.literal(name));
        made.onEnter(save);
        made.setTooltip(Tooltip.create(Component.literal(name + "\n" + Component.translatable(NAME + ".hint").getString())));
        box = made;
        return made;
    }

    /**
     * A reader's name in the strip's place, where no box stands there: cut to it, in the LCD's inset. Returns the whole name
     * where it was cut and door point ({@code x}, {@code y}) is over it (the hover), else null.
     */
    public @Nullable String draw(Canvas c, Skin skin, TitleStrip.Strip strip, Font font, double x, double y) {
        if (strip.name() == null || box != null) return null;
        String name = current.get(), cut = TitleStrip.text(name, strip.name(), GuiCanvas.measure(font));
        int[] at = TitleStrip.textAt(strip.name());
        skin.text(c, cut, at[0], at[1], Palette.TEXT);
        return cut.length() < name.length() && strip.name().contains(x, y) ? name : null;
    }
}
