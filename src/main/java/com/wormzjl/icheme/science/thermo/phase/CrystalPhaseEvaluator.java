package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.fluid.thermo.CoefficientIdealGas;
import com.wormzjl.icheme.science.material.CrystalReference;
import com.wormzjl.icheme.science.material.JaegerSpanGibbsFunction;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.Objects;

/**
 * The {@link SolidPhaseEvaluator} of a crystal record: the record's model ({@link CrystalReference}, for CO2 the Gibbs
 * function of Jaeger and Span 2012) anchored at the triple point to the fluid family's pure liquid.
 * {@link #anchor(CubicPhaseEvaluator)} evaluates the family's pure liquid of the species at the record's triple point
 * (T_t, P_t) and returns its chemical potential mu_L and enthalpy h_L; the crystal then satisfies g_s(T_t, P_t) = mu_L
 * and s_s(T_t, P_t) = (h_L - mu_L)/T_t - dH_fus/T_t (Jaeger and Span's equations 19 and 20), which for a Gibbs-function
 * record fix its integration constants g0 and g1. The evaluator fills a one-component {@link PhaseKind#SOLID} state
 * naming the record's id: v, h and s of the anchored crystal, split against the species' ideal gas (the package's
 * spine or fit, {@link #forPackage}) as {@code h = h_ig(T) + (h - h_ig)} and {@code s = s_ig(T, P) + (s - s_ig)}, and
 * {@code ln phi = (g_s - g_ig(T, P)) / (R T)}, so that {@link PhaseState#chemicalPotential(int)} equals g_s and compares
 * directly with a fluid phase's. Past the record's range and the ideal-gas function's, both continue; an unusable
 * record is refused at construction. {@link #anchored(TriplePointAnchor)} solves the integration constants once and
 * returns an immutable {@link Anchored} crystal, which an equilibrium service holds per crystal so a flash never
 * re-solves g0 and g1; {@link #evaluate(double, double, TriplePointAnchor, PhaseState)} reuses the last anchored view
 * while the caller passes an equal anchor. Derivatives ({@link #derivatives}): {@code d ln phi/dT = -(h_s - h_ig)/(R T^2)},
 * {@code d ln phi/dP = v_s/(R T) - 1/P}, {@code dv/dT = alpha v}, {@code dv/dP = -kappa v}, {@code c_p},
 * {@code dh/dP = v (1 - alpha T)}; no composition derivatives.
 */
public final class CrystalPhaseEvaluator implements SolidPhaseEvaluator {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final double[] ONE = {1.0};

    private final CrystalReference crystal;
    private final IdealGasFunction idealGas;
    private final String family;
    /** The last anchored view {@link #evaluate} built: reused while the caller passes an equal anchor. */
    private volatile Anchored last;

    private CrystalPhaseEvaluator(CrystalReference crystal, IdealGasFunction idealGas) {
        this.crystal = Objects.requireNonNull(crystal, "crystal");
        this.idealGas = Objects.requireNonNull(idealGas, "idealGas");
        if (!crystal.usable()) throw new IllegalStateException(crystal.id() + ": crystal record is not usable, missing " + crystal.missing());
        this.family = "crystal_" + crystal.kind().key() + "_triple_point_anchored_v1";
    }

    /** The crystal of {@code crystal} with the species' ideal-gas function {@code idealGas}. */
    public static CrystalPhaseEvaluator of(CrystalReference crystal, IdealGasFunction idealGas) {
        return new CrystalPhaseEvaluator(crystal, idealGas);
    }

