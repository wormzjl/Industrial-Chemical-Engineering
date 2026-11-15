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
 *
 * <p>Its sections (batch 2026-11-15-equipment-visual-design; owner: "HP turbine should include MP and LP section, MP
 * should include LP section", straight through, every size): the turbine named by its inlet class expands through every
 * section from that class down to its exhaust, below the LP header for every new design ({@link MachineDuty#TURBINE_EXHAUSTS}):
 * the front section ({@link #front}: HP for HP or VHP steam, MP, LP), then an MP section where steam above the MP class
 * expands below it, then an LP section where steam above the LP class expands below the LP header ({@link #sections}).
 * Up to one casing's 45 MW they are barrels of the one casing, each as long as its share of the drop ({@link #drops}).
 * Above it (batch 2026-11-10-prime-movers, owner P12) the unit runs to 300 MW, drawn as tandem casings on one shaft, the LP
 * double-flow above {@link #DOUBLE_FLOW}. The physics is the unit's work law, unchanged: the sections are its drawing.
 */
final class SteamTurbineDesign extends UnitDesign {
    /** Rated powers, kW: one casing to 45 MW (5 Dc² MW at 3 m), tandem sections above it to 300 MW (owner P12). */
    static final double MIN_POWER = 55, SINGLE_CASING = 45000, MAX_POWER = MachineDuty.MAX_TURBINE;
    /** An LP section above this power is double-flow, kW: a single flow's last stage passes about 100 MW at 3,000 rpm (game level). */
    static final double DOUBLE_FLOW = 100000;
    /** An LP section's hood across, in Dc: a single flow's, a double flow's (owner review S1: visibly wider than the MP casing). */
    static final double LP_WIDTH = 1.3, LP_DOUBLE_WIDTH = 1.7;
    /** A turbine saved before its design point: its inlet class and its exhaust, kPa (a back-pressure set into the LP header; a condensing set's 10 kPa). */
    static final MachineDuty.Steam DEFAULT_STEAM = MachineDuty.Steam.HP;
    static final double DEFAULT_EXHAUST = 450, CONDENSING_EXHAUST = 10;
    /** A fresh draft's design point in the constructor: HP steam to 10 kPa, a condensing turbine through its HP, MP and LP sections (owner point T2, option A pending the owner's answer). */
    static final MachineDuty.Steam DRAFT_STEAM = MachineDuty.Steam.HP;
    static final double DRAFT_EXHAUST = 10;
    /** A stage's share of the isentropic drop, kJ/kg, and the drop per e-fold of the pressure ratio (a game-level fit of the steam tables). */
    static final double STAGE_DROP = 90, DROP_PER_LOG = 200;

    SteamTurbineDesign() {
        // moves: The coupling's hub turns. Layout revision 2 (batch 2026-11-15-equipment-visual-design, K15): a double-flow
        // LP takes the crossover at its middle, one shell cell more at 300 MW; no nozzle moved.
        super(new Sizes(.3, 3, .1,
                1, 1,
                0, EquipmentShape.MAX_SUPPORT,
                Orientation.HORIZONTAL, "steam_turbine.casing", null, "steam_turbine.table", true,
                2));
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

    /** A fresh draft: 5 Dc² MW, {@link #DRAFT_STEAM} to {@link #DRAFT_EXHAUST}. */
    @Override
    public MachineDuty draft(double size, int length) {
        return MachineDuty.turbine(1000 * SteamTurbineTrain.power(size), DRAFT_STEAM, DRAFT_EXHAUST);
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

    /**
     * Power steps along {@link MachineDuty#POWERS} from 55 kW; the exhaust along {@link MachineDuty#TURBINE_EXHAUSTS} (below
     * the LP header) to half the inlet. A saved back pressure ({@link #checkDuty} still holds it) steps onto that list.
     */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return switch (setting) {
            case "power" -> MachineDuty.turbine(MachineDuty.step(MachineDuty.TURBINE_POWERS, duty.power(), steps),
                    duty.steam(), duty.exhaust());
            case "exhaust" -> MachineDuty.turbine(duty.power(), duty.steam(),
                    MachineDuty.step(MachineDuty.TURBINE_EXHAUSTS.stream().filter(e -> e <= duty.steam().pressure / 2).toList(), duty.exhaust(), steps));
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

    /**
     * A turbine's section: the front one ({@link #front}, the inlet class's) and those behind it ({@link #sections}). Up to
     * one casing's power the sections are barrels of the one casing; above it each behind the front is a casing of its own.
     */
    enum Section {
        HP, MP, LP, LP_DOUBLE;

        boolean lp() {
            return this == LP || this == LP_DOUBLE;
        }

        /** Its casing's length, in Dc (a separate casing, above one casing's power). */
        double length() {
            return switch (this) {
                case HP, MP -> 1;
                case LP -> 1.2;
                case LP_DOUBLE -> 2;
            };
        }

        /** Its casing's top over the shaft, in Dc. */
        double top() {
            return lp() ? width() / 2 : .45;
        }

        /** Its casing's width across, in Dc. */
        double width() {
            return switch (this) {
                case HP, MP -> .9;
                case LP -> LP_WIDTH;
                case LP_DOUBLE -> LP_DOUBLE_WIDTH;
            };
        }
    }

    /** The front section: the inlet class's (VHP steam enters an HP section). */
    static Section front(MachineDuty duty) {
        return switch (duty.steam()) {
            case LP -> Section.LP;
            case MP -> Section.MP;
            case HP, VHP -> Section.HP;
        };
    }

    /**
     * The sections behind the front one, the same rule at every size (owner, 2026-11-15: "HP turbine should include MP and
     * LP section, MP should include LP section", straight through): an MP section where steam above the MP class expands
     * below it; an LP section where steam above the LP class expands below the LP header, double-flow above
     * {@link #DOUBLE_FLOW}. A new design (its exhaust below the LP header) is HP+MP+LP, MP+LP or LP; a turbine saved with a
     * back pressure has fewer by the same rule.
     */
    static List<Section> sections(MachineDuty duty) {
        var out = new java.util.ArrayList<Section>();
        if (duty.steam().pressure > MachineDuty.Steam.MP.pressure && duty.exhaust() < MachineDuty.Steam.MP.pressure) out.add(Section.MP);
        if (duty.steam() != MachineDuty.Steam.LP && duty.exhaust() < MachineDuty.Steam.LP.pressure) out.add(duty.power() > DOUBLE_FLOW ? Section.LP_DOUBLE : Section.LP);
        return List.copyOf(out);
    }

    /**
     * Each section's isentropic drop, kJ/kg, the front first and then {@link #sections} in order: {@link #DROP_PER_LOG}
     * per e-fold of its pressure ratio, the section boundaries at the MP and LP headers (HP steam to 10 kPa: 181, 266, 761).
     */
    static double[] drops(MachineDuty duty) {
        var bounds = new java.util.ArrayList<Double>();
        bounds.add(duty.steam().pressure);
        for (var section : sections(duty))
            bounds.add(section.lp() ? MachineDuty.Steam.LP.pressure : MachineDuty.Steam.MP.pressure);
        bounds.add(duty.exhaust());
        double[] out = new double[bounds.size() - 1];
        for (int k = 0; k < out.length; k++)
            out[k] = DROP_PER_LOG * com.wormzjl.icheme.science.math.DeterministicMath.log(bounds.get(k) / bounds.get(k + 1));
        return out;
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

    /**
     * An LP turbine's inlet and exhaust above one casing (W6; owner: "Top, short neck"), both on the shaft line: the inlet
     * riser at {@code zI} (a cell centre) on a dome on the belt's top (a double flow's hood's middle), a butterfly valve's
     * wafer body and actuator at its middle, its flange and STEAM nozzle up; the exhaust's short neck on the hood's top
     * near its 0.7 point (a cell centre, clear of the cover's inset and, on a double flow, of the inlet's dome), tapering
     * to {@code oe}, its flange and EXHAUST nozzle up.
     */
    private static void lpNozzles(ProcessUnit u, SteamTurbineTrain t, double a, double shaft, double z0, double zI, double oe) {
        var m = u.mesh;
        double d = t.d, tf = .03 + .01 * d, oi = t.inletOutside(), rd = t.domeRadius(), yb = shaft + t.inletTop();
        // The inlet: its dome, the riser with the butterfly valve, the flange and its nuts.
        var in = Frame.of(new double[]{a, 0, zI}, 1, 1);
        m.cylinder(in, rd, yb - .05, yb + .1 * d, ProcessUnit.sides(2 * rd), Material.MACHINE);
        double r0 = yb + .1 * d, r1 = r0 + .35 * d, rm = (r0 + r1) / 2, rw = oi / 2 + .04 + .01 * d;
        m.prism(in, oi / 2, r0, r1, ProcessUnit.sides(oi), Material.PIPE);
        m.cylinder(in, rw, rm - .04 * d, rm + .04 * d, ProcessUnit.sides(2 * rw), Material.MACHINE);
        u.box(new double[]{a + rw, rm - .06 * d, zI - .06 * d}, new double[]{a + rw + .12 * d, rm + .06 * d, zI + .06 * d}, Material.ACTUATOR, false);
        double rfi = PipeSizes.flange(oi) / 2, topI = r1 + tf;
        m.cylinder(in, rfi, r1, topI, ProcessUnit.sides(2 * rfi), Material.FLANGE);
        m.bolts(in, oi, oi / 2 + .03, rfi, topI, 1, Material.BOLT);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zI}, 1, yb - .05, topI, Math.max(rd, rfi), 0));
        u.nozzles.add(new Nozzle(Role.STEAM, new double[]{a, topI - .01, zI}, 1, 1, Math.ceil(topI + ProcessUnit.NECK) - topI + .01, oi, 0));
        u.nozzleRoots.put(u.nozzles.size() - 1, new ProcessUnit.NozzleRoot(oi / 2, .4, .03, Material.PIPE));
        // The exhaust: a short neck on the hood's top, its flange and nuts.
        double z1 = z0 + t.hoodStart(), z2 = z0 + t.exhaustEnd, rn = Math.min(.3 * d, 1.6 * oe / 2), zX = Math.floor(z1 + .7 * (z2 - z1)) + .5;
        if (zX + rn > z2 - .06 * d) zX -= 1;
        if (t.doubleFlowHood()) while (zX < zI + rd + rn + .1 * d) zX += 1;
        double yh = shaft + t.hood, rfe = PipeSizes.flange(oe) / 2, topX = yh + .15 * d + tf;
        var out = Frame.of(new double[]{a, 0, zX}, 1, 1);
        m.frustum(out, rn, oe / 2, yh - .05, yh + .15 * d, ProcessUnit.sides(2 * rn), Material.PIPE);
        m.cylinder(out, rfe, yh + .15 * d, topX, ProcessUnit.sides(2 * rfe), Material.FLANGE);
        m.bolts(out, oe, oe / 2 + .03, rfe, topX, 1, Material.BOLT);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zX}, 1, yh - .05, topX, Math.max(rn, rfe), 0));
        u.nozzles.add(new Nozzle(Role.EXHAUST, new double[]{a, topX - .01, zX}, 1, 1, Math.ceil(topX + ProcessUnit.NECK) - topX + .01, oe, 0));
        u.nozzleRoots.put(u.nozzles.size() - 1, new ProcessUnit.NozzleRoot(oe / 2, .4, .03, Material.PIPE));
    }

    @Override
    void build(ProcessUnit u) {
        double d = u.shape.diameter();
        var duty = u.shape.duty();
        int h = u.shape.support();
        double plinth = .25 + .1 * d, frame = Math.min(.45, .15 + .1 * d), floor = h == 0 ? plinth : h, base = floor + frame;
        // The shaft on the lowest cell centre 0.6 Dc over the base frame (0.55 Dc with a vacuum exhaust, its lower half over the frame).
        double shaft = Math.ceil(base + (duty.vacuum() ? .55 : .6) * d - .5) + .5;
        // Up to one casing's power every section is a barrel of the one casing, each as long as its share of the drop;
        // above it the sections behind the front one are casings of their own, and the front casing is drawn whole.
        var sections = duty.power() > SINGLE_CASING ? sections(duty) : List.<Section>of();
        // An LP turbine above one casing is its LP hood alone behind the front standard (owner: "LP=LP").
        double hood = duty.steam() == MachineDuty.Steam.LP && duty.power() > SINGLE_CASING ? (duty.power() > DOUBLE_FLOW ? LP_DOUBLE_WIDTH : LP_WIDTH) * d / 2 : 0;
        var t = new SteamTurbineTrain(d, stages(duty), duty.vacuum(), shaft - base, sections.isEmpty() && hood == 0 ? drops(duty) : new double[]{1}, hood);
        var m = u.mesh;
        double rb = t.valveBody(), od = t.steamLine(), rpm = SteamTurbineTrain.speed(d), rs = ProcessUnit.shaftRadius(duty.power(), rpm);
        // The base frame as wide as the casing, the plinth 0.12 m wider.
        double wb = Math.max(.5 * d + .15, hood + .1), wp = wb + .12;
        // Across x: the shaft on a cell centre, the plinth's and the turbine's left side in the first cell; the
        // trip-and-throttle valve's handwheel out of the exhaust's column of cells (a small turbine's exhaust rises past it).
        double lpHalf = Math.max(hood, sections.stream().filter(Section::lp).mapToDouble(Section::width).max().orElse(0) * d / 2);
        double a = Math.ceil(Math.max(Math.max(wp + .02, t.leftReach()), lpHalf + .02) - .5) + .5, xv = a + Math.max(t.valveOffset(), .52 + t.handwheel() / 2);
        // Along z: the exhaust's centre line on a cell centre, the plinth's front in the first cell; the coupling face on
        // the block face behind the rear pedestal.
        double lead = Math.max(.3, t.frontReach());
        // An LP turbine above one casing (W6): its inlet riser on a cell centre instead, on the shaft line.
        double zI = Math.ceil(lead + t.inletStation() - .5 - 1e-9) + .5;
        double zE = Math.ceil(lead + t.exhaust - .5 - 1e-9) + .5, z0 = hood > 0 ? zI - t.inletStation() : zE - t.exhaust;
        // Behind the front casing, the separate casings and their pedestals (none to one casing's power); the coupling behind the last.
        double back = z0 + t.length();
        double[] start = new double[sections.size()];
        for (int k = 0; k < sections.size(); k++) {
            start[k] = back + .1 * d;
            back = start[k] + sections.get(k).length() * d + .08 * d + .03 + .25 * d + .05;
        }
        double face = Math.ceil(back + .2 + rs - 1e-9);
        // The trip-and-throttle valve's inlet on the cell row nearest the chest's axis that its stand reaches; its stand
        // on the base frame, the plinth or table, or the ground.
        double stand = xv < a + wb ? base : xv < a + wp ? floor : 0, yc = base + t.chestAxis();
        double yv = Math.max(Math.floor(yc) + .5, Math.ceil(stand + rb + .12 - .5) + .5), zv = Math.floor(z0 + t.chest) + .5;
        t.draw(u, a, base, z0, xv, yv, zv);
        t.couple(u, a, base, z0, face, rs, rpm);
        if (hood == 0) {
            u.post(xv, zv, stand, yv - rb, .1, false);
            u.box(new double[]{xv - .1, stand, zv - .1}, new double[]{xv + .1, stand + .02, zv + .1}, Material.STEELWORK, false);
        }

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
        if (hood > 0) {
            lpNozzles(u, t, a, shaft, z0, zI, oe);
            return;
        }
        var up = Frame.of(new double[]{a, 0, zE}, 1, 1);
        int ne = ProcessUnit.sides(2 * rbr);
        double y1 = shaft + Math.max(d / 2, t.hood) + .06 * d + .04, joint = Math.max(.15, .15 * d), y2 = y1 + joint, rr = rbr + .03 + .02 * d, tf = .03 + .01 * d;
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
        if (sections.isEmpty()) {
            u.nozzles.add(new Nozzle(Role.EXHAUST, new double[]{a, top - .01, zE}, 1, 1, Math.ceil(top + ProcessUnit.NECK) - top + .01, oe, 0));
            u.nozzleRoots.put(u.nozzles.size() - 1, new ProcessUnit.NozzleRoot(oe / 2, .4, .03, Material.PIPE));
            return;
        }
        // The tandem casings (owner P12; batch 2026-11-15-equipment-visual-design, K15): each split along the shaft line by its
        // joint flange, the MP casing widening to its exhaust, the LP hood (double-flow above 100 MW), each with its pedestal
        // behind it; the crossover in legs, steam in series: from the front casing's exhaust branch along the top into the
        // next casing's inlet, from an MP casing's exhaust at its back end into the LP's inlet (a double flow's at its
        // middle); the exhaust up from the last casing's back end.
        double rc = oe / 2, yo = top + rc + .05, th = Math.max(.05, .1 * d);
        int no = ProcessUnit.sides(oe);
        m.prism(up, rc, top, yo + rc, no, Material.PIPE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zE}, 1, top, yo + rc, rc, 0));
        var along = Frame.of(new double[]{a, yo, 0}, 2, 1);
        var axis = Frame.of(new double[]{a, shaft, 0}, 2, 1);
        double from = zE;
        for (int k = 0; k < sections.size(); k++) {
            var section = sections.get(k);
            double z1 = start[k], z2 = z1 + section.length() * d, casingTop = shaft + section.top() * d;
            double zin = section == Section.LP_DOUBLE ? (z1 + z2) / 2 : z1 + section.length() * d / 3, inletTop = casingTop;
            if (section.lp()) SteamTurbineTrain.hood(u, a, base, shaft, z1, z2, section.width() * d / 2, section == Section.LP_DOUBLE, d);
            else {
                // The MP casing: a cone from 0.8 of its exhaust end's radius, clad, its end walls; the support under it.
                double r = section.top() * d, r0 = .8 * r;
                int n = ProcessUnit.sides(2 * r);
                m.annulus(axis, 0, r0, z1, n, -1, Material.CLADDING);
                m.frustum(axis, r0, r, z1, z2, n, Material.CLADDING);
                m.annulus(axis, 0, r, z2, n, 1, Material.CLADDING);
                for (int side = -1; side <= 1; side += 2)
                    ProcessUnit.ledge(m, a, side, z1, r0, z2, r, shaft - th / 2, shaft + th / 2, .05 * d, true, true, Material.CLADDING);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{a, shaft, 0}, 2, z1, z2, r, 0));
                u.box(new double[]{a - .3 * d, base, z1 + .1 * d}, new double[]{a + .3 * d, shaft, z2 - .1 * d}, Material.MACHINE, true);
                inletTop = shaft + r0 + (r - r0) * (zin - z1) / (z2 - z1);
                if (k < sections.size() - 1) {
                    // Its exhaust up at its back end into the next leg.
                    double zr = z2 - .25 * d, rTop = shaft + r0 + (r - r0) * (zr - z1) / (z2 - z1);
                    var riser = Frame.of(new double[]{a, 0, zr}, 1, 1);
                    m.prism(riser, rc, rTop - .02, yo, no, Material.PIPE);
                    u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zr}, 1, rTop - .02, yo, rc, 0));
                }
            }
            SteamTurbineTrain.pedestal(u, a, base, shaft, z2 + .08 * d + .03, z2 + .33 * d + .08, d);
            // The leg into its inlet: along the top from where the steam rose, down into its inlet.
            var drop = Frame.of(new double[]{a, 0, zin}, 1, 1);
            m.prism(drop, rc, inletTop - .02, yo, no, Material.PIPE);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zin}, 1, Math.min(inletTop, casingTop) - .05, yo, rc, 0));
            m.prism(along, rc, from - rc, zin + rc, no, Material.PIPE);
            u.solids.add(ProcessUnit.Solid.rod(new double[]{a, yo, 0}, 2, from - rc, zin + rc, rc, 0));
            if (k < sections.size() - 1 && !section.lp()) from = z2 - .25 * d;
        }
        // The exhaust up from the last casing's back end, on a cell centre.
        var end = sections.getLast();
        double zLast = start[sections.size() - 1] + end.length() * d, zx = Math.floor(zLast - .3) + .5, yx = shaft + end.top() * d, rx = PipeSizes.flange(oe) / 2;
        var rise = Frame.of(new double[]{a, 0, zx}, 1, 1);
        // A short tapered neck (W6), its solid, flange and nozzle as before; its base no wider than the cell columns the
        // casing's crest reaches in that row, less 0.05 m for the facets inside the round (a unit's cells are those its facets reach).
        double crest = Math.floor(yx) > shaft ? Math.sqrt(Math.max(0, end.top() * d * end.top() * d - (Math.floor(yx) - shaft) * (Math.floor(yx) - shaft))) - .05 : end.top() * d;
        double neck = Math.max(rc, Math.min(1.6 * rc, Math.min(a - Math.floor(a - crest), Math.floor(a + crest) + 1 - a) - 1e-3));
        m.frustum(rise, neck, rc, yx - .05, yx + .15, ProcessUnit.sides(2 * neck), Material.PIPE);
        m.cylinder(rise, rx, yx + .15, yx + .15 + tf, ProcessUnit.sides(2 * rx), Material.FLANGE);
        double xTop = yx + .15 + tf;
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, zx}, 1, yx - .1, xTop, rx, 0));
        u.nozzles.add(new Nozzle(Role.EXHAUST, new double[]{a, xTop - .01, zx}, 1, 1, Math.ceil(xTop + ProcessUnit.NECK) - xTop + .01, oe, 0));
        u.nozzleRoots.put(u.nozzles.size() - 1, new ProcessUnit.NozzleRoot(oe / 2, .4, .03, Material.PIPE));
    }
}
