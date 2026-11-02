import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** One 4 m louver panel three ways: real slats, one flat quad with a baked sprite, one flat quad with a normal-mapped shader. */
public class Relief {
    static final double PANEL = 4, PITCH = .5, DEPTH = .4, SLAT_ALBEDO = .72, PLATE = .06, PILASTER = .1, OUT = .08;
    static final int SPRITE = 128, CELL = 300, LABEL = 22;
    record Hit(double albedo, double[] normal) {}

    static double shade(double[] n) {
        return Math.min(1, n[0] * n[0] * .6 + n[1] * n[1] * (3 + n[1]) / 4 + n[2] * n[2] * .8);
    }
    /** A panel facing +z turned to face +x. */
    static double[] east(double[] n) { return new double[]{n[2], n[1], -n[0]}; }

    /** The nearest surface along a ray, or null. */
    static Hit trace(double[] o, double[] d) {
        double best = 1e9;
        Hit hit = null;
        // Pilasters: boxes round x = 0, 2, 4.
        for (double c : new double[]{0, 2, 4}) {
            double[] lo = {c - PILASTER, 0, -DEPTH}, hi = {c + PILASTER, PANEL, OUT};
            double near = -1e9, far = 1e9;
            int axis = -1;
            double sign = 0;
            boolean miss = false;
            for (int a = 0; a < 3; a++) {
                if (Math.abs(d[a]) < 1e-12) {
                    if (o[a] < lo[a] || o[a] > hi[a]) miss = true;
                    continue;
                }
                double t0 = (lo[a] - o[a]) / d[a], t1 = (hi[a] - o[a]) / d[a];
                double s = -Math.signum(d[a]);
                if (t0 > t1) { double t = t0; t0 = t1; t1 = t; }
                if (t0 > near) { near = t0; axis = a; sign = s; }
                far = Math.min(far, t1);
            }
            if (!miss && near <= far && near < best && axis >= 0) {
                best = near;
                var n = new double[3];
                n[axis] = sign;
                hit = new Hit(SLAT_ALBEDO, n);
            }
        }
        // Slats: thin plates from a high outer edge to a low inner edge, as a louver sheds water inwards.
        double run = .42, back = .38, len = Math.hypot(run, back);
        double[] n = {0, -back / len, run / len};
        for (int k = -1; k < PANEL / PITCH; k++) {
            double y0 = k * PITCH + run;
            double denom = n[1] * d[1] + n[2] * d[2];
            if (Math.abs(denom) < 1e-12) continue;
            double t = (n[1] * (y0 - o[1]) + n[2] * (0 - o[2])) / denom;
            if (t <= 0 || t >= best) continue;
            double x = o[0] + t * d[0], y = o[1] + t * d[1], z = o[2] + t * d[2];
            if (z > 0 || z < -back || x < 0 || x > PANEL || y < 0 || y > PANEL) continue;
            best = t;
            hit = new Hit(SLAT_ALBEDO, denom < 0 ? n : new double[]{0, -n[1], -n[2]});
        }
        // The dark plate behind them.
        if (d[2] < 0) {
            double t = (-DEPTH - o[2]) / d[2];
            double x = o[0] + t * d[0], y = o[1] + t * d[1];
            if (t < best && x >= 0 && x <= PANEL && y >= 0 && y <= PANEL) { best = t; hit = new Hit(PLATE, new double[]{0, 0, 1}); }
        }
        return hit;
    }

