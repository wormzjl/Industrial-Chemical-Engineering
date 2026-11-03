# 2026-11-02-dry-ice-cost

Purpose: why the cold LNG worlds with CO2 crystals reach the 2 s island wall budget, and a proposal for a fundamental fix.

Status: In progress since 2026-11-02. Mode (a), form G and the junction tables are built and measured on the branch (`CARRIED_CRYSTALS_REVIEW.md`, `JUNCTION_TABLES_REVIEW.md`). All nine worlds: 0 holds, no job over 2 s. The campaign, the final lane and the merge wait for the owner's word, and the tables' carried fallback waits for the owner's decision.

## History

- 2026-11-02: open item 1 of the 0.18.0 merge (`2026-10-28-column-operation/MERGE_REVIEW.md`). The owner sent the symptoms to a Fable max subagent and asked it to consider `claude/focused-raman-3bbde7`. The symptoms widened to two groups: 183, 555, 673, 768 hold only on a slow machine; 244, 475, 651, 683, 855, 907 hold on every run.
- 2026-11-02: first pass: 86 to 90 % of a heavy job is engine flashes in the start-guess stages, most of it the crystal competition, whose crystal `adopt` dissolves back. Orchestrator's check against the code sent it back: three recorded measured reasons were missed (`FluidThermodynamics.java:1132-1134`, `CrystalFlushBudgetTest.java:27-29`, `adopt` 1320-1322), `LIQUID_LIQUID` refusals were not named, no refusal rate existed.
- 2026-11-02: measured the refusal rate by shadow flash and two adapter-level prototypes, (b) and (b+). Run totals fall 1.9 to 5.9x; the worst job falls only in 673 and 183; 651 gains two holds on a non-finite Jacobian; `CrystalFlushBudgetTest` fails its interval-1 bound. Neither prototype can land.
- 2026-11-02: 651's hold traced to a fluid-only trace liquid at a gas junction, 768's worst job to substeps x phase checks. Proposal (a) rewritten: an engine mode with the filter inside `TangentPlaneStability`'s trial loop, criterion the deposit test's sign. It is not built, and it cannot be expected to bring 651 and 768 under the budget with margin. Code guide: no change, no code changed.

- 2026-11-02: step control traced by an Opus high subagent on the owner's word (`STEP_CONTROL_REVIEW.md`). The extra attempts are solid DEPOSITION closures searched to 1 us, not phase crossings or the state cap; the phase check decides 1.2 % of its calls and is not the stage responsible.
- 2026-11-03: orchestrator's check sent the review back: plan line 93 names the delayed closure, option 3 was misdescribed, the final state shifts could be attributed, and the gate also refuses large steps. Forms A, F and G measured with (b+) standing in for mode (a), and F on today's thermo. Recommendation: G on (a), which reverses plan lines 79 and 93 and waits for the owner. No code changed.
- 2026-11-03: the owner chose "Build (a), then G" and accepted the reversal for deposition, bounded by D/v_s. main (0.19.0) merged into the branch (30ee73822); seed 855 reproduces its reference on it. Reading the engine showed that (a) as the review wrote it would read a vapour feed stable through the upper half of the two-phase region, so the plan filters dense phases and product checks only and states the stand-in in the engine. The owner sent the build to an Opus high thread.
- 2026-11-03: mode (a) and form G built. The plan's form was refused across a three-fluid window, so the form gained a gap statement in the stability filter and a held-out stand-in split (option A, the orchestrating session's reversible decision, for the owner to decide). Arrival site and `adopt`'s crystal branches removed; the density tables stay fluid-only because carried tables hold 244. Nine worlds: no job over 2 s, every worst job under 1 s, 855's holds gone, 907 holds 19 times. Named affected tests pass, six expectations changed with their physics, `CarriedCrystalsTest` added. Not run: campaign, final lane, `slowGates`, GameTests.
- 2026-11-03: orchestrator's check: `singleRoot` is `LIQUID` in both one-liquid branches (no checkpoint moved in the nine worlds, no state on an 18805-state grid, no test pins it); the plan carries a dated correction at its top and is otherwise as written; option C measured on G, 244 still stalls; 907's world is dead, not cheap, and today's 0 holds is a raised-budget number. The review now keeps A, B and C for the owner and writes 907's start stall and the PH steps as proposals. Named tests that load the carried path: 73 tests in 11 classes pass.
- 2026-11-03: the owner chose option A (B and C rejected) and proposal 1 (i), then "build it by yourself". Liquid junction tables built in this session. They cure 907 and hold 651 at job 88, where a two-phase junction the fluid phases refuse keeps its stated density. So a refused point now falls back to the carried flash, which goes beyond the owner's (i) and is written for the owner's decision. Nine worlds: 0 holds, no job over 2 s, worst 812 ms; 907 lives (371.0 + 30.2 kg). New `BalanceTablesTest`, proved with its rules off; 134 tests in 21 classes pass. Not run: campaign, final lane, whole `slowGates`, GameTests.

