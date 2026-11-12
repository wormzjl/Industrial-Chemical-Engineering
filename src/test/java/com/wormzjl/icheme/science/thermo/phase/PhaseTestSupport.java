package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared fixtures of the phase-contract tests. Everything here is built from the bundled network package's PR78
 * records and a stub {@link IdealGasFunction}, so the tests do not depend on the data spine's records.
 */
final class PhaseTestSupport {
    static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** Constant translations of the size of the P1 study's anchor (b) for N2 and CH4, m3/mol; the sign makes v smaller. */
    static final double NITROGEN_TRANSLATION = -3.519e-6;
    static final double METHANE_TRANSLATION = -3.389e-6;

    private PhaseTestSupport() {}

    static MaterialCatalog.Package network() { return MaterialCatalog.bundled().requirePackage(NETWORK); }

    /**
     * A stub ideal-gas function: {@code c_p = sum a_k (T - 298.15)^k}, {@code h = h0 + integral c_p},
     * {@code s = s0 + integral c_p/T - R ln(P/1e5)}, continued outside {@code [minimum, maximum]} as the interface
     * states: c_p held at the nearer end, h and s integrating it.
     */
    static final class StubIdealGas implements IdealGasFunction {
        static final double REFERENCE_T = 298.15;
        private final double[] a;
        private final double[] powers;
        private final double formationEnthalpy;
        private final double standardEntropy;
        private final double minimum;
        private final double maximum;

        StubIdealGas(double[] coefficients, double formationEnthalpy, double standardEntropy, double minimum, double maximum) {
            a = coefficients.clone();
            this.formationEnthalpy = formationEnthalpy;
            this.standardEntropy = standardEntropy;
            this.minimum = minimum;
            this.maximum = maximum;
            // The same polynomial in powers of T, for the entropy integral.
            powers = new double[a.length];
            for (int k = 0; k < a.length; k++) {
                for (int m = 0; m <= k; m++) powers[m] += a[k] * binomial(k, m) * Math.pow(-REFERENCE_T, k - m);
            }
        }

        static StubIdealGas constant(double heatCapacity, double minimum, double maximum) {
            return new StubIdealGas(new double[] {heatCapacity, 0, 0, 0, 0, 0}, 0.0, 0.0, minimum, maximum);
        }

        /** The property's main {@code shifted_polynomial_5} fit, with zero formation enthalpy and entropy. */
        static StubIdealGas of(MaterialCatalog.Property property, double minimum, double maximum) {
            double[] coefficients = property.cp().stream().mapToDouble(Double::doubleValue).toArray();
            return new StubIdealGas(coefficients, 0.0, 0.0, minimum, maximum);
        }

        StubIdealGas withReference(double enthalpy, double entropy) {
            return new StubIdealGas(a, enthalpy, entropy, minimum, maximum);
        }

        @Override public double heatCapacity(double t) {
            check(t);
            double delta = end(t) - REFERENCE_T;
            double cp = 0.0;
            for (int k = a.length - 1; k >= 0; k--) cp = cp * delta + a[k];
            return cp;
        }

        /** The Horner form the translated model uses, so its ideal part is reproduced to rounding. */
        @Override public double enthalpy(double t) {
            check(t);
            double e = end(t), delta = e - REFERENCE_T;
            double h = 0.0;
            for (int k = a.length - 1; k >= 0; k--) h = h * delta + a[k] / (k + 1);
            return formationEnthalpy + h * delta + heatCapacity(e) * (t - e);
        }

        @Override public double entropy(double t, double p) {
            check(t);
            if (!(p > 0.0)) throw new IllegalArgumentException("pressure");
            double e = end(t);
            double integral = powers[0] * Math.log(e / REFERENCE_T);
            for (int m = 1; m < powers.length; m++) integral += powers[m] * (Math.pow(e, m) - Math.pow(REFERENCE_T, m)) / m;
            return standardEntropy + integral + heatCapacity(e) * Math.log(t / e) - R * Math.log(p / 1.0e5);
        }

        @Override public double minimumTemperature() { return minimum; }
        @Override public double maximumTemperature() { return maximum; }
        public String revision() { return "test-stub"; }

        private void check(double t) {
            if (!(t > 0.0 && Double.isFinite(t))) throw new IllegalArgumentException("temperature " + t);
        }

        /** The nearer end of the data for {@code t} outside it, else {@code t}. */
        private double end(double t) { return Math.max(minimum, Math.min(maximum, t)); }

        private static double binomial(int n, int k) {
            double value = 1.0;
            for (int i = 1; i <= k; i++) value = value * (n - k + i) / i;
            return value;
        }
    }

    /** Methane and nitrogen of the network package with the P1-sized translations and their own cp fits as stubs. */
    static CubicPhaseEvaluator methaneNitrogen() {
        var pkg = network();
        List<IdealGasFunction> gas = new ArrayList<>();
        for (String id : List.of("Methane", "Nitrogen")) {
            gas.add(StubIdealGas.of(pkg.properties().get(pkg.components().indexOf(id)), 50.0, 1500.0));
        }
        return CubicPhaseEvaluator.fromPackage(pkg, List.of("Methane", "Nitrogen"),
                new double[] {METHANE_TRANSLATION, NITROGEN_TRANSLATION}, gas);
    }

    /** A declared open-domain research contract over the evaluator's basis, fluid-only, no water. */
    static PhaseContract openContract(CubicPhaseEvaluator evaluator) {
        return openContract(evaluator, Set.of(PhaseCompetition.FLUID_ONLY));
    }