    public static void main(String[] args) throws Exception {
        // Bake head-on, at the game's own 32 px per metre.
        var baked = new double[SPRITE][SPRITE];
        var albedo = new double[SPRITE][SPRITE];
        var normal = new double[SPRITE][SPRITE][];
        int samples = 4;
        double face = shade(new double[]{0, 0, 1});
        for (int v = 0; v < SPRITE; v++) for (int u = 0; u < SPRITE; u++) {
            double sumBake = 0, sumAlbedo = 0;
            var sum = new double[3];
            for (int i = 0; i < samples; i++) for (int j = 0; j < samples; j++) {
                double x = (u + (i + .5) / samples) / SPRITE * PANEL, y = PANEL - (v + (j + .5) / samples) / SPRITE * PANEL;
                var hit = trace(new double[]{x, y, 5}, new double[]{0, 0, -1});
                if (hit == null) continue;
                sumBake += hit.albedo() * shade(hit.normal()) / face;
                sumAlbedo += hit.albedo();
                for (int a = 0; a < 3; a++) sum[a] += hit.normal()[a];
            }
            double count = samples * samples, len = Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
            baked[v][u] = sumBake / count;
            albedo[v][u] = sumAlbedo / count;
            normal[v][u] = new double[]{sum[0] / len, sum[1] / len, sum[2] / len};
        }
        var sprite = new BufferedImage(SPRITE, SPRITE, BufferedImage.TYPE_INT_RGB);
        var map = new BufferedImage(SPRITE, SPRITE, BufferedImage.TYPE_INT_RGB);
        for (int v = 0; v < SPRITE; v++) for (int u = 0; u < SPRITE; u++) {
            int g = (int) Math.round(255 * Math.min(1, baked[v][u]));
            sprite.setRGB(u, v, g << 16 | g << 8 | g);
            var n = normal[v][u];
            map.setRGB(u, v, new Color((float) (n[0] * .5 + .5), (float) (n[1] * .5 + .5), (float) (n[2] * .5 + .5)).getRGB());
        }
        ImageIO.write(sprite, "png", new File(args[0] + "-sprite.png"));
        ImageIO.write(map, "png", new File(args[0] + "-normal.png"));

        String[] forms = {"Real slats, 64 quads", "One quad, baked sprite", "One quad, normal-map shader"};
        // Yaw, pitch of the eye, east: the panel turned to the side that shades 0.6.
        double[][] views = {{0, 0, 0}, {35, -22, 0}, {68, -14, 0}, {35, -22, 1}};
        String[] names = {"Head on", "Player on the ground, 35 deg", "Grazing, 68 deg", "35 deg, an east face"};
        int width = forms.length * CELL, height = views.length * (CELL + LABEL) + LABEL;
        var sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x9CC4F2));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.setColor(Color.BLACK);
        for (int f = 0; f < forms.length; f++) g.drawString(forms[f], f * CELL + 8, 16);
        for (int r = 0; r < views.length; r++) {
            g.setColor(Color.BLACK);
            g.drawString(names[r], 8, LABEL + r * (CELL + LABEL) + 16);
            for (int f = 0; f < forms.length; f++) panel(sheet, f * CELL, LABEL * 2 + r * (CELL + LABEL), views[r], f, baked, albedo, normal);
        }
        ImageIO.write(sheet, "png", new File(args[0] + "-sheet.png"));
    }

    static void panel(BufferedImage out, int ox, int oy, double[] view, int form, double[][] baked, double[][] albedo, double[][][] normal) {
        double yaw = Math.toRadians(view[0]), pitch = Math.toRadians(view[1]);
        boolean east = view[2] > 0;
        // From the eye towards the panel; a negative pitch is an eye below, looking up.
        double[] d = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)};
        double[] right = {Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] up = {right[1] * d[2] - right[2] * d[1], right[2] * d[0] - right[0] * d[2], right[0] * d[1] - right[1] * d[0]};
        if (up[1] < 0) up = new double[]{-up[0], -up[1], -up[2]};
        double scale = 5.2 / (CELL - LABEL);
        int ss = 3;
        for (int py = 0; py < CELL - LABEL; py++) for (int px = 0; px < CELL; px++) {
            double value = 0;
            int hits = 0;
            for (int i = 0; i < ss; i++) for (int j = 0; j < ss; j++) {
                double sx = (px + (i + .5) / ss - CELL / 2.0) * scale, sy = ((CELL - LABEL) / 2.0 - py - (j + .5) / ss) * scale;
                double[] o = new double[3];
                for (int a = 0; a < 3; a++) o[a] = (a == 0 || a == 1 ? 2 : 0) + right[a] * sx + up[a] * sy - d[a] * 20;
                double c = -1;
                if (form == 0) {
                    var hit = trace(o, d);
                    if (hit != null) c = hit.albedo() * shade(east ? east(hit.normal()) : hit.normal());
                } else {
                    double t = -o[2] / d[2], x = o[0] + t * d[0], y = o[1] + t * d[1];
                    if (x >= 0 && x < PANEL && y >= 0 && y < PANEL) {
                        int u = (int) (x / PANEL * SPRITE), v = (int) ((PANEL - y) / PANEL * SPRITE);
                        double[] flat = {0, 0, 1};
                        if (form == 1) c = baked[v][u] * shade(east ? east(flat) : flat);
                        else c = albedo[v][u] * shade(east ? east(normal[v][u]) : normal[v][u]);
                    }
                }
                if (c >= 0) { value += c; hits++; }
            }
            if (hits > 0) {
                double share = hits / (double) (ss * ss), mean = value / hits;
                int bg = 0x9CC4F2;
                int rr = (int) (255 * mean * share + (bg >> 16 & 255) * (1 - share));
                int gg = (int) (255 * mean * share + (bg >> 8 & 255) * (1 - share));
                int bb = (int) (255 * mean * share + (bg & 255) * (1 - share));
                out.setRGB(ox + px, oy + py, rr << 16 | gg << 8 | bb);
            }
        }
    }
}
