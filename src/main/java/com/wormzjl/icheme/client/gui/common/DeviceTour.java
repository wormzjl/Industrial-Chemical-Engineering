package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the fluid device screen's help tours stand (batch 2026-11-12-tutorial-system, P6): one tour for each tab, shared by
 * every device that shows the tab ({@code fluid_device.<tab>}), and on the Overview one for each door layout the device
 * column builds ({@link Layout}): devices whose door rows are the same share a tour, and a stop whose row a device lacks is
 * skipped. The anchors the frame sets, in the screen's own coordinates (its left and top at 0): the message display
 * ({@code status}), the tabs ({@code tab:<id>}), the HMI glass ({@code glass}: the Control tab's drawing, else the table
 * area), the display keys ({@code units}), Close ({@code close}), the footer's second key ({@code apply} or {@code copy}),
 * and the Composition page's keys ({@code load_mixture}, {@code new_mixture}, {@code search}) and the Solids page's entry
 * ({@code solidFraction}). The door's rows resolve through {@link TourAnchors}. No Minecraft class: the screen resolves
 * the same map, and the gate checks every stop against it.
 */
public final class DeviceTour {
    private DeviceTour() {
    }

    /** Every tab id the screen shows ({@code FluidDeviceScreen.rebuild}), the Overview first. */
    public static final List<String> TABS = List.of("overview", "control", "composition", "solids", "connections", "outlets", "datasheet", "accessory", "safety_valve");

    /**
     * The Overview's door layouts, by what leads the device column ({@code DeviceColumn.Input.lead}) and what the glass
     * draws: a batch still (with a column and its condenser: its reflux valve), a fluid converter, a cooling tower, a heat
     * unit's side, a built pump whose glass draws its curve, the other movers (pumps and compressors), a control valve, a
     * pipe, and every other device (tanks, vessels, feeds, voids, filters).
     */
    public enum Layout {
        STILL, STILL_COLUMN, CONVERTER, TOWER, HEAT, PUMP_CURVE, MOVER, VALVE, PIPE, VESSEL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The tour of tab {@code tab} on a device whose Overview has door layout {@code layout}. */
    public static String page(String tab, Layout layout) {
        if (!TABS.contains(tab)) throw new IllegalArgumentException("No tab " + tab);
        return "fluid_device." + (tab.equals("overview") ? layout.key() : tab);
    }

    /** The table area's left edge and width on every page but the Control tab ({@code FluidDeviceScreen.tableX}, {@code tableWidth}). */
    public static int tableX(int width) {
        return FluidGuiLayout.side(width) + 24;
    }

    public static int tableWidth(int width) {
        return width - tableX(width) - 22;
    }

    /** The Composition page's two keys over its table, Load and New ({@code FluidDeviceScreen.buildComposition}). */
    public static List<Rect> mixtureKeys(int width) {
        int x = tableX(width), bw = Math.max(60, (tableWidth(width) - 12) / 3);
        return List.of(new Rect(x, 82, bw, 18), new Rect(x + bw + 6, 82, bw, 18));
    }

    /** The Composition page's component search entry. */
    public static Rect search(int width) {
        return new Rect(tableX(width), 112, Math.min(360, tableWidth(width) - 4) - 20, 18);
    }

    /** The Solids page's solid fraction entry (under its label). */
    public static Rect solidFraction(int width) {
        return new Rect(tableX(width), 94, Math.min(160, tableWidth(width)), 18);
    }

    /** The table area's well on every page but the Control tab ({@code FluidDeviceScreen.pageWell}). */
    public static Rect pageWell(int width, int height) {
        int side = FluidGuiLayout.side(width);
        return new Rect(side + 15, 72, width - side - 20, height - 50 - 72);
    }

    /** The Control tab's drawing well, above its legend where the drawing has line parts ({@code FluidDeviceScreen.controlWell}). */
    public static Rect controlWell(int width, int height, boolean legend) {
        int top = FluidGuiLayout.DOOR_TOP, bottom = height - FluidGuiLayout.DOOR_FOOT;
        if (legend) bottom = bottom - 12 - 6;
        return new Rect(5, top - 3, FluidGuiLayout.control(width).drawingRight() - 3, bottom - top + 3);
    }

    /**
     * The anchors the frame sets on a screen {@code width} by {@code height} showing tab {@code tab} of {@code tabs}: the
     * Control tab's drawing with or without its {@code legend}; the footer's second key where it shows {@code apply} or
     * {@code copy}; the Composition page's keys outside its template list ({@code templates}).
     */
    public static Map<String, Rect> frame(int width, int height, List<String> tabs, String tab, boolean legend, boolean apply, boolean copy, boolean templates) {
        var out = new LinkedHashMap<String, Rect>();
        out.put("status", new Rect(8, 56, width - 16, 14));
        var slots = FluidGuiLayout.tabs(width, tabs.size());
        for (int i = 0; i < tabs.size(); i++) out.put("tab:" + tabs.get(i), new Rect(slots.get(i).x(), 29, slots.get(i).width(), 18));
        out.put("glass", tab.equals("control") ? controlWell(width, height, legend) : pageWell(width, height));
        out.put("units", FluidGuiLayout.softKeys(width, height));
        var footer = FluidGuiLayout.footer(width);
        out.put("close", new Rect(footer.get(0).x(), height - 27, footer.get(0).width(), 18));
        var second = new Rect(footer.get(1).x(), height - 27, footer.get(1).width(), 18);
        if (apply) out.put("apply", second);
        if (copy) out.put("copy", second);
        if (tab.equals("composition") && !templates) {
            var keys = mixtureKeys(width);
            out.put("load_mixture", keys.get(0));
            out.put("new_mixture", keys.get(1));
            out.put("search", search(width));
        }
        if (tab.equals("solids")) out.put("solidFraction", solidFraction(width));
        return out;
    }
}
