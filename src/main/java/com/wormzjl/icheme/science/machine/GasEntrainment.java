package com.wormzjl.icheme.science.machine;

/**
 * Gas carried into a centrifugal pump's suction (batch 2026-11-03-rotating-machinery, plan section 3.6, owner decisions D19
 * and D20 Q12; TWO_PHASE_MACHINES_RESEARCH_REVIEW.md section 1). Small bubbles ride with the liquid and cost little; past
 * an onset share they gather in the impeller and the head falls fast toward gas lock. The onset rises with the suction
 * pressure, as denser gas stays dispersed: Turpin et al. (1986), stable while {@code 2000 (q_g/q_l) / (3 p[psia]) < 1}, so
 * the critical gas-liquid volume ratio is {@code r_T = 3 p[psia]/2000 = 0.0218 p[bar]} and the critical vapour volume share
 * {@code α_T = r_T/(1 + r_T)}: 2.1 % at 1 bar, 5.2 % at 2.5 bar, 18 % at 10 bar. The onset reads the suction pressure only
 * (no speed or impeller factor, Q12).
 *
 * <p>The head share {@code M(α) = 1/(1 + (α/α_50)^4)} with {@code α_50 = 1.6 α_T} multiplies the liquid curve's head: 0.89
 * at 2 % and 0.34 at 4 % at 1 bar (the Goulds rule of thumb: 2 % costs 10 % of capacity, 4 % costs 43 %), 0.81 at 20 % at
 * 10 bar, 0.87 at the onset itself. It is smooth, one at no gas and falls toward zero; gas lock is no rule: where the
 * shutoff head times M falls below the line's lift the curve meets zero flow and the check valve closes the pump.
 * Simplification T1: the pump's own vapour counts as gas.
 */
public final class GasEntrainment {
    private GasEntrainment() {}

    /** Turpin's critical gas-liquid volume ratio per pascal of suction pressure: 3/2000 per psia. */
    public static final double ONSET_RATIO_PER_PASCAL = 3 / 2000.0 / 6894.757293168;
    /** The vapour share at which the head is halved, in onsets. */
    public static final double HALF_HEAD_ONSETS = 1.6;

    /** The onset vapour volume share α_T at suction pressure {@code suctionPressure} (Pa): {@code r_T/(1 + r_T)}. */
    public static double onset(double suctionPressure) {
        if (!(suctionPressure > 0) || !Double.isFinite(suctionPressure)) throw new IllegalArgumentException("Invalid suction pressure " + suctionPressure);
        double ratio = ONSET_RATIO_PER_PASCAL * suctionPressure;
        return ratio / (1 + ratio);
    }

    /** The share M of the liquid curve's head a pump makes with {@code vapourVolumeShare} (α, 0 to 1) of its suction
     * volume vapour at {@code suctionPressure} (Pa): {@code 1/(1 + (α/α_50)^4)}, one where there is no vapour. */
    public static double headShare(double vapourVolumeShare, double suctionPressure) {
        double half = HALF_HEAD_ONSETS * onset(suctionPressure);
        if (!(vapourVolumeShare > 0)) return 1;
        double x = Math.min(vapourVolumeShare, 1) / half, x2 = x * x;
        return 1 / (1 + x2 * x2);
    }
}
