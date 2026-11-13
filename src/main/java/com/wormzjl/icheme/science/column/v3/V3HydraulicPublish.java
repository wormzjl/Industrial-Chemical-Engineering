package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.mergedEvents;

/**
 * The publication of an accepted requested state, with the flow-dependent tray pressure profile and its one bounded
 * hydraulic correction.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2). {@code publishAccepted}
 * became package-private with the move (the classical ladder and the learned route call it). The correction solves
 * through the calculator's {@code solveSingleProblem} and judges through its {@code publishesSuccess} and
 * {@code publishesRequestedGeometry}; the diagnostics are the calculator's.</p>
 */
final class V3HydraulicPublish {
    /**
     * Relative total-drop disagreement a published profile may keep without re-solving the column.
     *
     * <p>The user's tolerance on total column pressure drop is about 10 %, and the measured contraction of the
     * marched map is 0.018 median / 0.086 worst, so a mismatch below this bound is already inside the target
     * without spending anything, and one above it is brought inside by a single correction.</p>
     */
    private static final double HYDRAULIC_MISMATCH_TOLERANCE = 0.10;
    /** Measured 3 Newton iterations median, 5 maximum; the cap only bounds a correction that is not working. */
    private static final int HYDRAULIC_CORRECTION_MAXIMUM_ITERATIONS = 64;
    /**
     * The correction's own allowance in control polls (D2 of batch 2026-11-13-codebase-review): a counter, so the same
     * request publishes the same profile on any machine, as the learned route's budget. Measured 2026-11-13 (one correction
     * each, V3TrayHydraulicsColumnTest): the shipped presets and tower templates 1,901 to 11,446 polls (heavy crude), the
     * Tia Juana literature preset 21,282 (the largest), the 12-tray literature column 1,676; median 5,356 of the eight. The
     * budget is eight times the largest, rounded up to one figure: the margin the 10 s wall clock held over the slowest 1.2 s.
     */
    static final long HYDRAULIC_CORRECTION_BUDGET_POLLS = 200_000L;
    /** Deliberately short: the published solve path is already near its bounded length on a ramped request. */
    private static final String HYDRAULIC_CORRECTION_PATH_SUFFIX = "/hyd";

    private V3HydraulicPublish() {}

    /**
     * The single publication seam of an accepted requested state, for the classical and the learned route alike.
     *
     * <p>It is where the flow-dependent tray pressure profile is attached: the pass arriving here solved the
     * request on its nominal profile and passed every gate, and {@link #trayHydraulics} either leaves that
     * publication exactly as it is — always, in prescribed-drop mode — or replaces it by one bounded warm
     * correction on the profile marched from this very state. Digest, duty ledger, exported profile and
     * diagnostics are then built once, from whichever state is published.</p>
     */
    static V3ColumnOutcome.Success publishAccepted(V3ColumnInput input, V3SolvePass pass,
            V3PengRobinsonThermo thermo, V3SolvePolicy policy, V3SolveControl control, String solvePath,
            List<String> solverEvents) {
        HydraulicPublication publication = trayHydraulics(input, pass, thermo, policy, control, solvePath, solverEvents);
        V3SolvePass published = publication.pass();
        V3ColumnProblem selected = published.prepared().problem();
        V3DryMeshState corrected = published.attempt().state();
        String revision = V3ColumnLabels.formulationRevision(input, policy.requestedCutoff(), policy.closureTolerance());
        V3ColumnResult result = V3ColumnResult.accepted(selected,
                V3InputDigest.of(selected, revision, thermo.datasetRevision(), V3ColumnLabels.assumptionsRevision(input),
                        policy.requestedCutoff(), policy.closureTolerance()),
                publication.audit(), published.attempt().evidence().convergenceEvidence(), corrected, thermo, revision,
                V3ColumnDutyLedger.fromAccepted(selected, corrected, thermo, published.feedMolarEnthalpyJoulesPerMol()),
                publication.summary());
        var success = new V3ColumnOutcome.Success(result, V3ColumnCalculator.diagnostics(published.attempt(), publication.audit(),
                publication.solvePath(), publication.solverEvents(), policy, V3SolveScratch.factorizationEvent()));
        if (policy.observer() != null)
            policy.observer().accept(V3NeuralSeed.capture(selected, corrected, thermo.datasetRevision()));
        return success;
    }

