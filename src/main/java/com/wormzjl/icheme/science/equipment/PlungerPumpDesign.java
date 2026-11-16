package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Motion;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.PlungerPump;
import com.wormzjl.icheme.science.machine.PlungerPump.Model;

import java.util.List;

/**
 * A triplex plunger pump casing on a shaft train (batch 2026-11-15-supercritical-fluids, stage S8, PLUNGER_PUMP_PLAN.md
 * section 4; owner "Form A"): a catalogue of four models by displacement (3, 10, 30 and 100 m³/h at 300 rpm, relief rise
 * 25 MPa). The shape's size is the model's number (1 to 4), its length 1, so a model is the kind and its size: no shape
 * field of its own.
 *
 * <p>The form: a horizontal triplex. An extruded crankcase with the crank along the shaft line, bearing covers at both
 * ends, a top cover, a breather and an oil sight glass; three crosshead guides out of its nose; an open cradle with the three
 * plungers in their stuffing boxes (three motions, phases 0, 1/3 and 2/3, at the crank's speed); the liquid end one forged
 * block overhanging the skid, a bolted cylinder cover in front of each plunger and a valve cover on top of each; the suction
 * manifold under the block, its riser at the front; the discharge out of the block's back end, a bladder dampener on its
 * tee; both flange faces 2 m up on every model. The coupling and the shaft end are at the back, where a shaft block joins
 * the driver. The two small models stand on a plinth (as the small vacuum pumps); the relief valve is inside the law, not
 * drawn. The drawing ({@link #monoblock} and its helpers) was drawn in {@code tmp/plunger-forms} and moved here unchanged.
 */
final class PlungerPumpDesign extends UnitDesign {
    static final double GROUT = .04, BASEPLATE = .16, XC = .5, YC = 1.5, ZM = 2.5;

    record Dim(double k, double cap) {
        double p() { return .40 * k; }
        double kw() { return cap / 3600 * 25e6 / .88 / 1e3; }
        double suction() { return PipeSizes.od(Math.sqrt(4 * cap / 3600 / (Math.PI * 1.5)), .05, .4); }
        double discharge() { return PipeSizes.od(Math.sqrt(4 * cap / 3600 / (Math.PI * 4)), .05, .4); }
        double[] zs() { return new double[]{ZM - p(), ZM, ZM + p()}; }
        double zA() { return ZM - 1.5 * p() - .04 * k; }
        double zB() { return ZM + 1.5 * p() + .04 * k; }
    }

    PlungerPumpDesign() {
        super(new Sizes(1, PlungerPump.MODELS.size(), 1,
                1, 1,
                0, 0,
                Orientation.HORIZONTAL, "plunger_pump.model", null, null, true,
                1));
    }

    /** The model of {@code shape}: its size's number. */
    static Model model(EquipmentShape shape) {
        return PlungerPump.model(shape.diameter());
    }

    /** The law of {@code shape}: its model's displacement at 300 rpm, relief rise 25 MPa. */
    static PlungerPump law(EquipmentShape shape) {
        return PlungerPump.of(model(shape));
    }

    /** The model's name: {@code PP-30}. */
    static String name(EquipmentShape shape) {
        return model(shape).name();
    }

    /** A model reads as its displacement, the unit beside the box (the box holds the number only); its name is on the datasheet. */
    @Override public String sizeUnit() {
        return "m³/h";
    }

    @Override public String sizeText(double size) {
        return whole(PlungerPump.model(size).capacity());
    }

    @Override public boolean lengthInMetres() {
        return false;
    }

    @Override public Line held(EquipmentShape shape) {
        var law = law(shape);
        // The middle display's line fits 180 px (UnitTextTest): the shaft power stays on the datasheet, a whole motor rating
        // reads without its decimal (110 kW, 7.5 kW).
        double motor = Motor.ratingFor(law.maximumShaftPower()) / 1e3;
        return new Line("plunger_pump.held", name(shape), whole(model(shape).capacity()), motor == Math.rint(motor) ? whole(motor) : number(motor));
    }

