# Carried crystals plan: mode (a), then form G

Owner decision, 2026-11-03: "Build (a), then G". The owner accepted the reversal of `solid-phase-fluid-system-plan.md` lines 79 and 93 for deposition, with the lateness bounded by D/v_s. Evidence: [DRY_ICE_COST_REVIEW.md](DRY_ICE_COST_REVIEW.md) (proposal (a), lines 24 to 30) and [STEP_CONTROL_REVIEW.md](STEP_CONTROL_REVIEW.md) (forms A, F, G). Nothing below is built or measured yet. The base is 30ee73822 (0.19.0 plus this batch's documents); seed 855 on it reproduces the 0.18.0 reference exactly (82576533 checkpoints, 9 holds).

## Physics

Expected behaviour. A step-level TP, PH or UV decision never books a crystal (D23); only a vessel's refresh does. So a step needs one thing from the crystal: that the CO2 the crystal would take is not a fluid phase of its own. Three shapes follow, and they are today's shapes after `adopt`:

- A dense fluid stays one liquid and carries its CO2 (today's `LIQUID_SOLID`).
- A vapour keeps its condensed CO2 as the stand-in: the liquid slot holds crystal species only (today's `VAPOR_SOLID`).
- Vapour beside a methane-rich liquid: that liquid holds the CO2, supersaturated (today's `VAPOR_LIQUID_SOLID`).

What the code does. Every step-level flash runs the crystal competition, at 5 to 24 times a fluid flash, and `adopt` dissolves the crystal back. Fluid-only alone is refused at the ambient 62 to 100 % and at 768's 114 K LNG 46 %, because a near-pure CO2 liquid, the crystal's stand-in, makes the fluid answer unstable.

The review's form does not work as written. It filters a CO2-rich trial on every feed. From a vapour-root feed supersaturated in CO2, every liquid-like trial runs away to the near-pure CO2 stationary point (`ln W_CO2 = d - ln phi`, `W_total >> 1`), so no methane-rich stationary point exists from that side. Filtering it would read the feed stable through the upper half of the two-phase region, with a jump in H(T) at G equality, and would state high-CO2 streams all vapour, the failure recorded in `adopt` (the 90 K floor). The V-L solution family is continuous from a CO2-rich L above methane's dew point to a methane-rich L below it. So a vapour feed is not filtered: it splits, and only the products' checks are filtered.

## Design

`PhaseCompetition`: a third field `carried`, and `FLUID_CARRYING_CRYSTALS` (no crystals, not packageCrystals, carried). Keep the two-argument constructor (`GasSolidEquilibriumTest:82-83`). `carried` with crystals is refused. `fluidOnly()` and `crystalsCompete()` are both false for it; `toString` gives `fluid+carried-crystals`. `solidsAssessed()` stays false. `PhaseContract.qualifies`: carried is qualified where `crystalCompetition()!=null`; `resolve` does not change.

`TangentPlaneStability`: a functional interface `TrialFilter`, `excludes(feed, feedRoot, trial, trialRoot)`, and one new overload of the full-form `test` with a filter. Every other overload stays bit for bit. In the trial loop (250-273), before the best update, for outcome `PROVEN` or `UNRESOLVED`: the trial's shares into a scratch array, and an excluded trial is skipped (it proves nothing, seeds nothing, the loop goes on). `runTrial` returns `PROVEN` only at convergence or at the iteration cap, so `w.current` is the stationary point. With a filter and no surviving trial, the result reports distance 0 and the feed.

`FluidTpEquilibrium`:

- `implementationChecks`: `w.carrying` from the competition. `w.activeCount` stays 0, so `solveWithCrystals`, `solveWetWithCrystals` and the PH and UV crystal branches are skipped.
- Workspace: a filter object built once in the constructor (no capture per call), a `PhaseState` of its own, the temperature, and a flag for a product check. The filter uses `w.first`, `w.second` and its own state, never `w.liquidState` or `w.vaporState`, which `finishSplit` reads after the tests. Count its kernel evaluations.
- `carriedTrial` is true exactly when all of these hold, cheap checks first:
  1. The crystal species' share of the trial or of the tested phase is over 1/2 (`CARRIED_SHARE`, a label for which end of the tie line is the crystal's, not a validity bound).
  2. The trial is dense (`PhaseIdentification.denseLiquidLike` on a kernel evaluation at its branch pressure). A vapour trial is never excluded.
  3. The tested phase is dense, or this is a product check. A feed on a vapour root is never filtered.
  4. The tested phase is supersaturated, by the deposit test's own sign: `(state.chemicalPotential(i) - c.anchored().chemicalPotential(t, p))/(R t) > DEPOSITION_TOLERANCE` (`solveWithCrystals`, 2796-2800). Above the triple point, where the liquid is the stable phase, nothing is excluded.
- `hydrocarbon()`: on `SPLIT` with `w.carrying`, `carriedSplit(t, w)`. One place, so the dry and the wet path both get it. Take the crystal-rich product, supersaturated; otherwise `SPLIT`.
  - Both products dense outside the critical band (the test of `twoPhaseResult`): this is the crystal's stand-in split, so the answer is the feed as one liquid: `w.singleRoot = feedRoot`, return `SINGLE`. A 40 % CO2 feed at 112 K lands here: its CO2-lean trial cannot be excluded by share. N2-rich liquid beside ethane-rich liquid with 5 % CO2 has no crystal-rich product and stays the `LIQUID_LIQUID` hold.
  - Otherwise the denser product is the stand-in: move every non-crystal species to the lighter product, evaluate both states again (denser on `LIQUID`, lighter on `VAPOR`), clear both dense flags, return `SPLIT`.
  - The classification stays `VAPOR_LIQUID`, with no new enum constant. `adopt`'s default branch stores it, `condensedShareOnly`, D29 A, D31 B and `PhaseLayout.support` read a stand-in as they do today, and the PH and UV searches see the very state the network adopts.

`NetworkPhaseEngine`: overloads of `tp`, `ph`, `crystalUv` taking a `PhaseCompetition`; the boolean ones delegate (`NetworkPhaseEngineTest` calls them many times).

`FluidThermodynamics`: `equilibriumTP`, `equilibriumPH` and `equilibriumUV` ask for `FLUID_CARRYING_CRYSTALS` where `crystalsCompete(t, overall)`, else `FLUID_ONLY`. `crystalsAtVolume` and `crystalsAtPressure` keep `FLUID_AND_CRYSTALS`: the refresh stays the only place a crystal is booked.

Remove what this makes unnecessary, after the measurement of (a) stands:

- The six-argument `equilibriumPH` and its two `false` sites in `PassiveStepSolver` (`arrivingPhases` about 1240, the density tables about 2864, with the comment on the tables' cost).
- `adopt`'s crystal branches, and the `crystal` flag of its `FREE_WATER` branch, if no caller remains. `crystalAnswer` does not use `adopt`; `NetworkPhaseEngineTest:101` and `:359` do.

Possible fallback, only if the data show refusals: a third split attempt seeded from the vapour product's unstable trial.

Recorded reasons that bind (AGENTS.md: they block removal unless the change removes the cause): `FluidThermodynamics.java:1132-1134` (three fluid phases in a bag of nitrogen; the slush tank offered 13 to 29 m3 of vapour), `adopt` 1317-1322 (CO2 let-down stated all vapour ran to the 90 K floor), `PhaseLayout.support` (methane held in the vapour only cooled an LNG-fed tank to the floor), `CrystalFlushBudgetTest.java:27-29`, `SolidEventIntegrator.java:337`.

## Form G

`PassiveIntervalSolver.run`: where `guard.checkFilters` throws a `SolidEventIntegrator.Transition` whose reason is `SolidMobility.Reason.DEPOSITION` and the step is at most D/v_s, accept the step and declare the closure at its end, after `flushTraces`. Every other reason, and a longer step, is searched as today. D is `sqrt(4 A/pi)` of the edge's `maximumArea()`, v_s is `found.check.minimumVelocity()` over the real suspension multiplier (`SlurryTransport.Settings`, not a literal 10). The prototype is `settling` and `step.lateBound` in `tools/dry-ice-cost/step/hook.pl`. Rewrite the comment of the transition catch (286-298), the class comment's Events bullet (192-195) and the `SolidClosureFeasibilityTest` bound (10.5 ms; `located >= exact - 5*floor`) to the owner's new bound. Filter and viscosity cases keep their search. Amend `documentation/2026-09-18-solid-phase-fluid-system/solid-phase-fluid-system-plan.md` lines 79 and 93 with the owner's decision.

## Order of work

1. Build (a) as far as a probe needs, and check these states before any world: LNG at 112 K and 101 kPa; gas at 138 K and 541 kPa with 0.14 % CO2; feeds just above and just below methane's dew point; a 40 % CO2 let-down; G3F7's states (91 to 120 K, 1 % CO2: one liquid). Compare carried with the competition after `adopt`: phases, amounts, H, refusals. Check a PH across methane's dew point for a jump.
2. Run the nine worlds on (a) with today's closures. Report worst job CPU, checkpoints, holds, jobs over 2 s, final states.
3. Remove what (a) makes unnecessary (see the Design section). A removal moves no number; show it on one world.
4. Build G, run the nine worlds on (a) + G.
5. Run the affected gates (below), each with `--tests`. Add engine unit tests of the three shapes, of the filter's four conditions, and that a refresh's UV is unchanged.
6. Write `CARRIED_CRYSTALS_REVIEW.md` (at most 50 lines), the batch README's history, documents and open items, and `tools/dry-ice-cost/`. Compress the run logs into the main checkout's `tools/dry-ice-cost/logs/` (never staged). Commit on this branch.

Stop there. No campaign, final lane, CHANGELOG, version bump, code guide, merge or push: those wait for the owner's word.

## Harness

`tmp/dry-ice-symptoms/run/` in this worktree, Gradle-free. It compiles `science/**` and `runtime/**` straight from `src/`:

```text
cd tmp/dry-ice-symptoms/run
source ./env.sh
export CLASSES="$RUN/classes-a" NOJFR=1
build
probe 651 a-651 -DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000
```

Output is `out/<name>-jobs.tsv`, `-states.txt`, `.log` (the `^END` line). `step/top.pl`, `col.pl`, `sum4.pl`, `fin.pl` read them. `step/runS5.sh` shows a detached serial runner. `build` also compiles the scratch sources: `src/.../EngineAccess.java` and `PhFlashCost.java` use the boolean `.ph`, `.tp` and the six-argument `equilibriumPH`, so update them when those change. Reference runs: `out/r-base-*`, `step/out/base-*`, `step/out/g-*`, and `out/head19-855` on the merged code.

Judge by the worst job, never the run total: the budget is per job, a deadline keeps nothing, and a short run can be a dead island. CPU resolution is 15.6 ms and a 1.9x machine spread is measured, so a margin under 2x can still miss 2 s. Wait for a detached runner's last line before reading its table.

## Reference

Worst job CPU in ms / holds, raised budget:

| Seed | Today | (b+) + G | (b+) alone |
|---|---|---|---|
| 651 | 2328 / 0 | 1562 / 0 | 9625 / 2 |
| 768 | 1312 / 0 | 312 / 0 | 1016 to 1266 |
| 673 | 1672 / 0 | 344 / 0 | 484 |
| 183 | 1812 / 0 | 578 / 0 | 1047 |
| 683 | 2828 / 13 | 328 / 0 | |
| 244 | 1781 / 0 | 2562 / 0 | 3047 / 1 |
| 475 | 2344 / 0 | 453 / 0 | |
| 855 | 3062 / 9 | 797 / 0 | 3000 / 9 |
| 907 | 2906 / 0 | 938 / 10 | 2000 / 10 |

(b+) stands in for (a) in these numbers and cannot land. Closure at the step's end without (b+) leaves holds: 651 1, 768 3, 183 3, 683 15.

## Risks to report on

- (b+) moves 768's tank 5.8 %, so a start guess changed an answer; (a) may do the same. A closure can pick a world's final regime (form F on today's thermo ends 768 STEADY at tank 97.6 kg against 194.0 kg).
- 907's 10 holds under (b+); 651's margin 1.28x; 244's job 57 (0.78x, 8.3 M checkpoints on every form, untraced).
- Newton stalls at a closure's start (257, 0.117, 2.017, 855's 0.1059), unexplained; 683's stall at 0.354, which (b+) cures.
- Between the frost line and the metastable liquid's dew line a gas stays one vapour, supersaturated, with no stand-in until a refresh. This is the same in (b+). A CO2 let-down below the triple point must not run to a floor; `PrecipitatedSolidsTest`'s gates decide.

## Known test changes

- `CrystalDepositionIslandTest:394-396`: flashTP at 95 K and 2 MPa becomes the `LIQUID_LIQUID` hold; the refresh's hold at 401 stays.
- `PrecipitatedSolidsTest:472`: at a frosted tank a kelvin below its frost point the TP answer no longer carries a stand-in. "No phase added" stays.
- `NetworkPhaseEngineTest`: `adopt` at 101 and 359, `crystalsCompete` at about 401.
- `SolidClosureFeasibilityTest`: the owner's bound.
- `CrystalFlushBudgetTest`: bound 10000, today 8495. Form (b) failed it at 12359. The bound does not widen.

Other affected gates: `CrystalIslandRuntimeTest`, `G3F7CarbonDioxideInLiquidMethaneTest`, `GasSolidEquilibriumTest`, `DataRangeContinuationTest`, and the tests of the changed classes. A gate's expectation changes only with a physics statement in the review; no bound widens to pass.
