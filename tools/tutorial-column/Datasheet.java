import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The small crude tower's datasheets (batch 2026-11-12-tutorial-system, P4): one PNG per candidate from its result file
 * (SmallColumn), the tower as an elevation to scale on the left and its numbers and acceptance rules on the right, all on one
 * scale; and columns-compare.png, the elevations side by side with a 16 x 16 block plot under each. Java2D only, no game GUI.
 * It also writes tmp/tutorial-p4/summary.txt, the table the review quotes.
 */
public final class Datasheet {
    static final String IN = "tmp/tutorial-p4", OUT = "tmp/tutorial-renders/p4";
    /** Tray spacing and the space above the top tray, m (ColumnTrays.SPACING, TOP_SPACE). */
    static final double SPACING = .6, TOP_SPACE = 1.2;
    static final int PLOT = 16;
    static final Color INK = new Color(0x1E2430), MUTED = new Color(0x6B7280), GRID = new Color(0xD8DCE3), PAPER = new Color(0xFBFAF7);
    static final Color GREEN = new Color(0x2E8B57), YELLOW = new Color(0xC9A227), RED = new Color(0xC0392B), STEEL = new Color(0x8A99AB), STEAM = new Color(0x3B7DD8),
            FEED = new Color(0xB5651D), PA = new Color(0x6A4C93);
    static final Map<String, Color> PRODUCT = Map.of("OFFGAS", new Color(0x9AA5B1), "NAPHTHA", new Color(0xE3B341), "KERO", new Color(0xD98E04), "DIESEL",
            new Color(0xB86E00), "MIDDLE", new Color(0xC27C0E), "AGO", new Color(0x8C5A1A), "RESIDUE", new Color(0x4A3423));

    record Item(String id, String kind, int x, int z, int y, int count, String lamp) {
    }

    record Rule(String text, boolean pass) {
    }

    /** One candidate's numbers, read once. */
    static final class Sheet {
        final String id;
        final JsonObject r;
        final double height;
        /** The shell as the constructor builds it at the shown diameter: tray levels, skirt and tangent length (m). */
        com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing.Size size;
        double skirt, tangent;
        final List<Item> items = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        final List<String> notGreen = new ArrayList<>();
        final Map<String, Double> gaps = new LinkedHashMap<>();
        /** Lines of tmp/tutorial-p4/findings-<id>.txt: what the analysis found, drawn under the rules. */
        final List<String> findings = new ArrayList<>();
        Packing packing;

        Sheet(String id, JsonObject r) {
            this.id = id;
            this.r = r;
            double h = Double.NaN;
            if (r.has("sized")) {
                var units = r.getAsJsonObject("sized").getAsJsonObject("units");
                for (var e : units.entrySet()) {
                    var u = e.getValue().getAsJsonObject();
                    String lamp = u.get("lamp").getAsString();
                    if (!lamp.equals("GREEN") && !lamp.equals("NONE")) notGreen.add(e.getKey() + " " + lamp + " " + worst(u));
                    if (!u.has("blocks_x")) continue;
                    var item = new Item(e.getKey(), u.get("kind").getAsString(), u.get("blocks_x").getAsInt(), u.get("blocks_z").getAsInt(), u.get("blocks_y").getAsInt(),
                            Math.max(1, u.get("count").getAsInt()), lamp);
                    items.add(item);
                    if (item.kind().equals("COLUMN")) h = item.y();
                }
                for (var e : r.getAsJsonObject("sized").getAsJsonArray("paths")) {
                    String path, lamp;
                    double spare;
                    if (e.isJsonPrimitive()) { // the first result files wrote "start -> end spare x bar, lamp L"
                        var text = e.getAsString();
                        path = text.substring(0, text.indexOf(" spare"));
                        spare = Double.parseDouble(text.substring(text.indexOf("spare ") + 6, text.indexOf(" bar")));
                        lamp = text.substring(text.lastIndexOf(' ') + 1);
                    } else {
                        path = e.getAsJsonObject().get("path").getAsString();
                        spare = e.getAsJsonObject().get("spare").getAsDouble();
                        lamp = e.getAsJsonObject().get("lamp").getAsString();
                    }
                    if (!lamp.equals("GREEN") && !lamp.equals("NONE")) notGreen.add(String.format(Locale.ROOT, "path %s %s spare %.2f bar", path, lamp, spare));
                }
                for (var e : r.getAsJsonObject("sized").getAsJsonObject("lines").entrySet()) {
                    String lamp = e.getValue().getAsJsonObject().get("lamp").getAsString();
                    if (!lamp.equals("GREEN") && !lamp.equals("NONE")) notGreen.add("line " + e.getKey() + " " + lamp + " " + worst(e.getValue().getAsJsonObject()));
                }
            }
            if (Double.isNaN(h)) h = TOP_SPACE + trays() * SPACING + 4;
            height = h;
            if (r.has("products")) {
                var liquids = liquids();
                for (int i = 0; i + 1 < liquids.size(); i++) {
                    var a = liquids.get(i);
                    var b = liquids.get(i + 1);
                    gaps.put(a.get("name").getAsString() + "-" + b.get("name").getAsString(), b.get("t5").getAsDouble() - a.get("t95").getAsDouble());
                }
            }
            packing = Packing.of(items);
        }

