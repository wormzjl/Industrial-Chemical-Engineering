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

    /** An exhaust stack at ({@code x}, {@code z}) of radius {@code r} from {@code y0} to {@code y1}: its open top's rim and dark inside, its mouth marked for the smoke. */
    private static void stack(ProcessUnit u, double x, double z, double r, double y0, double y1, double silencer) {
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        int n = ProcessUnit.sides(2 * r);
        u.mesh.prism(up, r, y0, y1, n, Material.PIPE);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, y0, y1, r, 0));
        if (silencer > 0) {
            double s0 = y0 + .3 * (y1 - y0), s1 = s0 + silencer;
            u.mesh.cylinder(up, 1.5 * r, s0, s1, ProcessUnit.sides(3 * r), Material.MACHINE);
            u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, s0, s1, 1.5 * r, 0));
        }
        double lip = Math.min(.5, 1.2 * r);
        u.mesh.ring(up, .96 * r, r + .03 + .02 * r, y1 - .1, y1 + .02, n, 3, Material.FLANGE);
        u.mesh.bore(up, .96 * r, y1 - .1 - lip, y1 - .1, n, Material.OPENING);
        u.mesh.disc(up, .96 * r, y1 - .1 - lip, n, 1, Material.OPENING);
        u.mouth("stack_top_0", x, z, .92 * r, y1);
    }

    /** The four sides of a duct from corners {@code a} to corners {@code b} (each four points in order round it), around {@code centre}. */
    private static void loft(ProcessUnit u, double[][] a, double[][] b, double[] centre, Material material) {
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            var face = List.of(a[i], a[j], b[j], b[i]);
            double[] out = new double[3];
            for (var p : face) for (int k = 0; k < 3; k++) out[k] += p[k] / 4;
            for (int k = 0; k < 3; k++) out[k] -= centre[k];
            u.mesh.flat(face, out, material);
        }
    }

    /** The corners of a rectangle across z at {@code z}: x from x0 to x1, y from y0 to y1. */
    private static double[][] across(double x0, double y0, double x1, double y1, double z) {
        return new double[][]{{x0, y0, z}, {x1, y0, z}, {x1, y1, z}, {x0, y1, z}};
    }

    /** The corners of a level rectangle at {@code y}: x from x0 to x1, z from z0 to z1. */
    private static double[][] level(double x0, double z0, double x1, double z1, double y) {
        return new double[][]{{x0, y, z0}, {x1, y, z0}, {x1, y, z1}, {x0, y, z1}};
    }

    /**
     * A turbocharger lying across x at height {@code y} and station {@code z}: its turbine's snail housing centred at
     * {@code tx}, its bearing housing, its compressor's snail housing and air filter towards {@code sg} (±x), the charge-air
     * pipe down from the compressor to the block's top at {@code block}.
     */
    private static void turbocharger(ProcessUnit u, double tx, double y, double z, double r, int sg, double block) {
        var dir = new double[]{sg, 0, 0};
        var f = new Frame(new double[]{tx, y, z}, dir, new double[]{0, 1, 0});
        var c = new Frame(new double[]{tx + sg * 1.05 * r, y, z}, dir, new double[]{0, 1, 0});
        int n = ProcessUnit.sides(2 * r);
        u.mesh.cylinder(f, .7 * r, -.28 * r, .28 * r, n, Material.MACHINE);
        u.mesh.torus(f, .62 * r, .34 * r, .3, 1.75 * Math.PI, 10, 8, Material.MACHINE);
        u.mesh.cylinder(f, .32 * r, .28 * r, .8 * r, 10, Material.MOTOR_PLAIN);
        u.mesh.cylinder(c, .72 * r, -.25 * r, .25 * r, n, Material.MOTOR_PLAIN);
        u.mesh.torus(c, .6 * r, .3 * r, .3, 1.75 * Math.PI, 10, 8, Material.MOTOR_PLAIN);
        u.mesh.cylinder(c, .55 * r, .25 * r, .75 * r, n, Material.AC_FINS);
        double xc = tx + sg * 1.05 * r;
        u.mesh.cylinder(Frame.of(new double[]{xc, 0, z}, 1, 1), .22 * r, block, y - .5 * r, 10, Material.PIPE);
        double x0 = Math.min(tx - .95 * r, xc + sg * .75 * r), x1 = Math.max(tx + .95 * r, xc + sg * .75 * r);
        x0 = Math.min(x0, tx + sg * .95 * r);
        x1 = Math.max(x1, tx + sg * .95 * r);
        u.solids.add(Solid.rod(new double[]{0, y, z}, 0, Math.min(x0, xc - r), Math.max(x1, xc + r), .95 * r, 0));
    }

    /**
     * A vertical box face's weather hoods: rows of hoods across x from {@code x0} to {@code x1}, each over its dark slot, on
     * the face at {@code z} facing {@code sign} along z, from {@code y0} to {@code y1}.
     */
    private static void hoods(ProcessUnit u, double x0, double x1, double y0, double y1, double z, int sign) {
        int rows = Math.max(2, (int) Math.floor((y1 - y0) / .5));
        double pitch = (y1 - y0) / rows;
        for (int k = 0; k < rows; k++) {
            double yt = y1 - k * pitch - .05, yb = yt - .55 * pitch;
            u.box(new double[]{x0, yb, Math.min(z, z + sign * .01)}, new double[]{x1, yt, Math.max(z, z + sign * .01)}, Material.OPENING, false);
            u.box(new double[]{x0 - .02, yt - .06, Math.min(z, z + sign * .18)}, new double[]{x1 + .02, yt + .02, Math.max(z, z + sign * .18)}, Material.MOTOR_PLAIN, false);
        }
    }

    /**
     * An engine (owner review E1 to E6): a high-speed set compact, its radiator on its free end (front) with its fans facing
     * out; a medium-speed set (750 rpm and under) taller and longer, its radiator bank lying beside it on the left with its
     * fans facing up. The crankcase with a door per cylinder along both lower sides; the block (two inclined banks for more
     * than ten cylinders, else one) with a row of head covers on each bank and the exhaust manifold along its outer side to
     * the turbocharger (two on a medium-speed set from 15 MW) on top at the free end; the exhaust up from it into the
     * silencer and the stack; the flywheel housing and the coupling at the back; a steel base frame on its plinth.
     */
    private void buildEngine(ProcessUnit u) {
        var m = model(u.shape);
        var law = new PrimeMover(m);
        boolean medium = m.speed() <= 750;
        double s = Math.cbrt(m.power() / 1e6), length = (medium ? 4.8 : 3.8) * s, width = 1.65 * s, height = (medium ? 2.3 : 2.0) * s;
        double frame = .1 + .08 * s, deck = PLINTH + frame;
        int cylinders = m.cylinders(), banks = cylinders > 10 ? 2 : 1, perBank = Math.max(1, cylinders / banks);
        double bank = medium ? .8 * width : 0, gap = medium ? .5 : 0, half = width / 2 + .15, left = half + gap + bank;
        double ax = ProcessUnit.centre(left + .12);
        double ys = Math.ceil(deck + .35 * height - .5) + .5;
        // A high-speed set's radiator at the front: its core and frame, its grilled fans on its front face.
        double zr0 = .3, zb0 = .5;
        if (!medium) {
            double zr1 = zr0 + .25 * width + .2, top = deck + 1.05 * height;
            u.box(new double[]{ax - half, deck, zr0}, new double[]{ax + half, top, zr1}, Material.AC_PLENUM, true);
            int fans = Math.max(1, (int) Math.round(width / 1.4));
            double fan = Math.min(.42 * width / fans, .45 * (top - deck));
            for (int k = 0; k < fans; k++) {
                double fx = ax - width / 2 + width * (k + .5) / fans, fy = (deck + top) / 2;
                var at = Frame.of(new double[]{fx, fy, 0}, 2, -1);
                int n = ProcessUnit.sides(2 * fan);
                u.mesh.ring(at, fan, fan + .05, -zr0, -zr0 + .1, n, 2, Material.AC_RING);
                u.grilledFan(at, fan, -zr0 + .1, .08, n, ProcessUnit.FAN_SPEED);
            }
            zb0 = zr1 + .3;
        }
        // The crankcase, a door per cylinder along both lower sides.
        double zb1 = zb0 + length, crank = ys + (banks == 2 ? .05 : .15) * height, blockTop = deck + height;
        u.box(new double[]{ax - width / 2, deck, zb0}, new double[]{ax + width / 2, crank, zb1}, Material.MACHINE, true);
        double pitch = (length - .4) / perBank;
        for (int side = -1; side <= 1; side += 2) {
            double xf = ax + side * width / 2, xo = xf + side * .02;
            for (int k = 0; k < perBank; k++) {
                double cz = zb0 + .2 + pitch * (k + .5);
                u.box(new double[]{Math.min(xf, xo), deck + .25 * (crank - deck), cz - .3 * pitch}, new double[]{Math.max(xf, xo), deck + .7 * (crank - deck), cz + .3 * pitch},
                        Material.MOTOR_PLAIN, false);
            }
        }
        // The block: one bank upright or two inclined; the head covers in a row on each bank; each bank's exhaust manifold along its outer side.
        double cover = .35 * pitch, coverHeight = .06 + .06 * s, rm = .05 + .04 * s;
        double[][] manifold = new double[banks][];
        if (banks == 1) {
            double topWidth = .22 * width;
            u.extrude(List.of(new double[]{ax - width / 2 + .05, crank}, new double[]{ax + width / 2 - .05, crank},
                    new double[]{ax + topWidth, blockTop}, new double[]{ax - topWidth, blockTop}), zb0 + .1, zb1 - .1, Material.MACHINE);
            for (int k = 0; k < perBank; k++) {
                double cz = zb0 + .2 + pitch * (k + .5);
                u.box(new double[]{ax - .8 * topWidth, blockTop, cz - cover}, new double[]{ax + .8 * topWidth, blockTop + coverHeight, cz + cover}, Material.MOTOR_PLAIN, false);
            }
            double my = blockTop - .25 * (blockTop - crank), mx = ax - (topWidth + .25 * (width / 2 - .05 - topWidth)) - rm;
            manifold[0] = new double[]{mx, my};
        } else {
            double yv = crank + .3 * (blockTop - crank), theta = Math.toRadians(25), sn = Math.sin(theta), cs = Math.cos(theta), tb = .3 * width;
            u.extrude(List.of(new double[]{ax - width / 2 + .05, crank}, new double[]{ax + width / 2 - .05, crank},
                    new double[]{ax + .3 * width, yv}, new double[]{ax - .3 * width, yv}), zb0 + .1, zb1 - .1, Material.MACHINE);
            for (int b = 0; b < 2; b++) {
                int sg = b == 0 ? -1 : 1;
                double[] dir = {sg * sn, cs, 0}, out = {sg * cs, -sn, 0}, ez = {0, 0, (length - .2) / 2};
                double bx = ax + sg * .17 * width, by = yv - .05, lb = (blockTop - by) / cs;
                double[] root = {bx, by, (zb0 + zb1) / 2};
                u.mesh.block(plus(root, dir, lb / 2), times(out, tb / 2), times(dir, lb / 2), ez, Material.MACHINE);
                double[] head = plus(root, dir, lb);
                for (int k = 0; k < perBank; k++) {
                    double cz = zb0 + .2 + pitch * (k + .5);
                    var c = plus(head, dir, coverHeight / 2);
                    c[2] = cz;
                    u.mesh.block(c, times(out, .38 * tb), times(dir, coverHeight / 2), new double[]{0, 0, cover}, Material.MOTOR_PLAIN);
                }
                var mp = plus(plus(root, dir, .7 * lb), out, tb / 2 + rm + .01);
                manifold[b] = new double[]{mp[0], mp[1]};
            }
        }
        u.solids.add(Solid.box(new double[]{ax - width / 2, crank, zb0 + .1}, new double[]{ax + width / 2, blockTop + coverHeight, zb1 - .1}));
        // The turbochargers on top at the free end; the exhaust up from them into the silencer and the stack.
        boolean twin = medium && m.power() >= 1.5e7;
        double rt = (twin ? .1 : .14) * width, yt = blockTop + coverHeight + rt + .05, zt = Math.ceil(zb0 + rt + .15 - .5) + .5;
        double rs = Math.max(.08, law.stackDiameter() / 2), stackTop = blockTop + 2 + (medium ? 2.5 : 1.5) * s;
        double[] turbines = twin ? new double[]{ax - .12 * width, ax + .12 * width} : new double[]{ax};
        for (int k = 0; k < turbines.length; k++) turbocharger(u, turbines[k], yt, zt, rt, twin ? (k == 0 ? -1 : 1) : 1, blockTop);
        double yh = yt + .6 * rt;
        if (twin) {
            yh = yt + 1.2 * rt + rs;
            for (double tx : turbines) {
                u.mesh.prism(Frame.of(new double[]{tx, 0, zt}, 1, 1), .8 * rs, yt + .5 * rt, yh, ProcessUnit.sides(1.6 * rs), Material.PIPE);
                u.solids.add(Solid.rod(new double[]{tx, 0, zt}, 1, yt + .5 * rt, yh, .8 * rs, 0));
            }
            u.mesh.cylinder(Frame.of(new double[]{0, yh, zt}, 0, 1), rs, turbines[0] - .8 * rs, turbines[1] + .8 * rs, ProcessUnit.sides(2 * rs), Material.PIPE);
            u.solids.add(Solid.rod(new double[]{0, yh, zt}, 0, turbines[0] - .8 * rs, turbines[1] + .8 * rs, rs, 0));
        }
        stack(u, ax, zt, rs, yh, stackTop, 1.2 + .6 * s);
        // Each manifold from the back of its bank to its turbine at the front.
        for (int b = 0; b < banks; b++) {
            double mx = manifold[b][0], my = manifold[b][1], tx = turbines[twin ? b : 0];
            u.mesh.cylinder(Frame.of(new double[]{mx, my, 0}, 2, 1), rm, zt, zb1 - .3, ProcessUnit.sides(2 * rm), Material.PIPE);
            u.mesh.bar(new double[]{mx, my, zt}, new double[]{tx, yt - .4 * rt, zt}, 1.6 * rm, new double[]{0, 0, 1}, Material.PIPE);
        }
        // A medium-speed set's radiator bank beside it on the left, on its legs, its fans facing up; its two coolant pipes.
        if (medium) {
            double x1 = ax - half - gap, x0 = x1 - bank, yb0 = PLINTH + .45 * height, yb1 = yb0 + .3 + .1 * s;
            u.box(new double[]{x0, yb0, zb0}, new double[]{x1, yb1, zb1}, Material.AC_PLENUM, true);
            for (double xp : new double[]{x0 + .12, x1 - .12})
                for (double zp : new double[]{zb0 + .12, (zb0 + zb1) / 2, zb1 - .12})
                    u.box(new double[]{xp - .08, deck, zp - .08}, new double[]{xp + .08, yb0, zp + .08}, Material.STEELWORK, true);
            int rows = bank > 2.6 ? 2 : 1, count;
            double fr = Math.min(.42 * bank / rows, .9);
            count = Math.max(1, (int) Math.floor((zb1 - zb0) / (2 * fr + .3)));
            for (int i = 0; i < rows; i++)
                for (int k = 0; k < count; k++) {
                    double fx = x0 + bank * (i + .5) / rows, fz = zb0 + (zb1 - zb0) * (k + .5) / count;
                    var f = Frame.of(new double[]{fx, 0, fz}, 1, 1);
                    int n = ProcessUnit.sides(2 * fr);
                    u.mesh.ring(f, fr, fr + .05, yb1, yb1 + .12, n, 2, Material.AC_RING);
                    u.grilledFan(f, fr, yb1 + .12, .1, n, ProcessUnit.FAN_SPEED);
                }
            double rp = .05 + .03 * s;
            for (double zc : new double[]{zb0 + .6, zb1 - .9}) {
                u.mesh.cylinder(Frame.of(new double[]{0, (crank + deck) / 2, zc}, 0, 1), rp, x1 - .05, ax - width / 2 + .02, ProcessUnit.sides(2 * rp), Material.PIPE);
                u.mesh.cylinder(Frame.of(new double[]{x1 - .3, 0, zc}, 1, 1), rp, (crank + deck) / 2 - rp, yb0, ProcessUnit.sides(2 * rp), Material.PIPE);
            }
        }
        // The flywheel housing and the coupling at the back.
        double rf = .42 * width, zf = zb1 + .15 + .1 * s;
        var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
        u.mesh.cylinder(along, rf, zb1, zf, ProcessUnit.sides(2 * rf), Material.MACHINE);
        u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, zb1, zf, rf, 0));
        // The load shaft turning in its open guard (owner: visible turning parts), then the half coupling under its guard.
        double shaft = ProcessUnit.shaftRadius(m.power() / 1e3, m.speed()), zl = zf + .5 + .2 * s, face = Math.ceil(zl + .2 + shaft - 1e-9);
        u.loadShaft(ax, ys, zf, zl, shaft, deck, ProcessUnit.shown(m.speed()));
        u.coupling(ax, ys, zl, face, 1, shaft, deck, ProcessUnit.shown(m.speed()), EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT);
        // The torsional damper turning on the crankshaft's free end.
        u.damper(ax, ys, zb0 - .08 - .05 * s, zb0, .22 * width, ProcessUnit.shown(m.speed()));
        // The fuel connection on the right, near the front of the block.
        side(u, Role.FUEL, ax + width / 2, ys, Math.floor(zb0 + 1) + .5, fuelNozzle(m));
        // The base frame on the plinth.
        double xl = ax - left;
        u.box(new double[]{xl, PLINTH, zr0 - .05}, new double[]{ax + half, deck, face - .2}, Material.STEELWORK, true);
        u.driverBase(xl - .1, ax + half + .1, zr0 - .15, face - .1, PLINTH, 0);
    }

    private static double[] plus(double[] p, double[] d, double t) {
        return new double[]{p[0] + t * d[0], p[1] + t * d[1], p[2] + t * d[2]};
    }

    private static double[] times(double[] d, double t) {
        return new double[]{t * d[0], t * d[1], t * d[2]};
    }

    /**
     * A gas turbine package (owner review G1 to G5): from the front, its stack on a rectangular-to-round transition over the
     * exhaust plenum (with the coil: the HRSG casing, taller than the package, its headers on top carrying FEEDWATER and
     * STEAM, an expanding inlet duct behind it); the turbine: an enclosure with louvred sides and roof fans, or a
     * heavy-duty frame's open casing (the diffuser widening into the plenum, the turbine, the ring of combustor cans, the
     * narrower compressor, its split flange and bolt row) with its inlet plenum at the compressor's front and its raised
     * filter house on a vertical inlet duct with its silencer; an enclosure's filter house on its roof over the compressor
     * end. Every filter house shows its weather hoods. The coupling at the back. Its sizes go by the cube root of its power
     * over 30 MW; its shaft lies at its generator's height.
     */
    private void buildTurbine(ProcessUnit u) {
        var m = model(u.shape);
        var law = new PrimeMover(m);
        boolean heavy = m.name().startsWith("HD"), coil = coil(u.shape);
        double s = Math.cbrt(m.power() / 3e7), length = 6 * s + 3.5, width = 2.4 * s + 1.2, height = 2.6 * s + 1.4;
        double rs = Math.max(.2, law.stackDiameter() / 2), deck = PLINTH + .2, rc = .38 * width;
        double top = deck + height, filterTop = top + .6 * height;
        // The shaft at its generator's height (owner review T1), above the diffuser's or the enclosure's floor.
        double own = Math.ceil(deck + (heavy ? 1.2 * rc + .05 : .3 * height) - .5) + .5;
        double generator = MotorDesign.generatorShaft(Motor.generatorRatingFor(m.power()) / 1e3, poles(m.speed()));
        double ys = Double.isNaN(generator) ? own : Math.max(own, generator);
        // The plenum (the HRSG casing with the coil) at the front, the stack over its front end.
        double dh = coil ? Math.max(.65 * width, rs + .35) : Math.max(.55 * width, rs + .3);
        double half = Math.max(dh, .55 * width) + .15, ax = ProcessUnit.centre(half + .12);
        double hz = rs + .25, zs = Math.ceil(hz + .2 - .5) + .5, zp0 = zs - hz;
        double zp1 = coil ? zs + hz + Math.max(4, 1.5 * rs + 2) : zs + hz + Math.max(.4, .2 * rs);
        // The package's top: an enclosure's filter house, or a heavy frame's raised one (below); the HRSG casing stands clear above it.
        double packageTop = heavy ? ys + 1.1 * rc + Math.max(2, .5 * height) + .7 * height : filterTop;
        double plenumTop = coil ? Math.max(packageTop + Math.max(1.5, .4 * height), deck + 2.4 * hz) : Math.max(deck + .8 * height, Math.max(deck + 2 * hz, heavy ? ys + 1.2 * rc + .1 : 0));
        u.box(new double[]{ax - dh, deck, zp0}, new double[]{ax + dh, plenumTop, zp1}, Material.HEATER, true);
        // The stack (owner review G1): about four enclosure heights over the deck (HD-300: 30 m), on its transition.
        double y1 = plenumTop + 1.2 * rs, stackTop = Math.max(deck + 4.2 * height, y1 + Math.max(6, 4 * rs)), hx = Math.min(dh - .05, 1.4 * rs);
        double[] mid = {ax, (plenumTop + y1) / 2, zs};
        loft(u, level(ax - hx, zs - hz + .05, ax + hx, zs + hz - .05, plenumTop), level(ax - .72 * rs, zs - .72 * rs, ax + .72 * rs, zs + .72 * rs, y1), mid, Material.HEATER);
        u.solids.add(Solid.box(new double[]{ax - hx, plenumTop, zs - hz}, new double[]{ax + hx, y1, zs + hz}));
        stack(u, ax, zs, rs, y1 - .1, stackTop, 0);
        // With the coil: the expanding inlet duct from the turbine's exhaust to the casing.
        double zg0 = zp1, mouthTop = heavy ? ys + 1.25 * rc : top, mouthHalf = heavy ? .6 * width : width / 2;
        if (coil) {
            double inlet = Math.max(1.5, .6 * height);
            zg0 = zp1 + inlet;
            loft(u, across(ax - dh, deck, ax + dh, plenumTop, zp1), across(ax - mouthHalf, deck, ax + mouthHalf, mouthTop, zg0), new double[]{ax, (deck + plenumTop) / 2, (zp1 + zg0) / 2},
                    Material.HEATER);
            u.solids.add(Solid.box(new double[]{ax - dh, deck, zp1}, new double[]{ax + dh, plenumTop, zg0}));
        }
        double zg1 = zg0 + length, zf0 = zg1 - .45 * length;
        if (heavy) {
            var along = Frame.of(new double[]{ax, ys, 0}, 2, 1);
            int n = ProcessUnit.sides(2 * rc);
            double zt0 = zg0 + .22 * length, zt1 = zg0 + .38 * length, zc1 = zg0 + .52 * length, zi0 = zg1 - .2 * length;
            // The exhaust diffuser widening forward into the plenum, the turbine, the combustors' section, the narrower compressor.
            u.mesh.frustum(along, 1.15 * rc, rc, zg0, zt0, n, Material.MACHINE);
            u.mesh.prism(along, rc, zt0, zt1, n, Material.MACHINE);
            u.mesh.annulus(along, .9 * rc, rc, zt1, n, 1, Material.MACHINE);
            u.mesh.prism(along, .9 * rc, zt1, zc1, n, Material.MACHINE);
            u.mesh.annulus(along, .75 * rc, .9 * rc, zc1, n, 1, Material.MACHINE);
            u.mesh.frustum(along, .75 * rc, .62 * rc, zc1, zi0, n, Material.MACHINE);
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, zg0, zi0, 1.15 * rc, 0));
            // The split flange along both sides at the shaft, its bolt row on top.
            double th = .08 + .04 * s, e = .1 * rc, stud = .03 + .01 * s;
            double[][] runs = {{zg0, 1.15 * rc, zt0, rc}, {zt0, rc, zt1, rc}, {zt1, .9 * rc, zc1, .9 * rc}, {zc1, .75 * rc, zi0, .62 * rc}};
            for (int side = -1; side <= 1; side += 2)
                for (var run : runs) {
                    SteamTurbineTrain.ledge(u.mesh, ax, side, run[0], run[1], run[2], run[3], ys - th / 2, ys + th / 2, e, true, true, Material.MACHINE);
                    int studs = Math.max(1, (int) Math.floor((run[2] - run[0]) / .35));
                    for (int k = 0; k < studs; k++) {
                        double t = (k + .5) / studs, z = run[0] + (run[2] - run[0]) * t, x = ax + side * (run[1] + (run[3] - run[1]) * t + .55 * e);
                        u.mesh.box(new double[]{x - stud, ys + th / 2, z - stud}, new double[]{x + stud, ys + th / 2 + 2 * stud + .02, z + stud}, Mesh.ALL_FACES & ~4, Material.BOLT);
                    }
                }
            // The ring of combustor cans, angled out towards the compressor (the lowest ones behind the base left out).
            int cans = (int) Math.max(8, Math.min(14, Math.round(6 + 3.7 * s)));
            double zc = zt1 + .25 * (zc1 - zt1), rk = .13 * rc, lk = .55 * rc, beta = Math.toRadians(55);
            for (int k = 0; k < cans; k++) {
                double phi = 2 * Math.PI * (k + .5) / cans, cx = Math.cos(phi), cy = Math.sin(phi);
                if (cy < -.35) continue;
                double[] dir = {cx * Math.sin(beta), cy * Math.sin(beta), Math.cos(beta)}, t = {-cy, cx, 0};
                var can = new Frame(new double[]{ax + .82 * rc * cx, ys + .82 * rc * cy, zc}, dir, t);
                u.mesh.cylinder(can, rk, 0, lk, 8, Material.MACHINE);
                u.mesh.cylinder(can, 1.2 * rk, lk, lk + .06 + .02 * s, 8, Material.FLANGE);
            }
            u.solids.add(Solid.rod(new double[]{ax, ys, 0}, 2, zt1, zc1 + .2 * rc, 1.45 * rc, 0));
            u.box(new double[]{ax - width / 2, deck, zg0}, new double[]{ax + width / 2, ys - .8 * rc, zi0}, Material.STEELWORK, true);
            // The inlet plenum at the compressor's front; the vertical inlet duct with its silencer up to the raised filter house.
            double pt = ys + 1.1 * rc;
            u.box(new double[]{ax - .5 * width, deck, zi0}, new double[]{ax + .5 * width, pt, zg1}, Material.MACHINE, true);
            double dz = .4 * (zg1 - zi0), zd = (zi0 + zg1) / 2, fy0 = pt + Math.max(2, .5 * height), fy1 = fy0 + .7 * height;
            u.box(new double[]{ax - .3 * width, pt, zd - dz}, new double[]{ax + .3 * width, fy0, zd + dz}, Material.TOWER_PANEL, true);
            double sm = (pt + fy0) / 2;
            u.box(new double[]{ax - .3 * width - .08, sm - .4, zd - dz - .08}, new double[]{ax + .3 * width + .08, sm + .4, zd + dz + .08}, Material.MOTOR_PLAIN, false);
            u.box(new double[]{ax - .55 * width, fy0, zf0}, new double[]{ax + .55 * width, fy1, zg1}, Material.TOWER_PANEL, true);
            hoods(u, ax - .5 * width, ax + .5 * width, fy0 + .15, fy1 - .1, zf0, -1);
            for (int side = -1; side <= 1; side += 2) {
                double xp = ax + side * .5 * width;
                u.box(new double[]{xp - .1, deck, zf0 + .05}, new double[]{xp + .1, fy0, zf0 + .25}, Material.STEELWORK, true);
            }
            // The fuel gas module on the right beside the combustors.
            double zn = Math.floor((zg0 + zg1) / 2) + .5;
            u.box(new double[]{ax + rc + .2, ys - .5, zn - .45}, new double[]{ax + width / 2, ys + .5, zn + .45}, Material.MACHINE, true);
        } else {
            // The enclosure, louvre bars over its vents, its roof fans; the filter house on its roof over the compressor end.
            u.box(new double[]{ax - width / 2, deck, zg0}, new double[]{ax + width / 2, top, zg1}, Material.MACHINE, true);
            for (int side = -1; side <= 1; side += 2) {
                double xf = ax + side * width / 2;
                for (double zl = zg0 + .6; zl < zg1 - .6; zl += 1.2)
                    for (int k = 0; k < 4; k++) {
                        double yb = deck + .3 * height + k * .1 * height;
                        u.box(new double[]{Math.min(xf, xf + side * .06), yb, zl - .3}, new double[]{Math.max(xf, xf + side * .06), yb + .04, zl + .3}, Material.MOTOR_PLAIN, false);
                    }
            }
            int fans = length > 7 ? 2 : 1;
            double rf = Math.min(.5, .1 * width + .15);
            for (int k = 0; k < fans; k++) {
                double fz = zg0 + (zf0 - zg0) * (k + 1) / (fans + 1);
                var f = Frame.of(new double[]{ax - .2 * width, 0, fz}, 1, 1);
                int n = ProcessUnit.sides(2 * rf);
                u.mesh.ring(f, rf, rf + .05, top, top + .3, n, 2, Material.AC_RING);
                u.grilledFan(f, rf, top + .3, .2, n, ProcessUnit.FAN_SPEED);
                u.solids.add(Solid.rod(new double[]{ax - .2 * width, 0, fz}, 1, top, top + .3, rf + .05, 0));
            }
            u.box(new double[]{ax - .55 * width, top, zf0}, new double[]{ax + .55 * width, filterTop, zg1}, Material.TOWER_PANEL, true);
            hoods(u, ax - .5 * width, ax + .5 * width, top + .15, filterTop - .1, zf0, -1);
        }
        // The coil's headers across the casing's top, each carrying its nozzle: steam at the hot end, feed water at the cold end.
        if (coil) {
            double steam = designSteam(m);
            double so = steamNozzle(m);
            double wo = PipeSizes.od(Math.sqrt(4 * steam / 1000 / WATER_SPEED / Math.PI), .05, .4);
            double rh = Math.max(Math.max(so, wo) / 2 + .06, .18);
            double cold = Math.ceil(zs + hz + rh + .05 - .5) + .5, hot = Math.floor(zp1 - .6) + .5;
            if (hot - cold < 2) hot = cold + 2;
            for (double zh : new double[]{cold, hot}) {
                u.mesh.cylinder(Frame.of(new double[]{0, plenumTop + rh, zh}, 0, 1), rh, ax - .7 * dh, ax + .7 * dh, ProcessUnit.sides(2 * rh), Material.PIPE);
                u.solids.add(Solid.rod(new double[]{0, plenumTop + rh, zh}, 0, ax - .7 * dh, ax + .7 * dh, rh, 0));
            }
            up(u, Role.STEAM, ax, hot, plenumTop + 2 * rh - .02, so);
            up(u, Role.FEEDWATER, ax, cold, plenumTop + 2 * rh - .02, wo);
        }
        // The fuel connection on the right, near the middle.
        side(u, Role.FUEL, ax + width / 2, ys, Math.floor((zg0 + zg1) / 2) + .5, fuelNozzle(m));
        // The coupling at the back; the base on its plinth.
        // The load shaft turning in its open guard (owner: visible turning parts), then the half coupling under its guard.
        double shaft = ProcessUnit.shaftRadius(m.power() / 1e3, m.speed()), zl = zg1 + .6 + .2 * s, face = Math.ceil(zl + .2 + shaft - 1e-9);
        u.loadShaft(ax, ys, zg1, zl, shaft, deck, ProcessUnit.shown(m.speed()));
        u.coupling(ax, ys, zl, face, 1, shaft, deck, ProcessUnit.shown(m.speed()), EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT);
        u.box(new double[]{ax - half, PLINTH, zp0 - .05}, new double[]{ax + half, deck, face - .2}, Material.STEELWORK, true);
        u.driverBase(ax - half - .1, ax + half + .1, zp0 - .15, face - .1, PLINTH, 0);
    }
}
