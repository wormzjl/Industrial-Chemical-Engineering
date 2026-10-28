import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatFlowsheet;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Software renders of the heat units' Control tab to PNG (batch 2026-10-28-gui-toolkit): the page as the screen lays it
 * out at 464 by 254 GUI px (the 480 by 270 GUI of GUI scale 2 at 960 by 540, or the automatic scale at 1920 by 1080),
 * drawn by the same code the screen draws with — {@code HeatFlowsheet} and {@code HeatControlColumn} on the GUI kit's
 * {@code Column} and {@code ScrollView} — on a Java2D canvas, with illustrative readings and English texts. The game's
 * widgets (fields and buttons) are drawn as plain boxes. Run from the worktree root after {@code ./gradlew compileJava}:
 * {@code java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders}
 */
public final class HeatDiagramRender {
    private static final int SCALE = 2, WIDTH = 464, HEIGHT = 254;
    private static Map<String, String> lang;

    private record Case(String file, String unit, String side, boolean paired, ExchangerBlueprint.Design design, HeatUnit.Mode mode, boolean powerOn,
                        String actuator, Double effort, FluidView.Lines lines, boolean edit) {
    }

    public static void main(String[] args) throws Exception {
        lang = lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var B = ExchangerBlueprint.Lines.BYPASS;
        var R = ExchangerBlueprint.Lines.RATIO;
        var N = ExchangerBlueprint.Lines.NONE;
        var cases = List.of(
                new Case("exchanger-plain", "EXCHANGER", "TUBE", true, ExchangerBlueprint.Design.NONE, HeatUnit.Mode.OFF, true, "bypass", 0.25, null, true),
                new Case("exchanger-tube-bypass", "EXCHANGER", "TUBE", true, new ExchangerBlueprint.Design(B, N), HeatUnit.Mode.OUTLET_TEMPERATURE, true, "bypass", 0.3, lines("CHECKED", 1), true),
                new Case("exchanger-shell-bypass", "EXCHANGER", "SHELL", true, new ExchangerBlueprint.Design(N, B), HeatUnit.Mode.OUTLET_TEMPERATURE, true, "bypass_valve", 0.43, lines("COMPLETE", 0), true),
                new Case("exchanger-both-bypass", "EXCHANGER", "SHELL", true, new ExchangerBlueprint.Design(B, B), HeatUnit.Mode.DUTY, true, "bypass", 0.1, lines("UNMETERED", 1), true),
                new Case("exchanger-ratio", "EXCHANGER", "TUBE", true, new ExchangerBlueprint.Design(R, R), HeatUnit.Mode.RATIO, true, "feed_valve", null, lines("COMPLETE", 0), true),
                new Case("exchanger-bypass-ratio", "EXCHANGER", "SHELL", true, new ExchangerBlueprint.Design(R, B), HeatUnit.Mode.OUTLET_TEMPERATURE, true, "bypass", 0.2, lines("NOT_VALIDATED", 0), true),
                new Case("air-cooler", "AIR_COOLER", "PROCESS", false, ExchangerBlueprint.Design.NONE, HeatUnit.Mode.OFF, true, "fans", 1.0, null, true),
                new Case("air-cooler-reader", "AIR_COOLER", "PROCESS", false, ExchangerBlueprint.Design.NONE, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "fans", 0.6, null, false),
                new Case("fired-heater", "FIRED_HEATER", "RADIANT", true, ExchangerBlueprint.Design.NONE, HeatUnit.Mode.OFF, true, "burners", 0.7, null, true),
                new Case("kettle", "KETTLE", "TUBE", true, ExchangerBlueprint.Design.NONE, HeatUnit.Mode.DUTY, true, "bypass", 0.2, null, true),
                new Case("kettle-pool", "KETTLE", "SHELL", true, ExchangerBlueprint.Design.NONE, HeatUnit.Mode.OFF, true, "none", null, null, true));
        for (var c : cases) {
            write(out.resolve(c.file() + ".png"), c, false);
            write(out.resolve(c.file() + "-scrolled.png"), c, true);
        }
        // The styles tried for the owner, two by two, for the units that differ most.
        var styles = out.resolve("styles");
        Files.createDirectories(styles);
        for (var c : cases)
            if (List.of("exchanger-shell-bypass", "exchanger-ratio", "air-cooler", "fired-heater").contains(c.file())) sheet(styles.resolve(c.file() + ".png"), c);
    }

