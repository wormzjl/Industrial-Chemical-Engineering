package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * The game's widgets of a scrolled column, each kept with its slot in the column. {@link #place} moves them as the view
 * scrolls and shows each only while it lies wholly inside the viewport, so a scroll needs no rebuild.
 */
public final class KitWidgets {
    private record Kept(AbstractWidget widget, Rect slot, boolean shown) {
    }

    private final List<Kept> kept = new ArrayList<>();

    /** Keeps {@code widget} at content slot {@code slot}; {@code shown} false keeps it hidden at any offset. */
    public <T extends AbstractWidget> T keep(T widget, Rect slot, boolean shown) {
        kept.add(new Kept(widget, slot, shown));
        return widget;
    }

    public <T extends AbstractWidget> T keep(T widget, Rect slot) {
        return keep(widget, slot, true);
    }

    public void clear() {
        kept.clear();
    }

    /** Every kept widget at its slot as {@code view} shows it; {@code left} and {@code top} the screen's corner. */
    public void place(ScrollView view, int left, int top) {
        for (var k : kept) {
            var r = view.onScreen(k.slot());
            k.widget().setPosition(left + r.x(), top + r.y());
            k.widget().visible = k.shown() && view.visible(k.slot());
        }
    }
}
