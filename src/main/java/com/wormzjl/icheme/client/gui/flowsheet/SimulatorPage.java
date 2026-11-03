package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The simulator's pages under their widgets (batch 2026-11-02-process-simulator, sheets 01, 05, 14, 16): which keys stand where, and what draws, so the screen forwards and the
 * render tool draws one page.
 */
public final class SimulatorPage {
    /** A key at its place. */
    public record Slot(Column.Button button, Rect rect) {
    }

    private SimulatorPage() {
    }

    private static Column.Button off(String key, String text, @Nullable String hint) {
        return new Column.Button(key, text, false, hint);
    }

    /** The frame's keys: tabs, file keys, the footer and the display keys. */
    public static List<Slot> frameKeys(SheetContext x, SimulatorFrame.Tab tab, SimulatorFrame frame, SimulatorHost host, SheetEditor editor) {
        var slots = new ArrayList<Slot>();
        var tabs = frame.tabs();
        for (var t : SimulatorFrame.Tab.values()) {
            boolean sizing = t == SimulatorFrame.Tab.SIZING;
            String label = x.text("tab." + t.name().toLowerCase(java.util.Locale.ROOT));
            slots.add(new Slot(sizing && !SizingPage.open(x) ? off("tab/" + t.ordinal(), label, x.text("hint.sizing")) : Door.position("tab/" + t.ordinal(), label, t == tab), tabs.get(t.ordinal())));
        }
        boolean narrow = frame.size() != SimulatorFrame.Size.WIDE, reader = host.reader();
        var file = frame.fileKeys();
        slots.add(new Slot(new Column.Button("file/new", x.text("file.new"), !editor.locked(), null), file.get(0)));
        slots.add(new Slot(new Column.Button("file/open", x.text("file.open"), !editor.locked(), null), file.get(1)));
        slots.add(new Slot(new Column.Button("file/save", x.text("file.save"), host.stores() && !reader, host.stores() ? null : x.text("hint.block")), file.get(2)));
        slots.add(new Slot(new Column.Button("file/save_as", x.text(narrow ? "file.save_as_short" : "file.save_as"), true, null), file.get(3)));
        if (tab == SimulatorFrame.Tab.SIZING) slots.addAll(SizingPage.footer(x, frame, host, editor));
        else footer(slots, x, frame, host, editor, reader);
        var soft = softKeys(x.units(), x.t());
        var at = frame.softKeys();
        for (int i = 0; i < 4; i++) slots.add(new Slot(soft.get(i), at.get(i)));
        return slots;
    }

    private static void footer(List<Slot> slots, SheetContext x, SimulatorFrame frame, SimulatorHost host, SheetEditor editor, boolean reader) {
        var foot = frame.footer();
        boolean running = host.running() != null;
        slots.add(new Slot(new Column.Button("close", x.t().get("fluid.close"), true, null), foot.get(0)));
        slots.add(new Slot(new Column.Button("run", x.text("frame.run"), host.canRun() && !running && !reader, host.canRun() ? null : x.text("hint.run")), foot.get(1)));
        slots.add(new Slot(new Column.Button("stop", x.text("frame.stop"), running, null), foot.get(2)));
        if (foot.size() > 3) {
            slots.add(new Slot(new Column.Button("undo", x.text("frame.undo"), editor.canUndo(), null), foot.get(3)));
            slots.add(new Slot(new Column.Button("redo", x.text("frame.redo"), editor.canRedo(), null), foot.get(4)));
        }
    }

    /** The display keys. */
    public static List<Column.Button> softKeys(SheetUnits u, HeatControlColumn.Texts t) {
        return List.of(new Column.Button("unit/basis", t.get(u.mass() ? "fluid.mass_basis_short" : "fluid.mole_basis_short"), true, null, false, true), new Column.Button("unit/temperature", u.temperature().symbol(), true, null, false, true),
                new Column.Button("unit/pressure", u.pressure().symbol(), true, null, false, true), new Column.Button("unit/time", u.time().suffix(), true, null, false, true));
    }

