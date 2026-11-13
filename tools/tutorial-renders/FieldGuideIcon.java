import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The Field Guide item's 16 by 16 texture, a PLACEHOLDER (batch 2026-11-12-tutorial-system, P1): an open book in the
 * mod's panel colours (Palette: LINE cover, TEXT pages, ACCENT lines of print, BG spine). Run from the worktree root:
 *
 * <pre>java tools/tutorial-renders/FieldGuideIcon.java src/main/resources/assets/icheme/textures/item/field_guide.png</pre>
 */
public final class FieldGuideIcon {
    private static final int CLEAR = 0, COVER = 0xFF536C7A, EDGE = 0xFF28363F, PAGE = 0xFFEAF1F5, SHADE = 0xFFA9BCC7, PRINT = 0xFF79D6CE,
            SPINE = 0xFF202A31;

    public static void main(String[] args) throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // Rows from the top: c cover, e cover's dark edge, p page, s page's shade by the spine, t print, b spine, . clear.
        String[] rows = {
                "................",
                "................",
                "..ppppp..ppppp..",
                ".cptttpsbsptttpc",
                ".cpppppsbspppppc",
                ".cptttpsbsptttpc",
                ".cpppppsbspppppc",
                ".cpttppsbspttppc",
                ".cpppppsbspppppc",
                ".cptttpsbsptttpc",
                ".cpppppsbspppppc",
                ".cppppppbppppppc",
                ".ccccccebecccccc",
                "..eeeeeebeeeeee.",
                "................",
                "................",
        };
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, switch (rows[y].charAt(x)) {
                    case 'c' -> COVER;
                    case 'e' -> EDGE;
                    case 'p' -> PAGE;
                    case 's' -> SHADE;
                    case 't' -> PRINT;
                    case 'b' -> SPINE;
                    default -> CLEAR;
                });
        var out = new File(args[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(image, "png", out);
    }
}
