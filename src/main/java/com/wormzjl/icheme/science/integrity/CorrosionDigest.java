package com.wormzjl.icheme.science.integrity;

/**
 * The digest of a device's fluid that the corrosion and fouling laws read (batch 2026-11-16-fouling-corrosion, plan
 * section 6.1), from its node's last committed state ({@link CorrosionStandard#digest}).
 *
 * @param temperature the state's temperature, K
 * @param wet         a free aqueous liquid is present (its moles above a trace)
 * @param oil         a hydrocarbon liquid is present (its moles above a trace)
 * @param sulfur      the oil liquid's sulfur, wt%, mass-weighted over its compounds
 * @param acid        the oil liquid's acid number, mg KOH/g, mass-weighted
 * @param h2s         partial pressures {@code y_i P} on the overall moles, bar: hydrogen sulfide,
 * @param co2         carbon dioxide,
 * @param so2         sulfur dioxide,
 * @param nh3         ammonia,
 * @param o2          oxygen,
 * @param h2          hydrogen
 * @param fouling     the oil liquid's fouling number, m²K/W, mass-weighted ({@link FoulingLaw#asymptote})
 */
public record CorrosionDigest(double temperature, boolean wet, boolean oil, double sulfur, double acid, double h2s,
                              double co2, double so2, double nh3, double o2, double h2, double fouling) {
    /** The digest of nothing: every rate 0. */
    public static final CorrosionDigest EMPTY = new CorrosionDigest(0, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
