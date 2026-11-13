package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * A centrifugal pump casing by its design point ({@link #build}; batch 2026-11-03-rotating-machinery, W4):
 * rated flow, rated head and speed class (2 or 4 poles). Its discharge carries the flow at 3 m/s; its impeller follows
 * the head and the speed. API 610 OH2 up to a {@link ProcessUnit#OH2_MAX} discharge, BB2 radially split between bearings above, both nozzles on top, up to
 * 0.6 m. It has no motor of its own: its shaft end takes a driver through a shaft block (owner decision D4). Its plinth
 * stands at grade; the constructor's Up lifts it onto a deck. A pump saved before its design point takes the datasheet's
 * values of its discharge (decision A3): the flow at 3 m/s, 100 m, 2 poles.
 */
final class PumpDesign extends UnitDesign {
    /** The pump's heads, m, and the least flow, m³/h. */
    static final double MIN_HEAD = 5, MAX_HEAD = 300, MIN_FLOW = 1;
    /** A pump saved before its design point: its head, m, and its poles (decision A3). */
    static final double DEFAULT_HEAD = 100;
    static final int DEFAULT_POLES = 2;

    PumpDesign() {
        // maxSize: The largest discharge whose suction, one size larger (ProcessUnit.pumpSuction), is a pipe's largest.
        super(new Sizes(ProcessUnit.SUCTION_STEP, .6, ProcessUnit.SUCTION_STEP,
                1, 1,
                0, 0,
                Orientation.HORIZONTAL, "pump_discharge", null, null, true));
    }

    @Override
    public boolean hasDuty() {
        return true;
    }

    @Override
    public MachineDuty duty(double size, int length) {
        return MachineDuty.pump(UnitRatings.pumpFlow(size), DEFAULT_HEAD, DEFAULT_POLES);
    }

    @Override
    public double size(MachineDuty duty) {
        return UnitRatings.pumpDischarge(duty.flow());
    }

    @Override
    void checkDuty(MachineDuty duty) {
        if (!(duty.flow() >= MIN_FLOW && duty.flow() <= UnitRatings.pumpFlow(maxSize()) + 1e-6))
            throw new IllegalArgumentException("A pump's rated flow is " + MIN_FLOW + " to " + whole(UnitRatings.pumpFlow(maxSize())) + " m³/h, not " + duty.flow());
        if (!(duty.head() >= MIN_HEAD && duty.head() <= MAX_HEAD)) throw new IllegalArgumentException("A pump's rated head is 5 to 300 m, not " + duty.head());
        if (duty.poles() != 2 && duty.poles() != 4) throw new IllegalArgumentException("A pump runs at 2 or 4 poles, not " + duty.poles());
        if (duty.polytropicHead() != 0 || duty.power() != 0 || duty.steam() != null || duty.exhaust() != 0 || duty.drive() != MachineDuty.Drive.FIXED)
            throw new IllegalArgumentException("A pump's design point is its flow, head and speed");
    }

    /** Flow and head step along the R10 numbers, to the largest discharge's flow and to 300 m. */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return switch (setting) {
            case "flow" -> MachineDuty.pump(MachineDuty.step(MachineDuty.series(MIN_FLOW, UnitRatings.pumpFlow(maxSize())), duty.flow(), steps), duty.head(), duty.poles()).withBaseSpeed(duty.baseSpeed());
            case "head" -> MachineDuty.pump(duty.flow(), MachineDuty.step(MachineDuty.series(MIN_HEAD, MAX_HEAD), duty.head(), steps), duty.poles()).withBaseSpeed(duty.baseSpeed());
            case "base_speed" -> duty.withBaseSpeed(Math.clamp(UnitDesign.designSpeed(EquipmentShape.Kind.PUMP,duty)+steps*100,300,com.wormzjl.icheme.science.machine.Motor.MAXIMUM_RPM));
            default -> duty;
        };
    }

    @Override
    public Line held(EquipmentShape shape) {
        var duty = shape.duty();
        return new Line("pump_flow", whole(duty.flow()), whole(duty.head()), whole(UnitRatings.pumpPower(duty)));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return shape.diameter() > ProcessUnit.OH2_MAX + 1e-9 ? ".split" : "";
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        var duty = shape.duty();
        return List.of(new Line("pump", size(d), size(ProcessUnit.pumpSuction(d))),
                new Line("pump_duty", whole(duty.flow()), whole(duty.head()), whole(duty.speed()), whole(UnitRatings.pumpPower(duty))));
    }

    /**
     * An API 610 OH2 pump (owner decision M2 2026-10-17; {@code MACHINES_HEATERS_SURVEY.md}) on its baseplate, its shaft
     * along z through a cell centre at height y. From the front: the pump's suction flange on a short cast bell; the
     * volute, its scroll growing from 0.52 to 0.9 impeller diameters round a hub, a rib along its crown, its throat turning
     * up into the cast discharge neck on the casing's centre line; the heavy cover on its joint ring, its studs on its face;
     * the bearing bracket with a window each side showing the seal chamber and gland; the bearing housing (oil sump in
     * front with its constant-level oiler, finned behind, its bolted end cover, a vent on top); the shaft end, its half
     * coupling under a half-round guard on a block face, where a shaft block joins the driver (batch
     * 2026-11-03-rotating-machinery, owner decision D4). The casing stands on its centreline feet on short pedestals (API
     * 610 allows no foot under the bearing housing). From 0.1 m a seal pot (API 682 plans 52/53) stands on its stand beside
     * the bracket, from 0.15 m a plan 11 flush line runs from the discharge neck to the gland. The baseplate's drain-rim
     * deck stands on a grout line on a chamfered concrete block, which rises to bring the baseplate up under the casing:
     * the shaft's height is the suction line's. Where a line runs {@link ProcessUnit#PAIR_RUN} or more from the pump to its block face,
     * the pump's flange and the line's meet at the casting ({@link ProcessUnit#pumpNozzle}). The fins, rib, studs, rims and channels
     * are baked into the pump's sprites ({@code tools/pump-sprites}, the cooling tower's way). Sizes follow the discharge d
     * (the rated flow at 3 m/s): suction {@link ProcessUnit#pumpSuction}, impeller from the rated head and speed ({@link #impeller}),
     * at least 0.18 + 1.2 d.
     */
    @Override
    void build(ProcessUnit u) {
        if (u.shape.diameter() > ProcessUnit.OH2_MAX + 1e-9) {
            betweenBearingsPump(u);
            return;
        }
        double d = u.shape.diameter(), s = ProcessUnit.pumpSuction(d), di = impeller(u, .18 + 1.2 * d);
        double rh = .42 * di, rv0 = .52 * di, rv1 = .9 * di, width = .4 * di + .06, joint = .7 * di, tj = .04 + .05 * di;
        double plate = .2 + .1 * di;
        double rb = .08 + .22 * di, lb = .3 + .6 * di, lk = .2 + .3 * di, shaft = .03 + .05 * di, rs = pumpShaft(u);
        // Keep the established port clearances; the shared nozzle mesh draws the flange at the connection face.
        double ts = 1.3 * PipeSizes.flangeThickness(s), td = 1.3 * PipeSizes.flangeThickness(d);
        double bell = .08 + .7 * s, pot = d >= .1 ? .07 + .1 * d : 0;
        double half = Math.max(rv1 + .14, pot > 0 ? .6 * di + 2 * pot + .11 : 0), base = half + .12;
        double a = ProcessUnit.centre(base);
        // Heights: the shaft on the lowest cell centre whose casing clears a baseplate on a 0.15 m block; the block rises
        // to bring the baseplate up to 0.05 + 0.1 di under the scroll's bottom, so the pedestals stay short.
        double y = Math.max(.5, Math.ceil(.15 + .04 + plate + .06 + rv1 - .5) + .5);
        double low = (rv0 + rv1) / 2, plinth = Math.max(.15, y - low - .05 - .1 * di - plate - .04), top = plinth + .04 + plate;
        // Along z: the line, the suction bell, the casing (its middle on a cell centre, the discharge's line), the cover, the
        // bracket, the bearing housing, the shaft end under its guard to the coupling face on a block face.
        double zc = Math.ceil(.25 + ts + bell + .3 * width - .5) + .5, z0 = zc - width / 2, z1 = zc + width / 2, zh = zc - .3 * width, zs = zh - bell;
        double zk = z1 + tj, zb0 = zk + lk, zb1 = zb0 + lb, coupling = Math.ceil(zb1 + .2 + rs - 1e-9), seal = zk + .45 * lk;
        var f = Frame.of(new double[]{a, y, 0}, 2, 1);
        int n = ProcessUnit.sides(2 * rv1), nb = ProcessUnit.sides(2 * rb);
        // The suction bell, the volute and its hub, the cover on its joint ring.
        u.mesh.frustum(f, .5 * s + .04, .43 * s + .04, zs, zh, n, Material.PUMP_CASTING);
        u.mesh.annulus(f, 0, .5 * s + .04, zs, n, -1, Material.PUMP_CASTING);
        u.scroll(a, y, z0, z1, zh, rh, rv0, rv1, Material.PUMP_CASTING, Material.PUMP_VOLUTE);
        u.mesh.prism(f, rh, zh, z1, n, Material.PUMP_CASTING);
        u.mesh.annulus(f, .43 * s + .04, rh, zh, n, -1, Material.PUMP_CASTING);
        u.mesh.annulus(f, 0, joint, z1, n, -1, Material.PUMP_CASTING);
        u.mesh.band(f, joint, joint, z1, zk, n, Material.PUMP_JOINT);
        u.mesh.disc(f, joint, zk, n, 1, Material.PUMP_DISC);
        // The bracket, windowed each side over the seal chamber, its gland and the shaft turning with its seal's collar.
        u.bracket(f, .5 * di, rb + .03, zk, zb0, n, Material.PUMP_CASTING);
        u.mesh.cylinder(f, .27 * di, zk, seal, n, Material.PUMP_CASTING);
        u.mesh.cylinder(f, .2 * di, seal, seal + .05, n, Material.FLANGE);
        u.shaft(f, shaft, new double[][]{{seal + .05, zb0}}, seal + .05 + ProcessUnit.collar(shaft) / 2);
        // The bearing housing: its oil sump, the round body finned behind it, the bolted end cover; the oiler on the sump,
        // the vent on top.
        double sump = zb0 + .45 * lb;
        u.box(new double[]{a - .9 * rb, y - 1.2 * rb, zb0}, new double[]{a + .9 * rb, y + .55 * rb, sump}, Material.PUMP_CASTING, false);
        u.mesh.prism(f, rb, zb0, sump, nb, Material.PUMP_CASTING);
        u.mesh.band(f, rb, rb, sump, zb1, nb, Material.PUMP_FINS);
        u.mesh.annulus(f, rb, rb + .03, zb0, nb, 1, Material.PUMP_CASTING);
        u.mesh.disc(f, rb, zb1, nb, 1, Material.PUMP_DISC);
        double bulb = .035 + .02 * di, oiler = y - .8 * rb, xo = a + .9 * rb + .06 + bulb;
        u.box(new double[]{a + .9 * rb, oiler - .015, zb0 + .2 * lb - .015}, new double[]{xo, oiler + .015, zb0 + .2 * lb + .015}, Material.PIPE, false);
        u.mesh.cylinder(Frame.of(new double[]{xo, 0, zb0 + .2 * lb}, 1, 1), bulb, oiler, oiler + .13 + .05 * di, 8, Material.OPENING);
        u.mesh.cylinder(Frame.of(new double[]{a, 0, sump + .6 * (zb1 - sump)}, 1, 1), .025 + .01 * di, y + rb - .01, y + rb + .05, 8, Material.PIPE);
        // The shaft end: the half coupling under its guard, the driver joined by a shaft block beyond the face.
        u.coupling(a, y, zb1, coupling, 1, rs, top, ProcessUnit.shown(u.shape.duty().speed()), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        // The casing's centreline feet near its cover on their short pedestals.
        for (int side = -1; side <= 1; side += 2) {
            double inner = a + side * rh, outer = a + side * (rv1 + .1), px = a + side * (rv1 + .02);
            u.box(new double[]{Math.min(inner, outer), y - .06, zc}, new double[]{Math.max(inner, outer), y + .02, zk}, Material.PUMP_CASTING, false);
            u.box(new double[]{px - .07, top, zc + .02}, new double[]{px + .07, y - .06, zk - .02}, Mesh.ALL_FACES & ~12, Material.STEELWORK, false);
        }
        // The cast discharge: a throat of about 1.2 DN square turning round into the neck, 0.12 + 0.2 d over the crown.
        double hx = .6 * d + .03, hz = Math.min(.6 * d + .03, .45 * width), neck = y + rv1 + .12 + .2 * d;
        u.box(new double[]{a - hx, y + rh, zc - hz}, new double[]{a + hx, y + .98 * rv0, zc + hz}, Mesh.ALL_FACES & ~12, Material.PUMP_CASTING, false);
        u.transition(1, a - hx, a + hx, zc - hz, zc + hz, y + .98 * rv0, a, zc, .5 * d + .025, neck, 4 * ((ProcessUnit.sides(d) + 3) / 4), Material.PUMP_CASTING, false);
        // The neck's top closed on the transition's own corners: the nozzle's neck, narrower, rises from it with no seam.
        var cap = new ArrayList<double[]>();
        for (int i = 0, m = 4 * ((ProcessUnit.sides(d) + 3) / 4); i < m; i++)
            cap.add(new double[]{a + (.5 * d + .025) * DeterministicMath.cos(2 * Math.PI * i / m), neck, zc + (.5 * d + .025) * DeterministicMath.sin(2 * Math.PI * i / m)});
        u.mesh.flat(cap, new double[]{0, 1, 0}, Material.PUMP_CASTING);
        // The baseplate on its grout line and chamfered block, both to the coupling's face.
        double zp0 = zs - .05, zp1 = coupling - .01, chamfer = .04;
        u.baseplate(a - half, a + half, plinth + .04, top, zp0, zp1);
        u.box(new double[]{a - half + .02, plinth, zp0 + .02}, new double[]{a + half - .02, plinth + .04, zp1 - .02}, Mesh.ALL_FACES & ~12, Material.CONCRETE, false);
        u.extrude(List.of(new double[]{a - base, 0}, new double[]{a + base, 0}, new double[]{a + base, plinth - chamfer}, new double[]{a + base - chamfer, plinth},
                new double[]{a - base + chamfer, plinth}, new double[]{a - base, plinth - chamfer}), zp0 - .12, zp1, Material.CONCRETE);
        u.solids.add(Solid.box(new double[]{a - base, 0, zp0 - .12}, new double[]{a + base, plinth + .04, zp1}));
        // The seal's pot on its stand beside the bracket and its two lines into the gland; the flush line from the neck.
        if (pot > 0) {
            double xp = a - (.6 * di + pot + .05), bottom = y - .05, height = .55 + 1.2 * d;
            var p = Frame.of(new double[]{xp, 0, seal}, 1, 1);
            u.mesh.prism(p, pot, bottom, bottom + height, 12, Material.PUMP_CASTING);
            u.mesh.head(p, pot, bottom + height, pot / 2, 12, Material.PUMP_CASTING);
            u.mesh.annulus(p, 0, pot, bottom, 12, -1, Material.PUMP_CASTING);
            u.box(new double[]{xp - .04, top, seal - .04}, new double[]{xp + .04, bottom, seal + .04}, Mesh.ALL_FACES & ~12, Material.STEELWORK, false);
            for (double yl : new double[]{y - .02, y + .1 * di + .04})
                u.mesh.cylinder(Frame.of(new double[]{0, yl, seal + .025}, 0, 1), .012, xp + pot - .01, a - .2 * di + .01, 6, Material.PIPE);
            u.solids.add(Solid.rod(new double[]{xp, 0, seal}, 1, bottom, bottom + height, pot, pot / 2));
        }
        if (d >= .15) {
            double x1 = a + rv1 + .06, tap = (y + rv0 + neck) / 2, lowLine = y + .1 * di;
            u.line(.015, Material.PIPE, new double[]{a + .5 * d + .02, tap, zc}, new double[]{x1, tap, zc}, new double[]{x1, lowLine, zc}, new double[]{x1, lowLine, seal + .025},
                    new double[]{a + .2 * di - .01, lowLine, seal + .025});
        }
        u.solids.add(Solid.rod(new double[]{a, y, 0}, 2, zs, zk, Math.max(rv1, joint), 0));
        u.solids.add(Solid.rod(new double[]{a, y, 0}, 2, zk, zb1, Math.max(rb + .03, .5 * di), 0));
        u.round(a, y, z0, z1, rv1);
        // The suction along -z and the discharge up, each from its casting to its connection face on a block face.
        u.pumpNozzle(Role.SUCTION, new double[]{a, y, 0}, 2, -1, zs, 0, s, .5 * s + .025);
        u.pumpNozzle(Role.DISCHARGE, new double[]{a, 0, zc}, 1, 1, neck, Math.ceil(neck + td + EquipmentShape.STAND_OUT), d, .5 * d + .01);
    }

    /**
     * This pump's impeller, m: from its design point's head and speed, {@code D = 60/(π n) √(2 g H)} (plan section 6), at
     * least {@code least}, the casing that takes its discharge's throat (the drawing's proportions before the design point).
     */
    private static double impeller(ProcessUnit u, double least) {
        var duty = u.shape.duty();
        return Math.max(least, 60 / (Math.PI * duty.speed()) * Math.sqrt(2 * 9.81 * duty.head()));
    }

    /** This pump's drawn shaft end radius: its rated power at its speed ({@link ProcessUnit#shaftRadius}). */
    private static double pumpShaft(ProcessUnit u) {
        return ProcessUnit.shaftRadius(UnitRatings.pumpPower(u.shape.duty()), u.shape.duty().speed());
    }

    /**
     * An API 610 BB2 pump (owner pick 2026-11-04, {@code PUMP_TYPES_REVIEW.md} option A; it replaces the axially split BB1,
     * a water pump, which API 610 does not allow at 200 °C and above or for light hydrocarbon): between bearings, single
     * stage, double suction, its casing split radially, the cover bolted on its joint ring at the drive end. Top suction
     * and top discharge: each a cast branch rising from the casing's side, one block either side of the shaft's line (the
     * suction on the right seen from the constructor), so one free block stands between their pipes. Centreline feet near
     * both ends of the casing on pedestals; seal chambers in windowed brackets and finned bearing housings at both ends;
     * the shaft end's half coupling under its guard behind, on one baseplate, grout line and plinth. The OH2's sprites
     * ({@link #build}): a rib round the volute, studs on the cover, fins on the housings, the drain-rim deck. Sizes follow
     * the discharge d: suction {@link ProcessUnit#pumpSuction}, impeller from the rated head and speed ({@link #impeller}), at least
     * 0.35 + 0.8 d.
     */
    private static void betweenBearingsPump(ProcessUnit u) {
        double d = u.shape.diameter(), s = ProcessUnit.pumpSuction(d), di = impeller(u, .35 + .8 * d);
        double rv = .9 * di, rc = .72 * di, wv = .5 * di, lc = 1.5 * di, joint = rc + .1, tj = .04 + .04 * di;
        double fs = 1.15 * PipeSizes.flange(s) / 2, ts = 1.3 * PipeSizes.flangeThickness(s), fd = 1.15 * PipeSizes.flange(d) / 2, td = 1.3 * PipeSizes.flangeThickness(d);
        double rb = .1 + .2 * di, lb = .25 + .35 * di, lk = .2 + .25 * di, shaft = .04 + .05 * di, rs = pumpShaft(u), plate = .2 + .1 * di;
        // Across x: the branches one block either side of the shaft's line; the box holds their flanges and the baseplate.
        double off = 1, deck = rv + .2;
        double a = ProcessUnit.centre(Math.max(off + Math.max(fs, fd) + .05, deck + .12));
        // Heights: a 0.3 m plinth, a grout line, the baseplate; the shaft on the lowest cell centre whose casing clears the
        // baseplate by 0.1 m.
        double plinth = .3, top = plinth + .04 + plate, y = Math.ceil(top + rv + .1 - .5) + .5;
        // Along z: the free end's housing and bracket, the casing (its middle, the branches' line, on a cell centre), the
        // cover's joint ring, the drive end's bracket and housing, the shaft end under its guard to the coupling face.
        double zc = Math.ceil(.15 + lb + lk + lc / 2 - .5) + .5, z0 = zc - lc / 2, z1 = zc + lc / 2, zk = z1 + tj;
        double zf1 = z0 - lk, zf0 = zf1 - lb, zb0 = zk + lk, zb1 = zb0 + lb, face = Math.ceil(zb1 + .2 + rs - 1e-9);
        var f = Frame.of(new double[]{a, y, 0}, 2, 1);
        int n = ProcessUnit.sides(2 * rv), nb = ProcessUnit.sides(2 * rb);
        // The casing: the suction chambers either side of the volute, the end wall, the cover on its joint ring.
        u.mesh.annulus(f, 0, rc, z0, n, -1, Material.PUMP_CASTING);
        u.mesh.prism(f, rc, z0, zc - wv / 2, n, Material.PUMP_CASTING);
        u.mesh.annulus(f, rc, rv, zc - wv / 2, n, -1, Material.PUMP_CASTING);
        u.mesh.band(f, rv, rv, zc - wv / 2, zc + wv / 2, n, Material.PUMP_VOLUTE);
        u.mesh.annulus(f, rc, rv, zc + wv / 2, n, 1, Material.PUMP_CASTING);
        u.mesh.prism(f, rc, zc + wv / 2, z1, n, Material.PUMP_CASTING);
        u.mesh.annulus(f, 0, joint, z1, n, -1, Material.PUMP_CASTING);
        u.mesh.band(f, joint, joint, z1, zk, n, Material.PUMP_JOINT);
        u.mesh.disc(f, joint, zk, n, 1, Material.PUMP_DISC);
        // The branches: a web from the casing's side, a round neck rising over the casing to the equipment neck.
        double[] neck = new double[2];
        for (int side = -1; side <= 1; side += 2) {
            int i = side < 0 ? 0 : 1;
            double bore = side < 0 ? s : d, rn = .5 * bore + .04, xb = a + side * off, low = y - .35 * rv, half = Math.min(rn, lc / 2);
            neck[i] = y + rv + .12 + .2 * bore;
            u.box(new double[]{Math.min(a, xb), low, zc - half}, new double[]{Math.max(a, xb), y + .3 * rv, zc + half}, Material.PUMP_CASTING, false);
            var up = Frame.of(new double[]{xb, 0, zc}, 1, 1);
            int nn = ProcessUnit.sides(2 * rn);
            u.mesh.cylinder(up, rn, low, neck[i], nn, Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{xb, 0, zc}, 1, low, neck[i], rn, 0));
            u.solids.add(Solid.box(new double[]{Math.min(a, xb), low, zc - half}, new double[]{Math.max(a, xb), y + .3 * rv, zc + half}));
            u.round(new double[]{xb, 0, zc}, 1, low, neck[i], rn);
        }
        // The centreline feet near both ends of the casing, each on its pedestal.
        for (int side = -1; side <= 1; side += 2)
            for (double z : new double[]{z0 + .05, z1 - .3}) {
                double inner = a + side * rc, outer = a + side * (rv + .12), px = a + side * (rv + .05);
                u.box(new double[]{Math.min(inner, outer), y - .06, z}, new double[]{Math.max(inner, outer), y + .02, z + .25}, Material.PUMP_CASTING, false);
                u.box(new double[]{px - .09, top, z + .02}, new double[]{px + .09, y - .06, z + .23}, Mesh.ALL_FACES & ~12, Material.STEELWORK, false);
            }
        // The brackets, seal chambers and finned bearing housings at both ends; the shaft through them.
        u.bracket(f, rb + .03, .45 * di, zf1, z0, n, Material.PUMP_CASTING);
        u.bracket(f, .45 * di, rb + .03, zk, zb0, n, Material.PUMP_CASTING);
        u.mesh.cylinder(f, .22 * di, z0 - .55 * lk, z0, n, Material.PUMP_CASTING);
        u.mesh.cylinder(f, .22 * di, zk, zk + .55 * lk, n, Material.PUMP_CASTING);
        u.shaft(f, shaft, new double[][]{{zf1, z0 - .55 * lk}, {zk + .55 * lk, zb0}}, z0 - .55 * lk - ProcessUnit.collar(shaft) / 2, zk + .55 * lk + ProcessUnit.collar(shaft) / 2);
        for (int e = 0; e < 2; e++) {
            double h0 = e == 0 ? zf0 : zb0, h1 = e == 0 ? zf1 : zb1;
            u.mesh.band(f, rb, rb, h0, h1, nb, Material.PUMP_FINS);
            // Close the housing behind its bracket; tuck the bore under the hexagonal shaft so the joint cannot leak light.
            u.mesh.annulus(f, shaft * DeterministicMath.cos(Math.PI / ShaftGeometry.SIDES), rb + .03, e == 0 ? h1 : h0, n, e == 0 ? 1 : -1, Material.PUMP_CASTING);
            u.mesh.disc(f, rb, e == 0 ? zf0 : zb1, nb, e == 0 ? -1 : 1, Material.PUMP_DISC);
            u.mesh.cylinder(Frame.of(new double[]{a, 0, (h0 + h1) / 2}, 1, 1), .025 + .01 * di, y + rb - .01, y + rb + .05, 8, Material.PIPE);
        }
        // The shaft end: the half coupling under its guard, the driver joined by a shaft block beyond the face.
        u.coupling(a, y, zb1, face, 1, rs, top, ProcessUnit.shown(u.shape.duty().speed()), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        // The baseplate on its grout line and chamfered plinth, both to the coupling's face.
        double zp0 = zf0 - .15, zp1 = face - .01, base = deck + .12, chamfer = .04;
        u.baseplate(a - deck, a + deck, plinth + .04, top, zp0, zp1);
        u.box(new double[]{a - deck + .02, plinth, zp0 + .02}, new double[]{a + deck - .02, plinth + .04, zp1 - .02}, Mesh.ALL_FACES & ~12, Material.CONCRETE, false);
        u.extrude(List.of(new double[]{a - base, 0}, new double[]{a + base, 0}, new double[]{a + base, plinth - chamfer}, new double[]{a + base - chamfer, plinth},
                new double[]{a - base + chamfer, plinth}, new double[]{a - base, plinth - chamfer}), zp0 - .12, zp1, Material.CONCRETE);
        u.solids.add(Solid.box(new double[]{a - base, 0, zp0 - .12}, new double[]{a + base, plinth + .04, zp1}));
        u.solids.add(Solid.rod(new double[]{a, y, 0}, 2, z0, zk, Math.max(rv, joint), 0));
        u.solids.add(Solid.rod(new double[]{a, y, 0}, 2, zf0, zb1, Math.max(rb + .03, .45 * di), 0));
        u.volumes.add(Solid.rod(new double[]{a, y, 0}, 2, z0 + .05, z1 - .05, rc - .05, 0));
        u.round(a, y, zc - wv / 2, zc + wv / 2, rv);
        // One equipment neck on each branch, up to its connection face on a block face.
        for (int side = -1; side <= 1; side += 2) {
            int i = side < 0 ? 0 : 1;
            double bore = side < 0 ? s : d, top0 = Math.ceil(neck[i] + (side < 0 ? ts : td) + EquipmentShape.STAND_OUT);
            u.pumpNozzle(side < 0 ? Role.SUCTION : Role.DISCHARGE, new double[]{a + side * off, 0, zc}, 1, 1, neck[i], top0, bore, .5 * bore + .025);
        }
    }
}
