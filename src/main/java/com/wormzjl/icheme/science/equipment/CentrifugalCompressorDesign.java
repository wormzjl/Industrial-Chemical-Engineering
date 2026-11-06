package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.ALL_FACES;

/**
 * A horizontally split multistage centrifugal compressor train (API 617; owner request 2026-10-18, {@code NEW_UNITS_PLAN.md};
 * {@code NEW_UNITS_SURVEY_ROTATING.md} section 1), sized by its impeller diameter D, 0.3 to 1.5 m, and its impellers, 2
 * to 9 (beyond nine a second casing). Its casing [survey; Elliott, MAN and Siemens outline drawings]: 1.9 D across, 1.2 D
 * plus 0.35 D an impeller long (the inlet plenum and the discharge volute 0.6 D each, banded a little proud), split at the
 * shaft by the heavy joint flange (0.12 D proud, 0.15 D thick, studs about 0.25 m apart: the signature) and standing on
 * feet at that joint; bearing housings 0.7 D across and 0.5 D long at both ends, the thrust bearing's cover in front; the
 * suction and discharge branches (0.9 D and 0.45 D, capped at the pipe limit) rise beside the inlet and discharge ends.
 * The shared pump baseplate and guarded half coupling match the other rotating machines; the driver is built separately.
 * At grade the baseplate stands on a low plinth; a selected support raises it as for a motor or turbine. A small oil
 * reservoir and cooler stand on a separate base beside the casing, with cooling water at the cooler's two ends.
 * There are no access platforms, stairs, overhead tank or empty driver bay. The coupling's shaft collar turns through
 * the shared machinery animation. The impeller dimensions, design duty and operating model are unchanged.
 */
final class CentrifugalCompressorDesign extends UnitDesign {
    /** The casing's diameter, its inlet plenum, an impeller's stage and the discharge volute, impeller diameters. */
    static final double CASING = 1.9, PLENUM = .6, STAGE = .35, VOLUTE = .6;
    /** The anchor machine (Elliott 32M4): its impeller diameter, m, its impellers, its flow, m³/h, and its shaft power, MW. */
    static final double ANCHOR = .65, ANCHOR_FLOW = 33000, ANCHOR_POWER = 2.5;
    static final int ANCHOR_STAGES = 4;
    /** The impellers' tip speed, m/s (plan section 6: about 300 m/s; the 32M4 runs 0.65 m at 7,176 rpm, 245 m/s). */
    static final double TIP_SPEED = 300;
    /** The suction and discharge nozzles over the impeller diameter (survey: 0.9 and 0.45). */
    static final double SUCTION = .9, DISCHARGE = .45;
    /** The compact plinth, grout and fabricated baseplate, m. Raised supports retain the old height range. */
    static final double PLINTH = .25, GROUT = .04, BASEPLATE = .16;
    static final int MAX_SUPPORT = 7;

    @Override
    public double minSize() {
        return .3;
    }

    @Override
    public double maxSize() {
        return 1.5;
    }

    @Override
    public double sizeStep() {
        return .1;
    }

    @Override
    public int minLength(double size) {
        return 2;
    }

