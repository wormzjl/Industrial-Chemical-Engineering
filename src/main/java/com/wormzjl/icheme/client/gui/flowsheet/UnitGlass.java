package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatFlowsheet;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import static com.wormzjl.icheme.client.gui.flowsheet.UnitDoors.*;

/**
 * A unit door's glass (batch 2026-11-02-process-simulator, split from {@link UnitDoors}): an exchanger's or a heater's shipped drawing, the
 * streams side by side, and the sketch of the unit with its streams as pills, a splitter's outlets beneath. Minecraft-free.
 */
final class UnitGlass {
    private UnitGlass() {
    }

    // ---- The glass ----

    /** The shipped drawing of an exchanger's or a heater's assembly, read from the unit's streams. */
    static Door.Glass heat(SheetContext x, Flowsheet.Unit unit, HeatSides.Unit kind, HeatSides.Side side) {
        return (c, skin, box) -> {
            var drawing = new HeatFlowsheet(HeatDiagram.of(kind, side, ExchangerBlueprint.Design.NONE), HeatDiagram.roles(kind, side));
            drawing.fit(box.x(), box.y(), box.w(), box.h());
            drawing.draw(c, data(x, unit));
        };
    }

    private static Flowsheet.@Nullable Stream role(SheetContext x, Flowsheet.Unit unit, String role) {
        String id = unit.id();
        boolean apart = unit.type().equals("fired_heater") && x.editor().unit(id).params().text("coils", "series").equals("apart");
        return switch (role) {
            case "TUBE_INLET" -> x.into(id, "tube_in");
            case "TUBE_OUTLET" -> x.outOf(id, "tube_out");
            case "SHELL_INLET" -> x.into(id, "shell_in");
            case "SHELL_OUTLET" -> x.outOf(id, "shell_out");
            case "CONVECTION_INLET" -> apart ? x.into(id, "convection_in") : x.into(id, "in");
            case "CONVECTION_OUTLET" -> apart ? x.outOf(id, "convection_out") : null;
            case "RADIANT_INLET" -> apart ? x.into(id, "radiant_in") : null;
            case "RADIANT_OUTLET" -> apart ? x.outOf(id, "radiant_out") : x.outOf(id, "out");
            default -> null;
        };
    }

    private static HeatFlowsheet.Data data(SheetContext x, Flowsheet.Unit unit) {
        var u = x.units();
        var results = x.state().shown(unit.id());
        return new HeatFlowsheet.Data() {
            @Override public String value(HeatDiagram.Value value) {
                for (var source : value.sources()) {
                    if (value.show() == HeatDiagram.Show.DUTY) return results == null ? "" : SheetUnits.megawatts(Math.abs(results.reading(unit.id(), "duty"))) + " MW";
                    if (!source.startsWith("port:")) continue;
                    var props = x.props(role(x, unit, source.substring(5).split(":")[0]));
                    if (props == null) continue;
                    if (value.show() == HeatDiagram.Show.TEMPERATURE) return u.t(props.temperature()) + " " + u.temperature().symbol();
                    return SheetUnits.number(1, props.mass()) + " kg/s";
                }
                return "";
            }

            @Override public String state(String slot) {
                return results == null ? "NOT_VALIDATED" : "OK";
            }

            @Override public String text(AssemblyDiagram.Text text) {
                return x.t().get(HeatDiagram.KEY.substring("gui.icheme.".length()) + text.key(), text.args().toArray());
            }
        };
    }

    /** The streams side by side: their properties, one column a stream, as many as the glass holds. */
    static Door.Glass streams(SheetContext x, Flowsheet.Unit unit) {
        return (c, skin, box) -> table(c, x, unit, box);
    }

    /** A column of the Streams table. */
    private record Cell(String head, @Nullable StreamProps props) {
    }

