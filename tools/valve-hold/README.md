# valve-hold

Batch: [`2026-11-16-review-plant-sizing`](../../documentation/2026-11-16-review-plant-sizing/README.md),
`VALVE_HOLD_REVIEW.md`. A scratch test, no mod code: a flow valve's change of target that holds its island.

## How to run

`SeriesHoldScratchTest.java` uses the test world (`EventPathWorld`), so it runs as a test. Copy it into
`src/test/java/com/wormzjl/icheme/runtime/fluid/`, run it once, read its lines, and remove the copy; it is never
committed there:

```
./gradlew test --tests "com.wormzjl.icheme.runtime.fluid.SeriesHoldScratchTest" --max-workers=4
```

Its lines start with `SCRATCH` in `build/test-results/test/TEST-com.wormzjl.icheme.runtime.fluid.SeriesHoldScratchTest.xml`.
It asserts nothing.

## What it does

`variants()` lists the cases. `run(...)` builds the line in the world (a supply, a centrifugal pump or none, a
player's flow valve or none, a parallel pair or none, a column-sensor flow valve, a void), edits the inlet's target
through each value and prints the island's status and each valve's flow and mode. `both(...)` runs the island to the
first target and solves the second on its graph, from the committed interval and by a fresh solver, at several
interval lengths.

## The trace (mechanism M2), 2026-11-17

`trace.patch` is temporary instrumentation, never committed under `src/`. While `SparseNewton.TRACE_DIR` is set,
`SparseNewton` writes one dump per Newton solve: row and column names, and per iteration the point, the residual, a
fresh Jacobian, the direction and each line-search trial. `StepEquations` names its rows and columns. `BalanceStart`
appends its rounds and iterations to `balance.txt`. `ValveHoldTraceScratchTest.java` sets the folder per case
(`tmp/valve-hold-trace/<case>/`) and solves the second target once: A2 fresh and carried, no pair, a charge of 30,
and case L. To run:

```
git apply tools/valve-hold/trace.patch
cp tools/valve-hold/ValveHoldTraceScratchTest.java src/test/java/com/wormzjl/icheme/runtime/fluid/
./gradlew test --tests "com.wormzjl.icheme.runtime.fluid.ValveHoldTraceScratchTest" --max-workers=4
python -P tools/valve-hold/analyse_trace.py tmp/valve-hold-trace/a2-fresh/newton-006.txt 10
git apply -R tools/valve-hold/trace.patch
rm src/test/java/com/wormzjl/icheme/runtime/fluid/ValveHoldTraceScratchTest.java
```

`analyse_trace.py` needs numpy. It prints per iteration the norm, the residual by row group, the largest rows, the
point, the Jacobian's singular values and near-null vectors, the direction and the trials. Results are in
`VALVE_HOLD_TRACE.md`. The patch is against `df742782f` and applies unchanged to `2919f7b83` (C3 and B are in
`PassiveStepSolver` and `DeviceModes`, which it does not touch).

## Results, 2026-11-17

`VALVE_HOLD_REVIEW.md`, section 3. The file holds the last cases run (A and A2 by `both`); the earlier cases are in
the review's tables.

## The start hold, 2026-11-17

`StartHoldScratchTest.java` builds the review world's crude charge line in small (Tia Juana crude at 300 K, P-101's law,
a run sized to pass 2 x design on 7.5 bar, the column-sensor flow valve, a void), dead-heads the pump behind the shut valve
for 800 ticks, gives the valve its target and prints the world's slices and direct carried and fresh solves for cases a to f.
`StartHoldTraceScratchTest.java` needs `trace.patch` and `StartHoldScratchTest.java` beside it, and dumps cases a carried, a
fresh, b carried and e carried under `tmp/start-hold/trace/`. Run each as the scratch above (copy into
`src/test/java/com/wormzjl/icheme/runtime/fluid/`, one run with `--tests` and `--max-workers=4`, remove the copies). The
patch applies unchanged to `c0a5eff0e`. Results in `START_HOLD_REVIEW.md`.
