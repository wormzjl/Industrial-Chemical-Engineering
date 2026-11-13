package com.wormzjl.icheme.science.column.v3;

import java.util.Objects;

/**
 * Keeps the frozen problem/support/wet set/seed together through correction, audit and publication.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2). A package-private
 * record cannot keep a private canonical constructor, so the constructor became package-private with the move.</p>
 */
record PreparedAttempt(
        V3ColumnProblem problem, V3TruncationSupport support, V3WetTraySet wetTrays, V3DryMeshState seed) {
    PreparedAttempt {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(wetTrays, "wetTrays");
        Objects.requireNonNull(seed, "seed");
    }
}
