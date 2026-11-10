package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.VacuumPump;

import java.util.List;

/**
 * A vacuum pump casing on a shaft train (batch 2026-11-10-vacuum-pump, VACUUM_PUMP_PLAN.md sections 2 and 4, owner "Both
 * types & different models"): a liquid ring of one or two stages, or a dry screw, each a catalogue of models. The shape's
 * size is the model's number (1 to 4), its length a liquid ring's stages (1 or 2; the largest ring is single stage only),
 * so a model is the kind and those two: no shape field of its own. The suction and discharge grow from the top at the two
 * ends of a casing at least 3 m long, so their pipe blocks have a full free block between them; the shaft end, its half
 * coupling under its guard, is at the back, where a shaft block joins the driver. A liquid ring is a round casing between
 * its two port heads; its seal is internal (owner V2 B). A dry screw is a ribbed, water-jacketed box.
 */
final class VacuumPumpDesign extends UnitDesign {
    /** One model: its name, nominal capacity (m³/h), design speed (rpm) and its rotor's or housing's width (m). */
    record Model(String name, double capacity, double speed, double width) {
    }

    /** The liquid ring's models by number: its name takes the stage count, its 5000 m³/h ring is single stage. */
    static final List<Model> RINGS = List.of(new Model("150", 150, 1450, .3), new Model("500", 500, 1450, .45),
            new Model("1500", 1500, 980, .7), new Model("5000", 5000, 590, 1.1));
    /** The dry screw's models by number. */
    static final List<Model> SCREWS = List.of(new Model("DS-100", 100, 2950, .35), new Model("DS-300", 300, 2950, .45),
            new Model("DS-650", 650, 2950, .55), new Model("DS-2500", 2500, 2950, .8));
    /** The nozzles' line speed at the nominal capacity, m/s (the vendors' flange sizes); below {@code VACUUM_SPEED} at every duty. */
    static final double NOZZLE_SPEED = 25;
    static final double PLINTH = .25, GROUT = .04, BASEPLATE = .16;

    private final boolean ring;

    VacuumPumpDesign(boolean ring) {
        this.ring = ring;
    }

    private List<Model> models() {
        return ring ? RINGS : SCREWS;
    }

    /** The model of {@code shape}. */
    static Model model(EquipmentShape shape) {
        var list = shape.kind() == EquipmentShape.Kind.LIQUID_RING_PUMP ? RINGS : SCREWS;
        return list.get((int) Math.max(0, Math.min(list.size() - 1, Math.round(shape.diameter()) - 1)));
    }

    /** The law of {@code shape}: its type, capacity and design speed. */
    static VacuumPump law(EquipmentShape shape) {
        var m = model(shape);
        var type = shape.kind() == EquipmentShape.Kind.DRY_SCREW_PUMP ? VacuumPump.Type.DRY_SCREW
                : shape.tangentLength() >= 2 ? VacuumPump.Type.LIQUID_RING_TWO_STAGE : VacuumPump.Type.LIQUID_RING_ONE_STAGE;
        return new VacuumPump(type, m.capacity / 3600, m.speed);
    }

    /** The model's name: {@code LR1-500}, {@code LR2-150}, {@code DS-650}. */
    static String name(EquipmentShape shape) {
        var m = model(shape);
        return shape.kind() == EquipmentShape.Kind.LIQUID_RING_PUMP ? "LR" + Math.max(1, (int) shape.tangentLength()) + "-" + m.name : m.name;
    }

    /** The nozzles' outside diameter, m: the nominal capacity at {@link #NOZZLE_SPEED}, to the nearest 0.05 m. */
    static double nozzle(Model m) {
        return PipeSizes.od(Math.sqrt(4 * m.capacity / 3600 / (Math.PI * NOZZLE_SPEED)), .05, .4);
    }

    @Override public double minSize() {
        return 1;
    }

    @Override public double maxSize() {
        return models().size();
    }

    @Override public double sizeStep() {
        return 1;
    }

    @Override public int minLength(double size) {
        return 1;
    }

    /** A ring of one or two stages, the largest single stage only; a screw has none. */
    @Override public int maxLength(double size) {
        return ring && Math.round(size) < RINGS.size() ? 2 : 1;
    }