## Documents

- [DRY_ICE_COST_REVIEW.md](DRY_ICE_COST_REVIEW.md): physics, the measured time split, the prototypes, the proposal of mode (a).
- [STEP_CONTROL_REVIEW.md](STEP_CONTROL_REVIEW.md): the step controller in the heaviest jobs, the three closure forms, the binding rule, the recommendation.
- [CARRIED_CRYSTALS_PLAN.md](CARRIED_CRYSTALS_PLAN.md): the design of mode (a) and form G, the order of work, the harness, the reference table.
- [CARRIED_CRYSTALS_REVIEW.md](CARRIED_CRYSTALS_REVIEW.md): what was built, the options A, B and C, the nine worlds, the gates, the risks, the proposals for 907 and the PH steps.
- [JUNCTION_TABLES_REVIEW.md](JUNCTION_TABLES_REVIEW.md): the balance's tables for flashing junctions, the two forms, the nine worlds, cost and reach.

## Code areas

- `FluidThermodynamics`: `equilibriumTP/PH/UV`, `fluidPH`, `stepCompetition`, `adopt`, `crossing`, `phaseCheck`, `crystalsCompete`.
- `FluidTpEquilibrium`: `carriedTrial`, `crystalLean`, `carriedSplit`, `beyondStandIn`, the PH and UV searches. `TangentPlaneStability`: `TrialFilter`. `PhaseCompetition`, `PhaseContract.qualifies`.
- `PassiveStepSolver`: `BalanceNet.mixed` and its tables (`flashFrom`, `tabled`), `arrivingPhases`, `phaseCorrection`, `balanceStart`, the equation gate.
- `PassiveIntervalSolver.run`: `settlesWithin`, the transition catch and `transitionFloor`, which also gates the state cap. `SolidEventIntegrator`: the step-end guard and the closure segments.

## Tools

`tools/dry-ice-cost/`, with the step trace in `step/` and the mode (a) runs and probes in `carried/`. Logs: the main checkout's `tools/dry-ice-cost/logs/dry-ice-cost-logs-2026-11-02.tar.gz`, `step-control-logs-2026-11-03.tar.gz` and `carried-crystals-logs-2026-11-03.tar.gz`, local only.

## Open items

1. The tables' carried fallback goes beyond the owner's option (i) and changes A's fluid-only statement: the owner decides (`JUNCTION_TABLES_REVIEW.md`).
2. PH steps left in carried mode: 2.8 % CO2 at 541 kPa (about 370 J/mol) and 40 % at 1 MPa (2900 J/mol). No world meets them. Options for the owner in the review; nothing built.
3. Why carried density tables hold 244, on the rows of junctions with vapour and near-pure CO2. Not traced past the rows. It sits at a point the fluid phases answer: carrying only the refused points leaves 244 clean.
4. Stalls at a closure's start: 257 under F with (b+), 0.117 under F on today's thermo, 2.017 under A without (b+), and 855's 0.1059. Unexplained; G avoids them without explaining them.
5. The equation gate: Newton's junction solid row against D33's booking. The refusals at 0.06 to 1 s are not fully explained.
6. 555 is not traced.
7. Run totals rise 1.2 to 4.4x with the tables. The worst job stays under 1 s; look here first if the campaign shows a new budget hold.
8. Campaign, final lane, CHANGELOG, version, code guide, merge: the owner's word.
