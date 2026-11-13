package com.wormzjl.icheme.science.column.v3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact, immutable presentation certificate for a committed accepted V3 result.
 *
 * <p>This excludes solver workspaces; optional bounded physical profiles are safe to inspect. It is safe to persist and send as a
 * bounded screen summary, but it is not a numerical result and cannot be used as a warm start or success input.</p>
 */
public record V3ColumnDisplayResult(
        String inputDigest,
        String formulationRevision,
        String assumptionsRevision,
        String datasetRevision,
        int newtonIterations,
        double maximumScaledResidual,
        int acceptanceCheckCount,
        List<V3ColumnStreamProperties> streams,
        Optional<V3ColumnDutyLedger> dutyLedger,
        double closureTolerance,
        Optional<V3TrayHydraulicsSummary> trayHydraulics,
        Optional<V3ColumnInspection> inspection) {
    public V3ColumnDisplayResult {
        Objects.requireNonNull(inspection, "inspection");
        if (inspection.isPresent() && inspection.orElseThrow().audit().checks().size() != acceptanceCheckCount)
            throw new IllegalArgumentException("Inspection audit differs from certificate");
        Objects.requireNonNull(dutyLedger, "dutyLedger");
        Objects.requireNonNull(trayHydraulics, "trayHydraulics");
        V3ConvergenceEvidence.requireClosure(closureTolerance);
        inputDigest = boundedDigest(inputDigest);
        formulationRevision = boundedRevision(formulationRevision, "formulationRevision", 128);
        assumptionsRevision = boundedRevision(assumptionsRevision, "assumptionsRevision", 128);
        // The package's data identity, every record's declared revision (MaterialCatalog.Package).
        datasetRevision = boundedRevision(datasetRevision, "datasetRevision", com.wormzjl.icheme.science.material.MaterialCatalog.MAX_IDENTITY);
        if (newtonIterations < 0 || !Double.isFinite(maximumScaledResidual) || maximumScaledResidual < 0.0
                || acceptanceCheckCount < 1 || acceptanceCheckCount > 64) {
            throw new IllegalArgumentException("Invalid compact V3 display certificate");
        }
        streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
        if (streams.size() > V3ColumnStreamProperties.MAX_STREAMS) {
            throw new IllegalArgumentException("V3 display certificate has too many product streams");
        }
    }

    /** Creates the compact presentation certificate only from a rigorously accepted scientific result. */
    public static V3ColumnDisplayResult fromAccepted(V3ColumnOutcome.Success success) {
        success = Objects.requireNonNull(success, "success");
        V3ColumnResult result = success.result();
        return new V3ColumnDisplayResult(
                result.inputDigest().hexadecimalSha256(),
                result.formulationRevision(),
                V3ColumnLabels.assumptionsRevision(result.problem().input()),
                result.datasetRevision(),
                success.diagnostics().newtonIterations(),
                success.diagnostics().maximumScaledResidual(),
                result.acceptanceAudit().checks().size(),
                result.streams(),
                result.dutyLedger(),
                result.closureTolerance(),
                result.trayHydraulics(), result.inspection());
    }

    private static String boundedDigest(String value) {
        value = Objects.requireNonNull(value, "inputDigest");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("V3 display digest is invalid");
        return value;
    }

    private static String boundedRevision(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is outside the bounded display contract");
        }
        return value;
    }
}
