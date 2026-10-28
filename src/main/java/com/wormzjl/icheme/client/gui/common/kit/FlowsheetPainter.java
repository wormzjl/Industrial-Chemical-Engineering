package com.wormzjl.icheme.client.gui.common.kit;

import com.wormzjl.icheme.science.assembly.AssemblyReport;

/**
 * The flowsheets' drawing on a design grid (GUI_TOOLKIT_PLAN.md section 3), shared by a column's assembly screen and a
 * heat unit's Control tab, in the game and in the render tool: the grid's fit (origin and scale), process and dashed
 * utility lines with their arrows, boxes, rounded shells, tees, valves, circles, nozzle squares, pills, and labels in a
 * font scaled with the drawing (never below {@link #MIN_FONT}) that draw only where they fit. Each model keeps its own
 * records and decides each part's look; this draws them.
 */
public final class FlowsheetPainter {
    public static final double MIN_FONT = 0.5;
    public static final int BODY = 0xFF33434D, EDGE = 0xFF444441;

    /** A part's fill, stroke and text colours. */
    public record Look(int fill, int stroke, int text) {
        public static final Look TEAL = new Look(Palette.TEAL_FILL, Palette.TEAL, 0xFF9FE1CB);
        public static final Look YELLOW = new Look(Palette.YELLOW_FILL, Palette.YELLOW, 0xFFFAC775);
        public static final Look RED = new Look(Palette.RED_FILL, Palette.RED, 0xFFF7C1C1);

        /** The look of an assembly report's colour group. */
        public static Look of(AssemblyReport.Colour colour) {
            return switch (colour) {
                case TEAL -> TEAL;
                case YELLOW -> YELLOW;
                case RED -> RED;
            };
        }
    }

    private final Canvas c;
    private final int ox, oy;
    private final double scale, font;

    /**
     * Draws on {@code c} with the grid's origin at ({@code ox}, {@code oy}) and {@code scale} canvas pixels per design
     * unit; {@code font} is the design's labels against the 9-pixel font.
     */
    public FlowsheetPainter(Canvas c, int ox, int oy, double scale, double font) {
        this.c = c;
        this.ox = ox;
        this.oy = oy;
        this.scale = scale;
        this.font = font;
    }

    /** A grid {@code gridW} by {@code gridH} fitted, centred, into the box from ({@code x}, {@code y}), {@code w} by {@code h}. */
    public static FlowsheetPainter fit(Canvas c, int gridW, int gridH, int x, int y, int w, int h, double font) {
        double scale = Math.max(0.05, Math.min(w / (double) gridW, h / (double) gridH));
        return new FlowsheetPainter(c, x + (int) ((w - gridW * scale) / 2), y + (int) ((h - gridH * scale) / 2), scale, font);
    }

    public Canvas canvas() {
        return c;
    }

    public int ox() {
        return ox;
    }

    public int oy() {
        return oy;
    }

    public double scale() {
        return scale;
    }

    public int x(int design) {
        return ox + (int) Math.round(design * scale);
    }

    public int y(int design) {
        return oy + (int) Math.round(design * scale);
    }

    /** A design length in canvas pixels, at least one. */
    public int px(int design) {
        return Math.max(1, (int) Math.round(design * scale));
    }

    public float fontScale() {
        return (float) Math.clamp(scale * font, MIN_FONT, 1.0);
    }

    /** Whether free labels draw: only where the font keeps the design's proportion; below it they would cover the parts. */
    public boolean labels() {
        return scale * font >= MIN_FONT;
    }

    /** A line through design points {@code points}; a utility line dashed (two pixels on, one off); an arrow at its end. */
    public void wire(int[] p, boolean arrow, boolean utility, int colour) {
        for (int i = 0; i + 3 < p.length; i += 2) {
            int x0 = x(p[i]), y0 = y(p[i + 1]), x1 = x(p[i + 2]), y1 = y(p[i + 3]);
            boolean across = y0 == y1;
            int from = across ? Math.min(x0, x1) : Math.min(y0, y1), to = across ? Math.max(x0, x1) : Math.max(y0, y1);
            for (int a = from; a <= to; a += utility ? 3 : to - from + 1) {
                int b = utility ? Math.min(a + 1, to) : to;
                if (across) c.hLine(a, b, y0, colour);
                else c.fill(x0, a, x0 + 1, b + 1, colour);
            }
        }
        if (!arrow || p.length < 4) return;
        int n = p.length;
        int ex = x(p[n - 2]), ey = y(p[n - 1]), dx = Integer.signum(x(p[n - 2]) - x(p[n - 4])), dy = Integer.signum(y(p[n - 1]) - y(p[n - 3]));
        for (int k = 0; k < 3; k++) {
            int bx = ex - dx * k, by = ey - dy * k;
            if (dx != 0) c.vLine(bx, by - k - 1, by + k + 1, colour);
            else c.hLine(bx - k, bx + k, by, colour);
        }
    }

