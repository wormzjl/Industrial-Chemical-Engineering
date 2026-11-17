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

## Results, 2026-11-17

`VALVE_HOLD_REVIEW.md`, section 3. The file holds the last cases run (A and A2 by `both`); the earlier cases are in
the review's tables.
