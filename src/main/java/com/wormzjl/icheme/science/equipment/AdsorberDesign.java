package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of temperature-swing adsorber beds (owner decisions 2026-10-18, NEW_UNITS_PLAN.md;
 * {@code NEW_UNITS_SURVEY_STIRRED_ADSORPTION.md} section 2): 1 to 4 molecular-sieve dryer vessels of inside diameter D,
 * 1 to 4.6 m in 0.2 m steps (4 to 5 m is the shipping limit), in a row along the unit's depth at a pitch of D + 2 m,
 * joined by valved headers top and bottom; {@code support} is the height of their skirts.
 *
 * <p>Proportions [CECA, GPN, JMC]: the bed 2 D deep (2.5 to 9 m), the vessel 1.2 m longer tangent to tangent (support
 * balls, cap layer, inlet space), 2:1 heads; the vessels clad in aluminium over 0.1 m of insulation (they cycle to
 * 290 °C), banded every 1.5 m, on skirts 2 to 4 m high (the survey's 1.5 + 0.25 D, at least the room for the bottom
 * lines) on a concrete ring, an access opening in each skirt's front and the adsorbent's unloading nozzle at 45° under
 * a blind flange at its back. Each bed's top nozzle (0.12 D) rises, lagged, to the top headers 2.1 m over the platform;
 * its bottom nozzle drops inside the skirt to the bottom headers 0.5 m over grade; each line then turns both ways
 * through an actuated ball valve (the survey's two per bed and header level) into a header: feed in at the top left and
 * product out at the bottom left, bare; regeneration gas in at the bottom right and out at the top right, lagged. The
 * platform ({@link ProcessUnit#squareDeck}) joins the beds at their top tangent: grating round a square opening per bed,
 * filled to the cladding, on beams bracketed off the shells, a caged ladder up its back walked through (from a rest
 * platform where the climb is over 9 m, owner decision C1 2026-10-18), portal frames on its edges carrying the top
 * headers. The nozzles are the header ends only (survey): FEED, VENT (0.04 D, to flare), PRODUCT and NITROGEN (purge)
 * at the front, REGENERATION_OUTLET, DRAIN and REGENERATION_INLET at the back, the top header's back end blind.
 */
final class AdsorberDesign extends UnitDesign {
    /** Insulation on the hot regeneration lines and the beds' top risers, m. */
    static final double LAGGING = .05;
    /** The platform's front edge (frame z), the headers' ends inside the unit's front and back faces, the bottom headers' row, the concrete rings' height, m. */
    static final double FRONT = .5, END = .35, BOTTOM_ROW = .5, PLINTH = .15;

    @Override
    public double minSize() {
        return 1;
    }

    @Override
    public double maxSize() {
        return 4.6;
    }

    @Override
    public double sizeStep() {
        return .2;
    }

    /** The beds: 1 to 4 (1 + 1 is the usual dryer, 2 + 1 the larger; a single bed stands for a batch guard bed). */
    @Override
    public int minLength(double size) {
        return 1;
    }

    @Override
    public int maxLength(double size) {
        return 4;
    }

    /** A new set: two beds, one adsorbing while the other regenerates (survey default 1 + 1). */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), 2));
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    /** The skirts: 2 m at least, and room under the bottom head for its nozzle, flanges and the elbow into the bottom row. */
    @Override
    public int minSupport(double size, int length) {
        return Math.max(2, (int) Math.ceil(size / 4 + .55 * main(size) + .82 - 1e-9));
    }

    @Override
    public int maxSupport() {
        return 4;
    }

    /** The survey's 1.5 + 0.25 D. */
    @Override
    public int defaultSupport(double size, int length) {
        return Math.max(minSupport(size, length), Math.min(maxSupport(), (int) Math.round(1.5 + .25 * size)));
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "adsorber.diameter";
    }

    @Override
    public String lengthRow() {
        return "adsorber.beds";
    }

    @Override
    public String supportRow() {
        return "adsorber.skirt";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("adsorber.adsorbent", (int) shape.tangentLength(), number(Math.PI * d * d * bed(d) / 4));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        var p = Plan.of(d, (int) shape.tangentLength(), shape.support());
        return List.of(new Line("adsorber", size(d), number(p.bed), number(p.tt)), new Line("adsorber.platform", number(p.walk), number(p.yh)));
    }

    /** The adsorbent's depth, m: 2 D, 2.5 to 9 m (L/D over 2; beds over 10 m are rare [GPN, CECA]). */
    static double bed(double d) {
        return Math.max(2.5, Math.min(9, 2 * d));
    }

    /** The feed and product lines (and each bed's nozzles), m: 0.12 D, 0.1 to 0.6 (the survey's 0.10 D usual). */
    static double main(double d) {
        return PipeSizes.od(.12 * d, .1, .6);
    }

    /** The regeneration gas lines, m: 0.08 D, 0.1 to 0.4 (the survey's 0.06 D usual; about 10 % of the feed, hot). */
    static double regen(double d) {
        return PipeSizes.od(.08 * d, .1, .4);
    }

    /** An actuated ball valve's length flange to flange, m (ASME B16.10 long pattern, about 1.3 bores and the flanges). */
    static double valveLength(double od) {
        return .25 + 1.3 * od;
    }

    /**
     * A set's sizes, m, frame y up from the ground and z back from its front face: the bed's radius, the skirt's (the
     * shell's outside) and the cladding's, the bed's depth and the vessel's tangent-to-tangent length, the bottom and top
     * tangents, the platform's walking level (the top tangent), the openings' half side, the platform's walkway width
     * and half width, the beds' pitch, the first bed's axis, the platform's back edge, the back face, the feed/product and
     * regeneration line sizes, the top and bottom headers' offsets from the beds' axis line, the top headers' row and the
     * beds' axis line (frame x).
     */
    record Plan(double d, int beds, int support, double r, double rs, double rc, double bed, double tt, double bottom, double top, double walk, double hole,
                double width, double half, double pitch, double z0, double zb, double back, double main, double regen, double kt, double kb, double yh,
                double a) {
        static Plan of(double d, int beds, int support) {
            double r = d / 2, rs = r + .02, rc = rs + .1, bed = AdsorberDesign.bed(d), tt = bed + 1.2, bottom = support, top = bottom + tt;
            double hole = rc + .12, width = .9 + .05 * d, half = hole + width, pitch = d + 2;
            double z0 = FRONT + width + hole, zb = z0 + (beds - 1) * pitch + hole + width, back = Math.ceil(zb + 1);
            double main = AdsorberDesign.main(d), regen = AdsorberDesign.regen(d), rm = main / 2, ri = regen / 2 + LAGGING;
            // A header stands off the beds' axis line by the riser (or the skirt), the valve and the header's own radius,
            // with 0.1 m between them.
            double kt = Math.max(1, Math.ceil(rm + LAGGING + .1 + valveLength(main) + .1 + Math.max(rm, ri) - 1e-9));
            double kb = Math.max(2, Math.ceil(rs + .15 + valveLength(main) + .1 + Math.max(rm, ri) - 1e-9));
            // The top headers on the first cell row whose portal beams leave 2.1 m over the platform.
            double yh = Math.ceil(top + 2.35 + Math.max(rm, ri) - .5 - 1e-9) + .5;
            double a = Math.ceil(Math.max(half, kb + .5) - .5) + .5;
            return new Plan(d, beds, support, r, rs, rc, bed, tt, bottom, top, top, hole, width, half, pitch, z0, zb, back, main, regen, kt, kb, yh, a);
        }

        /** Bed {@code i}'s axis, frame z. */
        double z(int i) {
            return z0 + i * pitch;
        }

        /** The cladding's top head over radius {@code rho}. */
        double head(double rho) {
            double depth = d / 4 + .1;
            return top + depth * Math.sqrt(Math.max(0, 1 - rho * rho / (rc * rc)));
        }
    }

    private static final double[] UP = {0, 1, 0}, X = {1, 0, 0}, Z = {0, 0, 1}, MINUS_Z = {0, 0, -1};

    @Override
    void build(ProcessUnit u) {
        var p = Plan.of(u.shape.diameter(), (int) u.shape.tangentLength(), u.shape.support());
        for (int i = 0; i < p.beds; i++) {
            double z = p.z(i);
            bed(u, p, z);
            topLines(u, p, z);
            bottomLines(u, p, z);
            unloading(u, p, z);
            manway(u, p, z);
            brackets(u, p, z, i == 0, i == p.beds - 1);
        }
        deck(u, p);
        supports(u, p);
        headers(u, p);
    }

    /**
     * A bed's vessel: the concrete ring, the skirt with its base ring, anchor chairs (from 1.5 m) and access opening, the
     * bottom head inside it, the cladding over the shell and top head with its bands.
     */
    private static void bed(ProcessUnit u, Plan p, double z) {
        double a = p.a, d = p.d, rs = p.rs, rc = p.rc;
        int n = ProcessUnit.sides(2 * rc);
        var axis = new double[]{a, 0, z};
        var up = Frame.of(axis, 1, 1);
        u.mesh.cylinder(up, rs + .25, 0, PLINTH, n, Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, 0, PLINTH, rs + .25, 0));
        // The skirt on its base ring, the anchor chairs between its openings.
        u.mesh.prism(up, rs, PLINTH, p.bottom, n, Material.SUPPORT);
        u.mesh.prism(up, rs + .1, PLINTH, PLINTH + .03, n, Material.SUPPORT);
        u.mesh.annulus(up, rs, rs + .1, PLINTH + .03, n, 1, Material.SUPPORT);
        if (d >= 1.5 - 1e-9) {
            int chairs = 4 * Math.max(2, (int) Math.round(2 * Math.PI * rs / 4));
            for (int k = 0; k < chairs; k++) {
                double t = 2 * Math.PI * (k + .5) / chairs, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
                u.mesh.block(new double[]{a + (rs + .06) * c, PLINTH + .155, z + (rs + .06) * s}, new double[]{.05 * c, 0, .05 * s}, new double[]{0, .125, 0},
                        new double[]{-.09 * s, 0, .09 * c}, Material.SUPPORT);
            }
        }
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, PLINTH, p.bottom, rs, 0));
        u.round(a, z, PLINTH, p.bottom, rs, 1);
        double ro = Math.min(.3, .15 + .05 * d), yc = PLINTH + .25 + ro;
        var opening = Frame.of(new double[]{a, yc, z}, 2, -1);
        int no = ProcessUnit.sides(2 * ro + .1);
        u.mesh.ring(opening, ro, ro + .05, rs - .06, rs + .06, no, 2, Material.SUPPORT);
        u.mesh.annulus(opening, 0, ro, rs + .01, no, 1, Material.OPENING);
        // The bottom head inside the skirt closes the bed's inside; the bed encloses with both of its heads.
        u.mesh.head(Frame.of(axis, 1, -1), rs, -p.bottom, d / 4 + .02, n, Material.HEAD);
        // The cladding from the skirt's top, closed under, over the shell and the top head; a band every 1.5 m.
        u.mesh.annulus(up, rs, rc, p.bottom, n, -1, Material.CLADDING);
        u.mesh.prism(up, rc, p.bottom, p.top, n, Material.CLADDING);
        u.mesh.head(up, rc, p.top, d / 4 + .1, n, Material.CLADDING);
        for (double y = p.bottom + .75; y < p.top - .3; y += 1.5) u.mesh.prism(up, rc + .01, y, y + .05, n, Material.CLADDING);
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, p.bottom, p.top, rc, d / 4 + .1));
        u.volumes.add(ProcessUnit.Solid.rod(axis, 1, p.bottom, p.top, .94 * p.r, .9 * d / 4, .9 * d / 4));
        u.round(a, z, p.bottom, p.top, rc, 1);
    }

    /**
     * A bed's top line: its nozzle on the head's apex, the flange pair, the lagged riser to the top headers' row, and
     * from its tee the bare feed line left and the lagged regeneration outlet right, each through its valve.
     */
    private static void topLines(ProcessUnit u, Plan p, double z) {
        double a = p.a, rm = p.main / 2, rr = rm + LAGGING, apex = p.head(0), y = p.yh;
        nozzle(u, new double[]{a, 0, z}, 1, apex, p.main);
        u.pipe(rr, Material.CLADDING, true, new double[]{a, apex + .27, z}, new double[]{a, y, z});
        u.mesh.sphere(new double[]{a, y, z}, 1.03 * rr, ProcessUnit.sides(2 * rr), Material.CLADDING);
        branch(u, a, y, z, 1, p.kt, rr, p.main, rm, Material.PIPE);
        branch(u, a, y, z, -1, p.kt, rr, p.regen, p.regen / 2 + LAGGING, Material.CLADDING);
    }

    /**
     * A bed's bottom line: its nozzle under the bottom head's apex, the flange pair, the drop inside the skirt to the
     * bottom row, and from its elbow the bare product line left and the lagged regeneration inlet right, out through
     * sleeves in the skirt and each through its valve.
     */
    private static void bottomLines(ProcessUnit u, Plan p, double z) {
        double a = p.a, rm = p.main / 2, ri = p.regen / 2 + LAGGING, apex = p.bottom - p.d / 4 - .02, y = BOTTOM_ROW;
        nozzle(u, new double[]{a, 0, z}, -1, apex, p.main);
        u.pipe(rm, Material.PIPE, true, new double[]{a, y, z}, new double[]{a, apex - .27, z});
        u.mesh.sphere(new double[]{a, y, z}, 1.03 * rm, ProcessUnit.sides(2 * rm), Material.PIPE);
        branch(u, a, y, z, 1, p.kb, p.rs + .05, p.main, rm, Material.PIPE);
        branch(u, a, y, z, -1, p.kb, p.rs + .05, p.regen, ri, Material.CLADDING);
        for (int sx = -1; sx <= 1; sx += 2) {
            double r = sx > 0 ? rm : ri;
            u.mesh.ring(Frame.of(new double[]{a, y, z}, 0, sx), r + .02, r + .07, p.rs - .04, p.rs + .06, ProcessUnit.sides(2 * r + .14), 3, Material.SUPPORT);
        }
    }

    /** A head's nozzle on the vertical through {@code axis} (its y unused), out from {@code apex} along y {@code sign}: neck, flange pair and bolts, 0.27 m. */
    private static void nozzle(ProcessUnit u, double[] axis, int sign, double apex, double od) {
        double r = od / 2, fl = PipeSizes.flange(od) / 2;
        var f = Frame.of(axis, 1, sign);
        int n = ProcessUnit.sides(2 * fl);
        double at = sign * apex;
        u.mesh.prism(f, r, at - .1, at + .15, n, Material.PIPE);
        u.mesh.cylinder(f, fl, at + .15, at + .21, n, Material.FLANGE);
        u.mesh.cylinder(f, fl, at + .21, at + .27, n, Material.FLANGE);
        u.mesh.bolts(f, od, r + .01, fl, at + .27, 1, Material.BOLT);
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, Math.min(apex - .1 * sign, apex + .27 * sign), Math.max(apex - .1 * sign, apex + .27 * sign), fl, 0));
    }

    /**
     * A line from its tee at (a, y, z) out along x (side {@code sx}) to the header {@code k} away: its pipe ({@code r} out,
     * {@code material}, lagged or bare) and its valve of {@code od} half way between {@code clear} and the header.
     */
    private static void branch(ProcessUnit u, double a, double y, double z, int sx, double k, double clear, double od, double r, Material material) {
        double lv = valveLength(od), mid = (clear + .1 + k - r - .1) / 2;
        u.pipe(r, material, true, new double[]{a, y, z}, new double[]{a + sx * (mid - lv / 2), y, z});
        valve(u, new double[]{a + sx * mid, y, z}, 0, od, lv);
        u.pipe(r, material, true, new double[]{a + sx * (mid + lv / 2), y, z}, new double[]{a + sx * k, y, z});
    }

    /**
     * An actuated ball valve (the survey's switching valves; detail cut to a box and a cylinder) on a line of {@code od}
     * along frame axis {@code axis} (0 or 2), centred at c, {@code length} flange to flange: its flanges, its body, the
     * yoke and the rack-and-pinion actuator on top with its position indicator.
     */
    private static void valve(ProcessUnit u, double[] c, int axis, double od, double length) {
        double fl = PipeSizes.flange(od) / 2, body = .6 * od + .05, half = length / 2, at = c[axis];
        double[] origin = c.clone();
        origin[axis] = 0;
        var f = Frame.of(origin, axis, 1);
        int n = ProcessUnit.sides(2 * fl);
        u.mesh.cylinder(f, fl, at - half, at - half + .06, n, Material.FLANGE);
        u.mesh.cylinder(f, fl, at + half - .06, at + half, n, Material.FLANGE);
        u.mesh.cylinder(f, body, at - half + .06, at + half - .06, n, Material.MACHINE);
        u.mesh.cylinder(f, 1.15 * body, at - .35 * half, at + .35 * half, n, Material.MACHINE);
        double top = c[1] + 1.15 * body, yoke = .08 + .15 * od, box = .12 + .3 * od, y0 = top + yoke, y1 = y0 + .8 * box;
        var vertical = Frame.of(new double[]{c[0], 0, c[2]}, 1, 1);
        u.mesh.cylinder(vertical, .3 * body + .02, top - .02, y0, 10, Material.MACHINE);
        double along = .8 * box, across = .45 * box;
        double ex = axis == 0 ? along : across, ez = axis == 0 ? across : along;
        u.box(new double[]{c[0] - ex, y0, c[2] - ez}, new double[]{c[0] + ex, y1, c[2] + ez}, Material.ACTUATOR, true);
        u.mesh.cylinder(vertical, .25 * box, y1, y1 + .05 + .05 * box, 10, Material.MACHINE);
        u.solids.add(ProcessUnit.Solid.rod(origin, axis, at - half, at + half, fl, 0));
    }

    /**
     * The adsorbent's unloading nozzle (DN 100 to 300) out of the bottom head at 45° towards the back, through the skirt,
     * under a blind flange: as near the axis as keeps its flange over the concrete ring.
     */
    private static void unloading(ProcessUnit u, Plan p, double z) {
        double od = PipeSizes.od(.06 * p.d, .1, .3), ru = od / 2, fl = PipeSizes.flange(od) / 2, depth = p.d / 4 + .02, s = Math.sqrt(.5);
        for (double k = .6; k < .85; k += .1) {
            double rho = k * p.r, y0 = p.bottom - depth * Math.sqrt(1 - k * k), out = p.rs - rho + .25;
            if (y0 - out - fl * s < PLINTH + .15) continue;
            var f = new Frame(new double[]{p.a, y0, z + rho}, new double[]{0, -s, s}, X);
            double length = out * Math.sqrt(2);
            int n = ProcessUnit.sides(2 * fl);
            u.mesh.prism(f, ru, -.05, length, n, Material.PIPE);
            u.mesh.cylinder(f, fl, length, length + .06, n, Material.FLANGE);
            u.mesh.cylinder(f, fl, length + .06, length + .12, n, Material.FLANGE);
            u.mesh.bolts(f, od, ru + .01, fl, length + .12, 1, Material.BOLT);
            double[] c = f.along(length + .06);
            u.solids.add(ProcessUnit.Solid.box(new double[]{c[0] - fl, c[1] - fl, c[2] - fl}, new double[]{c[0] + fl, c[1] + fl, c[2] + fl}));
            return;
        }
    }

    /**
     * The loading manway on the top head towards the front left, sized from the shared access catalog where it fits
     * beside the top nozzle. A fitted opening below 500 mm is an inspection hatch; none under 0.25 m. Its neck, flange
     * and bolted cover, and the davit at its front-right edge
     * with its arm over the cover.
     */
    private static void manway(ProcessUnit u, Plan p, double z) {
        double rc = p.rc, riser = PipeSizes.flange(p.main) / 2, s = Math.sqrt(.5);
        double size = Manway.preferredSize(p.d), cover = 0, rho = 0;
        for (; size >= .25 - 1e-9; size -= .05) {
            cover = .6 * size + .01;
            rho = riser + .06 + cover;
            if (rho + cover <= .9 * rc) break;
        }
        if (size < .25 - 1e-9) return;
        double mx = p.a + rho * s, mz = z - rho * s, surface = p.head(rho), face = surface + .3;
        var f = Frame.of(new double[]{mx, 0, mz}, 1, 1);
        int n = ProcessUnit.sides(2 * cover);
        u.mesh.prism(f, size / 2, surface - .1, face - .06, n, Material.SHELL);
        u.mesh.cylinder(f, cover, face - .06, face, n, Material.FLANGE);
        u.mesh.cylinder(f, cover, face, face + .06, n, Material.FLANGE);
        u.mesh.bolts(f, size, size / 2 + .02, cover, face + .06, 1, Material.BOLT);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{mx, 0, mz}, 1, surface - .1, face + .06, cover, 0));
        double px = mx - (cover + .08) * s, pz = mz - (cover + .08) * s, pr = Math.sqrt((px - p.a) * (px - p.a) + (pz - z) * (pz - z));
        if (pr > .9 * rc) return;
        double top = face + .7 + .2 * size, post = .03 + .005 * p.d;
        u.mesh.cylinder(Frame.of(new double[]{px, 0, pz}, 1, 1), post, p.head(pr) - .05, top, 8, Material.PIPE);
        u.mesh.bar(new double[]{px, top - .03, pz}, new double[]{mx, top - .03, mz}, .05, UP, Material.PIPE);
        u.mesh.bar(new double[]{mx, top - .05, mz}, new double[]{mx, top - .3, mz}, .025, X, Material.PIPE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{px, 0, pz}, 1, p.head(pr), top, post, 0));
    }

    /**
     * The platform's brackets off a bed's cladding: a beam out under the deck's edge beams on its left and right (and to
     * the front on the first bed, to the back on the last), each with a strut down to a pad on the cladding.
     */
    private static void brackets(ProcessUnit u, Plan p, double z, boolean first, boolean last) {
        double a = p.a, level = p.walk - Access.DECK, lo = level - beam(p.d), drop = .8 + .15 * p.d, reach = p.half - .15;
        for (int k = 0; k < 4; k++) {
            int dx = k == 0 ? 1 : k == 1 ? -1 : 0, dz = k == 2 ? -1 : k == 3 ? 1 : 0;
            if (dz < 0 && !first || dz > 0 && !last) continue;
            double[] near = {a + dx * (p.rc - .02), 0, z + dz * (p.rc - .02)}, far = {a + dx * reach, 0, z + dz * reach};
            double[] across = {dz * .06, 0, dx * .06};
            u.box(new double[]{Math.min(near[0], far[0]) - Math.abs(across[0]), lo, Math.min(near[2], far[2]) - Math.abs(across[2])},
                    new double[]{Math.max(near[0], far[0]) + Math.abs(across[0]), level, Math.max(near[2], far[2]) + Math.abs(across[2])}, Material.STEELWORK, true);
            double foot = lo - drop, toe = p.rc + .7 * (reach - p.rc);
            u.mesh.bar(new double[]{a + dx * p.rc, foot, z + dz * p.rc}, new double[]{a + dx * toe, lo, z + dz * toe}, .1, new double[]{dz, 0, dx}, Material.STEELWORK);
            u.mesh.block(new double[]{a + dx * (p.rc + .01), foot + .1, z + dz * (p.rc + .01)}, new double[]{dx * .02, 0, dz * .02}, new double[]{0, .18, 0},
                    new double[]{dz * .1, 0, dx * .1}, Material.STEELWORK);
        }
    }

    /** The platform's beams' depth, m. */
    private static double beam(double d) {
        return .2 + .02 * d;
    }

    /**
     * The platform at the top tangent ({@link ProcessUnit#squareDeck}): grating on its beams round each bed's square
     * opening, filled to the cladding, railed round its edges but where the ladder lands at the back, the pieces between
     * the beds without rails; its frame of beams along its edges and the openings' sides, and the caged ladder up its back
     * ({@link #climb}).
     */
    private static void deck(ProcessUnit u, Plan p) {
        double a = p.a, h = p.hole, half = p.half, level = p.walk - Access.DECK;
        var centres = new double[p.beds];
        for (int i = 0; i < p.beds; i++) centres[i] = p.z(i);
        u.squareDeck(a - half, a + half, FRONT, p.zb, level, 2, a, h, centres, p.rc + .04, 32, Access.RAILED, new double[][]{null, null, {a - Access.WALK_THROUGH, a + Access.WALK_THROUGH}, null}, null);
        climb(u, p);
        double lo = level - beam(p.d), fb = ProcessUnit.FLANGE_BEAM / 2;
        for (double off : new double[]{h + fb, half - fb})
            for (int s = -1; s <= 1; s += 2) u.box(new double[]{a + s * off - fb, lo, FRONT}, new double[]{a + s * off + fb, level, p.zb}, Material.STEELWORK, true);
        var ends = new ArrayList<Double>(List.of(FRONT + fb, p.zb - fb));
        for (int i = 0; i < p.beds; i++) {
            ends.add(p.z(i) - h - fb);
            ends.add(p.z(i) + h + fb);
        }
        for (double zz : ends) u.box(new double[]{a - half, lo, zz - fb}, new double[]{a + half, level, zz + fb}, Material.STEELWORK, true);
    }

    /**
     * The caged ladder up the platform's back, walked through onto it at the opening in its rail ({@link ProcessUnit#climb}):
     * where it is longer than {@link Access#MAX_CLIMB} (the platform stands under 15 m: one rest at most), its rest
     * platform behind the back edge from 0.6 m right of the upper section to 0.6 m left of the lower one, 1 m to the left,
     * on brackets off a beam between two posts, which rise from the ground to that edge's beam.
     */
    private static void climb(ProcessUnit u, Plan p) {
        double a = p.a, z = p.zb, level = p.walk - Access.DECK, offset = 1, x0 = a - .6, x1 = a + offset + .6, post = ProcessUnit.FLANGE_BEAM;
        for (double rest : u.climb(new Access.Line(new double[]{x0, 0, z}, X, Z, x1 - x0), 0, p.walk, offset + .6, .6, 0)) {
            for (double x : new double[]{x0 + post / 2, x1 - post / 2}) u.post(x, z - post / 2, 0, level - beam(p.d), post, false);
            u.beam(new double[]{x0 + post, rest - beam(p.d), z - post}, new double[]{x1 - post, rest, z});
        }
    }

    /**
     * The headers' supports at the platform's ends and between the beds: portal frames on the platform's edge beams under
     * the top headers (their beams 2.1 m over the walkway), stanchions on base plates under the bottom headers, a shoe
     * under each header.
     */
    private static void supports(ProcessUnit u, Plan p) {
        double a = p.a, level = p.walk - Access.DECK, rm = p.main / 2, ri = p.regen / 2 + LAGGING, yp = p.yh - Math.max(rm, ri) - .03, post = .15;
        var stations = new ArrayList<Double>(List.of(FRONT + .1, p.zb - .1));
        for (int i = 0; i + 1 < p.beds; i++) stations.add(p.z(i) + p.pitch / 2);
        for (double zz : stations) {
            for (int s = -1; s <= 1; s += 2) {
                double x = a + s * (p.half - ProcessUnit.FLANGE_BEAM / 2);
                u.box(new double[]{x - post / 2, level, zz - post / 2}, new double[]{x + post / 2, yp - .2, zz + post / 2}, Material.STEELWORK, true);
            }
            u.box(new double[]{a - p.half, yp - .2, zz - .075}, new double[]{a + p.half, yp, zz + .075}, Material.STEELWORK, true);
            for (int s = -1; s <= 1; s += 2) {
                double xt = a + s * p.kt, xb = a + s * p.kb, rt = s > 0 ? rm : ri;
                u.box(new double[]{xt - .08, yp, zz - .1}, new double[]{xt + .08, p.yh - rt + .01, zz + .1}, Material.STEELWORK, false);
                double foot = BOTTOM_ROW - rt;
                u.post(xb, zz, 0, foot - .03, .12, true);
                u.box(new double[]{xb - .1, foot - .03, zz - .1}, new double[]{xb + .1, foot + .01, zz + .1}, Material.STEELWORK, false);
            }
        }
    }

    /**
     * The four headers along the row and their end nozzles, each face on a block face END beyond the header's end: the
     * feed header (top left, bare) with FEED at the front and a blind flange at the back; the regeneration outlet header
     * (top right, lagged) with VENT through a reducer at the front and REGENERATION_OUTLET at the back; the product header
     * (bottom left, bare) with PRODUCT at the front and DRAIN through a reducer at the back; the regeneration inlet header
     * (bottom right, lagged) with NITROGEN through a reducer at the front and REGENERATION_INLET at the back.
     */
    private static void headers(ProcessUnit u, Plan p) {
        double d = p.d, rm = p.main / 2, rg = p.regen / 2, e0 = END, e1 = p.back - END, yh = p.yh, yb = BOTTOM_ROW;
        double xa = p.a + p.kt, xb = p.a - p.kt, xc = p.a + p.kb, xd = p.a - p.kb;
        double usualMain = PipeSizes.od(.1 * d, .05, p.main), usualRegen = PipeSizes.od(.06 * d, .05, p.regen);
        double vent = PipeSizes.od(.05 * d, .05, .25), usualVent = PipeSizes.od(.04 * d, .05, vent);
        // Feed.
        u.pipe(rm, Material.PIPE, true, new double[]{xa, yh, e0}, new double[]{xa, yh, e1 - .12});
        cap(u, xa, yh, e0, rm, -1);
        var blind = Frame.of(new double[]{xa, yh, 0}, 2, 1);
        double fl = PipeSizes.flange(p.main) / 2;
        int nf = ProcessUnit.sides(2 * fl);
        u.mesh.cylinder(blind, fl, e1 - .12, e1 - .06, nf, Material.FLANGE);
        u.mesh.cylinder(blind, fl, e1 - .06, e1, nf, Material.FLANGE);
        u.mesh.bolts(blind, p.main, rm + .01, fl, e1, 1, Material.BOLT);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{xa, yh, 0}, 2, e1 - .12, e1, fl, 0));
        u.nozzles.add(new Nozzle(Role.FEED, new double[]{xa, yh, e0 + .03}, 2, -1, e0 + .03, usualMain, 0));
        // Regeneration outlet and vent.
        lagged(u, xb, yh, e0 + .35, e1 - .1, rg);
        u.reducer(new double[]{xb, yh, e0 + .35}, MINUS_Z, rg, vent / 2, .25, PipeSizes.sides(Math.max(2 * rg, vent)), Material.PIPE, true);
        u.pipe(vent / 2, Material.PIPE, true, new double[]{xb, yh, e0}, new double[]{xb, yh, e0 + .1});
        cap(u, xb, yh, e0, vent / 2, -1);
        u.pipe(rg, Material.PIPE, true, new double[]{xb, yh, e1 - .1}, new double[]{xb, yh, e1});
        cap(u, xb, yh, e1, rg, 1);
        u.nozzles.add(new Nozzle(Role.VENT, new double[]{xb, yh, e0 + .03}, 2, -1, e0 + .03, usualVent, 0));
        u.nozzles.add(new Nozzle(Role.REGENERATION_OUTLET, new double[]{xb, yh, e1 - .03}, 2, 1, END + .03, usualRegen, 0));
        // Product and drain.
        u.pipe(rm, Material.PIPE, true, new double[]{xc, yb, e0}, new double[]{xc, yb, e1 - .35});
        cap(u, xc, yb, e0, rm, -1);
        u.reducer(new double[]{xc, yb, e1 - .35}, Z, rm, .05, .25, PipeSizes.sides(2 * rm), Material.PIPE, true);
        u.pipe(.05, Material.PIPE, true, new double[]{xc, yb, e1 - .1}, new double[]{xc, yb, e1});
        cap(u, xc, yb, e1, .05, 1);
        u.nozzles.add(new Nozzle(Role.PRODUCT, new double[]{xc, yb, e0 + .03}, 2, -1, e0 + .03, usualMain, 0));
        u.nozzles.add(new Nozzle(Role.DRAIN, new double[]{xc, yb, e1 - .03}, 2, 1, END + .03, .05, 0));
        // Regeneration inlet and nitrogen.
        lagged(u, xd, yb, e0 + .35, e1 - .1, rg);
        u.reducer(new double[]{xd, yb, e0 + .35}, MINUS_Z, rg, .05, .25, PipeSizes.sides(2 * rg), Material.PIPE, true);
        u.pipe(.05, Material.PIPE, true, new double[]{xd, yb, e0}, new double[]{xd, yb, e0 + .1});
        cap(u, xd, yb, e0, .05, -1);
        u.pipe(rg, Material.PIPE, true, new double[]{xd, yb, e1 - .1}, new double[]{xd, yb, e1});
        cap(u, xd, yb, e1, rg, 1);
        u.nozzles.add(new Nozzle(Role.NITROGEN, new double[]{xd, yb, e0 + .03}, 2, -1, e0 + .03, .05, 0));
        u.nozzles.add(new Nozzle(Role.REGENERATION_INLET, new double[]{xd, yb, e1 - .03}, 2, 1, END + .03, usualRegen, 0));
    }

    /** A header's end face at z, facing {@code sign} along z, with the pipe's facets ({@link PipeSizes#sides}, as the kit's {@link ProcessUnit#pipe}). */
    private static void cap(ProcessUnit u, double x, double y, double z, double r, int sign) {
        u.mesh.annulus(Frame.of(new double[]{x, y, 0}, 2, 1), 0, r, z, PipeSizes.sides(2 * r), sign, Material.PIPE);
    }

    /** A lagged header along z from z0 to z1 (its pipe of radius r under the lagging), its ends closed; with a pipe's facets. */
    private static void lagged(ProcessUnit u, double x, double y, double z0, double z1, double r) {
        double ri = r + LAGGING;
        var f = Frame.of(new double[]{x, y, 0}, 2, 1);
        int n = PipeSizes.sides(2 * ri);
        u.mesh.prism(f, ri, z0, z1, n, Material.CLADDING);
        u.mesh.annulus(f, r, ri, z0, n, -1, Material.CLADDING);
        u.mesh.annulus(f, r, ri, z1, n, 1, Material.CLADDING);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{x, y, 0}, 2, z0, z1, ri, 0));
    }
}
