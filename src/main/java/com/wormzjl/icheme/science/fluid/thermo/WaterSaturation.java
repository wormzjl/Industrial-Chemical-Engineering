package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;

/**
 * Pure water's saturation curve on the network's own model (W3b of batch 2026-11-11-aqueous-solvents, G2a): the pressure
 * where the model's liquid and vapour roots of pure water have one fugacity, so that a state the network builds at
 * saturation is the phase its flash calls it. Tabulated once per whole model: {@code ln p} on a grid uniform in
 * {@code 1/T}, with the Clapeyron slope {@code d ln p / d(1/T) = -T dh / (p dv)} of the same solve, read by cubic
 * Hermite interpolation. Below the table's cold end the curve continues with value and slope; above its hot end, up to
 * the critical temperature, with the last node's value and slope; at and above the critical temperature the pressure is
 * infinite.
 */
final class WaterSaturation {
    /** The grid's intervals; 1/T is uniform between the ends. */
    static final int INTERVALS = 4096;
    /** The table's hot end below the critical temperature, K, where the two roots are still apart. */
    static final double CRITICAL_MARGIN = 0.05;
    private static final double R = 8.314462618;

    private final double critical, x0, dx;
    private final double[] y, slope;
    private final int nodes;

    /**
     * The curve of pure water at component {@code water} of {@code model}, from {@code cold} to {@code critical} K, each
     * solve started from {@code guess} at the cold end and from the previous node after it.
     */
    WaterSaturation(HydrocarbonModel model, int water, double cold, double critical, java.util.function.DoubleUnaryOperator guess) {
        this.critical = critical;
        double hot = critical - CRITICAL_MARGIN;
        x0 = 1 / cold;
        dx = (1 / hot - x0) / INTERVALS;
        double[] pure = new double[model.componentCount() + 1];
        pure[water] = 1;
        double[] ys = new double[INTERVALS + 1], slopes = new double[INTERVALS + 1];
        int kept = 0;
        double lnp = DeterministicMath.log(guess.applyAsDouble(cold));
        for (int i = 0; i <= INTERVALS; i++) {
            double t = 1 / (x0 + i * dx);
            if (i > 0) lnp = ys[i - 1] + slopes[i - 1] * dx;
            double[] solved = solve(model, pure, t, lnp);
            if (solved == null) break;
            ys[i] = solved[0];
            slopes[i] = solved[1];
            kept = i + 1;
        }
        if (kept < 2) throw new IllegalStateException("No saturation curve of water on this model");
        nodes = kept;
        y = java.util.Arrays.copyOf(ys, kept);
        slope = java.util.Arrays.copyOf(slopes, kept);
    }

    /** {@code {ln p, d ln p / d(1/T)}} at saturation at {@code t}, Newton in {@code ln p} from {@code lnp}; null where the roots merge or vanish. */
    private static double[] solve(HydrocarbonModel model, double[] pure, double t, double lnp) {
        var terms = model.prepare(t);
        for (int iteration = 0; iteration < 50; iteration++) {
            double p = DeterministicMath.exp(lnp);
            HydrocarbonModel.Phase liquid, vapour;
            try {
                liquid = model.phase(t, p, pure, PengRobinsonKernel.Root.LIQUID, terms, false);
                vapour = model.phase(t, p, pure, PengRobinsonKernel.Root.VAPOR, terms, false);
            } catch (IllegalArgumentException absent) {
                return null;
            }
            double dv = vapour.molarVolume() - liquid.molarVolume();
            if (!(dv > 1e-3 * vapour.molarVolume())) return null;
            int w = pure.length - 1;
            double g = liquid.logFugacityView()[w] - vapour.logFugacityView()[w];
            // d(ln phi_L - ln phi_V)/d ln p = p (v_L - v_V) / (R T) for a pure fluid.
            double step = g / (p * dv / (R * t));
            lnp += step;
            if (Math.abs(step) < 1e-13) {
                double dh = vapour.molarEnthalpy() - liquid.molarEnthalpy();
                return new double[]{lnp, -t * dh / (DeterministicMath.exp(lnp) * dv)};
            }
        }
        return null;
    }

    /** The saturation pressure at {@code t}, Pa: infinite at and above the critical temperature. */
    double pressure(double t) {
        if (t >= critical) return Double.POSITIVE_INFINITY;
        double x = 1 / t, u = (x - x0) / dx;
        if (u <= 0) return DeterministicMath.exp(y[0] + slope[0] * (x - x0));
        int i = (int) u;
        if (i >= nodes - 1) {
            double xl = x0 + (nodes - 1) * dx;
            return DeterministicMath.exp(y[nodes - 1] + slope[nodes - 1] * (x - xl));
        }
        double s = u - i, s2 = s * s, s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
        return DeterministicMath.exp(h00 * y[i] + h10 * dx * slope[i] + h01 * y[i + 1] + h11 * dx * slope[i + 1]);
    }

    /** The temperature whose saturation pressure is {@code p}, K, by bisection on {@link #pressure} to {@code 1e-9} K. */
    double temperature(double p) {
        if (!(p > 0)) throw new IllegalArgumentException("Positive pressure required");
        double low = 1, high = critical;
        while (pressure(low) > p) low /= 2;
        while (high - low > 1e-9) {
            double middle = 0.5 * (low + high);
            if (pressure(middle) >= p) high = middle;
            else low = middle;
        }
        return high;
    }
}
