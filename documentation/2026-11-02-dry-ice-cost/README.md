# 2026-11-02-dry-ice-cost

Purpose: why the cold LNG worlds with CO2 crystals reach the 2 s island wall budget, and a proposal for a fundamental fix.

Status: In progress since 2026-11-02. Analysis finished, no code changed; the proposal waits for the owner.

## History

- 2026-11-02: open item 1 of the 0.18.0 merge (`2026-10-28-column-operation/MERGE_REVIEW.md`). The owner sent the symptoms to a Fable max subagent and asked it to consider `claude/focused-raman-3bbde7`. The symptoms widened to two groups: 183, 555, 673, 768 hold only on a slow machine; 244, 475, 651, 683, 855, 907 hold on every run.
- 2026-11-02: first pass: 86 to 90 % of a heavy job is engine flashes in the start-guess stages, most of it the crystal competition, whose crystal `adopt` dissolves back. Orchestrator's check against the code sent it back: three recorded measured reasons were missed (`FluidThermodynamics.java:1132-1134`, `CrystalFlushBudgetTest.java:27-29`, `adopt` 1320-1322), `LIQUID_LIQUID` refusals were not named, no refusal rate existed.
- 2026-11-02: measured the refusal rate by shadow flash and two adapter-level prototypes, (b) and (b+). Run totals fall 1.9 to 5.9x; the worst job falls only in 673 and 183; 651 gains two holds on a non-finite Jacobian; `CrystalFlushBudgetTest` fails its interval-1 bound. Neither prototype can land.
- 2026-11-02: 651's hold traced to a fluid-only trace liquid at a gas junction, 768's worst job to substeps x phase checks. Proposal (a) rewritten: an engine mode with the filter inside `TangentPlaneStability`'s trial loop, criterion the deposit test's sign. It is not built, and it cannot be expected to bring 651 and 768 under the budget with margin. Code guide: no change, no code changed.

## Documents

- [DRY_ICE_COST_REVIEW.md](DRY_ICE_COST_REVIEW.md): physics, the measured time split, the prototypes, the proposal.

## Code areas

- `FluidThermodynamics`: `equilibriumTP/PH/UV`, `adopt`, `crossing`, `phaseCheck`, `crystalsCompete`.
- `FluidTpEquilibrium`: the PH and UV searches, the deposit test (2729-2731), `TangentPlaneStability` (trial loop 250-273).
- `PassiveStepSolver`: `BalanceNet.mixed`, `arrivingPhases`, `phaseCorrection`, step control.

## Tools

`tools/dry-ice-cost/`. Logs: the main checkout's `tools/dry-ice-cost/logs/dry-ice-cost-logs-2026-11-02.tar.gz`, local only.

## Open items

1. Owner: whether to build mode (a).
2. The substep count at phase transitions and the per-substep phase check: what bounds 651's and 768's worst jobs. Not analysed.
3. 683: Newton line search stalled at residual 0.354 in a pipe blocked with solid. A convergence defect, not cost.
4. 244, 475, 555, 855 and 907 are not traced.
