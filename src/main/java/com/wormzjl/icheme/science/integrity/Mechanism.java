package com.wormzjl.icheme.science.integrity;

/**
 * The damage mechanisms of a constructed wall (batch 2026-11-16-fouling-corrosion, plan section 6.2): M1 to M7 thin a
 * carbon steel wall at {@link CorrosionLaw#rate} times the material's {@link Metallurgy#factor}; {@link #OVERHEATING} (M8),
 * {@link #HYDROGEN_ATTACK} (M10) and {@link #OXYGEN_FIRE} (M11) act on the material itself, against its own limits;
 * {@link #CORROSIVE_AIR} (M9) acts from outside ({@link CorrosionLaw#outside}); {@link #EROSION} is the particle law
 * ({@link ErosionLaw}). The status line names the governing one. Saved by name later (a wall's state): append-only.
 */
public enum Mechanism {
    SULFIDATION, NAPHTHENIC_ACID, SOUR_WATER, WET_CO2, AMMONIUM_BISULFIDE, ACID_CONDENSATE, AERATED_WATER, OVERHEATING,
    CORROSIVE_AIR, HYDROGEN_ATTACK, OXYGEN_FIRE, EROSION
}
