package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A centrifugal pump's curve and shaft power at a share of its rated speed (batch 2026-11-03-rotating-machinery, plan
 * section 3.1). Process level: one rated point, one curve shape for every pump, the affinity laws, the fluid given the
 * ideal work (the losses would warm it by 0.15 K at the rated point, simplification S1).
 *
 * <p>Head {@code H = H_r (1.21 s² − 0.05 q s − 0.15 q|q| − 0.01 q)}, with {@code q = Q/Q_r} (Q the suction volume flow) and
 * s the speed over the rated speed. The curve passes the rated point exactly, rises to 121 % of the rated head at shutoff
 * (API 610 asks a curve that rises to shutoff by at least 10 %) and falls to zero at 2.65 Q_r. Its slope is at most
 * −0.01 H_r/Q_r at every flow and speed, stopped included: a head that falls with flow by a bound has one solution in any
 * network of rising resistances (Wetter 2013; Modelica Buildings adds the same linear term for it). The pressure rise is
 * {@code ρ_s g H} on the suction's density.
 *
 * <p>Shaft power {@code P = P_r (0.35 s³ + 0.65 q s²)}, with {@code P_r = ρ g Q_r H_r / η}: finite at shutoff (35 % of
 * rated), where {@code ρ g Q H / η} would be 0/0; the best efficiency at the rated point, 85 % of it at half flow and
 * 89 % at 150 %. A reverse flow counts as none: the discharge check valve closes it.
 *
 * @param ratedFlow  Q_r, the suction volume flow at the rated point, m³/s
 * @param ratedHead  H_r, the head at the rated point, m
 * @param efficiency η, the efficiency at the rated point, the best on the curve
 */
public record CentrifugalPump(double ratedFlow, double ratedHead, double efficiency) {
    /** Standard gravity, m/s², the fluid solver's. */
    public static final double GRAVITY = 9.80665;
    /** Below this share of the flow at its best efficiency point a pump shows the {@code MIN FLOW} notice (plan S1: real
     * pumps need 25 to 40 % to carry their heat away). */
    public static final double MINIMUM_FLOW_SHARE = .3;
    /** US gallons per minute in one m³/s. */
    static final double GPM_PER_CUBIC_METRE_PER_SECOND = 60 / 3.785411784e-3;
    private static final double SHUTOFF = 1.21, SPEED_FLOW = .05, QUADRATIC = .15, LINEAR = .01;
    private static final double POWER_SHUTOFF = .35, POWER_FLOW = .65;

    public CentrifugalPump {
        if (!(ratedFlow > 0) || !(ratedHead > 0) || !(efficiency > 0 && efficiency <= 1) || !Double.isFinite(ratedFlow) || !Double.isFinite(ratedHead))
            throw new IllegalArgumentException("Invalid pump rating: " + ratedFlow + " m3/s, " + ratedHead + " m, " + efficiency);
    }

    /** A pump rated {@code ratedFlow} (m³/s) and {@code ratedHead} (m) with the {@link #bestEfficiency} of its flow. */
    public static CentrifugalPump rated(double ratedFlow, double ratedHead) {
        return new CentrifugalPump(ratedFlow, ratedHead, bestEfficiency(ratedFlow));
    }

    /** The head, m, at suction volume flow {@code flow} (m³/s) and speed {@code speed} (share of rated). */
    public double head(double flow, double speed) {
        double q = flow / ratedFlow;
        return ratedHead * (SHUTOFF * speed * speed - SPEED_FLOW * q * speed - QUADRATIC * q * Math.abs(q) - LINEAR * q);
    }

    /** {@code dH/dQ}, m per m³/s: at most {@code −0.01 H_r/Q_r} for any speed at or above zero. */
    public double headSlope(double flow, double speed) {
        double q = flow / ratedFlow;
        return -ratedHead / ratedFlow * (SPEED_FLOW * speed + 2 * QUADRATIC * Math.abs(q) + LINEAR);
    }

    /** The shaft power, W, at {@code flow} and {@code speed} on liquid of density {@code density} (kg/m³). */
    public double shaftPower(double flow, double speed, double density) {
        double q = Math.max(0, flow / ratedFlow);
        return ratedShaftPower(density) * speed * speed * (POWER_SHUTOFF * speed + POWER_FLOW * q);
    }

    /** {@code P_r = ρ g Q_r H_r / η}, W: the shaft power at the rated point on liquid of density {@code density}. */
    public double ratedShaftPower(double density) {
        return density * GRAVITY * ratedFlow * ratedHead / efficiency;
    }

    /** The hydraulic power over the shaft power at {@code flow} and {@code speed}; zero where no flow or no head. */
    public double efficiencyAt(double flow, double speed) {
        double q = flow / ratedFlow, h = head(flow, speed) / ratedHead;
        if (!(q > 0) || !(h > 0) || !(speed > 0)) return 0;
        return efficiency * q * h / (speed * speed * (POWER_SHUTOFF * speed + POWER_FLOW * q));
    }

    /** The flow, m³/s, below which the pump at {@code speed} shows {@code MIN FLOW}: {@link #MINIMUM_FLOW_SHARE} of its best
     * efficiency flow, which moves with the speed. */
    public double minimumFlow(double speed) {
        return MINIMUM_FLOW_SHARE * speed * ratedFlow;
    }

    /**
     * The best efficiency of a process pump rated {@code ratedFlow} (m³/s): Corripio's correlation (1982; Seider, Seader and
     * Lewin, Product and Process Design Principles, example 16.5), {@code η = −0.316 + 0.24015 ln Q − 0.01199 (ln Q)²} with Q
     * in US gallons per minute, held at its ends outside its range of 50 to 5000 gpm (11.4 to 1136 m³/h): 0.44 for the
     * smallest pumps, 0.70 at 100 m³/h, 0.86 for the largest.
     */
    public static double bestEfficiency(double ratedFlow) {
        double gpm = Math.min(Math.max(ratedFlow * GPM_PER_CUBIC_METRE_PER_SECOND, 50), 5000), x = DeterministicMath.log(gpm);
        return -.316 + .24015 * x - .01199 * x * x;
    }
}
