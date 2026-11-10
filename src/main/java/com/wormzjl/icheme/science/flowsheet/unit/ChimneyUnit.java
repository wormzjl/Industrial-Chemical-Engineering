package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.Chimney;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;

import java.util.List;
import java.util.Locale;

/**
 * A chimney (batch 2026-11-10-chimney, plan 3.10): the stream leaves the sheet to the atmosphere through its stack, as a product
 * does. Its door sets the form (steel or brick), the exit diameter, the height and the nearby structure's height. The run
 * reads what the built chimney's stack does with the gas at its design point: the exit velocity, the draft of its gas column
 * ({@link Chimney#draft}), the stack's friction and its entry and exit losses, and so the pressure its inlet needs over the
 * atmosphere at its foot; the solids it releases and the smoke's opacity ({@link Chimney#opacity}). Where the built chimney's
 * status would say so, the unit warns: NO DRAFT (the stream stands below what the inlet needs), WET STACK, ABOVE RATING, HOT
 * STACK, and a stack lower than good engineering practice gives the nearby structure.
 */
public final class ChimneyUnit implements UnitModel {
    /** A dropped chimney: a steel stack 1 m across, 38 m high beside a 15 m building. */
    public static final double DIAMETER = 1, NEARBY = 15;
    public static final int HEIGHT = 38;
    /** The gas's viscosity in the stack's friction, Pa·s: flue gas near 450 K. */
    static final double VISCOSITY = 2.5e-5;

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"));
    }

    /** The form a door names: {@code "brick"} or steel. */
    public static Chimney.Form form(Params params) {
        return "brick".equals(params.text("form", "steel").toLowerCase(Locale.ROOT)) ? Chimney.Form.BRICK : Chimney.Form.STEEL;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var form = form(u.params());
        double d = Math.clamp(u.params().number("diameter", Math.max(form.minDiameter, DIAMETER)), form.minDiameter, form.maxDiameter);
        double height = Math.clamp(u.params().number("height", HEIGHT), form.minHeight, form.maxHeight), nearby = Math.max(0, u.params().number("nearby", NEARBY));
        return List.of(u.calculation(List.of(u.wire("in")), List.of(), (board, report) -> {
            var in = board.stream(u.wire("in"));
            report.leaves(in.mass(), in.enthalpy());
            var state = in.state();
            boolean wet = state != null && state.liquidVolume() + state.waterVolume() > 1e-6 * Math.max(state.volume(), 1e-12);
            double solids = in.solids().massKg(), tip = PhaseLayout.atmosphere(PhaseLayout.SEA_LEVEL + height), foot = PhaseLayout.AMBIENT_PRESSURE;
            var gas = Chimney.reading(in.amountsView(), u.thermo().model().molecularWeights(), in.temperature(), Math.max(0, in.mass() - solids), solids, wet, d, tip);
            double rho = gas.density(), velocity = gas.exitVelocity(), draft = Chimney.draft(rho, height);
            double friction = rho > 0 ? PipeResistance.pressureDrop(new PipeResistance.Geometry(height, d, PipeResistance.DEFAULT_ROUGHNESS_METRES, Chimney.STACK_LOSS), gas.massFlow(), rho, VISCOSITY) : 0;
            report.reading("exit_velocity", velocity);
            report.reading("draft", draft);
            report.reading("losses", friction);
            report.reading("inlet", foot + friction - draft);
            report.reading("solids", solids);
            report.reading("opacity", gas.opacity());
            report.reading("rated", Chimney.ratedVolumeFlow(d));
            report.reading("gep", Chimney.gepHeight(nearby));
            // What the sizing reads to rate another stack on the same gas.
            report.reading("volume_flow", gas.volumeFlow());
            report.reading("density", rho);
            if (in.empty()) return;
            if (in.pressure() < foot + friction - draft) report.note(Note.warning(u.id(), "chimney.no_draft", String.valueOf(in.pressure()), String.valueOf(foot + friction - draft)));
            if (wet) report.note(Note.warning(u.id(), "chimney.wet_stack"));
            if (velocity > Chimney.HIGH_VELOCITY) report.note(Note.warning(u.id(), "chimney.above_rating", String.format(Locale.ROOT, "%.1f", velocity), String.format(Locale.ROOT, "%.0f", Chimney.HIGH_VELOCITY)));
            if (in.temperature() > form.hotLimit) report.note(Note.warning(u.id(), "chimney.hot_stack", String.valueOf(in.temperature()), String.valueOf(form.hotLimit)));
            if (height < Chimney.gepHeight(nearby)) report.note(Note.warning(u.id(), "chimney.low", String.format(Locale.ROOT, "%.0f", height), String.valueOf(Chimney.gepHeight(nearby))));
        }));
    }
}
