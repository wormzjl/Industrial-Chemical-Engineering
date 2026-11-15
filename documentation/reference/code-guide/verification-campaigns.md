# Verification: the exact regression, the printed lines, the hold-fuzz campaigns and determinism

Up: [code guide](README.md). The tasks and the final lane: [verification.md](verification.md); the test map and corpus: [verification-tests.md](verification-tests.md). AGENTS.md, "Use the least measurement that decides the task", sets how much of this a task may run; this chapter says how to run and read each check, never what the last run gave (results are in the batch reports).

## Read this when

- A solver or network physics change must be judged: chain-100 exact, the junction lines, `MIXED_GAS_COST`, a quick check or a campaign.
- A report names a hold class (A, B, C, D, E1 to E10, F, G, X), "budget-class hold", "cold random", "bundled defaults", "clean seeds" or `refused=`.
- A reference must be re-recorded, or a result moved and the move must be reported.
- A test fails only on another JDK, or a change touches elementary functions.

## What it does

Four instruments measure the fluid solver beyond the unit tests. **chain-100** replays one fixed 100-reservoir chain for one interval and compares it with a committed reference, bit for bit in `exact` mode. The **printed lines** of the full suite (above all the 33 junction lines of two transient tests and `MIXED_GAS_COST`) are compared line by line with the previous lane's. The **hold fuzz** builds a random world per seed, places it through the real event path at random online ticks, runs it and records every hold; a **quick check** runs 200 seeds, a **campaign** 1000, in three report sets, and the classifier sorts each seed by its first hold. **Determinism checks** keep every result bitwise equal across JDKs and machines, so any moved fixed-seed result is a finding, not noise.

## Class map

| File | Role |
|---|---|
| `src/test/java/com/wormzjl/icheme/fluid/benchmark/FluidSolverRegressionTest`, `SolverRegressionHarness` | The chain-100 replay and comparison (`cosineChain`, `REPORT`); run only by `fluidSolverRegression` |
| `src/test/resources/fluid/regression/chain-100.json` | The committed reference |
| `runtime/fluid/LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest` (tests) | Print the 33 junction lines and `MIXED_GAS_COST` |
| [`tools/floating-roof-tanks/final-lane/printed.sh`](../../../tools/floating-roof-tanks/final-lane/printed.sh) | `printed.sh <results dir> <out file>`: every test's printed lines, class by class, package dropped |
| [`tools/unified-thermo-port-forms/`](../../../tools/unified-thermo-port-forms/) `hold-fuzz-cold/`, `hold-fuzz-bundled/` | The two harnesses (`HoldFuzzTest`, attached to the test source set only by their `hold-fuzz.init.gradle`), with the hold classes' reproductions under `src/repro/` |
| [`tools/phase-ports-probes/hold-fuzz/classify.sh`](../../../tools/phase-ports-probes/hold-fuzz/classify.sh) | `classify.sh <report>`: one line per class by each seed's first hold, written to `<report>.classes.txt` |
| [`tools/fluid-solver-simplification/quick-lane.sh`](../../../tools/fluid-solver-simplification/quick-lane.sh) | The Gradle-free 200-seed quick check on an export of the javac lane (`tools/unified-thermo-port-m5/d40/lane.sh`) |
| [`tools/merge-lane/compare.mjs`](../../../tools/merge-lane/compare.mjs) | A head lane against a baseline lane ([verification.md](verification.md)) |
| `science/fluid/DeterministicMathTest`, `DeterministicMathSourceTest` (tests) | The math facade against StrictMath; the source scan |
| [`tools/jdk-determinism/`](../../../tools/jdk-determinism/) | One build under many runtimes, comparing the junction lines, chain-100 and bitwise probes |

## Main flow

**chain-100**: `gradlew fluidSolverRegression -PfluidRegressionMode=exact` builds the chain in code, replays one 5 s interval and compares every unit's state with the reference; `exact` prints the largest deviation (0 when bitwise equal). The report with solver counters per interval goes to `build/reports/fluid/solver-optimization.json`.

**Printed lines**: the lane's test step writes `printed.txt` from the JUnit XML and `junction.txt` when the two junction tests ran; `compare.mjs` compares the junction lines' integer counters and `MIXED_GAS_COST`'s `newtonSolves`; any other moved line is read by hand against the baseline lane's `printed.txt`.

