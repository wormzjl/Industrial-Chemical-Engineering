package com.wormzjl.icheme.science.material;

import com.wormzjl.icheme.science.thermo.PairInteractions;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The thermodynamic inputs of a package that declares a reference spine, resolved once from the catalog (P3 WP5, batch
 * 2026-09-24-coolprop-low-temperature, plan {@code P3_PILOT_ENGINE_PLAN.md} section 5, decisions D6 and D10). The fluid
 * network's {@code HydrocarbonModel} and the phase engine's {@code PackageBinding.evaluator} both build from this
 * one object, so the two evaluate the same components, pair interactions and translations and differ only by the
 * energy datum.
 *
 * <ul>
 *   <li>Components: the package's PR78 constants in package order.</li>
 *   <li>Pair interactions: the E-PPR78 rule pairs of {@link MaterialCatalog.Package#groupContributions()} as
 *   group terms (their constant matrix entry is 0.0 and is not their {@code k_ij}), every other nonzero constant as a
 *   constant pair; the kernel runs {@code TEMPERATURE_DEPENDENT_PAIRS} on it.</li>
 *   <li>Volume translations, one anchor per species (WP3's precedence rule): the property record's
 *   {@code volume_translation} ({@link MaterialCatalog#volumeTranslation}, {@code c = v_ref - v_PR,L(T, Psat_ref)} on
 *   the untranslated one-component kernel); else the global {@code liquid_calibration.json} point, else the record's
 *   standard density, each evaluated on the untranslated PR78 at the anchor's own temperature and pressure, as the
 *   coefficient path does.</li>
 *   <li>Ideal gas: the package's {@link ReferenceSpine} per component, the only ideal-gas source (D6).</li>
 *   <li>Energy datum (D10): the network subtracts {@code DeltafH_i = spine_i.formationEnthalpy()} per species, so its
 *   enthalpies stay sensible (zero at 298.15 K). The formation datum is carried as numbers: {@link #formationEnthalpies()}
 *   over the package components with its revision {@link #formationRevision()}; the network builds its
 *   {@code EnergyReference} from them ({@code HydrocarbonModel.formationReference()}, {@code formationDataQualified}):
 *   {@code EnergyReference.sensible(basis).rebase(E, n, formationReference)} is the engine's energy of a network energy
 *   {@code E}. Free water is not in this basis; it keeps the network's sensible
 *   free-water reference.</li>
 * </ul>
 */
public final class SpinePackageData {
    /** The revision prefix of {@link #formationRevision()}; the package's spine revision follows it. */
    public static final String FORMATION_DATUM = "icheme:formation-298.15K-spine-v1";

    /** Where a component's volume translation is anchored. */
    public enum AnchorSource { VOLUME_TRANSLATION, CALIBRATION_POINT, STANDARD_DENSITY }

    /**
     * A component's translation anchor: the liquid molar volume {@code molarVolume} at ({@code temperature},
     * {@code pressure}) that the translation {@code translation} (m3/mol) reproduces on the kernel's liquid root.
     */
    public record Anchor(AnchorSource source, double temperature, double pressure, double molarVolume, double translation) {}

    private final String packageId;
    private final List<ThermoComponent> components;
    private final List<ReferenceSpine> spines;
    private final PairInteractions interactions;
    private final List<Anchor> anchors;
    private final double[] formationEnthalpies;
    private final String spineRevision;

    private SpinePackageData(String packageId, List<ThermoComponent> components, List<ReferenceSpine> spines,
                             PairInteractions interactions, List<Anchor> anchors, double[] formationEnthalpies,
                             String spineRevision) {
        this.packageId = packageId;
        this.components = List.copyOf(components);
        this.spines = List.copyOf(spines);
        this.interactions = interactions;
        this.anchors = List.copyOf(anchors);
        this.formationEnthalpies = formationEnthalpies.clone();
        this.spineRevision = spineRevision;
    }

    /** Resolves a package that declares a reference spine; a package without one is refused. */
    public static SpinePackageData of(MaterialCatalog catalog, String packageId) {
        Objects.requireNonNull(catalog, "catalog");
        var propertyPackage = catalog.requirePackage(packageId);
        if (propertyPackage.spine().isEmpty())
            throw new IllegalArgumentException("Package declares no reference spine: " + packageId);
        int count = propertyPackage.components().size();
        List<ThermoComponent> components = new ArrayList<>(count);
        List<ReferenceSpine> spines = new ArrayList<>(count);
        double[] formation = new double[count];
        double[][] constants = new double[count][count];
        for (int i = 0; i < count; i++) {
            var property = propertyPackage.properties().get(i);
            if (property.pr() == null) throw new IllegalArgumentException("Package " + packageId + ": component "
                    + property.component() + " has no PR78 constants");
            components.add(new ThermoComponent(property.component(), property.pr().criticalTemperature(),
                    property.pr().criticalPressure(), property.pr().acentricFactor(), property.molecularWeight()));
            var spine = catalog.requireSpine(packageId, property.component());
            spines.add(spine);
            formation[i] = spine.formationEnthalpy().value();
            for (int j = 0; j < count; j++) constants[i][j] = propertyPackage.interactions().get(i).get(j);
        }
        // WP3's wiring (P3_PILOT_PACKAGE_AND_SPINE.md section 6): rule pairs as group terms, other nonzero constants.
        var builder = PairInteractions.builder(count);
        var rule = propertyPackage.groupContributions();
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                var pair = rule == null ? null : rule.pair(i, j).orElse(null);
                if (pair != null) builder.groupTerms(i, j, pair.weights(), pair.aPascal(), pair.exponents());
                else if (constants[i][j] != 0.0) builder.constant(i, j, constants[i][j]);
            }
        }
        List<Anchor> anchors = new ArrayList<>(count);
        var calibrations = catalog.fluidData().volumeReferences();
        PengRobinsonKernel pure = null;
        for (int i = 0; i < count; i++) {
            var property = propertyPackage.properties().get(i);
            var translation = catalog.volumeTranslation(packageId, property.component());
            if (translation.isPresent()) {
                var anchor = translation.get();
                anchors.add(new Anchor(AnchorSource.VOLUME_TRANSLATION, anchor.temperatureKelvin(), anchor.pressurePascal(),
                        anchor.molarVolumeCubicMetresPerMol(), anchor.shift(property.pr())));
                continue;
            }
            // The coefficient path's rule for a component without its own anchor, at the anchor's own (T, P).
            var point = calibrations.get(property.component());
            double t = point == null ? property.standardTemperature() : point.temperatureKelvin();
            double target = point == null ? property.molecularWeight() / property.density() : point.molarVolumeCubicMetres();
            double p = point == null ? property.standardPressure() : point.pressurePascal();
            if (pure == null) pure = CoefficientTranslations.pureKernel(components, constants);
            double rawVolume = CoefficientTranslations.pureLiquidVolume(pure, i, t, p);
            anchors.add(new Anchor(point == null ? AnchorSource.STANDARD_DENSITY : AnchorSource.CALIBRATION_POINT, t, p, target,
                    target - rawVolume));
        }
        return new SpinePackageData(packageId, components, spines, builder.build(), anchors, formation,
                catalog.spineRevision(packageId));
    }

    /** The package components with their PR78 constants, in package order. */
    public List<ThermoComponent> components() { return components; }
    /** The reference spine of each component, in package order: the ideal-gas functions. */
    public List<ReferenceSpine> idealGas() { return spines; }
    /** The pair-interaction model: E-PPR78 rule pairs as group terms, other nonzero constants. */
    public PairInteractions interactions() { return interactions; }
    /** Each component's translation anchor, in package order. */
    public List<Anchor> anchors() { return anchors; }
    /** A copy of every translation, m3/mol, in package order. */
    public double[] translations() {
        double[] translations = new double[anchors.size()];
        for (int i = 0; i < translations.length; i++) translations[i] = anchors.get(i).translation();
        return translations;
    }
    /** A copy of the formation enthalpies at 298.15 K, J/mol: the datum offsets the network subtracts. */
    public double[] formationEnthalpies() { return formationEnthalpies.clone(); }
    /**
     * The revision of the formation datum, {@value #FORMATION_DATUM}{@code :<spine revision>}: the network's
     * {@code EnergyReference} of {@link #formationEnthalpies()} over the package components carries it.
     */
    public String formationRevision() { return FORMATION_DATUM + ":" + spineRevision; }
}