    @Override public int minSupport(double size, int length) {
        return 0;
    }

    @Override public int maxSupport() {
        return 0;
    }

    @Override public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override public boolean moves() {
        return true;
    }

    @Override public String sizeRow() {
        return "vacuum_pump.model";
    }

    /** A model reads as its capacity, the unit beside the box (the box holds the number only); its name is on the datasheet. */
    @Override public String sizeUnit() {
        return "m³/h";
    }

    @Override public String sizeText(double size) {
        var m = models().get((int) Math.max(0, Math.min(models().size() - 1, Math.round(size) - 1)));
        return whole(m.capacity);
    }

    @Override public String lengthRow() {
        return ring ? "vacuum_pump.stages" : null;
    }

    @Override public boolean lengthInMetres() {
        return false;
    }

    @Override public String supportRow() {
        return null;
    }

    @Override public Line held(EquipmentShape shape) {
        var law = law(shape);
        // The middle display's line fits 180 px (UnitTextTest): the shaft power stays on the datasheet, a whole motor rating
        // reads without its decimal (200 kW, 7.5 kW).
        double motor = Motor.ratingFor(law.maximumShaftPower()) / 1e3;
        return new Line("vacuum_pump.held", name(shape), whole(model(shape).capacity), motor == Math.rint(motor) ? whole(motor) : number(motor));
    }

    @Override public List<Line> datasheet(EquipmentShape shape) {
        var law = law(shape);
        var m = model(shape);
        double blank = law.type().blankOff();
        var lines = new java.util.ArrayList<Line>();
        lines.add(new Line("vacuum_pump." + law.type().key(), name(shape), whole(m.capacity), whole(m.speed)));
        lines.add(new Line("vacuum_pump.limits", blank >= 100 ? number(blank / 1e3) + " kPa" : whole(blank) + " Pa", number(VacuumPump.MAXIMUM_DISCHARGE / 1e3)));
        lines.add(new Line("vacuum_pump.power", number(law.maximumShaftPower() / 1e3), number(Motor.ratingFor(law.maximumShaftPower()) / 1e3)));
        // The internal seal's heat and water (owner V2 B): all the shaft power at a 5 K rise.
        if (law.type().liquidRing()) lines.add(new Line("vacuum_pump.seal", number(law.maximumShaftPower() / (4186 * 5) * 3.6)));
        return lines;
    }

