package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

import java.util.List;

/**
 * A steam turbine for mechanical drive, the one turbine kind (owner decision D5, batch 2026-11-03-rotating-machinery,
 * W4; {@code NEW_UNITS_SURVEY_ROTATING.md} section 2): its design point is its rated power, its inlet steam class and
 * its exhaust pressure (back pressure into a header, or vacuum to a condenser the player builds from an exchanger). Its
 * exhaust-end casing Dc follows the power, about 5 Dc² MW (0.3 to 3 m in 0.1 m steps); its stages the isentropic drop
 * between inlet and exhaust ({@link #stages}). The turbine ({@link SteamTurbineTrain}) stands on a steel base frame,
 * 0.15 to 0.45 m deep, on a chamfered concrete plinth of 0.28 to 0.55 m at grade, or on a concrete table {@code support}
 * metres up (to {@link EquipmentShape#MAX_SUPPORT}, the decks old condensing turbines stood on) so its shaft lines up
 * with a machine on a deck; its shaft 0.6 Dc (vacuum 0.45 Dc) over the frame, on a cell
 * centre. Its shaft end behind the rear pedestal drives the machine joined there by a shaft block. The exhaust rises
 * from the exhaust barrel's top through a flanged expansion joint of 0.15 Dc (0.15 m at least) to its flange, 0.4 Dc
 * across (less on a casing too short for it), a concentric reducer taking a larger one to a pipe's largest. Steam enters
 * the trip-and-throttle valve from the side (+x), on the cell row nearest the steam chest's axis that its stand reaches.
 * Nozzles: STEAM into the trip-and-throttle valve (0.15 Dc, 0.05 m at least) and EXHAUST up. The back-pressure and
 * condensing generator sets saved before rebuild as this kind (their moved nozzles approved by the owner).
 */
final class SteamTurbineDesign extends UnitDesign {
    /** Rated powers, kW (5 Dc² MW reaches 45 MW at 3 m). */
    static final double MIN_POWER = 55, MAX_POWER = 45000;
    /** A turbine saved before its design point: its inlet class and its exhaust, kPa (a back-pressure set into the LP header; a condensing set's 10 kPa). */
    static final MachineDuty.Steam DEFAULT_STEAM = MachineDuty.Steam.HP;
    static final double DEFAULT_EXHAUST = 450, CONDENSING_EXHAUST = 10;
    /** A stage's share of the isentropic drop, kJ/kg, and the drop per e-fold of the pressure ratio (a game-level fit of the steam tables). */
    static final double STAGE_DROP = 90, DROP_PER_LOG = 200;

    @Override
    public double minSize() {
        return .3;
    }

    @Override
    public double maxSize() {
        return 3;
    }

    @Override
    public double sizeStep() {
        return .1;
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    /** The coupling's hub turns. */
    @Override
    public boolean moves() {
        return true;
    }

    @Override
    public boolean hasDuty() {
        return true;
    }

    /** A turbine saved before its design point: 5 Dc² MW, HP steam, into the LP header. */
    @Override
    public MachineDuty duty(double size, int length) {
        return MachineDuty.turbine(1000 * SteamTurbineTrain.power(size), DEFAULT_STEAM, DEFAULT_EXHAUST);
    }

    /** The exhaust-end casing: the root of the power over 5 MW, up onto the 0.1 m grid. */
    @Override
    public double size(MachineDuty duty) {
        return upTo(Math.sqrt(duty.power() / 1000 / 5), sizeStep(), minSize(), maxSize());
    }

    @Override
    void checkDuty(MachineDuty duty) {
        if (!(duty.power() >= MIN_POWER && duty.power() <= MAX_POWER))
            throw new IllegalArgumentException("A steam turbine's rated power is " + whole(MIN_POWER) + " to " + whole(MAX_POWER) + " kW, not " + duty.power());
        if (duty.steam() == null) throw new IllegalArgumentException("A steam turbine takes an inlet steam class");
        if (!(duty.exhaust() >= MachineDuty.EXHAUSTS.getFirst() && duty.exhaust() <= duty.steam().pressure / 2))
            throw new IllegalArgumentException("A steam turbine's exhaust is " + whole(MachineDuty.EXHAUSTS.getFirst()) + " kPa to half its inlet, not " + duty.exhaust());
        if (duty.flow() != 0 || duty.head() != 0 || duty.polytropicHead() != 0 || duty.poles() != 0 || duty.drive() != MachineDuty.Drive.FIXED)
            throw new IllegalArgumentException("A steam turbine's design point is its power, inlet steam and exhaust");
    }

    /** Power steps along {@link MachineDuty#POWERS} from 55 kW; the exhaust along {@link MachineDuty#EXHAUSTS} to half the inlet. */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return switch (setting) {
            case "power" -> MachineDuty.turbine(MachineDuty.step(MachineDuty.POWERS.stream().filter(p -> p >= MIN_POWER && p <= MAX_POWER).toList(), duty.power(), steps),
                    duty.steam(), duty.exhaust());
            case "exhaust" -> MachineDuty.turbine(duty.power(), duty.steam(),
                    MachineDuty.step(MachineDuty.EXHAUSTS.stream().filter(e -> e <= duty.steam().pressure / 2).toList(), duty.exhaust(), steps));
            default -> duty;
        };
    }