    /**
     * Attaches the flow-dependent tray pressure profile to an accepted state.
     *
     * <p>March the profile from this state's own traffic; if it agrees with the profile the state was solved on
     * to within {@link #HYDRAULIC_MISMATCH_TOLERANCE}, publish the state as it is and report the marched profile
     * as the hydraulic reading. Otherwise re-solve the identical request once on the marched profile, warm from
     * the accepted state, and publish that. A correction that will not close falls back to a half step and then
     * to the full one; if that fails too, the first accepted state is published on its nominal profile with a
     * hydraulic-mismatch warning. There is never a second correction and never a closure loop.</p>
     *
     * <p>Nothing here can fail a request. The first pass is already a complete accepted answer, so every way the
     * hydraulic step can end badly — a profile outside the property envelope, a correction that does not
     * converge or is not audited, its own poll budget, a cancelled request, an internal guard — publishes that
     * answer with a warning instead. That includes cancellation: discarding a solved column to report a deadline
     * that expired while polishing its pressure profile would be strictly worse for the caller.</p>
     */
    private static HydraulicPublication trayHydraulics(V3ColumnInput input, V3SolvePass pass,
            V3PengRobinsonThermo thermo, V3SolvePolicy policy, V3SolveControl control, String solvePath,
            List<String> solverEvents) {
        HydraulicPublication nominal = new HydraulicPublication(pass, pass.audit(), solvePath, solverEvents, null);
        // Each correction's control polls and band factorizations (P7), in the order they ran.
        List<long[]> polls = new ArrayList<>();
        if (!input.usesTrayHydraulics()) return nominal;
        try {
            V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(input.columnDiameterMetres());
            V3TrayHydraulics.ComponentConstants constants = V3TrayHydraulics.ComponentConstants.of(
                    thermo, input.componentBasis().componentCount());
            V3ColumnProblem solved = pass.prepared().problem();
            V3ColumnTopology topology = solved.topology();
            V3TrayHydraulics.Tray[] traffic = V3TrayHydraulics.traffic(solved, pass.attempt().state(), constants);
            double[] marched = hydraulics.march(solved, traffic);
            if (!V3TrayHydraulics.isAdmissible(topology, marched, thermo)) {
                return advisory(nominal, "the flow-dependent tray pressure profile left the property package's "
                        + "pressure envelope; the column was published on the authored uniform drop instead");
            }
            double mismatch = V3TrayHydraulics.totalDropMismatch(topology, solved.nodePressuresPascal(), marched);
            if (mismatch <= HYDRAULIC_MISMATCH_TOLERANCE) {
                return summarised(nominal, hydraulics, solved, traffic, marched, false, mismatch, polls);
            }
            // The published inspection carries the pressure profile of its solved problem.  Even on the
            // aggressive interactive route we must therefore apply the marched profile when the authored drop is
            // zero (the common diameter-driven preset): publishing only the hydraulic summary would show a
            // positive column dP while every inspected tray still had the flat nominal pressure.  Keep the same
            // bounded correction path for all routes so the summary and per-tray readings cannot disagree.
            V3SolvePass corrected = hydraulicCorrection(input, pass, thermo, policy, control, marched, polls);
            if (corrected == null) {
                // One half step, then the full one from the state it reached: a correction that refuses a 20 kPa
                // jump in sump pressure in one go has been measured to take it in two.
                V3SolvePass half = hydraulicCorrection(input, pass, thermo, policy, control,
                        V3TrayHydraulics.halfway(topology, solved.nodePressuresPascal(), marched), polls);
                if (half != null) {
                    V3ColumnProblem halfProblem = half.prepared().problem();
                    double[] fromHalf = hydraulics.march(halfProblem,
                            V3TrayHydraulics.traffic(halfProblem, half.attempt().state(), constants));
                    if (V3TrayHydraulics.isAdmissible(topology, fromHalf, thermo)) {
                        corrected = hydraulicCorrection(input, half, thermo, policy, control, fromHalf, polls);
                    }
                }
            }
            if (corrected == null) {
                return advisory(summarised(nominal, hydraulics, solved, traffic, marched, false, mismatch, polls),
                        String.format(Locale.ROOT, "the tray pressure drop of this column is %.1f kPa, which the "
                                + "authored uniform drop misses by %.0f%%, and the column would not re-solve on it; "
                                + "the published profile is the authored one",
                                V3TrayHydraulics.totalDropPascal(topology, marched) / 1000.0, 100.0 * mismatch));
            }
            V3ColumnProblem correctedProblem = corrected.prepared().problem();
            V3TrayHydraulics.Tray[] correctedTraffic =
                    V3TrayHydraulics.traffic(correctedProblem, corrected.attempt().state(), constants);
            double residual = V3TrayHydraulics.totalDropMismatch(topology, correctedProblem.nodePressuresPascal(),
                    hydraulics.march(correctedProblem, correctedTraffic));
            // The published path and events describe the state that is published: the correction's own solve,
            // under the assembled path of the pass that seeded it.
            HydraulicPublication publication = new HydraulicPublication(corrected, corrected.audit(),
                    solvePath + HYDRAULIC_CORRECTION_PATH_SUFFIX,
                    mergedEvents(corrected.solverEvents(), solverEvents), null);
            return summarised(publication, hydraulics, correctedProblem, correctedTraffic,
                    correctedProblem.nodePressuresPascal(), true, residual, polls);
        } catch (CancellationException expired) {
            return advisory(nominal, "the request ended before the flow-dependent tray pressure profile could be "
                    + "applied; the column was published on the authored uniform drop instead");
        } catch (RuntimeException unavailable) {
            return advisory(nominal, "the flow-dependent tray pressure profile could not be evaluated; the column "
                    + "was published on the authored uniform drop instead");
        }
    }

