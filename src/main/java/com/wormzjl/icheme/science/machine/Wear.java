package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A pump's wear (batch 2026-11-03-rotating-machinery W10b, build plan revision 2, owner decision D34): its health falls
 * from 1 (new) to 0 (failed) from two faults the player can see and fix, never from clean running.
 * <ul>
 * <li><b>Cavitation:</b> {@code I_c(σ) / τ_c}, with {@code σ = NPSHA / NPSH3} and Gülich's erosion intensity
 * {@code I_c = (σ/2.06)³ ((4 − σ)/1.94)^2.83} for {@code 0 < σ < 4}, else 0 (plan section 3.7 without the gas cushion):
 * one at its peak σ = 2.06, the measured maximum near twice NPSH3; τ_c = {@link #CAVITATION_HOURS} h. Lives in online
 * time: 2.0 h at σ 2.06, 5.1 h at NPSH3 (σ 1), 18.9 h at σ 3.5, none from σ 4 (inception) up.</li>
 * <li><b>Running dry:</b> {@code 1 / τ_d} while its driver is on and it is gas- or vapour-locked, τ_d =
 * {@link #DRY_MINUTES} min.</li>
 * </ul>
 * Pure: no state. The engine books {@code rate × Δt} over committed online time.
 */
public final class Wear {
    private Wear() {}

    /** The cavitation life at the erosion peak, h. */
    public static final double CAVITATION_HOURS = 2;
    /** The life running dry, min. */
    public static final double DRY_MINUTES = 10;
    /** The σ of the erosion peak, and the inception σ above which nothing erodes. */
    public static final double PEAK = 2.06, INCEPTION = 4;

    /** The config's three keys ({@code machines.wear.*}). */
    public record Settings(boolean enabled, double cavitationHours, double dryMinutes) {
        public static final Settings DEFAULT = new Settings(true, CAVITATION_HOURS, DRY_MINUTES);

        public Settings {
            if (!(cavitationHours > 0) || !Double.isFinite(cavitationHours) || !(dryMinutes > 0) || !Double.isFinite(dryMinutes))
                throw new IllegalArgumentException("Invalid wear lives " + cavitationHours + " h, " + dryMinutes + " min");
        }
    }

    /** Gülich's erosion intensity at {@code sigma} = NPSHA/NPSH3, one at its peak: 0 at and below 0, from 4 up and for NaN. */
    public static double intensity(double sigma) {
        if (!(sigma > 0) || !(sigma < INCEPTION)) return 0;
        double a = sigma / PEAK, b = (INCEPTION - sigma) / (INCEPTION - PEAK);
        return a * a * a * DeterministicMath.pow(b, 2.83);
    }

    /** The health a pump loses per second of online time at {@code sigma} (NaN: no cavitation read) and running dry or not. */
    public static double rate(double sigma, boolean dry, Settings settings) {
        if (!settings.enabled()) return 0;
        return intensity(sigma) / (settings.cavitationHours() * 3600) + (dry ? 1 / (settings.dryMinutes() * 60) : 0);
    }

    /** {@link #rate} at the default lives. */
    public static double rate(double sigma, boolean dry) {
        return rate(sigma, dry, Settings.DEFAULT);
    }

    /** The online life, s, of a new pump at a steady {@code sigma} and dry state: infinite where nothing wears. */
    public static double life(double sigma, boolean dry, Settings settings) {
        double r = rate(sigma, dry, settings);
        return r > 0 ? 1 / r : Double.POSITIVE_INFINITY;
    }
}
