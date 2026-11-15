# 2026-11-15 fluid step cost

**Status:** In progress since 2026-11-15. Branch `claude/fluid-step-jacobian`, base `main` e9edc974b (0.31.0).

Owner request, 2026-11-15: "do the analytic Jacobian and workspace items first", before any decision on a Rust port of the fluid network solver. A performance task.

## History

- 2026-11-15: the plan ([FLUID_STEP_COST_PLAN.md](FLUID_STEP_COST_PLAN.md)), from reading `SparseNewton`, `StepEquations.differentiate` and the 2026-11-13 profile. Found on reading: the thermodynamic half of the Jacobian is analytic since 2026-11-12; what the profile's reach search (10.7 % of the step) needs is a pattern fixed by structure, not analytic rows. Steps: S0 measure, S1 the pattern (results move in the last digits), S2 kept workspaces (bit for bit), S3 measure and decide. Nothing built.
- 2026-11-15: the owner: "Start both". Two subagents (Opus high, own worktrees, no builds): S0 (the counters `jacobianDeclaredEntries` and `luFactorEntries`, the observer `SolverDiagnostics.patternObserver`, the census probe `tools/fluid-step-cost/`) and S2 (kept kernel workspaces, tangent and decode buffers, bit for bit). The main thread merges, compiles, runs the named tests and the bench alone.

## Documents

- [FLUID_STEP_COST_PLAN.md](FLUID_STEP_COST_PLAN.md): the plan.

## Code areas

`science/fluid/solver` (`SparseNewton`, `PhaseLayout`), `science/fluid/network/StepEquations`, `science/fluid/linalg`, `science/fluid/thermo` (`FluidThermodynamics`, `HydrocarbonModel`), `science/fluid/diagnostics/SolverDiagnostics`.

## Open items

- S0 and S2 are being written; nothing is merged or run.
