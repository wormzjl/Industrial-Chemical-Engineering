package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

import java.util.List;

/**
 * A fluid converter (batch 2026-11-12-fluid-conversion, {@code FLUID_CONVERSION_PLAN.md} 3.1; owner decisions D1, D4, D7):
 * two tanks of one size on a common slab, the Forge fluid's on the left and the process fluid's on the right as the
 * constructor sees them, joined through the middle of the bay between them by a straight transfer line through a flow
 * meter, with the controller's cabinet in the bay in front of the meter. Its {@code diameter} is each tank's width, 1 to
 * 20 m in whole metres, its {@code tangentLength} each tank's height, 2 to 24 m; the process tank holds
 * {@code π D²/4 · H} ({@link #volume}), the Forge tank the same, and the converter passes at most one tank in 1000 s of
 * process time ({@link #rate}).
 *
 * <p>The two tanks read apart by shape and finish (owner 2026-11-12: "forge drum and IChemE drum needs to clearly
 * distinguish from each other"). The Forge tank is a square sectional panel tank, a block-world tank: bare steel panels
 * on painted seams, corner posts and a base frame, a flat roof with a hatch, the Forge fluid's square flanged port low on
 * its front with its face on a block face. The process tank is a round painted vessel with its nozzles on its back: on a
 * liquid converter an atmospheric shell under a cone roof with an open vent at its apex, the optional level gauge's
 * place on its front ({@link #gauge}); on a gas converter a pressure vessel on a skirt under a 2:1 head with stiffening
 * rings, and on its apex a flanged stub where a pressure safety valve mounts (owner 2026-11-12: "for gas variant, a PSV
 * needs to be installable").
 */
final class ConverterDesign extends UnitDesign {
    /**
     * The slab's thickness and its margin beyond the shells, m (a floating-roof tank's ringwall's: a bottom nozzle's largest
     * neck stands clear of it); the free width between the two shells, m.
     */
    static final double SLAB = .15, MARGIN = .25;
    /** The cabinet's half width, depth and top over the slab at scale 1, m. */
    static final double CABINET = .5, CABINET_DEPTH = .5, CABINET_TOP = 1.9;
    /** The process time in which the converter passes one tank at its rated flow, s. */
    static final double TURNOVER = 1000;

    private final boolean gas;

    ConverterDesign(boolean gas) {
        this.gas = gas;
    }

    /** One tank's volume, m³: its shell's cylinder. */
    static double volume(double diameter, double shell) {
        return Math.PI * diameter * diameter / 4 * shell;
    }

    /** The converter's rated flow, L per second of process time: one tank in {@link #TURNOVER}. */
    static double rate(double diameter, double shell) {
        return 1000 * volume(diameter, shell) / TURNOVER;
    }

    @Override
    public double minSize() {
        return 1;
    }

    @Override
    public double maxSize() {
        return 20;
    }

    @Override
    public double sizeStep() {
        return 1;
    }

    @Override
    public int minLength(double size) {
        return 2;
    }

    @Override
    public int maxLength(double size) {
        return 24;
    }