    @Override public List<Line> datasheet(EquipmentShape shape) {
        var law = law(shape);
        var m = model(shape);
        return List.of(new Line("plunger_pump.model", m.name(), whole(m.capacity()), whole(law.ratedSpeed())),
                new Line("plunger_pump.relief", whole(law.reliefRise() / 1e6)),
                new Line("plunger_pump.power", number(law.maximumShaftPower() / 1e3), number(Motor.ratingFor(law.maximumShaftPower()) / 1e3)));
    }

    @Override void build(ProcessUnit u) {
        var m = model(u.shape);
        monoblock(u, new Dim(m.scale(), m.capacity()));
    }

    // ---- Form A (tmp/plunger-forms/src/.../PlungerForms.java, moved unchanged: the same expressions, the same order) ----

    static double[] at(double x, double y, double z) { return new double[]{x, y, z}; }

    /** A bolted round cover on frame {@code f} from station {@code s} out by {@code t}. */
    static void cover(ProcessUnit u, Frame f, double r, double s, double t, int bolts) {
        u.mesh.band(f, r, r, s, s + t, 12, Material.PUMP_JOINT);
        u.mesh.disc(f, r, s + t, 12, 1, Material.PUMP_DISC);
        if (bolts > 0) u.mesh.bolts(f, Math.min(2 * r, .4), .45 * r, r, s + t, 1, bolts, Material.BOLT);
        else u.mesh.nut(f, .45 * r, s + t, s + 1.9 * t, 6, Material.BOLT);
    }

    /** The crank's bearing covers at both ends of a case from z0 to z1, the shaft leaving the back one. */
    static void bearings(ProcessUnit u, double k, double z0, double z1) {
        var along = Frame.of(at(XC, YC, 0), 2, 1);
        double rb = .3 * k, t = .06 * k;
        int n = ProcessUnit.sides(2 * rb);
        u.mesh.band(along, rb, rb, z0 - t, z0, n, Material.PUMP_JOINT);
        u.mesh.disc(along, rb, z0 - t, n, -1, Material.PUMP_DISC);
        u.mesh.band(along, rb, rb, z1, z1 + t, n, Material.PUMP_JOINT);
        u.mesh.annulus(along, 0, rb, z1 + t, n, 1, Material.PUMP_DISC);
    }

