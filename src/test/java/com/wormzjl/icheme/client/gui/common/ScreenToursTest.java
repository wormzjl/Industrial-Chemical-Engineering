package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.ConstructorColumn.Category;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn.Equipment;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.equipment.VesselPlacement.Form;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The help tours of the dialogs and the machine, control and constructor screens (batch 2026-11-12-tutorial-system, P6,
 * {@link ScreenTours}): every page of each screen has its tour, and every stop of every tour stands on its page at every
 * size the screen takes, on at least one variant of the page (an editor and a reader, each optional row present and
 * absent), and its first stop, which says what the page is for, on every variant: a frame place or a door row's widget or
 * group ({@link TourAnchors#find}) as the screen places its door. A fixed-width door is tested at its width and that width
 * less the view's bar. The variants are public: the tour render ({@code tools/tutorial-renders/CoverageRender}) draws them.
 */
public class ScreenToursTest {
    private static final Measure M = Measure.fixed(6);
    public static final HeatControlColumnTest.Recording T = new HeatControlColumnTest.Recording("gui.icheme.");
    private static final BiFunction<String, Object[], String> TEXT = T::get;

    /** One variant of a page at one size: its frame places, its door's columns and the foot of the door's view. */
    public record Variant(String name, int width, int height, Map<String, Rect> frame, List<Column> door, int viewBottom) {
        public Rect at(String anchor) {
            if (anchor.equals("page")) return ScreenTours.page(door);
            return frame.containsKey(anchor) ? frame.get(anchor) : TourAnchors.find(anchor, door, k -> T.get(k));
        }
    }

    /** Checks {@code page}'s tour against its variants, grouped by size: every stop on one variant of each size, the first on all. */
    private static void stands(String page, List<List<Variant>> sizes) {
        var tour = TourFile.load().get(page);
        assertNotNull(tour, page + " has its tour");
        assertFalse(sizes.isEmpty());
        for (var variants : sizes) {
            assertFalse(variants.isEmpty(), page);
            for (int i = 0; i < tour.stops().size(); i++) {
                String anchor = tour.stops().get(i).anchor();
                boolean found = false;
                for (var v : variants) {
                    var r = v.at(anchor);
                    if (r == null) {
                        assertTrue(i > 0, page + ", " + v.name() + " at " + v.width() + ": the first stop " + anchor + " stands on every variant");
                        continue;
                    }
                    found = true;
                    assertTrue(r.x() >= 0 && r.y() >= 0 && r.right() <= v.width() && r.w() > 0 && r.h() > 0, page + ", " + v.name() + ": " + anchor + " inside the screen: " + r);
                }
                assertTrue(found, page + " at " + variants.getFirst().width() + ": " + anchor + " on some variant");
            }
        }
    }

    /** A dialog's door at {@code width}, and less its bar. */
    private static List<Integer> doors(int width) {
        return List.of(width - 20, width - 20 - ScrollView.BAR - 4);
    }

    /** A dialog's variant: its rows on a door {@code door} wide, the panel as tall as the column (as GaugeScreen sizes it). */
    private static Variant dialog(String name, int width, int door, List<Column.Row> rows, String title, List<String> footer, Measure m) {
        var column = Column.place(rows, 10, DialogColumns.TOP, door, m, Column.Metrics.COMPACT);
        int height = DialogColumns.TOP + column.bounds().h() + DialogColumns.FOOTER;
        return new Variant(name, width, height, ScreenTours.dialog(width, height, title, m, footer), List.of(column), height - DialogColumns.FOOTER);
    }

    /** The gauge's variants: pressure and temperature, the drafts forming a range or not. */
    public static List<Variant> gauge(int door, Measure m) {
        var out = new ArrayList<Variant>();
        for (boolean pressure : new boolean[]{true, false})
            for (boolean valid : new boolean[]{true, false})
                out.add(dialog((pressure ? "pressure" : "temperature") + (valid ? "" : " invalid"), 246, door,
                        DialogColumns.gauge(new DialogColumns.Gauge(pressure, pressure ? List.of("bar", "kPa", "Pa") : List.of("°C", "K"), 0, true, valid), T),
                        pressure ? "Pressure gauge" : "Thermometer", List.of("cancel", "save"), m));
        return out;
    }

    /** The pipeline tools' variants: the laying tool laying pipe (first), the sizing tool, a bare secondary, a data cable. */
    public static List<Variant> pipelineTool(int door, Measure m) {
        var out = new ArrayList<Variant>();
        var names = List.of("laying pipe", "sizing", "bare secondary", "data cable");
        var tools = List.of(new DialogColumns.PipelineTool(true, 0, false, true), new DialogColumns.PipelineTool(false, 0, false, true),
                new DialogColumns.PipelineTool(true, 1, true, false), new DialogColumns.PipelineTool(true, 0, false, true, true));
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            out.add(dialog(names.get(i), 230, door, DialogColumns.pipelineTool(tool, T), tool.paints() ? "Pipe Laying Tool" : "Pipe Sizing Tool", List.of("cancel", "save"), m));
        }
        return out;
    }

    /** The datasheet's variants on a panel {@code w} by {@code h}: a unit with moving parts and a vessel, named and not. */
    public static List<Variant> vessel(int w, int h, Measure m) {
        var sheet = List.of(new DialogColumns.Size("diameter", "2.0", "m", true), new DialogColumns.Size("length", "6.0", "m", true), new DialogColumns.Size("volume", "20.9", "m³", false));
        var out = new ArrayList<Variant>();
        for (boolean moving : new boolean[]{true, false})
            for (boolean names : new boolean[]{true, false})
                for (int door : List.of(150, 150 - ScrollView.BAR - 4)) {
                    var frame = new LinkedHashMap<>(ScreenTours.vessel(w, h, 150, "Vertical drum", m, names));
                    frame.put("title", TitleStrip.placeKeys(w, 1, "Vertical drum", m).plate());
                    out.add(new Variant((moving ? "unit" : "vessel") + (names ? " named" : ""), w, h, frame,
                            List.of(Column.place(DialogColumns.vessel(sheet, moving, true, T), 10, DialogColumns.TOP, door, m, Column.Metrics.COMPACT)), h - DialogColumns.FOOTER));
                }
        return out;
    }

    /** The machine page's variants on an image {@code w} by {@code h}: every kind, editor and reader (MachineColumnSamples). */
    public static List<Variant> machine(int w, int h, Measure m) {
        var out = new ArrayList<Variant>();
        for (var c : MachineColumnSamples.cases(T)) {
            var frame = new LinkedHashMap<>(ScreenTours.machine(w, h, true, c.in().canEdit() && c.in().kind().settable()));
            frame.put("title", TitleStrip.placeKeys(w, 2, "Motor", m).plate());
            out.add(new Variant(c.name(), w, h, frame, List.of(Column.place(MachineColumn.rows(c.in(), T), 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(w) - 10, m, Column.Metrics.COMPACT)),
                    h - FluidGuiLayout.DOOR_FOOT));
        }
        return out;
    }

    private static ControlColumns.Choice choice(String key, String label, boolean selected) {
        return new ControlColumns.Choice(key, label, selected);
    }

    /** A control page's own column, as ControlPanelScreen.right builds it. */
    private static List<Column.Row> right(String page, boolean level, int pages) {
        return switch (page) {
            case "input", "output" -> {
                var rows = new ArrayList<>(ControlColumns.choices(T.get("control." + page), List.of(choice("sensor:a", "LT-101", true), choice("sensor:b", "PT-102", false)), TEXT));
                rows.addAll(ControlColumns.navigation(0, pages, "devices", TEXT));
                yield rows;
            }
            case "column" -> {
                var units = new OperationColumn.Units(DisplayUnit.TemperatureUnit.CELSIUS, DisplayUnit.PressureUnit.BAR, DisplayUnit.FlowRateUnit.PER_HOUR);
                var view = OperationColumnTest.running(OperationColumnTest.setpoints(1, 1, 1, false), OperationView.Phase.RUNNING);
                var rows = new ArrayList<Column.Row>(List.of(new Column.Group("C-101", null, List.of(new Column.Paragraph("Running", 0xFFFFFFFF)))));
                rows.addAll(OperationColumn.rows(new OperationColumn.Input(view, false, units), T));
                yield rows;
            }
            case "converter" -> {
                var tank = new ConverterDoor.Tank(0.5, "50", "500 L");
                var rows = new ArrayList<Column.Row>(List.of(new Column.Group("X-101", null, List.of(new Column.Paragraph("RUNNING", 0xFFFFFFFF)))));
                rows.addAll(ConverterDoor.rows(new ConverterDoor.Input(true, ConverterDoor.Mode.BALANCE, "L/h", "1000", "500", "10", List.of("—", "—", "—", "—"), tank, "Water", tank,
                        "icheme:water", false, List.of(), "0", "none"), (k, a) -> T.get("fluid." + k, a)));
                yield rows;
            }
            default -> ControlColumns.loop(new ControlColumns.View(true, "%", level ? "m" : "", false, "LT-101", "50", "CV-101", "OK", false,
                    level ? ControlColumns.Variable.LEVEL : ControlColumns.Variable.FLOW), "hint", TEXT);
        };
    }

    /** The DCS's list column: controllers, columns, converters, alarms. */
    private static List<Column.Row> list(int pages) {
        var rows = new ArrayList<>(ControlColumns.controllers(List.of(choice("controller:a", "FIC-101", true)), TEXT));
        rows.addAll(ControlColumns.navigation(0, pages, "controllers", TEXT));
        rows.add(new Column.Group(T.get("control.columns"), null, List.of(new Column.Choice("C-101", null, false, "column:1"))));
        rows.add(new Column.Group(T.get("control.converters"), null, List.of(new Column.Choice("X-101", null, false, "converter:1"))));
        rows.addAll(ControlColumns.alarms(List.of(), TEXT));
        return rows;
    }

    /** The control panel's page {@code page} in a window {@code w} by {@code h}: a DCS and a controller box where each shows it, a level and a flow loop, one and three list pages. */
    public static List<Variant> control(String page, int w, int h, Measure m) {
        var out = new ArrayList<Variant>();
        for (boolean dcs : new boolean[]{true, false}) {
            var layout = ControlColumns.layout(w, h, dcs);
            if (page.equals("controllers") && (!dcs || layout.split())) continue;
            if ((page.equals("column") || page.equals("converter")) && !dcs) continue;
            for (boolean level : new boolean[]{true, false})
                for (int pages : new int[]{1, 3}) {
                    var r = right(page, level, pages);
                    List<List<Column.Row>> rows;
                    if (!dcs) {
                        var all = new ArrayList<>(r);
                        if (page.equals("loop")) all.addAll(ControlColumns.alarms(List.of(), TEXT));
                        rows = List.of(all);
                    } else rows = layout.split() ? List.of(list(pages), r) : List.of(page.equals("controllers") ? list(pages) : r);
                    var columns = new ArrayList<Column>();
                    for (int i = 0; i < rows.size(); i++) {
                        var a = layout.areas().get(i);
                        columns.add(Column.place(rows.get(i), a.x(), a.y(), a.w(), m, Column.Metrics.COMPACT));
                    }
                    var area = layout.areas().getFirst();
                    out.add(new Variant((dcs ? "dcs" : "box") + (level ? " level" : " flow") + " " + pages, layout.width(), layout.height(),
                            ScreenTours.controlFrame(layout, dcs ? "Control station" : "Controller box", m), columns, area.bottom()));
                }
        }
        return out;
    }

    /** The constructor's page {@code page} on a window {@code height} high (464 wide), for each form and state that page shows. */
    public static List<Variant> constructor(ScreenTours.Constructor page, int height, Measure m) {
        var out = new ArrayList<Variant>();
        for (var shown : pages(page)) {
            var area = ConstructorColumn.area(height, shown.display());
            for (int door : List.of(area.w(), area.w() - ScrollView.BAR - 4))
                out.add(new Variant(page + " " + out.size(), 464, height, ScreenTours.constructor(464, height, page, "Vertical drum", m, shown),
                        List.of(Column.place(shown.rows(), area.x(), area.y(), door, m, Column.Metrics.COMPACT)), area.bottom()));
        }
        return out;
    }

    /** The constructor's page as the screen builds it, for each form and state that page shows. */
    public static List<ConstructorColumn.Page> pages(ScreenTours.Constructor page) {
        var out = new ArrayList<ConstructorColumn.Page>();
        switch (page) {
            case CHOOSE -> {
                for (var category : Category.values()) out.add(ConstructorColumn.choose(category, Equipment.VERTICAL_DRUM, 16, T));
            }
            case DESIGN, DESIGN_COLUMN -> {
                for (var d : ConstructorColumnTest.designs()) {
                    if (d.form().column() != (page == ScreenTours.Constructor.DESIGN_COLUMN)) continue;
                    var connections = !d.form().designed() && !d.form().column() ? ConstructorColumnTest.connectionCases() : java.util.Collections.<ConstructorColumn.Connections>singletonList(null);
                    for (var c : connections)
                        out.add(ConstructorColumn.design(d, c, d.form().column() ? ConstructorColumnTest.nozzles("feed", true, false, 14) : null, T));
                }
            }
            case PAINT -> {
                out.add(ConstructorColumn.paint(0, T));
                out.add(ConstructorColumn.paint(1, T));
            }
            case TEMPLATES -> {
                out.add(ConstructorColumn.templates(ConstructorColumnTest.TEMPLATES, 0, T));
                out.add(ConstructorColumn.templates(List.of(), 2, T));
            }
            case IMPORTS -> {
                out.add(ConstructorColumn.imports(ConstructorColumnTest.imports(T), T));
                out.add(ConstructorColumn.imports(List.of(), T));
            }
        }
        assertFalse(out.isEmpty(), page.name());
        return out;
    }

    @Test
    void theGaugesTourStands() {
        stands(ScreenTours.GAUGE, doors(246).stream().map(door -> gauge(door, M)).toList());
    }

    @Test
    void thePipelineToolsTourStands() {
        stands(ScreenTours.PIPELINE_TOOL, doors(230).stream().map(door -> pipelineTool(door, M)).toList());
    }

    @Test
    void theDatasheetsTourStands() {
        var sizes = new ArrayList<List<Variant>>();
        for (int window = 320; window <= 480; window += 7)
            for (int tall : new int[]{240, 480}) sizes.add(vessel(Math.min(400, window - 8), Math.min(250, tall - 8), M));
        stands(ScreenTours.VESSEL, sizes);
    }

    @Test
    void theMachinesTourStands() {
        var sizes = new ArrayList<List<Variant>>();
        for (int w = 300; w <= 790; w += 7)
            for (int h : new int[]{FluidGuiLayout.imageHeight(240), FluidGuiLayout.imageHeight(480)}) sizes.add(machine(w, h, M));
        stands(ScreenTours.MACHINE, sizes);
    }

    @Test
    void everyControlPanelPageHasItsTourAndItStands() {
        for (var page : ScreenTours.CONTROL_PAGES) {
            var sizes = new ArrayList<List<Variant>>();
            for (int w = 254; w <= 620; w += 7)
                for (int h : new int[]{240, 400}) {
                    var variants = control(page, w, h, M);
                    if (!variants.isEmpty()) sizes.add(variants);
                }
            stands(ScreenTours.control(page), sizes);
        }
    }

    @Test
    void everyConstructorPageHasItsTourAndItStands() {
        for (var page : ScreenTours.Constructor.values()) {
            var sizes = new ArrayList<List<Variant>>();
            for (int h : new int[]{240, 254, 360, 480}) sizes.add(constructor(page, h, M));
            stands(page.page(), sizes);
        }
        assertTrue(ScreenTours.CONSTRUCTOR_HELP.right() < ConstructorColumn.TITLE_KEY.x(), "the ? key stands left of Change");
        assertTrue(6 + ConstructorColumn.TITLE + 8 <= ScreenTours.CONSTRUCTOR_HELP.x(), "the title plate ends before the ? key");
    }

    /** The column's design page builds a column from a form whose kind is a column; another form, the plain design page. */
    @Test
    void aColumnsDesignPageHasItsOwnTour() {
        assertTrue(Form.values().length > 2);
        assertNotEquals(ScreenTours.Constructor.DESIGN.page(), ScreenTours.Constructor.DESIGN_COLUMN.page());
    }
}