    /**
     * One bounded warm correction of the identical request on a supplied per-tray profile.
     *
     * <p>Returns null unless the correction reaches an audited, certified solution of the requested geometry —
     * the same three gates the first pass passed. It runs under its own poll budget inside the caller's deadline,
     * so a correction that turns out to be expensive costs the request a bounded amount and nothing else.</p>
     */
    private static V3SolvePass hydraulicCorrection(V3ColumnInput input, V3SolvePass pass,
            V3PengRobinsonThermo thermo, V3SolvePolicy policy, V3SolveControl control, double[] profile, List<long[]> polls) {
        long[] spent = new long[1];
        int factorizedBefore = V3SolveScratch.totalFactorizations();
        V3SolveControl correctionControl = () -> {
            control.checkpoint(); // Whole-request cancellation always wins over the correction's own budget.
            if (++spent[0] > HYDRAULIC_CORRECTION_BUDGET_POLLS) throw new HydraulicBudgetExceeded();
        };
        V3ColumnProblem solved = pass.prepared().problem();
        try {
            // A profile is a request the whole contract has to admit, not just the solver: steam that was
            // superheated against the nominal can be saturated 20 kPa lower down. A refusal here is a refusal
            // of this step, so the caller can still try the half one.
            V3ColumnProblem reprofiled = V3ColumnProblemResolver.resolve(
                    input, solved.topology().condenserPhaseBranch(), profile);
            if (V3OperatingDomainValidator.assess(reprofiled, thermo)
                    instanceof V3OperatingDomainValidator.Assessment.Rejected) return null;
            V3SolvePass corrected = V3ColumnCalculator.solveSingleProblem(reprofiled, thermo, pass.attempt().state(), correctionControl,
                    pass.solvePath(), V3ColumnCalculator.ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                    HYDRAULIC_CORRECTION_MAXIMUM_ITERATIONS, policy,
                    V3SimultaneousColumnSolver.RungBudget.DEFAULT,
                    V3WetTraySet.of(reprofiled.topology(), wetTrayMask(solved)));
            return V3ColumnCalculator.publishesSuccess(corrected.attempt(), corrected.audit())
                    && V3ColumnCalculator.publishesRequestedGeometry(corrected, input)
                    && corrected.attempt().evidence().convergenceEvidence().satisfiesGates(policy.closureTolerance())
                    ? corrected : null;
        } catch (HydraulicBudgetExceeded | V3ThermoException | IllegalArgumentException refused) {
            return null;
        } finally {
            polls.add(new long[] {spent[0], V3SolveScratch.totalFactorizations() - factorizedBefore});
        }
    }

