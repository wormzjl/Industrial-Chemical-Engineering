import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Deterministic block model and pixel sprites for the equipment constructor's fabrication bench. */
public final class ConstructorAssets {
    static final String[] DIRECTIONS = {"north", "east", "south", "west", "up", "down"};
    record Face(String texture, double[] uv) {
        Face(String texture) { this(texture, new double[]{0, 0, 16, 16}); }
    }
    record Box(String name, double[] from, double[] to, Map<String, Face> faces) {}
    private ConstructorAssets() {}

    static List<Box> boxes() {
        var boxes = new ArrayList<Box>();
        boxes.add(box("cast base", 1, 0, 1, 15, 2, 15, "frame"));
        boxes.add(box("enclosed cabinet", 1, 2, 1, 15, 10, 15, "cabinet"));
        var deck = box("slotted worktable", 0, 10, 0, 16, 12, 16, "frame");
        deck.faces().put("up", new Face("bed"));
        deck.faces().put("north", new Face("warning", new double[]{0, 6, 16, 10}));
        boxes.add(deck);
        var controls = box("recessed blueprint console", 2, 3.5, .75, 14, 9.5, 1, "frame");
        controls.faces().put("north", new Face("controls"));
        boxes.add(controls);
        var dial = box("brass selector", 3, 4.5, .2, 5, 6.5, .75, "brass");
        boxes.add(dial);
        boxes.add(box("left gantry upright", 1, 12, 11, 3, 16, 13, "frame"));
        boxes.add(box("right gantry upright", 13, 12, 11, 15, 16, 13, "frame"));
        boxes.add(box("cross rail", 3, 14.5, 11, 13, 16, 13, "frame"));
        boxes.add(box("tool carriage", 6, 13, 10.5, 10, 15, 13.5, "cabinet"));
        boxes.add(box("tool nose", 7, 12.5, 10.5, 9, 13, 12.5, "frame"));
        boxes.add(new Box("blueprint sheet", new double[]{4, 12.015, 3}, new double[]{12, 12.025, 9},
                Map.of("up", new Face("plan"))));
        boxes.add(box("left paper clamp", 3.5, 12.03, 4, 4.5, 12.3, 5.5, "brass"));
        boxes.add(box("right paper clamp", 11.5, 12.03, 4, 12.5, 12.3, 5.5, "brass"));
        return List.copyOf(boxes);
    }

    private static Box box(String name, double x0, double y0, double z0, double x1, double y1, double z1, String texture) {
        var faces = new LinkedHashMap<String, Face>();
        for (var direction : DIRECTIONS) faces.put(direction, new Face(texture));
        return new Box(name, new double[]{x0, y0, z0}, new double[]{x1, y1, z1}, faces);
    }

    static Map<String, BufferedImage> sprites() {
        var out = new LinkedHashMap<String, BufferedImage>();
        var cabinet = surface(32, 32, 0x52666A);
        var g = cabinet.createGraphics();
        rect(g, 1, 1, 30, 30, 0x35484C); rect(g, 2, 2, 28, 28, 0x607579);
        rect(g, 3, 3, 26, 26, 0x52666A);
        for (int y = 15; y < 25; y += 3) {
            rect(g, 7, y, 18, 2, 0x27383C); rect(g, 8, y + 2, 17, 1, 0x6E8285);
        }
        for (int x : new int[]{4, 26}) for (int y : new int[]{4, 26}) screw(g, x, y);
        g.dispose(); out.put("cabinet", cabinet);

        var frame = surface(32, 32, 0x829092); g = frame.createGraphics();
        rect(g, 0, 0, 32, 2, 0xA6B1B2); rect(g, 0, 30, 32, 2, 0x46575B);
        rect(g, 0, 2, 2, 28, 0x64777A); rect(g, 30, 2, 2, 28, 0x64777A);
        g.dispose(); out.put("frame", frame);

        var bed = surface(32, 32, 0x809093); g = bed.createGraphics();
        rect(g, 1, 1, 30, 1, 0xAFB9B9); rect(g, 1, 30, 30, 1, 0x43565A);
        for (int x : new int[]{4, 11, 20, 27}) {
            rect(g, x, 3, 1, 26, 0x33494E); rect(g, x + 1, 3, 1, 26, 0xA2AFB0);
        }
        for (int x : new int[]{2, 28}) for (int y : new int[]{2, 28}) screw(g, x, y);
        g.dispose(); out.put("bed", bed);

        var plan = surface(32, 32, 0xE0DFCF); g = plan.createGraphics();
        rect(g, 1, 1, 30, 30, 0x173D50);
        blueprint(g, 3, 3, 26, 25); rect(g, 4, 28, 14, 1, 0x88BEC9);
        g.dispose(); out.put("plan", plan);

        var controls = surface(64, 32, 0x33464A); g = controls.createGraphics();
        rect(g, 1, 1, 62, 1, 0x899A9D); rect(g, 1, 30, 62, 1, 0x1E2D31);
        rect(g, 3, 3, 42, 25, 0x0E232D); blueprint(g, 5, 5, 38, 21);
        rect(g, 49, 5, 5, 4, 0x92BB79); rect(g, 57, 5, 3, 4, 0xCBAF65);
        rect(g, 50, 13, 10, 1, 0x94A3A3); rect(g, 50, 28, 4, 2, 0x9BAEAF);
        g.dispose(); out.put("controls", controls);

        var warning = surface(16, 16, 0xBEA346); g = warning.createGraphics();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            if (Math.floorMod(x + y, 8) < 3) warning.setRGB(x, y, 0x303B3E);
        g.dispose(); out.put("warning", warning);
        var brass = surface(16, 16, 0xB99F57); g = brass.createGraphics();
        rect(g, 0, 0, 16, 1, 0xD5BF7D); rect(g, 0, 15, 16, 1, 0x77683C);
        rect(g, 7, 2, 2, 5, 0x354347); g.dispose(); out.put("brass", brass);
        return out;
    }

