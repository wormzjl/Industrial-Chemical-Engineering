# dry-ice-cost

Purpose: measure the cold hold-fuzz worlds job by job and stage by stage, without Gradle, try a step-level flash without the crystal competition, and trace the step controller attempt by attempt.

Batch: `documentation/2026-11-02-dry-ice-cost/` (reviews `DRY_ICE_COST_REVIEW.md` and `STEP_CONTROL_REVIEW.md`).

## How to run

Copy this folder to `tmp/<name>/` of a worktree that has a `build/` folder, make `out/` there, and set `W` and `RUN` at the top of `env.sh`. Then, in Git Bash:

```text
source env.sh
build
probe 673 base-673
probe 651 raised-651 -DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000
perl summaryB.pl out/base-673-jobs.tsv out/raised-651-jobs.tsv
```

`build` compiles `science/**` and `runtime/**` from source, behind junctions it removes again, and takes the rest from the stale `build/classes`. Compiled output goes to `CLASSES`, so a patched build can sit beside the first. The patches apply to `src/` at c9e5ed099 (0.18.0): apply one, build with `CLASSES=$RUN/classes-proto`, then restore `src/` with `git checkout`. A raised budget gives each run its deterministic path; compare CPU time and checkpoints unless the machine is otherwise empty.

## Entry points

| File | What it does |
|---|---|
| `src/.../runtime/fluid/DryIceProbe.java` | The harness's tick loop for one seed: per-job wall, CPU, checkpoints and counters to `<name>-jobs.tsv`, node states to `<name>-states.txt` |
| `src/.../runtime/fluid/PhFlashCost.java` | One PH or TP flash of a seed's LNG with the competition on and off: time, kernel evaluations, the adopted state |
| `src/.../science/fluid/thermo/ShadowTally.java` | With `shadow.patch`: a fluid-only flash beside every competing call, the run's path unchanged; `tallyA.pl` tallies them per site |
| `src/.../science/fluid/thermo/TraceLog.java` | The last flashes before a non-finite Jacobian |
| `src/.../science/fluid/thermo/EngineAccess.java` | Package access to the engine for the probes |
| `stub/` | A stub `IChemE` for the Gradle-free run |
| `proto-b.patch` | Form (b): fluid first, the competition where the fluid is refused |
| `proto-bplus.patch` | Form (b+): (b) plus a fold of a crystal-rich `LIQUID_LIQUID` split into the liquid slot |
| `runB3.sh`, `gates.sh` | The pairs of the review; the three crystal gates on a patch, by Gradle |
| `jfrstages.pl` | JFR samples per solver stage |
| `step/hook.pl` | Writes instrumented copies of the solver classes to an overlay: read-only trace hooks, plus the closure forms behind switches (`step.late`, `step.tolerance=<s>`, `step.lateBound`). Every substitution must match exactly once or it dies, so it fails loudly on moved code |
| `step/extra/.../StepTrace.java` | The trace: one line per attempt, solve, Newton, pass, phase check and flash, for the jobs named in `step.jobs` |
| `step/env.sh` | `stepbuild <classes> [patched FluidThermodynamics.java]` and `sprobe <seed> <name> <jobs> <stop after job> [java options]`, on top of the scratch `env.sh` |
| `step/runS1.sh` to `step/runS5.sh` | The runs of the review, serial, at the raised budget, no JFR. `runS4.sh` runs forms F and G on (b+), `runS5.sh` runs form F on today's thermo |
| `step/runner/RunClosure.java` | One test class without Gradle, for a gate on a switched form |
| `step/attempts.pl`, `tally.pl`, `split.pl` | One job of a trace: attempts by outcome, phase check and repeat tallies, kernel by outcome and by site |
| `step/top.pl`, `col.pl`, `sum4.pl`, `fin.pl` | Job tables and states: worst jobs, run counters, the forms' table, final nodes side by side |
| `carried/runW.sh`, `sum.pl` | The nine worlds serial at the raised budget, one classes folder; their table. `SEEDS` narrows the list |
| `carried/runT.sh`, `tally.pl`, `track.pl`, `src/.../CarriedTally.java` | The refusals per site: `CarriedTally.record` goes at the engine's three entry points of an overlay |
| `carried/src/.../CarriedProbe.java`, `CarriedWindow.java`, `CarriedWindow2.java`, `CarriedGrid.java`, `CarriedWet.java` | Mode (a) on the plan's probe states, the three-fluid window, 244's refused states, the spine grid, the ethane-rich window |
| `carried/src/.../CarriedRefresh.java`, `CarriedRoots.java` | The refresh's crystal answers and a grid of single-phase states in full digits, to compare two builds |
| `carried/src/.../CarriedBubble.java` | Bubble and dew pressure of a stream at its temperature |
| `carried/first.pl` | Per run: the first job over 2 s by wall and CPU, the first held job, the last committed tick |
| `carried/series.pl`, `closures.pl` | With `-Dprobe.describeMs=0`: a world's tanks over time, each pipe's first closure |
| `carried/hook-a.pl` | `step/hook.pl` for the engine with a `PhaseCompetition` argument |
| `carried/gates.sh` | The named affected tests, by Gradle; `TESTS` narrows the list |
| `carried/a0-plan-form.patch`, `a1-prototype.patch` | The plan's form as written, and the rejected third attempt from the vapour's trial, both against 59b69d873 |
| `carried/hook-t.pl`, `traceT.sh` | `hook-a.pl` for the code after form G, without its prototype forms, plus each balance's pressures, flows and tables and the switch `step.noLiquidTables`; one traced job of a seed |
| `carried/t1-form.patch` | The liquid tables alone, rejected: 651 holds 7 times. Against 2ab4690e9 |

## History

- 2026-11-02: written for the dry ice analysis. Results and limits: the review. `runB3.sh` and `gates.sh` name the worktree they ran in. The output is in the main checkout's `tools/dry-ice-cost/logs/dry-ice-cost-logs-2026-11-02.tar.gz`, local only, without the JFR text dumps.
- 2026-11-03: `step/` added for `STEP_CONTROL_REVIEW.md`. To run it, copy `step/` to `<scratch>/step/` beside the scratch `env.sh`, build with `stepbuild "$RUN/classes-forms" <FluidThermodynamics.java with proto-bplus.patch applied>` for (b+), or with no second argument for today's thermo. `hook.pl` and the scripts name the worktree and the scratch folder they ran in. The output is in the main checkout's `tools/dry-ice-cost/logs/step-control-logs-2026-11-03.tar.gz`, local only.
- 2026-11-03: `carried/` added for `CARRIED_CRYSTALS_REVIEW.md`. Copy its scripts to `<scratch>/carried/` and its sources to `<scratch>/src/`, and set the path at the top of `runW.sh`, `runT.sh` and `gates.sh`. `src/.../PhFlashCost.java` and `EngineAccess.java` use the boolean `.ph` and `.tp`; `PhFlashCost` also uses the six-argument `equilibriumPH`, removed on the branch, so move it out of a build on that code (`fluidPH` is the fluid-only call). Logs: `carried-crystals-logs-2026-11-03.tar.gz`.
- 2026-11-03: `carried/hook-t.pl`, `traceT.sh` and `t1-form.patch` added for `JUNCTION_TABLES_REVIEW.md`. In a `BALANCE` trace line `~~` separates the nodes and pipes. Logs: `junction-tables-logs-2026-11-03.tar.gz`.
