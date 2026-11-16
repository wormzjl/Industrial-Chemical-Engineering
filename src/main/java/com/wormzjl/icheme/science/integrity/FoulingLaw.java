package com.wormzjl.icheme.science.integrity;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The fouling deposit of a heat side (batch 2026-11-16-fouling-corrosion, plan section 12; owner O1: a placeholder until
 * the reaction module exists). A deposit of resistance {@code Rf}, m²K/W, grows by Kern–Seaton while the side flows:
 * {@code dRf/dt = (Rf∞ − Rf)/τ}, {@code τ} 10 process hours by default, so {@link #step} is the exact exponential
 * approach. {@code Rf∞} from the digest ({@link #asymptote}, TEMA anchors):
 * <ul>
 * <li>gas, steam, a light clean liquid: 0;</li>
 * <li>water (a wet side without oil): 0.00035, 0.0007 above 50 °C;</li>
 * <li>oil: its mass-weighted fouling number ({@code corrosion.fouling} of the crude cuts: 0 light, 0.0005 crude and
 * heavy, 0.0018 residue); from 260 °C the crude and heavy share rises to 0.0009: {@code max(f, min(1.8 f, 0.0009))};</li>
 * <li>plus {@code min(0.0005, 0.02 φ)} for a liquid carrying solids (1 wt% adds 0.0002).</li>
 * </ul>
 * Never above {@link #CAP}. The graph sees {@code Rf} only in whole steps of {@link #STEP} ({@link #level}); the deposit's
 * thickness {@code δ = Rf k_d} narrows the passage ({@link #thickness}). The film law {@code h' = h/(1 + h Rf)} is
 * {@code HeatCurve}'s (WP5). Pure; no state.
 */
public final class FoulingLaw {
    private FoulingLaw() {}

    /** The largest deposit, m²K/W. */
    public static final double CAP = .002;
    /** The deposit step the graph sees, m²K/W: at most 20 steps between cleanings. */
    public static final double STEP = 1e-4;
    /** The deposit's thermal conductivity, W/mK: its thickness is {@code Rf k_d}. */
    public static final double DEPOSIT_CONDUCTIVITY = 1;
    /** The water rows, m²K/W, and the temperature, K, above which the warm one holds. */
    public static final double WATER = .00035, WARM_WATER = .0007, WARM_WATER_FROM = 50 + Metallurgy.ZERO_CELSIUS;
    /** The hot crude row, m²K/W, its ratio to the cold crude row (0.0005), and the temperature, K, from which it holds. */
    public static final double HOT_CRUDE = .0009, HOT_CRUDE_RATIO = HOT_CRUDE / .0005, HOT_CRUDE_FROM = 260 + Metallurgy.ZERO_CELSIUS;
    /** Particulate fouling: m²K/W per unit solid share, and its cap. */
    public static final double SOLIDS_PER_SHARE = .02, SOLIDS_CAP = .0005;

    /** The asymptotic deposit {@code Rf∞}, m²K/W, of a side carrying {@code d} with solid mass share {@code solidShare}. */
    public static double asymptote(CorrosionDigest d, double solidShare) {
        if (!d.oil() && !d.wet()) return 0;
        double t = d.temperature(), base;
        if (d.oil()) {
            double f = d.fouling() > 0 ? Math.min(d.fouling(), CAP) : 0;
            base = t >= HOT_CRUDE_FROM ? Math.max(f, Math.min(HOT_CRUDE_RATIO * f, HOT_CRUDE)) : f;
        } else base = t > WARM_WATER_FROM ? WARM_WATER : WATER;
        double solids = solidShare > 0 ? Math.min(SOLIDS_CAP, SOLIDS_PER_SHARE * solidShare) : 0;
        return Math.min(CAP, base + solids);
    }

    /**
     * The deposit after {@code dtProcessSeconds} of flow from {@code rf} towards {@code asymptote} at time constant
     * {@code tauProcessHours}: {@code Rf∞ + (Rf − Rf∞) exp(−Δt/τ)}, between 0 and {@link #CAP}. No time, NaN or a NaN
     * time constant keeps {@code rf}; a time constant of 0 or less reaches the asymptote at once.
     */
    public static double step(double rf, double asymptote, double dtProcessSeconds, double tauProcessHours) {
        double from = clamp(rf), to = clamp(asymptote);
        if (!(dtProcessSeconds > 0) || Double.isNaN(tauProcessHours)) return from;
        if (!(tauProcessHours > 0)) return to;
        return clamp(to + (from - to) * DeterministicMath.exp(-dtProcessSeconds / (tauProcessHours * 3600)));
    }

    /** The whole steps of {@link #STEP} in {@code rf}, 0 to 20. */
    public static int level(double rf) {
        return (int) Math.floor(clamp(rf) / STEP + 1e-9);
    }

    /** The deposit's thickness, m: {@code Rf k_d}. */
    public static double thickness(double rf) {
        return clamp(rf) * DEPOSIT_CONDUCTIVITY;
    }

    private static double clamp(double rf) {
        return rf > 0 ? Math.min(rf, CAP) : 0;
    }
}