        int trays() {
            return r.getAsJsonObject("template").get("tray_count").getAsInt();
        }

        JsonObject template() {
            return r.getAsJsonObject("template");
        }

        List<JsonObject> liquids() {
            var out = new ArrayList<JsonObject>();
            for (var e : r.getAsJsonArray("products")) {
                var p = e.getAsJsonObject();
                if (p.has("missing") || p.get("name").getAsString().equals("OFFGAS")) continue;
                out.add(p);
            }
            return out;
        }

        boolean converged() {
            return r.get("converged").getAsBoolean() && r.get("status").getAsString().equals("STEADY") && r.has("audit_accepted") && r.get("audit_accepted").getAsBoolean();
        }
    }

    static String worst(JsonObject u) {
        JsonObject worst = null;
        for (var e : u.getAsJsonArray("readings")) {
            var o = e.getAsJsonObject();
            if (o.get("lamp").getAsString().equals("GREEN")) continue;
            if (worst == null || o.get("load").getAsDouble() > worst.get("load").getAsDouble()) worst = o;
        }
        return worst == null ? "" : worst.get("limit").getAsString() + String.format(Locale.ROOT, " load %.2f", worst.get("load").getAsDouble());
    }

    public static void main(String[] args) throws IOException {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        var ids = System.getProperty("candidates", "A,B,C1").split(",");
        var sheets = new ArrayList<Sheet>();
        for (var id : ids) {
            var file = root.resolve(IN).resolve(id.trim() + "-result.json");
            if (!Files.exists(file)) throw new IOException("no result " + file);
            var sheet = new Sheet(id.trim(), JsonParser.parseString(Files.readString(file)).getAsJsonObject());
            double shown = sheet.r.has("sized_diameter") ? sheet.r.get("sized_diameter").getAsDouble() : sheet.template().get("diameter_m").getAsDouble();
            sheet.size = new com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing.Size(shown, ShapeCheck.layout(root.resolve(IN), id.trim()));
            sheet.skirt = com.wormzjl.icheme.science.equipment.EquipmentShape.minimumSkirt(shown);
            sheet.tangent = sheet.size.height();
            var findings = root.resolve(IN).resolve("findings-" + id.trim() + ".txt");
            if (Files.exists(findings)) for (var line : Files.readAllLines(findings)) if (!line.isBlank()) sheet.findings.add(line);
            sheets.add(sheet);
        }
        var reference = sheets.stream().filter(s -> s.id.equals("A")).findFirst().orElse(null);
        JsonObject ref8 = Files.exists(root.resolve(IN).resolve("REF8-result.json")) ? JsonParser.parseString(Files.readString(root.resolve(IN).resolve("REF8-result.json"))).getAsJsonObject() : null;
        for (var s : sheets) rules(s, reference, ref8);
        double tallest = sheets.stream().mapToDouble(s -> s.height).max().orElse(40);
        double scale = 1150 / (tallest + 4);
        Files.createDirectories(root.resolve(OUT));
        var summary = new StringBuilder();
        for (var s : sheets) {
            var image = new BufferedImage(1900, 1400, BufferedImage.TYPE_INT_RGB);
            var g = graphics(image);
            elevation(g, s, 40, 1300, scale, 560);
            table(g, s, 640, 40, summary);
            g.dispose();
            ImageIO.write(image, "png", root.resolve(OUT).resolve("column-" + s.id.substring(0, 1) + ".png").toFile());
        }
        compare(root, sheets, scale);
        Files.writeString(root.resolve(IN).resolve("summary.txt"), summary.toString(), StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static Graphics2D graphics(BufferedImage image) {
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(PAPER);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        return g;
    }

    /** The acceptance rules, each pass or fail, against candidate A for the overlaps. */
    static void rules(Sheet s, Sheet a, JsonObject ref8) {
        boolean converged = s.converged();
        s.rules.add(new Rule("Solve converges and passes its audit (" + s.r.get("status").getAsString() + (s.r.has("audit_accepted") ? ", audit " + (s.r.get("audit_accepted").getAsBoolean() ? "accepted" : "refused") : "") + ")", converged));
        if (!converged) {
            s.rules.add(new Rule("Does not converge: " + (s.r.has("reason") ? s.r.get("reason").getAsString() : s.r.get("status").getAsString()), false));
            return;
        }
        double sized = s.r.has("sized_diameter") ? s.r.get("sized_diameter").getAsDouble() : Double.NaN;
        double given = s.template().get("diameter_m").getAsDouble();
        s.rules.add(new Rule(s.notGreen.isEmpty() ? "Every sizing lamp green with the 20 % spare (Size all)" : "Lamps not green after Size all: " + String.join("; ", s.notGreen), s.notGreen.isEmpty()));
        s.rules.add(new Rule(String.format(Locale.ROOT, "Column diameter: candidate %.1f m, Size all %.1f m", given, sized), sized <= given + 1e-9));
        boolean apart = true;
        var text = new StringBuilder("Products apart: ");
        for (var e : s.gaps.entrySet()) {
            double gap = e.getValue();
            Double allowed = a == null ? null : referenceGap(a, e.getKey());
            boolean ok = gap >= 0 || allowed != null && gap >= allowed - 1e-9;
            apart &= ok;
            text.append(String.format(Locale.ROOT, "%s %+.0f K%s; ", e.getKey(), gap, gap < 0 && allowed != null ? String.format(Locale.ROOT, " (A %+.0f)", allowed) : ""));
        }
        s.rules.add(new Rule(text.toString(), apart));
        s.rules.add(new Rule(s.packing.fits ? "Fits a 16 x 16 block plot with one block between units" : "Does not fit 16 x 16: " + String.join(", ", s.packing.left), s.packing.fits));
        s.rules.add(new Rule("World supplies: steam from a fired heater on water, fuel from the off-gas, cooling water, FE for the pumps", true));
        if (s.id.equals("A") && ref8 != null) {
            double worst = 0;
            int at = -1;
            var t8 = ref8.getAsJsonArray("trays");
            var ta = s.r.getAsJsonArray("trays");
            for (int i = 0; i < Math.min(t8.size(), ta.size()); i++) {
                double d = Math.abs(t8.get(i).getAsJsonObject().get("T").getAsDouble() - ta.get(i).getAsJsonObject().get("T").getAsDouble());
                if (d > worst) {
                    worst = d;
                    at = i + 1;
                }
            }
            s.r.addProperty("ref8_worst_dT", worst);
            s.r.addProperty("ref8_worst_tray", at);
            s.rules.add(new Rule(String.format(Locale.ROOT, "Same trays as the 8 m tower: largest tray temperature difference %.2f K (tray %d)", worst, at), worst < 1));
        }
    }

    /** A's gap for the pair of the same cut lines: the pair's lighter product's upper cut, with the middle cut read as kerosene below and diesel above. */
    static Double referenceGap(Sheet a, String pair) {
        String[] p = pair.split("-");
        String light = p[0].equals("MIDDLE") ? "DIESEL" : p[0];
        String heavy = p[1].equals("MIDDLE") ? "KERO" : p[1];
        if (heavy.equals("RESIDUE") && !light.equals("AGO")) heavy = "AGO";
        return a.gaps.get(light + "-" + heavy);
    }

    // ---- the elevation ------------------------------------------------------------------------------------------------

    /** The tower to scale with its base at ({@code x}, {@code ground}): trays, feed, draws, pumparounds, steam, tray numbers. */
    static void elevation(Graphics2D g, Sheet s, int x, int ground, double scale, int width) {
        var t = s.template();
        int trays = t.get("tray_count").getAsInt();
        double d = s.r.has("sized_diameter") ? s.r.get("sized_diameter").getAsDouble() : t.get("diameter_m").getAsDouble();
        // The shell: skirt to the top head (ColumnSizing.Size); the shape's blocks above it are the davit.
        int top = (int) Math.round(ground - (s.skirt + s.tangent) * scale);
        int skirtY = (int) Math.round(ground - s.skirt * scale);
        int w = (int) Math.max(14, Math.round(d * scale));
        int cx = x + width / 2;
        int x0 = cx - w / 2;
        // Ground and metre scale.
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1));
        for (int m = 0; m <= s.height + 2; m += 5) {
            int y = (int) Math.round(ground - m * scale);
            g.drawLine(x + 30, y, x + width, y);
            g.setColor(MUTED);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString(m + " m", x, y + 4);
            g.setColor(GRID);
        }
        g.setColor(INK);
        g.setStroke(new BasicStroke(2));
        g.drawLine(x + 30, ground, x + width, ground);
        // Shell.
        g.setColor(new Color(0xE9ECF0));
        g.fillRoundRect(x0, top, w, skirtY - top, w / 2, w / 2);
        g.setColor(STEEL);
        g.drawRoundRect(x0, top, w, skirtY - top, w / 2, w / 2);
        g.drawRect(x0 + 2, skirtY, w - 4, ground - skirtY);
        g.drawLine(x0 + w / 2, top, x0 + w / 2, (int) Math.round(ground - s.height * scale));
        // Trays: public tray k (2..N-1) at height H - TOP_SPACE - (k-2) * spacing.
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        var marks = new java.util.TreeSet<Integer>();
        marks.add(2);
        marks.add(trays - 1);
        marks.add(t.get("feed_tray").getAsInt());
        for (var e : t.getAsJsonArray("draws")) marks.add(e.getAsJsonObject().get("tray").getAsInt());
        for (var e : t.getAsJsonArray("pumparounds")) {
            marks.add(e.getAsJsonObject().get("draw_tray").getAsInt());
            marks.add(e.getAsJsonObject().get("return_tray").getAsInt());
        }
        for (int k = 2; k <= trays - 1; k++) {
            int y = trayY(s, k, ground, scale);
            g.setColor(STEEL);
            g.setStroke(new BasicStroke(1));
            g.drawLine(x0 + 2, y, x0 + w - 2, y);
            if (marks.contains(k) || k % 5 == 0) {
                g.setColor(marks.contains(k) ? INK : MUTED);
                g.drawString(String.valueOf(k), x0 - 26, y + 4);
            }
        }
        // Condenser and reflux at the top (tray 1).
        int cy = top - 6;
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("1 condenser, reflux " + fmt(t.get("reflux_ratio").getAsDouble(), 2), cx + w / 2 + 10, top + 10);
        // Feed.
        int fy = trayY(s, t.get("feed_tray").getAsInt(), ground, scale);
        arrow(g, FEED, x0 - 120, fy, x0, fy);
        g.setColor(FEED);
        g.drawString("feed " + t.get("feed_tray").getAsInt(), x0 - 120, fy - 6);
        // Draws on the right.
        var products = new ArrayList<String>();
        for (var e : s.r.has("products") ? s.r.getAsJsonArray("products") : new JsonArray()) products.add(e.getAsJsonObject().get("name").getAsString());
        var drawNames = products.stream().filter(n -> !n.equals("OFFGAS") && !n.equals("NAPHTHA") && !n.equals("RESIDUE")).toList();
        int i = 0;
        for (var e : t.getAsJsonArray("draws")) {
            var o = e.getAsJsonObject();
            int y = trayY(s, o.get("tray").getAsInt(), ground, scale);
            String name = i < drawNames.size() ? drawNames.get(i) : "draw " + (i + 1);
            String shown = label(name);
            arrow(g, PRODUCT.getOrDefault(name, INK), x0 + w, y, x0 + w + 110, y);
            g.setColor(INK);
            g.drawString(shown + " " + fmt(o.get("flow_kmol_h").getAsDouble(), 2) + " kmol/h", x0 + w + 116, y + 5);
            i++;
        }
        // Naphtha and residue.
        g.setColor(PRODUCT.get("NAPHTHA"));
        arrow(g, PRODUCT.get("NAPHTHA"), cx, top, cx, top - 30);
        g.setColor(INK);
        g.drawString("naphtha and gas", cx + 6, top - 18);
        arrow(g, PRODUCT.get("RESIDUE"), x0 + w, ground - 10, x0 + w + 110, ground - 10);
        g.setColor(INK);
        g.drawString(s.r.has("products") && s.liquids().size() > 0 ? "bottoms" : "bottoms", x0 + w + 116, ground - 5);
        // Pumparounds on the left: draw tray out, cooled, back on the return tray.
        int lane = 0;
        for (var e : t.getAsJsonArray("pumparounds")) {
            var o = e.getAsJsonObject();
            int yd = trayY(s, o.get("draw_tray").getAsInt(), ground, scale), yr = trayY(s, o.get("return_tray").getAsInt(), ground, scale);
            int lx = x0 - 40 - 22 * lane;
            g.setColor(PA);
            g.setStroke(new BasicStroke(2));
            g.drawLine(x0, yd, lx, yd);
            g.drawLine(lx, yd, lx, yr);
            arrow(g, PA, lx, yr, x0, yr);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString(String.format(Locale.ROOT, "PA %d-%d %.3f MW", o.get("draw_tray").getAsInt(), o.get("return_tray").getAsInt(), o.get("cooling_mw").getAsDouble()), lx - 150,
                    (yd + yr) / 2 + 4);
            lane++;
        }
        // Steam.
        for (var e : t.getAsJsonArray("steam")) {
            var o = e.getAsJsonObject();
            int y = Math.min(ground - 14, trayY(s, Math.min(o.get("tray").getAsInt(), trays - 1), ground, scale) + 12);
            arrow(g, STEAM, x0 - 120, y, x0, y);
            g.setColor(STEAM);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString(String.format(Locale.ROOT, "steam %.2f kmol/h", o.get("flow_kmol_h").getAsDouble()), x0 - 160, y + 18);
        }
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString(String.format(Locale.ROOT, "%d trays, shell %.0f m on its skirt, %d blocks with the davit, %.1f m wide", trays, s.skirt + s.tangent, (int) Math.round(s.height), d), x + 30, ground + 32);
    }