    /** A new converter's shells: 1.2 D, within their range. */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), (int) Math.round(1.2 * size)));
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public int maxSupport() {
        return 0;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "converter.diameter";
    }

    @Override
    public String lengthRow() {
        return "converter.shell";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("converter.held", whole(volume(shape.diameter(), shape.tangentLength())), whole(rate(shape.diameter(), shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), h = shape.tangentLength();
        return List.of(new Line("converter", size(d), (int) h, whole(volume(d, h))), new Line("converter.rate", whole(rate(d, h))));
    }

    /** The free width between the two shells, whole metres: 2 m between the smallest tanks, 6 m from 16 m across. */
    static int bay(double diameter) {
        return (int) ProcessUnit.clamp(Math.round(.25 * diameter) + 2, 2, 6);
    }

    /** The cabinet's scale: from 0.55 of its drawn size beside the smallest tanks to 1.7 beside the largest. */
    static double cabinet(double diameter) {
        return ProcessUnit.clamp(.45 + .1 * diameter, .55, 1.7);
    }

    /**
     * The optional level gauge's place on a liquid converter's process tank: its column's axis (x, z) in the unit's frame,
     * on the front, 0.7 of the radius outboard of the middle, a gauge's stand-off from the shell.
     */
    static double[] gauge(double diameter) {
        double r = diameter / 2, a = ProcessUnit.centre(r + MARGIN), o = .7 * r, rho = r + GAUGE_OUT;
        return new double[]{a + Math.ceil(diameter) + bay(diameter) + o, a - Math.sqrt(rho * rho - o * o)};
    }

    /** The level gauge's column off the shell, m. */
    static final double GAUGE_OUT = .5;

    /** The skirt under a gas converter's vessels, m. */
    static double skirt(double diameter) {
        return ProcessUnit.clamp(.25 * diameter, .4, 1.5);
    }

    @Override
    void build(ProcessUnit u) {
        double d = u.shape.diameter(), h = u.shape.tangentLength(), r = d / 2;
        double a = ProcessUnit.centre(r + MARGIN), xl = a, xr = a + Math.ceil(d) + bay(d), zc = a, xm = (xl + xr) / 2;
        int n = u.mesh.detail().sides(Math.max(12, 4 * (int) Math.ceil(.75 * Math.PI * d)));
        double pipe = ProcessUnit.clamp(.03 + .02 * d, .05, .2);
        // The slab: under both tanks and the bay between them.
        u.box(new double[]{xl - r - MARGIN, 0, zc - r - MARGIN}, new double[]{xr + r + MARGIN, SLAB, zc + r + MARGIN}, Material.CONCRETE, true);
        double base = gas ? SLAB + skirt(d) : SLAB, top = base + h;
        panelTank(u, xl, zc, r, top);
        vessel(u, xr, zc, r, n, base, top);

        // The transfer line (owner 2026-11-12: "in the middle"): straight from the Forge tank's side to the process tank's
        // through the middle of the bay, the flow meter half way.
        double ym = SLAB + pipe + .25, half = Math.max(.25, 2.5 * pipe);
        u.pipe(pipe, Material.PIPE, true, new double[]{xl + r - .05, ym, zc}, new double[]{xm - half, ym, zc});
        u.pipe(pipe, Material.PIPE, true, new double[]{xm + half, ym, zc}, new double[]{xr - r + .05, ym, zc});
        var along = Frame.of(new double[]{xm - half, ym, zc}, 0, 1);
        double body = 1.7 * pipe, flange = 2.1 * pipe;
        u.mesh.cylinder(along, flange, 0, .04, 12, Material.FLANGE);
        u.mesh.cylinder(along, body, .04, 2 * half - .04, 12, Material.MACHINE);
        u.mesh.cylinder(along, flange, 2 * half - .04, 2 * half, 12, Material.FLANGE);
        u.solids.add(ProcessUnit.span(new double[]{xm - half, ym - flange, zc - flange}, new double[]{xm + half, ym + flange, zc + flange}));

        // The controller: a cabinet on two legs in the bay in front of the meter, its door on the tanks' front line (nearer
        // the meter between the smallest tanks), under a canopy; its door a framed panel with a display, three lamps and
        // a handle; a conduit along the slab to the meter's transmitter. Its size follows the tanks' ({@link #cabinet}).
        double k = cabinet(d), cab = k * CABINET;
        double z0 = Math.min(zc - r, zc - flange - .1 - k * CABINET_DEPTH), z1 = z0 + k * CABINET_DEPTH, y0 = SLAB + .35 * k, y1 = SLAB + k * CABINET_TOP;
        for (int s = -1; s <= 1; s += 2)
            u.box(new double[]{xm + s * (cab - .08 * k) - .04 * k, SLAB, z0 + .1 * k}, new double[]{xm + s * (cab - .08 * k) + .04 * k, y0, z1 - .1 * k}, Material.STEELWORK, true);
        u.box(new double[]{xm - cab, y0, z0}, new double[]{xm + cab, y1, z1}, Material.MACHINE, true);
        u.box(new double[]{xm - cab - .1 * k, y1, z0 - .25 * k}, new double[]{xm + cab + .1 * k, y1 + .05, z1 + .05 * k}, Material.STEELWORK, false);
        double[] out = {0, 0, -1};
        for (int s = -1; s <= 1; s += 2) {
            u.mesh.bar(new double[]{xm + s * (cab - .06 * k), y0 + .06 * k, z0 - .01}, new double[]{xm + s * (cab - .06 * k), y1 - .06 * k, z0 - .01}, .04 * k, out, Material.STEELWORK);
            u.mesh.bar(new double[]{xm - cab + .06 * k, s < 0 ? y0 + .06 * k : y1 - .06 * k, z0 - .01}, new double[]{xm + cab - .06 * k, s < 0 ? y0 + .06 * k : y1 - .06 * k, z0 - .01}, .04 * k, out, Material.STEELWORK);
        }
        u.mesh.box(new double[]{xm - .3 * k, y1 - .6 * k, z0 - .02}, new double[]{xm + .3 * k, y1 - .2 * k, z0}, Mesh.ALL_FACES, Material.GAUGE_SCALE);
        for (int lamp = -1; lamp <= 1; lamp++)
            u.mesh.cylinder(Frame.of(new double[]{xm + .2 * k * lamp, y1 - .8 * k, z0}, 2, -1), .04 * k, 0, .03, 8, Material.ACTUATOR);
        u.mesh.bar(new double[]{xm + .3 * k, y0 + .3 * k, z0 - .03}, new double[]{xm + .3 * k, y0 + .6 * k, z0 - .03}, .03 * k, out, Material.FLANGE);
        double run = SLAB + .04, drop = z1 - .06 * k;
        u.mesh.bar(new double[]{xm, y0, drop}, new double[]{xm, run, drop}, .04, new double[]{1, 0, 0}, Material.PIPE);
        u.mesh.bar(new double[]{xm, run, drop}, new double[]{xm, run, zc}, .04, new double[]{0, 1, 0}, Material.PIPE);
        u.mesh.bar(new double[]{xm, run, zc}, new double[]{xm, ym - body, zc}, .04, new double[]{1, 0, 0}, Material.PIPE);

        // A catwalk from roof to roof where the shells stand 4 m or more: its deck, two rails each side on posts.
        if (h >= 4) {
            double w = .4, x0 = xl + r - .05, x1 = xr - Math.sqrt(r * r - w * w) + .05;
            u.box(new double[]{x0, top - .05, zc - w}, new double[]{x1, top, zc + w}, Material.GRATING, true);
            int posts = Math.max(2, (int) Math.ceil((x1 - x0) / 1.5) + 1);
            for (int s = -1; s <= 1; s += 2) {
                u.mesh.bar(new double[]{x0, top - .12, zc + s * (w - .05)}, new double[]{x1, top - .12, zc + s * (w - .05)}, .1, new double[]{0, 1, 0}, Material.STEELWORK);
                for (double rail : new double[]{.55, 1.05})
                    u.mesh.bar(new double[]{x0, top + rail, zc + s * w}, new double[]{x1, top + rail, zc + s * w}, .04, new double[]{0, 1, 0}, Material.HANDRAIL);
                for (int i = 0; i < posts; i++) {
                    double x = x0 + (x1 - x0) * i / (posts - 1);
                    u.mesh.bar(new double[]{x, top, zc + s * w}, new double[]{x, top + 1.05, zc + s * w}, .04, new double[]{1, 0, 0}, Material.HANDRAIL);
                }
            }
        }

        // The Forge fluid's port: a square flanged pad low on the Forge tank's front, its face on the nearest block face in
        // front of the wall (flush with it on a tank an odd number of metres across).
        double port = Math.min(.4, .45 * r), face = Math.floor(zc - r + 1e-9), py = SLAB + .1;
        u.box(new double[]{xl - port, py, face}, new double[]{xl + port, py + 2 * port, zc - r + .1}, Material.FLANGE, true);
        u.mesh.box(new double[]{xl - .7 * port, py + .3 * port, face - .02}, new double[]{xl + .7 * port, py + 1.7 * port, face}, Mesh.ALL_FACES, Material.MACHINE);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2)
                u.mesh.cylinder(Frame.of(new double[]{xl + sx * .85 * port, py + port + sy * .85 * port, face}, 2, -1), .08 * port + .01, 0, .03, 6, Material.BOLT);

        // The process nozzles, on the process tank's back: liquid low and the feed high, or a gas converter's drain low
        // and its gas high.
        double od = PipeSizes.od(.1 + .007 * d, .15, .35), high = Math.floor(top) - .5;
        u.nozzles.add(ProcessUnit.radialSide(gas ? Role.DRAIN : Role.BOTTOM, xr, .5, zc, r, n, 1, od));
        u.nozzles.add(ProcessUnit.radialSide(gas ? Role.VAPOUR_OUTLET : Role.PROCESS_INLET, xr, high, zc, r, n, 1, od));
    }

    /**
     * The Forge fluid's tank at (x, z): a square sectional panel tank of side 2 {@code r} from the slab to {@code top}, its
     * bare panels a metre square (two from 11 m across) between painted seams, on a base frame between corner posts under
     * a rim angle, a hatch on its flat roof.
     */
    private static void panelTank(ProcessUnit u, double x, double z, double r, double top) {
        u.box(new double[]{x - r, SLAB, z - r}, new double[]{x + r, top, z + r}, Mesh.ALL_FACES & ~4, Material.PIPE, true);
        double pitch = Math.max(1, Math.ceil(2 * r / 10)), seam = .03, proud = .02;
        for (int axis = 0; axis <= 2; axis += 2)
            for (int sign = -1; sign <= 1; sign += 2) {
                int across = 2 - axis;
                double[] centre = {x, 0, z};
                double wall = centre[axis] + sign * r, lo = centre[across] - r, hi = centre[across] + r;
                // The upright seams and the corner posts, then the level seams, the base frame and the rim angle.
                for (double at = lo; at <= hi + 1e-9; at += pitch) {
                    boolean corner = at < lo + 1e-9 || at > hi - 1e-9;
                    strip(u, axis, sign, wall, corner ? .05 : proud, at - (corner ? .08 : seam), at + (corner ? .08 : seam), SLAB, top);
                }
                for (double y = SLAB + pitch; y < top - .3; y += pitch) strip(u, axis, sign, wall, proud, lo, hi, y - seam, y + seam);
                strip(u, axis, sign, wall, .05, lo - .05, hi + .05, SLAB, SLAB + .12);
                strip(u, axis, sign, wall, .05, lo - .05, hi + .05, top - .08, top);
            }
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        double hatch = ProcessUnit.clamp(.15 * 2 * r, .12, .45);
        u.mesh.cylinder(up, hatch, top, top + .12, 12, Material.FLANGE);
        u.mesh.bolts(up, 2 * hatch, .7 * hatch, hatch, top + .12, 1, Material.BOLT);
    }

    /** A painted strip {@code proud} of the wall at {@code wall} on frame axis {@code axis} towards {@code sign}, from a0 to a1 across and y0 to y1. */
    private static void strip(ProcessUnit u, int axis, int sign, double wall, double proud, double a0, double a1, double y0, double y1) {
        double[] lo = new double[3], hi = new double[3];
        lo[axis] = Math.min(wall, wall + sign * proud);
        hi[axis] = Math.max(wall, wall + sign * proud);
        lo[2 - axis] = a0;
        hi[2 - axis] = a1;
        lo[1] = y0;
        hi[1] = y1;
        u.mesh.box(lo, hi, Mesh.ALL_FACES, Material.STEELWORK);
    }

    /** The process fluid's vessel at (x, z), from {@code base} to its tangent line {@code top}: a liquid converter's tank or a gas converter's pressure vessel. */
    private void vessel(ProcessUnit u, double x, double z, double r, int n, double base, double top) {
        var axis = new double[]{x, 0, z};
        var up = Frame.of(axis, 1, 1);
        if (gas) {
            u.mesh.prism(up, r - .02, SLAB, base, n, Material.SUPPORT);
            u.mesh.hoop(up, r - .02, r + .1, SLAB, SLAB + .05, n, Material.SUPPORT);
        }
        u.mesh.prism(up, r, base, top, n, Material.SHELL);
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, SLAB, top, r, 0));
        u.round(x, z, SLAB, top, r, 1);
        double crown, fitting = ProcessUnit.clamp(.05 * 2 * r, .06, .25);
        if (gas) {
            // Stiffening rings, eight at most; a 2:1 head, the safety valve's stub on its apex.
            double pitch = Math.max(1, (top - base) / 8);
            for (double y = base + pitch; y < top - .3; y += pitch) u.mesh.hoop(up, r, r + .07, y - .04, y + .04, n, Material.STEELWORK);
            crown = top + r / 2;
            u.mesh.head(up, r, top, r / 2, n, Material.HEAD);
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, top, crown, .7 * r, 0));
            double stub = crown + ProcessUnit.clamp(.3 * r, .15, .3);
            u.mesh.prism(up, fitting, crown - .05, stub, 12, Material.PIPE);
            u.mesh.cylinder(up, 1.6 * fitting, stub, stub + .05, 12, Material.FLANGE);
            u.mesh.bolts(up, 2 * fitting, fitting, 1.6 * fitting, stub + .05, 1, Material.BOLT);
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, crown - .05, stub + .05, 1.6 * fitting, 0));
        } else {
            // A cone roof over a rim angle, the open vent under its weather cap at the apex.
            double rise = Math.max(.15, r / 6), tip = Math.min(.15, r / 3);
            crown = top + rise;
            u.mesh.hoop(up, r, r + .08, top - .08, top, n, Material.HEAD);
            u.mesh.frustum(up, r, tip, top, crown, n, Material.HEAD);
            u.mesh.annulus(up, 0, tip, crown, n, 1, Material.HEAD);
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, top, crown, r / 2, 0));
            double neck = crown + ProcessUnit.clamp(.25 * 2 * r, .2, .4), cap = neck + 1.5 * fitting;
            u.mesh.prism(up, fitting, crown - .02, neck, 12, Material.PIPE);
            u.mesh.frustum(up, 2 * fitting, .4 * fitting, neck, cap, 12, Material.FLANGE);
            u.mesh.annulus(up, 0, 2 * fitting, neck, 12, -1, Material.FLANGE);
            u.mesh.annulus(up, 0, .4 * fitting, cap, 12, 1, Material.FLANGE);
        }
    }

}