    /** The shaft end at the back, the baseplate from x0 to x1 on its grout and plinth. */
    static void drive(ProcessUnit u, Dim d, double z0, double z1, double x0, double x1, double least) {
        double k = d.k, deck = YC - .5 * k, base = deck - GROUT - BASEPLATE, zb = z1 + .06 * k;
        double rs = ProcessUnit.shaftRadius(d.kw(), 300), face = Math.max(least, Math.ceil(zb + .2 + rs - 1e-9));
        u.coupling(XC, YC, zb, face, 1, rs, deck, ProcessUnit.shown(300), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        double za = z0 - .14 * k, zz = face - .01;
        u.baseplate(x0, x1, base + GROUT, deck, za, zz);
        u.box(at(x0 + .02, base, za + .02), at(x1 - .02, base + GROUT, zz - .02), Material.CONCRETE, false);
        u.driverBase(x0 - .08, x1 + .08, za - .08, zz, base, 0);
    }

    /** A horizontal power end: the crank along z, the crosshead guides out of its nose towards +x as far as xg. */
    static void powerEnd(ProcessUnit u, Dim d, double z0, double z1, double nose, double xg) {
        double k = d.k, b = YC - .5 * k;
        u.extrude(List.of(at(XC - .55 * k, b, 0), at(nose, b, 0), at(nose, YC + .3 * k, 0), at(XC + .25 * k, YC + .5 * k, 0), at(XC - .3 * k, YC + .5 * k, 0),
                at(XC - .55 * k, YC + .25 * k, 0)), z0, z1, Material.PUMP_CASTING);
        u.solids.add(Solid.box(at(XC - .55 * k, b, z0), at(nose, YC + .5 * k, z1)));
        u.solids.add(Solid.box(at(nose, YC - .2 * k, d.zA()), at(xg, YC + .2 * k, d.zB())));
        u.box(at(XC - .26 * k, YC + .5 * k, z0 + .12 * k), at(XC + .2 * k, YC + .53 * k, z1 - .12 * k), Material.PUMP_JOINT, false);
        for (double z : new double[]{z0 + (z1 - z0) / 3, z1 - (z1 - z0) / 3})
            u.box(at(XC - .58 * k, b + .08 * k, z - .03 * k), at(XC - .55 * k, YC + .22 * k, z + .03 * k), Material.PUMP_CASTING, false);
        u.mesh.cylinder(Frame.of(at(XC - .38 * k, YC - .2 * k, 0), 2, 1), .05 * k, z0 - .03 * k, z0, 8, Material.GLASS);
        u.mesh.cylinder(Frame.of(at(XC - .1 * k, 0, z0 + .3 * k), 1, 1), .045 * k, YC + .53 * k, YC + .68 * k, 8, Material.PUMP_CASTING);
        bearings(u, k, z0, z1);
        for (double z : new double[]{z0 + .08 * k, z1 - .08 * k})
            u.box(at(XC - .62 * k, b, z - .08 * k), at(nose + .06 * k, b + .08 * k, z + .08 * k), Material.PUMP_CASTING, false);
        for (double z : d.zs()) {
            var f = Frame.of(at(0, YC, z), 0, 1);
            u.mesh.cylinder(f, .17 * k, nose, xg, 12, Material.PUMP_CASTING);
            u.mesh.ring(f, .17 * k, .2 * k, xg - .04 * k, xg, 12, 3, Material.PUMP_JOINT);
        }
        // Ribs on the nose between the guides.
        double[] zs = d.zs();
        for (double z : new double[]{(zs[0] + zs[1]) / 2, (zs[1] + zs[2]) / 2})
            u.box(at(nose, YC - .3 * k, z - .025 * k), at(nose + .3 * k, YC + .28 * k, z + .025 * k), Material.PUMP_CASTING, false);
    }

    /** The open cradle from the guides at xg to the liquid end at xf: its tray, the plungers in their stuffing boxes, the stay rods. */
    static void cradle(ProcessUnit u, Dim d, double xg, double xf) {
        double k = d.k, zA = d.zA(), zB = d.zB();
        u.solids.add(Solid.box(at(xg, YC - .32 * k, zA), at(xf, YC + .27 * k, zB)));
        u.box(at(xg - .02 * k, YC - .32 * k, zA), at(xf, YC - .27 * k, zB), Material.PUMP_CASTING, false);
        for (double z : new double[]{zA, zB - .035 * k})
            u.box(at(xg - .02 * k, YC - .32 * k, z), at(xf, YC + .02 * k, z + .035 * k), Material.PUMP_CASTING, false);
        double[] zs = d.zs();
        for (int i = 0; i < 3; i++) {
            var f = Frame.of(at(0, YC, zs[i]), 0, 1);
            u.mesh.cylinder(f, .1 * k, xf - .16 * k, xf, 12, Material.FLANGE);
            u.mesh.cylinder(f, .135 * k, xf - .2 * k, xf - .16 * k, 12, Material.FLANGE);
            u.mesh.part(u.motions.size());
            u.mesh.cylinder(f, .05 * k, xg - .05 * k, xf - .1 * k, 10, Material.SHAFT);
            u.mesh.part(-1);
            u.motions.add(new Motion(at((xg + xf) / 2, YC, zs[i]), at(1, 0, 0), ProcessUnit.CRANK_SPEED, i / 3.0, .06 * k, .3 * k));
        }
        for (double z : new double[]{zA + .06 * k, zB - .06 * k, (zs[0] + zs[1]) / 2, (zs[1] + zs[2]) / 2})
            u.mesh.cylinder(Frame.of(at(0, YC + .25 * k, z), 0, 1), .022 * k, xg - .03 * k, xf, 8, Material.BOLT);
    }

    /** A pulsation dampener: a neck up from (x, y, z) and a bladder sphere on it. */
    static void dampener(ProcessUnit u, double x, double y, double z, double neck, double r) {
        var up = Frame.of(at(x, 0, z), 1, 1);
        u.mesh.cylinder(up, .35 * r, y, y + neck, 10, Material.PUMP_CASTING);
        u.mesh.ring(up, .35 * r, .55 * r, y + neck - .04, y + neck, 12, 3, Material.FLANGE);
        u.mesh.sphere(at(x, y + neck + .92 * r, z), r, 16, Material.HEAD);
        u.mesh.cylinder(up, .12 * r, y + neck + 1.9 * r, y + neck + 2.08 * r, 6, Material.BOLT);
        u.solids.add(Solid.rod(at(x, 0, z), 1, y, y + neck + 1.92 * r, r, 0));
    }

    /** A line of radius r from the liquid end at (x, y, z) along z (sign s) to a riser at a cell centre and up to a flange. Returns the riser's z. */
    static double riser(ProcessUnit u, Role role, double x, double y, double z, int s, double clear, double od) {
        double r = od / 2 + .015, bend = Math.max(.15, 2 * r);
        double want = z + s * (clear + bend), zn = s > 0 ? Math.ceil(want - .5) + .5 : Math.floor(want - .5) + .5;
        double out = .05 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT, face = Math.ceil(y + bend + r + .05 + out - 1e-9), from = face - out;
        u.pipe(r, Material.PUMP_CASTING, true, at(x, y, z), at(x, y, zn), at(x, from, zn));
        u.pumpNozzle(role, at(x, 0, zn), 1, 1, from, face, od, od / 2);
        return zn;
    }

    /** Form A: a horizontal triplex with a monoblock liquid end overhanging the skid. */
    static void monoblock(ProcessUnit u, Dim d) {
        double k = d.k, p = d.p(), z0 = ZM - 1.5 * p - .25 * k, z1 = ZM + 1.5 * p + .25 * k, nose = XC + .45 * k, xg = XC + .85 * k;
        double xd = Math.ceil(XC + 1.3 * k - .5) + .5, xf0 = xd - .275 * k, xf1 = xd + .275 * k, top = YC + .4 * k, bot = YC - .4 * k, zA = d.zA(), zB = d.zB();
        powerEnd(u, d, z0, z1, nose, xg);
        cradle(u, d, xg, xf0);
        u.box(at(xf0, bot, zA), at(xf1, top, zB), Material.PUMP_CASTING, true);
        double rm = d.suction() / 2 + .015, ym = bot - rm - .05 * k;
        for (double z : d.zs()) {
            cover(u, Frame.of(at(0, YC, z), 0, 1), .15 * k, xf1, .05 * k, 6);
            cover(u, Frame.of(at(xd, 0, z), 1, 1), .14 * k, top, .06 * k, 0);
            u.mesh.cylinder(Frame.of(at(xd, 0, z), 1, 1), .8 * rm, ym, bot, 10, Material.PUMP_CASTING);
        }
        // The suction manifold under the block, its riser at the front; the discharge out of the back end, its dampener on a tee.
        u.mesh.disc(Frame.of(at(xd, ym, 0), 2, 1), rm, zB, 12, 1, Material.PUMP_CASTING);
        riser(u, Role.SUCTION, xd, ym, zB, -1, zB - zA + .12, d.suction());
        double rd = d.discharge() / 2 + .015, yd = YC, rb = .17 * k + .04;
        double zr = riser(u, Role.DISCHARGE, xd, yd, zB - .02, 1, 2 * rb + .05, d.discharge());
        dampener(u, xd, yd, zB + rb + .04, .12 * k + rd, rb);
        // A shaft block stands two cells clear of a pipe block: where the risers are in the cell row next to the shaft line, the shaft end reaches a block further back.
        drive(u, d, z0, z1, XC - .65 * k, xf0 - .06 * k, Math.floor(xd) - Math.floor(XC) < 2 ? zr + 1.5 : 0);
    }

}
