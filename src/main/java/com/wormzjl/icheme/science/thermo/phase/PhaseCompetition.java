package com.wormzjl.icheme.science.thermo.phase;

import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Which phases compete in an equilibrium calculation: the fluid phases always, plus the named pure crystals. A package
 * declares the competitions it is qualified for ({@link PhaseContract#qualifiedCompetitions()}) as whole sets, and a
 * request outside the package's set is refused ({@link EquilibriumResult.UnsupportedReason#PHASE_COMPETITION_NOT_QUALIFIED})
 * rather than answered with fewer phases. Gas hydrates are not modelled (owner decision O1: the hydrate request and its
 * refusal are gone).
 *
 * <p>{@link #FLUID_ONLY} is an explicitly constrained calculation: its result says nothing about solids and is never
 * labelled a complete equilibrium ({@link EquilibriumResult#solidsAssessed()} is false). {@link #FLUID_AND_CRYSTALS}
 * asks for the fluid phases and every crystal the package admits, without naming them: the service resolves it against
 * its contract ({@link PhaseContract#resolve}) to the package's qualified crystal set, and its results name that
 * resolved competition; on a package that admits no crystal it stays unresolved and is refused as not qualified.
 * {@link #FLUID_CARRYING_CRYSTALS} asks for the fluid phases a step carries where the package's crystals would form
 * (owner decision D23: only a vessel's refresh books a crystal): no crystal competes, and the phase a crystal would take
 * is no fluid phase of its own, so a dense fluid stays one liquid and a vapour keeps a stand-in of crystal species only.
 * Its result says nothing about solids either; a package that admits no crystal is not qualified for it.</p>
 *
 * @param crystals crystal ids, sorted; empty for fluid phases only
 * @param packageCrystals the {@link #FLUID_AND_CRYSTALS} request: all of the package's crystals, unnamed (then
 *        {@code crystals} is empty)
 * @param carried the {@link #FLUID_CARRYING_CRYSTALS} request (then {@code crystals} is empty and
 *        {@code packageCrystals} false)
 */
public record PhaseCompetition(SortedSet<String> crystals, boolean packageCrystals, boolean carried) {
    public static final PhaseCompetition FLUID_ONLY = new PhaseCompetition(new TreeSet<>(), false);
    /** The fluid phases and every crystal the package admits; resolved by the service's contract. */
    public static final PhaseCompetition FLUID_AND_CRYSTALS = new PhaseCompetition(new TreeSet<>(), true);
    /** The fluid phases, the crystal species carried condensed: no crystal is formed or assessed. */
    public static final PhaseCompetition FLUID_CARRYING_CRYSTALS = new PhaseCompetition(new TreeSet<>(), false, true);

    public PhaseCompetition {
        TreeSet<String> copy = new TreeSet<>();
        for (String crystal : Objects.requireNonNull(crystals, "crystals")) {
            if (crystal == null || crystal.isBlank()) throw new IllegalArgumentException("Blank crystal id");
            copy.add(crystal);
        }
        if (packageCrystals && !copy.isEmpty()) {
            throw new IllegalArgumentException("The package-crystals request names no crystal");
        }
        if (carried && (packageCrystals || !copy.isEmpty())) {
            throw new IllegalArgumentException("The carried request competes no crystal");
        }
        crystals = java.util.Collections.unmodifiableSortedSet(copy);
    }

    public PhaseCompetition(SortedSet<String> crystals, boolean packageCrystals) {
        this(crystals, packageCrystals, false);
    }

    /** The fluid phases and these pure crystals. */
    public static PhaseCompetition withCrystals(String... crystals) {
        return new PhaseCompetition(new TreeSet<>(Set.of(crystals)), false);
    }

    public boolean fluidOnly() { return crystals.isEmpty() && !packageCrystals && !carried; }

    /** Whether crystals compete: named ones, or the package's ({@link #FLUID_AND_CRYSTALS}). */
    public boolean crystalsCompete() { return !crystals.isEmpty() || packageCrystals; }

    @Override
    public String toString() {
        if (fluidOnly()) return "fluid-only";
        if (carried) return "fluid+carried-crystals";
        if (packageCrystals) return "fluid+package-crystals";
        return "fluid+" + String.join("+", crystals);
    }
}
