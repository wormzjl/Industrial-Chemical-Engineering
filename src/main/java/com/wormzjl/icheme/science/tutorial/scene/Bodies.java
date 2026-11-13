package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * The shapes of a frame's fires and liquids (batch 2026-11-12-tutorial-system, P2b), built where they are drawn, the same
 * in the game's view and the render tool: a liquid is an upright prism of {@link #SIDES} sides, its part on the kept side
 * of its cut and, where the cut is upright, the cut's face; a fire is {@link #TONGUES} camera-facing tongues in three
 * orange tones whose heights flicker by a hash of the time (no {@code Random}). No Minecraft type.
 */
public final class Bodies {
    /** A liquid prism's sides; a fire's tongues; the flicker's changes a second. */
    public static final int SIDES = 24, TONGUES = 5;
    static final double FLICKER = 9;
    /** A fire's tones, outer to inner (RGB). */
    public static final int[] TONES = {0xD9480F, 0xF08C24, 0xFFD27A};

    private Bodies() {
    }

    /** A fire's triangle: three scene points and its colour (RGB). */
    public record Tongue(double[] a, double[] b, double[] c, int rgb) {
    }

    /** The liquid's faces in scene coordinates (counter-clockwise seen from outside), cut as its frame says. */
    public static List<Mesh.Polygon> liquid(Frame.LiquidFrame l) {
        var faces = new ArrayList<Mesh.Polygon>();
        if (!(l.top() > l.bottom()) || !(l.radius() > 0)) return faces;
        double[] top = new double[3 * SIDES], bottom = new double[3 * SIDES];
        for (int i = 0; i < SIDES; i++) {
            double a = 2 * Math.PI * i / SIDES, x = l.x() + l.radius() * DeterministicMath.cos(a), z = l.z() + l.radius() * DeterministicMath.sin(a);
            // The top runs clockwise in x-z seen from above (+y): counter-clockwise from outside; the bottom the other way.
            int t = SIDES - 1 - i;
            top[3 * t] = x;
            top[3 * t + 1] = l.top();
            top[3 * t + 2] = z;
            bottom[3 * i] = x;
            bottom[3 * i + 1] = l.bottom();
            bottom[3 * i + 2] = z;
        }
        faces.add(polygon(top));
        faces.add(polygon(bottom));
        for (int i = 0; i < SIDES; i++) {
            int j = (i + 1) % SIDES;
            faces.add(polygon(new double[]{bottom[3 * j], l.bottom(), bottom[3 * j + 2], bottom[3 * i], l.bottom(), bottom[3 * i + 2], bottom[3 * i], l.top(), bottom[3 * i + 2],
                    bottom[3 * j], l.top(), bottom[3 * j + 2]}));
        }
        var cut = l.cut();
        if (cut == null) return faces;
        double[] p = {cut.px(), cut.py(), cut.pz()}, n = {cut.nx(), cut.ny(), cut.nz()};
        var kept = new ArrayList<>(Clip.keep(faces, p, n));
        // An upright cut opens the prism along a chord: its face, the liquid's section, faces the cut's normal.
        double h = Math.sqrt(n[0] * n[0] + n[2] * n[2]);
        if (Math.abs(n[1]) < 1e-9 && h > 0) {
            double nx = n[0] / h, nz = n[2] / h, d = (l.x() - p[0]) * nx + (l.z() - p[2]) * nz;
            if (Math.abs(d) < l.radius()) {
                double half = Math.sqrt(l.radius() * l.radius() - d * d), cx = l.x() - d * nx, cz = l.z() - d * nz, tx = -nz, tz = nx;
                double x0 = cx - half * tx, z0 = cz - half * tz, x1 = cx + half * tx, z1 = cz + half * tz;
                kept.add(polygon(new double[]{x1, l.bottom(), z1, x0, l.bottom(), z0, x0, l.top(), z0, x1, l.top(), z1}));
            }
        }
        return kept;
    }

    private static Mesh.Polygon polygon(double[] positions) {
        int n = positions.length / 3;
        return new Mesh.Polygon(positions, new double[2 * n], new double[3 * n], Mesh.Material.SHELL);
    }

    /**
     * The fire's tongues facing the camera: {@code right} and {@code up} are the view's axes (unit, scene coordinates).
     * Each tongue is a triangle on a base across the fire, its tip at its flickering height; three tones from the widest
     * and tallest to the smallest, each a hair nearer the camera than the one behind it ({@code forward}).
     */
    public static List<Tongue> flame(Frame.FlameFrame f, double[] right, double[] up, double[] forward) {
        var out = new ArrayList<Tongue>();
        if (!(f.size() > 0)) return out;
        double width = f.size() * .9, base = width / TONGUES;
        long tick = (long) Math.floor(f.time() * FLICKER);
        double blend = f.time() * FLICKER - tick;
        for (int tone = 0; tone < TONES.length; tone++) {
            double scale = 1 - .28 * tone, near = -.002 * (tone + 1);
            for (int i = 0; i < TONGUES; i++) {
                double h0 = DotMotion.unit(tone, i, (int) (tick & 0x3FFFFFFF)), h1 = DotMotion.unit(tone, i, (int) ((tick + 1) & 0x3FFFFFFF));
                double flicker = .7 + .3 * (h0 + (h1 - h0) * blend), centre = (i + .5) / TONGUES - .5;
                double tall = f.size() * scale * flicker * (1 - 1.2 * centre * centre), half = base * .75 * scale;
                double ox = centre * width * (tone == 0 ? 1 : .8);
                double[] left = at(f, right, up, forward, ox - half, 0, near), rightPoint = at(f, right, up, forward, ox + half, 0, near), tip = at(f, right, up, forward, ox, tall, near);
                out.add(new Tongue(left, rightPoint, tip, TONES[tone]));
            }
        }
        return out;
    }

    private static double[] at(Frame.FlameFrame f, double[] r, double[] u, double[] fw, double across, double high, double near) {
        return new double[]{f.x() + r[0] * across + u[0] * high + fw[0] * near, f.y() + r[1] * across + u[1] * high + fw[1] * near, f.z() + r[2] * across + u[2] * high + fw[2] * near};
    }
}
