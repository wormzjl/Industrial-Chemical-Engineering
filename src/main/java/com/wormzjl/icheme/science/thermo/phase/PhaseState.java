package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.Arrays;
import java.util.Objects;

/**
 * One evaluated phase at (T, P): its kind, composition, molar volume, caloric properties and fugacity coefficients.
 *
 * <p>Caller-owned and mutable: an evaluator fills it with {@link #set} and the next evaluation into the same
 * instance overwrites it, so a solver keeps a few per workspace and allocates nothing per evaluation. {@link #copy()}
 * returns a frozen copy, which is what an {@link EquilibriumResult} holds; {@link #set} on a frozen state throws.</p>
 *
 * <p>Conventions (the data spine of the unified multiphase thermodynamics plan, section 4): the ideal-gas parts come
 * from the evaluator's {@link com.wormzjl.icheme.science.thermo.IdealGasFunction}s, whose enthalpy at 298.15 K is
 * the standard enthalpy of formation and whose entropy at 298.15 K and 1e5 Pa is the standard entropy. For a fluid
 * mixture: {@code h = sum x_i h_ig,i(T) + h^R}, {@code s = sum x_i [s_ig,i(T, P) - R ln x_i] + s^R},
 * {@code g = sum x_i mu_i} with {@code mu_i = g_ig,i(T, P) + R T (ln x_i + ln phi_i)}, {@code u = h - P v}. The
 * residual parts are at (T, P) against the ideal gas at the same (T, P) and composition, so
 * {@code g^R = R T sum x_i ln phi_i} and {@code s^R = (h^R - g^R)/T}. Components with {@code x_i = 0} are absent:
 * their ideal-gas functions are not evaluated, their chemical potential is minus infinity.</p>
 *
 * <p>Two books (batch 2026-11-11-aqueous-solvents, W3a decision D2). The fields above are the equilibrium book: the
 * cubic (or the crystal) alone, self-consistent ({@code g = h - T s}); stability, flash, Newton and crystal anchors read
 * only it. Water's own share of enthalpy, entropy and volume ({@link #setShare}, {@link #shareEnthalpy()} and the rest;
 * zero unless the evaluator carries water in the equation of state) is kept apart and never enters {@code g} or
 * {@code ln phi}; the balance book ({@link #balanceEnthalpy()}, {@link #balanceEntropy()}, {@link #balanceVolume()},
 * {@link #balanceInternalEnergy()}) is the equilibrium book plus the share, and is what the energy and volume balances
 * read. With a zero share the balance book equals the equilibrium book bit for bit.</p>
 *
 * <p>For a pure crystal the composition is the single component of the crystal's own basis, and
 * {@code ln phi = (g_s - g_ig(T, P))/(R T)}: the fugacity of the solid against the ideal gas, so
 * {@link #chemicalPotential(int)} compares directly with a fluid's.</p>
 */
public final class PhaseState {
    private final double[] composition;
    private final double[] logFugacityCoefficients;
    private final double[] idealGasGibbsEnergies;
    private PhaseKind kind;
    private String crystal;
    private Root root;
    private int physicalRootCount;
    private double temperature = Double.NaN;
    private double pressure = Double.NaN;
    private double molarVolume;
    private double idealGasEnthalpy;
    private double residualEnthalpy;
    private double idealGasEntropy;
    private double residualEntropy;
    private double gibbsEnergy;
    private double shareEnthalpy, shareEntropy, shareVolume;
    private boolean frozen;

    public PhaseState(int componentCount) {
        if (componentCount < 1) throw new IllegalArgumentException("A phase needs at least one component");
        composition = new double[componentCount];
        logFugacityCoefficients = new double[componentCount];
        idealGasGibbsEnergies = new double[componentCount];
    }

