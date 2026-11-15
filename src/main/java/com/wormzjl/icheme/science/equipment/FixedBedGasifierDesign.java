package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.ProcessUnit.clamp;

/**
 * A Lurgi (Sasol-Lurgi FBDB) fixed-bed dry-bottom gasifier in its gasifier house (owner decisions D1 and D4 2026-10-18,
 * {@code NEW_UNITS_PLAN.md}; {@code NEW_UNITS_SURVEY_SOLID_FUEL.md} section 2): lump coal sinks through a bed against
 * oxygen and steam rising from the turning grate; the raw gas leaves near the top at 480 to 530 °C into the wash cooler,
 * the ash leaves through the ash lock. Its size is the gasifier's inside diameter D, 2 to 5 m in 0.25 m steps (Rheinbraun
 * 2.7 m, Mark III 3.66 m, Mark IV about 4 m, Dakota 13 ft); its support is the height of the ash lock's bottom valve,
 * 3 to 5 m, for the ash chute under it.
 *
 * <p>Proportions (the survey's): the water-jacketed vessel 3.1 D from tangent to tangent (12.2 m over 3.96 m at Dakota),
 * its jacket about D + 0.4 m across, insulated and clad (the jacket stands near saturation, 235 °C at 30 bar), a 2:1 head
 * on top; under it the grate housing's cone, 0.45 D high, with the two grate drive sets on opposite sides (motor,
 * turning coupling and gearbox driving the ring gear); the ash lock 0.6 D across and 0.9 D high over a cone, its top and
 * bottom cone valves worked by hydraulic cylinders beside it; above the vessel the coal lock, 0.6 D across and D high
 * over a cone, its valves' vertical hydraulic cylinders standing on its top head (the signature), and the coal bunker over
 * it, D across and 0.8 D high. The jacket steam drum, 0.25 D across and 0.8 D long, lies beside the coal lock with its
 * risers and downcomer; the raw gas leaves near the vessel's top into the wash cooler, a vertical vessel 0.3 D across and
 * 1.5 D high beside it with the quench liquor sprayed in on top. The open gasifier house, about (D + 5) square (the
 * least odd number of metres of D + 5.09 at least, {@link #bay}) and a bay of 3 m on the wash cooler's side, carries its floors at the ash lock, the grate drives and agent inlet,
 * the vessel's support (0.3 of its height), the gas outlet, the coal lock's bottom and top valves (with the distributor
 * drive) and the bunker's top (with the hoist beam over the coal lock), its columns cased in concrete up to the first
 * floor; the kit's stair tower beside its west face ({@link ProcessUnit#stairTower}), each floor open where the tower's
 * landing meets it. Floors closer than 2.6 m give way to the more needed one.
 *
 * <p>Nozzles (the survey's sizes, D the inside diameter): oxidant at the front of the grate housing, clamp(0.03 D, 0.05,
 * 0.2); agent steam at its back, clamp(0.05 D, 0.08, 0.3), importing what the jacket does not raise (its steam joins it
 * inside the unit: a steam/O2 ratio of 5 to 6 kg/kg is more than the jacket raises, {@code NEW_UNITS_PLAN.md}); syngas out
 * of the wash cooler's side, clamp(0.1 D, 0.2, 0.6); quench liquor into its top and gas liquor out of its bottom, each
 * clamp(0.03 D, 0.05, 0.15); jacket feedwater low on the vessel's front, 0.05 to 0.1; the locks' vent up from the coal
 * lock through the roof and lock gas into the ash lock's front, each 0.05 to 0.15; coal into the bunker's top, ash out of
 * the chute 1 m over grade. Moving: the grate drives' couplings turn; the locks' cylinders slide (0.3 to 0.6 m strokes).
 */
final class FixedBedGasifierDesign extends UnitDesign {
    FixedBedGasifierDesign() {
        // minSupport: The ash lock's bottom valve 3 m up at least: the chute and the sluice or conveyor under it.
        super(new Sizes(2, 5, .25,
                1, 1,
                3, 5,
                Orientation.VERTICAL, "fixed_bed_gasifier.diameter", null, "fixed_bed_gasifier.ash_lock", true,
                1));
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("fixed_bed_gasifier.vessel", size(d), number(3.1 * d));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        var stack = new Stack(d, shape.support());
        return List.of(new Line("fixed_bed_gasifier.vessel", size(d), number(3.1 * d)), new Line("fixed_bed_gasifier.locks", number(2 * stack.rCl), number(2 * stack.rBunker)),
                new Line("fixed_bed_gasifier.house", stack.floors.size(), number(stack.roof + Access.DECK)));
    }

    @Override
    void build(ProcessUnit unit) {
        new Build(unit).run();
    }

    /** Floors: their kinds, from the least needed (the first to give way to a closer one). */
    static final int GAS = 0, ASH = 1, DRIVES = 2, SUPPORT = 3, COAL_BOTTOM = 4, COAL_TOP = 5, ROOF = 6;
    /** The least distance between two floors, m: 2.1 m of headroom over a deck and its beams. */
    static final double STOREY = 2.6;
    /** A floor beam's depth and flange, a house column's section, m. */
    static final double BEAM = .3, FLANGE = .2, COLUMN = .35;

    /**
     * The gasifier's stack of parts, bottom up, at inside diameter {@code d} with the ash lock's bottom valve {@code s} up
     * (heights in m above grade, radii in m): the ash chute, the ash lock's bottom valve, cone, shell (y1 to y2) and head,
     * its top valve (y3 to y4), the grate housing's cone up to the vessel's bottom (yb), the vessel to its top tangent
     * (yt) and its head's apex, the coal lock's bottom valve (to y5), cone, shell (y6 to y7) and head, its top valve (y8 to
     * y9), the bunker's chute (to y10a), cone (to y10) and shell to its top (y11), 0.4 m over the roof's deck. The floors
     * that keep 2.6 m apart, {level, kind}.
     */
    static final class Stack {
        final double d, ro, rAl, rCl, rNeck, rValve, rBunker, rChute, rGate, valve, y1, y2, y3, y4, yb, yt, apex, y5, y6, y7, y8, y9, y10a, y10, y11, yGas, rGas;
        final int s, roof;
        final List<int[]> floors = new ArrayList<>();
        /** The coal lock's cylinders: their axes out from the lock's, their radius, the crank of their slider (half the stroke). */
        final double xCyl, rCyl, crank;

