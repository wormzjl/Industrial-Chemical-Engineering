package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.fluid.thermo.FluidDomain;
import com.wormzjl.icheme.science.material.CrystalReference;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCoverage;
import java.util.ArrayList;
import java.util.HashSet;
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
 *   <li>Water: {@link WaterParticipation#NONE} names no water component; {@link WaterParticipation#SEPARATE_FREE_WATER}
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
 *        like), refused under {@link WaterParticipation#SEPARATE_FREE_WATER}
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
            throw new IllegalArgumentException("Separate free water names its basis component; no water names none");
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
     * The fluid network package: its PR78 components followed by {@code Water} as separate free water, qualified for
     * fluid-only competition, the network's envelope ({@link FluidDomain}), no water-chemistry species (the bundled packages hold
     * hydrocarbons and nitrogen only), graded estimated-with-declared-error.
     */
    public static PhaseContract forNetworkPackage(MaterialCatalog catalog, String packageId, String spineRevision) {
        var propertyPackage = catalog.requirePackage(packageId);
        List<String> basis = new ArrayList<>(propertyPackage.components());
        basis.add("Water");
        var identity = ThermoIdentity.of(catalog, packageId, CubicPhaseEvaluator.FAMILY, spineRevision,
                WaterParticipation.SEPARATE_FREE_WATER);
        var domain = PhaseDomain.of(FluidDomain.of(catalog, packageId), basis);
        var coverage = new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR,
                "PR78 with constant volume translation, every phase at the state pressure (P1: liquid density within "
                        + "2.6 % mean per isobar to 10 MPa for N2, CH4, C2H6, CO2 outside the critical band); the "
                        + "declared critical band is graded research-only by the engine (P3); the package's advisory evidence applies");
        return new PhaseContract(packageId, identity, basis, WaterParticipation.SEPARATE_FREE_WATER, "Water",
                Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), domain, coverage);
    }

    /**
     * The fluid network package's contract with the package's own spine: {@link #forNetworkPackage(MaterialCatalog,
     * String, String)} with the package's {@link MaterialCatalog#spineRevision}, or {@link ThermoIdentity#NO_SPINE} without one.
     * The identity is the engine's ({@link CubicPhaseEvaluator#FAMILY}); the package's crystals, if any, are not
     * qualified here (the network requests fluid-only competition until its crystal inventory exists; {@link #forPackage}
     * is the contract with the package's crystal set).
     */
    public static PhaseContract forNetworkPackage(MaterialCatalog catalog, String packageId) {
        String spine = catalog.spineRevision(packageId);
        return forNetworkPackage(catalog, packageId, spine.isEmpty() ? ThermoIdentity.NO_SPINE : spine);
    }

    /**
     * The package's contract for the equilibrium engine with its crystals: {@link #forNetworkPackage(MaterialCatalog, String)}
     * qualified in addition for the competition of the fluid phases with every crystal the package admits
     * ({@link #admittedCrystals}), as one whole set. A package that admits no crystal gets the fluid-only contract, so a
     * crystal request on it is refused as not qualified.
     */
    public static PhaseContract forPackage(MaterialCatalog catalog, String packageId) {
        PhaseContract fluid = forNetworkPackage(catalog, packageId);
        List<String> crystals = admittedCrystals(catalog, packageId);
        if (crystals.isEmpty()) return fluid;
        var competitions = new HashSet<>(fluid.qualifiedCompetitions());
        competitions.add(PhaseCompetition.withCrystals(crystals.toArray(String[]::new)));
        return new PhaseContract(fluid.packageId(), fluid.identity(), fluid.components(), fluid.water(), fluid.waterComponent(),
                competitions, fluid.waterChemistrySpecies(), fluid.domain(), fluid.fluidCoverage());
    }

    /**
     * The contract of a package with derived pairs (batch 2026-11-11-aqueous-solvents, W3a decisions D1, D6), the pilot's
     * kind: the package's compounds other than {@value CubicPhaseEvaluator#WATER} in package order, then water, as
     * {@link WaterParticipation#IN_EQUATION_OF_STATE} (without water in the package {@link WaterParticipation#NONE});
     * qualified for fluid-only competition and, when the package admits crystals ({@link #admittedCrystals}), for their
     * competition as one set (D5); the package envelope; graded estimated-with-declared-error. The identity:
     * the package revision is {@link MaterialCatalog#fluidRevision} with the derivation's identity
     * ({@code DerivedPairs.Result.identity}), the family {@link CubicPhaseEvaluator#DERIVED_FAMILY}, the spine revision,
     * and the water part {@code water-in-eos-v1:<water dataset revision>:<share revision>}.
     */
    public static PhaseContract forDerivedPackage(MaterialCatalog catalog, String packageId) {
        return forDerived(catalog, packageId, CubicPhaseEvaluator.derived(catalog, packageId, derivedBasis(catalog, packageId)));
    }

    /** The derived basis of a package: its compounds other than water in package order, then water. */
    public static List<String> derivedBasis(MaterialCatalog catalog, String packageId) {
        var pack = catalog.requirePackage(packageId);
        List<String> basis = new ArrayList<>(pack.components());
        boolean water = basis.remove(CubicPhaseEvaluator.WATER);
        if (water) basis.add(CubicPhaseEvaluator.WATER);
        return basis;
    }

    /** {@link #forDerivedPackage} on an evaluator already built from {@link CubicPhaseEvaluator#derived}. */
    public static PhaseContract forDerived(MaterialCatalog catalog, String packageId, CubicPhaseEvaluator evaluator) {
        if (evaluator.derivation() == null) throw new IllegalArgumentException("A derived contract needs a derived evaluator");
        var pack = catalog.requirePackage(packageId);
        List<String> basis = evaluator.components();
        boolean water = basis.contains(CubicPhaseEvaluator.WATER);
        String spine = catalog.spineRevision(packageId);
        String waterPart = ThermoIdentity.NO_WATER;
        if (water) {
            var property = pack.properties().get(pack.components().indexOf(CubicPhaseEvaluator.WATER));
            waterPart = ThermoIdentity.WATER_IN_EOS_FORMULATION + ":" + property.id() + "@" + catalog.propertyRevision(property.id())
                    + ":" + WaterShare.REVISION;
        }
        var identity = new ThermoIdentity(catalog.fluidRevision(packageId) + ";" + evaluator.derivation().identity(),
                CubicPhaseEvaluator.DERIVED_FAMILY, spine.isEmpty() ? ThermoIdentity.NO_SPINE : spine, waterPart);
        var domain = PhaseDomain.of(FluidDomain.of(catalog, packageId), basis);
        var coverage = new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR,
                "PR78 with the Huron-Vidal rule of pairs derived from group counts and Henry constants, water a component of the "
                        + "cubic with its IF97 share of enthalpy, entropy and volume (batch 2026-11-11-aqueous-solvents); "
                        + "the declared critical band is graded research-only by the engine; the package's advisory evidence applies");
        // W3a D5: the package's admitted crystals (ice among them) compete as one whole set, as on a network package.
        var competitions = new HashSet<PhaseCompetition>();
        competitions.add(PhaseCompetition.FLUID_ONLY);
        List<String> crystals = admittedCrystals(catalog, packageId);
        if (!crystals.isEmpty()) competitions.add(PhaseCompetition.withCrystals(crystals.toArray(String[]::new)));
        return new PhaseContract(packageId, identity, basis, water ? WaterParticipation.IN_EQUATION_OF_STATE : WaterParticipation.NONE,
                water ? CubicPhaseEvaluator.WATER : null, competitions, Set.of(), domain, coverage);
    }

    /**
     * The crystals of a package that may compete (P4 stage 2a): those the package declares whose records are usable
     * ({@link CrystalReference#usable()}) and graded {@code qualified} or {@code estimated_declared_error}; a
     * research-only or unavailable record is not admitted. In declaration order.
     */
    public static List<String> admittedCrystals(MaterialCatalog catalog, String packageId) {
        List<String> admitted = new ArrayList<>();
        for (String id : catalog.requirePackage(packageId).crystals()) {
            CrystalReference record = catalog.crystals().get(id);
            if (record == null || !record.usable()) continue;
            MaterialCoverage.Grade grade = record.coverage().grade();
            if (grade == MaterialCoverage.Grade.QUALIFIED || grade == MaterialCoverage.Grade.ESTIMATED_DECLARED_ERROR) admitted.add(id);
        }
        return admitted;
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
