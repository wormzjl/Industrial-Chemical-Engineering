package com.wormzjl.icheme.science.integrity;

/**
 * A wall's stage and life (batch 2026-11-16-fouling-corrosion, plan sections 6.5 and 7). The used allowance
 * {@code u = loss / CA}: {@link Stage#SOUND} below 0.5, {@link Stage#THIN} to 0.9 ({@code THIN WALL}), {@link Stage#WEEPING}
 * to 1 (drips, no fluid leaves), {@link Stage#THROUGH} from 1 (the device fails by the failure its kind has). Loss is
 * booked as {@code rate × Δt_process / year}, one corrosion year {@code corrosion.yearProcessHours} long (owner D3: 20 h):
 * a 3 mm wall lasts 600 process hours at 0.1 mm/y, 46 at 1.3 mm/y, 12 at 5 mm/y. Pure; no state.
 */
public final class WallState {
    private WallState() {}

    /** The stages of a wall; saved by name later: append-only. */
    public enum Stage { SOUND, THIN, WEEPING, THROUGH }

    /** The used allowance at which each stage begins. */
    public static final double THIN_FROM = .5, WEEPING_FROM = .9, THROUGH_FROM = 1;

    /** The stage at {@code lossMm} of {@code allowanceMm}; {@link Stage#SOUND} for NaN, any loss through for no allowance. */
    public static Stage stage(double lossMm, double allowanceMm) {
        if (!(lossMm > 0)) return Stage.SOUND;
        if (!(allowanceMm > 0)) return Stage.THROUGH;
        double u = lossMm / allowanceMm;
        if (u >= THROUGH_FROM) return Stage.THROUGH;
        if (u >= WEEPING_FROM) return Stage.WEEPING;
        return u >= THIN_FROM ? Stage.THIN : Stage.SOUND;
    }

    /**
     * The process seconds until the wall is through, from {@code loss} of {@code allowance} (mm) at {@code rateMmPerYear}:
     * {@code (CA − loss) / rate × year × 3600}; 0 when already through, infinite for a rate of 0 (or NaN).
     */
    public static double lifeProcessSeconds(double loss, double allowance, double rateMmPerYear, double yearProcessHours) {
        double left = (allowance > 0 ? allowance : 0) - (loss > 0 ? loss : 0);
        if (!(left > 0)) return 0;
        if (!(rateMmPerYear > 0) || !(yearProcessHours > 0)) return Double.POSITIVE_INFINITY;
        return left / rateMmPerYear * yearProcessHours * 3600;
    }
}
