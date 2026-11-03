package com.wormzjl.icheme.science.machine;

/**
 * A positive-displacement machine: a reciprocating compressor, and the legacy and in-line movers under form F2 (batch
 * 2026-11-03-rotating-machinery, plan section 3.3). It delivers its displacement {@code Q_d s} whatever the rise, until its
 * relief valve lifts and passes the delivery back to the suction.
 *
 * <p>As one smooth law, the corner {@code (Q/(Q_d s))^m + (Δp/Δp_relief)^m = 1} with m = 16, of the family of the velocity
 * cap: 98.7 % of the displacement at 90 % of the relief rise, none at the relief rise. Each share counts from zero: a
 * negative rise (suction above discharge) passes the displacement, and a reverse flow stands at the relief rise, passed back
 * by the relief valve. It is today's "set flow up to a rise limit" written as one row instead of a mode switch. A stopped
 * machine is closed, so the law asks a speed above zero.
 *
 * @param displacement Q_d, the suction volume flow at rated speed and no rise, m³/s
 * @param reliefRise   Δp_relief, the rise at which the relief valve passes the whole delivery back, Pa
 */
public record PositiveDisplacement(double displacement, double reliefRise) {
    /** The corner's exponent m. */
    public static final int EXPONENT = 16;

    public PositiveDisplacement {
        if (!(displacement > 0) || !(reliefRise > 0) || !Double.isFinite(displacement) || !Double.isFinite(reliefRise))
            throw new IllegalArgumentException("Invalid displacement " + displacement + " m3/s or relief rise " + reliefRise + " Pa");
    }

    /**
     * The law as a residual, zero on the corner: {@code ‖(u⁺, v⁺)‖₁₆ − 1} with {@code u = Q/(Q_d s)} and
     * {@code v = Δp/Δp_relief}, each held at zero below zero. The norm is homogeneous of degree one, so the residual reads as a
     * distance from the corner (−0.48 at half flow and half rise, where {@code u^m + v^m − 1} is −1.0 almost everywhere
     * inside), and its gradient stays between 0 and 1 per share.
     */
    public double residual(double flow, double rise, double speed) {
        return norm(flow / capacity(speed), rise / reliefRise) - 1;
    }

    /** {@code ∂residual/∂Q}, per m³/s. */
    public double residualFlowSlope(double flow, double rise, double speed) {
        double capacity = capacity(speed);
        return weight(flow / capacity, rise / reliefRise) / capacity;
    }

    /** {@code ∂residual/∂Δp}, per Pa. */
    public double residualRiseSlope(double flow, double rise, double speed) {
        return weight(rise / reliefRise, flow / capacity(speed)) / reliefRise;
    }

    /** The suction volume flow, m³/s, at pressure rise {@code rise} (Pa) and speed {@code speed}: the displacement at or below
     * no rise, none at or above the relief rise. */
    public double flow(double rise, double speed) {
        double capacity = capacity(speed), v = Math.max(0, rise / reliefRise);
        return v >= 1 ? 0 : capacity * root16(1 - power16(v));
    }

    /** The pressure rise, Pa, at suction volume flow {@code flow} (m³/s) and speed {@code speed}: the relief rise at or below
     * no flow, zero at the displacement. No finite rise passes more than the displacement: above it, negative infinity. */
    public double rise(double flow, double speed) {
        double u = Math.max(0, flow / capacity(speed));
        if (u > 1) return Double.NEGATIVE_INFINITY;
        return reliefRise * root16(1 - power16(u));
    }

    /** {@code d(rise)/dQ}, Pa per m³/s: zero at or below no flow, negative infinity at and above the displacement. */
    public double riseSlope(double flow, double speed) {
        double capacity = capacity(speed), u = Math.max(0, flow / capacity);
        if (!(u > 0)) return 0;
        if (!(u < 1)) return Double.NEGATIVE_INFINITY;
        double t = u / root16(1 - power16(u)), t2 = t * t, t4 = t2 * t2, t8 = t4 * t4;
        return -reliefRise / capacity * t8 * t4 * t2 * t;
    }

    private double capacity(double speed) {
        if (!(speed > 0)) throw new IllegalArgumentException("A stopped positive-displacement machine is closed, speed " + speed);
        return displacement * speed;
    }

    /** {@code ‖(a⁺, b⁺)‖₁₆}, scaled by the larger share so no power overflows. */
    static double norm(double a, double b) {
        a = Math.max(0, a);
        b = Math.max(0, b);
        double high = Math.max(a, b), low = Math.min(a, b);
        if (!(high > 0)) return 0;
        return high * root16(1 + power16(low / high));
    }

    /** {@code ∂‖(a⁺, b⁺)‖₁₆/∂a = (a⁺/‖·‖)^15}. */
    private static double weight(double a, double b) {
        if (!(a > 0)) return 0;
        double t = a / norm(a, b), t2 = t * t, t4 = t2 * t2, t8 = t4 * t4;
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
