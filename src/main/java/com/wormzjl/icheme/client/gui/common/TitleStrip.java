package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.equipment.DeviceName;
import org.jetbrains.annotations.Nullable;

/**
 * A device screen's title strip (N1; the owner 2026-11-03, "You can put the equipment name here"): the kind's plate at the
 * left, the device's name after it, the theme key at the right where the screen has one. A player who may name the device
 * finds an LCD entry there, sent with Apply; a reader sees the name as text. The plate says the kind only, as the name
 * stands beside it. The entry keeps one width, {@link #NAME}, room for a whole name, where the strip has it. No Minecraft
 * classes: the device, assembly and datasheet screens, the gates and the render tool place the same strip.
 */
public final class TitleStrip {
    private TitleStrip() {
    }

    /** The plate's text origin (as {@code Skin.title} draws it), the name's top and height (the theme key's), the gap between them. */
    public static final int TITLE_X = 10, TITLE_Y = 10, TOP = 5, HEIGHT = Column.WIDGET, GAP = 6;
    /** The entry's width: {@link DeviceName#MAX} characters of the widest letters (6 px) and the LCD's 4 px either side. */
    public static final int NAME = DeviceName.MAX * 6 + 8;
    /** The narrowest the name's place gets where the strip is short of room (below the design size). */
    public static final int NAME_MIN = 40;

    /**
     * The strip: the {@code plate} behind the {@code kind} it shows (cut only where the strip has no room for it whole), and
     * the name's place, null where none is left.
     */
    public record Strip(Rect plate, String kind, @Nullable Rect name) {
    }

    /** A key's side on the strip (the theme key's, the help key's) and the gap between two keys. */
    public static final int KEY = HEIGHT, KEY_GAP = Column.BUTTON_GAP;

    /**
     * The places of {@code count} keys (0 to 2) at the strip's right, the first rightmost: the theme key where it always stood,
     * then the help key ({@code ?}, batch 2026-11-12-tutorial-system) left of it. A screen with a help key keeps its theme
     * key where it stood.
     */
    public static java.util.List<Rect> keys(int width, int count) {
        if (count < 0 || count > 2) throw new IllegalArgumentException("Zero to two title keys");
        var out = new java.util.ArrayList<Rect>();
        for (int i = 0; i < count; i++) out.add(new Rect(width - 10 - KEY - i * (KEY + KEY_GAP), TOP, KEY, KEY));
        return java.util.List.copyOf(out);
    }

    /** The width {@code count} keys take at the strip's right: what {@link #place} keeps for them. */
    public static int keysWidth(int count) {
        return count <= 0 ? 0 : count * KEY + (count - 1) * KEY_GAP;
    }

    /** The strip of a screen {@code width} wide with {@code count} keys at its right ({@link #keys}), for {@code kind}. */
    public static Strip placeKeys(int width, int count, String kind, Measure m) {
        return place(width, keysWidth(count), kind, m);
    }

    /** The strip of a screen {@code width} wide whose theme key takes {@code key} px at its right (0: no key), for {@code kind}. */
    public static Strip place(int width, int key, String kind, Measure m) {
        int right = width - 10 - (key > 0 ? key + GAP : 0);
        int room = right - TITLE_X;
        String shown = m.width(kind) <= room ? kind : m.cut(kind, room);
        var plate = new Rect(TITLE_X - 4, TITLE_Y - 4, m.width(shown) + 8, 16);
        int x = plate.right() + GAP, w = Math.min(NAME, right - x);
        return new Strip(plate, shown, w >= NAME_MIN ? new Rect(x, TOP, w, HEIGHT) : null);
    }

    /** A reader's name in its place: cut to it (its hover says it whole), empty for none. */
    public static String text(String name, Rect place, Measure m) {
        return m.cut(name, place.w() - 8);
    }

    /** Where a reader's name text stands in its place: the LCD's inset, centred on the line. */
    public static int[] textAt(Rect place) {
        return new int[]{place.x() + 4, place.y() + (place.h() - 8) / 2};
    }
}
