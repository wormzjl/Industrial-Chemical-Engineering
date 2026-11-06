package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

/**
 * A manway: its neck from
 * inside the vessel's plate out to its flange, the bolted flange and the painted blind cover, and the hinge davit that
 * swings the cover aside (a post on two lugs at the vessel's surface, an arm over the cover's centre, a drop to it).
 * Shared by a column's platforms ({@link ColumnStructure}) and a drum's shell or head ({@link EquipmentShape#manway}).
 *
 * <p>In the vessel frame: {@code centre} is the point on the vessel's axis the manway leaves from, {@code out} the frame
 * direction it faces and {@code up} the direction its davit stands; distances along {@code out} from {@code centre}:
 * the neck starts at {@code start} (under the plate), the davit's lugs sit on the {@code surface} and the flange face is
 * at {@code face}, the cover beyond it.
 */
public record Manway(double[] centre, double[] out, double[] up, double size, double start, double surface, double face) {
    /** Nominal access openings, m: general service, smaller vessels, and selected large equipment. */
    public static final double LARGE = .6, SMALL = .5, EXTRA_LARGE = .75;
    /** The cover's diameter over the neck's, its thickness and the flange's, and how far the face stands beyond the surface, m. */
    static final double COVER_RATIO = 4 / 3.0, THICKNESS = .06, REACH = .25;

    /** Game layout bands, not a pressure-design rule: 500 mm below 1.25 m, 600 mm generally, 750 mm from 4 m. */
    static double preferredSize(double diameter) {
        return diameter >= 4 ? EXTRA_LARGE : diameter >= 1.25 ? LARGE : SMALL;
    }

    /** A drum's opening; retain the 450 mm option where a 500 mm neck exceeds half the body diameter. */
    public static double size(double diameter) {
        return diameter >= 1 ? preferredSize(diameter) : diameter >= .9 ? .45 : 0;
    }

    public double cover() {
        return COVER_RATIO * size;
    }

    /** The across direction, out × up: the davit's post stands on its negative side. */
    double[] side() {
        return Mesh.cross(out, up);
    }

    /** The frame point {@code r} out, {@code t} across and {@code h} up from the centre. */
    double[] at(double r, double t, double h) {
        var s = side();
        var p = new double[3];
        for (int a = 0; a < 3; a++) p[a] = centre[a] + r * out[a] + t * s[a] + h * up[a];
        return p;
    }

    void draw(Mesh m) {
        var f = new Frame(centre, out, Mesh.neg(side()));
        double r = size / 2, cover = cover() / 2, t = THICKNESS, bar = Access.BAR;
        m.prism(f, r, start, face - t, 16, Material.PIPE);
        m.annulus(f, r, cover, face - t, 16, -1, Material.FLANGE);
        m.bolts(f, size, r, cover, face - t, -1, Material.BOLT);
        m.prism(f, cover, face - t, face, 16, Material.FLANGE);
        m.prism(f, cover, face, face + t, 16, Material.ACTUATOR);
        m.annulus(f, 0, cover, face + t, 16, 1, Material.ACTUATOR);
        m.bolts(f, size, r, cover, face + t, 1, Material.BOLT);
        double post = -(cover + .08), postR = surface + .15;
        m.bar(at(postR, post, -.35), at(postR, post, .58), bar * 1.5, out, Material.STEELWORK);
        for (double h : new double[]{-.22, .22}) m.bar(at(surface + .02, -r - .02, h), at(postR, post, h), bar, up, Material.STEELWORK);
        m.bar(at(postR, post, .55), at(face + t / 2, 0, .55), bar * 1.5, up, Material.STEELWORK);
        m.bar(at(face + t / 2, 0, .55), at(face + t / 2, 0, cover - .02), bar, out, Material.STEELWORK);
    }

    /** Whether a frame point lies in the neck (from just inside the surface) or the cover. */
    boolean inside(double[] p) {
        double r = 0, t = 0, h = 0;
        var s = side();
        for (int a = 0; a < 3; a++) {
            double d = p[a] - centre[a];
            r += d * out[a];
            t += d * s[a];
            h += d * up[a];
        }
        double across = t * t + h * h;
        return r >= surface - .1 && r <= face && across <= size * size / 4 || r >= face - THICKNESS && r <= face + THICKNESS && across <= cover() * cover() / 4;
    }

    /** The room it takes, {x0, y0, z0, x1, y1, z1} in the frame: neck, cover and davit. */
    double[] box() {
        double cover = cover() / 2;
        double[][] corners = {at(start, -(cover + .08) - .05, -Math.max(cover, .35)), at(face + THICKNESS, cover, Math.max(cover, .58) + .05)};
        var out = new double[6];
        for (int a = 0; a < 3; a++) {
            out[a] = Math.min(corners[0][a], corners[1][a]);
            out[a + 3] = Math.max(corners[0][a], corners[1][a]);
        }
        return out;
    }
}
