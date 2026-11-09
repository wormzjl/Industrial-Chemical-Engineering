import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Bakes the batch still's four finishes into the given texture folder (batch 2026-11-09-batch-distiller; owner, 2026-11-09:
 * "the texture needs major improvement"). Each tiles by the metre (Mesh.Material period 1), so its tiles meet at block faces.
 * The block shader reads no normals, so the hammer marks, lap seams, rivets and brick edges are painted in, lit from above
 * as the game's directional shade lights a wall. The still's finishes are unpainted, so the sprites carry their own
 * colours. Deterministic: the same files on every run.
 *
 * <ul>
 *   <li>{@code still_copper}: hammered copper sheet, 64 px a metre: soft dimples on a staggered lattice, a riveted lap seam
 *       every half metre round and every metre along, a faint large-scale tone.</li>
 *   <li>{@code still_copper_line}: drawn copper for the lines and fittings, 32 px a metre: a faint draw grain.</li>
 *   <li>{@code still_brick}: the setting's common brick in running bond, 64 px a metre: 16 courses of 62.5 mm (a real course
 *       is 75 mm), 250 mm bricks, each its own fired tone, its top edge lit and its bed joint in shadow.</li>
 *   <li>{@code still_iron}: sand-cast iron, 32 px a metre: a dark mottle with sparse rust.</li>
 * </ul>
 */
public class StillSprites {
    /** A fixed hash of a texel, 0 to 1: grain that repeats with the sprite. */
    static double grain(int x, int y, int salt) {
        int h = x * 73856093 ^ y * 19349663 ^ salt * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65535.0;
    }

    static int rgb(double r, double g, double b) {
        int R = (int) Math.round(Math.max(0, Math.min(255, r))), G = (int) Math.round(Math.max(0, Math.min(255, g))), B = (int) Math.round(Math.max(0, Math.min(255, b)));
        return R << 16 | G << 8 | B;
    }

    static void write(File folder, String name, int[][] rgb) throws Exception {
        var image = new BufferedImage(rgb[0].length, rgb.length, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rgb.length; y++) for (int x = 0; x < rgb[0].length; x++) image.setRGB(x, y, rgb[y][x]);
        ImageIO.write(image, "png", new File(folder, name + ".png"));
    }

    /** The shortest wrapped difference on a ring of {@code n}. */
    static double wrap(double d, int n) {
        d %= n;
        if (d > n / 2.0) d -= n;
        if (d < -n / 2.0) d += n;
        return d;
    }

