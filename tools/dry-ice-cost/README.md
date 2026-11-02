# dry-ice-cost

Purpose: measure the cold hold-fuzz worlds job by job and stage by stage, without Gradle, and try a step-level flash without the crystal competition.

Batch: `documentation/2026-11-02-dry-ice-cost/` (review `DRY_ICE_COST_REVIEW.md`).

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

## History

- 2026-11-02: written for the dry ice analysis. Results and limits: the review. `runB3.sh` and `gates.sh` name the worktree they ran in. The output is in the main checkout's `tools/dry-ice-cost/logs/dry-ice-cost-logs-2026-11-02.tar.gz`, local only, without the JFR text dumps.
