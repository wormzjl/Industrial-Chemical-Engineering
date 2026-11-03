package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

import java.util.List;

/**
 * An industrial parallel-shaft gearbox (batch 2026-11-03-rotating-machinery, W4; plan section 6): its design point is
 * its rated power, from the IEC list (0.75 kW to 20 MW, the main size). Two shaft ends: the input at its back, the output
 * at its front, a whole cell apart across (two from 5 MW), both on one cell centre's height; its ratio is set in the
 * game (W3), about 98 % efficient. A split housing with chamfered top edges, as tall and long as its power asks
 * ({@link #housing}), its split flange on the shafts' line, an inspection cover and a breather on top, feet along both
 * sides, on a baseplate on a chamfered plinth at grade or a concrete table {@code support} metres up. The drawing takes a
 * nominal 1,500 rpm at its slow shaft and 4 times that at its fast one for the shafts' sizes. With Create installed, a
 * Create coupling on its slow end brings Create's rotation in (W6b).
 */
final class GearboxDesign extends UnitDesign {
    /** The drawing's nominal slow-shaft speed, rpm, and ratio. */
    static final double SLOW = 1500, RATIO = 4;
    /** The plinth at grade and the baseplate, m. */
    static final double PLINTH = .2, BASEPLATE = .08;

    @Override
    public double minSize() {
        return MachineDuty.MOTOR_POWERS.getFirst();
    }

    @Override
    public double maxSize() {
        return MachineDuty.MOTOR_POWERS.getLast();
    }

    /** A notional step; the sizes are the IEC list ({@link #sizes()}). */
    @Override
    public double sizeStep() {
        return .05;
    }

    @Override
    public List<Double> sizes() {
        return MachineDuty.MOTOR_POWERS;
    }

    @Override
    public double snap(double size) {
        return Double.isFinite(size) ? MachineDuty.nearest(MachineDuty.MOTOR_POWERS, size) : minSize();
    }

    @Override
    public double step(double size, int steps) {
        return MachineDuty.step(MachineDuty.MOTOR_POWERS, size, steps);
    }

    @Override
    public String sizeUnit() {
        return "kW";
    }

