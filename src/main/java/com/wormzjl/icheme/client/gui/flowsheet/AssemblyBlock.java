package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A built column's assembly drawn as the process simulator draws a column (owner 2026-11-03: "looks more like the process
 * simulator"): the sheet's {@link ColumnBlock} of the column's component design, each part by its check. The block's parts are
 * the slots of {@link ColumnBlueprint} the assembly checks, so a drawn part is the part the check reports. No Minecraft classes.
 */
public final class AssemblyBlock {
    private static final int PLATE = 0xFF10181D, FRAME = 0xFF7F97A5, GRID = 0x24FFFFFF, MARGIN = 6;
    public final ColumnBlock block;
    private int ox, oy;
    private double scale = 1;

    /** The block of the column built from {@code trays}. */
    public AssemblyBlock(ColumnTrays trays) {
        block = ColumnBlock.of(unit(trays), ColumnDoor.of(params(trays)));
    }

    /**
     * The sheet's column door that draws as {@code trays}: its trays, feed, draws, steam stages and pumparounds by the same numbers
     * (ColumnTrays.of reads them so from a calculator), its condenser, member counts and pumps. A kettle stands for a positive
     * reboiler duty, an ejector for a top under ambient pressure; the door's other numbers do not change the drawing.
     */
    public static Params params(ColumnTrays trays) {
        var c = Objects.requireNonNull(trays.components(), "A column without a component design has no assembly");
        var door = new JsonObject();
        door.addProperty("stages", trays.trays());
        door.addProperty("feed_stage", trays.feed());
        door.addProperty("top_pressure", c.ejector() ? PhaseLayout.AMBIENT_PRESSURE / 3 : PhaseLayout.AMBIENT_PRESSURE * 1.5);
        door.addProperty("stage_drop", 700);
        door.addProperty("condenser_temperature", 320);
        door.addProperty("reflux", 2);
        door.addProperty("reboiler_duty", c.kettle() ? 1e6 : 0);
        door.addProperty("condenser", c.condenser().name());
        door.addProperty(ColumnDoor.CONDENSER_UNITS, c.units().condenser());
        door.addProperty(ColumnDoor.KETTLE_UNITS, c.units().kettle());
        door.addProperty(ColumnDoor.TRIM_UNITS, c.units().trim());
        door.addProperty("reflux_pump", c.refluxPump().name());
        var draws = new JsonArray();
        for (int i = 0; i < trays.draws().size(); i++) {
            var row = row(draws, i);
            row.addProperty("tray", trays.draws().get(i));
            row.addProperty("flow", 1);
        }
        door.add("draws", draws);
        var steam = new JsonArray();
        for (int i = 0; i < trays.steam().size(); i++) {
            var row = row(steam, i);
            row.addProperty("stage", trays.steam().get(i));
            row.addProperty("flow", 1);
            row.addProperty("temperature", 450);
        }
        door.add("steam", steam);
        var pumparounds = new JsonArray();
        for (int i = 0; i < trays.pumparounds().size(); i++) {
            var p = trays.pumparounds().get(i);
            var row = row(pumparounds, i);
            row.addProperty("return_tray", p.ret());
            row.addProperty("draw_tray", p.draw());
            row.addProperty("duty", -1e6);
            row.addProperty(ColumnDoor.UNITS, i < c.units().coolers().size() ? c.units().coolers().get(i) : 1);
            row.addProperty("pump", (i < c.pumparoundPumps().size() ? c.pumparoundPumps().get(i) : ColumnComponents.Pump.STANDARD).name());
        }
        door.add("pumparounds", pumparounds);
        return new Params(door);
    }

    private static JsonObject row(JsonArray rows, int i) {
        var row = new JsonObject();
        row.addProperty("id", Integer.toString(i + 1));
        rows.add(row);
        return row;
    }

    private static Flowsheet.Unit unit(ColumnTrays trays) {
        return new Flowsheet.Unit("column", "column", "", 0, 0, params(trays), new JsonObject());
    }

    /** Fits the block into {@code box}, centred, as large as it fits with a margin. */
    public void fit(Rect box) {
        var f = block.frame();
        scale = Math.min((box.w() - 2.0 * MARGIN) / f.w(), (box.h() - 2.0 * MARGIN) / f.h());
        ox = (int) Math.round(box.x() + (box.w() - f.w() * scale) / 2 - f.x() * scale);
        oy = (int) Math.round(box.y() + (box.h() - f.h() * scale) / 2 - f.y() * scale);
    }

    /** A part's look: the tower neutral while it is in place (or before a check), every other part by its check's colour, red before one. */
    public static Look look(Slot slot, @Nullable AssemblyReport report) {
        var r = AssemblyColumn.slot(report, slot);
        if (slot.equals(ColumnBlueprint.COLUMN) && (r == null || r.state() == AssemblyReport.State.OK)) return SheetPainter.NONE;
        return Look.of(AssemblyColumn.colour(r));
    }

    /**
     * Draws the block as {@link #fit} placed it, on the sheet's dotted grid: its frame, pipes, tower and parts by their checks, the
     * inlets' names on the tower, its {@code name} on its plate (none while it has none), the {@code selected} part ringed. {@code t} reads keys under {@code gui.icheme.}.
     */
    public void draw(Canvas c, Rect box, @Nullable AssemblyReport report, @Nullable Slot selected, String name, HeatControlColumn.Texts t) {
        int step = Math.max(8, (int) Math.round(16 * scale));
        c.batch(() -> {
            for (int y = box.y() + Math.floorMod(oy - box.y() + step / 2, step); y < box.bottom(); y += step)
                for (int x = box.x() + Math.floorMod(ox - box.x() + step / 2, step); x < box.right(); x += step) c.fill(x, y, x + 1, y + 1, GRID);
        });
        var p = new FlowsheetPainter(c, ox, oy, scale, 1);
        SheetPainter.block(p, block, FRAME, false, SheetPainter.NONE, slot -> look(slot, report), t);
        if (p.labels()) for (var mark : block.marks()) p.tag(t.get("flowsheet." + mark.key(), mark.argument()), mark.x(), mark.y(), .5f, Palette.TEXT, PLATE);
        var f = block.frame();
        if (p.labels() && !name.isEmpty()) p.tag(name, f.x() + 12, f.y(), 0f, Palette.TEXT, PLATE);
        if (selected != null)
            for (var part : block.parts())
                if (part.slot().equals(selected) && part.shape() != ColumnBlock.Shape.TOWER) {
                    var r = part.rect();
                    c.outline(p.x(r.x()) - 2, p.y(r.y()) - 2, p.px(r.w()) + 4, p.px(r.h()) + 4, Palette.TEXT);
                } else if (part.slot().equals(selected)) {
                    var r = block.tower();
                    c.outline(p.x(r.x()) - 2, p.y(r.y()) - 2, p.px(r.w()) + 4, p.px(r.h()) + 4, Palette.TEXT);
                }
    }

    /** The slot of the part at canvas point ({@code x}, {@code y}), or null: a part on the tower's lines before the tower. */
    public @Nullable Slot part(double x, double y) {
        double dx = (x - ox) / scale, dy = (y - oy) / scale, reach = 2 / scale;
        for (var part : block.parts()) if (part.shape() != ColumnBlock.Shape.TOWER && part.at(dx, dy, reach)) return part.slot();
        var t = block.tower();
        return dx >= t.x() && dx <= t.right() && dy >= t.y() && dy <= t.bottom() ? ColumnBlueprint.COLUMN : null;
    }

    public double scale() {
        return scale;
    }
}
