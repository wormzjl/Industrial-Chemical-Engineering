import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.HeatSides;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Software renders of the heat units' Control-tab flowsheet ({@code HeatDiagram}) to PNG, with illustrative readings, so
 * the layout is judged without a game client (HEAT_GUI_PLAN.md section 4). The game font is 9 px high: the renders use a
 * 9 px sans font at the design grid's scale. Run from the worktree root after {@code ./gradlew compileJava}:
 * {@code java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders}
 */
public final class HeatDiagramRender {
    private static final Color BG = new Color(0x202A31), PANEL = new Color(0x28363F), LINE = new Color(0x536C7A), TEXT = new Color(0xEAF1F5),
            MUTED = new Color(0xA9BCC7), ACCENT = new Color(0x79D6CE), BODY = new Color(0x33434D), NOZZLE = new Color(0x55DDFF), EDGE = new Color(0x444441);
    private static final Color[] FILLS = {new Color(0x12392F), new Color(0x3D2E0F), new Color(0x3F1A1A)}, STROKES = {new Color(0x1D9E75), new Color(0xEF9F27), new Color(0xE24B4A)};
    private static final int SCALE = 2;

    public static void main(String[] args) throws Exception {
        var lang = lang(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var B = ExchangerBlueprint.Lines.BYPASS;
        var R = ExchangerBlueprint.Lines.RATIO;
        var N = ExchangerBlueprint.Lines.NONE;
        write(out.resolve("exchanger-shell-bypass.png"), lang, HeatSides.Unit.EXCHANGER, HeatSides.Side.SHELL, new ExchangerBlueprint.Design(N, B));
        write(out.resolve("exchanger-tube-bypass.png"), lang, HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, new ExchangerBlueprint.Design(B, N));
        write(out.resolve("exchanger-both-bypass.png"), lang, HeatSides.Unit.EXCHANGER, HeatSides.Side.SHELL, new ExchangerBlueprint.Design(B, B));
        write(out.resolve("exchanger-ratio.png"), lang, HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, new ExchangerBlueprint.Design(R, R));
        write(out.resolve("exchanger-bypass-ratio.png"), lang, HeatSides.Unit.EXCHANGER, HeatSides.Side.SHELL, new ExchangerBlueprint.Design(R, B));
        write(out.resolve("exchanger-plain.png"), lang, HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, ExchangerBlueprint.Design.NONE);
        write(out.resolve("air-cooler.png"), lang, HeatSides.Unit.AIR_COOLER, HeatSides.Side.PROCESS, ExchangerBlueprint.Design.NONE);
        write(out.resolve("fired-heater.png"), lang, HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT, ExchangerBlueprint.Design.NONE);
        write(out.resolve("kettle.png"), lang, HeatSides.Unit.KETTLE, HeatSides.Side.TUBE, ExchangerBlueprint.Design.NONE);
    }

    private static Map<String, String> lang(Path file) throws Exception {
        var map = new HashMap<String, String>();
        var m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) map.put(m.group(1), m.group(2).replace("\\\"", "\""));
        return map;
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

    private static String text(Map<String, String> lang, AssemblyDiagram.Text text) {
        return lang.getOrDefault(HeatDiagram.KEY + text.key(), "?" + text.key());
    }

    private static void write(Path file, Map<String, String> lang, HeatSides.Unit unit, HeatSides.Side side, ExchangerBlueprint.Design design) throws Exception {
        var d = HeatDiagram.of(unit, side, design);
        var own = HeatDiagram.roles(unit, side);
        int pad = 6;
        var image = new BufferedImage((HeatDiagram.WIDTH + 2 * pad) * SCALE, (HeatDiagram.HEIGHT + 2 * pad) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.scale(SCALE, SCALE);
        g.translate(pad, pad);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        g.setColor(LINE);
        g.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{2, 4}, 0));
        g.drawRect(0, 0, HeatDiagram.WIDTH, HeatDiagram.HEIGHT);
        // Process lines under the parts; utility lines (air, the bundle, the weir) over them, as the screen draws them.
        for (var w : d.wires()) if (!w.utility()) wire(g, w);
        g.setStroke(new BasicStroke(1));
        for (var p : d.pills()) {
            g.setColor(PANEL);
            g.fill(new RoundRectangle2D.Double(p.x(), p.y() - 9, p.w(), 18, 18, 18));
            g.setColor(LINE);
            g.draw(new RoundRectangle2D.Double(p.x(), p.y() - 9, p.w(), 18, 18, 18));
            centred(g, text(lang, p.name()) + " " + value(p.value()), p.x() + p.w() / 2, p.y(), own.contains(p.role()) ? ACCENT : MUTED, p.w() - 4);
        }
        int k = 0;
        for (var p : d.parts()) {
            Color fill = BODY, stroke = STROKES[0];
            if (p.line()) {
                fill = FILLS[k % 3];
                stroke = STROKES[k++ % 3];
            }
            int cx = p.x() + p.w() / 2, cy = p.y() + p.h() / 2;
            switch (p.glyph()) {
                case SHELL, DRUM -> {
                    int r = p.glyph() == HeatDiagram.Glyph.SHELL ? p.h() : p.h() * 2 / 3;
                    g.setColor(fill);
                    g.fill(new RoundRectangle2D.Double(p.x(), p.y(), p.w(), p.h(), r, r));
                    g.setColor(stroke);
                    g.draw(new RoundRectangle2D.Double(p.x(), p.y(), p.w(), p.h(), r, r));
                }
                case FAN, METER -> {
                    g.setColor(fill);
                    g.fillOval(p.x(), p.y(), p.w(), p.h());
                    g.setColor(stroke);
                    g.drawOval(p.x(), p.y(), p.w(), p.h());
                    if (p.glyph() == HeatDiagram.Glyph.METER) centred(g, "FT", cx, cy, TEXT, 20);
                }
                case VALVE -> {
                    int vy = p.y() + 17;
                    g.setColor(fill);
                    g.fillPolygon(new int[]{cx - 8, cx - 8, cx + 8, cx + 8}, new int[]{vy - 6, vy + 6, vy - 6, vy + 6}, 4);
                    g.setColor(stroke);
                    g.drawPolygon(new int[]{cx - 8, cx - 8, cx + 8, cx + 8}, new int[]{vy - 6, vy + 6, vy - 6, vy + 6}, 4);
                    g.drawLine(cx, vy, cx, vy - 11);
                    g.fillRect(cx - 5, vy - 16, 10, 5);
                }
                case VALVE_DOWN -> {
                    int vx = p.x() + 17;
                    g.setColor(fill);
                    g.fillPolygon(new int[]{vx - 6, vx + 6, vx - 6, vx + 6}, new int[]{cy - 8, cy - 8, cy + 8, cy + 8}, 4);
                    g.setColor(stroke);
                    g.drawPolygon(new int[]{vx - 6, vx + 6, vx - 6, vx + 6}, new int[]{cy - 8, cy - 8, cy + 8, cy + 8}, 4);
                    g.drawLine(vx, cy, vx - 11, cy);
                    g.fillRect(vx - 16, cy - 5, 5, 10);
                }
                case BURNER -> {
                    g.setColor(new Color(0xEF9F27));
                    g.fillPolygon(new int[]{p.x(), p.x() + p.w(), cx}, new int[]{p.y() + p.h(), p.y() + p.h(), p.y()}, 3);
                }
                default -> {
                    g.setColor(fill);
                    g.fillRect(p.x(), p.y(), p.w(), p.h());
                    g.setColor(stroke);
                    g.drawRect(p.x(), p.y(), p.w(), p.h());
                }
            }
            if (p.label() != null) centred(g, text(lang, p.label()), cx, p.y() + p.h() / 2 - 6, TEXT, p.w());
        }
        for (var w : d.wires()) if (w.utility()) wire(g, w);
        g.setStroke(new BasicStroke(1));
        for (var l : d.labels()) anchored(g, text(lang, l.text()), l.x(), l.y(), l.align(), MUTED);
        for (var t : d.tags()) anchored(g, value(t.value()), t.x(), t.y(), t.align(), TEXT);
        for (var n : d.nozzles()) {
            g.setColor(EDGE);
            g.fillRect(n.x() - 5, n.y() - 5, 10, 10);
            g.setColor(NOZZLE);
            g.fillRect(n.x() - 4, n.y() - 4, 8, 8);
        }
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    private static void wire(Graphics2D g, HeatDiagram.Wire w) {
        var p = w.points();
        g.setColor(w.utility() ? LINE : MUTED);
        g.setStroke(w.utility() ? new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{2, 1}, 0) : new BasicStroke(1.2f));
        for (int i = 0; i + 3 < p.length; i += 2) g.drawLine(p[i], p[i + 1], p[i + 2], p[i + 3]);
        if (w.arrow()) {
            int n = p.length, ex = p[n - 2], ey = p[n - 1], dx = Integer.signum(p[n - 2] - p[n - 4]), dy = Integer.signum(p[n - 1] - p[n - 3]);
            g.fillPolygon(new int[]{ex, ex - 4 * dx - 3 * dy, ex - 4 * dx + 3 * dy}, new int[]{ey, ey - 4 * dy - 3 * dx, ey - 4 * dy + 3 * dx}, 3);
        }
    }

    private static void centred(Graphics2D g, String text, int x, int y, Color colour, int width) {
        var m = g.getFontMetrics();
        int w = m.stringWidth(text);
        g.setColor(w > width ? new Color(0xFF6060) : colour);
        g.drawString(text, x - w / 2, y + m.getAscent() / 2 - 1);
    }

    private static void anchored(Graphics2D g, String text, int x, int y, AssemblyDiagram.Align align, Color colour) {
        var m = g.getFontMetrics();
        int w = m.stringWidth(text);
        int tx = switch (align) {
            case LEFT -> x;
            case CENTRE -> x - w / 2;
            case RIGHT -> x - w;
        };
        g.setColor(colour);
        g.drawString(text, tx, y + m.getAscent() / 2 - 1);
    }
}
