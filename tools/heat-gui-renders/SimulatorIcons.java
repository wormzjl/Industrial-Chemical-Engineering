import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The process simulator's equipment symbols, as vector shapes in each symbol's own design box (batch
 * 2026-11-02-process-simulator, owner: "The equipment icons needs further improvement"). One geometry per symbol, three ways
 * to paint it, so the owner can pick on the option sheets. Each is true to the mod's own equipment: a column's trays stop short
 * of one wall in turn, as downcomers do; a drum stands on saddles or a skirt; a heater has its radiant box, convection bank and
 * stack; an air cooler's fans blow up through its bundle. Straight corners; only heads and domes are round (the owner's rule
 * from the logo work). No gradients, shading or light.
 *
 * <p>In the game a symbol would be a baked mask pair (body and marks) in a texture atlas, tinted by the unit's state, so its
 * edges stay smooth at every window scale and zoom. These sheets draw the same shapes straight onto the image.
 */
final class SimulatorIcons {
    /** How a symbol is painted. */
    enum Style {
        /** A flat silhouette in the state's colour, with its details cut out of it. */
        SOLID,
        /** The state's dark fill under an outline and details in the state's colour, as the shipped door drawings. */
        FRAMED,
        /** Outlines only, on the bare glass, with the schematic marks of a process drawing. */
        LINE
    }

    /** The style the sheets draw; each option sheet sets its own. */
    static Style style = Style.FRAMED;

    static final int FLAME = 0xFFEF9F27;

    /** The parts of a symbol, in its own box's units. */
    private static final class Pen {
        final List<Shape> bodies = new ArrayList<>(), solid = new ArrayList<>(), marks = new ArrayList<>(), fine = new ArrayList<>(), dash = new ArrayList<>(), hot = new ArrayList<>();

        void body(Shape... shapes) {
            bodies.addAll(List.of(shapes));
        }

        void solid(Shape... shapes) {
            solid.addAll(List.of(shapes));
        }

        void mark(Shape... shapes) {
            marks.addAll(List.of(shapes));
        }

        void fine(Shape... shapes) {
            fine.addAll(List.of(shapes));
        }

        void dash(Shape... shapes) {
            dash.addAll(List.of(shapes));
        }

        void hot(Shape... shapes) {
            hot.addAll(List.of(shapes));
        }
    }

    static Rectangle2D rect(double x0, double y0, double x1, double y1) {
        return new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
    }

    static Line2D line(double x0, double y0, double x1, double y1) {
        return new Line2D.Double(x0, y0, x1, y1);
    }

    static Ellipse2D oval(double x0, double y0, double x1, double y1) {
        return new Ellipse2D.Double(x0, y0, x1 - x0, y1 - y0);
    }

    static Ellipse2D circle(double cx, double cy, double r) {
        return oval(cx - r, cy - r, cx + r, cy + r);
    }

    /** A closed polygon through {@code xy}. */
    static Path2D poly(double... xy) {
        var path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.closePath();
        return path;
    }

    /** An open line through {@code xy}. */
    static Path2D path(double... xy) {
        var path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        return path;
    }

    /** A zigzag from ({@code x0}, {@code y}) to ({@code x1}, {@code y}), {@code teeth} teeth of amplitude {@code a}: a schematic coil. */
    static Path2D zigzag(double x0, double x1, double y, int teeth, double a) {
        var path = new Path2D.Double();
        path.moveTo(x0, y);
        double step = (x1 - x0) / (2 * teeth + 2);
        for (int i = 0; i < 2 * teeth; i++) path.lineTo(x0 + (i + 1.5) * step, y + (i % 2 == 0 ? -a : a));
        path.lineTo(x1, y);
        return path;
    }

    /** A vessel standing up: a cylinder between 2:1 heads, a quarter of its width deep. */
    static Area standing(double x0, double y0, double x1, double y1) {
        double head = (x1 - x0) / 4;
        var a = new Area(rect(x0, y0 + head, x1, y1 - head));
        a.add(new Area(oval(x0, y0, x1, y0 + 2 * head)));
        a.add(new Area(oval(x0, y1 - 2 * head, x1, y1)));
        return a;
    }

