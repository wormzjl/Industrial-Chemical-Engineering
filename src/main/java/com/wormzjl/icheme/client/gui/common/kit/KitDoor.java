package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * A dialog's door column (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 15): its rows in an area of the door,
 * narrowed by the bar's room where they are taller than the area, in a view that scrolls. Their fields and buttons are
 * kept at their content slots and moved as the view scrolls, so a scroll rebuilds nothing. The screen places the rows
 * again each frame for their texts; the rows with widgets come first, so the widgets keep their slots.
 */
public final class KitDoor {
    private final ScrollView view = new ScrollView();
    private final KitWidgets widgets = new KitWidgets();
    private Rect area = new Rect(0, 0, 0, 0);
    private int width, bar;
    private @Nullable Column column;

    /**
     * Lays the view over {@code area} (door coordinates) for rows as tall as {@code tallest} (the rows with every line
     * they may show), and forgets the kept widgets: at the screen's init.
     */
    public void layout(Rect area, List<? extends Column.Row> tallest, Measure m) {
        widgets.clear();
        this.area = area;
        int tall = Column.place(tallest, area.x(), area.y(), area.w(), m, Column.Metrics.COMPACT).bounds().h();
        width = tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w();
        bar = area.x() + width + 3;
        place(tallest, m);
    }

    /** {@code rows} placed in the area at the width {@link #layout} chose: at init for the widgets, each frame for the texts. */
    public Column place(List<? extends Column.Row> rows, Measure m) {
        column = Column.place(rows, area.x(), area.y(), width, m, Column.Metrics.COMPACT);
        view.layout(new Rect(area.x() - 2, area.y(), width + 4, area.h()), bar, column.bounds().h());
        return column;
    }

    public Column column() {
        if (column == null) throw new IllegalStateException("No column placed");
        return column;
    }

    /**
     * Keeps every field and button of the column as placed, each made by {@code field} or {@code button} at its content
     * slot (the screen adds it), a setting's steppers as buttons; {@link #place(int, int)} moves them to where the view
     * shows them.
     */
    public void keep(BiFunction<Column.Field, Rect, ? extends AbstractWidget> field, BiFunction<Column.Button, Rect, ? extends AbstractWidget> button) {
        for (var p : column().rows()) {
            if (p.row() instanceof Column.Field f) {
                var slot = p.slots().get(f.key());
                widgets.keep(field.apply(f, slot), slot);
            } else if (p.row() instanceof Column.Buttons b) for (var spec : b.buttons()) {
                var slot = p.slots().get(spec.key());
                widgets.keep(button.apply(spec, slot), slot);
            } else if (p.row() instanceof Column.Setting s && s.steps()) for (var spec : List.of(s.minus(), s.plus())) {
                var slot = p.slots().get(spec.key());
                widgets.keep(button.apply(spec, slot), slot);
            }
        }
    }

    /** Keeps another widget at content slot {@code slot} (a palette's swatch). */
    public <T extends AbstractWidget> T keep(T widget, Rect slot) {
        return widgets.keep(widget, slot);
    }

    /** Moves the kept widgets to where the view shows them, before they draw; {@code left} and {@code top} the door's corner on screen. */
    public void place(int left, int top) {
        widgets.place(view, left, top);
    }

    /** Draws the column clipped to its view, and the view's bar; returns the hint at door point ({@code x}, {@code y}), or null. */
    public @Nullable String draw(Canvas c, Skin skin, double x, double y) {
        c.clip(view.viewport());
        column().draw(c, -view.offset(), skin);
        c.unclip();
        view.drawBar(c, skin);
        return view.inside(x, y) ? column().hover(x, view.contentY(y)) : null;
    }

    public boolean wheel(double x, double y, double delta) {
        return view.wheel(x, y, delta);
    }

    public boolean press(double x, double y, int button) {
        return view.press(x, y, button);
    }

    public boolean drag(double y) {
        return view.drag(y);
    }

    public void release() {
        view.release();
    }

    /** A hint on hover, its lines as written and each wrapped to 200 px. */
    public static void hint(GuiGraphics g, Font font, @Nullable String text, int x, int y) {
        if (text == null) return;
        var lines = new ArrayList<FormattedCharSequence>();
        for (var line : text.split("\n")) lines.addAll(font.split(Component.literal(line), 200));
        g.renderTooltip(font, lines, x, y);
    }
}
