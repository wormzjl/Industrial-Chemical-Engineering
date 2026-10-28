package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Objects;
import java.util.List;
import java.util.Optional;

/** Immutable accepted scientific result with bounded physical inspection extracted at publication. */
public final class V3ColumnResult {
    private final V3ColumnProblem problem;
    private final V3InputDigest inputDigest;
    private final V3AcceptanceAudit acceptanceAudit;
    private final V3ConvergenceEvidence convergenceEvidence;
    private final List<V3ColumnStreamProperties> streams;
    private final String formulationRevision;
    private final V3ColumnDutyLedger dutyLedger;
    private final String datasetRevision;
    private final V3TrayHydraulicsSummary trayHydraulics;
    private final V3ColumnInspection inspection;
    /** Process-local accepted state used only as a warm seed for a later request; never serialized. */
    private V3NeuralSeed warmSeed;

    public Optional<V3ColumnInspection> inspection() { return Optional.ofNullable(inspection); }

    /** Scientific identity captured during the solve; never reconstructed from a later live catalog. */
    public String datasetRevision() { return datasetRevision; }

    /** The full certificate; tests that need a result without a state state every field. */
    V3ColumnResult(V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence, List<V3ColumnStreamProperties> streams, String formulationRevision,
            V3ColumnDutyLedger dutyLedger, V3TrayHydraulicsSummary trayHydraulics, V3ColumnInspection inspection) {
        this.inspection = inspection;
        this.warmSeed = null;
        this.trayHydraulics = trayHydraulics;
        this.dutyLedger = dutyLedger;
        this.problem = Objects.requireNonNull(problem, "problem");
        var catalogPackage = com.wormzjl.icheme.science.material.MaterialRuntime.current().packages().get(problem.input().packageId());
        this.datasetRevision = V3HollandExample32.isPackage(problem.input().packageId())
                ? V3HollandExample32.DATASET_REVISION
                : catalogPackage == null ? "unregistered-test-model" : catalogPackage.scientificRevision();
        this.inputDigest = Objects.requireNonNull(inputDigest, "inputDigest");
        this.acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        this.convergenceEvidence = Objects.requireNonNull(convergenceEvidence, "convergenceEvidence");
        this.streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
        this.formulationRevision = Objects.requireNonNull(formulationRevision, "formulationRevision");
        if (formulationRevision.isBlank() || formulationRevision.length() > 128) {
            throw new IllegalArgumentException("V3 result formulation revision is outside the bounded contract");
        }
        if (this.streams.size() > V3ColumnStreamProperties.MAX_STREAMS) {
            throw new IllegalArgumentException("V3 result exceeds the bounded accepted stream contract");
        }
        if (!acceptanceAudit.accepted() || !convergenceEvidence.satisfiesGates()) {
            throw new IllegalArgumentException("A V3 result requires a passing acceptance audit and convergence evidence");
        }
    }

    /**
     * Publishes the rigorously accepted final MESH state with its recomputed boundary duties, and the tray
     * hydraulics of a request that authored a column diameter (null otherwise).
     */
    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence, V3DryMeshState state, V3PengRobinsonThermo thermo,
            String formulationRevision, V3ColumnDutyLedger dutyLedger, V3TrayHydraulicsSummary trayHydraulics) {
        V3ColumnResult result = new V3ColumnResult(problem, inputDigest, acceptanceAudit, convergenceEvidence,
                V3ColumnStreamProperties.fromAccepted(problem, state, thermo), formulationRevision,
                Objects.requireNonNull(dutyLedger, "dutyLedger"), trayHydraulics,
                V3ColumnInspection.fromAccepted(problem, state, acceptanceAudit));
        result.warmSeed = V3NeuralSeed.capture(problem, state, thermo.datasetRevision());
        return result;
    }

    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence, V3DryMeshState state,
            double[] molecularWeightsKgPerMol, String formulationRevision) {
        return new V3ColumnResult(problem, inputDigest, acceptanceAudit, convergenceEvidence,
                V3ColumnStreamProperties.fromAccepted(problem, state, molecularWeightsKgPerMol),
                formulationRevision, null, null, V3ColumnInspection.fromAccepted(problem, state, acceptanceAudit));
    }

    public V3ColumnProblem problem() {
        return problem;
    }

    /** Returns the process-local accepted state for a subsequent drifted request, when available. */
    public java.util.Optional<V3NeuralSeed> warmSeed() {
        return java.util.Optional.ofNullable(warmSeed);
    }

    public V3InputDigest inputDigest() {
        return inputDigest;
    }

    /** Revision used for this result's digest, including a cutoff-enabled request's untruncated retry. */
    public String formulationRevision() {
        return formulationRevision;
    }

    public V3AcceptanceAudit acceptanceAudit() {
        return acceptanceAudit;
    }

    public V3ConvergenceEvidence convergenceEvidence() {
        return convergenceEvidence;
    }

    /**
     * Relative convergence closure this result was accepted at.
     *
     * <p>Read from the certificate itself rather than stored twice: the closure a state was solved to is a
     * property of its final Newton step, and a second copy could disagree with the gate that admitted it.</p>
     */
    public double closureTolerance() {
        return convergenceEvidence.closureTolerance();
    }

    public List<V3ColumnStreamProperties> streams() {
        return streams;
    }

    /** Recomputed boundary duties; absent only on the result paths that carry no property model. */
    public Optional<V3ColumnDutyLedger> dutyLedger() {
        return Optional.ofNullable(dutyLedger);
    }

    /**
     * Tray hydraulics of the published pressure profile; present exactly for a request that authored a diameter.
     *
     * <p>The profile itself is {@code problem().nodePressuresPascal()}; this is the operator-facing reading of
     * it — total and mean drop, and how close the worst tray came to flooding.</p>
     */
    public Optional<V3TrayHydraulicsSummary> trayHydraulics() {
        return Optional.ofNullable(trayHydraulics);
    }
}
