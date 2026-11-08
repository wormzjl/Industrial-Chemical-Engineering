import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Pairs PumpRender NORMAL/LOW views without changing their geometry or textures. Args: render-folder name kind... */
public final class DetailSheet {
    public static void main(String[] args) throws Exception {
        var folder = Path.of(args[0]);
        var sheet = new BufferedImage(1100, (args.length - 2) * 320, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        try {
            g.setColor(new Color(0xd8e0e3));
            g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
            for (int i = 2; i < args.length; i++) for (int column = 0; column < 2; column++) {
                String detail = column == 0 ? "normal" : "low";
                var image = ImageIO.read(folder.resolve(args[i] + "-" + detail + "-iso2.png").toFile());
                double scale = Math.min(535.0 / image.getWidth(), 280.0 / image.getHeight());
                int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
                int y = (i - 2) * 320;
                g.drawImage(image, column * 550 + (550 - w) / 2, y + 28, w, h, null);
                g.setColor(new Color(0x16272f));
                g.drawString(args[i].toUpperCase() + " / " + detail.toUpperCase(), column * 550 + 18, y + 20);
            }
        } finally { g.dispose(); }
        ImageIO.write(sheet, "png", folder.resolve(args[1] + "-comparison.png").toFile());
    }
}