    /**
     * A crystal the package declares, with the package's ideal gas of its species: its reference spine on a spine package,
     * its {@code shifted_polynomial_5} fit on the network's datum ({@link CoefficientIdealGas}) on any other, the same
     * function {@link CubicPhaseEvaluator#forPackage} gives the species (decision E27 of batch 2026-11-04-open-thermo-data:
     * one crystal rule for both ideal-gas sources). Anchored to that evaluator's liquid ({@link #anchor}), the crystal is on
     * the family's own datum.
     */
    public static CrystalPhaseEvaluator forPackage(MaterialCatalog catalog, String packageId, String crystalId) {
        var pkg = catalog.requirePackage(packageId);
        if (!pkg.crystals().contains(crystalId)) throw new IllegalArgumentException("Package " + packageId + " does not declare crystal " + crystalId);
        CrystalReference record = catalog.crystals().get(crystalId);
        IdealGasFunction idealGas = pkg.spine().isEmpty() ? CoefficientIdealGas.of(catalog, packageId, record.component())
                : catalog.requireSpine(packageId, record.component());
        return new CrystalPhaseEvaluator(record, idealGas);
    }

    /** The record this evaluator anchors. */
    public CrystalReference record() { return crystal; }

    /** The species' ideal-gas function the state is split against (the package's spine or fit): {@code ln phi} refers to it. */
    public IdealGasFunction idealGas() { return idealGas; }

    public String family() { return family; }
    public String crystal() { return crystal.id(); }
    public String component() { return crystal.component(); }

    /**
     * The anchor of this crystal on a fluid family: the family's pure liquid of {@link #component()} at the record's
     * triple point. The family must carry the component and have a liquid root there distinct from its vapour root.
     */
    public TriplePointAnchor anchor(CubicPhaseEvaluator fluid) {
        int index = fluid.components().indexOf(component());
        if (index < 0) throw new IllegalArgumentException("Fluid family " + fluid.family() + " does not carry " + component());
        double[] amounts = new double[fluid.componentCount()];
        amounts[index] = 1.0;
        double t = crystal.anchor().temperature();
        double p = crystal.anchor().pressure();
        PhaseState liquid = fluid.evaluate(t, p, amounts, Root.LIQUID, fluid.newWorkspace(), fluid.newState());
        if (liquid.kind() != PhaseKind.LIQUID)
            throw new IllegalStateException(fluid.family() + ": no liquid root of " + component() + " distinct from the vapour at the triple point "
                    + t + " K, " + p + " Pa (got " + liquid.kind() + ")");
        return new TriplePointAnchor(component(), t, p, liquid.chemicalPotential(index), liquid.molarEnthalpy());
    }

    /** The anchored crystal's properties at (T, P), including c_p, expansion and compressibility. */
    public CrystalReference.State properties(double temperature, double pressure, TriplePointAnchor anchor) {
        return view(anchor).properties(temperature, pressure);
    }

    @Override
    public PhaseState evaluate(double temperature, double pressure, TriplePointAnchor anchor, PhaseState out) {
        return view(anchor).evaluate(temperature, pressure, out);
    }

    /** The anchored crystal and its derivatives at (T, P) into {@code out} (one component); see the class comment. */
    public PhaseDerivatives derivatives(double temperature, double pressure, TriplePointAnchor anchor, PhaseDerivatives out) {
        return view(anchor).derivatives(temperature, pressure, out);
    }

    /**
     * The crystal anchored at {@code anchor}, its integration constants solved once: the view an equilibrium service
     * keeps per thermodynamic identity. The anchor must name the record's species and triple point.
     */
    public Anchored anchored(TriplePointAnchor anchor) {
        requireAnchor(anchor);
        return new Anchored(anchor, 0.0, 0.0, 0.0);
    }