        Stack(double d, int s) {
            this.d = d;
            this.s = s;
            ro = d / 2 + .28;
            rAl = .3 * d + .06;
            rCl = rAl;
            rNeck = .11 * d + .01;
            rValve = rNeck + .1;
            rBunker = .5 * d + .05;
            rChute = Math.min(.45, .075 * d + .125);
            rGate = .28 * d + .1;
            valve = .25 + .04 * d;
            y1 = s + valve + .25 * d;
            y2 = y1 + .9 * d;
            y3 = y2 + rAl / 2 - .1;
            y4 = y3 + .35 + .05 * d;
            yb = y4 + .45 * d;
            yt = yb + 3.1 * d;
            apex = yt + ro / 2;
            y5 = apex - .1 + .35 + .05 * d;
            y6 = y5 + .25 * d;
            y7 = y6 + d;
            y8 = y7 + rCl / 2 - .1;
            y9 = y8 + .35 + .05 * d;
            y10a = y9 + .6 + .15 * d;
            y10 = y10a + .3 * d;
            roof = (int) Math.floor(y10 + .8 * d - .4 - Access.DECK);
            y11 = roof + Access.DECK + .4;
            rGas = .06 * d + .08;
            yGas = yt - .5 - rGas;
            rCyl = .07 + .02 * d;
            xCyl = rValve + .06 + rCyl;
            crank = .15 + .03 * d;
            var want = new ArrayList<int[]>();
            want.add(new int[]{s - 1, ASH});
            want.add(new int[]{(int) Math.floor((y4 + yb) / 2 - 1.1), DRIVES});
            want.add(new int[]{(int) Math.round(yb + .93 * d), SUPPORT});
            want.add(new int[]{(int) Math.floor(yGas - 1.7), GAS});
            want.add(new int[]{(int) Math.floor(apex - .5), COAL_BOTTOM});
            want.add(new int[]{(int) Math.floor(y7 - .5), COAL_TOP});
            want.add(new int[]{roof, ROOF});
            boolean dropped = true;
            while (dropped) {
                dropped = false;
                for (int k = 0; k + 1 < want.size() && !dropped; k++)
                    if (want.get(k + 1)[0] - want.get(k)[0] < STOREY) {
                        want.remove(want.get(k)[1] < want.get(k + 1)[1] ? k : k + 1);
                        dropped = true;
                    }
            }
            floors.addAll(want);
        }

        /** The level of the floor of {@code kind}, or -1 where it gave way. */
        int floor(int kind) {
            for (var f : floors) if (f[1] == kind) return f[0];
            return -1;
        }

        /** The grate housing's radius at height y (between y4 and yb). */
        double gate(double y) {
            return rGate + (ro - rGate) * (y - y4) / (yb - y4);
        }

        /** The widest the stack's round parts reach at height y, m from its axis (flanges and bands included). */
        double radius(double y) {
            if (y < s) return rChute;
            if (y < s + valve) return rChute + .17;
            if (y < y1) return rChute + .05 + (rAl - rChute - .05) * (y - s - valve) / (y1 - s - valve);
            if (y < y2) return rAl;
            if (y < y3) return head(rAl, (y - y2) / (rAl / 2));
            if (y < y4) return Math.max(head(rAl, (y - y2) / (rAl / 2)), rValve + .05);
            if (y < yb) return gate(y) + .05;
            if (y < yt) return ro + .03;
            if (y < apex - .1) return head(ro, (y - yt) / (ro / 2));
            if (y < y5) return Math.max(head(ro, (y - yt) / (ro / 2)), rValve + .05);
            if (y < y6) return rNeck + .05 + (rCl - rNeck - .05) * (y - y5) / (y6 - y5);
            if (y < y7) return rCl;
            if (y < y8) return head(rCl, (y - y7) / (rCl / 2));
            if (y < y10a) return Math.max(head(rCl, Math.min(1, (y - y7) / (rCl / 2))), xCyl + rCyl);
            if (y < y10) return Math.max(xCyl + rCyl, rNeck + .05 + (rBunker - rNeck - .05) * (y - y10a) / (y10 - y10a));
            return y <= y11 ? rBunker : 0;
        }

        private static double head(double r, double up) {
            return up >= 1 ? 0 : r * Math.sqrt(1 - up * up);
        }

        /** The widest round part from just under a floor's beams to half a metre over its deck. */
        double opening(int level) {
            double widest = 0;
            for (double y = level - BEAM; y <= level + .5 + 1e-9; y += .05) widest = Math.max(widest, radius(y));
            return widest;
        }
    }

    /** The drawing of one unit. */
    private static final class Build {
        final ProcessUnit u;
        final Stack st;
        final double d, a, c, x0, x1, z0, z1, xw, xe;
        final int bay;

        Build(ProcessUnit u) {
            this.u = u;
            d = u.shape.diameter();
            st = new Stack(d, u.shape.support());
            bay = bay(d);
            // The stair tower west of the house (the constructor's right), the house from x 3 and z 1, the wash cooler's bay
            // east of it.
            x0 = 3;
            x1 = x0 + bay;
            z0 = 1;
            z1 = z0 + bay;
            a = x0 + bay / 2.0;
            c = z0 + bay / 2.0;
            xw = x1 + 1.5;
            xe = x1 + 3;
        }

        void run() {
            house();
            ashLock();
            gasifier();
            coalLock();
            jacket();
            washCooler();
            nozzles();
        }

        // ---- The house ---------------------------------------------------------------------------------------------

