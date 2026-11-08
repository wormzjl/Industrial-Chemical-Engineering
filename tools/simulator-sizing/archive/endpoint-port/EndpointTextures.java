import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Deterministic pixel-art port covers; fresh sprites, with the pipe's existing neutral steel palette. */
public final class EndpointTextures {
    public static void main(String[] args) throws Exception {
        var target = Path.of(args[0]);
        for (boolean source : new boolean[]{true, false}) {
            var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                double dx = x - 15.5, dy = y - 15.5, r = Math.hypot(dx, dy);
                int grain = Math.floorMod(x * 73 + y * 31 + x * y * 7, 7) - 3;
                int rgb = grey(153 + grain);
                if (r > 14) rgb = grey(93 + grain);
                else if (r > 12.5) rgb = grey(177 + grain);
                else if (r > 11) rgb = source ? 0x548F75 : 0xAB655B;
                else if (r < 10.5) rgb = grey(61 + grain);
                // A short arrow on each quadrant: outward on a source, inward on a sink.
                double u = Math.max(Math.abs(dx), Math.abs(dy)), v = Math.min(Math.abs(dx), Math.abs(dy));
                boolean arrow = source ? u >= 3 && u <= 9.5 && (v <= 1 || u >= 6.5 && v <= 9.5 - u)
                        : u >= 3 && u <= 9.5 && (v <= 1 || u <= 6 && v <= u - 3);
                if (arrow) rgb = source ? 0x96D2AD : 0xE0A093;
                if (r < 2.3) rgb = source ? 0x96D2AD : 0x191E20;
                double bolt = Math.hypot(Math.abs(dx) - 9, Math.abs(dy) - 9);
                if (bolt <= 1.4) rgb = bolt < .65 ? 0x343A3D : 0x60676A;
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
            ImageIO.write(image, "png", target.resolve(source ? "equipment_feed.png" : "equipment_drain.png").toFile());
        }
    }
    private static int grey(int value) { return value << 16 | value << 8 | value; }
}