    /** A vessel lying down: a cylinder between 2:1 heads, a quarter of its height deep. */
    static Area lying(double x0, double y0, double x1, double y1) {
        double head = (y1 - y0) / 4;
        var a = new Area(rect(x0 + head, y0, x1 - head, y1));
        a.add(new Area(oval(x0, y0, x0 + 2 * head, y1)));
        a.add(new Area(oval(x1 - 2 * head, y0, x1, y1)));
        return a;
    }

    /** {@code a} less everything outside {@code window}. */
    static Area clipped(Shape a, Shape window) {
        var out = new Area(a);
        out.intersect(new Area(window));
        return out;
    }

    /** A flame standing on ({@code x}, {@code y}), {@code w} wide and {@code h} high. */
    static Path2D flame(double x, double y, double w, double h) {
        return poly(x - w / 2, y, x - w / 4, y - h / 3, x, y - h, x + w / 4, y - h / 3, x + w / 2, y);
    }

    /** {@code g} in the box from ({@code x}, {@code y}), {@code w} by {@code h}, on {@code graphics}. */
    static void paint(Graphics2D graphics, SimulatorPrototypes.Glyph g, double x, double y, double w, double h, Look look) {
        var pen = new Pen();
        boolean schematic = style == Style.LINE;
        // A column's box sets its own proportions; every other symbol scales from its design box.
        double gw = g == SimulatorPrototypes.Glyph.COLUMN ? w : g.w, gh = g == SimulatorPrototypes.Glyph.COLUMN ? h : g.h;
        draw(pen, g, gw, gh, schematic);
        var at = new AffineTransform();
        at.translate(x, y);
        at.scale(w / gw, h / gh);
        double scale = Math.min(w / gw, h / gh);
        boolean small = scale < .62;
        var aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var all = new Area();
        for (var body : pen.bodies) all.add(new Area(at.createTransformedShape(body)));
        var edge = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
        var mark = new BasicStroke(.75f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
        var fine = new BasicStroke(.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
        var dash = new BasicStroke(.75f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, new float[]{2.5f, 1.5f}, 0);
        var colour = new Color(look.stroke(), true);
        switch (style) {
            case SOLID -> {
                for (var s : pen.solid) all.subtract(new Area(at.createTransformedShape(s)));
                for (var s : pen.marks) all.subtract(new Area(mark.createStrokedShape(at.createTransformedShape(s))));
                if (!small) for (var s : pen.fine) all.subtract(new Area(fine.createStrokedShape(at.createTransformedShape(s))));
                for (var s : pen.dash) all.subtract(new Area(dash.createStrokedShape(at.createTransformedShape(s))));
                graphics.setColor(colour);
                graphics.fill(all);
            }
            case FRAMED, LINE -> {
                if (style == Style.FRAMED) {
                    graphics.setColor(new Color(look.fill(), true));
                    graphics.fill(all);
                }
                graphics.setColor(colour);
                graphics.setStroke(edge);
                graphics.draw(all);
                for (var s : pen.solid) graphics.fill(at.createTransformedShape(s));
                graphics.setStroke(mark);
                for (var s : pen.marks) graphics.draw(at.createTransformedShape(s));
                graphics.setStroke(fine);
                if (!small) for (var s : pen.fine) graphics.draw(at.createTransformedShape(s));
                graphics.setStroke(dash);
                for (var s : pen.dash) graphics.draw(at.createTransformedShape(s));
                graphics.setStroke(new BasicStroke());
            }
        }
        graphics.setColor(new Color(FLAME));
        for (var s : pen.hot) graphics.fill(at.createTransformedShape(s));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    /** The masks {@link SymbolAtlas} bakes: a symbol's framed look, split as the game tints it. */
    enum Layer {
        BODY, MARKS, HOT
    }

    /**
     * One layer of {@code g}'s framed look, white, in its own design units on {@code graphics}: the body's fill, or its outline, solids, marks and fine lines at the
     * framed strokes, or its fire. A box {@code w} by {@code h}: a column sets its own proportions.
     */
    static void layer(Graphics2D graphics, SimulatorPrototypes.Glyph g, double w, double h, Layer layer) {
        var pen = new Pen();
        draw(pen, g, w, h, false);
        var all = new Area();
        for (var body : pen.bodies) all.add(new Area(body));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setColor(Color.WHITE);
        switch (layer) {
            case BODY -> graphics.fill(all);
            case HOT -> {
                for (var s : pen.hot) graphics.fill(s);
            }
            case MARKS -> {
                graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                graphics.draw(all);
                for (var s : pen.solid) graphics.fill(s);
                graphics.setStroke(new BasicStroke(.75f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                for (var s : pen.marks) graphics.draw(s);
                graphics.setStroke(new BasicStroke(.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                for (var s : pen.fine) graphics.draw(s);
                graphics.setStroke(new BasicStroke(.75f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, new float[]{2.5f, 1.5f}, 0));
                for (var s : pen.dash) graphics.draw(s);
            }
        }
    }

    /** The parts of {@code g} in a box {@code w} by {@code h}; {@code schematic}: a process drawing's marks in place of the equipment's own. */
    private static void draw(Pen p, SimulatorPrototypes.Glyph g, double w, double h, boolean schematic) {
        switch (g) {
            case FEED -> p.body(poly(0, 0, 28, 0, 40, 9, 28, 18, 0, 18));
            case PRODUCT -> p.body(poly(0, 0, 30, 0, 40, 9, 30, 18, 0, 18, 8, 9));
            case VALVE -> {
                p.body(poly(0, 6.5, 12, 12, 0, 17.5), poly(24, 6.5, 12, 12, 24, 17.5));
                p.body(rect(11.4, 5, 12.6, 12), clipped(oval(5, 0, 19, 10), rect(5, 0, 19, 5)));
            }
            case MIXER -> {
                p.body(poly(0, 0, 22, 13, 0, 26));
                p.mark(path(5, 8, 11, 13, 5, 18));
            }
            case SPLITTER -> {
                p.body(poly(22, 0, 0, 13, 22, 26));
                p.mark(path(11, 8, 17, 13, 11, 18));
            }
            case FILTER -> {
                p.body(rect(3, 3, 27, 23), rect(0, 11.2, 3, 14.8), rect(27, 11.2, 30, 14.8));
                p.dash(line(3, 23, 27, 3));
            }
            case PUMP -> {
                p.body(circle(15, 13, 12), rect(0, 11.2, 4, 14.8), rect(26, 11.2, 30, 14.8));
                p.body(poly(8, 23, 22, 23, 25, 31, 5, 31), rect(1, 31, 29, 34));
                p.solid(poly(10.5, 6.5, 22, 13, 10.5, 19.5));
            }
            case COMPRESSOR -> {
                // Balanced-opposed: a cylinder on either side of the frame, a bottle over them.
                p.body(rect(0, 12, 12, 26), rect(22, 12, 34, 26), rect(12, 15, 22, 30), rect(2, 3, 32, 8), rect(1, 30, 33, 34));
                p.body(rect(5, 8, 7.5, 12), rect(26.5, 8, 29, 12));
                p.mark(line(4, 12, 4, 26), line(30, 12, 30, 26));
                p.fine(line(12, 19, 22, 19));
                p.solid(circle(17, 23.5, 2.6));
            }
            case TURBO -> {
                p.body(poly(0, 1, 34, 9, 34, 25, 0, 33));
                p.mark(line(11, 3.6, 11, 30.4), line(22, 6.2, 22, 27.8));
                p.fine(line(0, 17, 34, 17));
            }
            case TURBINE, CONDENSING -> {
                p.body(poly(0, 9, 34, 1, 34, 33, 0, 25));
                p.mark(line(12, 6.2, 12, 27.8), line(23, 3.6, 23, 30.4));
                p.fine(line(0, 17, 34, 17));
                if (g == SimulatorPrototypes.Glyph.CONDENSING) p.body(rect(24, 31, 33, 34));
            }
            case EJECTOR -> {
                p.body(poly(0, 5, 16, 5, 25, 8.5, 31, 8.5, 50, 1, 50, 19, 31, 11.5, 25, 11.5, 16, 15, 0, 15));
                p.body(rect(7, 0, 13, 5));
                p.solid(poly(0, 8, 13, 9.3, 13, 10.7, 0, 12));
            }
            case EXCHANGER -> {
                // TEMA: channel and its head, girth flange, shell with its baffles, rear flange and head; shell nozzles under it.
                p.body(oval(0, 4, 12, 24), rect(6, 4, 10, 24), rect(10, 2, 12, 26), rect(12, 4, 52, 24), rect(52, 2, 54, 26), oval(52, 4, 64, 24), rect(54, 4, 58, 24));
                p.body(rect(14.4, 24, 17.6, 28), rect(46.4, 24, 49.6, 28));
                if (schematic) p.mark(zigzag(4, 60, 14, 5, 7));
                else {
                    for (double y : new double[]{9.5, 14, 18.5}) p.fine(line(12, y, 52, y));
                    for (int i = 0; i < 6; i++) p.mark(i % 2 == 0 ? line(17 + i * 6, 4, 17 + i * 6, 17) : line(17 + i * 6, 24, 17 + i * 6, 11));
                }
                p.mark(line(10, 4, 10, 24), line(12, 4, 12, 24), line(52, 4, 52, 24), line(54, 4, 54, 24));
            }
            case KETTLE -> {
                p.body(oval(0, 10, 11, 24), rect(5.5, 10, 9, 24), rect(9, 8.5, 11, 25.5), poly(11, 10, 23, 1, 23, 33, 11, 24), rect(23, 1, 62, 33), oval(54, 1, 70, 33));
                p.body(rect(40, 0, 48, 1.5), rect(44, 33, 48, 34));
                if (schematic) p.mark(zigzag(3, 52, 21, 5, 6));
                else {
                    p.mark(path(11, 14, 48, 14), path(11, 25, 48, 25), line(52, 33, 52, 11));
                    p.fine(line(48, 14, 48, 25));
                    for (double y : new double[]{17.7, 21.3}) p.fine(line(11, y, 48, y));
                }
                p.dash(line(23, 9.5, 52, 9.5));
                p.mark(line(9, 10, 9, 24), line(11, 10, 11, 24));
            }
            case AIR_COOLER -> {
                p.body(rect(5, 3, 55, 13), rect(0, 1, 6, 15), rect(54, 1, 60, 15));
                p.body(poly(14, 13, 46, 13, 39, 19, 21, 19), rect(19, 19, 41, 21), rect(28, 21, 32, 27), rect(7, 13, 9, 32), rect(51, 13, 53, 32));
                p.mark(line(6, 1, 6, 15), line(54, 1, 54, 15));
                if (schematic) p.mark(zigzag(7, 53, 8, 4, 3.5));
                else {
                    p.fine(line(6, 8, 54, 8));
                    for (int i = 1; i < 12; i++) p.fine(line(6 + i * 4, 3, 6 + i * 4, 13));
                }
                p.body(poly(17, 21, 43, 21, 43, 22, 17, 22));
            }
            case HEATER -> {
                p.body(rect(24, 0, 34, 2), rect(25.5, 2, 32.5, 16), poly(8, 24, 23, 16, 35, 16, 50, 24), rect(8, 24, 50, 42), rect(0, 42, 58, 80));
                p.body(rect(6, 80, 9, 88), rect(49, 80, 52, 88), rect(27.5, 80, 30.5, 88));
                p.mark(line(8, 24, 50, 24), line(0, 42, 58, 42));
                if (schematic) p.mark(zigzag(11, 47, 33, 5, 6), line(5, 46, 5, 76), line(53, 46, 53, 76));
                else {
                    for (int r = 0; r < 2; r++) for (int i = 0; i < 6; i++) p.fine(circle(14 + i * 6 + r * 3 - 1.5, 29 + r * 8, 1.7));
                    p.mark(line(5, 46, 5, 76), line(53, 46, 53, 76));
                }
                p.hot(flame(16, 78, 8, 20), flame(29, 78, 8, 24), flame(42, 78, 8, 20));
            }
            case CABIN -> {
                p.body(rect(32, 0, 42, 2), rect(33.5, 2, 40.5, 12), rect(16, 12, 58, 24), poly(0, 36, 16, 24, 58, 24, 74, 36), rect(0, 36, 74, 66));
                p.body(rect(6, 66, 9, 74), rect(65, 66, 68, 74), rect(35.5, 66, 38.5, 74));
                p.mark(line(16, 24, 58, 24), line(0, 36, 74, 36));
                if (schematic) p.mark(zigzag(19, 55, 18, 5, 4), line(4, 40, 4, 62), line(70, 40, 70, 62));
                else {
                    for (int i = 0; i < 6; i++) p.fine(circle(21 + i * 6.4, 18, 1.7));
                    p.mark(line(4, 40, 4, 62), line(70, 40, 70, 62));
                }
                p.hot(flame(16, 64, 8, 16), flame(30, 64, 8, 18), flame(44, 64, 8, 18), flame(58, 64, 8, 16));
            }
            case COLUMN -> {
                p.body(standing(0, 0, w, h));
                double head = w / 4, span = h - 2 * head, step = 8;
                int trays = Math.max(3, (int) Math.floor(span / step) - 1);
                for (int i = 1; i <= trays; i++) {
                    double y = head + i * span / (trays + 1);
                    if (schematic) p.dash(line(0, y, w, y));
                    else p.mark(i % 2 == 0 ? line(0, y, .76 * w, y) : line(.24 * w, y, w, y));
                }
            }
            case DRUM -> {
                p.body(standing(0, 0, 28, 54), rect(3, 52, 25, 60));
                p.dash(line(0, 36, 28, 36));
                if (!schematic) p.mark(rect(0, 13, 28, 16));
            }
            case LYING, BOOT_DRUM -> {
                p.body(lying(0, 0, 64, 22));
                p.dash(line(0, 14, 64, 14));
                p.body(poly(10, 21, 20, 21, 22, 28, 8, 28), poly(44, 21, 54, 21, 56, 28, 42, 28));
                if (g == SimulatorPrototypes.Glyph.BOOT_DRUM) p.body(rect(29, 21, 37, 27), oval(29, 24, 37, 28));
            }
            case TANK -> {
                p.body(rect(2, 2, 52, 40), rect(0, 5, 54, 7), rect(0, 40, 54, 42));
                p.mark(line(3, 15, 51, 15));
                p.mark(rect(3, 12.5, 10, 15), rect(44, 12.5, 51, 15));
                if (!schematic) p.fine(line(18, 15, 18, 19), line(36, 15, 36, 19));
                p.dash(line(2, 21, 52, 21));
            }
            case SPHERE -> {
                p.body(circle(22, 19, 19), rect(9, 19, 11, 44), rect(33, 19, 35, 44), rect(21, 36, 23, 44), rect(13, 0, 31, 1.2));
                p.fine(line(9, 44, 21, 32), line(21, 44, 9, 32), line(23, 32, 35, 44), line(23, 44, 35, 32));
                p.fine(line(3, 19, 41, 19));
                p.fine(oval(12, 0, 32, 38));
            }
            case ADSORBER -> {
                p.body(standing(0, 0, 26, 54), rect(3, 52, 23, 60));
                p.mark(line(0, 12, 26, 12), line(0, 42, 26, 42));
                for (int r = 0; r < 5; r++) for (int i = 0; i < 4; i++) p.solid(circle(5 + i * 5.3 + r % 2 * 2.6, 16 + r * 5.5, .8));
            }
            case CYCLONE -> {
                p.body(rect(9, 0, 17, 6), rect(0, 6, 26, 22), poly(0, 22, 26, 22, 15, 52, 11, 52), rect(11, 52, 15, 60));
                p.fine(line(9, 6, 9, 16), line(17, 6, 17, 16));
                p.mark(line(0, 22, 26, 22));
            }
            case REACTOR -> {
                p.body(rect(13, 0, 21, 7), rect(16.2, 7, 17.8, 10), standing(3, 9, 31, 52));
                p.mark(line(17, 12, 17, 43), line(9, 30, 25, 30), line(9, 41, 25, 41));
            }
            case TRICKLE -> {
                p.body(standing(0, 0, 26, 54), rect(3, 52, 23, 60));
                for (double y : new double[]{14, 30}) {
                    p.mark(rect(0, y, 26, y + 11));
                    p.fine(line(3, y + 11, 10, y), line(11, y + 11, 18, y), line(19, y + 11, 25, y + 2));
                }
            }
            case EBULLATED -> {
                p.body(standing(0, 0, 26, 54), rect(3, 52, 23, 60));
                p.mark(line(0, 42, 26, 42));
                p.dash(line(0, 18, 26, 18));
                for (int i = 0; i < 6; i++) p.fine(circle(6 + i % 3 * 7, 24 + i * 3, 1.6));
            }
            case FCC -> {
                // The regenerator, the reactor over its riser, the standpipes between them.
                p.body(standing(26, 22, 48, 56), standing(2, 0, 22, 24), rect(10, 24, 14, 54), rect(10, 50, 28, 54), poly(22, 34, 26, 38, 26, 42, 22, 38));
                p.mark(line(2, 12, 22, 12), line(26, 42, 48, 42));
            }
            case COKER -> {
                p.body(standing(0, 16, 20, 56), standing(24, 16, 44, 56));
                p.body(poly(9, 16, 10, 0, 34, 0, 35, 16));
                p.fine(line(10, 0, 22, 16), line(34, 0, 22, 16), line(9.5, 8, 34.5, 8));
                p.mark(line(0, 36, 20, 36), line(24, 36, 44, 36));
            }
            case CFB -> {
                p.body(rect(0, 0, 30, 54), rect(4, 54, 7, 60), rect(23, 54, 26, 60), rect(30, 4, 36, 10), rect(36, 2, 50, 18), poly(36, 18, 50, 18, 45, 36, 41, 36), rect(41, 36, 45, 48), rect(30, 44, 45, 48));
                p.mark(line(30, 4, 30, 10), line(0, 44, 30, 44));
                for (int i = 0; i < 4; i++) p.solid(circle(7 + i * 5.5, 39 - (i % 2) * 3, 1.1));
                p.hot(flame(15, 54, 8, 8));
            }
            case GRATE -> {
                p.body(rect(0, 0, 50, 52), rect(4, 52, 7, 60), rect(43, 52, 46, 60), poly(0, 8, 12, 8, 12, 14, 0, 20));
                p.mark(path(4, 34, 46, 48));
                p.hot(flame(18, 40, 9, 16), flame(30, 44, 9, 14));
            }
            case FLUID_GAS -> {
                p.body(standing(0, 0, 30, 54), rect(4, 52, 26, 60));
                p.mark(line(0, 42, 30, 42));
                p.dash(line(0, 24, 30, 24));
                for (int r = 0; r < 3; r++) for (int i = 0; i < 4; i++) p.solid(circle(6 + i * 6 + r % 2 * 2, 28 + r * 4.4, .9));
            }
            case FIXED_GAS -> {
                p.body(standing(0, 0, 30, 54), rect(10, 52, 20, 60));
                p.mark(path(0, 40, 15, 34, 30, 40));
                p.fine(line(0, 20, 30, 20));
                p.hot(flame(15, 34, 10, 8));
            }
            case COOLING_TOWER -> {
                p.body(poly(11, 0, 39, 0, 37, 14, 37, 24, 40, 38, 46, 54, 4, 54, 10, 38, 13, 24, 13, 14), rect(6, 54, 9, 60), rect(23, 54, 27, 60), rect(41, 54, 44, 60));
                p.mark(line(9, 42, 41, 42));
                p.dash(line(8, 47, 42, 47));
            }
            // A flare stack: a tapered foot, the riser, the tip with its windshield, and the flame over it (batch 2026-11-09-flare-stack).
            case FLARE -> {
                p.body(poly(5, 60, 15, 60, 12, 52, 8, 52), rect(8.5, 20, 11.5, 52), rect(6, 16, 14, 20));
                p.mark(line(8.5, 36, 11.5, 36), line(6, 18, 14, 18));
                p.hot(flame(10, 15, 10, 14));
            }
            case MISSING -> p.body(rect(0, 0, w, h));
        }
    }
}