    /**
     * {@link #anchored(TriplePointAnchor)} on a cubic whose liquid of the species carries a share on the balance book
     * (batch 2026-11-11-aqueous-solvents, W3a D5: ice on a cubic with water in it), the liquid's share at the anchor
     * {@code (h, s, v)} in J/mol, J/(mol K) and m3/mol. The crystal's balance book is the record (its enthalpy and
     * entropy as anchored, plus the liquid's share, so the fusion there is the record's; its volume the record's). Its
     * equilibrium book takes the liquid's volume gap {@code c = -v_share}: {@code g} and {@code h} gain
     * {@code c (P - P_t)} and {@code v} gains {@code c} (expansion and compressibility rescaled, so {@code dv/dT} and
     * {@code dv/dP} stay the record's), so the melting line from the chemical potentials has the balance book's volume of
     * fusion at the anchor (Clapeyron) rather than the cubic liquid's: for water the cubic's liquid is 21.3 cm3/mol, the
     * share brings it to IF97's 18.0, and ice is 19.65. The share the state carries: {@code h_share - c (P - P_t)},
     * {@code s_share}, {@code -c}.
     */
    public Anchored anchored(TriplePointAnchor anchor, double shareEnthalpy, double shareEntropy, double shareVolume) {
        requireAnchor(anchor);
        return new Anchored(anchor, shareEnthalpy, shareEntropy, shareVolume);
    }

    private Anchored view(TriplePointAnchor anchor) {
        Anchored view = last;
        if (view != null && view.anchor.equals(anchor)) return view;
        view = anchored(anchor);
        last = view;
        return view;
    }

    /**
     * The crystal of this evaluator anchored at one {@link TriplePointAnchor}, immutable and safe to share between
     * threads: a Gibbs-function record with its integration constants g0 and g1 solved at construction (one function
     * evaluation per state), a segment record through {@link CrystalReference#state}. Callers pass their own
     * {@link PhaseState} and {@link PhaseDerivatives} (one component); a state allocates its property records only.
     */
    public final class Anchored implements CrystalFugacity {
        private final TriplePointAnchor anchor;
        private final JaegerSpanGibbsFunction function;
        private final double g0;
        private final double g1;
        private final double shareEnthalpy, shareEntropy, shareVolume;

        private Anchored(TriplePointAnchor anchor, double shareEnthalpy, double shareEntropy, double shareVolume) {
            this.anchor = anchor;
            this.shareEnthalpy = shareEnthalpy;
            this.shareEntropy = shareEntropy;
            this.shareVolume = shareVolume;
            this.function = crystal.gibbsFunction();
            if (function != null) {
                double[] constants = crystal.integrationConstants(anchor.chemicalPotential(), anchor.liquidEnthalpy());
                g0 = constants[0];
                g1 = constants[1];
            } else {
                g0 = Double.NaN;
                g1 = Double.NaN;
            }
        }

        public TriplePointAnchor anchor() { return anchor; }
        public String crystal() { return crystal.id(); }
        public String component() { return crystal.component(); }
        public CrystalReference record() { return crystal; }
        /** The solved integration constants {g0, g1} of a Gibbs-function record; NaN for a segment record. */
        public double[] integrationConstants() { return new double[] {g0, g1}; }

        /** g, h, s, v, c_p, alpha and kappa at (T, P), continued past the record's range ({@link CrystalReference}). */
        public CrystalReference.State properties(double temperature, double pressure) {
            if (function == null) return gap(crystal.state(temperature, pressure, anchor.chemicalPotential(), anchor.liquidEnthalpy()));
            var s = function.evaluate(temperature, pressure, g0, g1);
            return gap(new CrystalReference.State(temperature, pressure, s.gibbs(), s.enthalpy(), s.entropy(), s.volume(),
                    s.heatCapacity(), s.expansion(), s.compressibility()));
        }

        /** The equilibrium book's volume gap (see {@link CrystalPhaseEvaluator#anchored(TriplePointAnchor, double, double, double)}). */
        private CrystalReference.State gap(CrystalReference.State s) {
            double c = -shareVolume;
            if (c == 0.0) return s;
            double shift = c * (s.pressure() - anchor.pressure()), volume = s.volume() + c, ratio = s.volume() / volume;
            return new CrystalReference.State(s.temperature(), s.pressure(), s.gibbs() + shift, s.enthalpy() + shift, s.entropy(), volume,
                    s.heatCapacity(), s.expansion() * ratio, s.compressibility() * ratio);
        }