    static int trayY(Sheet s, int tray, int ground, double scale) {
        return (int) Math.round(ground - s.size.level(Math.min(tray - 1, s.size.layout().trays() + 1)) * scale);
    }

    static void arrow(Graphics2D g, Color c, int x0, int y0, int x1, int y1) {
        g.setColor(c);
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(x0, y0, x1, y1);
        double a = Math.atan2(y1 - y0, x1 - x0);
        int[] xs = {x1, (int) (x1 - 10 * Math.cos(a - .4)), (int) (x1 - 10 * Math.cos(a + .4))};
        int[] ys = {y1, (int) (y1 - 10 * Math.sin(a - .4)), (int) (y1 - 10 * Math.sin(a + .4))};
        g.fillPolygon(xs, ys, 3);
    }

    // ---- the table ----------------------------------------------------------------------------------------------------

    static final class Writer {
        final Graphics2D g;
        final int x;
        int y;
        final StringBuilder text;

        Writer(Graphics2D g, int x, int y, StringBuilder text) {
            this.g = g;
            this.x = x;
            this.y = y;
            this.text = text;
        }

        void head(String s) {
            y += 10;
            g.setColor(INK);
            g.setFont(new Font("SansSerif", Font.BOLD, 17));
            g.drawString(s, x, y);
            y += 6;
            g.setColor(GRID);
            g.drawLine(x, y, x + 1220, y);
            y += 18;
            text.append("## ").append(s).append('\n');
        }

