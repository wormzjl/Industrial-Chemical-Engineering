import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Cabinet-mounted industrial operator terminal, sharing the constructor's steelwork and vanilla-model writer. */
public final class SimulatorAssets {
    private SimulatorAssets() {}

    static List<ConstructorAssets.Box> boxes() {
        var boxes = new ArrayList<ConstructorAssets.Box>();
        boxes.add(ConstructorAssets.box("steel cabinet plinth", .75, 0, .75, 15.25, 2, 15.25, "dark"));
        boxes.add(ConstructorAssets.box("lower control cabinet", 1, 2, 3, 15, 7, 15, "cabinet"));
        boxes.add(ConstructorAssets.box("pressed console worktop", 1, 7, 0, 15, 8, 15, "dark"));
        boxes.add(ConstructorAssets.box("stainless worktop skin", 1.12,7.88,.12,14.88,8.015,7.9,"frame"));
        boxes.add(ConstructorAssets.box("operator terminal cabinet", 1, 8, 8, 15, 16, 15, "cabinet"));
        boxes.add(ConstructorAssets.box("HMI backplate",1.75,8.85,7.9,14.25,15.6,8.02,"black"));
        boxes.add(ConstructorAssets.box("HMI left bezel",1.75,8.85,7.7,2.25,15.6,7.92,"dark"));
        boxes.add(ConstructorAssets.box("HMI right bezel",13.75,8.85,7.7,14.25,15.6,7.92,"dark"));
        boxes.add(ConstructorAssets.box("HMI bottom bezel",2.25,8.85,7.7,13.75,9.35,7.92,"dark"));
        boxes.add(ConstructorAssets.box("HMI top bezel",2.25,15.1,7.7,13.75,15.6,7.92,"dark"));
        boxes.add(new ConstructorAssets.Box("recessed flowsheet display", new double[]{2.25, 9.35, 7.82}, new double[]{13.75, 15.1, 7.88},
                Map.of("north", new ConstructorAssets.Face("screen"))));
        boxes.add(ConstructorAssets.box("screen hood",1,15.6,7.25,15,16,8.05,"dark"));
        var keyboard = ConstructorAssets.box("sealed panel keyboard", 2.5, 8.015, 1, 11.5, 8.25, 5.5, "black");
        keyboard.faces().put("up", new ConstructorAssets.Face("keys"));
        boxes.add(keyboard);
        boxes.add(ConstructorAssets.box("trackball mounting plate",12,8.015,1.5,14,8.18,4.5,"frame"));
        boxes.add(ConstructorAssets.box("rubber trackball",12.55,8.18,2.2,13.45,8.5,3.1,"black"));
        boxes.add(ConstructorAssets.box("left trackball key",12.2,8.18,3.55,12.9,8.28,4.2,"dark"));
        boxes.add(ConstructorAssets.box("right trackball key",13.1,8.18,3.55,13.8,8.28,4.2,"dark"));
        boxes.add(ConstructorAssets.box("lower service door gasket",1.5,2.4,2.82,14.5,6.6,3.02,"black"));
        boxes.add(ConstructorAssets.box("lower service door",1.7,2.6,2.7,14.3,6.4,2.85,"cabinet"));
        boxes.add(ConstructorAssets.box("quarter-turn door latch",13.2,4.05,2.55,13.5,4.8,2.74,"dark"));
        for(double y:new double[]{3.1,5.5})boxes.add(ConstructorAssets.box("service door hinge",1.65,y,2.56,2.05,y+.55,3.02,"frame"));
        for(double x:new double[]{1.95,13.8})for(double y:new double[]{9.0,15.2})boxes.add(ConstructorAssets.box("HMI captive screw",x,y,7.64,x+.22,y+.22,7.72,"frame"));
        boxes.add(ConstructorAssets.box("rear access panel gasket",1.75,2.7,14.99,14.25,15.3,15.04,"black"));
        boxes.add(ConstructorAssets.box("rear access panel",2,2.95,15.025,14,15.05,15.1,"cabinet"));
        for(int row=0;row<6;row++){
            double y=9.2+.55*row;
            boxes.add(ConstructorAssets.box("rear ventilation louvre",3,y,15.09,13,y+.15,15.16,"black"));
        }
        for(int row=0;row<4;row++){
            double y=3.3+.5*row;
            boxes.add(ConstructorAssets.box("side cooling louvre",14.99,y,7,15.06,y+.16,12.5,"black"));
        }
        return List.copyOf(boxes);
    }

    static Map<String, String> texturePaths() {
        var paths = new LinkedHashMap<String, String>();
        paths.put("particle", "icheme:block/pump_casting");
        paths.put("cabinet", "icheme:block/pump_casting");
        paths.put("frame", "icheme:block/equipment_flange");
        paths.put("dark", "icheme:block/equipment_steelwork");
        paths.put("black", "icheme:block/equipment_opening");
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
