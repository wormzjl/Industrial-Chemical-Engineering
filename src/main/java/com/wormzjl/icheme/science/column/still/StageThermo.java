package com.wormzjl.icheme.science.column.still;

/**
 * What the still's column reads of its thermo (batch 2026-11-13-codebase-review, C17 option (a)): the constants of its
 * basis, one phase's molar enthalpy and volume, ln φ of a phase at a prepared temperature, and the TP split of a liquid that
 * holds water. The plant layer implements it on the island's own model ({@code science/plant/still/NetworkStageThermo});
 * the column names no type of the network.
 *
 * <p>The basis is the column's: the compounds, then water last where the model carries it ({@link #water}). Every vector
 * passed is on that basis, in mole fractions. Each method is one evaluation of the model behind it and returns what that
 * evaluation gave; a phase with no equation-of-state root, and a refused split, throw a {@link RuntimeException}, which the
 * column reads as a refused trial. An implementation keeps no state between calls beyond what {@link #prepare} hands back.</p>
 */
public interface StageThermo {
    /** The components of the column's basis: the compounds, plus water where the model carries it. */
    int componentCount();

    /** Water's index on the column's basis (the last), -1 where the model carries none. */
    int water();

    /** Component {@code i}'s critical temperature, K (Wilson's start). */
    double criticalTemperature(int i);

    /** Component {@code i}'s critical pressure, Pa (Wilson's start). */
    double criticalPressure(int i);

    /** Component {@code i}'s acentric factor (Wilson's start). */
    double acentricFactor(int i);

    /** Component {@code i}'s molar mass, kg/mol. */
    double molecularWeight(int i);

    /** The root a phase is evaluated on. */
    enum Root {
        LIQUID, VAPOUR
    }

    /** One phase's molar enthalpy (J/mol, on the model's datum) and molar volume (m³/mol). */
    record Phase(double molarEnthalpy, double molarVolume) {
    }

    /** Phase {@code x} at {@code t} K and {@code p} Pa on {@code root}, prepared at {@code t} for this call alone. */
    Phase phase(double t, double p, double[] x, Root root);

    /** The model prepared at one temperature: an opaque handle that {@link #logFugacity} reuses at that temperature. */
    interface Prepared {
    }

    /** The model prepared at {@code t} K. */
    Prepared prepare(double t);

    /**
     * ln φ of each component of phase {@code x} at {@code t} K (the temperature {@code prepared} was made at) and {@code p} Pa
     * on {@code root}, with no vapour beside a one-root liquid. The array the model returned, at least
     * {@link #componentCount} long; the caller does not write it.
     */
    double[] logFugacity(double t, double p, double[] x, Root root, Prepared prepared);

    /**
     * A TP split: which of the gas, the oil and the aqueous liquid it holds, and each one's amounts on the model's
     * conserved basis (zeros where the phase is absent). The arrays are the split's own; the caller does not write them.
     */
    record Phases(boolean gas, boolean oil, boolean aqueous, double[] gasAmounts, double[] oilAmounts, double[] aqueousAmounts) {
    }

    /** The equilibrium TP split of liquid {@code x} at {@code t} K and {@code p} Pa (no checkpoint). */
    Phases flash(double t, double p, double[] x);
}