    @Override
    public int maxLength(double size) {
        return 9;
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public int maxSupport() {
        return MAX_SUPPORT;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public boolean moves() {
        return true;
    }

    @Override
    public String sizeRow() {
        return "centrifugal_compressor.impeller";
    }

    @Override
    public String lengthRow() {
        return "centrifugal_compressor.impellers";
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    @Override
    public String supportRow() {
        return "centrifugal_compressor.table";
    }

    @Override
    public Line held(EquipmentShape shape) {
        var duty = shape.duty();
        return new Line("centrifugal_compressor.flow", whole(duty.flow()), whole(duty.polytropicHead()), number(power(duty)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        int stages = (int) shape.tangentLength();
        var duty = shape.duty();
        return List.of(new Line("centrifugal_compressor", stages, size(d), size(CASING * d)),
                new Line("centrifugal_compressor.duty", whole(duty.flow()), whole(duty.polytropicHead()), number(airRatio(duty.polytropicHead()))),
                new Line("centrifugal_compressor.power", number(power(duty)), whole(speed(d))));
    }

    /** An impeller's polytropic head at the design point, kJ/kg (plan section 6: 30 to 40 kJ/kg each). */
    static final double IMPELLER_HEAD = 35;
    /** The least rated inlet flow, m³/h, and the polytropic heads, kJ/kg: two to nine impellers. */
    static final double MIN_FLOW = 1000, MIN_HEAD = 1.5 * IMPELLER_HEAD, MAX_HEAD = 9.49 * IMPELLER_HEAD;

    @Override
    public boolean hasDuty() {
        return true;
    }

    /** A train saved before its design point: its impellers' flow and head (each impeller {@link #IMPELLER_HEAD}). */
    @Override
    public MachineDuty duty(double size, int length) {
        return MachineDuty.compressor(flow(size), IMPELLER_HEAD * length);
    }

    /** The impeller: the anchor's, scaled with the root of the rated inlet flow, up onto the 0.1 m grid. */
    @Override
    public double size(MachineDuty duty) {
        return upTo(ANCHOR * Math.sqrt(duty.flow() / ANCHOR_FLOW), sizeStep(), minSize(), maxSize());
    }

    /** The impellers: the polytropic head at {@link #IMPELLER_HEAD} each, two to nine. */
    @Override
    public int length(MachineDuty duty) {
        return (int) Math.max(minLength(0), Math.min(maxLength(0), Math.round(duty.polytropicHead() / IMPELLER_HEAD)));
    }

    @Override
    void checkDuty(MachineDuty duty) {
        if (!(duty.flow() >= MIN_FLOW && duty.flow() <= flow(maxSize()) + 1e-6))
            throw new IllegalArgumentException("A centrifugal compressor's rated inlet flow is " + whole(MIN_FLOW) + " to " + whole(flow(maxSize())) + " m³/h, not " + duty.flow());
        if (!(duty.polytropicHead() >= MIN_HEAD && duty.polytropicHead() <= MAX_HEAD))
            throw new IllegalArgumentException("A centrifugal compressor's polytropic head is " + number(MIN_HEAD) + " to " + number(MAX_HEAD) + " kJ/kg, not " + duty.polytropicHead());
        if (duty.head() != 0 || duty.power() != 0 || duty.poles() != 0 || duty.steam() != null || duty.exhaust() != 0 || duty.drive() != MachineDuty.Drive.FIXED)
            throw new IllegalArgumentException("A centrifugal compressor's design point is its flow and polytropic head");
    }

    /** Inlet flow steps along the R10 numbers to the largest impeller's flow; polytropic head an impeller at a time. */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return switch (setting) {
            case "flow" -> MachineDuty.compressor(MachineDuty.step(MachineDuty.series(MIN_FLOW, flow(maxSize())), duty.flow(), steps), duty.polytropicHead());
            case "head" -> {
                var heads = new java.util.ArrayList<Double>();
                for (int n = minLength(0); n <= maxLength(0); n++) heads.add(IMPELLER_HEAD * n);
                yield MachineDuty.compressor(duty.flow(), MachineDuty.step(heads, duty.polytropicHead(), steps));
            }
            default -> duty;
        };
    }

    /**
     * The pressure ratio air at 30 °C reaches over polytropic head {@code head}, kJ/kg, at a polytropic efficiency of 0.8
     * (a reference for the constructor: the gas sets the real one).
     */
    static double airRatio(double head) {
        double exponent = .4 / (1.4 * .8), rt = 8.314 * 303.15 / 28.96;
        return com.wormzjl.icheme.science.math.DeterministicMath.pow(1 + head * exponent / rt, 1 / exponent);
    }

    /** The inlet flow, m³/h: the anchor's, scaled with the impeller's area. */
    static double flow(double d) {
        return ANCHOR_FLOW * (d / ANCHOR) * (d / ANCHOR);
    }

    /** The shaft power at design point {@code duty}, MW: the anchor's, scaled with the rated flow and polytropic head. */
    static double power(MachineDuty duty) {
        return ANCHOR_POWER * duty.flow() / ANCHOR_FLOW * duty.polytropicHead() / (IMPELLER_HEAD * ANCHOR_STAGES);
    }

    /** The shaft power, MW: the anchor's, scaled with the flow and the impellers (the head per impeller fixed by the tip speed). */
    static double power(double d, int stages) {
        return ANCHOR_POWER * (d / ANCHOR) * (d / ANCHOR) * stages / ANCHOR_STAGES;
    }

    /** The shaft speed, rpm, at {@link #TIP_SPEED}. */
    static double speed(double d) {
        return 60 * TIP_SPEED / (Math.PI * d);
    }

    /** The suction nozzle, m. */
    static double suction(double d) {
        return Math.min(PipeSizes.MAX_OUTSIDE, SUCTION * d);
    }

    /** Draws the train where it lies, its shaft and suction on cell centres: the box starts at the parts' and nozzle faces' least whole metre. */
    @Override
    void build(ProcessUnit unit) {
        draw(unit, .5, .5);
    }

    /** The train with its shaft over x = ax and its suction nozzle's centre at z = zs, both cell centres: see the class. */
    private static void draw(ProcessUnit u, double ax, double zs) {
        double d = u.shape.diameter(), k = (d - .3) / 1.2;
        int stages = (int) u.shape.tangentLength(), h = u.shape.support();
        double r = CASING * d / 2, rb = 1.04 * r, xj = rb + .12 * d, length = (PLENUM + STAGE * stages + VOLUTE) * d;
        double base = h == 0 ? PLINTH : h, deck = base + GROUT + BASEPLATE;
        // As on the pumps, put the shaft on the lowest cell centre that clears the baseplate.
        double ys = Math.ceil(deck + rb + .08 - .5) + .5;
        double zc0 = zs - PLENUM * d / 2, zc1 = zc0 + length, zp = zc0 + PLENUM * d, zw = zc1 - VOLUTE * d, zv = zc1 - VOLUTE * d / 2;
        var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
        var backwards = Frame.of(new double[]{ax, ys, 0}, 2, -1);
        int n = ProcessUnit.sides(CASING * d);
        // The casing: the banded plenum and volute either side of the barrel, its end walls; the joint flange along both
        // sides at the shaft, its nuts on top, its split line across the end walls.
        u.mesh.prism(along, rb, zc0, zp, n, Material.PUMP_CASTING);
        u.mesh.prism(along, r, zp, zw, n, Material.PUMP_CASTING);
        u.mesh.prism(along, rb, zw, zc1, n, Material.PUMP_CASTING);
        u.mesh.annulus(along, r, rb, zp, n, 1, Material.PUMP_CASTING);
        u.mesh.annulus(along, r, rb, zw, n, -1, Material.PUMP_CASTING);
        double housing = .35 * d;
        u.mesh.annulus(along, housing, rb, zc0, n, -1, Material.PUMP_CASTING);
        u.mesh.annulus(along, housing, rb, zc1, n, 1, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, zc0, zc1, rb, 0));
        u.volumes.add(Solid.rod(new double[]{ax, ys, 0}, 2, zc0 + .05, zc1 - .05, r - .05, 0));
        u.round(ax, ys, zc0, zc1, rb);
        double half = .075 * d;
        for (int side = -1; side <= 1; side += 2) {
            double inner = ax + side * .97 * r, outer = ax + side * xj;
            u.mesh.box(new double[]{Math.min(inner, outer), ys - half, zc0 - .02}, new double[]{Math.max(inner, outer), ys + half, zc1 + .02}, ALL_FACES & ~(side > 0 ? 1 : 2),
                    Material.PUMP_CASTING);
            u.solids.add(ProcessUnit.span(new double[]{inner, ys - half, zc0 - .02}, new double[]{outer, ys + half, zc1 + .02}));
            double end0 = ax + side * (housing + .01), end1 = ax + side * rb;
            u.mesh.box(new double[]{Math.min(end0, end1), ys - .6 * half, zc0 - .03}, new double[]{Math.max(end0, end1), ys + .6 * half, zc0}, ALL_FACES & ~32, Material.PUMP_CASTING);
            u.mesh.box(new double[]{Math.min(end0, end1), ys - .6 * half, zc1}, new double[]{Math.max(end0, end1), ys + .6 * half, zc1 + .03}, ALL_FACES & ~16, Material.PUMP_CASTING);
            // The joint's nuts about 0.25 m apart on top of the flange.
            int nuts = Math.max(4, (int) Math.round((length - .1) / .25) + 1);
            double nut = .03 + .03 * d, xn = ax + side * (rb + .06 * d);
            for (int j = 0; j < nuts; j++) {
                double z = zc0 + .05 + (length - .1) * j / (nuts - 1);
                var f = Frame.of(new double[]{xn, 0, z}, 1, 1);
                u.mesh.prism(f, nut, ys + half, ys + half + .8 * nut, 6, Material.BOLT);
                u.mesh.annulus(f, 0, nut, ys + half + .8 * nut, 6, 1, Material.BOLT);
            }
            // The feet at the joint over the plenum and the volute, on pedestals and soleplates.
            for (double zf : new double[]{zc0 + .15 * d, zc1 - .15 * d}) {
                double f0 = ax + side * (rb - .05 * d), f1 = ax + side * (xj + .1 * d), p0 = ax + side * (xj - .05 * d), foot = ys - half - .12 * d;
                u.box(new double[]{Math.min(f0, f1), foot, zf - .1 * d}, new double[]{Math.max(f0, f1), ys - half, zf + .1 * d}, Material.SUPPORT, true);
                u.box(new double[]{Math.min(p0, f1), deck + .05, zf - .1 * d}, new double[]{Math.max(p0, f1), foot, zf + .1 * d}, Material.SUPPORT, true);
                u.box(new double[]{Math.min(p0, f1) - .05, deck, zf - .15 * d - .03}, new double[]{Math.max(p0, f1) + .05, deck + .05, zf + .15 * d + .03}, Material.STEELWORK, false);
            }
        }
        // Lifting lugs on top of the barrel.
        for (double zl : new double[]{zp + .1 * d, zw - .1 * d - .2 * d})
            u.box(new double[]{ax - .02 - .01 * d, ys + .95 * r, zl}, new double[]{ax + .02 + .01 * d, ys + r + .12 * d + .04, zl + .2 * d}, Material.PUMP_CASTING, false);
        // The bearing housings, split at the shaft; the thrust bearing's cover in front.
        double zb0 = zc0 - .5 * d, zb1 = zc1 + .5 * d, cover = zb0 - .12 * d;
        for (double[] span : new double[][]{{zb0, zc0}, {zc1, zb1}}) {
            u.mesh.prism(along, housing, span[0], span[1], ProcessUnit.sides(2 * housing), Material.PUMP_CASTING);
            u.box(new double[]{ax - housing - .04 * d, ys - .04 * d, span[0]}, new double[]{ax + housing + .04 * d, ys + .04 * d, span[1]}, Material.PUMP_CASTING, false);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, span[0], span[1], housing, 0));
        }
        u.mesh.cylinder(along, .25 * d, cover, zb0, ProcessUnit.sides(.5 * d), Material.PUMP_CASTING);
        u.mesh.annulus(backwards, .25 * d, housing, -zb0, ProcessUnit.sides(2 * housing), 1, Material.PUMP_CASTING);
        u.mesh.annulus(along, .02 + .04 * d, housing, zb1, ProcessUnit.sides(2 * housing), 1, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, cover, zb0, .25 * d, 0));
        // Upward gas branches, one cell row either side of the shaft. Short casings may share their z row;
        // the transverse spacing still leaves a full free block between the two pipe blocks.
        double off = Math.ceil(rb + .15), odS = suction(d), odD = DISCHARGE * d;
        for (int side = -1; side <= 1; side += 2) {
            double od = side < 0 ? odS : odD, z = side < 0 ? zs : Math.floor(zv) + .5;
            double xb = ax + side * off, rn = od / 2 + .03, low = ys - .3 * r;
            double neck = ys + rb + .12 + .15 * od, halfZ = Math.min(rn, length / 3);
            u.box(new double[]{Math.min(ax, xb), low, z - halfZ}, new double[]{Math.max(ax, xb), ys + .3 * r, z + halfZ}, Material.PUMP_CASTING, false);
            var up = Frame.of(new double[]{xb, 0, z}, 1, 1);
            u.mesh.cylinder(up, rn, low, neck, ProcessUnit.sides(2 * rn), Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{xb, 0, z}, 1, low, neck, rn, 0));
            u.round(new double[]{xb, 0, z}, 1, low, neck, rn);
            u.pumpNozzle(side < 0 ? Role.SUCTION : Role.DISCHARGE, new double[]{xb, 0, z}, 1, 1,
                    neck, Math.ceil(neck + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, rn);
        }
        // The same guarded coupling and fabricated baseplate as the other rotating machines. No driver bay.
        double rs = ProcessUnit.shaftRadius(1000 * power(u.shape.duty()), speed(d)), face = Math.ceil(zb1 + .2 + rs - 1e-9);
        double x0 = ax - xj - .25, x1 = ax + xj + .25, z0 = cover - .15, z1 = face - .01;
        u.coupling(ax, ys, zb1, face, 1, rs, deck, ProcessUnit.shown(speed(d)), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        u.baseplate(x0, x1, base + GROUT, deck, z0, z1);
        u.box(new double[]{x0 + .02, base, z0 + .02}, new double[]{x1 - .02, base + GROUT, z1 - .02}, Material.CONCRETE, false);
        u.driverBase(x0 - .1, x1 + .1, z0 - .1, z1, PLINTH, h);

        // Keep the two cooling-water connections on a compact oil module rather than an entire plant console.
        // Its pipe row is two cells beyond the discharge branch, clear even with the largest connected pipe.
        double xc = ax + off + 2, odC = .25 + .25 * k, rc = odC / 2, odW = .06 + .08 * k;
        double yc = Math.ceil(deck + .4 - .5) + .5, c0 = zc0, c1 = Math.max(c0 + 1.2, zc1);
        double moduleHalf = Math.max(.4, rc + .15), m0 = c0 - .12, m1 = c1 + .12;
        u.baseplate(xc - moduleHalf, xc + moduleHalf, base + GROUT, deck, m0, m1);
        u.driverBase(xc - moduleHalf - .1, xc + moduleHalf + .1, m0 - .1, m1 + .1, PLINTH, h);
        u.box(new double[]{xc - .32, deck, c0 + .08}, new double[]{xc + .32, yc - rc - .1, c1 - .08}, Material.PUMP_CASTING, true);
        u.bottle(xc, yc, c0, c1, odC, ProcessUnit.sides(odC));
        double face0 = Math.floor(c0 - odC / 4 - ProcessUnit.NECK), face1 = Math.ceil(c1 + odC / 4 + ProcessUnit.NECK);
        u.nozzles.add(new Nozzle(Role.COOLANT_INLET, new double[]{xc, yc, c0 - .02}, 2, -1, c0 - .02 - face0, odW, 0));
        u.nozzles.add(new Nozzle(Role.COOLANT_OUTLET, new double[]{xc, yc, c1 + .02}, 2, 1, face1 - c1 - .02, odW, 0));
    }
}