    /** The keys on the canvas and the palette. */
    public static List<Slot> canvasKeys(SheetContext x, SimulatorFrame frame, Rect box, int category) {
        var slots = new ArrayList<Slot>();
        var zoom = SimulatorFrame.zoomKeys(box);
        String[] keys = {"out", "in", "fit"};
        String[] texts = {"-", "+", x.text("frame.fit")};
        for (int i = 0; i < 3; i++) slots.add(new Slot(new Column.Button("zoom/" + keys[i], texts[i], true, null, false, true), zoom.get(i)));
        var categories = frame.categories();
        if (frame.size() == SimulatorFrame.Size.TINY) {
            slots.add(new Slot(Door.position("category/" + category, x.t().get(Tiles.CATEGORIES.get(category).name()), true), categories.get(0)));
            slots.add(new Slot(Door.key("category-", "<", true, null), categories.get(1)));
            slots.add(new Slot(Door.key("category+", ">", true, null), categories.get(2)));
            return slots;
        }
        for (int i = 0; i < categories.size(); i++) slots.add(new Slot(Door.position("category/" + i, x.t().get(Tiles.CATEGORIES.get(i).name()), i == category), categories.get(i)));
        return slots;
    }

    /** The lit unit types a placeholder may turn into. */
    public static List<String> litTypes() {
        var types = new ArrayList<String>();
        for (var tile : Tiles.all()) if (tile.lit() && !tile.type().equals(Tiles.FEED) && !tile.type().equals(Tiles.PRODUCT)) types.add(tile.type());
        return types;
    }

    /** A dialog's list, by its name. */
    public static FileColumns.Listing listing(String dialog, SheetContext x, List<FileColumns.Entry> files, List<FileColumns.StreamTemplate> streams, List<FileColumns.ColumnTemplate> columns, int chosen) {
        return switch (dialog) {
            case "OPEN" -> FileColumns.open(files, chosen, x.t());
            case "COLUMN_TEMPLATES" -> FileColumns.columnTemplates(columns, chosen, x.units(), x.t());
            case "TEMPLATES" -> FileColumns.streamTemplates(streams, chosen, x.units(), x.t());
            default -> {
                var rows = new ArrayList<String[]>();
                for (var type : litTypes()) rows.add(new String[]{x.t().get(Tiles.name(type)), ""});
                yield FileColumns.templates(rows, chosen, x.t());
            }
        };
    }

    /** Every widget key rows hold. */
    public static List<String> keys(List<? extends Column.Row> rows) {
        var keys = new ArrayList<String>();
        for (var row : rows) {
            switch (row) {
                case Column.Group group -> keys.addAll(keys(group.rows()));
                case Column.Field field -> keys.add(field.key());
                case Column.Wide wide -> keys.add(wide.key());
                case Column.Buttons buttons -> {
                    for (var button : buttons.buttons()) keys.add(button.key());
                }
                case Column.Setting setting -> keys.add(setting.key());
                default -> {
                }
            }
        }
        return keys;
    }

    /** A tile: its symbol, its name in two lines at most; an unlit tile has no process model yet; a carried tile shows ringed. */
    public static void tile(Canvas c, SimulatorFrame.TileSlot slot, boolean lit, boolean carried, HeatControlColumn.Texts t) {
        var m = c.measure();
        var r = slot.rect();
        var symbol = slot.tile().symbol();
        Look look = lit ? SheetPainter.LIT : SheetPainter.UNLIT;
        if (carried) c.outline(r.x(), r.y() + 1, r.w(), r.h() - 2, Palette.TEXT);
        int top = r.y() + 2, room = slot.captions() ? r.h() - 24 : r.h() - 6;
        int[] size = SimulatorFrame.tileSize(symbol, r.w() - 10, room);
        int x = r.x() + (r.w() - size[0]) / 2, y = top + (room - size[1]) / 2;
        if (symbol == Symbol.COLUMN_TOP) SheetPainter.columnTile(c, x, y, size[0], size[1], look);
        else SheetPainter.sprite(c, symbol, x, y, size[0], size[1], look);
        if (!slot.captions()) return;
        var lines = m.wrap(t.get(slot.tile().name()), r.w() - 2);
        int ty = r.y() + r.h() - 1 - lines.size() * 9;
        for (var line : lines) {
            c.text(line, r.x() + (r.w() - m.width(line)) / 2, ty, lit ? Palette.TEXT : SheetPainter.UNLIT.text());
            ty += 9;
        }
    }