    private static void table(Canvas c, SheetContext x, Flowsheet.Unit unit, Rect box) {
        var cells = new ArrayList<Cell>();
        for (var stream : x.streams(unit.id(), true)) cells.add(new Cell(StreamDoors.portName(x, stream.to().port()) + " " + stream.name(), x.props(stream)));
        for (var stream : x.streams(unit.id(), false)) cells.add(new Cell(StreamDoors.portName(x, stream.from().port()) + " " + stream.name(), x.props(stream)));
        x.openOutlets(unit).forEach((port, props) -> cells.add(new Cell(x.text("door.open_outlet", StreamDoors.portName(x, port)), props)));
        int cell = 90, room = Math.max(1, (box.w() - 130) / cell), n = Math.min(room, cells.size());
        int[] widths = new int[n + 1];
        widths[0] = box.w() - n * cell - 6;
        for (int i = 1; i <= n; i++) widths[i] = cell;
        var u = x.units();
        var rows = new ArrayList<String[]>();
        var head = new String[n + 1];
        head[0] = "";
        var labels = List.of(x.text("label.temperature", u.temperature().symbol()), x.text("label.pressure", u.pressure().symbol()), x.text("label.flow", u.molesUnit()), x.text("label.flow", u.tonnesUnit()), x.text("label.vapour"), x.text("label.density"));
        for (int i = 0; i < n; i++) head[i + 1] = cells.get(i).head();
        rows.add(head);
        for (int r = 0; r < labels.size(); r++) {
            var row = new String[n + 1];
            row[0] = labels.get(r);
            for (int i = 0; i < n; i++) {
                var p = cells.get(i).props();
                row[i + 1] = p == null ? "" : switch (r) {
                    case 0 -> u.t(p.temperature());
                    case 1 -> u.p(p.pressure());
                    case 2 -> u.moles(p.moles());
                    case 3 -> u.tonnes(p.mass());
                    case 4 -> SheetUnits.number(1, p.vapour());
                    default -> SheetUnits.number(1, p.density());
                };
            }
            rows.add(row);
        }
        Door.table(c, box, widths, rows);
    }

    private static final int SKETCH = 118, MID = 58, PILL = 56;

    private static void pill(FlowsheetPainter p, String name, int x, int y, boolean free) {
        p.pill(x, y, PILL, SheetLayout.PILL_HALF);
        p.fitted(name, x + PILL / 2, y, PILL, free ? Palette.MUTED : Palette.TEXT);
    }

