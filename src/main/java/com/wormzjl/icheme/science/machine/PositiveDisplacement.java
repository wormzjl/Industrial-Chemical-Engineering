package com.wormzjl.icheme.science.machine;

/**
 * A positive-displacement machine: a reciprocating compressor, and the set-flow pumps and compressors (batch
 * 2026-11-03-rotating-machinery, plan section 3.3, form F2e). In the share {@code u} of its displacement {@code Q_d s}
 * it delivers and its rise {@code v} in relief rises, the law is two lines: the slip line {@code u = 1 − SLIP v}, the
 * displacement less what leaks back past the pistons or rotors, and the relief line {@code u = m (1 − v) + δ}, the
 * relief valve passing the whole displacement back over the last tenth of the rise (its accumulation, {@code m = 10}).
 * The smooth minimum joins them, {@code u = (x + y − √((x − y)² + ε²))/2} for {@code x} and {@code y} the two lines: 99.80 %
 * of the displacement at half the relief rise, 98.8 % at 90 %, 50 % at 95 %, 10 % at 99 %, nothing at the relief rise.
 * The relief line's lift {@code δ = ε²/(4 (1 − SLIP))}, 1.0e-4 of the displacement, puts the rounded law's no flow
 * exactly on the relief rise: a mover's rise limit is its law's rise at no flow, which a closed mover's reopening reads
 * ({@code FlowControl.Mover#riseLimit}). Past the relief rise the relief line goes on, a reverse flow the mover's reverse
 * closure ends; below no rise the slip line does, 0.4 % more per relief rise the suction stands above the discharge.
 *
 * <p>Each line has a slope, so the law is never vertical, the set flow a prescribed flow that a flow valve or a velocity
 * cap in series leaves singular, nor flat, a prescribed rise; its row ({@link #residual}) is linear on each line, with
 * a flow derivative at every point and a rise derivative between {@code SLIP} and {@code m}. The forms measured before it
 * (W2_FORMS_REVIEW.md, W2_CORNER_REVIEW.md): F2, the corner {@code u^16 + v^16 = 1} as a norm (no gradient at no flow and
 * no rise); F2b, that corner as a rise with a line past 0.999 of the displacement (up to 1.5 % above the displacement at
 * every rise below 77 % of the relief rise, and steep in the flow at the set flow); F2c, {@code u = 1 − v^64} as a flow
 * (vertical: singular behind a flow valve; curved over the whole top sixth of the rise).
 *
 * @param displacement Q_d, the suction volume flow at rated speed and no rise, m³/s
 * @param reliefRise   Δp_relief, the rise at which the relief valve passes the whole delivery back, Pa
 */
public record PositiveDisplacement(double displacement, double reliefRise) {
    /** The share of the displacement the slip loses per relief rise. */
    public static final double SLIP = .004;
    /** The relief line's slope, displacements per relief rise: the relief valve's accumulation is a tenth of the rise. */
    public static final double RELIEF_SLOPE = 10;
    /** The corner's rounding ε, in displacements: 1 % below both lines where they cross, at 90 % of the relief rise. */
    public static final double ROUNDING = .02;
    /** δ, the relief line's lift that puts no flow on the relief rise: {@code (1 − SLIP) δ = ε²/4} there. */
    private static final double LIFT = ROUNDING * ROUNDING / (4 * (1 - SLIP));

    public PositiveDisplacement {
        if (!(displacement > 0) || !(reliefRise > 0) || !Double.isFinite(displacement) || !Double.isFinite(reliefRise))
            throw new IllegalArgumentException("Invalid displacement " + displacement + " m3/s or relief rise " + reliefRise + " Pa");
    }

    /** The suction volume flow, m³/s, at pressure rise {@code rise} (Pa) and speed {@code speed}. */
    public double flow(double rise, double speed) {
        return capacity(speed) * share(rise / reliefRise);
    }

    /** The rise, Pa, at suction volume flow {@code flow} and speed {@code speed}, the inverse of {@link #flow}: finite at every
     * flow, the relief rise at no flow. */
    public double rise(double flow, double speed) {
        return reliefRise * level(flow / capacity(speed));
    }

    /** {@code d(rise)/dQ}, Pa per m³/s: between {@code −1/SLIP} and {@code −1/m} relief rises per displacement. */
    public double riseSlope(double flow, double speed) {
        double capacity = capacity(speed), u = flow / capacity, v = level(u);
        // The flow's distances below the two lines, both positive on the law: dv/du = −(a + b)/(SLIP b + m a).
        double a = 1 - SLIP * v - u, b = RELIEF_SLOPE * (1 - v) + LIFT - u;
        return -reliefRise / capacity * (a + b) / (SLIP * b + RELIEF_SLOPE * a);
    }

    /** The law as a row, Pa: the relief rise times the flow less the law's flow at {@code rise}, over the displacement. Zero
     * on the law; its flow derivative is the relief rise over the displacement at every point, its rise derivative between
     * {@code SLIP} and {@code m}. */
    public double residual(double flow, double rise, double speed) {
        return reliefRise * (flow - flow(rise, speed)) / capacity(speed);
    }

    private double capacity(double speed) {
        if (!(speed > 0)) throw new IllegalArgumentException("A stopped positive-displacement machine is closed, speed " + speed);
        return displacement * speed;
    }

    /** The share of the displacement at a rise of {@code v} relief rises: the smooth minimum of the two lines. */
    private static double share(double v) {
        double x = 1 - SLIP * v, y = RELIEF_SLOPE * (1 - v) + LIFT, d = x - y;
        return .5 * (x + y - Math.sqrt(d * d + ROUNDING * ROUNDING));
    }

    /** The rise in relief rises at which the law passes the share {@code u}: {@code (x − u)(y − u) = ε²/4} with both factors
     * positive, the smaller root of {@code SLIP m v² − (A m + B SLIP) v + A B − ε²/4 = 0} ({@code A = 1 − u},
     * {@code B = m + δ − u}), whose discriminant is {@code (A m − B SLIP)² + SLIP m ε²}; the root form without cancellation. */
    private static double level(double u) {
        double a = SLIP * RELIEF_SLOPE, along = 1 - u, across = RELIEF_SLOPE + LIFT - u, b = along * RELIEF_SLOPE + across * SLIP;
        double c = along * across - .25 * ROUNDING * ROUNDING, e = along * RELIEF_SLOPE - across * SLIP;
        double root = Math.sqrt(e * e + a * ROUNDING * ROUNDING);
        return b > 0 ? 2 * c / (b + root) : (b - root) / (2 * a);
    }
}
