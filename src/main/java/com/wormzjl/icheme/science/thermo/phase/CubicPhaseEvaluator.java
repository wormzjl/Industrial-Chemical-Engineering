package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PairInteractions;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.ThermoRefusal;
import com.wormzjl.icheme.science.thermo.TranslatedCubic;
import java.util.List;
import java.util.Objects;

/**
 * The fluid phase evaluator: the translated PR78 over a component basis, family {@value #FAMILY}. It turns (T, P,
 * amounts, root preference) into a {@link PhaseState} at that pressure; {@link PhaseState#kind()} states the root
 * situation actually met. {@link #family()} is part of the thermodynamic identity ({@link ThermoIdentity}): a change of
 * the residual formulation is a new family id.
 *
 * <p>The residual parts are {@link TranslatedCubic#residualOnly} (the shared {@link PengRobinsonKernel} with a
 * constant per-component volume translation), evaluated at the state pressure for every root, so the volume,
 * {@code ln phi} and volumetric derivatives are bit for bit those of a {@link TranslatedCubic} with the same
 * constants, and the fluid network's {@code HydrocarbonModel} agrees with them. The ideal-gas parts are the
 * {@link IdealGasFunction}s given at construction, one per component; {@link PackageBinding#evaluator} takes a spine
 * package's ({@code SpinePackageData}: spines, E-PPR78 pair interactions, the records' {@code volume_translation}
 * anchors). The catalog factories are {@link PackageBinding}'s: this class takes arrays and interfaces.</p>
 *
 * <p>The translation {@code c_i} adds {@code P c} to {@code h} and {@code g} and {@code P c_i/(R T)} to
 * {@code ln phi_i}, nothing to {@code s} or {@code c_p}; it cancels from every phase-equilibrium condition.</p>
 *
 * <p>The derived family {@value #DERIVED_FAMILY} (batch 2026-11-11-aqueous-solvents, W3a decisions D1, D2;
 * {@link PackageBinding#derivedEvaluator}): a list whose pairs come from {@link DerivedPairs} (the Huron-Vidal rule, a Mathias-Copeman alpha
 * per compound), water a component of the cubic with no volume translation, and water's own share of enthalpy,
 * entropy and volume ({@link WaterShare}) on each state's balance book, apart from {@code g} and {@code ln phi}.</p>
 *
 * <p>Errors: malformed input (wrong length, negative or non-finite amounts, no material, non-positive T or P) is an
 * {@link IllegalArgumentException}; a state outside a component's data is evaluated on the data's continuation (owner
 * rule of 2026-10-16), never refused; a state the model cannot evaluate (no physical root, a derivative at root
 * coalescence) is an {@link IllegalArgumentException} or {@link IllegalStateException} from the model.</p>
 */
public final class CubicPhaseEvaluator {
    public static final String FAMILY = "pr78_translated_v1";
    /** The family of {@link PackageBinding#derivedEvaluator}: PR78 with the Huron-Vidal rule of derived pairs and water's IF97 share. */
    public static final String DERIVED_FAMILY = "pr78_huron_vidal_water_share_v1";
    /** The water component's id in a derived list. */
    public static final String WATER = "Water";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private final String sourceId;
    private final List<String> names;
    private final List<ThermoComponent> components;
    private final IdealGasFunction[] idealGas;
    private final TranslatedCubic eos;
    private final double[] translations;
    private final String family;
    /** The derived family's water share, its water index and the derivation; null, -1, null otherwise. */
    private final WaterShare waterShare;
    private final int waterIndex;
    private final DerivedPairs.Result derivation;