    /** The isentropic drop, kJ/kg: {@link #DROP_PER_LOG} per e-fold of the pressure ratio (HP to 4.5 bar 450, to 0.1 bar 1,200). */
    static double drop(MachineDuty duty) {
        return DROP_PER_LOG * com.wormzjl.icheme.science.math.DeterministicMath.log(duty.steam().pressure / duty.exhaust());
    }

    /** The stages: the drop at {@link #STAGE_DROP} each, 1 (an API 611 single-stage look) to 12. */
    static int stages(MachineDuty duty) {
        return (int) Math.max(1, Math.min(12, Math.round(drop(duty) / STAGE_DROP)));
    }

    @Override
    public String sizeRow() {
        return "steam_turbine.casing";
    }

    @Override
    public String supportRow() {
        return "steam_turbine.table";
    }

    @Override
    public Line held(EquipmentShape shape) {
        var duty = shape.duty();
        return new Line("steam_turbine.power", whole(duty.power()), whole(duty.steam().pressure), whole(duty.exhaust()));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        var duty = shape.duty();
        return List.of(new Line("steam_turbine.casing", stages(duty), size(shape.diameter())),
                new Line("steam_turbine.power", whole(duty.power()), whole(SteamTurbineTrain.speed(shape.diameter()))),
                new Line("steam_turbine.steam", whole(duty.steam().pressure), whole(duty.steam().temperature - 273.15), whole(duty.exhaust())));
    }

