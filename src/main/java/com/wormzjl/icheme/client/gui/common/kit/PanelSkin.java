package com.wormzjl.icheme.client.gui.common.kit;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A control panel door (GUI_TOOLKIT_PLAN.md sections 9 to 13; owner: "Both 3a and 3c, configurable as light and dark"):
 * flat engraved legend plates for the title and the function groups, an engraved line round each group, engraved text,
 * push buttons in a black bezel with a chrome ring (lit from behind when latched), flat display keys in an engraved tray,
 * pilot lights with a glow, 7-segment LCD set-point windows for the entries and LED panel meters for the readings, an LED
 * bar graph with a top zone, a message display for the status, the HMI screen in its bezel with a power LED, a
 * quarter-turn latch.
 * Panel legends keep their colour; the lamps show state. Button colours follow IEC 60073: black general, green running
 * (latched), blue confirm. {@link #LIGHT} is door 3a, {@link #DARK} door 3c.
 */
public record PanelSkin(Door door, Face legend, int emboss, boolean shadow, Face key, Face lit, Face confirm, Face dim, int bezel, int chrome,
                        Window meter, int lampOn, int lampOff, Window bar, int barTop, Window display, int alarm, Window screen, int screenBezel,
                        Ink ink) implements Skin {
    /** A face of a push button or a legend plate: its colour, its top-left and bottom-right edges, and its legend's colour. */
    public record Face(int face, int light, int dark, int text) {
    }

    /** A door sheet: its colour, its top-left and bottom-right edges, and its outer edge. */
    public record Door(int sheet, int light, int dark, int edge) {
    }

    /** A window (a panel meter, the bar graph, the message display, the HMI screen): its glass, lit and unlit colours. */
    public record Window(int glass, int on, int off) {
    }

    /** The tones the rows name on the door: text, muted, accent, warning. */
    public record Ink(int text, int dim, int accent, int warn) {
    }

    private static final Face BLACK_KEY = new Face(0xFF262626, 0xFF4A4A4A, 0xFF141414, 0xFFF0F0F0);
    private static final Face GREEN_LIT = new Face(0xFF2DB34A, 0xFF8BE89A, 0xFF1B7A30, 0xFF06200C);
    private static final Face BLUE_CONFIRM = new Face(0xFF1F5FBF, 0xFF6A9BE8, 0xFF143F80, 0xFFFFFFFF);
    private static final Window SCREEN = new Window(0xFF10161B, 0xFF3BE060, 0);
    /** The entries' reflective LCD: grey-green glass, near-black segments, faint unlit ones. */
    private static final Window LCD = new Window(0xFFB3BEA2, 0xFF1A2016, 0xFF9AA58A);

    /** Door 3a: RAL 7035 light grey, white legend plates (black ones read as buttons), green lamps, red LED meters, a green bar graph. */
    public static final PanelSkin LIGHT = new PanelSkin(new Door(0xFFC9CCC8, 0xFFE2E5E1, 0xFFA5A8A4, 0xFF6E716D),
            new Face(0xFFF4F4F0, 0xFFFFFFFF, 0xFF6E716D, 0xFF1A1A1A), 0xFFE9ECE8, false,
            BLACK_KEY, GREEN_LIT, BLUE_CONFIRM, new Face(0xFF8A8D89, 0xFF9C9F9B, 0xFF767975, 0xFFC4C7C3), 0xFF111111, 0xFFA9ACA8,
            new Window(0xFF0A0A0A, 0xFFFF3B1F, 0xFF2A0D08), 0xFF3BE060, 0xFF1F3A26, new Window(0xFF0A0A0A, 0xFF3BE060, 0xFF12301A), 0xFFFFB000,
            new Window(0xFF06100D, 0xFF6CF0C2, 0), 0xFFFFB347, SCREEN, 0xFF1A1A1A, new Ink(0xFF1A1A1A, 0xFF3C3F3B, 0xFF1E7A34, 0xFF9A6A00));

    /** Door 3c: RAL 7016 anthracite, aluminium legend plates, amber LED meters and bar graph with a red top zone. */
    public static final PanelSkin DARK = new PanelSkin(new Door(0xFF383E42, 0xFF4F565B, 0xFF24282B, 0xFF121416),
            new Face(0xFFB9BDC0, 0xFFDDE1E4, 0xFF6E7275, 0xFF111111), 0xA0000000, true,
            new Face(0xFF1A1C1E, 0xFF3A3E42, 0xFF0C0D0E, 0xFFE6E6E6), GREEN_LIT, BLUE_CONFIRM,
            new Face(0xFF2C3134, 0xFF363B3F, 0xFF22262A, 0xFF6E757A), 0xFF0A0B0C, 0xFF8E9396,
            new Window(0xFF080808, 0xFFFFB000, 0xFF2A1E05), 0xFF3BE060, 0xFF1A2A1F, new Window(0xFF080808, 0xFFFFB000, 0xFF2A1E05), 0xFFFF4A2A,
            new Window(0xFF05090C, 0xFF7FD7FF, 0), 0xFFFFB000, SCREEN, 0xFF0E0F10, new Ink(0xFFE6E8EA, 0xFFA9AFB4, 0xFF7EE29A, 0xFFFFC24A));

    private static final Pattern SEGMENTS = Pattern.compile("[0-9.\\-ofOF]+");
    /** The seven segments a to g of a 6 by 11 digit: x0, y0, x1, y1. */
    private static final int[][] SEG = {{1, 0, 5, 1}, {5, 1, 6, 5}, {5, 6, 6, 10}, {1, 10, 5, 11}, {0, 6, 1, 10}, {0, 1, 1, 5}, {1, 5, 5, 6}};

    @Override
    public int tone(int p) {
        return p == Palette.TEXT ? ink.text() : p == Palette.MUTED ? ink.dim() : p == Palette.ACCENT ? ink.accent() : p == Palette.WARN ? ink.warn() : p;
    }

    /** Engraved: a highlight under the text on a light door, a shadow on a dark one. */
    @Override
    public void text(Canvas c, String text, int x, int y, int palette) {
        c.text(text, shadow ? x + 1 : x, y + 1, emboss);
        c.text(text, x, y, tone(palette));
    }

    /**
     * A function group: an engraved line round its rows, broken at the top left by its plate (GUI_TOOLKIT_PLAN.md
     * section 11). The plate is as wide as its widest line and its margins.
     */
    @Override
    public void group(Canvas c, Rect r, List<String> title) {
        int plate = Column.plate(title.size()), top = r.y() + plate / 2, wide = 0;
        for (var line : title) wide = Math.max(wide, c.measure().width(line));
        int x0 = r.x(), x1 = r.right() - 1, y1 = r.bottom() - 1;
        c.fill(x0, top, x1, top + 1, door.dark());
        c.fill(x0, top, x0 + 1, y1, door.dark());
        c.fill(x0, y1 - 1, x1, y1, door.dark());
        c.fill(x1 - 1, top, x1, y1, door.dark());
        c.fill(x0 + 1, top + 1, x1 - 1, top + 2, door.light());
        c.fill(x0 + 1, top + 1, x0 + 2, y1 - 1, door.light());
        c.fill(x0, y1, x1 + 1, y1 + 1, door.light());
        c.fill(x1, top, x1 + 1, y1, door.light());
        legendPlate(c, new Rect(x0 + 4, r.y(), Math.min(r.w() - 8, wide + 8), plate), title);
    }

    /** A flat engraved plate, unlike a push button: a rim with cut corners, its lines 4 px in and centred in its height. */
    private void legendPlate(Canvas c, Rect r, List<String> lines) {
        c.fill(r.x() + 1, r.y(), r.right() - 1, r.bottom(), legend.dark());
        c.fill(r.x(), r.y() + 1, r.right(), r.bottom() - 1, legend.dark());
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, legend.face());
        // Capitals stand 7 px over a 9 px line: centred, they leave as much face above them as below.
        int top = r.y() + (r.h() - lines.size() * Measure.LINE) / 2 + 2;
        for (int i = 0; i < lines.size(); i++) c.text(lines.get(i), r.x() + 4, top + i * Measure.LINE, legend.text());
    }

    @Override
    public void page(Canvas c, Rect r) {
        int x0 = r.x(), y0 = r.y(), x1 = r.right(), y1 = r.bottom();
        c.fill(x0 + 1, y0, x1 - 1, y1, door.edge());
        c.fill(x0, y0 + 1, x1, y1 - 1, door.edge());
        c.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, door.sheet());
        c.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, door.light());
        c.fill(x0 + 1, y0 + 2, x0 + 2, y1 - 1, door.light());
        c.fill(x0 + 2, y1 - 2, x1 - 1, y1 - 1, door.dark());
        c.fill(x1 - 2, y0 + 2, x1 - 1, y1 - 2, door.dark());
        // A quarter-turn latch on the door's right edge.
        int ly = (y0 + y1) / 2 - 11;
        c.fill(x1 - 7, ly, x1 - 2, ly + 22, bezel);
        c.fill(x1 - 6, ly + 1, x1 - 3, ly + 21, key.face());
        c.fill(x1 - 5, ly + 5, x1 - 4, ly + 17, chrome);
    }

    @Override
    public void title(Canvas c, int x, int y, String text) {
        legendPlate(c, new Rect(x - 4, y - 4, c.measure().width(text) + 8, 16), List.of(text));
    }

    /** The status on a message display; a warning in the display's alarm colour. */
    @Override
    public void status(Canvas c, Rect r, String text, int palette) {
        window(c, new Rect(r.x() - 4, r.y() - 5, r.w() + 8, 15), display.glass());
        c.text(text, r.x(), r.y() - 2, palette == Palette.WARN ? alarm : display.on());
    }

    private void window(Canvas c, Rect r, int glass) {
        c.fill(r.x() + 1, r.y(), r.right() - 1, r.bottom(), bezel);
        c.fill(r.x(), r.y() + 1, r.right(), r.bottom() - 1, bezel);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, glass);
    }

    @Override
    public void button(Canvas c, Rect r, String text, Press press, boolean hover) {
        var f = switch (press) {
            case REST -> key;
            case OFF -> dim;
            case CURRENT -> lit;
            case PRIMARY -> confirm;
        };
        int x0 = r.x(), y0 = r.y(), x1 = r.right(), y1 = r.bottom();
        c.fill(x0 + 1, y0, x1 - 1, y1, bezel);
        c.fill(x0, y0 + 1, x1, y1 - 1, bezel);
        // The chrome ring, lit from above.
        c.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, chrome);
        c.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, mix(chrome, 0xFFFFFFFF, 0.5));
        c.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, mix(chrome, 0xFF000000, 0.35));
        // The pointer on a button that acts lights its face a little.
        boolean lifted = hover && (press == Press.REST || press == Press.PRIMARY);
        c.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, lifted ? mix(f.face(), f.light(), 0.35) : f.face());
        c.fill(x0 + 2, y0 + 2, x1 - 3, y0 + 3, f.light());
        c.fill(x0 + 2, y0 + 3, x0 + 3, y1 - 3, f.light());
        c.fill(x0 + 3, y1 - 3, x1 - 2, y1 - 2, f.dark());
        c.fill(x1 - 3, y0 + 3, x1 - 2, y1 - 3, f.dark());
        // A latched button is lit from behind.
        if (press == Press.CURRENT) c.fill(x0 + 4, y0 + 4, x1 - 4, y1 - 4, mix(f.face(), f.light(), 0.4));
        Skin.centred(c, r, text, f.text());
    }

    /** A black key with its sign in the key's legend colour (owner: "Make the panel color switch an icon instead of word"). */
    @Override
    public void iconKey(Canvas c, Rect r, Icon icon, boolean hover) {
        button(c, r, "", Press.REST, hover);
        Skin.icon(c, r, icon, key.text());
    }

    /**
     * The display keys' tray: a recess pressed into the door, its line engraved as a function group's. A unit or a basis
     * changes what the screens show, not the plant, so its keys sit apart from the push buttons (owner: "these buttons
     * should distinguish themselves from the left ones"; of three colours tried, "C looks better").
     */
    @Override
    public void softKeys(Canvas c, Rect r) {
        c.fill(r.x(), r.y(), r.right() - 1, r.y() + 1, door.dark());
        c.fill(r.x(), r.y(), r.x() + 1, r.bottom() - 1, door.dark());
        c.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), door.light());
        c.fill(r.right() - 1, r.y(), r.right(), r.bottom(), door.light());
    }

    /** A display key: flat and black in a thin rim, no chrome ring; the pointer lights its face as a push button's. */
    @Override
    public void softKey(Canvas c, Rect r, String text, boolean hover) {
        int x0 = r.x(), y0 = r.y(), x1 = r.right(), y1 = r.bottom();
        c.fill(x0 + 1, y0, x1 - 1, y1, bezel);
        c.fill(x0, y0 + 1, x1, y1 - 1, bezel);
        c.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hover ? mix(key.face(), key.light(), 0.35) : key.face());
        Skin.centred(c, r, text, key.text());
    }

    /**
     * An entry: a reflective LCD, dark segments on grey-green glass, the same on both doors (owner: "need to differentiate
     * editable value fields with non-editable"; of three looks, "B with E"). What the player sets reads as a set-point
     * display; what the plant reads stays a lit LED meter ({@link #meter}). While edited, only the window.
     */
    @Override
    public void field(Canvas c, Rect r, String value, String hint, boolean editing) {
        window(c, r, LCD.glass());
        if (!editing) readout(c, r, value, hint, LCD);
    }

    /** A panel meter: the door's 7-segment LEDs, lit in their glass. */
    @Override
    public void meter(Canvas c, Rect r, String value) {
        window(c, r, meter.glass());
        readout(c, r, value, "", meter);
    }

    @Override
    public int fieldText() {
        return LCD.on();
    }

    /**
     * Numbers in 7-segment digits of {@code w}, right-aligned over their unlit segments; other text as text. An empty
     * window shows its hint dimmed; in segments a word reads better in capitals (OFF).
     */
    private static void readout(Canvas c, Rect r, String value, String hint, Window w) {
        String shown = value.isEmpty() ? hint.toUpperCase(Locale.ROOT) : value;
        int on = value.isEmpty() ? mix(w.off(), w.on(), 0.7) : w.on();
        if (SEGMENTS.matcher(shown).matches() && segmentsWidth(shown) <= r.w() - 6) segments(c, shown, r.right() - 4, r.y() + (r.h() - 11) / 2, on, mix(w.glass(), w.off(), 0.6));
        else c.text(c.measure().cut(shown, r.w() - 8), r.x() + 4, r.y() + (r.h() - 8) / 2, on);
    }

    /**
     * Digits 6 by 11 px, 2 px apart, right-aligned at {@code right}. A decimal point is a 2 by 2 px dot on the baseline,
     * twice a segment's stroke (owner, 2026-10-28: "the point should be slightly larger"), in 2 px of its own between
     * the digits.
     */
    /** The width {@link #segments} draws {@code text} in. */
    static int segmentsWidth(String text) {
        int digits = (int) text.chars().filter(ch -> ch != '.').count();
        return digits * 8 - 2 + (text.length() - digits) * 2;
    }

    private static void segments(Canvas c, String text, int right, int top, int on, int off) {
        int x = right - segmentsWidth(text);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '.') {
                c.fill(x - 1, top + 9, x + 1, top + 11, on);
                x += 2;
                continue;
            }
            digit(c, x, top, 0b1111111, off);
            digit(c, x, top, bits(ch), on);
            x += 8;
        }
    }

    private static void digit(Canvas c, int x, int y, int bits, int argb) {
        for (int s = 0; s < 7; s++) if ((bits >> s & 1) != 0) c.fill(x + SEG[s][0], y + SEG[s][1], x + SEG[s][2], y + SEG[s][3], argb);
    }

    /** A character's segments, a to g as bits 0 to 6. */
    static int bits(char ch) {
        return switch (ch) {
            case '0', 'O' -> 0b0111111;
            case '1' -> 0b0000110;
            case '2' -> 0b1011011;
            case '3' -> 0b1001111;
            case '4' -> 0b1100110;
            case '5' -> 0b1101101;
            case '6' -> 0b1111101;
            case '7' -> 0b0000111;
            case '8' -> 0b1111111;
            case '9' -> 0b1101111;
            case '-' -> 0b1000000;
            case 'o' -> 0b1011100;
            case 'f', 'F' -> 0b1110001;
            default -> 0;
        };
    }

    /** The HMI screen: its bezel, a little deeper below for the power LED. */
    @Override
    public Rect well(Canvas c, Rect r) {
        c.fill(r.x() + 1, r.y(), r.right() - 1, r.bottom(), screenBezel);
        c.fill(r.x(), r.y() + 1, r.right(), r.bottom() - 1, screenBezel);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.y() + 2, mix(screenBezel, 0xFFFFFFFF, 0.18));
        var glass = new Rect(r.x() + 3, r.y() + 3, r.w() - 6, r.h() - 7);
        c.fill(glass.x(), glass.y(), glass.right(), glass.bottom(), screen.glass());
        c.fill(r.right() - 8, r.bottom() - 3, r.right() - 5, r.bottom() - 2, screen.on());
        return new Rect(glass.x() + 3, glass.y() + 3, glass.w() - 6, glass.h() - 6);
    }

    /** A fold pressed into the door. */
    @Override
    public void vDivider(Canvas c, int x, int y0, int y1) {
        c.fill(x, y0, x + 1, y1, door.dark());
        c.fill(x + 1, y0, x + 2, y1, door.light());
    }

    @Override
    public void hDivider(Canvas c, int x0, int x1, int y) {
        c.fill(x0, y, x1, y + 1, door.dark());
        c.fill(x0, y + 1, x1, y + 2, door.light());
    }

    /** A pilot light: a lens in a bezel with a chrome edge; lit, it glows on the door. */
    @Override
    public void mark(Canvas c, int x, int y, boolean selected) {
        if (selected) {
            int glow = (lampOn & 0x00FFFFFF) | 0x50000000;
            c.fill(x + 1, y - 1, x + 7, y + 9, glow);
            c.fill(x - 1, y + 1, x + 9, y + 7, glow);
        }
        c.fill(x + 2, y, x + 6, y + 8, bezel);
        c.fill(x, y + 2, x + 8, y + 6, bezel);
        c.fill(x + 1, y + 1, x + 7, y + 7, bezel);
        c.fill(x + 2, y + 1, x + 6, y + 2, chrome);
        int lens = selected ? lampOn : lampOff;
        c.fill(x + 3, y + 2, x + 5, y + 6, lens);
        c.fill(x + 2, y + 3, x + 6, y + 5, lens);
        c.fill(x + 3, y + 3, x + 4, y + 4, selected ? 0xE0FFFFFF : 0x40FFFFFF);
    }

    /** An LED bar graph, 2 px segments; the top tenth in its own colour, as a meter's printed zone. */
    @Override
    public void bar(Canvas c, Rect r, double share) {
        window(c, r, bar.glass());
        int x0 = r.x() + 2, n = (r.w() - 3) / 3, lit = (int) Math.round(n * share);
        for (int s = 0; s < n; s++) {
            boolean top = s >= n * 9 / 10;
            int on = top ? barTop : bar.on(), off = top ? mix(bar.glass(), barTop, 0.22) : bar.off();
            c.fill(x0 + 3 * s, r.y() + 2, x0 + 3 * s + 2, r.bottom() - 2, s < lit ? on : off);
        }
    }

    /** A slot in the door with a small black key in it. */
    @Override
    public void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging) {
        c.fill(track.x() + 3, track.y(), track.x() + 5, track.bottom(), door.dark());
        c.fill(track.x() + 5, track.y(), track.x() + 6, track.bottom(), door.light());
        c.fill(thumb.x() + 1, thumb.y(), thumb.right() - 1, thumb.bottom(), bezel);
        c.fill(thumb.x() + 2, thumb.y() + 1, thumb.right() - 2, thumb.bottom() - 1, dragging ? lit.face() : key.face());
        c.fill(thumb.x() + 2, thumb.y() + 1, thumb.right() - 2, thumb.y() + 2, key.light());
    }

    /** {@code a} towards {@code b} by {@code t}, opaque. */
    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
