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
 * A grate-fired stoker boiler ({@code NEW_UNITS_SURVEY_SOLID_FUEL.md} section 2, "Grate boiler"; owner decisions D1 and D4
 * of 2026-10-18, batch 2026-10-15-custom-equipment-rendering): lump fuel burnt in a thin layer on a reciprocating grate
 * in excess air, its heat raising steam in water walls and a boiler, its flue gas leaving through an air heater, an
 * induced-draught fan and a drawn stack (survey Q3: flue gas does not fit a 1 m pipe and stays inside the unit).
 *
 * <p>Sizes (survey section 2): the main size is the grate's width Wg, 2 to 12 m on 0.5 m steps; the second its length Lg,
 * 3 to 10 m; the support its height over the ground, 3 to 6 m (survey: small units stand on a plinth with the grate at 2
 * to 4 m; 3 m is the least that keeps siftings hoppers and the ash trough under it). The furnace is Wg + 0.4 m wide
 * inside (the grate's side seals) and Lg + 1 m deep, its membrane walls, insulation and profiled cladding 0.3 m thick, and
 * clamp(10 + 2.2 Wg, 12, 36) m high over the grate (survey, typ.). Its fuel is 1.5 MW per square metre of grate (survey:
 * 1 to 2 MW/m², B&W's water-cooled grate up to 2.5): 9 to 180 MW.
 *
 * <p>Front to back (survey): the firing aisle across the front, one hydraulic ram feeder per 1.5 to 2 m of width under a
 * day bin, a drag conveyor over the bins; the furnace with buckstays every 3.5 m; three to five under-grate air plenums
 * (one per 2 m of grate) in the furnace's lower casing, a siftings hopper under each per 3.2 m of width [VG-pat]; over-fire
 * air in two rows across the rear wall (three from 8 m wide); at the grate's end the ash drop into the submerged drag-chain
 * trough, 0.8 to 1.5 m wide, running across under the furnace and rising at 32° (survey: 30 to 35°) to its head drive and
 * discharge chute east of the boiler (its signature). The boiler (survey: a bi-drum boiler to about 100 t/h): to 6 m wide
 * (at most 60 m² of grate, 23 m of furnace) a steam drum lying front to rear over a convection bank down to a mud drum
 * beside the furnace (+x), the furnace bottom-supported on columns; wider, a single drum lying across the roof over a
 * back pass behind the furnace, 0.35 of its depth (2 to 4 m), its lower 0.55 of the furnace's height, the furnace and back
 * pass hung from full-height columns. Behind the boiler the tubular air heater (0.25 of the furnace's height, survey
 * section 1, with its ash hoppers), its hot air down to the plenums' air main along the furnace's east side and up to the
 * over-fire air; the flue duct down to the fan (wheel 0.3 + 0.1 Wg m round, at most 1.5) and the stack beside it,
 * clamp(0.2 Wg + 0.6, 1, 3) m round (calc: 3 m carries the 75 m³/s of 180 MW at 11 m/s), 10 m over the roof.
 *
 * <p>Access (owner decision D4): the firing aisle's deck across the front at a metre under the grate, the drum deck over
 * the roof, and the kit's stair tower beside the west wall from the ground to both ({@link ProcessUnit#stairTower}), its
 * landing on the aisle in front of the furnace; the drum deck overhangs the front where the tower's top landing is there.
 * Moving parts (survey): each ram feeder's rod and crosshead slide; the drag chain's head shaft and both motors' fans
 * turn, as does the fan's shaft.
 *
 * <p>Nozzles (survey section 1 sizes with Wg for W), each facing the same way in both forms: the steam up from the drum,
 * clamp(0.025 Wg + 0.1, 0.15, 0.6); the feedwater east, into the drum's side (bi-drum) or the economiser's inlet on the
 * back pass's side, clamp(0.015 Wg + 0.08, 0.1, 0.45); the continuous blowdown east out of the drum's side or end, 0.05
 * to 0.08; the water walls' drain down from the rear lower
 * header to the ground, 0.05; the start-up burner's fuel out of the front wall over the bins, 0.05 to 0.1; the fuel into
 * the conveyor's end, and the ash down out of the trough's discharge chute.
 */
final class GrateBoilerDesign extends UnitDesign {
    /** Fuel burnt per square metre of grate, MW (survey: 1 to 2 MW/m² on a grate). */
    static final double HEAT_RELEASE = 1.5;
    /** The boiler's efficiency and the steam's enthalpy rise from feedwater, MJ/kg (about 105 °C water to 40 bar, 400 °C: 2.77). */
    static final double EFFICIENCY = .85, STEAM_ENTHALPY = 2.8;
    /** The widest grate under a bi-drum boiler, m: 60 m² of grate at most, about 100 t/h (survey), a furnace up to 23 m. */
    static final double BI_DRUM_WIDTH = 6;

    GrateBoilerDesign() {
        super(new Sizes(2, 12, .5,
                3, 10,
                3, 6,
                Orientation.VERTICAL, "grate_boiler.width", "grate_boiler.length", "grate_boiler.grate", true,
                1));
    }

    /** A new grate's length: 1.1 times its width (survey default clamp(1.1 Wg, 3, 10)). */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), (int) Math.round(1.1 * size)));
    }

    @Override
    public Line held(EquipmentShape shape) {
        int l = (int) Math.round(shape.tangentLength());
        return new Line("grate_boiler.duty", whole(fuel(shape.diameter(), l)), whole(steam(shape.diameter(), l)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double w = shape.diameter();
        int l = (int) Math.round(shape.tangentLength());
        return List.of(new Line("grate_boiler.furnace", size(w), l, number(height(w))), new Line(w <= BI_DRUM_WIDTH + 1e-9 ? "grate_boiler.bi_drum" : "grate_boiler.back_pass"),
                new Line("grate_boiler.duty", whole(fuel(w, l)), whole(steam(w, l))));
    }

    /** The furnace's height over the grate, m (survey section 2, typ.). */
    static double height(double w) {
        return clamp(10 + 2.2 * w, 12, 36);
    }

    /** The fuel burnt, MW. */
    static double fuel(double w, int l) {
        return HEAT_RELEASE * w * l;
    }

    /** The steam raised, t/h. */
    static double steam(double w, int l) {
        return fuel(w, l) * EFFICIENCY / STEAM_ENTHALPY * 3.6;
    }

    @Override
    void build(ProcessUnit unit) {
        new Build(unit).run();
    }

    /** One boiler's drawing: its sizes and stations, then its parts. */
    private static final class Build {
        /** The casing's thickness (membrane wall, insulation, cladding), a buckstay's depth out of it and height, m. */
        static final double WALL = .3, STAY = .2, STAY_HEIGHT = .3;
        /** A column's section, a deck beam's depth, m. */
        static final double COLUMN = .35, BEAM = .3;
        /** The drag chain's rise, radians (survey: 30 to 35°). */
        static final double RISE = Math.toRadians(32);
        /** A ram's speed, rev/s of its slider-crank: a stroke out and back every 30 s (real rams every 1 to 5 min). */
        static final double RAM_SPEED = 1 / 30.0;

        final ProcessUnit u;
        final double w, s, h, yL, yR, deck;
        final int l;
        final boolean bi;
        // The firing aisle: feeders, their pitch, stroke, housing, crosshead travel, cylinder, reach and the aisle's depth.
        final int feeders;
        final double pitch, stroke, housing, travel, barrel, reach, aisle, ram;
        // Plan stations: the furnace's casing, the west wall the stair tower stands by, the bank, the aisle's east end.
        final double fx0, fx1, zf, zb, wall, bank, bmid, bx1, xEnd, zA0;
        // Under the grate: the ash drop, the plenums' span and pitch; the trough's width, height, line and rise's foot.
        final int plenums;
        final double drop, zg0, zg1, pl, tw, th, zt, xr0;
        // The air main along the furnace's east side; the back pass's depth and bottom; the air heater.
        final double am, xa0, xa1, dp, ybp, ax0, ax1, az0, az1, ah0, ah1;
        // The mud drum's line (bi-drum), the steam drum's radius and line.
        final double yM, rs, yS;

        Build(ProcessUnit u) {
            this.u = u;
            w = u.shape.diameter();
            l = (int) Math.round(u.shape.tangentLength());
            s = u.shape.support();
            h = height(w);
            bi = w <= BI_DRUM_WIDTH + 1e-9;
            yL = s - .9;
            yR = s + h;
            deck = s - 1;
            feeders = Math.max(1, (int) Math.round(w / 1.75));
            pitch = (w + .4) / feeders;
            stroke = clamp(.4 + .06 * l, .5, 1);
            housing = 1.1 + stroke / 2;
            travel = stroke + .35;
            barrel = stroke + .35;
            reach = housing + travel + barrel;
            aisle = reach + 1.5;
            ram = Math.min(1.2, .7 * pitch);
            double b = w + .4 + 2 * WALL, f = l + 1 + 2 * WALL;
            zA0 = .1;
            zf = zA0 + aisle;
            zb = zf + f;
            // The stair tower's outer posts 2.75 m out from the west wall, which stands 0.3 m off the casing (over its
            // buckstays); a bi-drum unit's bank's middle, where its drums' nozzles stand, on a cell centre.
            double base = 3.05;
            if (bi) {
                double bank0 = clamp(.3 * w + 2.2, 2.8, 4), mid0 = base + b + bank0 / 2;
                fx0 = base + Math.ceil(mid0 - .5) + .5 - mid0;
                bank = bank0;
                bmid = fx0 + b + bank / 2;
            } else {
                fx0 = base;
                bank = 0;
                bmid = 0;
            }
            fx1 = fx0 + b;
            bx1 = fx1 + bank;
            wall = fx0 - .3;
            xEnd = bx1 + .3;
            plenums = (int) clamp(Math.round(l / 2.0), 3, 5);
            tw = clamp(.6 + .07 * w, .8, 1.5);
            th = clamp(.8 + .04 * w, .9, 1.3);
            drop = Math.max(clamp(.6 + .05 * l, .7, 1.1), tw - .2);
            zg0 = zf + WALL;
            zg1 = zb - WALL - drop;
            pl = (zg1 - zg0) / plenums;
            zt = Math.floor(zb - WALL - drop / 2) + .5;
            xr0 = bx1 + 1;
            am = clamp(.4 + .05 * w, .5, 1);
            xa0 = fx1 + .35;
            xa1 = xa0 + am;
            dp = bi ? 0 : clamp(.35 * f, 2, 4);
            ybp = yR - .55 * h;
            if (bi) {
                ax0 = fx1 + .2;
                ax1 = bx1 - .2;
                az0 = zb + .15 + am;
                az1 = az0 + clamp(.15 * f + 1.2, 1.5, 3);
                ah0 = s + 1.6;
                ah1 = ah0 + clamp(.25 * h, 3, 5);
            } else {
                ax0 = fx0 + .2;
                ax1 = fx1 - .2;
                az0 = zb + .1;
                az1 = zb + dp - .1;
                ah1 = ybp;
                ah0 = ybp - clamp(.2 * h, 3, 7);
            }
            yM = s + .4;
            rs = .5 + .03 * w;
            // The steam drum's line on a cell centre high enough that its side lines at a pipe's largest pass over the drum
            // deck's rail (owner decision 2026-10-21: a 1 m pipe has a 1 m root, other components give way).
            yS = Math.ceil(yR + Access.DECK + Access.FENCE + .05 + PipeSizes.MAX_OUTSIDE / 2 - .5) + .5;
        }

        void run() {
            furnace();
            columns();
            underGrate();
            trough();
            feeders();
            overFireAir();
            if (bi) biDrum();
            else backPass();
            airHeater();
            fanAndStack();
            access();
            nozzles();
        }

        // ---- Furnace -----------------------------------------------------------------------------------------------

        /**
         * The furnace's casing: the plenums' lower band (MACHINE) from the casing's bottom to the grate, the clad walls
         * over it to the roof, the bank or back pass beside or behind it sharing a wall, buckstays every 3.5 m round them
         * from 2.5 m over the grate, outlined along the roof's edges.
         */
        void furnace() {
            u.box(new double[]{fx0, yL, zf}, new double[]{fx1, s, zb}, Mesh.ALL_FACES & ~8, Material.MACHINE, false);
            int upper = Mesh.ALL_FACES & ~4 & ~(bi ? 2 : 32);
            u.box(new double[]{fx0, s, zf}, new double[]{fx1, yR, zb}, upper, Material.CLADDING, false);
            if (bi) u.mesh.flat(List.of(new double[]{fx1, s, zf}, new double[]{fx1, s, zb}, new double[]{fx1, yM, zb}, new double[]{fx1, yM, zf}), new double[]{1, 0, 0},
                    Material.CLADDING);
            else u.mesh.flat(List.of(new double[]{fx0, s, zb}, new double[]{fx1, s, zb}, new double[]{fx1, ybp, zb}, new double[]{fx0, ybp, zb}), new double[]{0, 0, 1},
                    Material.CLADDING);
            u.solids.add(ProcessUnit.Solid.box(new double[]{fx0, yL, zf}, new double[]{fx1, yR, zb}));
            u.volumes.add(ProcessUnit.Solid.box(new double[]{fx0 + .1, yL + .1, zf + .1}, new double[]{fx1 - .1, yR - .1, zb - .1}));
            double ox1 = bi ? bx1 : fx1, oz1 = bi ? zb : zb + dp;
            for (double[] p : new double[][]{{fx0, zf, ox1, zf}, {ox1, zf, ox1, oz1}, {ox1, oz1, fx0, oz1}, {fx0, oz1, fx0, zf}})
                u.segment(new double[]{p[0], yR, p[1]}, new double[]{p[2], yR, p[3]});
            // Plenum division seams on both side walls.
            for (int k = 1; k < plenums; k++) {
                double z = zg0 + k * pl;
                u.box(new double[]{fx0 - .05, yL, z - .05}, new double[]{fx0, s, z + .05}, Mesh.ALL_FACES & ~2, Material.STEELWORK, false);
                if (!bi) u.box(new double[]{fx1, yL, z - .05}, new double[]{fx1 + .05, s, z + .05}, Mesh.ALL_FACES & ~1, Material.STEELWORK, false);
            }
            // Peep doors over the grate on the west wall, one per plenum.
            for (int k = 0; k < plenums; k++) u.door(fx0, zg0 + (k + .5) * pl, -1, s + .9, .15, .12);
            for (double y = s + 2.5; y + STAY_HEIGHT <= yR - .7; y += 3.5) {
                if (bi || y >= ybp) buckstays(fx0, ox1, zf, oz1, y);
                else buckstays(fx0, fx1, zf, zb, y);
            }
        }

        /** A ring of buckstays round the casing x0..x1 by z0..z1 at height {@code y}. */
        void buckstays(double x0, double x1, double z0, double z1, double y) {
            double y1 = y + STAY_HEIGHT;
            u.box(new double[]{x0 - STAY, y, z0 - STAY}, new double[]{x1 + STAY, y1, z0}, Mesh.ALL_FACES & ~32, Material.STEELWORK, true);
            u.box(new double[]{x0 - STAY, y, z1}, new double[]{x1 + STAY, y1, z1 + STAY}, Mesh.ALL_FACES & ~16, Material.STEELWORK, true);
            u.box(new double[]{x0 - STAY, y, z0}, new double[]{x0, y1, z1}, Mesh.ALL_FACES & ~2 & ~16 & ~32, Material.STEELWORK, true);
            u.box(new double[]{x1, y, z0}, new double[]{x1 + STAY, y1, z1}, Mesh.ALL_FACES & ~1 & ~16 & ~32, Material.STEELWORK, true);
        }

        /** The stations along z of the columns beside the furnace: its front, the plenums' joints, in front of the trough, about 3 m apart or more. */
        List<Double> columnStations() {
            var out = new ArrayList<Double>();
            double last = zt - tw / 2 - .3;
            out.add(zf + .3);
            for (int k = 1; k < plenums; k++) {
                double z = zg0 + k * pl;
                if (z - out.getLast() >= 3 && last - z >= 1.5) out.add(z);
            }
            if (last - out.getLast() >= 1.5) out.add(last);
            return out;
        }

        /**
         * The columns beside the casing's side walls: a bi-drum unit's to the casing's bottom (bottom-supported), the
         * bank's to its own; a back-pass unit's from the ground to the roof, at the back pass's back too (top-supported).
         */
        void columns() {
            var stations = columnStations();
            double top = bi ? yL : yR;
            for (double z : stations) {
                u.post(fx0 - .1, z, 0, top, COLUMN, false);
                u.post(fx1 + .1, z, 0, bi ? yM : top, COLUMN, false);
                if (bi) u.post(bx1 + .1, z, 0, yM, COLUMN, false);
            }
            if (!bi)
                for (double x : new double[]{fx0 - .1, fx1 + .1}) u.post(x, zb + dp - .2, 0, yR, COLUMN, false);
        }

        // ---- Under the grate ---------------------------------------------------------------------------------------

        /**
         * A siftings hopper under each plenum per 3.2 m of width down to its flap valve; the ash drop at the grate's end,
         * a chute narrowing from the casing's bottom into the trough; the air main along the furnace's east side under
         * the casing, a branch with its damper into each plenum, rising behind the furnace to the air heater.
         */
        void underGrate() {
            int across = Math.max(1, (int) Math.ceil((w + .4) / 3.2));
            double inner0 = fx0 + WALL, inner1 = fx1 - WALL, seg = (inner1 - inner0) / across, hh = Math.min(clamp(.6 * Math.min(pl, seg), .5, 1.5), yL - 1.3);
            for (int k = 0; k < plenums; k++)
                for (int j = 0; j < across; j++) {
                    double x0 = inner0 + j * seg + .05, x1 = x0 + seg - .1, z0 = zg0 + k * pl + .05, z1 = z0 + pl - .1, cx = (x0 + x1) / 2, cz = (z0 + z1) / 2, yh = yL - hh;
                    loft(new double[]{x0, x1, z0, z1}, yL, new double[]{cx - .15, cx + .15, cz - .15, cz + .15}, yh, Material.MACHINE);
                    u.box(new double[]{cx - .18, yh - .22, cz - .18}, new double[]{cx + .18, yh, cz + .18}, Mesh.ALL_FACES, Material.MACHINE, true);
                    u.box(new double[]{cx + .18, yh - .15, cz - .03}, new double[]{cx + .3, yh - .09, cz + .03}, Mesh.ALL_FACES, Material.ACTUATOR, false);
                    u.solids.add(ProcessUnit.Solid.box(new double[]{x0, yh, z0}, new double[]{x1, yL, z1}));
                }
            // The ash drop.
            double c0 = zt - tw / 2 + .1, c1 = zt + tw / 2 - .1;
            loft(new double[]{inner0, inner1, zg1, zb - WALL}, yL, new double[]{inner0, inner1, c0, c1}, th, Material.MACHINE);
            u.solids.add(ProcessUnit.Solid.box(new double[]{inner0, th, Math.min(c0, zg1)}, new double[]{inner1, yL, Math.max(c1, zb - WALL)}));
            // The air main, its branches and the riser behind the furnace.
            double y0 = yL, y1 = yL + am, zr0 = zb + .05, zr1 = zr0 + am, top = riserTop();
            u.box(new double[]{xa0, y0, zg0 + .2}, new double[]{xa1, y1, zr0}, Mesh.ALL_FACES & ~32, Material.CLADDING, true);
            u.box(new double[]{xa0, y0, zr0}, new double[]{xa1, top, zr1}, Mesh.ALL_FACES, Material.CLADDING, true);
            for (int k = 0; k < plenums; k++) {
                double z = zg0 + (k + .5) * pl;
                u.box(new double[]{fx1, y0 + .1, z - .25}, new double[]{xa0, y1 - .1, z + .25}, Mesh.ALL_FACES & ~1 & ~2, Material.CLADDING, false);
                u.box(new double[]{fx1 + .1, y1 - .1, z - .08}, new double[]{fx1 + .25, y1 + .05, z + .08}, Mesh.ALL_FACES, Material.ACTUATOR, false);
            }
            // Into the air heater: a bi-drum's through its front, a back-pass unit's through its east side.
            if (bi) u.box(new double[]{xa0, top - am, zr1}, new double[]{xa1, top, az0}, Mesh.ALL_FACES & ~16 & ~32, Material.CLADDING, true);
            else u.box(new double[]{ax1, top - am, zr0}, new double[]{xa0, top, zr1}, Mesh.ALL_FACES & ~1 & ~2, Material.CLADDING, true);
        }

        /** The hot-air riser's top: into the air heater over its hoppers, over the top over-fire air row. */
        double riserTop() {
            double ofa = s + 1.3 + 1.2 * (overFireRows() - 1) + overFireRadius() + .3;
            return bi ? Math.max(ah0 + .8, ofa) : Math.max(ah0 + am + .2, ofa);
        }

        int overFireRows() {
            return w < 8 ? 2 : 3;
        }

        double overFireRadius() {
            return .1 + .012 * w;
        }

        /** Over-fire air: rows of headers across the rear wall under the boiler, from the hot-air riser, a port into the wall every 0.9 m or so. */
        void overFireAir() {
            double ro = overFireRadius(), zo = zb + .1 + ro, x0 = fx0 + .4, x1 = xa0 + am / 2;
            int n = ProcessUnit.sides(2 * ro);
            for (int k = 0; k < overFireRows(); k++) {
                double y = s + 1.3 + 1.2 * k;
                var f = Frame.of(new double[]{0, y, zo}, 0, 1);
                u.mesh.prism(f, ro, x0, x1, n, Material.CLADDING);
                u.mesh.annulus(f, 0, ro, x0, n, -1, Material.CLADDING);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{0, y, zo}, 0, x0, x1, ro, 0));
                int ports = Math.max(2, (int) Math.round((fx1 - fx0 - .8) / .9));
                for (int j = 0; j < ports; j++) {
                    double x = x0 + .2 + (fx1 - .6 - x0) * j / Math.max(1, ports - 1);
                    u.mesh.prism(Frame.of(new double[]{x, y, 0}, 2, 1), .05, zb, zb + .12, 8, Material.CLADDING);
                }
            }
        }

        /** The four sloping sides of a hopper or chute from the rectangle {x0, x1, z0, z1} at {@code y0} to {@code low} at {@code y1}. */
        void loft(double[] top, double y0, double[] low, double y1, Material material) {
            double[][] a = {{top[0], y0, top[2]}, {top[1], y0, top[2]}, {top[1], y0, top[3]}, {top[0], y0, top[3]}};
            double[][] c = {{low[0], y1, low[2]}, {low[1], y1, low[2]}, {low[1], y1, low[3]}, {low[0], y1, low[3]}};
            double[][] out = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                u.mesh.flat(List.of(a[i], a[j], c[j], c[i]), out[i], material);
            }
        }

        // ---- The drag-chain ash trough -----------------------------------------------------------------------------

        /**
         * The submerged drag-chain trough across under the ash drop from its tail west of it, rising at {@link #RISE}
         * from beyond the boiler to its head, the discharge chute just past the head shaft on a cell centre; stiffeners
         * along its sides, trestles under the rise; the head shaft turning in its bearings, driven through a gearbox by a
         * motor on a stool on its +z side.
         */
        void trough() {
            double z0 = zt - tw / 2, z1 = zt + tw / 2, xt = fx0 + .1, cos = DeterministicMath.cos(RISE), sin = DeterministicMath.sin(RISE);
            u.box(new double[]{xt, 0, z0}, new double[]{xr0, th, z1}, Mesh.ALL_FACES, Material.MACHINE, true);
            // The tail: its shaft's bearings either side.
            for (double z : new double[]{z0 - .08, z1}) u.mesh.cylinder(Frame.of(new double[]{xt + .35, th / 2, 0}, 2, 1), .13, z, z + .08, 10, Material.MACHINE);
            for (double x = xt + 1; x < xr0 - .3; x += 1.5)
                for (double z : new double[]{z0 - .04, z1}) u.box(new double[]{x - .04, 0, z}, new double[]{x + .04, th, z + .04}, Mesh.ALL_FACES, Material.STEELWORK, false);
            // The rise, its length set so the discharge chute's centre line stands on a cell centre.
            double cw = clamp(.15 + .01 * w, .18, .3), yHead0 = 3.4 + .04 * w, sl0 = (yHead0 - th / 2) / sin;
            double xd = Math.ceil(xr0 + sl0 * cos + .45 - .5) + .5, xh = xd - .45, sl = (xh - xr0) / cos, yHead = th / 2 + sl * sin;
            double[] along = {cos * sl / 2, sin * sl / 2, 0}, normal = {-sin * th / 2, cos * th / 2, 0};
            u.mesh.block(new double[]{xr0 + cos * sl / 2, th / 2 + sin * sl / 2, zt}, along, normal, new double[]{0, 0, tw / 2}, Material.MACHINE);
            int steps = (int) Math.ceil(sl / .5);
            for (int k = 0; k < steps; k++) {
                double sa = sl * k / steps, sb = sl * (k + 1) / steps, xa = xr0 + sa * cos, xb = xr0 + sb * cos;
                double lo = th / 2 + sa * sin - th / 2 / cos, hi = th / 2 + sb * sin + th / 2 / cos;
                u.solids.add(ProcessUnit.Solid.box(new double[]{xa, Math.max(0, lo), z0}, new double[]{xb, hi, z1}));
            }
            for (int k = 1; k <= 2; k++) {
                double sk = sl * k / 3, x = xr0 + sk * cos, under = th / 2 + sk * sin - th / 2 / cos;
                for (double z : new double[]{z0 - .1, z1 + .1}) u.post(x, z, 0, under, COLUMN, false);
                u.box(new double[]{x - .1, under - .15, z0 - .25}, new double[]{x + .1, under, z1 + .25}, Mesh.ALL_FACES, Material.STEELWORK, true);
            }
            // The head and its discharge chute.
            double hy0 = yHead - th / 2 - .1, hy1 = yHead + th / 2 + .2;
            u.box(new double[]{xh - .45, hy0, z0 - .05}, new double[]{xh + .75, hy1, z1 + .05}, Mesh.ALL_FACES, Material.MACHINE, true);
            for (double z : new double[]{z0 - .1, z1 + .1}) u.post(xh - .3, z, 0, hy0, COLUMN, false);
            double face = outletFace(yHead);
            u.box(new double[]{xd - cw, face + .3, zt - cw}, new double[]{xd + cw, hy0, zt + cw}, Mesh.ALL_FACES & ~8, Material.MACHINE, true);
            // The head shaft in its bearings, the gearbox and the motor on their stool.
            double rshaft = .05 + .004 * w, rm = clamp(.15 + .01 * w, .16, .28), gb0 = z1 + .3, gb1 = gb0 + .5, m0 = gb1 + .05, m1 = m0 + 3.2 * rm + .1;
            u.box(new double[]{xh - .15, yHead - .15, z0 - .2}, new double[]{xh + .15, yHead + .15, z0 - .05}, Mesh.ALL_FACES, Material.MACHINE, false);
            var shaft = Frame.of(new double[]{xh, yHead, 0}, 2, 1);
            u.shaft(shaft, rshaft, new double[][]{{z1 + .05, gb0}}, (z1 + .05 + gb0) / 2);
            u.box(new double[]{xh - .3, yHead - .35, gb0}, new double[]{xh + .3, yHead + .3, gb1}, Mesh.ALL_FACES, Material.MACHINE, true);
            double top = yHead - rm - .3;
            u.motor(shaft, rm, m0, m1, .8 * rshaft, top, ProcessUnit.MotorDetail.FULL);
            u.box(new double[]{xh - .45, top - .08, gb0}, new double[]{xh + .45, top, m1}, Mesh.ALL_FACES, Material.STEELWORK, true);
            for (double x : new double[]{xh - .38, xh + .38})
                for (double z : new double[]{gb0 + .07, m1 - .07}) u.box(new double[]{x - .06, 0, z - .06}, new double[]{x + .06, top - .08, z + .06}, Mesh.ALL_FACES, Material.STEELWORK, true);
            if (yHead - .35 > top + .01) u.box(new double[]{xh - .25, top, gb0 + .05}, new double[]{xh + .25, yHead - .35, gb1 - .05}, Mesh.ALL_FACES, Material.STEELWORK, true);
            outlet = new double[]{xd, face, zt, 2 * cw};
        }

        /** The trough's discharge: {x, face, z, chute}, set by {@link #trough}. */
        double[] outlet;

        /** The discharge chute's flange face under the head at {@code yHead}: a block face with its pipe block over the ground. */
        double outletFace(double yHead) {
            return Math.max(1, Math.floor(yHead - th / 2 - .1 - .6));
        }

        // ---- The firing aisle --------------------------------------------------------------------------------------

        /**
         * One ram feeder per {@link #pitch} across the front wall at the grate: its housing out from the wall on a
         * pedestal, the crosshead on its rod sliding between two guides to the cylinder's flange; over it its chute, hopper
         * and day bin standing 0.25 m off the wall on two legs at their front; a drag conveyor over the bins, its drive at
         * the west end, its inlet at the east end on a cell centre.
         */
        void feeders() {
            double rh = .45, y0 = s + .02, y1 = y0 + rh, yc = (y0 + y1) / 2, rc = .08 + .02 * ram, hz0 = zf - housing, gz0 = hz0 - travel, cz0 = gz0 - barrel;
            double bw = Math.min(pitch - .25, 2.2), bd = clamp(.9 + .06 * w, 1.1, 1.6), bh = clamp(1 + .1 * w, 1.2, 2.2), bz1 = zf - .25, bz0 = bz1 - bd, bc = (bz0 + bz1) / 2;
            double yb0 = y1 + .5, yb1 = yb0 + Math.max(.5, .45 * Math.min(bw, bd)), yb2 = yb1 + bh;
            for (int i = 0; i < feeders; i++) {
                double x = fx0 + WALL + pitch * (i + .5), half = ram / 2;
                u.box(new double[]{x - half, y0, hz0}, new double[]{x + half, y1, zf}, Mesh.ALL_FACES & ~32, Material.MACHINE, true);
                u.box(new double[]{x - half - .04, y0 - .04, hz0 - .06}, new double[]{x + half + .04, y1 + .04, hz0}, Mesh.ALL_FACES & ~32, Material.FLANGE, true);
                u.box(new double[]{x - half + .05, deck + Access.DECK, hz0 + .05}, new double[]{x + half - .05, y0, hz0 + .3}, Mesh.ALL_FACES, Material.STEELWORK, true);
                // The guides and the cylinder with its flanges.
                for (int side = -1; side <= 1; side += 2) {
                    double gx = x + side * (half - .12);
                    u.box(new double[]{gx - .03, yc - .03, gz0}, new double[]{gx + .03, yc + .03, hz0 - .06}, Mesh.ALL_FACES, Material.STEELWORK, false);
                }
                var f = Frame.of(new double[]{x, yc, 0}, 2, 1);
                u.mesh.prism(f, rc, cz0 + .05, gz0 - .05, 12, Material.ACTUATOR);
                u.mesh.cylinder(f, rc + .04, cz0, cz0 + .05, 12, Material.FLANGE);
                u.box(new double[]{x - half + .06, yc - rc - .04, gz0 - .05}, new double[]{x + half - .06, yc + rc + .04, gz0}, Mesh.ALL_FACES, Material.FLANGE, false);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{x, yc, 0}, 2, cz0, gz0, rc + .04, 0));
                // The rod and crosshead: drawn at mid-stroke; both rod ends stay in the cylinder and the housing.
                double zc = (gz0 + hz0 - .06) / 2, crank = stroke / 2;
                u.mesh.part(u.motions.size());
                u.mesh.prism(f, .45 * rc, gz0 - .55 * stroke - .1, hz0 + .55 * stroke + .1, 10, Material.PIPE);
                u.box(new double[]{x - .12, yc - .1, zc - .06}, new double[]{x + .12, yc + .1, zc + .06}, Mesh.ALL_FACES, Material.FLANGE, false);
                u.mesh.part(-1);
                u.motions.add(new ProcessUnit.Motion(new double[]{x, yc, zc}, new double[]{0, 0, 1}, RAM_SPEED, (double) i / feeders, crank, 5 * crank));
                // The chute, hopper and day bin, its legs.
                u.box(new double[]{x - .3, y1, bc - .3}, new double[]{x + .3, yb0, bc + .3}, Mesh.ALL_FACES & ~4 & ~8, Material.MACHINE, true);
                loft(new double[]{x - bw / 2, x + bw / 2, bz0, bz1}, yb1, new double[]{x - .3, x + .3, bc - .3, bc + .3}, yb0, Material.MACHINE);
                u.box(new double[]{x - bw / 2, yb1, bz0}, new double[]{x + bw / 2, yb2, bz1}, Mesh.ALL_FACES & ~4, Material.MACHINE, true);
                u.solids.add(ProcessUnit.Solid.box(new double[]{x - bw / 2, yb0, bz0}, new double[]{x + bw / 2, yb1, bz1}));
                for (int side = -1; side <= 1; side += 2) {
                    double lx = x + side * (bw / 2 - .06);
                    u.box(new double[]{lx - .05, deck + Access.DECK, bz0 + .01}, new double[]{lx + .05, yb1, bz0 + .11}, Mesh.ALL_FACES, Material.STEELWORK, true);
                }
                u.box(new double[]{x - bw / 2 + .1, yb2 - .25, bz1}, new double[]{x + bw / 2 - .1, yb2 - .15, zf}, Mesh.ALL_FACES & ~32, Material.STEELWORK, false);
            }
            // The conveyor over the bins, its inlet chute beyond the last bin.
            double cw = conveyorWidth(), ch = clamp(.4 + .02 * w, .4, .6), zi = conveyorLine(), yc0 = yb2 + .1, yc1 = yc0 + ch;
            double first = fx0 + WALL + pitch / 2 - bw / 2, last = fx0 + WALL + pitch * (feeders - .5) + bw / 2, xi = Math.ceil(last + .25 + cw / 2 - .5) + .5;
            u.box(new double[]{first - .5, yc0, zi - cw / 2}, new double[]{xi + .4, yc1, zi + cw / 2}, Mesh.ALL_FACES, Material.MACHINE, true);
            for (int i = 0; i < feeders; i++) {
                double x = fx0 + WALL + pitch * (i + .5);
                u.box(new double[]{x - .2, yb2, zi - .2}, new double[]{x + .2, yc0, zi + .2}, Mesh.ALL_FACES & ~4 & ~8, Material.MACHINE, false);
            }
            // The drive: its gearbox on the conveyor's west end, the motor flanged on its front (no feet).
            double yd = (yc0 + yc1) / 2, zd = zi - cw / 2 - .3;
            u.box(new double[]{first - .5, yc0 + .05, zd}, new double[]{first - .1, yc1 - .05, zi - cw / 2}, Mesh.ALL_FACES & ~32, Material.MACHINE, false);
            u.motor(Frame.of(new double[]{first - .3, yd, zd}, 2, -1), .14, 0, .45, 0, yd, ProcessUnit.MotorDetail.PLAIN);
            inlet = new double[]{xi, yc1, zi};
            fuelBase = yc1;
        }

        /** The fuel inlet {x, top, z} and the conveyor's top, set by {@link #feeders}. */
        double[] inlet;
        double fuelBase;

        double conveyorWidth() {
            return clamp(.5 + .03 * w, .55, .85);
        }

        /** The conveyor's line along x: the last cell centre at least 0.7 m in front of the wall, its pipe block clear of the buckstays. */
        double conveyorLine() {
            return Math.floor(zf - .7 - .5) + .5;
        }

        // ---- The boiler --------------------------------------------------------------------------------------------

        /**
         * The bi-drum boiler: the convection bank's clad casing beside the furnace's east wall from the mud drum to the
         * roof, the mud drum along its foot, the steam drum lying front to rear over it on two saddles, its two safety
         * valves; the bank's flue gas duct from its back into the air heater's top.
         */
        void biDrum() {
            u.box(new double[]{fx1, yM, zf}, new double[]{bx1, yR, zb}, Mesh.ALL_FACES & ~1, Material.CLADDING, false);
            // The joint between the furnace's and the bank's casings, a flat bar down the front and back.
            for (double z : new double[]{zf - .05, zb})
                u.box(new double[]{fx1 - .06, yM, z}, new double[]{fx1 + .06, yR, z + .05}, Mesh.ALL_FACES & ~(z < zf ? 32 : 16), Material.STEELWORK, false);
            u.solids.add(ProcessUnit.Solid.box(new double[]{fx1, yM, zf}, new double[]{bx1, yR, zb}));
            u.volumes.add(ProcessUnit.Solid.box(new double[]{fx1 - .2, yM + .1, zf + .1}, new double[]{bx1 - .1, yR - .1, zb - .1}));
            double rmd = .3 + .025 * w, m0 = zf + .15 + rmd / 2, m1 = zb - .15 - rmd / 2;
            int nm = ProcessUnit.sides(2 * rmd);
            var mud = Frame.of(new double[]{bmid, yM, 0}, 2, 1);
            u.mesh.prism(mud, rmd, m0, m1, nm, Material.SHELL);
            u.mesh.head(mud, rmd, m1, rmd / 2, nm, Material.HEAD);
            u.mesh.head(Frame.of(new double[]{bmid, yM, 0}, 2, -1), rmd, -m0, rmd / 2, nm, Material.HEAD);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{bmid, yM, 0}, 2, m0 - rmd / 2, m1, rmd, rmd / 2));
            u.round(bmid, yM, m0, m1, rmd);
            double s0 = zf + .2 + rs / 2, s1 = zb - .2 - rs / 2;
            drum(new double[]{bmid, yS, 0}, 2, s0, s1);
            // Flue gas from the bank's back into the air heater's top.
            double dh = clamp(.8 + .05 * w, .9, 1.2);
            u.box(new double[]{ax0 + .1, ah1 - dh, zb}, new double[]{ax1 - .1, ah1 - .1, az0}, Mesh.ALL_FACES & ~16 & ~32, Material.CLADDING, true);
        }

        /**
         * The back pass behind the furnace from the roof down 0.55 of its height, sharing its rear wall; the single drum
         * lying across the roof over it on two saddles, its risers and downcomers into the roof; the economiser's inlet
         * header on the back pass's east side.
         */
        void backPass() {
            u.box(new double[]{fx0, ybp, zb}, new double[]{fx1, yR, zb + dp}, Mesh.ALL_FACES & ~16, Material.CLADDING, false);
            u.solids.add(ProcessUnit.Solid.box(new double[]{fx0, ybp, zb}, new double[]{fx1, yR, zb + dp}));
            u.volumes.add(ProcessUnit.Solid.box(new double[]{fx0 + .1, ybp + .1, zb - .2}, new double[]{fx1 - .1, yR - .1, zb + dp - .1}));
            double zs = drumLine(), s0 = fx0 + .4 + rs / 2, s1 = fx1 - .4 - rs / 2;
            drum(new double[]{0, yS, zs}, 0, s0, s1);
            int risers = (int) clamp(Math.round((s1 - s0) / 1.5), 3, 6);
            for (int k = 0; k < risers; k++) {
                double x = s0 + (s1 - s0) * (k + .5) / risers;
                u.line(.06, Material.PIPE, new double[]{x, yR + .05, zs - rs - .25}, new double[]{x, yR + .5, zs - rs - .25}, new double[]{x, yS - .3 * rs, zs - .7 * rs});
            }
            double rdc = .1 + .01 * w;
            for (double x : new double[]{s0 + .3, s1 - .3}) {
                u.mesh.prism(Frame.of(new double[]{x, 0, zs}, 1, 1), rdc, yR, yS - .8 * rs, 12, Material.PIPE);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{x, 0, zs}, 1, yR, yS - .8 * rs, rdc, 0));
            }
            var header = Frame.of(new double[]{fx1 + .17, feedwaterHeight(), 0}, 2, 1);
            u.mesh.cylinder(header, .12, zb + .3, zb + dp - .3, 12, Material.PIPE);
        }

        /** A back-pass unit's drum's line along x over the back pass: a cell centre. */
        double drumLine() {
            return Math.floor(zb + dp / 2) + .5;
        }

        /** The economiser's inlet header's height on the back pass's east side: a cell centre 1.5 m or so over its bottom. */
        double feedwaterHeight() {
            return Math.floor(ybp + 1.5) + .5;
        }

        /** The steam drum lying along frame axis {@code axis} (0 x, 2 z) through {@code centre}, tangent to tangent {@code t0} to {@code t1}: shell, heads, saddles on the roof deck, two safety valves. */
        void drum(double[] centre, int axis, double t0, double t1) {
            int n = ProcessUnit.sides(2 * rs);
            var f = Frame.of(centre, axis, 1);
            u.mesh.prism(f, rs, t0, t1, n, Material.SHELL);
            u.mesh.head(f, rs, t1, rs / 2, n, Material.HEAD);
            u.mesh.head(Frame.of(centre, axis, -1), rs, -t0, rs / 2, n, Material.HEAD);
            u.solids.add(ProcessUnit.Solid.rod(centre, axis, t0 - rs / 2, t1, rs, rs / 2));
            u.volumes.add(ProcessUnit.Solid.rod(centre, axis, t0, t1, .9 * rs, .4 * rs));
            u.volumes.add(ProcessUnit.Solid.cone(centre, axis, t0 - .4 * rs, t0, .1 * rs, .9 * rs, 0));
            if (axis == 2) u.round(centre[0], centre[1], t0, t1, rs);
            else u.round(centre, 0, t0, t1, rs);
            double floor = yR + Access.DECK;
            for (double t : new double[]{t0 + .2 * (t1 - t0), t1 - .2 * (t1 - t0)}) {
                double[] lo = axis == 2 ? new double[]{centre[0] - .8 * rs, floor, t - .08} : new double[]{t - .08, floor, centre[2] - .8 * rs};
                double[] hi = axis == 2 ? new double[]{centre[0] + .8 * rs, yS - .6 * rs, t + .08} : new double[]{t + .08, yS - .6 * rs, centre[2] + .8 * rs};
                u.box(lo, hi, Mesh.ALL_FACES, Material.SUPPORT, true);
            }
            for (double[] v : valves()) safetyValve(v[0], v[1], yS + rs);
        }

        /** The safety valves' places {x, z} on the drum's top: in the cells beside the steam and feedwater nozzles' pipe blocks. */
        List<double[]> valves() {
            var out = new ArrayList<double[]>();
            if (bi) {
                double z = steamStation() - 1;
                out.add(new double[]{bmid - .35 * rs, z});
                out.add(new double[]{bmid + .35 * rs, z});
            } else {
                double x = steamStation(), lo = fx0 + .4 + rs / 2 + .2;
                for (double xv : new double[]{x - 1, x - 3}) if (xv >= lo) out.add(new double[]{xv, drumLine()});
                if (out.size() < 2) out.add(new double[]{x - 1.6, drumLine()});
            }
            return out;
        }

        /** A spring safety valve on the drum's top at (x, z) over {@code y}: its body, bonnet and lever, the vent pipe up to its silencer. */
        void safetyValve(double x, double z, double y) {
            var up = Frame.of(new double[]{x, 0, z}, 1, 1);
            u.mesh.cylinder(up, .11, y - .05, y + .05, 10, Material.FLANGE);
            u.mesh.prism(up, .09, y + .05, y + .4, 10, Material.MACHINE);
            u.mesh.cylinder(up, .05, y + .4, y + .7, 8, Material.MACHINE);
            u.mesh.prism(up, .06, y + .4, y + 2.1, 8, Material.PIPE);
            u.mesh.cylinder(up, .2, y + 2.1, y + 2.9, 12, Material.CLADDING);
            u.mesh.prism(up, .06, y + 2.9, y + 3.2, 8, Material.PIPE);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{x, 0, z}, 1, y - .05, y + 2.1, .11, 0));
            u.solids.add(ProcessUnit.Solid.rod(new double[]{x, 0, z}, 1, y + 2.1, y + 2.9, .2, 0));
        }

        /** The steam nozzle's station along the drum: a bi-drum's z near its back (the feedwater beside it, the blowdown 2 m in front), a back-pass unit's x near its east end, clear of the blowdown's pipe block. */
        double steamStation() {
            if (bi) {
                double s1 = zb - .2 - rs / 2;
                return Math.floor(s1 - .3 - .5) + .5;
            }
            double s1 = fx1 - .4 - rs / 2;
            return Math.floor(Math.min(s1 - .3, blowdownFace() - 1.5) - .5) + .5;
        }

        /** The flange face of the nozzles out of the drum's east side or end: past the drum deck's east edge. */
        double blowdownFace() {
            return Math.ceil((bi ? bx1 : fx1) + .05);
        }

        // ---- Air heater, fan and stack -----------------------------------------------------------------------------

        /** The tubular air heater: its clad box on four legs (a back-pass unit's hangs under the back pass), its ash hoppers, an air intake hood. */
        void airHeater() {
            u.box(new double[]{ax0, ah0, az0}, new double[]{ax1, ah1, az1}, bi ? Mesh.ALL_FACES : Mesh.ALL_FACES & ~8, Material.CLADDING, true);
            u.volumes.add(ProcessUnit.Solid.box(new double[]{ax0 + .1, ah0 + .1, az0 + .1}, new double[]{ax1 - .1, ah1 - .1, az1 - .1}));
            int hoppers = (int) clamp(Math.round((ax1 - ax0) / 2.5), 1, 4);
            double seg = (ax1 - ax0) / hoppers, hop = clamp(.4 * Math.min(seg, az1 - az0), .7, 1.5), zc = (az0 + az1) / 2;
            for (int k = 0; k < hoppers; k++) {
                double x0 = ax0 + k * seg, x1 = x0 + seg, xc = (x0 + x1) / 2;
                loft(new double[]{x0 + .05, x1 - .05, az0 + .05, az1 - .05}, ah0, new double[]{xc - .15, xc + .15, zc - .15, zc + .15}, ah0 - hop, Material.CLADDING);
                u.box(new double[]{xc - .17, ah0 - hop - .25, zc - .17}, new double[]{xc + .17, ah0 - hop, zc + .17}, Mesh.ALL_FACES, Material.MACHINE, true);
                u.solids.add(ProcessUnit.Solid.box(new double[]{x0, ah0 - hop, az0}, new double[]{x1, ah0, az1}));
            }
            if (bi)
                for (double x : new double[]{ax0 + .12, ax1 - .12})
                    for (double z : new double[]{az0 + .12, az1 - .12}) u.post(x, z, 0, ah0, COLUMN, false);
            // The air intake hood on its outer side (east of a bi-drum unit's, west of a back-pass unit's), its louvres dark.
            double hy0 = ah0 + .3, hy1 = Math.min(ah1 - .3, hy0 + clamp(.6 + .08 * w, .8, 1.6)), hz0 = zc - .5, hz1 = zc + .5;
            if (bi) {
                u.box(new double[]{ax1, hy0, hz0}, new double[]{ax1 + .5, hy1, hz1}, Mesh.ALL_FACES & ~1 & ~2, Material.CLADDING, true);
                u.mesh.flat(List.of(new double[]{ax1 + .5, hy0, hz0}, new double[]{ax1 + .5, hy0, hz1}, new double[]{ax1 + .5, hy1, hz1}, new double[]{ax1 + .5, hy1, hz0}),
                        new double[]{1, 0, 0}, Material.OPENING);
            } else {
                u.box(new double[]{ax0 - .5, hy0, hz0}, new double[]{ax0, hy1, hz1}, Mesh.ALL_FACES & ~1 & ~2, Material.CLADDING, true);
                u.mesh.flat(List.of(new double[]{ax0 - .5, hy0, hz0}, new double[]{ax0 - .5, hy1, hz0}, new double[]{ax0 - .5, hy1, hz1}, new double[]{ax0 - .5, hy0, hz1}),
                        new double[]{-1, 0, 0}, Material.OPENING);
            }
        }

        /**
         * The flue duct from the air heater's back over its hoppers to over the fan, down into the fan's inlet; the
         * induced-draught fan's scroll on its plinth, its hub, the shaft to its motor turning, its outlet duct into the
         * stack's square base; the kit's drawn stack ({@link ProcessUnit#stack}) from the base's top, 10 m over the roof
         * and at most 60 m high, its bands in the cladding's paint.
         */
        void fanAndStack() {
            double rw = clamp(.3 + .1 * w, .5, 1.5), r0 = 1.1 * rw, r1 = 1.55 * rw, rh = .55 * rw, width = .8 * rw + .2, yf = r1 + .35;
            double xf = (ax0 + ax1) / 2, dd = clamp(.6 + .05 * w, .6, 1.2), dw = 2 * rh + .3, z0 = az1 + .4 + dd, z1 = z0 + width, yd0 = ah0 + .1, yd1 = yd0 + dd;
            u.box(new double[]{xf - dw / 2, yd0, az1}, new double[]{xf + dw / 2, yd1, z0 - dd}, Mesh.ALL_FACES & ~16 & ~32, Material.CLADDING, true);
            u.box(new double[]{xf - dw / 2, yf - rh - .1, z0 - dd}, new double[]{xf + dw / 2, yd1, z0}, Mesh.ALL_FACES & ~32, Material.CLADDING, true);
            double rm = clamp(.2 + .025 * w, .25, .5), lm = 3.2 * rm + .1, m0 = z1 + .55, m1 = m0 + lm, shaft = .04 + .01 * w;
            u.box(new double[]{xf - r1 - .1, 0, z0 - .1}, new double[]{xf + r1 + .1, .3, m1 + .1}, Mesh.ALL_FACES, Material.CONCRETE, true);
            double zh = z1 - .3 * width;
            u.scroll(xf, yf, z0, z1, zh, rh, r0, r1);
            var f = Frame.of(new double[]{xf, yf, 0}, 2, 1);
            u.mesh.prism(f, rh, zh, z1, 16, Material.MACHINE);
            u.mesh.annulus(f, 0, rh, z1, 16, 1, Material.MACHINE);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{xf, yf, 0}, 2, z0, z1, r1, 0));
            u.round(xf, yf, z0, z1, r1);
            // The bearing pedestal, the shaft and the motor.
            u.box(new double[]{xf - .2, .3, z1 + .12}, new double[]{xf + .2, yf - shaft - .05, z1 + .35}, Mesh.ALL_FACES, Material.MACHINE, true);
            u.mesh.cylinder(f, shaft + .08, z1 + .12, z1 + .35, 12, Material.MACHINE);
            u.shaft(f, shaft, new double[][]{{z1, z1 + .12}, {z1 + .35, m0}}, z1 + .45);
            u.motor(f, rm, m0, m1, shaft, .3, ProcessUnit.MotorDetail.FULL);
            // The outlet duct off the scroll's top into the stack's base.
            double od = clamp(.2 * w + .6, 1, 3), half = od / 2 + .3, xs = xf + r1 + .4 + half, zs = (z0 + z1) / 2, base = yf + r1 + .4;
            u.box(new double[]{xf - .05, yf + .99 * rh, z0}, new double[]{xs - half, yf + r1, z1}, Mesh.ALL_FACES & ~1 & ~2, Material.MACHINE, true);
            u.box(new double[]{xs - half, 0, zs - half}, new double[]{xs + half, base, zs + half}, Mesh.ALL_FACES & ~4 & ~8, Material.CLADDING, true);
            u.stack(xs, zs, od, ProcessUnit.sides(od), base, Math.min(60, yR + 10), Material.CLADDING, new ProcessUnit.Breeching(xs - half, xs + half, zs - half, zs + half), false);
        }

        // ---- Access ------------------------------------------------------------------------------------------------

        /**
         * The firing aisle's deck across the front from the west wall to past the boiler a metre under the grate, on beams
         * and posts; the drum deck over the roof from the west wall; the kit's stair tower beside the west wall from the
         * ground to both ({@link ProcessUnit#stairTower}). Its lane A stands on the nearest cell centre whose flight keeps
         * clear of the furnace's columns, and its landing on the aisle ends at the furnace's front: the tower rises towards
         * the front where a flight of lane A reaches the aisle (lane A's landings are at the tower's far end), towards the
         * back where one of lane B does. The drum deck reaches over the furnace's front or back as far as the tower's top
         * landing needs, on beams with knee braces to the casing; where the tower stands off the wall, a {@link #bridge}
         * joins each landing to its deck.
         */
        void access() {
            double[] alongX = {1, 0, 0}, alongZ = {0, 0, 1};
            double lane = Math.floor(wall + .025 - ProcessUnit.TOWER_FLIGHT / 2 - .5) + .5;
            var plan = ProcessUnit.towerPlan(deck, yR);
            int sign = -plan.end(0);
            double[] landing = plan.opening(0);
            var tower = u.stairTower(plan, lane, ProcessUnit.TowerPlan.foot(zf, sign, sign > 0 ? landing[1] : landing[0]), 2, sign, -1, true);
            double[] onAisle = tower.openings().get(0), onRoof = tower.openings().get(1);
            // The aisle: railed at its front and east end, and along its west end but over the tower's landing.
            u.platform(new Access.Line(new double[]{wall, 0, zf}, alongX, new double[]{0, 0, -1}, xEnd - wall), deck, aisle, Access.RAILED, false, true);
            var west = new Access.Line(new double[]{wall + .3, 0, zA0}, alongZ, new double[]{-1, 0, 0}, zf - zA0);
            u.rails(west, deck, .3, false, false, west.gap(onAisle));
            bridge(tower.edge(), onAisle, deck);
            u.beam(new double[]{wall, deck - BEAM, zA0}, new double[]{xEnd, deck, zA0 + .2});
            u.beam(new double[]{wall, deck - BEAM, zf - .2}, new double[]{xEnd, deck, zf});
            int bays = Math.max(1, (int) Math.ceil((xEnd - wall) / 3));
            for (int k = 0; k <= bays; k++) {
                double x = Math.min(xEnd - .1, Math.max(wall + .1, wall + (xEnd - wall) * k / bays));
                u.beam(new double[]{x - .1, deck - BEAM, zA0 + .2}, new double[]{x + .1, deck, zf - .2});
                u.post(x, zA0 + .2, 0, deck - BEAM, COLUMN, false);
            }
            // The drum deck from the wall to the bank's or furnace's east side, its west edge railed but over the landing.
            double zEnd = bi ? zb : zb + dp, xR = bi ? bx1 : fx1, z0 = Math.min(zf, onRoof[0]), z1 = Math.max(zEnd, onRoof[1]);
            u.platform(new Access.Line(new double[]{wall - Access.GAP, 0, z0}, alongZ, new double[]{1, 0, 0}, z1 - z0), yR, xR - wall + Access.GAP, Access.RAILED | Access.WALL_EDGE,
                    true, true);
            var roof = new Access.Line(new double[]{wall + .3, 0, z0}, alongZ, new double[]{-1, 0, 0}, z1 - z0);
            u.rails(roof, yR, .3, false, false, roof.gap(onRoof));
            bridge(tower.edge(), onRoof, yR);
            u.beam(new double[]{wall, yR - BEAM, z0}, new double[]{wall + .2, yR, z1});
            // Over the front and the back: a beam along the deck's edge, cross beams out to it on knee braces to the casing.
            for (int e = 0; e < 2; e++) {
                double edge = e == 0 ? z0 : z1, casing = e == 0 ? zf : zEnd, in = e == 0 ? 1 : -1;
                if (Math.abs(casing - edge) < .3) continue;
                u.beam(new double[]{wall + .2, yR - BEAM, Math.min(edge, edge + .2 * in)}, new double[]{xR, yR, Math.max(edge, edge + .2 * in)});
                for (double x = wall + .1 + 2; x < xR - 1; x += 2.5) {
                    u.beam(new double[]{x - .1, yR - BEAM, Math.min(edge + .2 * in, casing)}, new double[]{x + .1, yR, Math.max(edge + .2 * in, casing)});
                    u.mesh.bar(new double[]{x, yR - BEAM, edge + .3 * in}, new double[]{x, yR - 1.2, casing}, .12, new double[]{1, 0, 0}, Material.STEELWORK);
                }
            }
        }

        /**
         * A bridge at {@code level} from the stair tower's side at x {@code edge} to a deck's west edge at the wall, over the
         * tower's landing {@code over} ({lo, hi} along z), where the tower stands off the wall: a landing piece railed across
         * its ends only ({@link ProcessUnit#platform}, no parts), on a beam under each end.
         */
        void bridge(double edge, double[] over, double level) {
            if (wall - edge < .1) return;
            u.platform(new Access.Line(new double[]{edge - Access.GAP, 0, over[0]}, new double[]{0, 0, 1}, new double[]{1, 0, 0}, over[1] - over[0]), level, wall - edge + Access.GAP, 0,
                    true, true);
            for (double z : new double[]{over[0], over[1] - .15}) u.beam(new double[]{edge, level - .2, z}, new double[]{wall, level, z + .15});
        }

        // ---- Nozzles -----------------------------------------------------------------------------------------------

        void nozzles() {
            double steam = clamp(.025 * w + .1, .15, .6), fw = clamp(.015 * w + .08, .1, .45), blow = clamp(.04 + .004 * w, .05, .08), fuel = clamp(.04 + .005 * w, .05, .1);
            int n = ProcessUnit.sides(2 * rs);
            if (bi) {
                double zs = steamStation();
                u.nozzles.add(ProcessUnit.radial(Role.STEAM, bmid, yS, rs, n, zs, 1, ColumnStructure.size(steam, .6)));
                u.nozzles.add(drumSide(Role.FEEDWATER, zs, n, ColumnStructure.size(fw, .45)));
                u.nozzles.add(drumSide(Role.BLOWDOWN, zs - 2, n, blow));
            } else {
                double xs = steamStation(), zs = drumLine();
                u.nozzles.add(ProcessUnit.radial(Role.STEAM, xs, yS, rs, n, zs, 1, ColumnStructure.size(steam, .6)));
                // The feedwater's default size no larger than the economiser's inlet header it leaves (0.24 m).
                double yf = feedwaterHeight(), bx = fx1 + .17, face = Math.ceil(fx1 + STAY + ProcessUnit.NECK);
                u.nozzles.add(new Nozzle(Role.FEEDWATER, new double[]{bx, yf, zs}, 0, 1, face - bx, ColumnStructure.size(fw, .24), 0));
                double base = fx1 - .4 - .05;
                u.nozzles.add(new Nozzle(Role.BLOWDOWN, new double[]{base, yS, zs}, 0, 1, blowdownFace() - base, blow, 0));
            }
            // The drain down from the rear lower header to the ground behind the furnace, clear of the trough.
            double xd = Math.floor(fx0 + 1.5) + .5, zd = Math.max(Math.ceil(zt + tw / 2 + .15), Math.ceil(zb)) + .5, yh = yL + .15;
            u.line(.035, Material.PIPE, new double[]{xd, yh, zb - .05}, new double[]{xd, yh, zd}, new double[]{xd, 1.3, zd});
            u.nozzles.add(ProcessUnit.end(Role.DRAIN, new double[]{xd, 1.3, zd}, 1, -1, .05));
            // The start-up burner on the front wall over the conveyor, its windbox and register.
            double yb = Math.floor(fuelBase + 1.5) + .5, xb = Math.floor(fx0 + 1) + .5;
            u.box(new double[]{xb - .4, yb - .4, zf - .4}, new double[]{xb + .4, yb + .4, zf}, Mesh.ALL_FACES & ~32, Material.MACHINE, true);
            u.mesh.ring(Frame.of(new double[]{xb, yb, 0}, 2, -1), .15, .3, -(zf - .4), -(zf - .48), 12, 3, Material.FLANGE);
            u.nozzles.add(ProcessUnit.front(Role.FUEL, xb, yb, zf - .37, ColumnStructure.size(fuel, fuel)));
            // Fuel into the conveyor's end, ash out of the trough's chute.
            double feed = Math.min(.6, conveyorWidth() - .1);
            u.nozzles.add(ProcessUnit.end(Role.SOLIDS_INLET, new double[]{inlet[0], inlet[1] - .03, inlet[2]}, 1, 1, ColumnStructure.size(.2 + .02 * w, feed)));
            u.nozzles.add(ProcessUnit.end(Role.SOLIDS_OUTLET, new double[]{outlet[0], outlet[1] + .3, outlet[2]}, 1, -1, ColumnStructure.size(outlet[3] - .05, outlet[3])));
        }

        /**
         * A bi-drum unit's nozzle out of its steam drum's east side at station {@code z}, its face past the drum deck's edge,
         * its neck rooted where a pipe's largest neck's edge meets the drum (owner decision 2026-10-21: a 1 m pipe has a 1 m root).
         */
        Nozzle drumSide(Role role, double z, int n, double defaultOd) {
            double start = ProcessUnit.rootUnder(rs, n);
            return new Nozzle(role, new double[]{bmid + start, yS, z}, 0, 1, blowdownFace() - bmid - start, defaultOd, 0);
        }

    }
}
