package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.Burners;
import com.wormzjl.icheme.science.fluid.network.ExhaustCoil;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.PrimeMover;

import java.util.ArrayList;
import java.util.List;

/**
 * A combustion engine or a gas turbine (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md section 11, owner P14 A): the world's
 * law. The fuel leaves the sheet through the combustor, as a flare's gas does ({@link FlareUnit}); its heat, the flow times its
 * lower heating value ({@link Burners#perKilogram}), gives the shaft power on the model's Willans line ({@link PrimeMover}). Its
 * model ({@code model}: 0 for the smallest whose rated fuel holds the fuel with the 20 % spare, or the model's number) is the
 * world's. The run moves what it is given, so where the built set would act the unit warns: fuel of the wrong phase (the built
 * set shuts its valve), fuel over the rated (the governor holds it back), a supply under the pressure the fuel needs (the built
 * set derates). A gas turbine's free pair is its heat-recovery steam coil: water in, steam out, designed to its pinch as the
 * world's {@link ExhaustCoil}, read at the two knots that can decide: the water's inlet and its bubble point.
 */
public final class PrimeMoverUnit implements UnitModel {
    public static final String FUEL = "fuel", FEEDWATER = "feedwater", STEAM = "steam";
    /** A vapour share under this is a liquid; over one less this, a gas. */
    private static final double TRACE = 1e-6;
    /** The spare the automatic model holds over the fuel. */
    public static final double SPARE = Motor.SPARE;

    private final PrimeMover.Family family;

    public PrimeMoverUnit(PrimeMover.Family family) {
        this.family = family;
    }

    @Override public List<Port> ports(Params params) {
        return family == PrimeMover.Family.ENGINE ? List.of(Port.in(FUEL)) : List.of(Port.in(FUEL), Port.optionalIn(FEEDWATER), Port.out(STEAM));
    }

    /** The kind the family builds. */
    public static EquipmentShape.Kind kind(PrimeMover.Family family) {
        return family == PrimeMover.Family.ENGINE ? EquipmentShape.Kind.COMBUSTION_ENGINE : EquipmentShape.Kind.GAS_TURBINE;
    }

    /** The shape of model {@code model} (1 to the family's count); a gas turbine with its coil where {@code coil}. */
    public static EquipmentShape shape(PrimeMover.Family family, int model, boolean coil) {
        return EquipmentShape.unit(kind(family), model, family == PrimeMover.Family.GAS_TURBINE && coil ? 2 : 1, EquipmentShape.Heading.NORTH, 0);
    }

    /** The family's models, by number. */
    public static int models(PrimeMover.Family family) {
        return PrimeMover.models(family).size();
    }

    /** The smallest model whose rated fuel holds {@code fired} W with the spare; 0 where none does. */
    public static int smallest(PrimeMover.Family family, double fired) {
        var list = PrimeMover.models(family);
        for (int i = 0; i < list.size(); i++) if (list.get(i).ratedFuel() >= SPARE * fired * (1 - 1e-12)) return i + 1;
        return 0;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        boolean turbine = family == PrimeMover.Family.GAS_TURBINE;
        String fuel = u.wire(FUEL), water = turbine ? u.wire(FEEDWATER) : null;
        if (turbine && water == null && u.wire(STEAM) != null) throw new FlowsheetFailure("unit.free_inlet", FEEDWATER);
        var reads = new ArrayList<String>();
        reads.add(fuel);
        if (water != null) reads.add(water);
        return List.of(u.calculation(reads, water != null ? List.of(u.out(STEAM)) : List.of(), (board, report) -> {
            var thermo = u.thermo();
            var in = board.stream(fuel);
            report.leaves(in.mass(), in.enthalpy());
            double perKilogram = in.empty() ? 0 : Burners.perKilogram(in.amountsView(), thermo.model().molecularWeights(), FlareUnit.heatingValues(thermo));
            double offered = in.mass() * perKilogram;
            int asked = (int) Math.round(u.params().number("model", 0));
            int model = asked >= 1 && asked <= models(family) ? asked : smallest(family, offered);
            if (model == 0) {
                model = models(family);
                report.note(Note.warning(u.id(), "prime_mover.capacity"));
            }
            var m = PrimeMover.models(family).get(model - 1);
            var law = new PrimeMover(m);
            double fired = offered;
            if (!in.empty()) {
                double vapour = thermo.vapourShare(in);
                boolean wrong = turbine ? vapour < 1 - TRACE : vapour > TRACE;
                if (wrong) {
                    report.note(Note.warning(u.id(), "prime_mover.wrong_fuel"));
                    fired = 0;
                } else if (!(perKilogram > 0)) report.note(Note.warning(u.id(), "prime_mover.not_combustible"));
                else {
                    if (asked >= 1 && offered > m.ratedFuel() * (1 + 1e-9)) report.note(Note.warning(u.id(), "prime_mover.capacity"));
                    if (law.supplyShare(in.pressure()) < 1) report.note(Note.warning(u.id(), "prime_mover.pressure"));
                }
            }
            double burnt = Math.min(fired, m.ratedFuel()), gross = law.gross(burnt), net = law.net(burnt), exhaust = law.exhaustHeat(burnt, gross), flue = law.flue(exhaust);
            report.reading("power", net);
            report.reading("fired", burnt);
            report.reading("efficiency", burnt > 0 ? net / burnt : 0);
            report.reading("model", model);
            report.reading("exhaust", m.exhaust());
            report.reading("exhaust_flow", law.exhaustFlow(exhaust));
            report.reading("exhaust_heat", exhaust);
            report.reading("required", m.required());
            report.reading("per_kilogram", perKilogram);
            if (!turbine) report.reading("radiator", law.cooling(burnt, gross));
            if (water != null) {
                var w = board.stream(water);
                double heat = coil(thermo, w, m.exhaust(), flue);
                u.write(board, report, STEAM, w.empty() ? w : thermo.heated(w, heat, w.pressure()));
                report.duty(heat);
                report.reading("coil", heat);
                report.reading("stack", ExhaustCoil.stack(m.exhaust(), flue, heat));
            } else report.reading("stack", m.exhaust());
        }));
    }

    /**
     * The heat, W, the coil gives {@code water} from gas at {@code gas} K of capacity rate {@code flue} W/K: its outlet the least of
     * its enthalpy {@link ExhaustCoil#APPROACH} under the gas, and, at its inlet and at its bubble point, the enthalpy that leaves
     * the gas {@link ExhaustCoil#PINCH} over it.
     */
    static double coil(FlowThermo thermo, FlowStream water, double gas, double flue) {
        if (water.empty() || !(flue > 0)) return 0;
        double tin = water.temperature(), top = gas - ExhaustCoil.APPROACH, pressure = water.pressure();
        if (!(tin < gas - ExhaustCoil.PINCH)) return 0;
        double out = Math.min(thermo.atTemperature(top, pressure, water.amountsView(), water.solids()).enthalpy(), water.enthalpy() + flue * (gas - tin - ExhaustCoil.PINCH));
        double bubble = EjectorUnit.saturation(thermo, pressure);
        if (bubble > tin && bubble < top)
            out = Math.min(out, thermo.atTemperature(bubble - 1e-3, pressure, water.amountsView(), water.solids()).enthalpy() + flue * (gas - bubble - ExhaustCoil.PINCH));
        return Math.max(0, out - water.enthalpy());
    }

    /** The model's name a reading {@code model} gives ({@code IND-30}). */
    public static String name(PrimeMover.Family family, int model) {
        return model < 1 ? "" : UnitDesign.primeMoverModel(shape(family, Math.min(model, models(family)), false)).name();
    }
}
