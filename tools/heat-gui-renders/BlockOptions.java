import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.flowsheet.SheetPainter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** One-off option sheet for the owner: T-101 as a block that shows what it holds, three ways. Numbers and spacing are the sample's. */
public class BlockOptions {
    static final int PANEL_W = 420, PANEL_H = 430, TITLE = 44;
    static final int WELL = 0xFF10181D, HOT = 0xFFE8A23C, COLD = Palette.ACCENT, LINE = Palette.MUTED, PLATE = 0xFF10181D, FRAME_FILL = 0xFF1A252C, FRAME = 0xFF7F97A5;
    static final int[] LEFT = {17, 41, 65, 90, 114, 139, 163, 188, 212, 237};
    static final int[] RIGHT = {17, 72, 127, 182, 237};
    static final int H = 254, W = 44;
    static final Look COOLER = new Look(0xFF17463F, Palette.TEAL, Palette.TEXT);

    static FlowsheetPainter p, small;
    static Canvas c;

    public static void main(String[] args) throws Exception {
        var jar = Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        boolean one = args.length > 1;
        int n = one ? 1 : 3, zoom = one ? 3 : 2;
        var image = new BufferedImage(n * PANEL_W * zoom, (PANEL_H + TITLE) * zoom, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.scale(zoom, zoom);
        g.setColor(new Color(0x0B1216));
        g.fillRect(0, 0, n * PANEL_W, PANEL_H + TITLE);
        c = new HeatDiagramRender.Java2dCanvas(g);
        p = new FlowsheetPainter(c, 0, 0, 1, 1);
        small = new FlowsheetPainter(c, 0, 0, .5, 1);
        if (one) {
            ground(0, "C  Rearranged", "Every part the constructor builds. No line crosses another.");
            rearranged(4, TITLE);
            ImageIO.write(image, "png", Path.of(args[0]).toFile());
            System.out.println("written " + args[0]);
            return;
        }
        panel(0, "A  Dashed battery limit", "Coolers in a bay; the condenser as a cooler. The tower stays dominant.", false, false);
        panel(1, "B  Solid frame, filled", "The same parts on a plate: one object, its boundary unmistakable.", true, false);
        panel(2, "C  Full internals, solid frame", "Adds PA pumps, the true draw and return trays, the reflux drum.", true, true);
        ImageIO.write(image, "png", Path.of(args[0]).toFile());
        System.out.println("written " + args[0]);
    }

    static void ground(int index, String title, String note) {
        int px = index * PANEL_W + 4, py = TITLE, ow = PANEL_W - 8, oh = PANEL_H - 4;
        c.fill(px, py, px + ow, py + oh, WELL);
        for (int y = py + 8; y < py + oh; y += 16) for (int x = px + 8; x < px + ow; x += 16) c.fill(x, y, x + 1, y + 1, 0x24FFFFFF);
        c.text(title, px + 2, 8, Palette.TEXT);
        c.text(note, px + 2, 24, Palette.MUTED);
    }

    static void panel(int index, String title, String note, boolean solid, boolean full) {
        int px = index * PANEL_W + 4, py = TITLE;
        ground(index, title, note);
        if (full) full(px, py);
        else simple(px, py, solid);
    }

    // ---- Options A and B ----

    static void simple(int px, int py, boolean solid) {
        int band = 46, tx = px + 178, ty = py + 62, bx = tx - band, br = tx + W + 14, by = ty - 12, bb = ty + H + 12;
        frame(bx, by, br, bb, solid);
        SheetPainter.column(c, tx, ty, 1, 14, Look.TEAL);
        int cx = bx + 20;
        int[][] pairs = {{0, 1}, {2, 3}, {4, 5}, {6, 7}};
        for (var pair : pairs) cooler(bx, cx, tx, ty + LEFT[pair[0]], ty + LEFT[pair[1]]);
        arrow(new int[]{bx, ty + LEFT[8], tx, ty + LEFT[8]}, LINE);
        arrow(new int[]{bx, ty + LEFT[9], tx, ty + LEFT[9]}, LINE);
        for (int y : RIGHT) arrow(new int[]{tx + W, ty + y, br, ty + y}, LINE);
        arrow(new int[]{tx + W / 2, ty, tx + W / 2, by}, LINE);
        arrow(new int[]{tx + W / 2, ty + H, tx + W / 2, bb}, LINE);
        marks(tx, ty);
        for (int y : LEFT) port(bx, ty + y);
        for (int y : RIGHT) port(br, ty + y);
        port(tx + W / 2, by);
        port(tx + W / 2, bb);
        outside(px, bx, br, by, bb, ty, tx + W / 2, tx + W / 2, ty + RIGHT[0], ty + RIGHT[1]);
        c.fill(bx + 6, by - 6, bx + 42, by + 6, PLATE);
        c.outline(bx + 6, by - 6, 36, 12, FRAME);
        c.text("T-101", bx + 11, by - 3, Palette.TEXT);
    }

    /** A pair's cold side through its cooler, from port to port; its hot side to the tower. */
    static void cooler(int bx, int cx, int tx, int yIn, int yOut) {
        int cy = (yIn + yOut) / 2;
        arrow(new int[]{bx, yIn, cx, yIn, cx, cy - 8}, COLD);
        arrow(new int[]{cx, cy + 8, cx, yOut, bx, yOut}, COLD);
        arrow(new int[]{cx + 7, cy - 4, tx, cy - 4}, HOT);
        arrow(new int[]{tx, cy + 4, cx + 7, cy + 4}, HOT);
        coil(cx, cy);
    }

    static void coil(int cx, int cy) {
        p.circle(cx, cy, 8, COOLER);
        p.line(cx, cy - 8, cx - 4, cy - 3, Palette.TEXT);
        p.line(cx - 4, cy - 3, cx + 4, cy + 3, Palette.TEXT);
        p.line(cx + 4, cy + 3, cx, cy + 8, Palette.TEXT);
    }

    static void marks(int tx, int ty) {
        int cx = tx + W / 2;
        String[] names = {"Cond.", "PA 1", "PA 2", "PA 3"};
        for (int i = 0; i < 4; i++) p.tag(names[i], cx, ty + (LEFT[2 * i] + LEFT[2 * i + 1]) / 2, .5f, Palette.TEXT, PLATE);
        p.tag("Feed", cx, ty + LEFT[8], .5f, Palette.TEXT, PLATE);
        p.tag("Steam", cx, ty + LEFT[9], .5f, Palette.TEXT, PLATE);
    }

    // ---- Option C ----

    static void full(int px, int py) {
        int band = 72, top = 46, tx = px + 186, ty = py + 72, bx = tx - band, br = tx + W + 66, by = ty - top, bb = ty + H + 12;
        frame(bx, by, br, bb, true);
        SheetPainter.column(c, tx, ty, 1, 14, Look.TEAL);
        // Condenser, drum, reflux.
        int cdx = tx - 26, cdy = ty - 26, dx0 = tx + W + 14, dx1 = dx0 + 34, dy = cdy;
        arrow(new int[]{tx + W / 2, ty, tx + W / 2, cdy - 12, cdx, cdy - 12, cdx, cdy - 8}, HOT);
        arrow(new int[]{cdx + 8, cdy, tx + 10, cdy, tx + 10, cdy + 12, dx0 + 6, cdy + 12, dx0 + 6, dy + 7}, HOT);
        arrow(new int[]{bx, cdy - 10, cdx - 12, cdy - 10, cdx - 12, cdy - 3, cdx - 7, cdy - 3}, COLD);
        arrow(new int[]{cdx - 7, cdy + 3, cdx - 12, cdy + 3, cdx - 12, cdy + 10, bx, cdy + 10}, COLD);
        coil(cdx, cdy);
        p.rounded(dx0, dy - 7, dx1, dy + 7, 6, new Look(0xFF17463F, Palette.TEAL, Palette.TEXT));
        arrow(new int[]{dx0 + 16, dy + 7, dx0 + 16, ty + 10, tx + W, ty + 10}, HOT);
        p.tag("Cond.", cdx, cdy + 16, .5f, Palette.TEXT, 0);
        p.tag("Drum", (dx0 + dx1) / 2, dy - 15, .5f, Palette.TEXT, 0);
        int[][] pa = {{10, 12}, {18, 20}, {27, 29}};
        String[] names = {"PA 1", "PA 2", "PA 3"};
        int cx = bx + 20;
        int[] leftY = new int[6];
        for (int i = 0; i < 3; i++) {
            int back = tray(ty, pa[i][0]), draw = tray(ty, pa[i][1]), cy = (back + draw) / 2;
            leftY[2 * i] = cy - 16;
            leftY[2 * i + 1] = cy + 16;
            arrow(new int[]{bx, cy - 16, cx, cy - 16, cx, cy - 8}, COLD);
            arrow(new int[]{cx, cy + 8, cx, cy + 16, bx, cy + 16}, COLD);
            int px0 = cx + 26;
            arrow(new int[]{tx, draw, px0, draw, px0, cy + 2, cx + 8, cy + 2}, HOT);
            arrow(new int[]{cx + 8, cy - 3, tx - 14, cy - 3, tx - 14, back, tx, back}, HOT);
            p.circle(px0, draw, 4, COOLER);
            coil(cx, cy);
            p.tag(names[i], cx + 36, cy + 12, .5f, Palette.MUTED, 0);
        }
        int feed = tray(ty, 38), steam = tray(ty, 42) - 2;
        arrow(new int[]{bx, feed, tx, feed}, LINE);
        arrow(new int[]{bx, steam, tx, steam}, LINE);
        int[] draws = {tray(ty, 11), tray(ty, 19), tray(ty, 29)};
        for (int y : draws) arrow(new int[]{tx + W, y, br, y}, LINE);
        arrow(new int[]{dx1, dy, br, dy}, LINE);
        arrow(new int[]{dx0 + 28, dy + 7, dx0 + 28, dy + 18, br, dy + 18}, LINE);
        arrow(new int[]{dx0 + 17, dy - 7, dx0 + 17, by}, LINE);
        arrow(new int[]{tx + W / 2, ty + H, tx + W / 2, bb}, LINE);
        p.tag("Feed", tx + W / 2, feed, .5f, Palette.TEXT, PLATE);
        p.tag("Steam", tx + W / 2, steam, .5f, Palette.TEXT, PLATE);
        int[] all = {cdy - 10, cdy + 10, leftY[0], leftY[1], leftY[2], leftY[3], leftY[4], leftY[5], feed, steam};
        for (int y : all) port(bx, y);
        for (int y : new int[]{dy, dy + 18, draws[0], draws[1], draws[2]}) port(br, y);
        port(dx0 + 17, by);
        port(tx + W / 2, bb);
        int ax = bx - 58;
        pill(ax - 2, all[0], "CWS", true, false);
        pill(ax - 2, all[1], "CWR", false, false);
        arrow(new int[]{ax + 18, all[0], bx, all[0]}, LINE);
        arrow(new int[]{bx, all[1], ax + 18, all[1]}, LINE);
        chain(px, bx, all);
        pill(br + 34, dy, "NAPHTHA", false, false);
        pill(br + 34, dy + 18 + 4, "WATER", false, false);
        arrow(new int[]{br, dy, br + 8, dy}, LINE);
        arrow(new int[]{br, dy + 18, br + 8, dy + 18, br + 8, dy + 22}, LINE);
        pill(br + 34, draws[0], "KERO", false, false);
        pill(br + 34, draws[1], "S-8", false, false);
        pill(br + 34, draws[2], "AGO", false, false);
        for (int y : draws) arrow(new int[]{br, y, br + 10, y}, LINE);
        arrow(new int[]{dx0 + 17, by, dx0 + 17, by - 8, br + 10, by - 8}, LINE);
        pill(br + 34, by - 8, "OFFGAS", false, false);
        arrow(new int[]{tx + W / 2, bb, tx + W / 2, bb + 12}, LINE);
        p.tag("S-9", tx + W / 2 + 14, bb + 8, .5f, LINE, PLATE);
        c.fill(bx + 6, by - 6, bx + 42, by + 6, PLATE);
        c.outline(bx + 6, by - 6, 36, 12, FRAME);
        c.text("T-101", bx + 11, by - 3, Palette.TEXT);
    }

    static int tray(int ty, int tray) {
        return ty + 15 + (tray - 1) * (H - 30) / 41;
    }

    // ---- Option C, rearranged: one part per slot of ColumnBlueprint ----

    static final Look PART = new Look(0xFF17463F, Palette.TEAL, Palette.TEXT);

    /** The pair's ports stay left, products right, overhead on top, bottoms below (plan 3.11). No line crosses another. */
    static void rearranged(int px, int py) {
        int left = 72, right = 86, top = 62, foot = 20;
        int tx = px + 186, ty = py + 84, mid = tx + W / 2, wall = tx + W;
        int bx = tx - left, br = wall + right, by = ty - top, bb = ty + H + foot;
        int yv = ty - 20, yw = ty - 6, yt = tray(ty, 1), cws = ty - 54, cwr = ty - 34;
        int ex = wall + 18, d0 = ex + 20, d1 = d0 + 32, oil = d0 + 8, boot = d1 - 9, gas = d1 - 9, vx = wall + 74;
        frame(bx, by, br, bb, true);
        SheetPainter.column(c, tx, ty, 1, 14, Look.TEAL);

        // Overhead: tower head, condenser, receiver; water passes over the head to its pair on the left.
        arrow(new int[]{mid, ty, mid, yv, ex - 8, yv}, HOT);
        arrow(new int[]{ex + 8, yv, d0, yv}, HOT);
        arrow(new int[]{bx, cws, ex + 4, cws, ex + 4, yv - 7}, COLD);
        arrow(new int[]{ex - 4, yv - 7, ex - 4, cwr, bx, cwr}, COLD);
        uCoil(ex, yv);
        p.tag("Cond.", ex, yv + 16, .5f, Palette.MUTED, 0);
        p.box(boot - 4, yv + 5, boot + 4, yw + 4, PART);
        p.vessel(d0, yv - 7, d1, yv + 7, 5, 5, PART);
        p.fitted("Drum", (d0 + d1) / 2, yv, d1 - d0, Palette.TEXT);

        // Off-gas up through its valve; water from the boot; oil down to the one pump, the tee, and a valve on each leg.
        arrow(new int[]{gas, yv - 7, gas, by}, LINE);
        small.valveDown(gas, by + 22, PART);
        arrow(new int[]{boot + 4, yw, br, yw}, LINE);
        small.valve(vx, yw, PART);
        int yp = ty - 1;
        p.wire(new int[]{oil, yv + 7, oil, yp - 5}, false, false, HOT);
        arrow(new int[]{oil, yp + 6, oil, yt - 2}, HOT);
        pump(oil, yp, true);
        arrow(new int[]{oil, yt, wall, yt}, HOT);
        small.valve(wall + 23, yt, PART);
        p.tag("Reflux", wall + 23, yt + 9, .5f, Palette.MUTED, 0);
        arrow(new int[]{oil, yt, br, yt}, LINE);
        small.valve(vx, yt, PART);
        p.tee(oil - 2, yt - 2, oil + 2, yt + 2, PART);

        // Pumparounds: draw, pump, cooler, return, each on its true tray; the cold stream through its cooler to the boundary.
        int[][] pa = {{10, 12}, {18, 20}, {27, 29}};
        String[] names = {"PA 1", "PA 2", "PA 3"};
        int cx = bx + 18, pumpX = cx + 30;
        int[] ly = new int[6];
        for (int i = 0; i < 3; i++) {
            int back = tray(ty, pa[i][0]), draw = tray(ty, pa[i][1]), cy = (back + draw) / 2;
            ly[2 * i] = cy - 15;
            ly[2 * i + 1] = cy + 15;
            arrow(new int[]{bx, cy - 15, cx, cy - 15, cx, cy - 8}, COLD);
            arrow(new int[]{cx, cy + 8, cx, cy + 15, bx, cy + 15}, COLD);
            arrow(new int[]{tx, draw, pumpX + 6, draw}, HOT);
            arrow(new int[]{pumpX - 6, draw, cx + 7, draw}, HOT);
            arrow(new int[]{cx + 7, back, tx, back}, HOT);
            coil(cx, cy);
            pump(pumpX, draw, false);
            p.tag(names[i], pumpX, draw + 16, .5f, Palette.MUTED, 0);
        }

        // What crosses the boundary through its own valve.
        int feed = tray(ty, 38), steam = tray(ty, 42) - 2, valveIn = bx + 14;
        arrow(new int[]{bx, feed, tx, feed}, LINE);
        arrow(new int[]{bx, steam, tx, steam}, LINE);
        small.valve(valveIn, feed, PART);
        small.valve(valveIn, steam, PART);
        p.tag("Feed", mid, feed, .5f, Palette.TEXT, PLATE);
        p.tag("Steam", mid, steam, .5f, Palette.TEXT, PLATE);
        int[] draws = {tray(ty, 11), tray(ty, 19), tray(ty, 29)};
        for (int y : draws) {
            arrow(new int[]{wall, y, br, y}, LINE);
            small.valve(vx, y, PART);
        }
        arrow(new int[]{mid, ty + H, mid, bb}, LINE);
        small.valveDown(mid, ty + H + 10, PART);

        int[] all = {cws, cwr, ly[0], ly[1], ly[2], ly[3], ly[4], ly[5], feed, steam};
        for (int y : all) port(bx, y);
        for (int y : new int[]{yw, yt, draws[0], draws[1], draws[2]}) port(br, y);
        port(gas, by);
        port(mid, bb);

        int ax = bx - 62;
        pill(ax, cws, "CWS", true, false);
        pill(ax, cwr, "CWR", false, false);
        arrow(new int[]{ax + 19, cws, bx, cws}, LINE);
        arrow(new int[]{bx, cwr, ax + 19, cwr}, LINE);
        chain(px, bx, all);
        arrow(new int[]{gas, by, gas, by - 12, br + 18, by - 12}, LINE);
        pill(br + 44, by - 12, "OFFGAS", false, false);
        String[] out = {"WATER", "NAPHTHA", "KERO", "S-8", "AGO"};
        int[] outY = {yw, yt, draws[0], draws[1], draws[2]};
        for (int i = 0; i < 5; i++) {
            pill(br + 44, outY[i], out[i], false, false);
            arrow(new int[]{br, outY[i], br + 18, outY[i]}, LINE);
        }
        arrow(new int[]{mid, bb, mid, bb + 12}, LINE);
        p.tag("S-9", mid + 14, bb + 8, .5f, LINE, PLATE);
        c.fill(bx + 6, by - 6, bx + 42, by + 6, PLATE);
        c.outline(bx + 6, by - 6, 36, 12, FRAME);
        c.text("T-101", bx + 11, by - 3, Palette.TEXT);
    }

    /** A cooler whose two nozzles stand on one face, so its tubes are a U. */
    static void uCoil(int cx, int cy) {
        p.circle(cx, cy, 8, COOLER);
        p.line(cx - 4, cy - 7, cx - 4, cy + 3, Palette.TEXT);
        p.line(cx - 4, cy + 3, cx + 4, cy + 3, Palette.TEXT);
        p.line(cx + 4, cy + 3, cx + 4, cy - 7, Palette.TEXT);
    }

    /** A standard pump, as the assembly drawing draws one: its motor beside a falling line, below a line across. */
    static void pump(int x, int y, boolean down) {
        if (down) c.fill(x + 5, y - 2, x + 9, y + 2, PART.stroke());
        else c.fill(x - 2, y + 5, x + 2, y + 9, PART.stroke());
        p.circle(x, y, 5, PART);
    }

    // ---- What stands outside ----

    /** The crude train from pair to pair, then the feed and steam, on the ports' y. */
    static void chain(int px, int bx, int[] y) {
        int loop = bx - 30, ax = px + 42;
        pill(ax, y[2], "CRUDE", true, false);
        arrow(new int[]{ax + 22, y[2], bx, y[2]}, LINE);
        p.tag("S-1", (ax + bx) / 2, y[2] - 8, .5f, LINE, PLATE);
        arrow(new int[]{bx, y[3], loop, y[3], loop, y[4], bx, y[4]}, LINE);
        p.tag("S-2", loop + 15, y[3] - 8, .5f, LINE, PLATE);
        arrow(new int[]{bx, y[5], loop, y[5], loop, y[6], bx, y[6]}, LINE);
        p.tag("S-3", loop + 15, y[5] - 8, .5f, LINE, PLATE);
        pill(ax, y[7], "E-101", false, false);
        arrow(new int[]{bx, y[7], ax + 22, y[7]}, LINE);
        p.tag("S-4", (ax + bx) / 2, y[7] - 8, .5f, LINE, PLATE);
        pill(ax, y[8], "V-101", false, false);
        arrow(new int[]{ax + 22, y[8], bx, y[8]}, LINE);
        p.tag("S-7", (ax + bx) / 2, y[8] - 8, .5f, LINE, PLATE);
        pill(ax, y[9], "STEAM", true, false);
        arrow(new int[]{ax + 22, y[9], bx, y[9]}, LINE);
    }

    static void outside(int px, int bx, int br, int by, int bb, int ty, int topX, int footX, int y0, int y1) {
        int[] y = new int[LEFT.length];
        for (int i = 0; i < y.length; i++) y[i] = ty + LEFT[i];
        int ax = bx - 62;
        pill(ax, y[0], "CWS", true, false);
        pill(ax, y[1], "CWR", false, false);
        arrow(new int[]{ax + 19, y[0], bx, y[0]}, LINE);
        arrow(new int[]{bx, y[1], ax + 19, y[1]}, LINE);
        chain(px, bx, y);
        String[] names = {"NAPHTHA", "WATER", "KERO", "S-8", "AGO"};
        for (int i = 0; i < 5; i++) {
            pill(br + 44, ty + RIGHT[i], names[i], false, false);
            arrow(new int[]{br, ty + RIGHT[i], br + 18, ty + RIGHT[i]}, LINE);
        }
        arrow(new int[]{topX, by, topX, by - 14, br + 18, by - 14}, LINE);
        pill(br + 44, by - 14, "OFFGAS", false, false);
        arrow(new int[]{footX, bb, footX, bb + 12}, LINE);
        p.tag("S-9", footX + 14, bb + 8, .5f, LINE, PLATE);
    }

    // ---- Parts ----

    static void frame(int x0, int y0, int x1, int y1, boolean solid) {
        if (solid) {
            c.fill(x0, y0, x1, y1, FRAME_FILL);
            c.outline(x0, y0, x1 - x0, y1 - y0, FRAME);
            return;
        }
        for (int x = x0; x < x1; x += 10) {
            c.hLine(x, Math.min(x + 5, x1), y0, FRAME);
            c.hLine(x, Math.min(x + 5, x1), y1, FRAME);
        }
        for (int y = y0; y < y1; y += 10) {
            c.fill(x0, y, x0 + 1, Math.min(y + 6, y1), FRAME);
            c.fill(x1, y, x1 + 1, Math.min(y + 6, y1), FRAME);
        }
    }

    static void arrow(int[] points, int colour) {
        p.wire(points, true, false, colour);
    }

    static void port(int x, int y) {
        p.square(x, y, 5, PortColours.CONNECTED);
    }

    static void pill(int cx, int y, String text, boolean feed, boolean lost) {
        int w = Math.max(38, c.measure().width(text) + 12);
        p.pill(cx - w / 2, y, w, 9);
        if (feed) c.outline(cx - w / 2, y - 9, w, 18, Palette.ACCENT);
        p.fitted(text, cx, y, w, feed ? Palette.ACCENT : Palette.TEXT);
    }
}
