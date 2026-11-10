package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.PrimeMover;

import java.util.ArrayList;
import java.util.List;

/**
 * A fuelled prime mover on a shaft train (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md section 7): a liquid-fuel
 * combustion engine or a gas turbine package, one of its family's catalogue models ({@link PrimeMover#MODELS}). The shape's
 * size is the model's number (1 up), a gas turbine's length its steam coil (1 none, 2 a coil in its exhaust duct, owner P8
 * A), so a model is the kind and those two: no shape field of its own. Its shaft end is at the back, where a shaft block
 * joins the generator or the machine it drives. Each draws its own exhaust stack (25 m/s at its rated exhaust), its smoke
 * leaving at the stack's mouth ({@code stack_top_0}). An engine stands on its base frame with its radiator at its front; its
 * FUEL nozzle on its right. A gas turbine's enclosure (an open casing for a heavy-duty frame) carries its filter house over
 * the compressor end at the back; its exhaust leaves forward through a duct (with the coil: FEEDWATER at the duct's cold
 * end, STEAM at its hot end, on its top) to the stack at the front.
 */
final class PrimeMoverDesign extends UnitDesign {
    static final double PLINTH = .25;
    /** The fuel line's speed at the rated fuel, m/s: a liquid's 1.5, a gas's 20 at its supply's pressure. */
    static final double LIQUID_SPEED = 1.5, GAS_SPEED = 20;
    /** The coil's steam line, m/s, at 40 bar and 400 °C (13 kg/m³), and its water line, m/s. */
    static final double STEAM_SPEED = 25, STEAM_DENSITY = 13, WATER_SPEED = 2;

    private final PrimeMover.Family family;

    PrimeMoverDesign(PrimeMover.Family family) {
        this.family = family;
    }

    private List<PrimeMover.Model> models() {
        return PrimeMover.models(family);
    }

    /** The model of {@code shape}. */
    static PrimeMover.Model model(EquipmentShape shape) {
        var list = PrimeMover.models(family(shape.kind()));
        return list.get((int) Math.max(0, Math.min(list.size() - 1, Math.round(shape.diameter()) - 1)));
    }

    /** The family a kind builds. */
    static PrimeMover.Family family(EquipmentShape.Kind kind) {
        return kind == EquipmentShape.Kind.GAS_TURBINE ? PrimeMover.Family.GAS_TURBINE : PrimeMover.Family.ENGINE;
    }

    /** Whether {@code shape} is a gas turbine with its steam coil. */
    static boolean coil(EquipmentShape shape) {
        return shape.kind() == EquipmentShape.Kind.GAS_TURBINE && shape.tangentLength() >= 2;
    }

    /** The steam line's outside diameter, m, at the coil's design steam. */
    static double steamNozzle(PrimeMover.Model m) {
        return PipeSizes.od(Math.sqrt(4 * designSteam(m) / STEAM_DENSITY / STEAM_SPEED / Math.PI), .05, PipeSizes.MAX_OUTSIDE);
    }

    /** The coil's tube surface, m²: its design duty at a 5 kW/m² mean flux (finned tubes; it sizes nothing, the coil is designed to its pinch). */
    static double coilSurface(PrimeMover.Model m) {
        return Math.max(1, designSteam(m) * 2.127e6 / 5e3);
    }

    /**
     * The coil's passage: its parallel tubes as one line of twice the steam line's area, 30 m long, so its water side loses
     * a few kPa at its design flow.
     */
    static com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry coilPassage(PrimeMover.Model m) {
        return new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(30, Math.sqrt(2) * steamNozzle(m), HeatSides.TUBE_ROUGHNESS, 0);
    }

