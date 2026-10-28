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
 * again each frame for their texts; the rows with widgets come first, so the widgets keep their slots. A door may hold
 * columns side by side (layout C, the column calculator): they scroll together, in one view with one bar.
 */
public final class KitDoor {
    private final ScrollView view = new ScrollView();
    private final KitWidgets widgets = new KitWidgets();
    private List<Rect> areas = List.of(new Rect(0, 0, 0, 0));
    private int bar;
    private List<Column> columns = List.of();

    /**
     * Lays the view over {@code area} (door coordinates) for rows as tall as {@code tallest} (the rows with every line
     * they may show), and forgets the kept widgets: at the screen's init.
     */
    public void layout(Rect area, List<? extends Column.Row> tallest, Measure m) {
        widgets.clear();
        int tall = Column.place(tallest, area.x(), area.y(), area.w(), m, Column.Metrics.COMPACT).bounds().h();
        int width = tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w();
        areas = List.of(new Rect(area.x(), area.y(), width, area.h()));
        bar = area.x() + width + 3;
        place(tallest, m);
    }

    /**
     * Lays the view over columns side by side, each in its {@code areas} entry (door coordinates, one top and height),
     * the bar at {@code bar} (the areas leave its room), for rows {@code rows} (one list a column), and forgets the kept
     * widgets.
     */
    public void layout(List<Rect> areas, int bar, List<? extends List<? extends Column.Row>> rows, Measure m) {
        if (areas.isEmpty() || areas.size() != rows.size()) throw new IllegalArgumentException("One area a column");
        widgets.clear();
        this.areas = List.copyOf(areas);
        this.bar = bar;
        placeAll(rows, m);
    }

    /** {@code rows} placed in the area at the width {@link #layout} chose: at init for the widgets, each frame for the texts. */
    public Column place(List<? extends Column.Row> rows, Measure m) {
        return placeAll(List.of(rows), m).getFirst();
    }

    /** Each column's rows placed in its area; the view as tall as the tallest. */
    public List<Column> placeAll(List<? extends List<? extends Column.Row>> rows, Measure m) {
        if (rows.size() != areas.size()) throw new IllegalArgumentException("One row list an area");
        var placed = new ArrayList<Column>();
        int tall = 0;
        for (int i = 0; i < rows.size(); i++) {
            var a = areas.get(i);
            var c = Column.place(rows.get(i), a.x(), a.y(), a.w(), m, Column.Metrics.COMPACT);
            placed.add(c);
            tall = Math.max(tall, c.bounds().h());
        }
        columns = List.copyOf(placed);
        var first = areas.getFirst();
        view.layout(new Rect(first.x() - 2, first.y(), areas.getLast().right() - first.x() + 4, first.h()), bar, tall);
        return columns;
    }

    public Column column() {
        if (columns.isEmpty()) throw new IllegalStateException("No column placed");
        return columns.getFirst();
    }

    /** The columns as placed, left to right. */
    public List<Column> columns() {
        return columns;
    }

    /** The view's offset back to the top (a page change). */
    public void reset() {
        view.reset();
    }

    /**
     * Keeps every field and button of the columns as placed, each made by {@code field} or {@code button} at its content
     * slot (the screen adds it), a setting's steppers as buttons; {@link #place(int, int)} moves them to where the view
     * shows them.
     */
    public void keep(BiFunction<Column.Field, Rect, ? extends AbstractWidget> field, BiFunction<Column.Button, Rect, ? extends AbstractWidget> button) {
        for (var column : columns)
            for (var p : column.rows()) {
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

    /** Draws the columns clipped to their view, and the view's bar; returns the hint at door point ({@code x}, {@code y}), or null. */
    public @Nullable String draw(Canvas c, Skin skin, double x, double y) {
        c.clip(view.viewport());
        for (var column : columns) column.draw(c, -view.offset(), skin);
        c.unclip();
        view.drawBar(c, skin);
        if (!view.inside(x, y)) return null;
        for (var column : columns) {
            var hover = column.hover(x, view.contentY(y));
            if (hover != null) return hover;
        }
        return null;
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

    /** The view, for a gate: its viewport, offset and largest offset. */
    public ScrollView view() {
        return view;
    }

    /** A hint on hover, its lines as written and each wrapped to 200 px. */
    public static void hint(GuiGraphics g, Font font, @Nullable String text, int x, int y) {
        if (text == null) return;
        var lines = new ArrayList<FormattedCharSequence>();
        for (var line : text.split("\n")) lines.addAll(font.split(Component.literal(line), 200));
        g.renderTooltip(font, lines, x, y);
    }
}
