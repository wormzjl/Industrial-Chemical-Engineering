package com.wormzjl.icheme.science.pollution;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The hazard standard of released materials (batch 2026-11-15-pollution-system, plan section 2), per compound in one order
 * (the fluid network's conserved order when built from the catalog): the toxic and corrosive references (NIOSH IDLH as a
 * mole fraction, 0 for none), the lower flammability limit (volume fraction, 0 for none) and the critical constants a
 * spill's volatile share reads (plan section 3). An index is linear in the moles: {@code I = sum y_i / ref_i}, the additive
 * hazard index for the health channels and Le Chatelier's rule for the combustible one. Immutable; no Minecraft types.
 */
public final class HazardStandard {
    /** Moles of air per cubic metre at 298.15 K and 1 atm. */
    public static final double AIR_MOLAR_DENSITY = PollutionField.AIR_MOLAR_DENSITY;
    /** kg/m3: the NIOSH IDLH of carbon black, 1750 mg/m3, the reference of every released solid. */
    public static final double PARTICULATE_REFERENCE = 1.75e-3;
    /** The air a release mixes into: 298.15 K, 1 atm. */
    public static final double AMBIENT_TEMPERATURE = 298.15, AMBIENT_PRESSURE = 101325;
    /** Wilson's constant, {@code 5.373 = (7/3) ln 10}. */
    private static final double WILSON = 5.373;

    private final double[] toxicReference, corrosiveReference, lowerFlammabilityLimit;
    /** Each compound's volatile share, computed once. */
    private final double[] volatileShare;

    /**
     * All arrays have one length, one entry per compound. A reference or limit is 0 (no hazard) or a positive finite
     * fraction at most 1. A compound's critical constants are finite (temperature and pressure positive), or all three NaN
     * for a record without them: its whole liquid counts as volatile.
     */
    public HazardStandard(double[] toxicReference, double[] corrosiveReference, double[] lowerFlammabilityLimit,
                          double[] criticalTemperature, double[] criticalPressure, double[] acentricFactor) {
        int n = toxicReference.length;
        if (corrosiveReference.length != n || lowerFlammabilityLimit.length != n || criticalTemperature.length != n
                || criticalPressure.length != n || acentricFactor.length != n)
            throw new IllegalArgumentException("hazard standard: every array needs " + n + " entries");
        this.toxicReference = references(toxicReference, "toxic");
        this.corrosiveReference = references(corrosiveReference, "corrosive");
        this.lowerFlammabilityLimit = references(lowerFlammabilityLimit, "lower flammability limit");
        this.volatileShare = new double[n];
        for (int i = 0; i < n; i++) {
            double tc = criticalTemperature[i], pc = criticalPressure[i], omega = acentricFactor[i];
            if (Double.isNaN(tc) && Double.isNaN(pc) && Double.isNaN(omega)) { volatileShare[i] = 1; continue; }
            if (!(tc > 0 && Double.isFinite(tc)) || !(pc > 0 && Double.isFinite(pc)) || !Double.isFinite(omega))
                throw new IllegalArgumentException("hazard standard: compound " + i + " needs finite critical constants, or none");
            // Wilson: Psat = Pc exp(5.373 (1 + omega)(1 - Tc/T)); above Tc the compound is a gas and goes whole.
            double saturation = pc * DeterministicMath.exp(WILSON * (1 + omega) * (1 - tc / AMBIENT_TEMPERATURE));
            volatileShare[i] = Math.min(1, saturation / AMBIENT_PRESSURE);
        }
    }

    private static double[] references(double[] values, String label) {
        var out = values.clone();
        for (int i = 0; i < out.length; i++)
            if (!(out[i] == 0 || out[i] > 0 && out[i] <= 1))
                throw new IllegalArgumentException("hazard standard: " + label + " reference of compound " + i + " must be 0 or in (0, 1]");
        return out;
    }

    /** The number of compounds. */
    public int size() { return toxicReference.length; }

    /** Air-mole equivalents of the toxic channel: {@code sum |n_i| / ref_i} (mol). A cube's index is this divided by its air moles. */
    public double toxic(double[] moles) { return index(moles, toxicReference); }

    /** As {@link #toxic}, for the corrosive channel. */
    public double corrosive(double[] moles) { return index(moles, corrosiveReference); }

    /** As {@link #toxic}, for the combustible channel on the lower flammability limits (Le Chatelier). */
    public double combustible(double[] moles) { return index(moles, lowerFlammabilityLimit); }

    /**
     * One compound's term of {@code channel} (toxic, corrosive or combustible) for {@code moles} of it: {@code |n| / ref};
     * 0 without a reference or for particulate. The terms of every compound sum to the channel's index.
     */
    public double term(Channel channel, int compound, double moles) {
        double[] reference = switch (channel) {
            case TOXIC -> toxicReference;
            case CORROSIVE -> corrosiveReference;
            case COMBUSTIBLE -> lowerFlammabilityLimit;
            case PARTICULATE -> null;
        };
        return reference == null || !(reference[compound] > 0) ? 0 : Math.abs(moles) / reference[compound];
    }

    /** A boundary transfer is signed: moles count by magnitude; a reference of 0 contributes nothing. */
    private double index(double[] moles, double[] reference) {
        if (moles.length != reference.length) throw new IllegalArgumentException("hazard standard: " + moles.length + " moles for " + reference.length + " compounds");
        double sum = 0;
        for (int i = 0; i < moles.length; i++) if (reference[i] > 0) sum += Math.abs(moles[i]) / reference[i];
        return sum;
    }

    /** Solids mass (kg, by magnitude) as air-mole equivalents: {@code m / PARTICULATE_REFERENCE * AIR_MOLAR_DENSITY}. */
    public static double particulate(double kilograms) {
        return Math.abs(kilograms) / PARTICULATE_REFERENCE * AIR_MOLAR_DENSITY;
    }

    /**
     * The share of a liquid compound that enters the air (plan section 3, owner decision D3): {@code min(1, Psat(298.15 K) / 1 atm)},
     * Wilson's {@code Psat = Pc exp(5.373 (1 + omega)(1 - Tc/T))}; 1 for a compound without critical constants.
     */
    public double volatileShare(int compound) { return volatileShare[compound]; }

    /** {@code liquidMoles} scaled per compound by {@link #volatileShare}: what a spilled liquid gives the air (a new array). */
    public double[] volatileMoles(double[] liquidMoles) {
        if (liquidMoles.length != volatileShare.length) throw new IllegalArgumentException("hazard standard: " + liquidMoles.length + " moles for " + volatileShare.length + " compounds");
        var out = new double[liquidMoles.length];
        for (int i = 0; i < out.length; i++) out[i] = liquidMoles[i] * volatileShare[i];
        return out;
    }
}
