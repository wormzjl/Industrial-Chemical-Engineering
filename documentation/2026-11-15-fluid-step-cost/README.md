# 2026-11-15 fluid step cost

**Status:** In progress since 2026-11-15. Branch `claude/fluid-step-jacobian`, base `main` e9edc974b (0.31.0).

Owner request, 2026-11-15: "do the analytic Jacobian and workspace items first", before any decision on a Rust port of the fluid network solver. A performance task.

## History

- 2026-11-15: the plan ([FLUID_STEP_COST_PLAN.md](FLUID_STEP_COST_PLAN.md)), from reading `SparseNewton`, `StepEquations.differentiate` and the 2026-11-13 profile. Found on reading: the thermodynamic half of the Jacobian is analytic since 2026-11-12; what the profile's reach search (10.7 % of the step) needs is a pattern fixed by structure, not analytic rows. Steps: S0 measure, S1 the pattern (results move in the last digits), S2 kept workspaces (bit for bit), S3 measure and decide. Nothing built.
- 2026-11-15: the owner: "Start both". Two subagents (Opus high, own worktrees, no builds): S0 (the counters `jacobianDeclaredEntries` and `luFactorEntries`, the observer `SolverDiagnostics.patternObserver`, the census probe `tools/fluid-step-cost/`) and S2 (kept kernel workspaces, tangent and decode buffers, bit for bit). The main thread merges, compiles, runs the named tests and the bench alone.
- 2026-11-15: **S0** merged (`77af86832`; the tests its files reach: 0 failures) and run. The census (plan, section 4b): the kept set changes in 92.7 % of consecutive factorizations, and rounding is not why (5 % of the flipping entries are under 1e-12 of their row); 31 % of the declared entries are a node's equilibrium, normalization and wall rows against a neighbour, which are never kept; half of the cross-node entries are zero by upwinding. Form B (noise floor) is ruled out; S1 compares F1 (a true declaration) and F2 (F1 and the upstream entries kept). The main thread added the transitions between consecutive factorizations to the probe.
- 2026-11-15: **S2** merged (`dafb95278`; subagent commits `fd585c1a2`, `0b683ee75`): a retained solver keeps its node bundles across steps, retired and refilled as new; the Jacobian sweep's tangents are filled into kept storage. The decode buffers were not done (their escape could not be proved). Gates: the named tests 0 failures; chain-100 exact, every deviation 0; `MIXED_GAS_COST` 313 Newton solves as before, 318.7 to 246.5 MB allocated.
- 2026-11-15: the bench pair, baseline `509d15e9d` against S2 (plan, section 4c). Every solver counter identical; bytes -20.3 %; island step mean 34.83 to 33.52 ms (-3.8 %, one pair, spread not measured), p95 unchanged; heap after a collection +0.17 GB (1.7 MB an island for the kept bundles). The profile by stack (`tools/fluid-step-cost/JfrAncestors.java`, made for it): the vessel closure after every step is 19.7 % of the step's CPU, 17.4 % in a Jacobian differenced on the equation of state (16,674 builds a minute, 43 decodes each). Two steps added to the plan: **S2b** (an evaluation with no bundle takes the thread's kept workspace: the remaining 8.4 GB a minute, bit for bit) and **S4** (the closure's Jacobian from phase tangents; results move in the last digits). The comments S2 left stale are corrected (`PassiveStepSolver`, `PassiveIntervalSolver`, the code guide's `fluid-thermo-port.md`).
- 2026-11-15: the owner: "Start both" (S2b and S4, one subagent each: Opus medium and Opus high, own worktrees, no builds).
- 2026-11-15: **S2b** merged (`bb771a9eb`; subagent commit `04cabf5f6`): an evaluation given no bundle takes its thread's kept Peng-Robinson workspace (`FluidThermodynamics.takeStandalone`, `giveStandalone`; counter `standaloneWorkspacesBuilt`). Gates: `PreparedRefillTest` (new case), `PassiveStepSolverTest`, `PhaseLayoutTest`, `HydrocarbonTangentTest`, `EngineDigestTest`: 22 tests, 0 failures; chain-100 exact, every deviation 0. No Rust twin. Bench pair (plan, section 4c): every solver counter identical; bytes 28.35 to 20.06 GB a minute (-43.6 % from the baseline); island step mean 30.64 ms (-12.0 % from the baseline's 34.83), p95 48.90 ms (-12.9 %).
- 2026-11-15: **S4** merged (`2b35cbdb4`; subagent commit `5289cd36f`): the vessel closure's Jacobian is swept on the node's phase tangents (`PhaseLayout.wallClosure`, `differentiateEntries`), the coloured sweep its fallback; `junctionClosure` stays on differences. Gates: `WallClosureJacobianTest` (new, 7 cases) and 14 other classes, 82 tests, 0 failures; chain-100 in its declared mode, every deviation 0 (it builds no closure Jacobian, so its reference stays exact); hold-fuzz seeds 1 to 200 on `tools/fluid-step-cost/`'s census: the one held seed (145) as before, every end tick equal. No Rust twin. Bench pair (plan, section 4c): island step mean 27.42 ms (-21.3 % from the baseline's 34.83), p95 45.46 ms (-19.0 %); residual evaluations 761,947 to 45,061 a minute; Newton solves 19,371 to 19,365 and iterations 25,710 to 25,696 (results move in the last digits, no closure gains an iteration); no held interval. The linear algebra is now 36.6 % of the step's CPU.
- 2026-11-15: the owner: "Start S1". The design is in the plan, section 4d: three forms as three commits (F1 the declaration made true, F2 the live direction's entries kept, F3 a minimum-degree ordering for the Newton), one subagent (Opus high, own worktree, no builds). F3 was added by the main thread after reading the ordering (reverse Cuthill-McKee on the declared graph) and the fill (120,565 entries of L and U against about 42,000 kept).

## Documents

- [FLUID_STEP_COST_PLAN.md](FLUID_STEP_COST_PLAN.md): the plan.

## Code areas

`science/fluid/solver` (`SparseNewton`, `PhaseLayout`), `science/fluid/network/StepEquations`, `science/fluid/linalg`, `science/fluid/thermo` (`FluidThermodynamics`, `HydrocarbonModel`), `science/fluid/diagnostics/SolverDiagnostics`.

## Open items

- S1 (forms F1, F2, F3) is being written by its subagent; the main thread then measures each and keeps one.
- S4 moves results in the last digits wherever a vessel's closure iterates: the final lane records the references that move (the `VIRTUAL_HOLDUP_DIGEST` line among them) once, old and new values stated.
- `wallClosure` decodes the base point three times a build (the Newton's residual, `linearising`, the sweep); keeping the bracket's state would save one. Not done: under 1 % of the step.
- The kept bundles' heap (1.7 MB an island): kept as built. Dropping the probe bundles at the end of a step halves it and builds one workspace a node a step again; decided with S3's numbers.
- The closure's own bundle (1.43 GB a minute) and `junctionClosure`'s difference Jacobian stay as they are (plan, section 4c).
- S3: the bench on a fixture that saturates the workers (occupancy reads 0.25 on `mixed100`), then the owner's decision on analytic rows, the Rust gate, or stop.
- The final lane and the merge preparation, on the owner's word; chain-100 is recorded again there if S4 or S1 is built.
