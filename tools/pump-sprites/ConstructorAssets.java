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
        boxes.add(box("welded plinth", 1, 0, 1, 15, 2, 15, "dark"));
        boxes.add(box("enclosed cabinet", 1, 2, 1, 15, 10, 15, "cabinet"));
        boxes.add(box("cast worktable bed", 0, 10, 0, 16, 11.5, 16, "dark"));
        var deck = box("machined slotted worktop", .15, 11.5, .15, 15.85, 12, 15.85, "frame");
        deck.faces().put("up", new Face("bed"));
        boxes.add(deck);
        boxes.add(box("front service door gasket", 1.5, 2.4, .91, 14.5, 9.6, 1.02, "black"));
        boxes.add(box("front service door", 1.7, 2.6, .82, 14.3, 9.4, .94, "cabinet"));
        boxes.add(box("operator panel backplate", 2, 4.9, .78, 10.6, 9.2, .85, "black"));
        boxes.add(box("operator panel left bezel",2,4.9,.68,2.3,9.2,.8,"dark"));
        boxes.add(box("operator panel right bezel",10.3,4.9,.68,10.6,9.2,.8,"dark"));
        boxes.add(box("operator panel bottom bezel",2.3,4.9,.68,10.3,5.05,.8,"dark"));
        boxes.add(box("operator panel top bezel",2.3,9.05,.68,10.3,9.2,.8,"dark"));
        var controls = box("recessed fabrication HMI", 2.3, 5.05, .74, 10.3, 9.05, .78, "dark");
        controls.faces().put("north", new Face("controls"));
        boxes.add(controls);
        boxes.add(box("emergency stop backing", 11.65, 6.15, .73, 12.75, 7.25, .86, "warning"));
        boxes.add(box("red emergency stop", 11.85, 6.35, .38, 12.55, 7.05, .75, "red"));
        boxes.add(box("selector escutcheon", 11.8, 7.85, .74, 12.6, 8.65, .86, "frame"));
        boxes.add(box("black rotary selector", 12.08, 7.95, .5, 12.32, 8.55, .76, "black"));
        boxes.add(box("door latch", 13.4, 3.8, .58, 13.65, 4.8, .84, "dark"));
        for(double y:new double[]{3.1,8.6})boxes.add(box("service door hinge", 1.65,y,.68,2.05,y+.55,1.02,"frame"));
        for(int row=0;row<5;row++){
            double y=3.4+.48*row;
            boxes.add(box("left cabinet louvre", .94,y,4,1.01,y+.16,12,"black"));
            boxes.add(box("right cabinet louvre", 14.99,y,4,15.06,y+.16,12,"black"));
        }
        boxes.add(box("rear maintenance panel gasket", 2,3,14.99,14,9,15.04,"black"));
        boxes.add(box("rear maintenance panel", 2.2,3.2,15.025,13.8,8.8,15.08,"cabinet"));
        boxes.add(box("left gantry upright", 1, 12, 11, 3, 16, 13, "dark"));
        boxes.add(box("right gantry upright", 13, 12, 11, 15, 16, 13, "dark"));
        boxes.add(box("cross rail", 3, 14.5, 11, 13, 16, 13, "dark"));
        boxes.add(box("linear guide rail", 3,14.85,10.78,13,15.2,11.02,"frame"));
        boxes.add(box("tool carriage", 6, 13, 10.5, 10, 15, 13.5, "cabinet"));
        boxes.add(box("tool head face", 6.3,13.25,10.42,9.7,14.65,10.52,"dark"));
        boxes.add(box("tool nose", 7.5, 12.5, 11, 8.5, 13, 12, "frame"));
        for(double x:new double[]{6.55,9.2})for(double y:new double[]{13.5,14.2})boxes.add(box("carriage fastener",x,y,10.32,x+.25,y+.25,10.44,"frame"));
        for(double x:new double[]{.7,12.7}){
            boxes.add(box("gantry mounting shoe",x,12.005,10.6,x+2.6,12.3,13.4,"frame"));
            for(double z:new double[]{10.7,13.0})boxes.add(box("mounting stud",x+.5,12.3,z,x+.8,12.5,z+.3,"dark"));
        }
        boxes.add(box("workholding plate",4.5,12.005,3.5,11.5,12.06,8.5,"frame"));
        boxes.add(box("machined steel blank",6,12.065,5,10,12.35,7,"frame"));
        return List.copyOf(boxes);
    }

    static Box box(String name, double x0, double y0, double z0, double x1, double y1, double z1, String texture) {
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

    static BufferedImage surface(int width, int height, int colour) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int grain = ((x / 3 * 17 + y / 2 * 31) & 3) - 1;
            int r = (colour >> 16 & 255) + grain, g = (colour >> 8 & 255) + grain, b = (colour & 255) + grain;
            image.setRGB(x, y, Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b));
        }
        return image;
    }

    static void rect(Graphics2D g, int x, int y, int w, int h, int colour) {
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

    static Map<String,String> texturePaths() {
        var textures = new LinkedHashMap<String, String>();
        textures.put("particle", "icheme:block/pump_casting");
        for (var name : sprites().keySet()) textures.put(name, "icheme:block/constructor_" + name);
        textures.put("cabinet","icheme:block/pump_casting");
        textures.put("frame","icheme:block/equipment_flange");
        textures.put("dark","icheme:block/equipment_steelwork");
        textures.put("black","icheme:block/equipment_opening");
        textures.put("red","icheme:block/equipment_actuator");
        return textures;
    }

    static String model(Map<String, String> textures, List<Box> boxes) {
        var json = new StringBuilder("{\n  \"parent\": \"minecraft:block/block\",\n  \"textures\": {\n");
        boolean firstTexture = true;
        for (var entry : textures.entrySet()) {
            if (!firstTexture) json.append(",\n"); firstTexture = false;
            json.append("    \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append('"');
        }
        json.append("\n  },\n  \"elements\": [\n");
        boolean first = true;
        for (var box : boxes) {
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
        Files.writeString(assets.resolve("models/block/equipment_constructor.json"), model(texturePaths(),boxes()));
        System.out.println("Wrote constructor model and " + sprites().size() + " sprites");
    }
}