    private static FluidView.Lines lines(String status, int missing) {
        return new FluidView.Lines(status, missing, List.of());
    }

    private static Map<String, String> lang(Path file) throws Exception {
        var map = new HashMap<String, String>();
        var m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) map.put(m.group(1), m.group(2).replace("\\\"", "\"").replace("\\n", "\n"));
        return map;
    }

    private static String tr(String key, Object... args) {
        String line = lang.get(key);
        return line == null ? "?" + key : String.format(Locale.ROOT, line, args);
    }

    /** Illustrative readings: a stream's temperature (°C) and flow (kg/s). */
    private static String value(HeatDiagram.Value v) {
        var t = Map.of("port:SHELL_INLET", 20.0, "port:SHELL_OUTLET", 37.5, "part:shell_outlet_tee", 30.0, "part:shell_inlet_tee", 20.0, "port:TUBE_INLET", 90.0,
                "port:TUBE_OUTLET", 72.6, "port:PROCESS_INLET", 85.0, "port:PROCESS_OUTLET", 80.0, "port:CONVECTION_INLET", 50.0, "air", 25.0);
        var f = Map.of("port:SHELL_INLET", 9.8, "part:shell_bypass_valve", 7.4, "port:TUBE_INLET", 5.9, "part:tube_bypass_valve", 1.2, "port:PROCESS_INLET", 13.5,
                "port:CONVECTION_INLET", 4.0, "port:RADIANT_INLET", 4.0, "port:FEED", 2.1, "port:VAPOUR_OUTLET", 1.2, "port:LIQUID_OUTLET", 0.9);
        for (var s : v.sources()) {
            switch (v.show()) {
                case DUTY -> {
                    return "433.0 kW";
                }
                case TEMPERATURE -> {
                    if (t.containsKey(s)) return String.format(Locale.ROOT, s.equals("air") ? "Air at %.1f C" : "%.1f C", t.get(s));
                }
                case FLOW -> {
                    if (f.containsKey(s)) return String.format(Locale.ROOT, "%.1f kg/s", f.get(s));
                }
                case BOTH -> {
                    if (t.containsKey(s) || f.containsKey(s)) return String.format(Locale.ROOT, "%.1f C · %.1f kg/s", t.getOrDefault(s, 150.0), f.getOrDefault(s, 0.0));
                }
            }
        }
        return v.show() == HeatDiagram.Show.TEMPERATURE ? "150.0 C" : "";
    }

    /** A Java2D canvas in GUI pixels, drawn at {@link #SCALE}; a 9 px sans font stands for the game font. */
    private static final class Java2dCanvas implements Canvas {
        private final Graphics2D g;
        private final Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
        private final FontMetrics metrics;
        private final Measure measure;

        Java2dCanvas(Graphics2D g) {
            this.g = g;
            g.setFont(font);
            metrics = g.getFontMetrics(font);
            measure = text -> (int) Math.ceil(metrics.getStringBounds(text, g).getWidth());
        }

        @Override
        public Measure measure() {
            return measure;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            if (x1 <= x0 || y1 <= y0) return;
            g.setColor(new Color(argb, true));
            g.fillRect(x0, y0, x1 - x0, y1 - y0);
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            text(text, x, y, 1, argb);
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            var saved = g.getTransform();
            g.translate(x, y);
            g.scale(scale, scale);
            g.setColor(new Color(argb, true));
            g.drawString(text, 0, 7);
            g.setTransform(saved);
        }

        @Override
        public void clip(Rect area) {
            g.setClip(area.x(), area.y(), area.w(), area.h());
        }

        @Override
        public void unclip() {
            g.setClip(null);
        }
    }

    /** A vanilla widget as a plain box: a field dark with its value, a button grey with its text (dimmed when inactive). */
    private static void field(Canvas c, Rect r, String value, String hint) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFFA0A0A0);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
        c.text(value.isEmpty() ? hint : value, r.x() + 4, r.y() + 5, value.isEmpty() ? 0xFF808080 : 0xFFE0E0E0);
    }

    /** A button; a chosen mode button (style B) reads as chosen: an accent edge and text, not dimmed. */
    private static void button(Canvas c, Rect r, String text, boolean active, boolean chosen) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), chosen ? Palette.ACCENT : 0xFF000000);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, chosen ? 0xFF1E3A36 : active ? 0xFF6F6F6F : 0xFF2C2C2C);
        String shown = c.measure().cut(text, r.w() - 6);
        c.text(shown, r.x() + (r.w() - c.measure().width(shown)) / 2, r.y() + 5, chosen ? Palette.ACCENT : active ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    /**
     * The Control tab's styles tried for the owner (batch 2026-10-28-gui-toolkit), each over the rows HeatControlColumn
     * builds: A as shipped; B to D prototypes, their row transforms here in the tool until the owner picks one.
     */
    private enum Style {
        LIST("A  List: radio modes with a line each, default rows (as shipped)"),
        COMPACT("B  Compact: mode buttons, compact rows, share on the acting line"),
        SPLIT("C  Split: narrower drawing, modes beside the settings"),
        CARDS("D  Cards: sections in panels, title-only modes");
        final String title;

        Style(String title) {
            this.title = title;
        }
    }

    /** A mode button's key prefix in style B when it is the chosen mode. */
    private static final String CHOSEN = "chosen:";

    private static void write(Path file, Case c, boolean scrolled) throws Exception {
        ImageIO.write(page(c, Style.LIST, scrolled), "png", file.toFile());
    }

    /** One case in the four styles, two by two, each as it opens. */
    private static void sheet(Path file, Case c) throws Exception {
        var pages = new ArrayList<BufferedImage>();
        for (var s : Style.values()) pages.add(page(c, s, false));
        int w = pages.getFirst().getWidth(), h = pages.getFirst().getHeight(), label = 40;
        var sheet = new BufferedImage(2 * w, 2 * (h + label), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x101418));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.setColor(new Color(0xEAF1F5));
        for (int i = 0; i < 4; i++) {
            int x = (i % 2) * w, y = (i / 2) * (h + label);
            g.drawString(Style.values()[i].title, x + 14, y + 29);
            g.drawImage(pages.get(i), x, y + label, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", file.toFile());
    }

    private static BufferedImage page(Case c, Style style, boolean scrolled) {
        var image = new BufferedImage((WIDTH + 8) * SCALE, (HEIGHT + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new Java2dCanvas(g);
        canvas.fill(-4, -4, WIDTH + 4, HEIGHT + 4, 0xFF101418);
        canvas.fill(0, 0, WIDTH, HEIGHT, Palette.BG);
        canvas.outline(0, 0, WIDTH, HEIGHT, Palette.LINE);
        // The page's frame as the screen draws it: title, tabs, status, separators, footer.
        canvas.text(tr("block.icheme." + c.unit().toLowerCase(Locale.ROOT)).startsWith("?") ? c.unit() + " · " + c.side() : tr("block.icheme." + c.unit().toLowerCase(Locale.ROOT)), 10, 10, Palette.TEXT);
        var tabs = FluidGuiLayout.tabs(WIDTH, 2);
        button(canvas, new Rect(tabs.get(0).x(), 29, tabs.get(0).width(), 18), tr("gui.icheme.fluid.overview"), true, false);
        button(canvas, new Rect(tabs.get(1).x(), 29, tabs.get(1).width(), 18), tr("gui.icheme.fluid.control"), false, false);
        canvas.text("Holding 45.0 C · steady since 12 s", 10, 59, Palette.ACCENT);
        int controlTop = 74, controlBottom = HEIGHT - 52;
        canvas.hLine(8, WIDTH - 8, HEIGHT - 49, Palette.LINE);
        var footer = FluidGuiLayout.footer(WIDTH);
        String[] footerText = {tr("gui.icheme.fluid.close"), tr("gui.icheme.fluid.apply"), "Mole", "C", "kPa", "/s"};
        for (int i = 0; i < footer.size(); i++) button(canvas, new Rect(footer.get(i).x(), HEIGHT - 27, footer.get(i).width(), 18), footerText[i], true, false);
        canvas.text("View at 102.4 s", 10, HEIGHT - 42, Palette.WARN);
        // Each style's split of the page: A and B as FluidGuiLayout.control places it; C and D with a narrower drawing.
        var slots = FluidGuiLayout.control(WIDTH);
        int drawingRight = switch (style) {
            case LIST, COMPACT -> slots.drawingRight();
            case SPLIT -> WIDTH * 38 / 100;
            case CARDS -> WIDTH * 45 / 100;
        };
        int panelX = drawingRight + 10, panelRight = slots.panelX() + slots.panelWidth();
        // The drawing, as FluidDeviceScreen.renderControl lays it out.
        var unit = com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(c.unit());
        var side = com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(c.side());
        var diagram = HeatDiagram.of(unit, side, c.design());
        var sheet = new HeatFlowsheet(diagram, HeatDiagram.roles(unit, side));
        boolean legend = diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        int bottom = controlBottom - (legend || c.lines() != null ? 24 : 4);
        sheet.fit(10, controlTop, drawingRight - 10, bottom - controlTop);
        String[] cycle = {"OK", "WRONG_ORDER", "MISSING"};
        var states = new HashMap<String, String>();
        sheet.draw(canvas, new HeatFlowsheet.Data() {
            @Override
            public String value(HeatDiagram.Value v) {
                return HeatDiagramRender.value(v);
            }

            @Override
            public String state(String slot) {
                return states.computeIfAbsent(slot, s -> cycle[states.size() % 3]);
            }

            @Override
            public String text(AssemblyDiagram.Text text) {
                return tr(HeatDiagram.KEY + text.key(), text.args().toArray());
            }
        });
        int check = c.edit() && c.lines() != null ? Math.min(90, drawingRight - 20) : 0;
        if (legend) {
            int lx = 10, ly = controlBottom - 14;
            int[][] marks = {{Palette.TEAL_FILL, Palette.TEAL}, {Palette.YELLOW_FILL, Palette.YELLOW}, {Palette.RED_FILL, Palette.RED}};
            String[] words = {"legend.in_place", "legend.misplaced", "legend.missing"};
            for (int i = 0; i < 3; i++) {
                String word = tr("gui.icheme.fluid." + words[i]);
                if (lx + 10 + canvas.measure().width(word) > drawingRight - check - 6) break;
                canvas.fill(lx, ly, lx + 8, ly + 8, marks[i][1]);
                canvas.fill(lx + 1, ly + 1, lx + 7, ly + 7, marks[i][0]);
                canvas.text(word, lx + 11, ly, Palette.MUTED);
                lx += 19 + canvas.measure().width(word);
            }
        }
        if (check > 0) button(canvas, new Rect(drawingRight - check, controlBottom - 19, check, 18), tr("gui.icheme.fluid.check_lines"), true, false);
        canvas.vLine(panelX - 6, controlTop, controlBottom, Palette.LINE);
        // The rows HeatControlColumn builds, then each style's form of them.
        var ports = List.of(new FluidView.Port("TUBE_INLET", 363.15, 2e5, 3, new double[3][1], new double[3]),
                new FluidView.Port("SHELL_INLET", 293.15, 2e5, 6, new double[3][1], new double[3]));
        var heat = new FluidView.Heat(c.unit(), c.side(), c.paired(), 318, -1e6, c.unit().equals("AIR_COOLER") ? 1.0 : null, c.unit().equals("AIR_COOLER") ? 1.2e4 : null,
                c.unit().equals("FIRED_HEATER") ? 1.0 : null, c.unit().equals("FIRED_HEATER") ? 473.15 : null, c.unit().equals("FIRED_HEATER") ? 2e6 : null,
                c.unit().equals("EXCHANGER") ? 0.25 : null).with(c.actuator(), c.effort(), "NONE", c.unit().equals("EXCHANGER") ? ports : List.of(), c.lines());
        boolean controllable = !c.unit().equals("FIRED_HEATER") && !(c.unit().equals("KETTLE") && c.side().equals("SHELL"));
        var applied = controllable ? new HeatUnit.Control(c.mode(), c.mode() == HeatUnit.Mode.RATIO ? 0.5 : 318.15, 0, 0.25) : null;
        var input = new HeatControlColumn.Input(heat, applied, c.edit(), controllable, c.mode(), c.powerOn(), "C");
        var rows = HeatControlColumn.rows(input, (key, a) -> tr("gui.icheme.fluid." + key, a));
        int tall = switch (style) {
            case LIST -> column(canvas, rows, List.of(), panelX, panelRight, controlTop, controlBottom, Column.Metrics.DEFAULT, scrolled);
            case COMPACT -> column(canvas, compact(rows, c.effort()), List.of(), panelX, panelRight, controlTop, controlBottom, Column.Metrics.COMPACT, scrolled);
            case SPLIT -> {
                var halves = split(rows);
                int left = panelX + 105;
                yield Math.max(column(canvas, halves.get(0), List.of(), panelX, left, controlTop, controlBottom, Column.Metrics.COMPACT, scrolled),
                        column(canvas, halves.get(1), List.of(), left + 12, panelRight, controlTop, controlBottom, Column.Metrics.COMPACT, scrolled));
            }
            case CARDS -> {
                var panels = new ArrayList<int[]>();
                var cards = cards(rows, panels);
                yield column(canvas, cards, panels, panelX + 3, panelRight - 3, controlTop + 3, controlBottom, new Column.Metrics(11, 12, 20, 20, 10), scrolled) + 3;
            }
        };
        if (!scrolled) System.out.printf(Locale.ROOT, "%-24s %-8s column %3d px in a %d px view%n", c.file(), style, tall, controlBottom - controlTop);
        g.dispose();
        return image;
    }

    /** B: the modes as buttons two by two with the chosen mode's line under them; the acting line carries the share; no small gaps. */
    private static List<Column.Row> compact(List<Column.Row> rows, Double effort) {
        var out = new ArrayList<Column.Row>();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            if (r instanceof Column.Gap) continue;
            if (r instanceof Column.Line && next(rows, i) instanceof Column.Choice) continue; // the heading: the buttons say it
            if (r instanceof Column.Choice) {
                int end = i;
                while (end < rows.size() && rows.get(end) instanceof Column.Choice) end++;
                var buttons = new ArrayList<Column.Button>();
                String line = null;
                for (int k = i; k < end; k++) {
                    var ch = (Column.Choice) rows.get(k);
                    if (ch.selected()) line = ch.line();
                    buttons.add(new Column.Button((ch.selected() ? CHOSEN : "mode") + k, ch.title(), !ch.selected() && ch.action() != null, ch.line()));
                }
                for (int k = 0; k < buttons.size(); k += 2) out.add(new Column.Buttons(buttons.subList(k, Math.min(k + 2, buttons.size()))));
                if (line != null) out.add(new Column.Line(line, Palette.MUTED));
                i = end - 1;
                continue;
            }
            if (r instanceof Column.Line l && i + 2 < rows.size() && rows.get(i + 1) instanceof Column.Bar b && rows.get(i + 2) instanceof Column.Line) {
                out.add(new Column.Line(l.text() + " · " + Format.number(effort * 100) + " %", Palette.TEXT));
                out.add(b);
                i += 2;
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private static Column.Row next(List<Column.Row> rows, int i) {
        for (int k = i + 1; k < rows.size(); k++) if (!(rows.get(k) instanceof Column.Gap)) return rows.get(k);
        return null;
    }

    /** C: the selector and the modes (or the pool's note) on the left; the fans, the fields, what acts and the notes on the right. */
    private static List<List<Column.Row>> split(List<Column.Row> rows) {
        int cut = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Column.Choice) cut = i + 1;
        if (cut == 0) for (int i = 0; i < rows.size() && cut == 0; i++) if (rows.get(i) instanceof Column.Paragraph) cut = i + 1;
        var left = rows.subList(0, cut).stream().filter(r -> !(r instanceof Column.Gap)).toList();
        var right = rows.subList(cut, rows.size()).stream().filter(r -> !(r instanceof Column.Gap)).toList();
        return List.of(left, right);
    }

    /** D: sections, each with its header and all but the side and the notes in a panel: the mode (titles only), the set points, what acts now. */
    private static List<Column.Row> cards(List<Column.Row> rows, List<int[]> panels) {
        var sections = new LinkedHashMap<String, List<Column.Row>>();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            if (r instanceof Column.Gap) continue;
            String key;
            Column.Row shown = r;
            if (r instanceof Column.Buttons b && b.buttons().getFirst().key().startsWith("side:")) key = "side";
            else if (r instanceof Column.Line l && next(rows, i) instanceof Column.Choice) {
                key = "mode";
                shown = new Column.Line(l.text(), Palette.ACCENT);
            } else if (r instanceof Column.Choice) key = "mode";
            else if (r instanceof Column.Buttons || r instanceof Column.Field) {
                key = "settings";
                sections.computeIfAbsent(key, k -> new ArrayList<>(List.of(new Column.Line("Set points", Palette.ACCENT))));
            } else if (r instanceof Column.Line l && l.colour() == Palette.MUTED) {
                key = "acting";
                shown = new Column.Line(l.text(), Palette.ACCENT);
            } else if (r instanceof Column.Bar || r instanceof Column.Line) key = "acting";
            else key = "notes";
            sections.computeIfAbsent(key, k -> new ArrayList<>()).add(shown);
        }
        var out = new ArrayList<Column.Row>();
        for (var e : sections.entrySet()) {
            if (!out.isEmpty()) out.add(new Column.Gap(8));
            int start = out.size();
            out.addAll(e.getValue());
            if (!e.getKey().equals("notes") && !e.getKey().equals("side")) panels.add(new int[]{start, out.size() - 1});
        }
        return out;
    }

    /**
     * A column placed from {@code x} to {@code right} at {@code metrics} in its own scrolled view, panels under its rows,
     * the widgets as boxes and its bar; returns the column's height.
     */
    private static int column(Java2dCanvas canvas, List<Column.Row> rows, List<int[]> panels, int x, int right, int top, int bottom, Column.Metrics metrics, boolean scrolled) {
        var column = Column.place(rows, x, top, right - x, canvas.measure(), metrics);
        var view = new ScrollView();
        view.layout(new Rect(x - 3, top, right - x + 6, bottom - top), right + 4, column.bounds().h());
        // Scrolled to the column's end: the wheel at the viewport until it stops.
        while (scrolled && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        // Style D's panels reach 3 px above its first row, which sits 3 px below the view's top.
        canvas.clip(panels.isEmpty() ? view.viewport() : new Rect(x - 3, top - 3, right - x + 6, bottom - top + 3));
        for (var p : panels) {
            var first = column.rows().get(p[0]).rect();
            var last = column.rows().get(p[1]).rect();
            canvas.fill(x - 3, first.y() - 3 - view.offset(), right + 3, last.bottom() + 1 - view.offset(), Palette.PANEL);
        }
        column.draw(canvas, -view.offset());
        var drafts = Map.of("heatTemperature", "45.0", "heatDuty", "500.0", "heatRatio", "0.5", "bypass", "25.0", "temperature", "200.0", "lowLimit", "");
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) field(canvas, view.onScreen(r), drafts.getOrDefault(f.key(), ""), f.key().equals("lowLimit") ? tr("gui.icheme.fluid.heat_field.off") : "");
            } else if (p.row() instanceof Column.Buttons b) {
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (view.visible(r)) button(canvas, view.onScreen(r), spec.text(), spec.active(), spec.key().startsWith(CHOSEN));
                }
            }
        }
        canvas.unclip();
        view.drawBar(canvas);
        return column.bounds().h();
    }
}
