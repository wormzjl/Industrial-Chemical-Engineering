package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the help tours of the dialogs and the machine, control and constructor screens stand (batch
 * 2026-11-12-tutorial-system, P6): each screen's tour names, its {@code ?} key's place and the anchors its frame sets, in
 * the screen's own coordinates (its left and top at 0). The door's rows resolve through {@link TourAnchors}. No
 * Minecraft class: the screens resolve the same maps, and {@code ScreenToursTest} checks every stop against them.
 */
public final class ScreenTours {
    private ScreenTours() {
    }

    /** The one-page screens' tours: a gauge's settings, a pipeline tool's, a structure's datasheet, a machine's page. */
    public static final String GAUGE = "gauge.settings", PIPELINE_TOOL = "pipeline_tool.settings", VESSEL = "vessel.datasheet", MACHINE = "machine.overview";

    /** The {@code ?} key of a screen {@code width} wide with no theme key: the title strip's rightmost key. */
    public static Rect help(int width) {
        return TitleStrip.keys(width, 1).getFirst();
    }

    /** A dialog's title plate (as {@code Skin.title} draws its text at (10, 10)), its text cut to {@code room} px. */
    public static Rect plate(String title, int room, Measure m) {
        return new Rect(TitleStrip.TITLE_X - 4, TitleStrip.TITLE_Y - 4, Math.min(m.width(title), room) + 8, 16);
    }

    /** The room a dialog's title takes left of its {@code ?} key, {@code width} wide. */
    public static int titleRoom(int width) {
        return help(width).x() - TitleStrip.GAP - TitleStrip.TITLE_X;
    }

    /** A dialog's frame: its title plate and its footer's push buttons by name, left to right ({@link DialogColumns#footer}). */
    public static Map<String, Rect> dialog(int width, int height, String title, Measure m, List<String> footer) {
        var out = new LinkedHashMap<String, Rect>();
        out.put("title", plate(title, titleRoom(width), m));
        var keys = DialogColumns.footer(width, height, footer.size());
        for (int i = 0; i < footer.size(); i++) out.put(footer.get(i), keys.get(i));
        return out;
    }

    /** A structure's datasheet ({@code VesselScreen}): the glass right of its door column {@code door} wide, Done and, for a player who may name it, Apply. */
    public static Map<String, Rect> vessel(int width, int height, int door, String title, Measure m, boolean names) {
        var out = dialog(width, height, title, m, names ? List.of("done", "apply") : List.of("done"));
        out.put("glass", new Rect(door + 16, 25, width - door - 21, height - 60));
        return out;
    }

    /**
     * A machine's page ({@code MachineScreen}): the device frame's tab, message display and glass, Close, the Datasheet key
     * where the part has one, Apply where it sends settings.
     */
    public static Map<String, Rect> machine(int width, int height, boolean datasheet, boolean apply) {
        var out = new LinkedHashMap<String, Rect>();
        var tab = FluidGuiLayout.tabs(width, 1).getFirst();
        out.put("tab:overview", new Rect(tab.x(), 29, tab.width(), 18));
        out.put("status", new Rect(10, 55, width - 20, 14));
        int side = FluidGuiLayout.side(width);
        out.put("glass", new Rect(side + 15, 72, width - side - 20, height - 50 - 72));
        int x = 10, y = height - 27;
        out.put("close", new Rect(x, y, 50, 18));
        x += 56;
        if (datasheet) {
            out.put("datasheet", new Rect(x, y, 60, 18));
            x += 66;
        }
        if (apply) out.put("apply", new Rect(x, y, 60, 18));
        return out;
    }

    /** The control panel's page names: a loop's settings, its input or output list, the controllers' list, a column's page, a converter's. */
    public static final List<String> CONTROL_PAGES = List.of("loop", "input", "output", "controllers", "column", "converter");

    public static String control(String page) {
        if (!CONTROL_PAGES.contains(page)) throw new IllegalArgumentException("No control page " + page);
        return "control." + page;
    }

    /** The page's own column's first row ({@code page}): a column's or a converter's name and state; the right column of a wide DCS. */
    public static Rect page(List<Column> columns) {
        return columns.getLast().rows().getFirst().rect();
    }

    /** A control panel's frame ({@code ControlPanelScreen}): its title plate, the narrow DCS's list key, Close and Apply. */
    public static Map<String, Rect> controlFrame(ControlColumns.Layout layout, String title, Measure m) {
        var out = dialog(layout.width(), layout.height(), title, m, List.of("close", "apply"));
        if (layout.chooser() != null) out.put("controller_list", layout.chooser());
        return out;
    }

    /** The constructor's pages ({@code EquipmentConstructorScreen.Page}): a column's design page has its own tour. */
    public enum Constructor {
        CHOOSE, DESIGN, DESIGN_COLUMN, PAINT, TEMPLATES, IMPORTS;

        public String page() {
            return "constructor." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The constructor's {@code ?} key: left of Change (the design page) or Back on the title row. */
    public static final Rect CONSTRUCTOR_HELP = new Rect(ConstructorColumn.TITLE_KEY.x() - Column.BUTTON_GAP - TitleStrip.KEY, 5, TitleStrip.KEY, TitleStrip.KEY);
    /** The schematic's glass on a column's design page ({@code EquipmentConstructorScreen.STRIP}). */
    public static final int STRIP = 140;

    /**
     * The constructor's frame on a window {@code width} by {@code height}: the title plate, Change or Back right of it
     * ({@code title_key}), the message display where the page shows one, the footer's keys by their keys, and a column's
     * schematic.
     */
    public static Map<String, Rect> constructor(int width, int height, Constructor page, String title, Measure m, ConstructorColumn.Page shown) {
        var out = new LinkedHashMap<String, Rect>();
        out.put("title", new Rect(6, 6, Math.min(m.width(title), ConstructorColumn.TITLE) + 8, 16));
        if (page != Constructor.PAINT) out.put("title_key", ConstructorColumn.TITLE_KEY);
        if (shown.display()) {
            var line = ConstructorColumn.display(height);
            out.put("status", new Rect(line.x() - 4, line.y() - 5, line.w() + 8, 15));
        }
        var footer = ConstructorColumn.footer(height, shown.footer().size());
        for (int i = 0; i < shown.footer().size(); i++) out.put(shown.footer().get(i).key(), footer.get(i));
        if (page == Constructor.DESIGN_COLUMN) out.put("glass", new Rect(width - 16 - STRIP, 16, STRIP + 12, height - 32));
        return out;
    }
}
