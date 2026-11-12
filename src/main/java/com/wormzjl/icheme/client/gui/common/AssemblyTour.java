package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the column's assembly screen's help tours stand (batch 2026-11-12-tutorial-system, P5 stage B): the Structure
 * tab's tour ({@code assembly.structure}) and the Operation tab's ({@code assembly.operation}), and the anchors a stop may
 * name, in the screen's own coordinates (its left and top at 0): the tabs by their keys ({@code tab:structure},
 * {@code tab:operation}), the message display ({@code status}), the glass ({@code glass}) and, on the Structure tab, its
 * drawing and parts list ({@code drawing}, {@code list}); a door row's widget by its key ({@code check}, {@code open},
 * {@code start}). No Minecraft class: the screen resolves the same map, and the gate checks every stop against it.
 */
public final class AssemblyTour {
    private AssemblyTour() {
    }

    /** The tour of the tab shown: the Operation tab's, else the Structure tab's. */
    public static String page(boolean operation) {
        return operation ? "assembly.operation" : "assembly.structure";
    }

    /** The anchors the frame sets on a screen {@code width} by {@code height} showing its Operation tab or its Structure tab. */
    public static Map<String, Rect> frame(int width, int height, boolean operation) {
        var out = new LinkedHashMap<String, Rect>();
        var tabs = AssemblyColumn.tabSlots(width);
        out.put("tab:structure", tabs.get(0));
        out.put("tab:operation", tabs.get(1));
        out.put("status", AssemblyColumn.display(width));
        var glass = AssemblyColumn.glass(width, height, true);
        out.put("glass", glass);
        if (!operation) {
            var split = AssemblyColumn.split(glass);
            out.put("drawing", split.drawing());
            out.put("list", split.list());
        }
        return out;
    }

    /** The door's widgets' slots by key, as placed (before any scroll). */
    public static Map<String, Rect> door(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var placed : column.rows()) out.putAll(placed.slots());
        return out;
    }
}