        /** {@code g_s(T, P)}, J/mol on the fluid family's reference: the crystal's chemical potential. */
        public double chemicalPotential(double temperature, double pressure) {
            if (function == null) return properties(temperature, pressure).gibbs();
            return function.evaluate(temperature, pressure, g0, g1).gibbs() - shareVolume * (pressure - anchor.pressure());
        }

        /** {@code ln phi_s = (g_s(T, P) - g_ig(T, P)) / (R T)}: the chemical potential against the species' ideal gas at P. */
        @Override
        public double logFugacityCoefficient(double temperature, double pressure) {
            double ideal = idealGas.enthalpy(temperature) - temperature * idealGas.entropy(temperature, pressure);
            return (chemicalPotential(temperature, pressure) - ideal) / (R * temperature);
        }

        /** Fills {@code out} (one component) with the crystal at (T, P), as the evaluator does. */
        public PhaseState evaluate(double temperature, double pressure, PhaseState out) {
            Objects.requireNonNull(out, "out");
            if (out.componentCount() != 1) throw new IllegalArgumentException("A crystal state has one component");
            return fill(properties(temperature, pressure), out);
        }

        /** The state and the derivative blocks (no composition block) at (T, P) into {@code out} (one component). */
        public PhaseDerivatives derivatives(double temperature, double pressure, PhaseDerivatives out) {
            Objects.requireNonNull(out, "out");
            if (out.componentCount() != 1) throw new IllegalArgumentException("A crystal state has one component");
            CrystalReference.State s = properties(temperature, pressure);
            PhaseState state = fill(s, out.state());
            out.begin(family);
            double rt = R * temperature;
            // ln phi = (g_s - g_ig)/(R T): d/dT = -(h_s - h_ig)/(R T^2), d/dP = (v_s - R T/P)/(R T).
            out.logFugacityTemperatureBuffer()[0] = -state.residualEnthalpy() / (rt * temperature);
            out.logFugacityPressureBuffer()[0] = s.volume() / rt - 1.0 / pressure;
            double volume = s.volume();
            out.setVolumetric(s.expansion() * volume, -s.compressibility() * volume);
            out.setCaloric(s.heatCapacity(), volume * (1.0 - s.expansion() * temperature));
            // The share's one derivative: its enthalpy h_share + v_share (P - P_t) in P.
            if (shareVolume != 0.0) out.setShare(0.0, shareVolume, 0.0, 0.0, 0.0, 0.0);
            return out;
        }

        private PhaseState fill(CrystalReference.State s, PhaseState out) {
            double temperature = s.temperature();
            double pressure = s.pressure();
            double hIdeal = idealGas.enthalpy(temperature);
            double sIdeal = idealGas.entropy(temperature, pressure);
            double gIdeal = hIdeal - temperature * sIdeal;
            out.set(PhaseKind.SOLID, crystal.id(), null, 0, temperature, pressure, s.volume(), hIdeal, s.enthalpy() - hIdeal,
                    sIdeal, s.entropy() - sIdeal, s.gibbs(), ONE, new double[] {(s.gibbs() - gIdeal) / (R * temperature)},
                    new double[] {gIdeal});
            if (shareEnthalpy != 0.0 || shareEntropy != 0.0 || shareVolume != 0.0) {
                out.setShare(shareEnthalpy + shareVolume * (pressure - anchor.pressure()), shareEntropy, shareVolume);
            }
            return out;
        }
    }

    private void requireAnchor(TriplePointAnchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (!anchor.component().equals(component()))
            throw new IllegalArgumentException("Anchor of " + anchor.component() + " for a crystal of " + component());
        if (anchor.temperature() != crystal.anchor().temperature() || anchor.pressure() != crystal.anchor().pressure())
            throw new IllegalArgumentException("Anchor at (" + anchor.temperature() + " K, " + anchor.pressure() + " Pa), not at the record's triple point ("
                    + crystal.anchor().temperature() + " K, " + crystal.anchor().pressure() + " Pa)");
    }
}