    /**
     * Hammered copper sheet. Dimples about 5 px (8 cm) apart on a staggered lattice, each a shallow bowl: lit on its lower
     * inside, in shade on its upper inside. A lap seam down the sheet at x 0 and 32 (every half metre round a vessel) and
     * one across at y 0 (every metre up it): the lap's shadowed edge, its lit lip, a row of rivets 62 mm apart.
     */
    static int[][] copper() {
        int n = 64;
        var f = new double[n][n];
        double pitchX = 64 / 12.0, pitchY = 64 / 14.0, radius = 2.4;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double a = 2 * Math.PI * x / n, b = 2 * Math.PI * y / n;
            double v = 1 + .03 * Math.sin(a + b) + .02 * Math.sin(2 * a - b + 1) + .015 * Math.sin(a + 3 * b + 2);
            // The nearest dimple, its centre jittered by a fixed hash.
            double best = 1e9, dy = 0;
            int row0 = (int) Math.floor(y / pitchY);
            for (int row = row0 - 1; row <= row0 + 1; row++) {
                int rr = Math.floorMod(row, 14);
                double off = (rr % 2) * pitchX / 2;
                int col0 = (int) Math.floor((x - off) / pitchX);
                for (int col = col0 - 1; col <= col0 + 1; col++) {
                    int cc = Math.floorMod(col, 12);
                    double cx = cc * pitchX + off + (grain(cc, rr, 3) - .5) * 1.4, cy = rr * pitchY + (grain(cc, rr, 4) - .5) * 1.2;
                    double ex = wrap(x + .5 - cx, n), ey = wrap(y + .5 - cy, n), d2 = ex * ex + ey * ey;
                    if (d2 < best) {
                        best = d2;
                        dy = ey;
                    }
                }
            }
            double d = Math.sqrt(best) / radius;
            if (d < 1) v *= 1 + .17 * (dy / radius) * (1 - d * d) - .03 * (1 - d * d);
            v *= 1 + (grain(x, y, 7) - .5) * .025;
            // The lap seams: down at x 0 and 32, across at y 0; rivets on the lap.
            int sx = x % 32;
            if (sx == 0) v *= .72;
            else if (sx == 1) v *= 1.1;
            else if (sx == 2) v *= 1.04;
            else if (sx == 4 && y % 4 == 1) v *= 1.28;
            else if (sx == 4 && y % 4 == 2) v *= .8;
            if (y == 0) v *= .74;
            else if (y == 1) v *= 1.09;
            else if (y == 3 && x % 4 == 2 && sx > 2) v *= 1.26;
            else if (y == 4 && x % 4 == 2 && sx > 2) v *= .82;
            f[y][x] = v;
        }
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) out[y][x] = rgb(180 * f[y][x], 108 * f[y][x], 70 * f[y][x]);
        return out;
    }

    /**
     * The shading of a welded seam {@code d} px from its centre line at {@code along} px along it: a raised bead 3 px wide
     * (lit crest, shaded toes) with a ripple every 2 px, then 2 px of heat tint either side ({@code tint} returns its share).
     */
    static double weld(int d, int along) {
        if (d == 0) return along % 2 == 0 ? 1.2 : 1.08;
        if (d == 1) return .82;
        return 1;
    }

    /** The heat tint's share at {@code d} px from a weld's centre line: 0 on the bead and past 4 px. */
    static double tint(int d) {
        return d == 2 ? .55 : d == 3 ? .35 : d == 4 ? .15 : 0;
    }

    /** The distance in px from texel x to the nearest of the vertical seams at 0 and 32, wrapped. */
    static int seamX(int x) {
        int s = x % 32;
        return Math.min(s, 32 - s);
    }

    /**
     * Carbon steel for a liquid-fired still's pot and condenser (owner, 2026-11-09: "iron-ish"): a dark grey with a
     * blue-grey mill-scale mottle and sparse rust, a welded seam down it every half metre and across it every metre, each
     * darkened by its heat.
     */
    static int[][] carbonSteel() {
        int n = 64;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double a = 2 * Math.PI * x / n, b = 2 * Math.PI * y / n;
            double scale = .5 + .5 * Math.sin(2 * a + b + .5) * Math.sin(a - 3 * b + 1.3);
            double v = 1 + .05 * Math.sin(a + 2 * b) + .035 * Math.sin(3 * a - b + 2) + (grain(x, y, 41) - .5) * .08;
            int dx = seamX(x), dy = Math.min(y, n - y);
            double w = 1, t = 0;
            if (dx <= 1) w = weld(dx, y);
            else if (dy <= 1) w = weld(dy, x);
            else t = Math.max(tint(dx), tint(dy));
            v *= w * (1 - .2 * t);
            double r = (100 - 8 * scale) * v, g = (102 - 4 * scale) * v, bl = (106 + 6 * scale) * v;
            if (grain(x, y, 42) < .035) {
                double s = .4;
                r = r * (1 - s) + 112 * s;
                g = g * (1 - s) + 70 * s;
                bl = bl * (1 - s) + 50 * s;
            }
            out[y][x] = rgb(r, g, bl);
        }
        return out;
    }

    /**
     * Brushed stainless steel for an electric pot: a light cool grey brushed round the vessel (streaks along the sprite's
     * rows), a welded seam down it every half metre and across it every metre, each with its straw-to-blue heat tint.
     */
    static int[][] stainless() {
        int n = 64;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double streak = .6 * (grain(0, y, 51) - .5) + .4 * (grain(x / 8, y, 52) - .5);
            double v = 1 + .06 * streak + (grain(x, y, 53) - .5) * .02 + .012 * Math.sin(2 * Math.PI * y / n * 3);
            int dx = seamX(x), dy = Math.min(y, n - y);
            double w = 1;
            int d = 99;
            if (dx <= 1) w = weld(dx, y);
            else if (dy <= 1) w = weld(dy, x);
            else d = Math.min(dx, dy);
            v *= w;
            double r = 172 * v, g = 177 * v, bl = 183 * v;
            // The heat tint: straw next to the bead, then blue.
            double t = .5 * tint(d);
            if (d == 2) {
                r = r * (1 - t) + 196 * t;
                g = g * (1 - t) + 170 * t;
                bl = bl * (1 - t) + 112 * t;
            } else if (d == 3 || d == 4) {
                r = r * (1 - t) + 118 * t;
                g = g * (1 - t) + 128 * t;
                bl = bl * (1 - t) + 172 * t;
            }
            out[y][x] = rgb(r, g, bl);
        }
        return out;
    }

    /** Drawn copper: an even tone, a faint grain along the line (the sprite's columns) and fine speckle. */
    static int[][] copperLine() {
        int n = 32;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double a = 2 * Math.PI * x / n;
            double v = 1 + .025 * Math.sin(3 * a) + .015 * Math.sin(7 * a + 1) + (grain(x, 0, 11) - .5) * .03 + (grain(x, y, 12) - .5) * .015;
            out[y][x] = rgb(188 * v, 114 * v, 74 * v);
        }
        return out;
    }

    /**
     * Common brick in running bond: courses of 4 px (3 brick, 1 bed joint), bricks of 16 px (15 brick, 1 head joint),
     * every other course set over by half a brick. Each brick takes its own fired tone (most red, some dark, some orange);
     * its top row lit, its bottom row and the joint under it in shade; the mortar a grey with grit.
     */
    static int[][] brick() {
        int n = 64;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int course = y / 4, inCourse = y % 4, shifted = Math.floorMod(x + (course % 2) * 8, n), brickNo = shifted / 16, inBrick = shifted % 16;
            if (inCourse == 3 || inBrick == 0) {
                double m = (inCourse == 3 ? .88 : .95) * (1 + (grain(x, y, 21) - .5) * .12);
                out[y][x] = rgb(152 * m, 147 * m, 137 * m);
                continue;
            }
            double kind = grain(brickNo, course, 22), tone = .86 + .26 * grain(brickNo, course, 23);
            double r = 148, g = 68, b = 50;
            if (kind < .18) {
                r *= .82;
                g *= .78;
                b *= .92;
            } else if (kind > .84) {
                r *= 1.1;
                g *= 1.12;
                b *= .96;
            }
            double v = tone * (1 + (grain(x, y, 24) - .5) * .09);
            if (inCourse == 0) v *= 1.07;
            if (inCourse == 2) v *= .9;
            if (inBrick == 1) v *= 1.03;
            if (inBrick == 15) v *= .95;
            out[y][x] = rgb(r * v, g * v, b * v);
        }
        return out;
    }

    /** Sand-cast iron: a dark mottle from two scales of tiled waves and the grain, with sparse rust. */
    static int[][] iron() {
        int n = 32;
        var out = new int[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double a = 2 * Math.PI * x / n, b = 2 * Math.PI * y / n;
            double v = 1 + .05 * Math.sin(a + 2 * b) + .04 * Math.sin(3 * a - b + 2) + (grain(x, y, 31) - .5) * .14;
            double r = 61 * v, g = 62 * v, bl = 65 * v;
            if (grain(x, y, 32) < .045) {
                double t = .45;
                r = r * (1 - t) + 108 * t;
                g = g * (1 - t) + 68 * t;
                bl = bl * (1 - t) + 48 * t;
            }
            out[y][x] = rgb(r, g, bl);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        var folder = new File(args[0]);
        write(folder, "still_copper", copper());
        write(folder, "still_carbon_steel", carbonSteel());
        write(folder, "still_stainless", stainless());
        write(folder, "still_copper_line", copperLine());
        write(folder, "still_brick", brick());
        write(folder, "still_iron", iron());
    }
}
