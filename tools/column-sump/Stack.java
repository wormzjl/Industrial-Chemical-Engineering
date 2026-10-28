import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Stacks sheets vertically with a label over each: Stack out.png label1 in1.png label2 in2.png ... */
public class Stack {
    public static void main(String[] a) throws Exception {
        int n = (a.length - 1) / 2, width = 0, height = 0, band = 26;
        var images = new BufferedImage[n];
        for (int i = 0; i < n; i++) {
            images[i] = ImageIO.read(new File(a[2 + 2 * i]));
            width = Math.max(width, images[i].getWidth());
            height += images[i].getHeight() + band;
        }
        var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setColor(new Color(0xEEF1F4));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        int y = 0;
        for (int i = 0; i < n; i++) {
            g.setColor(new Color(0x203040));
            g.drawString(a[1 + 2 * i], 12, y + 19);
            g.drawImage(images[i], 0, y + band, null);
            y += images[i].getHeight() + band;
        }
        g.dispose();
        ImageIO.write(out, "png", new File(a[0]));
    }
}