    /** The coil's design steam, kg/s: what the exhaust above a 260 °C pinch raises to 400 °C steam (plan section 10), for its nozzle sizes. */
    static double designSteam(PrimeMover.Model m) {
        var law = new PrimeMover(m);
        double heat = law.exhaustHeat(m.ratedFuel(), m.power()), flue = law.flue(heat);
        return Math.max(0, flue * (m.exhaust() - 533.15)) / 2.127e6;
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

    /** A gas turbine with or without its steam coil; an engine has none. */
    @Override public int maxLength(double size) {
        return family == PrimeMover.Family.GAS_TURBINE ? 2 : 1;
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

    /** The coupling's hub and the radiator's fans turn. */
    @Override public boolean moves() {
        return true;
    }

    @Override public String sizeRow() {
        return "prime_mover.model";
    }

    /** A model reads as its rated power, the unit beside the box; its name is on the datasheet. */
    @Override public String sizeUnit() {
        return "MW";
    }

    @Override public String sizeText(double size) {
        var m = models().get((int) Math.max(0, Math.min(models().size() - 1, Math.round(size) - 1)));
        return number(m.power() / 1e6);
    }

    @Override public String lengthRow() {
        return family == PrimeMover.Family.GAS_TURBINE ? "gas_turbine.coil" : null;
    }

    @Override public boolean lengthInMetres() {
        return false;
    }

    @Override public String supportRow() {
        return null;
    }

    @Override public Line held(EquipmentShape shape) {
        var m = model(shape);
        var law = new PrimeMover(m);
        return new Line("prime_mover.held", m.name(), number(law.ratedNet() / 1e6), whole(m.speed()), number(Motor.generatorRatingFor(law.ratedNet()) / 1e6));
    }

    @Override public List<Line> datasheet(EquipmentShape shape) {
        var m = model(shape);
        var law = new PrimeMover(m);
        double fuel = m.ratedFuel(), heat = law.exhaustHeat(fuel, m.power());
        var lines = new ArrayList<Line>();
        lines.add(new Line("prime_mover.rating", m.name(), number(law.ratedNet() / 1e6), whole(m.speed()), whole(100 * m.efficiency())));
        lines.add(new Line("prime_mover.fuel", number(fuel / 1e6), number(fuel / law.defaultHeatingValue()), number(m.required() / 1e5)));
        lines.add(new Line("prime_mover.exhaust", number(law.exhaustFlow(heat)), whole(m.exhaust() - 273.15), number(heat / 1e6)));
        if (family == PrimeMover.Family.ENGINE)
            lines.add(new Line("prime_mover.radiator", number(law.cooling(fuel, m.power()) / 1e6), whole(law.fans(fuel, m.power()) / 1e3)));
        else lines.add(new Line("prime_mover.combustor", number(m.ratio()), number(m.combustor() / 1e5)));
        if (coil(shape)) lines.add(new Line("prime_mover.coil", number(designSteam(m))));
        lines.add(new Line("prime_mover.generator", number(Motor.generatorRatingFor(law.ratedNet()) / 1e6), Integer.toString(poles(m.speed()))));
        return lines;
    }

    /** A generator's poles at {@code rpm} on 50 Hz: 2 at 3000 to 12 at 500. */
    static int poles(double rpm) {
        return (int) Math.round(6000 / rpm);
    }

    /** The fuel nozzle's outside diameter, m: the rated fuel at the line's speed, a gas at its required supply. */
    static double fuelNozzle(PrimeMover.Model m) {
        var law = new PrimeMover(m);
        double mass = m.ratedFuel() / law.defaultHeatingValue();
        double volume = m.family() == PrimeMover.Family.ENGINE ? mass / 850 / LIQUID_SPEED : mass / (m.required() * .016 / (8.314 * 298.15)) / GAS_SPEED;
        return PipeSizes.od(Math.sqrt(4 * volume / Math.PI), .05, .4);
    }

    @Override void build(ProcessUnit u) {
        if (family == PrimeMover.Family.ENGINE) buildEngine(u);
        else buildTurbine(u);
    }

    /** A nozzle up out of a box top at {@code y}, on cell column ({@code x}, {@code z}). */
    private static void up(ProcessUnit u, Role role, double x, double z, double y, double od) {
        double rn = od / 2 + .03, neck = y + .1;
        var frame = Frame.of(new double[]{x, 0, z}, 1, 1);
        u.mesh.cylinder(frame, rn, y - .05, neck, ProcessUnit.sides(2 * rn), Material.PIPE);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, y - .05, neck, rn, 0));
        u.pumpNozzle(role, new double[]{x, 0, z}, 1, 1, neck, Math.ceil(neck + .02 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, od / 2);
    }

    /** A nozzle out of a box side at {@code x} towards +x, on cell row ({@code y}, {@code z}). */
    private static void side(ProcessUnit u, Role role, double x, double y, double z, double od) {
        double rn = od / 2 + .03, neck = x + .1;
        var frame = Frame.of(new double[]{0, y, z}, 0, 1);
        u.mesh.cylinder(frame, rn, x - .05, neck, ProcessUnit.sides(2 * rn), Material.PIPE);
        u.solids.add(Solid.rod(new double[]{0, y, z}, 0, x - .05, neck, rn, 0));
        u.pumpNozzle(role, new double[]{0, y, z}, 0, 1, neck, Math.ceil(neck + .02 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT), od, od / 2);
    }

    /** An exhaust stack at ({@code x}, {@code z}) of radius {@code r} from {@code y0} to {@code y1}, its mouth marked for the smoke. */
    private static void stack(ProcessUnit u, double x, double z, double r, double y0, double y1, double silencer) {
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        int n = ProcessUnit.sides(2 * r);
        u.mesh.cylinder(up, r, y0, y1, n, Material.PIPE);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, y0, y1, r, 0));
        if (silencer > 0) {
            double s0 = y0 + .3 * (y1 - y0), s1 = s0 + silencer;
            u.mesh.cylinder(up, 1.5 * r, s0, s1, ProcessUnit.sides(3 * r), Material.MACHINE);
            u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, s0, s1, 1.5 * r, 0));
        }
        u.mesh.ring(up, .92 * r, r + .03, y1 - .04, y1, n, 3, Material.FLANGE);
        u.mouth("stack_top_0", x, z, .92 * r, y1);
    }

    /**
     * An engine: its radiator at the front (a fan face on its front, the fans' openings), the crankcase and cylinder block
     * (a V for more than ten cylinders, else in line), the rocker covers along the banks, the turbocharger at the front end
     * and the stack with its silencer over it, the flywheel housing and the coupling at the back; a steel base frame on its
     * plinth. Its sizes go by the cube root of its power.
     */
    private void buildEngine(ProcessUnit u) {
        var m = model(u.shape);
        var law = new PrimeMover(m);
        double s = Math.cbrt(m.power() / 1e6), length = 3.8 * s, width = 1.65 * s, height = 2.0 * s;
        double frame = .1 + .08 * s, deck = PLINTH + frame;
        double half = width / 2 + .15, ax = ProcessUnit.centre(half + .12);
        double ys = Math.ceil(deck + .35 * height - .5) + .5;
        // The radiator at the front: its core and frame, its fans' openings on its front face.
        double zr0 = .2, zr1 = zr0 + .25 * width + .2, top = deck + 1.05 * height;
        u.box(new double[]{ax - half, deck, zr0}, new double[]{ax + half, top, zr1}, Material.AC_PLENUM, true);
        int fans = Math.max(1, (int) Math.round(width / 1.4));
        double fan = Math.min(.42 * width / fans, .45 * (top - deck));
        for (int k = 0; k < fans; k++) {
            double fx = ax - width / 2 + width * (k + .5) / fans, fy = (deck + top) / 2;
            var at = Frame.of(new double[]{fx, fy, 0}, 2, -1);
            u.mesh.disc(at, fan, -zr0 + .01, ProcessUnit.sides(2 * fan), 1, Material.OPENING);
        }
        // The crankcase and the cylinder block over it.
        double zb0 = zr1 + .3, zb1 = zb0 + length, crank = ys + .15 * height;
        u.box(new double[]{ax - width / 2, deck, zb0}, new double[]{ax + width / 2, crank, zb1}, Material.MACHINE, true);
        int cylinders = m.cylinders();
        boolean vee = cylinders > 10;
        double topWidth = vee ? .32 * width : .22 * width, blockTop = deck + height;
        u.extrude(List.of(new double[]{ax - width / 2 + .05, crank}, new double[]{ax + width / 2 - .05, crank},
                new double[]{ax + topWidth, blockTop}, new double[]{ax - topWidth, blockTop}), zb0 + .1, zb1 - .1, Material.MACHINE);
        u.solids.add(Solid.box(new double[]{ax - width / 2, crank, zb0 + .1}, new double[]{ax + width / 2, blockTop, zb1 - .1}));
        // The rocker covers: one a cylinder, along each bank.
        int banks = vee ? 2 : 1, perBank = Math.max(1, cylinders / banks);
        double pitch = (length - .4) / perBank, cover = .35 * pitch, coverHeight = .06 + .06 * s;
        for (int b = 0; b < banks; b++) {
            double bx = banks == 1 ? ax : ax + (b == 0 ? -1 : 1) * .5 * topWidth;
            for (int k = 0; k < perBank; k++) {
                double cz = zb0 + .3 + pitch * (k + .5);
                u.box(new double[]{bx - .4 * topWidth / banks, blockTop, cz - cover}, new double[]{bx + .4 * topWidth / banks, blockTop + coverHeight, cz + cover}, Material.MOTOR_PLAIN, false);
            }
        }
        // The turbocharger at the front end and the exhaust stack with its silencer over it.
        double rt = .14 * width, yt = blockTop + rt, zt = zb0 + .15 + .5 * width;
        var turbo = Frame.of(new double[]{ax, yt, 0}, 2, 1);
        u.mesh.cylinder(turbo, rt, zb0 + .1, zt, ProcessUnit.sides(2 * rt), Material.MACHINE);
        u.solids.add(Solid.rod(new double[]{ax, yt, 0}, 2, zb0 + .1, zt, rt, 0));
        double rs = Math.max(.08, law.stackDiameter() / 2), zs = Math.floor(zb0 + .3) + .5;
        stack(u, ax, zs, rs, yt + rt * .6, blockTop + 2 + 1.5 * s, 1.2 + .6 * s);
        // The flywheel housing and the coupling at the back.
        double rf = .42 * width, zf = zb1 + .15 + .1 * s;
        var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
        u.mesh.cylinder(along, rf, zb1, zf, ProcessUnit.sides(2 * rf), Material.MACHINE);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, zb1, zf, rf, 0));
        double shaft = ProcessUnit.shaftRadius(m.power() / 1e3, m.speed()), face = Math.ceil(zf + .2 + shaft - 1e-9);
        u.coupling(ax, ys, zf, face, 1, shaft, deck, ProcessUnit.shown(m.speed()), EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT);
        // The fuel connection on the right, near the front of the block.
        side(u, Role.FUEL, ax + width / 2, ys, Math.floor(zb0 + 1) + .5, fuelNozzle(m));
        // The base frame on the plinth.
        u.box(new double[]{ax - half, PLINTH, zr0 - .05}, new double[]{ax + half, deck, face - .2}, Material.STEELWORK, true);
        u.driverBase(ax - half - .1, ax + half + .1, zr0 - .15, face - .1, PLINTH, 0);
    }

    /**
     * A gas turbine package: from the front, its stack, its exhaust duct (holding the steam coil when it has one), the
     * turbine's enclosure (a heavy-duty frame's open casing on its base), the filter house over the compressor end at the
     * back, and the coupling. Its sizes go by the cube root of its power over 30 MW.
     */
    private void buildTurbine(ProcessUnit u) {
        var m = model(u.shape);
        var law = new PrimeMover(m);
        boolean heavy = m.name().startsWith("HD"), coil = coil(u.shape);
        double s = Math.cbrt(m.power() / 3e7), length = 6 * s + 3.5, width = 2.4 * s + 1.2, height = 2.6 * s + 1.4;
        double rs = Math.max(.2, law.stackDiameter() / 2), deck = PLINTH + .2;
        double half = Math.max(width / 2, rs) + .15, ax = ProcessUnit.centre(half + .12);
        double ys = Math.ceil(deck + .45 * height - .5) + .5;
        // The stack at the front, the duct behind it, the enclosure behind that.
        double zs = Math.ceil(rs + .2 - .5) + .5, duct = coil ? Math.max(4, 1.5 * rs + 2) : Math.max(1, .6 * rs);
        double zd0 = zs, zd1 = zs + rs + duct, ductTop = deck + Math.max(.8 * height, 1.6 * rs), dh = Math.max(width / 2, .9 * rs);
        stack(u, ax, zs, rs, deck, ductTop + 3 + 4 * s, 0);
        u.box(new double[]{ax - dh, deck, zd0}, new double[]{ax + dh, ductTop, zd1}, Material.HEATER, true);
        // The turbine: an enclosure with louvred panels, or a heavy-duty frame's open casing on its base.
        double zg0 = zd1, zg1 = zg0 + length, top = deck + height;
        if (heavy) {
            double rc = .38 * width;
            var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
            u.mesh.frustum(along, rc * .7, rc, zg0, zg0 + .4 * length, ProcessUnit.sides(2 * rc), Material.MACHINE);
            u.mesh.cylinder(along, rc, zg0 + .4 * length, zg1 - .3 * length, ProcessUnit.sides(2 * rc), Material.MACHINE);
            u.mesh.frustum(along, rc, .8 * rc, zg1 - .3 * length, zg1, ProcessUnit.sides(2 * rc), Material.MACHINE);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, zg0, zg1, rc, 0));
            u.box(new double[]{ax - width / 2, deck, zg0}, new double[]{ax + width / 2, ys - .8 * rc, zg1}, Material.STEELWORK, true);
        } else {
            u.box(new double[]{ax - width / 2, deck, zg0}, new double[]{ax + width / 2, top, zg1}, Material.MACHINE, true);
            for (int side = -1; side <= 1; side += 2) {
                double xf = ax + side * width / 2, xo = xf + side * .01;
                for (double zl = zg0 + .6; zl < zg1 - .6; zl += 1.2)
                    u.box(new double[]{Math.min(xf, xo), deck + .3 * height, zl - .3}, new double[]{Math.max(xf, xo), deck + .7 * height, zl + .3}, Material.OPENING, false);
            }
        }
        // The filter house over the compressor end, its weather hoods' openings on its front.
        double zf0 = zg1 - .45 * length, filterTop = top + .6 * height;
        u.box(new double[]{ax - .55 * width, top, zf0}, new double[]{ax + .55 * width, filterTop, zg1}, Material.TOWER_PANEL, true);
        u.box(new double[]{ax - .5 * width, top + .1 * height, zf0 - .01}, new double[]{ax + .5 * width, filterTop - .1 * height, zf0}, Material.OPENING, false);
        // The steam coil's connections on the duct's top: steam at its hot end, feed water at its cold end, two cells apart at least.
        if (coil) {
            double steam = designSteam(m);
            double so = steamNozzle(m);
            double wo = PipeSizes.od(Math.sqrt(4 * steam / 1000 / WATER_SPEED / Math.PI), .05, .4);
            double hot = Math.floor(zd1 - .6) + .5, cold = Math.max(Math.floor(zs + rs + .6) + .5, Math.floor(zs) + 1.5);
            if (hot - cold < 2) hot = cold + 2;
            up(u, Role.STEAM, ax, hot, ductTop, so);
            up(u, Role.FEEDWATER, ax, cold, ductTop, wo);
        }
        // The fuel connection on the enclosure's right, near its middle.
        side(u, Role.FUEL, ax + width / 2, ys, Math.floor((zg0 + zg1) / 2) + .5, fuelNozzle(m));
        // The coupling at the back; the base on its plinth.
        double shaft = ProcessUnit.shaftRadius(m.power() / 1e3, m.speed()), face = Math.ceil(zg1 + .2 + shaft - 1e-9);
        u.coupling(ax, ys, zg1, face, 1, shaft, deck, ProcessUnit.shown(m.speed()), EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT);
        u.box(new double[]{ax - half, PLINTH, zs - rs - .05}, new double[]{ax + half, deck, face - .2}, Material.STEELWORK, true);
        u.driverBase(ax - half - .1, ax + half + .1, zs - rs - .15, face - .1, PLINTH, 0);
    }
}