    /**
     * Fills the state. Arrays are copied, nothing is allocated.
     *
     * @param crystal the crystal id for {@link PhaseKind#SOLID}, {@code null} for every fluid kind
     * @param root the root evaluated for a fluid, {@code null} for a solid
     * @param physicalRootCount physical roots of the cubic at this state, 0 for a solid
     * @param composition mole fractions, summing to one
     * @param logFugacityCoefficients {@code ln phi_i}; any value for an absent component
     * @param idealGasGibbsEnergies {@code g_ig,i(T, P) = h_ig,i(T) - T s_ig,i(T, P)} of each present component,
     *        {@code NaN} for an absent one
     */
    public void set(PhaseKind kind, String crystal, Root root, int physicalRootCount,
                    double temperature, double pressure, double molarVolume,
                    double idealGasEnthalpy, double residualEnthalpy,
                    double idealGasEntropy, double residualEntropy, double gibbsEnergy,
                    double[] composition, double[] logFugacityCoefficients, double[] idealGasGibbsEnergies) {
        if (frozen) throw new IllegalStateException("A frozen phase state cannot be refilled");
        Objects.requireNonNull(kind, "kind");
        if ((kind == PhaseKind.SOLID) != (crystal != null)) {
            throw new IllegalArgumentException("A solid names its crystal and a fluid names none");
        }
        if ((kind == PhaseKind.SOLID) != (root == null)) {
            throw new IllegalArgumentException("A fluid names its root and a solid names none");
        }
        if (!(temperature > 0.0) || !(pressure > 0.0) || !(molarVolume > 0.0) || !Double.isFinite(temperature)
                || !Double.isFinite(pressure) || !Double.isFinite(molarVolume) || !Double.isFinite(idealGasEnthalpy)
                || !Double.isFinite(residualEnthalpy) || !Double.isFinite(idealGasEntropy)
                || !Double.isFinite(residualEntropy) || !Double.isFinite(gibbsEnergy)) {
            throw new IllegalArgumentException("A phase state must be finite with positive T, P and volume");
        }
        if (composition.length != this.composition.length || logFugacityCoefficients.length != this.composition.length
                || idealGasGibbsEnergies.length != this.composition.length) {
            throw new IllegalArgumentException("Phase state basis mismatch");
        }
        this.kind = kind;
        this.crystal = crystal;
        this.root = root;
        this.physicalRootCount = physicalRootCount;
        this.temperature = temperature;
        this.pressure = pressure;
        this.molarVolume = molarVolume;
        this.idealGasEnthalpy = idealGasEnthalpy;
        this.residualEnthalpy = residualEnthalpy;
        this.idealGasEntropy = idealGasEntropy;
        this.residualEntropy = residualEntropy;
        this.gibbsEnergy = gibbsEnergy;
        this.shareEnthalpy = 0.0;
        this.shareEntropy = 0.0;
        this.shareVolume = 0.0;
        System.arraycopy(composition, 0, this.composition, 0, composition.length);
        System.arraycopy(logFugacityCoefficients, 0, this.logFugacityCoefficients, 0, composition.length);
        System.arraycopy(idealGasGibbsEnergies, 0, this.idealGasGibbsEnergies, 0, composition.length);
    }

    /**
     * Water's share of the balance book (D2), J/mol, J/(mol K), m3/mol, after {@link #set} (which clears it). Never part
     * of the Gibbs energy.
     */
    public void setShare(double enthalpy, double entropy, double volume) {
        if (frozen) throw new IllegalStateException("A frozen phase state cannot be refilled");
        requireFilled();
        if (!Double.isFinite(enthalpy) || !Double.isFinite(entropy) || !Double.isFinite(volume))
            throw new IllegalArgumentException("A water share must be finite");
        shareEnthalpy = enthalpy;
        shareEntropy = entropy;
        shareVolume = volume;
        if (!(molarVolume + volume > 0.0)) throw new IllegalArgumentException("A phase's balance volume must be positive");
    }

    /** A frozen copy: every value and array copied, {@link #set} refused. */
    public PhaseState copy() {
        requireFilled();
        PhaseState copy = new PhaseState(composition.length);
        copy.set(kind, crystal, root, physicalRootCount, temperature, pressure, molarVolume, idealGasEnthalpy,
                residualEnthalpy, idealGasEntropy, residualEntropy, gibbsEnergy, composition, logFugacityCoefficients,
                idealGasGibbsEnergies);
        copy.shareEnthalpy = shareEnthalpy;
        copy.shareEntropy = shareEntropy;
        copy.shareVolume = shareVolume;
        copy.frozen = true;
        return copy;
    }

