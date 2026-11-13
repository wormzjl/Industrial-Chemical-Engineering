package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the simulator's help tours stand (batch 2026-11-12-tutorial-system, P4b): each tab's tour by its page name, and the
 * anchors a stop may name on it: a frame key by its key ({@code run}, {@code file/open}, {@code tab/1}, {@code size_all}), a
 * door row's widget by its key ({@code view/1}, {@code tolerance}), and the named areas {@code status}, {@code canvas} and
 * {@code palette} (the sheet tab) or {@code glass} (the other tabs). No Minecraft class: the screen resolves the same map,
 * and the gate checks every stop against it.
 */
public final class SimulatorTour {
    /** The guide step the sheet tour's last stop shows: chapter 4's card. */
    public static final String SHOWN_STEP = "simulator/balance";
    /** The step the sheet page's tour completes: the reading step of what a flowsheet is. */
    public static final String SHEET_STEP = "simulator/sheet";
    /** The steps the screen reports itself: the bundled small unit opened ({@link #EXAMPLE}), and Size all's sizes landed. */
    public static final String OPEN_STEP = "simulator/open", SIZE_STEP = "simulator/size", EXAMPLE = "small_crude_unit";

    private SimulatorTour() {
    }

    /** The tour of {@code tab}: {@code simulator.sheet}, {@code simulator.results}, {@code simulator.sizing} or {@code simulator.basis}. */
    public static String page(SimulatorFrame.Tab tab) {
        return "simulator." + switch (tab) {
            case FLOWSHEET -> "sheet";
            case RESULTS -> "results";
            case SIZING -> "sizing";
            case BASIS -> "basis";
        };
    }

    /** The door's width on {@code tab}; 0 on the sheet tab, which has none. */
    static int door(SimulatorFrame.Tab tab) {
        return switch (tab) {
            case FLOWSHEET -> 0;
            case RESULTS -> SimulatorFrame.RESULT_DOOR;
            case SIZING -> SimulatorFrame.SIZING_DOOR;
            case BASIS -> SimulatorFrame.BASIS_DOOR;
        };
    }

    /** The glass right of a tab's door, before the skin's edge (as {@link SimulatorPage} draws it). */
    public static Rect glass(SimulatorFrame frame, SimulatorFrame.Tab tab, int w) {
        int door = door(tab);
        return new Rect(door + 9, 71, w - door - 14, frame.bottom() - 71);
    }

    /**
     * The anchors of {@code tab} that stand where the frame sets them: its frame keys ({@code keys}, as
     * {@link SimulatorPage#frameKeys} gives them), the status, and the canvas and palette or the glass. The door's rows add
     * their widgets' slots ({@link #door}).
     */
    public static Map<String, Rect> frame(SimulatorFrame frame, SimulatorFrame.Tab tab, int w, List<SimulatorPage.Slot> keys) {
        var out = new LinkedHashMap<String, Rect>();
        for (var slot : keys) out.putIfAbsent(slot.button().key(), slot.rect());
        out.put("status", frame.status());
        if (tab == SimulatorFrame.Tab.FLOWSHEET) {
            out.put("canvas", SimulatorFrame.inner(frame.well()));
            out.put("palette", frame.palette());
        } else out.put("glass", glass(frame, tab, w));
        return out;
    }

    /** The door's widgets' slots by key, and a choice row by its action ({@code package}), as placed (before any scroll). */
    public static Map<String, Rect> door(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var placed : column.rows()) {
            out.putAll(placed.slots());
            if (placed.row() instanceof Column.Choice choice && choice.action() != null) out.putIfAbsent(choice.action(), placed.rect());
        }
        return out;
    }
}
