# Fouling and corrosion: second final lane

Batch `2026-11-16-fouling-corrosion`, lane at `86020d062` (batch tip, D12 to D16 merged), 2026-11-16. Baseline: the first
lane at `96a6fa733` (`FINAL_LANE_REVIEW.md`, folder `fouling-corrosion-wp6-materials-tab-439886/tmp/merge-lane/head/`).
Output: `fouling-corrosion-wp4-repair-gui-c16d2b/tmp/merge-lane/head/` (git-ignored).

## Summary

Verdict: clean for the batch's code. No unexplained result. Two failures in the lane: `LanguageFilesTest`, which fails
until the translation commit, and one wall-deadline hold in `HoldClassWorldsTest`. That hold passed when replayed alone
once. Every moved reference follows D13 to D15. The lane tool has two defects (findings 2 and 3); they are not defects in
the batch's code.

What was narrowed and why (owner: "only rerun the affected parts"):
- The lane ran `lane.sh head test regression gametest`: no campaign, no `--all`, no edit of `lane.sh`.
- The campaign was not run. Every solver change since `96a6fa733` sits behind a heat law (`pipe.heat()` in
  `PassiveStepSolver.plan` and `couple`, `HeatCurve`, `ExchangerRelay`, `AirCooled`, `HeatSide`) or in the integrity
  rates. The hold-fuzz harness (`tools/phase-ports-probes/hold-fuzz/src/HoldFuzzTest.java`) builds only tanks,
  generators, voids, pipes, pumps, compressors, valves and filters, so it builds no heat side. It drives
  `PhysicalRegistry` and `IslandCoordinator` (`CommitHook.NO_MATERIAL`), not `FluidWorldAuthority`, which runs the
  integrity step. Fouling levels apply only to `HEAT_SIDE` reservoirs. So the first lane's campaign at `96a6fa733` stands.
- The default selection (`affected.mjs select`) equals `affected.mjs select --since 96a6fa733` except for one class,
  `NativeColumnSolverTest`. The reason: the first lane's load sets already narrow each class to the changes since
  `96a6fa733`. The default selection picks that class because its source reads `src/main/rust/LAYOUT.md`, last changed
  before `96a6fa733`. Its one rerun was accepted, but it did not run (finding 2).
- `lane.sh` line 27 (`rm -f "$SEL"`) removes `selection.txt` at start, so a pre-written selection is never reused. The
  first two attempts were stopped before any test ran: one after the compile, during selection (folder
  `tmp/merge-lane/stopped/`), and one during selection.

Selection (`selection.txt`):
- `TASK test 288/519`, `TASK holdGates ALL 10/10`, `TASK columnGates 1/89` (`NativeColumnSolverTest`),
  `TASK equipmentGates 19/31`, `TASK fluidSolverRegression ALL 1/1`.
- `STEP regression due` (loads `HeatCurve.java`: changed).
- `STEP gametest due` (`IntegrityGameTests.java` changed).
- `NOTE build.gradle changed: no test selected for it`.
- `NOTE since main (adb413b4d3): 210 files changed; 2 test classes have no load set`.

Steps and counts:
- `test` step: 552 classes, 3,532 tests, 2 failures, 0 errors, 0 skipped.
  - The `test` task ran whole, 519 classes, not 288: the lane prints "filters too long, the task runs whole" past 8,000
    characters of filters (finding 3).
  - `holdGates` ran 10 classes and `equipmentGates` 19. `columnGates` failed with "No tests found" (finding 2).
  - The lane saved 546 load sets at `86020d062` (2 classes did not pass).
- Regression: chain-100 against its reference, deviation 0 on every measure (state/moles, temperature, phase fraction,
  flow).
- GameTests: 119 of 119 passed, including `sixtyfifthterminalwaitsforthenexttickwithoutazerobudgetdrain`, so no replay.
- Junction lines: 33 of 33 identical, floats included. `MIXED_GAS_COST` newtonSolves 313 → 313.
- `LanguageFilesTest`: expected until the translation commit. `UnitTextTest` ran (equipmentGates) and passed.
- Replay: `HoldClassWorldsTest.aResidueIsBookedWithoutAClosure` alone once, `holdGates --tests … --rerun`: 1 of 1
  passed (`tmp/merge-lane/replay/`). Nothing else ran a second time.
- Campaign: not run. Class table: the first lane's stands, unchanged: refusals 0, cold random 992 of 1000, bundled
  random 989 of 1000, bundled defaults 1000 of 1000, only class F (budget) holds.

## Findings

