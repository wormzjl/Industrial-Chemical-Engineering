# E0-3 The column's codes as enums (S2): report

Brief: [WAVE_E_BRIEFS.md](../WAVE_E_BRIEFS.md), section "E0-3". Review row: [COLUMN_REVIEW.md](../COLUMN_REVIEW.md) S2.
Subagent (Opus high), 2026-11-13. Structure only: no solve path, digest, published text, wire byte or saved string changes.

## Step 1: survey

Every string a branch compares (`equals`, `startsWith`, `contains`, `matches`, a `switch` on a string) in the lane:
`V3ColumnCalculator`, `V3SimultaneousColumnSolver`, `V3TruncationFallback`, `V3AcceptanceAudit`, `V3AcceptanceAuditor`,
`V3SolverDiagnostics`. Line numbers are those of `1dbb7f3cb`. No `switch` on a string exists in the lane. 27 compare
sites in all (8 attempt codes, 3 families, 6 ramp kinds, 10 open texts); compares of records (`input.equals`, `packageId().equals`, `budget.equals`, revision tokens) and
`modelId.matches` (an input validation) are not codes and are left out.

### Attempt outcome codes (`V3SimultaneousColumnSolver.Attempt.Failure.code`)

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `STATE_DOMAIN` | solver 389 | calculator 3486 (`startsWith`, to NONCONVERGENCE) | progress listener `finishedAttempt(String)` (calculator 1699, `tools/neural-budget`); tests |
| `MAX_ITERATIONS` | solver 424 | calculator 3485 (prefix) | same; `V3StageContinuationTest` |
| `STALLED` | solver 446 | calculator 3487 (prefix) | same; `V3SimultaneousColumnSolverTest` (`assertEquals("STALLED", ...)`) |
| `STALLED_SUPPORT_FLOOR` | solver 455, 537, 573 | calculator 1716 (`equals`: the release), 3487 (prefix `STALLED`) | same |
| `JACOBIAN_BAND_STRUCTURE` | solver 522 | calculator 3482 (prefix `JACOBIAN_`, to LINEAR_SOLVE_FAILURE) | same |
| `LINEAR_` + band `FailureCode` (`SINGULAR`, `ILL_CONDITIONED`, `BACKWARD_ERROR_EXCEEDED`) | solver 551 | calculator 3482 (prefix `LINEAR_`) | same; `V3SimultaneousColumnSolverTest` (`startsWith("LINEAR_")`) |
| `LINE_SEARCH_EXHAUSTED` | solver 589 | calculator 3485 (prefix `LINE_SEARCH`) | same; `V3SimultaneousColumnSolverTest` (`startsWith("LINE_SEARCH")`) |
| `INTERNAL_INVARIANT` | solver 610 | none (falls to INTERNAL_ERROR) | same |
| `CONVERGENCE_EVIDENCE*` | **no producer** | calculator 3486 (prefix) | dead |

The attempt code never reaches `V3ColumnOutcome`, the wire, a digest or a save: `failureCode` maps it to
`V3SolverFailureCode` (an enum already) and the failure summary is built from `Evidence.termination`, not from the code.
The record's `toString` (assertion messages only) is the one place the component name shows.

### Audit check families (`V3AcceptanceAudit.Check.family`)

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `LOCAL_COMPONENT_BALANCE`, `EQUILIBRIUM`, `ENERGY_BALANCE` | auditor 86 to 91 (`maximumFamily`) | none in the lane | result audit |
| `GLOBAL_ENERGY_BALANCE`, `CONDENSER_ENERGY_BALANCE`, `INTERIOR_PHASE_IDENTITY`, `WATER_PROFILE`, `WATER_BALANCE`, `CARRY_OVER`, `FREE_WATER_SPLIT`, `SIDE_DRAW_SPLIT`, `FINITE_TOPOLOGY`, `TRUNCATION_MASS_DEFECT`, `PHASE_TRUNCATION_DEFECT` | auditor 191 to 727 | none in the lane | result audit |
| `WATER_DEW_POINT` | auditor 458 to 476 | calculator 311 (`equals`: the advisory neural candidate) | result audit; `tools/neural/V3WetQualificationProbe` |
| `CONDENSER_PHASE` | auditor 742 to 824 | calculator 1817 (`equals`), 1833 (`!equals`) | result audit |
| `UNAVAILABLE` | calculator 754, 3391, 3462 (`failedAudit`) | none | failure audit |

Every family leaves the solver: `V3InspectionCodec` writes `family()` as UTF text (96 chars) on the wire and in NBT and
decodes it back into a `Check` from that text; `ColumnGlass` shows it; `V3TruncationFallback` 23 writes it into a
published event (`audit=<family>`); tests and tool fixtures build checks with their own families (`"material"`,
`"TEST"`, `"HOLLAND_BENCHMARK"`) and compare the text (`family().equals`) in about 40 places.

### Ramp kinds (`V3ColumnCalculator.RampStep.pathLabel`)

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `heat-ramp` (`HEAT_RAMP_LABEL`) | calculator 2431 | 1895 (`heatRung`), 2378 | `solvePath` (2080, concatenated), energy-shift note (2310) |
| `steam-ramp` (`STEAM_RAMP_LABEL`) | 2355, 2426 | 1899 (`steamRung`) | same |
| `wet-ramp` (`WET_RAMP_LABEL`) | 2355 | 1899 (`steamRung`) | same |
| `draw-ramp` | 2349, 2362, 2440 | 2377 | same |
| `coupled-ramp` | 1980 | none | same |
| previous label (`""` at start) | 2372 | 2374 (`!equals(previousPath)`: the first rung of a kind) | no |

`solvePath` is matched with `contains` by tests and is bounded to 128 characters; only `pathLabel()` text enters it.

### Event prefixes and other open texts

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `stage-trace support: <note>` | calculator 1789 | calculator 2732 (`startsWith`) | diagnostics events |
| `condenser phase transition...` (three forms) | calculator 1835, 1843, 1849 | calculator 2732 (`startsWith`) | diagnostics events |
| `flash-trace cutoff=...` | calculator 3080 | calculator 2733 (`startsWith("flash-trace ")`) | diagnostics events |
| `stage-trace cutoff=...` | calculator 3069 | `V3TruncationFallback` 19 (`startsWith`) | diagnostics events |
| any event text | (the list above) | calculator 2735 (`contains`: de-duplication) | diagnostics events |
| check detail `warning: <text>` | auditor 597 | auditor 556 (`startsWith`) | result audit detail |
| exception message `NEWTON_OFF_BAND_MESSAGE`, `DAMPED_NORMAL_OFF_BAND_MESSAGE` | solver 757; `V3NormalEquations` 161 | solver 521, 872, 885 (`equals`) | no (rethrown otherwise) |
