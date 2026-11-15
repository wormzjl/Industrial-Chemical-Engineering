import com.wormzjl.icheme.science.equipment.AlarmGeometry;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * The alarm beacon's block and item models, blockstate and textures (batch 2026-11-15-fluid-interfaces, W9), from
 * {@link AlarmGeometry}'s boxes; and software views of it, clear and active. Deterministic: no random numbers.
 *
 * <pre>
 * javac -encoding UTF-8 -d tmp/alarm-beacon/classes src/main/java/com/wormzjl/icheme/science/equipment/AlarmGeometry.java tools/pump-sprites/AlarmAssets.java
 * java -Djava.awt.headless=true -Djava.io.tmpdir=tmp/alarm-beacon -cp tmp/alarm-beacon/classes AlarmAssets src/main/resources/assets/icheme
 * java -Djava.awt.headless=true -Djava.io.tmpdir=tmp/alarm-beacon -cp tmp/alarm-beacon/classes AlarmAssets src/main/resources/assets/icheme tmp/alarm-beacon/views
 * </pre>
 *
 * Detail is relief baked into the albedo from one height field per sprite (lit from the upper left, crevices darkened),
 * not polygons: the base's four hex bolts, the gland's nut, the lens's Fresnel ribs, the horn mouth's rings.
 */
public final class AlarmAssets {
    private AlarmAssets() {}

    /** One face: its texture key and uv (sixteenths), and whether it is drawn full-bright. */
    record Face(String texture, double[] uv) {}

    record Box(String name, double[] from, double[] to, Map<String, Face> faces, boolean lens) {}

    static final String[] DIRECTIONS = {"north", "east", "south", "west", "up", "down"};

