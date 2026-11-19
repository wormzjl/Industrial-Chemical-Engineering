package com.wormzjl.icheme.science.column.v3;

import java.util.Objects;

/** A V3 solve either exposes an accepted immutable result or a bounded typed failure. */
public sealed interface V3ColumnOutcome permits V3ColumnOutcome.Success, V3ColumnOutcome.Failure {
    /**
     * The solve paths of the three bounds on authored pumparound cooling, each followed by the pumparound count: the
     * static admission ({@link V3Admission#staticCoolingAdmission}, the heat the feed, reboiler and steam bring), the base
     * condenser duty and a tray's condensation cap (both {@link PathDependentHeatBound}, read on the continuation path).
     */
    String STATIC_COOLING_PATH = "input/heat-", CONDENSER_BOUND_PATH = "cold/heat-condenser-bound/heat-", CONDENSATION_CAP_PATH = "cold/heat-cap/heat-";

    V3SolverDiagnostics diagnostics();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    record Success(V3ColumnResult result, V3SolverDiagnostics diagnostics) implements V3ColumnOutcome {
        public Success {
            result = Objects.requireNonNull(result, "result");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (!result.acceptanceAudit().accepted() || !diagnostics.acceptanceAudit().accepted()
                    || !result.acceptanceAudit().equals(diagnostics.acceptanceAudit())
                    || !result.convergenceEvidence().satisfiesGates()
                    || !diagnostics.convergenceEvidence().satisfiesGates()
                    || !result.convergenceEvidence().equals(diagnostics.convergenceEvidence())) {
                throw new IllegalArgumentException("A V3 success requires matching passing acceptance and convergence evidence");
            }
        }
    }

    record Failure(V3SolverFailureCode code, String summary, V3SolverDiagnostics diagnostics)
            implements V3ColumnOutcome {
        public Failure {
            code = Objects.requireNonNull(code, "code");
            summary = Objects.requireNonNull(summary, "summary");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (summary.isBlank() || summary.length() > 512) {
                throw new IllegalArgumentException("V3 failure summary is blank or exceeds the bounded contract");
            }
        }

        /**
         * Whether the solve stopped on a bound of its authored pumparound cooling against the column's own heat (the three
         * paths above): the same request at lower pumparound duties may solve. Every other failure answers false.
         */
        public boolean coolingBound() {
            String path = diagnostics.solvePath();
            return code == V3SolverFailureCode.INFEASIBLE_SPECIFICATION && path.startsWith(STATIC_COOLING_PATH)
                    || code == V3SolverFailureCode.NONCONVERGENCE && (path.startsWith(CONDENSER_BOUND_PATH) || path.startsWith(CONDENSATION_CAP_PATH));
        }
    }
}
