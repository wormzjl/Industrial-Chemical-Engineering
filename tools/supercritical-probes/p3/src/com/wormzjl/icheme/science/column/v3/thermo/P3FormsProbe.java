package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseTraits;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PackageBinding;
import com.wormzjl.icheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseDomain;
import com.wormzjl.icheme.science.thermo.phase.ThermoIdentity;
import com.wormzjl.icheme.science.thermo.phase.WaterParticipation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Measurement probe of package P3 (batch 2026-11-16-supercritical-followups): forms F1, F2, F3 of the feed flash's
 * classification by the stability test, on the grids of COLUMN_FEED_REVIEW.md, against the frozen flash
 * (V3FeedFlashReference), the D1 flash (V3FeedFlash) and the equilibrium engine (the oracle). Not committed to src.
 *
 * <p>Args: {@code coarse|dense <output file>}.</p>
 */
public final class P3FormsProbe {
    static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    static final String CO2 = "test:methane_co2";
    private static final double PHASE_ENDPOINT_TOLERANCE = 1.0e-12;
    private static final double LOG_K_TOLERANCE = 1.0e-10;
    private static final int MAXIMUM_ITERATIONS = 64;
    private static final TangentPlaneStability.Settings WIDE = new TangentPlaneStability.Settings(
            1.0e-8, 1.0e-10, 1.0e-2, 30, 5, 100 * FluidTpEquilibrium.UNRESOLVED_STABILITY_BUDGET_FACTOR, 1.0e-4, 8, false);

