package com.wormzjl.icheme.science.thermo.phase;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a package promises the equilibrium engine: its shared component basis, how water takes part, which phase
 * competitions it is qualified for, which species need water chemistry, the envelope of its data (outside it an answer is
 * graded extrapolated, never refused), how good a fluid answer inside that envelope is, and its thermodynamic identity.
 *
 * <p>Rules the constructor enforces:</p>
 * <ul>
 *   <li>Water: {@link WaterParticipation#NONE} names no water component; {@link WaterParticipation#IN_EQUATION_OF_STATE}
 *   names one in the basis. {@link #waterChemistrySpecies()} lie in the basis and are not the water itself.</li>
 *   <li>Competitions: at least one.</li>
 *   <li>The domain names exactly the basis.</li>
 *   <li>The fluid coverage grade is one an answer can have ({@code QUALIFIED}, {@code ESTIMATED_DECLARED_ERROR} or
 *   {@code RESEARCH_ONLY}).</li>
 * </ul>
 *
 * @param components the shared conserved basis, EOS components and water (if any) alike
 * @param waterComponent the id of water in {@code components}, {@code null} for {@link WaterParticipation#NONE}
 * @param qualifiedCompetitions each competition the package is qualified for, as a whole
 * @param waterChemistrySpecies species whose presence together with water needs aqueous chemistry (ammonia and the
 *        like), refused where water is present
 * @param fluidCoverage the grade and evidence of a converged fluid-phase answer inside the envelope
 */
public record PhaseContract(String packageId, ThermoIdentity identity, List<String> components,
                            WaterParticipation water, String waterComponent,
                            Set<PhaseCompetition> qualifiedCompetitions, Set<String> waterChemistrySpecies,
                            PhaseDomain domain, EquilibriumResult.Coverage fluidCoverage) {
    public PhaseContract {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(identity, "identity");
        components = List.copyOf(components);
        if (components.isEmpty() || components.stream().distinct().count() != components.size()) {
            throw new IllegalArgumentException("A contract needs a nonempty basis without duplicates");
        }
        Objects.requireNonNull(water, "water");
        if ((water == WaterParticipation.NONE) != (waterComponent == null)
                || waterComponent != null && !components.contains(waterComponent)) {
            throw new IllegalArgumentException("Water in the basis names its component; no water names none");
        }
        qualifiedCompetitions = Set.copyOf(qualifiedCompetitions);
        if (qualifiedCompetitions.isEmpty()) throw new IllegalArgumentException("A package qualifies at least one competition");
        waterChemistrySpecies = Set.copyOf(waterChemistrySpecies);
        for (String species : waterChemistrySpecies) {
            if (!components.contains(species) || species.equals(waterComponent)) {
                throw new IllegalArgumentException("Water-chemistry species must be basis components other than water: " + species);
            }
        }
        Objects.requireNonNull(domain, "domain");
        if (!domain.components().equals(components)) throw new IllegalArgumentException("Domain basis differs from the contract basis");
        Objects.requireNonNull(fluidCoverage, "fluidCoverage");
        if (!fluidCoverage.grade().answer()) throw new IllegalArgumentException("The fluid coverage must be a grade an answer can have");
    }

    /**
     * The qualified competition with crystals that holds the most of them (the package's whole crystal set), or
     * {@code null} when the contract qualifies none.
     */
    public PhaseCompetition crystalCompetition() {
        PhaseCompetition best = null;
        for (PhaseCompetition competition : qualifiedCompetitions) {
            if (competition.crystals().isEmpty()) continue;
            if (best == null || competition.crystals().size() > best.crystals().size()
                    || competition.crystals().size() == best.crystals().size() && competition.toString().compareTo(best.toString()) < 0) {
                best = competition;
            }
        }
        return best;
    }

    /**
     * The competition a request means: {@link PhaseCompetition#FLUID_AND_CRYSTALS} is the package's
     * {@link #crystalCompetition()} (itself when the contract qualifies none, and then refused); any other competition
     * is itself.
     */
    public PhaseCompetition resolve(PhaseCompetition competition) {
        if (!competition.packageCrystals()) return competition;
        PhaseCompetition crystals = crystalCompetition();
        return crystals == null ? competition : crystals;
    }

    public int index(String component) { return components.indexOf(component); }
    /** The water slot of the basis, -1 without water. */
    public int waterIndex() { return waterComponent == null ? -1 : components.indexOf(waterComponent); }
    /** Whether a request under {@code competition} is answered; the carried request wherever a crystal competition is. */
    public boolean qualifies(PhaseCompetition competition) {
        return competition.carried() ? crystalCompetition() != null : qualifiedCompetitions.contains(competition);
    }
}
