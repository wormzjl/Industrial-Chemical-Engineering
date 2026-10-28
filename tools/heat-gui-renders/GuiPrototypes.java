import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
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

    /** A prototype page: its file name, its size in GUI px, and what it draws on a door. */
    record Prototype(String file, int width, int height, BiConsumer<Door, Skin> body) {
    }

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        for (var p : prototypes())
            HeatDiagramRender.grid(out.resolve(p.file() + ".png"), List.of("Light panel (3a)", "Dark panel (3c)"), List.of(render(p, PanelSkin.LIGHT), render(p, PanelSkin.DARK)));
    }

    static List<Prototype> prototypes() {
        return List.of(pump(), generator(), valve(), tank(), column(), assembly(), constructor(), pipelineTool(), gauge(), vessel());
    }

    // ---- The column calculator: the drawing on the glass, the inputs in groups on the door ----

    static Prototype column() {
        return new Prototype("column-calculator", 640, 360, (d, s) -> {
            d.title("Column calculator");
            d.tabs(0, "Column", "Feed", "Results", "Equipment");
            d.status("Solved: 22 trays, condenser 45.0 °C, reboiler 12.0 MW", Palette.ACCENT);
            int top = 74, bottom = d.h - 38, glassRight = 250;
            var box = d.well(new Rect(5, top - 3, glassRight - 5, bottom - top + 3));
            columnDrawing(d, box);
            d.skin.vDivider(d.c, glassRight + 4, top, bottom);
            int x = glassRight + 10, cw = (d.w - x - 22) / 2;
            d.column(Door.rows(
                    Door.group("Column", null, Door.field("trays", "Trays", ""), Door.field("dia", "Diameter", "m"), Door.field("top", "Top P", "bar")),
                    Door.group("Feed", null, Door.field("feed", "Flow", "kmol/h"), Door.field("ft", "Temperature", "°C"), Door.field("tray", "Tray", ""))),
                    x, top, cw, Map.of("trays", "22", "dia", "8.0", "top", "1.20", "feed", "420", "ft", "180.0", "tray", "8"));
            d.skin.vDivider(d.c, x + cw + 5, top, bottom);
            d.column(Door.rows(
                    Door.group("Condenser", null, Door.field("ct", "Outlet", "°C"), Door.field("reflux", "Reflux", "")),
                    Door.group("Reboiler", null, Door.field("duty", "Duty", "MW")),
                    Door.group("Draw D1", "The selected side draw: click another on the drawing.", Door.field("draw", "Flow", "kmol/h"), Door.field("dtray", "Tray", ""),
                            new Column.Buttons(List.of(new Column.Button("remove", "Remove", true, null))))),
                    x + cw + 12, top, cw, Map.of("ct", "45.0", "reflux", "2.50", "duty", "12.0", "draw", "60.0", "dtray", "14"));
            d.footer(3, "Templates", "Solve", "Save", "°C");
        });
    }

    /** A column on the glass: the shell, its trays, the condenser and reboiler, the feed and one draw. */
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

    // ---- The assembly check: the drawing and the parts on the glass, the check and the go-to on the door ----

    static Prototype assembly() {
        return new Prototype("assembly", 464, 254, (d, s) -> {
            d.title("Distillation column");
            d.tabs(0, "Structure", "Operation");
            d.status("Checked: 9 connected, 1 misplaced, 2 missing", Palette.WARN);
            int top = 74, bottom = d.h - 38, side = 150;
            var box = d.well(new Rect(5, top - 3, d.w - side - 15, bottom - top + 3));
            int lx = box.x() + box.w() / 2 + 6;
            var c = d.c;
            c.outline(box.x() + 30, box.y() + 10, 30, box.h() - 20, Palette.TEAL);
            c.text("Column", box.x() + 26, box.bottom() - 8, Palette.MUTED);
            c.outline(box.x() + 80, box.y() + 14, 28, 14, Palette.TEAL);
            c.outline(box.x() + 80, box.bottom() - 30, 28, 14, Palette.YELLOW);
            c.outline(box.x() + 120, box.y() + 60, 20, 12, Palette.RED);
            c.vLine(lx - 6, box.y(), box.bottom(), Palette.LINE);
            String[][] parts = {{"Condenser", "Connected"}, {"Reflux drum", "Connected"}, {"Reflux pump", "Misplaced"}, {"Reboiler", "Connected"}, {"Feed valve", "Missing"}, {"Flowmeter", "Missing"}};
            int[] colours = {Palette.TEAL, Palette.TEAL, Palette.YELLOW, Palette.TEAL, Palette.RED, Palette.RED};
            for (int i = 0; i < parts.length; i++) {
                int y = box.y() + 2 + i * 12;
                c.fill(lx, y + 1, lx + 6, y + 7, colours[i]);
                c.text(parts[i][0], lx + 10, y, Palette.TEXT);
                c.text(parts[i][1], box.right() - 3 - d.m.width(parts[i][1]), y, colours[i]);
            }
            d.column(Door.rows(
                    Door.group("Check", "Checks the parts against the design. The engine answers within 5 s.",
                            new Column.Buttons(List.of(new Column.Button("validate", "Check parts", true, null))), new Column.Line("12 parts, 3 to fix", Palette.TEXT)),
                    Door.group("Selected", null, new Column.Line("Reflux pump", Palette.TEXT),
                            new Column.Buttons(List.of(new Column.Button("open", "Open unit", true, null))))),
                    d.w - side - 4, top, side - 6, Map.of());
            d.footer(2, "Close", "Datasheet");
        });
    }

    // ---- The equipment constructor: its panel on the left of the world; steppers on 7-segment meters ----

    static Prototype constructor() {
        return new Prototype("equipment-constructor", 464, 254, (d, s) -> {
            int panel = 200;
            d.c.fill(panel, 0, d.w, d.h, 0xFF4A6B3A);
            d.c.text("(the world and the ghost)", panel + 80, d.h / 2, 0xFFE0E0E0);
            d.skin.page(d.c, new Rect(0, 0, panel, d.h));
            d.skin.title(d.c, 10, 10, "Vertical drum on a skirt");
            var col = d.column(Door.rows(
                    Door.group("Size", null, Door.field("dia", "Diameter", "m"), Door.field("len", "Length", "m"), Door.field("skirt", "Skirt", "m")),
                    Door.group("Place", "Where it stands from you: forward, right and up, in blocks.", Door.field("fwd", "Forward", ""), Door.field("right", "Right", ""), Door.field("up", "Up", ""))),
                    8, 28, panel - 16, Map.of("dia", "2.0", "len", "6.0", "skirt", "2.0", "fwd", "3", "right", "0", "up", "0"));
            // Steppers: [-] and [+] before each meter (shift: steps of 5, in the hover).
            for (var p : col.rows())
                if (p.row() instanceof Column.Field f) {
                    var box = p.slots().get(f.key());
                    d.skin.button(d.c, new Rect(box.x() - 32, box.y(), 14, box.h()), "-", Skin.Press.REST, false);
                    d.skin.button(d.c, new Rect(box.x() - 16, box.y(), 14, box.h()), "+", Skin.Press.REST, false);
                }
            d.skin.status(d.c, new Rect(10, d.h - 47, panel - 20, 9), "Volume 18.8 m³", Palette.ACCENT);
            // Done is the screen's close: closing saves the draft already.
            String[] bottom = {"Nozzles", "Paint", "Build"};
            int bw = (panel - 20 - 6) / 3;
            for (int i = 0; i < 3; i++)
                d.skin.button(d.c, new Rect(10 + i * (bw + 3), d.h - 27, bw, 18), bottom[i], i == 2 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        });
    }

    // ---- Small dialogs: the pipeline tool, the gauge's range, the vessel's datasheet ----

    static Prototype pipelineTool() {
        return new Prototype("pipeline-tool", 230, 250, (d, s) -> {
            d.skin.title(d.c, 10, 10, "Pipeline tool");
            d.column(Door.rows(
                    Door.group("Size", "From 0.001 to 0.8 m.", Door.field("bore", "Bore", "m")),
                    Door.group("Paint", "Pipe, bends and rack lanes take the first colour; bolts, valves and fittings keep theirs.",
                            new Column.Buttons(List.of(new Column.Button("p", "Primary", false, null, true), new Column.Button("s", "Secondary", true, null))),
                            new Column.Gap(44), Door.field("custom", "Custom", ""),
                            new Column.Buttons(List.of(new Column.Button("bare", "Bare steel", true, null))))),
                    10, 28, d.w - 20, Map.of("bore", "0.150", "custom", "#7A8B99"));
            int[] ral = {0xFF8F969C, 0xFF3B3C36, 0xFFE7EBDA, 0xFFA52019, 0xFFF4A900, 0xFF2271B3, 0xFF316650, 0xFF6C7059};
            for (int r = 0; r < 2; r++)
                for (int i = 0; i < 8; i++) {
                    int x = 18 + i * 25, y = 112 + r * 21;
                    d.c.fill(x, y, x + 20, y + 17, ral[(i + 3 * r) % 8]);
                    if (r == 0 && i == 0) d.c.outline(x - 1, y - 1, 22, 19, 0xFFFFFFFF);
                }
            String[] foot = {"Cancel", "Save"};
            for (int i = 0; i < 2; i++) d.skin.button(d.c, new Rect(10 + i * 107, d.h - 27, 103, 18), foot[i], i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        });
    }

    static Prototype gauge() {
        return new Prototype("gauge", 246, 170, (d, s) -> {
            d.skin.title(d.c, 10, 10, "Pressure gauge");
            d.column(Door.rows(
                    Door.group("Range", "The dial's span only; the reading is the same.", Door.field("min", "Minimum", "bar"), Door.field("max", "Maximum", "bar")),
                    Door.group("Reading", null, new Column.Buttons(List.of(new Column.Button("g", "Gauge", false, null, true), new Column.Button("a", "Absolute", true, null))))),
                    10, 28, d.w - 20, Map.of("min", "0.00", "max", "10.00"));
            String[] foot = {"Cancel", "Save"};
            for (int i = 0; i < 2; i++) d.skin.button(d.c, new Rect(10 + i * 115, d.h - 27, 111, 18), foot[i], i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        });
    }

    static Prototype vessel() {
        return new Prototype("vessel-datasheet", 340, 250, (d, s) -> {
            d.skin.title(d.c, 10, 10, "Vertical drum on a skirt");
            var col = d.column(Door.rows(
                    Door.group("Size", null, Door.field("dia", "Diameter", "m"), Door.field("tt", "Length", "m"), Door.reading("vol", "Volume", "18.8", "m³")),
                    Door.group("Moving parts", "Running turns the units' fans, agitators and impellers; still, they stand. Not saved.",
                            new Column.Buttons(List.of(new Column.Button("still", "Still", true, null), new Column.Button("run", "Running", false, null, true))))),
                    10, 28, 150, Map.of("dia", "2.00", "tt", "6.00", "vol", "18.8"));
            var box = d.well(new Rect(166, 25, d.w - 171, d.h - 60));
            // The side in its own column: no name is cut. "Length" is tangent to tangent (its hover).
            d.table(box, new int[]{box.w() - 82, 40, 42}, List.of(new String[]{"Nozzle", "Side", "Size"}, new String[]{"Inlet", "Front", "DN 150"},
                    new String[]{"Vapour out", "Top", "DN 200"}, new String[]{"Liquid out", "Bottom", "DN 100"}, new String[]{"Drain", "Bottom", "DN 50"}));
            d.skin.button(d.c, new Rect(d.w - 90, d.h - 27, 80, 18), "Done", Skin.Press.REST, false);
        });
    }

    // ---- FluidDeviceScreen's device pages: the door holds what the player sets and the indicating meters; the HMI glass the tables ----

    static final int SIDE = 160;

    /** The door's left column, the divider, and the glass on the right; returns the glass's inner box. */
    static Rect split(Door d, List<Column.Row> rows, Map<String, String> values) {
        int top = 74, bottom = d.h - 38;
        d.column(rows, 10, top, SIDE - 10, values);
        d.skin.vDivider(d.c, SIDE + 4, top, bottom);
        return d.well(new Rect(SIDE + 10, top - 3, d.w - SIDE - 15, bottom - top + 3));
    }

    /** A composition on the glass: the phase tabs latched, the phase bar, the table. */
    static void contents(Door d, Rect box, String[] phases, int phase, List<String[]> table) {
        int n = phases.length, tw = (box.w() - 3 * (n - 1)) / n;
        for (int i = 0; i < n; i++) d.skin.button(d.c, new Rect(box.x() + i * (tw + 3), box.y(), tw, 16), phases[i], i == phase ? Skin.Press.CURRENT : Skin.Press.REST, false);
        d.table(new Rect(box.x(), box.y() + 20, box.w(), box.h() - 20), new int[]{box.w() - 120, 60, 60}, table);
    }

    static Prototype pump() {
        return new Prototype("fluid-pump", 464, 254, (d, s) -> {
            d.title("Centrifugal pump");
            d.tabs(0, "Overview", "Datasheet");
            d.status("Running: 12.0 m³/h, 2.40 bar rise", Palette.ACCENT);
            var box = split(d, Door.rows(
                    Door.group("Control", null, Door.switchRow("Off", "On", true)),
                    Door.group("Set point", null, Door.field("flow", "Flow", "m³/h")),
                    Door.group("Limit", null, Door.field("rise", "Max rise", "bar"))),
                    Map.of("flow", "12.0", "rise", "5.00"));
            contents(d, box, new String[]{"Organic", "Aqueous", "Vapor", "Solids"}, 1,
                    List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Water", "92.1", "511"}, new String[]{"Ethanol", "7.9", "43.8"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        });
    }

    static Prototype generator() {
        return new Prototype("fluid-generator", 464, 254, (d, s) -> {
            d.title("Fluid source");
            d.tabs(0, "Overview", "Composition", "Solids");
            d.status("Feeding 3.20 kg/s at 2.00 bar, 25.0 °C", Palette.ACCENT);
            var box = split(d, Door.rows(
                    Door.group("Control", null, Door.switchRow("Off", "On", true)),
                    // The flow out is on the status display: no meter repeats it.
                    Door.group("Set point", null, Door.field("p", "Pressure", "bar"), Door.field("t", "Temperature", "°C"))),
                    Map.of("p", "2.00", "t", "25.0"));
            contents(d, box, new String[]{"Organic", "Aqueous", "Vapor", "Solids"}, 0,
                    List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"n-Hexane", "61.0", "82.4"}, new String[]{"n-Heptane", "39.0", "52.7"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/s");
        });
    }

    static Prototype valve() {
        return new Prototype("fluid-valve", 464, 254, (d, s) -> {
            d.title("Control valve");
            d.tabs(0, "Overview");
            d.status("Holding 2.50 kg/s, 63 % open", Palette.ACCENT);
            var box = split(d, Door.rows(
                    Door.group("Target", "What the valve holds. Choose one, then press Apply.",
                            new Column.Choice("Mass flow", null, true, "t:mass"), new Column.Choice("Volume flow", null, false, "t:volume")),
                    Door.group("Set point", null, Door.field("flow", "Flow", "kg/s")),
                    Door.group("Valve", null, new Column.Bar(0.63), new Column.Line("63 % open", Palette.TEXT))),
                    Map.of("flow", "2.50"));
            contents(d, box, new String[]{"Organic", "Aqueous", "Vapor", "Solids"}, 0,
                    List.of(new String[]{"Component", "Mole %", "kmol/h"}, new String[]{"Toluene", "100.0", "97.6"}));
            d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/s");
        });
    }

    static Prototype tank() {
        return new Prototype("fluid-tank", 464, 254, (d, s) -> {
            d.title("Atmospheric tank");
            d.tabs(0, "Overview", "Outlets", "Datasheet");
            d.status("Filling: 63 % full, 0.40 kg/s in", Palette.ACCENT);
            var box = split(d, Door.rows(
                    Door.group("Level", "Contents over capacity.", new Column.Bar(0.63), new Column.Line("63 %, 25.2 of 40 m³", Palette.TEXT)),
                    // The net flow in is on the status display: no meter repeats it.
                    Door.group("Pressure", null, Door.reading("top", "Top", "1.01", "bar"), Door.reading("bottom", "Bottom", "1.25", "bar"))),
                    Map.of("top", "1.01", "bottom", "1.25"));
            contents(d, box, new String[]{"Organic", "Aqueous", "Vapor", "Solids"}, 1,
                    List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"Water", "99.2", "1390"}, new String[]{"Salt", "0.8", "11.2"}));
            d.footer(1, "Close", "Mole", "°C", "bar", "/s");
        });
    }

    static BufferedImage render(Prototype p, Skin skin) {
        var image = new BufferedImage((p.width() + 8) * SCALE, (p.height() + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new HeatDiagramRender.Java2dCanvas(g);
        var door = new Door(canvas, skin, p.width(), p.height());
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

        Door(Canvas c, Skin skin, int w, int h) {
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
                        skin.button(c, p.slots().get(spec.key()), spec.text(), !spec.active() ? spec.latched() ? Skin.Press.CURRENT : Skin.Press.OFF : Skin.Press.REST, false);
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
