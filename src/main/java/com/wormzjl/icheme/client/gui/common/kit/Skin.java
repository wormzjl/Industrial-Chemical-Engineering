package com.wormzjl.icheme.client.gui.common.kit;

/**
 * How the kit draws what its rows ask for (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 8): the text tones
 * the rows name by the palette's colours, a choice's mark, a bar, and a scroll bar. {@link #PLAIN} is the look the
 * screens draw today; the skins tried for the owner live in the render tool until one is chosen.
 */
public interface Skin {
    /** A row colour named by the palette ({@link Palette#TEXT}, {@link Palette#MUTED}, {@link Palette#ACCENT}, {@link Palette#WARN}) in this skin. */
    default int tone(int palette) {
        return palette;
    }

    /** A choice's 8 by 8 mark with its top left at ({@code x}, {@code y}). */
    void mark(Canvas c, int x, int y, boolean selected);

    /** A bar {@code r} (8 px high) filled to {@code share} of its width. */
    void bar(Canvas c, Rect r, double share);

    /** A scroll bar: its {@code track} and its {@code thumb}, both the bar's full width; {@code dragging} while held. */
    void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging);

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
