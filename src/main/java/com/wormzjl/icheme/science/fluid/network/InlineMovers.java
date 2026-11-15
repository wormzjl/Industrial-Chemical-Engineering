package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.machine.InlineMachines;

/**
 * The network's side of the in-line machines (codebase review M-14, section 3.5): an in-line pump's or compressor's
 * {@link FlowControl.Machine} from its bore, and the shaft power and draw a mover's driver pays. The fixed ratings and the
 * motor's draw are {@link InlineMachines}' (science/machine), which reads no network type.
 */
public final class InlineMovers {
    private InlineMovers() {}

    /** The in-line machine of {@code bore} m: {@link InlineMachines#law} at its inlet flow, at most the movers' largest flow. */
    public static FlowControl.Machine design(double bore, boolean liquid) {
        double flow = Math.min(FlowControl.Mover.MAXIMUM_VOLUME_FLOW, InlineMachines.inletFlow(bore, liquid));
        return new FlowControl.Machine(InlineMachines.law(flow, liquid), 1, true);
    }

    /** A mover's rated electric draw, W: {@link InlineMachines#electricPower} of its {@link #ratedShaftPower}. */
    public static double ratedDraw(FlowControl.Mover mover) {
        return InlineMachines.electricPower(ratedShaftPower(mover));
    }

    /**
     * The shaft power, W, the driver of {@code mover} gives it at mass flow {@code massFlow} (kg/s) across {@code head} (Pa),
     * on a suction of {@code density} (kg/m³) and {@code pressure} (Pa), at the heat capacity ratio {@code kappa} and the wet
     * efficiency share {@code wetShare} the solve reads ({@code PassiveStepSolver.suctionKappa} and {@code wetShare}; code base
     * review M-01, owner decisions A2 and B1):
     * <ul>
     * <li>a machine: its law's shaft power at the suction volume flow and its speed, over {@code wetShare}. A gas machine's
     * recycle flow is in it: the recycle's heat, which the fluid does not get, is a stated loss;</li>
     * <li>a set-flow pump: its ideal work over {@link InlineMachines#SET_FLOW_PUMP_EFFICIENCY};</li>
     * <li>any other mover: its power (a set-flow compressor's is over its efficiency already; an ejector's is zero).</li>
     * </ul>
     * Nothing at no density or a stopped machine. The motor's and the drive's losses come after it
     * ({@link InlineMachines#electricPower}, the train's motor).
     */
    public static double shaftPower(FlowControl.Mover mover, double massFlow, double head, double density, double pressure, double kappa, double wetShare) {
        if (!(density > 0)) return 0;
        double flow = Math.max(0, massFlow);
        return switch (mover) {
            case FlowControl.Machine m -> m.speed() > 0 ? m.law().shaftPower(flow / density, m.speed(), density, pressure, kappa) / wetShare : 0;
            case FlowControl.Pump p -> p.power(flow, head, density, pressure, kappa) / InlineMachines.SET_FLOW_PUMP_EFFICIENCY;
            default -> mover.power(flow, head, density, pressure, kappa);
        };
    }

    /**
     * A mover's rated shaft power, W, a design figure shown before any slice exists ({@link #ratedDraw}; a held column pump
     * books it, W5 of batch 2026-11-10-column-failure-modes): a machine's law at its rated flow and full speed, another mover's
     * {@link #shaftPower} at its set flow and its rise limit, on the design fluids: water at 1000 kg/m³, or dry air at 1 bar
     * and 300 K, whose heat capacity ratio is 1.4 (no wet share).
     */
    public static double ratedShaftPower(FlowControl.Mover mover) {
        if (mover instanceof FlowControl.Machine machine) {
            double rho = machine.liquid() ? 1000 : 100000 / (287.05 * 300);
            return machine.law().shaftPower(machine.law().ratedFlow(), 1, rho, 100000, 1.4);
        }
        double rho = mover.liquid() ? 1000 : 1.161;
        return shaftPower(mover, mover.requestedVolumeFlow() * rho, mover.riseLimit(rho, 100000, 1000, 1.4), rho, 100000, 1.4, 1);
    }
}
