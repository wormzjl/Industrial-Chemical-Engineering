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
        // The styles and the industrial looks tried for the owner, two by two, for the units that differ most.
        var styles = out.resolve("styles");
        var industrial = out.resolve("industrial");
        Files.createDirectories(styles);
        Files.createDirectories(industrial);
        for (var c : cases)
            if (List.of("exchanger-shell-bypass", "exchanger-ratio", "air-cooler", "fired-heater").contains(c.file())) {
                styles(styles.resolve(c.file() + ".png"), c);
                industrial(industrial.resolve(c.file() + ".png"), c);
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

    /** A button's state: at rest, switched off, the page or side shown (latched), a chosen mode (style B), the confirm button. */
    private enum Press {
        REST, OFF, CURRENT, CHOSEN, PRIMARY
    }

    /**
     * How a page draws: the kit's {@link Skin} for the rows, and the page, its title, the game's widgets, the drawing's
     * well and the dividers. {@link #PLAIN} is today's look; the {@link Industrial} looks are tried for the owner.
     */
    private interface Look extends Skin {
        void page(Canvas c, Rect r);

        void title(Canvas c, int x, int y, String text);

        void button(Canvas c, Rect r, String text, Press press);

        void field(Canvas c, Rect r, String value, String hint);

        /** Whether the drawing sits in a well (else on the page, as today). */
        boolean framed();

        /** Draws the drawing's well {@code r}; returns the box the drawing fits in. */
        Rect well(Canvas c, Rect r);

        void vDivider(Canvas c, int x, int y0, int y1);

        void hDivider(Canvas c, int x0, int x1, int y);
    }

    /** Today's look: the kit's plain skin, the game's widgets as plain boxes. */
    private static final Look PLAIN = new Look() {
        @Override
        public void mark(Canvas c, int x, int y, boolean selected) {
            Skin.PLAIN.mark(c, x, y, selected);
        }

        @Override
        public void bar(Canvas c, Rect r, double share) {
            Skin.PLAIN.bar(c, r, share);
        }

        @Override
        public void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging) {
            Skin.PLAIN.scrollbar(c, track, thumb, dragging);
        }

        @Override
        public void page(Canvas c, Rect r) {
            c.fill(r.x(), r.y(), r.right(), r.bottom(), Palette.BG);
            c.outline(r.x(), r.y(), r.w(), r.h(), Palette.LINE);
        }

        @Override
        public void title(Canvas c, int x, int y, String text) {
            c.text(text, x, y, Palette.TEXT);
        }

        /** A vanilla button as a plain box, grey with its text (dimmed when off); a chosen mode button (style B) with an accent edge. */
        @Override
        public void button(Canvas c, Rect r, String text, Press press) {
            boolean chosen = press == Press.CHOSEN, active = press == Press.REST || press == Press.PRIMARY;
            c.fill(r.x(), r.y(), r.right(), r.bottom(), chosen ? Palette.ACCENT : 0xFF000000);
            c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, chosen ? 0xFF1E3A36 : active ? 0xFF6F6F6F : 0xFF2C2C2C);
            centred(c, r, text, chosen ? Palette.ACCENT : active ? 0xFFFFFFFF : 0xFFA0A0A0);
        }

        /** A vanilla field as a plain box, dark with its value. */
        @Override
        public void field(Canvas c, Rect r, String value, String hint) {
            c.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFFA0A0A0);
            c.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF000000);
            c.text(value.isEmpty() ? hint : value, r.x() + 4, r.y() + 5, value.isEmpty() ? 0xFF808080 : 0xFFE0E0E0);
        }

        @Override
        public boolean framed() {
            return false;
        }

        @Override
        public Rect well(Canvas c, Rect r) {
            return r;
        }

        @Override
        public void vDivider(Canvas c, int x, int y0, int y1) {
            c.vLine(x, y0, y1, Palette.LINE);
        }

        @Override
        public void hDivider(Canvas c, int x0, int x1, int y) {
            c.hLine(x0, x1, y, Palette.LINE);
        }
    };

    private static void centred(Canvas c, Rect r, String text, int argb) {
        String shown = c.measure().cut(text, r.w() - 6);
        c.text(shown, r.x() + (r.w() - c.measure().width(shown)) / 2, r.y() + (r.h() - 9) / 2 + 1, argb);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** A plate: its outline, its face, and its top-left and bottom-right edges (equal to the face: flat). */
    private record Plate(int outline, int face, int light, int dark) {
        static Plate flat(int outline, int face) {
            return new Plate(outline, face, face, face);
        }

        /** Pushed in: the edges swapped. */
        Plate pressed() {
            return new Plate(outline, face, dark, light);
        }
    }

    /** A sunken box: its fill, its top-left edge and its bottom-right edge. */
    private record Sunken(int fill, int dark, int light) {
    }

    private record Widget(Plate plate, int text) {
    }

    /** The page's title: a bar shaded from {@code from} to {@code to} (0: none), or on a plate (null: none); its text colour. */
    private record Title(int from, int to, Plate plate, int text) {
    }

    /** The tones the rows name: text, muted, accent, warning. */
    private record Ink(int text, int dim, int accent, int warn) {
    }

    private enum Mark {
        /** A pilot light: a round lens in a ring, lit or dark. */
        LAMP,
        /** A square, filled when chosen. */
        SQUARE,
        /** A sunken round button with a dot. */
        RADIO
    }

    /** A bar: its lit and dark colours, in LED segments or solid, with ticks (0: none). */
    private record Gauge(int lit, int dark, boolean segments, int ticks, int tick) {
    }

    /**
     * An industrial look after a real plant's screens and panels: plates (corners cut unless {@code square}), sunken
     * fields and screens, lamps or squares for the marks, a gauge bar; a title bar or title plate; panel screws.
     */
    private record Industrial(String name, Plate page, Title head, Widget rest, Widget current, Widget primary, Widget off,
                              Sunken input, int readout, int hintInk, Sunken screen, int bezel, Ink ink, Mark marks, int lamp, int lampOff,
                              Gauge gauge, int groove, int grooveLight, boolean square, boolean screws) implements Look {
        @Override
        public int tone(int p) {
            return p == Palette.TEXT ? ink.text() : p == Palette.MUTED ? ink.dim() : p == Palette.ACCENT ? ink.accent() : p == Palette.WARN ? ink.warn() : p;
        }

        private void plate(Canvas c, Rect r, Plate p) {
            int x0 = r.x(), y0 = r.y(), x1 = r.right(), y1 = r.bottom(), cut = square ? 0 : 1;
            c.fill(x0 + cut, y0, x1 - cut, y0 + 1, p.outline());
            c.fill(x0 + cut, y1 - 1, x1 - cut, y1, p.outline());
            c.fill(x0, y0 + 1, x0 + 1, y1 - 1, p.outline());
            c.fill(x1 - 1, y0 + 1, x1, y1 - 1, p.outline());
            c.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, p.face());
            c.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 2, p.light());
            c.fill(x0 + 1, y0 + 2, x0 + 2, y1 - 2, p.light());
            c.fill(x0 + 2, y1 - 2, x1 - 1, y1 - 1, p.dark());
            c.fill(x1 - 2, y0 + 2, x1 - 1, y1 - 2, p.dark());
        }

        private static void sunken(Canvas c, Rect r, Sunken s) {
            c.fill(r.x(), r.y(), r.right(), r.bottom(), s.fill());
            c.fill(r.x(), r.y(), r.right() - 1, r.y() + 1, s.dark());
            c.fill(r.x(), r.y() + 1, r.x() + 1, r.bottom() - 1, s.dark());
            c.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), s.light());
            c.fill(r.right() - 1, r.y(), r.right(), r.bottom() - 1, s.light());
        }

        @Override
        public void page(Canvas c, Rect r) {
            plate(c, r, page);
            if (head.from() != 0) {
                int x0 = r.x() + 1, x1 = r.right() - 1, n = 48;
                for (int i = 0; i < n; i++) c.fill(x0 + (x1 - x0) * i / n, r.y() + 1, x0 + (x1 - x0) * (i + 1) / n, r.y() + 23, mix(head.from(), head.to(), i / (n - 1.0)));
            }
            if (screws) for (int[] at : new int[][]{{r.x() + 2, r.y() + 2}, {r.right() - 7, r.y() + 2}, {r.x() + 2, r.bottom() - 7}, {r.right() - 7, r.bottom() - 7}}) screw(c, at[0], at[1]);
        }

        /** A slotted panel screw, 5 by 5. */
        private void screw(Canvas c, int x, int y) {
            c.fill(x + 1, y, x + 4, y + 5, page.outline());
            c.fill(x, y + 1, x + 5, y + 4, page.outline());
            c.fill(x + 1, y + 1, x + 4, y + 4, 0xFFB8BBB7);
            c.fill(x + 1, y + 1, x + 2, y + 2, 0xFFEEF0EC);
            c.fill(x + 1, y + 2, x + 4, y + 3, 0xFF4A4D49);
        }

        @Override
        public void title(Canvas c, int x, int y, String text) {
            if (head.plate() != null) plate(c, new Rect(x - 3, y - 4, c.measure().width(text) + 6, 16), head.plate());
            c.text(text, x, y, head.text());
        }

        @Override
        public void button(Canvas c, Rect r, String text, Press press) {
            var w = switch (press) {
                case REST -> rest;
                case OFF -> off;
                case CURRENT, CHOSEN -> current;
                case PRIMARY -> primary;
            };
            boolean in = press == Press.CURRENT || press == Press.CHOSEN;
            plate(c, r, in ? w.plate().pressed() : w.plate());
            // A pushed-in bevelled button's legend sits a pixel lower and right.
            boolean bevel = w.plate().light() != w.plate().face();
            centred(c, in && bevel ? r.moved(1, 1) : r, text, w.text());
        }

        @Override
        public void field(Canvas c, Rect r, String value, String hint) {
            sunken(c, r, input);
            c.text(value.isEmpty() ? hint : value, r.x() + 4, r.y() + 5, value.isEmpty() ? hintInk : readout);
        }

        @Override
        public boolean framed() {
            return true;
        }

        @Override
        public Rect well(Canvas c, Rect r) {
            var inner = r;
            if (bezel != 0) {
                c.fill(r.x(), r.y(), r.right(), r.bottom(), bezel);
                inner = new Rect(r.x() + 3, r.y() + 3, r.w() - 6, r.h() - 6);
            }
            sunken(c, inner, screen);
            return new Rect(inner.x() + 4, inner.y() + 4, inner.w() - 8, inner.h() - 8);
        }

        @Override
        public void vDivider(Canvas c, int x, int y0, int y1) {
            c.fill(x, y0, x + 1, y1, groove);
            if (grooveLight != 0) c.fill(x + 1, y0, x + 2, y1, grooveLight);
        }

        @Override
        public void hDivider(Canvas c, int x0, int x1, int y) {
            c.fill(x0, y, x1, y + 1, groove);
            if (grooveLight != 0) c.fill(x0, y + 1, x1, y + 2, grooveLight);
        }

        @Override
        public void mark(Canvas c, int x, int y, boolean selected) {
            switch (marks) {
                case LAMP -> {
                    int ring = rest.plate().outline(), lens = selected ? lamp : lampOff;
                    c.fill(x + 2, y, x + 6, y + 8, ring);
                    c.fill(x, y + 2, x + 8, y + 6, ring);
                    c.fill(x + 1, y + 1, x + 7, y + 7, ring);
                    c.fill(x + 2, y + 1, x + 6, y + 7, lens);
                    c.fill(x + 1, y + 2, x + 7, y + 6, lens);
                    c.fill(x + 2, y + 2, x + 4, y + 3, selected ? 0xC0FFFFFF : 0x30FFFFFF);
                }
                case SQUARE -> {
                    c.fill(x, y, x + 8, y + 8, rest.plate().outline());
                    c.fill(x + 1, y + 1, x + 7, y + 7, input.fill());
                    if (selected) c.fill(x + 2, y + 2, x + 6, y + 6, lamp);
                }
                case RADIO -> {
                    sunken(c, new Rect(x, y, 8, 8), input);
                    if (selected) {
                        c.fill(x + 3, y + 2, x + 5, y + 6, lamp);
                        c.fill(x + 2, y + 3, x + 6, y + 5, lamp);
                    }
                }
            }
        }

        @Override
        public void bar(Canvas c, Rect r, double share) {
            sunken(c, r, input);
            int x0 = r.x() + 1, inner = r.w() - 2, lit = (int) Math.round(inner * share);
            if (gauge.segments())
                for (int s = 1; s + 3 <= inner - 1; s += 4) c.fill(x0 + s, r.y() + 2, x0 + s + 3, r.bottom() - 2, s + 3 <= lit ? gauge.lit() : gauge.dark());
            else c.fill(x0, r.y() + 1, x0 + lit, r.bottom() - 1, gauge.lit());
            for (int k = 1; k < gauge.ticks(); k++) {
                int tx = x0 + inner * k / gauge.ticks();
                c.fill(tx, r.bottom() - 3, tx + 1, r.bottom() - 1, gauge.tick());
            }
        }

        @Override
        public void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging) {
            sunken(c, track, input);
            var p = (dragging ? current : rest).plate();
            plate(c, thumb, p);
            int cy = thumb.y() + thumb.h() / 2, grip = p.dark() != p.face() ? p.dark() : p.outline();
            for (int i = -2; i <= 2; i += 2) c.fill(thumb.x() + 2, cy + i, thumb.right() - 2, cy + i + 1, grip);
        }
    }

    /**
     * The industrial looks tried for the owner (owner: "real industrial style"), each after a real plant's screens or
     * panels; the flowsheet sits on a dark process screen in each.
     */
    private static final List<Industrial> INDUSTRIAL = List.of(
            // ISA-101 high-performance HMI: grey, flat, colour only for state; values in dark blue; a grey analog bar.
            new Industrial("HMI, ISA-101 high-performance: grey, flat, colour only for state",
                    Plate.flat(0xFF8C8C8C, 0xFFD2D2D2), new Title(0xFF5A5A5A, 0xFF5A5A5A, null, 0xFFFFFFFF),
                    new Widget(Plate.flat(0xFF6E6E6E, 0xFFBEBEBE), 0xFF1A1A1A), new Widget(Plate.flat(0xFF1A1A1A, 0xFF5A5A5A), 0xFFFFFFFF),
                    new Widget(Plate.flat(0xFF1A1A1A, 0xFFBEBEBE), 0xFF000000), new Widget(Plate.flat(0xFFA8A8A8, 0xFFD9D9D9), 0xFF9A9A9A),
                    new Sunken(0xFFF5F5F5, 0xFF6E6E6E, 0xFF6E6E6E), 0xFF12355B, 0xFF8C8C8C, new Sunken(0xFF1E2226, 0xFF6E6E6E, 0xFF6E6E6E), 0,
                    new Ink(0xFF1A1A1A, 0xFF4D4D4D, 0xFF12355B, 0xFF8A5A00), Mark.SQUARE, 0xFF4D4D4D, 0,
                    new Gauge(0xFF6A6A6A, 0, false, 4, 0xFF1A1A1A), 0xFFA8A8A8, 0xFFE6E6E6, true, false),
            // Classic SCADA (WinCC, InTouch): 3D grey, a navy title bar, white entry fields, a black process screen; the running mode green.
            new Industrial("SCADA, classic: 3D grey, navy title bar, black process screen",
                    new Plate(0xFF404040, 0xFFD4D0C8, 0xFFFFFFFF, 0xFF808080), new Title(0xFF0A246A, 0xFFA6CAF0, null, 0xFFFFFFFF),
                    new Widget(new Plate(0xFF404040, 0xFFD4D0C8, 0xFFFFFFFF, 0xFF808080), 0xFF000000),
                    new Widget(new Plate(0xFF404040, 0xFF00A000, 0xFF60D060, 0xFF006000), 0xFFFFFFFF),
                    new Widget(new Plate(0xFF000000, 0xFFD4D0C8, 0xFFFFFFFF, 0xFF808080), 0xFF000000),
                    new Widget(new Plate(0xFF404040, 0xFFD4D0C8, 0xFFFFFFFF, 0xFF808080), 0xFF808080),
                    new Sunken(0xFFFFFFFF, 0xFF808080, 0xFFFFFFFF), 0xFF000000, 0xFF808080, new Sunken(0xFF000000, 0xFF808080, 0xFFFFFFFF), 0,
                    new Ink(0xFF000000, 0xFF404040, 0xFF0A246A, 0xFFA03000), Mark.RADIO, 0xFF000000, 0,
                    new Gauge(0xFF0A246A, 0xFFFFFFFF, true, 0, 0), 0xFF808080, 0xFFFFFFFF, true, false),
            // A control panel door: RAL 7035 light grey, an engraved title plate, IEC 60073 push buttons (black general,
            // green running, blue confirm), green pilot lights, red LED panel meters, an HMI screen in a black bezel.
            new Industrial("Control panel: RAL 7035 door, IEC 60073 push buttons, LED meters",
                    new Plate(0xFF6E716D, 0xFFC9CCC8, 0xFFE2E5E1, 0xFFA5A8A4), new Title(0, 0, new Plate(0xFF000000, 0xFF1A1A1A, 0xFF3A3A3A, 0xFF0E0E0E), 0xFFF0F0F0),
                    new Widget(new Plate(0xFF111111, 0xFF2A2A2A, 0xFF4A4A4A, 0xFF161616), 0xFFF0F0F0),
                    new Widget(new Plate(0xFF111111, 0xFF2DB34A, 0xFF7EE08F, 0xFF1B7A30), 0xFF0A1A0E),
                    new Widget(new Plate(0xFF111111, 0xFF1F5FBF, 0xFF5A8FE0, 0xFF143F80), 0xFFFFFFFF),
                    new Widget(new Plate(0xFF5A5D59, 0xFF8A8D89, 0xFF9C9F9B, 0xFF767975), 0xFFBFC2BE),
                    new Sunken(0xFF0A0A0A, 0xFF2A2A2A, 0xFF7A7D79), 0xFFFF4A2A, 0xFF7A2A1A, new Sunken(0xFF10161B, 0xFF000000, 0xFF3A3A3A), 0xFF1A1A1A,
                    new Ink(0xFF1A1A1A, 0xFF3C3F3B, 0xFF1E7A34, 0xFF9A6A00), Mark.LAMP, 0xFF3BE060, 0xFF1F3A26,
                    new Gauge(0xFF3BE060, 0xFF1F3A26, true, 0, 0), 0xFFA5A8A4, 0xFFE2E5E1, false, true),
            // A dark control-room HMI (low ambient light): dark grey, flat, a grey bar; pale blue for what is chosen, amber for warnings.
            new Industrial("Control room, dark: low-light HMI, grey with pale blue selection",
                    Plate.flat(0xFF45484B, 0xFF2B2D2F), new Title(0xFF1E2022, 0xFF1E2022, null, 0xFFD8DADC),
                    new Widget(Plate.flat(0xFF5A5E63, 0xFF3E4145), 0xFFD8DADC), new Widget(Plate.flat(0xFFA8D1F5, 0xFF4F5A66), 0xFFFFFFFF),
                    new Widget(Plate.flat(0xFF8FB4D9, 0xFF3E4145), 0xFFFFFFFF), new Widget(Plate.flat(0xFF45484B, 0xFF323538), 0xFF6E7276),
                    new Sunken(0xFF1A1C1E, 0xFF5A5E63, 0xFF5A5E63), 0xFFCFE3F5, 0xFF6E7276, new Sunken(0xFF1A1C1E, 0xFF45484B, 0xFF45484B), 0,
                    new Ink(0xFFD8DADC, 0xFF9A9EA3, 0xFFA8D1F5, 0xFFF2C12E), Mark.SQUARE, 0xFFA8D1F5, 0,
                    new Gauge(0xFF8A8E93, 0, false, 4, 0xFF5A5E63), 0xFF45484B, 0, true, false));

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
        ImageIO.write(page(c, Style.LIST, PLAIN, scrolled), "png", file.toFile());
    }

    /** One case in the four styles, today's look. */
    private static void styles(Path file, Case c) throws Exception {
        var pages = new ArrayList<BufferedImage>();
        for (var s : Style.values()) pages.add(page(c, s, PLAIN, false));
        grid(file, java.util.Arrays.stream(Style.values()).map(s -> s.title).toList(), pages);
    }

    /** One case in style C, in each industrial look. */
    private static void industrial(Path file, Case c) throws Exception {
        var pages = new ArrayList<BufferedImage>();
        for (var look : INDUSTRIAL) pages.add(page(c, Style.SPLIT, look, false));
        var titles = new ArrayList<String>();
        for (int i = 0; i < INDUSTRIAL.size(); i++) titles.add((char) ('1' + i) + "  " + INDUSTRIAL.get(i).name());
        grid(file, titles, pages);
    }

    /** Pages two by two, each under its title, as they open. */
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

    private static BufferedImage page(Case c, Style style, Look look, boolean scrolled) {
        var image = new BufferedImage((WIDTH + 8) * SCALE, (HEIGHT + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new Java2dCanvas(g);
        canvas.fill(-4, -4, WIDTH + 4, HEIGHT + 4, 0xFF101418);
        // The page's frame as the screen draws it: title, tabs, status, separators, footer.
        look.page(canvas, new Rect(0, 0, WIDTH, HEIGHT));
        String block = tr("block.icheme." + c.unit().toLowerCase(Locale.ROOT));
        look.title(canvas, 10, 10, block.startsWith("?") ? c.unit() + " · " + c.side() : block);
        var tabs = FluidGuiLayout.tabs(WIDTH, 2);
        look.button(canvas, new Rect(tabs.get(0).x(), 29, tabs.get(0).width(), 18), tr("gui.icheme.fluid.overview"), Press.REST);
        look.button(canvas, new Rect(tabs.get(1).x(), 29, tabs.get(1).width(), 18), tr("gui.icheme.fluid.control"), Press.CURRENT);
        canvas.text("Holding 45.0 C · steady since 12 s", 10, 59, look.tone(Palette.ACCENT));
        int controlTop = 74, controlBottom = HEIGHT - 52;
        look.hDivider(canvas, 8, WIDTH - 8, HEIGHT - 49);
        var footer = FluidGuiLayout.footer(WIDTH);
        String[] footerText = {tr("gui.icheme.fluid.close"), tr("gui.icheme.fluid.apply"), "Mole", "C", "kPa", "/s"};
        for (int i = 0; i < footer.size(); i++)
            look.button(canvas, new Rect(footer.get(i).x(), HEIGHT - 27, footer.get(i).width(), 18), footerText[i], i == 1 ? Press.PRIMARY : Press.REST);
        canvas.text("View at 102.4 s", 10, HEIGHT - 42, look.tone(Palette.WARN));
        // Each style's split of the page: A and B as FluidGuiLayout.control places it; C and D with a narrower drawing.
        var slots = FluidGuiLayout.control(WIDTH);
        int drawingRight = switch (style) {
            case LIST, COMPACT -> slots.drawingRight();
            case SPLIT -> WIDTH * 38 / 100;
            case CARDS -> WIDTH * 45 / 100;
        };
        int panelX = drawingRight + 10, panelRight = slots.panelX() + slots.panelWidth();
        // The drawing, as FluidDeviceScreen.renderControl lays it out; in a well where the look has one.
        var unit = com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(c.unit());
        var side = com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(c.side());
        var diagram = HeatDiagram.of(unit, side, c.design());
        var sheet = new HeatFlowsheet(diagram, HeatDiagram.roles(unit, side));
        boolean legend = diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        int bottom = controlBottom - (legend || c.lines() != null ? 24 : 4);
        var box = new Rect(10, controlTop, drawingRight - 10, bottom - controlTop);
        if (look.framed()) box = look.well(canvas, new Rect(5, controlTop - 3, drawingRight - 3, bottom - controlTop + 5));
        sheet.fit(box.x(), box.y(), box.w(), box.h());
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
                canvas.text(word, lx + 11, ly, look.tone(Palette.MUTED));
                lx += 19 + canvas.measure().width(word);
            }
        }
        if (check > 0) look.button(canvas, new Rect(drawingRight - check, controlBottom - 19, check, 18), tr("gui.icheme.fluid.check_lines"), Press.REST);
        look.vDivider(canvas, panelX - 6, controlTop, controlBottom);
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
            case LIST -> column(canvas, look, rows, List.of(), panelX, panelRight, controlTop, controlBottom, Column.Metrics.DEFAULT, scrolled);
            case COMPACT -> column(canvas, look, compact(rows, c.effort()), List.of(), panelX, panelRight, controlTop, controlBottom, Column.Metrics.COMPACT, scrolled);
            case SPLIT -> {
                var halves = split(rows);
                int left = panelX + 105;
                if (look.framed()) look.vDivider(canvas, left + 5, controlTop, controlBottom);
                yield Math.max(column(canvas, look, halves.get(0), List.of(), panelX, left, controlTop, controlBottom, Column.Metrics.COMPACT, scrolled),
                        column(canvas, look, halves.get(1), List.of(), left + 12, panelRight, controlTop, controlBottom, Column.Metrics.COMPACT, scrolled));
            }
            case CARDS -> {
                var panels = new ArrayList<int[]>();
                var cards = cards(rows, panels);
                yield column(canvas, look, cards, panels, panelX + 3, panelRight - 3, controlTop + 3, controlBottom, new Column.Metrics(11, 12, 20, 20, 10), scrolled) + 3;
            }
        };
        if (!scrolled && look == PLAIN) System.out.printf(Locale.ROOT, "%-24s %-8s column %3d px in a %d px view%n", c.file(), style, tall, controlBottom - controlTop);
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
     * A column placed from {@code x} to {@code right} at {@code metrics} in its own scrolled view, in {@code look}: panels
     * under its rows, the rows, the widgets and its bar; returns the column's height.
     */
    private static int column(Java2dCanvas canvas, Look look, List<Column.Row> rows, List<int[]> panels, int x, int right, int top, int bottom, Column.Metrics metrics, boolean scrolled) {
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
        column.draw(canvas, -view.offset(), look);
        var drafts = Map.of("heatTemperature", "45.0", "heatDuty", "500.0", "heatRatio", "0.5", "bypass", "25.0", "temperature", "200.0", "lowLimit", "");
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) look.field(canvas, view.onScreen(r), drafts.getOrDefault(f.key(), ""), f.key().equals("lowLimit") ? tr("gui.icheme.fluid.heat_field.off") : "");
            } else if (p.row() instanceof Column.Buttons b) {
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    // The side shown is latched, not switched off; a chosen mode (style B) reads as chosen.
                    var press = spec.key().startsWith(CHOSEN) ? Press.CHOSEN : spec.key().startsWith("side:") && !spec.active() ? Press.CURRENT
                            : spec.active() ? Press.REST : Press.OFF;
                    if (view.visible(r)) look.button(canvas, view.onScreen(r), spec.text(), press);
                }
            }
        }
        canvas.unclip();
        view.drawBar(canvas, look);
        return column.bounds().h();
    }
}
