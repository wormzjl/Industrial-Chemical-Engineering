import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Bakes the fan tower's sprites into the given texture folder: the game's block shader reads no normals, so the relief
 * goes in the sprite. Louvers, ribs and seams are shaded with the game's own directional formula, relative to a flat wall.
 * The painted ones stay light and neutral, as a paint tint expects.
 */
public class TowerSprites {
    static final int SIZE = 128, SMALL = 64;
    static final double SLAT = .96, GAP = .05, BODY = .93;

    /** The share of a wall's light a surface of normal n takes, for a wall facing +z. */
    static double relief(double nx, double ny, double nz) {
        return Math.min(1.15, (nx * nx * .6 + ny * ny * (3 + ny) / 4 + nz * nz * .8) / .8);
    }

    static void write(File folder, String name, double[][] value) throws Exception {
        var image = new BufferedImage(value[0].length, value.length, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < value.length; y++) for (int x = 0; x < value[0].length; x++) {
            int g = (int) Math.round(255 * Math.max(0, Math.min(1, value[y][x])));
            image.setRGB(x, y, g << 16 | g << 8 | g);
        }
        ImageIO.write(image, "png", new File(folder, name + ".png"));
    }

    /** A 4 m by 2 m panel of six slats, a pilaster at each end and in the middle. */
    static double[][] louver() {
        var out = new double[SIZE][SIZE];
        double back = Math.hypot(.28, .25), underside = relief(0, -.25 / back, .28 / back);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            double along = (x + .5) / SIZE * 4, phase = ((y + .5) / SIZE * 6) % 1;
            double pilaster = Math.min(Math.min(along, Math.abs(along - 2)), 4 - along);
            double v;
            if (pilaster < .1) v = pilaster < .02 || pilaster > .08 ? BODY * .88 : BODY;
            else if (phase > .9) v = GAP;
            else v = SLAT * underside * (.94 + .06 * phase);
            out[y][x] = v;
        }
        return out;
    }

    /** A 4 m casing panel: a rib every metre, a bolted seam round the edge. */
    static double[][] panel() {
        var out = new double[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            double v = BODY + .01 * ((x * 7 + y * 13) % 5) / 4;
            int rib = Math.floorMod(x, 32);
            if (rib == 15) v *= relief(-.5, 0, .87);
            if (rib == 16) v *= 1.02;
            if (rib == 17) v *= relief(.5, 0, .87) * .97;
            int edge = Math.min(Math.min(x, SIZE - 1 - x), Math.min(y, SIZE - 1 - y));
            if (edge == 0) v *= .78;
            if (edge == 1) v *= 1.03;
            if (edge == 3 && (x + y) % 8 < 2) v *= .7;
            out[y][x] = v;
        }
        return out;
    }

    /** One of a fan stack's sixteen panels: a lap joint down one edge, two stiffening bands. */
    static double[][] stack() {
        var out = new double[SMALL][SMALL];
        for (int y = 0; y < SMALL; y++) for (int x = 0; x < SMALL; x++) {
            double v = BODY;
            if (x < 2) v *= .8;
            if (x == 2) v *= 1.04;
            if (y % 24 == 11) v *= .86;
            if (y % 24 == 12) v *= 1.03;
            out[y][x] = v;
        }
        return out;
    }

    /** A deck's plain steel: a whole panel stretches it, so it holds no lines. */
    static double[][] deck() {
        var out = new double[SMALL][SMALL];
        for (int y = 0; y < SMALL; y++) for (int x = 0; x < SMALL; x++) out[y][x] = .40 + .02 * ((x * 5 + y * 3) % 7) / 6;
        return out;
    }

    /** What a fan shows of the cell below it: drift eliminators in rows, the spray headers over them, dark at the shaft's wall. */
    static double[][] drift() {
        var out = new double[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            double v = y % 6 < 2 ? .22 : y % 6 < 4 ? .38 : .30;
            for (int lateral : new int[]{32, 64, 96}) {
                int off = Math.abs(y - lateral);
                if (off <= 3) v = .62 + .05 * (3 - off) - (y > lateral ? .12 : 0);
                if (off == 4) v = .12;
            }
            int main = Math.abs(x - 64);
            if (main <= 6) v = .72 - .022 * main;
            if (main == 7) v = .10;
            if (Math.abs(y % 32 - 0) >= 8 && (x + 6) % 12 < 2 && main > 8 && y % 6 == 4) v = .55;
            double r = Math.hypot(x - 63.5, y - 63.5) / 64;
            out[y][x] = v * (1 - .55 * Math.max(0, (r - .6) / .4));
        }
        return out;
    }

    /** 16 m of a natural shell's concrete, seamless: a slip-formed shell shows lifts 2 m high and tie holes, no vertical joints. */
    static double[][] concrete() {
        var out = new double[256][256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            double a = 2 * Math.PI * x / 256, b = 2 * Math.PI * y / 256;
            double v = .60 + .018 * Math.sin(a + 2 * b) + .014 * Math.sin(3 * a - b + 1) + .01 * Math.sin(5 * a + 3 * b + 2);
            v += .012 * (((x * 73 ^ y * 151) * 2654435 >>> 7) % 9 - 4) / 4;
            if (y % 32 == 0) v *= .84;
            if (y % 32 == 1) v *= 1.04;
            if (y % 32 == 16 && x % 32 < 2) v *= .74;
            out[y][x] = v;
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        var folder = new File(args[0]);
        write(folder, "tower_louver", louver());
        write(folder, "tower_panel", panel());
        write(folder, "tower_stack", stack());
        write(folder, "tower_deck", deck());
        write(folder, "tower_drift", drift());
        write(folder, "tower_concrete", concrete());
    }
}
