package com.wormzjl.icheme.client.gui.common.kit;

/**
 * A viewport over a column taller than it (GUI_TOOLKIT_PLAN.md section 1): an offset in pixels and its own 8 px bar.
 * It answers the wheel, the bar and clicks only while it is shown and only inside its viewport and bar, so a view of a
 * page not shown takes nothing. A content rect shows on screen moved up by the offset; its widget shows only when it
 * lies wholly inside the viewport.
 */
public final class ScrollView {
    public static final int BAR = 8, STEP = 30;
    private Rect viewport = new Rect(0, 0, 0, 0);
    private int barX, content, offset;
    private boolean shown = true, dragging;

    /** The viewport, the bar's left edge and the content's height; the offset is clamped to them. */
    public void layout(Rect viewport, int barX, int contentHeight) {
        this.viewport = viewport;
        this.barX = barX;
        content = Math.max(0, contentHeight);
        offset = Math.clamp(offset, 0, max());
    }

    public Rect viewport() {
        return viewport;
    }

    public int offset() {
        return offset;
    }

    /** The largest offset: how much taller the content is than the viewport. */
    public int max() {
        return Math.max(0, content - viewport.h());
    }

    public void shown(boolean value) {
        shown = value;
        if (!value) dragging = false;
    }

    public boolean shown() {
        return shown;
    }

    public void reset() {
        offset = 0;
    }

    /** Where content rect {@code r} lies on screen. */
    public Rect onScreen(Rect r) {
        return r.moved(0, -offset);
    }

    /** Whether content rect {@code r} lies wholly inside the viewport, so a widget at it may show. */
    public boolean visible(Rect r) {
        return shown && onScreen(r).inside(viewport);
    }

    /** Whether screen point ({@code x}, {@code y}) is in the viewport while the view is shown. */
    public boolean inside(double x, double y) {
        return shown && viewport.contains(x, y);
    }

    /** Screen y {@code y} in content coordinates. */
    public double contentY(double y) {
        return y + offset;
    }

    private Rect bar() {
        return new Rect(barX, viewport.y(), BAR, viewport.h());
    }

    private int thumb() {
        return content <= 0 ? viewport.h() : Math.min(viewport.h(), Math.max(18, viewport.h() * viewport.h() / content));
    }

    public boolean wheel(double x, double y, double delta) {
        if (!shown || max() == 0 || !(viewport.contains(x, y) || bar().contains(x, y))) return false;
        offset = Math.clamp(offset - (int) Math.signum(delta) * STEP, 0, max());
        return true;
    }

    /** A press on the bar starts a drag. */
    public boolean press(double x, double y, int button) {
        if (button != 0 || !shown || max() == 0 || !bar().contains(x, y)) return false;
        dragging = true;
        seek(y);
        return true;
    }

    public boolean drag(double y) {
        if (!dragging) return false;
        seek(y);
        return true;
    }

    public void release() {
        dragging = false;
    }

    private void seek(double y) {
        int travel = Math.max(1, viewport.h() - thumb());
        offset = Math.clamp((int) Math.round((y - viewport.y() - thumb() / 2.0) * max() / travel), 0, max());
    }

    public void drawBar(Canvas c) {
        if (!shown || max() == 0) return;
        int at = (viewport.h() - thumb()) * offset / max();
        c.fill(barX, viewport.y(), barX + BAR, viewport.bottom(), Palette.PANEL);
        c.fill(barX + 1, viewport.y() + at, barX + BAR - 1, viewport.y() + at + thumb(), dragging ? Palette.ACCENT : Palette.LINE);
    }
}
