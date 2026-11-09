package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.fluid.thermo.SpinePackageData;
import com.wormzjl.icheme.science.fluid.thermo.TranslatedPengRobinson;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PairInteractions;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import java.util.List;
import java.util.Objects;

/**
 * The fluid phase evaluator: the translated PR78 over a component basis, family {@value #FAMILY}. It turns (T, P,
 * amounts, root preference) into a {@link PhaseState} at that pressure; {@link PhaseState#kind()} states the root
 * situation actually met. {@link #family()} is part of the thermodynamic identity ({@link ThermoIdentity}): a change of
 * the residual formulation is a new family id.
 *
 * <p>The residual parts are {@link TranslatedPengRobinson#residualOnly} (the shared {@link PengRobinsonKernel} with a
 * constant per-component volume translation), evaluated at the state pressure for every root, so the volume,
 * {@code ln phi} and volumetric derivatives are bit for bit those of a {@link TranslatedPengRobinson} with the same
 * constants, and the fluid network's {@code HydrocarbonModel} agrees with them. The ideal-gas parts are the
 * {@link IdealGasFunction}s given at construction, one per component; {@link #forPackage} takes a spine package's
 * ({@link SpinePackageData}: spines, E-PPR78 pair interactions, the records' {@code volume_translation} anchors).</p>
 *
 * <p>The translation {@code c_i} adds {@code P c} to {@code h} and {@code g} and {@code P c_i/(R T)} to
 * {@code ln phi_i}, nothing to {@code s} or {@code c_p}; it cancels from every phase-equilibrium condition.</p>
 *
 * <p>Errors: malformed input (wrong length, negative or non-finite amounts, no material, non-positive T or P) is an
 * {@link IllegalArgumentException}; a state outside a component's data is evaluated on the data's continuation (owner
 * rule of 2026-10-16), never refused; a state the model cannot evaluate (no physical root, a derivative at root
 * coalescence) is an {@link IllegalArgumentException} or {@link IllegalStateException} from the model.</p>
 */
public final class CubicPhaseEvaluator {
    public static final String FAMILY = "pr78_translated_v1";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private final String sourceId;
    private final List<String> names;
    private final List<ThermoComponent> components;
    private final IdealGasFunction[] idealGas;
    private final TranslatedPengRobinson eos;
    private final double[] translations;

