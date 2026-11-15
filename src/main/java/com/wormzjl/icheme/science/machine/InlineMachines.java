package com.wormzjl.icheme.science.machine;

/**
 * Fixed inline ratings: inlet velocity 2 m/s liquid or 10 m/s gas; 25 m head or 1.5 air pressure
 * ratio at 300 K. The network's side, the machine of a bore under the movers' cap and the shaft power a driver pays, is
 * {@code science/fluid/network/InlineMovers} (codebase review M-14).
 */
public final class InlineMachines {
    private InlineMachines() {}

    /** An in-line machine's inlet flow, m³/s, at bore {@code bore} m: its inlet area at 2 m/s liquid or 10 m/s gas. */
    public static double inletFlow(double bore, boolean liquid) {
        if (!(bore > 0) || !Double.isFinite(bore))
            throw new IllegalArgumentException("Invalid inline bore");
        return Math.PI * bore * bore / 4 * (liquid ? 2 : 10);
    }

    /** The law of an in-line machine rated at {@code flow} m³/s: a pump of 25 m head, or a compressor of air ratio 1.5 at 300 K. */
    public static MachineLaw law(double flow, boolean liquid) {
        return liquid
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
    }

    /** Electrical draw for a finite shaft load, including the integral motor and VFD losses. */
    public static double electricPower(double shaftPower) {
        double rating = Motor.ratingFor(Math.max(1, shaftPower));
        return Motor.electricPower(
                shaftPower, Double.isFinite(rating) ? rating : 20000000, 4, true);
    }

    /**
     * A set-flow pump's hydraulic efficiency: the share of its shaft power the liquid takes as work. The solve books only the
     * ideal {@code q Δp} (simplification S1: the loss would warm the liquid a little), so the pump's driver pays that work over
     * this efficiency, and the pump's hydraulic loss is a stated loss the fluid does not get (code base review M-01, owner
     * decision B1). A set-flow compressor's power is already over its own efficiency and takes none of this.
     */
    public static final double SET_FLOW_PUMP_EFFICIENCY = .65;
}
