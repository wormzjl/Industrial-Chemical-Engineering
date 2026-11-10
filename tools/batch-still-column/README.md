# Batch still column comparison (B3)

Batch: `documentation/2026-11-09-batch-distiller/` (plan section 7, owner decision B3).

Purpose: the measurement that chose the still's column model. `BubblePointColumn` is the candidate in both forms
(energy-balance bubble point, constant molar overflow); `BatchColumnComparisonTest` solves it against V3 on the same
problem (V3's total condenser, N trays and reboiler, the pot liquid fed into the reboiler at 20 times the boil-up), over
stages 2, 4, 8, reflux 0.5, 2, 8 and four distilled fractions. The owner adopted the energy form; the game's
`science/fluid/network/StillColumn` is its port, so neither file is reached by the product or a gate.

## How to run

Re-attach the two files to the test tree, then run the one class with the switch set (from the worktree root):

```bash
mkdir -p src/test/java/com/wormzjl/icheme/science/column/batch
cp tools/batch-still-column/src/*.java src/test/java/com/wormzjl/icheme/science/column/batch/
ICHEME_BATCH_COLUMN=1 ./gradlew test --tests 'com.wormzjl.icheme.science.column.batch.*' --max-workers=4
```

The report goes to `build/batch-column/` (`report.txt`, `snapshots.csv`). The stored run is in the main checkout,
`research/2026-11-09-batch-distiller/b3-column/`. Remove the two files from `src/test` again afterwards.

## Entry points

- `src/BubblePointColumn.java`: `solve(Spec, guess, maxIterations, tolerance)`, `latentHeat(z, P)`.
- `src/BatchColumnComparisonTest.java`: `aNaphthaPotRectifies` (a sanity case in both forms) and the comparison, enabled
  only with `ICHEME_BATCH_COLUMN=1`. Package `icheme:tjl20_methane_nitrogen` (V3 refuses `icheme:refinery`, which admits
  CO₂ crystals).

## History

- 2026-11-09: written for B3 (commit 46fbd0387) and run: the energy form converges on every pot step, about 13 times
  faster than V3 cold and 5 times warm, within 0.004 mole fraction and 0.01 K; the constant-molar-overflow form is off by
  up to 0.13 on crude.
- 2026-11-10: detached from `src/test/java/com/wormzjl/icheme/science/column/batch/` at the merge's tooling cleanup.
  The files are stored unchanged: `git show 52415da92:src/test/java/com/wormzjl/icheme/science/column/batch/<file>`
  equals `src/<file>`; no patch is needed.
