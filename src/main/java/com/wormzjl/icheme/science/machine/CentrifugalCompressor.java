package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A centrifugal compressor's polytropic head, pressure ratio and power at a share of its rated speed (batch
 * 2026-11-03-rotating-machinery, plan section 3.2). Process level: one speed-line shape for every machine, a constant
 * polytropic efficiency along it (simplification S3).
 *
 * <p>Head against suction volume flow, per speed line: {@code H_p/H_r = s² (0.645 + 1.244 x − 0.889 x²)} with
 * {@code x = q/s} and {@code q = Q/Q_r}. The line passes the rated point, peaks near x = 0.70 and reaches zero near
 * x = 1.80 (choke). Surge stands at x = 0.75: below it the anti-surge recycle holds the machine's own flow on the surge
 * line ({@link #machineFlow}), and the net law continues as the straight line with the curve's slope there, −0.0895, so
 * it falls at every flow and stays smooth (C¹). Written without dividing by s, the law holds stopped too: a stopped
 * machine is a quadratic loss.
 *
 * <p>The pressure ratio follows from the head on the suction's own {@code P/ρ}, which carries its compressibility, with
 * {@code σ = η_p k/(k − 1)}: {@code r = (1 + H_p ρ_s/(σ P_s))^σ}, the ASME PTC 10 polytropic head turned round. The gas takes
 * {@code ṁ H_p/η_p}; the recycle's share of the shaft power leaves through the recycle cooler.
 *
 * @param ratedFlow  Q_r, the suction volume flow at the rated point, m³/s
 * @param ratedHead  H_r, the polytropic head at the rated point, J/kg
 * @param efficiency η_p, the polytropic efficiency
 */
public record CentrifugalCompressor(double ratedFlow, double ratedHead, double efficiency) implements MachineLaw {
    /** The polytropic efficiency of a new design unless it states its own (0.70 to 0.85 in service). */
    public static final double DEFAULT_EFFICIENCY = .78;
    /** The surge line, as {@code x = q/s}. */
    public static final double SURGE = .75;
    private static final double A = .645, B = 1.244, C = .889;
    /** The speed line's slope and value at the surge line, per {@code H_r/Q_r} and {@code H_r} at full speed. */
    private static final double SURGE_SLOPE = B - 2 * C * SURGE, SURGE_HEAD = A + B * SURGE - C * SURGE * SURGE;

    public CentrifugalCompressor {
        if (!(ratedFlow > 0) || !(ratedHead > 0) || !(efficiency > 0 && efficiency <= 1) || !Double.isFinite(ratedFlow) || !Double.isFinite(ratedHead))
            throw new IllegalArgumentException("Invalid compressor rating: " + ratedFlow + " m3/s, " + ratedHead + " J/kg, " + efficiency);
    }

    /** The polytropic head, J/kg, at suction volume flow {@code flow} (m³/s) and speed {@code speed} (share of rated). */
    public double head(double flow, double speed) {
        double q = flow / ratedFlow;
        if (q >= SURGE * speed) return ratedHead * (A * speed * speed + B * q * speed - C * q * q);
        return ratedHead * ((SURGE_HEAD - SURGE_SLOPE * SURGE) * speed * speed + SURGE_SLOPE * q * speed);
    }

    /** {@code dH_p/dQ}, J/kg per m³/s: at most {@code −0.0895 s H_r/Q_r}. */
    public double headSlope(double flow, double speed) {
        double q = flow / ratedFlow;
        return ratedHead / ratedFlow * (q >= SURGE * speed ? B * speed - 2 * C * q : SURGE_SLOPE * speed);
    }

    /** The suction volume flow on the surge line at {@code speed}, m³/s. */
    public double surgeFlow(double speed) {
        return SURGE * speed * ratedFlow;
    }

    /** The volume flow through the machine, m³/s: the net {@code flow}, or the surge flow while the recycle makes it up. */
    public double machineFlow(double flow, double speed) {
        return Math.max(flow, surgeFlow(speed));
    }

    /** The power the gas takes, W: {@code ṁ H_p/η_p} for the net mass flow; none at a negative flow or head. Past choke the
     * machine is a loss, which keeps the gas's enthalpy. */
    public double gasPower(double massFlow, double head) {
        return Math.max(0, massFlow) * Math.max(0, head) / efficiency;
    }

    /** The shaft power, W, at net suction volume flow {@code flow}, {@code speed}, suction density {@code density} and the
     * machine's {@code head}: the gas power of the {@link #machineFlow}, recycle included. */
    public double shaftPower(double flow, double speed, double density, double head) {
        return gasPower(density * machineFlow(flow, speed), head);
    }

    @Override public boolean liquid() {
        return false;
    }

    /** The pressure rise of the head at {@code flow} and {@code speed}, on the suction's pressure and density and the gas's k. */
    @Override public double rise(double flow, double speed, double density, double pressure, double k) {
        return pressureRise(head(flow, speed), pressure, density, sigma(k, efficiency));
    }

    @Override public double riseSlope(double flow, double speed, double density, double pressure, double k) {
        return pressureRiseSlope(head(flow, speed), pressure, density, sigma(k, efficiency)) * headSlope(flow, speed);
    }

    /** {@link #gasPower} of the polytropic head that the {@code rise} stands for. */
    @Override public double fluidPower(double massFlow, double rise, double density, double pressure, double k) {
        return gasPower(massFlow, polytropicHead(1 + rise / pressure, pressure, density, sigma(k, efficiency)));
    }

    @Override public double shaftPower(double flow, double speed, double density, double pressure, double k) {
        return shaftPower(flow, speed, density, head(flow, speed));
    }

    /** {@code σ = η_p k/(k − 1)} for heat capacity ratio {@code k} (from the suction's ideal-gas heat capacity) and
     * polytropic efficiency {@code efficiency}; {@code (n − 1)/n = 1/σ}. */
    public static double sigma(double k, double efficiency) {
        if (!(k > 1) || !(efficiency > 0)) throw new IllegalArgumentException("Invalid k " + k + " or efficiency " + efficiency);
        return efficiency * k / (k - 1);
    }

    /** The pressure ratio for polytropic head {@code head} (J/kg) on suction pressure {@code pressure} (Pa) and density
     * {@code density} (kg/m³): {@code (1 + H_p ρ_s/(σ P_s))^σ}; below zero head, {@code 1 + H_p ρ_s/P_s} ({@link #pressureRise}). */
    public static double ratio(double head, double pressure, double density, double sigma) {
        return 1 + pressureRise(head, pressure, density, sigma) / pressure;
    }

    /** The pressure rise, Pa, for polytropic head {@code head}: {@code P_s (r − 1)}. A negative head (past choke, or a stopped
     * machine passing gas) is a loss, {@code ρ_s H_p}: the polytropic law's tangent at zero head, so the rise and its slope run
     * on through zero. */
    public static double pressureRise(double head, double pressure, double density, double sigma) {
        if (!(head > 0)) return density * head;
        return pressure * (DeterministicMath.pow(1 + head * density / (sigma * pressure), sigma) - 1);
    }

    /** {@code d(rise)/dH_p}, kg/m³: {@code ρ_s (1 + H_p ρ_s/(σ P_s))^(σ − 1)}; {@code ρ_s} below zero head. */
    public static double pressureRiseSlope(double head, double pressure, double density, double sigma) {
        if (!(head > 0)) return density;
        return density * DeterministicMath.pow(1 + head * density / (sigma * pressure), sigma - 1);
    }

    /** The polytropic head, J/kg, that raises gas at {@code pressure} and {@code density} by pressure ratio {@code ratio}:
     * {@code σ (P_s/ρ_s)(r^(1/σ) − 1)}; below a ratio of one, the loss {@code P_s (r − 1)/ρ_s} ({@link #pressureRise} turned round). */
    public static double polytropicHead(double ratio, double pressure, double density, double sigma) {
        if (!(ratio > 1)) return pressure * (ratio - 1) / density;
        return sigma * pressure / density * (DeterministicMath.pow(ratio, 1 / sigma) - 1);
    }
}
