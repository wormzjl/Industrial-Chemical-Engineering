package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A pump's NPSH required (batch 2026-11-03-rotating-machinery, plan section 3.6, owner decisions D19 and D20 Q11 A;
 * TWO_PHASE_MACHINES_RESEARCH_REVIEW.md section 3). NPSH3 is the net positive suction head at which the head has fallen by
 * 3 % (HI 9.6.1); it grows with the speed squared and with the flow, slowly up to the rated flow and steeply past it:
 * {@code NPSH3(Q, s) = NPSH3_r s² (0.4 + 0.6 (q/s)²) = NPSH3_r (0.4 s² + 0.6 q²)} with {@code q = Q/Q_r}: 0.40 of the rated
 * value at shutoff, 0.55 at half flow, 1.26 at 120 %, 1.75 at 150 %. The rated value follows from a suction specific speed of
 * {@link #SUCTION_SPECIFIC_SPEED} (metric, US 10,300), {@code NPSH3_r = (n_r √Q_r / S)^(4/3)}: 3.3 m for 100 m³/h at
 * 2950 rpm. An in-line mover's design is its duty ({@link #forDuty}, owner decision D21): 1.7 m for its default 0.01 m³/s at
 * the 2-pole speed, 3.3 m for every set flow above 0.028 m³/s, which turns slower.
 *
 * <p>Below NPSH3 the head breaks down steeply: the pump delivers what its suction can feed. The fluid network holds that as
 * the pump's suction floor, {@code p_v + κ ρ_l g NPSH3(Q, s)} with κ = {@link #BREAKDOWN} (the full-breakdown NPSH;
 * simplification T4: the 0 to 3 % head drop above it is not in the curve; T3: no thermodynamic effect for hot liquids), so
 * the bubble floor is its {@code NPSH3 = 0} end.
 *
 * @param ratedFlow  Q_r, the suction volume flow at the design point, m³/s
 * @param speed      s, the speed as a share of the rated speed
 * @param ratedNpsh3 NPSH3_r, the NPSH3 at the design point, m
 */
public record Npsh(double ratedFlow, double speed, double ratedNpsh3) {
    /** The suction specific speed of a design, metric ({@code n[rpm] √Q[m³/s] / NPSH3[m]^0.75}). */
    public static final double SUCTION_SPECIFIC_SPEED = 200;
    /** The share of NPSH3 at which the head has broken down: the floor's NPSH. */
    public static final double BREAKDOWN = .85;
    /** NPSH3's share of its rated value at shutoff, and the share that grows with the square of the flow. */
    public static final double SHUTOFF_SHARE = .4, FLOW_SHARE = .6;

    public Npsh {
        if (!(ratedFlow > 0) || !Double.isFinite(ratedFlow) || !(speed >= 0) || !Double.isFinite(speed) || !(ratedNpsh3 >= 0) || !Double.isFinite(ratedNpsh3))
            throw new IllegalArgumentException("Invalid NPSH design: " + ratedFlow + " m3/s, speed " + speed + ", " + ratedNpsh3 + " m");
    }

    /** The rated NPSH3 a pump chosen for its duty needs at most, m: plan 1.1's pump, 100 m³/h at 2950 rpm (owner decision
     * D21). */
    public static final double DUTY_NPSH3 = 3.3;

    /**
     * The speed (rpm) of a pump chosen for a duty of {@code ratedFlow} (m³/s) with a motor of at most {@code maxSpeed} (rpm):
     * the speed whose rated NPSH3 is {@link #DUTY_NPSH3}, {@code S DUTY^0.75 / √Q_r}, at most {@code maxSpeed} (owner decision
     * D21: a designer turns a large pump slower so that a tank can feed it). 2950 rpm up to 0.028 m³/s, 1549 rpm at 0.1 m³/s,
     * 400 rpm at 1.5 m³/s. The speed feeds only NPSH3: an in-line mover runs on its set-flow law.
     */
    public static double dutySpeed(double ratedFlow, double maxSpeed) {
        if (!(ratedFlow > 0) || !(maxSpeed > 0) || !Double.isFinite(ratedFlow) || !Double.isFinite(maxSpeed))
            throw new IllegalArgumentException("Invalid duty: " + ratedFlow + " m3/s, at most " + maxSpeed + " rpm");
        return Math.min(maxSpeed, SUCTION_SPECIFIC_SPEED * DeterministicMath.pow(DUTY_NPSH3, .75) / Math.sqrt(ratedFlow));
    }

    /** The design of a pump chosen for a duty of {@code ratedFlow} (m³/s) with a motor of at most {@code maxSpeed} (rpm),
     * running at its rated speed ({@link #dutySpeed}): its rated NPSH3 is at most {@link #DUTY_NPSH3}. */
    public static Npsh forDuty(double ratedFlow, double maxSpeed) {
        return rated(ratedFlow, dutySpeed(ratedFlow, maxSpeed), 1);
    }

    /** A design rated {@code ratedFlow} (m³/s) at {@code ratedSpeed} (rpm), running at {@code speed} (share of rated). */
    public static Npsh rated(double ratedFlow, double ratedSpeed, double speed) {
        return new Npsh(ratedFlow, speed, rated(ratedFlow, ratedSpeed));
    }

    /** {@code NPSH3_r = (n_r √Q_r / S)^(4/3)}, m, for rated flow {@code ratedFlow} (m³/s) at {@code ratedSpeed} (rpm). */
    public static double rated(double ratedFlow, double ratedSpeed) {
        if (!(ratedFlow > 0) || !(ratedSpeed > 0) || !Double.isFinite(ratedFlow) || !Double.isFinite(ratedSpeed))
            throw new IllegalArgumentException("Invalid rating: " + ratedFlow + " m3/s at " + ratedSpeed + " rpm");
        return DeterministicMath.pow(ratedSpeed * Math.sqrt(ratedFlow) / SUCTION_SPECIFIC_SPEED, 4 / 3.0);
    }

    /** NPSH3, m, at suction volume flow {@code flow} (m³/s): {@code NPSH3_r (0.4 s² + 0.6 q²)}, even in the flow. */
    public double required(double flow) {
        double q = flow / ratedFlow;
        return ratedNpsh3 * (SHUTOFF_SHARE * speed * speed + FLOW_SHARE * q * q);
    }

    /** {@code κ ρ_l g NPSH3(Q)}, Pa: how far above the liquid's vapour pressure the suction of a pump passing {@code flow}
     * (m³/s) of a liquid of density {@code liquidDensity} (kg/m³) stands where its head breaks down. */
    public double breakdownPressure(double flow, double liquidDensity) {
        return BREAKDOWN * liquidDensity * CentrifugalPump.GRAVITY * required(flow);
    }
}
