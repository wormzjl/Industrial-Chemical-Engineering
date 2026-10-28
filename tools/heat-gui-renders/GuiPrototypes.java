import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.AssemblyFlowsheet;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.DeviceColumn;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.WindowScale;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Prototypes of the other screens under the Control tab's rules (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md; owner:
 * "Review other GUI based on above rule, prototype first"): function groups in frames under one- or two-word plates, the
 * sentences in hovers, Off and On then a target where a device runs, latched selectors, no cut text, the light and dark
 * doors. Drawn through the kit (`Column`, `PanelSkin`) in the game font, like {@code HeatDiagramRender}; the screens'
 * data are illustrative. Run from the worktree root (JDK 22+ runs both files of the folder):
 * <pre>java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/GuiPrototypes.java
 *     src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes [minecraft resources jar]</pre>
 */
public final class GuiPrototypes {
    static final int SCALE = HeatDiagramRender.SCALE;

    /** A prototype page: its file name, its size in GUI px, what it draws on a door, and whether it shows scrolled to the end too. */
    record Prototype(String file, int width, int height, BiConsumer<Door, Skin> body, boolean scrolls) {
        Prototype(String file, int width, int height, BiConsumer<Door, Skin> body) {
            this(file, width, height, body, false);
        }
    }

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        for (var p : prototypes()) {
            var titles = new ArrayList<>(List.of("Light panel (3a)", "Dark panel (3c)"));
            var pages = new ArrayList<>(List.of(render(p, PanelSkin.LIGHT, false), render(p, PanelSkin.DARK, false)));
            if (p.scrolls()) {
                titles.addAll(List.of("Light, the column scrolled to its end", "Dark, the column scrolled to its end"));
                pages.addAll(List.of(render(p, PanelSkin.LIGHT, true), render(p, PanelSkin.DARK, true)));
            }
            HeatDiagramRender.grid(out.resolve(p.file() + ".png"), titles, pages);
        }
    }

    static List<Prototype> prototypes() {
        var out = new ArrayList<>(List.of(pump(), compressor(), generator(), tank(), pipe()));
        out.addAll(valve());
        out.addAll(calculator());
        out.addAll(assembly());
        out.addAll(constructor());
        out.addAll(List.of(pipelineTool(), gauge(), thermometer(), vessel(), unit()));
        return out;
    }

    // ---- The column calculator: the shipped CalculatorColumn on its door (GUI_REVIEW.md order 5) ----

    /** The Column page with draw D1 selected and the Results page's profiles, at the smallest split door, a one-column door and a wide one. */
    static List<Prototype> calculator() {
        return List.of(new Prototype("column-calculator", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.COLUMN, CalculatorColumn.Metric.TEMPERATURE), true),
                new Prototype("column-calculator-narrow", 380, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.COLUMN, CalculatorColumn.Metric.TEMPERATURE), true),
                new Prototype("column-calculator-wide", 533, 280, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.COLUMN, CalculatorColumn.Metric.TEMPERATURE)),
                new Prototype("column-calculator-results", 464, 254, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.RESULTS, CalculatorColumn.Metric.TEMPERATURE)),
                new Prototype("column-calculator-results-wide", 533, 280, (d, s) -> calculatorDoor(d, CalculatorColumn.Page.RESULTS, CalculatorColumn.Metric.TRAFFIC)));
    }

    /** The draft's entries as the door shows them. */
    static final Map<String, String> CALCULATOR = Map.ofEntries(Map.entry("s2", "20"), Map.entry("s9", "8.0"), Map.entry("s7", "1.20"), Map.entry("s0", "420"),
            Map.entry("s1", "180.0"), Map.entry("s3", "8"), Map.entry("s4", "45.0"), Map.entry("s6", "2.50"), Map.entry("s5", "12.0"), Map.entry("d0rate", "60.0"),
            Map.entry("d0stage", "14"));

    /** A made-up accepted result down the 22 nodes: temperature, and the four phases' flows (water vapour only in the top half, no aqueous phase below tray 6). */
    static double[][] calculatorProfile(CalculatorColumn.Metric metric) {
        int nodes = 22;
        if (metric == CalculatorColumn.Metric.TEMPERATURE) {
            var t = new double[1][nodes];
            for (int i = 0; i < nodes; i++) t[0][i] = 45 + 190 * Math.pow(i / 21.0, 0.8);
            return t;
        }
        var f = new double[4][nodes];
        for (int i = 0; i < nodes; i++) {
            f[0][i] = i == 0 ? 1050 : 900 + 30 * Math.sin(i / 3.0) + (i >= 8 ? 420 : 0);
            f[1][i] = i == nodes - 1 ? Double.NaN : 1300 - 25 * i;
            f[2][i] = i < 11 ? 180 - 12 * i : Double.NaN;
            f[3][i] = i < 6 ? 60 - 9 * i : Double.NaN;
        }
        return f;
    }

    /**
     * The calculator's door as ColumnCalculatorV3Screen draws it: the title and the theme key; the tabs, the page shown
     * latched; the status once; the footer's divider and the display keys' tray; the glass (the drawing with D1
     * selected, or a profile) left of the door columns, one view over them, scrolled to its end where the door asks;
     * Templates, Solve (primary) and Save; the basis and temperature keys.
     */
    static void calculatorDoor(Door d, CalculatorColumn.Page page, CalculatorColumn.Metric metric) {
        var t = DIALOG;
        d.skin.title(d.c, 10, 10, d.m.cut(HeatDiagramRender.tr("block.icheme.column_calculator_v3"), CalculatorColumn.title(d.w)));
        d.skin.iconKey(d.c, CalculatorColumn.theme(d.w), Skin.Icon.THEME, false);
        var tabs = CalculatorColumn.tabs(page, t);
        var tabSlots = CalculatorColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        var status = CalculatorColumn.status(new CalculatorColumn.StatusInput(false, false, "", "", false, false, false, null, true, false, null), t);
        d.skin.status(d.c, CalculatorColumn.display(d.w), status.text(), status.palette());
        var rows = page == CalculatorColumn.Page.COLUMN
                ? CalculatorColumn.column(new CalculatorColumn.ColumnInput(false, true, "°C", CalculatorColumn.Connection.DRAW, 0, true, true, true, true), t)
                : CalculatorColumn.results(new CalculatorColumn.ResultsInput(false, CalculatorColumn.View.PROFILES, metric, List.of(), 0, 13, 20), t);
        boolean two = rows.two();
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - CalculatorColumn.DIVIDER);
        d.skin.softKeys(d.c, CalculatorColumn.keys(d.w, d.h));
        for (int x : CalculatorColumn.dividers(d.w, d.h, two)) d.skin.vDivider(d.c, x, CalculatorColumn.TOP, d.h - CalculatorColumn.FOOT);
        var box = d.well(CalculatorColumn.glass(d.w, d.h, two));
        d.c.clip(box);
        if (page == CalculatorColumn.Page.COLUMN) {
            var shares = new double[22];
            for (int i = 0; i < shares.length; i++) shares[i] = Math.pow(i / 21.0, 0.8);
            var in = new CalculatorColumn.DrawingInput(20, 7, List.of(new CalculatorColumn.Mark(0, 13, 0)), List.of(new CalculatorColumn.Mark(0, 10, 8)),
                    List.of(new CalculatorColumn.Mark(0, 21, 0)), 13, CalculatorColumn.Connection.DRAW, 0, shares);
            CalculatorColumn.draw(d.c, CalculatorColumn.drawing(in, box, 0, d.m, t, node -> ""), t);
        } else {
            var legend = metric == CalculatorColumn.Metric.TRAFFIC ? List.of(new CalculatorColumn.Legend(t.get("column.traffic.organic"), CalculatorColumn.TRAFFIC[0]),
                    new CalculatorColumn.Legend(t.get("column.traffic.vapor"), CalculatorColumn.TRAFFIC[1]), new CalculatorColumn.Legend(t.get("column.traffic.water"), CalculatorColumn.TRAFFIC[2]),
                    new CalculatorColumn.Legend(t.get("column.traffic.aqueous"), CalculatorColumn.TRAFFIC[3])) : List.<CalculatorColumn.Legend>of();
            String title = metric == CalculatorColumn.Metric.TRAFFIC ? t.get("column.traffic") : t.get("column.temperature", "°C");
            int head = CalculatorColumn.heading(d.c, box, title, legend, d.m);
            CalculatorColumn.profile(d.c, CalculatorColumn.plot(box, head), calculatorProfile(metric),
                    metric == CalculatorColumn.Metric.TRAFFIC ? CalculatorColumn.TRAFFIC : new int[]{Palette.ACCENT});
        }
        d.c.unclip();
        // The door columns in one view (KitDoor): each in its area, the bar right of the last.
        var areas = CalculatorColumn.door(d.w, d.h, two);
        var lists = rows.at(d.w);
        var columns = new ArrayList<Column>();
        int tall = 0;
        for (int i = 0; i < areas.size(); i++) {
            var a = areas.get(i);
            columns.add(Column.place(lists.get(i), a.x(), a.y(), a.w(), d.m, Column.Metrics.COMPACT));
            tall = Math.max(tall, columns.getLast().bounds().h());
        }
        var first = areas.getFirst();
        var view = new ScrollView();
        view.layout(new Rect(first.x() - 2, first.y(), areas.getLast().right() - first.x() + 4, first.h()), CalculatorColumn.bar(d.w), tall);
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        for (var column : columns) {
            column.draw(d.c, -view.offset(), d.skin);
            for (var p : column.rows()) {
                List<Column.Button> specs = p.row() instanceof Column.Buttons b ? b.buttons() : p.row() instanceof Column.Setting st && st.steps() ? List.of(st.minus(), st.plus()) : List.of();
                for (var spec : specs) {
                    var r = p.slots().get(spec.key());
                    if (view.visible(r)) d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
                if (p.row() instanceof Column.Field f && view.visible(p.slots().get(f.key())))
                    d.skin.field(d.c, view.onScreen(p.slots().get(f.key())), CALCULATOR.getOrDefault(f.key(), ""), "", false);
            }
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        var footer = CalculatorColumn.footer(d.w, d.h);
        var keys = CalculatorColumn.footerButtons(new CalculatorColumn.Footer(false, false, true, false, "°C"), t);
        for (int i = 0; i < keys.size(); i++) {
            var spec = keys.get(i);
            if (spec.soft()) d.skin.softKey(d.c, footer.get(i), spec.text(), false);
            else d.skin.button(d.c, footer.get(i), spec.text(), spec.key().equals("solve") ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        }
        if (!d.end && d.skin == PanelSkin.LIGHT)
            System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d: %d door columns %d px wide, %d px tall in a %d px view, glass %d px%n", "calculator " + page.key(), d.w, d.h,
                    areas.size(), first.w(), tall, first.h(), box.w());
    }

    /** A column on the glass as an illustration (the constructor's schematic): the shell, its trays, the condenser and reboiler, the feed and one draw. */
    static void columnDrawing(Door d, Rect box) {
        var c = d.c;
        int cx = box.x() + box.w() / 2, x0 = cx - 18, x1 = cx + 18, y0 = box.y() + 30, y1 = box.bottom() - 34;
        c.fill(x0, y0, x1, y1, 0xFF1C2630);
        c.outline(x0, y0, x1 - x0, y1 - y0, Palette.TEAL);
        for (int i = 1; i < 22; i++) c.hLine(x0 + 2, x1 - 2, y0 + i * (y1 - y0) / 22, 0xFF3C5A66);
        c.outline(cx + 30, box.y() + 6, 36, 16, Palette.TEAL);
        c.text("Cond.", cx + 34, box.y() + 10, Palette.MUTED);
        c.hLine(cx, cx + 30, box.y() + 14, Palette.TEAL);
        c.vLine(cx, box.y() + 14, y0, Palette.TEAL);
        c.outline(cx + 30, y1 + 8, 36, 16, Palette.TEAL);
        c.text("Reb.", cx + 36, y1 + 12, Palette.MUTED);
        c.hLine(cx, cx + 30, y1 + 16, Palette.TEAL);
        c.vLine(cx, y1, y1 + 16, Palette.TEAL);
        int fy = y0 + 8 * (y1 - y0) / 22, dy = y0 + 14 * (y1 - y0) / 22;
        c.hLine(box.x() + 8, x0, fy, Palette.ACCENT);
        c.text("Feed", box.x() + 8, fy - 10, Palette.ACCENT);
        c.hLine(x1, box.right() - 30, dy, Palette.YELLOW);
        c.outline(box.right() - 30, dy - 6, 20, 12, Palette.YELLOW);
        c.text("D1", box.right() - 26, dy - 3, Palette.YELLOW);
        c.outline(x0 - 2, dy - 4, x1 - x0 + 4, 8, 0xFFFFFFFF);
        c.text("Tray 14", box.x() + 8, dy + 4, Palette.TEXT);
    }

    // ---- The assembly check: the shipped AssemblyColumn and AssemblyFlowsheet on the door (GUI_REVIEW.md order 4) ----

    /** A column with a kettle, one side draw and one pumparound. */
    static final ColumnTrays ASSEMBLY = new ColumnTrays(20, 10, List.of(6), List.of(), List.of(new ColumnTrays.Pumparound(8, 6)),
            new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true));

    /** A check of {@link #ASSEMBLY}: an in-line pump where the reflux pump goes (misplaced), no feed valve, the PA cooler's line open; the rest in place. */
    static AssemblyReport assemblyReport() {
        var slots = ColumnBlueprint.of(ASSEMBLY).slots();
        var faults = Map.of(ColumnBlueprint.REFLUX_PUMP, new AssemblyReport.SlotReport(ColumnBlueprint.REFLUX_PUMP, AssemblyReport.State.WRONG_KIND, 1003, AssemblyReport.NONE, "INLINE_PUMP"),
                ColumnBlueprint.FEED_VALVE, new AssemblyReport.SlotReport(ColumnBlueprint.FEED_VALVE, AssemblyReport.State.MISSING, AssemblyReport.NONE, AssemblyReport.NONE, ""),
                ColumnBlueprint.pumparoundCooler(1), new AssemblyReport.SlotReport(ColumnBlueprint.pumparoundCooler(1), AssemblyReport.State.OPEN_END, 1012, AssemblyReport.NONE, "unconnected"));
        var reports = new ArrayList<AssemblyReport.SlotReport>();
        var members = new ArrayList<Long>();
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            var r = faults.getOrDefault(slot, new AssemblyReport.SlotReport(slot, AssemblyReport.State.OK, 1000L + i, AssemblyReport.NONE, ""));
            reports.add(r);
            if (r.member() != AssemblyReport.NONE) members.add(r.member());
        }
        // Each drawn port of a unit in place is connected; a faulted unit's still need their pipes.
        var ports = new ArrayList<AssemblyReport.PortReport>();
        for (var m : AssemblyDiagram.of(ASSEMBLY).ports())
            for (var r : reports)
                if (r.slot().equals(m.slot()) && r.member() != AssemblyReport.NONE)
                    ports.add(new AssemblyReport.PortReport(r.member(), m.role(), Math.max(0, m.ordinal()), r.state() == AssemblyReport.State.OK ? AssemblyReport.PortState.CONNECTED : AssemblyReport.PortState.NEEDED));
        return new AssemblyReport(reports, ports, java.util.Set.of(), members);
    }

    /** Nothing selected after a check; then the reflux pump selected while the next check is queued. */
    static List<Prototype> assembly() {
        return List.of(new Prototype("assembly", 464, 254, (d, s) -> assemblyDoor(d, null, false)),
                new Prototype("assembly-selected", 464, 254, (d, s) -> assemblyDoor(d, ColumnBlueprint.REFLUX_PUMP, true)));
    }

    /**
     * The assembly screen's door as AssemblyScreen draws it: the title and the theme key, the tabs (Structure latched,
     * Operation off until the structure is complete), the status once, the glass with the drawing and the parts list, the
     * door column, the footer under its divider.
     */
    static void assemblyDoor(Door d, Slot selected, boolean queued) {
        var t = DIALOG;
        var report = assemblyReport();
        var diagram = AssemblyDiagram.of(ASSEMBLY);
        var slots = ColumnBlueprint.of(ASSEMBLY).slots();
        d.title(t.get(AssemblyColumn.KEY + "title"));
        var tabs = AssemblyColumn.tabs(false, false, t);
        var tabSlots = AssemblyColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        var status = AssemblyColumn.status(new AssemblyView(AssemblyView.Status.CHECKED, report, AssemblyReport.NONE), at -> "?", t);
        d.skin.status(d.c, AssemblyColumn.display(d.w), status.text(), status.palette());
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - AssemblyColumn.DIVIDER);
        var box = d.well(AssemblyColumn.glass(d.w, d.h, true));
        var split = AssemblyColumn.split(box);
        var sheet = new AssemblyFlowsheet(diagram);
        sheet.fit(split.drawing());
        d.c.clip(split.drawing());
        sheet.draw(d.c, report, selected, x -> AssemblyColumn.text(x, t));
        d.c.unclip();
        var area = split.list();
        d.c.vLine(area.x() - AssemblyColumn.GAP / 2, box.y() - 1, box.bottom(), Palette.LINE);
        var lines = new Rect(area.x(), area.y(), area.w() - ScrollView.BAR - 2, area.h());
        var parts = AssemblyColumn.parts(AssemblyColumn.parts(slots, diagram, report, t), AssemblyColumn.words(t), lines.w(), d.m);
        var listView = new ScrollView();
        listView.layout(area, area.right() - ScrollView.BAR, parts.height());
        d.c.clip(area);
        AssemblyColumn.draw(d.c, parts, lines, 0, selected == null ? -1 : slots.indexOf(selected));
        d.c.unclip();
        listView.drawBar(d.c, d.skin);
        // The door column, narrowed for its bar where it is taller than its area (KitDoor).
        String name = selected == null ? null : AssemblyColumn.text(diagram.part(selected).name(), t);
        var rows = AssemblyColumn.rows(AssemblyColumn.input(slots.size(), report, queued, name, selected != null), t);
        var door = AssemblyColumn.area(d.w, d.h);
        int tall = Column.place(rows, door.x(), door.y(), door.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        d.column(rows, door.x(), door.y(), tall > door.h() ? door.w() - ScrollView.BAR - 4 : door.w(), Map.of());
        var footer = AssemblyColumn.footer(d.w, d.h);
        var keys = AssemblyColumn.footerButtons(t);
        for (int i = 0; i < keys.size(); i++) d.skin.button(d.c, footer.get(i), keys.get(i).text(), Skin.Press.REST, false);
        if (d.skin == PanelSkin.LIGHT)
            System.out.printf(java.util.Locale.ROOT, "%-24s column %3d px in a %d px view, list %d lines in %d px%n", "assembly" + (selected == null ? "" : " selected"), tall, door.h(),
                    parts.lines().size(), lines.h());
    }

    // ---- The equipment constructor: the shipped ConstructorColumn on its door left of the world (GUI_REVIEW.md order 3) ----

    /** A standing drum's sides as seen from the constructor ({@code EquipmentShape.side} 0 to 3): front, left, back, right. */
    static final List<String> STANDING = List.of("front", "left", "back", "right");
    static final String CONSTRUCTOR = ConstructorColumn.KEY;

    static List<Prototype> constructor() {
        var outlet = new ConstructorColumn.Outlet(1, 0, STANDING);
        var drum = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.VERTICAL, 2, 6, 3, 3, 0, 0, new ProcessUnit.Bank(1, 1), null, outlet, 16), DIALOG);
        var lying = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.CROSSWISE, 2.5, 8, 2, 4, -2, 0, new ProcessUnit.Bank(1, 1), null, outlet, 16), DIALOG);
        var connections = ConstructorColumn.connections(new ConstructorColumn.Connections(2, 1, 1, STANDING, "4.5", false, -1, "back", true, false, false,
                new ConstructorColumn.Outlet(0b1011, 0, STANDING)), DIALOG);
        var nozzles = ConstructorColumn.nozzles(new ConstructorColumn.Nozzles(7, 2, new ConstructorColumn.Nozzle("pumparound_draw", 1, true, "18.5",
                new UnitDesign.Line("nozzle.under_out", 12), false, 3, 1), STANDING, 0), DIALOG);
        String volume = DIALOG.get(CONSTRUCTOR + "volume", "20.9", "46.4");
        // A column's design page with Import and Templates, and its Templates page: the bundled templates, the pot off, one of the player's.
        var columnDesign = ConstructorColumn.design(new ConstructorColumn.Design(VesselPlacement.Form.COLUMN, 8, 6, 10, 3, 0, 0, new ProcessUnit.Bank(1, 1),
                new ConstructorColumn.Imported(8, 42, 38), outlet, 16), DIALOG);
        var templates = new ArrayList<ConstructorColumn.Template>();
        for (var name : List.of("Bonga", "Cold Lake Blend", "Dalia", "Tia Juana Light (default)", "Upper Zakum", "WTI Light - Export"))
            templates.add(new ConstructorColumn.Template(name, true, DIALOG.get(CONSTRUCTOR + "template.bundled", name)));
        templates.add(new ConstructorColumn.Template("Two-tray pot", false, DIALOG.get(CONSTRUCTOR + "template.unusable", "Two-tray pot", "a column needs a tray")));
        templates.add(new ConstructorColumn.Template("My vacuum column", true, DIALOG.get(CONSTRUCTOR + "template.own", "My vacuum column")));
        var templatePage = ConstructorColumn.templates(templates, 0, DIALOG);
        return List.of(
                new Prototype("equipment-constructor-column-design", 464, 254, (d, s) -> {
                    constructorDoor(d, columnDesign, "Distillation column", "change", DIALOG.get(CONSTRUCTOR + "volume", "1696.5", "792.7"), Palette.ACCENT, Map.of());
                    columnDrawing(d, d.well(new Rect(d.w - 16 - 140, 16, 152, d.h - 32)));
                }, true),
                new Prototype("equipment-constructor-templates", 464, 254, (d, s) -> constructorDoor(d, templatePage, "Distillation column", "back", null, 0, Map.of()), true),
                new Prototype("equipment-constructor-choose", 464, 254, (d, s) -> constructorDoor(d, ConstructorColumn.choose(ConstructorColumn.Category.VESSELS,
                        ConstructorColumn.Equipment.VERTICAL_DRUM, DIALOG), "Equipment constructor", "back", null, 0, Map.of()), true),
                new Prototype("equipment-constructor", 464, 254, (d, s) -> constructorDoor(d, drum, "Vertical drum", "change", volume, Palette.ACCENT, Map.of()), true),
                new Prototype("equipment-constructor-lying", 464, 254, (d, s) -> constructorDoor(d, lying, "Horizontal drum", "change",
                        DIALOG.get(CONSTRUCTOR + "blocked", 3), Palette.WARN, Map.of()), true),
                new Prototype("equipment-constructor-nozzles", 464, 254, (d, s) -> constructorDoor(d, connections, "Vertical drum", null,
                        DIALOG.get(CONSTRUCTOR + "clash.liquid", 2), Palette.WARN, Map.of()), true),
                new Prototype("equipment-constructor-column", 464, 254, (d, s) -> {
                    constructorDoor(d, nozzles, "Distillation column", null, DIALOG.get(CONSTRUCTOR + "volume", "1696.5", "792.7"), Palette.ACCENT, Map.of());
                    // The schematic on its glass beside the world (the screen draws the calculator's; here an illustration).
                    columnDrawing(d, d.well(new Rect(d.w - 16 - 140, 16, 152, d.h - 32)));
                }, true),
                new Prototype("equipment-constructor-paint", 464, 254, (d, s) -> {
                    var column = constructorDoor(d, ConstructorColumn.paint(0, DIALOG), "Vertical drum", null, volume, Palette.ACCENT, Map.of("custom", "A1A1A0"));
                    swatches(d, DialogColumns.palette(column, Paint.PALETTE.size()), 0);
                }, true));
    }

    /** The palette as PaintSwatch draws it: the edited colour ({@code chosen}) outlined in white. */
    static void swatches(Door d, List<Rect> swatches, int chosen) {
        for (int i = 0; i < swatches.size(); i++) {
            var r = swatches.get(i);
            boolean edited = i == chosen;
            d.c.fill(r.x(), r.y(), r.right(), r.bottom(), edited ? 0xFFFFFFFF : Palette.LINE);
            if (edited) d.c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
            int inset = edited ? 2 : 1;
            d.c.fill(r.x() + inset, r.y() + inset, r.right() - inset, r.bottom() - inset, 0xFF000000 | Paint.PALETTE.get(i).rgb());
        }
    }

    /**
     * The constructor's door as EquipmentConstructorScreen draws it: the world beside it; the title and its key
     * ({@code key}: Change or Back, or none); the page's column in its view, narrowed for its bar where it is taller,
     * scrolled to its end where the door asks; the message display; the footer, Build the primary key. Fields show
     * {@code values}. Returns the column as placed, unscrolled (where the column scrolls, the palette is not drawn).
     */
    static Column constructorDoor(Door d, ConstructorColumn.Page page, String title, String key, String status, int tone, Map<String, String> values) {
        int panel = ConstructorColumn.PANEL;
        d.c.fill(panel, 0, d.w, d.h, 0xFF4A6B3A);
        d.c.text("(the world and the ghost)", panel + 30, d.h / 2, 0xFFE0E0E0);
        d.skin.page(d.c, new Rect(0, 0, panel, d.h));
        d.skin.title(d.c, 10, 10, title);
        if (key != null) d.skin.button(d.c, ConstructorColumn.TITLE_KEY, DIALOG.get(CONSTRUCTOR + key), Skin.Press.REST, false);
        var area = ConstructorColumn.area(d.h, page.display());
        int tall = Column.place(page.rows(), area.x(), area.y(), area.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        int width = tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w();
        var column = Column.place(page.rows(), area.x(), area.y(), width, d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(area.x() - 2, area.y(), width + 4, area.h()), area.x() + width + 3, column.bounds().h());
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        column.draw(d.c, -view.offset(), d.skin);
        for (var p : column.rows()) {
            List<Column.Button> specs = p.row() instanceof Column.Buttons b ? b.buttons() : p.row() instanceof Column.Setting st && st.steps() ? List.of(st.minus(), st.plus()) : List.of();
            for (var spec : specs) {
                var r = p.slots().get(spec.key());
                if (view.visible(r)) d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
            }
            if (p.row() instanceof Column.Field f && view.visible(p.slots().get(f.key())))
                d.skin.field(d.c, view.onScreen(p.slots().get(f.key())), values.getOrDefault(f.key(), ""), "", false);
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        if (page.display()) d.skin.status(d.c, ConstructorColumn.display(d.h), status, tone);
        var slots = ConstructorColumn.footer(d.h, page.footer().size());
        for (int i = 0; i < page.footer().size(); i++) {
            var spec = page.footer().get(i);
            d.skin.button(d.c, slots.get(i), spec.text(), spec.key().equals("build") ? Skin.Press.PRIMARY : spec.active() ? Skin.Press.REST : Skin.Press.OFF, false);
        }
        if (!d.end && d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-24s column %3d px in a %d px view%n", title, column.bounds().h(), area.h());
        return view.offset() == 0 ? column : Column.place(List.of(), area.x(), area.y(), width, d.m, Column.Metrics.COMPACT);
    }

    // ---- Small dialogs: the shipped DialogColumns on the door (the gauge, the pipeline tool, the vessel's datasheet) ----

    static final HeatControlColumn.Texts DIALOG = (key, a) -> HeatDiagramRender.tr("gui.icheme." + key, a);

    /** A dialog's height as its screen sizes it: its column with every line it may show, then the footer. */
    static int dialogHeight(List<Column.Row> tallest, int width) {
        return DialogColumns.TOP + Column.place(tallest, 10, DialogColumns.TOP, width - 20, new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure(), Column.Metrics.COMPACT).bounds().h()
                + DialogColumns.FOOTER;
    }

    /** The dialog's footer push buttons, as DialogColumns places them; the last of two is the confirm one. */
    static void dialogFooter(Door d, String... texts) {
        var slots = DialogColumns.footer(d.w, d.h, texts.length);
        for (int i = 0; i < texts.length; i++)
            d.skin.button(d.c, slots.get(i), texts[i], texts.length == 2 && i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
    }

    static Prototype pipelineTool() {
        var tallest = DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, false), DIALOG);
        return new Prototype("pipeline-tool", 230, dialogHeight(tallest, 230), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Pipe laying tool");
            var column = d.column(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, true), DIALOG), 10, DialogColumns.TOP, d.w - 20,
                    Map.of("bore", "0.150", "custom", "A1A1A0"));
            // The palette as PaintSwatch draws it: the edited colour outlined in white.
            var swatches = DialogColumns.palette(column, Paint.PALETTE.size());
            for (int i = 0; i < swatches.size(); i++) {
                var r = swatches.get(i);
                boolean chosen = i == 0;
                d.c.fill(r.x(), r.y(), r.right(), r.bottom(), chosen ? 0xFFFFFFFF : Palette.LINE);
                if (chosen) d.c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
                int inset = chosen ? 2 : 1;
                d.c.fill(r.x() + inset, r.y() + inset, r.right() - inset, r.bottom() - inset, 0xFF000000 | Paint.PALETTE.get(i).rgb());
            }
            dialogFooter(d, "Cancel", "Save");
        });
    }

    static Prototype gauge() {
        var units = List.of("Pa", "kPa", "bar", "MPa");
        var tallest = DialogColumns.gauge(new DialogColumns.Gauge(true, units, 2, true, false), DIALOG);
        return new Prototype("gauge", 246, dialogHeight(tallest, 246), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Pressure gauge");
            d.column(DialogColumns.gauge(new DialogColumns.Gauge(true, units, 2, true, true), DIALOG), 10, DialogColumns.TOP, d.w - 20, Map.of("minimum", "0.00", "maximum", "10.00"));
            dialogFooter(d, "Cancel", "Save");
        });
    }

    /** A thermometer whose drafts form no range: the warning line under the groups, Save waiting. */
    static Prototype thermometer() {
        var tallest = DialogColumns.gauge(new DialogColumns.Gauge(false, List.of("°C", "K"), 0, false, false), DIALOG);
        return new Prototype("gauge-thermometer", 246, dialogHeight(tallest, 246), (d, s) -> {
            d.skin.title(d.c, 10, 10, "Thermometer");
            d.column(tallest, 10, DialogColumns.TOP, d.w - 20, Map.of("minimum", "200", "maximum", "20"));
            var slots = DialogColumns.footer(d.w, d.h, 2);
            d.skin.button(d.c, slots.get(0), "Cancel", Skin.Press.REST, false);
            d.skin.button(d.c, slots.get(1), "Save", Skin.Press.OFF, false);
        });
    }

    static DialogColumns.Size size(String key, String value, String unit, boolean chosen) {
        return new DialogColumns.Size(key, value, unit, chosen);
    }

    /** A datasheet: the door's sizes and switch, the glass's notes and nozzles, as VesselScreen draws them. */
    static void datasheet(Door d, String title, DialogColumns.Sheet sheet, boolean moving) {
        d.skin.title(d.c, 10, 10, title);
        var values = new java.util.HashMap<String, String>();
        for (var size : sheet.sizes()) values.put(size.key(), size.value());
        d.column(DialogColumns.vessel(sheet.sizes(), moving, true, DIALOG), 10, DialogColumns.TOP, 150, values);
        var box = d.well(new Rect(166, 25, d.w - 171, d.h - 60));
        var glass = DialogColumns.glass(sheet, false, box.w() - ScrollView.BAR - 2, d.m, DIALOG);
        d.c.clip(box);
        DialogColumns.draw(d.c, glass, new Rect(box.x(), box.y(), box.w() - ScrollView.BAR - 2, box.h()), 0);
        d.c.unclip();
        dialogFooter(d, "Done");
    }

    static Prototype vessel() {
        var sheet = new DialogColumns.Sheet(List.of(size("diameter", "2.00", "m", true), size("length", "6.00", "m", true), size("overall", "7.10", "m", false),
                size("top", "10.1", "m", false), size("volume", "20.9", "m³", false), size("area", "46.4", "m²", false)),
                List.of(DIALOG.get("vessel.kind.skirt"), DIALOG.get("vessel.skirt", 3), DIALOG.get("vessel.cells", 112, 36), DIALOG.get("vessel.controller", "120, 64, -40")),
                null, List.of(new DialogColumns.Nozzle("Feed", "Bulk", "Left"), new DialogColumns.Nozzle("Vapour outlet", "Vapour", "Top"),
                new DialogColumns.Nozzle("Liquid outlet", "Liquid", "Front"), new DialogColumns.Nozzle("Side connection", "Bulk", "Right")));
        return new Prototype("vessel-datasheet", 400, 250, (d, s) -> datasheet(d, "Drum", sheet, false));
    }

    /** A unit with moving parts and long nozzle names: the roles wrap in their column; a blocked count in the warning tone. */
    static Prototype unit() {
        var sheet = new DialogColumns.Sheet(List.of(size("overall", "14.0", "m", false), size("top", "5.20", "m", false)),
                List.of(DIALOG.get("vessel.kind.air_cooler"), DIALOG.get("vessel.bay", "3.0", "9.0", "24.3"), DIALOG.get("vessel.bank", 2, 1),
                        DIALOG.get("vessel.fans", 2, "2.4", 45), DIALOG.get("vessel.air_side", "1210", "60"), DIALOG.get("vessel.unit_support.air_cooler", 4),
                        DIALOG.get("vessel.cells", 640, 210), DIALOG.get("vessel.controller", "120, 64, -40")),
                DIALOG.get("vessel.blocked", 3), List.of(new DialogColumns.Nozzle("Process inlet", "Bulk", "Front"), new DialogColumns.Nozzle("Process outlet", "Bulk", "Back"),
                new DialogColumns.Nozzle("Regeneration gas outlet", "Vapour", "Bottom")));
        return new Prototype("vessel-datasheet-unit", 400, 250, (d, s) -> datasheet(d, "Air cooler", sheet, true));
    }

    // ---- FluidDeviceScreen's device pages: the shipped DeviceColumn on the door, the tables on the HMI glass ----

    static final HeatControlColumn.Texts TEXTS = (key, a) -> HeatDiagramRender.tr("gui.icheme.fluid." + key, a);

    /** A phase split, its four amounts on a basis; mole leaves custom solids out where some are held. */
    static DeviceColumn.Phases phases(String basis, double... amounts) {
        return new DeviceColumn.Phases(basis, amounts, false, TEXTS.get("phase_ratio.hint"));
    }

    /**
     * A device's page as FluidDeviceScreen draws it (GUI_TOOLKIT_PLAN.md section 14): the door column from x 10 to
     * {@code FluidGuiLayout.side}, its view and bar, the divider, the tables' glass; scrolled to the end where the door
     * asks for it. Fields show {@code drafts} by key. Returns the glass's inner box.
     */
    static Rect device(Door d, DeviceColumn.Input in, Map<String, String> drafts) {
        int side = FluidGuiLayout.side(d.w), top = FluidGuiLayout.DOOR_TOP, bottom = d.h - FluidGuiLayout.DOOR_FOOT;
        var column = Column.place(DeviceColumn.rows(in, TEXTS), 10, top, side - 10, d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(8, top, side - 6, bottom - top), side + 3, column.bounds().h());
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        column.draw(d.c, -view.offset(), d.skin);
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) d.skin.field(d.c, view.onScreen(r), drafts.getOrDefault(f.key(), ""), "", false);
            } else if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (!view.visible(r)) continue;
                    if (spec.soft()) d.skin.softKey(d.c, view.onScreen(r), spec.text(), false);
                    else d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        if (!d.end && d.skin == PanelSkin.LIGHT) System.out.printf(java.util.Locale.ROOT, "%-16s column %3d px in a %d px view%n", in.kind(), column.bounds().h(), bottom - top);
        d.skin.vDivider(d.c, side + 13, top, bottom);
        return d.well(new Rect(side + 15, 72, d.w - side - 20, d.h - 50 - 72));
    }

    /** A composition on the glass: the phase tabs latched, the table. */
    static void contents(Door d, Rect box, int phase, List<String[]> table) {
        String[] phases = {"Org", "Aq", "Gas", "Solid"};
        int n = phases.length, tw = (box.w() - 3 * (n - 1)) / n;
        for (int i = 0; i < n; i++) d.skin.button(d.c, new Rect(box.x() + i * (tw + 3), box.y(), tw, 16), phases[i], i == phase ? Skin.Press.CURRENT : Skin.Press.REST, false);
        d.table(new Rect(box.x(), box.y() + 20, box.w(), box.h() - 20), new int[]{box.w() - 120, 60, 60}, table);
    }

    static DeviceColumn.Meter meter(String key, String value, String unit) {
        return new DeviceColumn.Meter(key, value, unit, null);
    }

    /** The window the device pages are drawn for, in screen px: {@code -Dwindow=1920x1080} (the default). */
    static final int[] WINDOW = java.util.Arrays.stream(System.getProperty("window", "1920x1080").split("x")).mapToInt(Integer::parseInt).toArray();

    /**
     * A device's page as a window {@code fw} by {@code fh} screen px shows it: at the scale FluidDeviceScreen takes
     * (WindowScale with the page's column as its fit), its image 8 px inside the window's GUI size; drawn scrolled to its
     * end too where even that scale leaves the column a bar.
     */
    static Prototype devicePage(String file, int fw, int fh, DeviceColumn.Input in, BiConsumer<Door, Skin> body) {
        var m = new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure();
        var rows = DeviceColumn.rows(in, TEXTS);
        WindowScale.Fit fit = (w, h) -> FluidGuiLayout.fits(w, h, iw -> Column.place(rows, 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(iw) - 10, m, Column.Metrics.COMPACT).bounds().h());
        int s = WindowScale.of(fw, fh, false, FluidGuiLayout.HEIGHT, fit), gw = -Math.floorDiv(-fw, s), gh = -Math.floorDiv(-fh, s);
        System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d window: scale %d (the design size's %d, the minimum %d), %d by %d GUI px%n", file, fw, fh, s, WindowScale.of(fw, fh, false), WindowScale.minimum(fw, fh, false), gw, gh);
        return new Prototype(file, FluidGuiLayout.imageWidth(gw), FluidGuiLayout.imageHeight(gh), body, !fit.fits(gw, gh));
    }

    static Prototype pump() {
        var in = new DeviceColumn.Input(Kind.PUMP, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 1, 0, 0), List.of(meter("temperature", "25.0", "°C"), meter("suction_pressure", "1.20", "bar"),
                meter("throughput", "554.8", "kmol/h"), meter("pressure_change", "2.40", "bar")));
        return devicePage("fluid-pump", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Centrifugal pump");
            d.tabs(0, "Overview", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("volumeFlow", "12.0", "maximumAddedPressure", "5.00"));
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Water", "92.1", "511"}, new String[]{"Ethanol", "7.9", "43.8"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        });
    }

    /** A compressor's page: its readings as {@code meters} give them. */
    static Prototype compressor(String file, int fw, int fh, List<DeviceColumn.Meter> meters) {
        var in = new DeviceColumn.Input(Kind.COMPRESSOR, true, true, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("volume", 0, 0, 1, 0), meters);
        return devicePage(file, fw, fh, in, (d, s) -> {
            d.title("Centrifugal compressor");
            d.tabs(0, "Overview", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("volumeFlow", "1200", "maximumPressureRatio", "4.00"));
            contents(d, box, 2, List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Methane", "92.0", "46.0"}, new String[]{"Ethane", "8.0", "4.0"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        });
    }

    /** A compressor's readings: its pressure ratio, the pressure change on that meter's hover (owner 2026-10-28, option A). */
    static List<DeviceColumn.Meter> compressorMeters() {
        return List.of(meter("temperature", "25.0", "°C"), meter("suction_pressure", "1.01", "bar"), meter("throughput", "50.0", "kmol/h"),
                new DeviceColumn.Meter("pressure_ratio", "4.00", "", HeatDiagramRender.tr("gui.icheme.fluid.pressure_ratio.hint", "3.04 bar")));
    }

    static Prototype compressor() {
        return compressor("fluid-compressor", WINDOW[0], WINDOW[1], compressorMeters());
    }

    static Prototype generator() {
        var in = new DeviceColumn.Input(Kind.GENERATOR, true, true, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false, null, null, List.of(),
                phases("mole", 1, 0, 0.1, 0), List.of(meter("temperature", "25.0", "°C"), meter("pressure", "2.00", "bar"), meter("throughput", "0.2", "kmol/s")));
        return devicePage("fluid-generator", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Fluid source");
            d.tabs(0, "Overview", "Composition", "Solids");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("pressure", "2.00", "temperature", "25.0"));
            contents(d, box, 0, List.of(new String[]{"Component", "Mole %", "kmol/s"}, new String[]{"n-Hexane", "61.0", "0.1"}, new String[]{"n-Heptane", "39.0", "0.1"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/s");
        });
    }

    /** The valve, the tallest page, in a {@link #WINDOW} window; and in a 1366 by 768 one, where scale 2 still leaves it a bar. */
    static List<Prototype> valve() {
        return List.of(valve("fluid-valve", WINDOW[0], WINDOW[1]), valve("fluid-valve-1366x768", 1366, 768));
    }

    static Prototype valve(String file, int fw, int fh) {
        var in = new DeviceColumn.Input(Kind.VALVE, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.MASS_FLOW, new DeviceColumn.Opening(0.63, "Holding the set point"),
                false, null, null, List.of(), phases("volume", 1, 0, 0, 0), List.of(meter("temperature", "80.0", "°C"), meter("inlet_pressure", "3.10", "bar"),
                meter("throughput", "2.5", "kg/s"), meter("pressure_change", "-1.10", "bar")));
        return devicePage(file, fw, fh, in, (d, s) -> {
            d.title("Control valve");
            d.tabs(0, "Overview");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("massFlow", "2.50"));
            contents(d, box, 0, List.of(new String[]{"Component", "Mass %", "kg/s"}, new String[]{"Toluene", "100.0", "2.5"}));
            d.footer(2, "Close", "Apply", "Mass", "°C", "bar", "/s");
        });
    }

    static Prototype tank() {
        var in = new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/s", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.63, "25200.0", "40000.0"), null, List.of(), phases("volume", 0, 25.2, 14.8, 0),
                List.of(meter("temperature", "20.0", "°C"), meter("top_pressure", "1.01", "bar"), meter("bottom_pressure", "1.25", "bar"), meter("net_flow", "-0.4", "kg/s"),
                        meter("mass", "25150.0", "kg")));
        return devicePage("fluid-tank", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Atmospheric tank");
            d.tabs(0, "Overview", "Outlets", "Datasheet");
            d.status(Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of());
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"Water", "99.2", "1390"}, new String[]{"Salt", "0.8", "11.2"}));
            d.footer(1, "Close", "Mole", "°C", "bar", "/s");
        });
    }

    static Prototype pipe() {
        var in = new DeviceColumn.Input(Kind.PIPE, true, false, true, "kPa", "°C", "/min", DeviceColumn.Valve.NONE, null, false, null,
                HeatDiagramRender.tr("gui.icheme.fluid.junction", 3), List.of(), phases("volume", 0, 1, 0, 0),
                List.of(meter("temperature", "35.0", "°C"), meter("pressure", "180.0", "kPa"), meter("throughput", "120.0", "kmol/min"),
                        meter("velocity_lowest", "0.8", "m/s"), meter("velocity_highest", "1.6", "m/s"), meter("gradient", "0.4", "kPa/m")));
        return devicePage("fluid-pipe", WINDOW[0], WINDOW[1], in, (d, s) -> {
            d.title("Pipe");
            d.tabs(0, "Overview", "Connections");
            d.status(Door.tr("gui.icheme.fluid.reversed_interval") + " " + Door.tr("gui.icheme.fluid.flowing"), Palette.MUTED);
            var box = device(d, in, Map.of("diameter", "0.10"));
            contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol/min"}, new String[]{"Water", "100.0", "120.0"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "kPa", "/min");
        });
    }

    static BufferedImage render(Prototype p, Skin skin, boolean end) {
        var image = new BufferedImage((p.width() + 8) * SCALE, (p.height() + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new HeatDiagramRender.Java2dCanvas(g);
        var door = new Door(canvas, skin, p.width(), p.height(), end);
        skin.page(canvas, new Rect(0, 0, p.width(), p.height()));
        p.body().accept(door, skin);
        g.dispose();
        return image;
    }

    /** A door being drawn: the screen's frame parts and the kit's rows with their widgets. */
    static final class Door {
        final Canvas c;
        final Skin skin;
        final int w, h;
        final Measure m;
        /** Whether a scrolled column is drawn scrolled to its end. */
        final boolean end;

        Door(Canvas c, Skin skin, int w, int h, boolean end) {
            this.end = end;
            this.c = c;
            this.skin = skin;
            this.w = w;
            this.h = h;
            this.m = c.measure();
        }

        static String tr(String key, Object... args) {
            return HeatDiagramRender.tr(key, args);
        }

        /** The title plate and the theme key with its contrast sign, as FluidDeviceScreen draws them. */
        void title(String text) {
            skin.title(c, 10, 10, text);
            skin.iconKey(c, new Rect(w - 28, 5, 18, 18), Skin.Icon.THEME, false);
        }

        /** Tabs side by side at y 29, {@code current} latched. */
        void tabs(int current, String... names) {
            int n = names.length, tw = (w - 20 - 4 * (n - 1)) / n;
            for (int i = 0; i < n; i++)
                skin.button(c, new Rect(10 + i * (tw + 4), 29, tw, 18), names[i], i == current ? Skin.Press.CURRENT : Skin.Press.REST, false);
        }

        void status(String text, int palette) {
            skin.status(c, new Rect(10, 59, w - 20, 9), text, palette);
        }

        /**
         * The footer under a divider: its first {@code actions} texts are push buttons at the left (the second the
         * confirm one), the rest display keys (units, bases) on their strip at the right, as FluidDeviceScreen draws them.
         */
        void footer(int actions, String... texts) {
            skin.hDivider(c, 8, w - 8, h - 35);
            int keys = texts.length - actions, kw = 40, strip = keys * kw + Math.max(0, keys - 1) * 6;
            int bw = (w - 20 - (keys > 0 ? strip + 14 : 0) - 4 * (actions - 1)) / actions;
            for (int i = 0; i < actions; i++)
                skin.button(c, new Rect(10 + i * (bw + 4), h - 27, bw, 18), texts[i], i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
            if (keys == 0) return;
            int x = w - 10 - strip;
            skin.softKeys(c, new Rect(x - 4, h - 31, strip + 8, 26));
            for (int i = 0; i < keys; i++) skin.softKey(c, new Rect(x + i * (kw + 6), h - 27, kw, 18), texts[actions + i], false);
        }

        /** The HMI screen's glass in {@code r}; returns the box inside it. */
        Rect well(Rect r) {
            return skin.well(c, r);
        }

        /**
         * {@code rows} placed at ({@code x}, {@code y}), {@code width} wide, at compact heights, drawn with their widgets:
         * fields show {@code values} (by key, blank shows the hint "off"), buttons their latched or acting state.
         */
        Column column(List<? extends Column.Row> rows, int x, int y, int width, Map<String, String> values) {
            var column = Column.place(rows, x, y, width, m, Column.Metrics.COMPACT);
            column.draw(c, 0, skin);
            for (var p : column.rows()) {
                if (p.row() instanceof Column.Field f) skin.field(c, p.slots().get(f.key()), values.getOrDefault(f.key(), ""), "off", false);
                else if (p.row() instanceof Column.Buttons b)
                    for (var spec : b.buttons())
                        skin.button(c, p.slots().get(spec.key()), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                else if (p.row() instanceof Column.Setting st && st.steps())
                    for (var spec : List.of(st.minus(), st.plus())) skin.button(c, p.slots().get(spec.key()), spec.text(), Skin.Press.REST, false);
            }
            return column;
        }

        /** A table on the HMI glass: a header row, then rows; numbers right-aligned in the last columns. */
        void table(Rect box, int[] widths, List<String[]> rows) {
            int y = box.y() + 2;
            for (int r = 0; r < rows.size() && y + 10 <= box.bottom(); r++, y += 12) {
                int x = box.x() + 3;
                for (int i = 0; i < widths.length; i++) {
                    String cell = m.cut(rows.get(r)[i], widths[i] - 4);
                    int colour = r == 0 ? Palette.MUTED : i == 0 ? Palette.TEXT : Palette.ACCENT;
                    c.text(cell, i == 0 ? x : x + widths[i] - 4 - m.width(cell), y, colour);
                    x += widths[i];
                }
                if (r == 0) c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
            }
        }

        static Column.Group group(String title, String hint, Column.Row... rows) {
            return new Column.Group(title, hint, List.of(rows));
        }

        static Column.Buttons switchRow(String off, String on, boolean isOn) {
            return new Column.Buttons(List.of(new Column.Button("off", off, isOn, null, !isOn), new Column.Button("on", on, !isOn, null, isOn)));
        }

        static Column.Field field(String key, String label, String unit) {
            return new Column.Field(key, label, unit, null);
        }

        /** A meter: a value the plant reads, drawn by the skin as an LED meter (an entry is an LCD). */
        static Column.Reading reading(String key, String label, String value, String unit) {
            return new Column.Reading(key, label, value, unit, null);
        }

        static List<Column.Row> rows(Column.Row... rows) {
            return new ArrayList<>(List.of(rows));
        }
    }
}
