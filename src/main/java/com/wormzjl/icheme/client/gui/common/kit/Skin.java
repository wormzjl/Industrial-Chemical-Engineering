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
     * A split bar {@code r}: {@code amounts} side by side in {@code colours}, each as wide as its share of their sum; empty
     * while they sum to nothing. Here on a dark line.
     */
    default void split(Canvas c, Rect r, double[] amounts, int[] colours) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), Palette.LINE);
        splitFill(c, new Rect(r.x() + 1, r.y() + 1, r.w() - 2, r.h() - 2), amounts, colours);
    }

    /** The shares of a split bar inside {@code r}; the last ends at the right edge, so rounding leaves no gap. */
    static void splitFill(Canvas c, Rect r, double[] amounts, int[] colours) {
        double total = 0;
        for (double a : amounts) total += Math.max(0, a);
        if (!(total > 0)) return;
        double at = 0;
        int x = r.x();
        for (int i = 0; i < amounts.length; i++) {
            at += Math.max(0, amounts[i]);
            int end = i == amounts.length - 1 ? r.right() : r.x() + (int) Math.round(r.w() * at / total);
            if (end > x) c.fill(x, r.y(), end, r.bottom(), colours[i]);
            x = Math.max(x, end);
        }
    }

    /** A sign a key shows instead of a word: the theme's contrast sign, a ring half filled. */
    enum Icon {
        THEME
    }

    /** A key {@code r} with {@code icon} on its face instead of a word, {@code hover} as for a button. */
    default void iconKey(Canvas c, Rect r, Icon icon, boolean hover) {
        button(c, r, "", Press.REST, hover);
        icon(c, r, icon, 0xFFFFFFFF);
    }

    /** {@code icon} centred in {@code r}, 10 by 10 px, in {@code argb}. */
    static void icon(Canvas c, Rect r, Icon icon, int argb) {
        int x0 = r.x() + (r.w() - 10) / 2, y0 = r.y() + (r.h() - 10) / 2;
        // THEME: a ring of radius 5 round pixel centres, its left half filled.
        for (int y = 0; y < 10; y++)
            for (int x = 0; x < 10; x++) {
                double d = (x - 4.5) * (x - 4.5) + (y - 4.5) * (y - 4.5);
                if (d < 25 && (d >= 16 || x < 5)) c.fill(x0 + x, y0 + y, x0 + x + 1, y0 + y + 1, argb);
            }
    }

    /**
     * The strip {@code r} the display keys sit on ({@link #softKey}). A display key (a unit, a basis) changes what the
     * screens show, not the plant, so a skin may set it apart from its push buttons. Here none.
     */
    default void softKeys(Canvas c, Rect r) {
    }

    /** A display key {@code r} on its strip ({@link #softKeys}), {@code hover} as for a button. Here a button at rest. */
    default void softKey(Canvas c, Rect r, String text, boolean hover) {
        button(c, r, text, Press.REST, hover);
    }

    /**
     * An indicating meter {@code r} showing {@code value}: a value the plant reads, which the player cannot type
     * ({@link Column.Reading}). Here a field's box at rest.
     */
    default void meter(Canvas c, Rect r, String value) {
        field(c, r, value, "", false);
    }

    /**
     * An entry field {@code r}, a value the player types, showing {@code value}, or {@code hint} when it is empty. While
     * {@code editing} the game draws the text and the cursor ({@link #fieldText()}, 4 px in), so the skin draws only the box.
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
