package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.List;
import java.util.Objects;

/**
 * One registered V3 property package and its allocation-free Peng-Robinson arithmetic.
 *
 * <p>The mutable kernel workspace belongs to the V3 caller through {@link V3ThermoWorkspace}; no state is shared
 * between concurrent solves.</p>
 */
final class V3PengRobinsonSession {
    private final V3PropertyPackage propertyPackage;
    private final PengRobinsonKernel kernel;
    private final V3ComponentBasis componentBasis;
    /** {@link #constantClassicalInteractions}, formed once; null where the kernel is not a constant classical one. */
    private final double[][] constantMatrix;

    private V3PengRobinsonSession(V3PropertyPackage propertyPackage) {
        this.propertyPackage = Objects.requireNonNull(propertyPackage, "propertyPackage");
        this.kernel = kernelFor(propertyPackage);
        this.componentBasis = propertyPackage.componentBasis();
        this.constantMatrix = constantMatrix(propertyPackage);
    }

    /**
     * The package's kernel over the numerical box. A catalog package takes the kernel of its derived pairs
     * ({@link V3PropertyPackage#derivedPairs}, batch 2026-11-11-aqueous-solvents W5 D1): the network's pairs and
     * critical constants, so a sheet flashes a column's feed and solves the column on one model. A list without an
     * activity pair runs the kernel's classical branch. A package built outside a catalog (a test table) keeps the
     * classical kernel of its authored matrix.
     */
    static PengRobinsonKernel kernelFor(V3PropertyPackage propertyPackage) {
        Objects.requireNonNull(propertyPackage, "propertyPackage");
        var derived = propertyPackage.derivedPairs();
        if (derived != null) {
            return derived.kernel(NumericalBox.MINIMUM_TEMPERATURE, NumericalBox.MAXIMUM_TEMPERATURE,
                    NumericalBox.MINIMUM_PRESSURE, NumericalBox.MAXIMUM_PRESSURE);
        }
        int count = propertyPackage.componentBasis().componentCount();
        double[] criticalTemperatures = new double[count];
        double[] criticalPressures = new double[count];
        double[] acentricFactors = new double[count];
        for (int component = 0; component < count; component++) {
            V3PropertyComponent properties = propertyPackage.component(component);
            criticalTemperatures[component] = properties.criticalTemperatureKelvin();
            criticalPressures[component] = properties.criticalPressurePascal();
            acentricFactors[component] = properties.acentricFactor();
        }
        return new PengRobinsonKernel(criticalTemperatures, criticalPressures, acentricFactors,
                propertyPackage.binaryInteractions(),
                NumericalBox.MINIMUM_TEMPERATURE, NumericalBox.MAXIMUM_TEMPERATURE,
                NumericalBox.MINIMUM_PRESSURE, NumericalBox.MAXIMUM_PRESSURE);
    }

    static V3PengRobinsonSession registeredPackage(String packageId) {
        return new V3PengRobinsonSession(V3CatalogPropertyPackage.require(packageId));
    }

    String packageId() { return propertyPackage.packageId(); }
    String datasetRevision() { return propertyPackage.datasetRevision(); }
    V3ComponentBasis componentBasis() { return componentBasis; }
    int componentCount() { return kernel.componentCount(); }
    double minimumPressurePascal() { return propertyPackage.minimumPressurePascal(); }
    double maximumPressurePascal() { return propertyPackage.maximumPressurePascal(); }
    boolean outsideDataRange(double temperature, double pressure) {
        return temperature < propertyPackage.minimumTemperatureKelvin() || temperature > propertyPackage.maximumTemperatureKelvin()
                || pressure < propertyPackage.minimumPressurePascal() || pressure > propertyPackage.maximumPressurePascal();
    }
    List<String> advisoryEvidence() { return propertyPackage.advisoryEvidence(); }

    double componentMolecularWeightKgPerMol(int publicComponent) {
        return requireComponent(publicComponent).molecularWeightKgPerMol();
    }

    double componentCriticalTemperatureKelvin(int publicComponent) {
        return requireComponent(publicComponent).criticalTemperatureKelvin();
    }

    double componentCriticalPressurePascal(int publicComponent) {
        return requireComponent(publicComponent).criticalPressurePascal();
    }

    double componentAcentricFactor(int publicComponent) {
        return requireComponent(publicComponent).acentricFactor();
    }

    double componentStandardLiquidDensityKgPerCubicMetre(int publicComponent) {
        return requireComponent(publicComponent).standardLiquidDensityKgPerCubicMetre();
    }

    double[][] binaryInteractions() {
        return propertyPackage.binaryInteractions();
    }

    /**
     * The constant classical matrix the kernel evaluates, or null where the kernel is not one: an activity pair, a
     * {@code k_ij(T)}, or a Mathias-Copeman alpha. The native outer pass computes PR78 on a constant matrix only.
     */
    double[][] constantClassicalInteractions() {
        return constantMatrix;
    }