        /**
         * The stair tower west of the house ({@link ProcessUnit#stairTower}): its lane A beside the west face, its first
         * flight rising towards the back from a landing clear of the front corner column's casing and of the tower's own
         * corner posts; it climbs from the ground to every floor, each landing on a floor at the tower's front or back end.
         * The floors round the stack ({@link #floor}), the columns (cased in concrete up to the first floor) with X-bracing
         * on the back and west faces in alternate storeys, the wash cooler's bay, the hoist beam on the roof.
         */
        void house() {
            var levels = new double[st.floors.size()];
            for (int k = 0; k < levels.length; k++) levels[k] = st.floors.get(k)[0];
            double cased = ProcessUnit.casing(COLUMN);
            var plan = ProcessUnit.towerPlan(levels);
            var tower = u.stairTower(plan, x0 - .5, ProcessUnit.TowerPlan.foot(z0 + cased / 2, 1, plan.from()), 2, 1, -1, true);
            for (int k = 0; k < levels.length; k++) floor(st.floors.get(k)[0], tower.edge(), tower.openings().get(k));
            double top = st.roof + Access.DECK + 1.1, first = levels[0];
            // Columns at the corners, and in the middle of the back and east faces of a wider house.
            double[] alongX = bay >= 9 ? new double[]{x0, a, x1} : new double[]{x0, x1};
            var columns = new ArrayList<double[]>(List.of(new double[]{x0, z0}, new double[]{x1, z0}, new double[]{x0, z1}, new double[]{x1, z1}));
            if (bay >= 9) {
                columns.add(new double[]{a, z1});
                columns.add(new double[]{x1, c});
            }
            for (var p : columns) {
                u.leg(p[0], p[1], first - BEAM, cased, new double[]{1, 0, 0});
                u.post(p[0], p[1], first - BEAM, top, COLUMN, false);
            }
            // X-bracing in every other storey from the first floor up: each bay of the back face; the west face in the
            // columns' inner flange plane, clear of the stair tower beside it, over the longer stretch beside the storey's
            // floor's landing (the front stays open towards the constructor, the east face to the wash cooler's bay).
            for (int k = 0; k + 1 < levels.length; k += 2) {
                double lo = levels[k] + .05, hi = levels[k + 1] - BEAM, xb = x0 + COLUMN / 2 - .075;
                for (int i = 0; i + 1 < alongX.length; i++) u.brace(new double[]{alongX[i] + .2, z1}, new double[]{alongX[i + 1] - .2, z1}, lo, hi);
                double[] open = tower.openings().get(k);
                double front = open[0] - .2 - (z0 + .2), back = z1 - .2 - (open[1] + .2);
                if (Math.max(front, back) >= 1.5)
                    u.brace(new double[]{xb, front > back ? z0 + .2 : open[1] + .2}, new double[]{xb, front > back ? open[0] - .2 : z1 - .2}, lo, hi);
            }
            // The wash cooler's bay: two columns, beams out to them at the cooler's support, beams along it carrying the
            // cooler's lugs.
            double ys = washTop() - .3, rw = washRadius();
            for (double z : new double[]{z0, z1}) {
                u.post(xe, z, 0, ys, COLUMN, false);
                u.beam(new double[]{x1, ys - BEAM, z - .1}, new double[]{xe, ys, z + .1});
            }
            for (double x : new double[]{xw - rw - .3, xw + rw + .1}) u.beam(new double[]{x, ys - BEAM, z0 + .1}, new double[]{x + .2, ys, z1 - .1});
            // The hoist beam on two posts over the roof, along x over the coal lock, reaching out over the wash cooler's
            // bay; its trolley parked east of the bunker.
            double roofWalk = st.roof + Access.DECK, yh = roofWalk + 3, xt = (a + st.rBunker + x1) / 2;
            for (double x : new double[]{x0 + .45, x1 - .45})
                u.box(new double[]{x - .12, roofWalk, c - .12}, new double[]{x + .12, yh, c + .12}, Material.STEELWORK, true);
            u.box(new double[]{x0 + .25, yh, c - .14}, new double[]{xe + .15, yh + .45, c + .14}, Material.STEELWORK, true);
            u.box(new double[]{xt - .25, yh - .5, c - .2}, new double[]{xt + .25, yh, c + .2}, Material.MACHINE, false);
            u.mesh.cylinder(Frame.of(new double[]{xt, 0, c}, 1, 1), .02, yh - 1.4, yh - .5, 6, Material.PIPE);
        }

        /**
         * A house floor at {@code level} ({@link ProcessUnit#squareDeck}, on beams: no brackets): grating from the stair
         * tower's edge {@code west} to the east face and from the front face to the back round a square opening for the
         * stack's widest part there ({@link Stack#opening}), filled round it; railed at the house's edges but over the
         * tower's {@code landing} ({lo, hi} along z) on the west edge. Beams under the house's edges and round the opening.
         */
        void floor(int level, double west, double[] landing) {
            double rh = st.opening(level) + Access.GAP, h = rh + Access.GAP, y0 = level - BEAM;
            u.squareDeck(west, x1, z0, z1, level, 2, a, h, new double[]{c}, rh, rh < 1.5 ? 16 : 24, Access.RAILED, new double[][]{null, null, null, landing}, null);
            u.beam(new double[]{x0, y0, z0}, new double[]{x1, level, z0 + FLANGE});
            u.beam(new double[]{x0, y0, z1 - FLANGE}, new double[]{x1, level, z1});
            u.beam(new double[]{x0, y0, z0 + FLANGE}, new double[]{x0 + FLANGE, level, z1 - FLANGE});
            u.beam(new double[]{x1 - FLANGE, y0, z0 + FLANGE}, new double[]{x1, level, z1 - FLANGE});
            u.beam(new double[]{x0 + FLANGE, y0, c - h - FLANGE}, new double[]{x1 - FLANGE, level, c - h});
            u.beam(new double[]{x0 + FLANGE, y0, c + h}, new double[]{x1 - FLANGE, level, c + h + FLANGE});
            u.beam(new double[]{a - h - FLANGE, y0, c - h}, new double[]{a - h, level, c + h});
            u.beam(new double[]{a + h, y0, c - h}, new double[]{a + h + FLANGE, level, c + h});
        }

        // ---- The ash lock ------------------------------------------------------------------------------------------

