import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the machine part item icons (batch 2026-11-03-rotating-machinery W10b): 16 x 16 flat silhouettes in the mod
 * logo's direction (flat navy and gold fields, a dark outline, no lighting or gradients). Today one icon: the pump
 * repair kit, a toolbox with its handle, lid seam and a gold clasp and a double-ended gold wrench on its front.
 *
 * <p>Run: {@code java -Djava.awt.headless=true tools/machine-parts/MachineParts.java <item texture folder> [preview folder]}.
 * Writes {@code repair_kit.png} into the texture folder, and with a preview folder {@code repair_kit-preview.png} there,
 * enlarged 16 times on a checker background.
 */
public final class MachineParts {
    static final int OUTLINE = 0xFF0E1B2E, NAVY = 0xFF1F3A5F, LID = 0xFF2B4C78, GOLD = 0xFFD4A73A, GOLD_DARK = 0xFF9C7420, CLEAR = 0x00000000;

    /** The repair kit, row by row: '.' clear, 'o' outline, 'n' body, 'l' lid, 'g' gold, 'd' dark gold. */
    static final String[] REPAIR_KIT = {
            "................",
            "................",
            ".....oooooo.....",
            "....oddddddo....",
            "....od....do....",
            ".oooodoooodoooo.",
            ".ollllllllllllo.",
            ".ollllllllllllo.",
            ".oooooggggooooo.",
            ".onnnnoddonnnno.",
            ".onggnnnnnnggno.",
            ".onggggggggggno.",
            ".onggnnnnnnggno.",
            ".onnnnnnnnnnnno.",
            ".oooooooooooooo.",
            "................",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: MachineParts <item texture folder> [preview folder]");
        var icon = draw(REPAIR_KIT);
        var out = new File(args[0], "repair_kit.png");
        ImageIO.write(icon, "png", out);
        System.out.println("wrote " + out);
        if (args.length > 1) {
            var preview = new File(args[1], "repair_kit-preview.png");
            ImageIO.write(enlarge(icon, 16), "png", preview);
            System.out.println("wrote " + preview);
        }
    }

    static BufferedImage draw(String[] rows) {
        if (rows.length != 16) throw new IllegalStateException("16 rows, not " + rows.length);
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            if (rows[y].length() != 16) throw new IllegalStateException("row " + y + " is " + rows[y].length() + " wide");
            for (int x = 0; x < 16; x++) image.setRGB(x, y, switch (rows[y].charAt(x)) {
                case 'o' -> OUTLINE;
                case 'n' -> NAVY;
                case 'l' -> LID;
                case 'g' -> GOLD;
                case 'd' -> GOLD_DARK;
                case '.' -> CLEAR;
                default -> throw new IllegalStateException("unknown cell '" + rows[y].charAt(x) + "'");
            });
        }
        return image;
    }

    /** {@code icon} enlarged {@code scale} times, nearest neighbour, over a light checker that shows the clear cells. */
    static BufferedImage enlarge(BufferedImage icon, int scale) {
        var image = new BufferedImage(icon.getWidth() * scale, icon.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = icon.getRGB(x / scale, y / scale);
                int checker = ((x / 8 + y / 8) & 1) == 0 ? 0xFFE8E8E8 : 0xFFCCCCCC;
                image.setRGB(x, y, (argb >>> 24) == 0 ? checker : argb);
            }
        return image;
    }
}