    /** The accepted free-water tray set, as the mask a re-resolved problem of the same topology re-admits. */
    private static boolean[] wetTrayMask(V3ColumnProblem problem) {
        boolean[] mask = new boolean[problem.topology().nodeCount()];
        for (int tray : problem.wetTraySet().wetTrays()) mask[tray] = true;
        return mask;
    }

    private static HydraulicPublication summarised(HydraulicPublication publication, V3TrayHydraulics hydraulics,
            V3ColumnProblem problem, V3TrayHydraulics.Tray[] traffic, double[] profile, boolean correctionApplied,
            double residualMismatch, List<long[]> polls) {
        V3TrayHydraulicsSummary summary = hydraulics.summarise(problem, traffic, profile, correctionApplied,
                Math.min(residualMismatch, 1.0));
        String event = String.format(Locale.ROOT,
                "tray hydraulics: D=%.2f m, %.0f Pa/tray, %.2f kPa total, flood %.2f at tray %d, correction=%s, "
                        + "residual mismatch %.2f %%%s",
                summary.columnDiameterMetres(), summary.meanTrayPressureDropPascal(),
                summary.totalPressureDropPascal() / 1000.0, summary.maximumFloodFraction(),
                summary.maximumFloodTray() + 1, correctionApplied ? "applied" : "not needed",
                100.0 * summary.residualMismatchFraction(), polls.isEmpty() ? "" : ", correction polls "
                        + String.join("/", polls.stream().map(spent -> String.valueOf(spent[0])).toList())
                        + ", correction band factorizations "
                        + String.join("/", polls.stream().map(spent -> String.valueOf(spent[1])).toList()));
        HydraulicPublication summarised = new HydraulicPublication(publication.pass(), publication.audit(),
                publication.solvePath(), mergedEvents(List.of(boundedEvent(event)), publication.solverEvents()),
                summary);
        if (!summary.floods()) return summarised;
        return advisory(summarised, V3TrayHydraulics.floodingWarning(summary));
    }

    /**
     * Adds one operator-facing warning to the published audit without touching a single acceptance check.
     *
     * <p>This is the same advisory channel the below-dew-point tray warning uses, so a flooded or
     * hydraulically unresolved column publishes as "Success with warning" and never as a typed failure.</p>
     */
    private static HydraulicPublication advisory(HydraulicPublication publication, String warning) {
        V3AcceptanceAudit audit = publication.audit();
        if (audit.advisoryEvidence().size() >= 16) return publication;
        List<String> evidence = new ArrayList<>(audit.advisoryEvidence());
        String bounded = "Warning: " + warning;
        evidence.add(bounded.length() <= 256 ? bounded : bounded.substring(0, 256));
        return new HydraulicPublication(publication.pass(), new V3AcceptanceAudit(audit.checks(), evidence),
                publication.solvePath(), publication.solverEvents(), publication.summary());
    }
}
