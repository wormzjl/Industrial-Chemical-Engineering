import com.wormzjl.icheme.client.gui.common.kit.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public class DriveTextRender {
    public static void main(String[] args) throws Exception {
        HeatDiagramRender.font = HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));
        for (var entry : Map.of("light", PanelSkin.LIGHT, "dark", PanelSkin.DARK).entrySet()) {
            var skin = entry.getValue();
            var image = new BufferedImage(600, 246, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.scale(3, 3);
            var c = new HeatDiagramRender.Java2dCanvas(g);
            skin.page(c, new Rect(0, 0, 200, 82));
            var rows = List.<Column.Row>of(new Column.Group("Drive", null, List.of(
                    new Column.Line("STEAM TURBINE 20000.0 kW", Palette.TEXT),
                    new Column.Line("TRIPPED: OVERSPEED · 0 rpm", Palette.TEXT),
                    new Column.Buttons(List.of(new Column.Button("open_driver", "Open driver", true, null))))));
            var column = Column.place(rows, 6, 6, 188, c.measure(), Column.Metrics.COMPACT);
            column.draw(c, 0, skin);
            skin.button(c, column.slots().get("open_driver"), "Open driver", Skin.Press.REST, false);
            g.dispose();
            ImageIO.write(image, "png", Path.of(args[0] + "-" + entry.getKey() + ".png").toFile());
        }
    }
}