    /** The unit on the glass above its table: its symbol, its stream in, its streams out as pills. */
    static Door.Glass sketch(SheetContext x, Flowsheet.Unit unit) {
        return new Door.Glass() {
            @Override public void draw(Canvas c, Skin skin, Rect box) {
                var symbol = Tiles.symbol(unit, x.editor().sheet().sizing());
                var p = new FlowsheetPainter(c, box.x(), box.y(), 1, 1);
                int gx = box.w() / 2 - symbol.w / 2, gy = symbol == Symbol.PUMP ? MID - 13 : MID - symbol.h / 2, inX = gx - 96, outX = gx + symbol.w + 40;
                var in = x.streams(unit.id(), true);
                var out = outlets(x, unit);
                p.wire(new int[]{inX + PILL, MID, gx, MID}, true, false, Palette.MUTED);
                SheetPainter.sprite(c, symbol, box.x() + gx, box.y() + gy, symbol.w, symbol.h, SheetPainter.look(x.state().tone(unit.id())));
                pill(p, in.isEmpty() ? x.text("door.none") : in.getFirst().name(), inX, MID, in.isEmpty());
                for (int i = 0; i < out.size(); i++) {
                    int y = out.size() == 1 ? MID : MID - 36 + i * 72 / (out.size() - 1), cx = gx + symbol.w / 2;
                    int[] points = out.size() == 1 || y == MID ? new int[]{gx + symbol.w, MID, outX, MID} : y < MID ? new int[]{cx, gy, cx, y, outX, y} : new int[]{cx, gy + symbol.h, cx, y, outX, y};
                    p.wire(points, true, false, Palette.MUTED);
                    p.square(points[0], points[1], SheetLayout.PORT, PortColours.CONNECTED);
                    pill(p, out.get(i), outX, y, out.get(i).equals(x.text("door.none")));
                }
                p.square(gx, MID, SheetLayout.PORT, PortColours.CONNECTED);
                p.tag(unit.name(), out.size() > 1 ? gx - 8 : gx + symbol.w / 2, out.size() > 1 ? gy + symbol.h + 12 : gy - 8, .5f, Palette.TEXT, 0xFF10181D);
                c.hLine(box.x() + 2, box.right() - 2, box.y() + SKETCH, Palette.LINE);
                var rest = new Rect(box.x(), box.y() + SKETCH + 3, box.w(), box.h() - SKETCH - 3);
                if (unit.type().equals("splitter")) split(c, skin, x, unit, rest);
                else table(c, x, unit, rest);
            }

            @Override public List<Door.Slot> slots(Rect box) {
                if (!unit.type().equals("splitter")) return List.of();
                var rest = new Rect(box.x(), box.y() + SKETCH + 3, box.w(), box.h() - SKETCH - 3);
                var slots = new ArrayList<Door.Slot>();
                boolean flows = mode(unit).equals("flows");
                int i = 0, y = rest.y() + 18;
                for (var row : unit.params().rows("outlets")) {
                    String id = row.text("id", ""), field = flows ? "flow" : "share";
                    double value = row.number(field, Double.NaN);
                    String text = Double.isNaN(value) ? "" : flows ? x.units().moles(value) : SheetUnits.number(1, value * 100);
                    slots.add(Door.Slot.entry(field + "/" + id, new Rect(rest.x() + 110, y + i * 22 - 1, Column.BOX, 18), text, x.text("door.rest")));
                    slots.add(Door.Slot.key("remove/" + id, new Rect(rest.right() - 22, y + i * 22 - 1, 18, 18), "x", false));
                    i++;
                }
                return slots;
            }
        };
    }

    private static String mode(Flowsheet.Unit unit) {
        return unit.params().text("mode", "shares");
    }

    /** The pills of a unit's outlets, by its ports: a free port's reads none. */
    private static List<String> outlets(SheetContext x, Flowsheet.Unit unit) {
        var names = new ArrayList<String>();
        var box = x.editor().layout().box(unit.id());
        for (var place : box.places()) {
            if (place.direction() != com.wormzjl.icheme.science.flowsheet.Port.Direction.OUT) continue;
            var stream = x.outOf(unit.id(), place.port());
            names.add(stream == null ? x.text("door.none") : stream.name());
        }
        return names;
    }

    private static void split(Canvas c, Skin skin, SheetContext x, Flowsheet.Unit unit, Rect rest) {
        var m = c.measure();
        boolean flows = mode(unit).equals("flows");
        int y = rest.y() + 4;
        String amount = x.units().molesUnit();
        c.text(x.text("door.outlet"), rest.x() + 4, y, Palette.MUTED);
        c.text(flows ? amount : x.text("door.share"), rest.x() + 110, y, Palette.MUTED);
        c.text(amount, rest.right() - 26 - m.width(amount), y, Palette.MUTED);
        c.hLine(rest.x() + 2, rest.right() - 2, y + 10, Palette.LINE);
        int i = 0;
        for (var row : unit.params().rows("outlets")) {
            int ry = y + 14 + i * 22;
            if (i % 2 == 1) c.fill(rest.x(), ry - 2, rest.right(), ry + 20, DialogColumns.BAND);
            var stream = x.outOf(unit.id(), "out/" + row.text("id", ""));
            c.text(stream == null ? x.text("door.outlet_row", row.text("id", "")) : stream.name(), rest.x() + 4, ry + 5, Palette.TEXT);
            var props = x.props(stream);
            if (props != null) {
                String flow = x.units().moles(props.moles());
                c.text(flow, rest.right() - 26 - m.width(flow), ry + 5, Palette.ACCENT);
            }
            i++;
        }
    }
}
