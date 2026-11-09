import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Bakes the centrifugal pumps' sprites into the given texture folder, as tools/cooling-towers/relief/TowerSprites.java
 * bakes the fan tower's: the block shader reads no normals, so fins, ribs, seams, nuts and the baseplate's rim go in the
 * sprite, shaded with the game's directional formula relative to a flat wall. Painted sprites stay light and neutral
 * (about 0.93), as a paint tint expects. Deterministic: the same files on every run.
 *
 * <p>Every sprite but the casting is stretched once over its part (Mesh.band, Mesh.disc, ProcessUnit.baseplate), so its
 * edges are the part's edges; the casting tiles by the metre.
 */
public class PumpSprites {
    static final double BODY = .93;

    /** The share of a wall's light a surface of normal n takes, for a wall facing +z. */
    static double relief(double nx, double ny, double nz) {
        return Math.min(1.15, (nx * nx * .6 + ny * ny * (3 + ny) / 4 + nz * nz * .8) / .8);
    }

    /** A fixed hash of a texel, 0 to 1: grain that repeats with the sprite. */
    static double grain(int x, int y, int salt) {
        int h = x * 73856093 ^ y * 19349663 ^ salt * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65535.0;
    }

    static void write(File folder, String name, double[][] value) throws Exception {
        var image = new BufferedImage(value[0].length, value.length, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < value.length; y++) for (int x = 0; x < value[0].length; x++) {
            int g = (int) Math.round(255 * Math.max(0, Math.min(1, value[y][x])));
            image.setRGB(x, y, g << 16 | g << 8 | g);
        }
        ImageIO.write(image, "png", new File(folder, name + ".png"));
    }

