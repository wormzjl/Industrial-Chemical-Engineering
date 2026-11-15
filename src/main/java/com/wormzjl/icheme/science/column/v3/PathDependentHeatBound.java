package com.wormzjl.icheme.science.column.v3;

import java.util.Objects;

/**
 * A heat bound that only the continuation path this request happened to take could measure.
 *
 * <p>Both bounds that raise this — {@link V3FeatureRamp#requireCoolingBelowBaseCondenserDuty} and
 * {@link V3FeatureRamp#condensationCappedTray} — read the last accepted continuation state. Their verdict is therefore
 * a property of this initializer's path, not of the authored request: a differently seeded path can reach
 * a state on which the same request solves. Measured on the 405-case neural validation population, 42 of
 * the 108 requests the classical path typed {@code INFEASIBLE_SPECIFICATION} are strictly solved by a
 * neural-seeded pipeline on the identical input.</p>
 *
 * <p>So these are published as {@link V3SolverFailureCode#NONCONVERGENCE} carrying the bound's own
 * diagnostic plus the path hint, and {@code INFEASIBLE_SPECIFICATION} is left to the request-only gates
 * ({@code totalDraw >= totalFeed} and {@link V3Admission#staticCoolingAdmission}), which are computable from the
 * specification alone.</p>
 *
 * <p>Moved out of {@link V3ColumnCalculator} with {@link #PATH_DEPENDENT_BOUND_SUFFIX} (batch
 * 2026-11-13-codebase-review, D-C4): a nested class before. Its constructor and {@link #solvePath()} are
 * package-private now: {@link V3FeatureRamp} throws it and {@code V3ColumnCalculator.calculateBranch} reads the
 * path.</p>
 */
final class PathDependentHeatBound extends RuntimeException {
    private final String solvePath;

    PathDependentHeatBound(String detail, String hint, String solvePath) {
        super(detail + "; " + hint + PATH_DEPENDENT_BOUND_SUFFIX);
        this.solvePath = Objects.requireNonNull(solvePath, "solvePath");
    }

    String solvePath() {
        return solvePath;
    }

    /** Names the path dependence in every published state-dependent heat bound, so the GUI cannot read it as infeasibility. */
    static final String PATH_DEPENDENT_BOUND_SUFFIX =
            "; this bound reads the last accepted continuation state rather than the request, so the "
                    + "specification is not typed infeasible";
}