    @Override
    public String sizeText(double size) {
        return size < 10 ? UnitDesign.size(size) : whole(size);
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public int maxSupport() {
        return 8;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    /** The couplings' hubs turn. */
    @Override
    public boolean moves() {
        return true;
    }

    @Override
    public boolean hasDuty() {
        return true;
    }

    @Override
    public MachineDuty duty(double size, int length) {
        return MachineDuty.gearbox(size);
    }

    @Override
    public double size(MachineDuty duty) {
        return duty.power();
    }

    @Override
    void checkDuty(MachineDuty duty) {
        if (!MachineDuty.MOTOR_POWERS.contains(duty.power()))
            throw new IllegalArgumentException("A gearbox's rated power is one of the IEC list, 0.75 to 20,000 kW, not " + duty.power());
        if (!duty.equals(MachineDuty.gearbox(duty.power()))) throw new IllegalArgumentException("A gearbox's design point is its power");
    }

    /** Power steps along the IEC list. */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return setting.equals("power") ? MachineDuty.gearbox(MachineDuty.step(MachineDuty.MOTOR_POWERS, duty.power(), steps)) : duty;
    }

    @Override
    public String sizeRow() {
        return "gearbox.power";
    }

    @Override
    public String supportRow() {
        return "machine_table";
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("gearbox.rating", sizeText(shape.duty().power()), whole(offset(shape.duty().power())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        return List.of(held(shape));
    }

    /** The shafts' offset across, whole metres: one cell, two from 5 MW. */
    static int offset(double kw) {
        return kw >= 5000 ? 2 : 1;
    }

    /** The housing's half height over the shafts' line and its length, m: growing with the fourth root of the power. */
    static double[] housing(double kw) {
        double q = Math.sqrt(Math.sqrt(kw));
        return new double[]{.22 + .05 * q, .45 + .09 * q};
    }

    @Override
    void build(ProcessUnit u) {
        double kw = u.shape.duty().power(), rsSlow = ProcessUnit.shaftRadius(kw, SLOW), rsFast = ProcessUnit.shaftRadius(kw, SLOW * RATIO);
        int h = u.shape.support(), apart = offset(kw);
        double[] size = housing(kw);
        double hh = size[0], length = size[1], floor = h == 0 ? PLINTH : h, deck = floor + BASEPLATE;
        // The shafts on the lowest cell centre whose housing stands on its feet on the baseplate: the input (slow) at x a,
        // the output (fast) a whole cell or two to +x.
        double y = Math.ceil(deck + .7 * hh + .08 - .5) + .5, a = ProcessUnit.centre(hh + .4), b = a + apart;
        double x0 = a - hh - .05, x1 = b + .75 * hh + .05, bottom = deck + .05, top = y + hh, chamfer = .3 * hh;
        // Along z: the output's coupling face at z 1 or 2 (the front), the housing behind it, the input's face behind that.
        double front = Math.ceil(.25 + rsFast + .1 - 1e-9), z0 = front + .15 + rsFast, z1 = z0 + length, back = Math.ceil(z1 + .2 + rsSlow - 1e-9);
        u.extrude(List.of(new double[]{x0, bottom}, new double[]{x1, bottom}, new double[]{x1, top - chamfer}, new double[]{x1 - chamfer, top},
                new double[]{x0 + chamfer, top}, new double[]{x0, top - chamfer}), z0, z1, Material.MACHINE);
        u.solids.add(ProcessUnit.Solid.box(new double[]{x0, bottom, z0}, new double[]{x1, top, z1}));
        u.volumes.add(ProcessUnit.Solid.box(new double[]{x0 + .05, bottom + .05, z0 + .05}, new double[]{x1 - .05, top - .05, z1 - .05}));
        // The split flange on the shafts' line, the inspection cover and the breather on top, the feet along both sides.
        double sp = .02 + .01 * hh;
        u.box(new double[]{x0 - .03, y - sp, z0 - .02}, new double[]{x1 + .03, y + sp, z1 + .02}, Material.MACHINE, false);
        double xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        u.box(new double[]{xm - .25 * (x1 - x0), top, zm - .25 * length}, new double[]{xm + .25 * (x1 - x0), top + .03, zm + .25 * length}, Material.MACHINE, false);
        u.mesh.cylinder(Mesh.Frame.of(new double[]{xm + .3 * (x1 - x0), 0, zm}, 1, 1), .025 + .01 * hh, top, top + .08, 8, Material.PIPE);
        for (int side = -1; side <= 1; side += 2) {
            double xf0 = side < 0 ? x0 : x1, xf1 = xf0 + side * (.05 + .05 * hh);
            u.box(new double[]{Math.min(xf0, xf1), deck, z0 + .03}, new double[]{Math.max(xf0, xf1), deck + .08 + .05 * hh, z1 - .03}, Material.MACHINE, false);
        }
        // The bearing caps where the shafts leave, and the shaft ends: the input at the back, the output at the front.
        for (double[] cap : new double[][]{{a, z1, 1, rsSlow}, {b, z0, -1, rsFast}}) {
            var f = Mesh.Frame.of(new double[]{cap[0], y, 0}, 2, 1);
            double rc = 2 * cap[3] + .06, c0 = cap[2] > 0 ? cap[1] : cap[1] - .06, c1 = cap[2] > 0 ? cap[1] + .06 : cap[1];
            u.mesh.cylinder(f, rc, c0, c1, 12, Material.MACHINE);
        }
        u.coupling(a, y, z1 + .06, back, 1, rsSlow, deck, ProcessUnit.shown(SLOW), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        u.coupling(b, y, z0 - .06, front, -1, rsFast, deck, ProcessUnit.shown(SLOW * RATIO), EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT);
        // The baseplate and the plinth or table under it.
        double p0 = front + .15, p1 = back - .2;
        u.box(new double[]{x0 - .15, floor, p0}, new double[]{x1 + .15, deck, p1}, Material.STEELWORK, true);
        u.driverBase(x0 - .25, x1 + .25, p0 - .1, p1 + .1, PLINTH, h);
    }
}