    /**
     * Two metres of a casting under a smooth industrial coat, seamless, in the painted shell's family: an even light tone
     * with a very soft large-scale variation (under 0.02), no grain and no dots; on a pump's parts (0.1 to 1 m) it reads as
     * one even coat. The relief real castings have (the volute's rib, flange edges) is in their own sprites.
     */
    static double[][] casting() {
        int n = 64;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double a = 2 * Math.PI * x / n, b = 2 * Math.PI * y / n;
            out[y][x] = .925 + .008 * Math.sin(a + b) + .005 * Math.sin(a - 2 * b + 1) + .003 * Math.sin(2 * a + b + 2);
        }
        return out;
    }

    /**
     * A volute's crown: across its rows the casing's width (stretched), along its columns half a metre of the scroll.
     * A fillet at each side, the cast rib along the middle, lit on its upper flank.
     */
    static double[][] volute() {
        int n = 32;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double v = BODY + .004 * Math.sin(2 * Math.PI * x / n);
            int edge = Math.min(y, n - 1 - y);
            if (edge == 0) v *= .78;
            else if (edge == 1) v *= relief(0, .5, .87) * .97;
            else if (edge == 2) v *= .96;
            if (y == 13) v *= relief(0, .6, .8);
            if (y == 14 || y == 15) v *= 1.04;
            if (y == 16) v *= 1.02;
            if (y == 17) v *= relief(0, -.6, .8) * .9;
            out[y][x] = v;
        }
        return out;
    }

    /**
     * A finned bearing housing, stretched over it: a plain band at each end (the end flange and the bracket's spigot), seven
     * fins between, each a lit crest, a flank and the dark gap at its root.
     */
    static double[][] fins() {
        int n = 32;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double v = BODY;
            if (y >= 2 && y < 30) {
                int k = (y - 2) % 4;
                v *= switch (k) {
                    case 0 -> .52;
                    case 1 -> relief(0, -.5, .87) * .95;
                    case 2 -> 1.05;
                    default -> 1.0;
                };
            } else if (y == 1 || y == 30) v *= .86;
            out[y][x] = v;
        }
        return out;
    }

    /** A bolted joint's rim across its thickness (stretched): two flanges, each with a chamfer, the gasket between. */
    static double[][] joint() {
        int n = 16;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double v = BODY;
            if (y == 0 || y == 15) v *= 1.04;
            if (y == 6 || y == 9) v *= .84;
            if (y == 7) v = .36;
            if (y == 8) v = .5;
            out[y][x] = v;
        }
        return out;
    }

    /**
     * A bolted face seen square on, stretched across its diameter: a chamfered rim, a machined face with faint turning
     * rings, twelve nuts on their washers on a circle at 0.82 of the radius, a spigot ring round the middle.
     */
    static double[][] disc() { return disc(true); }
    static double[][] disc(boolean bolts) {
        int n = 64;
        double c = n / 2.0;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double px = x + .5 - c, py = y + .5 - c, r = Math.hypot(px, py) / c;
            double v = BODY + .008 * Math.sin(r * 70);
            if (r > .96) v = BODY * .8;
            else if (r > .92) v = BODY * (py < 0 ? 1.04 : .9);
            if (r > .33 && r < .37) v = BODY * (py < 0 ? 1.05 : .82);
            if (r <= .33) v = BODY * .97;
            for (int k = 0; bolts && k < 12; k++) {
                double t = 2 * Math.PI * (k + .5) / 12, nx = c * .82 * Math.cos(t), ny = c * .82 * Math.sin(t);
                double dx = px - nx, dy = py - ny, rr = Math.hypot(dx, dy);
                if (rr < 3.6) v = BODY * .78;
                // A hexagon across its flats 4.6 texels: the nut, its upper facets lit, its lower ones shaded.
                double hex = 0;
                for (int s = 0; s < 3; s++) hex = Math.max(hex, Math.abs(dx * Math.cos(Math.PI * s / 3) + dy * Math.sin(Math.PI * s / 3)));
                if (hex < 2.3) v = dy < -1 ? .62 : dy > 1 ? .38 : .5;
                if (rr < .9) v = .7;
            }
            out[y][x] = v;
        }
        return out;
    }

    /**
     * A drain-rim baseplate's deck, stretched over it (built for a deck about 1.7 times as long as it is wide): the
     * mounting ledge with its anchor nuts at each side, the raised rim, the deck falling to its drain at the front end
     * (the sprite's foot), two grout holes on its centre line.
     */
    static double[][] base() {
        int n = 64;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double v = BODY * .97;
            int side = Math.min(x, n - 1 - x), end = Math.min(y, n - 1 - y);
            if (side >= 7 && end >= 2) v = BODY * (.84 + .03 * y / n);
            if (side == 4) v = BODY * .82;
            if (side == 5 && end >= 0) v = BODY * 1.06;
            if (side == 6 && end >= 1) v = BODY * .7;
            if (side >= 6 && end == 0) v = BODY * 1.06;
            if (side >= 7 && end == 1) v = BODY * .72;
            // Anchor nuts on the ledges.
            for (int row : new int[]{6, 31, 57})
                if (side >= 1 && side <= 2 && (y == row || y == row + 1)) v = y == row ? .6 : .42;
            // The drain at the front end and the grout holes.
            double dr = Math.hypot(x + .5 - 32, (y + .5 - 58) * 1.7);
            if (dr < 2.2) v = .22;
            else if (dr < 3.2) v = BODY * .62;
            for (int row : new int[]{20, 41}) {
                double g = Math.hypot(x + .5 - 32, (y + .5 - row) * 1.7);
                if (g < 3.4) v = BODY * (g < 2.2 ? .66 : y < row ? 1.05 : .8);
            }
            out[y][x] = v;
        }
        return out;
    }

    /**
     * A baseplate's side, stretched over it: the rim's lip, the channel's top flange and its shadow, the recessed web with
     * two gussets, the bottom flange; the end plates flush at each end.
     */
    static double[][] channel() {
        int n = 32;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double v = BODY;
            int end = Math.min(x, n - 1 - x);
            boolean web = y >= 5 && y <= 25;
            if (y == 0) v *= 1.05;
            if (y == 4) v *= end <= 1 ? .9 : .5;
            if (web) {
                if (end <= 1) v *= .95;
                else if (end == 2) v *= .6;
                else if (x == 10 || x == 20) v *= 1.0;
                else if (x == 11 || x == 21) v *= .6;
                else v *= .76 + .02 * (y - 5) / 20.0;
            }
            if (y == 26) v *= end <= 1 ? .9 : 1.04;
            if (y == 31) v *= .72;
            out[y][x] = v;
        }
        return out;
    }

    /** The guard's safety yellow, as equipment_guard.png has it. */
    static final int[] YELLOW = {226, 182, 42}, OPENING = {52, 44, 26};

    /**
     * One framed expanded-metal panel of a coupling guard, unpainted (its own yellow): a seam at the panel's edge, a
     * rolled frame lit on its upper and left side, a plain band (what the end plates sample), and the
     * mesh: diamonds 8 texels wide and 5 high, their strands lit on one diagonal and shaded on the other, the dark coupling
     * showing through the openings.
     */
    static int[][] guard() {
        int n = 64;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int edge = Math.min(Math.min(x, n - 1 - x), Math.min(y, n - 1 - y));
            double shade;
            int[] colour = YELLOW;
            if (edge == 0) shade = .72;
            else if (edge == 1) shade = x == 1 || y == 1 ? 1.08 : .88;
            else if (edge <= 3) shade = 1.0;
            else {
                double a = (x - 4) / 8.0 + (y - 4) / 5.0, b = (x - 4) / 8.0 - (y - 4) / 5.0;
                double fa = a - Math.floor(a), fb = b - Math.floor(b);
                if (fa < .22) shade = .98 + .3 * (.22 - fa);
                else if (fb < .22) shade = .8;
                else {
                    colour = OPENING;
                    shade = 1 + .15 * (grain(x, y, 10) - .5);
                }
            }
            shade *= 1 + .03 * (grain(x, y, 11) - .5);
            int r = (int) Math.round(Math.min(255, colour[0] * shade)), g = (int) Math.round(Math.min(255, colour[1] * shade)), b = (int) Math.round(Math.min(255, colour[2] * shade));
            out[y][x] = r << 16 | g << 8 | b;
        }
        return out;
    }

    static void writeRgb(File folder, String name, int[][] rgb) throws Exception {
        var image = new BufferedImage(rgb[0].length, rgb.length, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rgb.length; y++) for (int x = 0; x < rgb[0].length; x++) image.setRGB(x, y, rgb[y][x]);
        ImageIO.write(image, "png", new File(folder, name + ".png"));
    }

    /**
     * Machined steel for every turning shaft and hub: faint grain without baked lighting bands. The rotating geometry
     * supplies the highlights, so the same finish works on the hexagonal sides and their cut ends.
     */
    static int[][] shaft() {
        int n = 16;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double v = .72 + .012 * (grain(x, 0, 12) - .5) + .008 * (grain(x, y, 13) - .5);
            int r = (int) Math.round(255 * Math.min(1, v * .97)), g = (int) Math.round(255 * Math.min(1, v * .99)), b = (int) Math.round(255 * Math.min(1, v * 1.03));
            out[y][x] = r << 16 | g << 8 | b;
        }
        return out;
    }

    /**
     * A motor's painted sprites' neutral grey (about 0.9: light enough that a dark paint still shows the relief), scaled by
     * a shade; the paint tint gives the colour (owner request 2026-11-04: motors are paintable). The nameplate is lighter.
     */
    static final int[] GREY = {230, 230, 230}, PLATE = {250, 250, 250};

    static int tone(int[] base, double shade) {
        int r = (int) Math.round(Math.max(0, Math.min(255, base[0] * shade))), g = (int) Math.round(Math.max(0, Math.min(255, base[1] * shade))),
                b = (int) Math.round(Math.max(0, Math.min(255, base[2] * shade)));
        return r << 16 | g << 8 | b;
    }

    /**
     * A TEFC motor's finned frame, neutral for its paint, stretched once round it (columns) and along it (rows, the fan end at the top): 32 axial
     * fins, a lit crest and a dark gap each; a plain spigot band at each end; the nameplate on its pad where the frame's
     * angle 0 lies (the sprite's left and right edges, which meet).
     */
    static int[][] motorFrame() {
        int n = 64;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int end = Math.min(y, n - 1 - y), seam = Math.min(x, n - x);
            double shade = 1;
            int[] colour = GREY;
            if (end == 0) shade *= .72;
            else if (end <= 2) shade *= end == 1 ? 1.1 : 1.0;
            else if (seam <= 4 && y >= 22 && y <= 41) {
                // The pad and its plate.
                if (seam <= 3 && y >= 24 && y <= 39) {
                    colour = PLATE;
                    shade = y == 24 || seam == 3 ? .8 : (y - 27) % 3 == 0 && y < 38 && seam <= 2 ? .55 : 1;
                }
            } else shade *= x % 2 == 0 ? 1.1 : .66;
            out[y][x] = tone(colour, shade);
        }
        return out;
    }

    /**
     * A motor's end shield seen square on, stretched across it: the spigot rim, eight bolts on a circle at 0.86, six cast
     * ribs lit on one flank, the bearing cap's boss with its four screws and grease nipple, the shaft's opening.
     */
    static int[][] motorShield() {
        int n = 64;
        double c = n / 2.0;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double px = x + .5 - c, py = y + .5 - c, r = Math.hypot(px, py) / c, t = Math.atan2(py, px);
            double shade = 1;
            if (r > .95) shade = .7;
            else if (r > .9) shade = py < 0 ? 1.12 : .82;
            else if (r > .36) {
                for (int k = 0; k < 6; k++) {
                    double rib = t - (2 * Math.PI * k / 6 + Math.PI / 6), across = r * c * Math.sin(rib), along = Math.cos(rib);
                    if (along > 0 && r < .84 && Math.abs(across) < 1.6) shade = across < 0 ? 1.18 : .72;
                }
            } else if (r > .33) shade = .7;
            else if (r > .12) shade = 1.06;
            else shade = .3;
            for (int k = 0; k < 8; k++) {
                double bt = 2 * Math.PI * (k + .5) / 8, dx = px - c * .86 * Math.cos(bt), dy = py - c * .86 * Math.sin(bt);
                double hex = 0;
                for (int s = 0; s < 3; s++) hex = Math.max(hex, Math.abs(dx * Math.cos(Math.PI * s / 3) + dy * Math.sin(Math.PI * s / 3)));
                if (hex < 2) shade = dy < -.7 ? .85 : dy > .7 ? .45 : .62;
            }
            for (int k = 0; k < 4; k++) {
                double bt = Math.PI / 2 * k + Math.PI / 4;
                if (Math.hypot(px - c * .24 * Math.cos(bt), py - c * .24 * Math.sin(bt)) < 1.3) shade = .5;
            }
            if (Math.hypot(px, py + c * .29) < 1.3) shade = 1.4;
            out[y][x] = tone(GREY, shade);
        }
        return out;
    }

    /** A metre of a large motor's fabricated frame, neutral for its paint, tiled by the metre: a bolted seam round the panel, a stiffener down its middle. */
    static int[][] motorPanel() {
        int n = 32;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double shade = .97;
            int edge = Math.min(Math.min(x, n - 1 - x), Math.min(y, n - 1 - y));
            if (edge == 0) shade = .62;
            else if (edge == 1) shade = 1.1;
            else if (edge == 2 && (x + y) % 6 == 0) shade = .5;
            else if (x == 15) shade *= 1.15;
            else if (x == 16) shade *= .68;
            out[y][x] = tone(GREY, shade);
        }
        return out;
    }

    /** A metre of a motor's plain cast or pressed parts under their coat, seamless: a soft cloud, no grain. */
    static int[][] motorPlain() {
        int n = 32;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double a = 2 * Math.PI * x / n, b = 2 * Math.PI * y / n;
            out[y][x] = tone(GREY, 1 + .025 * Math.sin(a + 2 * b) + .02 * Math.sin(3 * a - b + 1));
        }
        return out;
    }

    /** Fixed industrial IN/OUT tags, legible independently of the equipment's paint. */
    static int[][] shaftPort(boolean input) {
        int width = 80, height = 32, border = 0x26343C;
        int colour = input ? 0x2271B3 : 0xF7B500, ink = input ? 0xF4F5F2 : 0x17232B;
        var out = new int[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            out[y][x] = x < 2 || y < 2 || x >= width - 2 || y >= height - 2 ? border : colour;
        String text = input ? "IN" : "OUT";
        int scale = 3, left = (width - (6 * text.length() - 1) * scale) / 2, top = (height - 7 * scale) / 2;
        for (int letter = 0; letter < text.length(); letter++) {
            var rows = switch (text.charAt(letter)) {
                case 'I' -> new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "11111"};
                case 'N' -> new String[]{"10001", "11001", "11001", "10101", "10011", "10011", "10001"};
                case 'O' -> new String[]{"01110", "10001", "10001", "10001", "10001", "10001", "01110"};
                case 'U' -> new String[]{"10001", "10001", "10001", "10001", "10001", "10001", "01110"};
                case 'T' -> new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"};
                default -> throw new IllegalArgumentException("Unknown shaft label");
            };
            for (int y = 0; y < 7; y++) for (int x = 0; x < 5; x++) if (rows[y].charAt(x) == '1')
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++) out[top + y * scale + dy][left + (letter * 6 + x) * scale + dx] = ink;
        }
        return out;
    }

    /** One flush hex nut: the polygon supplies its outline, so no alpha/cutout pass is needed. */
    static double[][] boltFace() {
        int n = 32;
        var out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double px = (x + .5 - n / 2.0) / (n / 2.0), py = (y + .5 - n / 2.0) / (n / 2.0);
            double radial = Math.hypot(px, py);
            out[y][x] = radial < .18 ? .64 : radial < .27 ? .30 : py < -.2 ? .60 : py > .2 ? .37 : .48;
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        writeRgb(new File(args[0]), "motor_frame", motorFrame());
        writeRgb(new File(args[0]), "motor_shield", motorShield());
        writeRgb(new File(args[0]), "motor_panel", motorPanel());
        writeRgb(new File(args[0]), "motor_plain", motorPlain());
        writeRgb(new File(args[0]), "coupling_guard", guard());
        writeRgb(new File(args[0]), "shaft_steel", shaft());
        writeRgb(new File(args[0]), "shaft_input", shaftPort(true));
        writeRgb(new File(args[0]), "shaft_output", shaftPort(false));
        var folder = new File(args[0]);
        write(folder, "pump_casting", casting());
        write(folder, "pump_volute", volute());
        write(folder, "pump_fins", fins());
        write(folder, "pump_joint", joint());
        write(folder, "pump_disc", disc());
        write(folder, "pump_cover", disc(false));
        write(folder, "equipment_bolt_face", boltFace());
        write(folder, "pump_base", base());
        write(folder, "pump_channel", channel());
    }
}
