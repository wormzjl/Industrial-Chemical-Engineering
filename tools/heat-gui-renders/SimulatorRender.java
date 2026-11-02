import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.flowsheet.BasisPage;
import com.wormzjl.icheme.client.gui.flowsheet.ColumnDoors;
import com.wormzjl.icheme.client.gui.flowsheet.Door;
import com.wormzjl.icheme.client.gui.flowsheet.DoorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.FileColumns;
import com.wormzjl.icheme.client.gui.flowsheet.ResultsPage;
import com.wormzjl.icheme.client.gui.flowsheet.SelectionColumn;
import com.wormzjl.icheme.client.gui.flowsheet.SheetContext;
import com.wormzjl.icheme.client.gui.flowsheet.SheetEditor;
import com.wormzjl.icheme.client.gui.flowsheet.SheetGestures;
import com.wormzjl.icheme.client.gui.flowsheet.SheetLayout;
import com.wormzjl.icheme.client.gui.flowsheet.SheetPainter;
import com.wormzjl.icheme.client.gui.flowsheet.SheetState;
import com.wormzjl.icheme.client.gui.flowsheet.SheetUnits;
import com.wormzjl.icheme.client.gui.flowsheet.SheetView;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorHost;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorPage;
import com.wormzjl.icheme.client.gui.flowsheet.StreamDoors;
import com.wormzjl.icheme.client.gui.flowsheet.StreamProps;
import com.wormzjl.icheme.client.gui.flowsheet.Tiles;
import com.wormzjl.icheme.client.gui.flowsheet.UnitDoors;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The simulator's sheets from shipped code (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 8): the screen's own builders, layout, painter and gesture machine, in the game font,
 * both doors, on the owner's sample with a real {@code FlowsheetEngine} run. Nothing here draws a screen of its own: it places what {@code client.gui.flowsheet} says, and presses what a player would press.
 * Run from the worktree root, after compileJava (JDK 22+ compiles the folder's files together):
 * <pre>java -Dfile.encoding=UTF-8 -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main;build/resources/main;gson.jar tools/heat-gui-renders/SimulatorRender.java
 *     src/main/resources/assets/icheme/lang/en_us.json tmp/w6/sheets [minecraft resources jar]</pre>
 */
public final class SimulatorRender {
    static final int W = SimulatorPrototypes.W, H = SimulatorPrototypes.H, DW = SimulatorPrototypes.DW, DH = SimulatorPrototypes.DH;
    static final List<Skin> SKINS = List.of(PanelSkin.LIGHT, PanelSkin.DARK);
    static final HeatControlColumn.Texts T = (key, a) -> HeatDiagramRender.tr("gui.icheme." + key, a).replace("%%", "%");
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static final String NETWORK = CATALOG.presets().network().packageId();
    static final V3ColumnInput BASE = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);
    static StreamProps.Fluids FLUIDS;
    static final Measure FONT = new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure();

    private static StreamProps.Fluids fluids() {
        var model = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));
        var pack = CATALOG.requirePackage(NETWORK);
        String key = "gui.icheme.flowsheet.basis.pack." + MaterialCatalog.Package.label(pack.id()).replace(':', '.');
        return new StreamProps.Fluids(model, pack, id -> CATALOG.name(id).english(), HeatDiagramRender.tr(key));
    }

    // ---- The sample: the owner's crude unit, as FlowsheetColumnTest solves it, placed as the approved sheet places it ----

    private static final class Sheet {
        final List<Flowsheet.Unit> units = new ArrayList<>();
        final List<Flowsheet.Stream> streams = new ArrayList<>();

        Sheet unit(String id, String type, int x, int y, Params params) {
            units.add(new Flowsheet.Unit(id, type, id, x, y, params));
            return this;
        }

        Sheet unit(String id, String type, int x, int y, String settings) {
            return unit(id, type, x, y, new Params(JsonParser.parseString(settings).getAsJsonObject()));
        }

        Sheet feed(String id, String unit, String port, Flowsheet.Feed feed, int x, int y) {
            streams.add(new Flowsheet.Stream(id, id, null, new Flowsheet.End(unit, port), feed, List.of(new Flowsheet.Point(x, y)), new JsonObject()));
            return this;
        }

        Sheet line(String id, String from, String fromPort, String to, String toPort, Flowsheet.Point... route) {
            streams.add(new Flowsheet.Stream(id, id, new Flowsheet.End(from, fromPort), new Flowsheet.End(to, toPort), null, List.of(route), new JsonObject()));
            return this;
        }

        Sheet product(String id, String from, String port, int x, int y) {
            streams.add(new Flowsheet.Stream(id, id, new Flowsheet.End(from, port), null, null, List.of(new Flowsheet.Point(x, y)), new JsonObject()));
            return this;
        }

        Flowsheet build() {
            var pack = CATALOG.requirePackage(NETWORK);
            return new Flowsheet(new Flowsheet.Basis(NETWORK, pack.revision(), FLUIDS.axis()), units, streams, FlowsheetSolver.Settings.DEFAULT);
        }
    }

    static Flowsheet.Feed crude(double temperature, double pressure) {
        var ids = BASE.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = BASE.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    static Flowsheet.Feed water(double temperature, double pressure, double flow) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Water", flow);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    static Params columnDoor() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER);
        door.json().addProperty("condenser_drop", .3e5);
        for (var row : door.rows("pumparounds")) row.json().addProperty("drop", .3e5);
        return door;
    }

    static Flowsheet sample() {
        var steam = BASE.steamFeeds().getFirst();
        return new Sheet().unit("P-101", "pump", 84, 76, "{\"target\":\"outlet\",\"pressure\":14e5,\"rise\":2e5}").unit("T-101", "column", 438, 24, columnDoor())
                .unit("E-101", "exchanger", 130, 216, "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":473.15,\"duty\":1e6,\"area\":100,\"tube_drop\":0.5e5,\"shell_drop\":0.3e5}")
                .unit("H-101", "fired_heater", 232, 194, "{\"coils\":\"series\",\"target\":\"outlet\",\"temperature\":638.15,\"duty\":1e7,\"limit\":90e6,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}")
                .unit("V-101", "valve", 340, 224, "{\"target\":\"outlet\",\"pressure\":2.72e5,\"drop\":1e5}").unit("E-102", "air_cooler", 70, 266, "{\"target\":\"outlet\",\"temperature\":343.15,\"duty\":1e6,\"drop\":0.2e5}")
                .unit("P-102", "pump", 560, 193, "{\"target\":\"outlet\",\"pressure\":6e5,\"rise\":2e5}")
                .feed("CRUDE", "P-101", "in", crude(298.15, 8e5), 28, 89).line("S-1", "P-101", "out", "T-101", "pa/1/in").line("S-2", "T-101", "pa/1/out", "T-101", "pa/2/in").line("S-3", "T-101", "pa/2/out", "T-101", "pa/3/in")
                .line("S-4", "T-101", "pa/3/out", "E-101", "tube_in", new Flowsheet.Point(392, 212), new Flowsheet.Point(392, 176), new Flowsheet.Point(116, 176), new Flowsheet.Point(116, 230))
                .line("S-5", "E-101", "tube_out", "H-101", "in").line("S-6", "H-101", "out", "V-101", "in").line("S-7", "V-101", "out", "T-101", "feed")
                .feed("STEAM", "T-101", "steam/1", water(steam.temperatureKelvin(), 3.5e5, steam.molarFlowMolPerSecond()), 356, 261)
                .feed("CWS", "T-101", "condenser_in", water(303.15, 4e5, 190000 / 3.6), 353, 41).product("CWR", "T-101", "condenser_out", 353, 65)
                .product("OFFGAS", "T-101", "overhead", 670, 12).product("NAPHTHA", "T-101", "distillate", 670, 41).product("WATER", "T-101", "water", 670, 96).product("KERO", "T-101", "draw/1", 670, 151)
                .line("S-8", "T-101", "draw/2", "P-102", "in").product("DIESEL", "P-102", "out", 670, 206).product("AGO", "T-101", "draw/3", 670, 261)
                .line("S-9", "T-101", "bottoms", "E-101", "shell_in").line("S-10", "E-101", "shell_out", "E-102", "in").product("RESIDUE", "E-102", "out", 28, 274).build();
    }

    /** The doors the crude unit does not hold: the same crude through a drum, its oil split three ways. */
    static Flowsheet vessels() {
        return new Sheet().unit("D-101", "drum", 200, 60, SheetEditor.defaults("drum", () -> null)).unit("TEE-1", "splitter", 340, 140, "{\"mode\":\"shares\",\"outlets\":[{\"id\":\"1\",\"share\":0.4},{\"id\":\"2\",\"share\":0.35},{\"id\":\"3\"}]}")
                .feed("S-6", "D-101", "in", crude(638.15, 5e5), 100, 90).product("S-14", "D-101", "gas", 320, 30).line("DIESEL", "D-101", "oil", "TEE-1", "in").product("S-11", "TEE-1", "out/1", 460, 130).product("S-12", "TEE-1", "out/2", 460, 153).product("S-13", "TEE-1", "out/3", 460, 176).build();
    }

    // ---- A state of the screen ----

    /** A host as W7 and W8 will be: a run in hand or not, a block or not. */
    private record Host(boolean reads, SheetState.Running now) implements SimulatorHost {
        @Override public String holder() {
            return reads ? "Alex" : null;
        }

        @Override public boolean canRun() {
            return true;
        }

        @Override public boolean stores() {
            return true;
        }

        @Override public boolean reader() {
            return reads;
        }

        @Override public SheetState.Running running() {
            return now;
        }
    }

    /** A screen's state: what a player has done to it so far. */
    private static final class Scene {
        final SheetEditor editor;
        final SheetView view = new SheetView();
        final List<SheetGestures.Selection> doors = new ArrayList<>();
        final SheetGestures gestures;
        final SimulatorHost host;
        Flowsheet ran;
        FlowsheetRun run;
        double seconds;
        SimulatorFrame.Tab tab = SimulatorFrame.Tab.FLOWSHEET;
        ResultsPage.State results = new ResultsPage.State();
        int category = 2;
        SheetLayout.Place hover;
        /** A runner's result, which stands instead of the scene's run; a client's data that differ from the server's. */
        SimulatorHost.Finished finished;
        List<String> differing = List.of();

        Scene(Flowsheet sheet, FlowsheetRun run, double seconds, SimulatorHost host) {
            editor = new SheetEditor(sheet, FONT, SimulatorRender::columnDoor);
            host.lock(editor);
            this.ran = run == null ? null : sheet;
            this.run = run;
            this.seconds = seconds;
            this.host = host;
            gestures = new SheetGestures(editor, view, doors::add);
        }

        SheetContext context() {
            var state = finished != null ? SheetState.of(editor.sheet(), finished).running(host.running()) : new SheetState(editor.sheet(), ran, run, seconds, host.running());
            return new SheetContext(editor, state, SheetUnits.DEFAULT, T, FONT, FLUIDS, host, differing);
        }
    }

    private static void keys(GuiPrototypes.Door d, List<SimulatorPage.Slot> slots) {
        for (var slot : slots) {
            var b = slot.button();
            if (b.soft()) d.skin.softKey(d.c, slot.rect(), b.text(), false);
            else d.skin.button(d.c, slot.rect(), b.text(), b.key().equals("run") && b.active() ? Skin.Press.PRIMARY : SimulatorPage.press(b), false);
        }
    }

    /** Rows with their widgets, a wide entry's too. */
    private static Column rows(GuiPrototypes.Door d, List<? extends Column.Row> rows, int x, int y, int w, Map<String, String> values) {
        var column = d.column(rows, x, y, w, values);
        for (var p : column.rows()) if (p.row() instanceof Column.Wide wide) d.skin.field(d.c, p.slots().get(wide.key()), values.getOrDefault(wide.key(), ""), "", false);
        return column;
    }

    /** The hover as the game draws it, left of the mouse where the canvas ends. */
    private static void tooltip(GuiPrototypes.Door d, String text, int x, int y) {
        var lines = text.split("\n");
        int w = 0;
        for (var line : lines) w = Math.max(w, d.m.width(line));
        int left = x - w - 18, top = y - 6, h = lines.length * 11 + 6;
        d.c.fill(left, top, left + w + 10, top + h, 0xF0100010);
        d.c.outline(left, top, w + 10, h, 0xFF5000FF);
        for (int i = 0; i < lines.length; i++) d.c.text(lines[i], left + 5, top + 4 + i * 11, i == 0 ? 0xFFFFFFFF : 0xFFAAAAAA);
    }

    private static void screen(GuiPrototypes.Door d, Scene s) {
        var x = s.context();
        var frame = new SimulatorFrame(d.w, d.h);
        boolean narrow = frame.size() != SimulatorFrame.Size.WIDE;
        SimulatorPage.back(d.c, d.skin, SimulatorPage.status(x, s.tab, s.results, s.gestures, s.editor, narrow, s.host.holder()), T.get("flowsheet.frame.title", "CDU train"), frame, d.w, d.h);
        keys(d, SimulatorPage.frameKeys(x, s.tab, frame, s.host, s.editor));
        d.skin.iconKey(d.c, frame.theme(), Skin.Icon.THEME, false);
        switch (s.tab) {
            case FLOWSHEET -> {
                var box = SimulatorPage.flowsheet(d.c, d.skin, x, frame, s.view, s.gestures, s.editor, s.category);
                keys(d, SimulatorPage.canvasKeys(x, frame, box, s.category));
                var selected = s.gestures.selected();
                if (selected != null) {
                    SimulatorPage.panel(d.c, d.skin, box);
                    var r = SimulatorFrame.panel(box);
                    var panel = SelectionColumn.of(x, selected);
                    rows(d, panel.rows(), r.x() + 7, r.y() + 5, r.w() - 18, panel.values());
                }
                var naming = s.gestures.naming();
                if (naming != null) {
                    int nx = (int) s.view.canvasX(naming.x()), ny = (int) s.view.canvasY(naming.y()) - 2;
                    d.skin.field(d.c, new Rect(nx, ny, 66, 15), naming.text() + "_", "", false);
                }
                if (s.hover != null) tooltip(d, SimulatorPage.portHover(x, s.hover), (int) s.view.canvasX(s.hover.x()), (int) s.view.canvasY(s.hover.y()));
            }
            case RESULTS -> {
                SimulatorPage.results(d.c, d.skin, x, s.results, frame, d.w);
                rows(d, ResultsPage.rows(x, s.results), 10, SimulatorFrame.DOOR_TOP, SimulatorFrame.RESULT_DOOR - 10, Map.of());
            }
            case BASIS -> {
                SimulatorPage.basis(d.c, d.skin, x, frame, d.w);
                rows(d, BasisPage.rows(x), 10, SimulatorFrame.DOOR_TOP, SimulatorFrame.BASIS_DOOR - 10, BasisPage.values(x));
            }
            default -> {
            }
        }
    }

    private static void door(GuiPrototypes.Door d, Door door) {
        var box = DoorFrame.back(d.c, d.skin, door, d.w, d.h);
        d.skin.iconKey(d.c, com.wormzjl.icheme.client.gui.common.CalculatorColumn.theme(d.w), Skin.Icon.THEME, false);
        var tabs = DoorFrame.tabs(door, d.w);
        for (int i = 0; i < door.tabs().size(); i++) d.skin.button(d.c, tabs.get(i), door.tabs().get(i).text(), SimulatorPage.press(door.tabs().get(i)), false);
        var areas = DoorFrame.areas(door, d.w, d.h);
        for (int i = 0; i < areas.size(); i++) rows(d, DoorFrame.columns(door, d.w).get(i), areas.get(i).x(), areas.get(i).y(), areas.get(i).w(), door.values());
        var footer = DoorFrame.footer(door, d.w, d.h, d.m);
        d.skin.button(d.c, footer.getFirst(), T.get("fluid.close"), Skin.Press.REST, false);
        for (int i = 0; i < door.footer().size(); i++) {
            var key = door.footer().get(i);
            if (key.soft()) d.skin.softKey(d.c, footer.get(i + 1), key.text(), false);
            else d.skin.button(d.c, footer.get(i + 1), key.text(), SimulatorPage.press(key), false);
        }
        if (!door.calculator()) {
            var soft = SimulatorPage.softKeys(SheetUnits.DEFAULT, T);
            for (int i = 0; i < 4; i++) d.skin.softKey(d.c, footer.get(footer.size() - 4 + i), soft.get(i).text(), false);
        }
        for (var slot : door.glass().slots(box)) {
            if (slot.button()) d.skin.button(d.c, slot.rect(), slot.text(), slot.latched() ? Skin.Press.CURRENT : Skin.Press.REST, false);
            else d.skin.field(d.c, slot.rect(), slot.text(), slot.hint() == null ? "" : slot.hint(), false);
        }
    }

    // ---- The sheets ----

    private static BufferedImage page(int w, int h, Skin skin, BiConsumer<GuiPrototypes.Door, Skin> body) {
        return GuiPrototypes.render(new GuiPrototypes.Prototype("x", w, h, body), skin, false);
    }

    /** The pages one under another, a file for each door. */
    private static void sheet(Path out, String file, int w, int h, List<String> titles, List<BiConsumer<GuiPrototypes.Door, Skin>> bodies) throws Exception {
        for (var skin : SKINS) {
            var pages = new ArrayList<BufferedImage>();
            var names = new ArrayList<String>();
            for (int i = 0; i < bodies.size(); i++) {
                pages.add(page(w, h, skin, bodies.get(i)));
                names.add(titles.get(i) + (skin == PanelSkin.LIGHT ? " (light door)" : " (dark door)"));
            }
            SimulatorPrototypes.stack(out.resolve(file + (skin == PanelSkin.LIGHT ? "" : "-dark") + ".png"), names, pages, List.of());
        }
    }

    private static void grid(Path out, String file, int w, int h, List<String> titles, List<BiConsumer<GuiPrototypes.Door, Skin>> bodies) throws Exception {
        for (var skin : SKINS) {
            var pages = new ArrayList<BufferedImage>();
            for (var body : bodies) pages.add(page(w, h, skin, body));
            SimulatorPrototypes.grid(out, file + (skin == PanelSkin.LIGHT ? "" : "-dark"), titles, pages);
        }
    }

    private static Consumer<Scene> nothing() {
        return s -> {
        };
    }

    /** A page of a fresh screen on {@code sheet}, after what {@code player} does. */
    private static BiConsumer<GuiPrototypes.Door, Skin> scene(Flowsheet sheet, FlowsheetRun run, double seconds, SimulatorHost host, Consumer<Scene> player) {
        return (d, skin) -> {
            var s = new Scene(sheet, run, seconds, host);
            var frame = new SimulatorFrame(d.w, d.h);
            s.view.layout(SimulatorFrame.inner(frame.well()));
            if (frame.size() != SimulatorFrame.Size.WIDE) s.view.fit(s.editor.layout().bounds());
            player.accept(s);
            screen(d, s);
        };
    }

    private static double cx(Scene s, double x) {
        return s.view.canvasX(x);
    }

    private static double cy(Scene s, double y) {
        return s.view.canvasY(y);
    }

    private static Door unitDoor(Scene s, String id, int tab) {
        var view = new Door.View();
        view.tab = tab;
        var unit = s.editor.unit(id);
        if (unit.type().equals("column") && tab == 0) {
            // A click on the drawing's D1 opens its row.
            view.connection = com.wormzjl.icheme.client.gui.common.CalculatorColumn.Connection.DRAW;
            view.index = 0;
            view.tray = 11;
        }
        return unit.type().equals("column") ? ColumnDoors.door(s.context(), id, view) : UnitDoors.door(s.context(), id, view);
    }

    private static BiConsumer<GuiPrototypes.Door, Skin> unit(Flowsheet sheet, FlowsheetRun run, String id, int tab) {
        return (d, skin) -> door(d, unitDoor(new Scene(sheet, run, 3.6, new Host(false, null)), id, tab));
    }

    private static BiConsumer<GuiPrototypes.Door, Skin> stream(Flowsheet sheet, FlowsheetRun run, String id, int tab) {
        return (d, skin) -> {
            var view = new Door.View();
            view.tab = tab;
            door(d, StreamDoors.door(new Scene(sheet, run, 3.6, new Host(false, null)).context(), id, view));
        };
    }

    private static BiConsumer<GuiPrototypes.Door, Skin> columnResults(Flowsheet sheet, FlowsheetRun run) {
        return (d, skin) -> {
            var view = new Door.View();
            view.tab = 2;
            door(d, ColumnDoors.door(new Scene(sheet, run, 3.6, new Host(false, null)).context(), "T-101", view));
        };
    }

    /** A sheet of the column alone, with no feed. */
    private static Flowsheet columnOnly(Flowsheet sheet) {
        return new Flowsheet(sheet.basis(), sheet.units().stream().filter(u -> u.id().equals("T-101")).toList(), List.of(), sheet.settings());
    }

    /** A column's door with its hover where a key is off, as the game draws it. */
    private static BiConsumer<GuiPrototypes.Door, Skin> doorKeys(Flowsheet sheet, FlowsheetRun run, SimulatorHost host, String id, boolean hover) {
        return (d, skin) -> {
            var built = ColumnDoors.door(new Scene(sheet, run, 3.6, host).context(), id, new Door.View());
            door(d, built);
            var solve = built.footer().getFirst();
            var at = DoorFrame.footer(built, d.w, d.h, d.m).get(1);
            if (hover && !solve.active() && solve.hint() != null) tooltip(d, solve.hint(), at.x() + 280, at.y() - 24);
        };
    }

    private static void list(GuiPrototypes.Door d, FileColumns.Listing l) {
        FileColumns.draw(d.c, d.skin, l, d.w, d.h);
        var slots = com.wormzjl.icheme.client.gui.common.DialogColumns.footer(d.w, d.h, l.keys().length);
        for (int i = 0; i < slots.size(); i++) d.skin.button(d.c, slots.get(i), l.keys()[i], i == l.primary() ? Skin.Press.PRIMARY : Skin.Press.REST, false);
    }

    /** One block as dropped on the canvas, its ports named at the end of a stub. */
    private static void block(GuiPrototypes.Door d, Rect box, String title, List<String> notes, String tag, String type, Params params, int x, int y) {
        var c = d.c;
        c.text(title, box.x() + 5, box.y() + 5, Palette.TEXT);
        for (int i = 0; i < notes.size(); i++) c.text(notes.get(i), box.x() + 5, box.y() + 18 + i * 10, Palette.MUTED);
        var sheet = new Sheet().unit(tag, type, x, y, params).build();
        var layout = SheetLayout.of(sheet, FONT);
        var state = new SheetState(sheet, null, null, 0, null);
        var view = new SheetView();
        view.layout(box);
        c.clip(box);
        SheetPainter.draw(c, layout, state, view, null, null, T);
        var p = new FlowsheetPainter(c, box.x(), box.y(), 1, 1);
        var scene = new Scene(sheet, null, 0, SimulatorHost.NONE);
        for (var b : layout.boxes())
            for (var place : b.places()) {
                int dx = place.side().dx * 12, dy = place.side().dy * (place.side().dy < 0 ? 24 : 12);
                p.wire(new int[]{place.x(), place.y(), place.x() + dx, place.y() + dy}, false, false, Palette.MUTED);
                p.square(place.x(), place.y(), SheetLayout.PORT, place.rgb());
                String name = StreamDoors.portName(scene.context(), place.port());
                if (dy == 0) p.label(name, place.x() + dx + (dx < 0 ? -3 : 3), place.y(), dx < 0 ? 1f : 0f, Palette.MUTED);
                else p.label(name, place.x(), place.y() + dy + (dy < 0 ? -6 : 7), place.x() < b.rect().x() + b.rect().w() / 2 - 4 ? 1f : place.x() > b.rect().x() + b.rect().w() / 2 + 4 ? 0f : .5f, Palette.MUTED);
            }
        c.unclip();
    }

    private static Params edited(Params params, Consumer<JsonObject> change) {
        var json = params.json().deepCopy();
        change.accept(json);
        return new Params(json);
    }

    private static void blockPorts(GuiPrototypes.Door d) {
        int gap = 4, n = 4, w = (d.w - 10 - (n - 1) * gap) / n;
        var full = columnDoor();
        var plain = edited(ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER), j -> {
            j.add("draws", new com.google.gson.JsonArray());
            j.add("steam", new com.google.gson.JsonArray());
            j.add("pumparounds", new com.google.gson.JsonArray());
        });
        var kettle = edited(plain, j -> {
            j.addProperty("condenser", ColumnComponents.Condenser.EXCHANGER.name());
            j.addProperty("reboiler_duty", 18e6);
        });
        var vacuum = edited(full, j -> {
            j.getAsJsonArray("pumparounds").remove(2);
            j.getAsJsonArray("draws").remove(2);
        });
        block(d, d.well(new Rect(5, 5, w, d.h - 10)), "Air cooler, no reboiler", List.of("Condenser: Air cooler", "Rows: none"), "T-101", "column", plain, w / 2 - 22, 92);
        block(d, d.well(new Rect(5 + w + gap, 5, w, d.h - 10)), "Exchanger and kettle", List.of("Condenser: Exchanger", "Reboiler duty: 18.00 MW"), "T-101", "column", kettle, w / 2 - 22, 92);
        block(d, d.well(new Rect(5 + 2 * (w + gap), 5, w, d.h - 10)), "The sample's T-101", List.of("Pumparounds 3, steam 1, draws 3"), "T-101", "column", full, w / 2 - 22, 92);
        block(d, d.well(new Rect(5 + 3 * (w + gap), 5, w, d.h - 10)), "Two rows fewer", List.of("Pumparounds 2, steam 1, draws 2"), "T-101", "column", vacuum, w / 2 - 22, 92);
    }

    private static void otherBlocks(GuiPrototypes.Door d) {
        int gap = 4, w = (d.w - 10 - gap) / 2, h = (d.h - 10 - gap) / 2;
        var defaults = (java.util.function.Function<String, Params>) type -> SheetEditor.defaults(type, () -> null);
        block(d, d.well(new Rect(5, 5, w, h)), "Heat exchanger", List.of("Bypass and ratio lines stay inside the block."), "E-101", "exchanger", defaults.apply("exchanger"), w / 2 - 32, 90);
        block(d, d.well(new Rect(5 + w + gap, 5, w, h)), "Kettle", List.of("Gas and liquid outlets, a tube side for the heat."), "E-102", "kettle", defaults.apply("kettle"), w / 2 - 35, 90);
        block(d, d.well(new Rect(5, 5 + h + gap, w, h)), "Fired heater, coils in series", List.of("One pair: the sample's H-101."), "H-101", "fired_heater", defaults.apply("fired_heater"), w / 2 - 29, 56);
        block(d, d.well(new Rect(5 + w + gap, 5 + h + gap, w, h)), "Fired heater, coils apart", List.of("Two pairs."), "H-102", "fired_heater", edited(defaults.apply("fired_heater"), j -> j.addProperty("coils", "apart")), w / 2 - 29, 56);
    }

    private static void opened(Path out) throws Exception {
        var folder = Path.of("tmp/w6/flowsheets");
        Files.createDirectories(folder);
        var sheet = sample();
        FlowsheetFile.save(folder.resolve("CDU train.json"), sheet);
        var removed = withType(sheet, "H-101", "old_heater");
        FlowsheetFile.save(folder.resolve("Gas plant inlet.json"), removed);
        Files.writeString(folder.resolve("Broken.json"), "{ not a flowsheet");
        var entries = FileColumns.entries(folder, T);
        var pages = new ArrayList<BufferedImage>();
        for (var skin : SKINS) {
            int ready = 0;
            while (!entries.get(ready).name().equals("CDU train")) ready++;
            int chosen = ready;
            pages.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> list(d, FileColumns.open(entries, chosen, T))));
            pages.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> {
                FileColumns.drawChanges(d.c, d.skin, T.get("flowsheet.file.changes_title", "Gas plant inlet"), FileColumns.changes(removed, T), T.get("flowsheet.file.changes_foot"), d.w, d.h);
                var slots = com.wormzjl.icheme.client.gui.common.DialogColumns.footer(d.w, d.h, 2);
                d.skin.button(d.c, slots.get(0), T.get("flowsheet.file.cancel"), Skin.Press.REST, false);
                d.skin.button(d.c, slots.get(1), T.get("flowsheet.file.open"), Skin.Press.PRIMARY, false);
            }));
        }
        SimulatorPrototypes.grid(out, "17-file-and-repair", List.of("Open: the player's own files (light door)", "After an update: what changed (light door)", "Open (dark door)", "After an update (dark door)"), pages);
    }

    private static Flowsheet withType(Flowsheet sheet, String id, String type) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) units.add(unit.id().equals(id) ? new Flowsheet.Unit(unit.id(), type, unit.name(), unit.x(), unit.y(), unit.params(), unit.unknown()) : unit);
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), sheet.unknown());
    }

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        FLUIDS = fluids();
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        var sheet = sample();
        long start = System.nanoTime();
        var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("sample: %s, %d streams, %d loops, %.1f s, notes %s%n", run.status(), run.streams().size(), run.loops().size(), seconds, com.wormzjl.icheme.client.gui.flowsheet.NoteText.all(run));
        var more = vessels();
        var moreRun = FlowsheetEngine.run(more, CATALOG, () -> {
        });
        System.out.printf("vessels: %s, log %s%n", moreRun.status(), moreRun.log());
        var missing = withType(sheet, "H-101", "old_heater");
        var missingRun = FlowsheetEngine.run(missing, CATALOG, () -> {
        });
        System.out.printf("missing: %s, log %s%n", missingRun.status(), missingRun.log());
        var host = new Host(false, null);

        sheet(out, "01-main-screen", W, H, List.of("The main screen with nothing selected"), List.of(scene(sheet, run, seconds, host, nothing())));
        sheet(out, "02-selection-panel", W, H, List.of("A unit selected", "A feed selected", "A stream a unit makes selected"), List.of(
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(true, "E-101"))),
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(false, "CRUDE"))),
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(false, "S-5")))));
        sheet(out, "03-interaction", W, H, List.of("A tile dragged from the palette", "A stream drawn from a free port: what can take it is ringed", "A name typed in place", "Right click S-8: its end follows the cursor", "A port's hover"), List.of(
                scene(sheet, run, seconds, host, s -> {
                    s.gestures.carry(Tiles.tile("drum"));
                    s.gestures.move(cx(s, 138), cy(s, 140));
                }), scene(sheet, run, seconds, host, s -> {
                    s.editor.removeStream("KERO");
                    s.editor.addUnit("pump", 610, 165);
                    var port = s.editor.layout().place("T-101", "draw/1");
                    s.gestures.press(cx(s, port.x()), cy(s, port.y()), 0, 1000);
                    s.gestures.drag(cx(s, 596), cy(s, 179));
                }), scene(sheet, run, seconds, host, s -> {
                    var tag = s.editor.layout().name(s.editor.layout().line("S-9"));
                    s.gestures.press(cx(s, tag.x() + 2), cy(s, tag.y() + 2), 0, 1000);
                    s.gestures.release(cx(s, tag.x() + 2), cy(s, tag.y() + 2), 0);
                    s.gestures.press(cx(s, tag.x() + 2), cy(s, tag.y() + 2), 0, 1100);
                    s.gestures.typed("RES-HOT");
                }), scene(sheet, run, seconds, host, s -> {
                    s.editor.addUnit("pump", 610, 165);
                    var p = s.editor.layout().line("S-8").points();
                    s.gestures.press(cx(s, p[p.length - 2] - 8), cy(s, p[p.length - 1]), 1, 1000);
                    s.gestures.move(cx(s, 596), cy(s, 179));
                }),
                scene(sheet, run, seconds, host, s -> {
                    for (var place : s.editor.layout().box("T-101").places()) if (place.port().startsWith("pa/") && place.direction() == com.wormzjl.icheme.science.flowsheet.Port.Direction.IN && s.hover == null) s.hover = place;
                })));
        var small = new ArrayList<BufferedImage>();
        small.add(page(624, 344, PanelSkin.LIGHT, scene(sheet, run, seconds, host, nothing())));
        small.add(page(464, 254, PanelSkin.DARK, scene(sheet, run, seconds, host, nothing())));
        SimulatorPrototypes.stack(out.resolve("05-small-windows.png"), List.of("1280 by 720 window, scale 2: 624 by 344", "The design minimum, 464 by 254"), small, List.of());
        var running = new Host(false, new SheetState.Running(3.1, 2, 30, "T-101", com.wormzjl.icheme.science.flowsheet.Report.Start.LAST));
        sheet(out, "06-run-in-progress", W, H, List.of("A run in progress", "An edit refused during the run", "A second player, read only"), List.of(
                scene(sheet, run, seconds, running, nothing()), scene(sheet, run, seconds, running, s -> s.editor.moveUnit("P-101", 92, 84, null)), scene(sheet, run, seconds, new Host(true, null), nothing())));
        sheet(out, "07-feed-stream", DW, DH, List.of("A feed stream's door"), List.of(stream(sheet, run, "CRUDE", 0)));
        sheet(out, "08-feed-composition", DW, DH, List.of("A feed stream's Composition tab"), List.of(stream(sheet, run, "CRUDE", 1)));
        sheet(out, "10-product-stream", DW, DH, List.of("A product stream's door"), List.of(stream(sheet, run, "NAPHTHA", 0)));
        grid(out, "11-unit-doors", DW, DH, List.of("Heat exchanger", "Fired heater", "Pump", "Drum", "Control valve", "Splitter"), List.of(unit(sheet, run, "E-101", 0), unit(sheet, run, "H-101", 0), unit(sheet, run, "P-101", 0), unit(more, moreRun, "D-101", 0), unit(sheet, run, "V-101", 0), unit(more, moreRun, "TEE-1", 0)));
        grid(out, "12-column-door", DW, DH, List.of("The Column page, D1 open", "The Feed page: read from S-7", "The Results page, T-101's real profile", "The Equipment page"), List.of(unit(sheet, run, "T-101", 0), unit(sheet, run, "T-101", 1), columnResults(sheet, run), unit(sheet, run, "T-101", 3)));
        var ticks = List.of("CRUDE", "S-7", "OFFGAS", "NAPHTHA", "KERO", "RESIDUE");
        var bundled = new ArrayList<FileColumns.ColumnTemplate>();
        try (var index = Files.newBufferedReader(Path.of("src/main/resources/assets/icheme/columnpreset/index.json"))) {
            for (var file : com.google.gson.JsonParser.parseReader(index).getAsJsonArray())
                try (var in = Files.newBufferedReader(Path.of("src/main/resources/assets/icheme/columnpreset/" + file.getAsString()))) {
                    var o = com.google.gson.JsonParser.parseReader(in).getAsJsonObject();
                    bundled.add(new FileColumns.ColumnTemplate(o.get("name").getAsString(), com.wormzjl.icheme.science.column.v3.V3ColumnTemplate.read(o), true));
                }
        }
        var presets = com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog.resolve(CATALOG);
        var templates = new ArrayList<BufferedImage>();
        for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK)) {
            templates.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> list(d, FileColumns.columnTemplates(bundled, 0, SheetUnits.DEFAULT, T))));
            templates.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> list(d, SimulatorPage.listing("TEMPLATES", new Scene(sheet, run, seconds, host).context(), List.of(), presets, List.of(), 0))));
        }
        SimulatorPrototypes.stack(out.resolve("13-templates.png"), List.of("Column templates, a column's door", "Stream templates, a feed's door"), templates.subList(0, 2), List.of());
        sheet(out, "14-results-streams", W, H, List.of("The Results tab"), List.of(scene(sheet, run, seconds, host, s -> {
            s.tab = SimulatorFrame.Tab.RESULTS;
            s.results.ticked.addAll(ticks);
        })));
        var units = scene(sheet, run, seconds, host, s -> {
            s.tab = SimulatorFrame.Tab.RESULTS;
            s.results.view = ResultsPage.View.UNITS;
        });
        var log = scene(sheet, run, seconds, host, s -> {
            s.tab = SimulatorFrame.Tab.RESULTS;
            s.results.view = ResultsPage.View.LOG;
        });
        sheet(out, "15-results-units-log", W, H, List.of("The Units view", "The Log view"), List.of(units, log));
        sheet(out, "16-basis", W, H, List.of("The Basis tab"), List.of(scene(sheet, run, seconds, host, s -> s.tab = SimulatorFrame.Tab.BASIS)));
        opened(out);
        // Solve alone on a real runner: the door's reflux edited, then the column on what it had.
        var runner = new com.wormzjl.icheme.client.gui.flowsheet.SimulatorRunner(() -> CATALOG);
        runner.run(sheet);
        while (runner.running() != null) Thread.sleep(50);
        var alone = new Scene(sheet, null, 0, host);
        var door = alone.editor.unit("T-101").params().json().deepCopy();
        door.addProperty("reflux", door.get("reflux").getAsDouble() * 1.05);
        alone.editor.setParams("T-101", new com.wormzjl.icheme.science.flowsheet.Params(door), null);
        var edited = alone.editor.sheet();
        var ranWhole = runner.last();
        runner.alone(edited, "T-101");
        while (runner.running() != null) Thread.sleep(50);
        var solved = runner.last();
        System.out.printf("alone: %s, %s%n", solved.alone().status(), solved.alone().why());
        Consumer<Scene> fresh = s -> {
            s.editor.setParams("T-101", new com.wormzjl.icheme.science.flowsheet.Params(door), null);
            s.finished = ranWhole;
        };
        Consumer<Scene> settled = s -> {
            s.editor.setParams("T-101", new com.wormzjl.icheme.science.flowsheet.Params(door), null);
            s.finished = solved;
        };
        sheet(out, "22-solve-alone", W, H, List.of("T-101's reflux edited: the loop greys, the column's own feed with it", "Solve alone: T-101 solved on what it had, and what lies after it waits for a Run"),
                List.of(scene(sheet, null, 0, host, fresh), scene(sheet, null, 0, host, settled)));
        grid(out, "22-column-door-keys", DW, DH, List.of("Solve alone and Templates live", "No stream yet: the hover says why", "During a run"), List.of(doorKeys(sheet, run, host, "T-101", false), doorKeys(columnOnly(sheet), null, host, "T-101", true), doorKeys(sheet, run, running, "T-101", false)));
        sheet(out, "23-revision-notice", W, H, List.of("A client on a dedicated server whose data differ: a notice, and nothing blocked"), List.of(scene(sheet, run, seconds, host, s -> s.differing = List.of(com.wormzjl.icheme.science.material.MaterialCatalog.Package.label(NETWORK)))));
        sheet(out, "18-missing-unit", W, H, List.of("A sheet after an update removed a unit"), List.of(scene(missing, missingRun, 1.2, host, s -> s.gestures.select(new SheetGestures.Selection(true, "H-101")))));
        sheet(out, "19-block-ports", W, 442, List.of("One block: the door's settings add the connection points"), List.of((d, s) -> blockPorts(d)));
        sheet(out, "21-other-blocks", W, 402, List.of("The same rule for every block"), List.of((d, s) -> otherBlocks(d)));
        System.out.println("sheets in " + out);
    }
}
