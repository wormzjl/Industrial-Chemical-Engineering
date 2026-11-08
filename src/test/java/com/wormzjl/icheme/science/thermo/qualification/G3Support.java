package com.wormzjl.icheme.science.thermo.qualification;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.TranslatedPengRobinson;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseDomain;
import com.wormzjl.icheme.science.thermo.phase.ThermoIdentity;
import com.wormzjl.icheme.science.thermo.phase.WaterParticipation;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers of the G3 qualification fixtures (batch 2026-09-24-coolprop-low-temperature, P3 WP9b, plan
 * {@code P3_PILOT_ENGINE_PLAN.md} section 8): the pilot and bundled network models, pure-fluid evaluations through the
 * network's own equation of state, the Java Helmholtz oracle, the declared critical band with D14's corner box, the
 * melting lines of the reference files and a plain table printer whose rows the G3 report quotes.
 *
 * <p>Every pure-fluid number here comes from {@link FluidThermodynamics#hydrocarbonDerivatives}, the equation of state
 * the network evaluates (WP5 proved it bit for bit equal to the engine's {@code CubicPhaseEvaluator.forPackage} in
 * volume and {@code ln phi}). The enthalpy departure is {@code h(T, P) - h(T, 1 Pa)} on the vapour root, so it does not
 * depend on the package's energy datum (sensible for both network paths).</p>
 */
final class G3Support {
    static final String PILOT = "icheme:pilot_cryogenic";
    static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    /** Pilot species in package order; also the CoolProp fluid file names of the oracle. */
    static final String[] SPECIES = {"Nitrogen", "Methane", "Ethane", "CarbonDioxide"};
    static final String[] SHORT = {"N2", "CH4", "C2H6", "CO2"};
    static final double R = FluidThermodynamics.R;

    private static final Map<String, HelmholtzFluid> ORACLES = new HashMap<>();
    private static final Map<String, FluidThermodynamics> MODELS = new HashMap<>();

    private G3Support() {}

    static synchronized HelmholtzFluid oracle(String fluid) { return ORACLES.computeIfAbsent(fluid, HelmholtzFluid::load); }

    static HelmholtzFluid oracle(int species) { return oracle(SPECIES[species]); }

    static synchronized FluidThermodynamics model(String packageId) {
        return MODELS.computeIfAbsent(packageId, id -> FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), id));
    }

    static FluidThermodynamics pilot() { return model(PILOT); }

    static FluidThermodynamics network() { return model(NETWORK); }

    /** A pure-component amount vector over the model's hydrocarbon basis. */
    static double[] pure(FluidThermodynamics model, String component) {
        double[] x = new double[model.hydrocarbon.componentCount()];
        int index = model.hydrocarbon.components().indexOf(component);
        if (index < 0) throw new IllegalArgumentException(component + " not in " + model.hydrocarbon.components());
        x[index] = 1;
        return x;
    }

    // ------------------------------------------------------------------------------------------------ evaluations

    /** One hydrocarbon phase of the network's equation of state (no domain check: the qualification grid decides). */
    record Eval(double molarVolume, double enthalpy, double departure, double heatCapacity, double[] logPhi,
                int roots, double pip, double gibbsProxy) {
        double density() { return 1 / molarVolume; }
    }

    /** Evaluates one phase at (T, P) on {@code root}; {@code root == null} takes the root with the lower {@code ln phi} (pure). */
    static Eval evaluate(FluidThermodynamics model, double t, double p, double[] x, Root root) {
        var prepared = model.prepare(t);
        if (root == null) {
            var liquid = values(model, t, p, x, Root.LIQUID, prepared);
            var vapour = values(model, t, p, x, Root.VAPOR, prepared);
            root = gibbs(liquid, x) <= gibbs(vapour, x) ? Root.LIQUID : Root.VAPOR;
        }
        var v = copy(values(model, t, p, x, root, prepared), x);
        var zero = values(model, t, 1.0, x, Root.VAPOR, prepared);
        double departure = v.enthalpy - zero.molarEnthalpy();
        return new Eval(v.molarVolume, v.enthalpy, departure, v.heatCapacity, v.logPhi, v.roots, v.pip, v.gibbs);
    }

    private record Raw(double molarVolume, double enthalpy, double heatCapacity, double[] logPhi, int roots, double pip, double gibbs) {}

    private static Raw copy(TranslatedPengRobinson.Values values, double[] x) {
        return new Raw(values.molarVolume(), values.molarEnthalpy(), values.heatCapacity(), values.logFugacityCoefficientsView().clone(),
                values.physicalRootCount(), values.phaseIdentificationParameter(), gibbs(values, x));
    }

    private static TranslatedPengRobinson.Values values(FluidThermodynamics model, double t, double p, double[] x, Root root,
                                                        FluidThermodynamics.Prepared prepared) {
        return com.wormzjl.icheme.science.fluid.thermo.HydrocarbonValues.of(model.hydrocarbon, t, p, x, root);
    }

    /** The residual Gibbs energy per mole over RT at a fixed (T, P, x): {@code sum x_i ln phi_i} (roots compare on it). */
    private static double gibbs(TranslatedPengRobinson.Values values, double[] x) {
        double total = 0, sum = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i] > 0) total += x[i] * values.logFugacityCoefficientsView()[i];
            sum += x[i];
        }
        return total / sum;
    }

    /**
     * The model's own saturation pressure of a pure component: equal {@code ln phi} of the liquid and vapour roots
     * (the translation adds {@code P c/RT} to both and cancels), Newton in {@code ln P} with
     * {@code d(ln phi_L - ln phi_V)/d ln P = P (v_L - v_V)/RT}, started at {@code start}. {@code NaN} if the cubic has
     * no two roots near the start.
     */
    static double saturationPressure(FluidThermodynamics model, String component, double t, double start) {
        double[] x = pure(model, component);
        int i = model.hydrocarbon.components().indexOf(component);
        var prepared = model.prepare(t);
        double lnP = Math.log(start), previous = Double.NaN;
        for (int iteration = 0; iteration < 200; iteration++) {
            double p = Math.exp(lnP);
            var liquid = copy(values(model, t, p, x, Root.LIQUID, prepared), x);
            var vapour = copy(values(model, t, p, x, Root.VAPOR, prepared), x);
            if (liquid.roots != 3) {
                // Above the upper spinodal pressure: step down (a decade at a time before the first Newton step).
                lnP = Double.isNaN(previous) ? lnP - Math.log(10) : 0.5 * (lnP + previous);
                continue;
            }
            double f = liquid.logPhi[i] - vapour.logPhi[i];
            double slope = p * (liquid.molarVolume - vapour.molarVolume) / (R * t);
            double step = -f / slope;
            step = Math.max(-0.5, Math.min(0.5, step));
            previous = lnP;
            lnP += step;
            if (Math.abs(step) < 1e-13) return Math.exp(lnP);
        }
        return Double.NaN;
    }

    /** The oracle's saturation temperature at {@code p} (below its critical pressure), by bisection on its saturation pressure. */
    static double saturationTemperature(HelmholtzFluid fluid, double p) {
        double lo = fluid.tripleTemperature(), hi = fluid.criticalTemperature() * (1 - 1e-9);
        if (p <= fluid.saturation(lo).pressure()) return lo;
        if (p >= fluid.criticalPressure()) return Double.NaN;
        for (int k = 0; k < 80; k++) {
            double mid = 0.5 * (lo + hi);
            if (fluid.saturation(mid).pressure() < p) lo = mid; else hi = mid;
            if (hi - lo < 1e-10 * hi) break;
        }
        return 0.5 * (lo + hi);
    }

    /** The oracle's enthalpy departure {@code h(T, rho) - h_ig(T)}. */
    static double oracleDeparture(HelmholtzFluid fluid, double t, double enthalpy) {
        return enthalpy - fluid.state(t, 1e-10).enthalpy();
    }

    // ------------------------------------------------------------------------------------------ band and melting

    /**
     * The declared critical band of a pure fluid (plan section 3 widened by D7, D15's widening of box 2, as the engine
     * encodes it since P3 WP7d): box 1 Tr 0.95-1.1 x Pr 0.8-1.5, box 2 Tr 0.90-0.95 x Pr 0.58-0.74 with Tr 0.95-0.97 x Pr
     * 0.58-0.8 (D15: the liquid between boxes 1 and 2 below Pr 0.8), box 3 Tr 1.05-1.2 x Pr 2-3; 0 outside. D14's corner,
     * the engine's fourth box, is {@link #d14Corner} (scored at its 9 % declared density error by F1 and F4).
     */
    static int bandBox(double tr, double pr) {
        if (tr >= 0.95 && tr <= 1.1 && pr >= 0.8 && pr <= 1.5) return 1;
        if (tr >= 0.90 && tr <= 0.95 && pr >= 0.58 && pr <= 0.74 || tr >= 0.95 && tr <= 0.97 && pr >= 0.58 && pr <= 0.8) return 2;
        if (tr >= 1.05 && tr <= 1.2 && pr >= 2 && pr <= 3) return 3;
        return 0;
    }

    /** D14: the supercritical corner Tr 1.0-1.1 x Pr 1.5-2 joins the band with a 9 % declared density error. */
    static boolean d14Corner(double tr, double pr) {
        return tr >= 1.0 && tr <= 1.1 && pr > 1.5 && pr < 2;
    }

    /**
     * Melting pressure of the reference file's melting line ({@code ANCILLARIES.melting_line}, CoolProp {@code ae81610e}):
     * N2 Span et al. 2000 and C2H6 Buecker and Wagner 2006 ({@code polynomial_in_Tr}), CH4 Abramson 2011 (Simon), CO2
     * Span and Wagner 1996 ({@code polynomial_in_Theta}). A liquid state above it is solid-stable: the P1 grid dropped
     * such states (CoolProp refused them), and so do these fixtures.
     */
    static double meltingPressure(String fluid, double t) {
        return switch (fluid) {
            case "Nitrogen" -> 12523 * (1 + 12798.61 * (Math.pow(t / 63.151, 1.78963) - 1));
            case "Methane" -> 11700 + 208000000 * (Math.pow(t / 90.6941, 1.698) - 1);
            case "Ethane" -> 1.14 * (1 + 223626315 * (t / 90.368 - 1) + 105262374 * (Math.pow(t / 90.368, 2.55) - 1));
            case "CarbonDioxide" -> {
                double theta = t / 216.592 - 1;
                yield 517950 * (1 + 1955.539 * theta + 2055.4593 * theta * theta);
            }
            default -> throw new IllegalArgumentException(fluid);
        };
    }

    /**
     * The density rule of a pure state (reduced by the oracle's constants; {@code z} the reference compressibility): the
     * region, the D7 target and the bound held (NaN for both: in the band or report-only). Liquid Tr <= 0.85 3 % (D7);
     * Tr 0.85-0.9 D7 3 %, D15 5 % declared (next to saturation); Tr 0.90-0.95 D7 5 %, D14 6 %; Tr 0.95-1 at Pr > 1.5 5 %
     * (D7); the liquid at Tr 0.95-0.97 below Pr 0.8, between boxes 1 and 2, lies in box 2 since D15 (research-only, not
     * scored), and a liquid at Tr 0.95-1 below Pr 0.8 outside it would be held at 10 % (no D7 row; none exists for the
     * pilot species: their saturation pressure at Tr 0.97 is above Pr 0.8). Vapour Z >= 0.8 D7 1 %, D14 2.5 %; Z < 0.8 no
     * target. Supercritical 7 % (D7); D14's corner 9 %.
     */
    record DensityRule(String region, double d7, double held) {
        boolean scored() { return !Double.isNaN(held); }
    }

    static DensityRule densityRule(HelmholtzFluid fluid, double t, double p, double z) {
        double tc = fluid.criticalTemperature(), tr = t / tc, pr = p / fluid.criticalPressure();
        int box = bandBox(tr, pr);
        if (box != 0) return new DensityRule("band box " + box, Double.NaN, Double.NaN);
        if (t < tc && p > fluid.saturation(t).pressure()) {
            if (tr <= 0.85) return new DensityRule("liquid Tr <= 0.85", 3, 3);
            if (tr <= 0.9) return new DensityRule("liquid Tr 0.85-0.9 (D15 5 % declared)", 3, 5);
            if (tr <= 0.95) return new DensityRule("liquid Tr 0.90-0.95 (D14 6 %)", 5, 6);
            if (pr > 1.5) return new DensityRule("liquid Tr 0.95-1, Pr > 1.5", 5, 5);
            return new DensityRule("liquid Tr 0.95-1, Pr < 0.8, outside D15's box 2", Double.NaN, 10);
        }
        if (t < tc || p < fluid.criticalPressure()) {
            return z >= 0.8 ? new DensityRule("vapour Z >= 0.8", 1, 2.5) : new DensityRule("vapour Z < 0.8", Double.NaN, Double.NaN);
        }
        return d14Corner(tr, pr) ? new DensityRule("D14 corner", 7, 9) : new DensityRule("supercritical", 7, 7);
    }

    // -------------------------------------------------------------------------------------------- research engines

    /**
     * A constant-cp ideal gas (100 J/(mol K), 1 to 6000 K) for research-contract probes whose phase split does not depend
     * on the ideal part (a flash reads {@code ln phi} only); their enthalpies and entropies are not reported.
     */
    static IdealGasFunction stubGas() {
        return new IdealGasFunction() {
            @Override public double heatCapacity(double t) { return 100; }
            @Override public double enthalpy(double t) { return 100 * (t - 298.15); }
            @Override public double entropy(double t, double p) { return 100 * Math.log(t / 298.15) - R * Math.log(p / 1e5); }
            @Override public double minimumTemperature() { return 1; }
            @Override public double maximumTemperature() { return 6000; }
            public String revision() { return "g3-probe-constant-cp"; }
        };
    }

    /**
     * The TP engine on an open-domain, research-only, fluid-only contract over {@code evaluator}'s basis (no water): a probe
     * outside a package's declared domain (P2's research contract form). Its answers are graded research-only.
     */
    static FluidTpEquilibrium researchEngine(CubicPhaseEvaluator evaluator, String label) {
        var identity = new ThermoIdentity("g3:" + label, evaluator.family(), ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
        var contract = new PhaseContract("g3:" + label, identity, evaluator.components(), WaterParticipation.NONE, null,
                Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), PhaseDomain.open("g3:" + label, evaluator.components()),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, "G3 probe, open domain"));
        return new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
    }

    /** The bundled network package's PR78 components and constant kij with zero translations and stub ideal gases. */
    static CubicPhaseEvaluator networkEvaluator() {
        var pkg = MaterialCatalog.bundled().requirePackage(NETWORK);
        List<IdealGasFunction> gas = new ArrayList<>();
        for (int i = 0; i < pkg.components().size(); i++) gas.add(stubGas());
        return CubicPhaseEvaluator.fromPackage(pkg, pkg.components(), new double[pkg.components().size()], gas);
    }

    // ------------------------------------------------------------------------------------------------ reporting

    /** The D7 verdict of a worst deviation against a target (percent); "no target" for NaN. */
    static String verdict(double target, double worst) {
        if (Double.isNaN(target)) return "no target";
        return String.format(Locale.ROOT, "%s %% %s", number(target), Math.abs(worst) <= target ? "met" : "NOT met");
    }

    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e6) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String f(String format, Object... args) { return String.format(Locale.ROOT, format, args); }

    /** A pipe table printed with a family prefix, one line per row, so the Gradle output (test XML) is quotable. */
    static final class Table {
        private final String prefix;
        private final List<String> lines = new ArrayList<>();

        Table(String prefix, String... header) {
            this.prefix = prefix;
            lines.add("| " + String.join(" | ", header) + " |");
            lines.add("|" + "---|".repeat(header.length));
        }

        Table row(Object... cells) {
            var parts = new ArrayList<String>();
            for (Object cell : cells) parts.add(String.valueOf(cell));
            lines.add("| " + String.join(" | ", parts) + " |");
            return this;
        }

        void print(String title) {
            System.out.println(prefix + " " + title);
            for (String line : lines) System.out.println(prefix + " " + line);
        }
    }

    /** The worst signed deviation of a quantity and where it occurred. */
    static final class Worst {
        double value;
        String at = "-";
        int count;
        double sum;

        void add(double deviation, String where) {
            if (!Double.isFinite(deviation)) return;
            count++;
            sum += Math.abs(deviation);
            if (Math.abs(deviation) > Math.abs(value) || at.equals("-")) {
                value = deviation;
                at = where;
            }
        }

        double mean() { return count == 0 ? 0 : sum / count; }

        String signed(String format) { return count == 0 ? "-" : f(format, value) + " (" + at + ")"; }
    }
}
