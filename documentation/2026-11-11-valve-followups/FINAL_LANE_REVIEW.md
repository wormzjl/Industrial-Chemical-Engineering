# Final lane review: valve follow-ups (0.27.1)

The lane ran once, `bash tools/merge-lane/lane.sh head`, on `0f38afa3b` (the final code: items 1 to 5, merge preparation). No other test JVM was running. Times are not recorded. Logs: main checkout, `tools/merge-lane/logs/valve-followups-final-lane-logs-2026-11-11.tar.gz` (local).

Baselines:
- For the tests, chain-100, the junction lines and the printed lines: the 0.27.0 lane (`gas-blanketing-final-lane-logs-2026-11-11.tar.gz`).
- For the campaign: the factor-4 campaign of the time factor batch (`tools/minecraft-time-delta/logs/time-scale-campaign4-logs-2026-11-11.tar.gz`, TIME_SCALE_BUILD_REVIEW.md section 3).
- Compared with `node tools/merge-lane/compare.mjs <0.27.0 lane> tmp/merge-lane/head <campaign4>`.

## Selection and tests
- `test`: 223 of 384 classes were selected. The filter list was too long, so `test` ran whole.
- `holdGates`: 12 of 12. `equipmentGates`: 17 of 28. `columnGates`: none (nothing they load changed).
- 2680 tests, 0 failures, 0 errors. One class had no load set: `BlanketDrainWorldTest` (new).
- Load sets saved: 413 for the tests and 1 for the regression, at `0f38afa3b`.

## References
- chain-100: exact. Every deviation is 0 (state, temperature, phase fraction, flow).
- Junction lines: 33 of 33 identical, floats included. `MIXED_GAS_COST`: newtonSolves 313, as in the base.
- Printed lines (per class, timestamps and timings removed): 151 classes identical.
- Classes that differ:
  - Items 2 to 4, expected: the new `ReliefValveWorldTest` lines (`RELIEF_SINK`, `RELIEF_FLARE_X5`) and `GasBlanketTest`'s fast fill. The vent case moved in the last digit (157492.91206937179 to ...173 Pa); item 2 changed the vent law.
  - `SimulatorRunTest`: packed sample body 22587 to 22586 bytes, results unchanged.
  - `V3InspectionCodecTest`: the base's 470 lines of a printed stack trace are not printed now.
  - The rest: log-file delete warnings, worktree paths.

## Fluid GameTests
- All 94 required tests pass, `level-loop-blanket-direct` among them. They were due because `LevelLoopGameTests.java` changed.

## Campaign (factor 4, 1000 seeds, 8 workers)

| Distribution | Clean (campaign4, head) | Newton solves | Newton iterations | Holds |
|---|---|---|---|---|
| cold-random | 991, 991 | 325 049, 325 046 | 2 002 081, 2 002 115 | none new, none fixed, none moved |
| bundled-random | 990, 990 | 154 140, 154 125 | 860 192, 859 982 | none new, none fixed; 431 moved E6 to F |
| bundled-defaults | 1000, 1000 | 26 542, 26 542 | 62 242, 62 242 | none |

- Refusals: 0. `compare.mjs` warns that campaign4's factor is "not recorded": it predates `factor=`. It was run at 4 (`factor.sh campaign4 ... 4`).
- Seed 431 newly fell into a budget class (F), so it was replayed once, alone: `THREADS=1 bash tools/minecraft-time-delta/factor.sh lane-replay431 431 bundled 4`. It held in E6, "Newton iteration limit", as in campaign4. No new hold class.

## Verdict
- Clean: no test failure, chain-100 exact, the GameTests pass, no new hold class. Code guide: fluid-science, fluid-runtime and verification at `ea8c63ef6`. No translation commit (`en_us.json` unchanged).
- Not run: `base` (archived baselines serve), a second lane, an in-game check (item 7, the owner's).
