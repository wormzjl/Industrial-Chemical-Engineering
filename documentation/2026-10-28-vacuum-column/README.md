# Vacuum-column feed and distillation test

Status: In progress since 2026-10-28 (`codex/vacuum-column-test`; fix and offline retest complete, not merged).

## History

- 2026-10-28 — baseline `79b853e0` (0.17.1): accepted default CDU solve; exact bottoms feed export; three operating forms (wet, dry, reduced-stage) plus an initial wet-route replay and inlet-energy correction. Every VDU attempt returned NONCONVERGENCE. Independent CDU component closure < 1.61e-8 mol/s, every transferred feed component unchanged; no production code, Gradle gates, client or world changes.
- 2026-10-29 — follow-up to `8d511f71`: traced the unchanged failure, independently reproduced two accepted startup states containing the same liquid labelled as vapor, measured the projection's energy-defect amplification and located the 900 K range wall. Three collapsed pairs and two valid controls verified; diagnosis only, no production changes.
- 2026-10-29 — `494d3e45` diagnosis followed by `dba4ba45`: implemented zero-reflux dry upper stages, stronger phase admission, safer startup/projection and caloric continuation; accepted the unchanged CDU-bottoms feed with zero reflux, steam, two cuts and 10+15 MW cooling. Final quick 1810 and slow 188 pass with no failures/errors/skips; historical captured-state analyzer and every-component export checks pass. Exported and independently closed every VDU component to <7.11e-15 mol/s. No GUI/world check or merge.
- 2026-10-29 — tools `3509f67a`, tested production at `9c0ce21f`: completed full-chain conditioning and 1,000 cold VDU cases (228 pass all checks; heater-pressure correction fails), then 200 old/1,000 updated full positive-reflux CDU cases. Matched CDU acceptance 129→131/200; all four controls pass both; sole lost raw acceptance is a timeout that accepts alone in 11.199 s. Updated CDU accepts 682/1,000. Recorded load caveat; no speed claim, production changes or merge.

## Documents

- [VACUUM_COLUMN_REVIEW.md](VACUUM_COLUMN_REVIEW.md): expected physics, sources, complete chosen configuration, measured outcomes and proposed next work.
- [VACUUM_STARTUP_REVIEW.md](VACUUM_STARTUP_REVIEW.md): confirmed phase-admission defect, bad-seed amplification and property-range rejection wall.
- [VACUUM_FIX_REVIEW.md](VACUUM_FIX_REVIEW.md): implemented dry-stage/phase fixes, final operating case, qualifications and remaining model limits.
- [BULK_CONVERGENCE_REVIEW.md](BULK_CONVERGENCE_REVIEW.md): full-chain heater conditioning, 1,000 zero-reflux VDU cases, checks and unresolved outcomes.
- [CDU_CONVERGENCE_REVIEW.md](CDU_CONVERGENCE_REVIEW.md): full positive-reflux CDU comparison, 1,000-case screen, sole budget replay and load caveat.
- [../../tools/vacuum-column-test/README.md](../../tools/vacuum-column-test/README.md): executable harness, input cases, generated artifacts, import instructions and limitations.

## Code areas and open items

Changed: `science/column/v3/` topology, resolution, initialization, continuation, audit, seed transfer, hydraulics and column PR adapter; regression fixtures/tests; code guide column-v3/verification; offline tools. Shared fluid-network EOS arithmetic and material data were not changed.
Open: owner merge/version choice; packed-column pressure model, general dry middle sections, outer heater-enthalpy coupling and broad VDU convergence. The accepted trial uses 25 MW of authored cooling; earlier 3/8 MW trials did not converge and their feasibility remains unproven. Correcting heater enthalpy at final feed pressure also failed to converge, so no closed heater/VDU chain is claimed. The exported fixed-inlet column says OFFLINE ACCEPTED.
Raw outputs, loadable exports, frozen populations, revision results and replay are retained in this worktree's indexed `tmp/` folders while the branch remains unmerged; they are not tracked. The reviews name each run; re-run the documented commands to regenerate them.
Baseline comparison used own clean detached `.claude/worktrees/vacuum-baseline-scratch` at `494d3e45`; its targeted tests passed. Its indexed output is retained; no unmerged output was deleted. No new production probe or one-off Gradle switch remains; measurement helpers live in `tools/vacuum-column-test/`.
