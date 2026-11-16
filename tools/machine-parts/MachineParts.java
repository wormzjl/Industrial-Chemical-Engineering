import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the machine part item icons (batch 2026-11-03-rotating-machinery W10b): 16 x 16 flat silhouettes in the mod
 * logo's direction (flat navy and gold fields, a dark outline, no lighting or gradients). Two icons: the pump repair kit,
 * a toolbox with its handle, lid seam and a gold clasp and a double-ended gold wrench on its front; and the handheld
 * pollution meter (batch 2026-11-15-pollution-system, WP8).
 *
 * <p>Run: {@code java -Djava.awt.headless=true tools/machine-parts/MachineParts.java <item texture folder> [preview folder]}.
 * Writes {@code repair_kit.png} and {@code pollution_meter.png} into the texture folder, and with a preview folder
 * {@code <name>-preview.png} there, enlarged 16 times on a checker background.
 */
public final class MachineParts {
    static final int OUTLINE = 0xFF0E1B2E, NAVY = 0xFF1F3A5F, LID = 0xFF2B4C78, GOLD = 0xFFD4A73A, GOLD_DARK = 0xFF9C7420, CLEAR = 0x00000000;
    /** The pollution meter's lit display and its alarm lamp (flat fields, as the rest). */
    static final int SCREEN = 0xFF8FD8A0, LAMP = 0xFFD2402F;

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

    /**
     * The handheld pollution meter (batch 2026-11-15-pollution-system, WP8): a portable gas detector, a navy case with a
     * gold sampling probe on its top left, a lit display in a dark bezel, a red alarm lamp and two gold keys, and a gold
     * belt-clip band at its foot. '.' clear, 'o' outline, 'n' body, 'l' lighter case edge, 'g' gold, 'd' dark gold,
     * 's' display, 'r' alarm lamp.
     */
    static final String[] POLLUTION_METER = {
            "................",
            "....oo..........",
            "....od..........",
            "....od..........",
            "...oooooooooo...",
            "...ollllllllo...",
            "...onoooooono...",
            "...onossssono...",
            "...onossssono...",
            "...onoooooono...",
            "...onnnnnnnno...",
            "...onrngngnno...",
            "...onnnnnnnno...",
            "...oddddddddo...",
            "...oooooooooo...",
            "................",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: MachineParts <item texture folder> [preview folder]");
        write(args, "repair_kit", REPAIR_KIT);
        write(args, "pollution_meter", POLLUTION_METER);
    }

    private static void write(String[] args, String name, String[] rows) throws Exception {
        var icon = draw(rows);
        var out = new File(args[0], name + ".png");
        ImageIO.write(icon, "png", out);
        System.out.println("wrote " + out);
        if (args.length > 1) {
            var preview = new File(args[1], name + "-preview.png");
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
                case 's' -> SCREEN;
                case 'r' -> LAMP;
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
