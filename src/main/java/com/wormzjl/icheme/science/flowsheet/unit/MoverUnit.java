package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.InletPhase;

import java.util.List;

/**
 * A pump or a compressor (batch 2026-11-02-process-simulator, plan section 3.2): the world's own closures. Target Outlet or Rise.
 * The shaft work is ideal and booked as heat into the discharge, and each refuses what the world's device refuses. The sheet
 * knows no heights, so a pump on a liquid at its bubble point warns; the sizing stage clears that with the nozzle's height.
 */
public final class MoverUnit implements UnitModel {
    /** Standard gravity (m/s²), for a head in metres. */
    private static final double GRAVITY = 9.80665;
    /** A liquid that boils this share under its pressure stands at its bubble point. */
    private static final double AT_BUBBLE = 1e-3;
    private final FlowControl.Mover closure;

    /** @param closure the world's device, whose power and refusal read neither of its settings */
    public MoverUnit(FlowControl.Mover closure) {
        this.closure = closure;
    }

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            var thermo = u.thermo();
            double outlet = u.params().text("target", "outlet").equals("rise") ? in.pressure() + u.params().number("rise") : u.params().number("pressure");
            double rise = outlet - in.pressure();
            if (!(rise > 0)) throw new FlowsheetFailure("mover.rise");
            if (closure.refuses(InletPhase.bulk(thermo.model(), in.state()), false)) throw new FlowsheetFailure(closure.deviceName() + ".phase");
            double density = in.mass() / in.state().volume(), power = closure.power(in.mass(), rise, density, in.pressure());
            if (closure instanceof FlowControl.Pump && thermo.boils(in.temperature(), in.pressure() * (1 - AT_BUBBLE), in.amounts())) report.note(Note.warning(u.id(), "pump.bubble_point"));
            u.write(board, report, "out", thermo.heated(in, power, outlet));
            report.duty(power);
            report.reading("power", power);
            report.reading("rise", rise);
            report.reading("head", rise / (density * GRAVITY));
        }));
    }
}
