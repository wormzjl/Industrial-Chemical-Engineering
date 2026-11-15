package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.material.CoefficientIdealGas;
import com.wormzjl.icheme.science.material.CoefficientTranslations;
import com.wormzjl.icheme.science.material.CrystalReference;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCoverage;
import com.wormzjl.icheme.science.material.SpinePackageData;
import com.wormzjl.icheme.science.material.WaterRecords;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.TranslatedCubic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The equilibrium engine bound to the material catalog: every factory that builds an engine, a contract, an evaluator,
 * a crystal or an identity from a catalog package. It is the one class of the engine's package that reads
 * {@link MaterialCatalog} (batch 2026-11-13-codebase-review, C1b item 6, finding S05); the engine classes take arrays,
 * interfaces and the records of {@code science/material}. Each factory is the one that sat on its engine class, moved
 * operation for operation:
 * <ul>
 *   <li>{@link #equilibrium}, {@link #derivedEquilibrium}: {@code FluidTpEquilibrium.forPackage}, {@code forDerivedPackage};</li>
 *   <li>{@link #contract}, {@link #derivedContract}, {@link #derivedBasis}, {@link #admittedCrystals}:
 *   {@code PhaseContract.forPackage}, {@code forDerivedPackage} and {@code forDerived}, {@code derivedBasis},
 *   {@code admittedCrystals};</li>
 *   <li>{@link #evaluator}, {@link #fromPackage}, {@link #derivedEvaluator}: {@code CubicPhaseEvaluator.forPackage},
 *   {@code fromPackage}, {@code derived};</li>
 *   <li>{@link #crystal}: {@code CrystalPhaseEvaluator.forPackage};</li>
 *   <li>{@link #identity}: {@code ThermoIdentity.of}.</li>
 * </ul>
 */
public final class PackageBinding {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private PackageBinding() {}

    /**
     * The service of a package with its crystals (P4 stage 2a): {@link #contract}, the package's cubic evaluator
     * ({@link #evaluator}), the default settings, no water model (a request with water is {@code NOT_IMPLEMENTED}, with
     * crystals or without, unless the contract refuses it first as {@code WATER_CHEMISTRY_NOT_MODELLED}, when it also
     * holds a species that needs aqueous chemistry), and an evaluator of every crystal the contract admits.
     */
    public static FluidTpEquilibrium equilibrium(MaterialCatalog catalog, String packageId) {
        // W3b: a package that carries Water in its cubic is served on its derived contract.
        if (catalog.requirePackage(packageId).components().contains(CubicPhaseEvaluator.WATER)) return derivedEquilibrium(catalog, packageId);
        PhaseContract contract = contract(catalog, packageId);
        List<CrystalPhaseEvaluator> solids = new ArrayList<>();
        PhaseCompetition competition = contract.crystalCompetition();
        if (competition != null) {
            for (String crystal : competition.crystals()) solids.add(crystal(catalog, packageId, crystal));
        }
        return new FluidTpEquilibrium(contract, evaluator(catalog, packageId), FluidTpEquilibrium.Settings.DEFAULT, solids);
    }

    /**
     * The service of a package with derived pairs (batch 2026-11-11-aqueous-solvents, W3a): {@link #derivedContract} on
     * {@link #derivedEvaluator} over {@link #derivedBasis}, the default settings (water is a component of the cubic), an
     * evaluator of every crystal the contract admits (ice is water's, D5).
     */
    public static FluidTpEquilibrium derivedEquilibrium(MaterialCatalog catalog, String packageId) {
        CubicPhaseEvaluator evaluator = derivedEvaluator(catalog, packageId, derivedBasis(catalog, packageId));
        PhaseContract contract = derivedContract(catalog, packageId, evaluator);
        List<CrystalPhaseEvaluator> solids = new ArrayList<>();
        PhaseCompetition competition = contract.crystalCompetition();
        if (competition != null) {
            for (String crystal : competition.crystals()) solids.add(crystal(catalog, packageId, crystal));
        }
        return new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT, solids);
    }

    /**
     * The package's contract for the equilibrium engine: a package with water in its cubic on its derived contract
     * ({@link #derivedContract(MaterialCatalog, String)}); any other its PR78 components without water, qualified for
     * fluid-only competition and for the competition of the fluid phases with every crystal the package admits
     * ({@link #admittedCrystals}), as one whole set, the package envelope ({@link PhaseDomain}), graded
     * estimated-with-declared-error, its identity with the package's spine ({@link ThermoIdentity#NO_SPINE} without one)
     * and no water. A package that admits no crystal gets the fluid-only contract, so a crystal request on it is refused
     * as not qualified.
     */
    public static PhaseContract contract(MaterialCatalog catalog, String packageId) {
        var propertyPackage = catalog.requirePackage(packageId);
        if (propertyPackage.components().contains(CubicPhaseEvaluator.WATER)) return derivedContract(catalog, packageId);
        List<String> basis = List.copyOf(propertyPackage.components());
        String spine = catalog.spineRevision(packageId);
        var identity = identity(catalog, packageId, CubicPhaseEvaluator.FAMILY, spine.isEmpty() ? ThermoIdentity.NO_SPINE : spine);
        var domain = envelopeOf(catalog, packageId, basis);
        var coverage = new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR,
                "PR78 with constant volume translation, every phase at the state pressure (P1: liquid density within "
                        + "2.6 % mean per isobar to 10 MPa for N2, CH4, C2H6, CO2 outside the critical band); the "
                        + "declared critical band is graded research-only by the engine (P3); the package's advisory evidence applies");
        var competitions = new HashSet<PhaseCompetition>();
        competitions.add(PhaseCompetition.FLUID_ONLY);
        List<String> crystals = admittedCrystals(catalog, packageId);
        if (!crystals.isEmpty()) competitions.add(PhaseCompetition.withCrystals(crystals.toArray(String[]::new)));
        return new PhaseContract(packageId, identity, basis, WaterParticipation.NONE, null, competitions, Set.of(), domain, coverage);
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
    public static PhaseContract derivedContract(MaterialCatalog catalog, String packageId) {
        return derivedContract(catalog, packageId, derivedEvaluator(catalog, packageId, derivedBasis(catalog, packageId)));
    }

    /** The derived basis of a package: its compounds other than water in package order, then water. */
    public static List<String> derivedBasis(MaterialCatalog catalog, String packageId) {
        var pack = catalog.requirePackage(packageId);
        List<String> basis = new ArrayList<>(pack.components());
        boolean water = basis.remove(CubicPhaseEvaluator.WATER);
        if (water) basis.add(CubicPhaseEvaluator.WATER);
        return basis;
    }

    /** {@link #derivedContract(MaterialCatalog, String)} on an evaluator already built from {@link #derivedEvaluator}. */
    public static PhaseContract derivedContract(MaterialCatalog catalog, String packageId, CubicPhaseEvaluator evaluator) {
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
        var domain = envelopeOf(catalog, packageId, basis);
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
     * The package envelope over {@code basis}: the hull of its datasets' {@code fluid_domain} ranges. A package with a
     * compound whose dataset declares none is refused, with the fluid network's words ({@code FluidDomain.of}).
     */
    private static PhaseDomain envelopeOf(MaterialCatalog catalog, String packageId, List<String> basis) {
        var envelope = catalog.fluidValidity(packageId).orElseThrow(() -> new IllegalArgumentException(
                "Package " + packageId + " has a compound whose dataset declares no fluid_domain; the fluid network cannot read its data range"));
        return PhaseDomain.of(packageId, basis, envelope.minimumTemperature(), envelope.maximumTemperature(),
                envelope.minimumPressure(), envelope.maximumPressure());
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
     * The evaluator of a package over its whole component basis in package order. A package that declares a reference
     * spine (P3 WP5): the spines as the ideal-gas functions, the E-PPR78 pair interactions and the one-anchor-per-species
     * translations, all from {@link SpinePackageData#of}. A package without one: its {@code shifted_polynomial_5} fits on
     * the network's datum ({@link CoefficientIdealGas}), its constant interactions and the translations of
     * {@link CoefficientTranslations#of}.
     */
    public static CubicPhaseEvaluator evaluator(MaterialCatalog catalog, String packageId) {
        var pack = catalog.requirePackage(packageId);
        if (pack.spine().isEmpty()) {
            List<IdealGasFunction> gas = new java.util.ArrayList<>();
            for (String id : pack.components()) gas.add(CoefficientIdealGas.of(catalog, packageId, id));
            return fromPackage(pack, pack.components(), CoefficientTranslations.of(catalog, packageId), gas);
        }
        var data = SpinePackageData.of(catalog, packageId);
        return new CubicPhaseEvaluator(packageId, data.components(), data.interactions(), data.translations(), data.idealGas());
    }

    /**
     * The named components of a catalog package, in the given order, with their PR78 constants and interactions. The
     * translations are the caller's: their anchoring is a P3 decision (D1: saturated liquid at Tr = 0.8).
     */
    public static CubicPhaseEvaluator fromPackage(MaterialCatalog.Package propertyPackage, List<String> componentIds,
                                                  double[] translations, List<? extends IdealGasFunction> idealGas) {
        int n = componentIds.size();
        int[] index = new int[n];
        List<ThermoComponent> components = new java.util.ArrayList<>(n);
        for (int a = 0; a < n; a++) {
            index[a] = propertyPackage.components().indexOf(propertyPackage.canonicalId(componentIds.get(a)));
            if (index[a] < 0) throw new IllegalArgumentException("Component outside package: " + componentIds.get(a));
            var property = propertyPackage.properties().get(index[a]);
            var pr = property.pr();
            components.add(new ThermoComponent(propertyPackage.components().get(index[a]), pr.criticalTemperature(),
                    pr.criticalPressure(), pr.acentricFactor(), property.molecularWeight()));
        }
        double[][] interactions = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) interactions[a][b] = propertyPackage.interactions().get(index[a]).get(index[b]);
        }
        return new CubicPhaseEvaluator(propertyPackage.id(), components, interactions, translations, idealGas);
    }

    /**
     * The derived evaluator of package {@code packageId} over {@code componentIds} in that order (W3a D1): the kernel of
     * {@link DerivedPairs#derive}, one {@link CoefficientIdealGas} per compound (the network's sensible datum; on a package
     * with a reference spine its spine records, on the spine's formation datum as the crystals' anchors are), water
     * untranslated and every other compound translated so that its pure liquid on this kernel has its volume anchor's
     * volume (on a package with a reference spine the spine's own anchors, {@link SpinePackageData#anchors}; else the
     * catalog's volume reference, else the record's standard density; none where the anchor has no liquid root), and
     * water's share ({@link WaterShare}) when {@value CubicPhaseEvaluator#WATER} is in the list.
     */
    public static CubicPhaseEvaluator derivedEvaluator(MaterialCatalog catalog, String packageId, List<String> componentIds) {
        var result = DerivedPairs.derive(catalog, packageId, componentIds);
        var pack = catalog.requirePackage(packageId);
        List<String> ids = result.components();
        int n = ids.size();
        PengRobinsonKernel kernel = result.kernel(Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
        double[] tc = result.criticalTemperatures(), pc = result.criticalPressures(), omega = result.acentricFactors();
        List<ThermoComponent> components = new java.util.ArrayList<>(n);
        List<IdealGasFunction> idealGas = new java.util.ArrayList<>(n);
        double[] translations = new double[n];
        var calibrations = catalog.fluidData().volumeReferences();
        var scratch = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        int water = ids.indexOf(CubicPhaseEvaluator.WATER);
        var spine = pack.spine().isEmpty() ? null : SpinePackageData.of(catalog, packageId);
        for (int i = 0; i < n; i++) {
            var property = pack.properties().get(pack.components().indexOf(ids.get(i)));
            components.add(new ThermoComponent(ids.get(i), tc[i], pc[i], omega[i], property.molecularWeight()));
            idealGas.add(spine == null ? CoefficientIdealGas.of(catalog, packageId, ids.get(i))
                    : spine.idealGas().get(pack.components().indexOf(ids.get(i))));
            if (i == water) continue;
            if (spine != null) {
                // The pure liquid does not see the pairs: the spine's anchor translation is this kernel's too.
                translations[i] = spine.anchors().get(pack.components().indexOf(ids.get(i))).translation();
                continue;
            }
            var point = calibrations.get(property.component());
            double t = point == null ? property.standardTemperature() : point.temperatureKelvin();
            double target = point == null ? property.molecularWeight() / property.density() : point.molarVolumeCubicMetres();
            double p = point == null ? property.standardPressure() : point.pressurePascal();
            double[] pure = new double[n];
            pure[i] = 1.0;
            kernel.prepareTemperature(t, scratch);
            kernel.evaluate(t, p, pure, Root.LIQUID, scratch, evaluation);
            double raw = evaluation.compressibility() * R * t / p;
            boolean liquid = evaluation.physicalRootCount() > 1 || raw < PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO * kernel.coVolume(i);
            translations[i] = liquid ? target - raw : 0.0;
        }
        var eos = TranslatedCubic.residualOnly(components, kernel, translations);
        WaterShare share = null;
        if (water >= 0) share = new WaterShare(WaterRecords.constants(pack.water()), tc[water], pc[water],
                omega[water], result.mathiasCopeman()[water]);
        return new CubicPhaseEvaluator(packageId, components, eos, translations, idealGas, CubicPhaseEvaluator.DERIVED_FAMILY,
                share, water, result);
    }

    /**
     * A crystal the package declares, with the package's ideal gas of its species: its reference spine on a spine package,
     * its {@code shifted_polynomial_5} fit on the network's datum ({@link CoefficientIdealGas}) on any other, the same
     * function {@link #evaluator} gives the species (decision E27 of batch 2026-11-04-open-thermo-data: one crystal rule
     * for both ideal-gas sources). Anchored to that evaluator's liquid ({@link CrystalPhaseEvaluator#anchor}), the crystal
     * is on the family's own datum.
     */
    public static CrystalPhaseEvaluator crystal(MaterialCatalog catalog, String packageId, String crystalId) {
        var pkg = catalog.requirePackage(packageId);
        if (!pkg.crystals().contains(crystalId)) throw new IllegalArgumentException("Package " + packageId + " does not declare crystal " + crystalId);
        CrystalReference record = catalog.crystals().get(crystalId);
        IdealGasFunction idealGas = pkg.spine().isEmpty() ? CoefficientIdealGas.of(catalog, packageId, record.component())
                : catalog.requireSpine(packageId, record.component());
        return CrystalPhaseEvaluator.of(record, idealGas);
    }

    /**
     * The identity of a catalog package without water evaluated by {@code evaluatorFamily}; a package with water in its
     * cubic names its water part itself ({@link #derivedContract(MaterialCatalog, String, CubicPhaseEvaluator)}).
     */
    public static ThermoIdentity identity(MaterialCatalog catalog, String packageId, String evaluatorFamily, String spineRevision) {
        catalog.requirePackage(packageId);
        return new ThermoIdentity(catalog.fluidRevision(packageId), evaluatorFamily, spineRevision, ThermoIdentity.NO_WATER);
    }
}
