import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
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
import com.wormzjl.icheme.client.gui.flowsheet.ColumnBlock;
import com.wormzjl.icheme.client.gui.flowsheet.ColumnDoors;
import com.wormzjl.icheme.client.gui.flowsheet.Door;
import com.wormzjl.icheme.client.gui.flowsheet.DoorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.FileColumns;
import com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamples;
import com.wormzjl.icheme.client.gui.flowsheet.ResultsPage;
import com.wormzjl.icheme.client.gui.flowsheet.SelectionColumn;
import com.wormzjl.icheme.client.gui.flowsheet.SheetClipboard;
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
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorRunner;
import com.wormzjl.icheme.client.gui.flowsheet.SizingPage;
import com.wormzjl.icheme.client.gui.flowsheet.StreamDoors;
import com.wormzjl.icheme.client.gui.flowsheet.StreamProps;
import com.wormzjl.icheme.client.gui.flowsheet.Tiles;
import com.wormzjl.icheme.client.gui.flowsheet.UnitDoors;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
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
    /** The display units the sheets show: the default's, with the flow's basis from {@code -Dbasis=MOLE|MASS|VOLUME}. */
    static final SheetUnits UNITS = new SheetUnits(SheetUnits.DEFAULT.temperature(), SheetUnits.DEFAULT.pressure(), SheetUnits.DEFAULT.time(), SheetUnits.Basis.valueOf(System.getProperty("basis", "MOLE")));
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

    static Params columnDoor() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER);
        door.json().addProperty("condenser_drop", .3e5);
        for (var row : door.rows("pumparounds")) row.json().addProperty("drop", .3e5);
        return door;
    }

    /** The bundled crude unit example (FlowsheetExamples), as a player opens it from Open. */
    static Flowsheet sample() {
        var example = FlowsheetExamples.read(ClassLoader::getSystemResourceAsStream).stream().filter(e -> e.stem().equals("crude_unit")).findFirst().orElseThrow();
        if (example.sheet() == null) throw new IllegalStateException("crude_unit does not read: " + example.refused());
        return example.sheet();
    }

    /** The bundled examples, for Open's rows. */
    static List<FlowsheetExamples.Example> examples() {
        return FlowsheetExamples.read(ClassLoader::getSystemResourceAsStream);
    }

    /** The doors the crude unit does not hold: the same crude through a drum, its oil split three ways. */
    static Flowsheet vessels() {
        return new Sheet().unit("D-101", "drum", 200, 60, SheetEditor.defaults("drum", () -> null)).unit("TEE-1", "splitter", 340, 140, "{\"mode\":\"shares\",\"outlets\":[{\"id\":\"1\",\"share\":0.4},{\"id\":\"2\",\"share\":0.35},{\"id\":\"3\"}]}")
                .feed("S-6", "D-101", "in", crude(638.15, 5e5), 100, 90).product("S-14", "D-101", "gas", 320, 30).line("DIESEL", "D-101", "oil", "TEE-1", "in").product("S-11", "TEE-1", "out/1", 460, 130).product("S-12", "TEE-1", "out/2", 460, 153).product("S-13", "TEE-1", "out/3", 460, 176).build();
    }

    // ---- A state of the screen ----

    /** A host on a block: a run in hand or not, the player editing or reading, what the block said last. */
    private record Host(boolean reads, SheetState.Running now, SimulatorHost.Said spoken) implements SimulatorHost {
        Host(boolean reads, SheetState.Running now) {
            this(reads, now, null);
        }

        @Override public SimulatorHost.Said said() {
            return spoken;
        }

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
        SizingPage.State sizing = new SizingPage.State();
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
            return new SheetContext(editor, state, UNITS, T, FONT, FLUIDS, host, differing);
        }
    }

    private static void keys(GuiPrototypes.Door d, List<SimulatorPage.Slot> slots) {
        for (var slot : slots) {
            var b = slot.button();
            if (b.soft()) d.skin.softKey(d.c, slot.rect(), b.text(), false);
            else d.skin.button(d.c, slot.rect(), b.text(), (b.key().equals("run") || b.key().equals("size_all")) && b.active() ? Skin.Press.PRIMARY : SimulatorPage.press(b), false);
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
        SimulatorPage.back(d.c, d.skin, SimulatorPage.status(x, s.tab, s.results, s.gestures, s.editor, narrow, s.host.holder()), HeatDiagramRender.tr("block.icheme.process_simulator"), frame, d.w, d.h);
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
            case SIZING -> {
                SimulatorPage.sizing(d.c, d.skin, x, s.sizing, frame, d.w);
                rows(d, SizingPage.rows(x, s.sizing), 10, SimulatorFrame.DOOR_TOP, SimulatorFrame.SIZING_DOOR - 10, SizingPage.values(x, s.sizing));
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
        for (int i = 0; i < areas.size(); i++) {
            d.c.clip(areas.get(i));
            rows(d, DoorFrame.columns(door, d.w).get(i), areas.get(i).x(), areas.get(i).y(), areas.get(i).w(), door.values());
            d.c.unclip();
        }
        var footer = DoorFrame.footer(door, d.w, d.h, d.m);
        d.skin.button(d.c, footer.getFirst(), T.get("fluid.close"), Skin.Press.REST, false);
        for (int i = 0; i < door.footer().size(); i++) {
            var key = door.footer().get(i);
            if (key.soft()) d.skin.softKey(d.c, footer.get(i + 1), key.text(), false);
            else d.skin.button(d.c, footer.get(i + 1), key.text(), SimulatorPage.press(key), false);
        }
        if (!door.calculator()) {
            var soft = SimulatorPage.softKeys(UNITS, T);
            for (int i = 0; i < 4; i++) d.skin.softKey(d.c, footer.get(footer.size() - 4 + i), soft.get(i).text(), false);
        }
        for (var slot : door.glass().slots(box)) {
            if (slot.button()) d.skin.button(d.c, slot.rect(), slot.text(), slot.latched() ? Skin.Press.CURRENT : Skin.Press.REST, false);
            else d.skin.field(d.c, slot.rect(), slot.text(), slot.hint() == null ? "" : slot.hint(), false);
        }
    }

    // ---- The sheets ----

    private static BufferedImage page(int w, int h, Skin skin, BiConsumer<GuiPrototypes.Door, Skin> body) {
        return page("page", w, h, skin, body);
    }

    /** A page named {@code name} in the draw-call count ({@code -Dcount}). */
    private static BufferedImage page(String name, int w, int h, Skin skin, BiConsumer<GuiPrototypes.Door, Skin> body) {
        return GuiPrototypes.render(new GuiPrototypes.Prototype(name, w, h, body), skin, false);
    }

    /** The pages one under another, a file for each door. */
    private static void sheet(Path out, String file, int w, int h, List<String> titles, List<BiConsumer<GuiPrototypes.Door, Skin>> bodies) throws Exception {
        for (var skin : SKINS) {
            var pages = new ArrayList<BufferedImage>();
            var names = new ArrayList<String>();
            for (int i = 0; i < bodies.size(); i++) {
                pages.add(page(file + " | " + titles.get(i), w, h, skin, bodies.get(i)));
                names.add(titles.get(i) + (skin == PanelSkin.LIGHT ? " (light door)" : " (dark door)"));
            }
            SimulatorPrototypes.stack(out.resolve(file + (skin == PanelSkin.LIGHT ? "" : "-dark") + ".png"), names, pages, List.of());
        }
    }

    private static void grid(Path out, String file, int w, int h, List<String> titles, List<BiConsumer<GuiPrototypes.Door, Skin>> bodies) throws Exception {
        for (var skin : SKINS) {
            var pages = new ArrayList<BufferedImage>();
            for (int i = 0; i < bodies.size(); i++) pages.add(page(file + " | " + (i < titles.size() ? titles.get(i) : "#" + i), w, h, skin, bodies.get(i)));
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
            s.view.fit(s.editor.layout().bounds());
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

    /** A feed's Composition tab after Paste with T and P from what the clipboard holds: the door says what it placed and what it could not. */
    private static BiConsumer<GuiPrototypes.Door, Skin> pasted(Flowsheet sheet, FlowsheetRun run, String id, SheetClipboard.Held held, boolean paste) {
        return (d, skin) -> {
            SheetClipboard.copy(held);
            var view = new Door.View();
            view.tab = 1;
            var x = new Scene(sheet, run, 3.6, new Host(false, null)).context();
            if (paste) StreamDoors.door(x, id, view).actions().button("paste_state");
            door(d, StreamDoors.door(x, id, view));
            SheetClipboard.copy(null);
        };
    }

    /** A dialog that asks for a name, with the keys the screen gives it. */
    private static void naming(GuiPrototypes.Door d, String title, String typed, String... keys) {
        d.skin.title(d.c, 10, 10, title);
        d.skin.field(d.c, new Rect(12, 40, d.w - 24, 18), typed, T.get("flowsheet.file.name"), false);
        var slots = com.wormzjl.icheme.client.gui.common.DialogColumns.footer(d.w, d.h, keys.length);
        for (int i = 0; i < keys.length; i++) d.skin.button(d.c, slots.get(i), keys[i], i == keys.length - 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
    }

    private static void unsavedPrompt(GuiPrototypes.Door d) {
        FileColumns.drawChanges(d.c, d.skin, T.get("flowsheet.file.unsaved_title"), FileColumns.unsaved(T), T.get("flowsheet.file.unsaved_foot"), d.w, d.h);
        var slots = com.wormzjl.icheme.client.gui.common.DialogColumns.footer(d.w, d.h, 3);
        d.skin.button(d.c, slots.get(0), T.get("flowsheet.file.cancel"), Skin.Press.REST, false);
        d.skin.button(d.c, slots.get(1), T.get("flowsheet.file.discard"), Skin.Press.REST, false);
        d.skin.button(d.c, slots.get(2), T.get("flowsheet.file.save"), Skin.Press.PRIMARY, false);
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
        for (int i = 0; i < slots.size(); i++) d.skin.button(d.c, slots.get(i), l.keys()[i], l.off()[i] != null ? Skin.Press.OFF : i == l.primary() ? Skin.Press.PRIMARY : Skin.Press.REST, false);
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
        int x = (w - ColumnBlock.WIDTH) / 2, y = 76;
        block(d, d.well(new Rect(5, 5, w, d.h - 10)), "Air cooler, no reboiler", List.of("Condenser: Air cooler", "Rows: none"), "T-101", "column", plain, x, y);
        block(d, d.well(new Rect(5 + w + gap, 5, w, d.h - 10)), "Exchanger and kettle", List.of("Condenser: Exchanger", "Reboiler duty: 18.00 MW"), "T-101", "column", kettle, x, y);
        block(d, d.well(new Rect(5 + 2 * (w + gap), 5, w, d.h - 10)), "The sample's T-101", List.of("Pumparounds 3, steam 1, draws 3"), "T-101", "column", full, x, y);
        block(d, d.well(new Rect(5 + 3 * (w + gap), 5, w, d.h - 10)), "Two rows fewer", List.of("Pumparounds 2, steam 1, draws 2"), "T-101", "column", vacuum, x, y);
    }

    private static com.google.gson.JsonArray pumparounds(int... trays) {
        var rows = new com.google.gson.JsonArray();
        for (int i = 0; i + 1 < trays.length; i += 2) {
            var row = new JsonObject();
            row.addProperty("id", Integer.toString(i / 2 + 1));
            row.addProperty("return_tray", trays[i]);
            row.addProperty("draw_tray", trays[i + 1]);
            row.addProperty("duty", 1e7);
            rows.add(row);
        }
        return rows;
    }

    /** What the sample does not show: the door's other settings, each on the block's own drawing. No stream is on any of them, so every pair here is free. */
    private static void blockVariants(GuiPrototypes.Door d) {
        int gap = 4, n = 4, w = (d.w - 10 - (n - 1) * gap) / n, h = (d.h - 10 - gap) / 2, x = (w - ColumnBlock.WIDTH) / 2, y = 76;
        var full = columnDoor();
        var air = edited(full, j -> j.addProperty("condenser", ColumnComponents.Condenser.AIR_COOLER.name()));
        var kettle = edited(full, j -> j.addProperty("reboiler_duty", 18e6));
        var vacuum = edited(kettle, j -> j.addProperty("top_pressure", .3e5));
        var inline = edited(full, j -> {
            j.addProperty("reflux_pump", ColumnComponents.Pump.IN_LINE.name());
            for (var row : j.getAsJsonArray("pumparounds")) row.getAsJsonObject().addProperty("pump", ColumnComponents.Pump.IN_LINE.name());
        });
        var near = edited(full, j -> j.add("pumparounds", pumparounds(8, 10, 9, 11, 10, 12)));
        var nested = edited(full, j -> j.add("pumparounds", pumparounds(4, 24, 8, 12, 15, 20, 26, 28)));
        var rows = edited(vacuum, j -> {
            j.addProperty("condenser", ColumnComponents.Condenser.AIR_COOLER.name());
            j.add("pumparounds", pumparounds(3, 5, 4, 6, 5, 7, 6, 8));
            var steam = j.getAsJsonArray("steam").get(0).getAsJsonObject().deepCopy();
            steam.addProperty("id", "2");
            steam.addProperty("stage", 39);
            j.getAsJsonArray("steam").add(steam);
            var draw = j.getAsJsonArray("draws").get(0).getAsJsonObject().deepCopy();
            draw.addProperty("id", "4");
            draw.addProperty("tray", 9);
            j.getAsJsonArray("draws").add(draw);
        });
        block(d, d.well(new Rect(5, 5, w, h)), "Air-cooled condenser", List.of("No ports, a part all the same"), "T-101", "column", air, x, y);
        block(d, d.well(new Rect(5 + w + gap, 5, w, h)), "Kettle", List.of("Bottom left, its pair left"), "T-101", "column", kettle, x, y);
        block(d, d.well(new Rect(5 + 2 * (w + gap), 5, w, h)), "Vacuum: the ejector", List.of("Top 0.3 bar, ahead of the condenser"), "T-101", "column", vacuum, x, y);
        block(d, d.well(new Rect(5 + 3 * (w + gap), 5, w, h)), "In-line pumps", List.of("Smaller, no motor"), "T-101", "column", inline, x, y);
        block(d, d.well(new Rect(5, 5 + h + gap, w, h)), "Free pairs", List.of("No stream: a utility takes the heat, the cooler stays"), "T-101", "column", full, x, y);
        block(d, d.well(new Rect(5 + w + gap, 5 + h + gap, w, h)), "PAs on near trays", List.of("Returns 8, 9, 10; draws 10, 11, 12"), "T-101", "column", near, x, y);
        block(d, d.well(new Rect(5 + 2 * (w + gap), 5 + h + gap, w, h)), "One PA round two others", List.of("Elbows keep each loop whole"), "T-101", "column", nested, x, y);
        block(d, d.well(new Rect(5 + 3 * (w + gap), 5 + h + gap, w, h)), "Every row", List.of("4 PAs, 2 steam, 4 draws, kettle, ejector, air"), "T-101", "column", rows, x, y);
    }

    private static Flowsheet withDoor(Flowsheet sheet, Consumer<JsonObject> change) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) units.add(unit.id().equals("T-101") ? new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), edited(unit.params(), change), unit.unknown()) : unit);
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), sheet.sizing(), sheet.unknown());
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
        try (var old = Files.list(folder)) {
            for (var file : old.toList()) Files.delete(file);
        }
        var sheet = sample();
        FlowsheetFile.save(folder.resolve("CDU train.json"), sheet);
        var removed = withType(sheet, "H-101", "old_heater");
        FlowsheetFile.save(folder.resolve("Gas plant inlet.json"), removed);
        var updated = afterAnUpdate(removed);
        Files.writeString(folder.resolve("Broken.json"), "{ not a flowsheet");
        FlowsheetFile.save(FileColumns.autosave(folder, "CDU train", "unused"), sheet);
        FlowsheetFile.save(FileColumns.autosave(folder, null, "3f2a1c9e-7b10-4e55-9c1a-0d2f6e8b4a77"), sheet);
        var entries = FileColumns.entries(folder, examples(), T);
        var pages = new ArrayList<BufferedImage>();
        for (var skin : SKINS) {
            int ready = 0;
            while (!entries.get(ready).name().equals("CDU train")) ready++;
            int chosen = ready;
            pages.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> list(d, FileColumns.open(entries, chosen, T))));
            pages.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> {
                FileColumns.drawChanges(d.c, d.skin, T.get("flowsheet.file.changes_title", "Gas plant inlet"), FileColumns.changes(updated, FLUIDS, SimulatorRender::columnDoor, T), T.get("flowsheet.file.changes_foot"), d.w, d.h);
                var slots = com.wormzjl.icheme.client.gui.common.DialogColumns.footer(d.w, d.h, 2);
                d.skin.button(d.c, slots.get(0), T.get("flowsheet.file.cancel"), Skin.Press.REST, false);
                d.skin.button(d.c, slots.get(1), T.get("flowsheet.file.open"), Skin.Press.PRIMARY, false);
            }));
        }
        SimulatorPrototypes.grid(out, "17-file-and-repair", List.of("Open: the bundled example first, then the player's own files, an autosave beside its file (light door)", "After an update: what changed (light door)", "Open (dark door)", "After an update (dark door)"), pages);
        var prompts = new ArrayList<BufferedImage>();
        for (var skin : SKINS) {
            prompts.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> unsavedPrompt(d)));
            prompts.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> naming(d, T.get("flowsheet.file.save_title"), "CDU train", T.get("flowsheet.file.cancel"), T.get("flowsheet.file.to_clipboard"), T.get("flowsheet.file.save"))));
        }
        SimulatorPrototypes.grid(out, "25-unsaved-and-save-as", List.of("Open or Close with changes the block has not taken (light door)", "Save as: a file, or the clipboard as text (light door)", "Unsaved changes (dark door)", "Save as (dark door)"), prompts);
    }

    /** A file older than this version: an older revision, a component the package has lost, and a setting P-101 does not hold. */
    private static Flowsheet afterAnUpdate(Flowsheet sheet) {
        var basis = new Flowsheet.Basis(sheet.basis().packageId(), "r1", sheet.basis().components());
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) units.add(unit.id().equals("P-101") ? new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), edited(unit.params(), j -> j.remove("rise")), unit.unknown()) : unit);
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var stream : sheet.streams()) {
            if (!stream.name().equals("CRUDE")) {
                streams.add(stream);
                continue;
            }
            var amounts = new LinkedHashMap<>(stream.feed().amounts());
            amounts.put("Bitumen", amounts.values().stream().mapToDouble(Double::doubleValue).sum() * .0082);
            var feed = new Flowsheet.Feed(stream.feed().temperature(), stream.feed().pressure(), amounts, stream.feed().solids());
            streams.add(new Flowsheet.Stream(stream.id(), stream.name(), stream.from(), stream.to(), feed, stream.route(), stream.unknown()));
        }
        return new Flowsheet(basis, units, streams, sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    /** {@code sheet} with unit {@code id}'s door changed by {@code change}, and without the streams {@code dropped}. */
    private static Flowsheet withDoor(Flowsheet sheet, String id, Consumer<JsonObject> change, String... dropped) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) {
            if (!unit.id().equals(id)) {
                units.add(unit);
                continue;
            }
            var json = unit.params().json().deepCopy();
            change.accept(json);
            units.add(new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new com.wormzjl.icheme.science.flowsheet.Params(json), unit.unknown()));
        }
        var streams = sheet.streams().stream().filter(stream -> !List.of(dropped).contains(stream.id())).toList();
        return new Flowsheet(sheet.basis(), units, streams, sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    /**
     * S3b's sheets (batch 2026-11-02-process-simulator): members in parallel on T-101's Equipment page and on its block. Five
     * condenser shells, as Size all leaves the sample, and two PA 1 cooler shells; an air-cooled condenser of two banks; a
     * reboiled column with two kettles. A count changes no stream, so the counted sample keeps the sample's run.
     */
    private static void memberCounts(Path out, Flowsheet sheet, FlowsheetRun run, double seconds) throws Exception {
        var host = new Host(false, null);
        var counted = withDoor(sheet, "T-101", json -> {
            json.addProperty(com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.CONDENSER_UNITS, 5);
            json.getAsJsonArray("pumparounds").get(0).getAsJsonObject().addProperty(com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.UNITS, 2);
        });
        var air = withDoor(counted, "T-101", json -> {
            json.addProperty("condenser", ColumnComponents.Condenser.AIR_COOLER.name());
            json.addProperty(com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.CONDENSER_UNITS, 2);
            json.addProperty("reboiler_duty", 5e6);
            json.addProperty(com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.KETTLE_UNITS, 2);
        }, "CWS", "CWR");
        grid(out, "28-member-counts-door", DW, DH, List.of("The Equipment page: five condenser shells, two PA 1 cooler shells", "An air-cooled condenser of two banks, and two kettles"),
                List.of(unit(counted, run, "T-101", 3), unit(air, null, "T-101", 3)));
        sheet(out, "29-member-counts-block", W, H, List.of("The block: five condenser shells (×5 over the line into it), two PA 1 cooler shells (×2 by its cooler)", "Two air cooler banks and two kettles, not solved"),
                List.of(scene(counted, run, seconds, host, nothing()), scene(air, null, 0, host, nothing())));
    }

    /** A host whose last run and rating stand as a sizing left them, with a Size all in hand or not. */
    private record Sized(SimulatorHost.Finished finished, SimulatorRunner.Rated rated, SheetState.Running now) implements SimulatorHost {
        @Override public SimulatorHost.Finished last() {
            return finished;
        }

        @Override public SimulatorRunner.Rated rated() {
            return rated;
        }

        @Override public SheetState.Running running() {
            return now;
        }

        @Override public boolean canRun() {
            return true;
        }

        @Override public boolean stores() {
            return true;
        }
    }

    /** The Sizing tab of a screen on the rating's sheet, after what {@code player} does. */
    private static BiConsumer<GuiPrototypes.Door, Skin> sized(Sized host, Consumer<Scene> player) {
        return scene(host.rated().sheet(), host.finished().run(), host.finished().seconds(), host, s -> {
            s.finished = host.finished();
            s.tab = SimulatorFrame.Tab.SIZING;
            player.accept(s);
        });
    }

    private static Flowsheet withSizing(Flowsheet sheet, SizingFacts facts) {
        return new Flowsheet(sheet.basis(), sheet.units(), sheet.streams(), sheet.settings(), facts, sheet.unknown());
    }

    /**
     * S4b's sheets (batch 2026-11-02-process-simulator): the Sizing tab's four views on a real Size all of the sample, a Fixed size
     * rated red, a run that is not steady, the type keys, the exchanger's door before and after sizing, and Size all mid-run.
     */
    private static void sizing(Path out, Flowsheet sheet, FlowsheetRun run, double seconds) throws Exception {
        var thermo = new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {
        });
        long start = System.nanoTime();
        var all = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, run, thermo));
        double took = (System.nanoTime() - start) / 1e9;
        System.out.printf("sized: run %s, %d units, %d lines, %d paths, %.1f s%n", all.run().status(), all.sized().units().size(), all.sized().lines().size(), all.sized().paths().size(), took);
        var finished = new SimulatorHost.Finished(all.sheet(), all.run(), seconds + took);
        var host = new Sized(finished, new SimulatorRunner.Rated(all.sheet(), finished, all.sized(), true), null);
        // E-101 kept at a 0.75 m shell: Fixed, rated red, and greyed until the next run.
        var held = all.sheet().sizing().unit("E-101");
        var kept = held.size() instanceof ExchangerSizing.Size e ? new ExchangerSizing.Size(false, e.shells(), .75, e.tubes()) : new ExchangerSizing.Size(false, 1, .75, 6);
        var fixed = withSizing(all.sheet(), all.sheet().sizing().with("E-101", held.with(SizingFacts.Mode.FIXED).with(kept)));
        var fixedHost = new Sized(finished, new SimulatorRunner.Rated(fixed, finished, MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(fixed, all.run(), thermo)), false), null);
        var unsteady = new SimulatorHost.Finished(all.sheet(), FlowsheetRun.none(), 0);
        var unsteadyHost = new Sized(unsteady, new SimulatorRunner.Rated(all.sheet(), unsteady, MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(all.sheet(), FlowsheetRun.none(), thermo)), false), null);
        sheet(out, "01-sizing-units", W, H, List.of("Units after Size all, E-101 selected", "T-101 selected: its block's members are rows under it", "E-101 kept Fixed at a 0.75 m shell: red, and greyed until the next run",
                "A run that is not steady: every item red"), List.of(sized(host, s -> s.sizing.unit = "E-101"), sized(host, s -> s.sizing.unit = "T-101"), sized(fixedHost, s -> s.sizing.unit = "E-101"), sized(unsteadyHost, nothing())));
        sheet(out, "02-sizing-lines", W, H, List.of("Lines: S-5 selected", "T-101's overhead selected"), List.of(sized(host, s -> {
            s.sizing.view = SizingPage.View.LINES;
            s.sizing.line = "S-5";
        }), sized(host, s -> {
            s.sizing.view = SizingPage.View.LINES;
            s.sizing.line = SheetSizing.member("T-101", "overhead");
        })));
        int pump = 0;
        for (int i = 0; i < all.sized().paths().size(); i++) if (all.sized().paths().get(i).start().equals("P-101")) pump = i;
        int p101 = pump;
        sheet(out, "03-sizing-pressure", W, H, List.of("Pressure: P-101's path", "The next path"), List.of(sized(host, s -> {
            s.sizing.view = SizingPage.View.PRESSURE;
            s.sizing.path = p101;
        }), sized(host, s -> {
            s.sizing.view = SizingPage.View.PRESSURE;
            s.sizing.path = p101 + 1;
        })));
        sheet(out, "04-sizing-build", W, H, List.of("Build: what the constructor builds, and the lines"), List.of(sized(host, s -> s.sizing.view = SizingPage.View.BUILD)));
        var vertical = withSizing(all.sheet(), all.sheet().sizing().with("H-101", all.sheet().sizing().unit("H-101").typed(UnitType.VERTICAL_HEATER)));
        var verticalHost = new Sized(finished, new SimulatorRunner.Rated(vertical, finished, MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(vertical, all.run(), thermo)), false), null);
        sheet(out, "05-sizing-types", W, H, List.of("H-101 as Auto proposes it", "Vertical kept: rated as a vertical heater", "The sheet draws H-101 as the type its size holds"),
                List.of(sized(host, s -> s.sizing.unit = "H-101"), sized(verticalHost, s -> s.sizing.unit = "H-101"), sized(host, s -> s.tab = SimulatorFrame.Tab.FLOWSHEET)));
        grid(out, "06-exchanger-door-sized", DW, DH, List.of("E-101 before sizing: the drops are the player's estimates", "E-101 sized: its drops are its law's at the run's flow"),
                List.of(unit(sheet, run, "E-101", 0), unit(all.sheet(), all.run(), "E-101", 0)));
        var plain = new SimulatorHost.Finished(sheet, run, seconds);
        var proposed = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(sheet, run, thermo));
        var rated = new SimulatorRunner.Rated(sheet, plain, proposed, false);
        sheet(out, "07-size-all-running", W, H, List.of("A run's end rates Auto's proposal", "Size all in hand: Stop in its place, the sheet locked", "Size all running the sheet again"),
                List.of(sized(new Sized(plain, rated, null), nothing()), sized(new Sized(plain, rated, new SheetState.Running(0.4, 0, 0, null, null, SheetState.Task.SIZE)), nothing()),
                        sized(new Sized(plain, rated, new SheetState.Running(14.2, 2, 30, "T-101", com.wormzjl.icheme.science.flowsheet.Report.Start.LAST, SheetState.Task.SIZE)), nothing())));
    }

    /**
     * S5's sheets (batch 2026-11-02-process-simulator): Send to block on the Sizing tab after one Size all of the sample and after a second, with
     * what its hover says; then the constructor's Import page listing the designs Send to block stores, Import on the choice page, and
     * the condenser landed on the draft.
     */
    private static void send(Path out, Flowsheet sheet, FlowsheetRun run, double seconds) throws Exception {
        var thermo = new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {
        });
        var first = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, run, thermo));
        var second = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(first.sheet(), first.run(), thermo));
        int red1 = SizingPage.reds(first.sized()), red2 = SizingPage.reds(second.sized());
        var designs = SheetSizing.designs(second.sheet(), second.sized());
        System.out.printf("send: first Size all %d red, second %d red; %d designs%n", red1, red2, designs.size());
        for (var round : List.of(first, second)) {
            String which = round == first ? "first" : "second";
            round.sized().units().forEach((id, unit) -> {
                if (unit.rating().lamp() == com.wormzjl.icheme.science.flowsheet.sizing.Lamp.RED) System.out.println("red after the " + which + ": unit " + id + " " + unit.rating().closest());
            });
            round.sized().lines().forEach((id, line) -> {
                if (line.line().flows() && line.rating().lamp() == com.wormzjl.icheme.science.flowsheet.sizing.Lamp.RED) System.out.println("red after the " + which + ": line " + id + " " + line.rating().closest());
            });
            for (var path : round.sized().paths())
                if (path.rating().lamp() == com.wormzjl.icheme.science.flowsheet.sizing.Lamp.RED) System.out.println("red after the " + which + ": path " + path.start() + " " + path.rating().closest());
        }
        var f1 = new SimulatorHost.Finished(first.sheet(), first.run(), seconds);
        var f2 = new SimulatorHost.Finished(second.sheet(), second.run(), seconds);
        var host1 = new Sized(f1, new SimulatorRunner.Rated(first.sheet(), f1, first.sized(), true), null);
        var host2 = new Sized(f2, new SimulatorRunner.Rated(second.sheet(), f2, second.sized(), true), null);
        String hint1 = red1 > 0 ? HeatDiagramRender.tr("gui.icheme.flowsheet.sizing.send_red", red1) : HeatDiagramRender.tr("gui.icheme.flowsheet.sizing.send_hint", designs.size());
        String hint2 = red2 > 0 ? HeatDiagramRender.tr("gui.icheme.flowsheet.sizing.send_red", red2) : HeatDiagramRender.tr("gui.icheme.flowsheet.sizing.send_hint", designs.size());
        sheet(out, "01-send-to-block", W, H, List.of("One Size all. Send's hover: " + hint1, "A second Size all. Send's hover: " + hint2),
                List.of(sized(host1, nothing()), sized(host2, nothing())));
        var t = GuiPrototypes.DIALOG;
        var outlet = new ConstructorColumn.Outlet(1, 0, GuiPrototypes.STANDING);
        var condenser = designs.stream().filter(d -> d.member().equals("condenser")).findFirst().orElseThrow();
        var shape = condenser.shape();
        var landed = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.EXCHANGER, shape.diameter(), (int) Math.round(shape.tangentLength()), shape.support(), 3, 0, 0,
                shape.bank(), null, outlet, 16, com.wormzjl.icheme.science.equipment.TowerBank.ONE), t);
        // The screen keeps what the unit holds on its display, as it does for any draft, and sends the import's message to chat.
        var held = UnitDesign.of(shape.kind()).held(shape);
        String said = HeatDiagramRender.tr("message.icheme.equipment.imported_unit", t.get(ConstructorColumn.KEY + "import.member", condenser.tag(), t.get("flowsheet.sizing.member.condenser")),
                t.get("vessel.title.exchanger"), condenser.count());
        grid(out, "02-constructor-import", 464, 254, List.of("Import: the designs Send to block stored", "Import on the choice page, beside the last groups", "The condenser landed on the draft. Chat: " + said),
                List.of((d, s) -> GuiPrototypes.constructorDoor(d, ConstructorColumn.imports(designs.stream().map(x -> {
                            var l = ConstructorColumn.listed(x, t);
                            return new ConstructorColumn.Listed(d.m.cut(l.name(), ConstructorColumn.WIDTH - 12), l.hint());
                        }).toList(), t), t.get(ConstructorColumn.KEY + "group.imports"), "back", null, 0, java.util.Map.of()),
                        (d, s) -> GuiPrototypes.constructorDoor(d, ConstructorColumn.choose(ConstructorColumn.Category.HEAT, ConstructorColumn.Equipment.EXCHANGER, 16, t),
                                HeatDiagramRender.tr("block.icheme.equipment_constructor"), "back", null, 0, java.util.Map.of()),
                        (d, s) -> GuiPrototypes.constructorDoor(d, landed, t.get("vessel.title.exchanger"), "change", t.get(ConstructorColumn.KEY + held.key(), held.args()), Palette.ACCENT, java.util.Map.of())));
    }

    /** W10's load: nitrogen with a little steam at 20 kPa and 50 °C. */
    private static Flowsheet.Feed ejectorLoad() {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Nitrogen", 10.);
        amounts.put("Water", .5);
        return new Flowsheet.Feed(323.15, 2e4, amounts, List.of());
    }

    private static Flowsheet.Feed motiveSteam(double molPerSecond) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Water", molPerSecond);
        return new Flowsheet.Feed(460, 1e6, amounts, List.of());
    }

    /**
     * Main plan W10's sheets (batch 2026-11-02-process-simulator): the lit ejector tile on a small vacuum sheet, its Motive port free,
     * wired at 1.1 times the need and at 0.9 times it; their doors; the column block's Motive steam port, and the door's Ejector group;
     * the Sizing rows of the three. W10b draws them again on the vacuum set's law S1.
     */
    private static void ejector(Path out) throws Exception {
        var thermo = new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {
        });
        String door = "{\"discharge\":1.2e5,\"motive\":1e6}";
        var free = new Sheet().unit("J-101", "ejector", 120, 60, door).feed("LOAD", "J-101", "suction", ejectorLoad(), 60, 40).product("S-1", "J-101", "discharge", 260, 70).build();
        var freeRun = FlowsheetEngine.run(free, CATALOG, () -> {
        });
        double need = freeRun.reports().get("J-101").readings().get("need"), waterMass = MaterialRuntime.with(CATALOG, NETWORK, () -> com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit.waterMass(thermo));
        System.out.printf("ejector: free run %s, need %.3f kg/s (%.0f kg/h), notes %s%n", freeRun.status(), need, 3600 * need, com.wormzjl.icheme.client.gui.flowsheet.NoteText.all(freeRun));
        var sheet = new Sheet().unit("J-101", "ejector", 120, 60, door).feed("LOAD", "J-101", "suction", ejectorLoad(), 60, 40).product("S-1", "J-101", "discharge", 260, 70)
                .unit("J-102", "ejector", 120, 160, door).feed("LOAD-2", "J-102", "suction", ejectorLoad(), 60, 140).feed("STEAM-2", "J-102", "motive", motiveSteam(1.1 * need / waterMass), 40, 170)
                .product("S-2", "J-102", "discharge", 260, 170)
                .unit("J-103", "ejector", 120, 260, door).feed("LOAD-3", "J-103", "suction", ejectorLoad(), 60, 240).feed("STEAM-3", "J-103", "motive", motiveSteam(.9 * need / waterMass), 40, 270)
                .product("S-3", "J-103", "discharge", 260, 270).build();
        long start = System.nanoTime();
        var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("ejector sheet: %s, mass gap %.3g, heat gap %.3g W, notes %s%n", run.status(), run.massGap(), run.heatGap(), com.wormzjl.icheme.client.gui.flowsheet.NoteText.all(run));
        var host = new Host(false, null);
        sheet(out, "01-ejector-tile", W, H, List.of("The Machines tiles: the steam ejector lit. J-101 selected: its Motive port free, a utility at the need",
                        "J-102 selected: Motive wired at 1.1 times the need", "J-103 selected: Motive wired at 0.9 times the need, short"),
                List.of(scene(sheet, run, seconds, host, s -> {
                    s.category = 4;
                    s.gestures.select(new SheetGestures.Selection(true, "J-101"));
                }), scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(true, "J-102"))),
                        scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(true, "J-103")))));
        grid(out, "02-ejector-doors", DW, DH, List.of("J-101's door: the two pressures, the steam the law needs", "J-103's door: its wired steam short of the need", "J-102's streams"),
                List.of(unit(sheet, run, "J-101", 0), unit(sheet, run, "J-103", 0), unit(sheet, run, "J-102", 1)));

        // The column block under ambient pressure: its ejector on the vapour line, with the Motive steam port.
        var exchanger = edited(ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER), j -> {
            j.addProperty("top_pressure", .3e5);
            j.add("draws", new com.google.gson.JsonArray());
            j.add("pumparounds", pumparounds(4, 6));
        });
        var air = edited(exchanger, j -> j.addProperty("condenser", ColumnComponents.Condenser.AIR_COOLER.name()));
        sheet(out, "03-ejector-block", W, H, List.of("The block's Motive steam port"), List.of((d, skin) -> {
            int gap = 4, w = (d.w - 10 - gap) / 2, x = (w - ColumnBlock.WIDTH) / 2, y = 76;
            block(d, d.well(new Rect(5, 5, w, d.h - 10)), "Top 30 kPa, exchanger condenser", List.of("The cooling water's pair one pitch higher", "Motive steam enters under it"), "T-101", "column", exchanger, x, y);
            block(d, d.well(new Rect(5 + w + gap, 5, w, d.h - 10)), "Top 30 kPa, air-cooled condenser", List.of("No cooling water pair"), "T-101", "column", air, x, y);
        }));
        var vacuum = new Sheet().unit("T-101", "column", 40, 20, exchanger).build();
        var equipment = new Door.View();
        equipment.tab = 3;
        grid(out, "04-ejector-column-door", DW, DH, List.of("The column door's Equipment page: the Ejector group's two pressures"),
                List.of((d, s) -> door(d, ColumnDoors.door(new Scene(vacuum, null, 0, host).context(), "T-101", equipment))));

        // The Sizing tab after Size all: the free port's bore, the wired ports' motive rows.
        var all = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, run, thermo));
        for (var id : List.of("J-101", "J-102", "J-103")) {
            var unit = all.sized().units().get(id);
            System.out.printf("sized %s: %s, lamp %s, closest %s%n", id, unit.size(), unit.rating().lamp(), unit.rating().closest());
        }
        var finished = new SimulatorHost.Finished(all.sheet(), all.run(), seconds);
        var sized = new Sized(finished, new SimulatorRunner.Rated(all.sheet(), finished, all.sized(), true), null);
        sheet(out, "05-ejector-sizing", W, H, List.of("J-101 after Size all: the suction holds the load with its spare", "J-102: the wired steam between the need and 1.20 times it", "J-103: the wired steam short of the need"),
                List.of(sized(sized, s -> s.sizing.unit = "J-101"), sized(sized, s -> s.sizing.unit = "J-102"), sized(sized, s -> s.sizing.unit = "J-103")));
    }

    /** {@code sheet} with unit {@code id}'s settings changed by {@code change}. */
    private static Flowsheet withParams(Flowsheet sheet, String id, Consumer<JsonObject> change) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) {
            if (!unit.id().equals(id)) {
                units.add(unit);
                continue;
            }
            var json = unit.params().json().deepCopy();
            change.accept(json);
            units.add(new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new Params(json), unit.unknown()));
        }
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    /**
     * C3's sheets (batch 2026-11-02-process-simulator, COOLER_MODEL_PLAN.md section 7): T-101 on an air-then-water condenser at a
     * 38 °C receiver with two trim shells, its block and its door's Equipment page; the Results' Units view and Log on that run;
     * the CWS feed whose flow T-101 sets; and the Sizing tab after Size all, E-102 (the Cooler, its type keys and fans) and T-101
     * (its proposed condenser) picked.
     */
    private static void c3(Path out, Flowsheet sheet, FlowsheetRun run, double seconds) throws Exception {
        var trimmed = withParams(sheet, "T-101", json -> {
            json.addProperty("condenser", ColumnComponents.Condenser.AIR_THEN_WATER.name());
            json.addProperty("condenser_temperature", 311.15);
            json.addProperty("trim_units", 2);
        });
        long start = System.nanoTime();
        var trimmedRun = FlowsheetEngine.run(trimmed, CATALOG, () -> {
        });
        double took = (System.nanoTime() - start) / 1e9;
        System.out.printf("air then water: %s, condenser %.2f MW, air %.2f MW, water %.2f MW, notes %s%n", trimmedRun.status(), trimmedRun.reports().get("T-101").readings().getOrDefault("condenser", Double.NaN) / 1e6,
                trimmedRun.reports().get("T-101").readings().getOrDefault("condenser_air", Double.NaN) / 1e6, trimmedRun.reports().get("T-101").readings().getOrDefault("condenser_water", Double.NaN) / 1e6,
                com.wormzjl.icheme.client.gui.flowsheet.NoteText.all(trimmedRun));
        var host = new Host(false, null);
        sheet(out, "01-air-then-water-block", W, H, List.of("T-101 on air then water: the air cooler, then two trim shells on the cooling water's pair"),
                List.of(scene(trimmed, trimmedRun, took, host, s -> s.gestures.select(new SheetGestures.Selection(true, "T-101")))));
        grid(out, "02-column-door-equipment", DW, DH, List.of("The Equipment page, air then water: the third key and the trim's shells", "The sample's exchanger, for comparison"),
                List.of(unit(trimmed, trimmedRun, "T-101", 3), unit(sheet, run, "T-101", 3)));
        sheet(out, "03-results-units-log", W, H, List.of("Units: the condenser's air and trim, the steam past its valve", "Log: the steam check's margin in bar"), List.of(
                scene(trimmed, trimmedRun, took, host, s -> {
                    s.tab = SimulatorFrame.Tab.RESULTS;
                    s.results.view = ResultsPage.View.UNITS;
                }), scene(trimmed, trimmedRun, took, host, s -> {
                    s.tab = SimulatorFrame.Tab.RESULTS;
                    s.results.view = ResultsPage.View.LOG;
                })));
        grid(out, "04-feed-set-by", DW, DH, List.of("CWS: its flow set by T-101; the entry is the first guess, the readings what the run gave"), List.of(stream(sheet, run, "CWS", 0)));
        var thermo = new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {
        });
        var all = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, run, thermo));
        var finished = new SimulatorHost.Finished(all.sheet(), all.run(), seconds);
        var sized = new Sized(finished, new SimulatorRunner.Rated(all.sheet(), finished, all.sized(), true), null);
        System.out.printf("sized: E-102 %s, values %s; T-101 proposes %s; fans %.1f kW%n", all.sized().units().get("E-102").size(), all.sized().units().get("E-102").rating().values(), all.sized().condensers().get("T-101"),
                all.sized().fanPower() / 1e3);
        sheet(out, "05-sizing-values", W, H, List.of("E-102, the Cooler: its type keys (Air, Water), its fans, and the sheet's fans", "T-101: the condenser sizing proposes"),
                List.of(sized(sized, s -> s.sizing.unit = "E-102"), sized(sized, s -> s.sizing.unit = "T-101")));
    }

    private static Flowsheet withType(Flowsheet sheet, String id, String type) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) units.add(unit.id().equals(id) ? new Flowsheet.Unit(unit.id(), type, unit.name(), unit.x(), unit.y(), unit.params(), unit.unknown()) : unit);
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        FLUIDS = fluids();
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        // -Donly=ejector: W10's sheets alone, on a sheet of their own.
        if ("ejector".equals(System.getProperty("only"))) {
            ejector(out);
            return;
        }
        // -Donly=open: Open, with the bundled examples first, and its prompts (sheets 17 and 25), with no run.
        if ("open".equals(System.getProperty("only"))) {
            opened(out);
            return;
        }
        var sheet = sample();
        long start = System.nanoTime();
        var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("sample: %s, %d streams, %d loops, %.1f s, notes %s%n", run.status(), run.streams().size(), run.loops().size(), seconds, com.wormzjl.icheme.client.gui.flowsheet.NoteText.all(run));
        // -Donly=member-counts: S3b's two sheets alone.
        if ("member-counts".equals(System.getProperty("only"))) {
            memberCounts(out, sheet, run, seconds);
            return;
        }
        // -Donly=sizing: S4b's sheets alone.
        if ("sizing".equals(System.getProperty("only"))) {
            sizing(out, sheet, run, seconds);
            return;
        }
        // -Donly=c3: C3's sheets alone.
        if ("c3".equals(System.getProperty("only"))) {
            c3(out, sheet, run, seconds);
            return;
        }
        // -Donly=send: S5's sheets alone.
        if ("send".equals(System.getProperty("only"))) {
            send(out, sheet, run, seconds);
            return;
        }
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
        sheet(out, "02-selection-panel", W, H, List.of("A unit selected", "A column selected: its ring follows the frame", "A feed selected", "A stream a unit makes selected"), List.of(
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(true, "E-101"))),
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(true, "T-101"))),
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(false, "CRUDE"))),
                scene(sheet, run, seconds, host, s -> s.gestures.select(new SheetGestures.Selection(false, "S-5")))));
        sheet(out, "03-interaction", W, H, List.of("A tile dragged from the palette", "The Heat tiles: a cooling tower stands drawn, off", "A stream drawn from a free port: what can take it is ringed", "A name typed in place", "Right click S-8: its end follows the cursor", "A port's hover"), List.of(
                scene(sheet, run, seconds, host, s -> {
                    s.gestures.carry(Tiles.tile("drum"));
                    s.gestures.move(cx(s, 138), cy(s, 140));
                }), scene(sheet, run, seconds, host, s -> s.category = 3), scene(sheet, run, seconds, host, s -> {
                    s.editor.removeStream("KERO");
                    s.editor.addUnit("pump", 640, 138);
                    var port = s.editor.layout().place("T-101", "draw/1");
                    s.gestures.press(cx(s, port.x()), cy(s, port.y()), 0, 1000);
                    s.gestures.drag(cx(s, 626), cy(s, 152));
                }), scene(sheet, run, seconds, host, s -> {
                    var tag = s.editor.layout().name(s.editor.layout().line("S-9"));
                    s.gestures.press(cx(s, tag.x() + 2), cy(s, tag.y() + 2), 0, 1000);
                    s.gestures.release(cx(s, tag.x() + 2), cy(s, tag.y() + 2), 0);
                    s.gestures.press(cx(s, tag.x() + 2), cy(s, tag.y() + 2), 0, 1100);
                    s.gestures.typed("RES-HOT");
                }), scene(sheet, run, seconds, host, s -> {
                    s.editor.addUnit("pump", 640, 138);
                    var p = s.editor.layout().line("S-8").points();
                    s.gestures.press(cx(s, p[p.length - 2] - 8), cy(s, p[p.length - 1]), 1, 1000);
                    s.gestures.move(cx(s, 626), cy(s, 152));
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
        var empty = new Flowsheet(sheet.basis(), List.of(), List.of(), sheet.settings());
        sheet(out, "24-block-words", W, H, List.of("Saved on the block", "A stored sheet this version cannot read: the block opens empty and keeps it", "The data changed: the sheet stands, its results went"), List.of(
                scene(sheet, run, seconds, new Host(false, null, new SimulatorHost.Said("store.saved", List.of(), false)), nothing()),
                scene(empty, null, 0, new Host(false, null, new SimulatorHost.Said("store.kept", List.of(), true)), nothing()),
                scene(sheet, null, 0, new Host(false, null, new SimulatorHost.Said("store.results_dropped", List.of(), false)), nothing())));
        sheet(out, "07-feed-stream", DW, DH, List.of("A feed stream's door"), List.of(stream(sheet, run, "CRUDE", 0)));
        var tank = new LinkedHashMap<String, Double>();
        tank.putAll(crude(298.15, 8e5).amounts());
        tank.put("Bitumen", 12.0);
        tank.put("Sulfolane", 3.0);
        var held = new SheetClipboard.Held("Tank at 120, 64, -310", tank, 313.15, 1.013e5);
        sheet(out, "08-feed-composition", DW, DH, List.of("A feed stream's Composition tab, nothing copied yet", "Something copied: Paste is live, and the clipboard's contents show", "Paste with T and P: it says what it could not place"), List.of(stream(sheet, run, "CRUDE", 1), pasted(sheet, run, "CRUDE", held, false), pasted(sheet, run, "CRUDE", held, true)));
        SimulatorPrototypes.both(out, "09-reservoir-copy", DW, DH, (d, s) -> SimulatorPrototypes.reservoirCopy(d), "Copy on the tank's own page",
                List.of("1. Copy takes the composition, temperature and pressure the page already shows, from the last published snapshot: no packet, no solve. Paste on a feed's Composition tab takes it from there."));
        sheet(out, "10-product-stream", DW, DH, List.of("A product stream's door"), List.of(stream(sheet, run, "NAPHTHA", 0)));
        grid(out, "11-unit-doors", DW, DH, List.of("Heat exchanger", "Fired heater", "Pump", "Drum", "Control valve", "Splitter"), List.of(unit(sheet, run, "E-101", 0), unit(sheet, run, "H-101", 0), unit(sheet, run, "P-101", 0), unit(more, moreRun, "D-101", 0), unit(sheet, run, "V-101", 0), unit(more, moreRun, "TEE-1", 0)));
        grid(out, "12-column-door", DW, DH, List.of("The Column page, D1 open", "The Feed page: read from S-7", "The Results page, T-101's real profile", "The Equipment page"), List.of(unit(sheet, run, "T-101", 0), unit(sheet, run, "T-101", 1), columnResults(sheet, run), unit(sheet, run, "T-101", 3)));
        var ticks = List.of("CRUDE", "S-7", "OFFGAS", "NAPHTHA", "KERO", "RESIDUE");
        var bundled = new ArrayList<FileColumns.ColumnTemplate>();
        try (var index = Files.newBufferedReader(Path.of("src/main/resources/assets/icheme/columnpreset/index.json"))) {
            for (var file : com.google.gson.JsonParser.parseReader(index).getAsJsonArray())
                try (var in = Files.newBufferedReader(Path.of("src/main/resources/assets/icheme/columnpreset/" + file.getAsString()))) {
                    var o = com.google.gson.JsonParser.parseReader(in).getAsJsonObject();
                    bundled.add(new FileColumns.ColumnTemplate(o.get("name").getAsString(), com.wormzjl.icheme.science.column.v3.V3ColumnTemplate.read(o), true, file.getAsString()));
                }
        }
        var streams = new ArrayList<FileColumns.StreamTemplate>();
        for (var preset : com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog.resolve(CATALOG)) {
            var amounts = new LinkedHashMap<String, Double>();
            double[] shares = preset.moleFractions();
            for (int i = 0; i < shares.length && i < FLUIDS.axis().size(); i++) if (shares[i] > 0) amounts.put(FLUIDS.axis().get(i), shares[i]);
            streams.add(new FileColumns.StreamTemplate(preset.name(), amounts, Double.NaN, Double.NaN, Double.NaN));
        }
        streams.add(new FileColumns.StreamTemplate("Boiler feed", Map.of("Water", 1.0), 393.15, 40e5, 27.8));
        var templates = new ArrayList<BufferedImage>();
        for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK)) {
            templates.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> list(d, FileColumns.columnTemplates(bundled, 0, SheetUnits.DEFAULT, T))));
            templates.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> list(d, SimulatorPage.listing("TEMPLATES", new Scene(sheet, run, seconds, host).context(), List.of(), streams, List.of(), 0))));
            templates.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> naming(d, T.get("flowsheet.file.template_title"), "Boiler feed", T.get("flowsheet.file.cancel"), T.get("flowsheet.file.save"))));
            templates.add(page(FileColumns.W, FileColumns.H, skin, (d, s) -> naming(d, T.get("flowsheet.file.column_title"), "CDU two draws", T.get("flowsheet.file.cancel"), T.get("flowsheet.file.save"))));
        }
        SimulatorPrototypes.stack(out.resolve("13-templates.png"), List.of("Column templates, a column's door", "Stream templates, a feed's door: a saved one shows its conditions", "Save as template, a feed's door", "Save current, the Templates dialog"), templates.subList(0, 4), List.of());
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
        sheet(out, "19-block-ports", 1450, 500, List.of("One block: the door's settings add the connection points"), List.of((d, s) -> blockPorts(d)));
        sheet(out, "21-other-blocks", W, 402, List.of("The same rule for every block"), List.of((d, s) -> otherBlocks(d)));
        sheet(out, "26-block-variants", 1450, 1020, List.of("What the door can ask of the block"), List.of((d, s) -> blockVariants(d)));
        var warned = withDoor(sheet, j -> j.addProperty("condenser_temperature", 300));
        var warnedRun = FlowsheetEngine.run(warned, CATALOG, () -> {
        });
        var failed = withDoor(sheet, j -> j.addProperty("top_pressure", 1e3));
        var failedRun = FlowsheetEngine.run(failed, CATALOG, () -> {
        });
        System.out.printf("tones: warned %s %s; failed %s %s%n", warnedRun.status(), warnedRun.log(), failedRun.status(), failedRun.log());
        sheet(out, "27-block-tones", W, H, List.of("Not solved: every line greys", "A warning", "Failed"), List.of(scene(sheet, null, 0, host, nothing()), scene(warned, warnedRun, 1.2, host, nothing()), scene(failed, failedRun, 1.2, host, nothing())));
        System.out.println("sheets in " + out);
    }
}
