package com.wormzjl.icheme.science.integrity;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A running column's trays and wall (batch 2026-11-16-fouling-corrosion, plan section 13.1, decision D10: the agent's
 * numbers, to be tuned later). Particles that ride the liquid down the trays settle on them and in the downcomers, so the
 * trays pass less before they flood; and they scour the shell and the trays. Both go with the solid mass share {@code φ}
 * of what a cycle took: the inert solids it moved to the bottoms over the feed mass it took plus those solids.
 * <ul>
 * <li><b>Tray fouling</b> {@code f}, 0 to 1: while solids pass, {@code f} approaches {@code f∞ = min(1, φ / 0.02)} with the
 * fouling time {@code τ} ({@code fouling.asymptoteProcessHours}, 10 h); it never falls by itself.</li>
 * <li><b>Tray capacity</b> {@code c = 1 − 0.4 f}: the flood fraction the cycle reads is divided by it. At 1 wt% the column
 * settles at 80 % of its clean capacity; from 2 wt% at 60 %.</li>
 * <li><b>Solids wear</b> {@code min(5, 0.5 φ / 0.01)} mm/y on carbon steel, times the material's erosion factor: 1 wt% wears
 * carbon steel 0.5 mm/y and stainless steel 0.25 mm/y.</li>
 * <li><b>Words</b>: {@code TRAYS FOULED} from {@code f} ≥ 0.5.</li>
 * </ul>
 * Pure; no state.
 */
public final class ColumnWear {
    private ColumnWear() {}

    /** The solid share at which the trays foul whole, {@code f∞ = 1} (plan 13.1). */
    public static final double FULL_SHARE = .02;
    /** The capacity a whole fouling takes from the trays (plan 13.1): {@code c = 1 − 0.4 f}. */
    public static final double CAPACITY_LOSS = .4;
    /** The fouling from which the column says {@code TRAYS FOULED} (plan 13.1). */
    public static final double FOULED_FROM = .5;
    /** The solids wear on carbon steel at {@link ErosionLaw#SHARE} (1 wt%), mm/y, and its cap (plan 13.1). */
    public static final double SOLIDS_RATE = .5, SOLIDS_CAP = 5;

    /** The solid mass share of a cycle's take: {@code solids / (feed + solids)}, kg; 0 when it took nothing (and for NaN). */
    public static double share(double solidsKg, double feedKg) {
        double s = solidsKg > 0 && solidsKg < Double.POSITIVE_INFINITY ? solidsKg : 0;
        double total = s + (feedKg > 0 && feedKg < Double.POSITIVE_INFINITY ? feedKg : 0);
        return total > 0 ? s / total : 0;
    }

    /** The fouling the trays approach at solid share {@code share}: {@code min(1, φ / 0.02)}; 0 for none or NaN. */
    public static double asymptote(double share) {
        return share > 0 ? Math.min(1, share / FULL_SHARE) : 0;
    }

    /**
     * The fouling after {@code seconds} process seconds at solid share {@code share} from {@code fouling}, the fouling time
     * {@code tauSeconds}: {@code f + (f∞ − f)(1 − exp(−dt/τ))} while {@code f∞ > f}, else {@code f} (it never falls by itself);
     * always 0 to 1.
     */
    public static double foul(double fouling, double share, double seconds, double tauSeconds) {
        double f = clamp(fouling), target = asymptote(share);
        if (!(target > f) || !(seconds > 0) || !(tauSeconds > 0)) return f;
        return clamp(f + (target - f) * -DeterministicMath.expm1(-seconds / tauSeconds));
    }

    /** The trays' capacity at fouling {@code fouling}: {@code 1 − 0.4 f}; exactly 1 for a clean column. */
    public static double capacity(double fouling) {
        double f = clamp(fouling);
        return f == 0 ? 1 : 1 - CAPACITY_LOSS * f;
    }

    /** Whether the trays at fouling {@code fouling} say {@code TRAYS FOULED}. */
    public static boolean fouled(double fouling) {
        return fouling >= FOULED_FROM;
    }

    /** A {@code material} column's solids wear, mm/y, at solid share {@code share}: {@code min(5, 0.5 φ/0.01)} × its erosion factor. */
    public static double solids(Metallurgy material, double share) {
        if (!(share > 0)) return 0;
        return Math.min(SOLIDS_CAP, SOLIDS_RATE * Math.min(share, 1) / ErosionLaw.SHARE) * material.factor(Mechanism.EROSION, false);
    }

    /** The wall loss, mm, after {@code seconds} process seconds at {@code rate} mm/y from {@code loss}, one corrosion year {@code yearProcessHours} long; at most {@code max}. */
    public static double lose(double loss, double rate, double seconds, double yearProcessHours, double max) {
        double l = loss > 0 ? loss : 0;
        if (!(rate > 0) || !(seconds > 0) || !(yearProcessHours > 0)) return Math.min(l, max);
        return Math.min(max, l + Math.min(rate, CorrosionLaw.MAX_RATE) * seconds / (yearProcessHours * 3600));
    }

    private static double clamp(double f) {
        return f > 0 ? Math.min(f, 1) : 0;
    }
}