    public static void main(String[] args) throws Exception {
        boolean dense = args[0].equals("dense");
        Path file = Path.of(args[1]);
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/packages/test_methane_co2.json",
                "{\"schema_version\":1,\"id\":\"" + CO2 + "\",\"revision\":\"probe-r1\",\"method\":\"icheme:crude_pr78\","
                        + "\"extends\":\"" + NETWORK + "\",\"components\":[{\"id\":\"CarbonDioxide\",\"properties\":\"icheme:chemsep_carbon_dioxide\"}]}");
        MaterialCatalog catalog = MaterialCatalog.parse(resources);
        List<Object[]> mixtures = new ArrayList<>(List.of(
                new Object[] {"C1/C2 50/50", NETWORK, Map.of("Methane", 0.5, "Ethane", 0.5)},
                new Object[] {"CO2/C1 90/10", CO2, Map.of("CarbonDioxide", 0.9, "Methane", 0.1)},
                new Object[] {"natural gas", NETWORK, Map.of("Methane", 0.90, "Ethane", 0.06, "Propane", 0.03, "Nitrogen", 0.01)},
                new Object[] {"C1/pc03 90/10", NETWORK, Map.of("Methane", 0.9, "crude_pc03", 0.1)}));
        if (dense) {
            mixtures.add(new Object[] {"C1/C2/C3 40/30/30", NETWORK, Map.of("Methane", 0.4, "Ethane", 0.3, "Propane", 0.3)});
            mixtures.add(new Object[] {"C1/C3 70/30", NETWORK, Map.of("Methane", 0.7, "Propane", 0.3)});
        }
        Tally total = new Tally("ALL");
        StringBuilder states = new StringBuilder("# mixture\tT\tP_MPa\tengine\tengine_class\tengine_beta\tref\tcurrent\tF1\tF2\tF3\tdetail_F2\tdetail_F3\n");
        StringBuilder summary = new StringBuilder();
        for (Object[] mixture : mixtures) {
            String name = (String) mixture[0];
            String packageId = (String) mixture[1];
            @SuppressWarnings("unchecked") Map<String, Double> parts = (Map<String, Double>) mixture[2];
            Tally tally = MaterialRuntime.with(catalog, packageId, () -> run(catalog, name, packageId, parts, dense, states));
            summary.append(tally.report());
            total.add(tally);
        }
        summary.append(total.report());
        Files.createDirectories(file.getParent());
        Files.writeString(file, summary + "\n# states\n" + states);
        System.out.println(summary);
        System.out.println("PROBE wrote " + file);
    }

    /** One flash form's outcome: an answer or a refusal, with its counters. */
    static final class Outcome {
        V3FlashResult result;
        String refusal;
        int stabilityTests;
        int stabilityEvaluations;
        int engineCalls;
        String cls() {
            if (result == null) return "refused";
            return result.phase() == V3FeedPhase.TWO_PHASE ? "two" : "one";
        }
    }

    static final class Context {
        final V3PengRobinsonThermo thermo;
        final TangentPlaneStability stability;
        final TangentPlaneStability wide;
        final FluidTpEquilibrium engine;
        final FluidTpEquilibrium.Workspace engineWorkspace;
        final List<String> basis;
        Context(V3PengRobinsonThermo thermo, PengRobinsonKernel kernel, int water, FluidTpEquilibrium engine, List<String> basis) {
            this.thermo = thermo;
            this.stability = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT, water);
            this.wide = new TangentPlaneStability(kernel, WIDE, water);
            this.engine = engine;
            this.engineWorkspace = engine.newWorkspace();
            this.basis = basis;
        }
    }

    private static Tally run(MaterialCatalog catalog, String name, String packageId, Map<String, Double> parts, boolean dense,
                             StringBuilder states) {
        var pkg = catalog.requirePackage(packageId);
        List<String> all = pkg.components();
        double[] z = new double[all.size()];
        parts.forEach((id, x) -> z[all.indexOf(id)] = x);
        List<String> subset = new ArrayList<>(parts.keySet());
        subset.sort((a, b) -> Integer.compare(all.indexOf(a), all.indexOf(b)));
        double[] zs = new double[subset.size()];
        for (int i = 0; i < zs.length; i++) zs[i] = parts.get(subset.get(i));
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        PengRobinsonKernel kernel = V3PengRobinsonSession.kernelFor(V3CatalogPropertyPackage.require(packageId));
        // The oracle: the review's engine, over the feed's own components.
        FluidTpEquilibrium oracle = engine(catalog, packageId, subset);
        var oracleWorkspace = oracle.newWorkspace();
        // F3's engine: over the column's whole basis, as the column would build it.
        List<String> basis = new ArrayList<>();
        for (int i = 0; i < thermo.componentBasis().componentCount(); i++) basis.add(thermo.componentBasis().componentId(i));
        Context context = new Context(thermo, kernel, basis.indexOf(CubicPhaseEvaluator.WATER), engine(catalog, packageId, basis), basis);
        Tally tally = new Tally(name);
        List<double[]> grid = new ArrayList<>();
        if (dense) {
            for (int ti = 0; ti <= 30; ti++) for (int pi = 0; pi <= 56; pi++) grid.add(new double[] {250.0 + 5.0 * ti, (2.0 + 0.5 * pi) * 1.0e6});
        } else {
            for (double t = 250.0; t <= 400.0; t += 25.0)
                for (double mpa : new double[] {2, 4, 6, 8, 10, 12, 15, 20, 25, 30}) grid.add(new double[] {t, mpa * 1.0e6});
        }
        for (double[] state : grid) {
            double t = state[0], p = state[1];
            var result = oracle.tp(EquilibriumRequest.tp(t, p, subset, zs, PhaseCompetition.FLUID_ONLY), oracleWorkspace);
            String eng;
            double beta = Double.NaN;
            if (!result.converged()) eng = "failed";
            else if (result.phases().size() > 1) {
                double vapor = 0, sum = 0;
                for (var ph : result.phases()) { sum += ph.total(); if (ph.kind() == com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR) vapor += ph.total(); }
                beta = vapor / sum;
                eng = "two";
            } else eng = "one";
            Outcome ref = reference(thermo, t, p, z);
            Outcome current = current(thermo, t, p, z);
            Outcome[] forms = new Outcome[4];
            for (int f = 1; f <= 3; f++) forms[f - 1] = form(f, context, t, p, z);
            forms[3] = current; // form D: the production flash
            tally.count(eng, String.valueOf(result.classification()), ref, current, forms);
            // Lane fixes, rule B: an engine split the production flash answers as one phase, by the engine's lighter phase.
            if (eng.equals("two") && current.result != null && current.result.phase() != V3FeedPhase.TWO_PHASE) {
                PhaseAmounts light = null;
                for (var ph : result.phases()) {
                    if (light == null || ph.kind() == com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR
                            || light.kind() != com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR && ph.traits() != null && light.traits() != null
                            && ph.traits().reducedDensity() < light.traits().reducedDensity()) light = ph;
                }
                boolean supercritical = light != null && light.traits() != null && light.traits().supercriticalIsotherm();
                tally.counts.merge("D_one_on_engine_two|" + result.classification() + "|ref_" + ref.cls() + "|lighter_supercritical_" + supercritical
                        + (current.result.detail().endsWith(V3FeedFlash.SECOND_LIQUID) ? "|second_liquid" : "|" + current.result.detail()), 1, Integer::sum);
            }
            // Agreement of the column's own two-phase answers with F3's engine on the same states (the stop rule).
            if (current.result != null && current.result.phase() == V3FeedPhase.TWO_PHASE) {
                V3FlashResult engineAnswer = engineSplit(context, t, p, z, new Outcome(), 0);
                if (engineAnswer != null) tally.agreement(current.result, engineAnswer, name, t, p);
                else tally.engineNoSplitOnColumnSplit++;
            }
            boolean interesting = !(ref.cls().equals(forms[0].cls()) && ref.cls().equals(forms[1].cls()) && ref.cls().equals(forms[2].cls()) && ref.cls().equals(current.cls()))
                    || eng.equals("two") && !ref.cls().equals("two");
            if (interesting) {
                states.append(String.format(Locale.ROOT, "%s\t%.0f\t%.1f\t%s\t%s\t%.6f\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%n", name, t, p / 1e6, eng,
                        result.classification(), beta, label(ref), label(current), label(forms[0]), label(forms[1]), label(forms[2]),
                        forms[1].result != null ? forms[1].result.detail() : forms[1].refusal,
                        forms[2].result != null ? forms[2].result.detail() : forms[2].refusal,
                        current.result != null ? current.result.detail() : current.refusal));
            }
        }
        return tally;
    }

    private static String label(Outcome o) {
        if (o.result == null) return "refused";
        return o.result.phase() == V3FeedPhase.TWO_PHASE ? String.format(Locale.ROOT, "two(%.6f)", o.result.vaporFraction())
                : o.result.phase().name().toLowerCase(Locale.ROOT);
    }

    static FluidTpEquilibrium engine(MaterialCatalog catalog, String packageId, List<String> components) {
        CubicPhaseEvaluator evaluator = PackageBinding.derivedEvaluator(catalog, packageId, components);
        var identity = new ThermoIdentity("column:" + packageId, evaluator.family(), ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
        var contract = new PhaseContract("column:open", identity, evaluator.components(), WaterParticipation.NONE, null,
                Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), PhaseDomain.open("column:open", evaluator.components()),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, "column feed"));
        return new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT);
    }

    private static Outcome reference(V3PengRobinsonThermo thermo, double t, double p, double[] z) {
        Outcome o = new Outcome();
        V3ThermoWorkspace w = thermo.newWorkspace();
        System.arraycopy(z, 0, w.normalizedOverall, 0, z.length);
        try { o.result = V3FeedFlashEquivalenceTest.V3FeedFlashReference.resolve(thermo, t, p, w); }
        catch (V3ThermoException refusal) { o.refusal = refusal.getMessage(); }
        return o;
    }

    private static Outcome current(V3PengRobinsonThermo thermo, double t, double p, double[] z) {
        Outcome o = new Outcome();
        V3ThermoWorkspace w = thermo.newWorkspace();
        System.arraycopy(z, 0, w.normalizedOverall, 0, z.length);
        try { o.result = V3FeedFlash.resolve(thermo, t, p, w); }
        catch (V3ThermoException refusal) { o.refusal = refusal.getMessage(); }
        return o;
    }

    // ---- The forms ----

    private static TangentPlaneStability.Result stability(Context c, double t, double p, double[] z, Outcome o) {
        TangentPlaneStability.Result s = c.stability.test(t, p, z);
        o.stabilityTests++;
        o.stabilityEvaluations += s.kernelEvaluations();
        if (s.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) {
            s = c.wide.test(t, p, z);
            o.stabilityTests++;
            o.stabilityEvaluations += s.kernelEvaluations();
        }
        return s;
    }

    /** Seeds ln K from the stability test's stationary phase; false where it lies at the feed (Wilson's K kept). */
    private static boolean seed(V3PengRobinsonThermo model, double t, double p, TangentPlaneStability.Result s, V3ThermoWorkspace w) {
        double[] trial = s.trialComposition();
        double[] z = w.normalizedOverall;
        model.evaluateInto(t, p, trial, s.trialRoot(), w);
        double trialZ = w.prSession.compressibilityFactor(s.trialRoot());
        model.evaluateInto(t, p, z, s.feedRoot(), w);
        double feedZ = w.prSession.compressibilityFactor(s.feedRoot());
        boolean vapour = trialZ > feedZ;
        double distance = 0.0;
        double[] logK = new double[z.length];
        for (int i = 0; i < z.length; i++) {
            if (z[i] == 0.0) continue;
            double ratio = DeterministicMath.log(trial[i]) - DeterministicMath.log(z[i]);
            logK[i] = vapour ? ratio : -ratio;
            distance += logK[i] * logK[i];
        }
        if (!(distance >= 1.0e-8)) return false;
        System.arraycopy(logK, 0, w.logK, 0, z.length);
        return true;
    }

    static Outcome form(int f, Context c, double t, double p, double[] z) {
        Outcome o = new Outcome();
        V3PengRobinsonThermo model = c.thermo;
        V3ThermoWorkspace w = model.newWorkspace();
        System.arraycopy(z, 0, w.normalizedOverall, 0, z.length);
        try {
            o.result = formResolve(f, c, model, t, p, w, o);
        } catch (V3ThermoException refusal) {
            o.refusal = refusal.getMessage();
        }
        return o;
    }

    private static V3FlashResult formResolve(int f, Context c, V3PengRobinsonThermo model, double t, double p,
                                             V3ThermoWorkspace w, Outcome o) {
        int n = w.componentCount();
        double[] z = w.normalizedOverall;
        model.wilsonK(t, p, w.wilsonK, w);
        for (int i = 0; i < n; i++) w.logK[i] = DeterministicMath.log(w.wilsonK[i]);
        double atLiquid = rr(z, w.logK, 0.0), atVapor = rr(z, w.logK, 1.0);
        if (!Double.isFinite(atLiquid) || !Double.isFinite(atVapor)) {
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, "V3 feed flash could not establish a finite Rachford-Rice phase bracket");
        }
        TangentPlaneStability.Result unstable = null;
        boolean seeded = false;
        if (atLiquid <= PHASE_ENDPOINT_TOLERANCE || atVapor >= -PHASE_ENDPOINT_TOLERANCE) {
            TangentPlaneStability.Result s = stability(c, t, p, z, o);
            if (s.verdict() != TangentPlaneStability.Verdict.UNSTABLE) {
                Root root = atLiquid <= PHASE_ENDPOINT_TOLERANCE ? Root.LIQUID : Root.VAPOR;
                model.evaluateInto(t, p, z, root, w);
                double h = model.phaseMolarEnthalpy(t, z, root, w);
                return root == Root.LIQUID ? V3FlashResult.liquid(0, z, h, "all-liquid Wilson endpoint classification")
                        : V3FlashResult.vapor(0, z, h, "all-vapor Wilson endpoint classification");
            }
            unstable = s;
            if (f >= 2) seeded = seed(model, t, p, s, w);
        }
        Attempt a = iterate(model, t, p, w);
        if (a.result != null) return a.result;
        if (unstable == null) {
            TangentPlaneStability.Result s = stability(c, t, p, z, o);
            if (s.verdict() == TangentPlaneStability.Verdict.STABLE) return onePhase(model, t, p, w, a.iteration);
            if (s.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) {
                throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, a.message + "; stability test unresolved");
            }
            unstable = s;
            if (f >= 2) {
                String first = a.message + " at " + a.iteration;
                if (seed(model, t, p, s, w)) {
                    a = iterate(model, t, p, w);
                    if (a.result != null) return a.result;
                    a.message = first + "; restarted from the stationary point: " + a.message + " at " + a.iteration;
                } else a.message = first + "; stationary point at the feed";
            }
        } else if (f >= 2) {
            a.message = (seeded ? "endpoint unstable, from the stationary point: " : "endpoint unstable, stationary point at the feed: ")
                    + a.message + " at " + a.iteration;
        } else a.message = "endpoint unstable: " + a.message + " at " + a.iteration;
        if (f == 3) {
            V3FlashResult engine = engineSplit(c, t, p, z, o, a.iteration);
            if (engine != null) return engine;
        }
        throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, a.message);
    }

    /** F3: the engine's split, its enthalpies on the column's own roots; null where the engine does not split. */
    static V3FlashResult engineSplit(Context c, double t, double p, double[] z, Outcome o, int iterations) {
        o.engineCalls++;
        var result = c.engine.tp(EquilibriumRequest.tp(t, p, c.basis, z, PhaseCompetition.FLUID_ONLY), c.engineWorkspace);
        if (!result.converged() || result.phases().size() != 2) return null;
        PhaseAmounts first = result.phases().get(0), second = result.phases().get(1);
        var vapourKind = com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR;
        boolean firstLighter = first.kind() == vapourKind ? true : second.kind() == vapourKind ? false
                : first.traits() == null || second.traits() == null || first.traits().reducedDensity() <= second.traits().reducedDensity();
        PhaseAmounts light = firstLighter ? first : second, heavy = firstLighter ? second : first;
        double beta = light.total() / (light.total() + heavy.total());
        int n = z.length;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = heavy.amount(i) / heavy.total(); y[i] = light.amount(i) / light.total(); }
        V3PengRobinsonThermo model = c.thermo;
        V3ThermoWorkspace w = model.newWorkspace();
        model.evaluateInto(t, p, x, Root.LIQUID, w);
        model.evaluateInto(t, p, y, Root.VAPOR, w);
        double h = (1.0 - beta) * model.phaseMolarEnthalpy(t, x, Root.LIQUID, w) + beta * model.phaseMolarEnthalpy(t, y, Root.VAPOR, w);
        return V3FlashResult.twoPhase(iterations, beta, x, y, h, "two-phase split of the equilibrium engine");
    }

    static final class Attempt {
        V3FlashResult result;
        int iteration;
        String message;
    }

    private static Attempt iterate(V3PengRobinsonThermo model, double t, double p, V3ThermoWorkspace w) {
        Attempt a = new Attempt();
        int n = w.componentCount();
        double[] z = w.normalizedOverall;
        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            double beta = root(z, w.logK);
            if (!Double.isFinite(beta) || beta <= 0.0 || beta >= 1.0) {
                a.iteration = iteration;
                a.message = "V3 feed flash lost its two-phase Rachford-Rice root";
                return a;
            }
            compositions(z, w.logK, beta, w.liquidComposition, w.vaporComposition);
            model.evaluateInto(t, p, w.liquidComposition, Root.LIQUID, w);
            model.evaluateInto(t, p, w.vaporComposition, Root.VAPOR, w);
            double change = 0.0;
            for (int i = 0; i < n; i++) {
                double target = model.logFugacityCoefficient(Root.LIQUID, i, w) - model.logFugacityCoefficient(Root.VAPOR, i, w);
                change = Math.max(change, Math.abs(target - w.logK[i]));
                w.nextLogK[i] = 0.5 * (w.logK[i] + target);
            }
            if (change <= LOG_K_TOLERANCE) {
                if (rr(z, w.logK, 0.0) <= PHASE_ENDPOINT_TOLERANCE) {
                    model.evaluateInto(t, p, z, Root.LIQUID, w);
                    a.result = V3FlashResult.liquid(iteration, z, model.phaseMolarEnthalpy(t, z, Root.LIQUID, w),
                            "all-liquid converged PR endpoint classification");
                    return a;
                }
                double hl = model.phaseMolarEnthalpy(t, w.liquidComposition, Root.LIQUID, w);
                double hv = model.phaseMolarEnthalpy(t, w.vaporComposition, Root.VAPOR, w);
                a.result = V3FlashResult.twoPhase(iteration, beta, w.liquidComposition, w.vaporComposition,
                        (1.0 - beta) * hl + beta * hv, "rigorous two-phase Rachford-Rice flash");
                return a;
            }
            System.arraycopy(w.nextLogK, 0, w.logK, 0, n);
        }
        a.iteration = MAXIMUM_ITERATIONS;
        a.message = "V3 feed flash did not converge within " + MAXIMUM_ITERATIONS + " iterations";
        return a;
    }

    private static V3FlashResult onePhase(V3PengRobinsonThermo model, double t, double p, V3ThermoWorkspace w, int iterations) {
        double[] overall = w.normalizedOverall;
        model.evaluateInto(t, p, overall, Root.LIQUID, w);
        model.evaluateInto(t, p, overall, Root.VAPOR, w);
        PhaseTraits traits = model.phaseTraits(Root.LIQUID, t, p, w);
        Root root;
        String detail;
        if (traits.physicalRootCount() > 1) {
            double gl = 0.0, gv = 0.0;
            for (int i = 0; i < overall.length; i++) {
                if (overall[i] == 0.0) continue;
                gl += overall[i] * model.logFugacityCoefficient(Root.LIQUID, i, w);
                gv += overall[i] * model.logFugacityCoefficient(Root.VAPOR, i, w);
            }
            root = gl < gv ? Root.LIQUID : Root.VAPOR;
            detail = V3FeedFlash.ONE_PHASE + ": " + (root == Root.LIQUID ? "liquid" : "vapour") + " root of lower Gibbs energy";
        } else {
            double reducedDensity = traits.reducedDensity();
            root = reducedDensity >= 1.0 ? Root.LIQUID : Root.VAPOR;
            detail = V3FeedFlash.ONE_PHASE + (traits.supercriticalIsotherm() ? " above" : " below") + " the mixture's pseudo-critical temperature: "
                    + (root == Root.LIQUID ? "liquid" : "vapour") + String.format(Locale.ROOT, " by reduced density %.3f", reducedDensity);
        }
        double h = model.phaseMolarEnthalpy(t, overall, root, w);
        return root == Root.LIQUID ? V3FlashResult.liquid(iterations, overall, h, detail) : V3FlashResult.vapor(iterations, overall, h, detail);
    }

    static double rr(double[] z, double[] logK, double beta) {
        double s = 0;
        for (int i = 0; i < z.length; i++) {
            if (z[i] == 0) continue;
            double k = DeterministicMath.exp(logK[i]);
            double d = (1.0 - beta) + beta * k;
            if (!(d > 0.0) || !Double.isFinite(d)) return Double.NaN;
            s += z[i] * DeterministicMath.expm1(logK[i]) / d;
        }
        return s;
    }

    static double root(double[] z, double[] logK) {
        double lo = 0, hi = 1;
        for (int it = 0; it < 100; it++) {
            double mid = 0.5 * (lo + hi);
            double res = rr(z, logK, mid);
            if (!Double.isFinite(res)) return Double.NaN;
            if (mid == lo || mid == hi) return mid;
            if (res > 0.0) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    static void compositions(double[] z, double[] logK, double beta, double[] x, double[] y) {
        double lt = 0, vt = 0;
        for (int i = 0; i < z.length; i++) {
            if (z[i] == 0) { x[i] = 0; y[i] = 0; continue; }
            double k = DeterministicMath.exp(logK[i]);
            x[i] = z[i] / ((1.0 - beta) + beta * k);
            y[i] = k * x[i];
            lt += x[i]; vt += y[i];
        }
        if (!Double.isFinite(lt) || !Double.isFinite(vt) || lt <= 0 || vt <= 0) {
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, "V3 feed flash generated a nonphysical phase composition");
        }
        for (int i = 0; i < z.length; i++) { x[i] /= lt; y[i] /= vt; }
    }

    // ---- Counting ----

    static boolean same(V3FlashResult a, V3FlashResult b) {
        return a.phase() == b.phase() && a.iterations() == b.iterations() && a.detail().equals(b.detail())
                && Double.doubleToLongBits(a.vaporFraction()) == Double.doubleToLongBits(b.vaporFraction())
                && Double.doubleToLongBits(a.molarEnthalpyJoulesPerMol()) == Double.doubleToLongBits(b.molarEnthalpyJoulesPerMol())
                && Arrays.equals(a.liquidComposition(), b.liquidComposition()) && Arrays.equals(a.vaporComposition(), b.vaporComposition());
    }

    static final String[] FORM = {"F1", "F2", "F3", "D"};

    static final class Tally {
        final String name;
        final Map<String, Integer> counts = new TreeMap<>();
        final long[] stabilityTests = new long[4], stabilityEvaluations = new long[4], engineCalls = new long[4];
        final int[] wrongOne = new int[4], refused = new int[4], answered = new int[4], wrongTwo = new int[4];
        final int[] movedRef = new int[4], movedRefNot181 = new int[4], movedCurrent = new int[4];
        final int[] wrongOneVapourLiquid = new int[4], refusedTwoLiquids = new int[4];
        int states, engineFailed, the181, engineNoSplitOnColumnSplit, agreements;
        double worstBeta, worstLogK;
        String worstAt = "";
        final int[] over = new int[4]; // |d ln K| above 1e-8, 1e-6, 1e-4, 1e-2
        Tally(String name) { this.name = name; }

        void count(String eng, String engineClass, Outcome ref, Outcome current, Outcome[] forms) {
            states++;
            if (eng.equals("failed")) engineFailed++;
            boolean is181 = eng.equals("two") && ref.result != null && ref.result.phase() != V3FeedPhase.TWO_PHASE;
            if (is181) the181++;
            counts.merge("engine_" + eng + "|ref_" + ref.cls() + "|current_" + current.cls(), 1, Integer::sum);
            for (int f = 0; f < 4; f++) {
                Outcome o = forms[f];
                stabilityTests[f] += o.stabilityTests;
                stabilityEvaluations[f] += o.stabilityEvaluations;
                engineCalls[f] += o.engineCalls;
                counts.merge(FORM[f] + "|engine_" + eng + "|ref_" + ref.cls() + "|form_" + o.cls() + (is181 ? "|the181" : ""), 1, Integer::sum);
                if (o.result == null) refused[f]++; else answered[f]++;
                if (eng.equals("two") && o.result != null && o.result.phase() != V3FeedPhase.TWO_PHASE) { wrongOne[f]++; if (engineClass.equals("VAPOR_LIQUID")) wrongOneVapourLiquid[f]++; }
                if (o.refusal != null && o.refusal.contains("two liquids")) refusedTwoLiquids[f]++;
                if (eng.equals("one") && o.result != null && o.result.phase() == V3FeedPhase.TWO_PHASE) wrongTwo[f]++;
                if (ref.result != null && (o.result == null || !same(ref.result, o.result))) {
                    movedRef[f]++;
                    if (!is181) movedRefNot181[f]++;
                }
                if (current.result != null && (o.result == null || !same(current.result, o.result))) movedCurrent[f]++;
            }
        }

        void agreement(V3FlashResult column, V3FlashResult engine, String mixture, double t, double p) {
            agreements++;
            double db = Math.abs(column.vaporFraction() - engine.vaporFraction());
            double dk = 0.0;
            double[] xc = column.liquidComposition(), yc = column.vaporComposition(), xe = engine.liquidComposition(), ye = engine.vaporComposition();
            for (int i = 0; i < xc.length; i++) {
                if (xc[i] <= 0 || yc[i] <= 0) continue;
                double kc = Math.log(yc[i] / xc[i]), ke = Math.log(ye[i] / xe[i]);
                dk = Math.max(dk, Math.abs(kc - ke));
            }
            if (dk > 1e-8) over[0]++;
            if (dk > 1e-6) over[1]++;
            if (dk > 1e-4) over[2]++;
            if (dk > 1e-2) over[3]++;
            if (dk > worstLogK) { worstLogK = dk; worstAt = String.format(Locale.ROOT, "%s %.0f K %.1f MPa (beta %.6f vs %.6f)", mixture, t, p / 1e6, column.vaporFraction(), engine.vaporFraction()); }
            worstBeta = Math.max(worstBeta, db);
        }

        void add(Tally o) {
            o.counts.forEach((k, v) -> counts.merge(k, v, Integer::sum));
            for (int f = 0; f < 4; f++) {
                stabilityTests[f] += o.stabilityTests[f]; stabilityEvaluations[f] += o.stabilityEvaluations[f]; engineCalls[f] += o.engineCalls[f];
                wrongOne[f] += o.wrongOne[f]; refused[f] += o.refused[f]; answered[f] += o.answered[f]; wrongTwo[f] += o.wrongTwo[f];
                movedRef[f] += o.movedRef[f]; movedRefNot181[f] += o.movedRefNot181[f]; movedCurrent[f] += o.movedCurrent[f];
                wrongOneVapourLiquid[f] += o.wrongOneVapourLiquid[f]; refusedTwoLiquids[f] += o.refusedTwoLiquids[f];
            }
            states += o.states; engineFailed += o.engineFailed; the181 += o.the181;
            engineNoSplitOnColumnSplit += o.engineNoSplitOnColumnSplit; agreements += o.agreements;
            for (int i = 0; i < 4; i++) over[i] += o.over[i];
            if (o.worstLogK > worstLogK) { worstLogK = o.worstLogK; worstAt = o.worstAt; }
            worstBeta = Math.max(worstBeta, o.worstBeta);
        }

        String report() {
            StringBuilder s = new StringBuilder("## " + name + ": " + states + " states, engine failed " + engineFailed
                    + ", engine split answered one-phase by the frozen flash " + the181 + "\n");
            s.append("form\tanswered\trefused\twrong_one\twrong_two\tmoved_vs_ref\tmoved_vs_ref_not181\tmoved_vs_production\tstability_tests\tstability_kernel_evals\tengine_calls\twrong_one_vs_VAPOR_LIQUID\trefused_two_liquids\n");
            for (int f = 0; f < 4; f++) {
                s.append(String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n", FORM[f], answered[f], refused[f], wrongOne[f], wrongTwo[f],
                        movedRef[f], movedRefNot181[f], movedCurrent[f], stabilityTests[f], stabilityEvaluations[f], engineCalls[f], wrongOneVapourLiquid[f], refusedTwoLiquids[f]));
            }
            s.append(String.format(Locale.ROOT, "column two-phase vs F3 engine: %d compared, %d engine no split; |d lnK| > 1e-8: %d, > 1e-6: %d, > 1e-4: %d, > 1e-2: %d; worst |d lnK| %.3e at %s; worst |d beta| %.3e%n",
                    agreements, engineNoSplitOnColumnSplit, over[0], over[1], over[2], over[3], worstLogK, worstAt, worstBeta));
            counts.forEach((k, v) -> s.append(k).append('\t').append(v).append('\n'));
            return s.append('\n').toString();
        }
    }
}