        /**
         * The ash chute down to its outlet, the bottom valve, the lock's cone, shell and head, the top valve up into the
         * grate housing; two hydraulic cylinders on brackets beside the lock (diagonally opposite, between the drives),
         * their rods sliding into the valves' lever boxes.
         */
        void ashLock() {
            var up = Frame.of(new double[]{a, 0, c}, 1, 1);
            int nc = ProcessUnit.sides(2 * st.rChute), nl = ProcessUnit.sides(2 * st.rAl);
            double s = st.s, rv = st.rChute + .12;
            u.mesh.prism(up, st.rChute, 1.3, s, nc, Material.PIPE);
            flanged(up, rv, s, s + st.valve, nl);
            u.mesh.frustum(up, st.rChute + .05, st.rAl, s + st.valve, st.y1, nl, Material.SHELL);
            u.mesh.prism(up, st.rAl, st.y1, st.y2, nl, Material.SHELL);
            u.mesh.head(up, st.rAl, st.y2, st.rAl / 2, nl, Material.HEAD);
            flanged(up, st.rValve, st.y3, st.y4, ProcessUnit.sides(2 * st.rValve));
            var axis = new double[]{a, 0, c};
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, 1.3, s, st.rChute, 0));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, s, s + st.valve, rv + .05, 0));
            u.solids.add(ProcessUnit.Solid.cone(axis, 1, s + st.valve, st.y1, st.rChute + .05, st.rAl, 0));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.y1, st.y2, st.rAl, st.rAl / 2));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.y3, st.y4, st.rValve + .05, 0));
            inside(s + st.valve, st.y1, st.rChute + .05, st.rAl);
            u.volumes.add(ProcessUnit.Solid.rod(axis, 1, st.y1, st.y2, .94 * st.rAl, .45 * st.rAl));
            u.round(a, c, st.y1, st.y2, st.rAl, 1);
            // The cylinders: lever boxes on the bottom valve, the cylinders on brackets up the shell, their rods between.
            double crank = st.crank, rc = st.rCyl, out = st.rAl + .12 + rc, box = Math.max(st.valve, crank + .25), lever = s + box;
            double length = Math.max(.4, Math.min(.6 + .1 * d, st.floor(DRIVES) - BEAM - .1 - (lever + crank + .15)));
            for (double t : new double[]{.75 * Math.PI, -.25 * Math.PI}) {
                double[] radial = {DeterministicMath.cos(t), 0, DeterministicMath.sin(t)}, tangent = {-radial[2], 0, radial[0]};
                double x = a + out * radial[0], z = c + out * radial[2];
                double reach = out + rc + .06 - rv;
                u.mesh.block(new double[]{a + (rv + reach / 2) * radial[0], s + box / 2, c + (rv + reach / 2) * radial[2]}, Mesh.scale(radial, reach / 2), new double[]{0, box / 2, 0},
                        Mesh.scale(tangent, rc + .06), Material.MACHINE);
                double c0 = lever + crank + .15, c1 = c0 + length;
                var f = Frame.of(new double[]{x, 0, z}, 1, 1);
                u.mesh.cylinder(f, rc, c0, c1, 12, Material.ACTUATOR);
                u.mesh.cylinder(f, rc + .025, c1 - .06, c1, 12, Material.ACTUATOR);
                for (double yb : new double[]{c0 + .15, c1 - .2})
                    u.mesh.block(new double[]{a + (st.rAl + .06) * radial[0], yb, c + (st.rAl + .06) * radial[2]}, Mesh.scale(radial, .08 + rc), new double[]{0, .04, 0},
                            Mesh.scale(tangent, .03), Material.STEELWORK);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{x, 0, z}, 1, c0, c1, rc + .025, 0));
                slider(f, .035 + .006 * d, lever - crank - .1, c0 + crank + .1, (c0 + lever) / 2, t < 0 ? .5 : 0);
            }
        }

        /**
         * A valve body of radius r from y0 to y1 about the stack's axis, flanged at both ends, the upper flange's nuts on
         * top (the lower's face the part under it); it encloses its inside.
         */
        void flanged(Frame up, double r, double y0, double y1, int n) {
            u.mesh.prism(up, r, y0 + .06, y1 - .06, n, Material.MACHINE);
            for (double y : new double[]{y0, y1 - .06}) u.mesh.cylinder(up, r + .07, y, y + .06, n, Material.FLANGE);
            u.mesh.bolts(up, Math.min(1, 2 * r), r, r + .07, y1, 1, Material.BOLT);
            inside(y0, y1, r, r);
        }

        /** The inside of a closed round part on the stack's axis from y0 to y1 (radius r0 to r1), a little inside its walls. */
        void inside(double y0, double y1, double r0, double r1) {
            u.volumes.add(ProcessUnit.Solid.cone(new double[]{a, 0, c}, 1, y0, y1, .94 * r0, .94 * r1, 0));
        }

        /**
         * A cylinder's rod along frame {@code f}'s vertical axis from y0 to y1, sliding up and down on a slider crank of
         * the lock's half stroke (a moving part, its pivot at height {@code at}); its ends stay in the cylinder and the
         * body it works.
         */
        void slider(Frame f, double r, double y0, double y1, double at, double phase) {
            u.mesh.part(u.motions.size());
            u.mesh.prism(f, r, y0, y1, 8, Material.PIPE);
            u.mesh.part(-1);
            double[] o = f.origin();
            u.motions.add(new ProcessUnit.Motion(new double[]{o[0], at, o[2]}, new double[]{0, 1, 0}, LOCK_SPEED, phase, st.crank, 5 * st.crank));
        }

        // ---- The gasifier ------------------------------------------------------------------------------------------

        /**
         * The grate housing's cone and its two drive sets (diagonally opposite: gearbox on the cone, coupling, motor out
         * on its pedestal), the bolted flange to the vessel, the clad vessel with its bands and head, its support lugs on
         * the support floor.
         */
        void gasifier() {
            var up = Frame.of(new double[]{a, 0, c}, 1, 1);
            var axis = new double[]{a, 0, c};
            int n = ProcessUnit.sides(2 * st.ro);
            u.mesh.annulus(up, st.rValve, st.rGate, st.y4, n, -1, Material.SHELL);
            u.mesh.frustum(up, st.rGate, st.ro - .04, st.y4, st.yb - .08, n, Material.SHELL);
            u.mesh.hoop(up, st.ro - .04, st.ro + .06, st.yb - .08, st.yb, n, Material.FLANGE);
            u.mesh.prism(up, st.ro, st.yb, st.yt, n, Material.CLADDING);
            u.mesh.head(up, st.ro, st.yt, st.ro / 2, n, Material.CLADDING);
            for (double y = st.yb + 2; y < st.yt - .5; y += 2) u.mesh.hoop(up, st.ro, st.ro + .03, y, y + .06, n, Material.CLADDING);
            u.solids.add(ProcessUnit.Solid.cone(axis, 1, st.y4, st.yb, st.rGate, st.ro + .06, 0));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.yb, st.yt, st.ro, st.ro / 2));
            u.volumes.add(ProcessUnit.Solid.cone(axis, 1, st.y4 + .1, st.yb, .94 * st.rGate, .94 * st.ro, 0));
            u.volumes.add(ProcessUnit.Solid.rod(axis, 1, st.yb, st.yt, .94 * st.ro, .45 * st.ro));
            u.round(a, c, st.yb, st.yt, st.ro, 1);
            // The drive sets at the cone's middle, on the diagonals clear of the floors' walkways.
            double yd = (st.y4 + st.yb) / 2, rd = st.gate(yd), deck = st.floor(DRIVES) + Access.DECK;
            for (double t : new double[]{.25 * Math.PI, 1.25 * Math.PI}) drive(t, yd, rd, deck);
            // The support lugs on the support floor's beams, on the diagonals.
            int support = st.floor(SUPPORT);
            for (int k = 0; k < 4; k++) {
                double t = (k + .5) * Math.PI / 2;
                double[] radial = {DeterministicMath.cos(t), 0, DeterministicMath.sin(t)}, tangent = {-radial[2], 0, radial[0]};
                double ly = support + Access.DECK + .25;
                u.mesh.block(new double[]{a + (st.ro + .25) * radial[0], ly, c + (st.ro + .25) * radial[2]}, Mesh.scale(radial, .25), new double[]{0, .25, 0},
                        Mesh.scale(tangent, .05), Material.SUPPORT);
                u.mesh.block(new double[]{a + (st.ro + .25) * radial[0], support + Access.DECK + .02, c + (st.ro + .25) * radial[2]}, Mesh.scale(radial, .28),
                        new double[]{0, .02, 0}, Mesh.scale(tangent, .2), Material.SUPPORT);
            }
        }

        /**
         * A grate drive set at angle t round the grate housing at height y (its cone radius r there): the gearbox on the
         * cone and its pedestal from the deck, the turning coupling, the kit's motor on its pedestal ({@link
         * ProcessUnit#motor(Frame, double, double, double, double, double, ProcessUnit.MotorDetail)}, plain: a small drive
         * among large parts), all along the radius.
         */
        void drive(double t, double y, double r, double deck) {
            double[] radial = {DeterministicMath.cos(t), 0, DeterministicMath.sin(t)};
            var f = new Frame(new double[]{a, y, c}, radial, new double[]{0, 1, 0});
            double gb = .45 + .1 * d, gh = .5 + .08 * d, gw = .55 + .1 * d, coupling = .25 + .05 * d, rm = .22 + .04 * d, lm = .8 + .15 * d;
            double g0 = r - .1, g1 = r + gb, m0 = g1 + coupling, m1 = m0 + lm;
            double[] tangent = {-radial[2], 0, radial[0]};
            u.mesh.block(f.along((g0 + g1) / 2), Mesh.scale(radial, (g1 - g0) / 2), new double[]{0, gh / 2, 0}, Mesh.scale(tangent, gw / 2), Material.MACHINE);
            u.mesh.block(Mesh.plus(f.along((g0 + g1) / 2), new double[]{0, gh / 2 + .04, 0}), Mesh.scale(radial, (g1 - g0) / 2 - .06), new double[]{0, .04, 0},
                    Mesh.scale(tangent, gw / 2 - .06), Material.MACHINE);
            u.mesh.cylinder(f, .12 + .03 * d, g1, g1 + .06, 12, Material.MACHINE);
            u.mesh.cylinder(f, .12 + .03 * d, m0 - .06, m0, 12, Material.MOTOR);
            u.shaft(f, .04 + .01 * d, new double[][]{{g1 + .06, m0 - .06}}, (g1 + m0) / 2);
            u.motor(f, rm, m0, m1, 0, deck, ProcessUnit.MotorDetail.PLAIN);
            if (y - gh / 2 - deck > .05)
                u.mesh.block(new double[]{a + (g0 + g1) / 2 * radial[0], (deck + y - gh / 2) / 2, c + (g0 + g1) / 2 * radial[2]}, Mesh.scale(radial, (g1 - g0) / 2 - .05),
                        new double[]{0, (y - gh / 2 - deck) / 2, 0}, Mesh.scale(tangent, gw / 2 - .1), Material.STEELWORK);
            // Collision: the box round the gearbox and coupling, axis-aligned (the motor collides as the kit draws it).
            var lo = new double[]{Double.MAX_VALUE, deck, Double.MAX_VALUE};
            var hi = new double[]{-Double.MAX_VALUE, y + gh / 2 + .08, -Double.MAX_VALUE};
            for (double s : new double[]{g0 + .3, m0})
                for (double side : new double[]{-1, 1}) {
                    double x = a + s * radial[0] + side * gw / 2 * tangent[0], z = c + s * radial[2] + side * gw / 2 * tangent[2];
                    lo[0] = Math.min(lo[0], x);
                    lo[2] = Math.min(lo[2], z);
                    hi[0] = Math.max(hi[0], x);
                    hi[2] = Math.max(hi[2], z);
                }
            u.solids.add(ProcessUnit.Solid.box(lo, hi));
        }

        // ---- The coal lock and bunker ------------------------------------------------------------------------------

        /**
         * The coal lock's bottom valve on the vessel's head, its cone, shell and head, its top valve; the two hydraulic
         * cylinders on its head, their rods sliding into it; the bunker's chute, cone, shell and roof; the distributor
         * drive on the coal lock's floor; the vent's line from the lock up through the roof.
         */
        void coalLock() {
            var up = Frame.of(new double[]{a, 0, c}, 1, 1);
            var axis = new double[]{a, 0, c};
            int nl = ProcessUnit.sides(2 * st.rCl), nb = ProcessUnit.sides(2 * st.rBunker), nv = ProcessUnit.sides(2 * st.rValve);
            flanged(up, st.rValve, st.apex - .1, st.y5, nv);
            u.mesh.frustum(up, st.rNeck + .05, st.rCl, st.y5, st.y6, nl, Material.SHELL);
            u.mesh.prism(up, st.rCl, st.y6, st.y7, nl, Material.SHELL);
            u.mesh.head(up, st.rCl, st.y7, st.rCl / 2, nl, Material.HEAD);
            flanged(up, st.rValve, st.y8, st.y9, nv);
            u.mesh.prism(up, st.rNeck, st.y9, st.y10a + .05, nv, Material.SHELL);
            u.mesh.frustum(up, st.rNeck + .05, st.rBunker, st.y10a, st.y10, nb, Material.SHELL);
            u.mesh.annulus(up, st.rNeck, st.rNeck + .05, st.y10a, nb, -1, Material.SHELL);
            u.mesh.prism(up, st.rBunker, st.y10, st.y11, nb, Material.SHELL);
            u.mesh.hoop(up, st.rBunker, st.rBunker + .05, st.y11 - .1, st.y11, nb, Material.SHELL);
            u.mesh.annulus(up, 0, st.rBunker, st.y11, nb, 1, Material.SHELL);
            for (double y = st.y10 + 1; y < st.y11 - .5; y += 1.5) u.mesh.hoop(up, st.rBunker, st.rBunker + .04, y, y + .12, nb, Material.SHELL);
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.apex - .3, st.y5, st.rValve + .07, 0));
            u.solids.add(ProcessUnit.Solid.cone(axis, 1, st.y5, st.y6, st.rNeck + .05, st.rCl, 0));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.y6, st.y7, st.rCl, st.rCl / 2));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.y8, st.y10a, st.rValve + .07, 0));
            u.solids.add(ProcessUnit.Solid.cone(axis, 1, st.y10a, st.y10, st.rNeck + .05, st.rBunker, 0));
            u.solids.add(ProcessUnit.Solid.rod(axis, 1, st.y10, st.y11, st.rBunker + .05, 0));
            inside(st.y5, st.y6, st.rNeck + .05, st.rCl);
            u.volumes.add(ProcessUnit.Solid.rod(axis, 1, st.y6, st.y7, .94 * st.rCl, .45 * st.rCl));
            inside(st.y9, st.y10a, st.rNeck, st.rNeck);
            inside(st.y10a, st.y10, st.rNeck + .05, st.rBunker);
            u.volumes.add(ProcessUnit.Solid.rod(axis, 1, st.y10, st.y11 - .05, .94 * st.rBunker, 0));
            u.round(a, c, st.y6, st.y7, st.rCl, 1);
            u.round(a, c, st.y10, st.y11, st.rBunker, 1);
            // The cylinders on the head, either side of the top valve: rods into the head, bodies up beside the chute.
            double rc = st.rCyl, crank = st.crank;
            for (int side = -1; side <= 1; side += 2) {
                double x = a + side * st.xCyl, surface = st.y7 + st.rCl / 2 * Math.sqrt(1 - st.xCyl * st.xCyl / (st.rCl * st.rCl));
                double c0 = surface + crank + .15, c1 = Math.min(c0 + .6 + .1 * d, st.y10a + .1);
                var f = Frame.of(new double[]{x, 0, c}, 1, 1);
                u.mesh.cylinder(f, rc + .03, surface - .1, surface + .06, 12, Material.FLANGE);
                u.mesh.cylinder(f, rc, c0, c1, 12, Material.ACTUATOR);
                u.mesh.cylinder(f, rc + .025, c0, c0 + .06, 12, Material.ACTUATOR);
                u.mesh.cylinder(f, rc + .025, c1 - .06, c1, 12, Material.ACTUATOR);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{x, 0, c}, 1, c0, c1, rc + .025, 0));
                slider(f, .035 + .006 * d, surface - crank - .1, c0 + crank + .1, (surface + c0) / 2, side < 0 ? .25 : .75);
            }
            // The distributor drive on the coal lock's floor, back and west of the lock: gearbox and vertical motor.
            int floor = st.floor(COAL_BOTTOM);
            if (floor > 0) {
                double deck = floor + Access.DECK, t = .75 * Math.PI, r = st.opening(floor) + Access.GAP + .55, gx = a + r * DeterministicMath.cos(t), gz = c + r * DeterministicMath.sin(t);
                double g = .3 + .05 * d, rm = .18 + .03 * d;
                u.box(new double[]{gx - g, deck, gz - g}, new double[]{gx + g, deck + .5 + .05 * d, gz + g}, Material.MACHINE, true);
                var m = Frame.of(new double[]{gx, 0, gz}, 1, 1);
                double m0 = deck + .5 + .05 * d;
                u.mesh.cylinder(m, rm, m0, m0 + .7 + .1 * d, 12, Material.MOTOR);
                u.mesh.cylinder(m, rm + .03, m0 + .7 + .1 * d, m0 + .8 + .1 * d, 12, Material.MOTOR);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{gx, 0, gz}, 1, m0, m0 + .8 + .1 * d, rm + .03, 0));
                double yl = deck - .6;
                u.line(.05, Material.PIPE, new double[]{gx, deck, gz}, new double[]{gx, yl, gz}, new double[]{a + (st.ro * .4) * DeterministicMath.cos(t), yl, c + (st.ro * .4) * DeterministicMath.sin(t)});
            }
            // The vent: out of the lock's upper shell, west, up through the roof.
            double xv = a - ventOut(), zv = c - 1, yv = st.y7 - .3, r = ventOd() / 2, tv = DeterministicMath.atan2(zv - c, xv - a);
            u.line(r, Material.PIPE, new double[]{a + .95 * st.rCl * DeterministicMath.cos(tv), yv, c + .95 * st.rCl * DeterministicMath.sin(tv)}, new double[]{xv, yv, zv},
                    new double[]{xv, st.roof + Access.DECK + .25, zv});
        }

        /** The vent riser's distance west of the axis: whole metres clear of the bunker. */
        double ventOut() {
            return Math.ceil(st.rBunker + .35);
        }

        double ventOd() {
            return ColumnStructure.size(.05 + .02 * d, .15);
        }

        // ---- The jacket's steam drum -------------------------------------------------------------------------------

        /** The jacket's steam drum on the coal lock's floor east of the lock, its two risers, downcomer, safety valve, and the jacket steam's line down to the agent steam at the grate housing. */
        void jacket() {
            int floor = st.floor(COAL_BOTTOM);
            double rd = .125 * d + .05, ld = .8 * d, deck = (floor > 0 ? floor : st.floor(COAL_TOP)) + Access.DECK;
            double xd = (a + st.opening((int) (deck - Access.DECK)) + Access.GAP + x1) / 2, yd = deck + .45 + rd, zd0 = c + 1, zd1 = zd0 + ld;
            var f = Frame.of(new double[]{xd, yd, 0}, 2, 1);
            int n = ProcessUnit.sides(2 * rd);
            u.mesh.prism(f, rd, zd0, zd1, n, Material.CLADDING);
            u.mesh.head(f, rd, zd1, rd / 2, n, Material.CLADDING);
            u.mesh.head(Frame.of(new double[]{xd, yd, 0}, 2, -1), rd, -zd0, rd / 2, n, Material.CLADDING);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{xd, yd, 0}, 2, zd0 - rd / 2, zd1, rd, rd / 2));
            u.volumes.add(ProcessUnit.Solid.rod(new double[]{xd, yd, 0}, 2, zd0, zd1, .9 * rd, .4 * rd));
            u.volumes.add(ProcessUnit.Solid.cone(new double[]{xd, yd, 0}, 2, zd0 - .4 * rd, zd0, .1 * rd, .9 * rd, 0));
            u.round(xd, yd, zd0, zd1, rd);
            for (double z : new double[]{zd0 + .2 * ld, zd1 - .2 * ld}) {
                u.box(new double[]{xd - .7 * rd, deck, z - .08}, new double[]{xd + .7 * rd, yd - .6 * rd, z + .08}, Material.SUPPORT, true);
            }
            // The safety valve and its vent stack.
            var sv = Frame.of(new double[]{xd, 0, zd0 + .5 * ld}, 1, 1);
            u.mesh.cylinder(sv, .06 + .01 * d, yd + rd - .05, yd + rd + .3, 8, Material.FLANGE);
            u.mesh.cylinder(sv, .04 + .01 * d, yd + rd + .3, yd + rd + 1.6, 8, Material.PIPE);
            // Risers from the jacket's top into the drum, a downcomer from its back end to the jacket's bottom.
            double rr = .04 + .01 * d, yr = st.yt - .3;
            for (double z : new double[]{zd0 + .25 * ld, zd0 + .75 * ld}) {
                double t = DeterministicMath.atan2(z - c, xd - a);
                u.line(rr, Material.CLADDING, new double[]{a + (st.ro - .05) * DeterministicMath.cos(t), yr, c + (st.ro - .05) * DeterministicMath.sin(t)}, new double[]{xd, yr, z},
                        new double[]{xd, yd - .9 * rd, z});
            }
            double zDown = zd1 - .3 * ld, yDown = st.yb + .4, tDown = DeterministicMath.atan2(zDown - c, xd - a);
            u.line(rr, Material.CLADDING, new double[]{xd, yd - .9 * rd, zDown}, new double[]{xd, yDown, zDown},
                    new double[]{a + (st.ro - .05) * DeterministicMath.cos(tDown), yDown, c + (st.ro - .05) * DeterministicMath.sin(tDown)});
            // The jacket steam down the vessel's back into the grate housing beside the agent steam.
            double ya = agentY() + 1, xs = a + .8, zs = c + st.ro + .5;
            u.line(rr, Material.CLADDING, new double[]{xd, yd + .9 * rd, zd0 + .35 * ld}, new double[]{xd, yd + rd + .45, zd0 + .35 * ld}, new double[]{xs, yd + rd + .45, zd0 + .35 * ld},
                    new double[]{xs, yd + rd + .45, zs}, new double[]{xs, ya, zs}, new double[]{xs, ya, c + .8 * st.radius(ya)});
        }

        // ---- The wash cooler ---------------------------------------------------------------------------------------

        double washRadius() {
            return .15 * d + .05;
        }

        double washTop() {
            return st.yGas + st.rGas + .3;
        }

        double washBottom() {
            return washTop() - 1.5 * d;
        }

        /** The raw gas duct from the vessel's east side to the wash cooler, its spray stubs; the wash cooler and its lugs. */
        void washCooler() {
            double rw = washRadius(), top = washTop(), bottom = washBottom(), rg = st.rGas, y = st.yGas;
            int n = ProcessUnit.sides(2 * rw), ng = ProcessUnit.sides(2 * rg);
            var duct = Frame.of(new double[]{0, y, c}, 0, 1);
            u.mesh.prism(duct, rg, a + st.ro - .1, xw - .5 * rw, ng, Material.CLADDING);
            u.mesh.ring(duct, rg, rg + .07, a + st.ro, a + st.ro + .07, ng, 3, Material.FLANGE);
            u.mesh.ring(duct, rg, rg + .07, a + st.ro + .07, a + st.ro + .14, ng, 3, Material.FLANGE);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{0, y, c}, 0, a + st.ro, xw - .5 * rw, rg + .07, 0));
            for (double x : new double[]{(a + st.ro + xw) / 2 - .4, (a + st.ro + xw) / 2 + .4})
                u.mesh.cylinder(Frame.of(new double[]{x, 0, c}, 1, 1), .03 + .005 * d, y + rg - .05, y + rg + .3, 8, Material.PIPE);
            var up = Frame.of(new double[]{xw, 0, c}, 1, 1);
            u.mesh.prism(up, rw, bottom, top, n, Material.CLADDING);
            u.mesh.head(up, rw, top, rw / 2, n, Material.CLADDING);
            u.mesh.head(Frame.of(new double[]{xw, 0, c}, 1, -1), rw, -bottom, rw / 2, n, Material.CLADDING);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{xw, 0, c}, 1, bottom - rw / 2, top, rw, rw / 2));
            u.volumes.add(ProcessUnit.Solid.rod(new double[]{xw, 0, c}, 1, bottom, top, .9 * rw, .4 * rw));
            u.volumes.add(ProcessUnit.Solid.cone(new double[]{xw, 0, c}, 1, bottom - .4 * rw, bottom, .1 * rw, .9 * rw, 0));
            u.round(xw, c, bottom, top, rw, 1);
            double ys = top - .3;
            for (int side = -1; side <= 1; side += 2)
                u.box(new double[]{xw + side * rw - .08, ys, c - .1}, new double[]{xw + side * (rw + .32) + .08, ys + .25, c + .1}, Material.SUPPORT, false);
        }

        // ---- Nozzles -----------------------------------------------------------------------------------------------

        /** The agent nozzles' height: the cell centre 1.5 m over the drive floor's deck. */
        double agentY() {
            return st.floor(DRIVES) + 1.5;
        }

        void nozzles() {
            double ya = agentY();
            double ox = clamp(.03 * d, .05, .2), steam = clamp(.05 * d, .08, .3);
            u.nozzles.add(side(Role.OXIDANT, ya, -1, ColumnStructure.size(ox, ox)));
            u.nozzles.add(side(Role.STEAM, ya, 1, ColumnStructure.size(steam, steam)));
            // Feedwater low on the jacket's front, two rows at least over the oxidant, clear of the floors.
            double fw = .1, yf = clear(Math.max(st.yb + .5, ya + 2), st.yt - 1);
            u.nozzles.add(side(Role.FEEDWATER, yf, -1, fw));
            // Lock gas into the ash lock's front, two rows clear of the oxidant; where a floor crosses the lock's shell, under
            // it on the lock's cone (a pipe's largest neck under the floor's beams, owner decision 2026-10-21: a 1 m pipe has
            // a 1 m root; at D 2 m the drive floor crosses the shell, 2026-10-25).
            double lock = .1, yn = clear(st.y1 + .3, st.y2 - .3);
            if (!free(yn)) yn = clear(st.s + st.valve, st.y2 - .3);
            if (Math.abs(yn - ya) < 2) yn = clear(Math.max(st.y1 + .2, ya - 4), ya - 2);
            u.nozzles.add(side(Role.NITROGEN, yn, -1, lock));
            // The vent up through the roof.
            double vent = ventOd(), base = st.roof + Access.DECK + .2;
            u.nozzles.add(ProcessUnit.end(Role.VENT, new double[]{a - ventOut(), base, c - 1}, 1, 1, vent));
            // Coal into the bunker's roof, ash out of the chute 1 m over grade.
            double coal = ColumnStructure.size(.2 * d + .2, PipeSizes.MAX_OUTSIDE), roof = st.y11;
            u.nozzles.add(ProcessUnit.end(Role.SOLIDS_INLET, new double[]{a, roof - .03, c}, 1, 1, coal));
            double ash = 2 * st.rChute;
            u.nozzles.add(ProcessUnit.end(Role.SOLIDS_OUTLET, new double[]{a, 1.3, c}, 1, -1, ColumnStructure.size(ash, Math.min(ash, PipeSizes.MAX_OUTSIDE))));
            // The wash cooler: quench liquor on top, gas liquor out of the bottom, syngas out of its east side near the bottom.
            // Each neck rooted where a pipe's largest neck's edge meets the cooler (owner decision 2026-10-21: a 1 m pipe has
            // a 1 m root): a head's under the head, the syngas's under the shell.
            double rw = washRadius(), top = washTop() + rw / 2, bottom = washBottom() - rw / 2, liquor = clamp(.03 * d, .05, .15), gas = clamp(.1 * d, .2, .6);
            double big = PipeSizes.MAX_OUTSIDE / 2, edge = big / rw, dip = rw / 2 * (1 - Math.sqrt(Math.max(0, 1 - edge * edge))) + .04;
            u.nozzles.add(new Nozzle(Role.QUENCH, new double[]{xw, top - dip, c}, 1, 1, Math.ceil(top + ProcessUnit.NECK) - top + dip, liquor, 0));
            double faceL = Math.floor(bottom - ProcessUnit.NECK);
            u.nozzles.add(new Nozzle(Role.LIQUID_OUTLET, new double[]{xw, bottom + dip, c}, 1, -1, bottom + dip - faceL, liquor, 0));
            double ys = Math.ceil(Math.max(washBottom() + gas / 2 + .15, faceL + 1.5) - .5) + .5;
            double apothem = rw * DeterministicMath.cos(Math.PI / ProcessUnit.sides(2 * rw)), start = Math.sqrt(Math.max(0, apothem * apothem - big * big)) - .03;
            u.nozzles.add(new Nozzle(Role.SYNGAS, new double[]{xw + start, ys, c}, 0, 1, Math.ceil(xw + rw + ProcessUnit.NECK) - xw - start,
                    ColumnStructure.size(gas, gas), 0));
        }

        /** The lowest cell centre from {@code lo} to {@code hi} whose cell is {@link #free}; {@code lo}'s where there is none. */
        double clear(double lo, double hi) {
            for (double y = Math.ceil(lo - .5) + .5; y <= hi + 1e-9; y++) if (free(y)) return y;
            return Math.ceil(lo - .5) + .5;
        }

        /** Whether the cell round centre height {@code y} keeps clear of every floor's deck and beams. */
        boolean free(double y) {
            for (var f : st.floors) if (y + .5 > f[0] - BEAM - .05 && y - .5 < f[0] + Access.DECK + .05) return false;
            return true;
        }

        /**
         * A nozzle out of the stack's front ({@code sign} -1, frame -z) or back (+1) at height y: its neck from under the
         * surface where the stack is narrowest over a pipe's largest neck's height ({@link #radius}, as
         * {@link ProcessUnit#radialSide}; owner decision 2026-10-21: a 1 m pipe has a 1 m root), its flange face on the
         * block face beyond the stack's widest over the pipe block's height (the grate housing's cone widens upwards).
         */
        Nozzle side(Role role, double y, int sign, double defaultOd) {
            double lo = Double.MAX_VALUE, widest = 0, big = PipeSizes.MAX_OUTSIDE / 2;
            for (double h = y - big; h <= y + big + 1e-9; h += .025) lo = Math.min(lo, st.radius(h));
            for (double h = Math.floor(y); h <= Math.floor(y) + 1 + 1e-9; h += .05) widest = Math.max(widest, st.radius(h));
            double r = lo - .06;
            int n = ProcessUnit.sides(2 * r);
            double apothem = r * DeterministicMath.cos(Math.PI / n), start = Math.sqrt(Math.max(0, apothem * apothem - big * big)) - .03, base = c + sign * start;
            double plane = sign < 0 ? Math.floor(c - widest - ProcessUnit.NECK) : Math.ceil(c + widest + ProcessUnit.NECK);
            return new Nozzle(role, new double[]{a, y, base}, 2, sign, Math.abs(plane - base), defaultOd, 0);
        }
    }

    /**
     * The house's bay, m: the least odd number of metres (the axis on a cell centre) of D + 4.6 + 2 {@link ProcessUnit#NECK}
     * (D + 5.09) at least, about the survey's D + 5, so that a nozzle out of the vessel's front, its face a pipe's largest
     * root out (owner decision 2026-10-21; D + 4.9 under the 0.15 m stand-out), keeps its pipe a block inside the house's
     * edge rails.
     */
    static int bay(double d) {
        int b = (int) Math.ceil(d + 4.6 + 2 * ProcessUnit.NECK - 1e-9);
        return b % 2 == 0 ? b + 1 : b;
    }

    /** The locks' cylinders' shown speed, rev/s: a stroke out and back every 30 s (the locks cycle several times an hour). */
    static final double LOCK_SPEED = 1 / 30.0;
}