    /** A straight line between canvas points, one pixel wide, at any slope. */
    public void line(int x0, int y0, int x1, int y1, int colour) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = Integer.signum(x1 - x0), sy = Integer.signum(y1 - y0), err = dx + dy;
        while (true) {
            c.fill(x0, y0, x0 + 1, y0 + 1, colour);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * A vessel lying across, canvas pixels: its ends dished {@code left} and {@code right} pixels deep (a half ellipse over
     * the full height; 0 flat, half the height a half circle), filled and outlined.
     */
    public void vessel(int x0, int y0, int x1, int y1, int left, int right, Look look) {
        double b = (y1 - y0) / 2.0;
        for (int yy = y0; yy < y1; yy++) {
            double t = Math.min(1, Math.abs(yy + 0.5 - (y0 + b)) / b);
            double chord = 1 - Math.sqrt(Math.max(0, 1 - t * t));
            int l = x0 + (int) Math.round(left * chord), r = x1 - 1 - (int) Math.round(right * chord);
            c.hLine(l, r, yy, look.fill());
            c.fill(l, yy, l + 1, yy + 1, look.stroke());
            c.fill(r, yy, r + 1, yy + 1, look.stroke());
        }
        c.hLine(x0 + left / 2, x1 - 1 - right / 2, y0, look.stroke());
        c.hLine(x0 + left / 2, x1 - 1 - right / 2, y1 - 1, look.stroke());
    }

    /** A trapezoid, canvas pixels: its top edge {@code topInset} and its bottom edge {@code bottomInset} in from each side, filled and outlined. */
    public void trapezoid(int x0, int y0, int x1, int y1, int topInset, int bottomInset, Look look) {
        int h = Math.max(1, y1 - y0 - 1);
        for (int yy = y0; yy < y1; yy++) {
            int inset = (int) Math.round(topInset + (bottomInset - topInset) * (yy - y0) / (double) h);
            c.hLine(x0 + inset, x1 - 1 - inset, yy, yy == y0 || yy == y1 - 1 ? look.stroke() : look.fill());
            c.fill(x0 + inset, yy, x0 + inset + 1, yy + 1, look.stroke());
            c.fill(x1 - 1 - inset, yy, x1 - inset, yy + 1, look.stroke());
        }
    }

    /** A filled box with its outline, canvas pixels. */
    public void box(int x0, int y0, int x1, int y1, Look look) {
        c.fill(x0, y0, x1, y1, look.fill());
        c.outline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), look.stroke());
    }

    /** A box with rounded ends of radius {@code r}: rows shortened by a circle's chord at each end, canvas pixels. */
    public void rounded(int x0, int y0, int x1, int y1, int r, Look look) {
        for (int yy = y0; yy < y1; yy++) {
            double dy = Math.max(0, Math.max(y0 + r - yy - 0.5, yy + 0.5 - (y1 - r)));
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            c.hLine(x0 + inset, x1 - 1 - inset, yy, look.fill());
            c.fill(x0 + inset, yy, x0 + inset + 1, yy + 1, look.stroke());
            c.fill(x1 - 1 - inset, yy, x1 - inset, yy + 1, look.stroke());
        }
        c.hLine(x0 + r, x1 - 1 - r, y0, look.stroke());
        c.hLine(x0 + r, x1 - 1 - r, y1 - 1, look.stroke());
    }

    /** A tee: a solid block at least two pixels across. */
    public void tee(int x0, int y0, int x1, int y1, Look look) {
        c.fill(x0, y0, Math.max(x1, x0 + 2), Math.max(y1, y0 + 2), look.stroke());
    }

    /** A valve on a line across at canvas y {@code vy}, centred on {@code cx}: two triangles and its actuator above. */
    public void valve(int cx, int vy, Look look) {
        int hw = Math.max(2, px(8)), hh = Math.max(2, px(6));
        for (int dx = -hw; dx <= hw; dx++) {
            int h = Math.round(hh * Math.abs(dx) / (float) hw);
            c.vLine(cx + dx, vy - h - 1, vy + h + 1, look.stroke());
        }
        c.vLine(cx, vy - px(11) - 1, vy, look.stroke());
        c.fill(cx - px(5), vy - px(16), cx + px(5) + 1, vy - px(11), look.stroke());
    }

    /** A valve on a line down at canvas x {@code vx}, centred on {@code cy}: its actuator to the left. */
    public void valveDown(int vx, int cy, Look look) {
        int hw = Math.max(2, px(8)), hh = Math.max(2, px(6));
        for (int dy = -hw; dy <= hw; dy++) {
            int w = Math.round(hh * Math.abs(dy) / (float) hw);
            c.hLine(vx - w - 1, vx + w + 1, cy + dy, look.stroke());
        }
        c.hLine(vx - px(11) - 1, vx, cy, look.stroke());
        c.fill(vx - px(16), cy - px(5), vx - px(11), cy + px(5) + 1, look.stroke());
    }

    /** A filled circle of radius {@code r} with its outline, centred on canvas point ({@code cx}, {@code cy}). */
    public void circle(int cx, int cy, int r, Look look) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
            c.hLine(cx - half, cx + half, cy + dy, look.fill());
            c.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, look.stroke());
            c.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, look.stroke());
        }
        c.hLine(cx - 1, cx + 1, cy - r, look.stroke());
        c.hLine(cx - 1, cx + 1, cy + r, look.stroke());
    }

    /** A nozzle's or port's square of design size {@code size} at design point ({@code x}, {@code y}), RGB {@code rgb}, edged. */
    public void square(int x, int y, int size, int rgb) {
        int half = Math.max(2, px(size / 2)), cx = x(x), cy = y(y);
        c.fill(cx - half - 1, cy - half - 1, cx + half + 1, cy + half + 1, EDGE);
        c.fill(cx - half, cy - half, cx + half, cy + half, 0xFF000000 | rgb);
    }

    /** A pill from design x {@code x}, {@code w} wide, {@code half} above and below design y {@code y}. */
    public void pill(int x, int y, int w, int half) {
        c.fill(x(x), y(y - half), x(x + w), y(y + half), Palette.PANEL);
        c.outline(x(x), y(y - half), x(x + w) - x(x), y(y + half) - y(y - half), Palette.LINE);
    }

    /** A label centred on design point ({@code x}, {@code y}), drawn only if it fits {@code width} design units; whether it was drawn. */
    public boolean fitted(String text, int x, int y, int width, int colour) {
        if (c.measure().width(text) * fontScale() > width * scale - 2) return false;
        label(text, x, y, 0.5f, colour);
        return true;
    }

    /**
     * A label centred on design point ({@code x}, {@code y}) on a plate of {@code plate} (ARGB), drawn only if it fits
     * {@code width} design units and covers no label placed before it; whether it was drawn.
     */
    public boolean fitted(String text, int x, int y, int width, int colour, int plate) {
        return c.measure().width(text) * fontScale() <= width * scale - 2 && tag(text, x, y, 0.5f, colour, plate);
    }

    /** A label at design point ({@code x}, {@code y}), its anchor 0 at its left, 0.5 its centre, 1 its right. */
    public void label(String text, int x, int y, float anchor, int colour) {
        float fs = fontScale();
        float w = c.measure().width(text) * fs;
        c.text(text, x(x) - w * anchor, y(y) - c.measure().height() * fs / 2 + fs, fs, colour);
    }

    /** The canvas boxes the labels placed with {@link #tag} and the areas {@link #take}n hold; a later label avoids them. */
    private final java.util.List<Rect> taken = new java.util.ArrayList<>();

    /** Holds canvas box {@code r} (a pill) against later labels. */
    public void take(Rect r) {
        taken.add(r);
    }

    /**
     * A label at design point ({@code x}, {@code y}) as {@link #label} places it, on a plate of {@code plate} (ARGB; 0
     * none), drawn only where it covers no label or area placed before it, so no text is ever drawn over another; whether it
     * was drawn.
     */
    public boolean tag(String text, int x, int y, float anchor, int colour, int plate) {
        float fs = fontScale(), w = c.measure().width(text) * fs, h = c.measure().height() * fs;
        float left = x(x) - w * anchor, top = y(y) - h / 2 + fs;
        var box = new Rect((int) Math.floor(left) - 1, (int) Math.floor(top) - 1, (int) Math.ceil(w) + 2, (int) Math.ceil(h));
        for (var t : taken) if (t.overlaps(box)) return false;
        taken.add(box);
        if (plate != 0) c.fill(box.x(), box.y(), box.right(), box.bottom(), plate);
        c.text(text, left, top, fs, colour);
        return true;
    }

    /** A design point's x of canvas x {@code cx}. */
    public double designX(double cx) {
        return (cx - ox) / scale;
    }

    public double designY(double cy) {
        return (cy - oy) / scale;
    }
}
