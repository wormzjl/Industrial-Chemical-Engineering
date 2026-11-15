package com.wormzjl.icheme.science.pollution;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The blast of a flammable cube (plan section 7). The heat of a flammable mixture at its LFL is nearly the same per
 * mole of mixture for hydrocarbons (Burgess-Wheeler: {@code LFL × LHV ≈ 44 kJ/mol}), so the combustible index alone
 * gives the energy. A cube burns at most the index 2 (about stoichiometric; above it oxygen limits). The TNT
 * equivalent uses a vapour-cloud yield of 3 %, and the Minecraft power scales with the cube root of the TNT mass
 * (Hopkinson), one TNT block (power 4) for 50 kg.
 */
public final class ExplosionLaw {
    /** The highest index a cube burns. */
    public static final double BURNABLE_INDEX = 2.0;
    /** Heat per mole of mixture at the LFL, J/mol. */
    public static final double ENERGY_PER_AIR_MOLE = 44_000.0;
    /** Share of the heat that becomes blast (vapour cloud). */
    public static final double YIELD = 0.03;
    /** Blast energy of TNT, J/kg. */
    public static final double TNT_ENERGY = 4.6e6;
    /** The TNT mass that gives {@link #REFERENCE_POWER}, kg. */
    public static final double REFERENCE_MASS = 50.0;
    /** Minecraft's power of one TNT block. */
    public static final double REFERENCE_POWER = 4.0;

    private ExplosionLaw() {
    }

    /** The index that burns: {@code min(index, 2)}. */
    public static double burntIndex(double index) {
        return Math.min(index, BURNABLE_INDEX);
    }

    /** The heat released by a cube of the given combustible index and air share, J. */
    public static double energy(double index, double airShare) {
        return burntIndex(index) * airShare * PollutionField.CUBE_AIR_MOLES * ENERGY_PER_AIR_MOLE;
    }

    /** The TNT equivalent of a heat, kg. */
    public static double tntKilograms(double energy) {
        return YIELD * energy / TNT_ENERGY;
    }

    /** The Minecraft explosion power of a TNT mass, capped: {@code min(cap, 4 (m / 50 kg)^(1/3))}. */
    public static double power(double tntKilograms, double cap) {
        if (!(tntKilograms > 0.0)) {
            return 0.0;
        }
        return Math.min(cap, REFERENCE_POWER * DeterministicMath.cbrt(tntKilograms / REFERENCE_MASS));
    }

    /** A cube is flammable at or above the LFL. */
    public static boolean flammable(double index) {
        return index >= 1.0;
    }
}
