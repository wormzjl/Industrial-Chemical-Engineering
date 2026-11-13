package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.fluid.network.FlowControl;

/**
 * Fixed inline ratings: inlet velocity 2 m/s liquid or 10 m/s gas; 25 m head or 1.5 air pressure
 * ratio at 300 K.
 */
public final class InlineMachines {
    private InlineMachines() {}

    public static FlowControl.Machine design(double bore, boolean liquid) {
        if (!(bore > 0) || !Double.isFinite(bore))
            throw new IllegalArgumentException("Invalid inline bore");
        double flow =
                Math.min(
                        FlowControl.Mover.MAXIMUM_VOLUME_FLOW,
                        Math.PI * bore * bore / 4 * (liquid ? 2 : 10));
        MachineLaw law =
                liquid
                        ? CentrifugalPump.rated(flow, 25)
                        : new CentrifugalCompressor(
                                flow,
                                CentrifugalCompressor.polytropicHead(
                                        1.5,
                                        100000,
                                        100000 / (287.05 * 300),
                                        CentrifugalCompressor.sigma(
                                                1.4, CentrifugalCompressor.DEFAULT_EFFICIENCY)),
                                CentrifugalCompressor.DEFAULT_EFFICIENCY);
        return new FlowControl.Machine(law, 1, true);
    }

    /** Electrical draw for a finite shaft load, including the integral motor and VFD losses. */
    public static double electricPower(double shaftPower) {
        double rating = Motor.ratingFor(Math.max(1, shaftPower));
        return Motor.electricPower(
                shaftPower, Double.isFinite(rating) ? rating : 20000000, 4, true);
    }

    public static double ratedDraw(FlowControl.Mover mover) {
        return electricPower(ratedShaftPower(mover));
    }

    /**
     * A set-flow pump's hydraulic efficiency: the share of its shaft power the liquid takes as work. The solve books only the
     * ideal {@code q Δp} (simplification S1: the loss would warm the liquid a little), so the pump's driver pays that work over
     * this efficiency, and the pump's hydraulic loss is a stated loss the fluid does not get (code base review M-01, owner
     * decision B1). A set-flow compressor's power is already over its own efficiency and takes none of this.
     */
    public static final double SET_FLOW_PUMP_EFFICIENCY = .65;

    /**
     * The shaft power, W, the driver of {@code mover} gives it at mass flow {@code massFlow} (kg/s) across {@code head} (Pa),
     * on a suction of {@code density} (kg/m³) and {@code pressure} (Pa), at the heat capacity ratio {@code kappa} and the wet
     * efficiency share {@code wetShare} the solve reads ({@code PassiveStepSolver.suctionKappa} and {@code wetShare}; code base
     * review M-01, owner decisions A2 and B1):
     * <ul>
     * <li>a machine: its law's shaft power at the suction volume flow and its speed, over {@code wetShare}. A gas machine's
     * recycle flow is in it: the recycle's heat, which the fluid does not get, is a stated loss;</li>
     * <li>a set-flow pump: its ideal work over {@link #SET_FLOW_PUMP_EFFICIENCY};</li>
     * <li>any other mover: its power (a set-flow compressor's is over its efficiency already; an ejector's is zero).</li>
     * </ul>
     * Nothing at no density or a stopped machine. The motor's and the drive's losses come after it ({@link #electricPower},
     * the train's motor).
     */
    public static double shaftPower(FlowControl.Mover mover, double massFlow, double head, double density, double pressure, double kappa, double wetShare) {
        if (!(density > 0)) return 0;
        double flow = Math.max(0, massFlow);
        return switch (mover) {
            case FlowControl.Machine m -> m.speed() > 0 ? m.law().shaftPower(flow / density, m.speed(), density, pressure, kappa) / wetShare : 0;
            case FlowControl.Pump p -> p.power(flow, head, density, pressure, kappa) / SET_FLOW_PUMP_EFFICIENCY;
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
