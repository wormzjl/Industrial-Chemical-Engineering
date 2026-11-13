package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * Where a help tour's stop stands on a door (batch 2026-11-12-tutorial-system, P6), shared by every screen's anchor map:
 * a door row's widget by its slot key ({@code power:on}, {@code minimum}), or a function group by its plate
 * ({@code group:<plate key>}, the group whose plate reads that key's line; {@code group:<n>}, the n-th group from 1), in
 * the door's own coordinates before any scroll; and the scroll that shows it. No Minecraft class: the screens, the gates
 * and the render tool resolve the same anchors.
 */
public final class TourAnchors {
    private TourAnchors() {
    }

    /** {@code anchor} on the placed {@code columns}, or null where none of them holds it; {@code line} reads a plate key's text. */
    public static @Nullable Rect find(String anchor, List<Column> columns, Function<String, String> line) {
        if (anchor.startsWith("group:")) {
            String plate = anchor.substring(6);
            int n = plate.chars().allMatch(Character::isDigit) && !plate.isEmpty() ? Integer.parseInt(plate) : 0, seen = 0;
            String title = n > 0 ? null : line.apply(plate);
            for (var column : columns)
                for (var p : column.rows())
                    if (p.row() instanceof Column.Group g && (n > 0 ? ++seen == n : g.title().equals(title))) return p.rect();
            return null;
        }
        for (var column : columns)
            for (var p : column.rows()) if (p.slots().containsKey(anchor)) return p.slots().get(anchor);
        return null;
    }

    /** {@link #find} on one column. */
    public static @Nullable Rect find(String anchor, Column column, Function<String, String> line) {
        return find(anchor, List.of(column), line);
    }

    /**
     * {@code r} (door coordinates) on the screen through {@code view}: where {@code reveal}, the view is first scrolled a
     * wheel step at a time until {@code r} shows or the view moves no more. The part the view shows is returned.
     */
    public static Rect reveal(ScrollView view, Rect r, boolean reveal) {
        for (int i = 0; reveal && i < 64 && !view.visible(r); i++) {
            var port = view.viewport();
            int before = view.offset();
            view.wheel(port.x() + 2, port.y() + 2, view.onScreen(r).y() < port.y() ? 1 : -1);
            if (view.offset() == before) break;
        }
        // A row taller than the view is lit only where the view shows it, so the light does not cover the footer.
        var on = view.onScreen(r);
        var port = view.viewport();
        int top = Math.max(on.y(), port.y()), bottom = Math.min(on.bottom(), port.bottom());
        return bottom > top ? new Rect(on.x(), top, on.w(), bottom - top) : on;
    }
}
