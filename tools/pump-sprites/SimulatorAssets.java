import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Pixel-art process-control console. Shares the constructor's metalwork palette and vanilla-model writer. */
public final class SimulatorAssets {
    private SimulatorAssets() {}

    static List<ConstructorAssets.Box> boxes() {
        var boxes = new ArrayList<ConstructorAssets.Box>();
        boxes.add(ConstructorAssets.box("terminal base", 1, 0, 1, 15, 2, 15, "frame"));
        var pedestal = ConstructorAssets.box("console pedestal", 2, 2, 6, 14, 7, 15, "cabinet");
        pedestal.faces().put("up", new ConstructorAssets.Face("frame"));
        boxes.add(pedestal);
        boxes.add(ConstructorAssets.box("keyboard tray", 1, 6, 0, 15, 7, 6, "frame"));
        boxes.add(ConstructorAssets.box("monitor bezel", 1, 7, 9, 15, 16, 12, "bezel"));
        boxes.add(ConstructorAssets.box("vented monitor housing", 2, 8, 12, 14, 15, 14.5, "vents"));
        boxes.add(new ConstructorAssets.Box("flowsheet display", new double[]{2, 9, 8.7}, new double[]{14, 15, 9},
                Map.of("north", new ConstructorAssets.Face("screen"))));
        var keyboard = ConstructorAssets.box("keyboard", 4, 7.03, 1, 14, 7.35, 6, "bezel");
        keyboard.faces().put("up", new ConstructorAssets.Face("keys"));
        boxes.add(keyboard);
        boxes.add(ConstructorAssets.box("trackball controller", 1.6, 7.03, 2, 3.4, 7.5, 5, "brass"));
        boxes.add(ConstructorAssets.box("power selector", 3, 7.6, 8.55, 4.25, 8.65, 9, "brass"));
        return List.copyOf(boxes);
    }

    static Map<String, String> texturePaths() {
        var paths = new LinkedHashMap<String, String>();
        paths.put("particle", "icheme:block/constructor_cabinet");
        for (var name : new String[]{"frame", "cabinet", "brass"}) paths.put(name, "icheme:block/constructor_" + name);
        for (var name : new String[]{"bezel", "screen", "keys", "vents"}) paths.put(name, "icheme:block/simulator_" + name);
        return paths;
    }

    static Map<String, BufferedImage> sprites() {
        var out = new LinkedHashMap<String, BufferedImage>();
        var bezel = ConstructorAssets.surface(32, 32, 0x314247);
        var g = bezel.createGraphics();
        ConstructorAssets.rect(g, 0, 0, 32, 1, 0x82979B); ConstructorAssets.rect(g, 0, 31, 32, 1, 0x1C2C30);
        ConstructorAssets.rect(g, 1, 1, 30, 1, 0x526B71); ConstructorAssets.rect(g, 1, 30, 30, 1, 0x23373D);
        g.dispose(); out.put("bezel", bezel);

        var vents = ConstructorAssets.surface(32, 32, 0x52666A); g = vents.createGraphics();
        for (int y = 5; y < 27; y += 4) {
            ConstructorAssets.rect(g, 5, y, 22, 2, 0x27383C); ConstructorAssets.rect(g, 6, y + 2, 21, 1, 0x71868A);
        }
        g.dispose(); out.put("vents", vents);

        var keys = ConstructorAssets.surface(64, 32, 0x25383E); g = keys.createGraphics();
        for (int row = 0; row < 3; row++) for (int key = 0; key < 12; key++) {
            int x = 3 + 5 * key, y = 3 + 7 * row;
            ConstructorAssets.rect(g, x, y, 4, 5, 0x82969B); ConstructorAssets.rect(g, x, y, 4, 1, 0xAEC1C3);
        }
        ConstructorAssets.rect(g, 3, 25, 8, 4, 0x82969B); ConstructorAssets.rect(g, 14, 25, 35, 4, 0x82969B);
        ConstructorAssets.rect(g, 52, 25, 8, 4, 0xB5A367); g.dispose(); out.put("keys", keys);

        var screen = ConstructorAssets.surface(128, 64, 0x102B36); g = screen.createGraphics();
        ConstructorAssets.rect(g, 1, 1, 126, 7, 0x284B58);
        for (int x = 8; x < 88; x += 8) ConstructorAssets.rect(g, x, 11, 1, 41, 0x173A46);
        for (int y = 12; y < 53; y += 8) ConstructorAssets.rect(g, 4, y, 83, 1, 0x173A46);
        g.setColor(new Color(0xA6D4D9));
        g.drawLine(4, 34, 9, 34); g.drawOval(9, 28, 12, 12);
        g.drawLine(12, 30, 18, 34); g.drawLine(18, 34, 12, 38); g.drawLine(12, 38, 12, 30);
        g.drawLine(21, 34, 31, 34); g.drawOval(31, 26, 16, 16);
        g.drawLine(33, 39, 45, 29); g.drawLine(47, 34, 61, 34);
        g.drawOval(61, 17, 14, 5); g.drawLine(61, 20, 61, 47); g.drawLine(75, 20, 75, 47); g.drawArc(61, 44, 14, 5, 180, 180);
        for (int y = 25; y < 44; y += 5) g.drawLine(63, y, 73, y);
        g.drawLine(68, 17, 68, 13); g.drawLine(68, 13, 84, 13);
        g.fillPolygon(new int[]{82, 86, 82}, new int[]{11, 13, 15}, 3);
        g.setColor(new Color(0xC3AD69)); g.drawLine(68, 49, 68, 52); g.drawLine(68, 52, 84, 52);
        g.fillPolygon(new int[]{82, 86, 82}, new int[]{50, 52, 54}, 3);
        ConstructorAssets.rect(g, 91, 12, 34, 39, 0x264652); ConstructorAssets.rect(g, 93, 14, 30, 35, 0x0C2530);
        g.setColor(new Color(0x46717A)); g.drawLine(96, 18, 96, 45); g.drawLine(96, 45, 120, 45);
        g.setColor(new Color(0x8EC7AB)); g.drawPolyline(new int[]{97, 102, 107, 112, 117, 121}, new int[]{41, 35, 38, 28, 30, 21}, 6);
        ConstructorAssets.rect(g, 6, 57, 22, 2, 0x54828B); ConstructorAssets.rect(g, 33, 57, 22, 2, 0x54828B);
        ConstructorAssets.rect(g, 60, 57, 22, 2, 0xB5A367); ConstructorAssets.rect(g, 116, 3, 7, 3, 0x8EC7AB);
        g.dispose(); out.put("screen", screen);
        return out;
    }

    public static void main(String[] args) throws Exception {
        var assets = Path.of(args[0]);
        for (var entry : sprites().entrySet()) ImageIO.write(entry.getValue(), "png",
                assets.resolve("textures/block/simulator_" + entry.getKey() + ".png").toFile());
        Files.writeString(assets.resolve("models/block/process_simulator.json"), ConstructorAssets.model(texturePaths(), boxes()));
        System.out.println("Wrote simulator model and four sprites");
    }
}
