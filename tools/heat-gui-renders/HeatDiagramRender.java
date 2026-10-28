import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatFlowsheet;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
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
import java.util.HashMap;
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

    private static void button(Canvas c, Rect r, String text, boolean active) {
        c.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF000000);
        c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, active ? 0xFF6F6F6F : 0xFF2C2C2C);
        String shown = c.measure().cut(text, r.w() - 6);
        c.text(shown, r.x() + (r.w() - c.measure().width(shown)) / 2, r.y() + 5, active ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    private static void write(Path file, Case c, boolean scrolled) throws Exception {
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
        button(canvas, new Rect(tabs.get(0).x(), 29, tabs.get(0).width(), 18), tr("gui.icheme.fluid.overview"), true);
        button(canvas, new Rect(tabs.get(1).x(), 29, tabs.get(1).width(), 18), tr("gui.icheme.fluid.control"), false);
        canvas.text("Holding 45.0 C · steady since 12 s", 10, 59, Palette.ACCENT);
        int controlTop = 74, controlBottom = HEIGHT - 52;
        canvas.hLine(8, WIDTH - 8, HEIGHT - 49, Palette.LINE);
        var footer = FluidGuiLayout.footer(WIDTH);
        String[] footerText = {tr("gui.icheme.fluid.close"), tr("gui.icheme.fluid.apply"), "Mole", "C", "kPa", "/s"};
        for (int i = 0; i < footer.size(); i++) button(canvas, new Rect(footer.get(i).x(), HEIGHT - 27, footer.get(i).width(), 18), footerText[i], true);
        canvas.text("View at 102.4 s", 10, HEIGHT - 42, Palette.WARN);
        // The drawing, as FluidDeviceScreen.renderControl lays it out.
        var slots = FluidGuiLayout.control(WIDTH);
        var unit = com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(c.unit());
        var side = com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(c.side());
        var diagram = HeatDiagram.of(unit, side, c.design());
        var sheet = new HeatFlowsheet(diagram, HeatDiagram.roles(unit, side));
        boolean legend = diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        int bottom = controlBottom - (legend || c.lines() != null ? 24 : 4);
        sheet.fit(10, controlTop, slots.drawingRight() - 10, bottom - controlTop);
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
        if (legend) {
            int lx = 10, ly = controlBottom - 14;
            int[][] marks = {{Palette.TEAL_FILL, Palette.TEAL}, {Palette.YELLOW_FILL, Palette.YELLOW}, {Palette.RED_FILL, Palette.RED}};
            String[] words = {"legend.in_place", "legend.misplaced", "legend.missing"};
            for (int i = 0; i < 3; i++) {
                String word = tr("gui.icheme.fluid." + words[i]);
                if (lx + 10 + canvas.measure().width(word) > slots.drawingRight() - 96) break;
                canvas.fill(lx, ly, lx + 8, ly + 8, marks[i][1]);
                canvas.fill(lx + 1, ly + 1, lx + 7, ly + 7, marks[i][0]);
                canvas.text(word, lx + 11, ly, Palette.MUTED);
                lx += 19 + canvas.measure().width(word);
            }
        }
        if (c.edit() && c.lines() != null) {
            int bw = Math.min(90, slots.drawingRight() - 20);
            button(canvas, new Rect(slots.drawingRight() - bw, controlBottom - 19, bw, 18), tr("gui.icheme.fluid.check_lines"), true);
        }
        canvas.vLine(slots.panelX() - 6, controlTop, controlBottom, Palette.LINE);
        // The column, as FluidDeviceScreen.buildControl places it.
        var ports = List.of(new FluidView.Port("TUBE_INLET", 363.15, 2e5, 3, new double[3][1], new double[3]),
                new FluidView.Port("SHELL_INLET", 293.15, 2e5, 6, new double[3][1], new double[3]));
        var heat = new FluidView.Heat(c.unit(), c.side(), c.paired(), 318, -1e6, c.unit().equals("AIR_COOLER") ? 1.0 : null, c.unit().equals("AIR_COOLER") ? 1.2e4 : null,
                c.unit().equals("FIRED_HEATER") ? 1.0 : null, c.unit().equals("FIRED_HEATER") ? 473.15 : null, c.unit().equals("FIRED_HEATER") ? 2e6 : null,
                c.unit().equals("EXCHANGER") ? 0.25 : null).with(c.actuator(), c.effort(), "NONE", c.unit().equals("EXCHANGER") ? ports : List.of(), c.lines());
        boolean controllable = !c.unit().equals("FIRED_HEATER") && !(c.unit().equals("KETTLE") && c.side().equals("SHELL"));
        var applied = controllable ? new HeatUnit.Control(c.mode(), c.mode() == HeatUnit.Mode.RATIO ? 0.5 : 318.15, 0, 0.25) : null;
        var input = new HeatControlColumn.Input(heat, applied, c.edit(), controllable, c.mode(), c.powerOn(), "C");
        var rows = HeatControlColumn.rows(input, (key, a) -> tr("gui.icheme.fluid." + key, a));
        var column = Column.place(rows, slots.panelX(), controlTop, slots.panelWidth(), canvas.measure());
        var view = new ScrollView();
        view.layout(new Rect(slots.panelX() - 2, controlTop, slots.panelWidth() + 4, controlBottom - controlTop), slots.scrollX(), column.bounds().h());
        // Scrolled to the column's end: the wheel at the viewport until it stops.
        while (scrolled && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        canvas.clip(view.viewport());
        column.draw(canvas, -view.offset());
        var drafts = Map.of("heatTemperature", "45.0", "heatDuty", "500.0", "heatRatio", "0.5", "bypass", "25.0", "temperature", "200.0", "lowLimit", "");
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) field(canvas, view.onScreen(r), drafts.getOrDefault(f.key(), ""), f.key().equals("lowLimit") ? tr("gui.icheme.fluid.heat_field.off") : "");
            } else if (p.row() instanceof Column.Buttons b) {
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (view.visible(r)) button(canvas, view.onScreen(r), spec.text(), spec.active());
                }
            }
        }
        canvas.unclip();
        view.drawBar(canvas);
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }
}