**Campaign** (the lane's last step, or alone `lane.sh head campaign`): for each set, `test --tests '*HoldFuzzTest'` under the harness's init script with `-PholdFuzzSeeds=1-1000 -PholdFuzzThreads=8 -PholdFuzzCounters=true`, `-PholdFuzzDefaults` for defaults and `-PholdFuzzCold=true` for the cold harness, `-PholdFuzzOut=<report>`; then `classify.sh` on the report. Each report gives a `SUMMARY` line (with `factor=`, the time factor), `COUNTERS` and `.classes.txt`.

**Quick check**: `lane.sh <sha> quick` with Gradle, or `bash tools/fluid-solver-simplification/quick-lane.sh <export> <label> [seeds] [dists]` on the javac lane (4 threads, counters on); seed lists take ranges and commas.

**Reading a campaign**: `node tools/merge-lane/compare.mjs <base lane> <head lane> [<campaign base>]` prints, per set, the clean count, refusals, Newton counters, new, fixed and moved holds by class, and the new budget-class (C, F) holds. Replay alone, batched in one run, only new C and F holds not in the baseline; list worse worlds by class; minimize and trace at most the two smallest new ones (AGENTS.md).

## Contracts

- **Report sets** (deterministic per seed): **cold random** (the pilot cryogenic package as the network package through `PilotCryogenicTestCatalog`; LNG generators with some CO2, or a CO2-rich gas), **bundled random** (random block specs on the bundled catalog) and **bundled defaults** (blocks as the world places them). The harnesses run at the game's time factor (`SimulationTime.DEFAULT_FACTOR`) unless `-PholdFuzzTimeScale` is given, and record it on the `SUMMARY` line; campaigns at different factors, or before and after `holdFuzzPipeBetween`, are not comparable.
- **A fuzz seed stops at its first hold**; a held seed's one replay alone, in the lane, is its measurement (AGENTS.md). Never loop a suite to count a flaky hold.
- **Hold classes** (`classify.sh`, by the first hold's status): A state-change lock; B trace underflow (non-finite matrix values); **C phase hold (typed)**, `HELD (phases)` (a capability limit: leaving a data range never holds); D singular at the interval-start rate pass; E1 rate pass does not converge; E2 step solve singular at every step size; E3 a partial pressure outside the numerical box; E4 reconstruction equation gate; E5 step line search stalls; E6 step Newton iteration limit; E7 active-set cycle; E8 interval attempt limit; E9 other; E10 no finite Jacobian trial at a first step; **F budget**, `HELD: wall deadline`; G zero row at the interval start; X exception. C and F are the budget-class holds of the lean rule.
- **Counters, not time.** Wall times and whole-campaign counters move with machine load (a seed can hold F under load and run clean alone): compare counters on the seeds clean in both runs, and replay a new F hold alone before counting it. No times are reported outside a performance task.
- **chain-100 tolerances**: `declared` (the default) is 1e-6 relative on pressure, mass, volume and totals, 1e-4 K, 1e-6 on phase fractions, 1e-3 on average flows; `exact` is bitwise; `relative` reads as `declared`.
- **Determinism**: all transcendental math goes through `DeterministicMath` (the facade's `atan` and `tanh` are StrictMath bit for bit; `sin`, `asin`, `atan2` delegate to StrictMath), so fixed-seed results (chain-100, the junction lines, GameTest prints at a fixed tick, fuzz seeds) compare bitwise.

## Laws and constants

- The campaign: seeds 1 to 1000 on 8 workers, never overlapping another campaign of that size; a run on the second Gradle lane at most 4 workers; the quick check 200 seeds in all three sets, at most three forms per task (AGENTS.md).
- The campaign's inputs: `tools/merge-lane/campaign-inputs.txt` ([verification.md](verification.md)).

## Formats

`chain-100.json` holds the reference states; a capture that reproduces it bit for bit leaves the file unchanged in git. Fuzz reports are text: `SEED`, `HELD`, `EXCEPTION`, `SUMMARY`, `COUNTERS` lines.

## Tests and gates

- **Re-record chain-100** only for a reviewed move named in the CHANGELOG: `gradlew fluidSolverRegression -PfluidRegressionCapture=true` writes the reference in place. To prove a change bitwise neutral without touching the file, capture into a scratch folder with `-PfluidRegressionReferences=<dir>` on the old commit, then run `exact` against it on the new one. `git log -- src/test/resources/fluid/regression/chain-100.json` lists the re-records.
- **A moved reference** (AGENTS.md, "Results that move"): the review states the old and new values and the bound the solver's tolerances give; the final lane re-records it once with both values in its output; conservation stays exact.
- **The JDK sweep** (`tools/jdk-determinism/`) when a change touches elementary functions or the facade.

## Traps

- A green `test` says nothing about chain-100: it lives in `fluid/benchmark/**`, which `test` excludes.
- The junction lines' `ms=` fields are wall time: masked in comparisons.
- `lane.sh`, `fuzz.sh`, `quick-lane.sh` and the javac lane's scripts outside `tools/merge-lane/` may name worktree paths of the batch that wrote them; the merge lane reads its tools from the worktree it runs in.
- The javac lane does not run `science/column`, and `PackagedSparseLuTest` fails there.
- `tools/cloud-science-harness/` (a Gradle-free fallback) predates `science/thermo/**`; the Gradle gates are the gates of record.

**Snapshot:** `d6f721b94`, 2026-11-15.
