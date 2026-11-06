package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;

import java.util.List;

/**
 * A multistage barrel centrifugal compressor (API 617), following the Siemens barrel-casing photo and the owner's
 * top-connection choice of 2026-11-06: suction and discharge grow directly from the casing, whose bolted end covers allow
 * axial access to the internals. Its impeller diameter D, 0.3 to 1.5 m, and two to nine impellers retain their existing
 * duty model. The casing is 1.9 D across and at least 3 m long so its two top pipe blocks have a full free block
 * between them, including on the smallest casing. Distinct bearing housings stand on broad tapered pedestals.
 * The shared pump baseplate and guarded half coupling match the other rotating machines; the driver is separate.
 * The plinth takes up the shaft's block-grid alignment allowance; a selected support raises the base as for a motor.
 * No cooling-water fittings, side box, access platform, stairs or driver bay is drawn.
 */
final class CentrifugalCompressorDesign extends UnitDesign {
    /** The casing's diameter and its unscaled plenum, stage and discharge lengths, impeller diameters. */
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

    @Override
    void build(ProcessUnit u) {
        double d = u.shape.diameter(), ax = .5;
        int stages = (int) u.shape.tangentLength(), h = u.shape.support();
        double r = CASING * d / 2, cap = r + .1 * d + .03, capLength = .06 + .04 * d;
        // The port rows are at least two cells apart; the casing supplies space for both roots and their flanges.
        double length = Math.max(3, (PLENUM + STAGE * stages + VOLUTE) * d);
        double inRow = .5, outRow = Math.floor(length - 1) + .5;
        double minimumDeck = (h == 0 ? PLINTH : h) + GROUT + BASEPLATE;
        double ys = Math.ceil(minimumDeck + cap + .08 - .5) + .5;
        double base = h == 0 ? Math.min(ys - cap - .12, Math.floor(ys) - .04) - GROUT - BASEPLATE : h;
        double deck = base + GROUT + BASEPLATE;
        var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
        int n = ProcessUnit.sides(2 * cap);
        // A barrel with bolted end closures, rather than a horizontal split through the top connections.
        u.mesh.prism(along, r, 0, length, n, Material.PUMP_CASTING);
        for (double[] end : new double[][]{{-capLength, 0, -1}, {length, length + capLength, 1}}) {
            u.mesh.band(along, cap, cap, end[0], end[1], n, Material.PUMP_JOINT);
            u.mesh.disc(along, cap, end[2] < 0 ? end[0] : end[1], n, (int) end[2], Material.PUMP_DISC);
            u.mesh.annulus(along, r, cap, end[2] < 0 ? end[1] : end[0], n, -(int) end[2], Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, end[0], end[1], cap, 0));
        }
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, 0, length, r, 0));
        u.volumes.add(Solid.rod(new double[]{ax, ys, 0}, 2, .05, length - .05, r - .05, 0));
        u.round(ax, ys, 0, length, r);
        // Two lifting lugs, as on the reference barrel casing.
        for (double z : new double[]{.22 * length, .78 * length})
            u.box(new double[]{ax - .025, ys + .94 * r, z - .1}, new double[]{ax + .025, ys + r + .08 + .05 * d, z + .1}, Material.PUMP_CASTING, false);

        // Capped journal housings and broad tapered bearing pedestals at the two ends.
        double housing = .35 * d + .06, bearing = .18 + .3 * d;
        double zb0 = -capLength - bearing, zb1 = length + capLength + bearing, cover = zb0 - .08;
        int nb = ProcessUnit.sides(2 * housing);
        for (double[] span : new double[][]{{zb0, -capLength}, {length + capLength, zb1}}) {
            u.mesh.prism(along, housing, span[0], span[1], nb, Material.PUMP_CASTING);
            u.box(new double[]{ax - housing - .02, ys - .035, span[0]}, new double[]{ax + housing + .02, ys + .035, span[1]}, Material.PUMP_CASTING, false);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, span[0], span[1], housing, 0));
            double wide = Math.max(.22, .95 * housing), narrow = .65 * housing, top = ys - .6 * housing;
            double p0 = span[0] + .03, p1 = span[1] - .03;
            u.extrude(List.of(new double[]{ax - wide, deck + .04}, new double[]{ax + wide, deck + .04},
                    new double[]{ax + narrow, top}, new double[]{ax - narrow, top}), p0, p1, Material.PUMP_CASTING);
            u.solids.add(Solid.box(new double[]{ax - wide, deck, p0}, new double[]{ax + wide, top, p1}));
            u.box(new double[]{ax - wide - .06, deck, p0 - .03}, new double[]{ax + wide + .06, deck + .04, p1 + .03}, Material.STEELWORK, false);
        }
        u.mesh.band(along, housing, housing, cover, zb0, nb, Material.PUMP_JOINT);
        u.mesh.disc(along, housing, cover, nb, -1, Material.PUMP_DISC);
        u.mesh.annulus(along, .02 + .04 * d, housing, zb1, nb, 1, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, cover, zb0, housing, 0));

        // Straight cast branches grow directly out of the top: larger suction, smaller discharge.
        for (int which = 0; which < 2; which++) {
            double od = which == 0 ? suction(d) : DISCHARGE * d, z = which == 0 ? inRow : outRow;
            // Start the line inside the cast branch so even the smallest visible neck has room for its flow arrow.
            double rn = od / 2 + .03, low = ys + .55 * r, neck = ys + r - .03;
            var up = Frame.of(new double[]{ax, 0, z}, 1, 1);
            u.mesh.cylinder(up, rn, low, neck, ProcessUnit.sides(2 * rn), Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, 0, z}, 1, low, neck, rn, 0));
            u.pumpNozzle(which == 0 ? Role.SUCTION : Role.DISCHARGE, new double[]{ax, 0, z}, 1, 1,
                    neck, Math.ceil(ys + r + .02 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, od / 2);
        }
        // One compact frame and the shared rotating coupling; no side cabinet or operating platform.
        double rs = ProcessUnit.shaftRadius(1000 * power(u.shape.duty()), speed(d)), face = Math.ceil(zb1 + .2 + rs - 1e-9);
        double half = Math.max(cap + .2, Math.ceil(cap + .15) + .12);
        double x0 = ax - half, x1 = ax + half, z0 = cover - .15, z1 = face - .01;
        u.coupling(ax, ys, zb1, face, 1, rs, deck, ProcessUnit.shown(speed(d)), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        u.baseplate(x0, x1, base + GROUT, deck, z0, z1);
        u.box(new double[]{x0 + .02, base, z0 + .02}, new double[]{x1 - .02, base + GROUT, z1 - .02}, Material.CONCRETE, false);
        u.driverBase(x0 - .08, x1 + .08, z0 - .08, z1, base, h);

        // Keep the placement frame of the former coolant fittings so removing them moves neither gas port
        // nor the coupling in an existing structure, at any heading. These points occupy no cells.
        u.framePoints.add(new double[]{ax, 0, Math.floor(z0 - ProcessUnit.NECK)});
        u.framePoints.add(new double[]{ax, 0, Math.ceil(z1 + ProcessUnit.NECK)});
    }
}
