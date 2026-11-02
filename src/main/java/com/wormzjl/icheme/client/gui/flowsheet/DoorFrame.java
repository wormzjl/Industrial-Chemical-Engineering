package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a {@link Door}'s parts stand on a door {@code w} by {@code h} (batch 2026-11-02-process-simulator): the device frame's or the calculator's slots, and what draws under
 * the widgets. The screen and the render tool share it, so a door has one frame.
 */
public final class DoorFrame {
    private DoorFrame() {
    }

    public static List<Rect> tabs(Door door, int w) {
        if (door.calculator()) return CalculatorColumn.tabSlots(w);
        var tabs = new ArrayList<Rect>();
        for (var slot : FluidGuiLayout.tabs(w, door.tabs().size())) tabs.add(new Rect(slot.x(), 29, slot.width(), 18));
        return tabs;
    }

    public static Rect status(Door door, int w) {
        return door.calculator() ? CalculatorColumn.display(w) : new Rect(10, 59, w - 20, 9);
    }

    private static boolean two(Door door) {
        return door.columns().size() == 2;
    }

    /** The rows a column, as the door at {@code w} shows them: a calculator page's two fold into one where the door is too narrow to split. */
    public static List<List<Column.Row>> columns(Door door, int w) {
        if (!door.calculator() || !two(door) || CalculatorColumn.split(w)) return door.columns();
        var all = new ArrayList<Column.Row>();
        door.columns().forEach(all::addAll);
        return List.of(all);
    }

    /** The door's columns, one area a list of rows. */
    public static List<Rect> areas(Door door, int w, int h) {
        if (door.calculator()) return CalculatorColumn.door(w, h, two(door));
        int side = FluidGuiLayout.side(w), top = FluidGuiLayout.DOOR_TOP;
        return List.of(new Rect(10, top, side - 10, h - FluidGuiLayout.DOOR_FOOT - top));
    }

    /** The scroll bar's x beside the columns. */
    public static int bar(Door door, int w) {
        return door.calculator() ? CalculatorColumn.bar(w) : FluidGuiLayout.side(w) + 4;
    }

    /** The glass, before the skin's edge. */
    public static Rect glass(Door door, int w, int h) {
        if (door.calculator()) return CalculatorColumn.glass(w, h, two(door));
        int side = FluidGuiLayout.side(w);
        return new Rect(side + 15, 72, w - side - 20, h - 50 - 72);
    }

    /** Close, then the door's footer keys in order; the last four of a device door are the display keys. */
    public static List<Rect> footer(Door door, int w, int h, com.wormzjl.icheme.client.gui.common.kit.Measure m) {
        if (door.calculator()) return CalculatorColumn.footer(w, h);
        var slots = FluidGuiLayout.footer(w);
        int y = h - 27;
        var keys = new ArrayList<Rect>();
        keys.add(new Rect(slots.get(0).x(), y, slots.get(0).width(), 18));
        int x = slots.get(1).x();
        for (var key : door.footer()) {
            int width = Math.max(slots.get(1).width(), m.width(key.text()) + 14);
            keys.add(new Rect(x, y, width, 18));
            x += width + 4;
        }
        for (int i = 2; i < 6; i++) keys.add(new Rect(slots.get(i).x(), y, slots.get(i).width(), 18));
        return keys;
    }

    /** Draws what lies under the widgets; returns the glass's inner box. */
    public static Rect back(Canvas c, Skin skin, Door door, int w, int h) {
        skin.page(c, new Rect(0, 0, w, h));
        skin.title(c, 10, 10, door.title());
        skin.status(c, status(door, w), c.measure().cut(door.status(), status(door, w).w() - 4), door.tone());
        Rect box;
        if (door.calculator()) {
            skin.hDivider(c, 8, w - 8, h - CalculatorColumn.DIVIDER);
            skin.softKeys(c, CalculatorColumn.keys(w, h));
            for (int x : CalculatorColumn.dividers(w, h, two(door))) skin.vDivider(c, x, CalculatorColumn.TOP, h - CalculatorColumn.FOOT);
        } else {
            int side = FluidGuiLayout.side(w);
            skin.vDivider(c, side + 13, FluidGuiLayout.DOOR_TOP, h - FluidGuiLayout.DOOR_FOOT);
            skin.hDivider(c, 8, w - 8, h - 49);
            skin.softKeys(c, FluidGuiLayout.softKeys(w, h));
        }
        box = skin.well(c, glass(door, w, h));
        door.glass().draw(c, skin, box);
        return box;
    }
}