    /**
     * @param sourceId the package the constants come from
     * @param interactions square, symmetric PR78 {@code k_ij}
     * @param translations constant volume translation per component, m3/mol ({@code v = v_PR + c})
     * @param idealGas one ideal-gas function per component, in basis order
     */
    public CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, double[][] interactions,
                               double[] translations, List<? extends IdealGasFunction> idealGas) {
        this(sourceId, components, TranslatedCubic.residualOnly(List.copyOf(components), interactions, translations),
                translations, idealGas);
    }

    /** The same evaluator on a pair-interaction model (the kernel's {@code TEMPERATURE_DEPENDENT_PAIRS}). */
    CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, PairInteractions interactions,
                                double[] translations, List<? extends IdealGasFunction> idealGas) {
        this(sourceId, components, TranslatedCubic.residualOnly(List.copyOf(components), interactions, translations),
                translations, idealGas);
    }

    private CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, TranslatedCubic eos,
                                double[] translations, List<? extends IdealGasFunction> idealGas) {
        this(sourceId, components, eos, translations, idealGas, FAMILY, null, -1, null);
    }

    /** The evaluator on a prebuilt residual cubic, of family {@code family} with water's share where it has one ({@link PackageBinding#derivedEvaluator}). */
    CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, TranslatedCubic eos,
                                double[] translations, List<? extends IdealGasFunction> idealGas, String family,
                                WaterShare waterShare, int waterIndex, DerivedPairs.Result derivation) {
        this.family = family;
        this.waterShare = waterShare;
        this.waterIndex = waterIndex;
        this.derivation = derivation;
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

    public String family() { return family; }
    /** The water component's index in a derived list with water, else -1. */
    public int waterComponent() { return waterShare == null ? -1 : waterIndex; }
    /** The derivation of a {@link PackageBinding#derivedEvaluator} evaluator, null otherwise. */
    public DerivedPairs.Result derivation() { return derivation; }
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
     * Scratch for one thread: the translated model's prepared temperature (its kernel workspace serves the values and
     * the derivative block alike), the kernel's derivative bundle, and the per-evaluation buffers.
     */
    public static final class Workspace {
        private final CubicPhaseEvaluator owner;
        private final TranslatedCubic.Workspace terms;
        private final PengRobinsonKernel.Derivatives mixtureDerivatives;
        private final double[] composition;
        private final double[] idealGibbs;
        private final WaterShare.Workspace shareScratch;
        private final double[] share = new double[WaterShare.SIZE];

        private Workspace(CubicPhaseEvaluator owner) {
            this.owner = owner;
            terms = owner.eos.newWorkspace();
            mixtureDerivatives = owner.eos.kernel().newDerivativesWithoutHeatCapacity();
            composition = new double[owner.names.size()];
            idealGibbs = new double[owner.names.size()];
            shareScratch = owner.waterShare == null ? null : owner.waterShare.newWorkspace();
        }

        /**
         * The compounds the owning flash's feed carries ({@link PengRobinsonKernel.Workspace#carry}), on the kernel
         * workspace: every value on them keeps its bits, and outside them the phase's {@code ln phi} and derivatives
         * are the kernel's fill (zero). A new workspace, and so a direct call, carries the whole list.
         */
        public void carry(double[] feed) {
            terms.carry(feed);
        }
    }

    public PhaseState evaluate(double temperature, double pressure, double[] amounts, Root preference,
                               Workspace workspace, PhaseState out) {
        Workspace w = own(workspace);
        prepare(temperature, pressure, amounts, preference, w);
        TranslatedCubic.Values values = eos.evaluateValues(temperature, pressure, amounts, preference, w.terms);
        fill(temperature, pressure, preference, values, w, Objects.requireNonNull(out, "out"));
        return out;
    }

    public PhaseDerivatives derivatives(double temperature, double pressure, double[] amounts, Root preference,
                                        Workspace workspace, PhaseDerivatives out) {
        Workspace w = own(workspace);
        Objects.requireNonNull(out, "out");
        if (out.componentCount() != names.size()) throw new IllegalArgumentException("Derivative basis mismatch");
        prepare(temperature, pressure, amounts, preference, w);
        // One kernel evaluation for the values and, on the same root, d ln phi/dn and the untranslated d ln phi/dT.
        TranslatedCubic.Values values = eos.evaluateValues(temperature, pressure, amounts, preference, w.terms,
                w.mixtureDerivatives);
        fill(temperature, pressure, preference, values, w, out.state());
        out.begin(family);
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
        double outside = PengRobinsonKernel.outsideSupportFill();
        for (int i = 0; i < n; i++) {
            if (!w.terms.carries(i)) {
                dt[i] = outside;
                dp[i] = outside;
                continue;
            }
            dt[i] = mixtureDt[i] - pressure * translations[i] / (rt * temperature);
            dp[i] = partialVolumes[i] / rt - 1 / pressure;
            if (!Double.isFinite(dt[i]) || !Double.isFinite(dp[i])) {
                throw new ThermoRefusal("Nonfinite chemical-potential derivative");
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
            throw new ThermoRefusal("Invalid phase heat capacity ", heatCapacity, "");
        }
        out.setCaloric(heatCapacity, values.enthalpyPressureDerivative());
        if (waterShare != null) {
            double[] s = w.share;
            out.setShare(s[WaterShare.H_T], s[WaterShare.H_P], s[WaterShare.S_T], s[WaterShare.S_P], s[WaterShare.V_T], s[WaterShare.V_P]);
        }
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
            throw new ThermoRefusal("Positive finite temperature and pressure required");
        }
        if (amounts == null || amounts.length != names.size()) throw new IllegalArgumentException("Composition basis mismatch");
        double total = 0.0;
        for (double amount : amounts) {
            if (!Double.isFinite(amount) || amount < 0.0) throw new ThermoRefusal("Invalid composition");
            total += amount;
        }
        if (!(total > 0.0)) throw new ThermoRefusal("Composition has no material");
        for (int i = 0; i < amounts.length; i++) w.composition[i] = amounts[i] / total;
        if (w.terms.temperature() != temperature) eos.prepare(temperature, w.terms);
    }

    private void fill(double temperature, double pressure, Root preference, TranslatedCubic.Values values,
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
        // The root's identification inputs (I-1): the kernel evaluation's own Z, a, b and da/dT, stored as they are.
        out.setIdentification(values.compressibility(), values.attraction(), values.coVolume(),
                values.attractionTemperatureDerivative());
        if (waterShare != null) {
            // The balance book's share (D2): from the phase's own untranslated v/b, never part of g or ln phi.
            double shift = 0.0, coVolume = 0.0;
            for (int i = 0; i < names.size(); i++) {
                shift += w.composition[i] * translations[i];
                coVolume += w.composition[i] * eos.kernel().coVolume(i);
            }
            waterShare.phase(temperature, pressure, w.composition[waterIndex], values.molarVolume() - shift, coVolume,
                    values.volumeTemperatureDerivative(), values.volumePressureDerivative(), w.shareScratch, w.share);
            out.setShare(w.share[WaterShare.H], w.share[WaterShare.S], w.share[WaterShare.V]);
        }
    }
}