    /** A placed key's look, for a page that draws it itself. */
    public static Skin.Press press(Column.Button b) {
        return b.latched() ? Skin.Press.CURRENT : !b.active() ? Skin.Press.OFF : Skin.Press.REST;
    }

    /** The frame under its keys: the page, the title, the status once, the footer's rule and tray. */
    public static void back(Canvas c, Skin skin, SheetState.Status status, String title, SimulatorFrame frame, int w, int h) {
        skin.page(c, new Rect(0, 0, w, h));
        skin.title(c, 10, 8, title);
        var line = frame.status();
        skin.status(c, line, c.measure().cut(status.text(), line.w() - 4), status.tone());
        skin.hDivider(c, 8, w - 8, h - 35);
        skin.softKeys(c, frame.softTray());
    }

    /** The Flowsheet tab's canvas, palette and panel; the zoom keys' tray. Returns the canvas's inner box. */
    public static Rect flowsheet(Canvas c, Skin skin, SheetContext x, SimulatorFrame frame, SheetView view, SheetGestures gestures, SheetEditor editor, int category) {
        var box = skin.well(c, frame.well());
        view.layout(box);
        SheetPainter.draw(c, editor.layout(), x.state(), view, gestures.selected(), gestures.overlay(), x.t());
        skin.softKeys(c, SimulatorFrame.zoomTray(box));
        var well = skin.well(c, frame.tileWell());
        boolean locked = editor.locked();
        var overlay = gestures.overlay();
        for (var slot : frame.tiles(category, well)) tile(c, slot, slot.tile().lit() && !locked, overlay != null && overlay.tile() != null && overlay.tile().equals(slot.tile()), x.t());
        return box;
    }