    private static double[][] constantMatrix(V3PropertyPackage propertyPackage) {
        var derived = propertyPackage.derivedPairs();
        if (derived == null) return propertyPackage.binaryInteractions();
        var excess = derived.excess();
        if (excess.nrtlPairCount() > 0) return null;
        for (double[] alpha : derived.mathiasCopeman()) if (alpha != null) return null;
        int n = excess.componentCount();
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (excess.interactionPair(i, j) >= 0 || excess.interactionSlope(i, j) != 0.0) return null;
                matrix[i][j] = matrix[j][i] = excess.interaction(i, j);
            }
        }
        return matrix;
    }

    private V3PropertyComponent requireComponent(int publicComponent) {
        if (publicComponent < 0 || publicComponent >= componentBasis.componentCount()) {
            throw new IllegalArgumentException("V3 component property index is outside the public basis");
        }
        return propertyPackage.component(publicComponent);
    }

    Session newSession() { return new Session(kernel); }

    V3CrudeFeed crudeFeed(String assayId) {
        return propertyPackage.crudeFeed(assayId);
    }

    void wilsonK(double temperatureKelvin, double pressurePascal, double[] output) {
        try {
            kernel.wilsonK(temperatureKelvin, pressurePascal, output);
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, null,
                    "V3 Wilson K input is outside the property domain", exception);
        }
    }

    void evaluate(
            double temperatureKelvin, double pressurePascal, double[] composition, Root phase, Session session) {
        try {
            kernel.evaluate(temperatureKelvin, pressurePascal, composition, phase,
                    session.workspace, session.evaluation(phase));
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, phase,
                    "V3 Peng-Robinson input is outside the property domain", exception);
        } catch (IllegalStateException exception) {
            throw new V3ThermoException(V3ThermoException.Code.EOS_ROOT_FAILURE, phase,
                    "V3 Peng-Robinson could not select a physical phase root", exception);
        }
    }

    /** Differentiates the same evaluation {@link #evaluate} performs, with the same failure classification. */
    void evaluateDerivatives(
            double temperatureKelvin, double pressurePascal, double[] composition, Root phase, Session session) {
        try {
            kernel.evaluateDerivatives(temperatureKelvin, pressurePascal, composition, phase,
                    session.workspace, session.derivatives(phase));
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, phase,
                    "V3 Peng-Robinson derivative input is outside the property domain", exception);
        } catch (IllegalStateException exception) {
            throw new V3ThermoException(V3ThermoException.Code.EOS_ROOT_FAILURE, phase,
                    "V3 Peng-Robinson could not differentiate a physical phase root", exception);
        }
    }

    double idealGasMolarEnthalpy(double temperatureKelvin, double[] normalizedComposition) {
        if (normalizedComposition.length != componentCount()) throw new IllegalArgumentException("V3 composition dimension differs");
        double enthalpy = 0.0;
        for (int component = 0; component < componentCount(); component++) {
            enthalpy += normalizedComposition[component]
                    * componentIdealGasEnthalpy(component, temperatureKelvin);
        }
        return enthalpy;
    }

    /** Mixture ideal-gas heat capacity in J/(mol K); the ideal-gas half of a phase heat capacity. */
    double idealGasMolarHeatCapacity(double temperatureKelvin, double[] normalizedComposition) {
        if (normalizedComposition.length != componentCount()) throw new IllegalArgumentException("V3 composition dimension differs");
        double capacity = 0.0;
        for (int component = 0; component < componentCount(); component++) {
            capacity += normalizedComposition[component]
                    * propertyPackage.component(component).idealGasHeatCapacity(caloricTemperature(temperatureKelvin));
        }
        return capacity;
    }

    /** Pure-component ideal-gas enthalpy about the shared datum; the ideal-gas half of a partial molar one. */
    double componentIdealGasEnthalpy(int publicComponent, double temperatureKelvin) {
        var component = propertyPackage.component(publicComponent);
        double edge = caloricTemperature(temperatureKelvin);
        double h = component.idealGasEnthalpy(edge);
        return edge == temperatureKelvin ? h : h + component.idealGasHeatCapacity(edge) * (temperatureKelvin - edge);
    }

    private double caloricTemperature(double temperatureKelvin) {
        return Math.clamp(temperatureKelvin, propertyPackage.minimumTemperatureKelvin(), propertyPackage.maximumTemperatureKelvin());
    }

    static final class Session {
        private final PengRobinsonKernel.Workspace workspace;
        private final PengRobinsonKernel.Evaluation liquid;
        private final PengRobinsonKernel.Evaluation vapor;
        private PengRobinsonKernel.Derivatives liquidDerivatives;
        private PengRobinsonKernel.Derivatives vaporDerivatives;
        private final PengRobinsonKernel kernel;

        private Session(PengRobinsonKernel kernel) {
            this.kernel = kernel;
            workspace = kernel.newWorkspace();
            liquid = kernel.newEvaluation();
            vapor = kernel.newEvaluation();
        }

        private PengRobinsonKernel.Evaluation evaluation(Root phase) {
            return phase == Root.LIQUID ? liquid : vapor;
        }

        /** Allocated on first use: a solve that never asks for derivatives never pays for their storage. */
        private PengRobinsonKernel.Derivatives derivatives(Root phase) {
            if (phase == Root.LIQUID) {
                if (liquidDerivatives == null) liquidDerivatives = kernel.newDerivatives();
                return liquidDerivatives;
            }
            if (vaporDerivatives == null) vaporDerivatives = kernel.newDerivatives();
            return vaporDerivatives;
        }

        PengRobinsonKernel.Derivatives derivativesOf(Root phase) {
            return derivatives(phase);
        }

        double logFugacityCoefficient(Root phase, int component) {
            return evaluation(phase).logFugacityCoefficient(component);
        }

        double[] logFugacityCoefficients(Root phase) {
            return evaluation(phase).logFugacityCoefficients();
        }

        double compressibilityFactor(Root phase) { return evaluation(phase).compressibility(); }
        double residualEnthalpyJoulesPerMol(Root phase) { return evaluation(phase).residualEnthalpyJoulesPerMol(); }
        int physicalRootCount(Root phase) { return evaluation(phase).physicalRootCount(); }
        double rootSeparation(Root phase) { return evaluation(phase).rootSeparation(); }
        void clear() { workspace.clear(); }
    }
}