    private static BufferedImage surface(int width, int height, int colour) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int grain = ((x / 3 * 17 + y / 2 * 31) & 3) - 1;
            int r = (colour >> 16 & 255) + grain, g = (colour >> 8 & 255) + grain, b = (colour & 255) + grain;
            image.setRGB(x, y, Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b));
        }
        return image;
    }

    private static void rect(Graphics2D g, int x, int y, int w, int h, int colour) {
        g.setColor(new Color(colour)); g.fillRect(x, y, w, h);
    }

    private static void screw(Graphics2D g, int x, int y) {
        rect(g, x, y, 2, 2, 0xA1AFB0); rect(g, x, y, 2, 1, 0x35464A);
    }

    private static void blueprint(Graphics2D g, int x, int y, int w, int h) {
        rect(g, x, y, w, h, 0x173D50);
        for (int i = x + 2; i < x + w; i += 4) rect(g, i, y, 1, h, 0x224D5D);
        for (int i = y + 2; i < y + h; i += 4) rect(g, x, i, w, 1, 0x224D5D);
        int left = x + w / 3, right = x + 2 * w / 3, top = y + 4, bottom = y + h - 5;
        g.setColor(new Color(0xCEE3DF));
        g.drawOval(left, top - 2, right - left, 4); g.drawLine(left, top, left, bottom);
        g.drawLine(right, top, right, bottom); g.drawArc(left, bottom - 2, right - left, 4, 180, 180);
        g.drawLine(left + 2, bottom + 2, left + 2, bottom + 4); g.drawLine(right - 2, bottom + 2, right - 2, bottom + 4);
        g.setColor(new Color(0x7CB9C5));
        g.drawLine(right + 4, top - 2, right + 4, bottom + 2);
        g.drawLine(right + 2, top - 2, right + 6, top - 2); g.drawLine(right + 2, bottom + 2, right + 6, bottom + 2);
        g.drawLine(left - 4, top + 2, left - 1, top + 2); g.drawLine(left - 4, top + 2, left - 4, top + 5);
    }

    private static String vector(double[] values) {
        return "[" + values[0] + ", " + values[1] + ", " + values[2] + "]";
    }

    private static String model() {
        var json = new StringBuilder("{\n  \"parent\": \"minecraft:block/block\",\n  \"textures\": {\n");
        json.append("    \"particle\": \"icheme:block/constructor_cabinet\"");
        for (var name : sprites().keySet()) json.append(",\n    \"").append(name).append("\": \"icheme:block/constructor_").append(name).append('"');
        json.append("\n  },\n  \"elements\": [\n");
        boolean first = true;
        for (var box : boxes()) {
            if (!first) json.append(",\n"); first = false;
            json.append("    {\n      \"name\": \"").append(box.name()).append("\",\n      \"from\": ").append(vector(box.from()))
                    .append(",\n      \"to\": ").append(vector(box.to())).append(",\n      \"faces\": {");
            boolean firstFace = true;
            for (var entry : box.faces().entrySet()) {
                if (!firstFace) json.append(','); firstFace = false;
                var face = entry.getValue(); var uv = face.uv();
                json.append("\n        \"").append(entry.getKey()).append("\": { \"uv\": [").append(uv[0]).append(", ").append(uv[1])
                        .append(", ").append(uv[2]).append(", ").append(uv[3]).append("], \"texture\": \"#").append(face.texture()).append("\" }");
            }
            json.append("\n      }\n    }");
        }
        return json.append("\n  ]\n}\n").toString();
    }

    public static void main(String[] args) throws Exception {
        var assets = Path.of(args[0]);
        var textures = assets.resolve("textures/block");
        for (var entry : sprites().entrySet()) ImageIO.write(entry.getValue(), "png", textures.resolve("constructor_" + entry.getKey() + ".png").toFile());
        Files.writeString(assets.resolve("models/block/equipment_constructor.json"), model());
        System.out.println("Wrote constructor model and " + sprites().size() + " sprites");
    }
}
