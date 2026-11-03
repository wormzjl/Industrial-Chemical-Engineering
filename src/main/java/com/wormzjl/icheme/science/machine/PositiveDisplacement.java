package com.wormzjl.icheme.science.machine;

/**
 * A positive-displacement machine: a reciprocating compressor, and the legacy and in-line movers under form F2 (batch
 * 2026-11-03-rotating-machinery, plan section 3.3). It delivers its displacement {@code Q_d s} whatever the rise, until its
 * relief valve lifts and passes the delivery back to the suction.
 *
 * <p>As the row every law mover states, {@code head − rise(Q) = 0}: the corner {@code (Q/(Q_d s))^m + (Δp/Δp_relief)^m = 1}
 * with m = 16, of the family of the velocity cap (98.7 % of the displacement at 90 % of the relief rise, none at the relief
 * rise). At or below no flow the rise is the relief rise: a reverse flow stands there, passed back by the relief valve until
 * the mover's reverse closure ends it. Past {@link #KNEE} of the displacement the rise goes on falling along a straight
 * line with the corner's slope there, −47.9 {@code Δp_relief/(Q_d s)}: a suction above the discharge blows through, 1.5 %
 * above the displacement at no rise. A finite rise at every flow holds the row's head derivative at one, so the row is
 * never empty; the corner first written as a norm, {@code ‖(u⁺, v⁺)‖₁₆ − 1}, had no gradient at all at no flow and no rise,
 * where a mover that cannot beat its discharge starts (W2's quick check, cold seed 200). A stopped machine is closed, so
 * the law asks a speed above zero.
 *
 * @param displacement Q_d, the suction volume flow at rated speed and no rise, m³/s
 * @param reliefRise   Δp_relief, the rise at which the relief valve passes the whole delivery back, Pa
 */
public record PositiveDisplacement(double displacement, double reliefRise) {
    /** The corner's exponent m. */
    public static final int EXPONENT = 16;
    /** The share of the displacement past which the rise falls along a straight line. */
    public static final double KNEE = .999;
    /** The corner's rise at the knee, a share of the relief rise (0.772), and its slope there, relief rises per
     * displacement (−47.9). */
    static final double KNEE_RISE = root16(1 - power16(KNEE)), KNEE_SLOPE = -fall(KNEE);

    public PositiveDisplacement {
        if (!(displacement > 0) || !(reliefRise > 0) || !Double.isFinite(displacement) || !Double.isFinite(reliefRise))
            throw new IllegalArgumentException("Invalid displacement " + displacement + " m3/s or relief rise " + reliefRise + " Pa");
    }

    /** The pressure rise, Pa, at suction volume flow {@code flow} (m³/s) and speed {@code speed}: the relief rise at or below
     * no flow, the corner up to the knee, then the straight line, zero at 1.0151 of the displacement and negative past it. */
    public double rise(double flow, double speed) {
        double u = Math.max(0, flow / capacity(speed));
        return reliefRise * (u <= KNEE ? root16(1 - power16(u)) : KNEE_RISE + KNEE_SLOPE * (u - KNEE));
    }

    /** {@code d(rise)/dQ}, Pa per m³/s: zero at or below no flow, the corner's slope up to the knee, the line's past it. */
    public double riseSlope(double flow, double speed) {
        double capacity = capacity(speed), u = Math.max(0, flow / capacity);
        if (!(u > 0)) return 0;
        return reliefRise / capacity * (u <= KNEE ? -fall(u) : KNEE_SLOPE);
    }

    /** The suction volume flow, m³/s, at pressure rise {@code rise} (Pa) and speed {@code speed}, the inverse of
     * {@link #rise}: none at or above the relief rise, more than the displacement below the knee's rise. */
    public double flow(double rise, double speed) {
        double capacity = capacity(speed), v = rise / reliefRise;
        if (v >= 1) return 0;
        return capacity * (v >= KNEE_RISE ? root16(1 - power16(v)) : KNEE + (v - KNEE_RISE) / KNEE_SLOPE);
    }

    private double capacity(double speed) {
        if (!(speed > 0)) throw new IllegalArgumentException("A stopped positive-displacement machine is closed, speed " + speed);
        return displacement * speed;
    }

    /** The corner's fall, {@code −d(rise/Δp_relief)/du = (u/(1 − u^16)^(1/16))^15}, at a share {@code u} below one. */
    private static double fall(double u) {
        double t = u / root16(1 - power16(u)), t2 = t * t, t4 = t2 * t2, t8 = t4 * t4;
        return t8 * t4 * t2 * t;
    }

    private static double power16(double x) {
        double x2 = x * x, x4 = x2 * x2, x8 = x4 * x4;
        return x8 * x8;
    }

    /** The sixteenth root by four square roots: correctly rounded steps, the same on every JVM. */
    private static double root16(double x) {
        return Math.sqrt(Math.sqrt(Math.sqrt(Math.sqrt(x))));
    }
}