    public boolean frozen() { return frozen; }
    public boolean filled() { return kind != null; }
    public int componentCount() { return composition.length; }
    public PhaseKind kind() { requireFilled(); return kind; }
    /** The crystal id of a solid, {@code null} for a fluid. */
    public String crystal() { requireFilled(); return crystal; }
    /** The cubic root that was evaluated, {@code null} for a solid. */
    public Root root() { requireFilled(); return root; }
    /** Physical roots of the cubic at this composition, temperature and pressure; 0 for a solid. */
    public int physicalRootCount() { requireFilled(); return physicalRootCount; }
    public double temperature() { requireFilled(); return temperature; }
    public double pressure() { requireFilled(); return pressure; }
    /** m3/mol, the physical (translated) volume. */
    public double molarVolume() { requireFilled(); return molarVolume; }
    /** {@code P v / (R T)} of the physical volume. */
    public double compressibilityFactor() {
        requireFilled();
        return pressure * molarVolume / (PengRobinsonKernel.GAS_CONSTANT * temperature);
    }
    /** J/mol. */
    public double molarEnthalpy() { requireFilled(); return idealGasEnthalpy + residualEnthalpy; }
    public double idealGasEnthalpy() { requireFilled(); return idealGasEnthalpy; }
    /** For a fluid {@code h^R} including the translation's {@code P c}; for a solid {@code h_s - h_ig}. */
    public double residualEnthalpy() { requireFilled(); return residualEnthalpy; }
    /** J/(mol K). */
    public double molarEntropy() { requireFilled(); return idealGasEntropy + residualEntropy; }
    /** Includes the ideal entropy of mixing. */
    public double idealGasEntropy() { requireFilled(); return idealGasEntropy; }
    public double residualEntropy() { requireFilled(); return residualEntropy; }
    /** J/mol, {@code sum x_i mu_i}. */
    public double molarGibbsEnergy() { requireFilled(); return gibbsEnergy; }
    /** J/mol, {@code h - P v}. */
    public double molarInternalEnergy() { requireFilled(); return molarEnthalpy() - pressure * molarVolume; }
    /** Water's share of the molar enthalpy (D2), J/mol; zero without water in the equation of state. */
    public double shareEnthalpy() { requireFilled(); return shareEnthalpy; }
    /** Water's share of the molar entropy, J/(mol K). */
    public double shareEntropy() { requireFilled(); return shareEntropy; }
    /** Water's share of the molar volume, m3/mol. */
    public double shareVolume() { requireFilled(); return shareVolume; }
    /** The balance book's molar enthalpy, {@link #molarEnthalpy()} plus the share, J/mol. */
    public double balanceEnthalpy() { requireFilled(); return idealGasEnthalpy + residualEnthalpy + shareEnthalpy; }
    /** The balance book's molar entropy, J/(mol K). */
    public double balanceEntropy() { requireFilled(); return idealGasEntropy + residualEntropy + shareEntropy; }
    /** The balance book's molar volume, m3/mol. */
    public double balanceVolume() { requireFilled(); return molarVolume + shareVolume; }
    /** The balance book's molar internal energy, {@code h - P v} of the balance book, J/mol. */
    public double balanceInternalEnergy() { requireFilled(); return balanceEnthalpy() - pressure * balanceVolume(); }
    public double moleFraction(int component) { requireFilled(); return composition[component]; }
    /** The caller must not mutate it; refilled by the next evaluation into this state. */
    public double[] compositionView() { requireFilled(); return composition; }
    public double logFugacityCoefficient(int component) { requireFilled(); return logFugacityCoefficients[component]; }
    /** The caller must not mutate it; refilled by the next evaluation into this state. */
    public double[] logFugacityCoefficientsView() { requireFilled(); return logFugacityCoefficients; }
    /**
     * {@code mu_i = g_ig,i(T, P) + R T (ln x_i + ln phi_i)}, J/mol, on the spine's reference; minus infinity for an
     * absent component.
     */
    public double chemicalPotential(int component) {
        requireFilled();
        double x = composition[component];
        if (!(x > 0.0)) return Double.NEGATIVE_INFINITY;
        return idealGasGibbsEnergies[component]
                + PengRobinsonKernel.GAS_CONSTANT * temperature * (DeterministicMath.log(x) + logFugacityCoefficients[component]);
    }

    private void requireFilled() {
        if (kind == null) throw new IllegalStateException("Phase state has not been evaluated");
    }

    @Override
    public String toString() {
        if (kind == null) return "PhaseState[empty]";
        return "PhaseState[" + kind + (crystal == null ? "" : " " + crystal) + ", T=" + temperature + ", P=" + pressure
                + ", v=" + molarVolume + ", h=" + molarEnthalpy() + ", s=" + molarEntropy() + ", x="
                + Arrays.toString(composition) + ']';
    }
}
