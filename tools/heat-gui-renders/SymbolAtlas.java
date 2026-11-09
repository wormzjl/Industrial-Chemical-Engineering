import com.wormzjl.icheme.client.gui.flowsheet.Symbol;
import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Bakes the simulator's symbols to the masks the game tints (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 3): each symbol of {@code Symbol}, in {@code SimulatorIcons}' approved
 * framed shapes, white with the mask in alpha, at the cells {@code Symbol} computes, so the game and this tool cannot disagree. A column is cut from one painted 3 slices tall: its head,
 * a whole tray slice from the middle, its foot, each cell with the wall going on through its margin. The PNG is committed; a gate reads its size against {@code Symbol}.
 * Run from the worktree root, after compileJava (JDK 22+ compiles the folder's files together):
 * <pre>java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/SymbolAtlas.java src/main/resources/assets/icheme/textures/gui/flowsheet/symbols.png</pre>
 */
public final class SymbolAtlas {
    private static final int SLICES = 3;

    public static void main(String[] args) throws Exception {
        var atlas = new BufferedImage(Symbol.ATLAS_WIDTH, Symbol.atlasHeight(), BufferedImage.TYPE_INT_ARGB);
        for (var symbol : Symbol.values())
            for (var layer : symbol.layers()) bake(atlas, symbol, layer);
        var out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        ImageIO.write(atlas, "png", out.toFile());
        System.out.println(out + ": " + atlas.getWidth() + " by " + atlas.getHeight());
    }

    private static SimulatorPrototypes.Glyph glyph(Symbol symbol) {
        return symbol.name().startsWith("COLUMN") ? SimulatorPrototypes.Glyph.COLUMN : SimulatorPrototypes.Glyph.valueOf(symbol.name());
    }

    private static void bake(BufferedImage atlas, Symbol symbol, Symbol.Layer layer) {
        int[] cell = symbol.cell(layer);
        var g = atlas.createGraphics();
        g.setClip(cell[0], cell[1], cell[2], cell[3]);
        g.setComposite(AlphaComposite.SrcOver);
        g.translate(cell[0], cell[1]);
        g.scale(Symbol.PIXELS, Symbol.PIXELS);
        double h = symbol.h, shift = 0;
        boolean column = symbol.name().startsWith("COLUMN");
        if (column) {
            h = 30 + 16 * SLICES;
            shift = switch (symbol) {
                case COLUMN_TOP -> 0;
                case COLUMN_TRAY -> -(15 + 16);
                default -> -(15 + 16 * SLICES);
            };
        }
        g.translate(Symbol.MARGIN, Symbol.MARGIN + shift);
        SimulatorIcons.layer(g, glyph(symbol), symbol.w, h, SimulatorIcons.Layer.valueOf(layer.name()));
        g.dispose();
    }
}