| # | Kind | Test / tool | What | Trace | Proposal |
|---|---|---|---|---|---|
| 1 | Wall-clock hold, cleared | `HoldClassWorldsTest.aResidueIsBookedWithoutAClosure` (holdGates) | `HELD: wall deadline` at tick 8992, island 15, committed 8892. It ran while the whole `test` task ran beside it. Alone: passed. | `tmp/merge-lane/head/results/TEST-…HoldClassWorldsTest.xml`, `gates.gradle.txt` line 25, island dump in `printed.txt` (`EVENT_PATH_HELD`, `EVENT_PATH_ISLAND 15`); replay in `tmp/merge-lane/replay/` | None: it does not count (AGENTS.md, a wall-clock hold replayed alone first). |
| 2 | Lane tool | `affected.mjs` and `columnGates` | `build.gradle` (lines 682–686) excludes `NativeColumnSolverTest` from `columnGates` and runs it in `nativeSmoke`, but `select` files it under `columnGates`. The task fails "No tests found for given includes", so the class does not run. | `gates.gradle.txt` lines 47–49 | `affected.mjs` should read `nativeTestClasses` and select `nativeSmoke` (owner's call; tools only). |
| 3 | Lane tool | `lane.sh` line 58 | Past 8,000 characters of `--tests` filters the `test` task runs whole: 519 classes against the 288 selected. That reran 231 classes nothing changed for. | `lane-console.txt` ("filters too long, the task runs whole") | Pass the filter in a file or by package (owner's call; tools only). |
| 4 | Lane tool | `lane.sh` line 27 | `rm -f "$SEL"` makes a selection written beforehand impossible, for example `--since <first lane>`. | — | Keep a non-empty `selection.txt`, or accept `--since` (owner's call). |
| 5 | Moved counter, not named in the history | `UpLookingLuParityTest` (P-17) | Heat side, five solves: reach searches 0 → 5. Step matrices in the gate 13 → 11. Bytes per factorization 598 → 595. Parity with EJML still bit for bit (the test passed). | `printed.txt` | Accept: the gate takes its matrices from a heat-side step, and D13/D15 changed that step's films and pivots. No solver code in `linalg` changed. |

## Moved references (first lane → this lane)

All of these pass in this lane. Each was re-recorded or follows from the README history line named.

| Reference | Old (`96a6fa733`) | New (`86020d062`) | Explained by |
|---|---|---|---|
| Crude sample, condenser surface need | 765.9 m² | 423.9 m² | D14 and D15 (README, "the conductive film (D15) reported", condenser 423.9) |
| Crude sample, pumparound cooler needs (1/2/3) | 289.9 / 347.8 / 269.8 m² | 449.8 / 398.9 / 242.2 m² | D15 line (450 / 399 / 242) |
| Pumparound cooler sizes (1/2/3) | 1.00×5 / 1.25×4 / 1.00×5 | 1.25×5 / 1.25×5 / 1.00×4 | D15 line (sizes follow the needs) |
| E-101 dirty need; size | 1344 m²; 1.5 m shell, 10 tubes (1688 m²) | 1235 m²; 1.5 m shell, 9 tubes (1519 m²) | D13, D14, D15 lines (dirty 1234.8) |
| E-102 dirty need; size | 953.9 m²; 5.5×9 m, bank 2×3 (1146 m²) | 1313 m²; 2.0×9 m, bank 6×4 (1581 m²) | D15 line (dirty 1313.4) |
| Kettle need | 344.2 m² | 350.0 m² | D15 (liquid film by k, cp, μ) |
| P-101 → V-101 path lost / spare | 2.97 / 8.23 bar | 2.74 / 8.46 bar | D13 to D15 (resized units) |
| Residue path lost / spare | 0.41 / 0.58 bar | 0.42 / 0.56 bar | same |
| Sample run S-6 / S-7 / H-101 / vapour | 11.981 bar, 347.04 C, 67.528 MW, 0.4124 | 12.207 bar, 346.79 C, 67.408 MW, 0.4053 | same |
| SAMPLE_BODY packed bytes | 23186 | 23189 | sizes and readings above (3 bytes; not traced further) |
| `ColumnSolveTest` U2 air cooler | need 1633 m², 5×3 bays of 3.00×11 m | need 1630 m², 5×2 bays of 5.00×10 m | D15 (air cooler liquid per face) |
| `ColumnSolveTest` U2 air then water | need 2035 m², 4×4 bays of 6.00×7 m | need 2016 m², 5×2 bays of 5.00×12 m | D15 |
| `HeatSideTopologyTest` air cooler | fans 0.792 / 0.234; full fans 335.82 K; louvers 0.626 | 0.670 / 0.202; 333.56 K; 0.613 | D15 (air cooler liquid per face 2149.6 → 2435.7) |
| `ExchangerCouplingTest` feed/effluent | hot out 337.175 K, cold out 319.234 K | 321.579 K, 334.834 K | D15 (water–water U 1000 → 2500) |
| `ExchangerCouplingTest` 80 °C out of reach | hot out 340.087 K, cold out 325.934 K | 327.549 K, 343.777 K | D15 |
| `ExchangerCouplingTest` bypass | 0.521 → 0.657; real bypass valve 8.704 → 10.807 kg/s | 0.637 → 0.705; 10.280 → 11.591 kg/s | D15 (bypass line 0.1 → 0.15 m) |
| `ExchangerCouplingTest` kettle | 2785.8 kW, boil-up 1.2333 kg/s | 3809.4 kW, 1.6864 kg/s | D15 |
| `ExchangerCouplingTest` coupling wake gate | cold side committed 3001 / 3001 | 1901 / 1901 | D15: the faster coupling reaches steady sooner; the gate asserts only equality |
| `PassiveStepSolverTest` S1 heat side bytes per step | 141,248 | 146,035 | D13/D15: the curve keeps its liquid resistance per knot; allocation only, 0 LU per step both |
| `MixedGasJunctionTransientTest` bytes | e.g. 49,242,800 | 49,176,696 | allocation only; every outcome, newtonSolves and `MIXED_GAS_COST` identical |

Lines that differ only in time, map order (`ColumnFeedOnePhaseOracleTest`) or log-file locks ("Unable to delete file")
are not listed. The comparison is `tmp/merge-lane/compare/printed-diff.txt` (timestamps, times and map order
normalised; script `tmp/merge-lane/compare/norm.mjs`).
