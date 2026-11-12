package com.wormzjl.icheme.science.thermo.phase;

/**
 * How water takes part in a package's equilibrium (plan P2: "decide how water participates").
 *
 * <p>Water is either absent from the package or a component of the cubic (batch 2026-11-11-aqueous-solvents, W3b: the
 * separate free-water approximation of P2 is gone). Nothing models aqueous chemistry; a request that would need it is
 * refused with
 * {@link EquilibriumResult.UnsupportedReason#WATER_CHEMISTRY_NOT_MODELLED}: water co-present with a species the
 * package declares water-reactive ({@link PhaseContract#waterChemistrySpecies()}, e.g. ammonia, whose dissolution
 * hydrates the approximation cannot describe).</p>
 */
public enum WaterParticipation {
    /** Water is not a component of the package; a request carrying water names a species outside the package. */
    NONE,
    /**
     * Water is a component of the cubic like every other (batch 2026-11-11-aqueous-solvents, W3a decision D1): it
     * dissolves and is dissolved, forms up to two liquids beside a gas, and its crystal is ice in the crystal
     * competition. The equilibrium is the cubic's alone; water's own enthalpy, entropy and volume take IF97's share on
     * the balance book ({@link PhaseState}, {@link WaterShare}). The basis keeps its order: the other compounds, then
     * water. A contract of this kind is built from a package with derived pairs ({@link PhaseContract#forDerivedPackage}).
     */
    IN_EQUATION_OF_STATE
}
