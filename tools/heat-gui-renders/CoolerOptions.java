import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The water cooler's symbol, four ways, for the owner (batch 2026-11-02-process-simulator, C3): each option beside the exchanger and
 * the air cooler it must be told from, at the sheet's zoom 1 and 2 and at 4 for detail, in the solved, warning and idle looks the
 * sheet tints a symbol with. The shapes are {@code SimulatorIcons}' {@code waterCooler} cases.
 *
 * <pre>java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/CoolerOptions.java tmp/c3/cooler-options.png</pre>
 */
public class CoolerOptions {
    static final int WELL = 0xFF10181D, ROW = 150, LABEL = 220;
    /** Where each zoom's column starts, after the labels. */
    static final int[] AT = {LABEL, LABEL + 240, LABEL + 680};
    static final String[] NAMES = {"A: shell with water waves", "B: hairpin (double pipe)", "C: box cooler (coil in a bath)", "D: exchanger with a drop"};

    public static void main(String[] args) throws Exception {
        int width = LABEL + 680 + 280, height = 60 + ROW * 5;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(WELL));
        g.fillRect(0, 0, width, height);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(Palette.TEXT));
        g.drawString("Water cooler symbol: pick one (the sheet tints it: solved, warning, idle)", 12, 26);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        String[] heads = {"zoom 1 (as on the sheet)", "zoom 2", "zoom 4"};
        for (int k = 0; k < 3; k++) g.drawString(heads[k], AT[k], 50);
        Look[] looks = {Look.TEAL, Look.YELLOW, new Look(FlowsheetPainter.BODY, Palette.LINE, Palette.TEXT)};
        for (int row = 0; row < 5; row++) {
            int y = 60 + row * ROW;
            g.setColor(new Color(Palette.TEXT));
            g.drawString(row < 4 ? NAMES[row] : "for comparison: exchanger, air cooler", 12, y + 20);
            if (row < 4) SimulatorIcons.waterCooler = (char) ('A' + row);
            // Zoom 1 and 2: the three looks side by side; zoom 4: the solved look alone.
            for (int k = 0; k < 3; k++) {
                int scale = k == 0 ? 1 : k == 1 ? 2 : 4, x = AT[k];
                if (row == 4) {
                    if (k == 2) continue;
                    paint(g, SimulatorPrototypes.Glyph.EXCHANGER, x, y + 40, scale, looks[0]);
                    paint(g, SimulatorPrototypes.Glyph.AIR_COOLER, x + 64 * scale + 10, y + 40, scale, looks[0]);
                    continue;
                }
                if (k == 2) paint(g, SimulatorPrototypes.Glyph.WATER_COOLER, x, y + 10, 4, looks[0]);
                else for (int l = 0; l < 3; l++) paint(g, SimulatorPrototypes.Glyph.WATER_COOLER, x + l * (64 * scale + 10), y + 40, scale, looks[l]);
            }
        }
        g.dispose();
        var out = Path.of(args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(image, "png", out.toFile());
        System.out.println("wrote " + out);
    }

    private static void paint(Graphics2D g, SimulatorPrototypes.Glyph glyph, int x, int y, int scale, Look look) {
        SimulatorIcons.paint(g, glyph, x, y, glyph.w * scale, glyph.h * scale, look);
    }
}
