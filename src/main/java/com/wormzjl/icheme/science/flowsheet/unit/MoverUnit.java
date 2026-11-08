package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.InletPhase;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.Motor;

import java.util.List;

/**
 * A pump or a compressor (batch 2026-11-02-process-simulator, plan section 3.2): the world's own closures. Target Outlet or Rise.
 * Each refuses what the world's device refuses. The sheet knows no heights, so a pump on a liquid at its bubble point warns; the
 * sizing stage clears that with the nozzle's height.
 *
 * <p>Its shaft power is its design's (batch 2026-11-03-rotating-machinery, W8 form A, owner decision D42), and all of it heats the
 * discharge, since the losses stay in the fluid: a pump's is the ideal work {@code Q Δp} over the best efficiency of a centrifugal
 * pump rated at the tile's duty ({@link CentrifugalPump#rated}, Corripio); a compressor's is the world's closure's, the polytropic
 * work already over {@link CentrifugalCompressor#DEFAULT_EFFICIENCY}. Each proposes its driver: a 2-pole IE3 motor with the
 * {@link Motor#SPARE} ({@link #driver}).
 */
public final class MoverUnit implements UnitModel {
    /** Standard gravity (m/s²), for a head in metres. */
    private static final double GRAVITY = 9.80665;
    /** A liquid that boils this share under its pressure stands at its bubble point. */
    private static final double AT_BUBBLE = 1e-3;
    /** The proposed motor's poles: a pump's design speed with no speed of its own (decision A3), and the compressor's, which has
     * no pole count of its own (its gearbox takes the speed up). */
    public static final int POLES = 2;
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
            double density = in.mass() / in.state().volume(), ideal = closure.power(in.mass(), rise, density, in.pressure(), kappa(thermo.model(), in.state()));
            boolean pump = closure instanceof FlowControl.Pump;
            if (pump && thermo.boils(in.temperature(), in.pressure() * (1 - AT_BUBBLE), in.amounts())) report.note(Note.warning(u.id(), "pump.bubble_point"));
            double head = rise / (density * GRAVITY);
            double efficiency = pump ? pumpEfficiency(in.mass() / density) : CentrifugalCompressor.DEFAULT_EFFICIENCY;
            double shaft = pump ? ideal / efficiency : ideal;
            u.write(board, report, "out", thermo.heated(in, shaft, outlet));
            report.duty(shaft);
            report.reading("power", shaft);
            if (pump) report.reading("hydraulic", ideal);
            report.reading("efficiency", efficiency);
            report.reading("rise", rise);
            report.reading("head", head);
            driver(report, shaft);
        }));
    }

    /** The best efficiency of a centrifugal pump rated at suction volume flow {@code volumeFlow} (m³/s), as the world's design
     * takes it ({@link CentrifugalPump#rated}); a tile with no flow moves nothing, and its one is a placeholder. */
    public static double pumpEfficiency(double volumeFlow) {
        return volumeFlow > 0 ? CentrifugalPump.bestEfficiency(volumeFlow) : 1;
    }

    /** The proposed driver of {@code shaft} W: a {@link #POLES}-pole IE3 motor rated {@link Motor#ratingFor} (20 % spare), its
     * efficiency and its electric power, as readings {@code motor} (W), {@code motor_efficiency} and {@code electric} (W). None
     * above the largest motor, 20 MW. */
    static void driver(Report report, double shaft) {
        double rating = Motor.ratingFor(shaft);
        if (!(shaft > 0) || Double.isNaN(rating)) return;
        report.reading("motor", rating);
        report.reading("motor_efficiency", Motor.efficiency(rating, POLES));
        report.reading("electric", Motor.electricPower(shaft, rating, POLES, false));
    }

    /** The heat capacity ratio the world reads for this mover: its suction gas's for a compressor, as the fluid network reads
     * it ({@code PassiveStepSolver.kappa}); a liquid mover does not use it. */
    private double kappa(FluidThermodynamics model, FluidThermodynamics.State state) {
        return closure.liquid() ? 1.4 : model.heatCapacityRatio(state);
    }
}
