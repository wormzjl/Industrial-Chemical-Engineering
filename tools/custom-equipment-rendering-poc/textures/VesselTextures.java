import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Writes the vessel sprites (batch 2026-10-15-custom-equipment-rendering, supports task), from a fixed
 * seed so the output is reproducible:
 * <ul>
 *   <li>vessel_shell.png 64x64: 4 m of plate, seamless: no plate courses or weld seams (owner, 2026-11-15: "these should
 *   not be visually presented"; batch 2026-11-15-equipment-visual-design, RELIEF_SPRITES_PLAN.md).</li>
 *   <li>vessel_head.png 64x64: a pressed head, seamless, without its tangent line weld (the same rule).</li>
 *   <li>vessel_support.png 32x32: 2 m of painted support steel, darker, plain.</li>
 *   <li>equipment_*.png (process equipment task 2026-10-16), one per remaining Mesh.Material: 32x32 for 1 m of pipe and
 *   flange, 16x16 for the other 1 m parts (opening, grating, handrail, steelwork, machine, motor, actuator, feed, drain).
 *   The cladding sprites, with their band straps, are written by tools/pbr-textures/equipment_textures.py since
 *   2026-11-15; equipment_feed.png and equipment_drain.png in the assets are no longer this tool's (32x32 there).</li>
 * </ul>
 * Run: java tools/custom-equipment-rendering-poc/textures/VesselTextures.java <folder>, then copy the sprites a change
 * touched into src/main/resources/assets/icheme/textures/block (the others there match this output bit for bit).
 */
public class VesselTextures {
    public static void main(String[] args) throws Exception {
        var out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();
        ImageIO.write(shell(), "png", new File(out, "vessel_shell.png"));
        ImageIO.write(head(), "png", new File(out, "vessel_head.png"));
        ImageIO.write(support(), "png", new File(out, "vessel_support.png"));
        // Process equipment task (2026-10-16), colours from PROCESS_EQUIPMENT_PHOTO_SURVEY.md; one sprite per Mesh.Material.
        ImageIO.write(line(PIPE), "png", new File(out, "equipment_pipe.png"));
        ImageIO.write(line(FLANGE), "png", new File(out, "equipment_flange.png"));
        ImageIO.write(opening(), "png", new File(out, "equipment_opening.png"));
        ImageIO.write(grating(), "png", new File(out, "equipment_grating.png"));
        ImageIO.write(plain(16, HANDRAIL, 6, .6), "png", new File(out, "equipment_handrail.png"));
        ImageIO.write(plain(16, STEELWORK, 7, .9), "png", new File(out, "equipment_steelwork.png"));
        ImageIO.write(plain(16, MACHINE, 8, 1), "png", new File(out, "equipment_machine.png"));
        ImageIO.write(motor(), "png", new File(out, "equipment_motor.png"));
        ImageIO.write(plain(16, ACTUATOR, 10, .7), "png", new File(out, "equipment_actuator.png"));
        ImageIO.write(plain(16, FEED, 11, .8), "png", new File(out, "equipment_feed.png"));
        ImageIO.write(plain(16, DRAIN, 12, .8), "png", new File(out, "equipment_drain.png"));
        ImageIO.write(plain(16, BOLT, 13, .6), "png", new File(out, "equipment_bolt.png"));
        // Painted parts (owner requests 2026-10-16), tinted by the vessel's paint in game.
        ImageIO.write(paintedShell(), "png", new File(out, "painted_shell.png"));
        ImageIO.write(paintedHead(), "png", new File(out, "painted_head.png"));
        ImageIO.write(paintedPlain(32, 21, 2, .5), "png", new File(out, "painted_support.png"));
        ImageIO.write(paintedPlain(16, 23, 2.5, .5), "png", new File(out, "painted_steelwork.png"));
        ImageIO.write(paintedPlain(16, 24, 4, 1), "png", new File(out, "painted_machine.png"));
        if (args.length > 1) ImageIO.write(preview(), "png", new File(args[1]));
    }

    /**
     * Value of the painted sprites' neutral grey: the game's tint scales a paint colour by 255 / NEUTRAL so the sprite's
     * mean shows the paint itself ({@code Paint.SPRITE_VALUE} must match).
     */
    static final double NEUTRAL = 236;

    /**
     * A painted metal surface, light and neutral for the tint (owner requests 2026-10-16: "a bit more shiny & metallic",
     * "no need to do reflection on the surface"): a smooth fine grain, soft streaks along the sprite's v axis (the
     * plate's rolling direction, a meridian on a head) varying across u, and a sparse bright flake; {@code streak}
     * scales the streaks (0 matte), {@code flake} the flake.
     */
    static double metal(int x, int y, int size, int seed, double streak, double flake) {
        double grain = (hash(x, y, seed) - .5) * 3;
        // Streaks: fine brushed lines and broad sheen bands, box-smoothed noise across u (periodic in the sprite), and a
        // slow swell along v so they do not read as ruled lines.
        double fine = 0, broad = 0;
        for (int k = -1; k <= 1; k++) fine += hash(Math.floorMod(x + k, size), 0, seed + 40) - .5;
        for (int k = -3; k <= 3; k++) broad += hash(Math.floorMod(x + k, size), 0, seed + 41) - .5;
        double s = fine / 3 * 12 + broad / 7 * 34 + 1.5 * Math.sin(2 * Math.PI * y / size + seed) * (hash(x, 1, seed + 42) - .5) * 2;
        double sparkle = hash(x, y, seed + 50) > .975 ? 7 : 0;
        return grain + streak * s + flake * sparkle;
    }

    static int grey(double shade) {
        int v = clamp(NEUTRAL + shade);
        return v << 16 | v << 8 | v;
    }

    /** The painted shell: the painted metal, seamless. */
    static BufferedImage paintedShell() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) image.setRGB(x, y, grey(metal(x, y, 64, 25, 1, 1)));
        return image;
    }

    /** The painted head: pressed, seamless. */
    static BufferedImage paintedHead() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) image.setRGB(x, y, grey(metal(x, y, 64, 26, 1, 1)));
        return image;
    }

    /** A painted part without seams: steel under a semi-gloss coat, its grain and streaks scaled. */
    static BufferedImage paintedPlain(int size, int seed, double grain, double streak) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) image.setRGB(x, y, grey(metal(x, y, size, seed, streak, .5) + (hash(x, y, seed + 7) - .5) * grain));
        return image;
    }

    /** A few paints on the shell sprite round a cylinder lit from the left (cos shade), for a look before the game. */
    static BufferedImage preview() {
        int[][] paints = {{0xA1, 0xA1, 0xA0}, {0xCB, 0xD0, 0xCC}, {0x5B, 0x68, 0x6D}, {0xF1, 0xED, 0xE1}, {0xA7, 0x29, 0x20}, {0x22, 0x71, 0xB3}, {0x2F, 0x45, 0x38}, {0xE6, 0xD2, 0xB5}};
        var shell = paintedShell();
        int w = 128, h = 128, gap = 8;
        var image = new BufferedImage(paints.length * (w + gap), h, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < paints.length; p++)
            for (int x = 0; x < w; x++) {
                double angle = Math.PI * (x + .5) / w - Math.PI / 2, light = .55 + .45 * Math.max(0, Math.cos(angle + .5));
                for (int y = 0; y < h; y++) {
                    int texel = shell.getRGB(x % 64, y % 64) & 0xFF, rgb = 0;
                    for (int c = 0; c < 3; c++) {
                        double tint = Math.min(255, paints[p][c] * 255 / NEUTRAL);
                        rgb = rgb << 8 | clamp(texel * tint / 255 * light);
                    }
                    image.setRGB(p * (w + gap) + x, y, rgb);
                }
            }
        return image;
    }

    /** Light grey painted plate. */
    static final int[] PAINT = {170, 174, 178};
    /** Darker blue-grey painted support steel. */
    static final int[] SUPPORT = {96, 102, 109};
    /**
     * Grey/silver line paint, a darker forged flange, aluminium jacketing, galvanised grating, safety-yellow handrail,
     * painted structural steel, a grey pump volute, a blue finned motor, a red diaphragm casing, and the source (green)
     * and sink (dark red) boxes of the debug devices.
     */
    static final int[] PIPE = {161, 161, 160}, FLANGE = {135, 136, 136}, CLADDING = {196, 199, 201}, GRATING = {142, 145, 140},
            HANDRAIL = {214, 172, 36}, STEELWORK = {104, 110, 117}, MACHINE = {124, 130, 136}, MOTOR = {58, 94, 140},
            ACTUATOR = {168, 52, 44}, FEED = {58, 150, 84}, DRAIN = {120, 42, 52};
    /** Flange nuts and studs: black-oxide steel, darker than the flanges (owner request 2026-10-16: bolts on all flanges). */
    static final int[] BOLT = {66, 68, 72};

    /** Smooth line finish in the pump coating's family, 32 texels per metre: no coarse grain or baked highlight bands. */
    static BufferedImage line(int[] colour) {
        int size = 32;
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                double a = 2 * Math.PI * x / size, b = 2 * Math.PI * y / size;
                double shade = 1.4 * Math.sin(a + b) + .9 * Math.sin(a - 2 * b + 1) + .5 * Math.sin(2 * a + b + 2);
                image.setRGB(x, y, rgb(colour, shade));
            }
        return image;
    }

    /** A plain painted part: grain and mottle scaled by {@code texture}. */
    static BufferedImage plain(int size, int[] colour, int seed, double texture) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) image.setRGB(x, y, rgb(colour, plate(x, y, size, seed) * texture));
        return image;
    }

    /** The dark inside of a skirt seen through its access opening, lighter towards the rim. */
    static BufferedImage opening() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                double r = Math.hypot(x - 7.5, y - 7.5) / 7.5;
                image.setRGB(x, y, rgb(new int[]{24, 26, 28}, 10 * r * r + (hash(x, y, 9) - .5) * 4));
            }
        return image;
    }

    /** Galvanised grating: bearing bars every other column, cross bars every fourth row, dark openings between. */
    static BufferedImage grating() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                boolean bar = x % 2 == 0 || y % 4 == 0;
                image.setRGB(x, y, bar ? rgb(GRATING, (hash(x, y, 15) - .5) * 10) : rgb(new int[]{40, 42, 44}, (hash(x, y, 16) - .5) * 6));
            }
        return image;
    }

    /** A finned motor frame: a bright fin every other row. */
    static BufferedImage motor() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) image.setRGB(x, y, rgb(MOTOR, plate(x, y, 16, 17) * .6 + (y % 2 == 0 ? 12 : -10)));
        return image;
    }

    static BufferedImage shell() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) image.setRGB(x, y, rgb(PAINT, plate(x, y, 64, 1)));
        return image;
    }

    static BufferedImage head() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) image.setRGB(x, y, rgb(PAINT, plate(x, y, 64, 2)));
        return image;
    }

    static BufferedImage support() {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) image.setRGB(x, y, rgb(SUPPORT, plate(x, y, 32, 3) * .9));
        return image;
    }

    /** Tileable plate surface: per-pixel grain plus a soft mottle periodic in the sprite size. */
    static double plate(int x, int y, int size, int seed) {
        double grain = (hash(x, y, seed) - .5) * 12;
        double mottle = 3 * Math.sin(2 * Math.PI * (2 * x + y) / size + seed) + 2 * Math.sin(2 * Math.PI * (x - 3 * y) / size + 2 * seed);
        return grain + mottle;
    }

    static double hash(int x, int y, int seed) {
        long h = x * 73856093L ^ y * 19349663L ^ seed * 83492791L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        h ^= h >>> 15;
        return (h & 0xffff) / 65535.0;
    }

    static int rgb(int[] base, double shade) {
        int r = clamp(base[0] + shade), g = clamp(base[1] + shade), b = clamp(base[2] + shade);
        return r << 16 | g << 8 | b;
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
