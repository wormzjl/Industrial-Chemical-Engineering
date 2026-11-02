# 2026-11-02-dry-ice-cost

Purpose: why the cold LNG worlds with CO2 crystals reach the 2 s island wall budget, and a proposal for a fundamental fix.

Status: In progress since 2026-11-02. Analysis finished, no code changed; the proposal waits for the owner.

## History

- 2026-11-02: open item 1 of the 0.18.0 merge (`2026-10-28-column-operation/MERGE_REVIEW.md`). The owner sent the symptoms to a Fable max subagent and asked it to consider `claude/focused-raman-3bbde7`. The symptoms widened to two groups: 183, 555, 673, 768 hold only on a slow machine; 244, 475, 651, 683, 855, 907 hold on every run.
- 2026-11-02: first pass: 86 to 90 % of a heavy job is engine flashes in the start-guess stages, most of it the crystal competition, whose crystal `adopt` dissolves back. Orchestrator's check against the code sent it back: three recorded measured reasons were missed (`FluidThermodynamics.java:1132-1134`, `CrystalFlushBudgetTest.java:27-29`, `adopt` 1320-1322), `LIQUID_LIQUID` refusals were not named, no refusal rate existed.
- 2026-11-02: measured the refusal rate by shadow flash and two adapter-level prototypes, (b) and (b+). Run totals fall 1.9 to 5.9x; the worst job falls only in 673 and 183; 651 gains two holds on a non-finite Jacobian; `CrystalFlushBudgetTest` fails its interval-1 bound. Neither prototype can land.
- 2026-11-02: 651's hold traced to a fluid-only trace liquid at a gas junction, 768's worst job to substeps x phase checks. Proposal (a) rewritten: an engine mode with the filter inside `TangentPlaneStability`'s trial loop, criterion the deposit test's sign. It is not built, and it cannot be expected to bring 651 and 768 under the budget with margin. Code guide: no change, no code changed.

- 2026-11-02: step control traced by an Opus high subagent on the owner's word (`STEP_CONTROL_REVIEW.md`). The extra attempts are solid DEPOSITION closures searched to 1 us, not phase crossings or the state cap; the phase check decides 1.2 % of its calls and is not the stage responsible.
- 2026-11-03: orchestrator's check sent the review back: plan line 93 names the delayed closure, option 3 was misdescribed, the final state shifts could be attributed, and the gate also refuses large steps. Forms A, F and G measured with (b+) standing in for mode (a), and F on today's thermo. Recommendation: G on (a), which reverses plan lines 79 and 93 and waits for the owner. No code changed.

## Documents

- [DRY_ICE_COST_REVIEW.md](DRY_ICE_COST_REVIEW.md): physics, the measured time split, the prototypes, the proposal of mode (a).
- [STEP_CONTROL_REVIEW.md](STEP_CONTROL_REVIEW.md): the step controller in the heaviest jobs, the three closure forms, the binding rule, the recommendation.

## Code areas

- `FluidThermodynamics`: `equilibriumTP/PH/UV`, `adopt`, `crossing`, `phaseCheck`, `crystalsCompete`.
- `FluidTpEquilibrium`: the PH and UV searches, the deposit test (2729-2731), `TangentPlaneStability` (trial loop 250-273).
- `PassiveStepSolver`: `BalanceNet.mixed`, `arrivingPhases`, `phaseCorrection`, `balanceStart`, the equation gate.
- `PassiveIntervalSolver.run`: the transition catch and `transitionFloor`, which also gates the state cap. `SolidEventIntegrator`: the step-end guard and the closure segments.

## Tools

`tools/dry-ice-cost/`, with the step trace in `step/`. Logs: the main checkout's `tools/dry-ice-cost/logs/dry-ice-cost-logs-2026-11-02.tar.gz` and `step-control-logs-2026-11-03.tar.gz`, local only.

## Open items

1. Owner: whether to build mode (a). Everything measured so far stands on (b+), which cannot land.
2. Owner: form G, which reverses plan lines 79 and 93 for DEPOSITION, or form F, which keeps them and leaves holds.
3. Stalls at a closure's start: 257 under F with (b+), 0.117 under F on today's thermo, 2.017 under A without (b+), and 855's 0.1059. Unexplained; G avoids them without explaining them.
4. 683's stall at 0.354: (b+) cures it, and mode (a) may not.
5. 907's 10 holds under (b+), and the margins of 651 (1.28x) and 244 (0.78x, job 57 not traced).
6. 768's tank moves 5.8 % under (b+), so a start guess changed the answer: a risk for mode (a).
7. The equation gate: Newton's junction solid row against D33's booking. The refusals at 0.06 to 1 s are not fully explained.
8. 555 is not traced.
