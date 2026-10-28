import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatFlowsheet;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Software renders of the heat units' Control tab to PNG (batch 2026-10-28-gui-toolkit): the page as {@code FluidDeviceScreen}
 * lays it out at 464 by 254 GUI px (the 480 by 270 GUI of GUI scale 2 at 960 by 540, or the automatic scale at 1920 by
 * 1080), drawn by the code the screen draws with — {@code HeatFlowsheet}, {@code HeatControlColumn} in layout C, the kit's
 * {@code Column}, {@code ScrollView} and {@code PanelSkin} — on a Java2D canvas, in the game's own bitmap font (read from
 * the Minecraft resources jar), with illustrative readings and English texts. Each page in the light and the dark panel.
 * Run from the worktree root after {@code ./gradlew compileJava}:
 * {@code java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar}
 */
public final class HeatDiagramRender {
    private static final int SCALE = 2, WIDTH = 464, HEIGHT = 254;
    private static Map<String, String> lang;
    private static GameFont font;

    private record Case(String file, String unit, String side, boolean paired, ExchangerBlueprint.Design design, HeatUnit.Mode mode, boolean powerOn,
                        String actuator, Double effort, FluidView.Lines lines, boolean edit) {
    }

    public static void main(String[] args) throws Exception {
        lang = lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var jar = Path.of(args.length > 2 ? args[2] : "build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        font = Files.exists(jar) ? GameFont.load(jar) : null;
        if (font == null) System.out.println("No Minecraft resources jar at " + jar + ": a sans font stands in, so widths differ from the game's");
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
            var light = page(c, PanelSkin.LIGHT, false);
            grid(out.resolve(c.file() + ".png"), List.of("Light panel (3a)", "Dark panel (3c)"), List.of(light, page(c, PanelSkin.DARK, false)));
            if (overflow) grid(out.resolve(c.file() + "-scrolled.png"), List.of("Light panel, scrolled to the end", "Dark panel, scrolled to the end"),
                    List.of(page(c, PanelSkin.LIGHT, true), page(c, PanelSkin.DARK, true)));
        }
        // Each unit's drawing alone at the design grid's own size (as on a 790 px screen it is about two thirds of this).
        var drawings = new ArrayList<BufferedImage>();
        var names = new ArrayList<String>();
        for (var c : cases)
            if (List.of("exchanger-both-bypass", "air-cooler", "fired-heater", "kettle").contains(c.file())) {
                drawings.add(drawing(c));
                names.add(c.file());
            }
        grid(out.resolve("drawings.png"), names, drawings);
    }

    /** {@code c}'s flowsheet alone on the HMI screen's glass, at one GUI pixel per design unit. */
    private static BufferedImage drawing(Case c) {
        int pad = 6, w = HeatDiagram.WIDTH + 2 * pad, h = HeatDiagram.HEIGHT + 2 * pad;
        var image = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.scale(SCALE, SCALE);
        var canvas = new Java2dCanvas(g);
        canvas.fill(0, 0, w, h, PanelSkin.LIGHT.screen().glass());
        var unit = com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(c.unit());
        var side = com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(c.side());
        var sheet = new HeatFlowsheet(HeatDiagram.of(unit, side, c.design()), HeatDiagram.roles(unit, side));
        sheet.fit(pad, pad, HeatDiagram.WIDTH, HeatDiagram.HEIGHT);
        sheet.draw(canvas, data());
        g.dispose();
        return image;
    }

    /** The illustrative readings, the English texts, and line parts cycling teal, yellow and red to show the legend's colours. */
    private static HeatFlowsheet.Data data() {
        String[] cycle = {"OK", "WRONG_ORDER", "MISSING"};
        var states = new HashMap<String, String>();
        return new HeatFlowsheet.Data() {
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
        };
    }

    private static FluidView.Lines lines(String status, int missing) {
        return new FluidView.Lines(status, missing, List.of());
    }