    static PhaseContract openContract(CubicPhaseEvaluator evaluator, Set<PhaseCompetition> competitions) {
        var identity = new ThermoIdentity("test:" + String.join(",", evaluator.components()), evaluator.family(),
                ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
        return new PhaseContract("test:open", identity, evaluator.components(), WaterParticipation.NONE, null,
                competitions, Set.of(), PhaseDomain.open("test:open", evaluator.components()),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, "test contract, open domain"));
    }

    /**
     * The network package's 20 EOS components in package order, with zero translations (a flash does not depend on
     * them) and stubs from each record's main cp fit over the component's fluid-network temperature range.
     */
    static CubicPhaseEvaluator networkEvaluator() {
        var catalog = MaterialCatalog.bundled();
        var pkg = network();
        List<IdealGasFunction> gas = new ArrayList<>();
        for (int i = 0; i < pkg.components().size(); i++) {
            var range = catalog.fluidValidity(NETWORK, pkg.components().get(i)).orElseThrow();
            gas.add(StubIdealGas.of(pkg.properties().get(i), range.minimumTemperature(), range.maximumTemperature()));
        }
        return CubicPhaseEvaluator.fromPackage(pkg, pkg.components(), new double[pkg.components().size()], gas);
    }

    /** The bundled network package's own contract (its PR78 compounds, no water) on {@link #networkEvaluator}. */
    static FluidTpEquilibrium networkService() {
        return new FluidTpEquilibrium(PhaseContract.forPackage(MaterialCatalog.bundled(), NETWORK), networkEvaluator(),
                FluidTpEquilibrium.Settings.DEFAULT);
    }

    /** {@code contract} qualified for fluid-only competition alone. */
    static PhaseContract fluidOnly(PhaseContract contract) {
        return new PhaseContract(contract.packageId(), contract.identity(), contract.components(), contract.water(),
                contract.waterComponent(), Set.of(PhaseCompetition.FLUID_ONLY), contract.waterChemistrySpecies(),
                contract.domain(), contract.fluidCoverage());
    }

    /** {@code settings} with other PH/UV search limits (the outer search's evaluation budget and step tolerance). */
    static FluidTpEquilibrium.Settings withOuterSearch(FluidTpEquilibrium.Settings settings, int maximumOuterIterations,
                                                       double outerTolerance) {
        var s = settings;
        return new FluidTpEquilibrium.Settings(s.lnKTolerance(), s.maximumIterations(), s.accelerationCycle(),
                s.coexistenceTolerance(), s.trivialDistance(), s.stability(), s.substitutionLimit(), s.maximumNewtonIterations(),
                s.mergeTieLine(), s.mergeVolumeGap(), maximumOuterIterations, outerTolerance);
    }

    /**
     * The second-virial steam's qualified partial pressure, Pa (the free water's rule until W3b): each band holds the pressure
     * where the second-virial steam's volume first leaves 2 % of IAPWS-IF97 Region 2 at the band's lower temperature,
     * rounded down to two digits (1.8 MPa below 500 K to 65 MPa from 875 K).
     */
    static double vaporLimit(double t) {
        int band = 0;
        while (band < VAPOR_LIMIT_TEMPERATURES.length && t >= VAPOR_LIMIT_TEMPERATURES[band]) band++;
        return VAPOR_LIMIT_PRESSURES[band];
    }
    private static final double[] VAPOR_LIMIT_TEMPERATURES = {500, 525, 550, 575, 600, 625, 650, 675, 700, 725, 750, 775, 800, 825, 850, 875};
    static final double[] VAPOR_LIMIT_PRESSURES = {1.8e6, 2.2e6, 2.8e6, 3.6e6, 4.5e6, 5.5e6, 6.7e6, 8.1e6, 9.6e6, 11e6, 13e6, 15e6, 18e6, 22e6, 27e6, 34e6, 65e6};

    /** The Tia Juana light assay plus 5 mol% nitrogen over the network basis (water last, zero). */
    /** Amounts over {@code contract}'s basis from (species, amount) pairs, every other species zero (owner decision O2: a
     * request names the whole basis). */
    static double[] over(PhaseContract contract, Object... speciesAndAmounts) {
        double[] amounts = new double[contract.components().size()];
        for (int i = 0; i < speciesAndAmounts.length; i += 2) {
            amounts[contract.index((String) speciesAndAmounts[i])] = ((Number) speciesAndAmounts[i + 1]).doubleValue();
        }
        return amounts;
    }

    static double[] crudeWithNitrogen(PhaseContract contract) {
        var pkg = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog().requirePackage(NETWORK);
        var assay = pkg.assays().get(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID);
        double[] z = new double[contract.components().size()];
        for (int c = 0; c < assay.components().size(); c++) z[contract.index(assay.components().get(c))] = assay.amounts().get(c);
        z[contract.index("Nitrogen")] = 0.05;
        return z;
    }

    /** A PR kernel over named network components, built as {@code TangentPlaneStabilityTest} builds its own. */
    static PengRobinsonKernel classicalKernel(String... names) {
        var pkg = network();
        int n = names.length;
        double[] tc = new double[n];
        double[] pc = new double[n];
        double[] omega = new double[n];
        double[][] interactions = new double[n][n];
        for (int a = 0; a < n; a++) {
            int i = pkg.components().indexOf(names[a]);
            var pr = pkg.properties().get(i).pr();
            tc[a] = pr.criticalTemperature();
            pc[a] = pr.criticalPressure();
            omega[a] = pr.acentricFactor();
            for (int b = 0; b < n; b++) interactions[a][b] = pkg.interactions().get(i).get(pkg.components().indexOf(names[b]));
        }
        return new PengRobinsonKernel(tc, pc, omega, interactions, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.MIN_VALUE, Double.MAX_VALUE);
    }

    static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }
}