    /**
     * A liquid ring package as the vendors ship it (owner: "Pump and separator"): from the front, a vertical separator on the
     * skid, the outboard bearing, the front port head with the suction flange on its top, the short ring housing (wider than it
     * is long; two stages a little longer, with a waist between them), the back port head, the drive-end bearing and the
     * coupling. The front head's discharge port runs into the separator's side; the gas leaves from the separator's top; the
     * seal water (internal, owner V2 B) shows as a stub on the housing's side. Every part scales with the model's rotor.
     */
    private void buildRing(ProcessUnit u) {
        var shape = u.shape;
        var m = model(shape);
        var law = law(shape);
        int h = shape.support();
        boolean two = shape.tangentLength() >= 2;
        double w = m.width, ax = .5, od = nozzle(m);
        double r = .7 * w + .1, rh = r + .05, th = Math.max(.12 + .12 * w, od + .08);
        double housing = two ? 1.8 * r : 1.2 * r;
        double bearingRadius = .2 * w + .06, bearing = .15 + .15 * w;
        double rs = .3 + .3 * w, minimumDeck = PLINTH + GROUT + BASEPLATE;
        double ys = Math.ceil(minimumDeck + rh + .08 - .5) + .5;
        double base = Math.min(ys - rh - .12, Math.floor(ys) - .04) - GROUT - BASEPLATE, deck = base + GROUT + BASEPLATE;
        // Along z: the separator's centre, then the suction (the front head's centre) two cells on at least, clear of it.
        double sepZ = Math.ceil(rs + .05 - .5) + .5, suction = sepZ + 2;
        while (suction - th / 2 - bearing < sepZ + rs + .1) suction += 1;
        double h0 = suction - th / 2, r0 = suction + th / 2, r1 = r0 + housing, h1 = r1 + th;
        var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
        int n = ProcessUnit.sides(2 * rh);
        // The ring housing: one ring, or two with a waist between the stages.
        if (two) {
            double waist = .12 + .1 * w, mid = (r0 + r1) / 2;
            u.mesh.prism(along, r, r0, mid - waist / 2, n, Material.PUMP_CASTING);
            u.mesh.prism(along, .82 * r, mid - waist / 2, mid + waist / 2, n, Material.PUMP_JOINT);
            u.mesh.prism(along, r, mid + waist / 2, r1, n, Material.PUMP_CASTING);
            u.mesh.annulus(along, .82 * r, r, mid - waist / 2, n, 1, Material.PUMP_CASTING);
            u.mesh.annulus(along, .82 * r, r, mid + waist / 2, n, -1, Material.PUMP_CASTING);
        } else u.mesh.prism(along, r, r0, r1, n, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, r0, r1, r, 0));
        u.volumes.add(Solid.rod(new double[]{ax, ys, 0}, 2, r0 + .03, r1 - .03, r - .05, 0));
        u.round(ax, ys, r0, r1, r);
        // The two thick port heads, round castings standing on feet.
        for (double[] head : new double[][]{{h0, r0}, {r1, h1}}) {
            u.mesh.band(along, rh, rh, head[0], head[1], n, Material.PUMP_JOINT);
            u.mesh.disc(along, rh, head[0], n, -1, Material.PUMP_DISC);
            u.mesh.disc(along, rh, head[1], n, 1, Material.PUMP_DISC);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, head[0], head[1], rh, 0));
            u.box(new double[]{ax - .8 * rh, deck, head[0] + .01}, new double[]{ax + .8 * rh, ys - .6 * rh, head[1] - .01}, Material.PUMP_CASTING, true);
        }
        // The bearing housings on their pedestals, outboard of both heads, and the front cover.
        double zb0 = h0 - bearing, zb1 = h1 + bearing, cover = zb0 - .05;
        int nb = ProcessUnit.sides(2 * bearingRadius);
        for (double[] span : new double[][]{{zb0, h0}, {h1, zb1}}) {
            u.mesh.prism(along, bearingRadius, span[0], span[1], nb, Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, span[0], span[1], bearingRadius, 0));
            double wide = Math.max(.18, .95 * bearingRadius), narrow = .65 * bearingRadius, top = ys - .6 * bearingRadius, p0 = span[0] + .02, p1 = span[1] - .02;
            u.extrude(List.of(new double[]{ax - wide, deck + .04}, new double[]{ax + wide, deck + .04},
                    new double[]{ax + narrow, top}, new double[]{ax - narrow, top}), p0, p1, Material.PUMP_CASTING);
            u.solids.add(Solid.box(new double[]{ax - wide, deck, p0}, new double[]{ax + wide, top, p1}));
        }
        u.mesh.band(along, bearingRadius, bearingRadius, cover, zb0, nb, Material.PUMP_JOINT);
        u.mesh.disc(along, bearingRadius, cover, nb, -1, Material.PUMP_DISC);
        u.mesh.annulus(along, .02 + .03 * w, bearingRadius, zb1, nb, 1, Material.PUMP_CASTING);
        // The seal water's stub on the housing's side, flanged.
        double seal = .03 + .02 * w, sealY = ys - .35 * r, sealZ = (r0 + r1) / 2;
        var sideways = Frame.of(new double[]{0, sealY, sealZ}, 0, 1);
        u.mesh.cylinder(sideways, seal, ax - r - .2, ax - .6 * r, 8, Material.PUMP_CASTING);
        u.mesh.cylinder(sideways, seal + .03, ax - r - .22, ax - r - .17, 10, Material.PUMP_JOINT);
        // The discharge: out of the front head's side, along the skid and into the separator's side.
        double rp = Math.max(.04, .4 * od), px = ax + Math.max(.3, .55 * rh), py = ys - .2 * rh;
        int np = ProcessUnit.sides(2 * rp);
        u.mesh.cylinder(Frame.of(new double[]{0, py, suction}, 0, 1), rp, ax + .4 * rh, px + rp, np, Material.PUMP_CASTING);
        u.mesh.cylinder(Frame.of(new double[]{px, py, 0}, 2, 1), rp, sepZ, suction + rp, np, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{px, py, 0}, 2, sepZ, suction + rp, rp, 0));
        // The separator: a vertical vessel on the skid, a 2:1 head on top, the gas outlet up out of it.
        double top = deck + 1.3 + 2.4 * rs, apex = .5 * rs;
        var up = Frame.of(new double[]{ax, 0, sepZ}, 1, 1);
        int ns = ProcessUnit.sides(2 * rs);
        u.mesh.prism(up, rs, deck, top, ns, Material.PUMP_CASTING);
        u.mesh.disc(up, rs, deck, ns, -1, Material.PUMP_DISC);
        u.mesh.head(up, rs, top, apex, ns, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, 0, sepZ}, 1, deck, top + apex, rs, 0));
        u.round(new double[]{ax, 0, sepZ}, 1, deck, top, rs);
        double rn = od / 2 + .03, neck = top + apex + .05;
        u.mesh.cylinder(up, rn, top, neck, ProcessUnit.sides(2 * rn), Material.PUMP_CASTING);
        u.pumpNozzle(Role.DISCHARGE, new double[]{ax, 0, sepZ}, 1, 1, neck, Math.ceil(neck + .02 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, od / 2);
        // The suction flange on top of the front head.
        double low = ys + .3 * rh, sNeck = ys + rh + .05;
        u.mesh.cylinder(Frame.of(new double[]{ax, 0, suction}, 1, 1), rn, low, sNeck, ProcessUnit.sides(2 * rn), Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, 0, suction}, 1, low, sNeck, rn, 0));
        u.pumpNozzle(Role.SUCTION, new double[]{ax, 0, suction}, 1, 1, sNeck, Math.ceil(ys + rh + .07 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, od / 2);
        // The shaft end and its coupling at the back; the skid under it all.
        double shaft = ProcessUnit.shaftRadius(law.maximumShaftPower() / 1e3, m.speed), face = Math.ceil(zb1 + .2 + shaft - 1e-9);
        double half = Math.max(rh, rs) + .15, x0 = ax - half, x1 = ax + half, z0 = sepZ - rs - .12, z1 = face - .01;
        u.coupling(ax, ys, zb1, face, 1, shaft, deck, ProcessUnit.shown(m.speed), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        u.baseplate(x0, x1, base + GROUT, deck, z0, z1);
        u.box(new double[]{x0 + .02, base, z0 + .02}, new double[]{x1 - .02, base + GROUT, z1 - .02}, Material.CONCRETE, false);
        u.driverBase(x0 - .08, x1 + .08, z0 - .08, z1, base, h);
    }

    @Override void build(ProcessUnit u) {
        if (ring) {
            buildRing(u);
            return;
        }
        var shape = u.shape;
        var m = model(shape);
        var law = law(shape);
        int h = shape.support();
        double w = m.width, ax = .5;
        // The body: a ring's casing 1.3 rotor diameters across, a screw's housing as wide as its rotors' pair; the port heads
        // or the jacket's ends at both ends; the port rows two cells apart at least.
        double r = ring ? .65 * w + .05 : .5 * w + .05, cap = r + .06 + .05 * w, capLength = .08 + .06 * w;
        double length = Math.max(3, (ring ? 1.1 + .5 * shape.tangentLength() : 2.2) * w);
        double inRow = .5, outRow = Math.floor(length - 1) + .5;
        double minimumDeck = PLINTH + GROUT + BASEPLATE;
        double ys = Math.ceil(minimumDeck + cap + .08 - .5) + .5;
        double base = Math.min(ys - cap - .12, Math.floor(ys) - .04) - GROUT - BASEPLATE, deck = base + GROUT + BASEPLATE;
        var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
        int n = ProcessUnit.sides(2 * cap);
        if (ring) {
            u.mesh.prism(along, r, 0, length, n, Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, 0, length, r, 0));
            u.volumes.add(Solid.rod(new double[]{ax, ys, 0}, 2, .05, length - .05, r - .05, 0));
            u.round(ax, ys, 0, length, r);
            // A ring between stages for a two-stage casing.
            if (shape.tangentLength() >= 2) u.mesh.band(along, cap, cap, length / 2 - capLength / 2, length / 2 + capLength / 2, n, Material.PUMP_JOINT);
        } else {
            u.box(new double[]{ax - r, ys - r, 0}, new double[]{ax + r, ys + r, length}, Material.PUMP_CASTING, true);
            u.volumes.add(Solid.box(new double[]{ax - r + .05, ys - r + .05, .05}, new double[]{ax + r - .05, ys + r - .05, length - .05}));
            // The cooling jacket's ribs across the housing.
            for (double z = .4; z < length - .3; z += .3)
                u.box(new double[]{ax - r - .03, ys - r + .04, z - .025}, new double[]{ax + r + .03, ys + r - .04, z + .025}, Material.PUMP_CASTING, false);
        }
        for (double[] end : new double[][]{{-capLength, 0, -1}, {length, length + capLength, 1}}) {
            u.mesh.band(along, cap, cap, end[0], end[1], n, Material.PUMP_JOINT);
            u.mesh.disc(along, cap, end[2] < 0 ? end[0] : end[1], n, (int) end[2], Material.PUMP_DISC);
            u.mesh.annulus(along, ring ? r : 0, cap, end[2] < 0 ? end[1] : end[0], n, -(int) end[2], Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, end[0], end[1], cap, 0));
        }
        // The bearing housings on their pedestals at the two ends.
        double housing = .25 * w + .06, bearing = .15 + .2 * w;
        double zb0 = -capLength - bearing, zb1 = length + capLength + bearing, cover = zb0 - .06;
        int nb = ProcessUnit.sides(2 * housing);
        for (double[] span : new double[][]{{zb0, -capLength}, {length + capLength, zb1}}) {
            u.mesh.prism(along, housing, span[0], span[1], nb, Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, span[0], span[1], housing, 0));
            double wide = Math.max(.2, .95 * housing), narrow = .65 * housing, top = ys - .6 * housing, p0 = span[0] + .03, p1 = span[1] - .03;
            u.extrude(List.of(new double[]{ax - wide, deck + .04}, new double[]{ax + wide, deck + .04},
                    new double[]{ax + narrow, top}, new double[]{ax - narrow, top}), p0, p1, Material.PUMP_CASTING);
            u.solids.add(Solid.box(new double[]{ax - wide, deck, p0}, new double[]{ax + wide, top, p1}));
        }
        u.mesh.band(along, housing, housing, cover, zb0, nb, Material.PUMP_JOINT);
        u.mesh.disc(along, housing, cover, nb, -1, Material.PUMP_DISC);
        u.mesh.annulus(along, .02 + .03 * w, housing, zb1, nb, 1, Material.PUMP_CASTING);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, cover, zb0, housing, 0));
        // The suction at the front and the discharge at the back, straight up out of the casing's top.
        double od = nozzle(m);
        for (int which = 0; which < 2; which++) {
            double z = which == 0 ? inRow : outRow, rn = od / 2 + .03, low = ys + .55 * r, neck = ys + r + .05;
            var up = Frame.of(new double[]{ax, 0, z}, 1, 1);
            u.mesh.cylinder(up, rn, low, neck, ProcessUnit.sides(2 * rn), Material.PUMP_CASTING);
            u.solids.add(Solid.rod(new double[]{ax, 0, z}, 1, low, neck, rn, 0));
            u.pumpNozzle(which == 0 ? Role.SUCTION : Role.DISCHARGE, new double[]{ax, 0, z}, 1, 1,
                    neck, Math.ceil(ys + r + .07 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, od / 2);
        }
        // The shaft end and its coupling at the back; the baseplate on its grout and plinth.
        double rs = ProcessUnit.shaftRadius(law.maximumShaftPower() / 1e3, m.speed), face = Math.ceil(zb1 + .2 + rs - 1e-9);
        double half = cap + .2, x0 = ax - half, x1 = ax + half, z0 = cover - .15, z1 = face - .01;
        u.coupling(ax, ys, zb1, face, 1, rs, deck, ProcessUnit.shown(m.speed), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        u.baseplate(x0, x1, base + GROUT, deck, z0, z1);
        u.box(new double[]{x0 + .02, base, z0 + .02}, new double[]{x1 - .02, base + GROUT, z1 - .02}, Material.CONCRETE, false);
        u.driverBase(x0 - .08, x1 + .08, z0 - .08, z1, base, h);
    }
}
