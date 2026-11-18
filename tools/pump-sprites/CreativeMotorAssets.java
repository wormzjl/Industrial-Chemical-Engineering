import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The creative motor's block model and its sprites (batch 2026-11-16-review-plant-sizing; owner, 2026-11-18: "C: round, marked"): an
 * eight-sided finned frame from four crossed boxes, a ringed fan cowl, two feet on a base, the terminal box with the
 * creative plate (the creative power block's teal frame and yellow bolt) on its lid and on both flanks, the shaft stub on
 * the facing side. Fins, bolts and the grille are in the sprites, not in boxes. Deterministic: no random numbers.
 *
 * <pre>
 * java -Djava.awt.headless=true tools/pump-sprites/CreativeMotorAssets.java src/main/resources/assets/icheme
 * java -Djava.awt.headless=true tools/pump-sprites/CreativeMotorAssets.java src/main/resources/assets/icheme tmp/creative-motor/views
 * </pre>
 *
 * The second form also writes software views (the shaft side and the fan side, in the game's face shades). The block's
 * collision boxes ({@code CreativeMotorBlock}) repeat the base, the frame's box, the terminal box and the shaft.
 */
public final class CreativeMotorAssets {
    static final String[] FACES = {"north", "south", "west", "east", "up", "down"};
    static final int NAVY = 0x25324a, NAVY_DARK = 0x1a2436, NAVY_LIGHT = 0x34466a, TEAL = 0x2f8fb0, TEAL_LIGHT = 0x58b6d2, YELLOW = 0xe9b732, STEEL = 0x8b949c, STEEL_DARK = 0x5d666e;

    record Face(String texture, double u0, double v0, double u1, double v1) {}
    /** A box in the north-facing model's 1/16 units, turned by {@code angle} degrees about the shaft axis (z) through (8, 8). */
    record Box(String name, double x0, double y0, double z0, double x1, double y1, double z1, double angle, Map<String, Face> faces) {}

    static final Map<String, BufferedImage> TEXTURES = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        var assets = new File(args[0]);
        textures();
        for (var t : TEXTURES.entrySet()) ImageIO.write(t.getValue(), "png", new File(assets, "textures/block/" + t.getKey() + ".png"));
        TEXTURES.put("shaft_steel", ImageIO.read(new File(assets, "textures/block/shaft_steel.png")));
        var boxes = model();
        Files.writeString(new File(assets, "models/block/creative_motor.json").toPath(), json(boxes));
        if (args.length < 2) return;
        var out = new File(args[1]); out.mkdirs();
        var sheet = new BufferedImage(840, 420, BufferedImage.TYPE_INT_RGB); var g = sheet.createGraphics();
        g.setColor(new Color(236, 241, 245)); g.fillRect(0, 0, 840, 420);
        view(g, 0, 10, boxes, new double[]{-.75, .55, -1}); view(g, 420, 10, boxes, new double[]{.9, .5, 1});
        g.dispose(); ImageIO.write(sheet, "png", new File(out, "creative_motor.png"));
        System.out.println(boxes.size() + " boxes, " + boxes.stream().mapToInt(b -> b.faces().size()).sum() + " faces");
    }

    // ---- textures -------------------------------------------------------------------------------------------------

    static void textures() {
        // Fins along u (horizontal lines) and along v: a TEFC frame's axial fins on a side face and on a top face.
        var h = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB); var v = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int a = y % 2 == 0 ? NAVY_LIGHT : NAVY_DARK, b = x % 2 == 0 ? NAVY_LIGHT : NAVY_DARK;
            h.setRGB(x, y, 0xff000000 | (x == 0 || x == 15 ? NAVY : a)); v.setRGB(x, y, 0xff000000 | (y == 0 || y == 15 ? NAVY : b));
        }
        TEXTURES.put("creative_motor_fins", h); TEXTURES.put("creative_motor_fins_top", v);
        // End shield: rim, eight bolts, bearing cap; eight-fold, so a box turned 45 degrees shows the same face.
        var shield = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            double dx = x - 15.5, dy = y - 15.5, r = Math.hypot(dx, dy), angle = Math.atan2(dy, dx);
            int c = r > 14.5 ? NAVY_DARK : r > 12.5 ? NAVY_LIGHT : r > 6 ? NAVY : r > 4.5 ? NAVY_LIGHT : STEEL_DARK;
            double sector = ((angle / (Math.PI / 4)) % 1 + 1) % 1;
            if (r > 9 && r < 11.5 && Math.abs(sector - .5) < .11) c = STEEL;
            shield.setRGB(x, y, 0xff000000 | c);
        }
        TEXTURES.put("creative_motor_shield", shield);
        // Fan cowl: a pressed cover with a ringed grille (bars, not a dark hole), eight-fold as the shield.
        var cowl = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            double r = Math.hypot(x - 15.5, y - 15.5);
            int c = r > 14.5 ? NAVY_DARK : r > 11.5 ? TEAL : ((int) r) % 2 == 0 ? TEAL_LIGHT : NAVY;
            if (r < 2.5) c = TEAL;
            cowl.setRGB(x, y, 0xff000000 | c);
        }
        TEXTURES.put("creative_motor_cowl", cowl);
        // Casing: plain navy with a darker edge; band: the cowl's side, teal.
        var plain = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB); var band = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            boolean edge = x == 0 || y == 0 || x == 15 || y == 15;
            plain.setRGB(x, y, 0xff000000 | (edge ? NAVY_DARK : NAVY)); band.setRGB(x, y, 0xff000000 | (edge ? NAVY_DARK : (x + y) % 7 == 0 ? TEAL_LIGHT : TEAL));
        }
        TEXTURES.put("creative_motor_case", plain); TEXTURES.put("creative_motor_band", band);
        // Plate: the creative power block's cue, a teal frame round a yellow bolt on the dark casing.
        var plate = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int[][] bolt = {{9, 3}, {8, 4}, {7, 5}, {6, 6}, {7, 7}, {8, 7}, {9, 7}, {8, 8}, {7, 9}, {6, 10}, {5, 11}, {8, 3}, {7, 4}, {6, 5}, {8, 6}, {9, 8}, {8, 9}, {7, 10}, {6, 11}};
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            boolean frame = (x == 1 || x == 14 || y == 1 || y == 14) && x >= 1 && x <= 14 && y >= 1 && y <= 14;
            plate.setRGB(x, y, 0xff000000 | (x == 0 || y == 0 || x == 15 || y == 15 ? NAVY_DARK : frame ? TEAL : NAVY_DARK + 0x040608));
        }
        for (var p : bolt) plate.setRGB(p[0] + 1, p[1], 0xff000000 | YELLOW);
        TEXTURES.put("creative_motor_plate", plate);
    }

    // ---- options --------------------------------------------------------------------------------------------------

    /** A box whose six faces show {@code texture} at the face's own size from the texture's corner. */
    static Box box(String name, double x0, double y0, double z0, double x1, double y1, double z1, String texture) {
        return box(name, x0, y0, z0, x1, y1, z1, 0, texture, texture, texture, FACES);
    }

    /** A box with {@code ends} on north and south, {@code sides} on west and east, {@code top} on up and down; only {@code shown} faces. */
    static Box box(String name, double x0, double y0, double z0, double x1, double y1, double z1, double angle, String ends, String sides, String top, String... shown) {
        var faces = new LinkedHashMap<String, Face>();
        for (var f : shown) faces.put(f, switch (f) {
            case "north", "south" -> new Face(ends, 0, 0, Math.min(16, x1 - x0), Math.min(16, y1 - y0));
            case "west", "east" -> new Face(sides, 0, 0, Math.min(16, z1 - z0), Math.min(16, y1 - y0));
            default -> new Face(top, 0, 0, Math.min(16, x1 - x0), Math.min(16, z1 - z0));
        });
        return new Box(name, x0, y0, z0, x1, y1, z1, angle, faces);
    }

    static Box face(Box box, String face, String texture, double u0, double v0, double u1, double v1) {
        var faces = new LinkedHashMap<>(box.faces()); faces.put(face, new Face(texture, u0, v0, u1, v1));
        return new Box(box.name(), box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1(), box.angle(), faces);
    }

    static Box shaft() { return box("shaft", 6.5, 6.5, 0, 9.5, 9.5, 4, 0, "shaft_steel", "shaft_steel", "shaft_steel", "north", "west", "east", "up", "down"); }

    /** The terminal box on the frame's top with the creative plate on its lid; the data gland's place is its lid's centre. */
    static List<Box> terminal(double y0) {
        var box = box("terminal box", 5, y0, 6, 11, 16, 11, 0, "creative_motor_case", "creative_motor_case", "creative_motor_case", "north", "south", "west", "east", "up");
        return List.of(face(box, "up", "creative_motor_plate", 0, 0, 16, 16));
    }

    static List<Box> feet() {
        return List.of(box("base", 2, 0, 3, 14, 1, 15, "creative_motor_case"), box("foot", 2.5, 1, 4.5, 5, 3.5, 13.5, "creative_motor_case"), box("foot", 11, 1, 4.5, 13.5, 3.5, 13.5, "creative_motor_case"));
    }

    /** The model, facing north: an eight-sided finned frame from four crossed boxes, a ringed fan cowl, feet, the terminal box, the plates. */
    static List<Box> model() {
        final boolean marked = true;
        var list = new ArrayList<Box>(feet());
        double flat = 10, half = flat / 2, side = flat * (Math.sqrt(2) - 1) / 2;
        for (int k = 0; k < 4; k++) {
            boolean upright = k == 1;
            double angle = k < 2 ? 0 : k == 2 ? 45 : -45;
            Box b = upright ? box("frame", 8 - side, 8 - half, 4, 8 + side, 8 + half, 12, 0, "creative_motor_shield", "creative_motor_fins", "creative_motor_fins_top", "north", "up", "down")
                    : box("frame", 8 - half, 8 - side, 4, 8 + half, 8 + side, 12, angle, "creative_motor_shield", "creative_motor_fins", "creative_motor_fins_top", "north", "west", "east");
            // The shield's disc lies over the four boxes as one: each box shows the part of it behind its own face.
            list.add(upright ? face(b, "north", "creative_motor_shield", 8 - side * 1.6, 0, 8 + side * 1.6, 16) : face(b, "north", "creative_motor_shield", 0, 8 - side * 1.6, 16, 8 + side * 1.6));
        }
        double cowlHalf = 5.5, cowlSide = 2 * cowlHalf * (Math.sqrt(2) - 1) / 2;
        String band = marked ? "creative_motor_band" : "creative_motor_case";
        for (int k = 0; k < 4; k++) {
            boolean upright = k == 1;
            double angle = k < 2 ? 0 : k == 2 ? 45 : -45;
            Box b = upright ? box("fan cowl", 8 - cowlSide, 8 - cowlHalf, 12, 8 + cowlSide, 8 + cowlHalf, 15, 0, "creative_motor_cowl", band, band, "north", "south", "up", "down")
                    : box("fan cowl", 8 - cowlHalf, 8 - cowlSide, 12, 8 + cowlHalf, 8 + cowlSide, 15, angle, "creative_motor_cowl", band, band, "north", "south", "west", "east");
            // The cowl stands half a unit proud of the frame all round: its front shows as a ring of the band.
            b = face(b, "north", band, 0, 0, 16, 16);
            list.add(upright ? face(b, "south", "creative_motor_cowl", 8 - cowlSide * 16 / 11, 0, 8 + cowlSide * 16 / 11, 16) : face(b, "south", "creative_motor_cowl", 0, 8 - cowlSide * 16 / 11, 16, 8 + cowlSide * 16 / 11));
        }
        list.addAll(marked ? terminal(12.5) : List.of(box("terminal box", 5, 12.5, 6, 11, 16, 11, "creative_motor_case")));
        if (marked) {
            // The plate on both flanks of the frame too, where a player looks at a motor on the ground.
            list.add(face(box("plate", 2.9, 6, 6, 3, 10, 10, 0, "creative_motor_plate", "creative_motor_plate", "creative_motor_plate", "west"), "west", "creative_motor_plate", 0, 0, 16, 16));
            list.add(face(box("plate", 13, 6, 6, 13.1, 10, 10, 0, "creative_motor_plate", "creative_motor_plate", "creative_motor_plate", "east"), "east", "creative_motor_plate", 0, 0, 16, 16));
        }
        list.add(shaft());
        return list;
    }

    // ---- JSON -----------------------------------------------------------------------------------------------------

    static String json(List<Box> boxes) {
        var used = new LinkedHashSet<String>();
        for (var b : boxes) for (var f : b.faces().values()) used.add(f.texture());
        var s = new StringBuilder("{\n  \"parent\": \"block/block\",\n  \"textures\": {\n    \"particle\": \"icheme:block/creative_motor_case\"");
        for (var t : used) s.append(",\n    \"").append(t).append("\": \"icheme:block/").append(t).append('"');
        s.append("\n  },\n  \"elements\": [\n");
        for (int i = 0; i < boxes.size(); i++) {
            var b = boxes.get(i);
            s.append("    {\"name\": \"").append(b.name()).append("\", \"from\": [").append(n(b.x0())).append(", ").append(n(b.y0())).append(", ").append(n(b.z0())).append("], \"to\": [")
                    .append(n(b.x1())).append(", ").append(n(b.y1())).append(", ").append(n(b.z1())).append("]");
            if (b.angle() != 0) s.append(", \"rotation\": {\"origin\": [8, 8, 8], \"axis\": \"z\", \"angle\": ").append(n(b.angle())).append("}");
            s.append(", \"faces\": {");
            int k = 0;
            for (var f : b.faces().entrySet()) {
                var face = f.getValue();
                s.append(k++ == 0 ? "" : ", ").append('"').append(f.getKey()).append("\": {\"uv\": [").append(n(face.u0())).append(", ").append(n(face.v0())).append(", ").append(n(face.u1())).append(", ")
                        .append(n(face.v1())).append("], \"texture\": \"#").append(face.texture()).append("\"").append(cull(b, f.getKey())).append("}");
            }
            s.append("}}").append(i + 1 < boxes.size() ? ",\n" : "\n");
        }
        return s.append("  ]\n}\n").toString();
    }

    /** A face on the block's own boundary is culled by a full neighbour there: the base's underside, the shaft's end, the terminal box's lid. */
    static String cull(Box b, String face) {
        boolean out = face.equals("down") && b.y0() == 0 || face.equals("up") && b.y1() == 16 || face.equals("north") && b.z0() == 0;
        return out && b.angle() == 0 ? ", \"cullface\": \"" + face + "\"" : "";
    }

    static String n(double v) { double r = Math.round(v * 1000) / 1000.0; return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r); }

    // ---- views ----------------------------------------------------------------------------------------------------

    static void view(Graphics2D g, int x, int y, List<Box> boxes, double[] eye) {
        int size = 400;
        g.setColor(Color.WHITE); g.fillRoundRect(x + 10, y, size, size, 12, 12);
        double[] forward = unit(eye), right = unit(new double[]{forward[2], 0, -forward[0]}), up = cross(forward, right);
        double scale = size / 27.0, ox = size / 2.0 - scale * dot(new double[]{8, 8, 8}, right), oy = size / 2.0 + scale * dot(new double[]{8, 8, 8}, up);
        var pixels = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB); var depth = new double[size * size]; Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        // The block's cell, as a faint floor square.
        for (var b : boxes) {
            double c = Math.cos(Math.toRadians(b.angle())), s = Math.sin(Math.toRadians(b.angle()));
            for (var f : b.faces().entrySet()) {
                double[][] p = corners(b, f.getKey());
                for (var q : p) { double dx = q[0] - 8, dy = q[1] - 8; q[0] = 8 + dx * c - dy * s; q[1] = 8 + dx * s + dy * c; }
                double[] normal = cross(sub(p[1], p[0]), sub(p[3], p[0]));
                if (dot(normal, forward) <= 1e-9) continue;
                double[] nn = unit(normal);
                // The game's face shades: up 1, north and south 0.8, east and west 0.6, down 0.5, blended for a turned face.
                double shade = Math.abs(nn[1]) * (nn[1] > 0 ? 1 : .5) + Math.abs(nn[2]) * .8 + Math.abs(nn[0]) * .6;
                shade /= Math.abs(nn[0]) + Math.abs(nn[1]) + Math.abs(nn[2]);
                var face = f.getValue(); var texture = TEXTURES.get(face.texture());
                double[][] uv = {{face.u0(), face.v0()}, {face.u0(), face.v1()}, {face.u1(), face.v1()}, {face.u1(), face.v0()}};
                double[][] q = new double[4][];
                for (int i = 0; i < 4; i++) q[i] = new double[]{ox + scale * dot(p[i], right), oy - scale * dot(p[i], up), dot(p[i], forward), uv[i][0], uv[i][1]};
                triangle(pixels, depth, q[0], q[1], q[2], texture, shade); triangle(pixels, depth, q[0], q[2], q[3], texture, shade);
            }
        }
        g.drawImage(pixels, x + 10, y, null);
    }

    /** A face's corners as seen from outside: top left, bottom left, bottom right, top right (the game's order). */
    static double[][] corners(Box b, String face) {
        double x0 = b.x0(), y0 = b.y0(), z0 = b.z0(), x1 = b.x1(), y1 = b.y1(), z1 = b.z1();
        return switch (face) {
            case "north" -> new double[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
            case "south" -> new double[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
            case "west" -> new double[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
            case "east" -> new double[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
            case "up" -> new double[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            default -> new double[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
        };
    }

    static void triangle(BufferedImage image, double[] depth, double[] a, double[] b, double[] c, BufferedImage texture, double shade) {
        double area = edge(a, b, c[0], c[1]); if (Math.abs(area) < 1e-10) return;
        int w = image.getWidth(), h = image.getHeight();
        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), x1 = Math.min(w - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), y1 = Math.min(h - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            double u = edge(b, c, x + .5, y + .5) / area, v = edge(c, a, x + .5, y + .5) / area, t = 1 - u - v;
            if (u < -1e-8 || v < -1e-8 || t < -1e-8) continue;
            double z = u * a[2] + v * b[2] + t * c[2]; int at = y * w + x;
            if (z <= depth[at]) continue;
            double tu = (u * a[3] + v * b[3] + t * c[3]) / 16, tv = (u * a[4] + v * b[4] + t * c[4]) / 16;
            int px = Math.clamp((int) (tu * texture.getWidth()), 0, texture.getWidth() - 1), py = Math.clamp((int) (tv * texture.getHeight()), 0, texture.getHeight() - 1);
            int rgb = texture.getRGB(px, py);
            depth[at] = z;
            image.setRGB(x, y, 0xff000000 | ((int) ((rgb >> 16 & 255) * shade) << 16) | ((int) ((rgb >> 8 & 255) * shade) << 8) | (int) ((rgb & 255) * shade));
        }
    }

    static double edge(double[] a, double[] b, double x, double y) { return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]); }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double[] unit(double[] a) { double n = Math.sqrt(dot(a, a)); return new double[]{a[0] / n, a[1] / n, a[2] / n}; }
}
