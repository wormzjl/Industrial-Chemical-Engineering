package com.wormzjl.icheme.client.gui.common.kit;

/** A rectangle in GUI pixels: its left, top, width and height. */
public record Rect(int x, int y, int w, int h) {
    public Rect {
        if (w < 0 || h < 0) throw new IllegalArgumentException("Negative size");
    }

    public int right() {
        return x + w;
    }

    public int bottom() {
        return y + h;
    }

    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    /** Whether this lies wholly inside {@code outer}. */
    public boolean inside(Rect outer) {
        return x >= outer.x && y >= outer.y && right() <= outer.right() && bottom() <= outer.bottom();
    }

    public boolean overlaps(Rect other) {
        return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
    }

    public Rect moved(int dx, int dy) {
        return new Rect(x + dx, y + dy, w, h);
    }
}
