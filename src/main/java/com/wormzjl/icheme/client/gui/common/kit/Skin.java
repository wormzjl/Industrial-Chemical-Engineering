package com.wormzjl.icheme.client.gui.common.kit;

/**
 * How a screen and the kit's rows draw (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md sections 8 to 10): the page,
 * its title and status, the buttons and fields, the well the drawing and the tables sit in, the dividers, and what the
 * rows ask for (the texts in the tones the rows name by the palette's colours, function groups, a choice's mark, a bar, a scroll
 * bar). {@link #PLAIN} is the look the screens drew before; {@link PanelSkin} draws a control panel door, light or dark.
 */
public interface Skin {
    /** A button's state: at rest, switched off, latched (the page or the side shown), the confirm button (Apply). */
    enum Press {
        REST, OFF, CURRENT, PRIMARY
    }

    /** A row colour named by the palette ({@link Palette#TEXT}, {@link Palette#MUTED}, {@link Palette#ACCENT}, {@link Palette#WARN}) in this skin. */
    default int tone(int palette) {
        return palette;
    }

    /** A row's text at ({@code x}, {@code y}) in the tone the row names by {@code palette}; a skin may engrave it. */
    default void text(Canvas c, String text, int x, int y, int palette) {
        c.text(text, x, y, tone(palette));
    }

    /**
     * A function group's frame {@code r} and its plate: the plate, {@link Column#plate} px high, at the frame's top
     * left with its {@code title} lines; the frame's top edge through the plate's middle. Here a thin outline broken by
     * the muted title.
     */
    default void group(Canvas c, Rect r, java.util.List<String> title) {
        int plate = Column.plate(title.size()), top = r.y() + plate / 2, wide = 0;
        for (var line : title) wide = Math.max(wide, c.measure().width(line));
        c.outline(r.x(), top, r.w(), r.bottom() - top, Palette.LINE);
        c.fill(r.x() + 4, top, r.x() + 12 + wide, top + 1, Palette.BG);
        for (int i = 0; i < title.size(); i++) text(c, title.get(i), r.x() + 8, r.y() + 2 + i * Measure.LINE, Palette.MUTED);
    }

    /** A choice's 8 by 8 mark with its top left at ({@code x}, {@code y}). */
    void mark(Canvas c, int x, int y, boolean selected);

    /** A bar {@code r} (8 px high) filled to {@code share} of its width. */
    void bar(Canvas c, Rect r, double share);

    /** A scroll bar: its {@code track} and its {@code thumb}, both the bar's full width; {@code dragging} while held. */
    void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging);

    /** The screen's background {@code r}. */
    default void page(Canvas c, Rect r) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), Palette.BG);
        c.outline(r.x(), r.y(), r.w(), r.h(), Palette.LINE);
    }

    /** The screen's title, its text's top left at ({@code x}, {@code y}). */
    default void title(Canvas c, int x, int y, String text) {
        c.text(text, x, y, Palette.TEXT);
    }

    /** The status line, its text's top left at {@code r}'s, in the tone {@code palette} names. */
    default void status(Canvas c, Rect r, String text, int palette) {
        c.text(text, r.x(), r.y(), tone(palette));
    }

    /** A button {@code r} with its text, {@code hover} while the pointer or the keyboard is on it. */
    default void button(Canvas c, Rect r, String text, Press press, boolean hover) {
        boolean active = press == Press.REST || press == Press.PRIMARY;
        c.fill(r.x(), r.y(), r.right(), r.bottom(), hover && active ? 0xFFFFFFFF : 0xFF000000);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, active ? 0xFF6F6F6F : 0xFF2C2C2C);
        centred(c, r, text, active ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    /**
     * A field {@code r} showing {@code value}, or {@code hint} when it is empty. While {@code editing} the game draws the
     * text and the cursor ({@link #fieldText()}, 4 px in), so the skin draws only the box.
     */
    default void field(Canvas c, Rect r, String value, String hint, boolean editing) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), editing ? 0xFFFFFFFF : 0xFFA0A0A0);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
        if (!editing) c.text(c.measure().cut(value.isEmpty() ? hint : value, r.w() - 8), r.x() + 4, r.y() + (r.h() - 8) / 2, value.isEmpty() ? 0xFF808080 : fieldText());
    }

    /** A field's text while it is edited. */
    default int fieldText() {
        return 0xFFE0E0E0;
    }

    /**
     * Draws the well {@code r} the drawing or the tables sit in (a panel: the HMI screen) and returns the box inside it,
     * which keeps the screens' dark palette. {@link #PLAIN} draws none and returns the box the screens used before.
     */
    default Rect well(Canvas c, Rect r) {
        return new Rect(r.x() + 5, r.y() + 3, r.w() - 8, r.h() - 5);
    }

    default void vDivider(Canvas c, int x, int y0, int y1) {
        c.vLine(x, y0, y1, Palette.LINE);
    }

    default void hDivider(Canvas c, int x0, int x1, int y) {
        c.hLine(x0, x1, y, Palette.LINE);
    }

    /** {@code text} centred in {@code r}, cut to its width. */
    static void centred(Canvas c, Rect r, String text, int argb) {
        String shown = c.measure().cut(text, r.w() - 6);
        c.text(shown, r.x() + (r.w() - c.measure().width(shown)) / 2, r.y() + (r.h() - 9) / 2 + 1, argb);
    }

    Skin PLAIN = new Skin() {
        @Override
        public void mark(Canvas c, int x, int y, boolean selected) {
            c.fill(x, y, x + 8, y + 8, selected ? Palette.ACCENT : Palette.LINE);
            c.fill(x + 1, y + 1, x + 7, y + 7, Palette.BG);
            if (selected) c.fill(x + 2, y + 2, x + 6, y + 6, Palette.ACCENT);
        }

        @Override
        public void bar(Canvas c, Rect r, double share) {
            c.fill(r.x(), r.y(), r.right(), r.bottom(), Palette.BG);
            c.outline(r.x(), r.y(), r.w(), r.h(), Palette.LINE);
            c.fill(r.x() + 1, r.y() + 1, r.x() + 1 + (int) Math.round((r.w() - 2) * share), r.bottom() - 1, Palette.ACCENT);
        }

        @Override
        public void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging) {
            c.fill(track.x(), track.y(), track.right(), track.bottom(), Palette.PANEL);
            c.fill(thumb.x() + 1, thumb.y(), thumb.right() - 1, thumb.bottom(), dragging ? Palette.ACCENT : Palette.LINE);
        }
    };
}
