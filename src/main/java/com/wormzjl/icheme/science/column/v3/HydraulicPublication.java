package com.wormzjl.icheme.science.column.v3;

import java.util.List;

/**
 * What the hydraulic step decided to publish: possibly a different pass, and always its own evidence.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2).</p>
 */
record HydraulicPublication(V3SolvePass pass, V3AcceptanceAudit audit, String solvePath,
        List<String> solverEvents, V3TrayHydraulicsSummary summary) {}
