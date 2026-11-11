package com.wormzjl.icheme.science.thermo.phase;

/**
 * How water takes part in a package's equilibrium (plan P2: "decide how water participates").
 *
 * <p>The decision encoded in P2: water is either absent from the package or the existing separate free-water
 * approximation. No P2 package puts water into the equation of state, and nothing in P2 models aqueous chemistry.
 * A request that would need it is refused with
 * {@link EquilibriumResult.UnsupportedReason#WATER_CHEMISTRY_NOT_MODELLED}: water co-present with a species the
 * package declares water-reactive ({@link PhaseContract#waterChemistrySpecies()}, e.g. ammonia, whose dissolution
 * hydrates the approximation cannot describe).</p>
 */
public enum WaterParticipation {
    /** Water is not a component of the package; a request carrying water names a species outside the package. */
    NONE,
    /**
     * Water is a basis component that forms its own pure liquid, never dissolved in the hydrocarbon phases and never
     * dissolving them (the fluid network's model since the free-water batch). Below its melting line the pure condensed
     * water is its ice Ih (WP4 of batch 2026-09-24-coolprop-low-temperature), past its data the models continue (owner
     * rule of 2026-10-16). Only for species declared immiscible with water; it describes no ammonia dissolution, no
     * aqueous non-ideality and no hydrates (decision 6 A: ice and a competing crystal coexist).
     */
    SEPARATE_FREE_WATER,
    /**
     * Water is a component of the cubic like every other (batch 2026-11-11-aqueous-solvents, W3a decision D1): it
     * dissolves and is dissolved, forms up to two liquids beside a gas, and its crystal is ice in the crystal
     * competition. The equilibrium is the cubic's alone; water's own enthalpy, entropy and volume take IF97's share on
     * the balance book ({@link PhaseState}, {@link WaterShare}). The basis keeps its order: the other compounds, then
     * water. A contract of this kind is built from a package with derived pairs ({@link PhaseContract#forDerivedPackage}).
     */
    IN_EQUATION_OF_STATE
}
