package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
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
    /** The equilibrium engine's stability test on this kernel, with its trial for water where the list holds it. */
    private final TangentPlaneStability stability;
    /** The same test with the engine's larger budget, for a feed the first test leaves unresolved. */
    private final TangentPlaneStability widerStability;

    private V3PengRobinsonSession(V3PropertyPackage propertyPackage) {
        this.propertyPackage = Objects.requireNonNull(propertyPackage, "propertyPackage");
        this.kernel = kernelFor(propertyPackage);
        this.componentBasis = propertyPackage.componentBasis();
        int water = componentBasis.componentIds().indexOf("Water");
        TangentPlaneStability.Settings settings = TangentPlaneStability.Settings.DEFAULT;
        this.stability = new TangentPlaneStability(kernel, settings, water);
        this.widerStability = new TangentPlaneStability(kernel, new TangentPlaneStability.Settings(settings.instabilityTolerance(),
                settings.convergenceTolerance(), settings.newtonSwitch(), settings.maximumSuccessiveSubstitutions(),
                settings.accelerationCycle(), settings.maximumIterations() * FluidTpEquilibrium.UNRESOLVED_STABILITY_BUDGET_FACTOR,
                settings.trivialDistance(), settings.pureComponentTrialLimit(), settings.exhaustive()), water);
    }

    /**
     * The tangent-plane stability of the normalised feed {@code z} on this kernel, as the equilibrium engine tests it: an
     * unresolved test is repeated once with {@link FluidTpEquilibrium#UNRESOLVED_STABILITY_BUDGET_FACTOR} times the budget.
     */
    TangentPlaneStability.Result stability(double temperatureKelvin, double pressurePascal, double[] z, Session session) {
        try {
            TangentPlaneStability.Result result = stability.test(temperatureKelvin, pressurePascal, z, session.stabilityWorkspace());
            if (result.verdict() != TangentPlaneStability.Verdict.UNRESOLVED) return result;
            return widerStability.test(temperatureKelvin, pressurePascal, z, session.stabilityWorkspace());
        } catch (IllegalArgumentException exception) {
            throw new V3ThermoException(V3ThermoException.Code.DOMAIN, null,
                    "V3 feed stability test input is outside the property domain", exception);
        } catch (IllegalStateException exception) {
            throw new V3ThermoException(V3ThermoException.Code.EOS_ROOT_FAILURE, null,
                    "V3 feed stability test could not select a physical phase root", exception);
        }
    }

    /** The package's feed engine ({@link V3PropertyPackage#feedEngine}), or null. */
    V3FeedEngine feedEngine() {
        return propertyPackage.feedEngine();
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

    private V3PropertyComponent requireComponent(int publicComponent) {
        if (publicComponent < 0 || publicComponent >= componentBasis.componentCount()) {
            throw new IllegalArgumentException("V3 component property index is outside the public basis");
        }
        return propertyPackage.component(publicComponent);
    }

    Session newSession() { return new Session(this); }

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
        return idealGasMolarEnthalpy(temperatureKelvin, normalizedComposition, null);
    }

    double idealGasMolarEnthalpy(double temperatureKelvin, double[] normalizedComposition, Session session) {
        if (normalizedComposition.length != componentCount()) throw new IllegalArgumentException("V3 composition dimension differs");
        double[] pureEnthalpies = session == null ? null : session.idealGasEnthalpiesView(temperatureKelvin);
        double enthalpy = 0.0;
        for (int component = 0; component < componentCount(); component++) {
            double pure = pureEnthalpies == null ? componentIdealGasEnthalpy(component, temperatureKelvin) : pureEnthalpies[component];
            enthalpy += normalizedComposition[component] * pure;
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
        private final V3PengRobinsonSession owner;
        private final PengRobinsonKernel.Workspace workspace;
        private final PengRobinsonKernel.Evaluation liquid;
        private final PengRobinsonKernel.Evaluation vapor;
        private PengRobinsonKernel.Derivatives liquidDerivatives;
        private PengRobinsonKernel.Derivatives vaporDerivatives;
        private final PengRobinsonKernel kernel;
        private double enthalpyTemperature = Double.NaN;
        private double[] pureEnthalpies;
        /** Allocated on first use, as the derivatives are: most flashes run no stability test. */
        private TangentPlaneStability.Workspace stabilityWorkspace;
        private FluidTpEquilibrium.Workspace engineWorkspace;

        private Session(V3PengRobinsonSession owner) {
            this.owner = owner;
            this.kernel = owner.kernel;
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

        private TangentPlaneStability.Workspace stabilityWorkspace() {
            if (stabilityWorkspace == null) stabilityWorkspace = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT).newWorkspace();
            return stabilityWorkspace;
        }

        /** The scratch of {@code engine}; one session serves one model, so one engine. */
        FluidTpEquilibrium.Workspace engineWorkspace(V3FeedEngine engine) {
            if (engineWorkspace == null) engineWorkspace = engine.newWorkspace();
            return engineWorkspace;
        }

        double logFugacityCoefficient(Root phase, int component) {
            return evaluation(phase).logFugacityCoefficient(component);
        }

        /** Borrowed until this phase is evaluated again. The result constructor copies it. */
        double[] logFugacityCoefficientsView(Root phase) {
            return evaluation(phase).logFugacityCoefficientsView();
        }

        /** The same pure enthalpies serve both phases and the partial-molar derivatives at an exact temperature. */
        double[] idealGasEnthalpiesView(double temperatureKelvin) {
            if (enthalpyTemperature != temperatureKelvin) {
                if (pureEnthalpies == null) pureEnthalpies = new double[kernel.componentCount()];
                enthalpyTemperature = Double.NaN;
                for (int component = 0; component < pureEnthalpies.length; component++) {
                    pureEnthalpies[component] = owner.componentIdealGasEnthalpy(component, temperatureKelvin);
                }
                enthalpyTemperature = temperatureKelvin;
            }
            return pureEnthalpies;
        }

        double compressibilityFactor(Root phase) { return evaluation(phase).compressibility(); }
        double residualEnthalpyJoulesPerMol(Root phase) { return evaluation(phase).residualEnthalpyJoulesPerMol(); }
        int physicalRootCount(Root phase) { return evaluation(phase).physicalRootCount(); }
        /** The kernel's phase traits of the last evaluation on {@code phase}, at the state it was evaluated at. */
        com.wormzjl.icheme.science.thermo.PhaseTraits traits(Root phase, double temperatureKelvin, double pressurePascal) {
            return com.wormzjl.icheme.science.thermo.PhaseTraits.of(evaluation(phase), temperatureKelvin, pressurePascal);
        }
        double rootSeparation(Root phase) { return evaluation(phase).rootSeparation(); }
        void clear() {
            workspace.clear();
            enthalpyTemperature = Double.NaN;
        }
    }
}
