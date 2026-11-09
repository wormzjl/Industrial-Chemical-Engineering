package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * Draws an FCC converter ({@link FccConverterDesign}) from its {@link FccConverterLayout} ({@code NEW_UNITS_SURVEY_FCC_COKER.md}
 * section 1; owner decisions D3 and D4 of 2026-10-18): a side-by-side UOP/Shell converter with a single-stage regenerator.
 * <ul>
 * <li>The regenerator: a squat shell with 2:1 heads on a concrete table of four columns on its diagonals, steel lugs at
 * its bottom tangent, tie beams front and back; two ring platforms (the lower 2 m over the bottom tangent, the upper at
 * about 0.45 of the shell) with a manway each, reached by caged ladders on the -x side between the table's columns, offset
 * at a rest platform wherever a climb would pass 9 m ({@link #rests}); the air line rising out of the start-up air heater
 * in front and turning up into the bottom head, the flue gas line out of the crown.</li>
 * <li>The riser on its stand with bellows, the feed injectors pointing 30° up into it in a ring with their feed and
 * atomising steam ring headers, open towards the regenerated-catalyst standpipe; the stripper over it with its steam ring,
 * the 30° cone and the reactor (disengager) with its manway, the vapour line out of its crown.</li>
 * <li>Both standpipes with their slide valves (body, bonnet, stem and hydraulic actuator towards the front, where the
 * operator stands).</li>
 * <li>The steel frame round the riser and reactor: columns fireproofed to the first floor, a beam under every deck's edges,
 * struts at most 8 m apart between floors more than 9 m apart, X-bracing on the front and back faces over the spent-catalyst valve's floor,
 * the -x face open in the middle where the standpipes pass. Floors at the feed injectors, both slide valves, the stripper's
 * steam ring, the reactor's manway and its top: round the stripper and reactor a square deck to the frame's faces, its
 * opening's corners filled in to the vessel ({@link ProcessUnit#squareDeck}); where a standpipe passes, decks round three
 * sides of the riser's opening, open to the -x face and railed along it.</li>
 * <li>The stair tower on the +x face ({@link ProcessUnit#stairTower}): two lanes of 0.9 m flights, at most 3.6 m a flight,
 * landings 1.2 m deep at both ends on corner posts and beams, opening onto every floor through its +x handrail.</li>
 * </ul>
 * Lines wider than a pipe's largest (air, flue gas, vapour; {@link PipeSizes#MAX_OUTSIDE}) are drawn at their size and end
 * in a 15° concentric reducer to a nozzle of that size. The layout's x and z are shifted half a block ({@link #O}) so both axes run through cell centres.
 */
final class FccConverterBuilder {
    private static final double[] UP = {0, 1, 0}, DOWN = {0, -1, 0}, PLUS_X = {1, 0, 0}, MINUS_X = {-1, 0, 0}, PLUS_Z = {0, 0, 1}, MINUS_Z = {0, 0, -1};
    /** The layout's origin (the regenerator's axis) on a cell's centre. */
    private static final double O = .5;
    /** A floor beam's depth and width, a strut's depth, a column's section, m. */
    static final double BEAM = .4, BEAM_WIDTH = .3, STRUT = .3, COLUMN = .35;
    /** The feed injectors' slope, 30° up into the riser. */
    private static final double TAN30 = 1 / Math.sqrt(3), COS30 = Math.sqrt(3) / 2;
    /** A vessel's inside kept this far within its surface, m (less than half a block: see {@link #inside}). */
    private static final double INSIDE = .3;
    /** The regenerator's ladders' centres at a rest, m round their rung plane: the arriving cage 0.5 m from the next ladder's. */
    private static final double OFFSET = 1.2;
    /** The regenerator's manways' angle (from +x towards +z): at the front of the -x side, clear of its ladders. */
    private static final double MANWAY = 1.25 * Math.PI;

    /** A floor of the frame: its walking surface, its opening's half size, its decks' -x end, whether a standpipe passes it (open to the -x face), the radius a closed floor's opening is filled in to round its vessel. */
    private record Floor(double surface, double hole, double from, boolean open, double fill) {
        double level() {
            return surface - Access.DECK;
        }
    }

    private final ProcessUnit u;
    private final FccConverterLayout l;
    private final Mesh m;

    FccConverterBuilder(ProcessUnit u, FccConverterLayout l) {
        this.u = u;
        this.l = l;
        m = u.mesh;
    }

    void build() {
        regenerator();
        table();
        air();
        reactor();
        riser();
        injectors();
        standpipes();
        structure();
        lines();
    }

    // ---- Regenerator --------------------------------------------------------------------------------------------------

    /** The regenerator's shell and heads, its ring platforms, ladders and manways, its catalyst nozzles. */
    private void regenerator() {
        double r = l.r, y0 = l.support, y1 = y0 + l.straight;
        int n = vesselSides(l.d);
        var axis = p(0, 0, 0);
        var up = Frame.of(axis, 1, 1);
        m.prism(up, r, y0, y1, n, Material.SHELL);
        m.head(up, r, y1, l.head, n, bands(n), Material.HEAD);
        m.head(Frame.of(axis, 1, -1), r, -y0, l.head, n, bands(n), Material.HEAD);
        u.solids.add(Solid.rod(axis, 1, y0, y1, r, l.head, l.head));
        inside(axis, r, n, y0, y1, l.head, l.head);
        u.round(axis, 1, y0, y1, r);
        // Ring platforms round the half away from the frame (back, -x, front): the lower one's walking level 2 m over the
        // bottom tangent, the upper one's at about 0.45 of the shell; a manway 0.9 m over each.
        int low = l.support + 2, high = (int) Math.round(y0 + .45 * l.straight);
        rests(r + .1, List.of(low, high), Math.PI / 2 + .35, 1.5 * Math.PI - .35);
        manway(0, 0, r, MANWAY, low + .9, n);
        manway(0, 0, r, MANWAY, high + .9, n);
        // Fresh catalyst in at the front over the bed; equilibrium catalyst out at the back just over the bottom tangent.
        u.nozzles.add(ProcessUnit.radialSide(Role.CATALYST_INLET, O, y0 + 4.5, O, r, n, -1, .1));
        u.nozzles.add(ProcessUnit.radialSide(Role.CATALYST_OUTLET, O, y0 + .5, O, r, n, 1, .15));
    }

    /**
     * The regenerator's ring platforms ({@link ProcessUnit#ringAccess}) round its wall, {@code wall} from the axis: from
     * the ground to each walking level of {@code wanted} (whole metres, rising) a ring from {@code from} to {@code to}, and
     * a rest platform wherever a caged ladder would climb more than {@link Access#MAX_CLIMB} ({@link Access#climbs}; owner
     * decision C1 2026-10-18). The ladders alternate between two angles {@link #OFFSET} apart round their rung plane, so
     * each section is offset from the next at its rest (the old OSHA 1910.27(d)(2)); the first, from the ground, and a rest
     * over it lie centred between the table's -x columns. A rest is a short arc from the ladder arriving on it to just past
     * the next one; under the shell's bottom tangent, with no wall to bracket to, it stands on a post under each bracket.
     */
    private void rests(double wall, List<Integer> wanted, double from, double to) {
        double rung = wall + Access.STANDOFF, clear = Access.clearance(wall), offset = OFFSET / rung;
        // The span from the rest's far end to the first ladder's cage, centred on -x.
        double a = Math.PI + (offset + clear - DeterministicMath.atan2(Access.CAGE_HALF, rung)) / 2, b = a - offset;
        var levels = Access.climbs(0, wanted, wanted.getLast());
        var rings = new ArrayList<ProcessUnit.Ring>();
        for (int k = 0; k < levels.size(); k++) {
            double in = k % 2 == 0 ? a : b, next = k % 2 == 0 ? b : a, level = levels.get(k) - Access.DECK;
            rings.add(wanted.contains(levels.get(k)) ? new ProcessUnit.Ring(level, in, from, to)
                    : new ProcessUnit.Ring(level, in, Math.min(in, next - clear), Math.max(in, next + clear)));
        }
        var arcs = u.ringAccess(O, O, wall, Access.MIN_DECK, 0, rings);
        for (int k = 0; k < rings.size(); k++)
            if (levels.get(k) < l.support && !wanted.contains(levels.get(k)))
                for (var arc : arcs.get(k))
                    for (double s : Access.posts(arc.length())) {
                        var foot = arc.at(s, .1, 0);
                        u.post(foot[0], foot[2], 0, rings.get(k).level(), .15, false);
                    }
    }

    /** A manway on a round shell (axis at local (x, z), radius {@code r}) at angle {@code t}, centred at {@code y}: neck, cover and hinge. */
    private void manway(double x, double z, double r, double t, double y, int n) {
        double c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
        double[] out = {c, 0, s};
        var f = new Frame(p(x + (r - .1) * c, y, z + (r - .1) * s), out, UP);
        double size = Manway.preferredSize(2 * r), cover = Manway.COVER_RATIO * size / 2;
        m.prism(f, size / 2, 0, .45, 12, Material.SHELL);
        m.cylinder(f, cover, .45, .53, 12, Material.FLANGE);
        m.bar(f.at(Math.PI / 2, .875 * cover, .5), f.at(Math.PI / 2, .875 * cover, .7), .05, UP, Material.STEELWORK);
    }

    /**
     * A standing vessel's inside (axis at frame point {@code axis}, radius {@code r} to its {@code n} vertices) from its
     * bottom tangent {@code y0} (a 2:1 head {@code low} deep under it, 0: none) to its top tangent {@code y1} (a head
     * {@code high} deep over it), {@link #INSIDE} within its surface: every cell whose centre the surface does not come
     * within half a block of is inside it, so the inside's cells are enclosed by the cells the surface passes through.
     */
    private void inside(double[] axis, double r, int n, double y0, double y1, double low, double high) {
        double ri = r * DeterministicMath.cos(Math.PI / n) - INSIDE;
        if (ri >= .3) u.volumes.add(Solid.rod(axis, 1, y0, y1, ri, Math.max(0, low - INSIDE), Math.max(0, high - INSIDE)));
    }

    /** A vessel's 2:1 head's bands ({@link Mesh#head}): a sixth of its sides, four at least (a large head keeps its outline in a third fewer quads). */
    private static int bands(int n) {
        return Math.max(4, n / 6);
    }

    // ---- Concrete table -----------------------------------------------------------------------------------------------

    /**
     * The regenerator's table: four square concrete columns on its diagonals just outside the shell, a steel lug on each
     * under the bottom tangent, tie beams front and back between the columns (the air line passes under the front one,
     * the standpipes over the +x side, the ladders at -x).
     */
    private void table() {
        double c = l.tableColumn, k = Math.sqrt(.5) * l.tableRadius, top = l.support - .3, y1 = l.support + .5;
        for (int i = 0; i < 4; i++) {
            double sx = i == 0 || i == 3 ? 1 : -1, sz = i < 2 ? 1 : -1, cx = sx * k, cz = sz * k;
            u.box(p(cx - c / 2, 0, cz - c / 2), p(cx + c / 2, top, cz + c / 2), ALL_FACES & ~4, Material.CONCRETE, true);
            double[] radial = {sx * Math.sqrt(.5), 0, sz * Math.sqrt(.5)}, across = {-radial[2], 0, radial[0]};
            double inner = l.r - .05, outer = l.tableRadius + .3 * c;
            var mid = p((inner + outer) / 2 * radial[0], (top + y1) / 2, (inner + outer) / 2 * radial[2]);
            m.block(mid, scale(radial, (outer - inner) / 2), new double[]{0, (y1 - top) / 2, 0}, scale(across, .06), Material.STEELWORK);
            m.block(p(cx, top + .03, cz), scale(radial, c * .4), new double[]{0, .03, 0}, scale(across, c * .35), Material.STEELWORK);
            for (int side = -1; side <= 1; side += 2)
                m.block(plus(mid, scale(across, side * .25)), scale(radial, (outer - inner) / 2), new double[]{0, (y1 - top) / 2, 0}, scale(across, .03), Material.STEELWORK);
            u.solids.add(ProcessUnit.span(p(inner * radial[0] - .3 * across[0], top, inner * radial[2] - .3 * across[2]), p(outer * radial[0] + .3 * across[0], y1, outer * radial[2] + .3 * across[2])));
        }
        double depth = .5 + .02 * l.d, wide = .4 + .02 * l.d;
        double y = Math.max(.5 * l.support, l.airOut + l.air / 2 + .45 + depth / 2);
        for (int sz = -1; sz <= 1; sz += 2)
            u.box(p(-k + c / 2, y - depth / 2, sz * k - wide / 2), p(k - c / 2, y + depth / 2, sz * k + wide / 2), ALL_FACES & ~3, Material.CONCRETE, true);
    }

    // ---- Air -----------------------------------------------------------------------------------------------------------

    /**
     * The start-up air heater in front of the regenerator (a vertical refractory-lined shell on four legs, its burner on
     * the front near the top) and the air line: from the blower's nozzle at the front, up beside the heater, into its upper
     * side, out of its lower back, along +z under the regenerator and up into the bottom head through its bellows.
     */
    private void air() {
        double a = l.air, ra = a / 2, hz = l.heaterZ, hr = l.heater / 2, yb = l.heaterBottom, yt = l.heaterTop, ax = l.airRiserX;
        int nh = Math.max(16, 4 * (int) Math.ceil(.4 * l.heater + 2)), np = lineSides(a);
        var axis = p(0, 0, hz);
        var up = Frame.of(axis, 1, 1);
        m.prism(up, hr, yb, yt, nh, Material.SHELL);
        m.head(up, hr, yt, hr / 2, nh, bands(nh), Material.HEAD);
        m.head(Frame.of(axis, 1, -1), hr, -yb, hr / 2, nh, bands(nh), Material.HEAD);
        u.solids.add(Solid.rod(axis, 1, yb, yt, hr, hr / 2, hr / 2));
        inside(axis, hr, nh, yb, yt, hr / 2, hr / 2);
        u.round(axis, 1, yb, yt, hr);
        for (int i = 0; i < 4; i++) {
            double sx = i == 0 || i == 3 ? 1 : -1, sz = i < 2 ? 1 : -1;
            u.post(sx * .5 * hr + O, hz + sz * .5 * hr + O, 0, yb, .2, false);
        }
        // The burner box and the fuel gas nozzle under it on the front.
        double box = Math.min(.4, .25 * hr + .15), yFuel = Math.floor(yt - .6) + .5, zBox = hz - hr - .45;
        u.box(p(-box, yFuel - box, zBox), p(box, yFuel + box, hz - hr + .1), Material.MACHINE, true);
        u.nozzles.add(ProcessUnit.front(Role.FUEL, O, yFuel, zBox + O, .1));
        // The air riser beside the heater: its nozzle low at the front, an elbow up, an elbow into the heater's side.
        double yLow = Math.ceil(ra - .35 - 1e-9) + .5, r1 = -ax - hr, r2 = a;
        var top = u.elbow(p(ax, l.airIn - r1, hz), UP, PLUS_X, r1, ra, np, pieces(r1), Material.PIPE);
        u.line(ra, np, Material.PIPE, top, p(-hr + .2, l.airIn, hz));
        u.line(ra, np, Material.PIPE, p(ax, yLow + r2, hz), p(ax, l.airIn - r1, hz));
        var low = u.elbow(p(ax, yLow + r2, hz), DOWN, MINUS_Z, r2, ra, np, pieces(r2), Material.PIPE);
        double zEnd = Math.min(hz - r2 - .5, hz - hr - .3);
        u.line(ra, np, Material.PIPE, low, p(ax, yLow, zEnd));
        u.solids.add(Solid.rod(p(ax, 0, hz), 1, yLow + r2, l.airIn - r1, ra, 0));
        u.solids.add(Solid.rod(p(ax, yLow, 0), 2, zEnd + O, hz - r2 + O, ra, 0));
        var end = p(ax, yLow, zEnd);
        if (a > PipeSizes.MAX_OUTSIDE) end = u.reducer(end, MINUS_Z, ra, PipeSizes.MAX_OUTSIDE / 2, taper(ra), np, Material.PIPE, false);
        double airOd = PipeSizes.od(a, .1, PipeSizes.MAX_OUTSIDE);
        u.nozzles.add(ProcessUnit.front(Role.AIR, end[0], yLow, end[2], airOd));
        // Out of the heater's lower back, under the regenerator and up into its bottom head.
        double r3 = a, yo = l.airOut, apex = l.support - l.head;
        u.line(ra, np, Material.PIPE, p(0, yo, hz + hr - .2), p(0, yo, -r3));
        var turn = u.elbow(p(0, yo, -r3), PLUS_Z, UP, r3, ra, np, pieces(r3), Material.PIPE);
        u.line(ra, np, Material.PIPE, turn, p(0, apex + .3, 0));
        bellows(Frame.of(p(0, 0, 0), 1, 1), ra, Math.max(yo + r3 + .2, apex - 1.2), np);
        u.solids.add(Solid.rod(p(0, yo, 0), 2, hz + hr + O, -r3 + O, ra, 0));
        u.solids.add(Solid.rod(p(0, 0, 0), 1, yo + r3, apex + .3, ra, 0));
        u.round(p(0, yo, 0), 2, hz + hr + O, -r3 + O, ra);
        u.round(p(0, 0, 0), 1, yo + r3, apex, ra);
        u.round(p(ax, 0, hz), 1, yLow + r2, l.airIn - r1, ra);
        u.round(p(ax, yLow, 0), 2, zEnd + O, hz - r2 + O, ra);
    }

    // ---- Reactor, stripper and riser ----------------------------------------------------------------------------------

    /** The reactor (disengager) with its top head and manway, the 30° cone, the stripper with its bottom head and steam ring. */
    private void reactor() {
        double x = l.x, rr = l.rr, rs = l.rs;
        int n = vesselSides(2 * rr);
        var axis = p(x, 0, 0);
        var up = Frame.of(axis, 1, 1);
        m.prism(up, rr, l.reactorBottom, l.reactorTop, n, Material.SHELL);
        m.head(up, rr, l.reactorTop, rr / 2, n, bands(n), Material.HEAD);
        m.frustum(up, rs, rr, l.stripperTop, l.reactorBottom, n, Material.SHELL);
        m.prism(up, rs, l.stripperBottom, l.stripperTop, n, Material.SHELL);
        m.head(Frame.of(axis, 1, -1), rs, -l.stripperBottom, rs / 2, n, bands(n), Material.HEAD);
        u.solids.add(Solid.rod(axis, 1, l.reactorBottom, l.reactorTop, rr, rr / 2));
        u.solids.add(Solid.cone(axis, 1, l.stripperTop, l.reactorBottom, rs, rr, 0));
        u.solids.add(Solid.rod(axis, 1, l.stripperBottom, l.stripperTop, rs, rs / 2, 0));
        inside(axis, rr, n, l.reactorBottom, l.reactorTop, 0, rr / 2);
        u.volumes.add(Solid.cone(axis, 1, l.stripperTop, l.reactorBottom, rs - INSIDE / COS30, rr - INSIDE / COS30, 0));
        inside(axis, rs, n, l.stripperBottom, l.stripperTop, rs / 2, 0);
        u.round(axis, 1, l.reactorBottom, l.reactorTop, rr);
        u.round(axis, 1, l.stripperBottom, l.stripperTop, rs);
        manway(x, 0, rr, Math.PI / 4, l.manwayFloor + .9, n);
        // The stripping steam ring over its floor, four feeds into the shell.
        double ring = rs + .35, y = l.stripperBottom + 1.2, od = l.steamOd;
        int np = lineSides(od);
        m.bend(new Frame(p(x, y, 0), DOWN, PLUS_X), ring, od / 2, 0, 2 * Math.PI, Math.max(12, (int) Math.ceil(2 * Math.PI * ring / 1.5)), np, true, Material.PIPE);
        u.solids.add(Solid.ring(x + O, O, y - od / 2, y + od / 2, ring - od / 2, ring + od / 2, 0, 2 * Math.PI));
        for (int k = 0; k < 4; k++) {
            double t = Math.PI / 4 + k * Math.PI / 2, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
            u.line(od / 2, np, Material.PIPE, p(x + ring * c, y, ring * s), p(x + (rs - .05) * c, y, (rs - .05) * s));
        }
    }

    /** The riser: on its stand from its bottom head (lift steam) up into the stripper's bottom head, three bellows. */
    private void riser() {
        double x = l.x, r = l.rri, yB = l.wye - 1.2 * r - .3, ratio = r / l.rs;
        double top = l.stripperBottom - l.rs / 2 * Math.sqrt(1 - ratio * ratio) + .1;
        int n = Math.max(16, lineSides(2 * r));
        var axis = p(x, 0, 0);
        var up = Frame.of(axis, 1, 1);
        m.prism(up, r, yB, top, n, Material.SHELL);
        m.head(Frame.of(axis, 1, -1), r, -yB, r / 2, n, bands(n), Material.HEAD);
        u.solids.add(Solid.rod(axis, 1, yB, top, r, r / 2, 0));
        inside(axis, r, n, yB, top - .3, r / 2, 0);
        u.round(axis, 1, yB, top, r);
        for (double y : new double[]{l.wye + 5.5, l.stripperBottom - l.rs / 2 - 2.5}) bellows(up, r, y, n);
        double leg = .2, at = Math.sqrt(.5) * Math.max(r, .3);
        for (int i = 0; i < 4; i++) {
            double sx = i == 0 || i == 3 ? 1 : -1, sz = i < 2 ? 1 : -1;
            u.post(x + sx * at + O, sz * at + O, 0, yB, leg, false);
        }
        // The lift steam into the bottom head.
        u.line(.06, 8, Material.PIPE, p(x, yB - r / 2 + .05, 0), p(x, .35, 0));
        u.line(.06, 8, Material.PIPE, p(x, .35, 0), p(x + 1, .35, 0));
    }

    /** A riser's or a line's bellows at station {@code at} along frame {@code f}: two flanges and three convolutions. */
    private void bellows(Frame f, double r, double at, int n) {
        double ring = r + .05 + .03 * r, flange = r + .08 + .05 * r;
        for (int k = 0; k < 2; k++) {
            double z = at + .14 + k * .14;
            m.prism(f, ring, z, z + .07, n, Material.PIPE);
            m.annulus(f, r, ring, z, n, -1, Material.PIPE);
            m.annulus(f, r, ring, z + .07, n, 1, Material.PIPE);
        }
        for (double z : new double[]{at, at + .43}) {
            m.prism(f, flange, z, z + .06, n, Material.FLANGE);
            m.annulus(f, r, flange, z, n, -1, Material.FLANGE);
            m.annulus(f, r, flange, z + .06, n, 1, Material.FLANGE);
        }
    }

    /**
     * The feed injectors in a ring round the riser, 30° up into it, each fed from below by the feed ring header and its
     * atomising steam by the steam ring header under that; both headers open towards the regenerated-catalyst standpipe
     * (and the injectors clear of its slide valve's actuator where it stands among them). The feed and steam lines drop
     * from their headers through the floor and run under it to their nozzles beyond the front and back faces.
     */
    private void injectors() {
        double x = l.x, a0 = Math.PI + l.gap, a1 = 3 * Math.PI - l.gap, first = a0;
        if (l.rcsvOnInjectorFloor) {
            double rho = x - l.rcsvX, w = l.injectorOd / 2 + .15, inside = (rho - .5 * l.dsp - w) / l.outer;
            if (inside < 1) first = Math.max(first, Math.PI + DeterministicMath.acos(Math.max(-1, inside)) + w / l.outer);
        }
        int n = l.injectors;
        double reachIn = l.outer - (l.rri - .05), rise = reachIn * TAN30;
        for (int k = 0; k < n; k++) {
            double t = first + (a1 - first) * (k + .5) / n, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
            double[] outer = p(x + l.outer * c, l.outerY, l.outer * s), back = p(x + (l.outer + .3) * c, l.outerY - .3 * TAN30, (l.outer + .3) * s);
            double[] inner = p(x + (l.rri - .05) * c, l.outerY + rise, (l.rri - .05) * s);
            u.line(l.injectorOd / 2, 8, Material.PIPE, back, inner);
            var dir = unit(sub(inner, back));
            var f = new Frame(outer, dir, unit(cross(dir, UP)));
            m.cylinder(f, l.injectorOd / 2 + .06, -.04, .04, 8, Material.FLANGE);
            // Feed up from its header into the injector's back, atomising steam from its header round to the back's end.
            double fr = Math.max(.03, .3 * l.feedOd), sr = Math.max(.025, .3 * l.steamOd), rs = l.steamRing;
            u.line(fr, 8, Material.PIPE, p(x + l.feedRing * c, l.feedY, l.feedRing * s), p(x + (l.outer + .15) * c, l.outerY - .1, (l.outer + .15) * s));
            u.line(sr, 6, Material.PIPE, p(x + rs * c, l.steamY, rs * s), p(x + rs * c, l.outerY - .2, rs * s));
            u.line(sr, 6, Material.PIPE, p(x + rs * c, l.outerY - .2, rs * s), p(x + (l.outer + .3) * c, l.outerY - .2, (l.outer + .3) * s));
        }
        header(l.feedRing, l.feedY, l.feedOd, a0, a1);
        header(l.steamRing, l.steamY, l.steamOd, a0, a1);
        // The feed and steam lines: each leaves its header where it crosses x + 1, drops under the floor's beams and runs
        // out to its nozzle.
        double under = l.injectorFloor - Access.DECK - BEAM - .1;
        double yF = Math.floor(under - l.feedOd / 2 - .5) + .5, yS = Math.floor(under - l.steamOd / 2 - .5) + .5;
        double zF = -Math.sqrt(l.feedRing * l.feedRing - 1), zS = Math.sqrt(l.steamRing * l.steamRing - 1), zFront = -l.half - .3, zBack = l.half + .3;
        u.pipe(l.feedOd / 2, Material.PIPE, true, p(x + 1, l.feedY, zF), p(x + 1, yF, zF), p(x + 1, yF, zFront));
        u.pipe(l.steamOd / 2, Material.PIPE, true, p(x + 1, l.steamY, zS), p(x + 1, yS, zS), p(x + 1, yS, zBack));
        u.nozzles.add(ProcessUnit.front(Role.FEED, x + 1 + O, yF, zFront + O, l.feedOd));
        u.nozzles.add(ProcessUnit.end(Role.STEAM, p(x + 1, yS, zBack), 2, 1, l.steamOd));
    }

    /** A ring header round the riser at {@code y}, radius {@code ring}, from angle a0 to a1 (from +x towards +z), its ends capped. */
    private void header(double ring, double y, double od, double a0, double a1) {
        int np = lineSides(od), segments = Math.max(8, (int) Math.ceil((a1 - a0) * ring / 1.5));
        m.bend(new Frame(p(l.x, y, 0), DOWN, PLUS_X), ring, od / 2, a0, a1, segments, np, true, Material.PIPE);
        for (int e = 0; e < 2; e++) {
            double t = e == 0 ? a0 : a1, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
            var cap = new Frame(p(l.x + ring * c, y, ring * s), e == 0 ? new double[]{s, 0, -c} : new double[]{-s, 0, c}, UP);
            m.annulus(cap, 0, od / 2, 0, np, 1, Material.PIPE);
        }
        u.solids.add(Solid.ring(l.x + O, O, y - od / 2, y + od / 2, ring - od / 2, ring + od / 2, a0, a1));
    }

    // ---- Standpipes ---------------------------------------------------------------------------------------------------

    /**
     * The regenerated-catalyst standpipe straight from the regenerator's lower shell to the riser's wye, the spent-catalyst
     * standpipe from the stripper's bottom head down to its 45° lateral into the regenerator's bed; a slide valve on each.
     */
    private void standpipes() {
        double r = l.asp, d = l.dsp;
        int n = lineSides(d);
        // Regenerated catalyst: along (sin1, -cos1) from the regenerator's shell.
        double ux = l.sin1, uy = -l.cos1, inside = r + .15;
        double tValve = (l.rcsvX - l.r) / l.sin1, tEnd = (l.x - l.rri - l.r) / l.sin1 + .1;
        double[] start = {l.r - inside * ux, l.rcspTop - inside * uy}, valveTop = {l.r + (tValve - .8 * d) * ux, l.rcspTop + (tValve - .8 * d) * uy};
        double[] valveBottom = {l.r + (tValve + .8 * d) * ux, l.rcspTop + (tValve + .8 * d) * uy}, end = {l.r + tEnd * ux, l.rcspTop + tEnd * uy};
        u.line(r, n, Material.PIPE, p(start[0], start[1], 0), p(valveTop[0], valveTop[1], 0));
        u.line(r, n, Material.PIPE, p(valveBottom[0], valveBottom[1], 0), p(end[0], end[1], 0));
        slanted(start, valveTop, r);
        slanted(valveBottom, end, r);
        slideValve(l.rcsvX, l.rcsvY, ux, uy);
        // Spent catalyst: from inside the stripper down along -(sin2, cos2) to the elbow over the lateral.
        double vx = l.sin2, vy = l.cos2, tv = (l.scsvY - l.p1y) / l.cos2;
        double[] s0 = {l.s0x, l.stripperBottom + .2}, p1 = {l.p1x, l.p1y}, in = {l.r - .3 * Math.sqrt(.5), l.scspIn - .3 * Math.sqrt(.5)};
        double[] top = {l.p1x + (tv + .8 * d) * vx, l.p1y + (tv + .8 * d) * vy}, bottom = {l.p1x + (tv - .8 * d) * vx, l.p1y + (tv - .8 * d) * vy};
        u.line(r, n, Material.PIPE, p(s0[0], s0[1], 0), p(top[0], top[1], 0));
        u.line(r, n, Material.PIPE, p(bottom[0], bottom[1], 0), p(p1[0], p1[1], 0), p(in[0], in[1], 0));
        slanted(s0, top, r);
        slanted(bottom, p1, r);
        slanted(p1, in, r);
        slideValve(l.scsvX, l.scsvY, vx, vy);
    }

    /**
     * A slide valve on a standpipe (local centre (x, y) on z = 0, the pipe along (ax, ay)): its body 1.6 standpipe
     * diameters along the pipe, 1.3 across and 2.2 along z with a flange at each end; the bonnet, the stem's tube and the
     * hydraulic actuator out towards the front ({@link FccConverterLayout#valveReach} from the pipe's axis), its
     * positioner box.
     */
    private void slideValve(double x, double y, double ax, double ay) {
        double d = l.dsp;
        double[] c = p(x, y, 0), along = {ax, ay, 0}, across = {-ay, ax, 0};
        m.block(c, scale(along, .8 * d), scale(across, .65 * d), new double[]{0, 0, 1.1 * d}, Material.MACHINE);
        int n = lineSides(d);
        for (int s = -1; s <= 1; s += 2) {
            var f = new Frame(plus(c, scale(along, s * .8 * d)), along, across);
            double flange = l.asp + .08 + .05 * d;
            m.prism(f, flange, -.05, .05, n, Material.FLANGE);
            m.annulus(f, l.asp, flange, s > 0 ? .05 : -.05, n, s, Material.FLANGE);
        }
        m.block(plus(c, new double[]{0, 0, -1.4 * d}), scale(along, .5 * d), scale(across, .42 * d), new double[]{0, 0, .3 * d}, Material.MACHINE);
        m.block(plus(c, new double[]{0, 0, -1.12 * d}), scale(along, .62 * d), scale(across, .52 * d), new double[]{0, 0, .03}, Material.FLANGE);
        var stem = new Frame(plus(c, new double[]{0, 0, -1.7 * d}), MINUS_Z, PLUS_X);
        m.prism(stem, .12 * d, 0, 1.4 * d, 8, Material.ACTUATOR);
        double reach = FccConverterLayout.valveReach(d), cylinder = reach - 3.1 * d, r = .2 * d;
        m.cylinder(new Frame(plus(c, new double[]{0, 0, -3.1 * d}), MINUS_Z, PLUS_X), r, 0, cylinder, 12, Material.ACTUATOR);
        for (double z : new double[]{0, cylinder - .05})
            m.cylinder(new Frame(plus(c, new double[]{0, 0, -3.1 * d - z}), MINUS_Z, PLUS_X), r + .04, 0, .05, 12, Material.ACTUATOR);
        m.block(plus(c, new double[]{0, r + .1, -3.1 * d - cylinder / 2}), new double[]{.12, 0, 0}, new double[]{0, .1, 0}, new double[]{0, 0, .15}, Material.ACTUATOR);
        double px = .8 * d * Math.abs(ax) + .65 * d * Math.abs(ay), py = .8 * d * Math.abs(ay) + .65 * d * Math.abs(ax);
        u.solids.add(Solid.box(p(x - px, y - py, -1.1 * d), p(x + px, y + py, 1.1 * d)));
        u.solids.add(Solid.box(p(x - .45 * d, y - .45 * d, -reach), p(x + .45 * d, y + .45 * d, -1.1 * d)));
    }

    /** A slanted pipe's collision in the xy plane at z = 0 (local {x, y} ends): boxes 0.6 m along it. */
    private void slanted(double[] a, double[] b, double r) {
        double dx = b[0] - a[0], dy = b[1] - a[1], length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1e-6) return;
        int pieces = Math.max(1, (int) Math.ceil(length / .6));
        double ex = r * Math.abs(dy) / length, ey = r * Math.abs(dx) / length;
        for (int k = 0; k < pieces; k++) {
            double x0 = a[0] + dx * k / pieces, y0 = a[1] + dy * k / pieces, x1 = a[0] + dx * (k + 1) / pieces, y1 = a[1] + dy * (k + 1) / pieces;
            u.solids.add(Solid.box(p(Math.min(x0, x1) - ex, Math.min(y0, y1) - ey, -r), p(Math.max(x0, x1) + ex, Math.max(y0, y1) + ey, r)));
        }
    }

    // ---- Structure and stair tower ------------------------------------------------------------------------------------

    /** The frame's floors, columns, beams, struts and bracing, and the stair tower that joins every floor. */
    private void structure() {
        double xf = l.xf;
        var floors = new ArrayList<Floor>();
        floors.add(new Floor(l.injectorFloor, l.riserHole, xf, true, 0));
        if (!l.rcsvOnInjectorFloor) floors.add(new Floor(l.rcsvFloor, l.rcsvHole, l.rcsvEnd, true, 0));
        floors.add(new Floor(l.scsvFloor, l.scsvHole, xf, true, 0));
        floors.add(new Floor(l.stripperFloor, l.rs + .15, xf, false, l.rs + .05));
        floors.add(new Floor(l.manwayFloor, l.rr + .15, xf, false, l.rr + .05));
        double shoulder = .25 / (l.rr / 2);
        floors.add(new Floor(l.topFloor, l.rr + .15, xf, false, l.rr * Math.sqrt(Math.max(0, 1 - shoulder * shoulder)) + .05));
        // The stair tower on the +x face, lane A beside the frame from the landing at its front corner (the landing's edge
        // in the frame's front face), lane B outside it; the face is where the tower's side meets it (FccConverterLayout#xe).
        var stops = new double[floors.size()];
        for (int i = 0; i < stops.length; i++) stops[i] = floors.get(i).level();
        var plan = ProcessUnit.towerPlan(stops);
        var tower = u.stairTower(plan, l.xe + O + ProcessUnit.TOWER_FLIGHT / 2, ProcessUnit.TowerPlan.foot(-l.half + O, 1, plan.near()), 2, 1, 1, true);
        for (int i = 0; i < floors.size(); i++) floor(floors.get(i), tower.openings().get(i));
        frame(floors, tower);
    }

    /**
     * A floor, open in its +x handrail over the stair tower's {@code opening} (frame z, {lo, hi}). Where no standpipe
     * passes, a square deck from the frame's -x face to its +x face round its vessel's opening, filled in to the vessel
     * ({@link ProcessUnit#squareDeck}); where one does, the +x deck the frame's full depth, the front and back decks from
     * its -x end to it, the opening open to the -x face with a handrail along both its sides. Beams under the decks' edges.
     */
    private void floor(Floor f, double[] opening) {
        double x = l.x, h = f.hole(), half = l.half, xe = l.xe, from = f.from(), level = f.level();
        if (!f.open())
            u.squareDeck(from + O, xe + O, -half + O, half + O, level, 0, O, h, new double[]{x + O}, f.fill(), 16, Access.RAILED, new double[][]{null, opening, null, null}, null);
        else {
            int parts = Access.RAILED | Access.WALL_EDGE;
            var side = new Access.Line(p(x + h, 0, -half), PLUS_Z, PLUS_X, 2 * half);
            u.platform(side, level, xe - x - h, parts, true, true, side.gap(opening));
            u.platform(new Access.Line(p(from, 0, -h), PLUS_X, MINUS_Z, x + h - from), level, half - h, parts, true, false);
            u.platform(new Access.Line(p(from, 0, h), PLUS_X, PLUS_Z, x + h - from), level, half - h, parts, true, false);
            for (int s = -1; s <= 1; s += 2) u.rails(new Access.Line(p(from, 0, s * (h + 1)), PLUS_X, new double[]{0, 0, -s}, x - from), level, 1, false, false);
        }
        // Beams under the outer edges (on the -x face only under the front and back decks' ends: the standpipes pass its
        // middle) and under the opening's front and back edges.
        double y0 = level - BEAM, w = BEAM_WIDTH;
        u.beam(p(from, y0, -half), p(xe, level, -half + w));
        u.beam(p(from, y0, half - w), p(xe, level, half));
        u.beam(p(xe - w, y0, -half + w), p(xe, level, half - w));
        u.beam(p(l.xf, y0, -half + w), p(l.xf + w, level, -h - .05));
        u.beam(p(l.xf, y0, h + .05), p(l.xf + w, level, half - w));
        u.beam(p(from, y0, -h - .05 - w), p(xe - w, level, -h - .05));
        u.beam(p(from, y0, h + .05), p(xe - w, level, h + .05 + w));
        if (from < l.xf - 1e-9) {
            // Under the regenerator towards its table: a post at each outer corner, a beam across the decks' end.
            for (int s = -1; s <= 1; s += 2) {
                u.post(from + COLUMN / 2 + O, s * (half - COLUMN / 2) + O, 0, y0, COLUMN, false);
                u.beam(p(from, y0, s > 0 ? h + .05 : -half), p(from + w, level, s > 0 ? half : -h - .05));
            }
        }
    }

    /**
     * The frame: columns at its corners, at the riser's line along its front and back where it is longer than 9 m and at
     * the middle of its +x face where it is deeper than 14.4 m and the stair tower ends short of it (the -x face stays open
     * for the standpipes), cased in concrete ({@link ProcessUnit#casing}) to the first floor; struts on its front and back
     * at most 8 m apart between floors more than 9 m apart; X-bracing in alternate front and back bays over the
     * spent-catalyst valve's floor; a post carrying the vapour line on the top floor.
     */
    private void frame(List<Floor> floors, ProcessUnit.Tower tower) {
        double xf = l.xf, xe = l.xe, half = l.half, c = COLUMN / 2, zc = half - c;
        var xs = new ArrayList<Double>(List.of(xf + c));
        if (xe - xf > 9) xs.add((double) l.x);
        xs.add(xe - c);
        var columns = new ArrayList<double[]>();
        for (double x : xs) for (int s = -1; s <= 1; s += 2) columns.add(new double[]{x, s * zc});
        if (half > 7.2 && tower.to() - O < -.35) columns.add(new double[]{xe - c, 0});
        double cased = floors.getFirst().level() - BEAM, top = floors.getLast().level();
        for (var col : columns) {
            u.leg(col[0] + O, col[1] + O, cased, ProcessUnit.casing(COLUMN), PLUS_X);
            u.post(col[0] + O, col[1] + O, cased, top, COLUMN, false);
        }
        // Struts, and the levels the bracing runs between (each with its beam's depth).
        var levels = new ArrayList<double[]>();
        for (int i = 0; i < floors.size(); i++) {
            double level = floors.get(i).level();
            if (floors.get(i).surface() >= l.scsvFloor - 1e-9) levels.add(new double[]{level, BEAM});
            if (i + 1 == floors.size()) break;
            double next = floors.get(i + 1).level(), gap = next - level;
            int struts = gap > 9 ? (int) Math.ceil(gap / 8 - 1e-9) - 1 : 0;
            for (int k = 1; k <= struts; k++) {
                double y = level + gap * k / (struts + 1), y0 = y - STRUT, w = .2;
                u.beam(p(xf, y0, -half), p(xe, y, -half + w));
                u.beam(p(xf, y0, half - w), p(xe, y, half));
                if (floors.get(i).surface() >= l.scsvFloor - 1e-9) levels.add(new double[]{y, STRUT});
            }
        }
        for (int k = 0; k + 1 < levels.size(); k++)
            for (int j = 0; j + 1 < xs.size(); j++)
                for (int s = -1; s <= 1; s += 2)
                    if ((j + k) % 2 == 0) u.brace(new double[]{xs.get(j) + O, s * zc + O}, new double[]{xs.get(j + 1) + O, s * zc + O}, levels.get(k)[0], levels.get(k + 1)[0] - levels.get(k + 1)[1]);
        // The vapour line's support post on the top floor's +x beam, unless the tower's landing joins the floor there.
        var landing = tower.openings().getLast();
        if (landing[0] - O > .3 || landing[1] - O < -.3) u.post(xe - .15 + O, O, top, vapourAxis() - l.vapour / 2, .3, false);
    }

    // ---- Flue gas and vapour lines ------------------------------------------------------------------------------------

    /** The flue gas line out of the regenerator's crown and over to its nozzle beyond the shell at -x; the vapour line out of the reactor's crown over the stair tower to its nozzle at +x. */
    private void lines() {
        // Flue gas.
        double rf = l.flue / 2, bend = l.flue, crown = l.support + l.straight + l.head, y = axis(crown, bend);
        int nf = lineSides(l.flue);
        u.line(rf, nf, Material.PIPE, p(0, crown - .3, 0), p(0, y - bend, 0));
        var turn = u.elbow(p(0, y - bend, 0), UP, MINUS_X, bend, rf, nf, pieces(bend), Material.PIPE);
        double xEnd = -(l.r + .3);
        u.line(rf, nf, Material.PIPE, turn, p(xEnd, y, 0));
        u.solids.add(Solid.rod(p(0, 0, 0), 1, crown, y - bend, rf, 0));
        u.solids.add(Solid.rod(p(0, y, 0), 0, xEnd + O, -bend + O, rf, 0));
        u.round(p(0, y, 0), 0, xEnd + O, -bend + O, rf);
        var end = p(xEnd, y, 0);
        if (l.flue > PipeSizes.MAX_OUTSIDE) end = u.reducer(end, MINUS_X, rf, PipeSizes.MAX_OUTSIDE / 2, taper(rf), nf, Material.PIPE, false);
        double flueOd = PipeSizes.od(l.flue, .1, PipeSizes.MAX_OUTSIDE);
        u.nozzles.add(ProcessUnit.end(Role.FLUE_GAS, end, 0, -1, flueOd));
        // Vapour, on past the stair tower's outer side.
        double rv = l.vapour / 2, bv = 1.5 * l.vapour, top = l.reactorTop + l.rr / 2, yv = vapourAxis(), x = l.x;
        int nv = lineSides(l.vapour);
        u.line(rv, nv, Material.CLADDING, p(x, top - .3, 0), p(x, yv - bv, 0));
        var over = u.elbow(p(x, yv - bv, 0), UP, PLUS_X, bv, rv, nv, pieces(bv), Material.CLADDING);
        double xv = Math.max(x + bv + .5, l.xe + ProcessUnit.TOWER_LANE + ProcessUnit.TOWER_FLIGHT + .5);
        u.line(rv, nv, Material.CLADDING, over, p(xv, yv, 0));
        u.solids.add(Solid.rod(p(x, 0, 0), 1, top, yv - bv, rv, 0));
        u.solids.add(Solid.rod(p(0, yv, 0), 0, x + bv + O, xv + O, rv, 0));
        u.round(p(0, yv, 0), 0, x + bv + O, xv + O, rv);
        var out = p(xv, yv, 0);
        if (l.vapour > PipeSizes.MAX_OUTSIDE) out = u.reducer(out, PLUS_X, rv, PipeSizes.MAX_OUTSIDE / 2, taper(rv), nv, Material.CLADDING, false);
        double vapourOd = PipeSizes.od(l.vapour, .1, PipeSizes.MAX_OUTSIDE);
        u.nozzles.add(ProcessUnit.end(Role.VAPOUR_OUTLET, out, 0, 1, vapourOd));
    }

    /** The vapour line's horizontal axis: a cell centre at least its elbow and 0.3 m over the reactor's crown. */
    private double vapourAxis() {
        return axis(l.reactorTop + l.rr / 2, 1.5 * l.vapour);
    }

    /** A horizontal line's axis over a crown at {@code crown} through an elbow of {@code bend}: the cell centre at least 0.3 m higher than the elbow needs. */
    private static double axis(double crown, double bend) {
        return Math.ceil(crown + .3 + bend - .5 - 1e-9) + .5;
    }

    // ---- Pipe parts ---------------------------------------------------------------------------------------------------

    /** A mitred elbow's pieces ({@link ProcessUnit#elbow}) for a bend radius {@code bend}: about one every 0.9 m along it, three at least. */
    private static int pieces(double bend) {
        return Math.max(3, (int) Math.ceil(bend * Math.PI / 2 / .9));
    }

    /** The length of a 15° concentric reducer ({@link ProcessUnit#reducer}) from a line of radius {@code r} down to a pipe's largest. */
    private static double taper(double r) {
        return (r - PipeSizes.MAX_OUTSIDE / 2) / FccConverterLayout.TAN15;
    }

    // ---- Frame helpers ------------------------------------------------------------------------------------------------

    /** A local point (the layout's frame) in the unit's frame: x and z shifted half a block. */
    private static double[] p(double x, double y, double z) {
        return new double[]{x + O, y, z + O};
    }

    /** A line's sides: 8 up to 0.6 m, then about one every 0.7 m round a large line (its outline at a block's scale). */
    static int lineSides(double od) {
        return od <= .35 ? 6 : od <= .6 ? 8 : Math.max(12, 4 * (int) Math.ceil(.5 * od + 1.5));
    }

    /** A vessel's sides: at least 24, about one every 0.8 m round a large one. */
    static int vesselSides(double od) {
        return Math.max(24, 4 * (int) Math.ceil(.45 * od + 2));
    }
}
