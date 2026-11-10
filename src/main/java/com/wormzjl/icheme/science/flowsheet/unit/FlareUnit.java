package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.Flare;
import com.wormzjl.icheme.science.material.MaterialCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * A flare stack (batch 2026-11-09-flare-stack, W4): the stream leaves the sheet through its flame, as a product does. Its door
 * sets the tip; the run reads the heat it releases, the gas's heating value and flame temperature, and the most the tip passes
 * of that gas ({@link Flare#gas}), as the built flare does. The run moves what it is given, so where the built flare would act
 * the unit warns: a flame that does not hold (FLAME OUT), a flow over the most the tip passes (the built tip holds it back),
 * solids in the gas (the built tip closes).
 */
public final class FlareUnit implements UnitModel {
    /** A dropped flare's tip, m. */
    public static final double TIP = .3;

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        double tip = Math.clamp(u.params().number("tip", TIP), Flare.MIN_TIP, Flare.MAX_TIP);
        return List.of(u.calculation(List.of(u.wire("in")), List.of(), (board, report) -> {
            var in = board.stream(u.wire("in"));
            report.leaves(in.mass(), in.enthalpy());
            var gas = Flare.gas(in.amountsView(), u.thermo().model().molecularWeights(), heatingValues(u.thermo()), formulas(u.thermo()), in.temperature(), tip);
            boolean burns = gas.burns() && !in.empty();
            report.reading("heat", burns ? in.mass() * gas.perKilogram() : 0);
            report.reading("heating_value", gas.nhv());
            report.reading("flame", gas.flame());
            report.reading("limit", gas.limit());
            report.reading("rated", Flare.rated(tip));
            // What the sizing reads to rate another tip on the same gas.
            report.reading("per_kilogram", gas.perKilogram());
            report.reading("density", gas.density());
            if (in.empty()) return;
            if (!in.solids().empty()) report.note(Note.warning(u.id(), "flare.blocked"));
            else if (!burns) report.note(Note.warning(u.id(), "flare.flame_out", String.valueOf(gas.flame()), String.valueOf(Flare.FLAME_LIMIT)));
            else if (in.mass() > gas.limit()) report.note(Note.warning(u.id(), "flare.over_capacity", String.valueOf(in.mass()), String.valueOf(gas.limit())));
        }));
    }

    /** The sheet package's lower heating values, J/mol, on the run's axis (water last). */
    static double[] heatingValues(FlowThermo thermo) {
        var values = thermo.catalog().heatingValues(thermo.packageId());
        var names = names(thermo);
        var axis = thermo.axis();
        var out = new double[axis.size()];
        for (int c = 0; c < out.length; c++) {
            int i = names.indexOf(axis.get(c));
            out[c] = i < 0 ? 0 : values[i];
        }
        return out;
    }

    /** The sheet package's formulas on the run's axis: atoms of C, H, N, O, S per molecule. */
    private static double[][] formulas(FlowThermo thermo) {
        var values = thermo.catalog().formulas(thermo.packageId());
        var names = names(thermo);
        var axis = thermo.axis();
        var out = new double[axis.size()][];
        for (int c = 0; c < out.length; c++) {
            int i = names.indexOf(axis.get(c));
            out[c] = i < 0 ? new double[MaterialCatalog.FORMULA_ELEMENTS.size()] : values[i];
        }
        return out;
    }

    private static List<String> names(FlowThermo thermo) {
        var names = new ArrayList<>(thermo.catalog().requirePackage(thermo.packageId()).components());
        names.add("Water");
        return names;
    }
}
