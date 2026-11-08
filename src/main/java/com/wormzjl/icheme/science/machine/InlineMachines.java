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
        if (mover instanceof FlowControl.Machine machine) {
            double rho = machine.liquid() ? 1000 : 100000 / (287.05 * 300);
            return electricPower(
                    machine.law().shaftPower(machine.law().ratedFlow(), 1, rho, 100000, 1.4));
        }
        return electricPower(
                mover.power(
                                mover.requestedVolumeFlow() * (mover.liquid() ? 1000 : 1.161),
                                mover.riseLimit(mover.liquid() ? 1000 : 1.161, 100000, 1000, 1.4),
                                mover.liquid() ? 1000 : 1.161,
                                100000,
                                1.4)
                        / .65);
    }
}