    @Override
    void build(ProcessUnit u) {
        double d = u.shape.diameter();
        var duty = u.shape.duty();
        int h = u.shape.support();
        double plinth = .25 + .1 * d, frame = Math.min(.45, .15 + .1 * d), floor = h == 0 ? plinth : h, base = floor + frame;
        // The shaft on the lowest cell centre 0.6 Dc over the base frame (0.55 Dc with a vacuum exhaust, its lower half over the frame).
        double shaft = Math.ceil(base + (duty.vacuum() ? .55 : .6) * d - .5) + .5;
        var t = new SteamTurbineTrain(d, stages(duty), duty.vacuum(), shaft - base);
        var m = u.mesh;
        double rb = t.valveBody(), od = t.steamLine(), rpm = SteamTurbineTrain.speed(d), rs = ProcessUnit.shaftRadius(duty.power(), rpm);
        // The base frame as wide as the casing, the plinth 0.12 m wider.
        double wb = .5 * d + .15, wp = wb + .12;
        // Across x: the shaft on a cell centre, the plinth's and the turbine's left side in the first cell; the
        // trip-and-throttle valve's handwheel out of the exhaust's column of cells (a small turbine's exhaust rises past it).
        double a = Math.ceil(Math.max(wp + .02, t.leftReach()) - .5) + .5, xv = a + Math.max(t.valveOffset(), .52 + t.handwheel() / 2);
        // Along z: the exhaust's centre line on a cell centre, the plinth's front in the first cell; the coupling face on
        // the block face behind the rear pedestal.
        double lead = Math.max(.3, t.frontReach());
        double zE = Math.ceil(lead + t.exhaust - .5 - 1e-9) + .5, z0 = zE - t.exhaust, face = Math.ceil(z0 + t.length() + .2 + rs - 1e-9);
        // The trip-and-throttle valve's inlet on the cell row nearest the chest's axis that its stand reaches; its stand
        // on the base frame, the plinth or table, or the ground.
        double stand = xv < a + wb ? base : xv < a + wp ? floor : 0, yc = base + t.chestAxis();
        double yv = Math.max(Math.floor(yc) + .5, Math.ceil(stand + rb + .12 - .5) + .5), zv = Math.floor(z0 + t.chest) + .5;
        t.draw(u, a, base, z0, xv, yv, zv);
        t.couple(u, a, base, z0, face, rs, rpm);
        u.post(xv, zv, stand, yv - rb, .1, false);
        u.box(new double[]{xv - .1, stand, zv - .1}, new double[]{xv + .1, stand + .02, zv + .1}, Material.STEELWORK, false);

        // The base frame with its drip rim, on the plinth or the table.
        double zf0 = z0 - .15, zf1 = face - .2;
        u.box(new double[]{a - wb, floor, zf0}, new double[]{a + wb, base, zf1}, Material.STEELWORK, true);
        for (int side = -1; side <= 1; side += 2) {
            double x0 = side < 0 ? a - wb : a + wb - .03, ze = side < 0 ? zf0 : zf1 - .03;
            u.box(new double[]{x0, base, zf0}, new double[]{x0 + .03, base + .05, zf1}, Material.STEELWORK, false);
            u.box(new double[]{a - wb + .03, base, ze}, new double[]{a + wb - .03, base + .05, ze + .03}, Material.STEELWORK, false);
        }
        u.driverBase(a - wp, a + wp, zf0 - .12, zf1 + .12, plinth, h);

        // The exhaust: a branch up from the barrel's top, its flange pair, the expansion joint's convolutions, a reducer
        // to a nozzle of a pipe's largest where the branch is larger, the exhaust flange and its nuts.
        double len = t.exhaustEnd - t.coneEnd, rbr = Math.min(.2 * d, .5 * len - .02 * d), oe = Math.min(PipeSizes.MAX_OUTSIDE, 2 * rbr);
        var up = Frame.of(new double[]{a, 0, zE}, 1, 1);
        int ne = ProcessUnit.sides(2 * rbr);
        double y1 = shaft + d / 2 + .06 * d + .04, joint = Math.max(.15, .15 * d), y2 = y1 + joint, rr = rbr + .03 + .02 * d, tf = .03 + .01 * d;
        m.prism(up, rbr, shaft + .3 * d, y1 - tf, ne, Material.MACHINE);
        m.ring(up, rbr, rr, y1 - tf, y1, ne, 3, Material.FLANGE);
        m.prism(up, rbr + .01, y1, y2, ne, Material.PIPE);
        int folds = Math.max(3, (int) Math.round(joint / .07));
        for (int k = 0; k < folds; k++) {
            double y = y1 + joint * (k + .5) / folds, w = .015 + .005 * d;
            m.hoop(up, rbr + .01, rbr + .03 + .015 * d, y - w, y + w, ne, Material.PIPE);
        }
        m.ring(up, rbr, rr, y2, y2 + tf, ne, 3, Material.FLANGE);
        double y3 = y2 + tf, step = 2 * rbr > oe + 1e-9 ? .2 * d : .05 + .03 * d;
        if (2 * rbr > oe + 1e-9) m.frustum(up, rbr, oe / 2, y3, y3 + step, ne, Material.PIPE);
        else m.prism(up, rbr, y3, y3 + step, ne, Material.PIPE);
        y3 += step;
        double rf = PipeSizes.flange(oe) / 2, top = y3 + tf;
        m.cylinder(up, rf, y3, top, ProcessUnit.sides(2 * rf), Material.FLANGE);
        m.bolts(up, oe, oe / 2 + .03, rf, top, 1, Material.BOLT);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zE}, 1, shaft + .3 * d, top, rr, 0));
        u.round(a, zE, y1, y2, rbr + .01, 1);

        // Steam into the valve from the side, its pipe block two columns at least from the exhaust's; the exhaust up.
        double inlet = xv + rb + .08, steamFace = Math.max(Math.ceil(inlet + ProcessUnit.NECK), a + 1.5);
        u.nozzles.add(new Nozzle(Role.STEAM, new double[]{inlet - .01, yv, zv}, 0, 1, steamFace - inlet + .01, od, 0));
        // A reducer from the fixed valve flange to the connected pipe. On a short inlet it replaces most of the
        // straight hub, retaining the flange face and 30 mm of neck rather than cutting a large pipe off at the valve.
        u.nozzleRoots.put(u.nozzles.size() - 1, new ProcessUnit.NozzleRoot(od / 2, .4, .03, Material.PIPE));
        u.nozzles.add(new Nozzle(Role.EXHAUST, new double[]{a, top - .01, zE}, 1, 1, Math.ceil(top + ProcessUnit.NECK) - top + .01, oe, 0));
        u.nozzleRoots.put(u.nozzles.size() - 1, new ProcessUnit.NozzleRoot(oe / 2, .4, .03, Material.PIPE));
    }
}
