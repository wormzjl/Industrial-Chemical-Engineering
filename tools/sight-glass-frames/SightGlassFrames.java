import com.wormzjl.icheme.science.equipment.SightGlassGeometry;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * A frame strip of a sight glass's bubbles (batch 2026-11-18-equipment-reflections, part 3): five runs, 8 frames each,
 * 2 ticks apart, on one sheet. The motion is the production law ({@code SightGlassGeometry.velocity}, {@code Travel},
 * {@code bubbles}); the shading of each bubble is a COPY of {@code bubbleShade} and {@code main} in
 * {@code assets/icheme/shaders/core/sight_glass.fsh}, line for line in Java (keep the two the same). Full light, no fog.
 * Run from the repository root after {@code compileJava}:
 * {@code java -cp build/classes/java/main tools/sight-glass-frames/SightGlassFrames.java [out.png]}.
 */
public final class SightGlassFrames {
    static final int SIZE = 112, PAD = 8, FRAMES = 8, STEP = 2, LABEL = 170, TOP = 22;
    static final double DIAMETER = .1;
    static final int LIQUID = 0xC8A17A40, STRENGTH = 200;
    static final long SEED = 9;

    record Run(String name, double speed, int rise, SightGlassGeometry.Motion motion, boolean stratified) {}

    public static void main(String[] args) throws Exception {
        var runs = new Run[]{
                new Run("horizontal 0.05 m/s", .05, 0, new SightGlassGeometry.Motion(1, 0), true),
                new Run("horizontal 0.5 m/s", .5, 0, new SightGlassGeometry.Motion(1, 0), true),
                new Run("horizontal 2 m/s", 2, 0, new SightGlassGeometry.Motion(1, 0), true),
                new Run("vertical up 0.5 m/s", .5, 1, new SightGlassGeometry.Motion(0, 1), false),
                new Run("vertical down 0.5 m/s", .5, -1, new SightGlassGeometry.Motion(0, -1), false)};
        int width = LABEL + FRAMES * (SIZE + PAD) + PAD, height = TOP + runs.length * (SIZE + PAD) + PAD;
        var sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x2B2B2B));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(Color.WHITE);
        g.drawString("window 0.1 m, gas share " + STRENGTH + "/255, frames 2 ticks apart, flow arrow in each label", PAD, 15);
        int row = 0;
        for (var run : runs) {
            double v = SightGlassGeometry.velocity(run.speed(), DIAMETER, run.rise());
            var travel = SightGlassGeometry.Travel.start(1000, v);
            int y0 = TOP + row * (SIZE + PAD);
            g.setColor(Color.WHITE);
            g.drawString(run.name() + (run.motion().y() > 0 ? "  ^" : run.motion().y() < 0 ? "  v" : "  >"), PAD, y0 + SIZE / 2 - 6);
            g.drawString(String.format("bubbles %+.3f m/s", v), PAD, y0 + SIZE / 2 + 10);
            g.drawString(String.format("crossing %.2f s", SightGlassGeometry.crossing(v, DIAMETER)), PAD, y0 + SIZE / 2 + 26);
            for (int f = 0; f < FRAMES; f++) {
                double ticks = 1000 + STEP * f;
                var bubbles = SightGlassGeometry.bubbles(travel.at(ticks), SEED, STRENGTH, run.motion(), DIAMETER, travel.eased(ticks), run.stratified());
                frame(sheet, LABEL + f * (SIZE + PAD), y0, bubbles);
            }
            row++;
        }
        g.dispose();
        var out = new File(args.length > 0 ? args[0] : "tmp/sight-glass-frames/sheet.png");
        out.getParentFile().mkdirs();
        ImageIO.write(sheet, "png", out);
        System.out.println("wrote " + out);
    }

    static void frame(BufferedImage sheet, int x0, int y0, java.util.List<SightGlassGeometry.Bubble> bubbles) {
        int colour = SightGlassGeometry.bubbleColour(LIQUID, STRENGTH);
        double[] rim = {(colour >> 16 & 255) / 255.0, (colour >> 8 & 255) / 255.0, (colour & 255) / 255.0}, liquid = {(LIQUID >> 16 & 255) / 255.0, (LIQUID >> 8 & 255) / 255.0, (LIQUID & 255) / 255.0};
        double rimAlpha = (colour >>> 24) / 255.0;
        for (int py = 0; py < SIZE; py++)
            for (int px = 0; px < SIZE; px++) {
                // Window radii, y up; a small margin outside the glass shows the steel.
                double wx = (px + .5 - SIZE / 2.0) / (SIZE / 2.0 * .9), wy = -(py + .5 - SIZE / 2.0) / (SIZE / 2.0 * .9);
                double[] c = Math.hypot(wx, wy) > 1 ? new double[]{.42, .43, .45} : liquid.clone();
                for (var b : bubbles) {
                    // As sight_glass.vsh: UV0 the offset in the bubble's radius, the normal the flow and the stretch.
                    double dx = (wx - b.x()) / b.radius(), dy = (wy - b.y()) / b.radius();
                    if (Math.abs(dx) > b.stretch() || Math.abs(dy) > b.stretch()) continue; // outside the quad
                    double[] shade = bubbleShade(dx, dy, b.ax(), b.ay(), b.stretch());
                    // main(): the glass's rim, the alpha, the white highlight, then the translucent blend.
                    double edge = 1 - smoothstep(APERTURE - .02, APERTURE, Math.hypot(wx, wy));
                    double alpha = rimAlpha * shade[0] * edge;
                    if (alpha < .004) continue;
                    for (int k = 0; k < 3; k++) {
                        double lit = rim[k] + (1 - rim[k]) * .8 * shade[1];
                        c[k] = lit * alpha + c[k] * (1 - alpha);
                    }
                }
                int rgb = (int) Math.round(255 * c[0]) << 16 | (int) Math.round(255 * c[1]) << 8 | (int) Math.round(255 * c[2]);
                sheet.setRGB(x0 + px, y0 + py, rgb);
            }
    }

    // ---- a copy of sight_glass.fsh -------------------------------------------------------------------------------------
    static final double APERTURE = 0.97, HIGHLIGHT_X = -0.32, HIGHLIGHT_Y = 0.42;

    static double[] bubbleShade(double dx, double dy, double ax, double ay, double stretch) {
        double along = (dx * ax + dy * ay) / stretch, across = dx * -ay + dy * ax;
        double r = Math.hypot(along, across);
        if (r > 1.0) return new double[]{0, 0};
        double rim = smoothstep(0.5, 0.88, r) * (1.0 - smoothstep(0.9, 1.0, r));
        double spot = 1.0 - smoothstep(0.0, 0.3, Math.hypot(dx - HIGHLIGHT_X, dy - HIGHLIGHT_Y));
        return new double[]{Math.max(0, Math.min(1, 0.1 + 0.9 * rim + 0.8 * spot)), spot};
    }

    static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
