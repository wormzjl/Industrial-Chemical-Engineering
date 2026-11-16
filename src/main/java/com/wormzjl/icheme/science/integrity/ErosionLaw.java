package com.wormzjl.icheme.science.integrity;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * Erosion by particles (batch 2026-11-16-fouling-corrosion, plan section 8; DNV RP O501 for steel): metal loss in
 * proportion to the solid mass share {@code φ} of the stream and to its speed to the power 2.6. Every inert solid alike;
 * crystals do not count (they melt).
 * <ul>
 * <li><b>Wall</b> (a heat side, by its passage speed): {@code (φ/0.01) (v/10 m/s)^2.6} mm/y on carbon steel, times the
 * material's erosion factor (1, 0.8, 0.5, 0.4). At 1 wt%: 0.015 mm/y at 2 m/s, 0.16 at 5 m/s.</li>
 * <li><b>Control valve trim</b>: speed {@code sqrt(2 Δp/ρ)} from its own drop (16 m/s at 1 bar, 800 kg/m³); health lost
 * per process second {@code (φ/0.01) (v/30 m/s)^2.6 / (year)}, the year {@code corrosion.yearProcessHours} (20 h): at 1 wt%
 * 860 process hours at 0.2 bar, 106 at 1 bar, 13 at 5 bar, 2.2 at 20 bar.</li>
 * <li><b>Pump</b> ({@code Wear.Fault.SOLIDS}, the erosion class): intensity {@code clamp((φ − 0.001)/0.05, 0, 1)}; 1 wt%
 * reads 0.18, 44 process hours at the default erosion life.</li>
 * </ul>
 * 0 for a share, speed, drop or density that is 0, negative or NaN; every result finite. Pure; no state.
 */
public final class ErosionLaw {
    private ErosionLaw() {}

    /** The speed exponent (DNV RP O501, steel). */
    public static final double EXPONENT = 2.6;
    /** The reference solid share, the wall's reference speed (m/s) and the trim's reference speed (m/s). */
    public static final double SHARE = .01, WALL_SPEED = 10, TRIM_SPEED = 30;
    /** A pump's solids fault: the share at and below which it does not erode, and the span above it to full intensity. */
    public static final double SOLIDS_ONSET = .001, SOLIDS_SPAN = .05;
    /** The largest speed read, m/s: keeps every result finite (far above the network's 100 m/s cap). */
    static final double MAX_SPEED = 1000;

    /** A {@code material} wall's erosion, mm/y, at solid mass share {@code solidShare} and speed {@code speed}, m/s. */
    public static double wall(Metallurgy material, double solidShare, double speed) {
        return Math.min(CorrosionLaw.MAX_RATE, intensity(solidShare, speed, WALL_SPEED) * material.factor(Mechanism.EROSION, false));
    }

    /** A valve trim's speed, m/s, at its pressure drop {@code dropPa} and density {@code density}, kg/m³: {@code sqrt(2 Δp/ρ)}. */
    public static double trimSpeed(double dropPa, double density) {
        if (!(dropPa > 0) || !(density > 0)) return 0;
        double v = Math.sqrt(2 * dropPa / density);
        return Double.isFinite(v) ? v : MAX_SPEED;
    }

    /** A valve trim's health lost per process second at {@code solidShare} and trim {@code speed}, the corrosion year {@code yearProcessHours} long. */
    public static double trim(double solidShare, double speed, double yearProcessHours) {
        if (!(yearProcessHours > 0)) return 0;
        return intensity(solidShare, speed, TRIM_SPEED) / (Math.min(yearProcessHours, 1e9) * 3600);
    }

    /** A pump's solids erosion intensity, 0 to 1: {@code clamp((φ − 0.001)/0.05, 0, 1)}; 0 for NaN. */
    public static double solidsIntensity(double solidShare) {
        if (!(solidShare > SOLIDS_ONSET)) return 0;
        return Math.min(1, (solidShare - SOLIDS_ONSET) / SOLIDS_SPAN);
    }

    /** {@code (φ/0.01) (v/reference)^2.6}, the share at most 1 and the speed at most {@link #MAX_SPEED}. */
    private static double intensity(double solidShare, double speed, double reference) {
        if (!(solidShare > 0) || !(speed > 0)) return 0;
        return Math.min(solidShare, 1) / SHARE * DeterministicMath.pow(Math.min(speed, MAX_SPEED) / reference, EXPONENT);
    }
}