    /**
     * @param sourceId the package the constants come from
     * @param interactions square, symmetric PR78 {@code k_ij}
     * @param translations constant volume translation per component, m3/mol ({@code v = v_PR + c})
     * @param idealGas one ideal-gas function per component, in basis order
     */
    public CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, double[][] interactions,
                               double[] translations, List<? extends IdealGasFunction> idealGas) {
        this(sourceId, components, TranslatedPengRobinson.residualOnly(List.copyOf(components), interactions, translations),
                translations, idealGas);
    }

    /** The same evaluator on a pair-interaction model (the kernel's {@code TEMPERATURE_DEPENDENT_PAIRS}). */
    private CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, PairInteractions interactions,
                                double[] translations, List<? extends IdealGasFunction> idealGas) {
        this(sourceId, components, TranslatedPengRobinson.residualOnly(List.copyOf(components), interactions, translations),
                translations, idealGas);
    }

    private CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, TranslatedPengRobinson eos,
                                double[] translations, List<? extends IdealGasFunction> idealGas) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.components = List.copyOf(components);
        if (idealGas.size() != this.components.size()) throw new IllegalArgumentException("One ideal-gas function per component");
        this.idealGas = idealGas.toArray(new IdealGasFunction[0]);
        for (IdealGasFunction function : this.idealGas) Objects.requireNonNull(function, "idealGas");
        this.names = this.components.stream().map(ThermoComponent::id).toList();
        if (names.stream().distinct().count() != names.size()) throw new IllegalArgumentException("Duplicate component");
        this.eos = eos;
        this.translations = translations.clone();
    }

    /**
     * The evaluator of a package over its whole component basis in package order, exactly as the network's
     * {@code HydrocarbonModel} takes it. A package that declares a reference spine (P3 WP5): the spines as the ideal-gas
     * functions, the E-PPR78 pair interactions and the one-anchor-per-species translations, all from
     * {@link SpinePackageData#of}. A package without one: its {@code shifted_polynomial_5} fits on the network's datum,
     * its constant interactions and the model's translations ({@link HydrocarbonModel#coefficientEvaluator}).
     */
    public static CubicPhaseEvaluator forPackage(MaterialCatalog catalog, String packageId) {
        if (catalog.requirePackage(packageId).spine().isEmpty()) return new HydrocarbonModel(catalog, packageId).coefficientEvaluator();
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

    public String family() { return FAMILY; }
    public List<String> components() { return names; }
    public ThermoComponent component(int index) { return components.get(index); }
    public IdealGasFunction idealGas(int index) { return idealGas[index]; }
    /** The constant volume translation {@code c_i} of a component, m3/mol ({@code v = v_PR + c}). */
    public double translation(int index) { return translations[index]; }
    /** A copy of every translation, in basis order. */
    public double[] translations() { return translations.clone(); }
    /** The untranslated equation of state; stability tests and flash iterations run on it (the translation cancels). */
    public PengRobinsonKernel kernel() { return eos.kernel(); }

    public Workspace newWorkspace() { return new Workspace(this); }
    public int componentCount() { return names.size(); }
    public PhaseState newState() { return new PhaseState(names.size()); }
    public PhaseDerivatives newDerivatives() { return new PhaseDerivatives(names.size()); }

    /**
     * Scratch for one thread: the translated model's prepared temperature, the kernel's own workspace and derivative
     * bundle for the derivative block, and the per-evaluation buffers.
     */
    public static final class Workspace {
        private final CubicPhaseEvaluator owner;
        private final TranslatedPengRobinson.Workspace terms;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Derivatives mixtureDerivatives;
        private final double[] composition;
        private final double[] idealGibbs;

        private Workspace(CubicPhaseEvaluator owner) {
            this.owner = owner;
            terms = owner.eos.newWorkspace();
            mixture = owner.eos.kernel().newWorkspace();
            mixtureDerivatives = owner.eos.kernel().newDerivatives();
            composition = new double[owner.names.size()];
            idealGibbs = new double[owner.names.size()];
        }
    }

    public PhaseState evaluate(double temperature, double pressure, double[] amounts, Root preference,
                               Workspace workspace, PhaseState out) {
        Workspace w = own(workspace);
        prepare(temperature, pressure, amounts, preference, w);
        TranslatedPengRobinson.Values values = eos.evaluateValues(temperature, pressure, amounts, preference, w.terms);
        fill(temperature, pressure, preference, values, w, Objects.requireNonNull(out, "out"));
        return out;
    }

    public PhaseDerivatives derivatives(double temperature, double pressure, double[] amounts, Root preference,
                                        Workspace workspace, PhaseDerivatives out) {
        Workspace w = own(workspace);
        Objects.requireNonNull(out, "out");
        if (out.componentCount() != names.size()) throw new IllegalArgumentException("Derivative basis mismatch");
        prepare(temperature, pressure, amounts, preference, w);
        TranslatedPengRobinson.Values values = eos.evaluateValues(temperature, pressure, amounts, preference, w.terms);
        // The same root on the kernel's own workspace: d ln phi/dn and the untranslated d ln phi/dT.
        eos.kernel().evaluateDerivatives(temperature, pressure, amounts, preference, w.mixture, w.mixtureDerivatives);
        fill(temperature, pressure, preference, values, w, out.state());
        out.begin(FAMILY);
        int n = names.size();
        for (int i = 0; i < n; i++) {
            double[] row = out.logFugacityCompositionRowBuffer(i);
            System.arraycopy(w.mixtureDerivatives.dLogPhiDnRowView(i), 0, row, 0, n);
        }
        // ln phi_i carries +P c_i/(RT) from the translation, whose T and P derivatives are these two terms.
        double rt = R * temperature;
        double[] mixtureDt = w.mixtureDerivatives.dLogPhiDtView();
        double[] partialVolumes = values.partialMolarVolumesView();
        double[] dt = out.logFugacityTemperatureBuffer();
        double[] dp = out.logFugacityPressureBuffer();
        for (int i = 0; i < n; i++) {
            dt[i] = mixtureDt[i] - pressure * translations[i] / (rt * temperature);
            dp[i] = partialVolumes[i] / rt - 1 / pressure;
            if (!Double.isFinite(dt[i]) || !Double.isFinite(dp[i])) {
                throw new IllegalArgumentException("Nonfinite chemical-potential derivative");
            }
        }
        out.setVolumetric(values.volumeTemperatureDerivative(), values.volumePressureDerivative());
        double idealHeatCapacity = 0.0;
        for (int i = 0; i < n; i++) {
            double x = w.composition[i];
            if (x > 0.0) idealHeatCapacity += x * idealGas[i].heatCapacity(temperature);
        }
        // The residual-only model's heat capacity is the residual c_p; the whole c_p must be positive.
        double heatCapacity = idealHeatCapacity + values.heatCapacity();
        if (!(heatCapacity > 0.0) || !Double.isFinite(heatCapacity)) {
            throw new IllegalArgumentException("Invalid phase heat capacity " + heatCapacity);
        }
        out.setCaloric(heatCapacity, values.enthalpyPressureDerivative());
        return out;
    }

    private Workspace own(Workspace workspace) {
        if (!(Objects.requireNonNull(workspace, "workspace") instanceof Workspace w) || w.owner != this) {
            throw new IllegalArgumentException("Workspace belongs to another evaluator");
        }
        return w;
    }

    /** Validates the state, normalises the composition exactly as the kernel does, and prepares the temperature. */
    private void prepare(double temperature, double pressure, double[] amounts, Root preference, Workspace w) {
        Objects.requireNonNull(preference, "preference");
        if (!Double.isFinite(temperature) || !(temperature > 0.0) || !Double.isFinite(pressure) || !(pressure > 0.0)) {
            throw new IllegalArgumentException("Positive finite temperature and pressure required");
        }
        if (amounts == null || amounts.length != names.size()) throw new IllegalArgumentException("Composition basis mismatch");
        double total = 0.0;
        for (double amount : amounts) {
            if (!Double.isFinite(amount) || amount < 0.0) throw new IllegalArgumentException("Invalid composition");
            total += amount;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("Composition has no material");
        for (int i = 0; i < amounts.length; i++) w.composition[i] = amounts[i] / total;
        if (w.terms.temperature() != temperature) eos.prepare(temperature, w.terms);
    }

    private void fill(double temperature, double pressure, Root preference, TranslatedPengRobinson.Values values,
                      Workspace w, PhaseState out) {
        double rt = R * temperature;
        double[] logPhi = values.logFugacityCoefficientsView();
        double idealEnthalpy = 0.0;
        double idealEntropy = 0.0;
        double gibbs = 0.0;
        double weightedLogPhi = 0.0;
        for (int i = 0; i < names.size(); i++) {
            double x = w.composition[i];
            if (!(x > 0.0)) {
                w.idealGibbs[i] = Double.NaN;
                continue;
            }
            double h = idealGas[i].enthalpy(temperature);
            double s = idealGas[i].entropy(temperature, pressure);
            double g = h - temperature * s;
            double logX = DeterministicMath.log(x);
            w.idealGibbs[i] = g;
            idealEnthalpy += x * h;
            idealEntropy += x * (s - R * logX);
            gibbs += x * (g + rt * (logX + logPhi[i]));
            weightedLogPhi += x * logPhi[i];
        }
        // Residual-only model: its enthalpy is h^R + P c exactly, and g^R = R T sum x ln phi (translation included).
        double residualEnthalpy = values.molarEnthalpy();
        double residualEntropy = (residualEnthalpy - rt * weightedLogPhi) / temperature;
        PhaseKind kind = values.physicalRootCount() > 1
                ? (preference == Root.VAPOR ? PhaseKind.VAPOR : PhaseKind.LIQUID)
                : PhaseKind.FLUID;
        out.set(kind, null, preference, values.physicalRootCount(), temperature, pressure, values.molarVolume(),
                idealEnthalpy, residualEnthalpy, idealEntropy, residualEntropy, gibbs, w.composition, logPhi,
                w.idealGibbs);
    }
}
