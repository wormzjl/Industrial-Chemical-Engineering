# Fluid conversion: final lane

The lane ran once, `bash tools/merge-lane/lane.sh head`, on `9435b480a` (the final code: W1 to W6 and merge preparation items 1 to 6). The lane's own check counted one test or client JVM at its start (20:22, not identified; no run of this session was active). Times are not recorded. Logs: main checkout, `tools/merge-lane/logs/fluid-conversion-final-lane-logs-2026-11-12.tar.gz` (local, not staged).

Baseline: the 0.27.1 lane (`valve-followups-final-lane-logs-2026-11-11.tar.gz`), its campaign at factor 4. Compared with `node tools/merge-lane/compare.mjs` and, for the printed lines, line by line without timings.

## Tests (affected classes, `affected.mjs`; 126 files changed since `main`)

| Task | Classes run | Tests | Failures |
|---|---|---|---|
| `test` | 310 of 387 (the filter list too long: the task ran whole) | | 1 |
| `holdGates` | 12 of 12 | | 0 |
| `columnGates` | 61 of 82 | | 0 |
| `equipmentGates` | 22 of 28 | | 0 |
| All | | 3,096 | 1 |

The failure (finding 1): `ConstructorColumnTest.eachPageShowsItsKeysWithTheCurrentPositionsLatched` expects the Vessels page's keys without `kind:fluid_converter` and `kind:gas_converter`.
- Diagnosis: the model commit `4d72a0cba` put the two kinds on the constructor's Vessels page, as plan D10 asks ("constructor forms under Vessels"). That commit ran `equipmentGates` on five classes and `FlowsheetPaletteCoverageTest` and `SizingCoverageTest`, but not this `test` class. The test's expected list is stale; the game's behaviour is the intended one.
- Proposed fix: add the two keys to the expected list, then run `ConstructorColumnTest` alone. No production change. Held for the owner's or the main thread's word: no second lane.

## References against the baseline

| Reference | Result |
|---|---|
| chain-100 exact | 0 deviation in moles, temperature, phase fraction and flow |
| 33 junction lines | 33 of 33 identical, floats included; counters identical on 33 of 33 |
| `MIXED_GAS_COST` | newtonSolves 313, as in the base |
| Printed lines | identical apart from timings, bytes and a lock warning on `logs/debug.log`; `FluidFactsCompatTest` sees one more vessel and island (the schema-24 converter in its world, W2) and prints its corpus lines; classes the base lane did not select print their lines for the first time |

## Fluid GameTests

All 96 required tests pass, `ConverterGameTests` among them. They were due because a GameTest source changed.

## Campaign (factor 4, 1000 seeds, 8 workers, alone)

| Distribution | Clean (base, head) | Newton solves (base, head) | Newton iterations (base, head) | Holds |
|---|---|---|---|---|
| cold-random | 991, 991 | 325,046, 324,988 | 2,002,115, 2,001,901 | none new, none fixed, none moved |
| bundled-random | 990, 990 | 154,125, 154,003 | 859,982, 858,458 | none new, none fixed, none moved |
| bundled-defaults | 1000, 1000 | 26,542, 26,542 | 62,242, 62,242 | none |

- Refusals 0; no new budget-class (C, F) hold.
- Every seed's line (end tick, holds, classes, island statuses) is identical to the base in both random distributions. One hold detail differs: seed 431's second HELD line reads "wall deadline" where the base read "Newton iteration limit", at the same tick and in the same class (e). It is a wall-clock budget hold of a seed that already held, not a new hold, so it is not replayed (AGENTS.md).
- The Newton totals differ by 0.02 % (cold) and 0.08 % (bundled) while every seed's result is the same. They count work that the wall-clock budgets cut, for example seed 431's last attempt. The base lane against its own baseline, `campaign4`, moved by similar amounts (that batch also changed solver code, so this is no proof of noise alone).

## Verdict

The references are identical to the 0.27.1 lane, and the campaign's results are the same. The one failure is a stale test expectation (finding 1), held for a decision before it is fixed.