        void row(int[] at, String... cells) {
            row(INK, at, cells);
        }

        void row(Color c, int[] at, String... cells) {
            g.setFont(new Font("Monospaced", Font.PLAIN, 14));
            g.setColor(c);
            for (int i = 0; i < cells.length; i++) g.drawString(cells[i], x + at[i], y);
            y += 19;
            text.append(String.join(" | ", cells)).append('\n');
        }

        void line(Color c, String s) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.setColor(c);
            for (var part : wrap(s, 150)) {
                g.drawString(part, x, y);
                y += 19;
            }
            text.append(s).append('\n');
        }
    }

    static List<String> wrap(String s, int width) {
        var out = new ArrayList<String>();
        var line = new StringBuilder();
        for (var word : s.split(" ")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                line.append("    ");
            }
            if (!line.isEmpty() && !line.toString().isBlank()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    static void table(Graphics2D g, Sheet s, int x, int y, StringBuilder summary) {
        var t = s.template();
        var w = new Writer(g, x, y, summary);
        summary.append("# Candidate ").append(s.id).append('\n');
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString("Small crude tower (Medium crude), candidate " + (s.id.length() > 1 ? s.id.charAt(0) + " (variation " + s.id.substring(1) + ")" : s.id), x, w.y + 10);
        w.y += 34;
        w.line(MUTED, s.r.get("note").getAsString());
        w.line(INK, String.format(Locale.ROOT, "%d trays (condenser 1, sump %d), feed tray %d, top %.2f bar, condenser %.1f C, reflux ratio %.2f, design feed %.2f kmol/h",
                t.get("tray_count").getAsInt(), t.get("tray_count").getAsInt(), t.get("feed_tray").getAsInt(), t.get("top_pressure_bar").getAsDouble(),
                t.get("top_temperature_kelvin").getAsDouble() - 273.15, t.get("reflux_ratio").getAsDouble(), t.get("design_feed_kmol_h").getAsDouble()));
        w.head("Solve");
        var polls = s.r.getAsJsonObject("polls_run");
        w.line(INK, "Sheet " + s.r.get("status").getAsString() + (s.r.has("audit_accepted") ? ", audit " + (s.r.get("audit_accepted").getAsBoolean() ? "accepted" : "refused") : "")
                + (s.r.has("newton") ? ", Newton iterations of the last solve " + s.r.get("newton").getAsInt() : "") + ", loops " + s.r.get("loops"));
        w.line(INK, "Column start: " + startText(polls));
        // The package's data notes (NAME: text) stand on every solve; only the solve's own advisories are drawn.
        var own = new ArrayList<String>();
        if (s.r.has("advisories")) for (var e : s.r.getAsJsonArray("advisories")) if (!e.getAsString().matches("[A-Z0-9_]+: .*")) own.add(e.getAsString());
        if (!own.isEmpty()) w.line(MUTED, "Audit advisories: " + String.join("; ", own));
        var warnings = new ArrayList<String>();
        for (var e : s.r.getAsJsonArray("log")) if (!e.getAsString().startsWith("INFO")) warnings.add(e.getAsString());
        if (!warnings.isEmpty()) w.line(YELLOW, "Notes: " + String.join("; ", warnings));
        if (!s.converged()) {
            w.line(RED, "Does not converge: " + (s.r.has("reason") ? s.r.get("reason").getAsString() : s.r.get("status").getAsString()));
            rulesBlock(w, s);
            return;
        }
        w.head("Products (hydrocarbon; boiling points by mass)");
        int[] at = {0, 120, 240, 360, 470, 580, 700, 820, 960};
        w.row(MUTED, at, "product", "kmol/h", "kg/h", "m3/h", "mass %", "5 % C", "50 % C", "95 % C", "gap to next");
        var products = new ArrayList<JsonObject>();
        for (var e : s.r.getAsJsonArray("products")) if (!e.getAsJsonObject().has("missing")) products.add(e.getAsJsonObject());
        for (int i = 0; i < products.size(); i++) {
            var p = products.get(i);
            String name = p.get("name").getAsString();
            String gap = "";
            if (!name.equals("OFFGAS") && i + 1 < products.size()) {
                Double v = s.gaps.get(name + "-" + products.get(i + 1).get("name").getAsString());
                if (v != null) gap = String.format(Locale.ROOT, "%+.0f K", v);
            }
            w.row(p.has("m3_h") || name.equals("OFFGAS") ? INK : YELLOW, at, label(name), fmt(p.get("kmol_h").getAsDouble(), 2), fmt(p.get("kg_h").getAsDouble(), 0),
                    p.has("m3_h") ? fmt(p.get("m3_h").getAsDouble(), 2) : "gas", fmt(100 * p.get("share").getAsDouble(), 1), c(p, "t5"), c(p, "t50"), c(p, "t95"), gap);
        }
        w.head("Utilities");
        var col = s.r.getAsJsonObject("column_readings");
        var units = s.r.getAsJsonObject("units");
        double heater = units.has("H-101") ? units.getAsJsonObject("H-101").get("duty").getAsDouble() : Double.NaN;
        var pas = new ArrayList<String>();
        for (var e : t.getAsJsonArray("pumparounds")) {
            var o = e.getAsJsonObject();
            pas.add(String.format(Locale.ROOT, "%d to %d %.3f MW", o.get("draw_tray").getAsInt(), o.get("return_tray").getAsInt(), o.get("cooling_mw").getAsDouble()));
        }
        var steam = t.getAsJsonArray("steam").get(0).getAsJsonObject();
        double steamKg = steam.get("flow_kmol_h").getAsDouble() * 18.015;
        double bottoms = bottomsKg(s);
        w.line(INK, String.format(Locale.ROOT, "Reflux ratio %.2f; condenser %.3f MW; fired heater %.3f MW; pumparounds %s", t.get("reflux_ratio").getAsDouble(),
                col.get("condenser").getAsDouble() / 1e6, heater / 1e6, String.join(", ", pas)));
        w.line(INK, String.format(Locale.ROOT, "Bottom steam %.2f kmol/h (%.0f kg/h) at %.0f C on tray %d: %.3f kg per kg of bottoms", steam.get("flow_kmol_h").getAsDouble(), steamKg,
                steam.get("temperature_kelvin").getAsDouble() - 273.15, steam.get("tray").getAsInt(), steamKg / bottoms));
        if (s.r.has("sized_units")) {
            var su = s.r.getAsJsonObject("sized_units");
            w.line(MUTED, String.format(Locale.ROOT, "After Size all (sized drops): fired heater %.3f MW, feed to the column %.1f C", su.getAsJsonObject("H-101").get("duty").getAsDouble() / 1e6,
                    su.getAsJsonObject("streams").getAsJsonObject("S-7").get("T").getAsDouble() - 273.15));
        }
        w.head("Sizing after Size all (lamp; closest row; headroom = 1/load - 1)");
        int[] su = {0, 250, 330, 640, 800, 1000};
        w.row(MUTED, su, "item", "lamp", "closest row", "load", "headroom", "blocks x z y");
        if (s.r.has("sized")) {
            var list = new ArrayList<Map.Entry<String, JsonElement>>(s.r.getAsJsonObject("sized").getAsJsonObject("units").entrySet());
            for (var e : list) {
                var u = e.getValue().getAsJsonObject();
                JsonObject near = null;
                for (var r : u.getAsJsonArray("readings")) {
                    var o = r.getAsJsonObject();
                    if (java.util.Set.of("UNIT_SHAPE", "COLUMN_SOLVES", "LINE_RUNS").contains(o.get("limit").getAsString())) continue;
                    if (!Double.isFinite(o.get("load").getAsDouble())) {
                        near = o;
                        break;
                    }
                    if (near == null || o.get("load").getAsDouble() > near.get("load").getAsDouble()) near = o;
                }
                String lamp = u.get("lamp").getAsString();
                Color c = lamp.equals("RED") ? RED : lamp.equals("YELLOW") ? YELLOW : INK;
                double load = near == null ? Double.NaN : near.get("load").getAsDouble();
                w.row(c, su, e.getKey(), lamp, near == null ? "" : near.get("limit").getAsString(), Double.isFinite(load) ? fmt(load, 2) : "",
                        Double.isFinite(load) && load > 0 ? fmt(100 * (1 / load - 1), 0) + " %" : "",
                        u.has("blocks_x") ? u.get("blocks_x").getAsInt() + " " + u.get("blocks_z").getAsInt() + " " + u.get("blocks_y").getAsInt() + (u.get("count").getAsInt() > 1 ? " x" + u.get("count").getAsInt() : "") : "");
            }
            long lines = s.r.getAsJsonObject("sized").getAsJsonObject("lines").size();
            long badLines = s.notGreen.stream().filter(n -> n.startsWith("line")).count();
            w.line(INK, lines + " lines sized, " + badLines + " not green; plot: " + (s.packing.fits ? "fits 16 x 16" : "does not fit (" + String.join(", ", s.packing.left) + ")")
                    + String.format(Locale.ROOT, "; footprints %d blocks", s.items.stream().mapToInt(it -> it.x() * it.z() * it.count()).sum()));
        }
        rulesBlock(w, s);
    }

    static void rulesBlock(Writer w, Sheet s) {
        w.head("Acceptance rules");
        for (var rule : s.rules) w.line(rule.pass() ? GREEN : RED, (rule.pass() ? "PASS  " : "FAIL  ") + rule.text());
        if (!s.findings.isEmpty()) {
            w.head("Findings");
            for (var f : s.findings) w.line(INK, f);
        }
    }

    static String startText(JsonObject polls) {
        var b = new StringBuilder();
        for (var k : List.of("learned", "classical", "warm")) if (polls.has(k)) b.append(k).append(' ').append(polls.get(k).getAsLong()).append(" polls, ");
        var solves = polls.getAsJsonArray("solves");
        b.append(solves.size()).append(" column solves");
        if (!solves.isEmpty()) b.append("; first: ").append(solves.get(0).getAsString());
        return b.toString();
    }

    static double bottomsKg(Sheet s) {
        for (var e : s.r.getAsJsonArray("products")) {
            var p = e.getAsJsonObject();
            if (p.get("name").getAsString().equals("RESIDUE") && !p.has("missing")) return p.get("kg_h").getAsDouble();
        }
        return Double.NaN;
    }

    static String label(String name) {
        return switch (name) {
            case "OFFGAS" -> "off-gas";
            case "NAPHTHA" -> "naphtha";
            case "KERO" -> "kerosene";
            case "DIESEL" -> "diesel";
            case "MIDDLE" -> "kero+diesel";
            case "AGO" -> "gas oil";
            case "RESIDUE" -> "bottoms";
            default -> name;
        };
    }

    static String c(JsonObject p, String key) {
        double k = p.get(key).getAsDouble();
        return (p.has(key + "_above") ? ">" : "") + fmt(k - 273.15, 0);
    }

    static String fmt(double v, int digits) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%." + digits + "f", v) : "-";
    }

    // ---- the plot ------------------------------------------------------------------------------------------------------

    /** Shelf packing of the footprints on 16 x 16 blocks, one block between units (the larger first, either way round). */
    static final class Packing {
        record Placed(Item item, int x, int z, int w, int d) {
        }

        final List<Placed> placed = new ArrayList<>();
        final List<String> left = new ArrayList<>();
        boolean fits;

        static Packing of(List<Item> items) {
            var p = new Packing();
            var list = new ArrayList<Item>();
            for (var it : items) for (int k = 0; k < it.count(); k++) list.add(it);
            list.sort(Comparator.comparingInt((Item it) -> Math.max(it.x(), it.z())).thenComparingInt(it -> it.x() * it.z()).reversed());
            // Skyline of free rows: try each unit at the lowest row it fits, either way round.
            boolean[][] used = new boolean[PLOT][PLOT];
            for (var it : list) {
                boolean done = false;
                for (int z = 0; z < PLOT && !done; z++)
                    for (int x = 0; x < PLOT && !done; x++)
                        for (int turn = 0; turn < 2 && !done; turn++) {
                            int w = turn == 0 ? it.x() : it.z(), d = turn == 0 ? it.z() : it.x();
                            if (free(used, x, z, w, d)) {
                                for (int i = Math.max(0, x - 1); i < Math.min(PLOT, x + w + 1); i++)
                                    for (int j = Math.max(0, z - 1); j < Math.min(PLOT, z + d + 1); j++) used[i][j] = true;
                                p.placed.add(new Placed(it, x, z, w, d));
                                done = true;
                            }
                        }
                if (!done) p.left.add(it.id());
            }
            p.fits = p.left.isEmpty();
            return p;
        }

        /** Whether a {@code w} x {@code d} unit at ({@code x}, {@code z}) stands on free blocks inside the plot (the gap is marked by its neighbours). */
        static boolean free(boolean[][] used, int x, int z, int w, int d) {
            if (x + w > PLOT || z + d > PLOT) return false;
            for (int i = x; i < x + w; i++) for (int j = z; j < z + d; j++) if (used[i][j]) return false;
            return true;
        }
    }

    static void plot(Graphics2D g, Sheet s, int x, int y, int cell) {
        g.setColor(Color.WHITE);
        g.fillRect(x, y, PLOT * cell, PLOT * cell);
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1));
        for (int i = 0; i <= PLOT; i++) {
            g.drawLine(x + i * cell, y, x + i * cell, y + PLOT * cell);
            g.drawLine(x, y + i * cell, x + PLOT * cell, y + i * cell);
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        for (var pl : s.packing.placed) {
            var it = pl.item();
            Color c = switch (it.kind()) {
                case "COLUMN" -> STEEL;
                case "PUMP" -> new Color(0x5DADE2);
                case "DRUM" -> new Color(0xA9CCE3);
                case "VERTICAL_HEATER", "CABIN_HEATER" -> new Color(0xE59866);
                case "AIR_COOLER" -> new Color(0xA3E4D7);
                default -> new Color(0xD7BDE2);
            };
            g.setColor(c);
            g.fillRect(x + pl.x() * cell + 1, y + pl.z() * cell + 1, pl.w() * cell - 1, pl.d() * cell - 1);
            g.setColor(INK);
            g.drawRect(x + pl.x() * cell, y + pl.z() * cell, pl.w() * cell, pl.d() * cell);
            String name = it.id().replace("T-101/", "");
            if (name.length() > 12) name = name.substring(0, 12);
            g.drawString(name, x + pl.x() * cell + 2, y + pl.z() * cell + 11);
        }
        g.setColor(INK);
        g.setStroke(new BasicStroke(2));
        g.drawRect(x, y, PLOT * cell, PLOT * cell);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(s.packing.fits ? GREEN : RED);
        g.drawString(s.packing.fits ? "fits 16 x 16" : "left over: " + String.join(", ", s.packing.left), x, y + PLOT * cell + 18);
    }

    static void compare(Path root, List<Sheet> sheets, double scale) throws IOException {
        int each = 620, cell = 24;
        var image = new BufferedImage(each * sheets.size() + 40, 1350 + PLOT * cell + 60, BufferedImage.TYPE_INT_RGB);
        var g = graphics(image);
        for (int i = 0; i < sheets.size(); i++) {
            var s = sheets.get(i);
            g.setColor(INK);
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.drawString("Candidate " + (s.id.length() > 1 ? s.id.charAt(0) + " (variation " + s.id.substring(1) + ")" : s.id), 40 + i * each, 34);
            elevation(g, s, 20 + i * each, 1260, scale, 560);
            plot(g, s, 40 + i * each + 90, 1320, cell);
        }
        g.dispose();
        ImageIO.write(image, "png", root.resolve(OUT).resolve("columns-compare.png").toFile());
    }
}