    /** The panel's page over the canvas's right end. */
    public static void panel(Canvas c, Skin skin, Rect box) {
        var r = SimulatorFrame.panel(box);
        c.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF000000);
        skin.page(c, new Rect(r.x() + 1, r.y(), r.w() - 1, r.h()));
    }

    /** The Results tab under its widgets. */
    public static void results(Canvas c, Skin skin, SheetContext x, ResultsPage.State state, SimulatorFrame frame, int w) {
        int bottom = frame.bottom(), door = SimulatorFrame.RESULT_DOOR;
        skin.vDivider(c, door + 3, SimulatorFrame.DOOR_TOP, bottom);
        if (state.view == ResultsPage.View.STREAMS) {
            var column = SimulatorFrame.doorColumn(ResultsPage.rows(x, state), door, x.m());
            var list = skin.well(c, ResultsPage.list(column, bottom));
            ResultsPage.drawList(c, skin, x, state, list);
        }
        var box = skin.well(c, new Rect(door + 9, 71, w - door - 14, bottom - 71));
        ResultsPage.draw(c, skin, x, state, box);
    }

    /** The Basis tab under its widgets. */
    public static void basis(Canvas c, Skin skin, SheetContext x, SimulatorFrame frame, int w) {
        int bottom = frame.bottom(), door = SimulatorFrame.BASIS_DOOR;
        skin.vDivider(c, door + 3, SimulatorFrame.DOOR_TOP, bottom);
        BasisPage.draw(c, x, skin.well(c, new Rect(door + 9, 71, w - door - 14, bottom - 71)));
    }

    /** The Sizing tab under its widgets. */
    public static void sizing(Canvas c, Skin skin, SheetContext x, SizingPage.State state, SimulatorFrame frame, int w) {
        int bottom = frame.bottom(), door = SimulatorFrame.SIZING_DOOR;
        skin.vDivider(c, door + 3, SimulatorFrame.DOOR_TOP, bottom);
        SizingPage.draw(c, x, state, skin.well(c, glass(frame, w)));
    }

    /** The Sizing tab's glass, before the skin's edge. */
    public static Rect glass(SimulatorFrame frame, int w) {
        int door = SimulatorFrame.SIZING_DOOR;
        return new Rect(door + 9, 71, w - door - 14, frame.bottom() - 71);
    }

    /** What the status says on a tab, a refusal or a gesture's prompt first; {@code holder} is who edits a sheet this player only reads. */
    public static SheetState.Status status(SheetContext x, SimulatorFrame.Tab tab, ResultsPage.State results, SheetGestures gestures, SheetEditor editor, boolean compact, @Nullable String holder) {
        if (x.differing().isEmpty()) return said(x, tab, results, gestures, editor, compact, holder);
        // The status line does not wrap, so a notice takes the short form of what stands before it.
        var said = said(x, tab, results, gestures, editor, true, holder);
        return new SheetState.Status(said.text() + " " + (compact ? x.text("status.data_differs_short") : x.text("status.data_differs", String.join(", ", x.differing()))), said.tone());
    }

    private static SheetState.Status said(SheetContext x, SimulatorFrame.Tab tab, ResultsPage.State results, SheetGestures gestures, SheetEditor editor, boolean compact, @Nullable String holder) {
        var refusal = editor.refusal();
        if (refusal != null) {
            String why = x.t().get(refusal.key(), refusal.arguments().toArray());
            return new SheetState.Status(x.state().running() ? x.state().status(x.t(), x.units(), compact).text() + " " + why : why, Palette.WARN);
        }
        if (x.state().running()) return x.state().status(x.t(), x.units(), compact);
        var told = x.host().said();
        if (told != null) return new SheetState.Status(x.text(told.key(), told.arguments().toArray()), told.warn() ? Palette.WARN : Palette.ACCENT);
        String lifted = gestures.lifted();
        if (lifted != null) return new SheetState.Status(x.text("status.reconnect", editor.stream(lifted).name()), Palette.ACCENT);
        if (tab == SimulatorFrame.Tab.RESULTS) return new SheetState.Status(ResultsPage.status(x, results), Palette.ACCENT);
        if (tab == SimulatorFrame.Tab.BASIS) return new SheetState.Status(BasisPage.status(x), Palette.ACCENT);
        if (tab == SimulatorFrame.Tab.SIZING) return SizingPage.status(x);
        var status = x.state().status(x.t(), x.units(), compact);
        return holder == null ? status : new SheetState.Status(x.text("status.reader", holder, Door.lower(status.text())), status.tone());
    }

    /** What the mouse stands on says: a port, a part of a column, a tile; nothing while a gesture runs. */
    private static @Nullable String hover(SheetContext x, SimulatorFrame frame, SheetView view, SheetGestures gestures, SheetEditor editor, Rect box, int category, double mouseX, double mouseY) {
        if (gestures.mode() != SheetGestures.Mode.IDLE) return null;
        var tile = frame.tileAt(category, SimulatorFrame.inner(frame.tileWell()), mouseX, mouseY);
        if (tile != null) return tile.lit() ? x.t().get(tile.name()) : x.t().get(tile.name()) + "\n" + x.text("tile.unlit");
        if (!box.contains(mouseX, mouseY)) return null;
        var hit = editor.layout().at(view.designX(mouseX), view.designY(mouseY), view.zoom());
        if (hit != null && hit.kind() == SheetLayout.Kind.PORT) return portHover(x, hit.place());
        if (hit == null || hit.kind() != SheetLayout.Kind.UNIT) return null;
        var part = editor.layout().part(view.designX(mouseX), view.designY(mouseY), view.zoom());
        return part == null ? null : partHover(x, part);
    }

    /** A part's name, the assembly screen's. */
    public static String partHover(SheetContext x, ColumnBlock.Part part) {
        return x.t().get("assembly.part." + part.slot().part(), part.argument());
    }

    /** A port's hover (plan section 3.11): its full name, the stream it holds, what happens if it stays free. */
    public static String portHover(SheetContext x, SheetLayout.Place place) {
        var unit = x.editor().unit(place.unit());
        var lines = new ArrayList<String>();
        // A passage end reads as the role it plays: the one its stream gives it, or both while none does.
        var wiring = x.editor().wiring(null);
        var passage = wiring.passage(unit.id(), place.port());
        boolean free = passage != null && wiring.free(unit.id(), place.port());
        String role = passage == null ? place.port() : free || Boolean.TRUE.equals(wiring.isInlet(unit.id(), place.port())) ? passage.in() : passage.out();
        String name = free ? x.text("port.either", StreamDoors.portName(x, passage.in()), Door.lower(StreamDoors.portName(x, passage.out()))) : StreamDoors.portName(x, role);
        lines.add(x.text("port.hover", unit.name(), name));
        for (var stream : x.editor().sheet().streams()) {
            var ends = passage != null ? new Flowsheet.End[]{stream.from(), stream.to()} : new Flowsheet.End[]{place.direction() == Port.Direction.OUT ? stream.from() : stream.to()};
            for (var end : ends) if (end != null && end.unit().equals(unit.id()) && end.port().equals(place.port())) lines.add(x.text("port.holds", stream.name()));
        }
        var port = port(unit, role);
        if (port != null) lines.add(x.text(left(port)));
        return String.join("\n", lines);
    }

    private static String left(Port port) {
        if (port.direction() == Port.Direction.OUT) return "port.left_open";
        if (port.required()) return "port.left_required";
        boolean nothing = port.id().startsWith("in/") || port.id().startsWith("steam/") || port.id().equals("convection_in");
        return nothing ? "port.left_empty" : "port.left_utility";
    }

    /** The unit's port, or null: a removed type's, or a removed row's. */
    private static @Nullable Port port(Flowsheet.Unit unit, String id) {
        var model = UnitTypes.model(unit.type());
        if (model == null) return null;
        try {
            for (var port : model.ports(unit.params())) if (port.id().equals(id)) return port;
        } catch (FlowsheetFailure noPorts) {
            return null;
        }
        return null;
    }

    /** Draws the page the screen stands on; returns the hover. */
    public static @Nullable String draw(Canvas c, Skin skin, SheetContext x, SimulatorFrame.Tab tab, SimulatorFrame frame, SheetView view, SheetGestures gestures, ResultsPage.State results, SizingPage.State sizing, int category, SheetEditor editor, KitDoor kit, String title, @Nullable String holder, double mouseX, double mouseY) {
        int w = frame.status().w() + 20, h = frame.bottom() + SimulatorFrame.BOTTOM;
        back(c, skin, status(x, tab, results, gestures, editor, frame.size() != SimulatorFrame.Size.WIDE, holder), title, frame, w, h);
        switch (tab) {
            case FLOWSHEET -> {
                var box = flowsheet(c, skin, x, frame, view, gestures, editor, category);
                var selected = gestures.selected();
                if (selected != null) {
                    panel(c, skin, box);
                    kit.place(SelectionColumn.of(x, selected).rows(), x.m());
                    String hover = kit.draw(c, skin, mouseX, mouseY);
                    if (SimulatorFrame.panel(box).contains(mouseX, mouseY)) return hover;
                }
                return hover(x, frame, view, gestures, editor, box, category, mouseX, mouseY);
            }
            case RESULTS -> {
                results(c, skin, x, results, frame, w);
                kit.place(ResultsPage.rows(x, results), x.m());
                return kit.draw(c, skin, mouseX, mouseY);
            }
            case SIZING -> {
                sizing(c, skin, x, sizing, frame, w);
                kit.place(SizingPage.rows(x, sizing), x.m());
                return kit.draw(c, skin, mouseX, mouseY);
            }
            case BASIS -> {
                basis(c, skin, x, frame, w);
                kit.place(BasisPage.rows(x), x.m());
                return kit.draw(c, skin, mouseX, mouseY);
            }
        }
        return null;
    }
}
