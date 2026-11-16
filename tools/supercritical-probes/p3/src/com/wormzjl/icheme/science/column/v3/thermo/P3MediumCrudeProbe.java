package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Step 0 of batch 2026-11-16-cdu17-fixture-removal (CDU17_REMOVAL_PLAN.md, section 4): is the bundled medium crude one
 * liquid when cold? On the bundled package {@code icheme:tjl20_methane}, dry: the {@code icheme:medium_crude} feed, and its
 * 565 C+ end ({@code crude_pc10..12} in the assay's proportions) with its naphtha ({@code crude_pc01..03}), 50/50 by mass;
 * 280, 300, 320, 350 K at 0.1, 0.25, 1.0 MPa.
 *
 * <p>Per state: the production flash ({@link V3FeedFlash#resolve}: phase, vapour fraction, the second-liquid flag of its
 * detail), then the tangent-plane test of its liquid (the flash's liquid, or the feed when it is one liquid), exhaustive,
 * with every vapour-root trial excluded: a negative distance there is a second liquid. Args: {@code medium <output file>}.</p>
 */
public final class P3MediumCrudeProbe {
    static final String PACKAGE = "icheme:tjl20_methane";
    static final String ASSAY = "icheme:medium_crude";
    static final double[] TEMPERATURES = {280, 300, 320, 350};
    static final double[] PRESSURES = {0.1e6, 0.25e6, 1.0e6};

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[1]);
        MaterialCatalog bundled = MaterialCatalog.bundled();
        String table = MaterialRuntime.with(bundled, PACKAGE, P3MediumCrudeProbe::run);
        Files.createDirectories(file.getParent());
        Files.writeString(file, table);
        System.out.println(table);
        System.out.println("PROBE wrote " + file);
    }

    private static String run() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
        List<String> ids = thermo.componentBasis().componentIds();
        double[] crude = thermo.crudeFeed(ASSAY).moleFractions();
        // The 565 C+ end and the naphtha, each in the assay's proportions, mixed 50/50 by mass.
        double[] heavy = cut(crude, ids, "crude_pc10", "crude_pc11", "crude_pc12");
        double[] naphtha = cut(crude, ids, "crude_pc01", "crude_pc02", "crude_pc03");
        double heavyMass = mass(thermo, heavy), naphthaMass = mass(thermo, naphtha);
        double[] blend = new double[ids.size()];
        for (int i = 0; i < blend.length; i++) blend[i] = 0.5 * heavy[i] / heavyMass + 0.5 * naphtha[i] / naphthaMass;
        normalise(blend);

        PengRobinsonKernel kernel = kernel(thermo);
        var settings = TangentPlaneStability.Settings.DEFAULT;
        var exhaustive = new TangentPlaneStability(kernel, new TangentPlaneStability.Settings(settings.instabilityTolerance(),
                settings.convergenceTolerance(), settings.newtonSwitch(), settings.maximumSuccessiveSubstitutions(),
                settings.accelerationCycle(), settings.maximumIterations() * 4, settings.trivialDistance(),
                settings.pureComponentTrialLimit(), true));
        TangentPlaneStability.TrialFilter liquidTrialsOnly = (feed, feedRoot, trial, trialRoot) -> trialRoot == PengRobinsonKernel.Root.VAPOR;

        StringBuilder out = new StringBuilder();
        out.append("# package ").append(PACKAGE).append(", assay ").append(ASSAY).append(", identity ").append(thermo.datasetRevision()).append('\n');
        out.append("# mixture\tT_K\tP_MPa\tflash\tvapour_fraction\tflash_second_liquid\tliquid_test\ttm_liquid_trials\ttrial_root\ttrial_pc10_12\tliquid_pc10_12\n");
        int states = 0, secondLiquid = 0;
        for (var mixture : List.of(new Object[] {"medium_crude", crude}, new Object[] {"565C+/naphtha 50/50 mass", blend})) {
            String name = (String) mixture[0];
            double[] z = (double[]) mixture[1];
            for (double t : TEMPERATURES) for (double p : PRESSURES) {
                states++;
                V3ThermoWorkspace w = thermo.newWorkspace();
                System.arraycopy(z, 0, w.normalizedOverall, 0, z.length);
                String flash, flag;
                double beta;
                double[] liquid;
                try {
                    V3FlashResult r = V3FeedFlash.resolve(thermo, t, p, w);
                    flash = r.phase().name().toLowerCase(Locale.ROOT);
                    beta = r.vaporFraction();
                    flag = r.detail() != null && r.detail().endsWith(V3FeedFlash.SECOND_LIQUID) ? "YES" : "no";
                    liquid = r.phase() == V3FeedPhase.TWO_PHASE ? r.liquidComposition() : z.clone();
                } catch (V3ThermoException refusal) {
                    flash = "refused: " + refusal.getMessage();
                    beta = Double.NaN;
                    flag = "-";
                    liquid = z.clone();
                }
                var result = exhaustive.test(t, p, liquid, liquidTrialsOnly, exhaustive.newWorkspace());
                boolean unstable = result.verdict() == TangentPlaneStability.Verdict.UNSTABLE;
                if (unstable || flag.equals("YES")) secondLiquid++;
                out.append(String.format(Locale.ROOT, "%s\t%.0f\t%.2f\t%s\t%.6f\t%s\t%s\t%.3e\t%s\t%.4f\t%.4f%n", name, t, p / 1e6, flash, beta, flag,
                        result.verdict(), result.minimumTangentPlaneDistance(), result.trialRoot(),
                        share(result.trialComposition(), ids), share(liquid, ids)));
            }
        }
        out.append(String.format(Locale.ROOT, "# states %d, a second liquid at %d%n", states, secondLiquid));
        return out.toString();
    }

    private static double[] cut(double[] crude, List<String> ids, String... cuts) {
        double[] x = new double[crude.length];
        for (String id : cuts) {
            int i = ids.indexOf(id);
            if (i < 0) throw new IllegalStateException(id + " not in " + PACKAGE);
            x[i] = crude[i];
        }
        normalise(x);
        return x;
    }

    private static double mass(V3PengRobinsonThermo thermo, double[] x) {
        double m = 0;
        for (int i = 0; i < x.length; i++) if (x[i] > 0) m += x[i] * thermo.componentMolecularWeightKgPerMol(i);
        return m;
    }

    private static void normalise(double[] x) {
        double sum = 0;
        for (double v : x) sum += v;
        for (int i = 0; i < x.length; i++) x[i] /= sum;
    }

    private static double share(double[] x, List<String> ids) {
        double s = 0;
        for (String id : List.of("crude_pc10", "crude_pc11", "crude_pc12")) s += x[ids.indexOf(id)];
        return s;
    }

    /** The column's own kernel (the session's, on the package's derived pairs). */
    private static PengRobinsonKernel kernel(V3PengRobinsonThermo thermo) {
        try {
            var sessionField = V3PengRobinsonThermo.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            Object session = sessionField.get(thermo);
            var kernelField = session.getClass().getDeclaredField("kernel");
            kernelField.setAccessible(true);
            return (PengRobinsonKernel) kernelField.get(session);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
