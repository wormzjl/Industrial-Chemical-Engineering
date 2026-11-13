package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * A batch still ({@link #build}; batch 2026-11-09-batch-distiller, W2): a copper pot of 100 to 2000 L
 * (its main size, litres), heated as its {@link BatchStillOptions} say, with a worm-tub condenser, a column or neither.
 * Its pot's diameter follows the plan's size table ({@link #potDiameter}), its heated surface (the bottom and the
 * flue-swept lower wall) is about 1.73 D² (BATCH_DISTILLER_PLAN.md section 2).
 */
final class BatchStillDesign extends UnitDesign {
    /** The pot sizes a player picks, litres: the middle one (500 L) is a new still's. */
    static final List<Double> POTS = List.of(100.0, 150.0, 200.0, 300.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0);

    /**
     * The pot's diameter law {@code D = 0.5 (V / 100 L)^b}, b = ln 2.74 / ln 20 = 0.3365: through the plan's 0.50 m at
     * 100 L and 1.37 m at 2000 L, and 0.86 m at 500 L (section 2's table), to the centimetre.
     */
    static final double POT_EXPONENT = DeterministicMath.log(2.74) / DeterministicMath.log(20);

    /**
     * The largest bore of a liquid-fired still's fuel connection, its burner, m (owner, 2026-11-09: a small pipe connector
     * is enough). A 2000 L still burns about 4 g/s of oil; a 50 mm line carries it at under 0.01 m/s.
     */
    static final double FUEL_BORE = .05;

    /** The pot's heated surface over D²: its bottom and the lower wall the flue gas sweeps (0.3 D high). */
    static final double HEATED_AREA = 1.73;

    /** The pot's diameter for {@code litres}, m, to the centimetre. */
    static double potDiameter(double litres) {
        return Math.round(50 * DeterministicMath.pow(litres / 100, POT_EXPONENT)) / 100.0;
    }

    /** The column's diameter for a pot of {@code d}, m: the plan's 124 mm at 100 L to 338 mm at 2000 L (an F-factor of 1.5 at full boil-up). */
    static double columnDiameter(double d) {
        return Math.round(248 * d) / 1000.0;
    }

    @Override
    public double minSize() {
        return POTS.getFirst();
    }

    @Override
    public double maxSize() {
        return POTS.getLast();
    }

    /** A notional step; the sizes are the list ({@link #sizes()}). */
    @Override
    public double sizeStep() {
        return 50;
    }

    @Override
    public List<Double> sizes() {
        return POTS;
    }

    @Override
    public double snap(double size) {
        return Double.isFinite(size) ? MachineDuty.nearest(POTS, size) : minSize();
    }

    @Override
    public double step(double size, int steps) {
        return MachineDuty.step(POTS, size, steps);
    }

    @Override
    public String sizeUnit() {
        return "L";
    }

    @Override
    public String sizeText(double size) {
        return whole(size);
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
        return "still_pot";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = potDiameter(shape.diameter());
        return new Line("still_pot_held", size(d), number(HEATED_AREA * d * d));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return "." + shape.still().heater().key();
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        var options = shape.still();
        double d = potDiameter(shape.diameter());
        var out = new ArrayList<Line>();
        out.add(new Line("batch_still", whole(shape.diameter()), size(d), number(HEATED_AREA * d * d)));
        out.add(new Line("batch_still.heater." + options.heater().key()));
        if (options.column())
            out.add(new Line("batch_still.column", Integer.toString(options.stages()), whole(1000 * columnDiameter(d))));
        out.add(new Line("batch_still.condenser." + (options.column() ? "column" : options.condenser().key())));
        return out;
    }

    /**
     * A batch still (batch 2026-11-09-batch-distiller, W2), less industrial than the columns: a copper pot ({@link StillPlan})
     * in a brick setting with an iron firing door and a brick flue (solid fuel, or a burner through the door for liquid
     * fuel), or on a steel skirt with a terminal box (electric); a helmet, or a copper column with a porthole per stage;
     * the copper condenser overhead (a column's over a reflux pot and a hand-wheel valve), or a swan neck to a vapour
     * flange where it has no condenser of its own. Seen from the front (owner, 2026-11-09): its charge enters at the
     * front, its drain leaves at the back, a liquid-fired still's fuel on the right; the pot one column in from the right,
     * a free column, the condenser on the left (owner, 2026-11-10: one block wider), its
     * distillate down to a sight glass and the spout at the front, its vent up. The firing door, the fire
     * bed, the flue top, the burner, the lamp, the spout and the reflux valve are named ({@link ProcessUnit#marks}).
     */
    @Override
    void build(ProcessUnit u) {
        var p = StillPlan.of(u.shape);
        var o = p.options();
        double d = p.d(), r = d / 2, x = StillPlan.POT_X, z = StillPlan.POT_Z;
        int n = ProcessUnit.sides(d);
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        // The pot: a shell on a flat bottom over the fire, its head, a seam at the tangent line, in its tier's finish.
        var pot = p.finish();
        u.mesh.prism(up, r, p.bottom(), p.cylinder(), n, pot);
        // The pot's node (W3): its lowest inside point, on its flat bottom, on its axis at a block's centre.
        u.fluidPoint = new double[]{x, p.bottom() + .01, z};
        u.mesh.annulus(up, 0, r, p.bottom(), n, -1, pot);
        u.mesh.head(up, r, p.cylinder(), d / 4, n, pot);
        u.mesh.hoop(up, r, r + .015, p.cylinder() - .03, p.cylinder(), n, pot);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, p.bottom(), p.cylinder(), r, d / 4));
        u.round(x, z, p.bottom(), p.cylinder(), r, 1);
        if (o.heater().fired()) stillSetting(u, p);
        else stillSkirt(u, p);
        stillCharge(u, p);
        stillDrain(u, p);
        if (o.column()) stillColumn(u, p);
        else stillHelmet(u, p);
        if (o.condenses()) stillCondenser(u, p);
        else stillVapour(u, p);
    }

    /**
     * A batch still's layout in its frame, m. The pot, {@code d} across, stands on the vertical line through
     * ({@link #POT_X}, {@link #POT_Z}): its flat bottom {@code bottom} up, its shell to {@code cylinder}, its 2:1 head's
     * apex at {@code apex}. A fired still's brick setting is {@code w} square to {@code setting} (the flue gas sweeps the
     * pot's wall to there), its grate {@code grate} up, its brick flue {@code flue} square in the setting's back right
     * corner to {@code flueTop}. A pot still's helmet, radius {@code helmet}, rises to {@code helmetTop}; its swan neck is
     * {@code arm} in radius. The charge enters at the front at row {@code charge}. A column, radius {@code column}, rises
     * from {@code columnBase} to {@code columnTop}. The condenser, a shell of radius {@code shell}, lies front to back in
     * the left column ({@link #SPOUT_X}) at height {@code condenser}, a free column from the pot (0 without a condenser):
     * the same top condenser for both (owner, 2026-11-09).
     */
    record StillPlan(BatchStillOptions options, double d, double w, double grate, double bottom, double setting, double cylinder, double apex,
                     double helmet, double helmetTop, double flue, double flueTop, double arm, double charge,
                     double column, double columnBase, double columnTop, double shell, double condenser) {
        /**
         * The pot's axis in the frame, on the centre of the second of four columns from the right seen from the front, the
         * right column free for the terminal box or the burner's line, a free column between the pot and the condenser
         * (owner, 2026-11-10: every still one block wider); the left column's line, where the condenser, the spout and the
         * vent stand.
         */
        static final double POT_X = 1.5, POT_Z = 1.5, SPOUT_X = 3.5;
        /** The line (frame z) of the condenser's cold end: the reflux pot, the valve, the sight glass and the spout's drop. */
        static final double COLD_END = .75;
        /** The condenser shell's front and back ends along the frame's z (its channels reach 0.2 m past them). */
        static final double SHELL_FRONT = .6, SHELL_BACK = 1.85;
        static StillPlan of(EquipmentShape shape) {
            var o = shape.still();
            // The setting: 0.35 m of brick round the pot; the right column leaves the burner's line at least 0.46 m.
            double d = BatchStillDesign.potDiameter(shape.diameter()), w = d + .7;
            double bottom = .3 + .6 * d, setting = bottom + .3 * d, cylinder = bottom + .85 * d, apex = cylinder + d / 4;
            // The charge's row: a pipe's largest neck, 0.41 m round its centre line, clears the setting's coping or the skirt.
            double helmet = .22 * d, charge = rowAtLeast(setting + PipeSizes.MAX_OUTSIDE / 2 + .02);
            double column = .124 * d, columnBase = apex - .03, columnTop = columnBase + .3 * o.stages() + .2, shell = .1 + .05 * d;
            double helmetTop = apex + .3 * d + helmet / 2;
            // At least three rows over the spout: the water inlet's pipe block, a row under the condenser, keeps a free block
            // from the spout's on the front face.
            double condenser = o.condenses() ? Math.max(rowAtLeast((o.column() ? columnTop : helmetTop) + shell + .5), 3.5) : 0;
            // The flue in the setting's back right corner, its inner corner 0.03 m clear of the pot's wall.
            double flue = Math.min(.15 + .2 * d, w / 2 - (d / 2 + .03) / Math.sqrt(2));
            return new StillPlan(o, d, w, .1 + .2 * d, bottom, setting, cylinder, apex, helmet, helmetTop, flue,
                    Math.ceil((apex + 1.2) * 4) / 4, .06 * d + .01, charge, column, columnBase, columnTop, shell, condenser);
        }

        /**
         * The finish of every process vessel (pot, helmet, column, condenser, reflux pot) by the heater's tier (owner,
         * 2026-11-09): hammered, riveted copper over a solid fire; welded carbon steel over a burner; welded, brushed
         * stainless steel on an electric plate.
         */
        Material finish() {
            return switch (options.heater()) {
                case SOLID -> Material.COPPER;
                case LIQUID -> Material.CARBON_STEEL;
                case ELECTRIC -> Material.STAINLESS;
            };
        }

        /** The process lines' finish in the same tier (drawn copper, carbon-steel pipe, stainless tube); utilities stay plant pipe. */
        Material line() {
            return switch (options.heater()) {
                case SOLID -> Material.COPPER_LINE;
                case LIQUID -> Material.CARBON_STEEL_LINE;
                case ELECTRIC -> Material.STAINLESS_LINE;
            };
        }

        /** The lowest cell centre at or over {@code y}, where a nozzle's centre line runs. */
        static double rowAtLeast(double y) {
            return Math.ceil(y - .5 - 1e-9) + .5;
        }

        /** The gooseneck's centre line height, from the helmet or the column's top over to the condenser: 0.25 m over its shell. */
        double gooseneck() {
            return condenser + shell + .25;
        }

        /** The pot's radius at height {@code y}: its shell's, its head's over the tangent line, 0 outside the pot. */
        double radiusAt(double y) {
            if (y < bottom || y >= apex) return 0;
            if (y <= cylinder) return d / 2;
            double t = (y - cylinder) / (d / 4);
            return d / 2 * Math.sqrt(1 - t * t);
        }
    }

    /**
     * A fired still's brick setting under the pot, its coping, the flue in its back right corner, the iron firing door and
     * ash-pit door at its front, and for liquid fuel the burner: its small fuel connection on the setting's right wall
     * (seen from the front) at ground level, the fuel nozzle itself. A fired still has no data port: only the electric
     * still takes a controller (owner, 2026-11-09).
     */
    private static void stillSetting(ProcessUnit u, StillPlan p) {
        double x = StillPlan.POT_X, z = StillPlan.POT_Z, h = p.w() / 2, x0 = x - h, x1 = x + h, z0 = z - h, z1 = z + h, top = p.setting(), cope = top - .06;
        u.box(new double[]{x0, 0, z0}, new double[]{x1, cope, z1}, Mesh.ALL_FACES & ~4, Material.BRICK, true);
        u.box(new double[]{x0 - .03, cope, z0 - .03}, new double[]{x1 + .03, top, z1 + .03}, Mesh.ALL_FACES, Material.BRICK, true);
        // The flue: up from the coping in the back right corner seen from the front (frame -x, +z; owner, 2026-11-10), a
        // cap at its top, its mouth dark.
        double s = p.flue(), fx = x0 + s, fz = z1 - s, ft = p.flueTop();
        u.box(new double[]{x0, top, fz}, new double[]{fx, ft - .08, z1}, Mesh.ALL_FACES & ~4, Material.BRICK, true);
        u.box(new double[]{x0 - .03, ft - .08, fz - .03}, new double[]{fx + .03, ft, z1 + .03}, Mesh.ALL_FACES, Material.BRICK, true);
        u.box(new double[]{x0 + .05, ft, fz + .05}, new double[]{fx - .05, ft + .002, z1 - .05}, 8, Material.OPENING, false);
        u.mark("flue_top", new double[]{x0 + .05, ft, fz + .05}, new double[]{fx - .05, ft + .05, z1 - .05});
        // The firing door in its frame, its handle; the ash-pit door under it; the grate behind.
        double d = p.d(), wd = .25 + .12 * d, hd = .22 + .1 * d, g = p.grate();
        u.box(new double[]{x - wd / 2 - .03, g - .03, z0 - .015}, new double[]{x + wd / 2 + .03, g + hd + .03, z0}, Mesh.ALL_FACES & ~32, Material.IRON, false);
        u.box(new double[]{x - wd / 2, g, z0 - .035}, new double[]{x + wd / 2, g + hd, z0 - .015}, Mesh.ALL_FACES & ~32, Material.IRON, true);
        u.mesh.bar(new double[]{x + wd / 2 - .06, g + hd / 2 - .05, z0 - .045}, new double[]{x + wd / 2 - .06, g + hd / 2 + .05, z0 - .045}, .02, new double[]{0, 0, 1}, Material.IRON);
        u.mark("door", new double[]{x - wd / 2, g, z0 - .035}, new double[]{x + wd / 2, g + hd, z0 - .015});
        u.box(new double[]{x - wd / 3, .05, z0 - .025}, new double[]{x + wd / 3, g - .05, z0}, Mesh.ALL_FACES & ~32, Material.IRON, false);
        u.mark("fire_bed", new double[]{x - .3 * p.w(), g, z - .3 * p.w()}, new double[]{x + .3 * p.w(), g + .06, z + .3 * p.w()});
        if (p.options().heater() == BatchStillOptions.Heater.LIQUID) {
            // The burner (owner, 2026-11-09: it faces right with the fuel connection, a small pipe connector is enough, at
            // ground level): an iron port plate on the right wall in the middle row, the fuel nozzle on its face, its neck
            // across the right column to the frame's face.
            double yn = .5;
            u.box(new double[]{x0 - .01, yn - .06, z - .06}, new double[]{x0, yn + .06, z + .06}, Mesh.ALL_FACES & ~2, Material.IRON, false);
            u.mark("burner", new double[]{x0, yn - .06, z - .06}, new double[]{x0 + .15, yn + .06, z + .06});
            u.nozzles.add(new Nozzle(Role.FUEL, new double[]{x0 - .01, yn, z}, 0, -1, x0 - .01, .03, 0));
        }
    }

    /**
     * An electric still's skirt as a drum's (owner, 2026-11-10: a skirt with heating elements, as a drum's skirt): the
     * steel plate flush with the pot's shell from grade to its flat bottom, the heating elements inside under it, a band at
     * the junction, two vents under the band on the left, the base ring (no anchor chairs under 1.5 m, as a drum's). The
     * ring stays within 0.69 m of the axis, clear of the corner blocks round the pot's block (0.707 m), where the terminal
     * box's sockets open: the 1500 and 2000 L rings are narrower. The terminal box stands on a stand in
     * the right column's centre seen from the front, on the middle row (owner, 2026-11-10: the FE socket faced the skirt,
     * no cable could reach it): its pilot lamp outward, its data socket to the front and its FE socket to the back, a
     * conduit straight into the skirt. The box is 0.52 m wide (owner, 2026-11-10): the pot and skirt are one cylinder
     * that bows 0.08 m across a plate, so the still's nameplate ({@link Nameplate}, 0.5 m) sits on the box's outward face.
     */
    private static void stillSkirt(ProcessUnit u, StillPlan p) {
        double x = StillPlan.POT_X, z = StillPlan.POT_Z, r = p.d() / 2, top = p.bottom();
        int n = ProcessUnit.sides(p.d());
        u.skirt(x, z, r, top, n);
        u.baseRing(x, z, r, p.d(), n, .69);
        u.mesh.hoop(Frame.of(new double[]{x, 0, z}, 1, 1), r, r + .02, top - .1, top, n, Material.FLANGE);
        for (double t : new double[]{-Math.PI / 4, Math.PI / 4}) {
            var vent = new Frame(Access.around(x, z, r, t, top - .25), ProcessUnit.outward(t), new double[]{0, 1, 0});
            u.mesh.prism(vent, .05, -.03, .05, 8, Material.SUPPORT);
            u.mesh.annulus(vent, .035, .05, .05, 8, 1, Material.SUPPORT);
            u.mesh.bore(vent, .035, -.03, .05, 8, Material.SUPPORT);
            u.mesh.annulus(vent, 0, .035, -.03, 8, 1, Material.OPENING);
        }
        u.round(x, z, 0, top, r, 1);
        double[] lo = {.38, .2, z - .26}, hi = {.62, .8, z + .26};
        u.box(new double[]{.44, 0, z - .1}, new double[]{.56, .2, z + .1}, Mesh.ALL_FACES & ~4, Material.STEELWORK, true);
        u.box(lo, hi, Mesh.ALL_FACES, Material.MACHINE, true);
        u.mesh.disc(new Frame(new double[]{lo[0] - .002, .7, z}, new double[]{-1, 0, 0}, new double[]{0, 0, 1}), .025, 0, 8, 1, Material.GLASS);
        u.mark("lamp", new double[]{lo[0] - .01, .675, z - .025}, new double[]{lo[0], .725, z + .025});
        double yc = Math.min(.7, top - .15);
        u.line(.02, 6, Material.PIPE, new double[]{hi[0], yc, z}, new double[]{x - r + .01, yc, z});
        u.serviceHousing = new double[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
    }

    /**
     * The charge line in at the front on the pot's line (owner, 2026-11-10: the pot on a block's centre), on its row: straight
     * back along the pot's front meridian, into the shell or the head square to its wall where its row meets them, else
     * down onto the head at 0.6 of its radius (owner, 2026-11-09: the oblique entries looked wrong). Its end lies 5 cm in,
     * past the faceted wall.
     */
    private static void stillCharge(ProcessUnit u, StillPlan p) {
        double x = StillPlan.POT_X, z = StillPlan.POT_Z, y = p.charge(), r = p.d() / 2, rq = p.radiusAt(y), pr = .03 + .01 * p.d();
        double[] base = {x, y, .3};
        if (rq > 0) u.pipe(pr, p.line(), true, base, new double[]{x, y, z - rq + .05});
        else u.pipe(pr, p.line(), true, base, new double[]{x, y, z - .6 * r}, new double[]{x, p.cylinder() + .8 * p.d() / 4 - .01, z - .6 * r});
        u.nozzles.add(ProcessUnit.end(Role.CHARGE, base, 2, -1, 2 * pr));
    }

    /** The drain from the pot's bottom, down inside the setting or skirt, then straight back on the pot's line to the flange at the back, low. */
    private static void stillDrain(ProcessUnit u, StillPlan p) {
        double x = StillPlan.POT_X, z = StillPlan.POT_Z, r = p.d() / 2, pr = .03 + .01 * p.d();
        double[] base = {x, .5, 2.7};
        u.pipe(pr, p.line(), true, new double[]{x, p.bottom() + .01, z + .4 * r}, new double[]{x, .5, z + .4 * r}, base);
        u.nozzles.add(ProcessUnit.end(Role.DRAIN, base, 2, 1, 2 * pr));
    }

    /**
     * A pot still's helmet on the head's apex, its collar and a sight glass on its front. Its foot starts a centimetre
     * under the drawn head at its collar's rim (the head's facets sag up to 28 mm under the ellipsoid there; owner,
     * 2026-11-10: a seam showed under the collar).
     */
    private static void stillHelmet(ProcessUnit u, StillPlan p) {
        double x = StillPlan.POT_X, z = StillPlan.POT_Z, rh = p.helmet(), y1 = p.helmetTop() - rh / 2;
        double y0 = p.cylinder() + u.mesh.headHeight(p.d() / 2, p.d() / 4, ProcessUnit.sides(p.d()), rh + .02) - .01;
        int n = ProcessUnit.sides(2 * rh);
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        u.mesh.hoop(up, rh, rh + .02, y0, y0 + .04, n, p.finish());
        u.mesh.prism(up, rh, y0, y1, n, p.finish());
        u.mesh.head(up, rh, y1, rh / 2, n, p.finish());
        u.mesh.disc(new Frame(new double[]{x, (y0 + y1) / 2, z - rh - .004}, new double[]{0, 0, -1}, new double[]{1, 0, 0}), .4 * rh, 0, 12, 1, Material.GLASS);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, y0, y1, rh, rh / 2));
        u.round(x, z, y0, y1, rh, 1);
    }

    /**
     * The condenser overhead, the same for a pot still and a column (owner, 2026-11-09): a shell in the still's finish lying
     * front to back on the left seen from the front (the left column), the gooseneck from the helmet or the column's top
     * over and down into its back half; cooling water in at its front end and out at its back end (against the vapour);
     * the vent from its cold front end straight up. A column's condensate falls into the reflux pot (a gauge glass on its
     * front) under the cold end, and the hand-wheel valve under the pot sends the reflux back to the column's top; a pot
     * still's condensate runs straight down. The product passes a sight glass to the spout at the front. The shell rests on
     * two saddles, each on a steel H-frame: a leg either side of the shell's line and a cross beam under the saddle, so its
     * weight goes straight down (owner, 2026-11-09: not on outboard posts); the spout's drop passes between the front legs.
     */
    private static void stillCondenser(ProcessUnit u, StillPlan p) {
        double d = p.d(), x = StillPlan.POT_X, z = StillPlan.POT_Z;
        boolean column = p.options().column();
        double neck = column ? p.columnTop() + p.column() / 2 : p.helmetTop();
        double yk = p.condenser(), rk = p.shell(), ra = p.arm(), cx = StillPlan.SPOUT_X, zc = StillPlan.COLD_END, sz0 = StillPlan.SHELL_FRONT, sz1 = StillPlan.SHELL_BACK, ch0 = .4, ch1 = 2.25;
        var along = Frame.of(new double[]{cx, yk, 0}, 2, 1);
        var shell = p.finish();
        u.mesh.prism(along, rk, sz0, sz1, ProcessUnit.sides(2 * rk), shell);
        u.mesh.cylinder(along, .92 * rk, ch0, sz0, 12, shell);
        u.mesh.cylinder(along, .92 * rk, sz1, ch1, 12, shell);
        // A tube sheet and flange at each end, closing the shell round its channel (owner, 2026-11-10: the open ring
        // between shell and channel showed through).
        for (double at : new double[]{sz0, sz1}) u.mesh.ring(along, .9 * rk, rk + .025, at - .02, at + .02, 12, 3, shell);
        u.solids.add(Solid.rod(new double[]{cx, yk, 0}, 2, ch0, ch1, rk + .025, 0));
        u.round(new double[]{cx, yk, 0}, 2, sz0, sz1, rk);
        double yg = p.gooseneck();
        u.line(ra, 12, p.line(), new double[]{x, neck - .02, z}, new double[]{x, yg, z}, new double[]{cx, yg, z}, new double[]{cx, yk + rk - .02, z});
        // Cooling water in at the cold front end, from under its channel a row under the condenser (a free block from the
        // vent's flange a row over it), out at the back end.
        double wr = .03 + .01 * d, yw = yk - 1;
        u.pipe(wr, Material.PIPE, true, new double[]{cx, yk - .9 * rk, .5}, new double[]{cx, yw, .5}, new double[]{cx, yw, .3});
        u.nozzles.add(ProcessUnit.end(Role.WATER_INLET, new double[]{cx, yw, .3}, 2, -1, 2 * wr));
        u.pipe(wr, Material.PIPE, true, new double[]{cx, yk, ch1}, new double[]{cx, yk, 2.7});
        u.nozzles.add(ProcessUnit.end(Role.WATER_OUTLET, new double[]{cx, yk, 2.7}, 2, 1, 2 * wr));
        // The vent from the cold end's channel straight up on its block's centre line, its flange on the condenser's row's
        // top (owner, 2026-11-10: the riser to the row above stuck out).
        double vr = .015 + .005 * d, lr = .015 + .005 * d, zv = .5, yt = yk + .2;
        u.pipe(vr, p.line(), true, new double[]{cx, yk + .9 * rk - .02, zv}, new double[]{cx, yt, zv});
        u.nozzles.add(ProcessUnit.end(Role.VENT, new double[]{cx, yt, zv}, 1, 1, 2 * vr));
        double rs = .02 + .01 * d, rg = 1.8 * rs, rp = .07 + .04 * d, yr1 = yk - rk - .1, yr0 = yr1 - .3;
        if (column) {
            // The reflux pot under the cold end, the condensate straight down into it.
            u.line(lr, 8, p.line(), new double[]{cx, yk - rk + .02, zc}, new double[]{cx, yr1 - .02, zc});
            u.mesh.cylinder(Frame.of(new double[]{cx, 0, zc}, 1, 1), rp, yr0, yr1, 12, p.finish());
            u.box(new double[]{cx - .015, yr0 + .04, zc - rp - .02}, new double[]{cx + .015, yr1 - .04, zc - rp + .01}, Mesh.ALL_FACES & ~32, Material.GLASS, false);
            u.solids.add(Solid.rod(new double[]{cx, 0, zc}, 1, yr0, yr1, rp, 0));
            // The reflux valve under it, its hand wheel to the front.
            double yv = yr0 - .14, wz = zc - .17;
            u.line(lr, 8, p.line(), new double[]{cx, yr0, zc}, new double[]{cx, yv, zc});
            u.box(new double[]{cx - .05, yv - .05, zc - .05}, new double[]{cx + .05, yv + .05, zc + .05}, Material.IRON, true);
            u.mesh.bar(new double[]{cx, yv, zc - .05}, new double[]{cx, yv, wz}, .015, new double[]{0, 1, 0}, Material.IRON);
            var wheel = new Frame(new double[]{cx, yv, wz}, new double[]{0, 0, -1}, new double[]{1, 0, 0});
            u.mesh.ring(wheel, .065, .08, 0, .015, 16, 3, Material.IRON);
            u.mesh.bar(new double[]{cx - .07, yv, wz - .008}, new double[]{cx + .07, yv, wz - .008}, .012, new double[]{0, 0, 1}, Material.IRON);
            u.mesh.bar(new double[]{cx, yv - .07, wz - .008}, new double[]{cx, yv + .07, wz - .008}, .012, new double[]{0, 0, 1}, Material.IRON);
            u.mark("reflux_valve", new double[]{cx - .08, yv - .08, wz - .015}, new double[]{cx + .08, yv + .08, zc + .05});
            // The reflux back to the column's top, run square as a plant's line (owner, 2026-11-10: no straight line from
            // point to point): from the valve back along the cold end's line to beside the column, along to the column's
            // line, down, and into the column's side square to it, under its top; the product down past a sight glass to
            // the spout.
            double rc = p.column(), xs = x + rc + .12, yi = p.columnTop() - .12;
            u.line(lr, 8, p.line(), new double[]{cx - .05, yv, zc}, new double[]{xs, yv, zc}, new double[]{xs, yv, z}, new double[]{xs, yi, z},
                    new double[]{x + rc - .02, yi, z});
            u.pipe(rs, p.line(), true, new double[]{cx, yv - .05, zc}, new double[]{cx, .5, zc}, new double[]{cx, .5, .3});
        } else {
            // A pot still's condensate from the cold end straight down to the spout.
            u.pipe(rs, p.line(), true, new double[]{cx, yk - rk + .02, zc}, new double[]{cx, .5, zc}, new double[]{cx, .5, .3});
        }
        sightGlass(u, new double[]{cx, .78, zc}, new double[]{0, 1, 0}, rg, .15, p.line());
        u.mark("spout", new double[]{cx - rg, .78, zc - rg}, new double[]{cx + rg, .93, zc + rg});
        u.nozzles.add(ProcessUnit.end(Role.DISTILLATE, new double[]{cx, .5, .3}, 2, -1, 2 * rs));
        // The stand: a saddle near each end of the shell, clear of its girth hoops and the condensate outlet at the cold end;
        // under each a cross beam, its top just under the shell and over the reflux pot, on two legs 0.3 m either side.
        double yb = yk - rk - .05, xl = cx - .3, xr = cx + .3;
        for (double zs : new double[]{sz0 + .06, sz1 - .1}) {
            // The saddle (its foot on the beam, not drawn) and the beam closed on every face: nothing covers its top.
            u.box(new double[]{cx - .8 * rk, yb, zs - .03}, new double[]{cx + .8 * rk, yk - .7 * rk, zs + .03}, Mesh.ALL_FACES & ~4, Material.STEELWORK, false);
            u.box(new double[]{xl - .03, yb - .04, zs - .03}, new double[]{xr + .03, yb, zs + .03}, Mesh.ALL_FACES, Material.STEELWORK, true);
            for (double xs : new double[]{xl, xr}) u.post(xs, zs, 0, yb - .04, .06, true);
        }
        // The reflux pot's bracket from the front right leg, square: back along the leg's side, then across to the pot.
        if (column) {
            double zf = sz0 + .06, yh = yr0 + .15;
            u.mesh.bar(new double[]{xr, yh, zf + .03}, new double[]{xr, yh, zc}, .04, new double[]{0, 1, 0}, Material.STEELWORK);
            u.mesh.bar(new double[]{xr + .02, yh, zc}, new double[]{cx + rp - .01, yh, zc}, .04, new double[]{0, 1, 0}, Material.STEELWORK);
        }
    }

    /** A pot still without a condenser: its swan neck over to the vapour flange on the left face, on a post in the left column. */
    private static void stillVapour(ProcessUnit u, StillPlan p) {
        double x = StillPlan.POT_X, z = StillPlan.POT_Z, ra = p.arm(), yv = StillPlan.rowAtLeast(p.helmetTop() + .1);
        u.line(ra, 12, p.line(), new double[]{x, p.helmetTop() - .02, z}, new double[]{x, yv + .15, z}, new double[]{x + .35, yv + .17, z},
                new double[]{StillPlan.SPOUT_X + .2, yv, z});
        u.post(StillPlan.SPOUT_X, z, 0, yv - ra - .01, .06, true);
        u.nozzles.add(ProcessUnit.end(Role.VAPOUR_OUTLET, new double[]{StillPlan.SPOUT_X + .2, yv, z}, 0, 1, 2 * ra));
    }

    /** A still's column on the pot, a porthole on the front of each stage; its condenser is {@link #stillCondenser}'s. */
    private static void stillColumn(ProcessUnit u, StillPlan p) {
        double d = p.d(), x = StillPlan.POT_X, z = StillPlan.POT_Z, rc = p.column(), y0 = p.columnBase(), yt = p.columnTop();
        int n = ProcessUnit.sides(2 * rc);
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        u.mesh.prism(up, rc, y0, yt, n, p.finish());
        u.mesh.head(up, rc, yt, rc / 2, n, p.finish());
        u.mesh.hoop(up, rc, rc + .025, y0, y0 + .04, n, p.finish());
        for (int k = 0; k < p.options().stages(); k++) {
            double yk = y0 + .25 + .3 * k;
            u.mesh.hoop(up, rc, rc + .02, yk - .14, yk - .11, n, p.finish());
            var face = new Frame(new double[]{x, yk, z - rc - .006}, new double[]{0, 0, -1}, new double[]{1, 0, 0});
            u.mesh.ring(face, .5 * rc, .62 * rc, -.006, .004, 12, 2, p.finish());
            u.mesh.disc(face, .5 * rc, 0, 12, 1, Material.GLASS);
        }
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, y0, yt, rc + .02, rc / 2));
        u.round(x, z, y0, yt, rc, 1);
    }

    /** A sight glass on a line: a glass barrel of radius {@code r} from {@code start} along the unit {@code dir} for {@code length}, a {@code collar} at each end. */
    private static void sightGlass(ProcessUnit u, double[] start, double[] dir, double r, double length, Material collar) {
        var ref = Math.abs(dir[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        var f = new Frame(start, dir, Mesh.unit(Mesh.cross(dir, ref)));
        u.mesh.cylinder(f, r, .02, length - .02, 12, Material.GLASS);
        u.mesh.ring(f, .55 * r, 1.15 * r, 0, .02, 12, 3, collar);
        u.mesh.ring(f, .55 * r, 1.15 * r, length - .02, length, 12, 3, collar);
    }
}
