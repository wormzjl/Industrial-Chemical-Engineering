# Saved-plant and fixed-circuit measurements

The junction rollback batch used these observers to compare CPU, allocation and committed checkpoints. The production jar and normal merge lane never load them. [Rollback measurements](../../documentation/2026-11-18-junction-lag/JUNCTION_ROLLBACK_REVIEW.md)

## Running the probes

From the worktree root, use the project's JDK 21. Run timed workloads alone. Use the same JVM flags, material data and copy of the input save for each side of a comparison.

```powershell
.\gradlew.bat --offline -I tools/junction-cost/bench.init.gradle junctionCheckpointDigest
.\gradlew.bat --offline -I tools/junction-cost/bench.init.gradle junctionCircuitCost
.\gradlew.bat --offline -I tools/junction-cost/bench.init.gradle junctionColumnCost
.\gradlew.bat --offline -I tools/junction-cost/bench.init.gradle runReviewWorldServer -PjunctionWorld=tmp/plant-copy -PreviewBatch=review-column-soak-save
```

Place a **disposable copy** under `tmp/plant-copy/world/` before running the last command. The observer forces the plant chunks and the server can save its progress. Use a fresh copy for each trial.

- `CircuitLedgerDigest.java` emits the accepted/rejected substep counts and a SHA-256 digest of the serialized checkpoint packs for 64 liquid and mixed-gas intervals.
- `CircuitBench.java` uses the same circuit fixtures. Each JVM warms up six complete sets and samples twelve. The CSV contains calling-thread CPU and allocated bytes.
- `ColumnRepeat.java` uses the literature column from `tools/codebase-review-bench/ColumnBench.java`. It raises the inlet temperature by 2 K and measures four warm solves after two warm-ups. Its checksum covers the returned phase amounts, tray temperatures and wet-tray state.
- `PerfSample.java` samples the detached column-soak observer every 20 ticks. In `resources.csv`, subtract the cumulative CPU and allocated bytes at tick 1,000 from those at tick 6,000. Divide allocated bytes by elapsed monotonic seconds and 1,048,576 for MiB/s. Resident memory needs a separate OS working-set sampler. Recorded historical trials used fresh standalone server JVMs, G1 and a 512 MiB to 4 GiB heap.

## History

- 2026-11-19 — Preserved the fixed-input probes and saved-plant sampler from the junction investigation for 0.35.3 merge preparation. The opt-in build compiles against the production and test classes of the selected checkout.