    private static Map<String, String> lang(Path file) throws Exception {
        var map = new HashMap<String, String>();
        var m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) map.put(m.group(1), unescape(m.group(2)));
        return map;
    }

    /** A JSON string's escapes: {@code \\uXXXX}, {@code \\n}, {@code \\"}, {@code \\\\}, {@code \\/}. */
    private static String unescape(String s) {
        var out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                out.append(ch);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'u' -> {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                default -> out.append(next);
            }
        }
        return out.toString();
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
                    if (t.containsKey(s)) return String.format(Locale.ROOT, s.equals("air") ? "Air at %.1f °C" : "%.1f °C", t.get(s));
                }
                case FLOW -> {
                    if (f.containsKey(s)) return String.format(Locale.ROOT, "%.1f kg/s", f.get(s));
                }
                case BOTH -> {
                    if (t.containsKey(s) || f.containsKey(s)) return String.format(Locale.ROOT, "%.1f °C · %.1f kg/s", t.getOrDefault(s, 150.0), f.getOrDefault(s, 0.0));
                }
            }
        }
        return v.show() == HeatDiagram.Show.TEMPERATURE ? "150.0 °C" : "";
    }

    /**
     * The game's bitmap font as {@code assets/minecraft/font/include/default.json} and {@code space.json} define it: each
     * character's pixels from its sheet, its advance the glyph's inked width plus one (a space 4), its top at the line's
     * top plus 7 less its ascent. A character no sheet holds (the game's unifont) is drawn and measured in a sans font.
     */
    private static final class GameFont {
        private record Glyph(BufferedImage sheet, int x0, int y0, int width, int height, float scale, int ascent, int advance) {
        }

        private final Map<Integer, Glyph> glyphs = new HashMap<>();
        private final Font fallback = new Font(Font.SANS_SERIF, Font.PLAIN, 9);

        static GameFont load(Path jar) throws IOException {
            var font = new GameFont();
            try (var zip = new ZipFile(jar.toFile())) {
                String json = new String(zip.getInputStream(zip.getEntry("assets/minecraft/font/include/default.json")).readAllBytes(), StandardCharsets.UTF_8);
                for (String block : json.split("\"type\"\\s*:\\s*\"bitmap\"")) {
                    String file = group(block, "\"file\"\\s*:\\s*\"([^\"]+)\"");
                    if (file == null) continue;
                    int ascent = Integer.parseInt(group(block, "\"ascent\"\\s*:\\s*(-?\\d+)"));
                    String height = group(block, "\"height\"\\s*:\\s*(\\d+)");
                    var rows = new ArrayList<int[]>();
                    Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(block.substring(block.indexOf('[', block.indexOf("\"chars\"")), block.indexOf(']', block.indexOf("\"chars\""))));
                    while (m.find()) rows.add(unescape(m.group(1)).codePoints().toArray());
                    var sheet = ImageIO.read(zip.getInputStream(zip.getEntry("assets/minecraft/textures/" + file.substring(file.indexOf(':') + 1))));
                    int cw = sheet.getWidth() / rows.getFirst().length, ch = sheet.getHeight() / rows.size();
                    float scale = (height == null ? 8f : Integer.parseInt(height)) / ch;
                    for (int r = 0; r < rows.size(); r++)
                        for (int col = 0; col < rows.get(r).length; col++) {
                            int cp = rows.get(r)[col];
                            if (cp == 0 || cp == ' ' || font.glyphs.containsKey(cp)) continue;
                            int inked = inked(sheet, col * cw, r * ch, cw, ch);
                            font.glyphs.put(cp, new Glyph(sheet, col * cw, r * ch, inked, ch, scale, ascent, (int) (0.5f + inked * scale) + 1));
                        }
                }
            }
            return font;
        }

        private static String group(String text, String regex) {
            var m = Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : null;
        }

        /** The glyph's width to its rightmost inked column, as the game measures it. */
        private static int inked(BufferedImage sheet, int x0, int y0, int w, int h) {
            for (int i = w - 1; i >= 0; i--)
                for (int k = 0; k < h; k++) if ((sheet.getRGB(x0 + i, y0 + k) >>> 24) != 0) return i + 1;
            return 0;
        }

        int width(Graphics2D g, String text) {
            int w = 0;
            for (int cp : text.codePoints().toArray()) w += advance(g, cp);
            return w;
        }

        private int advance(Graphics2D g, int cp) {
            if (cp == ' ') return 4;
            if (cp == 0x200c) return 0;
            var glyph = glyphs.get(cp);
            if (glyph != null) return glyph.advance();
            return (int) Math.ceil(g.getFontMetrics(fallback).getStringBounds(new String(Character.toChars(cp)), g).getWidth()) + 1;
        }

        void draw(Graphics2D g, String text, float x, float y, float s, Color colour) {
            g.setColor(colour);
            float at = x;
            for (int cp : text.codePoints().toArray()) {
                var glyph = glyphs.get(cp);
                if (glyph != null) {
                    float px = glyph.scale() * s, top = y + (7 - glyph.ascent()) * s;
                    for (int i = 0; i < glyph.width(); i++)
                        for (int k = 0; k < glyph.height(); k++)
                            if ((glyph.sheet().getRGB(glyph.x0() + i, glyph.y0() + k) >>> 24) != 0) g.fill(new Rectangle2D.Float(at + i * px, top + k * px, px, px));
                } else if (cp != ' ' && cp != 0x200c) {
                    var saved = g.getTransform();
                    g.translate(at, y);
                    g.scale(s, s);
                    g.setFont(fallback);
                    g.drawString(new String(Character.toChars(cp)), 0, 7);
                    g.setTransform(saved);
                }
                at += advance(g, cp) * s;
            }
        }
    }

    /** A Java2D canvas in GUI pixels, drawn at {@link #SCALE}, in the game font (or a 9 px sans font standing in). */
    private static final class Java2dCanvas implements Canvas {
        private final Graphics2D g;
        private final Font sans = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
        private final FontMetrics metrics;
        private final Measure measure;

        Java2dCanvas(Graphics2D g) {
            this.g = g;
            g.setFont(sans);
            metrics = g.getFontMetrics(sans);
            measure = font != null ? text -> font.width(g, text) : text -> (int) Math.ceil(metrics.getStringBounds(text, g).getWidth());
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
            if (font != null) {
                font.draw(g, text, x, y, scale, new Color(argb, true));
                return;
            }
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

    /** Pages side by side, each under its title. */
    private static void grid(Path file, List<String> titles, List<BufferedImage> pages) throws Exception {
        int w = pages.getFirst().getWidth(), h = pages.getFirst().getHeight(), label = 40, rows = (pages.size() + 1) / 2;
        var sheet = new BufferedImage(2 * w, rows * (h + label), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x101418));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.setColor(new Color(0xEAF1F5));
        for (int i = 0; i < pages.size(); i++) {
            int x = (i % 2) * w, y = (i / 2) * (h + label);
            g.drawString(titles.get(i), x + 14, y + 29);
            g.drawImage(pages.get(i), x, y + label, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", file.toFile());
    }

    /** Whether the last page's columns were taller than their view. */
    private static boolean overflow;

    /** The Control page as {@code FluidDeviceScreen} draws it (renderBg, renderLabels, renderControl, its widgets) in {@code skin}. */
    private static BufferedImage page(Case c, Skin skin, boolean scrolled) {
        var image = new BufferedImage((WIDTH + 8) * SCALE, (HEIGHT + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new Java2dCanvas(g);
        var m = canvas.measure();
        canvas.fill(-4, -4, WIDTH + 4, HEIGHT + 4, 0xFF101418);
        int controlTop = 74, controlBottom = HEIGHT - 52;
        skin.page(canvas, new Rect(0, 0, WIDTH, HEIGHT));
        // The drawing's HMI screen, above the legend where the drawing has line parts (controlWell).
        var unit = com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(c.unit());
        var side = com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(c.side());
        var diagram = HeatDiagram.of(unit, side, c.design());
        var sheet = new HeatFlowsheet(diagram, HeatDiagram.roles(unit, side));
        boolean legend = diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        var slots = FluidGuiLayout.control(WIDTH);
        int legendY = controlBottom - 12, wellBottom = legend ? legendY - 6 : controlBottom;
        var box = skin.well(canvas, new Rect(5, controlTop - 3, slots.drawingRight() - 3, wellBottom - controlTop + 3));
        // The title row: the server's title (vessel and side) and the theme button.
        String themeText = tr(skin == PanelSkin.DARK ? "gui.icheme.fluid.theme.dark" : "gui.icheme.fluid.theme.light");
        int themeWidth = Math.max(m.width(tr("gui.icheme.fluid.theme.light")), m.width(tr("gui.icheme.fluid.theme.dark"))) + 16;
        String vessel = c.unit().equals("FIRED_HEATER") ? "vertical_heater" : c.unit().toLowerCase(Locale.ROOT);
        String title = tr("gui.icheme.heat_side.title", tr("gui.icheme.vessel.title." + vessel), tr("gui.icheme.heat_side." + c.side().toLowerCase(Locale.ROOT)));
        skin.title(canvas, 10, 10, m.cut(title, WIDTH - 36 - themeWidth));
        skin.button(canvas, new Rect(WIDTH - 10 - themeWidth, 5, themeWidth, 18), themeText, Skin.Press.REST, false);
        var tabs = FluidGuiLayout.tabs(WIDTH, 2);
        skin.button(canvas, new Rect(tabs.get(0).x(), 29, tabs.get(0).width(), 18), tr("gui.icheme.fluid.overview"), Skin.Press.REST, false);
        skin.button(canvas, new Rect(tabs.get(1).x(), 29, tabs.get(1).width(), 18), tr("gui.icheme.fluid.control"), Skin.Press.CURRENT, false);
        skin.status(canvas, new Rect(10, 59, WIDTH - 20, 9), "Holding 45.0 °C · steady since 12 s", Palette.ACCENT);
        skin.hDivider(canvas, 8, WIDTH - 8, HEIGHT - 49);
        skin.text(canvas, "View at 102.4 s", 10, HEIGHT - 42, Palette.WARN);
        var footer = FluidGuiLayout.footer(WIDTH);
        String[] footerText = {tr("gui.icheme.fluid.close"), tr("gui.icheme.fluid.apply"), tr("gui.icheme.fluid.mole_basis_short"), "°C", "kPa", "/s"};
        for (int i = 0; i < footer.size(); i++)
            skin.button(canvas, new Rect(footer.get(i).x(), HEIGHT - 27, footer.get(i).width(), 18), footerText[i], i == 1 ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        sheet.fit(box.x(), box.y(), box.w(), box.h());
        sheet.draw(canvas, data());
        if (legend) {
            int lx = 10;
            int[][] marks = {{Palette.TEAL_FILL, Palette.TEAL}, {Palette.YELLOW_FILL, Palette.YELLOW}, {Palette.RED_FILL, Palette.RED}};
            String[] words = {"legend.in_place", "legend.misplaced", "legend.missing"};
            for (int i = 0; i < 3; i++) {
                String word = tr("gui.icheme.fluid." + words[i]);
                if (lx + 11 + m.width(word) > slots.drawingRight()) break;
                canvas.fill(lx, legendY, lx + 8, legendY + 8, marks[i][1]);
                canvas.fill(lx + 1, legendY + 1, lx + 7, legendY + 7, marks[i][0]);
                skin.text(canvas, word, lx + 11, legendY, Palette.MUTED);
                lx += 19 + m.width(word);
            }
        }
        skin.vDivider(canvas, slots.leftX() - 6, controlTop, controlBottom);
        if (slots.split()) skin.vDivider(canvas, slots.rightX() - 7, controlTop, controlBottom);
        // The rows HeatControlColumn builds, in layout C's two columns at compact heights, as buildControl places them.
        var ports = List.of(new FluidView.Port("TUBE_INLET", 363.15, 2e5, 3, new double[3][1], new double[3]),
                new FluidView.Port("SHELL_INLET", 293.15, 2e5, 6, new double[3][1], new double[3]));
        var heat = new FluidView.Heat(c.unit(), c.side(), c.paired(), 318, -1e6, c.unit().equals("AIR_COOLER") ? 1.0 : null, c.unit().equals("AIR_COOLER") ? 1.2e4 : null,
                c.unit().equals("FIRED_HEATER") ? 1.0 : null, c.unit().equals("FIRED_HEATER") ? 473.15 : null, c.unit().equals("FIRED_HEATER") ? 2e6 : null,
                c.unit().equals("EXCHANGER") ? 0.25 : null).with(c.actuator(), c.effort(), "NONE", c.unit().equals("EXCHANGER") ? ports : List.of(), c.lines());
        boolean controllable = !c.unit().equals("FIRED_HEATER") && !(c.unit().equals("KETTLE") && c.side().equals("SHELL"));
        var applied = controllable ? new HeatUnit.Control(c.mode(), c.mode() == HeatUnit.Mode.RATIO ? 0.5 : 318.15, 0, 0.25) : null;
        var input = new HeatControlColumn.Input(heat, applied, c.edit(), controllable, c.mode(), c.powerOn(), "°C");
        var rows = HeatControlColumn.rows(input, (key, a) -> tr("gui.icheme.fluid." + key, a));
        List<Column> columns;
        if (slots.split()) {
            var two = HeatControlColumn.columns(rows);
            columns = List.of(Column.place(two.left(), slots.leftX(), controlTop, slots.leftWidth(), m, Column.Metrics.COMPACT),
                    Column.place(two.right(), slots.rightX(), controlTop, slots.rightWidth(), m, Column.Metrics.COMPACT));
        } else columns = List.of(Column.place(rows, slots.rightX(), controlTop, slots.rightWidth(), m, Column.Metrics.COMPACT));
        int tall = columns.stream().mapToInt(col -> col.bounds().h()).max().orElse(0);
        var view = new ScrollView();
        view.layout(new Rect(slots.leftX() - 2, controlTop, slots.rightX() + slots.rightWidth() - slots.leftX() + 4, controlBottom - controlTop), slots.scrollX(), tall);
        // Scrolled to the end: the wheel at the viewport until it stops.
        while (scrolled && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        overflow = view.max() > 0;
        canvas.clip(view.viewport());
        var drafts = Map.of("heatTemperature", "45.0", "heatDuty", "500.0", "heatRatio", "0.5", "bypass", "25.0", "temperature", "200.0", "lowLimit", "");
        for (var column : columns) {
            column.draw(canvas, -view.offset(), skin);
            for (var p : column.rows()) {
                if (p.row() instanceof Column.Field f) {
                    var r = p.slots().get(f.key());
                    if (view.visible(r)) skin.field(canvas, view.onScreen(r), drafts.getOrDefault(f.key(), ""), f.key().equals("lowLimit") ? tr("gui.icheme.fluid.heat_field.off") : "", false);
                } else if (p.row() instanceof Column.Buttons b) {
                    for (var spec : b.buttons()) {
                        var r = p.slots().get(spec.key());
                        // The side shown is latched, as the screen marks it.
                        var press = !spec.active() ? spec.key().startsWith("side:") ? Skin.Press.CURRENT : Skin.Press.OFF : Skin.Press.REST;
                        if (view.visible(r)) skin.button(canvas, view.onScreen(r), spec.text(), press, false);
                    }
                }
            }
        }
        canvas.unclip();
        view.drawBar(canvas, skin);
        if (!scrolled && skin == PanelSkin.LIGHT)
            System.out.printf(Locale.ROOT, "%-24s columns %3d px in a %d px view%s%n", c.file(), tall, controlBottom - controlTop, cutTexts(columns, m));
        g.dispose();
        return image;
    }

    /** Buttons whose text the skin would cut (a button row that cannot stack them wider). */
    private static String cutTexts(List<Column> columns, Measure m) {
        var cut = new ArrayList<String>();
        for (var column : columns)
            for (var p : column.rows())
                if (p.row() instanceof Column.Buttons b)
                    for (var spec : b.buttons()) if (m.width(spec.text()) > p.slots().get(spec.key()).w() - 6) cut.add(spec.text());
        return cut.isEmpty() ? "" : "; cut buttons: " + cut;
    }
}