    /** Minecraft's default uv of a face of the box {@code a}..{@code b} (BlockElement's uvsByDefault). */
    static double[] auto(String face, double[] a, double[] b) {
        return switch (face) {
            case "down" -> new double[] {a[0], 16 - b[2], b[0], 16 - a[2]};
            case "up" -> new double[] {a[0], a[2], b[0], b[2]};
            case "north" -> new double[] {16 - b[0], 16 - b[1], 16 - a[0], 16 - a[1]};
            case "south" -> new double[] {a[0], 16 - b[1], b[0], 16 - a[1]};
            case "west" -> new double[] {a[2], 16 - b[1], b[2], 16 - a[1]};
            default -> new double[] {16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1]};
        };
    }

    static final double[] FULL = {0, 0, 16, 16};

    static List<Box> boxes(boolean lit) {
        var out = new ArrayList<Box>();
        for (var p : AlarmGeometry.parts()) {
            double[] a = {p.x(), p.y(), p.z()}, b = {p.X(), p.Y(), p.Z()};
            String texture =
                    switch (p.finish()) {
                        case BASE, HORN -> "housing";
                        case SOCKET, GUARD -> "guard";
                        case LENS -> lit ? "lens_lit" : "lens";
                        case MOUTH -> "housing";
                    };
            var faces = new LinkedHashMap<String, Face>();
            for (var d : DIRECTIONS) faces.put(d, new Face(texture, auto(d, a, b)));
            if (p.name().equals("base plate")) faces.put("up", new Face("base_top", auto("up", a, b)));
            if (p.name().equals("socket gland")) faces.put("north", new Face("gland", FULL));
            if (p.name().equals("horn mouth")) faces.put("east", new Face("mouth", FULL));
            out.add(new Box(p.name(), a, b, faces, p.finish() == AlarmGeometry.Finish.LENS && lit));
        }
        return out;
    }

    static Map<String, String> textures(boolean lit) {
        var t = new LinkedHashMap<String, String>();
        t.put("particle", "icheme:block/alarm_housing");
        t.put("housing", "icheme:block/alarm_housing");
        t.put("base_top", "icheme:block/alarm_base_top");
        t.put("guard", "icheme:block/alarm_guard");
        t.put("gland", "icheme:block/alarm_gland");
        t.put("mouth", "icheme:block/alarm_horn_mouth");
        t.put(lit ? "lens_lit" : "lens", "icheme:block/" + (lit ? "alarm_lens_lit" : "alarm_lens"));
        return t;
    }

    static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    static String model(boolean lit) {
        var json = new StringBuilder("{\n  \"parent\": \"minecraft:block/block\",\n  \"textures\": {\n");
        var textures = textures(lit);
        int i = 0;
        for (var e : textures.entrySet())
            json.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"').append(++i < textures.size() ? ",\n" : "\n");
        json.append("  },\n  \"elements\": [\n");
        var boxes = boxes(lit);
        for (int k = 0; k < boxes.size(); k++) {
            var box = boxes.get(k);
            json.append("    {\n      \"name\": \"").append(box.name()).append("\",\n");
            json.append("      \"from\": [").append(num(box.from()[0])).append(", ").append(num(box.from()[1])).append(", ").append(num(box.from()[2])).append("],\n");
            json.append("      \"to\": [").append(num(box.to()[0])).append(", ").append(num(box.to()[1])).append(", ").append(num(box.to()[2])).append("],\n");
            if (box.lens()) json.append("      \"shade\": false,\n      \"neoforge_data\": { \"block_light\": 15, \"sky_light\": 15, \"ambient_occlusion\": false },\n");
            json.append("      \"faces\": {\n");
            int f = 0;
            for (var e : box.faces().entrySet()) {
                var uv = e.getValue().uv();
                json.append("        \"").append(e.getKey()).append("\": { \"uv\": [").append(num(uv[0])).append(", ").append(num(uv[1])).append(", ")
                        .append(num(uv[2])).append(", ").append(num(uv[3])).append("], \"texture\": \"#").append(e.getValue().texture()).append("\" }")
                        .append(++f < box.faces().size() ? ",\n" : "\n");
            }
            json.append("      }\n    }").append(k + 1 < boxes.size() ? ",\n" : "\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    static String blockstate() {
        var json = new StringBuilder("{\n  \"variants\": {\n");
        var faces = new String[] {"down", "up", "north", "south", "west", "east"};
        var states = new String[] {"clear", "active", "acknowledged"};
        int n = 0;
        for (int facing = 0; facing < 6; facing++)
            for (var state : states) {
                int[] r = AlarmGeometry.rotation(facing);
                json.append("    \"facing=").append(faces[facing]).append(",state=").append(state).append("\": { \"model\": \"icheme:block/")
                        .append(state.equals("clear") ? "alarm_beacon" : "alarm_beacon_lit").append('"');
                if (r[0] != 0) json.append(", \"x\": ").append(r[0]);
                if (r[1] != 0) json.append(", \"y\": ").append(r[1]);
                json.append(" }").append(++n < 18 ? ",\n" : "\n");
            }
        return json.append("  }\n}\n").toString();
    }

    static final String ITEM =
            """
            {
              "parent": "icheme:block/alarm_beacon",
              "display": {
                "gui": {"rotation": [30, 225, 0], "translation": [0, 1, 0], "scale": [0.9, 0.9, 0.9]},
                "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
                "fixed": {"rotation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
                "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.5, 0.5, 0.5]},
                "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]}
              }
            }
            """;

    // Sprites: 32 x 32 px (two px per sixteenth), the lit lens 32 x 64 (two frames).

    /** A fixed hash in [-1, 1]: the grain. */
    static double grain(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 32767.5 - 1;
    }

    static int rgb(double r, double g, double b) {
        return (int) Math.clamp(Math.round(r), 0, 255) << 16 | (int) Math.clamp(Math.round(g), 0, 255) << 8 | (int) Math.clamp(Math.round(b), 0, 255);
    }

    /** The albedo {@code base} (0xRRGGBB) relief-lit from height field {@code h} (px): upper-left light, crevices darker. */
    static BufferedImage relief(double[][] h, int base, double grain, int seed) {
        int w = h[0].length, ht = h.length;
        var image = new BufferedImage(w, ht, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ht; y++)
            for (int x = 0; x < w; x++) {
                double slope = h[Math.max(0, y - 1)][Math.max(0, x - 1)] - h[Math.min(ht - 1, y + 1)][Math.min(w - 1, x + 1)];
                double low = 0;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int xx = Math.clamp(x + d[0], 0, w - 1), yy = Math.clamp(y + d[1], 0, ht - 1);
                    low += Math.max(0, h[yy][xx] - h[y][x]);
                }
                double f = 1 + .22 * Math.clamp(slope, -1.5, 1.5) - .08 * Math.min(low, 3) + grain * grain(x, y, seed);
                image.setRGB(x, y, rgb((base >> 16 & 255) * f, (base >> 8 & 255) * f, (base & 255) * f));
            }
        return image;
    }

    static double[][] flat(int w, int h) {
        return new double[h][w];
    }

    /** A raised hex nut of across-flats {@code af} px at (cx, cy), height {@code top}, with a chamfer. */
    static void hex(double[][] h, double cx, double cy, double af, double top) {
        for (int y = 0; y < h.length; y++)
            for (int x = 0; x < h[0].length; x++) {
                double dx = x + .5 - cx, dy = y + .5 - cy, r = 0;
                for (int k = 0; k < 3; k++) {
                    double a = Math.PI / 3 * k;
                    r = Math.max(r, Math.abs(dx * Math.cos(a) + dy * Math.sin(a)));
                }
                if (r <= af / 2) h[y][x] = Math.max(h[y][x], top - Math.max(0, r - (af / 2 - .8)) * .8);
            }
    }

    static BufferedImage housing() {
        var h = flat(32, 32);
        // A cast parting line around the middle, the only relief: the sides read as painted castings.
        for (int x = 0; x < 32; x++) h[15][x] = -.6;
        return relief(h, 0xA7ACA8, .025, 1);
    }

    static BufferedImage baseTop() {
        var h = flat(32, 32);
        // The base's cast rim (u, v 3..13 sixteenths: 6..26 px) and its four fixing bolts.
        for (int y = 6; y < 26; y++)
            for (int x = 6; x < 26; x++) if (x == 6 || y == 6 || x == 25 || y == 25) h[y][x] = .8;
        for (double[] c : new double[][] {{8.5, 8.5}, {23.5, 8.5}, {8.5, 23.5}, {23.5, 23.5}}) hex(h, c[0], c[1], 3.4, 1.6);
        return relief(h, 0xA7ACA8, .025, 2);
    }

    static BufferedImage guard() {
        var h = flat(32, 32);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) h[y][x] = .4 * Math.sin((x + y) * .9);
        return relief(h, 0x8D9390, .03, 3);
    }

    static BufferedImage gland() {
        var h = flat(32, 32);
        hex(h, 16, 16, 24, 1.8);
        // The compression seal (a grey rubber ring) and the cable bore inside it, recessed, not black.
        var image = relief(h, 0xB3AB92, .02, 4);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                double r = Math.hypot(x + .5 - 16, y + .5 - 16);
                if (r < 7.5) image.setRGB(x, y, rgb(0x4A * (1 - .1 * (r / 7.5)), 0x50 * (1 - .1 * (r / 7.5)), 0x52 * (1 - .1 * (r / 7.5))));
                if (r < 4) image.setRGB(x, y, rgb(0x5E + 10 * grain(x, y, 5), 0x64, 0x66));
            }
        return image;
    }

    static BufferedImage mouth() {
        var h = flat(32, 32);
        // The horn's mouth: three ridged rings round a phasing plug.
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                double r = Math.hypot(x + .5 - 16, y + .5 - 16);
                h[y][x] = r < 3.5 ? 1.2 : r > 15 ? 1.5 : .6 * Math.cos(r * 1.25) - .4;
            }
        var image = relief(h, 0x666C6B, .03, 6);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) if (Math.hypot(x + .5 - 16, y + .5 - 16) > 15) image.setRGB(x, y, 0xA7ACA8);
        return image;
    }

    /** The lens: red polycarbonate with vertical Fresnel ribs every 4 px; {@code core} adds the lit lamp's hot streak. */
    static BufferedImage lens(int base, boolean core) {
        var h = flat(32, 32);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) h[y][x] = .7 * Math.sin(x * Math.PI / 2);
        var image = relief(h, base, .015, 7);
        if (core)
            for (int y = 0; y < 32; y++)
                for (int x = 13; x < 19; x++) {
                    int c = image.getRGB(x, y);
                    double k = 1 - Math.abs(x - 15.5) / 3.5;
                    image.setRGB(x, y, rgb((c >> 16 & 255) + (255 - (c >> 16 & 255)) * .7 * k, (c >> 8 & 255) + (210 - (c >> 8 & 255)) * .7 * k, (c & 255) + (170 - (c & 255)) * .7 * k));
                }
        return image;
    }

    static BufferedImage lensLit() {
        var image = new BufferedImage(32, 64, BufferedImage.TYPE_INT_RGB);
        var on = lens(0xFF4A2E, true);
        var off = lens(0xA2271A, false);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                image.setRGB(x, y, on.getRGB(x, y));
                image.setRGB(x, y + 32, off.getRGB(x, y));
            }
        return image;
    }

    static Map<String, BufferedImage> sprites() {
        var out = new LinkedHashMap<String, BufferedImage>();
        out.put("alarm_housing", housing());
        out.put("alarm_base_top", baseTop());
        out.put("alarm_guard", guard());
        out.put("alarm_gland", gland());
        out.put("alarm_horn_mouth", mouth());
        out.put("alarm_lens", lens(0x7C1D16, false));
        out.put("alarm_lens_lit", lensLit());
        return out;
    }

    public static void main(String[] args) throws Exception {
        var assets = Path.of(args[0]);
        var sprites = sprites();
        if (args.length > 1) {
            views(sprites, Path.of(args[1]));
            return;
        }
        for (var e : sprites.entrySet()) ImageIO.write(e.getValue(), "png", assets.resolve("textures/block/" + e.getKey() + ".png").toFile());
        // Two frames of 5 ticks: the lamp flashes at 2 Hz (plan section 5.2).
        Files.writeString(assets.resolve("textures/block/alarm_lens_lit.png.mcmeta"), "{\n  \"animation\": { \"frametime\": 5, \"interpolate\": false }\n}\n");
        Files.writeString(assets.resolve("models/block/alarm_beacon.json"), model(false));
        Files.writeString(assets.resolve("models/block/alarm_beacon_lit.json"), model(true));
        Files.writeString(assets.resolve("blockstates/alarm_beacon.json"), blockstate());
        Files.writeString(assets.resolve("models/item/alarm_beacon.json"), ITEM);
    }

    // Software views (the StationRender recipe), with lit lens faces drawn full-bright.

    record Quad(double[][] points, double[][] uv, double[] normal, BufferedImage texture, boolean glow) {}

    static void views(Map<String, BufferedImage> sprites, Path out) throws Exception {
        Files.createDirectories(out);
        for (boolean lit : new boolean[] {false, true}) {
            var textures = new HashMap<String, BufferedImage>();
            for (var e : textures(lit).entrySet()) {
                var sprite = sprites.get(e.getValue().substring("icheme:block/".length()));
                textures.put(e.getKey(), sprite.getHeight() > sprite.getWidth() ? sprite.getSubimage(0, 0, 32, 32) : sprite);
            }
            for (int facing : new int[] {1, 2}) {
                var quads = new ArrayList<Quad>();
                for (var box : boxes(lit)) {
                    for (var f : box.faces().entrySet()) quads.add(quad(box, f.getKey(), f.getValue(), textures, facing, box.lens()));
                }
                String name = (lit ? "active" : "clear") + (facing == 1 ? "-floor" : "-wall");
                render(quads, new double[] {-1, -.8, 1.3}, out.resolve(name + "-front.png"), lit);
                render(quads, new double[] {1.2, -.7, -1}, out.resolve(name + "-rear.png"), lit);
            }
        }
    }

    static Quad quad(Box box, String face, Face f, Map<String, BufferedImage> textures, int facing, boolean glow) {
        var a = box.from();
        var b = box.to();
        double x = a[0], y = a[1], z = a[2], X = b[0], Y = b[1], Z = b[2];
        double[][] p;
        double[] n;
        switch (face) {
            case "north" -> { p = new double[][] {{X, Y, z}, {X, y, z}, {x, y, z}, {x, Y, z}}; n = new double[] {0, 0, -1}; }
            case "south" -> { p = new double[][] {{x, Y, Z}, {x, y, Z}, {X, y, Z}, {X, Y, Z}}; n = new double[] {0, 0, 1}; }
            case "east" -> { p = new double[][] {{X, Y, Z}, {X, y, Z}, {X, y, z}, {X, Y, z}}; n = new double[] {1, 0, 0}; }
            case "west" -> { p = new double[][] {{x, Y, z}, {x, y, z}, {x, y, Z}, {x, Y, Z}}; n = new double[] {-1, 0, 0}; }
            case "up" -> { p = new double[][] {{x, Y, z}, {x, Y, Z}, {X, Y, Z}, {X, Y, z}}; n = new double[] {0, 1, 0}; }
            default -> { p = new double[][] {{x, y, Z}, {x, y, z}, {X, y, z}, {X, y, Z}}; n = new double[] {0, -1, 0}; }
        }
        var turned = new double[4][];
        for (int i = 0; i < 4; i++) turned[i] = AlarmGeometry.point(p[i][0], p[i][1], p[i][2], facing);
        var o = AlarmGeometry.point(8, 8, 8, facing);
        var m = AlarmGeometry.point(8 + n[0], 8 + n[1], 8 + n[2], facing);
        var u = f.uv();
        return new Quad(turned, new double[][] {{u[0] / 16, u[1] / 16}, {u[0] / 16, u[3] / 16}, {u[2] / 16, u[3] / 16}, {u[2] / 16, u[1] / 16}},
                new double[] {m[0] - o[0], m[1] - o[1], m[2] - o[2]}, textures.get(f.texture()), glow);
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] unit(double[] a) {
        double r = Math.sqrt(dot(a, a));
        return new double[] {a[0] / r, a[1] / r, a[2] / r};
    }

    static double edge(double[] a, double[] b, double x, double y) {
        return (x - a[0]) * (b[1] - a[1]) - (y - a[1]) * (b[0] - a[0]);
    }

    static void render(List<Quad> quads, double[] view, Path output, boolean lit) throws Exception {
        var depth = unit(view);
        var right = unit(cross(depth, new double[] {0, 1, 0}));
        var up = cross(right, depth);
        // The whole cell is the frame, so a floor and a wall mount compare at one scale.
        double loX = 1e9, hiX = -1e9, loY = 1e9, hiY = -1e9;
        for (int i = 0; i < 8; i++) {
            double[] c = {(i & 1) * 16, (i >> 1 & 1) * 16, (i >> 2 & 1) * 16};
            double sx = dot(c, right), sy = dot(c, up);
            loX = Math.min(loX, sx); hiX = Math.max(hiX, sx); loY = Math.min(loY, sy); hiY = Math.max(hiY, sy);
        }
        double scale = 520 / Math.max(hiX - loX, hiY - loY);
        int w = (int) ((hiX - loX) * scale) + 48, h = (int) ((hiY - loY) * scale) + 48;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var zbuffer = new double[w * h];
        Arrays.fill(zbuffer, Double.POSITIVE_INFINITY);
        int ground = lit ? 0x8C7B78 : 0xD7DEDE;
        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) image.setRGB(xx, yy, ground);
        for (var q : quads) {
            if (dot(q.normal(), depth) >= 0) continue;
            double[][] screen = new double[4][3];
            for (int i = 0; i < 4; i++) {
                var p = q.points()[i];
                screen[i] = new double[] {24 + (dot(p, right) - loX) * scale, h - 24 - (dot(p, up) - loY) * scale, dot(p, depth)};
            }
            double[] n = q.normal();
            double light = q.glow() ? 1 : (n[0] * n[0] * .6 + n[1] * n[1] * (3 + n[1]) / 4 + n[2] * n[2] * .8) * (lit ? .62 : 1);
            for (var tri : new int[][] {{0, 1, 2}, {0, 2, 3}}) {
                var a = screen[tri[0]];
                var b = screen[tri[1]];
                var c = screen[tri[2]];
                double area = edge(a, b, c[0], c[1]);
                if (Math.abs(area) < 1e-9) continue;
                int minX = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), maxX = Math.min(w - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
                int minY = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), maxY = Math.min(h - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
                for (int yy = minY; yy <= maxY; yy++)
                    for (int xx = minX; xx <= maxX; xx++) {
                        double aa = edge(b, c, xx + .5, yy + .5) / area, bb = edge(c, a, xx + .5, yy + .5) / area, cc = 1 - aa - bb;
                        if (aa < 0 || bb < 0 || cc < 0) continue;
                        double zz = aa * a[2] + bb * b[2] + cc * c[2];
                        if (zz >= zbuffer[yy * w + xx]) continue;
                        double u = aa * q.uv()[tri[0]][0] + bb * q.uv()[tri[1]][0] + cc * q.uv()[tri[2]][0];
                        double v = aa * q.uv()[tri[0]][1] + bb * q.uv()[tri[1]][1] + cc * q.uv()[tri[2]][1];
                        var t = q.texture();
                        int rgb = t.getRGB(Math.clamp((int) (u * t.getWidth()), 0, t.getWidth() - 1), Math.clamp((int) (v * t.getHeight()), 0, t.getHeight() - 1));
                        image.setRGB(xx, yy, rgb((rgb >> 16 & 255) * light, (rgb >> 8 & 255) * light, (rgb & 255) * light));
                        zbuffer[yy * w + xx] = zz;
                    }
            }
        }
        ImageIO.write(image, "png", output.toFile());
        System.out.println("Wrote " + output);
    }
}
